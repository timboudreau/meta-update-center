package com.timboudreau.metaupdatecenter;

import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.bunyan.java.v2.Log;
import com.mastfrog.bunyan.java.v2.Logs;
import com.mastfrog.netty.http.client.HttpClient;
import com.mastfrog.shutdown.hooks.ShutdownHookRegistry;
import com.mastfrog.util.preconditions.ConfigurationError;
import com.mastfrog.util.preconditions.Exceptions;
import com.timboudreau.metaupdatecenter.NbmDownloader.DownloadHandler;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.GUICE_BINDING_POLLER_THREAD_POOL;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_POLL_INITIAL_DELAY_MINUTES;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_POLL_INTERVAL_MINUTES;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Named;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import org.xml.sax.SAXException;

/**
 *
 * @author Tim Boudreau
 */
public class Poller implements Runnable {

    private final ModuleSet set;
    private final NbmDownloader downloader;
    private final Logs pollLogger;
    private final PollerProbe probe;
    private ScheduledFuture<?> future;
    private volatile boolean polling;
    private long lastTickle;
    private long initialDelay;
    private long interval;
    private final ScheduledExecutorService pollThreadPool;
    private int pollLoops;

    @Inject
    Poller(ModuleSet set, @Named(SETTINGS_KEY_POLL_INTERVAL_MINUTES) long interval,
            @Named(SETTINGS_KEY_POLL_INITIAL_DELAY_MINUTES) long initialDelay,
            HttpClient client, ShutdownHookRegistry registry, NbmDownloader downloader,
            @Named(GUICE_BINDING_POLLER_THREAD_POOL) ScheduledExecutorService pollThreadPool,
            @Named(UpdateCenterServer.DOWNLOAD_LOGGER) Logs pollLogger,
            PollerProbe probe) {
        if (interval <= 0) {
            throw new ConfigurationError("Poll interval must be > 0 but is " + interval);
        }
        if (initialDelay < 0) {
            throw new ConfigurationError("Poll initial delay must be >= 0 but is " + initialDelay);
        }
        this.pollThreadPool = pollThreadPool;
        this.initialDelay = initialDelay;
        this.interval = interval;
        this.probe = probe;
        this.downloader = downloader;
        this.set = set;
        this.pollLogger = pollLogger;
        registry.add((Runnable) client::shutdown);
        future = pollThreadPool.scheduleWithFixedDelay(this, initialDelay, interval, TimeUnit.MINUTES);
        pollLogger.info("schedulePollTask").add("initialMinutes", initialDelay)
                .add("interval", interval)
                .add("moduleCount", set.size())
                .add("delaySeconds", future.getDelay(TimeUnit.SECONDS))
                .add("done", future.isDone())
                .add("cancelled", future.isDone())
                .close();
    }

    public synchronized boolean pollNow() {
        try (Log log = pollLogger.warn("scheduleImmediatePollRun")) {
            if (polling) {
                log.add("success", false).add("pollingState", "running");
                return false;
            }
            if (future.isCancelled()) {
                log.add("success", false).add("pendingState", "enqueued");
                return false;
            }
            long newTickle = System.currentTimeMillis();
            if (newTickle - lastTickle < Duration.ofMinutes(2).toMillis()) {
                log.add("success", false).add("pendingState", "quietPeriod");
                return false;
            }
            lastTickle = newTickle;
            log.add("done", future.isDone())
                    .add("cancelled", future.isDone());
            future.cancel(false);
            log.add("success", true).add("pendingState", "pending");
            pollThreadPool.schedule(this, 1, TimeUnit.MILLISECONDS);
            return true;
        }
    }

    @Override
    public void run() {
        polling = true;
        int loop = pollLoops++;
        try {
            String msg = "Poll NBMs at " + Headers.toISO2822Date(ZonedDateTime.now());
            Thread.currentThread().setName(msg);
            Set<ModuleItem> pending = ConcurrentHashMap.newKeySet(set.size());
            List<ModuleItem> shuffled = set.toList();
            // Mix up the download order, so any catastrophic failure doesn't take
            // everything with it
            Collections.shuffle(shuffled);
            Logs loopLogs = pollLogger.child("pollRun", loop);
            pollLogger.info("poll")
                    .add("modules", set.size())
                    .add("pollRun", loop)
                    .add("polling", shuffled.size())
                    .close();
            for (final ModuleItem item : shuffled) {
                try (Log log = loopLogs.trace("polling")) {
                    if (UpdateCenterServer.DUMMY_URL.equals(item.getFrom())) {
                        pollLogger.debug("skipInternalModule").add(item.getFrom())
                                .add("cnb", item.getCodeNameBase()).close();
                        continue;
                    }
                    pending.add(item);
                    probe.onAttemptDownload(item);
                    log.add("downloadAttempt", item.getFrom()).add("hash", item.getHash())
                            .add("cnb", item.getCodeNameBase()).close();
                    downloader.download(item.getDownloaded(), item.getFrom(), new DownloadHandler() {
                        ZonedDateTime lastModified;

                        @Override
                        public boolean onResponse(HttpResponseStatus status, HttpHeaders headers) {
                            if (status.code() > 399) {
                                loopLogs.warn("downloadFail")
                                        .add("url", item.getFrom()).add("status", status.code()).close();
                            }
                            probe.onDownloadStatus(item, status);
                            String lm = headers.get(HttpHeaderNames.LAST_MODIFIED);
                            if (lm != null) {
                                try {
                                    lastModified = Headers.LAST_MODIFIED.toValue(lm);
                                } catch (Exception ex) {
                                    loopLogs.error("invalid-last-modified").add("value", lm).add("cnb", item.getCodeNameBase())
                                            .add("url", item.getFrom());
                                    probe.onError(item, ex);
                                }
                            }
                            loopLogs.trace("downloadResponse").add("status", status.code())
                                    .add("pollRun", loop)
                                    .add("url", item.getFrom()).add("lastModified", lm).close();
                            boolean result = OK.equals(status);
                            if (!result) {
                                removePending(item);
                            }
                            return result;
                        }

                        private void removePending(ModuleItem item) {
                            if (pending.remove(item) && pending.isEmpty()) {
                                loopLogs.trace("pollCycleCompleted")
                                        .add("pollRun", loop)
                                        .add("cnb", item.getCodeNameBase())
                                        .close();
                                probe.onPollCycleCompleted(set);
                            }
                        }

                        @Override
                        public void onModuleDownload(InfoFile module, InputStream bytes, String hash, String url) {
                            try {
                                loopLogs.info("newVersionDownloaded")
                                        .add("cnb", module.getModuleCodeName())
                                        .add("url", url)
                                        .add("version", module.getModuleVersion().toString()).close();
                                probe.onNewVersionDownloaded(item, module, url);
                                set.add(module, bytes, url, hash, item.isUseOriginalURL(), lastModified);
                            } catch (IOException ex) {
                                loopLogs.error("downloadFail")
                                        .add("url", url)
                                        .add(ex).close();
                                Exceptions.printStackTrace(ex);
                                probe.onError(item, ex);
                                removePending(item);
                            } catch (XPathExpressionException ex) {
                                loopLogs.error("downloadFail")
                                        .add("url", url)
                                        .add(ex).close();
                                Exceptions.printStackTrace(ex);
                                probe.onError(item, ex);
                                removePending(item);
                            } finally {
                                removePending(item);
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            loopLogs.error("download")
                                    .add("pollRun", loop)
                                    .add("url", item.getFrom()).add(t).close();
                            probe.onError(item, t);
                            removePending(item);
                        }
                    });
                } catch (IOException | URISyntaxException | SAXException | ParserConfigurationException ex) {
                    pollLogger.error("download").add("url", item.getFrom()).add(ex).close();
                    probe.onError(item, ex);
                    Exceptions.printStackTrace(ex);
                }
            }
        } catch (Exception | Error e) {
            pollLogger.error("download").add(e).add("pollRun", loop);
        } finally {
            polling = false;
            synchronized (this) {
                if (future.isCancelled()) {
                    pollLogger.info("scheduleNewPollForCancelled")
                            .add("minutes", interval)
                            .add("pollRun", loop)
                            .close();
                    future = pollThreadPool.scheduleWithFixedDelay(this, initialDelay, interval, TimeUnit.MINUTES);
                }
            }
        }
    }

    @ImplementedBy(DefaultPollerProbe.class)
    public interface PollerProbe { // used by tests to monitor polling status

        void onAttemptDownload(ModuleItem item);

        void onDownloadStatus(ModuleItem item, HttpResponseStatus status);

        void onNewVersionDownloaded(ModuleItem item, InfoFile module, String url);

        void onError(ModuleItem item, Throwable thrown);

        void onPollCycleCompleted(ModuleSet set);
    }

    static final class DefaultPollerProbe implements PollerProbe {

        @Override
        public void onNewVersionDownloaded(ModuleItem item, InfoFile module, String url) {
            // do nothing
        }

        @Override
        public void onDownloadStatus(ModuleItem item, HttpResponseStatus status) {
            // do nothing
        }

        @Override
        public void onAttemptDownload(ModuleItem item) {
            // do nothing
        }

        @Override
        public void onError(ModuleItem item, Throwable thrown) {
            // do nothing
        }

        @Override
        public void onPollCycleCompleted(ModuleSet set) {
            // do nothing
        }
    }
}

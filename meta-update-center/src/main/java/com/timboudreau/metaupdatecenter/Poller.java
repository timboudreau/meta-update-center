package com.timboudreau.metaupdatecenter;

import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.bunyan.java.v2.Logs;
import com.mastfrog.giulius.ShutdownHookRegistry;
import com.mastfrog.netty.http.client.HttpClient;
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
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
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

    @Inject
    Poller(ModuleSet set, @Named(SETTINGS_KEY_POLL_INTERVAL_MINUTES) long interval,
            @Named(SETTINGS_KEY_POLL_INITIAL_DELAY_MINUTES) long initialDelay,
            HttpClient client, ShutdownHookRegistry registry, NbmDownloader downloader,
            @Named(GUICE_BINDING_POLLER_THREAD_POOL) ScheduledExecutorService pollThreadPool,
            @Named(UpdateCenterServer.SYSTEM_LOGGER) Logs pollLogger,
            PollerProbe probe) {
        if (interval <= 0) {
            throw new ConfigurationError("Poll interval must be > 0 but is " + interval);
        }
        if (initialDelay < 0) {
            throw new ConfigurationError("Poll initial delay must be >= 0 but is " + initialDelay);
        }
        this.probe = probe;
        this.downloader = downloader;
        this.set = set;
        this.pollLogger = pollLogger;
        registry.add((Runnable) client::shutdown);
        pollThreadPool.scheduleWithFixedDelay(this, initialDelay, interval, TimeUnit.MINUTES);
    }

    @Override
    public void run() {
        String msg = "Poll NBMs at " + Headers.toISO2822Date(ZonedDateTime.now());
        pollLogger.trace("poll").close();
        Thread.currentThread().setName(msg);
        Set<ModuleItem> pending = ConcurrentHashMap.newKeySet(set.size());
        for (final ModuleItem item : set) {
            try {
                if (UpdateCenterServer.DUMMY_URL.equals(item.getFrom())) {
                    continue;
                }
                pending.add(item);
                probe.onAttemptDownload(item);
                downloader.download(item.getDownloaded(), item.getFrom(), new DownloadHandler() {
                    ZonedDateTime lastModified;

                    @Override
                    public boolean onResponse(HttpResponseStatus status, HttpHeaders headers) {
                        if (status.code() > 399) {
                            pollLogger.warn("downloadFail").add("url", item.getFrom()).add("status", status.code()).close();
                        }
                        probe.onDownloadStatus(item, status);
                        String lm = headers.get(HttpHeaderNames.LAST_MODIFIED);
                        if (lm != null) {
                            try {
                                lastModified = Headers.LAST_MODIFIED.toValue(lm);
                            } catch (Exception ex) {
                                pollLogger.error("invalid-last-modified").add("value", lm).add("cnb", item.getCodeNameBase())
                                        .add("url", item.getFrom());
                                probe.onError(item, ex);
                            }
                        }
                        boolean result = OK.equals(status);
                        if (!result) {
                            removePending(item);
                        }
                        return result;
                    }

                    private void removePending(ModuleItem item) {
                        if (pending.remove(item) && pending.isEmpty()) {
                            probe.onPollCycleCompleted(set);
                        }
                    }

                    @Override
                    public void onModuleDownload(InfoFile module, InputStream bytes, String hash, String url) {
                        try {
                            pollLogger.info("newVersionDownloaded")
                                    .add("cnb", module.getModuleCodeName())
                                    .add("url", url)
                                    .add("version", module.getModuleVersion().toString()).close();
                            probe.onNewVersionDownloaded(item, module, url);
                            set.add(module, bytes, url, hash, item.isUseOriginalURL(), lastModified);
                            removePending(item);
                        } catch (IOException ex) {
                            pollLogger.error("downloadFail")
                                    .add("url", url)
                                    .add(ex).close();
                            Exceptions.printStackTrace(ex);
                            probe.onError(item, ex);
                            removePending(item);
                        } catch (XPathExpressionException ex) {
                            pollLogger.error("downloadFail")
                                    .add("url", url)
                                    .add(ex).close();
                            Exceptions.printStackTrace(ex);
                            probe.onError(item, ex);
                            removePending(item);
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        pollLogger.error("download").add("url", item.getFrom()).add(t).close();
                        probe.onError(item, t);
                        removePending(item);
                    }
                });
            } catch (IOException | URISyntaxException | SAXException | ParserConfigurationException ex) {
                probe.onError(item, ex);
                Exceptions.printStackTrace(ex);
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

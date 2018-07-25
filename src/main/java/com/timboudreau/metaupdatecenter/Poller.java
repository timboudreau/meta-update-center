package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.bunyan.Logger;
import com.mastfrog.giulius.ShutdownHookRegistry;
import com.mastfrog.giulius.annotations.Defaults;
import com.mastfrog.giulius.annotations.Namespace;
import com.mastfrog.netty.http.client.HttpClient;
import com.mastfrog.util.preconditions.ConfigurationError;
import com.mastfrog.util.preconditions.Exceptions;
import com.timboudreau.metaupdatecenter.NbmDownloader.DownloadHandler;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_POLL_INTERVAL_MINUTES;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.time.ZonedDateTime;
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
@Namespace("nbmserver")
@Defaults(namespace = @Namespace("nbmserver"), value = SETTINGS_KEY_POLL_INTERVAL_MINUTES + "=10")
public class Poller implements Runnable {

    private final ModuleSet set;
    private final NbmDownloader downloader;
    private final Logger pollLogger;

    @Inject
    Poller(ModuleSet set, @Named(SETTINGS_KEY_POLL_INTERVAL_MINUTES) long interval,
            HttpClient client, ShutdownHookRegistry registry, NbmDownloader downloader,
            @Named("poller") ScheduledExecutorService pollThreadPool,
            @Named(UpdateCenterServer.SYSTEM_LOGGER) Logger pollLogger) {
        if (interval <= 0) {
            throw new ConfigurationError("Poll interval must be >= 0 but is " + interval);
        }
        this.downloader = downloader;
        this.set = set;
        this.pollLogger = pollLogger;
        registry.add((Runnable) client::shutdown);
        pollThreadPool.scheduleWithFixedDelay(this, 3, interval, TimeUnit.MINUTES);
    }

    @Override
    public void run() {
        String msg = "Poll NBMs at " + Headers.toISO2822Date(ZonedDateTime.now());
        pollLogger.trace("poll").close();
        Thread.currentThread().setName(msg);
        try {
            for (final ModuleItem item : set) {
                try {
                    if (UpdateCenterServer.DUMMY_URL.equals(item.getFrom())) {
                        continue;
                    }
                    downloader.download(item.getDownloaded(), item.getFrom(), new DownloadHandler() {
                        ZonedDateTime lastModified;
                        @Override
                        public boolean onResponse(HttpResponseStatus status, HttpHeaders headers) {
                            if (status.code() > 399) {
                                pollLogger.warn("downloadFail").add("url", item.getFrom()).add("status", status.code()).close();
                            }
                            String lm = headers.get(HttpHeaderNames.LAST_MODIFIED);
                            if (lm != null) {
                                try {
                                    lastModified = Headers.LAST_MODIFIED.toValue(lm);
                                } catch (Exception ex) {
                                    pollLogger.error("invalid-last-modified").add("value", lm).add(item.getCodeNameBase());
                                }
                            }
                            return OK.equals(status);
                        }

                        @Override
                        public void onModuleDownload(InfoFile module, InputStream bytes, String hash, String url) {
                            try {
                                pollLogger.info("newVersionDownloaded")
                                        .add("cnb", module.getModuleCodeName())
                                        .add("url", url)
                                        .add("version", module.getModuleVersion().toString()).close();
                                set.add(module, bytes, url, hash, item.isUseOriginalURL(), lastModified);
                            } catch (IOException ex) {
                                Exceptions.printStackTrace(ex);
                            } catch (XPathExpressionException ex) {
                                Exceptions.printStackTrace(ex);
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            pollLogger.error("download").add("url", item.getFrom()).add(t).close();
                        }
                    });
                } catch (IOException | URISyntaxException | SAXException | ParserConfigurationException ex) {
                    Exceptions.printStackTrace(ex);
                }
            }
        } finally {
//            task.schedule((int) this.interval.toMillis());
        }
    }
}

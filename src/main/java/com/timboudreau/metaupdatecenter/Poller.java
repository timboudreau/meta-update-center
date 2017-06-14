package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.bunyan.Logger;
import com.mastfrog.giulius.ShutdownHookRegistry;
import com.mastfrog.giulius.annotations.Defaults;
import com.mastfrog.giulius.annotations.Namespace;
import com.mastfrog.netty.http.client.HttpClient;
import com.mastfrog.util.ConfigurationError;
import com.timboudreau.metaupdatecenter.NbmDownloader.DownloadHandler;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_POLL_INTERVAL_MINUTES;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.ZonedDateTime;
import javax.inject.Named;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import org.openide.util.Exceptions;
import org.openide.util.RequestProcessor;
import org.xml.sax.SAXException;

/**
 *
 * @author Tim Boudreau
 */
@Namespace("nbmserver")
@Defaults(namespace = @Namespace("nbmserver"), value = SETTINGS_KEY_POLL_INTERVAL_MINUTES + "=10")
public class Poller implements Runnable {

    private final ModuleSet set;
    private final Duration interval;
    private final HttpClient client;
    private final NbmDownloader downloader;
    private final RequestProcessor.Task task = RequestProcessor.getDefault().create(this);
    private final Logger pollLogger;

    @Inject
    Poller(ModuleSet set, @Named(SETTINGS_KEY_POLL_INTERVAL_MINUTES) long interval, HttpClient client, ShutdownHookRegistry registry, NbmDownloader downloader, @Named(UpdateCenterServer.SYSTEM_LOGGER) Logger pollLogger) {
        if (interval <= 0) {
            throw new ConfigurationError("Poll interval must be >= 0 but is " + interval);
        }
        this.set = set;
        this.interval = Duration.ofMinutes(interval);
        this.client = client;
        registry.add(new Runnable() {
            @Override
            public void run() {
                Poller.this.client.shutdown();
                task.cancel();
            }
        });
        this.downloader = downloader;
        task.schedule((int) Duration.ofMinutes(3).toMillis());
        this.pollLogger = pollLogger;
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

                        @Override
                        public boolean onResponse(HttpResponseStatus status, HttpHeaders headers) {
                            if (status.code() > 399) {
                                pollLogger.warn("downloadFail").add("url", item.getFrom()).add("status", status.code()).close();
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
                                set.add(module, bytes, url, hash, item.isUseOriginalURL());
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
            task.schedule((int) this.interval.toMillis());
        }
    }
}

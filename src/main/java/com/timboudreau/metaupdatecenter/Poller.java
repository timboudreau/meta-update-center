package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.mastfrog.acteur.util.Headers;
import com.mastfrog.giulius.ShutdownHookRegistry;
import com.mastfrog.guicy.annotations.Defaults;
import com.mastfrog.guicy.annotations.Namespace;
import com.mastfrog.netty.http.client.HttpClient;
import com.mastfrog.util.ConfigurationError;
import com.timboudreau.metaupdatecenter.NbmDownloader.DownloadHandler;
import static com.timboudreau.metaupdatecenter.Poller.SETTINGS_KEY_POLL_INTERVAL_MINUTES;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import javax.inject.Named;
import javax.xml.parsers.ParserConfigurationException;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.openide.util.Exceptions;
import org.openide.util.RequestProcessor;
import org.xml.sax.SAXException;

/**
 *
 * @author Tim Boudreau
 */
@Namespace("nbmserver")
@Defaults(namespace=@Namespace("nbmserver"), value=SETTINGS_KEY_POLL_INTERVAL_MINUTES + "=3")
public class Poller implements Runnable {

    public static final String SETTINGS_KEY_POLL_INTERVAL_MINUTES = "poll.interval.minutes";
    private final ModuleSet set;
    private final Duration interval;
    private final HttpClient client;
    private final NbmDownloader downloader;
    private final RequestProcessor.Task task = RequestProcessor.getDefault().create(this);

    @Inject
    Poller(ModuleSet set, @Named(SETTINGS_KEY_POLL_INTERVAL_MINUTES) long interval, HttpClient client, ShutdownHookRegistry registry, NbmDownloader downloader) {
        if (interval <= 0) {
            throw new ConfigurationError("Poll interval must be >= 0 but is " + interval);
        }
        this.set = set;
        this.interval = Duration.standardMinutes(interval);
        this.client = client;
        registry.add(new Runnable() {
            @Override
            public void run() {
                Poller.this.client.shutdown();
                task.cancel();
            }
        });
        this.downloader = downloader;
        task.schedule((int) Duration.standardSeconds(30).getMillis());
    }

    @Override
    public void run() {
        String msg = "Poll NBMs at " + Headers.toISO2822Date(DateTime.now());
        Thread.currentThread().setName(msg);
        System.out.println(msg);
        try {
            for (final ModuleItem item : set) {
                try {
                    if (UpdateCenterServer.DUMMY_URL.equals(item.getFrom())) {
                        continue;
                    }
                    downloader.download(item.getDownloaded(), item.getFrom(), new DownloadHandler() {

                        @Override
                        public boolean onResponse(HttpResponseStatus status, HttpHeaders headers) {
                            return OK.equals(status);
                        }

                        @Override
                        public void onModuleDownload(InfoFile module, InputStream bytes, String hash, String url) {
                            try {
                                set.add(module, bytes, url, hash, item.isUseOriginalURL());
                            } catch (IOException ex) {
                                Exceptions.printStackTrace(ex);
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            t.printStackTrace();
                        }
                    });
                } catch (IOException | URISyntaxException | SAXException | ParserConfigurationException ex) {
                    Exceptions.printStackTrace(ex);
                }
            }
        } finally {
            task.schedule((int) this.interval.getMillis());
        }
    }
}

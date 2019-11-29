package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.ResponseWriter;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.headers.Headers;
import static com.mastfrog.acteur.headers.Method.GET;
import static com.mastfrog.acteur.headers.Method.POST;
import static com.mastfrog.acteur.headers.Method.PUT;
import com.mastfrog.acteur.preconditions.Authenticated;
import com.mastfrog.acteur.preconditions.Description;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.PathRegex;
import com.mastfrog.acteur.preconditions.RequiredUrlParameters;
import com.mastfrog.acteur.util.CacheControl;
import com.mastfrog.url.URL;
import com.mastfrog.util.preconditions.Exceptions;
import com.mastfrog.util.time.TimeUtil;
import com.timboudreau.metaupdatecenter.NbmDownloader.DownloadHandler;
import static com.timboudreau.metaupdatecenter.PutModulePage.ADD_PAGE_REGEX;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCounted;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;

/**
 * Add a module to the update center
 *
 * @author Tim Boudreau
 */
@HttpCall
@PathRegex(ADD_PAGE_REGEX)
@Methods({PUT, POST, GET})
@RequiredUrlParameters("url")
@Authenticated
@Description("Add a module to be downloaded and served")
public class PutModulePage extends Acteur {

    public static final String ADD_PAGE_REGEX = "^add$";

    @Inject
    PutModulePage(ModuleSet set, HttpEvent evt, NbmDownloader downloader, ObjectMapper mapper) throws Exception {
        add(Headers.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        add(Headers.EXPIRES, ZonedDateTime.now().minus(Duration.ofDays(30)));
        add(Headers.CACHE_CONTROL, CacheControl.PRIVATE_NO_CACHE_NO_STORE);
        setChunked(true);
        String url = evt.urlParameter("url");
        boolean useOriginalUrl = "true".equals(evt.urlParameter("useOriginalUrl"));
        add(Headers.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        URL u = URL.parse(url);
        if (!u.isValid()) {
            setState(new RespondWith(BAD_REQUEST, "URL " + u + " has problems: " + u.getProblems()));
            return;
        }
        ok();
        Downloader handler = new Downloader(u, set, useOriginalUrl, mapper);
        setResponseWriter(handler);
        downloader.download(TimeUtil.fromUnixTimestamp(0), url, handler);
        HttpRequest req = evt.request();
        if (req instanceof ReferenceCounted) {
            // XXX figure out why this is needed
            ((ReferenceCounted) req).release();
        }
    }

    private static class Downloader extends ResponseWriter implements DownloadHandler {

        private final URL url;
        private volatile Output out;
        private final ModuleSet set;
        private final boolean origUrl;
        private final ObjectMapper mapper;

        public Downloader(URL url, ModuleSet set, boolean origUrl, ObjectMapper mapper) {
            this.url = url;
            this.set = set;
            this.origUrl = origUrl;
            this.mapper = mapper;
        }

        private final List<String> messages = new LinkedList<>();

        @Override
        public Status write(Event<?> evt, Output out, int iteration) throws Exception {
            this.out = out;
            write("Starting download of " + url + "\n");
            for (String msg : messages) {
                out.write(msg);
            }
            return Status.DEFERRED;
        }

        public boolean onResponse(HttpResponseStatus status, HttpHeaders headers) {
            try {
                write("Got " + status + " from remote host" + "\n");
            } finally {
                boolean result = HttpResponseStatus.OK.equals(status);
                if (!result) {
                    Output o = out;
                    if (o != null) {
                        try {
                            o.write(DefaultLastHttpContent.EMPTY_LAST_CONTENT).channel().flush();
                        } catch (IOException ex) {
                            Exceptions.printStackTrace(ex);
                            close();
                        }
                    }
                }
                return result;
            }
        }

        private void write(String what) {
            if (out != null) {
                try {
                    out.write(what);
                } catch (IOException ex) {
                    Exceptions.printStackTrace(ex);
                    out = null;
                }
            } else {
                messages.add(what);
            }
        }

        @Override
        public void onModuleDownload(InfoFile module, InputStream bytes, String hash, String url) {
            write("Download of " + module + " completed.  SHA-1 nbm hash: " + hash + "\n");
            ModuleItem item = null;
            try {
                item = set.add(module, bytes, url, hash, origUrl);
            } catch (IOException ex) {
                Exceptions.printStackTrace(ex);
                write("Failed " + ex + "\n");
            }
            if (item != null) {
                try {
                    write("Metadata:\n");
                    write(mapper.writeValueAsString(item.getMetadata()));
                    write("\n");
                    if (out != null) {
                        out.future().channel().flush();
                    }
                } catch (JsonProcessingException ex) {
                    Exceptions.printStackTrace(ex);
                    write("Failed " + ex + "\n");
//                    close();
                }
            } else {
                write("I already have that module.\n");
                if (out != null) {
                    out.future().channel().flush();
//                    close();
                }
            }
            if (out != null) {
                try {
                    out.future().channel().flush();
                    out.write(LastHttpContent.EMPTY_LAST_CONTENT);
                } catch (IOException ex) {
                    Exceptions.printStackTrace(ex);
                    close();
                }
            }
        }

        @Override
        public void onError(Throwable t) {
            write("Failed: " + t + "\n");
        }

        private void close() {
            if (out != null) {
                out.future().channel().flush();
                out.future().addListener(ChannelFutureListener.CLOSE);
            }
        }
    }
}

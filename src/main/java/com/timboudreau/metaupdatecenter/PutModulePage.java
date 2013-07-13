package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.ActeurFactory;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.ResponseWriter;
import com.mastfrog.acteur.auth.AuthenticateBasicActeur;
import com.mastfrog.acteur.util.CacheControlTypes;
import com.mastfrog.acteur.util.Headers;
import com.mastfrog.acteur.util.Method;
import com.mastfrog.url.URL;
import com.timboudreau.metaupdatecenter.NbmDownloader.DownloadHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.openide.util.Exceptions;

/**
 *
 * @author Tim Boudreau
 */
public class PutModulePage extends Page {
    public static final String ADD_PAGE_REGEX = "^add$";

    @Inject
    PutModulePage(ActeurFactory af) {
        add(af.matchPath(ADD_PAGE_REGEX));
        add(af.matchMethods(Method.PUT, Method.POST, Method.GET));
        add(af.requireParameters("url"));
        add(AuthenticateBasicActeur.class);
        add(AddModuleActeur.class);
        getReponseHeaders().addCacheControl(CacheControlTypes.no_cache);
        getReponseHeaders().addCacheControl(CacheControlTypes.no_store);
        getReponseHeaders().setExpires(DateTime.now().minus(Duration.standardDays(30)));
        getReponseHeaders().setContentType(MediaType.PLAIN_TEXT_UTF_8);
    }

    private static class AddModuleActeur extends Acteur {

        @Inject
        AddModuleActeur(ModuleSet set, Event evt, NbmDownloader downloader, ObjectMapper mapper) throws Exception {
            String url = evt.getParameter("url");
            boolean useOriginalUrl = "true".equals(evt.getParameter("useOriginalUrl"));
            add(Headers.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
            URL u = URL.parse(url);
            if (!u.isValid()) {
                setState(new RespondWith(BAD_REQUEST, "URL " + u + " has problems: " + u.getProblems()));
                return;
            }
            ok();
            Downloader handler = new Downloader(u, set, useOriginalUrl, mapper);
            setResponseWriter(handler);
            downloader.download(new DateTime(0), url, handler);
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

        private List<String> messages = new LinkedList<>();
        @Override
        public Status write(Event evt, Output out, int iteration) throws Exception {
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
                    close();
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
                } catch (JsonProcessingException ex) {
                    Exceptions.printStackTrace(ex);
                } finally {
                    close();
                }
            } else {
                write("I already have that module.\n");
                if (out != null) {
                    out.future().channel().flush();
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
                out.future().addListener(ChannelFutureListener.CLOSE);
            }
        }
    }
}

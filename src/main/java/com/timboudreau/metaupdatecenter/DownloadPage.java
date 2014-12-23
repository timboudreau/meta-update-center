package com.timboudreau.metaupdatecenter;

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.ActeurFactory;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.ResponseWriter;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.errors.Err;
import com.mastfrog.acteur.util.CacheControlTypes;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.headers.Method;
import static com.mastfrog.acteur.headers.Method.GET;
import static com.mastfrog.acteur.headers.Method.HEAD;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.PathRegex;
import com.mastfrog.url.Path;
import static com.timboudreau.metaupdatecenter.DownloadPage.DOWNLOAD_REGEX;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.openide.modules.SpecificationVersion;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall
@Methods({GET, HEAD})
@PathRegex(DOWNLOAD_REGEX)
public class DownloadPage extends Page {

    private static final int BUFFER_SIZE = 1490;

    public static final String DOWNLOAD_REGEX = "^download/.*?/.*\\.nbm";

    @Inject
    public DownloadPage(ActeurFactory af) {
        add(FindModuleItem.class);
        getResponseHeaders().addCacheControl(CacheControlTypes.Public);
        getResponseHeaders().addCacheControl(CacheControlTypes.max_age, Duration.standardDays(120));
        getResponseHeaders().addCacheControl(CacheControlTypes.must_revalidate);
        add(af.sendNotModifiedIfIfModifiedSinceHeaderMatches());
        add(af.sendNotModifiedIfETagHeaderMatches());
//        add(af.exactPathLength(3));
        add(DownloadActeur.class);
    }

    private static class FindModuleItem extends Acteur {

        @Inject
        FindModuleItem(ModuleSet ms, HttpEvent evt, Page page, Stats stats) {
            Path pth = evt.getPath();
            String codeName = pth.getElement(1).toString();
            String hash = pth.getElement(2).toString();
            hash = hash.substring(0, hash.length() - 4);
            System.out.println("Look for hash " + hash);
            ModuleItem item = ms.find(codeName, hash);
            if (item == null) {
                setState(new RespondWith(404, "No such file " + hash + ".nbm"));
            } else {
                page.getResponseHeaders().setLastModified(item.getDownloaded());
                page.getResponseHeaders().setETag(hash);
            }
            setState(new ConsumedLockedState(item));
            stats.logDownload(evt);
        }
    }

    private static class DownloadActeur extends Acteur {

        @Inject
        DownloadActeur(ModuleSet ms, HttpEvent evt) {
            Path pth = evt.getPath();
            String codeName = pth.getElement(1).toString();
            String hash = pth.getElement(2).toString();
            ModuleItem item = ms.find(codeName, hash);
            if (item == null) {
                setState(new RespondWith(Err.conflict("Could not find " + codeName + " with hash " + hash + " in " + ms)));
                return;
            }
            final File file = ms.getNBM(codeName, hash);
            String ifNoneMatch = evt.getHeader(Headers.IF_NONE_MATCH);
            if (ifNoneMatch != null && hash.equals(ifNoneMatch)) {
                setState(new RespondWith(NOT_MODIFIED));
                return;
            }
            setChunked(true);
            if (!file.exists()) {
                setState(new RespondWith(404, "No such file " + file));
            } else {
                add(Headers.CONTENT_TYPE, MediaType.OCTET_STREAM);
                add(Headers.LAST_MODIFIED, new DateTime(file.lastModified()));
                add(Headers.ETAG, hash);
                SpecificationVersion version = item.getVersion();
                String fn = codeName.replace('.', '-') + "_" + version + ".nbm";
                add(Headers.stringHeader("Content-Disposition"), "attachment; filename=\"" + fn + '"');
                ok();
                if (evt.getMethod() != Method.HEAD) {
                    setResponseWriter(new ResponseWriter() {
                        @Override
                        public ResponseWriter.Status write(Event<?> evt, ResponseWriter.Output out, int iteration) throws Exception {
                            // XXX chunk this in smaller chunks
                            out.write(new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE));
                            return Status.DONE;
                        }
                    });
                }
            }
        }
    }
}

package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.CheckIfModifiedSinceHeader;
import com.mastfrog.acteur.CheckIfNoneMatchHeader;
import com.mastfrog.acteur.Closables;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.annotations.Precursors;
import com.mastfrog.acteur.errors.Err;
import com.mastfrog.acteur.headers.Headers;
import static com.mastfrog.acteur.headers.Headers.CONTENT_DISPOSITION;
import static com.mastfrog.acteur.headers.Headers.CONTENT_TYPE;
import static com.mastfrog.acteur.headers.Headers.ETAG;
import static com.mastfrog.acteur.headers.Headers.LAST_MODIFIED;
import com.mastfrog.acteur.headers.Method;
import static com.mastfrog.acteur.headers.Method.GET;
import static com.mastfrog.acteur.headers.Method.HEAD;
import com.mastfrog.acteur.preconditions.Description;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.PathRegex;
import com.mastfrog.mime.MimeType;
import com.mastfrog.url.Path;
import com.mastfrog.util.time.TimeUtil;
import static com.timboudreau.metaupdatecenter.DownloadActeur.DOWNLOAD_REGEX;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AsciiString;
import java.io.File;
import java.io.FileNotFoundException;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall
@Methods({GET, HEAD})
@PathRegex(DOWNLOAD_REGEX)
@Description("Download a module")
@Precursors({FindModuleItem.class, CheckIfNoneMatchHeader.class, CheckIfModifiedSinceHeader.class})
class DownloadActeur extends Acteur {

    static final int BUFFER_SIZE = 1490;
    public static final String DOWNLOAD_REGEX = "^download/.*?/.*\\.nbm";
    public static final int FILE_CHUNK_SIZE = 768;

    @Inject
    DownloadActeur(ModuleSet ms, HttpEvent evt, Closables clos) throws FileNotFoundException {
        Path pth = evt.path();
        String codeName = pth.getElement(1).toString();
        String hash = pth.getElement(2).toString();
        ModuleItem item = ms.find(codeName, hash);
        if (item == null) {
            setState(new RespondWith(Err.conflict("Could not find " + codeName + " with hash " + hash + " in " + ms)));
            return;
        }
        final File file = ms.getNBM(codeName, hash);
        CharSequence ifNoneMatch = evt.header(Headers.IF_NONE_MATCH);
        if (ifNoneMatch != null && hash.equals(ifNoneMatch)) {
            setState(new RespondWith(HttpResponseStatus.NOT_MODIFIED));
            return;
        }
        if (!file.exists()) {
            notFound("No such file " + file);
        } else {
            setChunked(true);
            add(CONTENT_TYPE, MimeType.OCTET_STREAM);
            add(LAST_MODIFIED, TimeUtil.fromUnixTimestamp(file.lastModified()));
            add(ETAG, new AsciiString(hash));
            ok();
            if (evt.method() != Method.HEAD) {
                String filename = item.getCodeNameBase().replace('.', '-') + "_" + item.getVersion() + ".nbm";
                add(CONTENT_DISPOSITION, new AsciiString("filename=\"" + filename + "\""));
                setResponseWriter(new ChunkedFileResponseWriter(file, clos));
            }
        }
    }
}

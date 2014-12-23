package com.timboudreau.metaupdatecenter;

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.ResponseWriter;
import com.mastfrog.acteur.errors.Err;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.headers.Method;
import com.mastfrog.url.Path;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import org.joda.time.DateTime;
import org.openide.modules.SpecificationVersion;

/**
 *
 * @author Tim Boudreau
 */
class DownloadActeur extends Acteur {

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
            setState(new RespondWith(HttpResponseStatus.NOT_MODIFIED));
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
                setResponseWriter(new ResponseWriterImpl(file));
            }
        }
    } // XXX chunk this in smaller chunks

    private static class ResponseWriterImpl extends ResponseWriter {

        private final File file;

        public ResponseWriterImpl(File file) {
            this.file = file;
        }

        @Override
        public ResponseWriter.Status write(Event<?> evt, ResponseWriter.Output out, int iteration) throws Exception {
            // XXX chunk this in smaller chunks
            out.write(new BufferedInputStream(new FileInputStream(file), DownloadPage.BUFFER_SIZE));
            return ResponseWriter.Status.DONE;
        }
    }

}

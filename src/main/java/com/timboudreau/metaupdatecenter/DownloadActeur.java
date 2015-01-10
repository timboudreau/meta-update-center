package com.timboudreau.metaupdatecenter;

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.Closables;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.ResponseWriter;
import com.mastfrog.acteur.errors.Err;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.headers.Method;
import com.mastfrog.url.Path;
import com.timboudreau.metaupdatecenter.borrowed.SpecificationVersion;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import org.joda.time.DateTime;

/**
 *
 * @author Tim Boudreau
 */
class DownloadActeur extends Acteur {

    private static final int BUFFER_SIZE = 1024;

    @Inject
    DownloadActeur(ModuleSet ms, HttpEvent evt, Closables clos) throws FileNotFoundException {
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
            notFound("No such file " + file);
        } else {
            add(Headers.CONTENT_TYPE, MediaType.OCTET_STREAM);
            add(Headers.LAST_MODIFIED, new DateTime(file.lastModified()));
            add(Headers.ETAG, hash);
            SpecificationVersion version = item.getVersion();
            String fn = codeName.replace('.', '-') + "_" + version + ".nbm";
            add(Headers.stringHeader("Content-Disposition"), "attachment; filename=\"" + fn + '"');
            ok();
            if (evt.getMethod() != Method.HEAD) {
                setResponseWriter(new ResponseWriterImpl(file, clos));
            }
        }
    } // XXX chunk this in smaller chunks

    private static class ResponseWriterImpl extends ResponseWriter {

        private final byte[] buffer = new byte[BUFFER_SIZE];
        private final InputStream stream;

        public ResponseWriterImpl(File file, Closables clos) throws FileNotFoundException {
            stream = clos.add(new BufferedInputStream(new FileInputStream(file), buffer.length));
        }

        @Override
        public ResponseWriter.Status write(Event<?> evt, ResponseWriter.Output out, int iteration) throws Exception {
            ByteBuf buf = evt.getChannel().alloc().buffer(BUFFER_SIZE);
            int bytes = buf.writeBytes(stream, BUFFER_SIZE);
            if (bytes == -1) {
                stream.close();
                return ResponseWriter.Status.DONE;
            }
            out.write(buf);
            return ResponseWriter.Status.NOT_DONE;
        }
    }
}

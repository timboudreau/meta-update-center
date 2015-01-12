package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.mastfrog.acteur.Closables;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.ResponseWriter;
import io.netty.buffer.ByteBuf;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

/**
 *
 * @author Tim Boudreau
 */
class ChunkedFileResponseWriter extends ResponseWriter {
    private final byte[] buffer = new byte[DownloadActeur.FILE_CHUNK_SIZE];
    private final InputStream stream;

    @Inject
    public ChunkedFileResponseWriter(File file, Closables clos) throws FileNotFoundException {
        stream = clos.add(new BufferedInputStream(new FileInputStream(file), buffer.length));
    }

    @Override
    public ResponseWriter.Status write(Event<?> evt, ResponseWriter.Output out, int iteration) throws Exception {
        ByteBuf buf = evt.getChannel().alloc().buffer(DownloadActeur.FILE_CHUNK_SIZE);
        int bytes = buf.writeBytes(stream, DownloadActeur.FILE_CHUNK_SIZE);
        if (bytes == -1) {
            stream.close();
            return ResponseWriter.Status.DONE;
        }
        out.write(buf);
        return ResponseWriter.Status.NOT_DONE;
    }
}

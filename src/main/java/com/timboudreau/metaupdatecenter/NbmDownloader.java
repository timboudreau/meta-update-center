package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.netty.http.client.HttpClient;
import com.mastfrog.netty.http.client.HttpRequestBuilder;
import com.mastfrog.netty.http.client.ResponseFuture;
import com.mastfrog.netty.http.client.State;
import com.mastfrog.util.Streams;
import com.mastfrog.util.streams.HashingInputStream;
import com.mastfrog.util.thread.Receiver;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.joda.time.DateTime;
import org.openide.util.Exceptions;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 *
 * @author Tim Boudreau
 */
@Singleton
public class NbmDownloader {

    private final HttpClient client;

    @Inject
    public NbmDownloader(HttpClient client) {
        this.client = client;
    }

    private void handleFileDownload(URL url, DownloadHandler callback) throws URISyntaxException, FileNotFoundException, IOException, SAXException, ParserConfigurationException {
        File file = new File(url.toURI());
        if (!file.exists()) {
            throw new IOException("No such file " + url);
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 2048)) {
            ByteBuf buf = Unpooled.buffer((int) file.length());
            try (ByteBufOutputStream out = new ByteBufOutputStream(buf)) {
                Streams.copy(in, out);
            }
            buf.resetReaderIndex();
            handleDownloadedNBM(buf, callback, url.toString());
        }
    }

    public ResponseFuture download(DateTime ifModifiedSince, final String url, final DownloadHandler callback) throws MalformedURLException, URISyntaxException, FileNotFoundException, IOException, SAXException, ParserConfigurationException {

        URL uu = new URL(url);
        if ("file".equals(uu.toURI().getScheme())) {
            handleFileDownload(uu, callback);
            return null;
        }

        HttpRequestBuilder bldr = client.get().setURL(url);
        if (ifModifiedSince != null) {
            bldr = bldr.addHeader(Headers.IF_MODIFIED_SINCE, ifModifiedSince);
        }
        ResponseFuture fut = bldr.execute();
        fut.onAnyEvent(new Receiver<State<?>>() {
            @Override
            public void receive(State<?> object) {
                try {
                    switch (object.stateType()) {
                        case Error:
                            Throwable t = (Throwable) object.get();
                            callback.onError(t);
                            break;
                        case Finished:
                            DefaultFullHttpResponse resp = (DefaultFullHttpResponse) object.get();
                            if (!callback.onResponse(resp.status(), resp.headers())) {
                                return;
                            }
                            ByteBuf buf = resp.content();
                            handleDownloadedNBM(buf, callback, url);
                            break;
                        default:
                            break;
                    }
                } catch (IOException ex) {
                    Exceptions.printStackTrace(ex);
                } catch (ParserConfigurationException ex) {
                    Exceptions.printStackTrace(ex);
                } catch (SAXException ex) {
                    Exceptions.printStackTrace(ex);
                }
            }

        });
        return fut;
    }

    protected void handleDownloadedNBM(ByteBuf buf, DownloadHandler callback, String url) throws IOException, SAXException, ParserConfigurationException {
        InfoFile moduleInfo = null;
        try (HashingInputStream stream = HashingInputStream.sha1(new ByteBufInputStream(buf))) {

            ZipInputStream in = new ZipInputStream(stream, CharsetUtil.UTF_8);
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                try {
                    if ("Info/info.xml".equals(e.getName())) {
                        long size = e.getSize();
                        if (size == -1) {
                            size = 32768;
                        }

                        ByteArrayOutputStream out = new ByteArrayOutputStream();

                        byte[] buffer = new byte[Math.min(4096, (int) size)];
                        int read = 0;
                        int len = 0;
                        while ((read = in.read(buffer)) > 0) {
                            if (read != -1) {
                                len += read;
                                out.write(buffer, 0, read);
                            } else {
                                break;
                            }
                        }
                        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                        dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

                        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                        Document doc = dBuilder.parse(new ByteArrayInputStream(out.toByteArray(), 0, len));
                        doc.getDocumentElement().normalize();
                        moduleInfo = new InfoFile(doc);
                        break;
                    }
                } finally {
                    in.closeEntry();
                }
            }
            stream.close();
            String hash = stream.getHashAsString();
            buf.resetReaderIndex();
            callback.onModuleDownload(moduleInfo, new ByteBufInputStream(buf), hash, url);
        }
    }

    interface DownloadHandler {

        boolean onResponse(HttpResponseStatus status, HttpHeaders headers);

        void onModuleDownload(InfoFile module, InputStream bytes, String hash, String url);

        void onError(Throwable t);
    }
}

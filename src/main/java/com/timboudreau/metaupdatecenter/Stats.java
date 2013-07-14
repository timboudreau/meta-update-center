package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.util.BasicCredentials;
import com.mastfrog.acteur.util.Headers;
import com.mastfrog.giulius.ShutdownHookRegistry;
import com.mastfrog.util.ConfigurationError;
import io.netty.handler.codec.http.HttpHeaders;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;
import org.joda.time.DateTime;
import org.openide.util.RequestProcessor;

/**
 *
 * @author Tim Boudreau
 */
@Singleton
public class Stats implements Runnable {

    private final File statsFile;
    private final LinkedTransferQueue<CharSequence> queue = new LinkedTransferQueue<>();
    private volatile boolean shuttingDown;
    private volatile boolean shutdown;
    private final PrintStream stream;
    private final RequestProcessor.Task task = RequestProcessor.getDefault().create(this);

    @Inject
    public Stats(ModuleSet dir, ShutdownHookRegistry reg) throws IOException {
        File f = new File(dir == null ? new File(System.getProperty("java.io.tmpdir")) : dir.getStorageDir(), "stats.log");
        if (!f.exists()) {
            if (!f.createNewFile()) {
                throw new ConfigurationError("Could not create " + f);
            }
        }
        this.statsFile = f;
        OutputStream out = new BufferedOutputStream(new FileOutputStream(f, true), 80);
        stream = new PrintStream(out);
        if (reg != null) { // null in test
            reg.add(new Runnable() {

                @Override
                public void run() {
                    println("{\"shutdown\"=\"" + Headers.toISO2822Date(DateTime.now()) + "\"}");
                    shuttingDown = true;
                    Stats.this.run();
                }
            });
        }
        System.out.println("Logging stats to " + statsFile);
        println("{\"start\"=\"" + Headers.toISO2822Date(DateTime.now()) + "\"}");
    }

    private void println(CharSequence line) {
        if (shutdown) {
            System.out.println("SHUTTING DOWN:" + line);
        } else {
            queue.offer(line);
            task.schedule(1000);
        }
    }

    public void logWebHit(Event evt) {
        String referrer = evt.getHeader(HttpHeaders.Names.REFERER);
        String now = Headers.toISO2822Date(DateTime.now());
        StringBuilder sb = new StringBuilder("{\"ref\"=\"").append(referrer == null ? "-" : referrer).append("\", \"addr\"=\"").append(evt.getRemoteAddress()).append("\", \"time\"=\"").append(now).append("\"}");
        println(sb);
    }

    public void logIngest(ModuleItem item) {
        String now = Headers.toISO2822Date(DateTime.now());
        StringBuilder sb = new StringBuilder("{\"dl\"=\"").append(item.getCodeNameBase()).append("\", \"ver\"=\"").append(item.getVersion()).append("\", \"hash\"=\"").append(item.getHash()).append("\", \"time\"=\"" + now + "\"}");
        println(sb);
    }

    public void logInvalidCredentials(BasicCredentials creds, Event evt) {
        String now = Headers.toISO2822Date(DateTime.now());
        StringBuilder sb = new StringBuilder("{\"un\"=\"").append(creds.username).append("\", \"pw\"=\"").append(creds.password).append("\", \"time\"=\"").append(now).append("\", \"addr\"=\"").append(evt.getRemoteAddress()).append("\"}");
        println(sb);
    }

    public void logHit(Event evt) {
        String id = evt.getParameter(evt.getParameter("unique"));
        if (id == null) {
            id = "unknown";
        }
        String now = Headers.toISO2822Date(DateTime.now());
        StringBuilder sb = new StringBuilder("{\"id\"=\"").append(id).append("\", \"addr\"=\"").append(evt.getRemoteAddress()).append("\", \"time\"=\"").append(now).append("\"}");
        println(sb);
    }

    public void logDownload(Event evt) {
        String pth = evt.getPath().getChildPath().toString();
        String now = Headers.toISO2822Date(DateTime.now());
        StringBuilder sb = new StringBuilder("{\"pth\"=\"").append(pth).append("\", \"addr\"=\"").append(evt.getRemoteAddress()).append("\", \"time\"=\"").append(now).append("\"}");
        println(sb);
    }

    @Override
    public void run() {
        while (!queue.isEmpty()) {
            List<CharSequence> l = new LinkedList<>();
            queue.drainTo(l);
            for (CharSequence s : l) {
                stream.println(s);
            }
        }
        stream.flush();
        if (shuttingDown) {
            shutdown = true;
            stream.close();
        }
    }
}

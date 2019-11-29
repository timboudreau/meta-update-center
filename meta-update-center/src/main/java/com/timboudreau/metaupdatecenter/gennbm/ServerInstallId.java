package com.timboudreau.metaupdatecenter.gennbm;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.mastfrog.settings.Settings;
import com.mastfrog.util.streams.Streams;
import io.netty.util.CharsetUtil;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 *
 * @author Tim Boudreau
 */
@Singleton
public class ServerInstallId {

    private final long id;
    private static final long TIME_OFFSET = 1373764126035L;
    public static final String SETTINGS_KEY_SERVER_ID_FILE_PATH = "server.id.file";
    public static final String DEFAULT_SERVER_ID_FILE = ".serverid";

    @Inject
    public ServerInstallId(Settings settings) throws IOException {
        this(loadOrCreateId(settings));
    }

    public ServerInstallId(long val) throws IOException {
        id = val;
    }

    private static long loadOrCreateId(Settings settings) throws IOException {
        String serverIdFilePath = settings.getString(SETTINGS_KEY_SERVER_ID_FILE_PATH, 
                DEFAULT_SERVER_ID_FILE);
        // We compute and persist an "install id" and store it in ./.serverid
        // It is based on the current timestamp to the minute, subtracting
        // an offset (so the ID is really "minutes since this class was written").
        // This gives us a persistent but incrementing integer for the middle
        // number of the specification version, and keeps it as short as possible
        // This guarantees that generated modules will retain a middle ID after        
        long id;
        File f = new File(serverIdFilePath);
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                String s = Streams.readString(in).trim();
                id = Long.parseLong(s);
            }
        } else {
            id = (System.currentTimeMillis() - TIME_OFFSET) / (1000 * 60);
            if (!f.createNewFile()) {
                throw new IOException("Server working directory not writable: " + f.getAbsolutePath());
            }
            try (OutputStream out = new FileOutputStream(f)) {
                out.write(Long.toString(id).getBytes(CharsetUtil.UTF_8));
            }
        }
        return id;
    }

    public long get() {
        return id;
    }
}

package com.timboudreau.metaupdatecenter;

import com.mastfrog.util.streams.Streams;
import com.mastfrog.util.time.TimeUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.ZonedDateTime;

/**
 *
 * @author Tim Boudreau
 */
public enum DummyNBM {

    FIRST_1,
    FIRST_2;

    private ZonedDateTime lastModified;

    DummyNBM() {
        this(Duration.ZERO);
    }

    DummyNBM(Duration dur) {
        // A stable base time
        this.lastModified = TimeUtil.fromIsoFormat("2019-11-26T20:30:00Z").plus(dur);
    }

    public ZonedDateTime lastModified() {
        return lastModified;
    }

    public String versionStrippedName() {
        String name = name().toLowerCase();
        while (Character.isDigit(name.charAt(name.length() - 1)) || '_' == name.charAt(name.length() - 1)) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }

    public int rev() {
        StringBuilder sb = new StringBuilder();
        for (char c : name().toCharArray()) {
            if (Character.isDigit(c)) {
                sb.append(c);
            }
        }
        return Integer.parseInt(sb.toString());
    }

    @Override
    public String toString() {
        return name() + "(" + versionStrippedName() + ".nbm" + ")";
    }

    public String urlName() {
        return versionStrippedName() + ".nbm";
    }

    private String resourceName() {
        return name().toLowerCase() + ".nbm";
    }

    public byte[] bytes() throws IOException {
        try (InputStream in = DummyNBM.class.getResourceAsStream(resourceName())) {
            if (in == null) {
                throw new IOException(toString()
                        + " not on classpath next to " + DummyNBM.class.getName() + " for " + name());
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream(in.available())) {
                Streams.copy(in, out);
                return out.toByteArray();
            }
        }
    }
}

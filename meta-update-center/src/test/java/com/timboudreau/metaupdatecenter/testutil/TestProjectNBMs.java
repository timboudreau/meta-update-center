package com.timboudreau.metaupdatecenter.testutil;

import com.mastfrog.util.time.TimeUtil;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 *
 * @author Tim Boudreau
 */
public enum TestProjectNBMs {

    MODULE_A_v1(TimeUtil.fromIsoFormat("2018-11-26T00:00:00Z"),
            "first-test-module-v1/target/first-test-module-v1.nbm"),
    MODULE_A_v2(TimeUtil.fromIsoFormat("2018-11-27T00:00:00Z"),
            "first-test-module-v2/target/first-test-module-v2.nbm"),
    MODULE_B_v1(TimeUtil.fromIsoFormat("2018-11-26T00:00:00Z"),
            "second-test-module-v1/target/second-test-module-v1.nbm"),
    MODULE_B_v2(TimeUtil.fromIsoFormat("2018-11-26T00:00:00Z"),
            "second-test-module-v2/target/second-test-module-v2.nbm");

    private ZonedDateTime lastModified;
    private final String path;

    TestProjectNBMs(ZonedDateTime lastModified, String path) {
        this.lastModified = lastModified;
        this.path = path;
    }

    public synchronized void touch() {
        lastModified = ZonedDateTime.now();
    }

    public String etagValue() {
        return '"' + name() + '"';
    }

    public synchronized ZonedDateTime lastModified() {
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

    private Path path() throws URISyntaxException {
        return projectBaseDir().resolve(this.path);
    }

    private Path manifestPath() throws URISyntaxException {
        return path().getParent().resolve("classes/META-INF/MANIFEST.MF");
    }

    private Manifest man;

    private Manifest manifest() throws URISyntaxException, IOException {
        if (man != null) {
            return man;
        }
        return man = new Manifest(Files.newInputStream(manifestPath()));
    }

    public String codeNameBase() throws URISyntaxException, IOException {
        return getManifestProperty("OpenIDE-Module");
    }

    public String specificationVersion() throws URISyntaxException, IOException {
        return getManifestProperty("OpenIDE-Module-Specification-Version");
    }

    public String getBundleProperty(String key) throws URISyntaxException, IOException {
        Path bp = bundlePath();
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(bp)) {
            props.load(in);
        }
        return props.getProperty(key);
    }

    private Path bundlePath() throws URISyntaxException, IOException {
        String relpath = getManifestProperty("OpenIDE-Module-Localizing-Bundle");
        assertNotNull("No bundle specified in " + manifestPath());
        Path bp = manifestPath().getParent().getParent().resolve(relpath);
        return bp;
    }

    public String getManifestProperty(String name) throws URISyntaxException, IOException {
        Manifest m = manifest();
        Attributes.Name nm = new Attributes.Name(name);
        Attributes a = m.getMainAttributes();
        Object o = a.get(nm);
        return o == null ? null : o.toString();
    }

    public long length() throws URISyntaxException, IOException {
        return Files.size(path());
    }

    public byte[] bytes() throws IOException, URISyntaxException {
        Path base = projectBaseDir();
        Path path = base.resolve(this.path);
        if (!Files.exists(path)) {
            throw new IOException(name() + " should have its bytes at "
                    + path + ", which does not exist.  Either the "
                    + "constructor argument is wrong, or it was not built "
                    + "before this project was.  Detected project root is "
                    + base + " for " + getClass().getName());
        }
        return Files.readAllBytes(path);
    }

    static Path projectBaseDir() throws URISyntaxException {
        Path baseDir = Paths.get(TestProjectNBMs.class
                .getProtectionDomain().getCodeSource()
                .getLocation().toURI()).getParent().getParent()
                .getParent();
        return baseDir;
    }
}

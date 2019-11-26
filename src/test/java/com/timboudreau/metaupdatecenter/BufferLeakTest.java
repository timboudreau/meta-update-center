package com.timboudreau.metaupdatecenter;

import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import static com.mastfrog.acteur.server.ServerModule.HTTP_COMPRESSION;
import static com.mastfrog.acteur.server.ServerModule.MAX_CONTENT_LENGTH;
import static com.mastfrog.acteur.server.ServerModule.PORT;
import com.mastfrog.acteur.util.Server;
import com.mastfrog.acteur.util.ServerControl;
import static com.mastfrog.bunyan.LoggingModule.SETTINGS_KEY_ASYNC_LOGGING;
import static com.mastfrog.bunyan.LoggingModule.SETTINGS_KEY_LOG_FILE;
import static com.mastfrog.bunyan.LoggingModule.SETTINGS_KEY_LOG_LEVEL;
import com.mastfrog.function.throwing.ThrowingRunnable;
import com.mastfrog.giulius.Dependencies;
import com.mastfrog.settings.MutableSettings;
import com.mastfrog.settings.SettingsBuilder;
import com.mastfrog.util.file.FileUtils;
import com.mastfrog.util.net.PortFinder;
import com.mastfrog.util.streams.Streams;
import com.timboudreau.metaupdatecenter.UpdateCenterServer.NbmInfoModule;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_HTTP_LOG_ENABLED;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_NBM_DIR;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_PASSWORD;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_POLL_INTERVAL_MINUTES;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_NAMESPACE;
import io.netty.util.ResourceLeakDetector;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BufferLeakTest {

    private static final PortFinder FINDER = new PortFinder();
    private final ThrowingRunnable onShutdown = ThrowingRunnable.oneShot(true);
    private static final String TEST_PASSWORD = "wurgles99";
    private int port;
    private LaunchInfo info;

    @Test
    public void test() throws Throwable {
        URL url = new URL("http://localhost:" + port + "/");
        String homePage;
        try (InputStream in = url.openStream()) {
            homePage = Streams.readUTF8String(in);
        }
        System.out.println("HOME PAGE: " + homePage);
        
    }

    @BeforeEach
    public void setup() throws IOException {
        port = FINDER.findAvailableServerPort();
        Path testDir = FileUtils.newTempDir("meta-update-center-");
        onShutdown.andAlways(() -> {
            FileUtils.deltree(testDir);
        });
        Path nbmsDir = testDir.resolve("nbms");
        Files.createDirectories(nbmsDir);
        Path logFile = testDir.resolve("nbmserver.log");
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED);

        MutableSettings settings = new SettingsBuilder(SETTINGS_NAMESPACE)
                .add(SETTINGS_KEY_NBM_DIR, nbmsDir.toString())
                .add(SETTINGS_KEY_LOG_LEVEL, "trace")
                .add(SETTINGS_KEY_LOG_FILE, logFile.toString())
                .add(PORT, port)
                .add(SETTINGS_KEY_HTTP_LOG_ENABLED, "true")
                .add("productionMode", "true")
                .add(SETTINGS_KEY_ASYNC_LOGGING, "false")
                .add(HTTP_COMPRESSION, "true")
                .add(MAX_CONTENT_LENGTH, "384")
                .add(SETTINGS_KEY_PASSWORD, TEST_PASSWORD)
                // we will manually force polling
                .add(SETTINGS_KEY_POLL_INTERVAL_MINUTES, 24000)
                .buildMutableSettings();

        UpdateCenterServer.MAIN_MODULE = this::createModule;
        Server server = UpdateCenterServer.createServer(settings);
        ServerControl ctl = server.start();
        onShutdown.andAlways(() -> {
            ctl.shutdown(true);
        });
        assertNotNull(info, "Info was not set");
    }

    @AfterEach
    public void teardown() throws Exception {
        onShutdown.run();
    }

    void forcePoll() {
        assertNotNull(info, "info null");
        info.poller.run();
    }

    void setLaunchInfo(LaunchInfo info) {
        this.info = info;
        System.out.println("set launch info " + info);
        onShutdown.andAlways(info.deps::shutdown);
    }

    Module createModule(File file) {
        return (Binder binder) -> {
            NbmInfoModule m = new NbmInfoModule(file);
            binder.install(m);
            binder.bind(new TypeLiteral<Consumer<LaunchInfo>>() {
            }).toInstance(this::setLaunchInfo);
            binder.bind(LaunchInfo.class).asEagerSingleton();
        };
    }

    static class LaunchInfo {

        final Dependencies deps;
        final Poller poller;
        final ModuleSet set;

        @Inject
        @SuppressWarnings("LeakingThisInConstructor")
        LaunchInfo(Dependencies deps, Poller poller, ModuleSet set, Consumer<LaunchInfo> c) {
            this.deps = deps;
            this.poller = poller;
            this.set = set;
            c.accept(this);
        }

        @Override
        public String toString() {
            return "LaunchInfo(" + deps + " " + poller + " " + set + ")";
        }
    }
}

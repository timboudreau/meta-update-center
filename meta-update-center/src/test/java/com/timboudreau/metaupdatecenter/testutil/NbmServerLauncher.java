/*
 * The MIT License
 *
 * Copyright 2019 Mastfrog Technologies.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.timboudreau.metaupdatecenter.testutil;

import com.google.inject.Binder;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.mastfrog.acteur.auth.AuthenticateBasicActeur;
import com.mastfrog.acteur.debug.Probe;
import static com.mastfrog.acteur.server.ServerModule.HTTP_COMPRESSION;
import static com.mastfrog.acteur.server.ServerModule.MAX_CONTENT_LENGTH;
import static com.mastfrog.acteur.server.ServerModule.PORT;
import static com.mastfrog.acteur.server.ServerModule.SETTINGS_KEY_BASE_PATH;
import static com.mastfrog.acteur.util.PasswordHasher.SETTINGS_KEY_PASSWORD_SALT;
import com.mastfrog.acteur.util.Server;
import com.mastfrog.acteur.util.ServerControl;
import static com.mastfrog.bunyan.LoggingModule.SETTINGS_KEY_ASYNC_LOGGING;
import static com.mastfrog.bunyan.LoggingModule.SETTINGS_KEY_LOG_FILE;
import static com.mastfrog.bunyan.LoggingModule.SETTINGS_KEY_LOG_HOSTNAME;
import static com.mastfrog.bunyan.LoggingModule.SETTINGS_KEY_LOG_LEVEL;
import static com.mastfrog.bunyan.LoggingModule.SETTINGS_KEY_LOG_TO_CONSOLE;
import com.mastfrog.function.throwing.ThrowingRunnable;
import com.mastfrog.settings.MutableSettings;
import com.mastfrog.settings.SettingsBuilder;
import com.mastfrog.util.file.FileUtils;
import com.mastfrog.util.net.PortFinder;
import com.timboudreau.metaupdatecenter.Poller;
import com.timboudreau.metaupdatecenter.UpdateCenterServer;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_HTTP_LOG_ENABLED;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_NBM_DIR;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_PASSWORD;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_POLL_INITIAL_DELAY_MINUTES;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_POLL_INTERVAL_MINUTES;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_NAMESPACE;
import io.netty.util.ResourceLeakDetector;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 * @author Tim Boudreau
 */
public final class NbmServerLauncher {

    // Intentionally not using the netty HttpClient since we are
    // trying to debug buffer leaks, and extra things using netty buffers
    // can get confusing
    public static final String TEST_PASSWORD = "wurgles99";
    public static final String TEST_ADMIN_USER = "parasaurolophus";
    private static final PortFinder FINDER = new PortFinder();
    private final String basePath;
    private final ThrowingRunnable onShutdown = ThrowingRunnable.oneShot(true);
    private EnumSet<TestProjectNBMs> nbmSet;
    private LaunchInfo info;
    private PollInterceptor interceptor = new PollInterceptor();
    private boolean instrumentEventLoop;
    private boolean instrumentAuthentication;
    private Consumer<MutableSettings> onCreateSettings;
    private Consumer<Binder> onBind;
    private final EnumSet<TestProjectNBMs> initialNbms = EnumSet.noneOf(TestProjectNBMs.class);
    private boolean moduleCreated;

    public NbmServerLauncher() {
        this(null);
    }

    public NbmServerLauncher(String basePath) {
        this.basePath = basePath;
    }

    public NbmServerLauncher setInitialNbms(TestProjectNBMs... nbms) {
        for (TestProjectNBMs nbm : nbms) {
            initialNbms.add(nbm);
        }
        return this;
    }

    public NbmServerLauncher onBind(Consumer<Binder> c) {
        if (this.onBind == null) {
            this.onBind = c;
        } else {
            this.onBind = this.onBind.andThen(c);
        }
        return this;
    }

    public NbmServerLauncher onCreateSettings(Consumer<MutableSettings> c) {
        if (this.onCreateSettings == null) {
            this.onCreateSettings = c;
        } else {
            this.onCreateSettings = this.onCreateSettings.andThen(c);
        }
        return this;
    }

    public LaunchedNbmServer launch() throws Exception {
        int port = FINDER.findAvailableServerPort();
        Path testDir = FileUtils.newTempDir("meta-update-center-");
        onShutdown.andAlways(() -> {
            FileUtils.deltree(testDir);
        });
        Path nbmsDir = testDir.resolve("nbms");
        Files.createDirectories(nbmsDir);
        Path logFile = testDir.resolve("nbmserver.log");
        System.setProperty("io.netty.leakDetection.targetRecords", "100");
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        int serverPort = DummyNbmsServer.start(FINDER, (server, deps, nbmSet, app) -> {
            onShutdown.andAlways(deps::shutdown);
            server.start();
            this.nbmSet = nbmSet;
        });

        MutableSettings settings = new SettingsBuilder(SETTINGS_NAMESPACE)
                .add("realm", "whatevs")
                .add(SETTINGS_KEY_NBM_DIR, nbmsDir.toString())
                .add(SETTINGS_KEY_LOG_LEVEL, "trace")
                .add(SETTINGS_KEY_LOG_TO_CONSOLE, true)
                .add(SETTINGS_KEY_LOG_HOSTNAME, "metaupdate")
                .add(SETTINGS_KEY_LOG_FILE, logFile.toString())
                .add(PORT, port)
                .add(SETTINGS_KEY_HTTP_LOG_ENABLED, "true")
                .add("productionMode", "true")
                .add(SETTINGS_KEY_ASYNC_LOGGING, "false")
                .add(HTTP_COMPRESSION, "true")
                .add(MAX_CONTENT_LENGTH, "384")
                .add("admin.user.name", TEST_ADMIN_USER)
                .add(SETTINGS_KEY_PASSWORD, TEST_PASSWORD)
                .add(SETTINGS_KEY_PASSWORD_SALT, "9020zksldalep")
                // we will manually force polling, so just make the delay long
                // enough that it will never run automatically and surprise us
                .add(SETTINGS_KEY_POLL_INITIAL_DELAY_MINUTES, 24000)
                .add(SETTINGS_KEY_POLL_INTERVAL_MINUTES, 24000)
                .buildMutableSettings();
        if (basePath != null && !basePath.isEmpty()) {
            settings.setString(SETTINGS_KEY_BASE_PATH, basePath);
        }
        if (onCreateSettings != null) {
            onCreateSettings.accept(settings);
        }

        UpdateCenterServer.MAIN_MODULE = this::createModule;
        Server server = UpdateCenterServer.createServer(settings);
        ServerControl ctl = server.start();
        onShutdown.andAlways(() -> {
            ctl.shutdown(true);
        });
        assertTrue(moduleCreated, "Module was never created - server not launched?");
        assertNotNull(info, "Info was not set");
        assertTrue(serverPort > 0, "" + serverPort);
        assertNotNull(nbmSet);
        nbmSet.addAll(initialNbms);
//        nbmSet.add(MODULE_A_v1);
        return new LaunchedNbmServer(basePath, port, serverPort, nbmsDir, info,
                interceptor, onShutdown, nbmSet);
    }

    private void setLaunchInfo(LaunchInfo info) {
        this.info = info;
        onShutdown.andAlways(info.deps::shutdown);
    }

    public NbmServerLauncher instrumentEventLoop() {
        instrumentEventLoop = true;
        return this;
    }

    public NbmServerLauncher instrumentAuthentication() {
        instrumentAuthentication = true;
        return this;
    }

    private com.google.inject.Module createModule(File file) {
        return (Binder binder) -> {
            UpdateCenterServer.NbmInfoModule m = new UpdateCenterServer.NbmInfoModule(file);
            binder.install(m);
            binder.bind(new TypeLiteral<Consumer<LaunchInfo>>() {
            }).toInstance(this::setLaunchInfo);
            binder.bind(LaunchInfo.class).asEagerSingleton();

            binder.bind(Poller.PollerProbe.class).toInstance(interceptor);
            if (instrumentEventLoop) {
                binder.bind(Probe.class).toInstance(new ProbeImpl().get());
            }
            if (instrumentAuthentication) {
                binder.bind(AuthenticateBasicActeur.AuthenticationDecorator.class)
                        .to(AuthLoggingDecorator.class).in(Scopes.SINGLETON);
            }
            if (onBind != null) {
                onBind.accept(binder);
            }
            moduleCreated = true;
        };
    }

}

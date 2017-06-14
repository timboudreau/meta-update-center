package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.mastfrog.acteur.ActeurFactory;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.Help;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.ImplicitBindings;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.annotations.GenericApplication;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.auth.Authenticator;
import com.mastfrog.acteur.bunyan.ActeurBunyanModule;
import static com.mastfrog.acteur.server.ServerModule.BYTEBUF_ALLOCATOR_SETTINGS_KEY;
import static com.mastfrog.acteur.server.ServerModule.POOLED_ALLOCATOR;
import static com.mastfrog.acteur.headers.Method.GET;
import static com.mastfrog.acteur.headers.Method.HEAD;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.PathRegex;
import com.mastfrog.acteur.server.ServerBuilder;
import com.mastfrog.acteur.server.ServerModule;
import static com.mastfrog.acteur.server.ServerModule.HTTP_COMPRESSION;
import static com.mastfrog.acteur.server.ServerModule.MAX_CONTENT_LENGTH;
import com.mastfrog.acteur.util.Server;
import com.mastfrog.bunyan.Log;
import com.mastfrog.bunyan.Logger;
import static com.mastfrog.bunyan.LoggingModule.SETTINGS_KEY_ASYNC_LOGGING;
import static com.mastfrog.bunyan.LoggingModule.SETTINGS_KEY_LOG_FILE;
import static com.mastfrog.bunyan.LoggingModule.SETTINGS_KEY_LOG_LEVEL;
import com.mastfrog.giulius.ShutdownHookRegistry;
import com.mastfrog.giulius.annotations.Defaults;
import com.mastfrog.giulius.annotations.Namespace;
import com.mastfrog.jackson.DurationSerializationMode;
import com.mastfrog.jackson.JacksonModule;
import com.mastfrog.jackson.TimeSerializationMode;
import com.mastfrog.netty.http.client.HttpClient;
import com.mastfrog.settings.MutableSettings;
import com.mastfrog.settings.Settings;
import com.mastfrog.settings.SettingsBuilder;
import com.mastfrog.util.GUIDFactory;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_ADMIN_USER_NAME;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_NAMESPACE;
import com.timboudreau.metaupdatecenter.gennbm.ServerInstallId;
import com.timboudreau.metaupdatecenter.gennbm.UpdateCenterModuleGenerator;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.ParserConfigurationException;
import org.openide.util.Exceptions;
import org.xml.sax.SAXException;

@ImplicitBindings(ModuleItem.class)
@Defaults(namespace = @Namespace(SETTINGS_NAMESPACE), value = {
    SETTINGS_KEY_ADMIN_USER_NAME + "=admin",
    "realm=NbmServerAdmin"
})
@Help
public class UpdateCenterServer extends GenericApplication {

    public static final String SETTINGS_KEY_SERVER_VERSION = "serverVersion";
    public static final int VERSION = 8;
    public static final String STATS_LOGGER = "stats";
    public static final String ERROR_LOGGER = ActeurBunyanModule.ERROR_LOGGER;
    public static final String REQUESTS_LOGGER = ActeurBunyanModule.ACCESS_LOGGER;
    public static final String DOWNLOAD_LOGGER = "downloads";
    public static final String SYSTEM_LOGGER = "system";
    public static final String SETTINGS_KEY_NBM_DIR = "nbm.dir";
    public static final String SETTINGS_KEY_PASSWORD = "password";
    public static final String SETTINGS_KEY_ADMIN_USER_NAME = "admin.user.name";
    public static final String SETTINGS_KEY_POLL_INTERVAL_MINUTES = "poll.interval.minutes";
    public static final String SETTINGS_KEY_HTTP_LOG_ENABLED = "http.log.enabled";
    public static final boolean DEFAULT_HTTP_LOG_ENABLED = true;
    public static final int FILE_CHUNK_SIZE = 768;
    private static final String SERVER_NAME = " Tim Boudreau's Update Aggregator 1." + VERSION + " - " + "https://github.com/timboudreau/meta-update-center";
    public static final String SETTINGS_NAMESPACE = "nbmserver";
    public static final String DUMMY_URL = "http://GENERATED.MODULE";
    private final Stats stats;

    @Inject
    UpdateCenterServer(ModuleSet set, UpdateCenterModuleGenerator gen, Stats stats, @Named(SYSTEM_LOGGER) final Logger systemLogger, Settings settings, ServerInstallId serverId, ShutdownHookRegistry shutdown) throws IOException, ParserConfigurationException, SAXException {
        try {
            set.scan();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        try (InputStream nbm = gen.getNbmInputStream()) {
            InfoFile nbmInfo = gen.getInfoFile();
            String hash = gen.getHash();
            set.add(nbmInfo, nbm, DUMMY_URL, hash, false);
        }
        System.err.println("Serving the following modules:");
        List<Map<String, Object>> logInfo = new LinkedList<>();
        for (ModuleItem i : set) {
            System.err.println("\t" + i);
            logInfo.add(i.toMap());
        }
        this.stats = stats;
        try (Log<?> log = systemLogger.info("startup")) {
            log.addIfNotNull(SETTINGS_KEY_NBM_DIR, settings.getString(SETTINGS_KEY_NBM_DIR))
                    .add("serverId", serverId.get())
                    .add("version", VERSION)
                    .add("modules", logInfo)
                    .addIfNotNull("pollInterval", settings.getInt(SETTINGS_KEY_POLL_INTERVAL_MINUTES))
                    .addIfNotNull(ServerModule.PORT, settings.getInt(ServerModule.PORT))
                    .addIfNotNull(ServerModule.WORKER_THREADS, settings.getInt(ServerModule.WORKER_THREADS))
                    .addIfNotNull(ServerModule.BYTEBUF_ALLOCATOR_SETTINGS_KEY, settings.getString(ServerModule.BYTEBUF_ALLOCATOR_SETTINGS_KEY))
                    .addIfNotNull(SETTINGS_KEY_LOG_LEVEL, settings.getString(SETTINGS_KEY_LOG_LEVEL));
        }
        shutdown.add(new Runnable() {
            @Override
            public void run() {
                systemLogger.info("shutdown").close();
            }
        });
    }

    @Override
    public String getName() {
        return SERVER_NAME;
    }

    public static void main(String[] args) {
        try {
            MutableSettings settings = new SettingsBuilder(SETTINGS_NAMESPACE)
                    .add(SETTINGS_KEY_LOG_LEVEL, "info")
                    .add(SETTINGS_KEY_LOG_FILE, "nbmserver.log")
                    .add(SETTINGS_KEY_HTTP_LOG_ENABLED, DEFAULT_HTTP_LOG_ENABLED + "")
                    .add("productionMode", "true")
                    .add(SETTINGS_KEY_ASYNC_LOGGING, "true")
                    .add(HTTP_COMPRESSION, "true")
                    .add(MAX_CONTENT_LENGTH, "384")
                    .addFilesystemAndClasspathLocations()
                    .parseCommandLineArguments(args).buildMutableSettings();

            settings.setString(BYTEBUF_ALLOCATOR_SETTINGS_KEY, POOLED_ALLOCATOR);
            String path = settings.getString(SETTINGS_KEY_NBM_DIR);
            if (path == null) {
                File tmp = new File(System.getProperty("java.io.tmpdir"));
                File dir = new File(tmp, SETTINGS_NAMESPACE);
                if (!dir.exists()) {
                    if (!dir.mkdirs()) {
                        throw new IOException("Could not create " + dir);
                    }
                }
                System.err.println("--" + SETTINGS_KEY_NBM_DIR
                        + " not specified on command line or settings.");
                System.err.println("Serving nbms from " + dir.getAbsolutePath());
                settings.setString(SETTINGS_KEY_NBM_DIR, path = dir.getAbsolutePath());
            }
            File base = new File(path);

            String password = settings.getString(SETTINGS_KEY_PASSWORD);
            if (password == null) {
                password = GUIDFactory.get().newGUID(1, 12);
                System.err.println("--" + SETTINGS_KEY_PASSWORD
                        + " not specified on command line or setttings.");
                System.err.println("Using one-time password '" + password + "'");
                settings.setString(SETTINGS_KEY_PASSWORD, password);
            }

            Server server = new ServerBuilder(SETTINGS_NAMESPACE)
                    .add(new ActeurBunyanModule(true)
                            .bindLogger(STATS_LOGGER)
                            .bindLogger(SYSTEM_LOGGER)
                            .bindLogger(DOWNLOAD_LOGGER))
                    .add(new NbmInfoModule(base))
                    .applicationClass(UpdateCenterServer.class)
                    .add(new JacksonModule().withJavaTimeSerializationMode(TimeSerializationMode.TIME_AS_EPOCH_MILLIS,
                            DurationSerializationMode.DURATION_AS_MILLIS))
                    .add(settings)
                    .build();

            server.start().await();
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
            System.exit(1);
        } catch (InterruptedException ex) {
            Exceptions.printStackTrace(ex);
        }
    }

    @Override
    protected HttpResponse createNotFoundResponse(Event<?> event) {
        HttpEvent evt = (HttpEvent) event;
        stats.logNotFound(evt);
        return super.createNotFoundResponse(event);
    }

    @HttpCall(order = Integer.MIN_VALUE)
    @PathRegex("^favicon.ico$")
    @Methods({GET, HEAD})
    private static class FaviconPage extends Page {

        @Inject
        FaviconPage(ActeurFactory af) {
            add(af.respondWith(HttpResponseStatus.NOT_FOUND));
        }
    }

    private static class NbmInfoModule extends AbstractModule {

        private final File base;

        public NbmInfoModule(File base) {
            this.base = base;
        }

        @Override
        protected void configure() {
            ModuleSet set = new ModuleSet(base, binder().getProvider(ObjectMapper.class), binder().getProvider(Stats.class));
            bind(ModuleSet.class).toInstance(set);
            bind(HttpClient.class).toProvider(HttpClientProvider.class);
            bind(Authenticator.class).to(AuthenticatorImpl.class);
            bind(Poller.class).asEagerSingleton();
            bind(Integer.class).annotatedWith(Names.named(SETTINGS_KEY_SERVER_VERSION)).toInstance(VERSION);
        }

        @Singleton
        static class HttpClientProvider implements Provider<HttpClient> {

            private final HttpClient client;

            @Inject
            HttpClientProvider(ByteBufAllocator alloc, Settings settings) {
                int downloadThreads = settings.getInt("download.threads", 4);
                client = HttpClient.builder()
                        .setUserAgent(SERVER_NAME)
                        .followRedirects()
                        .setChannelOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                        .threadCount(downloadThreads)
                        .maxChunkSize(32768).build();
            }

            @Override
            public HttpClient get() {
                return client;
            }
        }
    }
}

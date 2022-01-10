package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import com.mastfrog.acteur.ActeurFactory;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.Help;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.ImplicitBindings;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.annotations.GenericApplication;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.auth.AuthenticationActeur;
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
import com.mastfrog.bunyan.java.v2.Log;
import com.mastfrog.bunyan.java.v2.Logs;
import com.mastfrog.giulius.ShutdownHookRegistry;
import static com.mastfrog.giulius.bunyan.java.v2.LoggingModule.SETTINGS_KEY_ASYNC_LOGGING;
import static com.mastfrog.giulius.bunyan.java.v2.LoggingModule.SETTINGS_KEY_LOG_FILE;
import static com.mastfrog.giulius.bunyan.java.v2.LoggingModule.SETTINGS_KEY_LOG_LEVEL;
import com.mastfrog.giulius.thread.ThreadModule;
import com.mastfrog.jackson.DurationSerializationMode;
import com.mastfrog.jackson.JacksonModule;
import com.mastfrog.jackson.TimeSerializationMode;
import com.mastfrog.netty.http.client.HttpClient;
import com.mastfrog.settings.MutableSettings;
import com.mastfrog.settings.Settings;
import com.mastfrog.settings.SettingsBuilder;
import com.mastfrog.util.libversion.VersionInfo;
import com.mastfrog.util.preconditions.Exceptions;
import com.mastfrog.util.strings.Strings;
import com.mastfrog.util.strings.RandomStrings;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_ADMIN_USER_NAME;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_NAMESPACE;
import com.timboudreau.metaupdatecenter.gennbm.ServerInstallId;
import com.timboudreau.metaupdatecenter.gennbm.UpdateCenterModuleGenerator;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

@ImplicitBindings(ModuleItem.class)
@Help
public class UpdateCenterServer extends GenericApplication {

    public static final String SETTINGS_KEY_WATCH_DIR = "watch.dir";
    public static final String SETTINGS_KEY_FILE_NOTIFICATION_PROCESS_DELAY_SECONDS = "watch.delay.seconds";
    public static final String STATS_LOGGER = "stats";
    public static final String ERROR_LOGGER = ActeurBunyanModule.ERROR_LOGGER;
    public static final String REQUESTS_LOGGER = ActeurBunyanModule.ACCESS_LOGGER;
    public static final String DOWNLOAD_LOGGER = "downloads";
    public static final String SYSTEM_LOGGER = "system";
    public static final String AUTH_LOGGER = "auth";
    public static final String FILE_WATCH_LOGGER = "filewatch";
    public static final String SETTINGS_KEY_TICKLE_TOKEN = "tickleToken";
    public static final String SETTINGS_KEY_NBM_DIR = "nbm.dir";
    public static final String SETTINGS_KEY_PASSWORD = "password";
    public static final String SETTINGS_KEY_ADMIN_USER_NAME = "admin.user.name";
    public static final String SETTINGS_KEY_POLL_INTERVAL_MINUTES = "poll.interval.minutes";
    public static final String SETTINGS_KEY_POLL_INITIAL_DELAY_MINUTES = "poll.initial.delay.minutes";
    public static final String SETTINGS_KEY_HTTP_LOG_ENABLED = "http.log.enabled";
    public static final String SETTINGS_KEY_TAG = "server.tag";
    public static final String SETTINGS_KEY_DOWNLOAD_THREADS = "download.threads";
    public static final String SETTINGS_KEY_GEN_MODULE_AUTHOR = "gen.module.author";
    public static final String DEFAULT_MODULE_AUTHOR = "Tim Boudreau";
    public static final String SETTINGS_KEY_INFO_PARA = "home.page.info";
    public static final String SETTINGS_KEY_DISPLAY_NAME = "home.page.display.name";
    public static final String SETTINGS_KEY_NB_UI_DISPLAY_NAME = "nb.ui.display.name";
    public static final boolean DEFAULT_HTTP_LOG_ENABLED = true;
    public static final int FILE_CHUNK_SIZE = 768;
    public static final String SETTINGS_NAMESPACE = "nbmserver";
    public static final String DUMMY_URL = "http://GENERATED.MODULE";
    public static final String GUICE_BINDING_POLLER_THREAD_POOL = "poller";
    public static final String SYS_PROP_SETTINGS_FILE_NAMESPACE = "settings.file.name";
    public static final String SETTINGS_KEY_SETTINGS_NAMESPACE = SYS_PROP_SETTINGS_FILE_NAMESPACE;
    private final Stats stats;
    private final String serverName;

    private static final Logs SYSLOG = Logs.named(UpdateCenterServer.class.getName());

    @Inject
    UpdateCenterServer(ModuleSet set, UpdateCenterModuleGenerator gen, Stats stats, Settings settings, ServerInstallId serverId, ShutdownHookRegistry shutdown, VersionInfo ver) throws IOException, ParserConfigurationException, SAXException {
        serverName = settings.getString(SETTINGS_KEY_DISPLAY_NAME) + " " + ver.deweyDecimalVersion();
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
        try (Log log = SYSLOG.info("startup")) {
            log.addIfNotNull(SETTINGS_KEY_NBM_DIR, settings.getString(SETTINGS_KEY_NBM_DIR))
                    .add("serverId", serverId.get())
                    .add("version", ver.deweyDecimalVersion())
                    .add("git-hash", ver.shortCommitHash)
                    .add("git-date", ver.commitDate)
                    .add("git-dirty", ver.dirty)
                    .add("modules", logInfo)
                    .addIfNotNull("pollInterval", settings.getInt(SETTINGS_KEY_POLL_INTERVAL_MINUTES))
                    .addIfNotNull(ServerModule.PORT, settings.getInt(ServerModule.PORT))
                    .addIfNotNull(ServerModule.WORKER_THREADS, settings.getInt(ServerModule.WORKER_THREADS))
                    .addIfNotNull(ServerModule.BYTEBUF_ALLOCATOR_SETTINGS_KEY, settings.getString(ServerModule.BYTEBUF_ALLOCATOR_SETTINGS_KEY))
                    .addIfNotNull(SETTINGS_KEY_LOG_LEVEL, settings.getString(SETTINGS_KEY_LOG_LEVEL));
        }
        shutdown.add((Runnable) () -> {
            SYSLOG.info("shutdown").close();
        });
    }

    @Override
    public String getName() {
        return serverName;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        // The system property can be used to cause an alternate configuration file
        // to be loaded - useful if more than one copy of this server is running on
        // one system

        String ns = System.getProperty(SYS_PROP_SETTINGS_FILE_NAMESPACE, SETTINGS_NAMESPACE);
        MutableSettings settings = new SettingsBuilder(ns)
                .add(SETTINGS_KEY_POLL_INTERVAL_MINUTES, 10)
                .add(SETTINGS_KEY_SETTINGS_NAMESPACE, ns)
                .add("realm", "NbmServerAdmin")
                .add(BYTEBUF_ALLOCATOR_SETTINGS_KEY, POOLED_ALLOCATOR)
                .add(SETTINGS_KEY_ADMIN_USER_NAME, "admin")
                .add(SETTINGS_KEY_LOG_LEVEL, "info")
                .add(SETTINGS_KEY_LOG_FILE, "nbmserver.log")
                .add(SETTINGS_KEY_HTTP_LOG_ENABLED, DEFAULT_HTTP_LOG_ENABLED)
                .add("productionMode", "true")
                .add("salt", "as098df7u0aQ#3,0cH!")
                .add(SETTINGS_KEY_ASYNC_LOGGING, "false")
                .add(HTTP_COMPRESSION, "true")
                .add(MAX_CONTENT_LENGTH, "384")
                .add(SETTINGS_KEY_POLL_INITIAL_DELAY_MINUTES, 2)
                .addFilesystemAndClasspathLocations()
                .parseCommandLineArguments(args).buildMutableSettings();
        createServer(settings).start().await();
    }

    public static Server createServer(MutableSettings settings) {
        try {
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
                password = Strings.shuffleAndExtract(ThreadLocalRandom.current(), new RandomStrings().get(36), 18);
                System.err.println("--" + SETTINGS_KEY_PASSWORD
                        + " not specified on command line or setttings.");
                System.err.println("Using one-time password '" + password + "'");
                settings.setString(SETTINGS_KEY_PASSWORD, password);
            }

            String tickleToken = settings.getString(SETTINGS_KEY_TICKLE_TOKEN);
            if (tickleToken == null) {
                String defaultTickleToken = new RandomStrings().randomChars(32);
                System.out.println("Fallback tickle token: '" + defaultTickleToken + "'");
                settings.setString(SETTINGS_KEY_TICKLE_TOKEN, defaultTickleToken);
            }

            Server server = new ServerBuilder(SETTINGS_NAMESPACE)
                    .add(new ActeurBunyanModule(true)
                            .bindLogger(STATS_LOGGER)
                            .bindLogger(SYSTEM_LOGGER)
                            .bindLogger(AUTH_LOGGER)
                            .bindLogger(FILE_WATCH_LOGGER)
                            .bindLogger(DOWNLOAD_LOGGER))
                    .mergeNamespaces()
                    .add(MAIN_MODULE.apply(base))
                    .add(new ThreadModule().builder(GUICE_BINDING_POLLER_THREAD_POOL).daemon().eager()
                            .scheduled()
                            .withDefaultThreadCount(3)
                            .bind())
                    .applicationClass(UpdateCenterServer.class)
                    .add(new JacksonModule().withJavaTimeSerializationMode(TimeSerializationMode.TIME_AS_EPOCH_MILLIS,
                            DurationSerializationMode.DURATION_AS_MILLIS))
                    .add(settings)
                    .build();

            return server;
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
            System.exit(1);
            return null;
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

    // Allows a test to replace NbmInfoModule with its own, or
    // otherwise intervene in the startup process
    public static Function<File, Module> MAIN_MODULE = NbmInfoModule::new;

    public static class NbmInfoModule extends AbstractModule {

        private final File base;

        public NbmInfoModule(File base) {
            this.base = base;
        }

        @Override
        protected void configure() {
            Provider<Logs> logs = binder().getProvider(Key.get(new TypeLiteral<Logs>(){}, Names.named(SYSTEM_LOGGER)));
            ModuleSet set = new ModuleSet(base, binder().getProvider(ObjectMapper.class), binder().getProvider(Stats.class), logs);
            bind(ModuleSet.class).toInstance(set);
            bind(HttpClient.class).toProvider(HttpClientProvider.class);
            bind(Authenticator.class).to(AuthenticatorImpl.class);
            bind(Poller.class).asEagerSingleton();
            bind(WatchDir.class).asEagerSingleton();
            bind(VersionInfo.class).toInstance(VersionInfo.find(UpdateCenterServer.class, "com.mastfrog", "meta-update-server"));
        }

        @Singleton
        static class HttpClientProvider implements Provider<HttpClient> {

            private final HttpClient client;

            @Inject
            HttpClientProvider(ByteBufAllocator alloc, Settings settings, VersionInfo info) {
                String ver = "nbmserver-" + info.deweyDecimalVersion() + " - https://github.com/timboudreau/meta-update-center";
                int downloadThreads = settings.getInt(SETTINGS_KEY_DOWNLOAD_THREADS, 16);
                client = HttpClient.builder()
                        .setUserAgent(ver)
                        .followRedirects()
                        .setChannelOption(ChannelOption.ALLOCATOR, alloc)
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

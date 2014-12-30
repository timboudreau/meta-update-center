package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import com.mastfrog.acteur.ActeurFactory;
import com.mastfrog.acteur.Help;
import com.mastfrog.acteur.ImplicitBindings;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.annotations.GenericApplication;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.auth.Authenticator;
import com.mastfrog.acteur.server.ServerModule;
import static com.mastfrog.acteur.server.ServerModule.BYTEBUF_ALLOCATOR_SETTINGS_KEY;
import static com.mastfrog.acteur.server.ServerModule.POOLED_ALLOCATOR;
import static com.mastfrog.acteur.headers.Method.GET;
import static com.mastfrog.acteur.headers.Method.HEAD;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.PathRegex;
import com.mastfrog.acteur.util.Server;
import com.mastfrog.giulius.Dependencies;
import com.mastfrog.guicy.annotations.Defaults;
import com.mastfrog.guicy.annotations.Namespace;
import com.mastfrog.jackson.JacksonModule;
import com.mastfrog.netty.http.client.HttpClient;
import com.mastfrog.settings.MutableSettings;
import com.mastfrog.settings.Settings;
import com.mastfrog.settings.SettingsBuilder;
import com.mastfrog.util.GUIDFactory;
import com.timboudreau.metaupdatecenter.gennbm.UpdateCenterModuleGenerator;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.xml.parsers.ParserConfigurationException;
import org.openide.util.Exceptions;
import org.xml.sax.SAXException;

@ImplicitBindings(ModuleItem.class)
@Defaults(namespace = @Namespace("nbmserver"), value = {
    "admin.user.name=admin",
    "realm=NbmServerAdmin"
})
@Help
public class UpdateCenterServer extends GenericApplication {

    public static final String SETTINGS_KEY_SERVER_VERSION = "serverVersion";
    public static final int VERSION = 4;

    private static final String SERVER_NAME = " Tim Boudreau's Update Aggregator 1." + VERSION + " - " + "https://github.com/timboudreau/meta-update-center";

    public static final String DUMMY_URL = "http://GENERATED.MODULE";

    @Inject
    UpdateCenterServer(ModuleSet set, UpdateCenterModuleGenerator gen) throws IOException, ParserConfigurationException, SAXException {
        try {
            set.scan();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        InputStream nbm = gen.getNbmInputStream();
        InfoFile nbmInfo = gen.getInfoFile();
        String hash = gen.getHash();
        set.add(nbmInfo, nbm, DUMMY_URL, hash, false);

        System.out.println("Serving the following modules:");
        for (ModuleItem i : set) {
            System.out.println("  " + i + " - " + i.getFrom());
        }
    }

    @Override
    public String getName() {
        return SERVER_NAME;
    }

    public static void main(String[] args) {
        try {
            MutableSettings settings = new SettingsBuilder("nbmserver").addDefaultLocations()
                    .parseCommandLineArguments(args).buildMutableSettings();

            // Compression is broken in Netty 4.0-CR10 - turning it off for now
            settings.setBoolean("httpCompression", true);
            settings.setString(BYTEBUF_ALLOCATOR_SETTINGS_KEY, POOLED_ALLOCATOR);
            String path = settings.getString("nbm.dir");
            if (path == null) {
                File tmp = new File(System.getProperty("java.io.tmpdir"));
                File dir = new File(tmp, "nbmserver");
                if (!dir.exists()) {
                    if (!dir.mkdirs()) {
                        throw new IOException("Could not create " + dir);
                    }
                }
                System.out.println("--nbm.dir not specified on command line or settings.");
                System.out.println("Serving nbms from " + dir.getAbsolutePath());
                settings.setString("nbm.dir", path = dir.getAbsolutePath());
            }
            File base = new File(path);

            String password = settings.getString("password");
            if (password == null) {
                password = GUIDFactory.get().newGUID();
                System.out.println("--password not specified on command line or setttings.");
                System.out.println("Using password '" + password + "'");
                settings.setString("password", password);
            }
            Dependencies deps = Dependencies.builder()
                    .add(new NbmInfoModule(base))
                    .add(new ServerModule(UpdateCenterServer.class))
                    .add(new JacksonModule())
                    .add(settings, Namespace.DEFAULT)
                    .add(settings, "nbmserver")
                    .build();

            Server server = deps.getInstance(Server.class);
            server.start().await();

        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
            System.exit(1);
        } catch (InterruptedException ex) {
            Exceptions.printStackTrace(ex);
        }
    }

    @HttpCall
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
                        .maxChunkSize(16384).build();
            }

            @Override
            public HttpClient get() {
                return client;
            }
        }
    }
}

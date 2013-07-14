package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.name.Names;
import com.mastfrog.acteur.ActeurFactory;
import com.mastfrog.acteur.Application;
import com.mastfrog.acteur.ImplicitBindings;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.auth.Authenticator;
import com.mastfrog.acteur.server.ServerModule;
import static com.mastfrog.acteur.server.ServerModule.BYTEBUF_ALLOCATOR_SETTINGS_KEY;
import static com.mastfrog.acteur.server.ServerModule.POOLED_ALLOCATOR;
import com.mastfrog.acteur.util.Method;
import com.mastfrog.acteur.util.Server;
import com.mastfrog.giulius.Dependencies;
import com.mastfrog.guicy.annotations.Defaults;
import com.mastfrog.guicy.annotations.Namespace;
import com.mastfrog.netty.http.client.HttpClient;
import com.mastfrog.settings.MutableSettings;
import com.mastfrog.settings.Settings;
import com.mastfrog.settings.SettingsBuilder;
import com.mastfrog.util.GUIDFactory;
import com.timboudreau.metaupdatecenter.gennbm.UpdateCenterModuleGenerator;
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
public class UpdateCenterServer extends Application {

    public static final String SETTINGS_KEY_SERVER_VERSION = "serverVersion";
    public static final int VERSION = 1;

    private static final String SERVER_NAME = " Tim Boudreau's Update Aggregator " + VERSION + " - " + "https://github.com/timboudreau/meta-update-center";

    public static final String DUMMY_URL = "http://GENERATED.MODULE";

    @Inject
    UpdateCenterServer(ModuleSet set, UpdateCenterModuleGenerator gen) throws IOException, ParserConfigurationException, SAXException {
        add(ModuleCatalogPage.class);
        add(PutModulePage.class);
        add(DownloadPage.class);
        add(FaviconPage.class);
        add(helpPageType());
        add(IndexPage.class);
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
            System.out.println("  " + i);
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
//            settings.setBoolean("httpCompression", false);
            settings.setString(BYTEBUF_ALLOCATOR_SETTINGS_KEY, POOLED_ALLOCATOR);
            String path = settings.getString("nbm.dir");
            if (path == null) {
                File tmp = new File(System.getProperty("java.io.tmpdir"));
                File dir = new File(tmp, "nbmserver");
                if (!dir.exists()) {
                    dir.mkdirs();
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
                    .add(new NbmInfoModule(base, settings))
                    .add(new ServerModule(UpdateCenterServer.class))
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

    private static class FaviconPage extends Page {

        @Inject
        FaviconPage(ActeurFactory af) {
            add(af.matchPath("^favicon.ico$"));
            add(af.matchMethods(Method.GET, Method.HEAD));
            add(af.respondWith(HttpResponseStatus.NOT_FOUND));
        }
    }

    private static class NbmInfoModule extends AbstractModule {

        private final File base;
        private final Settings settings;

        public NbmInfoModule(File base, Settings settings) {
            this.base = base;
            this.settings = settings;
        }

        @Override
        protected void configure() {
            ModuleSet set = new ModuleSet(base, binder().getProvider(ObjectMapper.class), binder().getProvider(Stats.class));
            bind(ModuleSet.class).toInstance(set);
            int downloadThreads = settings.getInt("download.threads", 4);
            bind(HttpClient.class).toInstance(HttpClient.builder().setUserAgent(SERVER_NAME).followRedirects().threadCount(downloadThreads).maxChunkSize(16384).build());
            bind(Authenticator.class).to(AuthenticatorImpl.class);
            bind(Poller.class).asEagerSingleton();
            bind(Integer.class).annotatedWith(Names.named(SETTINGS_KEY_SERVER_VERSION)).toInstance(VERSION);
        }
    }
}

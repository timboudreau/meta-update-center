/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.timboudreau.metaupdatecenter;

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.google.inject.TypeLiteral;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.ActeurFactory;
import com.mastfrog.acteur.Application;
import com.mastfrog.acteur.ImplicitBindings;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.server.ServerModule;
import static com.mastfrog.acteur.server.ServerModule.PORT;
import com.mastfrog.acteur.util.Server;
import com.mastfrog.function.throwing.ThrowingQuadConsumer;
import com.mastfrog.giulius.Dependencies;
import com.mastfrog.settings.Settings;
import com.mastfrog.url.Path;
import com.mastfrog.util.net.PortFinder;
import java.io.IOException;
import java.util.EnumSet;

/**
 *
 * @author Tim Boudreau
 */
@ImplicitBindings(DummyNBM.class)
public class DummyNbmsServer extends Application {
    // Intentionally don't use annotations so we don't gum up
    // the application we're testing.

    int start(PortFinder portFinder, ThrowingQuadConsumer<Server, Dependencies, EnumSet<DummyNBM>, DummyNbmsServer> c) throws IOException, Exception {
        int port = portFinder.findAvailableServerPort();
        EnumSet<DummyNBM> nbmsServed = EnumSet.noneOf(DummyNBM.class);
        Settings settings = Settings.builder().add(PORT, port).build();
        Dependencies deps = Dependencies.builder()
                .add(settings)
                .add(binder -> {
                    binder.bind(new TypeLiteral<EnumSet<DummyNBM>>() {
                    }).toInstance(nbmsServed);
                    binder.install(new ServerModule(DummyNbmsServer.class));
                }).build();

        Server server = deps.getInstance(Server.class);
        DummyNbmsServer app = (DummyNbmsServer) deps.getInstance(Application.class);
        c.apply(server, deps, nbmsServed, app);
        return port;
    }

    static class DownloadPage extends Page {

        @Inject
        DownloadPage(ActeurFactory f) {
            add(f.globPathMatch("nbms/*.nbm"));
            add(CheckExistsActeur.class);
            add(DlHeadersActeur.class);
            add(com.mastfrog.acteur.CheckIfModifiedSinceHeader.class);
            add(com.mastfrog.acteur.CheckIfNoneMatchHeader.class);
            add(DownloadActeur.class);
        }
    }

    static class CheckExistsActeur extends Acteur {

        @Inject
        CheckExistsActeur(EnumSet<DummyNBM> active, Path path) {
            String file = path.lastElement().toString();
            System.out.println("  attempt download " + file);
            DummyNBM which = null;
            for (DummyNBM nbm : active) {
                if (nbm.urlName().equals(file)) {
                    if (which != null) {
                        if (nbm.rev() > which.rev()) {
                            System.out.println("  maybe download " + nbm);
                            which = nbm;
                        }
                    } else {
                        which = nbm;
                    }
                } else {
                    System.out.println("  it is not " + nbm);
                }
            }
            if (which == null) {
                notFound();
            } else {
                next(which);
            }
        }
    }

    static class DlHeadersActeur extends Acteur {

        @Inject
        DlHeadersActeur(DummyNBM target) {
            add(Headers.ETAG, target.name());
            add(Headers.LAST_MODIFIED, target.lastModified());
            add(Headers.CONTENT_TYPE, MediaType.OCTET_STREAM);
        }
    }

    static class DownloadActeur extends Acteur {

        DownloadActeur(DummyNBM target) throws IOException {
            ok(target.bytes());
        }
    }
}

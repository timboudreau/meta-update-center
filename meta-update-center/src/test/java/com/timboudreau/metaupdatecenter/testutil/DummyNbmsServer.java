package com.timboudreau.metaupdatecenter.testutil;

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.google.inject.TypeLiteral;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.ActeurFactory;
import com.mastfrog.acteur.Application;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.ImplicitBindings;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.bunyan.ActeurBunyanModule;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.server.ServerModule;
import static com.mastfrog.acteur.server.ServerModule.HTTP_COMPRESSION;
import static com.mastfrog.acteur.server.ServerModule.PORT;
import com.mastfrog.acteur.util.CacheControl;
import com.mastfrog.acteur.util.CacheControlTypes;
import com.mastfrog.acteur.util.Server;
import com.mastfrog.function.throwing.ThrowingQuadConsumer;
import com.mastfrog.giulius.Dependencies;
import com.mastfrog.giulius.bunyan.java.v2.LoggingModule;
import com.mastfrog.settings.Settings;
import com.mastfrog.url.Path;
import com.mastfrog.util.net.PortFinder;
import com.mastfrog.util.thread.OneThreadLatch;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import static io.netty.util.CharsetUtil.UTF_8;
import java.io.IOException;
import java.net.URISyntaxException;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A server which serves the NBM files from the adjacent test projects, so the
 * instance of meta update server we are testing can download them from
 * somewhere.
 *
 * @author Tim Boudreau
 */
@ImplicitBindings(TestProjectNBMs.class)
final class DummyNbmsServer extends Application {

    @Inject
    DummyNbmsServer(EnumSet<TestProjectNBMs> nbmsServed) {
        this.nbmsServed = nbmsServed;
        add(DownloadPage.class);
    }

    // Intentionally don't use annotations so we don't gum up
    // the application we're testing.
    static int start(PortFinder portFinder, ThrowingQuadConsumer<Server, Dependencies, EnumSet<TestProjectNBMs>, DummyNbmsServer> c) throws IOException, Exception {
        int port = portFinder.findAvailableServerPort();
        EnumSet<TestProjectNBMs> nbmsServed = EnumSet.noneOf(TestProjectNBMs.class);
        Settings settings = Settings.builder()
                .add(PORT, port)
                .add("application.name", "Dummy Server 1.0")
                .add(LoggingModule.SETTINGS_KEY_LOG_HOSTNAME, "dummyNbms")
                .add(LoggingModule.SETTINGS_KEY_LOG_LEVEL, "trace")
                .add(LoggingModule.SETTINGS_KEY_LOG_TO_CONSOLE, true)
                .add(HTTP_COMPRESSION, true)
                .build();
        Dependencies deps = Dependencies.builder()
                .add(settings)
                .add(new ActeurBunyanModule(true))
                .add(binder -> {
                    binder.bind(new TypeLiteral<EnumSet<TestProjectNBMs>>() {
                    }).toInstance(nbmsServed);
                    binder.install(new ServerModule<>(DummyNbmsServer.class));
                }).build();
        Server server = deps.getInstance(Server.class);
        DummyNbmsServer app = (DummyNbmsServer) deps.getInstance(Application.class);
        c.accept(server, deps, nbmsServed, app);
        return port;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("acteur.debug", "true");
        start(new PortFinder(8001, 65535, 100), (s, d, all, x) -> {
            all.add(TestProjectNBMs.MODULE_A_v1);
            all.add(TestProjectNBMs.MODULE_B_v1);
            System.out.println("Starting server on " + s.getPort());
            System.out.println("\nTry, e.g.");
            System.out.println("http://localhost:" + s.getPort() + "/nbms/" + TestProjectNBMs.MODULE_A_v1.urlName());
            s.start().await();
        });
    }
    private final EnumSet<TestProjectNBMs> nbmsServed;

    @Override
    protected HttpResponse createNotFoundResponse(Event<?> event) {
        StringBuilder msg = new StringBuilder();
        msg.append("Not found:\n\t")
                .append(event).append("\n\n")
                .append("Available:\n");
        for (TestProjectNBMs nbm : nbmsServed) {
            msg.append('\t').append(nbm.urlName()).append('\t').append(nbm.name());
        }
        msg.append('\n');
        ByteBuf buf = event.channel().alloc().ioBuffer(msg.length());
        buf.touch("dummy-nbms-server-create-not-found-response");
        buf.writeBytes(msg.toString().getBytes(UTF_8));
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.NOT_FOUND, buf);
        Headers.write(Headers.CONTENT_TYPE, MediaType.HTML_UTF_8.withCharset(UTF_8), resp);
        Headers.write(Headers.CONTENT_LENGTH, buf.writerIndex(), resp);
        Headers.write(Headers.CONTENT_LANGUAGE, Locale.ENGLISH, resp);
        Headers.write(Headers.CACHE_CONTROL, new CacheControl(CacheControlTypes.no_cache), resp);
        Headers.write(Headers.DATE, ZonedDateTime.now(), resp);
        String pth = ((HttpEvent) event).path().toString();
        Headers.write(Headers.stringHeader("X-Req-Path"), pth, resp);
        return resp;
    }

    static final class DownloadPage extends Page {

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

    static final class CheckExistsActeur extends Acteur {

        @Inject
        CheckExistsActeur(EnumSet<TestProjectNBMs> active, Path path) {
            String file = path.lastElement().toString();
            synchronized (active) {
                active = EnumSet.copyOf(active);
            }
            TestProjectNBMs which = null;
            for (TestProjectNBMs nbm : active) {
                if (nbm.urlName().equals(file)) {
                    if (which != null) {
                        if (nbm.rev() > which.rev()) {
                            which = nbm;
                        }
                    } else {
                        which = nbm;
                    }
                }
            }
            if (which == null) {
                notFound();
            } else {
                next(which);
            }
        }
    }

    static final class DlHeadersActeur extends Acteur {

        @Inject
        DlHeadersActeur(TestProjectNBMs target, HttpEvent evt) {
            add(Headers.ETAG, target.name());
            add(Headers.LAST_MODIFIED, target.lastModified());
            add(Headers.CONTENT_TYPE, MediaType.OCTET_STREAM);
            add(Headers.stringHeader("x-module-rev"), "" + target.rev());
            add(Headers.stringHeader("x-module-id"), "" + target.name());
            ZonedDateTime ims = evt.header(Headers.IF_MODIFIED_SINCE);
            next();
        }
    }

    static TestProjectNBMs lastTarget;
    static final OneThreadLatch latch = new OneThreadLatch();

    static void assertLastTarget(TestProjectNBMs expected) throws InterruptedException {
        TestProjectNBMs found = null;
        latch.await(10, TimeUnit.SECONDS);
        synchronized (DummyNbmsServer.class) {
            if (lastTarget != null) {
                found = lastTarget;
                lastTarget = null;
            }
        }
        assertNotNull(found, "No new download recorded");
        assertSame(found, expected);
    }

    static class DownloadActeur extends Acteur {

        @Inject
        DownloadActeur(TestProjectNBMs target, HttpEvent evt, ByteBufAllocator alloc) throws IOException, URISyntaxException {
            if (evt.method().is(HttpMethod.HEAD)) {
                ok();
                add(Headers.stringHeader("x-content-length"), "" + target.length());
            } else {
                add(Headers.CONTENT_LENGTH, target.length());
                byte[] bytes = target.bytes();
                ByteBuf buf = alloc.ioBuffer(bytes.length);
                buf.writeBytes(bytes);
                buf.touch("dummy-download-nbm");
                ok(buf);
            }
            evt.channel().closeFuture().addListener(f -> {
                if (f.cause() == null) {
                    synchronized (DummyNbmsServer.class) {
                        lastTarget = target;
                    }
                    latch.releaseAll();
                }
            });
        }
    }
}

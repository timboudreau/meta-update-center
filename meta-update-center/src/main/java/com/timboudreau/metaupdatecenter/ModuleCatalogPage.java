package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.CheckIfNoneMatchHeader;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.ResponseWriter;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.headers.HeaderValueType;
import com.mastfrog.acteur.server.PathFactory;
import static com.mastfrog.acteur.headers.Headers.CACHE_CONTROL;
import static com.mastfrog.acteur.headers.Headers.CONTENT_ENCODING;
import static com.mastfrog.acteur.headers.Headers.CONTENT_TYPE;
import static com.mastfrog.acteur.headers.Headers.ETAG;
import static com.mastfrog.acteur.headers.Headers.LAST_MODIFIED;
import static com.mastfrog.acteur.headers.Headers.VARY;
import com.mastfrog.acteur.headers.Method;
import com.mastfrog.acteur.preconditions.Description;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.PathRegex;
import com.mastfrog.acteur.header.entities.CacheControl;
import static com.mastfrog.acteur.header.entities.CacheControlTypes.Public;
import static com.mastfrog.acteur.header.entities.CacheControlTypes.max_age;
import static com.mastfrog.acteur.header.entities.CacheControlTypes.must_revalidate;
import com.mastfrog.mime.MimeType;
import static com.timboudreau.metaupdatecenter.ModuleCatalogPage.MODULE_PAGE_REGEX;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCounted;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.util.Iterator;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall
@Methods(Method.GET)
@PathRegex(MODULE_PAGE_REGEX)
@Description("Get the XML module catalog used by Tools | Plugins in NetBeans")
public class ModuleCatalogPage extends Page {

    public static final String MODULE_PAGE_REGEX = "^modules$";

    @Inject
    ModuleCatalogPage() {
        add(SetupETag.class);
        add(CheckIfNoneMatchHeader.class);
        add(ModuleListSender.class);
    }

    @Description("Sets the last modified date for the catalog")
    private static final class SetupLastModified extends Acteur {

        @Inject
        SetupLastModified(ModuleSet set, HttpEvent evt, Stats stats) {
            add(LAST_MODIFIED, set.getNewestDownloaded());
            MimeType contentType = "true".equals(evt.urlParameter("json")) ? MimeType.JSON_UTF_8 : MimeType.XML_UTF_8;
            add(CONTENT_TYPE, contentType);
            add(CACHE_CONTROL, new CacheControl(Public, must_revalidate).add(max_age, Duration.ofHours(1)));
            add(VARY, new HeaderValueType<?>[]{CONTENT_ENCODING});
            next();
        }
    }

    @Description("Sets the ETag heard field for the catalog")
    private static final class SetupETag extends Acteur {

        @Inject
        SetupETag(ModuleSet set, HttpEvent evt, Stats stats) {
            stats.logHit(evt);
            add(ETAG, set.getCombinedHash());
            next();
        }
    }

    @Description("Streams the list of available modules to the http socket")
    private static final class ModuleListSender extends Acteur {

        @Inject
        ModuleListSender(ModuleSet set, HttpEvent evt, ObjectMapper mapper, final PathFactory factory) throws JsonProcessingException {
            final Iterator<ModuleItem> items = set.iterator();
            final ZonedDateTime lm = set.getNewestDownloaded();
            setChunked(true);
            if ("true".equals(evt.urlParameter("json"))) {
                ok(mapper.writeValueAsString(set.toList()));
            } else {
                ok();
                if (evt.method() != Method.HEAD) {
                    setResponseWriter(new ResponseWriterImpl(lm, items, factory));
                }
            }
            HttpRequest req = evt.request();
            if (req instanceof ReferenceCounted) {
                // XXX figure out why this is needed
                ((ReferenceCounted) req).release();
            }
        }

        private static class ResponseWriterImpl extends ResponseWriter {

            private final ZonedDateTime lm;
            private final Iterator<ModuleItem> items;
            private final PathFactory factory;

            public ResponseWriterImpl(ZonedDateTime lm, Iterator<ModuleItem> items, PathFactory factory) {
                this.lm = lm;
                this.items = items;
                this.factory = factory;
            }

            @Override
            public Status write(Event<?> evt, ResponseWriter.Output out, int iteration) throws Exception {
                if (iteration == 0) {
                    String timestamp = lm.get(ChronoField.SECOND_OF_MINUTE) + "/" + lm.get(ChronoField.MINUTE_OF_HOUR) + "/"
                            + lm.get(ChronoField.HOUR_OF_DAY) + "/" + lm.get(ChronoField.DAY_OF_MONTH) + "/"
                            + lm.get(ChronoField.MONTH_OF_YEAR) + "/" + lm.getYear();
                    out.write("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"
                            + "<!DOCTYPE module_updates PUBLIC \"-//NetBeans//DTD Autoupdate Catalog 2.6//EN\" \"http://www.netbeans.org/dtds/autoupdate-catalog-2_6.dtd\">\n"
                            + "<module_updates timestamp=\"" + timestamp + "\">\n\n");
                }
                if (items.hasNext()) {
                    out.write(items.next().toXML(factory, "download"));
                }
                if (!items.hasNext()) {
                    out.write("</module_updates>\n\n");
                    out.channel().flush();
//                    out.future().addListener(ChannelFutureListener.CLOSE);
                }
                return items.hasNext() ? Status.NOT_DONE : Status.DONE;
            }
        }
    }
}

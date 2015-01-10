package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.ActeurFactory;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.ResponseWriter;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.server.PathFactory;
import com.mastfrog.acteur.util.CacheControlTypes;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.headers.Method;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.PathRegex;
import static com.timboudreau.metaupdatecenter.ModuleCatalogPage.MODULE_PAGE_REGEX;
import io.netty.channel.ChannelFutureListener;
import java.util.Iterator;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall
@Methods(Method.GET)
@PathRegex(MODULE_PAGE_REGEX)
public class ModuleCatalogPage extends Page {

    public static final String MODULE_PAGE_REGEX = "^modules$";

//    MAKE USING THE ORIGINAL URL OPTIONAL
//    ADD PUT PAGE
    @Inject
    ModuleCatalogPage(ActeurFactory af) {
        add(af.sendNotModifiedIfIfModifiedSinceHeaderMatches());
        add(SetupETag.class);
        add(af.sendNotModifiedIfETagHeaderMatches());
        add(ModuleListSender.class);
    }

    private static final class SetupLastModified extends Acteur {

        @Inject
        SetupLastModified(Page page, ModuleSet set, HttpEvent evt, Stats stats) {
            stats.logHit(evt);
            page.getResponseHeaders().setLastModified(set.getNewestDownloaded());
            page.getResponseHeaders().setContentType("true".equals(evt.getParameter("json")) ? MediaType.JSON_UTF_8 : MediaType.XML_UTF_8);
            page.getResponseHeaders().addCacheControl(CacheControlTypes.Public);
            page.getResponseHeaders().addCacheControl(CacheControlTypes.must_revalidate);
            page.getResponseHeaders().addCacheControl(CacheControlTypes.max_age, Duration.standardHours(1));
            page.getResponseHeaders().addVaryHeader(Headers.CONTENT_ENCODING);
            next();
        }
    }

    private static final class SetupETag extends Acteur {

        @Inject
        SetupETag(Page page, ModuleSet set) {
            page.getResponseHeaders().setETag(set.getCombinedHash());
            next();
        }
    }

    private static final class ModuleListSender extends Acteur {

        @Inject
        ModuleListSender(ModuleSet set, HttpEvent evt, ObjectMapper mapper, final PathFactory factory) throws JsonProcessingException {
            final Iterator<ModuleItem> items = set.iterator();
            final DateTime lm = set.getNewestDownloaded();
            setChunked(true);
            if ("true".equals(evt.getParameter("json"))) {
                ok(mapper.writeValueAsString(set.toList()));
            } else {
                ok();
                if (evt.getMethod() != Method.HEAD) {
                    setResponseWriter(new ResponseWriterImpl(lm, items, factory));
                }
            }
        }

        private static class ResponseWriterImpl extends ResponseWriter {

            private final DateTime lm;
            private final Iterator<ModuleItem> items;
            private final PathFactory factory;

            public ResponseWriterImpl(DateTime lm, Iterator<ModuleItem> items, PathFactory factory) {
                this.lm = lm;
                this.items = items;
                this.factory = factory;
            }

            @Override
            public Status write(Event<?> evt, ResponseWriter.Output out, int iteration) throws Exception {
                if (iteration == 0) {
                    String timestamp = lm.getSecondOfMinute() + "/" + lm.getMinuteOfHour() + "/" + lm.getHourOfDay() + "/" + lm.getDayOfMonth() + "/" + lm.getMonthOfYear() + "/" + lm.getYear();
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
                    out.future().addListener(ChannelFutureListener.CLOSE);
                }
                return items.hasNext() ? Status.NOT_DONE : Status.DONE;
            }
        }
    }
}

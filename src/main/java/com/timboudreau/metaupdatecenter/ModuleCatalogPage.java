package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.ActeurFactory;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.ResponseWriter;
import com.mastfrog.acteur.server.PathFactory;
import com.mastfrog.acteur.util.CacheControlTypes;
import com.mastfrog.acteur.util.Headers;
import com.mastfrog.acteur.util.Method;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultHttpRequest;
import java.util.Iterator;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 *
 * @author Tim Boudreau
 */
public class ModuleCatalogPage extends Page {

    public static final String MODULE_PAGE_REGEX = "^modules$";

//    MAKE USING THE ORIGINAL URL OPTIONAL
//    ADD PUT PAGE
    @Inject
    ModuleCatalogPage(ActeurFactory af) {
        add(af.matchMethods(Method.GET, Method.HEAD));
        add(af.matchPath(MODULE_PAGE_REGEX));
        add(SetupLastModified.class);
        add(af.sendNotModifiedIfIfModifiedSinceHeaderMatches());
        add(SetupETag.class);
        add(af.sendNotModifiedIfETagHeaderMatches());
        add(ModuleListSender.class);
    }

    private static final class SetupLastModified extends Acteur {

        @Inject
        SetupLastModified(Page page, ModuleSet set, Event evt) {
            page.getReponseHeaders().setLastModified(set.getNewestDownloaded());
            page.getReponseHeaders().setContentType("true".equals(evt.getParameter("json")) ? MediaType.JSON_UTF_8 : MediaType.XML_UTF_8);
            page.getReponseHeaders().addCacheControl(CacheControlTypes.Public);
            page.getReponseHeaders().addCacheControl(CacheControlTypes.must_revalidate);
            page.getReponseHeaders().addCacheControl(CacheControlTypes.max_age, Duration.standardDays(1));
            page.getReponseHeaders().addVaryHeader(Headers.CONTENT_ENCODING);
            setState(new ConsumedLockedState());
        }
    }

    private static final class SetupETag extends Acteur {

        @Inject
        SetupETag(Page page, ModuleSet set) {
            page.getReponseHeaders().setETag(set.getCombinedHash());
            setState(new ConsumedLockedState());
        }
    }

    private static final class ModuleListSender extends Acteur {

        @Inject
        ModuleListSender(ModuleSet set, Event evt, ObjectMapper mapper, final PathFactory factory) throws JsonProcessingException {
            
            System.out.println("PAREMETER: " + evt.getParametersAsMap());
            for ( String s : ((DefaultHttpRequest) evt.getRequest()).headers().names()){
                System.out.println(s + ": " + ((DefaultHttpRequest) evt.getRequest()).headers().getAll(s));
            }
            System.out.println("ADDR " + evt.getRemoteAddress());
            
            final Iterator<ModuleItem> items = set.iterator();
            final DateTime lm = set.getNewestDownloaded();
            setChunked(false);
            if ("true".equals(evt.getParameter("json"))) {
                ok(mapper.writeValueAsString(set.toList()));
            } else {
                ok();
                if (evt.getMethod() != Method.HEAD) {
                    setResponseWriter(new ResponseWriter() {
                        @Override
                        public Status write(Event evt, ResponseWriter.Output out, int iteration) throws Exception {
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
                    });
                }
            }
        }
    }
}

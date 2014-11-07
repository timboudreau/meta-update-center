package com.timboudreau.metaupdatecenter;

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.ActeurFactory;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.annotations.Precursors;
import com.mastfrog.acteur.server.PathFactory;
import com.mastfrog.acteur.util.CacheControlTypes;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.headers.Method;
import static com.mastfrog.acteur.headers.Method.GET;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.settings.Settings;
import com.mastfrog.url.Path;
import com.mastfrog.url.URL;
import com.timboudreau.metaupdatecenter.IndexPage.LogActeur;
import java.util.Iterator;
import org.joda.time.DateTime;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall(order=Integer.MAX_VALUE)
@Methods(GET)
@Precursors(LogActeur.class)
public class IndexPage extends Page {

    @Inject
    IndexPage(ActeurFactory af, DateTime serverStartTime) {
        add(af.sendNotModifiedIfIfModifiedSinceHeaderMatches());
        add(IndexActeur.class);
        getResponseHeaders().addCacheControl(CacheControlTypes.no_cache);
        getResponseHeaders().addCacheControl(CacheControlTypes.no_store);
        getResponseHeaders().setExpires(serverStartTime);

        getResponseHeaders().setContentType(MediaType.HTML_UTF_8);
        getResponseHeaders().setLastModified(serverStartTime);
    }
    
    static class LogActeur extends Acteur {
        @Inject
        LogActeur(Stats stats, HttpEvent evt) {
            stats.logWebHit(evt);
            setState(new ConsumedLockedState());
        }
    }

    private static class IndexActeur extends Acteur {

        @Inject
        IndexActeur(ModuleSet set, PathFactory paths, Settings settings, @Named(UpdateCenterServer.SETTINGS_KEY_SERVER_VERSION) int ver, DateTime serverStart) {
            final Iterator<ModuleItem> items = set.iterator();
            ok();
            StringBuilder sb = new StringBuilder();
            sb.append("<!doctype html><html><head><title>Modules</title>\n");
            sb.append("<style> .content { margin: 12px; } html{height:100%; font-family:'Verdana';}\n body{ margin: 0px;}\n td { vertical-align: top; text-align: left;}\n .header {background-color: #CCCCAA; padding: 12px;}\n tr { margin-bottom: 0.5em; border-bottom: 1px solid #CCCCCC;}\n code{background-color: #EDEDED; margin: 0.5em; font-size: 1.2em;}\n .odd { background-color: #FFFFEA; }\n td { border-left: solid 1px #BBBBBB; margin-left: 3px; margin-right: 3px; }</style>\n");
            sb.append("</head>\n<body>\n");
            sb.append("<div class='header'><h1>Update Center Server</h1>\n");
            sb.append("<font size='-1'><a target='other' href='https://github.com/timboudreau/meta-update-center'>MetaUpdateServer</a> 1.").append(ver).append(" online since ").append(serverStart.getMonthOfYear()).append('/').append(serverStart.getDayOfMonth()).append('/').append(serverStart.getYear()).append("</font><p/>\n");
            sb.append("</div>\n");
            sb.append("<div class='content'>\n");
                    
            sb.append("<p>This server is happily serving ").append(set.size()).append(" modules.\n");
            sb.append("To access it from the NetBeans Update Center, add this URL to the settings tab in the IDE: \n");
            sb.append("<code>").append(paths.constructURL(Path.parse("modules"), false)).append("</code> (or just download the update center module below).\n");

            sb.append("<table class='table'><tr><th>Name</th><th>Code Name</th><th>Description</th><th>Version</th><th>Updated</th><th>URLs</th></tr>\n");
            int ix = 0;
            while (items.hasNext()) {
                boolean odd = ix++ % 2 != 0;
                ModuleItem item = items.next();
                sb.append("<tr").append(odd ? " class='odd'" : "").append(">\n<th>").append(item.getName()).append("</th>\n");
                sb.append("  <td>").append(item.getCodeNameBase()).append("</td>\n");
                sb.append("  <td>").append(item.getDescription()).append("</td>\n");
                sb.append("  <td>").append(item.getVersion()).append("</td>\n");
                sb.append("  <td>").append(Headers.toISO2822Date(item.getDownloaded())).append("</td>\n");
                if (!UpdateCenterServer.DUMMY_URL.equals(item.getFrom())) {
                    sb.append("  <td><a href=\"").append(item.getFrom()).append("\">").append("Link to Original").append("</a>").append(" &middot; \n");
                } else {
                    sb.append("  <td>\n");
                }
                URL u = paths.constructURL(Path.builder().add("download").add(item.getCodeNameBase()).add(item.getHash() + ".nbm").create(), false);
                sb.append("<a href=\"").append(u).append("\">").append("Cached Copy").append("</a>").append("</td></tr>\n");
            }
            sb.append("</table>\n");
            sb.append("<hr>\n<h2>Add A Module URL</h2><p>Authentication Required</p>\n");

            URL addUrl = paths.constructURL(Path.parse("add"), false);

            sb.append("<form name='add' method='GET' action='").append(addUrl).append("'>\n");
            sb.append("<input type='text' name='url'></input>\n");
            sb.append("<input type='checkbox' name='useOriginalURL' value='false'>Serve remote, not cached URL to clients</input>\n");
            sb.append("<input type='submit' name='submit'></input>\n");
            sb.append("</form>\n");
            sb.append("</div>\n");
            sb.append("</body></html>");

            ok(sb.toString());
//            setResponseWriter(new ResponseWriter() {
//
//                @Override
//                public ResponseWriter.Status write(Event evt, ResponseWriter.Output out, int iteration) throws Exception {
//                    if (iteration == 0) {
//                        out.write("<!doctype html><html><head><title>Modules</title></head>\n<body>\n");
//                        out.write("<h1>Update Center Server</h1>\n");
//                        out.write("This server is happily serving\n");
//                        out.write("<table><tr><th>Name</th><th>Code Name</th><th>Description</th><th>Updated</th><th>URL</th></tr>\n");
//                    }
//                    if (!items.hasNext()) {
//                        out.write("</table>\n</body></html>\n");
//                        return Status.DONE;
//                    } else {
//                        ModuleItem item = items.next();
//
//                        out.write("<tr><td>").write(item.getName()).write("</td>\n");
//                        out.write("<td>").write(item.getCodeNameBase()).write("</td>\n");
//                        out.write("<td>").write(item.getDescription()).write("</td>\n");
//                        out.write("<td>").write(item.getDownloaded() + "").write("</td>\n");
//                        out.write("<td>").write(item.getFrom()).write("</td></tr>\n");
//                        out.channel().flush();
//                        return Status.NOT_DONE;
//                    }
//                }
//
//            });
        }
    }
}

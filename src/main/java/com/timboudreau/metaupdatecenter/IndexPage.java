package com.timboudreau.metaupdatecenter;

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.ActeurFactory;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.server.PathFactory;
import com.mastfrog.acteur.util.CacheControlTypes;
import static com.mastfrog.acteur.headers.Method.GET;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.settings.Settings;
import com.mastfrog.url.Path;
import com.mastfrog.url.URL;
import com.timboudreau.metaupdatecenter.IndexPage.EtagGenActeur;
import com.timboudreau.metaupdatecenter.IndexPage.LogActeur;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED;
import java.util.Iterator;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.format.DateTimeFormat;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall(order = Integer.MAX_VALUE)
@Methods(GET)
@com.mastfrog.acteur.preconditions.Path({"", "/", "index.html"})
public class IndexPage extends Page {

    @Inject
    IndexPage(ActeurFactory af, DateTime serverStartTime) {
        add(LogActeur.class);
        add(af.sendNotModifiedIfIfModifiedSinceHeaderMatches());
        add(EtagGenActeur.class);
        add(IndexActeur.class);
        getResponseHeaders().addCacheControl(CacheControlTypes.Public);
        getResponseHeaders().addCacheControl(CacheControlTypes.max_age, Duration.standardDays(1));
        getResponseHeaders().setExpires(serverStartTime);
        getResponseHeaders().setContentType(MediaType.HTML_UTF_8);
    }

    static class LogActeur extends Acteur {

        @Inject
        LogActeur(Stats stats, HttpEvent evt) {
            stats.logWebHit(evt);
            setState(new ConsumedLockedState());
        }
    }

    static class EtagGenActeur extends Acteur {

        @Inject
        EtagGenActeur(ModuleSet set, DateTime serverStart, Page page, HttpEvent evt) {
            StringBuilder sb = new StringBuilder();
            sb.append(Long.toString(serverStart.getMillis(), 36));
            for (ModuleItem item : set) {
                sb.append(Integer.toString(item.getCodeNameBase().hashCode(), 36));
            }
            String etag = sb.toString();
            String sent = evt.getHeader(Headers.IF_NONE_MATCH);
            if (sent != null && etag.equals(sent)) {
                setState(new RespondWith(NOT_MODIFIED));
            } else {
                add(Headers.ETAG, etag);
                setState(new ConsumedLockedState());
            }
        }
    }

    private static class IndexActeur extends Acteur {

        String instructions = "To access it from NetBeans, open <b>Tools | Plugins</b>.  On the settings tab, "
                + "click <b>Add</b> (middle right), and enter <code>__URL__</code>.";

        @Inject
        IndexActeur(ModuleSet set, PathFactory paths, Settings settings, @Named(UpdateCenterServer.SETTINGS_KEY_SERVER_VERSION) int ver, DateTime serverStart) {
            ok();
            StringBuilder sb = new StringBuilder();
            sb.append("<!doctype html><html><head><title>NetBeans Plugins</title>\n");
            sb.append("<style>.tophead { font-size: 1.3em; border-top: 1px #AAAAAA solid; border-bottom: 1px #AAAAAA solid; margin-top: 2.5em;} .table { min-height: 50%;} h1 { color: #222222; } .content { margin: 12px; } html{height:100%; font-family:'Helvetica'; color: #444441}\n body{ margin: 0px;}\n td { vertical-align: top; text-align: left;}\n .header {background-color: #EEEEFF; padding: 12px; color: #999991; border-bottom: #AAAAAA solid 1px;}\n tr { margin-bottom: 0.5em; border-bottom: 1px solid #CCCCCC; min-height: 5em;}\n code{background-color: #EDEDED; margin: 0.5em; font-size: 1.2em;}\n .odd { background-color: #F3F3FF; }\n td { border-left: solid 1px #BBBBBB; margin-left: 3px; margin-right: 3px; padding: 5px; }</style>\n");
            sb.append("</head>\n<body>\n");
            String name = settings.getString("server.name", "Update Center Server");
            sb.append("<div class='header'><h1>").append(name).append("</h1>\n");
            sb.append("<font size='-1'><a target='other' href='https://github.com/timboudreau/meta-update-center'>MetaUpdateServer</a> 1.").append(ver).append(" online since ").append(serverStart.getMonthOfYear()).append('/').append(serverStart.getDayOfMonth()).append('/').append(serverStart.getYear()).append("</font><p/>\n");
            sb.append("</div>\n");
            sb.append("<div class='content'>\n");

            ModuleItem ucModule = null;
            for (ModuleItem item : set) {
                if (UpdateCenterServer.DUMMY_URL.equals(item.getFrom())) {
                    ucModule = item;
                    break;
                }
            }

            sb.append("<p>This server is happily serving ").append(set.size()).append(" plugins.</p>\n");

            if (ucModule != null) {
                URL u = paths.constructURL(Path.builder().add("download").add(ucModule.getCodeNameBase()).add(ucModule.getHash() + ".nbm").create(), false);
                sb.append("<p>To use these plugins in NetBeans, download <a href='").append(u).append("'>this plugin</a> and install it on "
                        + "the <b>Download</b> tab in <b>Tools | Plugins</b>. Then go to the <b>Available</b> tab and click <b>Check For Newest</b> "
                        + "and these will be there, and you will be notified of updates automatically.</p>");
                sb.append("<p>Alternately, you can download any plugin individually and install it in the same place.  ");
                sb.append("Or, open <b>Tools | Plugins</b>, and on the <b>Settings</b> tab, \n"
                        + " click <b>Add</b> (middle right), and enter \n");
                sb.append("<code>").append(paths.constructURL(Path.parse("modules"), false)).append("</code> (which is what the "
                        + "update center plugin does).</p>\n");
            } else {
                // ??
                sb.append("To access it from NetBeans, open <b>Tools | Plugins</b>.  On the settings tab, \n"
                        + "                + \"click <b>Add</b> (middle right), and enter \n");
                sb.append("<code>").append(paths.constructURL(Path.parse("modules"), false)).append("</code> (or just download the update center plugin below, and add that on the <b>Downloaded</b> tab).\n");
            }

//            sb.append("<table class='table'><tr><th>Name</th><th>Code Name</th><th>Description</th><th>Version</th><th>Updated</th><th>URLs</th></tr>\n");
            sb.append("<table class='table'><tr><th class='tophead'>Name</th><th class='tophead'>Description</th><th class='tophead'>Version</th><th class='tophead'>Updated</th><th class='tophead'>Download</th></tr>\n");
            int ix = 0;
            final Iterator<ModuleItem> items = set.sorted().iterator();
            while (items.hasNext()) {
                boolean odd = ix++ % 2 != 0;
                ModuleItem item = items.next();
                sb.append("<tr style='min-height:5em;'").append(odd ? " class='odd'" : "").append(">\n<th style='vertical-align: middle; text-align: left'>").append(item.getName()).append("</th>\n");
//                sb.append("  <td valign='middle'>").append(item.getCodeNameBase()).append("</td>\n");
                sb.append("  <td style='vertical-align: middle; margin: 5px;'>").append(item.getDescription()).append("</td>\n");
                sb.append("  <td style='vertical-align: middle; margin: 5px;'>").append(item.getVersion()).append("</td>\n");
                sb.append("  <td style='vertical-align: middle; margin: 5px;'>").append(DateTimeFormat.mediumDateTime().print(item.getDownloaded()).replaceAll(" ", "&nbsp;")).append("</td>\n");
                URL u = paths.constructURL(Path.builder().add("download").add(item.getCodeNameBase()).add(item.getHash() + ".nbm").create(), false);

                if (!UpdateCenterServer.DUMMY_URL.equals(item.getFrom())) {
                    sb.append("  <td style='vertical-align: middle; margin: 5px;'>")
                            .append("<a href=\"").append(u).append("\">").append("Download").append("</a>")
                            .append(" <p/> \n")
                            .append("<a href=\"").append(item.getFrom()).append("\">").append("Link to Original").append("</a>")
                            .append("</td></tr>\n");
                } else {
                    sb.append("  <td style='vertical-align: middle; margin: 5px;'><p>&nbsp;</p>\n")
                            .append("<a href=\"").append(u).append("\">").append("Download").append("</a>");
                }
                sb.append("</td></tr>\n");
            }
            sb.append("</table>\n");
            sb.append("<hr>\n<h2>Add A Plugin URL</h2><p>Authentication Required</p>\n");

            URL addUrl = paths.constructURL(Path.parse("add"), false);

            sb.append("<form name='add' method='GET' action='").append(addUrl).append("'>\n");
            sb.append("<input type='text' name='url'></input>\n");
            sb.append("<input type='checkbox' name='useOriginalURL' value='false'>Serve remote, not cached URL to clients</input>\n");
            sb.append("<input type='submit' name='submit'></input>\n");
            sb.append("</form>\n");
            sb.append("</div>\n");
            sb.append("</body></html>");

            ok(sb.toString());
        }
    }
}

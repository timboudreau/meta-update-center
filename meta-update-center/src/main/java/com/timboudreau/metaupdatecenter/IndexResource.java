package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.CheckIfNoneMatchHeader;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.annotations.Precursors;
import com.mastfrog.acteur.headers.Headers;
import static com.mastfrog.acteur.headers.Headers.CACHE_CONTROL;
import com.mastfrog.acteur.server.PathFactory;
import static com.mastfrog.acteur.headers.Method.GET;
import com.mastfrog.acteur.preconditions.Description;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.util.CacheControl;
import com.mastfrog.settings.Settings;
import com.mastfrog.url.Path;
import com.mastfrog.url.URL;
import com.mastfrog.util.libversion.VersionInfo;
import static com.mastfrog.util.strings.Strings.charSequencesEqual;
import com.mastfrog.util.time.TimeUtil;
import com.timboudreau.metaupdatecenter.IndexResource.EtagGenActeur;
import com.timboudreau.metaupdatecenter.IndexResource.LogActeur;
import io.netty.handler.codec.http.HttpRequest;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED;
import io.netty.util.ReferenceCounted;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import static java.time.temporal.ChronoField.DAY_OF_MONTH;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.YEAR;
import java.util.Iterator;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall(order = Integer.MAX_VALUE)
@Methods(GET)
@com.mastfrog.acteur.preconditions.Path({"", "/", "index.html"})
@Description("The home page")
@Precursors({LogActeur.class, EtagGenActeur.class, CheckIfNoneMatchHeader.class})
public class IndexResource extends Acteur {

    static DateTimeFormatter FMT;

    static {
        FMT = new DateTimeFormatterBuilder().appendValue(MONTH_OF_YEAR, 2)
                .appendLiteral("/").appendValue(DAY_OF_MONTH, 2).appendLiteral("/")
                .appendValue(YEAR, 4).appendLiteral(" ").appendValue(HOUR_OF_DAY, 2)
                .appendLiteral(":").appendValue(MINUTE_OF_HOUR, 2).toFormatter();
    }

    @Description("Logs the request")
    static class LogActeur extends Acteur {

        @Inject
        LogActeur(Stats stats, HttpEvent evt) {
            stats.logWebHit(evt);
            next();
        }
    }

    @Description("Generates the ETag header field for the home page")
    static class EtagGenActeur extends Acteur {

        @Inject
        EtagGenActeur(ModuleSet set, ZonedDateTime serverStart, HttpEvent evt) {
            add(CACHE_CONTROL, CacheControl.PUBLIC_MUST_REVALIDATE_MAX_AGE_1_DAY);
            StringBuilder etag = new StringBuilder();
            etag.append(Long.toString(TimeUtil.toUnixTimestamp(serverStart), 36));
            for (ModuleItem item : set) {
                etag.append(Integer.toString(item.getCodeNameBase().hashCode(), 36));
            }
            CharSequence sent = evt.header(Headers.IF_NONE_MATCH);
            if (sent != null && charSequencesEqual(etag, sent)) {
                reply(NOT_MODIFIED);
            } else {
                add(Headers.ETAG, etag);
                next();
            }
        }
    }

    String instructions = "To access it from NetBeans, open <b>Tools | Plugins</b>.  On the settings tab, "
            + "click <b>Add</b> (middle right), and enter <code>__URL__</code>.";

    @Inject
    IndexResource(ModuleSet set, PathFactory paths, Settings settings, VersionInfo version, ZonedDateTime serverStart, HttpEvent evt) {
        ok();
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><title>NetBeans Plugins</title>\n");
        sb.append("<style>@import url(//fonts.googleapis.com/css?family=Sanchez|Montserrat);"
                + ".tophead { font-size: 1.3em; border-top: 1px #AAAAAA solid; border-bottom: 1px #AAAAAA solid; margin-top: 2.5em;} "
                + ".table { min-height: 50%; min-width: 100%; width: 100%;} "
                + "h1, h2, h3, h4{ color: #222222; font-family: 'Montserrat';} "
                + ".content { margin: 12px; } "
                + "html{height:100%; font-family:'Montserrat'; color: #644}\n "
                + "body{ margin: 0px;}\n "
                + "body{ margin: 0px; font-family: 'Sanchez'}\n "
                + "td { vertical-align: top;"
                + "text-align: left;}\n "
                + "a { text-decoration: none; color: #3333AA; }"
                + "body > div.content > table > tbody > tr > td > a, body > div.content > table > tbody > tr > td > p > a { margin-top: 5px; text-align: center; min-width: 10em; padding: 0.5em; background-color: #EEEEFF; display: inline-block; border: 1px solid #CCCCCC; border-radius: 1em; }"
                + ".header {background-color: #EFEFFF; padding: 12px; color: #999991; border-bottom: #AAAAAA solid 1px;}\n "
                + "tr { margin-bottom: 0.5em; border-bottom: 1px solid #CCCCCC; min-height: 5em;}\n "
                + "code{background-color: #EDEDED; margin: 0.5em; font-size: 1.2em;}\n "
                + ".odd { background-color: #e8e8ef; }\n"
                + ".even { background-color: #FEFEFE; }\n"
                + " td { border-left: solid 1px #BBBBBB; margin-left: 3px; margin-right: 3px; padding: 5px; }</style>\n");
        sb.append("</head>\n<body>\n");
        String name = settings.getString("server.name", "Update Center Server");
        sb.append("<div class='header'><h1>").append(name).append("</h1>\n");
        sb.append("<font size='-1'><a target='other' href='https://github.com/timboudreau/meta-update-center'>MetaUpdateServer</a> ")
                .append(version.version)
                .append(" online since ")
                .append(serverStart.get(MONTH_OF_YEAR))
                .append('/')
                .append(serverStart.getDayOfMonth())
                .append('/')
                .append(serverStart.getYear())
                .append(", serving ").append(set.size()).append(" plugins.")
                .append("</font><p/>\n");
        sb.append("</div>\n");
        sb.append("<div class='content'>\n");

        ModuleItem ucModule = null;
        for (ModuleItem item : set) {
            if (UpdateCenterServer.DUMMY_URL.equals(item.getFrom())) {
                ucModule = item;
                break;
            }
        }

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

        sb.append("<table class='table'><tr><th class='tophead'>Name</th><th class='tophead'>Description</th><th class='tophead'>Version</th><th class='tophead'>Updated</th><th class='tophead'>Download</th></tr>\n");
        int ix = 0;
        final Iterator<ModuleItem> items = set.sorted().iterator();
        while (items.hasNext()) {
            boolean odd = ix++ % 2 != 0;
            ModuleItem item = items.next();
            sb.append("<tr style='min-height:5em;'").append(odd ? " class='odd'" : "class='even'").append(">\n<th style='vertical-align: middle; text-align: left'>").append(item.getName()).append("</th>\n");
            sb.append("  <td style='vertical-align: middle; margin: 5px;'>").append(item.getDescription()).append("</td>\n");
            sb.append("  <td style='vertical-align: middle; margin: 5px;'>").append(item.getVersion()).append("</td>\n");
            sb.append("  <td style='vertical-align: middle; margin: 5px;'>").append(FMT.format(item.getWhen()).replaceAll(" ", "&nbsp;")).append("</td>\n");
            URL u = paths.constructURL(Path.builder().add("download").add(item.getCodeNameBase()).add(item.getHash() + ".nbm").create(), false);

            if (!UpdateCenterServer.DUMMY_URL.equals(item.getFrom())) {
                sb.append("  <td style='vertical-align: middle; margin: 5px;'>")
                        .append("<a href=\"").append(u).append("\">").append("Download").append("</a>");
                String from = item.getFrom();
                if (from != null && !from.isEmpty() && !from.startsWith("file")) {
                    sb.append(" <p/> \n").append("<a href=\"").append(item.getFrom())
                            .append("\">").append("Link to Original").append("</a>");
                }
                sb.append("</td></tr>\n");
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
        HttpRequest req = evt.request();
        if (req instanceof ReferenceCounted) {
            // XXX figure out why this is needed
            ((ReferenceCounted) req).release();
        }
    }
}

package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.ActeurFactory;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.util.CacheControlTypes;
import static com.mastfrog.acteur.headers.Method.GET;
import static com.mastfrog.acteur.headers.Method.HEAD;
import com.mastfrog.acteur.preconditions.Description;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.PathRegex;
import com.mastfrog.url.Path;
import static com.timboudreau.metaupdatecenter.DownloadPage.DOWNLOAD_REGEX;
import org.joda.time.Duration;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall
@Methods({GET, HEAD})
@PathRegex(DOWNLOAD_REGEX)
@Description("Download a module")
public class DownloadPage extends Page {

    static final int BUFFER_SIZE = 1490;

    public static final String DOWNLOAD_REGEX = "^download/.*?/.*\\.nbm";

    @Inject
    public DownloadPage(ActeurFactory af) {
        add(FindModuleItem.class);
        getResponseHeaders().addCacheControl(CacheControlTypes.Public);
        getResponseHeaders().addCacheControl(CacheControlTypes.max_age, Duration.standardDays(120));
        getResponseHeaders().addCacheControl(CacheControlTypes.must_revalidate);
        add(af.sendNotModifiedIfIfModifiedSinceHeaderMatches());
        add(af.sendNotModifiedIfETagHeaderMatches());
        add(DownloadActeur.class);
    }

    private static class FindModuleItem extends Acteur {

        @Inject
        FindModuleItem(ModuleSet ms, HttpEvent evt, Page page, Stats stats) {
            Path pth = evt.getPath();
            String codeName = pth.getElement(1).toString();
            String hash = pth.getElement(2).toString();
            hash = hash.substring(0, hash.length() - 4);
            ModuleItem item = ms.find(codeName, hash);
            if (item == null) {
                stats.logFailedDownload(evt, codeName, hash);
                notFound("No such file " + hash + ".nbm");
                return;
            } else {
                page.getResponseHeaders().setLastModified(item.getDownloaded());
                page.getResponseHeaders().setETag(hash);
            }
            next(item);
            stats.logDownload(evt, item);
        }
    }
}

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.util.CacheControl;
import com.mastfrog.acteur.util.CacheControlTypes;
import com.mastfrog.url.Path;
import java.time.Duration;

/**
 *
 * @author Tim Boudreau
 */
class FindModuleItem extends Acteur {

    @Inject
    FindModuleItem(ModuleSet ms, HttpEvent evt, Page page, Stats stats) {
        Path pth = evt.path();
        String codeName = pth.getElement(1).toString();
        String hash = pth.getElement(2).toString();
        hash = hash.substring(0, hash.length() - 4);
        ModuleItem item = ms.find(codeName, hash);
        if (item == null) {
            stats.logFailedDownload(evt, codeName, hash);
            notFound("No such file " + hash + ".nbm");
            return;
        } else {
            add(Headers.LAST_MODIFIED, item.getDownloaded());
            add(Headers.ETAG, hash);
        }
        add(Headers.CACHE_CONTROL, new CacheControl(CacheControlTypes.Public, CacheControlTypes.must_revalidate).add(CacheControlTypes.max_age, Duration.ofDays(120)));
        next(item);
        stats.logDownload(evt, item);
    }

}

package com.timboudreau.metaupdatecenter;

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.Closables;
import com.mastfrog.acteur.annotations.HttpCall;
import static com.mastfrog.acteur.headers.Headers.CONTENT_TYPE;
import static com.mastfrog.acteur.headers.Method.GET;
import com.mastfrog.acteur.preconditions.Authenticated;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.Path;
import com.mastfrog.bunyan.LoggingModule;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_HTTP_LOG_ENABLED;
import java.io.File;
import java.io.FileNotFoundException;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall(scopeTypes = File.class)
@Path("/log")
@Authenticated
@Methods(GET)
public class LogPage extends Acteur {

    @Inject
    LogPage(@Named(LoggingModule.SETTINGS_KEY_LOG_FILE) String logFile, Closables clos, @Named(SETTINGS_KEY_HTTP_LOG_ENABLED) boolean enabled) throws FileNotFoundException {
        if (!enabled) {
            notFound();
            return;
        }
        File f = new File(logFile);
        setChunked(true);
        if (!f.exists() || !f.isFile() || !f.canRead()) {
            super.notFound(f.getAbsolutePath());
        } else {
            ok();
            setResponseWriter(new ChunkedFileResponseWriter(f, clos));
            add(CONTENT_TYPE, MediaType.JSON_UTF_8);
        }
    }
}

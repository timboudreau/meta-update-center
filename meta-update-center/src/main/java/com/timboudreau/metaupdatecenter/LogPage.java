package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.Closables;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.annotations.Precursors;
import static com.mastfrog.acteur.headers.Headers.CONTENT_TYPE;
import static com.mastfrog.acteur.headers.Method.GET;
import com.mastfrog.acteur.preconditions.Authenticated;
import com.mastfrog.acteur.preconditions.Description;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.Path;
import static com.mastfrog.giulius.bunyan.java.v2.LoggingModule.SETTINGS_KEY_LOG_FILE;
import com.mastfrog.mime.MimeType;
import com.timboudreau.metaupdatecenter.LogPage.CheckLogEnabled;
import com.timboudreau.metaupdatecenter.LogPage.CheckLogFileReadable;
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
@Precursors({CheckLogEnabled.class, CheckLogFileReadable.class})
@Description("Get the server log")
public class LogPage extends Acteur {

    @Inject
    LogPage(Closables clos, @Named(SETTINGS_KEY_HTTP_LOG_ENABLED) boolean enabled) throws FileNotFoundException {
        setChunked(true);
        ok();
        add(CONTENT_TYPE, MimeType.JSON_UTF_8);
        setResponseWriter(ChunkedFileResponseWriter.class);
    }

    @Description("Check that server is configured to serve logs via HTTP")
    static class CheckLogEnabled extends Acteur {
        @Inject
        CheckLogEnabled(@Named(SETTINGS_KEY_HTTP_LOG_ENABLED) boolean enabled) {
            if (enabled) {
                next();
            } else {
                notFound();
                return;
            }
        }
    }

    @Description("Check that the server is configured to log to a file")
    static class CheckLogFileReadable extends Acteur {
        @Inject
        CheckLogFileReadable(@Named(SETTINGS_KEY_LOG_FILE) String logFile) {
            File f = new File(logFile);
            if (!f.exists() || !f.isFile() || !f.canRead()) {
                super.notFound(f.getAbsolutePath());
            } else {
                next(f);
            }
        }
    }
}

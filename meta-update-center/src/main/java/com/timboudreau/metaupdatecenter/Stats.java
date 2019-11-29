package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.util.BasicCredentials;
import com.mastfrog.acteur.util.RequestID;
import com.mastfrog.bunyan.Log;
import com.mastfrog.bunyan.Logger;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.STATS_LOGGER;
import java.io.IOException;

/**
 *
 * @author Tim Boudreau
 */
@Singleton
public class Stats {

    private final Logger statsLog;
    private final Logger requestLog;
    private final Logger downloadLog;
    private final Provider<RequestID> id;

    @Inject
    public Stats(@Named(STATS_LOGGER) Logger statsLog, @Named(UpdateCenterServer.REQUESTS_LOGGER) Logger requestLog, @Named(UpdateCenterServer.DOWNLOAD_LOGGER) Logger downloadLog, Provider<RequestID> id) throws IOException {
        this.statsLog = statsLog;
        this.requestLog = requestLog;
        this.downloadLog = downloadLog;
        this.id = id;
    }

    public void logWebHit(HttpEvent evt) {
        try (Log<?> log = statsLog.info("homepage")) {
            log.add("id", id.get())
                    .add(evt);
        }
    }

    public void logIngest(ModuleItem item) {
        try (Log<?> log = downloadLog.info("ingest")) {
            log.add("cnb", item.getCodeNameBase())
                    .add("url", item.getFrom())
                    .add("version", item.getVersion().toString())
                    .add("hash", item.getHash());
        }
    }

    public void logInvalidCredentials(BasicCredentials creds, HttpEvent evt) {
        try (Log<?> log = requestLog.warn("loginFail")) {
            log.add("un", creds.username)
                    .add("pw", creds.password)
                    .add("id", id.get())
                    .add(evt);
        }
    }

    public void logHit(HttpEvent evt) {
        try (Log<?> log = statsLog.info("catalog")) {
            log.add(evt)
                    .add("id", id.get());
        }
    }

    public void logDownload(HttpEvent evt, ModuleItem item) {
        try (Log<?> log = downloadLog.info("download")) {
            log.add("cnb", item.getCodeNameBase())
                    .add("id", id.get())
                    .add("version", item.getVersion().toString())
                    .add("hash", item.getHash())
                    .add(evt);
        }
    }

    public void logFailedDownload(HttpEvent evt, String codeName, String hash) {
        try (Log<?> log = downloadLog.warn("downloadFail")) {
            log.add("cnb", codeName)
                    .add("hash", hash)
                    .add(evt);

        }
    }

    void logNotFound(HttpEvent evt) {
        try (Log<?> log = requestLog.info("notfound")) {
            log.add(evt);
        }
    }
}

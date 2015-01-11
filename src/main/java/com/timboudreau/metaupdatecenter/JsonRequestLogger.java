package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.RequestLogger;
import com.mastfrog.acteur.util.RequestID;
import com.mastfrog.bunyan.Log;
import com.mastfrog.bunyan.Logger;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 *
 * @author Tim Boudreau
 */
@Singleton
public class JsonRequestLogger implements RequestLogger {

    private final Logger logger;

    @Inject
    JsonRequestLogger(@Named(UpdateCenterServer.REQUESTS_LOGGER) Logger logger) {
        this.logger = logger;
    }

    @Override
    public void onBeforeEvent(RequestID rid, Event<?> event) {
    }

    @Override
    public void onRespond(RequestID rid, Event<?> event, HttpResponseStatus status) {
        HttpEvent evt = (HttpEvent) event;
        try (Log<?> log = logger.debug("request")) {
            log.add("id", rid.stringValue())
                    .add("method", evt.getMethod().name())
                    .add("address", evt.getRemoteAddress().toString())
                    .add("path", evt.getPath().toString())
                    .add("status", status.code())
                    .add("dur", rid.getDuration().getMillis());
        }
    }
}

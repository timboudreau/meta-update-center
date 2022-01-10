/*
 * The MIT License
 *
 * Copyright 2022 Mastfrog Technologies.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.annotations.HttpCall;
import static com.mastfrog.acteur.headers.Method.POST;
import com.mastfrog.acteur.preconditions.Description;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.Path;
import com.mastfrog.acteur.preconditions.RequiredUrlParameters;
import com.mastfrog.settings.Settings;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_TICKLE_TOKEN;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.TOO_MANY_REQUESTS;
import java.util.Objects;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall
@Path("/tickle")
@RequiredUrlParameters("token")
@Methods(POST)
@Description("Force an immediate attempt to download updated versions of all modules")
public class TicklePage extends Acteur {

    @Inject
    TicklePage(HttpEvent evt, Settings settings, Poller poller) {
        String token = settings.getString(SETTINGS_KEY_TICKLE_TOKEN);
        if (token == null) {
            reply(NOT_FOUND, "Tickle not enabled.\n");
        } else {
            String received = evt.urlParameter("token");
            if (!Objects.equals(token, received)) {
                badRequest("Incorrect token.\n");
            } else {
                boolean polling = poller.pollNow();
                if (polling) {
                    ok("Poll scheduled immediately.\n");
                } else {
                    reply(TOO_MANY_REQUESTS, "Already polling or too recently tickled.\n");
                }
            }
        }
    }
}

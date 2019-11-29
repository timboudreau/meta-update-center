/*
 * The MIT License
 *
 * Copyright 2019 Mastfrog Technologies.
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

package com.timboudreau.metaupdatecenter.testutil;

import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.debug.HttpProbe;
import com.mastfrog.acteur.util.RequestID;
import com.mastfrog.acteurbase.ActeurState;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.ReferenceCounted;

class ProbeImpl extends HttpProbe {

    // For debugging issues in requests

    @Override
    protected boolean isEnabled() {
        return true;
    }

    @Override
    protected void onThrown(RequestID id, HttpEvent evt, Throwable thrown) {
        thrown.printStackTrace();
    }

    @Override
    protected void onBeforeSendResponse(RequestID id, HttpEvent httpEvent, Acteur acteur, HttpResponseStatus status, boolean hasListener, Object message) {
        HttpRequest req = httpEvent.request();
        if (req instanceof ReferenceCounted) {
            ((ReferenceCounted) req).touch(httpEvent.path() + "-send-response-" + acteur);
        }
        System.out.println("  * onBeforeSendResponse " + message + " for " + httpEvent.path() + " acteur " + acteur + " status " + status);
    }

    @Override
    protected void onActeurWasRun(RequestID id, HttpEvent evt, Page page, Acteur acteur, ActeurState result) {
        HttpRequest req = evt.request();
        if (req instanceof ReferenceCounted) {
            ((ReferenceCounted) req).touch(evt.path() + "-" + acteur);
        }
        System.out.println("  * acteur was run: " + acteur + " result " + result);
    }

    @Override
    protected void onBeforeRunPage(RequestID id, HttpEvent evt, Page page) {
        System.out.println("  * before run page " + page);
    }

    @Override
    protected void onBeforeProcessRequest(RequestID id, HttpEvent req) {
        System.out.println("  * on before process request: " + req.path() + "  " + req.headersAsMap());
    }

}

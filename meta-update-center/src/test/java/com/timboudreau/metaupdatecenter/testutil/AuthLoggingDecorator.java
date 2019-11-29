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

import com.google.inject.Inject;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.Page;
import com.mastfrog.acteur.Response;
import com.mastfrog.acteur.auth.AuthenticateBasicActeur;
import com.mastfrog.acteur.util.Realm;
import java.util.HashMap;

/**
 *
 * @author Tim Boudreau
 */
final class AuthLoggingDecorator implements AuthenticateBasicActeur.AuthenticationDecorator {

    // For debugging login problems in tests
    private final Realm realm;
    private final com.mastfrog.acteur.auth.Authenticator auth;

    @Inject
    AuthLoggingDecorator(com.mastfrog.acteur.auth.Authenticator auth, Realm realm) {
        this.auth = auth;
        this.realm = realm;
    }

    @Override
    public void onAuthenticationSucceeded(HttpEvent evt, Page page, Response response, Object[] stuff) {
        System.out.println("    * AUTH SUCCESS " + evt.request().uri());
    }

    @Override
    public void onAuthenticationFailed(HttpEvent evt, Page page, Response response) {
        System.out.println("    * onAuthenticationFailed " + response);
        System.out.println("    * headers " + new HashMap<>(evt.headersAsMap()));
        System.out.println("    * ORIG URL " + evt.request().uri() + " parsed to " + evt.getRequestURL(true));
        System.out.println("    * REALM: " + realm.value());
        System.out.println("    * AUTH: " + auth);
    }

}

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

import com.mastfrog.util.thread.OneThreadLatch;
import com.timboudreau.metaupdatecenter.InfoFile;
import com.timboudreau.metaupdatecenter.ModuleItem;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Assertions;

/**
 *
 * @author Tim Boudreau
 */
public final class PollResult {

    private final ModuleItem item;
    private Throwable thrown;
    private HttpResponseStatus status;
    private InfoFile module;
    private String url;
    final OneThreadLatch latch = new OneThreadLatch();

    PollResult(ModuleItem item, Throwable thrown, HttpResponseStatus status, InfoFile module, String url) {
        this.item = item;
        this.thrown = thrown;
        this.status = status;
        this.module = module;
        this.url = url;
    }

    public void rethrow() throws Throwable {
        if (thrown != null) {
            throw thrown;
        }
    }

    public synchronized HttpResponseStatus status() {
        return status;
    }

    public synchronized ModuleItem item() {
        return item;
    }

    public synchronized InfoFile module() {
        Assertions.assertNotNull(module, "module");
        return module;
    }

    public synchronized boolean isSuccess() {
        return status != null && status.code() == 200 && module != null;
    }

    public synchronized boolean isNotModified() {
        return status != null && status.code() == 304;
    }

    public synchronized void setThrown(Throwable thrown) {
        this.thrown = thrown;
        latch.releaseAll();
    }

    public synchronized void setInfoFile(InfoFile module) {
        this.module = module;
        latch.releaseAll();
    }

    public synchronized void setStatus(HttpResponseStatus status) {
        this.status = status;
        if (status.code() != 200) {
            latch.releaseAll();
        }
    }

    public synchronized void setUrl(String url) {
        this.url = url;
    }

    public synchronized boolean isComplete() {
        if (thrown != null || (status != null && status.code() != 200)) {
            return true;
        }
        if (module != null) {
            return true;
        }
        return false;
    }

}

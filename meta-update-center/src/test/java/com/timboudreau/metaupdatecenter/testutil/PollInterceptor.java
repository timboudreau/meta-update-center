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
import com.timboudreau.metaupdatecenter.ModuleSet;
import com.timboudreau.metaupdatecenter.Poller;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 * @author Tim Boudreau
 */
final class PollInterceptor implements Poller.PollerProbe {

    private final Map<String, PollResult> results = new ConcurrentHashMap<>();
    private final OneThreadLatch latch = new OneThreadLatch();
    private final AtomicInteger cycles = new AtomicInteger();

    @Override
    public void onPollCycleCompleted(ModuleSet set) {
        cycles.incrementAndGet();
        latch.releaseAll();
    }

    public PollCycleWatcher newWatcher() {
        return new PollCycleWatcherImpl();
    }

    class PollCycleWatcherImpl implements PollCycleWatcher {

        private volatile int lastCycle = cycles.get();

        private PollCycleWatcherImpl() {

        }

        public void reset() {
            lastCycle = cycles.get();
        }

        public boolean awaitCycleComplete(long ms) throws InterruptedException {
            int lc = lastCycle;
            if (lc < cycles.get()) {
                latch.await(ms, TimeUnit.MILLISECONDS);
            }
            lastCycle = cycles.get();
            return lastCycle > lc;
        }

    }

    public void awaitCompleted(long ms) throws InterruptedException {
        latch.await(ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void onAttemptDownload(ModuleItem item) {
        resultFor(item); // init an item
    }

    @Override
    public synchronized void onDownloadStatus(ModuleItem item, HttpResponseStatus status) {
        PollResult r = resultFor(item);
        r.setStatus(status);
    }

    @Override
    public synchronized void onNewVersionDownloaded(ModuleItem item, InfoFile module, String url) {
        PollResult r = resultFor(item);
        r.setUrl(url);
        r.setInfoFile(module);
    }

    @Override
    public synchronized void onError(ModuleItem item, Throwable thrown) {
        PollResult r = resultFor(item);
        r.setThrown(thrown);
    }

    synchronized void clear() {
        results.clear();
    }

    public PollResult await(String cnb) throws Throwable {
        PollResult result = findAndRemove(cnb);
        Assertions.assertNotNull(result, "No download was attempted before the timeout");
        assertTrue(result.isComplete(), "Download not completed");
        result.rethrow();
        return result;
    }

    private PollResult findAndRemove(String cnb) throws InterruptedException {
        PollResult res = null;
        for (int i = 0; i < 300; i++) {
            synchronized (this) {
                res = results.get(cnb);
            }
            if (res != null && res.isComplete()) {
                break;
            }
            if (res != null) {
                res.latch.await(50, TimeUnit.MILLISECONDS);
            } else {
                Thread.sleep(50);
            }
        }
        results.remove(cnb);
        return res;
    }

    private PollResult resultFor(ModuleItem item) {
        PollResult r = results.get(item.getCodeNameBase());
        if (r == null) {
            r = new PollResult(item, null, null, null, null);
            results.put(item.getCodeNameBase(), r);
        }
        return r;
    }


}

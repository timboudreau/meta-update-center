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

import com.google.inject.Injector;
import com.mastfrog.function.throwing.ThrowingRunnable;
import com.timboudreau.metaupdatecenter.Poller;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.fail;

/**
 *
 * @author Tim Boudreau
 */
public final class LaunchedNbmServer {

    public final TestUtils utils;
    public final int port;
    public final int serverPort;
    public final Path nbmsDir;
    private final LaunchInfo info;
    private final PollInterceptor interceptor;
    private final ThrowingRunnable onShutdown;
    private final EnumSet<TestProjectNBMs> nbmsServedByOriginServer;

    LaunchedNbmServer(String basePath, int port, int serverPort, Path nbmsDir, LaunchInfo info, PollInterceptor interceptor, ThrowingRunnable onShutdown, EnumSet<TestProjectNBMs> nbmsServedByOriginServer) {
        utils = new TestUtils(basePath);
        this.port = port;
        this.serverPort = serverPort;
        this.nbmsDir = nbmsDir;
        this.info = info;
        this.interceptor = interceptor;
        this.onShutdown = onShutdown;
        this.nbmsServedByOriginServer = nbmsServedByOriginServer;
    }

    public int port() {
        return port;
    }

    public int dummyNbmServerPort() {
        return serverPort;
    }

    public Path nbmsDir() {
        return nbmsDir;
    }

    public LaunchedNbmServer assertNbmsDirFileCount(long count) throws IOException {
        long res = Files.list(nbmsDir).count();
        Assertions.assertEquals(count, res, "Expected nbm dir file count " + count + " but got " + res + ": " + Arrays.asList(nbmsDir.toFile().list()));
        return this;
    }

    public LaunchedNbmServer modifyServedNbms(Consumer<EnumSet<TestProjectNBMs>> c) {
        synchronized (nbmsServedByOriginServer) {
            c.accept(nbmsServedByOriginServer);
        }
        return this;
    }

    public Injector injector() {
        return info.deps.getInjector();
    }

    public <T> T getServerObject(Class<T> type) {
        return injector().getInstance(type);
    }

    public void shutdown() throws Throwable {
        onShutdown.run();
    }

    public LaunchedNbmServer forcePoll() {
        Assertions.assertNotNull(info, "info null");
        info.deps.getInstance(Poller.class).run();
        return this;
    }

    public PollCycleWatcher newPollCycleWatcher() {
        return interceptor.newWatcher();
    }

    public PollResult awaitPollOf(String cnb) throws Throwable {
        return interceptor.await(cnb);
    }

    public LaunchedNbmServer clearPollResults() {
        interceptor.clear();
        ;
        return this;
    }

    public LaunchedNbmServer assertHomePageContains(String text) throws MalformedURLException, IOException {
        utils.assertHomePageContains(port, text);
        return this;
    }

    public byte[] bytes(String path) throws IOException {
        return utils.bytes(port, path, null);
    }

    public LaunchedNbmServer assertBytes(TestProjectNBMs nbm, String path) throws IOException, URISyntaxException {
        utils.assertBytes(nbm, port, path);
        return this;
    }

    public URL serverUrl(String path) throws MalformedURLException {
        return utils.nbmServerUrl(port, path);
    }

    public URL serverUrl(String path, String query) throws MalformedURLException {
        return utils.nbmServerUrl(port, path, query);
    }

    public String get(String path) throws IOException {
        return utils.get(port, path);
    }

    public String get(String path, String query) throws IOException {
        return utils.get(port, path, query);
    }

    public LaunchedNbmServer assertNotFound(String path) throws IOException {
        try {
            String result = utils.get(port, path);
            fail("Expected a 404 response but got: " + result);
        } catch (FileNotFoundException ex) {
            // expected
        }
        return this;
    }

    public String getHomePage() throws IOException {
        return utils.getHomePage(port);
    }

    public String moduleSourceURL(TestProjectNBMs nbm) {
        return utils.moduleSourceURL(serverPort, nbm);
    }

    public Map<String, Object> addModuleAndEnsurePresent(TestProjectNBMs nbm) throws Throwable {
        return utils.addModuleAndEnsurePresent(port, serverPort, nbm);
    }

    public Map<String, Object> findInCatalog(String cnb) throws Throwable {
        return utils.findInCatalog(port, cnb);
    }

    public List<Map<String, Object>> getCatalog() throws Throwable {
        return utils.getCatalog(port);
    }

    public URL nbmServerUrl(String path) throws MalformedURLException {
        return utils.nbmServerUrl(port, path);
    }

    public URL nbmServerUrl(String path, String query) throws MalformedURLException {
        return utils.nbmServerUrl(port, path, query);
    }

    public Map<String, Object> putUrlToServe(String srcUrl) throws Throwable {
        return utils.putUrlToServe(port, srcUrl);
    }

}

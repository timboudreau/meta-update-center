package com.timboudreau.metaupdatecenter;

import com.timboudreau.metaupdatecenter.testutil.PollResult;
import com.timboudreau.metaupdatecenter.testutil.PollCycleWatcher;
import com.timboudreau.metaupdatecenter.testutil.TestProjectNBMs;
import com.timboudreau.metaupdatecenter.testutil.LaunchedNbmServer;
import com.timboudreau.metaupdatecenter.testutil.NbmServerLauncher;
import com.mastfrog.util.streams.Streams;
import com.mastfrog.util.time.TimeUtil;
import static com.timboudreau.metaupdatecenter.testutil.TestUtils.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.time.ZonedDateTime;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static com.timboudreau.metaupdatecenter.testutil.TestProjectNBMs.MODULE_A_v1;
import static com.timboudreau.metaupdatecenter.testutil.TestProjectNBMs.MODULE_A_v2;
import static com.timboudreau.metaupdatecenter.testutil.TestProjectNBMs.MODULE_B_v1;
import org.junit.jupiter.api.AfterEach;

public class BufferLeakTest {

    private LaunchedNbmServer serv;

    private Map<String, Object> findInCatalog(String cnb) throws Throwable {
        return serv.findInCatalog(cnb);
    }

    private Map<String, Object> addModuleAndEnsurePresent(TestProjectNBMs nbm) throws Throwable {
        return serv.addModuleAndEnsurePresent(nbm);
    }

    @Test
    public void testHomepage() throws Throwable {
        assertNotNull(serv.getHomePage());
    }

    @Test
    public void test() throws Throwable {
        assertEquals(MODULE_A_v1.urlName(), MODULE_A_v2.urlName());
        assertEquals(MODULE_A_v1.codeNameBase(), MODULE_A_v2.codeNameBase());
        assertNotEquals(MODULE_A_v1.specificationVersion(), MODULE_A_v2.specificationVersion());
        assertTrue(MODULE_A_v2.lastModified().isAfter(MODULE_A_v1.lastModified()));

        String cnb = MODULE_A_v1.codeNameBase();
        Map<String, Object> info = addModuleAndEnsurePresent(MODULE_A_v1);
        assertNotNull(info);
        Map<String, Object> info2 = addModuleAndEnsurePresent(MODULE_A_v1);
        assertNull(info2);


        serv.clearPollResults();
        // Will block until the loop over all module updates has completed
        PollCycleWatcher watcher = serv.newPollCycleWatcher();
        for (int i = 0; i < 5; i++) {
            serv.forcePoll();
            PollResult res = serv.awaitPollOf(cnb);
            assertNotNull(res);
            assertTrue(res.isComplete());
            assertTrue(res.isNotModified());
            watcher.awaitCycleComplete(500);
            gc();
        }
        // should be
        // genmodule.json, com.timboudreau.nbmserver.localhost, com.mastfrog.first.test
        serv.assertNbmsDirFileCount(3);

        // Now replace the module the dummy server is serving with
        // an updated version - the next poll will pick up the new
        // one and download it, and that will become the one in the
        // catalog.  Also add another module as a sanity check that
        // we are picking up the right things.
        serv.modifyServedNbms(nbmSet -> {
            nbmSet.clear();
            nbmSet.add(MODULE_A_v2);
            nbmSet.add(MODULE_B_v1);
        });
        PollResult res = null;
        // we are passing the last-check date, not
        // the previous last-modified date, so we need
        // to update the timestamp on the NBM
        MODULE_A_v2.touch();
        serv.clearPollResults();
        serv.forcePoll();
        // Wait for the new NBM info
        res = serv.awaitPollOf(cnb);
        assertNotNull(res);
        assertTrue(res.isComplete());
        // Wait until all URLs have been cycled through, so the catalog
        // has really been updated before we try to use it
        watcher.awaitCycleComplete(500);
//        interceptor.awaitCompleted(500);
        assertFalse(res.isNotModified());
        assertTrue(res.isSuccess());

        InfoFile mod = res.module();
        assertNotNull(mod, "No module");
        assertEquals(MODULE_A_v2.codeNameBase(), mod.getModuleCodeName(),
                "Got wrong module update - PollInterceptor is broken");

        String secondVer = MODULE_A_v2.specificationVersion();
        assertNotNull(secondVer, "No version found from manifest of " + MODULE_A_v2);
        assertEquals(secondVer, mod.getModuleVersion().toString(),
                "Should have received version " + secondVer + " of "
                + MODULE_A_v2.codeNameBase() + " but was "
                + mod.getModuleVersion());

        Map<String, Object> updatedItem = findInCatalog(MODULE_A_v2.codeNameBase());
        assertValue("metadata.manifest.OpenIDE-Module-Specification-Version", secondVer, updatedItem);

        gc();

        Map<String, Object> info3 = addModuleAndEnsurePresent(MODULE_B_v1);
        assertNotNull(info3);

        gc();
    }

    private void gc() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            System.gc();
            System.runFinalization();
            Thread.sleep(5);
        }
    }

    @Test
    public void sanityCheckDummyServer() throws Throwable {
        URL url = new URL("http://localhost:" + serv.dummyNbmServerPort() + "/nbms/" + MODULE_A_v1.urlName());
        ZonedDateTime lastModified = null;
        URLConnection conn = url.openConnection();
        conn.setDoInput(true);
        conn.setConnectTimeout(1500);
        conn.setReadTimeout(1500);
        conn.connect();
        assertNotNull(conn.getHeaderFields().get(null));
        assertFalse(conn.getHeaderFields().get(null).isEmpty());
        String responseLine = conn.getHeaderFields().get(null).get(0);
        assertEquals("HTTP/1.1 200 OK", responseLine.trim());

        assertNotNull(conn.getHeaderFields().get("content-length"));
        assertFalse(conn.getHeaderFields().get("content-length").isEmpty());
        assertEquals(MODULE_A_v1.length(),
                Long.parseLong(conn.getHeaderFields().get("content-length").get(0)));

        assertNotNull(conn.getHeaderFields().get("last-modified"));
        assertFalse(conn.getHeaderFields().get("last-modified").isEmpty());

        lastModified = TimeUtil.fromHttpHeaderFormat(conn.getHeaderField("last-modified"));
//        lastModified = TimeUtil.fromUnixTimestamp(conn.getLastModified());

        ZonedDateTime expectedLastModified = TimeUtil.fromHttpHeaderFormat("Sun, 25 Nov 2018 19:00:00 -05:00");
        assertTrue(TimeUtil.equals(expectedLastModified, lastModified), "Timestamps do not match: " + expectedLastModified + " and " + lastModified);

        assertNotNull(conn.getHeaderFields().get("etag"));
        assertFalse(conn.getHeaderFields().get("etag").isEmpty());
        assertEquals(MODULE_A_v1.etagValue(), conn.getHeaderFields().get("etag").get(0));

        try (InputStream inStream = conn.getInputStream()) {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream(inStream.available())) {
                Streams.copy(inStream, out);
                Assertions.assertArrayEquals(MODULE_A_v1.bytes(), out.toByteArray());
            }
        }
        conn = url.openConnection();
        conn.setDoInput(true);
        conn.setConnectTimeout(1500);
        conn.setReadTimeout(1500);
        conn.setIfModifiedSince(TimeUtil.toUnixTimestamp(lastModified));
        conn.connect();

        assertNotNull(conn.getHeaderFields().get(null));
        assertFalse(conn.getHeaderFields().get(null).isEmpty());
        String responseLine2 = conn.getHeaderFields().get(null).get(0);
        assertTrue(responseLine2.contains("304"), '"' + responseLine2 + " does not contain the string '304'");

        conn = url.openConnection();
        conn.setDoInput(true);
        conn.setConnectTimeout(1500);
        conn.setReadTimeout(1500);
        conn.setRequestProperty("if-none-match", MODULE_A_v1.etagValue());
        conn.connect();

        String responseLine3 = conn.getHeaderFields().get(null).get(0);
        assertTrue(responseLine3.contains("304"), '"' + responseLine3 + " does not contain the string '304'");
    }

    @BeforeEach
    public void setup() throws Exception {
        serv = new NbmServerLauncher()
//                .instrumentEventLoop()
                .setInitialNbms(MODULE_A_v1)
                .launch();
    }

    @AfterEach
    public void shutdown() throws Throwable {
        if (serv != null) {
            serv.shutdown();;
        }
    }
}

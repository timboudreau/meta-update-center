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
package com.timboudreau.metaupdatecenter;

import com.mastfrog.giulius.bunyan.java.v2.LoggingModule;
import com.timboudreau.metaupdatecenter.testutil.LaunchedNbmServer;
import com.timboudreau.metaupdatecenter.testutil.NbmServerLauncher;
import com.timboudreau.metaupdatecenter.testutil.PollCycleWatcher;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static com.timboudreau.metaupdatecenter.testutil.TestProjectNBMs.MODULE_A_v1;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 * @author Tim Boudreau
 */
public class BasePathTest {

    private static final String TEST_BASE_PATH = "nbmstuff";
    private LaunchedNbmServer serv;

    @Test
    public void testAllUrlsWorkWithBasePathSet() throws Throwable {
        serv.modifyServedNbms(nbmSet -> {
            nbmSet.add(MODULE_A_v1);
        });

        String mods = serv.get("modules");
        assertFalse(mods.contains(MODULE_A_v1.codeNameBase()));

        PollCycleWatcher watcher = serv.newPollCycleWatcher();
        Map<String, Object> newModule = serv.addModuleAndEnsurePresent(MODULE_A_v1);
        assertNotNull(newModule);
        watcher.awaitCycleComplete(500);

        mods = serv.get("modules");
        assertTrue(mods.contains(MODULE_A_v1.codeNameBase()));

        serv.assertHomePageContains("MetaUpdateServer");
        String home2 = serv.get("/index.html");
        assertTrue(home2.contains("MetaUpdateServer"));

        Map<String, Object> catInfo = serv.findInCatalog(MODULE_A_v1.codeNameBase());

        String modulesXml = serv.get("modules");
        assertTrue(modulesXml.contains(MODULE_A_v1.codeNameBase()));

        Pattern p = Pattern.compile("distribution=\"(.*?" + MODULE_A_v1.codeNameBase() + ".*\\.nbm)\"");
        Matcher m = p.matcher(modulesXml);
        assertTrue(m.find());
        String hash = (String) catInfo.get("hash");
        assertNotNull(hash, "No hash in " + catInfo);

        String expectedPath = "download/" + MODULE_A_v1.codeNameBase() + "/" + hash + ".nbm";
        URL url = serv.serverUrl(expectedPath);
        assertEquals(url.toString(), m.group(1), "URL from XML catalog '" + m.group(1) +
            "' does not match expected '" + url + "'");

        serv.assertBytes(MODULE_A_v1, expectedPath);

//        String log = serv.get("log");
//        assertNotNull(log);
//        System.out.println("\n\nLOG:\n" + log);
    }

    @BeforeEach
    public void setup() throws IOException, Exception {
        serv = new NbmServerLauncher(TEST_BASE_PATH)
//                .instrumentEventLoop()
                .setInitialNbms(MODULE_A_v1)
//                .onCreateSettings(s -> {
//                    s.setInt(SETTINGS_KEY_STREAM_BUFFER_SIZE, 32);
//                })
                .launch();
    }

    @AfterEach
    public void tearDown() throws Throwable {
        if (serv != null) {
            serv.shutdown();
        }
    }
}

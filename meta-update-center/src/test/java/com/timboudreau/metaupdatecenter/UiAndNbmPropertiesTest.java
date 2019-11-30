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

import com.fasterxml.jackson.databind.ObjectMapper;
import static com.mastfrog.acteur.server.ServerModule.SETTINGS_KEY_URLS_HOST_NAME;
import static com.mastfrog.bunyan.LoggingModule.SETTINGS_KEY_LOG_HOSTNAME;
import com.mastfrog.settings.MutableSettings;
import com.mastfrog.util.collections.StringObjectMap;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_DISPLAY_NAME;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_GEN_MODULE_AUTHOR;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_INFO_PARA;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_NB_UI_DISPLAY_NAME;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_TAG;
import com.timboudreau.metaupdatecenter.testutil.LaunchedNbmServer;
import com.timboudreau.metaupdatecenter.testutil.NbmServerLauncher;
import com.timboudreau.metaupdatecenter.testutil.TestProjectNBMs;
import static com.timboudreau.metaupdatecenter.testutil.TestProjectNBMs.MODULE_A_v1;
import static com.timboudreau.metaupdatecenter.testutil.TestUtils.assertValue;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 * @author Tim Boudreau
 */
public class UiAndNbmPropertiesTest {

    private static final String TEST_BASE_PATH = "woogles";
    private LaunchedNbmServer serv;
    private MutableSettings settings;

    @Test
    public void test() throws IOException {
        assertNotNull(settings, "Setting not set - not started?");
        serv.getHomePage();
        Map<String, Object> props = genModuleProps();
        System.out.println("PROPS: " + props);
        assertFalse(props.isEmpty());

        assertEquals(Integer.valueOf(1), props.get("version"));
        assertNotNull(props.get("month"));
        assertNotNull(props.get("year"));
        assertNotNull(props.get("day"));
        assertNotNull(props.get("hash"));

        String home = serv.getHomePage();
        for (String k : new String[]{}) {
            String val = settings.getString(k);
            assertNotNull(val, "Not present in settings: " + k);
            assertTrue(home.contains(val), "Home page should contain "
                    + "the text '" + val + "' specified by " + k);
        }

        Path dir = serv.nbmsDir().resolve("com.timboudreau.nbmserver.lightsaber.com.obiwan.woogles");
        assertTrue(Files.exists(dir), "Genmodule dir not created");
        assertTrue(Files.isDirectory(dir), "Genmodule dir not a directory: " + dir);

        Path moduleJson = findJsonFile(dir);

        Map<String, Object> info = new ObjectMapper().readValue(moduleJson.toFile(),
                StringObjectMap.class);

        assertValue("metadata.manifest.OpenIDE-Module-Name", "Update Center for Zen and the art of droid maintenance (lightsaber.com)", info);
        assertValue("metadata.manifest.OpenIDE-Module-Short-Description", "Adds modules from Zen and the art of droid maintenance (lightsaber.com) to Tools | Plugins. Modules for the Jedi and Paduan alike ", info);
        assertValue("metadata.manifest.OpenIDE-Module-Long-Description", "Adds modules from Zen and the art of droid maintenance (lightsaber.com) to Tools | Plugins. Modules for the Jedi and Paduan alike ", info);
        assertValue("metadata.manifest.OpenIDE-Module", "com.timboudreau.nbmserver.lightsaber.com.obiwan.woogles", info);
        assertValue("metadata.homepage", "http://lightsaber.com:" + serv.port() + "/woogles", info);
        assertValue("metadata.distribution", "http://lightsaber.com:" + serv.port() + "/woogles", info);
        // If the generation code changes, this hash will change - normally it
        // should remain stable for years
        assertValue("hash", "19zvms1q74jkddys5bj1t3llkf1lr7oxi", info);
        assertValue("from", "http://GENERATED.MODULE", info);
        assertValue("codeNameBase", "com.timboudreau.nbmserver.lightsaber.com.obiwan.woogles", info);
    }

    private Path findJsonFile(Path dir) throws IOException {
        List<Path> results = new ArrayList<>(2);
        try (Stream<Path> str = Files.list(dir)) {
            str.filter(pth -> {
                return pth.getFileName().toString().endsWith(".json");
            }).forEach(results::add);
        }
        assertEquals(1, results.size(), "Found wrong number of json files: " + results);
        return results.get(0);
    }

    private Map<String, Object> genModuleProps() throws IOException {
        Map<String, Object> result;
        Path file = serv.nbmsDir().resolve("genmodule.json");
        assertTrue(Files.exists(file));
        try (InputStream in = Files.newInputStream(file)) {
            result = new ObjectMapper().readValue(in, StringObjectMap.class);
        }
        return result;
    }

    @BeforeEach
    public void setup() throws IOException, Exception {
        serv = new NbmServerLauncher(TEST_BASE_PATH)
                //                .instrumentEventLoop()
                .setInitialNbms(MODULE_A_v1)
                .onCreateSettings(s -> {
                    s.setString(SETTINGS_KEY_TAG, "obiwan");
                    s.setInt(UpdateCenterServer.SETTINGS_KEY_DOWNLOAD_THREADS, 2);
                    s.setBoolean("url.update.https", true);
                    s.setString(SETTINGS_KEY_DISPLAY_NAME, "The Death Star");
                    s.setString(SETTINGS_KEY_GEN_MODULE_AUTHOR, "Darth NetBeans");
                    s.setString(SETTINGS_KEY_INFO_PARA, "Modules for the Jedi and Paduan alike");
                    s.setString(SETTINGS_KEY_NB_UI_DISPLAY_NAME, "Zen and the art of droid maintenance");
                    s.setString(SETTINGS_KEY_URLS_HOST_NAME, "lightsaber.com");
                    s.setString(SETTINGS_KEY_LOG_HOSTNAME, "nbms.lightsaber.com");
                    settings = s;
                })
                .launch();
        serv.modifyServedNbms(es -> {
            es.add(TestProjectNBMs.MODULE_B_v1);
        });
    }

    @AfterEach
    public void tearDown() throws Throwable {
        if (serv != null) {
            serv.shutdown();
        }
    }

}

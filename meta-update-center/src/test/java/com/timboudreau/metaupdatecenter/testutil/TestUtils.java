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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mastfrog.acteur.header.entities.BasicCredentials;
import com.mastfrog.util.collections.StringObjectMap;
import com.mastfrog.util.streams.Streams;
import static com.mastfrog.util.streams.Streams.readUTF8String;
import static com.timboudreau.metaupdatecenter.testutil.NbmServerLauncher.TEST_ADMIN_USER;
import static com.timboudreau.metaupdatecenter.testutil.NbmServerLauncher.TEST_PASSWORD;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import static java.net.URLEncoder.encode;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Common utils for fetching from the servers.
 *
 * @author Tim Boudreau
 */
public class TestUtils {

    private static final String HOST = "127.0.0.1";
    private static final String CATALOG_KEY_CODE_NAME_BASE = "codeNameBase";
    private static final String NEW_URL_INFO_DOWNLOAD_SIZE_KEY = "downloadsize";
    private static final String MANIFEST_KEY_MODULE_NAME = "OpenIDE-Module-Name";
    private static final int TIMEOUT_MS = 1500;
    private final String basePath;

    TestUtils() {
        this(null);
    }

    TestUtils(String basePath) {
        if (basePath == null || basePath.isEmpty() || "/".equals(basePath)) {
            this.basePath = "/";
        } else {
            if (basePath.charAt(0) != '/') {
                basePath = "/" + basePath;
            }
            while (basePath.length() > 0 && basePath.charAt(basePath.length() - 1) == '/') {
                basePath = basePath.substring(0, basePath.length() - 1);
            }
            this.basePath = basePath;
        }
    }

    void assertHomePageContains(int port, String text) throws MalformedURLException, IOException {
        String homePage = getHomePage(port);
        assertNotNull(homePage);
        assertTrue(homePage.contains(text), "No '" + text + "' in " + homePage);
    }

    String getHomePage(int port) throws IOException {
        URL url = nbmServerUrl(port, "", null);
        String homePage;
        try (InputStream in = url.openStream()) {
            homePage = Streams.readUTF8String(in);
        }
        return homePage;
    }

    String get(int port, String path) throws MalformedURLException, IOException {
        return get(port, path, null);
    }

    String get(int port, String path, String query) throws MalformedURLException, IOException {
        URL url = nbmServerUrl(port, path, query);
        String result;
        try (InputStream in = url.openStream()) {
            result = Streams.readUTF8String(in);
        }
        return result;
    }

    void assertBytes(TestProjectNBMs nbm, int port, String path) throws IOException, URISyntaxException {
        byte[] received = bytes(port, path, null);
        Assertions.assertArrayEquals(nbm.bytes(), received,
                "Bytes do not match " + nbm + " at path " + path);
    }

    byte[] bytes(int port, String path, String query) throws MalformedURLException, IOException {
        URL url = nbmServerUrl(port, path, query);
        try (InputStream in = url.openStream()) {
//            result = Streams.readUTF8String(in);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream(in.available())) {
                Streams.copy(in, out, 512);
                return out.toByteArray();
            }
        }
    }

    String moduleSourceURL(int serverPort, TestProjectNBMs nbm) {
        String result = "http://localhost:" + serverPort + "/nbms/" + nbm.urlName();
        return result;
    }

    Map<String, Object> addModuleAndEnsurePresent(int port, int serverPort, TestProjectNBMs nbm) throws Throwable {
        String expectedCnb = nbm.codeNameBase();
        assertNotNull(expectedCnb, "could not find OpenIDE-Module");
        Map<String, Object> info = putUrlToServe(port, moduleSourceURL(serverPort, nbm));
        if (info == null) {
            // Already present - anything else and an exception would be thrown
            return null;
        }
        String ver = nbm.specificationVersion();
        assertNotNull(ver, "Could not find specification version");
        String displayName = nbm.getBundleProperty(MANIFEST_KEY_MODULE_NAME);
        assertNotNull(displayName, "Could not find display name");
        assertTrue(info.containsKey(NEW_URL_INFO_DOWNLOAD_SIZE_KEY));
        assertValue(NEW_URL_INFO_DOWNLOAD_SIZE_KEY, "" + nbm.length(), info);
        Map<String, Object> catInfo = findInCatalog(port, expectedCnb);
        assertNotNull(catInfo, "Newly retrieved module not in catalog");
        assertValue("manifest.OpenIDE-Module-Name", displayName, info);
        assertValue(CATALOG_MANIFEST_VERSION_PATH, ver, catInfo);
        assertValue("codenamebase", expectedCnb, info);
        assertHomePageContains(port, displayName);
        return info;
    }
    private static final String CATALOG_MANIFEST_VERSION_PATH = "metadata.manifest.OpenIDE-Module-Specification-Version";

    public static void assertValue(String key, Object val, Object search) {
        assertEquals(val, fetchMapValue(key, search), "Wrong value for "
                + key + " in " + search);
    }

    @SuppressWarnings("unchecked")
    public static Object fetchMapValue(String key, Object search) {
        Object orig = search;
        LinkedList<String> parts = new LinkedList<>(Arrays.asList(key.split("\\.")));
        while (!parts.isEmpty() && search != null) {
            if (search instanceof Map<?, ?>) {
                Map<String, Object> m = (Map<String, Object>) search;
                String next = parts.pop();
                search = m.get(next);
                if (parts.isEmpty()) {
                    return search;
                }
            } else if (search instanceof List<?>) {
                String k = parts.pop();
                try {
                    int val = Integer.parseInt(k);
                    List<?> l = (List<?>) search;
                    if (l.size() > val) {
                        search = l.get(val);
                        if (parts.isEmpty()) {
                            return search;
                        }
                    }
                } catch (NumberFormatException ex) {
                    fail("Searching a list, but key is not a number: '" + k + "' "
                            + "searching " + search);
                }
            } else {
                fail("Search is now a " + search + " looking for "
                        + key + " in " + orig);
            }
        }
        return null;
    }

    Map<String, Object> findInCatalog(int port, String cnb) throws Throwable {
        for (Map<String, Object> m : getCatalog(port)) {
            String module = (String) m.get(CATALOG_KEY_CODE_NAME_BASE);
            assertNotNull(module, "Catalog contains entry with no codeNameBase in manifest");
            if (cnb.equals(module)) {
                return m;
            }
        }
        return null;
    }

    public List<Map<String, Object>> getCatalog(int port) throws Throwable {
        URL catalogUrl = nbmServerUrl(port, "modules", "json=true");
        HttpURLConnection conn = (HttpURLConnection) catalogUrl.openConnection();
        try {
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setAllowUserInteraction(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.connect();
            try (InputStream in = conn.getInputStream()) {
                return Arrays.asList(new ObjectMapper().readValue(in, StringObjectMap[].class));
            }
        } catch (Exception ex) {
            try (InputStream err = conn.getErrorStream()) {
                if (err != null) {
                    String msg = Streams.readUTF8String(err);
                    throw new IOException(msg, ex);
                }
                throw ex;
            }
        }
    }

    URL nbmServerUrl(int port, String path) throws MalformedURLException {
        return nbmServerUrl(port, path, null);
    }

    URL nbmServerUrl(int port, String path, String query) throws MalformedURLException {
        StringBuilder sb = new StringBuilder(96)
                .append("http://")
                .append(HOST)
                .append(':')
                .append(port)
                .append(basePath);
        if (path != null && !path.isEmpty()) {
            if (!"/".equals(basePath) && path.charAt(0) != '/') {
                sb.append('/');
            }
            sb.append(path);
        }
        if (query != null) {
            sb.append('?').append(query);
        }
        URL result = new URL(sb.toString());
        return result;
    }

    Map<String, Object> putUrlToServe(int port, String srcUrl) throws Throwable {
        BasicCredentials bc = new BasicCredentials(TEST_ADMIN_USER, TEST_PASSWORD);
        String encSrcUrl = encode(srcUrl, "UTF-8");
        URL aModule = nbmServerUrl(port, "add", "url=" + encSrcUrl);
        HttpURLConnection conn = (HttpURLConnection) aModule.openConnection();
        try {
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setAllowUserInteraction(true);
            conn.setRequestProperty("Authorization", bc.toString());
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.connect();
            String response;
            try (InputStream in = conn.getInputStream()) {
                response = readUTF8String(in);
            }
            if (response.contains("I already have that module.")) {
                return null;
            }
            return extractModuleInfo(response);
        } catch (Exception ex) {
            try (InputStream err = conn.getErrorStream()) {
                if (err != null) {
                    String msg = readUTF8String(err);
                    throw new IOException(msg, ex);
                }
                throw ex;
            }
        } finally {
            conn.disconnect();
        }
    }

    static Map<String, Object> extractModuleInfo(String putResponse) throws JsonProcessingException {
        int ix = putResponse.lastIndexOf("Metadata:");
        assertTrue(ix >= 0, "Did not find metadata info in response '" + putResponse + "'");
        String json = putResponse.substring(ix + "Metadata:".length() + 1);
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, StringObjectMap.class);
    }
}

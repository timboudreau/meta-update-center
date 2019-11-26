package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.inject.Key;
import com.google.inject.name.Names;
import com.google.inject.util.Providers;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.server.PathFactory;
import com.mastfrog.acteur.util.RequestID;
import com.mastfrog.bunyan.Logger;
import com.mastfrog.bunyan.LoggingModule;
import com.mastfrog.giulius.Dependencies;
import com.mastfrog.jackson.DurationSerializationMode;
import com.mastfrog.jackson.JacksonModule;
import com.mastfrog.jackson.TimeSerializationMode;
import com.mastfrog.settings.Settings;
import com.mastfrog.settings.SettingsBuilder;
import com.mastfrog.url.Path;
import com.mastfrog.url.PathElement;
import com.mastfrog.url.Protocol;
import com.mastfrog.util.builder.AbstractBuilder;
import com.mastfrog.util.libversion.VersionInfo;
import com.mastfrog.util.streams.Streams;
import com.timboudreau.metaupdatecenter.borrowed.SpecificationVersion;
import com.timboudreau.metaupdatecenter.gennbm.ServerInstallId;
import com.timboudreau.metaupdatecenter.gennbm.UpdateCenterModuleGenerator;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.xpath.XPathExpressionException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

public class AppTest {

    @Test
    public void testDlRex() {
        Pattern p = Pattern.compile(DownloadActeur.DOWNLOAD_REGEX);
        Matcher m = p.matcher("download/com.timboudreau.netbeans.mongodb/fmcer18v7npq1cso8w61m0u8rx18luad5.nbm");
        assertTrue(m.find());
    }

    @Test
    public void test() throws SAXException, ParserConfigurationException, IOException, XPathExpressionException, TransformerException {
        Dependencies deps = new Dependencies(new LoggingModule().bindLogger("x"), new JacksonModule().withJavaTimeSerializationMode(TimeSerializationMode.TIME_AS_EPOCH_MILLIS,
                DurationSerializationMode.DURATION_AS_MILLIS));
        Logger logger = deps.getInstance(Key.get(Logger.class, Names.named("x")));

        File tmp = new File(System.getProperty("java.io.tmpdir"));
        assertTrue(tmp.isDirectory());
        File dir = new File(tmp, getClass().getSimpleName() + "-" + System.currentTimeMillis());
        assertTrue(dir.mkdirs());

        InputStream nbmIn = AppTest.class.getResourceAsStream("org-netbeans-modules-fisheye.nbm");
        assertNotNull(nbmIn);

        InputStream xml = AppTest.class.getResourceAsStream("info.xml");
        assertNotNull(xml);

        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(xml);
        doc.getDocumentElement().normalize();
        InfoFile moduleInfo = new InfoFile(doc);

        assertEquals("org.netbeans.modules.fisheye", moduleInfo.getModuleCodeName());
        ObjectMapper mapper = deps.getInstance(ObjectMapper.class);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);

        ModuleSet set = new ModuleSet(dir, Providers.of(mapper), Providers.of(
                new Stats(logger, logger, logger, Providers.of(new RequestID.Factory().next()))));

        URL res = AppTest.class.getResource("org-netbeans-modules-fisheye.nbm");
        set.add(moduleInfo, nbmIn, res.toString(), "test-hash", false);

        File mdir = new File(dir, "org.netbeans.modules.fisheye");
        assertTrue(mdir.exists());
        assertTrue(mdir.isDirectory());
        File mfile = new File(mdir, "test-hash.nbm");
        assertTrue(mfile.exists(), "Non existent: " + mfile);
        assertTrue(mfile.isFile());
        assertFalse(mfile.length() == 0L);
        File meta = new File(mdir, "test-hash.json");
        assertTrue(meta.exists(), "Non existent: " + meta);
        assertTrue(meta.isFile());
        assertFalse(meta.length() == 0L);

        assertTrue(set.iterator().hasNext());
        ModuleItem info = set.iterator().next();
        assertNotNull(info);

        assertEquals("org.netbeans.modules.fisheye", info.getCodeNameBase());
        ZonedDateTime dt = ZonedDateTime.now();
        assertTrue(dt.isAfter(info.getDownloaded()));
        assertEquals(res.toString(), info.getFrom());
        assertEquals("test-hash", info.getHash());

        assertNotNull(info.getVersion());
        assertEquals(new SpecificationVersion("1.3.1"), info.getVersion());

        testGeneration(new X(), set);
    }

    private void testGeneration(X x, ModuleSet set) throws IOException {
        Settings settings = new SettingsBuilder()
                .add("hostname", "foo.com")
                .add("module.author", "Joe Blow")
                .add("basepath", "121/final/modules")
                .add("serverDisplayName", "The Foo Collection")
                .build();
        Dependencies deps = new Dependencies(settings, new JacksonModule().withJavaTimeSerializationMode(TimeSerializationMode.TIME_AS_EPOCH_MILLIS,
                DurationSerializationMode.DURATION_AS_MILLIS));
        UpdateCenterModuleGenerator gen = new UpdateCenterModuleGenerator(set, new ServerInstallId(1), settings, x, deps.getInstance(ObjectMapper.class),
                VersionInfo.find(UpdateCenterServer.class, "com.timboudreau", "meta-update-center"));
        gen.version = 6;

        File f = new File(new File(System.getProperty("java.io.tmpdir")), "test.nbm");
        if (!f.exists()) {
            f.createNewFile();
        }
        try (InputStream in = gen.getNbmInputStream()) {
            try (FileOutputStream out = new FileOutputStream(f)) {
                Streams.copy(in, out);
            }
        }
    }

    private static class X implements PathFactory {

        @Override
        public Path toPath(String uri) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public com.mastfrog.url.URL constructURL(Path path, boolean secure) {
            AbstractBuilder<PathElement, com.mastfrog.url.URL> b = com.mastfrog.url.URL.builder(com.mastfrog.url.URL.parse(secure ? "https" : "http" + "://foo.com")).add("download");
            for (PathElement pe : path.getElements()) {
                b.add(pe);
            }
            return b.create();
        }

        @Override
        public Path toExternalPath(Path path) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public Path toExternalPath(String path) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public com.mastfrog.url.URL constructURL(Protocol protocol, Path path) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public com.mastfrog.url.URL constructURL(Protocol protocol, Path path, boolean secure) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public com.mastfrog.url.URL constructURL(Protocol protocol, Path path, int port) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public com.mastfrog.url.URL constructURL(Path path) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public int portForProtocol(Protocol protocol) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public com.mastfrog.url.URL constructURL(String path, HttpEvent evt) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }
    }
}

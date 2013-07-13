package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.inject.util.Providers;
import com.mastfrog.acteur.server.PathFactory;
import com.mastfrog.url.Path;
import com.mastfrog.url.PathElement;
import com.mastfrog.util.AbstractBuilder;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.xpath.XPathExpressionException;
import org.joda.time.DateTime;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import org.openide.modules.SpecificationVersion;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

public class AppTest {
    
    @Test
    public void testDlRex() {
        Pattern p = Pattern.compile(DownloadPage.DOWNLOAD_REGEX);
        Matcher m = p.matcher("download/com.timboudreau.netbeans.mongodb/fmcer18v7npq1cso8w61m0u8rx18luad5.nbm");
        assertTrue(m.find());
        
    }

    @Test
    public void test() throws SAXException, ParserConfigurationException, IOException, XPathExpressionException, TransformerException {

        File tmp = new File(System.getProperty("java.io.tmpdir"));
        assertTrue(tmp.isDirectory());
        File dir = new File(tmp, getClass().getSimpleName() + "-" + System.currentTimeMillis());
        assertTrue(dir.mkdirs());

        InputStream nbmIn = AppTest.class.getResourceAsStream("org-netbeans-modules-fisheye.nbm");
        assertNotNull(nbmIn);

        InputStream xml = AppTest.class.getResourceAsStream("info.xml");
        assertNotNull(xml);

        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(xml);
        doc.getDocumentElement().normalize();
        InfoFile moduleInfo = new InfoFile(doc);

        assertEquals("org.netbeans.modules.fisheye", moduleInfo.getModuleCodeName());
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);

        ModuleSet set = new ModuleSet(dir, Providers.of(mapper));

        URL res = AppTest.class.getResource("org-netbeans-modules-fisheye.nbm");
        set.add(moduleInfo, nbmIn, res.toString(), "test-hash", false);

        File mdir = new File(dir, "org.netbeans.modules.fisheye");
        assertTrue(mdir.exists());
        assertTrue(mdir.isDirectory());
        File mfile = new File(mdir, "test-hash.nbm");
        assertTrue("Non existent: " + mfile,mfile.exists());
        assertTrue(mfile.isFile());
        assertFalse(mfile.length() == 0L);
        File meta = new File(mdir, "test-hash.json");
        System.out.println("META IS " + meta.getAbsolutePath());
        assertTrue("Non existent: " + meta, meta.exists());
        assertTrue(meta.isFile());
        assertFalse(meta.length() == 0L);

        assertTrue(set.iterator().hasNext());
        ModuleItem info = set.iterator().next();
        assertNotNull(info);

        assertEquals("org.netbeans.modules.fisheye", info.getCodeNameBase());
        DateTime dt = DateTime.now();
        assertTrue (dt.isAfter(info.getDownloaded()));
        assertEquals(res.toString(), info.getFrom());
        assertEquals("test-hash", info.getHash());
        
        System.out.println("-------------XML--------------------");
        System.out.println(info.toXML(new X(), "download"));
        
        assertNotNull(info.getVersion());
        assertEquals(new SpecificationVersion("1.3.1"), info.getVersion());
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
    }
}

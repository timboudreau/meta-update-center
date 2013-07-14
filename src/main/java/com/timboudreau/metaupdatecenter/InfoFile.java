package com.timboudreau.metaupdatecenter;

import com.google.common.base.Objects;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.openide.modules.SpecificationVersion;
import com.mastfrog.util.Exceptions;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;

/**
 *
 * @author Tim Boudreau
 */
public class InfoFile implements Comparable<InfoFile> {

    private Document doc;
    private Element module;
    private Element manifest;

    public InfoFile(Document doc) {
        this.doc = doc;
    }

    public Map<String, Object> toMap() throws XPathExpressionException {
        Map<String, Object> result = new HashMap<>();
        Element moduleNode = getModuleNode();
        NamedNodeMap nnm = moduleNode.getAttributes();
        for (int i = 0; i < nnm.getLength(); i++) {
            String key = nnm.item(i).getNodeName();
            String value = moduleNode.getAttribute(key);
            result.put(key, value);
        }
        Map<String, Object> manifest = new HashMap<>();
        result.put("manifest", manifest);
        Element manifestNode = getManifestNode();
        nnm = manifestNode.getAttributes();
        for (int i = 0; i < nnm.getLength(); i++) {
            String key = nnm.item(i).getNodeName();
            String value = manifestNode.getAttribute(key);
            manifest.put(key, value);
        }
        return result;
    }

    /*
     <module codenamebase="org.netbeans.modules.fisheye"
     distribution="http://deadlock.netbeans.org/hudson/job/nbms-and-javadoc/lastStableBuild/artifact/nbbuild/nbms/extra/org-netbeans-modules-fisheye.nbm"
     downloadsize="0"
     homepage="http://contrib.netbeans.org"
     license="8B813426"
     moduleauthor="Tim Boudreau"
     needsrestart="false"
     releasedate="2013/07/12">
     <manifest AutoUpdate-Show-In-Client="true"
     OpenIDE-Module="org.netbeans.modules.fisheye/1"
     OpenIDE-Module-Display-Category="Infrastructure"
     OpenIDE-Module-Implementation-Version="20130712-c7816c3e6fc8"
     OpenIDE-Module-Java-Dependencies="Java &gt; 1.5"
     OpenIDE-Module-Long-Description="A module that can provide &quot;fish eye&quot; views over a text component" OpenIDE-Module-Module-Dependencies="org.netbeans.modules.editor.errorstripe/2 = 1, org.netbeans.modules.editor.lib/3 &gt; 3.1, org.openide.util &gt; 8.0" OpenIDE-Module-Name="Fisheye Text View Factory"
     OpenIDE-Module-Requires="org.openide.modules.ModuleFormat1"
     OpenIDE-Module-Short-Description="A module that can provide &quot;fish eye&quot; views over a text component"
     OpenIDE-Module-Specification-Version="1.3.1"/>
     </module>
     */

    public String getDistributionURL() throws XPathExpressionException {
        return getModuleNode().getAttribute("distribution");
    }

    public String getHomePage() throws XPathExpressionException {
        return getModuleNode().getAttribute("homepage");
    }

    public String getAuthor() throws XPathExpressionException {
        return getModuleNode().getAttribute("moduleauthor");
    }

    public String getReleaseDate() throws XPathExpressionException {
        return getModuleNode().getAttribute("releasedate");
    }

    public String getModuleCodeName() throws XPathExpressionException {
        String name = getManifestNode().getAttribute("OpenIDE-Module");
        Matcher m = NM.matcher(name);
        if (m.find()) {
            name = m.group(1);
        }
        return name;
    }

    private static final Pattern NM = Pattern.compile("(.*)/\\d+");

    public String getModuleDisplayName() throws XPathExpressionException {
        String name = getManifestNode().getAttribute("OpenIDE-Module-Name");
        return name;
    }

    public String getModuleDesription() throws XPathExpressionException {
        String result = getManifestNode().getAttribute("OpenIDE-Module-Long-Description");
        if (result == null || result.isEmpty()) {
            result = getManifestNode().getAttribute("OpenIDE-Module-Short-Description");
        }
        return result;
    }

    public SpecificationVersion getModuleVersion() throws XPathExpressionException {
        String res = getManifestNode().getAttribute("OpenIDE-Module-Specification-Version");
        return new SpecificationVersion(res);
    }

    private synchronized Element getModuleNode() throws XPathExpressionException {
        return module == null ? module = findModuleNode() : module;
    }

    private synchronized Element getManifestNode() throws XPathExpressionException {
        return manifest == null ? manifest = findManifestNode() : manifest;
    }

    public String getImplementationVersion() throws XPathExpressionException {
        String result = getManifestNode().getAttribute("OpenIDE-Module-Implementation-Version");
        return result == null || result.isEmpty() ? "Unknown" : result;
    }

    public String getDisplayCategory() throws XPathExpressionException {
        String result = getManifestNode().getAttribute("OpenIDE-Module-Display-Category");
        return result == null || result.isEmpty() ? "Uncategorized" : result;
    }

    public String toString() {
        try {
            return getModuleDisplayName() + " (" + getModuleCodeName() + "-" + getModuleVersion() + "-" + getImplementationVersion() + " - " + getDisplayCategory() + ")";
        } catch (XPathExpressionException ex) {
            Exceptions.printStackTrace(ex);
            return ex.getMessage() + "";
        }
    }

    private Element findModuleNode() throws XPathExpressionException {
        Document d = doc;
        XPathFactory fac = XPathFactory.newInstance();
        XPath xpath = fac.newXPath();
        XPathExpression findArtifactId = xpath.compile(
                "/module");
        Element n = (Element) findArtifactId.evaluate(d, XPathConstants.NODE);
        return n;
    }

    private Element findManifestNode() throws XPathExpressionException {
        Document d = doc;
        XPathFactory fac = XPathFactory.newInstance();
        XPath xpath = fac.newXPath();
        XPathExpression findArtifactId = xpath.compile(
                "/module/manifest");
        Element n = (Element) findArtifactId.evaluate(d, XPathConstants.NODE);
        return n;
    }

    @Override
    public int hashCode() {
        try {
            return (getModuleCodeName() + getModuleVersion() + getImplementationVersion()).hashCode();
        } catch (XPathExpressionException ex) {
            Exceptions.printStackTrace(ex);
            return -1;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof InfoFile) {
            InfoFile f = (InfoFile) o;
            try {
                return f.getModuleCodeName().equals(getModuleCodeName())
                        && f.getModuleVersion().equals(getModuleVersion())
                        && Objects.equal(getImplementationVersion(), f.getImplementationVersion());
            } catch (XPathExpressionException ex) {
                Exceptions.printStackTrace(ex);
                return false;
            }
        }
        return false;
    }

    @Override
    public int compareTo(InfoFile o) {
        try {
            return getModuleDisplayName().compareToIgnoreCase(o.getModuleDisplayName());
        } catch (XPathExpressionException ex) {
            Exceptions.printStackTrace(ex);
            return 1;
        }
    }
}

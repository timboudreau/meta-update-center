package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.mastfrog.acteur.server.PathFactory;
import com.mastfrog.url.Path;
import com.mastfrog.url.URL;
import com.sun.org.apache.xml.internal.serialize.OutputFormat;
import com.sun.org.apache.xml.internal.serialize.XMLSerializer;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import org.joda.time.DateTime;
import org.openide.modules.SpecificationVersion;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 *
 * @author Tim Boudreau
 */
public final class ModuleItem implements Comparable<ModuleItem> {

    private final String codeNameBase;
    private final String hash;
    private final Map<String, Object> metadata;
    private final DateTime downloaded;
    private final boolean useOriginalURL;
    private final String from;

    @JsonCreator
    public ModuleItem(@JsonProperty("codeNameBase") String codeNameBase,
            @JsonProperty("hash") String hash,
            @JsonProperty("metadata") Map<String, Object> info,
            @JsonProperty("downloaded") DateTime downloaded,
            @JsonProperty("useOriginalURL") boolean useOriginalURL,
            @JsonProperty("from") String from) {
        this.codeNameBase = codeNameBase;
        this.hash = hash;
        this.metadata = info;
        this.downloaded = downloaded;
        this.useOriginalURL = useOriginalURL;
        this.from = from;
    }
    
    public static ModuleItem fromFile(File file, ObjectMapper mapper) throws IOException {
        return mapper.readValue(file, ModuleItem.class);
    }
    
    public boolean isUseOriginalURL() {
        return useOriginalURL;
    }

    public Map<String, Object> getMetadata() {
        return new ImmutableMap.Builder<String, Object>().putAll(metadata).build();
    }

    public DateTime getDownloaded() {
        return downloaded;
    }

    public String getFrom() {
        return from;
    }

    public String getCodeNameBase() {
        return codeNameBase;
    }

    public String getHash() {
        return hash;
    }
    
    public String toString() {
        return getCodeNameBase() + "-" + getVersion() + " downloaded " + getDownloaded();
    }

    public SpecificationVersion getVersion() {
        String val = (String) getManifest().get("OpenIDE-Module-Specification-Version");
        return val == null ? new SpecificationVersion("0.0.0") : new SpecificationVersion(val);
    }

    @JsonIgnore
    public Map<String, Object> getManifest() {
        return (Map<String, Object>) getMetadata().get("manifest");
    }
    
    public String getName() {
        return (String) getManifest().get("OpenIDE-Module-Name");
    }
    
    public String getDescription() {
        String result = (String) getManifest().get("OpenIDE-Module-Long-Description");
        if (result == null) {
            result = (String) getManifest().get("OpenIDE-Module-Short-Description");
        }
        return result == null ? "(undefined)" : "<undefined>".equals(result) ? "(undefined)" : result;
    }

    @Override
    public int compareTo(ModuleItem o) {
        // Highest specification version first, then newest first
        // So, compare foreign value against ours to do reverse compare
        SpecificationVersion mine = getVersion();
        SpecificationVersion theirs = o.getVersion();
        int result = theirs.compareTo(mine);
        if (result == 0) {
            result = o.getDownloaded().compareTo(o.getDownloaded());
        }
        return result;
    }

    private String xml;

    public String toXML(PathFactory paths, String base) throws ParserConfigurationException, IOException, TransformerException {
        if (xml != null) {
            return xml;
        }
        Map<String, Object> meta = new HashMap<>(getMetadata());
        if (!useOriginalURL) {
            URL nue = paths.constructURL(Path.builder().add(base).add(getCodeNameBase()).add(hash + ".nbm").create(), false);
            meta.put("distribution", nue.toString());
        }
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element moduleNode = document.createElement("module");
        for (Map.Entry<String, Object> e : meta.entrySet()) {
            String key = e.getKey();
            if (e.getValue() instanceof String) {
                moduleNode.setAttribute(key, (String) e.getValue());
            }
        }
        Map<String, Object> manifest = getManifest();
        assert manifest != null : "Manifest is null " + this.codeNameBase + " " + this.hash;
        Element manifestNode = document.createElement("manifest");
        for (Map.Entry<String, Object> e : manifest.entrySet()) {
            String key = e.getKey();
            if (e.getValue() instanceof String) {
                manifestNode.setAttribute(key, (String) e.getValue());
            }
        }
        document.appendChild(moduleNode);
        moduleNode.appendChild(manifestNode);

        return xml = nodeToString(document);
    }

    public static String nodeToString(Document document) throws IOException, TransformerConfigurationException, TransformerException {
        OutputFormat format = new OutputFormat(document);
        format.setOmitXMLDeclaration(true);
        format.setStandalone(false);
        format.setPreserveEmptyAttributes(true);
        format.setAllowJavaNames(true);
        format.setPreserveSpace(false);
        format.setIndenting(true);
        format.setIndent(4);
        format.setLineWidth(80);
        Writer out = new StringWriter();
        XMLSerializer serializer = new XMLSerializer(out, format);
        serializer.serialize(document);
        return out.toString();
    }
}

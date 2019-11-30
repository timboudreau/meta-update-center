package com.timboudreau.metaupdatecenter.gennbm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.mastfrog.acteur.server.PathFactory;
import com.mastfrog.acteur.server.ServerModule;
import com.mastfrog.settings.Settings;
import com.mastfrog.url.Path;
import com.mastfrog.url.URL;
import com.mastfrog.util.collections.StringObjectMap;
import com.mastfrog.util.libversion.VersionInfo;
import com.mastfrog.util.preconditions.ConfigurationError;
import com.mastfrog.util.preconditions.Exceptions;
import com.mastfrog.util.streams.Streams;
import com.mastfrog.util.streams.HashingOutputStream;
import com.timboudreau.metaupdatecenter.InfoFile;
import com.timboudreau.metaupdatecenter.ModuleSet;
import com.timboudreau.metaupdatecenter.UpdateCenterServer;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_GEN_MODULE_AUTHOR;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_INFO_PARA;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_TAG;
import io.netty.util.CharsetUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * Generates a NetBeans plugin which registers this server as an update center
 *
 * @author Tim Boudreau
 */
public final class UpdateCenterModuleGenerator {

    private final ModuleSet modules;
    private final Settings settings;
    private final PathFactory paths;
    private final List<FileTemplate> nbmFileTemplates = new LinkedList<>();
    private final List<FileTemplate> jarFileTemplates = new LinkedList<>();
    private final ObjectMapper mapper;
    public int version = 1; //public for unit test
    private String year;
    private String day;
    private String month;
    private final boolean updateUrlHttps;
    private final long serverInstallId;
    private final String serverVersion;
    private final String tag;

    @Inject
    public UpdateCenterModuleGenerator(ModuleSet modules, ServerInstallId idProvider, Settings settings, PathFactory paths, ObjectMapper mapper, VersionInfo version) throws IOException {
        this.modules = modules;
        this.settings = settings;
        this.paths = paths;
        this.mapper = mapper;
        updateUrlHttps = settings.getBoolean("update.url.https", false);
        serverInstallId = idProvider.get();
        tag = settings.getString(SETTINGS_KEY_TAG);
        serverVersion = version.deweyDecimalVersion();
        initTemplates();
        load();
    }

    private FileTemplate infoTemplate;

    private void initTemplates() {
        Map<String, String> substs = getSubstitutions();
        addNbmTemplate(FileTemplate.of("NBM_MANIFEST.MF", "META-INF/MANIFEST.MF"));
        addNbmTemplate(infoTemplate = FileTemplate.of("info.xml", "Info/info.xml"));
        addNbmTemplate(FileTemplate.of("com-timboudreau-uc.xml", "netbeans/config/Modules/" + substs.get(CODE_NAME_DASHES) + ".xml"));
        addNbmTemplate(new JarTemplate("netbeans/modules/" + substs.get(CODE_NAME_DASHES) + ".jar"));

        addJarTemplate(FileTemplate.of("MANIFEST.MF", "META-INF/MANIFEST.MF"));
        addJarTemplate(FileTemplate.of("Bundle_template.properties", substs.get(CODE_NAME_SLASHES) + "/Bundle.properties"));
        addJarTemplate(FileTemplate.of("layer.xml", substs.get(CODE_NAME_SLASHES) + "/layer.xml"));
    }

    private static final TypeReference<StringObjectMap> REF = new TypeReference<StringObjectMap>(){};
    private void load() throws IOException {
        File dir = modules.getStorageDir();
        File f = new File(dir, "genmodule.json");
        ZonedDateTime now = ZonedDateTime.now();
        if (f.exists()) {
            Map<String, Object> m = mapper.readValue(f, REF);
            Number num = (Number) m.get("version");
            if (num != null) {
                version = num.intValue();
            }

            // Here we are determining if the bits will be identical to the
            // last bits we generated.  If so, then we make sure the dates
            // used in generated files are the same
            String lastHash = (String) m.get("hash");
            // This is the SHA-1 of all the substitutions, in order
            String currHash = getImplementationVersion(getSubstitutions());
            if (!currHash.equals(lastHash)) { // something changed
                // Increment the version from the last known one
                version++;
                m.put("hash", currHash);
                m.put("version", version);
                // Record the current date, so on restart we can generate an
                // identical NBM
                m.put("year", now.getYear() + "");
                m.put("month", now.get(ChronoField.MONTH_OF_YEAR) + "");
                m.put("day", now.getDayOfMonth() + "");
                mapper.writeValue(f, m);
            } else {
                // Use the date from the last time the bits changed
                year = (String) m.get("year");
                day = (String) m.get("day");
                month = (String) m.get("month");
            }
        } else {
            if (!f.createNewFile()) {
                throw new ConfigurationError("Could not create " + f.getAbsolutePath());
            }
            String currHash = getImplementationVersion(getSubstitutions());
            Map<String, Object> m = new HashMap<>();
            m.put("hash", currHash);
            m.put("version", version);
            // Record the current date, so on restart we can generate an
            // identical NBM
            m.put("year", now.getYear() + "");
            m.put("month", now.get(ChronoField.MONTH_OF_YEAR) + "");
            m.put("day", now.getDayOfMonth() + "");
            year = (String) m.get("year");
            day = (String) m.get("day");
            month = (String) m.get("month");
            substs = null;
            currHash = getImplementationVersion(getSubstitutions());
            m.put("hash", currHash);
            mapper.writeValue(f, m);
        }
    }

    private String implVersion;

    private String getImplementationVersion(Map<String, String> substs) {
        if (implVersion != null) {
            return implVersion;
        }
        List<String> keys = new LinkedList<>(substs.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            sb.append(key).append('=').append(substs.get(key));
        }
        ByteArrayInputStream in = new ByteArrayInputStream(sb.toString().getBytes(CharsetUtil.UTF_8));
        HashingOutputStream out = HashingOutputStream.sha1(Streams.nullOutputStream());
        try {
            Streams.copy(in, out);
            out.close();
            return implVersion = out.getHashAsString();
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
            return implVersion = System.currentTimeMillis() + "";
        }
    }

    private UpdateCenterModuleGenerator addJarTemplate(FileTemplate f) {
        jarFileTemplates.add(f);
        return this;
    }

    private UpdateCenterModuleGenerator addNbmTemplate(FileTemplate f) {
        nbmFileTemplates.add(f);
        return this;
    }

    private String getUpdatesURL() {
        // + "?unique={$netbeans.hash.code}"
        URL base = paths.constructURL(Path.parse("modules"), updateUrlHttps);
//        base = base.withParameter("unique", "{$netbeans.hash.code}");
        return base.toString() + "?{$netbeans.hash.code}";
    }

    private String getServerDisplayName() {
        String result = settings.getString(UpdateCenterServer.SETTINGS_KEY_NB_UI_DISPLAY_NAME);
        if (result != null) {
            return result;
        }
        String mungedHostName = hostName().replace('.', ' ');
        StringBuilder sb = new StringBuilder();
        for (String word : mungedHostName.split(" ")) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            char[] c = word.toCharArray();
            c[0] = Character.toUpperCase(c[0]);
            switch (new String(c)) {
                // XXX should have all top level domains
                case "Com":
                case "Org":
                case "Edu":
                case "Net":
                case "Gov":
                case "Mil":
                case "Cz":
                case "Eu":
                case "Ee":
                case "Fr":
                case "Uk":
                case "Hu":
                case "Ie":
                case "In":
                case "Jp":
                case "Nl":
                case "Pl":
                case "Ro":
                case "Sk":
                case "Ua":
                case "It":
                case "Biz":
                case "Info":
                case "Name":
                case "Br":
                case "Es":
                case "De":
                case "Cn":
                case "Us":
                    break;
                default:
                    sb.append(c);
            }
        }
        mungedHostName = sb.toString();
        return settings.getString(SERVER_DISPLAY_NAME, mungedHostName);
    }

    private String hostName() {
        return settings.getString("hostname", "localhost");
    }

    private String getModuleCodeName() {
        String host = hostName();
        String result = "com.timboudreau.nbmserver." + host;
        if (tag != null) {
            result += "." + tag;
        }
        // Incorporate the server path if present, so if there are two instances
        // both running on foo.com, they do not both get the same packages and names
        String basePath = settings.getString(ServerModule.SETTINGS_KEY_BASE_PATH);
        // Skip empty paths
        if (basePath != null && !basePath.trim().isEmpty() && !"/".equals(basePath.trim())) {
            // Replace /'s with the empty string in case of multiple /'s in a row
            String munged = basePath.trim().replace("/", "");
            result += '.' + munged;
        }
        // Strip anything not a legal java identifier
        char[] c = result.toCharArray();
        boolean wasStart = true;
        for (int i = 0; i < c.length; i++) {
            if (wasStart && !Character.isJavaIdentifierStart(c[i])) {
                c[i] = 'x';
            }
            if (c[i] != '.' && !Character.isDigit(c[1]) && !Character.isJavaIdentifierPart(c[i])) {
                c[i] = '_';
            }
            wasStart = c[i] == '.';
        }
        result = new String(c);
        return result;
    }

    private Map<String, String> substs;

    private Map<String, String> getSubstitutions() {
        if (substs != null) {
            return substs;
        }
        Map<String, String> result = new HashMap<>();
        String info = settings.getString(SETTINGS_KEY_INFO_PARA);
        String author = settings.getString(SETTINGS_KEY_GEN_MODULE_AUTHOR);
        // author, year, serverUrl
        ZonedDateTime now = ZonedDateTime.now();
        result.put(YEAR, year == null ? now.getYear() + "" : year);
        result.put(MONTH, month == null ? now.get(MONTH_OF_YEAR) + "" : month);
        result.put(DAY, day == null ? now.getDayOfMonth() + "" : day);
        result.put(AUTHOR, settings.getString("module.author", author == null
                ? System.getProperty("user.name") : author));
        result.put(SERVER_URL, paths.constructURL(Path.parse("/"), updateUrlHttps).toString());
        result.put(CODE_NAME_BASE, getModuleCodeName());
        result.put(CODE_NAME_SLASHES, getModuleCodeName().replace('.', '/'));
        result.put(CODE_NAME_UNDERSCORES, getModuleCodeName().replace('.', '_'));
        result.put(CODE_NAME_DASHES, getModuleCodeName().replace('.', '-'));
        result.put(JAR_NAME, result.get(CODE_NAME_DASHES) + ".jar");
        result.put(JAR_PATH, "netbeans/modules/" + result.get(JAR_NAME));
        result.put(SERVER_DISPLAY_NAME, getServerDisplayName());
        result.put(MODULE_DISPLAY_NAME, "Update Center for " + getServerDisplayName() + " (" + hostName() + ")");
        result.put(MODULE_DESCRIPTION, "Adds modules from " + getServerDisplayName() + " (" + hostName() + ") to Tools | Plugins."
                + (info == null ? "" : " " + info + "\n"));
        result.put(UPDATE_URL, getUpdatesURL());
        result.put(SPECIFICATION_VERSION, serverVersion + "." + serverInstallId + "." + version);
        result.put(IMPLEMENTATION_VERSION, getImplementationVersion(result));
        result.put(HOME_PAGE, paths.constructURL(Path.parse("/"), updateUrlHttps).toString());
        return substs = result;
    }

    public static final String SERVER_URL = "serverUrl";
    public static final String AUTHOR = "author";
    public static final String YEAR = "year";
    public static final String MONTH = "month";
    public static final String DAY = "day";
    public static final String JAR_PATH = "jarPath";
    public static final String JAR_NAME = "jarName";
    public static final String UPDATE_URL = "updateUrl";
    public static final String IMPLEMENTATION_VERSION = "implementationVersion";
    public static final String SPECIFICATION_VERSION = "specificationVersion";
    public static final String SERVER_DISPLAY_NAME = "serverDisplayName";
    public static final String MODULE_DISPLAY_NAME = "moduleDisplayName";
    public static final String MODULE_DESCRIPTION = "moduleDescription";
    public static final String CODE_NAME_BASE = "codeNameBase";
    public static final String CODE_NAME_SLASHES = "codeNameSlashes";
    public static final String CODE_NAME_UNDERSCORES = "codeNameUnderscores";
    public static final String CODE_NAME_DASHES = "codeNameDashes";
    public static final String HOME_PAGE = "homePage";

    private String hash;

    public String getHash() {
        if (hash == null) {
            throw new IllegalStateException("Call getNbmInputStream first");
        }
        return hash;
    }

    public InputStream getNbmInputStream() throws IOException {
        Map<String, String> substs = getSubstitutions();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HashingOutputStream hashOut = HashingOutputStream.sha1(out);
        try (JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.setLevel(9);
            for (FileTemplate f : nbmFileTemplates) {
                f.write(jarOut, substs);
            }
        }
        hashOut.close();
        hash = hashOut.getHashAsString();
        return new ByteArrayInputStream(out.toByteArray());
    }

    public InfoFile getInfoFile() throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(infoTemplate.getInputStream(getSubstitutions()));
        doc.getDocumentElement().normalize();
        InfoFile moduleInfo = new InfoFile(doc);
        return moduleInfo;
    }

    private class JarTemplate extends FileTemplate {

        public JarTemplate(String nameInJAR) {
            super(null, nameInJAR);
        }

        @Override
        protected InputStream getInputStream(Map<String, String> substs) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (JarOutputStream jarOut = new JarOutputStream(out)) {
                for (FileTemplate f : jarFileTemplates) {
                    f.write(jarOut, substs);
                }
            }
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    private static class FileTemplate {

        protected final String resource;
        protected final String nameInJAR;

        public FileTemplate(String resource, String nameInJAR) {
            this.resource = resource;
            this.nameInJAR = nameInJAR;
        }

        public static FileTemplate of(String resource, String nameInJAR) {
            return new FileTemplate(resource, nameInJAR);
        }

        protected String getText() throws IOException {
            InputStream in = FileTemplate.class.getResourceAsStream(resource);
            return Streams.readUTF8String(in);
        }

        public String getSubstitutedText(Map<String, String> substs) throws IOException {
            String text = getText();
            for (Map.Entry<String, String> e : substs.entrySet()) {
                // Use two sets of delimiters - simple delimiters screw up with
                // {$ ... } delimited URL parameters
                String replace = "~^~" + e.getKey() + "~%~";
                text = text.replace(replace, e.getValue());
                replace = "${" + e.getKey() + "}";
                Pattern p = Pattern.compile(replace, Pattern.LITERAL | Pattern.DOTALL | Pattern.MULTILINE);
                text = p.matcher(text).replaceAll(e.getValue());
            }
            return text;
        }

        public void write(JarOutputStream out, Map<String, String> substs) throws IOException {
            JarEntry entry = new JarEntry(nameInJAR);
            out.putNextEntry(entry);
            write(substs, out);
        }

        protected InputStream getInputStream(Map<String, String> substs) throws IOException {
            return new ByteArrayInputStream(getSubstitutedText(substs).getBytes(CharsetUtil.UTF_8));
        }

        public void write(Map<String, String> substs, OutputStream file) throws IOException {
            try (InputStream in = getInputStream(substs)) {
                Streams.copy(in, file);
            }
        }
    }
}

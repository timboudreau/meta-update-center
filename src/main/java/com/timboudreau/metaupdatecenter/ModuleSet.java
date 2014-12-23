package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.mastfrog.util.ConfigurationError;
import com.mastfrog.util.Streams;
import io.netty.util.internal.ConcurrentSet;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.xpath.XPathExpressionException;
import org.joda.time.DateTime;
import org.openide.util.Exceptions;

/**
 *
 * @author Tim Boudreau
 */
@Singleton
public final class ModuleSet implements Iterable<ModuleItem> {

    private final File dir;
    private final Provider<ObjectMapper> mapper;
    private final Set<ModuleItem> items = new ConcurrentSet<>();

    public File getStorageDir() {
        return dir;
    }

    @Override
    public Iterator<ModuleItem> iterator() {
        return toList().iterator();
    }

    public List<ModuleItem> sorted() {
        List<ModuleItem> result = new LinkedList<>(items);
        Collections.sort(result, new ModuleItemComparator());
        return result;
    }

    public File getNBM(String codeName, String fileName) {
        return new File(new File(dir, codeName), fileName);
    }

    public ModuleItem find(String codeName, String hash) {
        if (hash.endsWith(".nbm")) {
            hash = hash.substring(0, hash.length() - 4);
        }
        for (ModuleItem i : items) {
            if (codeName.equals(i.getCodeNameBase())) {
                if (hash.equals(i.getHash())) {
                    return i;
                }
            }
        }
        File f = new File(new File(dir, codeName), hash + ".nbm");
        if (f.exists()) {
            try {
                return ModuleItem.fromFile(f, mapper.get());
            } catch (IOException ex) {
                Exceptions.printStackTrace(ex);
            }
        }
        return null;
    }

    public List<ModuleItem> toList() {
        List<ModuleItem> result = new ArrayList<>(this.items);
        Collections.sort(result);
        return result;
    }

    public String toString() {
        return items.toString();
    }

    private final Provider<Stats> stats;

    @Inject
    ModuleSet(File dir, Provider<ObjectMapper> mapper, Provider<Stats> stats) {
        this.dir = dir;
        this.mapper = mapper;
        this.stats = stats;
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                throw new ConfigurationError("Could not create " + dir);
            }
        }
    }

    public DateTime getNewestDownloaded() {
        List<DateTime> l = new ArrayList<>(items.size());
        for (ModuleItem item : items) {
            l.add(item.getDownloaded());
        }
        Collections.sort(l);
        return l.isEmpty() ? new DateTime(0) : l.get(l.size() - 1);
    }

    public String getCombinedHash() {
        StringBuilder sb = new StringBuilder();
        for (ModuleItem item : this) { // must be sorted
            sb.append(item.getHash());
        }
        return sb.toString();
    }

    private Pattern PAT = Pattern.compile("(.*)\\.(.*)");
    public static final int COPY_BUFFER_SIZE = 2048;

    public File getModuleFile(ModuleItem item) {
        File modulesDir = new File(dir, item.getCodeNameBase());
        File moduleFile = new File(modulesDir, item.getHash() + ".nbm");
        return moduleFile;
    }

    public int size() {
        return items.size();
    }

    public ModuleItem add(InfoFile info, InputStream module, String url, String hash, boolean useOrigUrl) throws IOException {
        String codeName;
        Map<String, Object> metadata;
        try {
            codeName = info.getModuleCodeName();
            metadata = info.toMap();
        } catch (XPathExpressionException ex) {
            throw new IOException(ex);
        }
        File moduleDir = new File(dir, codeName);
        File nbmFile = new File(moduleDir, hash + ".nbm");
        File mdFile = new File(moduleDir, hash + ".json");
        if (nbmFile.exists() && mdFile.exists()) {
            return null;
        }
        boolean moduleDirCreated = false;
        try {
            if (!moduleDir.exists()) {
                moduleDirCreated = moduleDir.mkdirs();
                if (!moduleDirCreated) {
                    throw new IOException("Could not create " + moduleDir);
                }
            }
            if (!nbmFile.exists()) {
                System.out.println("Create " + nbmFile);
                if (!nbmFile.createNewFile()) {
                    throw new IOException("Could not create " + nbmFile);
                }
            }
            if (!mdFile.exists()) {
                if (!mdFile.createNewFile()) {
                    throw new IOException("Could not create " + mdFile);
                }
            }

            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(nbmFile), COPY_BUFFER_SIZE)) {
                try (InputStream in = module) {
                    Streams.copy(in, out, COPY_BUFFER_SIZE);
                }
            }
            metadata.put("downloadsize", Long.toString(nbmFile.length()));
            Map<String, Object> mdInfo = new HashMap<>();
            mdInfo.put("metadata", metadata);
            mdInfo.put("downloaded", DateTime.now().getMillis());
            mdInfo.put("from", url);
            mdInfo.put("hash", hash);
            mdInfo.put("useOriginalURL", useOrigUrl);
            mdInfo.put("codeNameBase", codeName);
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(mdFile), COPY_BUFFER_SIZE)) {
                mapper.get().writeValue(out, mdInfo);
            }
            ModuleItem old = null;
            for (ModuleItem i : this.items) {
                if (i.getCodeNameBase().equals(codeName)) {
                    old = i;
                    break;
                }
            }
            ModuleItem item = mapper.get().readValue(mdFile, ModuleItem.class);
            this.items.add(item);
            if (old != null) {
                this.items.remove(old);
            }
            stats.get().logIngest(item);
            return item;
        } catch (Exception e) {
            if (mdFile.exists()) {
                mdFile.delete();
            }
            if (nbmFile.exists()) {
                nbmFile.delete();
            }
            if (moduleDir.list().length == 0) {
                moduleDir.delete();
            }
            throw e instanceof IOException ? ((IOException) e) : new IOException(e);
        }
    }

    void scan() {
        this.items.clear();
        Map<String, Map<String, Pair>> pairs = new HashMap<>();
        for (File moduleDir : dir.listFiles()) {
            if (moduleDir.isDirectory()) {
                String cnb = moduleDir.getName();
                Map<String, Pair> pairForHash = pairs.get(cnb);
                if (pairForHash == null) {
                    pairs.put(cnb, pairForHash = new HashMap<>());
                }
                for (File mf : moduleDir.listFiles()) {
                    if (mf.isFile()) {
                        String hash = mf.getName();
                        Matcher m = PAT.matcher(hash);
                        if (m.find()) {
                            hash = m.group(1);
                        }
                        Pair pp = pairForHash.get(hash);
                        if (pp == null) {
                            pairForHash.put(hash, pp = new Pair());
                        }
                        if (mf.getName().endsWith(".json")) {
                            pp.manifest = mf;
                        } else if (mf.getName().endsWith(".nbm")) {
                            pp.nbm = mf;
                        }
                    }
                }
                for (String key : new LinkedList<>(pairForHash.keySet())) {
                    Pair pair = pairForHash.get(key);
                    if (!pair.isComplete()) {
                        System.out.println("  Incomplete file pair " + pair + " - for hash " + key + " ignoring");
                        pairForHash.remove(key);
                    }
                }
            }
        }
        for (Map.Entry<String, Map<String, Pair>> e : pairs.entrySet()) {
            Map<String, Pair> m = e.getValue();
            List<ModuleItem> items = new LinkedList<>();
            for (Map.Entry<String, Pair> e1 : m.entrySet()) {
                if (!e1.getValue().isComplete()) {
                    continue;
                }
                File md = e1.getValue().manifest;
                try {
                    ModuleItem item = mapper.get().readValue(md, ModuleItem.class);
                    items.add(item);
                } catch (IOException ex) {
                    Exceptions.printStackTrace(ex);
                }
            }
            Collections.sort(items);
            if (!items.isEmpty()) {
                this.items.add(items.iterator().next());
            }
        }
    }

    static class ModuleEntry {

        public final ModuleItem item;
        public final File metadata;

        public ModuleEntry(ModuleItem item, File metadata) {
            this.item = item;
            this.metadata = metadata;
        }
    }

    private static class Pair {

        public File manifest;
        public File nbm;

        public boolean isComplete() {
            return manifest != null && nbm != null;
        }

        @Override
        public String toString() {
            return "manifest: " + manifest + " nbm: " + nbm;
        }
    }
}

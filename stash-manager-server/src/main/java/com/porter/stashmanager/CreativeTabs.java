package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Creative-menu tabs for items whose bot didn't send one (PROTOCOL.md, "Additions: creative tabs for bots that
 * don't send them"). One table per Minecraft version, shipped as resources at creative-tabs/&lt;mc&gt;.json:
 * {@code {"version": "1.21.4", "tabs": [...], "items": {"minecraft:stone": ["building_blocks", 12], ...}}}.
 * With no tables at all, {@link #fill} changes nothing and items stay "other".
 */
final class CreativeTabs {
    private static final String DIR = "creative-tabs";
    static final CreativeTabs NONE = new CreativeTabs(List.of());

    /** One version's table: item id -> [tab, order]. */
    record Table(String version, int[] parts, Map<String, Entry> items) {
    }

    record Entry(String tab, int order) {
    }

    private final List<Table> tables; // newest first

    CreativeTabs(List<Table> tables) {
        List<Table> sorted = new ArrayList<>(tables);
        sorted.sort((a, b) -> compare(b.parts, a.parts));
        this.tables = List.copyOf(sorted);
    }

    int size() {
        return tables.size();
    }

    // ---------- loading ----------

    /** The tables shipped with the server (in the jar, or the resources folder when run from the build). */
    static CreativeTabs bundled() {
        List<Table> out = new ArrayList<>();
        try {
            URL source = CreativeTabs.class.getProtectionDomain().getCodeSource().getLocation();
            Path where = source == null ? null : Path.of(source.toURI());
            if (where != null && Files.isRegularFile(where)) {
                try (JarFile jar = new JarFile(where.toFile())) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry e = entries.nextElement();
                        String name = e.getName();
                        if (e.isDirectory() || !name.startsWith(DIR + "/") || !name.endsWith(".json") || name.indexOf('/', DIR.length() + 1) >= 0) continue;
                        try (InputStream in = jar.getInputStream(e)) {
                            add(out, name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                }
            } else {
                URL dir = CreativeTabs.class.getClassLoader().getResource(DIR);
                if (dir != null && "file".equals(dir.getProtocol())) out.addAll(load(Path.of(dir.toURI())).tables);
            }
        } catch (IOException | URISyntaxException | RuntimeException e) {
            System.err.println("Could not read the creative-tab tables: " + e);
        }
        if (out.isEmpty()) System.out.println("No creative-tab tables found; items without a tab stay in \"Other\".");
        return new CreativeTabs(out);
    }

    /** Every *.json table in a folder (for tests and the resources folder). A missing folder means no tables. */
    static CreativeTabs load(Path dir) throws IOException {
        List<Table> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return new CreativeTabs(out);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : files) add(out, f.getFileName().toString(), Files.readString(f, StandardCharsets.UTF_8));
        }
        return new CreativeTabs(out);
    }

    private static void add(List<Table> out, String name, String text) {
        try {
            Table t = parse(text);
            if (t == null) {
                System.err.println("Skipping creative-tab table " + name + ": bad version");
                return;
            }
            for (Table other : out) {
                if (compare(other.parts, t.parts) == 0) {
                    System.err.println("Skipping creative-tab table " + name + ": version " + t.version + " twice");
                    return;
                }
            }
            out.add(t);
        } catch (RuntimeException e) {
            System.err.println("Skipping creative-tab table " + name + ": " + e);
        }
    }

    /** A table from its JSON, or null when its version is missing or malformed. Bad item entries are skipped. */
    static Table parse(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        JsonElement v = root.get("version");
        String version = v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
        int[] parts = parts(version);
        if (parts == null) return null;
        Map<String, Entry> items = new HashMap<>();
        JsonElement ie = root.get("items");
        if (ie != null && ie.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : ie.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonArray()) continue;
                JsonArray a = e.getValue().getAsJsonArray();
                if (a.size() < 2 || !a.get(0).isJsonPrimitive() || !a.get(0).getAsJsonPrimitive().isString()
                    || !a.get(1).isJsonPrimitive() || !a.get(1).getAsJsonPrimitive().isNumber()) continue;
                String tab = a.get(0).getAsString();
                if (tab.isEmpty() || tab.length() > 40 || tab.equals("other")) continue;
                items.put(e.getKey(), new Entry(tab, a.get(1).getAsInt()));
            }
        }
        return new Table(version, parts, Map.copyOf(items));
    }

    // ---------- versions ----------

    /** "1.21.4" -> {1, 21, 4}; null when it isn't dot-separated whole numbers. */
    static int[] parts(String version) {
        if (version == null || version.isEmpty() || version.length() > 32) return null;
        String[] s = version.split("\\.", -1);
        int[] out = new int[s.length];
        try {
            for (int i = 0; i < s.length; i++) {
                if (s[i].isEmpty()) return null;
                out[i] = Integer.parseInt(s[i]);
                if (out[i] < 0) return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /** Compares versions part by part; missing parts count as 0. */
    static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    /**
     * The table for an index's mcVersion: an exact match, else the nearest lower version with the same major.minor,
     * else the newest table. Null only when there are no tables.
     */
    Table pick(String mcVersion) {
        if (tables.isEmpty()) return null;
        int[] want = parts(mcVersion);
        if (want == null) return tables.get(0);
        Table lower = null;
        for (Table t : tables) { // newest first, so the first lower match is the nearest
            int c = compare(t.parts, want);
            if (c == 0) return t;
            if (lower == null && c < 0 && sameMinor(t.parts, want)) lower = t;
        }
        return lower != null ? lower : tables.get(0);
    }

    private static boolean sameMinor(int[] a, int[] b) {
        return (a.length > 0 ? a[0] : 0) == (b.length > 0 ? b[0] : 0) && (a.length > 1 ? a[1] : 0) == (b.length > 1 ? b[1] : 0);
    }

    // ---------- filling ----------

    /**
     * Fills tab and tabOrder of every item in the index's items array whose tab is missing, null or "other", by its
     * id, from the table for the index's mcVersion. A real tab is never changed; an id not in the table stays as it
     * is. Returns how many items were filled.
     */
    int fill(JsonObject index) {
        if (tables.isEmpty() || !index.has("items") || !index.get("items").isJsonArray()) return 0;
        JsonElement mc = index.get("mcVersion");
        Table t = pick(mc != null && mc.isJsonPrimitive() && mc.getAsJsonPrimitive().isString() ? mc.getAsString() : null);
        int filled = 0;
        for (JsonElement e : index.getAsJsonArray("items")) {
            if (!e.isJsonObject()) continue;
            JsonObject item = e.getAsJsonObject();
            JsonElement tab = item.get("tab");
            boolean other = tab == null || tab.isJsonNull()
                || (tab.isJsonPrimitive() && tab.getAsJsonPrimitive().isString() && tab.getAsString().equals("other"));
            if (!other) continue;
            JsonElement id = item.get("id");
            if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) continue;
            Entry en = t.items.get(id.getAsString());
            if (en == null) continue;
            item.addProperty("tab", en.tab);
            item.addProperty("tabOrder", en.order);
            filled++;
        }
        return filled;
    }
}

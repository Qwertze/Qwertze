package com.porter.stashmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One recipe set per Minecraft version (CRAFT-CONTRACT.md, "Recipe data"), built in the same one-time pass as the icon
 * set: {@link IconPacks} owns the schedule and the jar, this class reads the recipe and item-tag entries of that jar,
 * keeps them under {@code <data>/recipes/<mc>/raw/}, and writes {@code table.json}, {@code manifest.json} and the
 * {@code .complete} marker last. A set whose normaliser {@code format} is older is rebuilt from {@code raw/} without a jar.
 * Nothing from the jar goes anywhere but the data folder.
 */
final class RecipePacks implements RecipeSource {
    static final String MARKER = ".complete";
    static final String MANIFEST = "manifest.json";
    static final String TABLE = "table.json";
    static final int MAX_RECIPES = 4096;
    static final int MAX_TAGS = 1024;
    static final int MAX_ENTRY = 64 * 1024;
    static final long MAX_TOTAL = 16L * 1024 * 1024;
    static final int MAX_JAR_ENTRIES = 200_000;
    static final long MAX_UNCOMPRESSED = 2L * 1024 * 1024 * 1024;
    private static final Pattern RECIPE_ENTRY = Pattern.compile("^data/minecraft/recipe/([a-z0-9_/]{1,128})\\.json$");
    private static final Pattern TAG_ENTRY = Pattern.compile("^data/minecraft/tags/item/([a-z0-9_/]{1,128})\\.json$");
    private static final Pattern ITEM_ENTRY = Pattern.compile("^assets/minecraft/items/([a-z0-9_]{1,128})\\.json$");
    private static final Pattern NAME = Pattern.compile("^[a-z0-9_/]{1,128}$");

    enum State { BUILDING, WAITING, READY, FAILED, NONE }

    /** The set read back from disk, with the numbers of its manifest. */
    record Loaded(RecipeTable table, JsonObject manifest) {
    }

    private final Path dir;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, RecipeTable> tables = new ConcurrentHashMap<>();
    private volatile Predicate<String> driver = v -> false;

    RecipePacks(Path dataDir) {
        this.dir = dataDir.resolve("recipes").toAbsolutePath().normalize();
    }

    /** IconPacks wires itself in: it runs the pass for a version and says whether it took the request. */
    void driver(Predicate<String> driver) {
        this.driver = driver;
    }

    // ---------- the source ----------

    @Override
    public void request(String mcVersion) {
        if (mcVersion == null || !IconService.MC_VERSION.matcher(mcVersion).matches()) return;
        if (states.putIfAbsent(mcVersion, State.BUILDING) != null) return;
        if (!driver.test(mcVersion)) states.remove(mcVersion, State.BUILDING); // refused (limits): not remembered
    }

    @Override
    public RecipeTable table(String mcVersion) {
        if (mcVersion == null || states.get(mcVersion) != State.READY) return null;
        return tables.get(mcVersion);
    }

    @Override
    public String state(String mcVersion) {
        State s = mcVersion == null ? null : states.get(mcVersion);
        if (s == null) return "none";
        return switch (s) {
            case READY -> tables.containsKey(mcVersion) ? "ready" : "failed";
            case BUILDING, WAITING -> "building";
            case FAILED -> "failed";
            case NONE -> "none";
        };
    }

    State rawState(String mcVersion) {
        return states.get(mcVersion);
    }

    void mark(String mcVersion, State s) {
        states.put(mcVersion, s);
    }

    /** Waits until the version's recipe set is no longer being built or waiting for a retry (tests). */
    boolean await(String mcVersion, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            State s = states.get(mcVersion);
            if (s != State.BUILDING && s != State.WAITING) return true;
            if (System.currentTimeMillis() > end) return false;
            Thread.sleep(10);
        }
    }

    boolean has(String mcVersion) {
        return states.containsKey(mcVersion);
    }

    private Path setDir(String v) {
        return dir.resolve(v);
    }

    // ---------- on disk ----------

    /**
     * Whether the version's set is complete on disk (marker, manifest and table agree, format current). A set of an older
     * format is first rebuilt from raw/ when that is there. On a complete set the table is loaded for use.
     */
    boolean complete(String v) {
        Path d = setDir(v);
        try {
            Loaded l = read(d);
            if (l == null) return false;
            tables.put(v, l.table());
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Reads and checks a complete set; null when it is not complete or of another format. Throws on damage. */
    private static Loaded read(Path d) throws IOException {
        if (!Files.isRegularFile(d.resolve(MARKER)) || !Files.isRegularFile(d.resolve(MANIFEST)) || !Files.isRegularFile(d.resolve(TABLE))) return null;
        JsonObject m = JsonParser.parseString(Files.readString(d.resolve(MANIFEST), StandardCharsets.UTF_8)).getAsJsonObject();
        if (m.get("format").getAsInt() != RecipeTable.FORMAT) return null;
        RecipeTable t = RecipeTable.parse(Files.readString(d.resolve(TABLE), StandardCharsets.UTF_8));
        if (t.format != RecipeTable.FORMAT || !t.tableHash().equals(m.get("tableHash").getAsString())) return null;
        return new Loaded(t, m);
    }

    /** The normaliser's format went up (or table.json is damaged) but raw/ is there: rebuild the table with no jar. */
    boolean rebuildFromRaw(String v) {
        Path d = setDir(v);
        try {
            Path rawRecipes = d.resolve("raw").resolve("recipe");
            if (!Files.isDirectory(rawRecipes) || !Files.isRegularFile(d.resolve(MANIFEST))) return false;
            JsonObject old = JsonParser.parseString(Files.readString(d.resolve(MANIFEST), StandardCharsets.UTF_8)).getAsJsonObject();
            Raw raw = readRaw(d.resolve("raw"));
            write(v, d, raw, old.has("jarSha1") ? old.get("jarSha1").getAsString() : "", false);
            System.out.println("Recipes " + v + ": table rebuilt from the saved raw files (format " + RecipeTable.FORMAT + ").");
            return complete(v);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Raw entries of one version. recipes and tags: name (no ".json") to JSON text. */
    record Raw(Map<String, byte[]> recipes, Map<String, byte[]> tags, java.util.Set<String> items) {
    }

    /** At most this many folders deep (readRaw walks the same depth), no leading, trailing or doubled slash. */
    static final int MAX_DEPTH = 8;

    private static boolean wellNamed(String name) {
        if (!NAME.matcher(name).matches() || name.startsWith("/") || name.endsWith("/") || name.contains("//")) return false;
        int slashes = 0;
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) == '/') slashes++;
        return slashes < MAX_DEPTH;
    }

    /** Reads the recipe and item-tag entries (and the item definition names) of a jar. Checks every cap. */
    static Raw readJar(Path jar) throws IOException {
        Map<String, byte[]> recipes = new TreeMap<>();
        Map<String, byte[]> tags = new TreeMap<>();
        java.util.Set<String> items = new TreeSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            long declared = 0, total = 0;
            int n = 0;
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (++n > MAX_JAR_ENTRIES) throw new ClientJarFetcher.PermanentException("jar has too many entries");
                if (e.getSize() > 0) declared += e.getSize();
                if (declared > MAX_UNCOMPRESSED) throw new ClientJarFetcher.PermanentException("jar is too large when unpacked");
                if (e.isDirectory()) continue;
                String name = e.getName();
                Matcher rm = RECIPE_ENTRY.matcher(name);
                Matcher tm = rm.matches() ? null : TAG_ENTRY.matcher(name);
                if (rm.matches() || (tm != null && tm.matches())) {
                    boolean isRecipe = rm.matches();
                    String key = isRecipe ? rm.group(1) : tm.group(1);
                    if (!wellNamed(key)) continue;
                    if (isRecipe ? recipes.size() >= MAX_RECIPES : tags.size() >= MAX_TAGS) {
                        throw new ClientJarFetcher.PermanentException("jar has too many " + (isRecipe ? "recipe" : "tag") + " entries");
                    }
                    if (e.getSize() > MAX_ENTRY) throw new ClientJarFetcher.PermanentException("a recipe entry is larger than " + MAX_ENTRY + " bytes");
                    byte[] data;
                    try (InputStream in = zip.getInputStream(e)) {
                        data = in.readNBytes(MAX_ENTRY + 1); // the declared size can lie
                    }
                    if (data.length > MAX_ENTRY) throw new ClientJarFetcher.PermanentException("a recipe entry is larger than " + MAX_ENTRY + " bytes");
                    total += data.length;
                    if (total > MAX_TOTAL) throw new ClientJarFetcher.PermanentException("recipe data larger than " + MAX_TOTAL + " bytes");
                    (isRecipe ? recipes : tags).put(key, data);
                } else {
                    Matcher im = ITEM_ENTRY.matcher(name);
                    if (im.matches() && items.size() < IconPacks.MAX_ITEMS) items.add(im.group(1)); // the name only
                }
            }
        }
        if (recipes.isEmpty()) throw new ClientJarFetcher.PermanentException("the jar has no recipes");
        return new Raw(recipes, tags, items);
    }

    /** Reads raw/ back (same caps and name rules; nothing outside raw/recipe, raw/tags/item and raw/items.json). */
    static Raw readRaw(Path raw) throws IOException {
        Map<String, byte[]> recipes = new TreeMap<>();
        Map<String, byte[]> tags = new TreeMap<>();
        long total = 0;
        for (int pass = 0; pass < 2; pass++) {
            Path base = pass == 0 ? raw.resolve("recipe") : raw.resolve("tags").resolve("item");
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> walk = Files.walk(base, MAX_DEPTH)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    String rel = base.relativize(p).toString().replace('\\', '/');
                    if (!rel.endsWith(".json")) continue;
                    String key = rel.substring(0, rel.length() - 5);
                    if (!wellNamed(key)) continue;
                    if (Files.size(p) > MAX_ENTRY) throw new IOException("raw entry too large");
                    byte[] data = Files.readAllBytes(p);
                    total += data.length;
                    if (total > MAX_TOTAL) throw new IOException("raw data too large");
                    Map<String, byte[]> target = pass == 0 ? recipes : tags;
                    if (target.size() >= (pass == 0 ? MAX_RECIPES : MAX_TAGS)) throw new IOException("too many raw entries");
                    target.put(key, data);
                }
            }
        }
        java.util.Set<String> items = new TreeSet<>();
        Path ip = raw.resolve("items.json");
        if (Files.isRegularFile(ip) && Files.size(ip) <= 1024 * 1024) {
            for (var e : JsonParser.parseString(Files.readString(ip, StandardCharsets.UTF_8)).getAsJsonArray()) {
                String s = e.getAsString();
                if (s.matches("[a-z0-9_]{1,128}") && items.size() < IconPacks.MAX_ITEMS) items.add(s);
            }
        }
        if (recipes.isEmpty()) throw new IOException("no raw recipes");
        return new Raw(recipes, tags, items);
    }

    /** Builds the set from a jar: raw files, table.json, manifest.json, then the marker. */
    void build(String v, Path jar) throws IOException {
        long start = System.nanoTime();
        Raw raw = readJar(jar);
        write(v, setDir(v), raw, sha1(Files.readAllBytes(jar)), true);
        complete(v);
        RecipeTable t = tables.get(v);
        System.out.printf("Recipes %s: %d crafting recipes (%d raw files) in %.1f s.%n", v, t == null ? 0 : t.recipes().size(),
            raw.recipes().size() + raw.tags().size(), (System.nanoTime() - start) / 1e9);
    }

    private void write(String v, Path d, Raw raw, String jarSha1, boolean writeRaw) throws IOException {
        Map<String, JsonObject> recipes = parseAll(raw.recipes());
        Map<String, JsonObject> tags = parseAll(raw.tags());
        RecipeNormalizer.Result r = RecipeNormalizer.normalize(v, recipes, tags, raw.items());
        if (r.table().recipes().isEmpty()) throw new ClientJarFetcher.PermanentException("the jar's recipes gave an empty table");
        Files.createDirectories(d);
        AtomicFiles.ownerOnlyDir(dir);
        AtomicFiles.ownerOnlyDir(d);
        Files.deleteIfExists(d.resolve(MARKER));
        if (writeRaw) {
            deleteTree(d.resolve("raw"));
            Path rr = Files.createDirectories(d.resolve("raw").resolve("recipe"));
            Path rt = Files.createDirectories(d.resolve("raw").resolve("tags").resolve("item"));
            for (Map.Entry<String, byte[]> e : raw.recipes().entrySet()) put(rr, e.getKey(), e.getValue());
            for (Map.Entry<String, byte[]> e : raw.tags().entrySet()) put(rt, e.getKey(), e.getValue());
            com.google.gson.JsonArray items = new com.google.gson.JsonArray();
            raw.items().forEach(items::add);
            AtomicFiles.write(d.resolve("raw").resolve("items.json"), items.toString().getBytes(StandardCharsets.UTF_8), true);
        }
        AtomicFiles.write(d.resolve(TABLE), r.table().toJsonText().getBytes(StandardCharsets.UTF_8), true);
        JsonObject m = new JsonObject();
        m.addProperty("mc", v);
        m.addProperty("format", RecipeTable.FORMAT);
        m.addProperty("jarSha1", jarSha1);
        m.addProperty("tableHash", r.table().tableHash());
        m.addProperty("recipes", r.table().recipes().size());
        m.addProperty("otherStations", r.table().otherStations().size());
        m.addProperty("dropped", r.dropped());
        AtomicFiles.write(d.resolve(MANIFEST), m.toString().getBytes(StandardCharsets.UTF_8), true);
        AtomicFiles.write(d.resolve(MARKER), new byte[0], true); // last: the set is complete only now
        tables.remove(v);
    }

    private static void put(Path base, String name, byte[] data) throws IOException {
        Path target = base.resolve(name + ".json").normalize();
        if (!target.startsWith(base)) throw new IOException("bad entry name"); // can't happen: names are matched first
        AtomicFiles.write(target, data, true); // owner only, like the other files of the set
    }

    private static Map<String, JsonObject> parseAll(Map<String, byte[]> raw) {
        Map<String, JsonObject> out = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : raw.entrySet()) {
            try {
                var j = JsonParser.parseString(new String(e.getValue(), StandardCharsets.UTF_8));
                if (j.isJsonObject()) out.put(e.getKey(), j.getAsJsonObject());
            } catch (RuntimeException | StackOverflowError ignored) {
                // one broken file does not stop the set
            }
        }
        return out;
    }

    private static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path d) {
        if (!Files.exists(d)) return;
        try (Stream<Path> walk = Files.walk(d)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}

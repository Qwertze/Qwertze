package com.porter.qwerte.craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Builds the recipe table of the running game from its own jar (CRAFT-CONTRACT.md "Recipe tables for the bots": the addon may build the
 * same table instead of fetching it). It is the server's format-1 normaliser (stash-manager-server {@code RecipeNormalizer}) ported
 * line for line, so the {@link Recipes#tableHash} of the result is the server's for the same version; a test checks that against the jar
 * on the machine (skipped when there is none). Nothing derived from the game is stored in the repository: the table is built at run time.
 * <p>
 * Normalisation (format 1): only {@code crafting_shaped} and {@code crafting_shapeless} are kept (the other types put their result in
 * {@code otherStations}); recipes whose result has components or whose ingredient leaves a crafting remainder (buckets, honey bottle,
 * dragon's breath) are dropped; tags are expanded (at most 8 levels, cycle safe, an unknown tag drops the recipe); alternatives are
 * de-duplicated and sorted; a shaped pattern is shrunk to its used rows and columns; {@code id} is {@code "minecraft:" + path}.
 * <p>
 * Pure apart from {@link #fromGameJar}. Not for the game thread: reading the jar takes a moment.
 */
public final class RecipeBuilder {
    private RecipeBuilder() {
    }

    public static final int FORMAT = 1;
    static final int MAX_TAG_DEPTH = 8;
    /** Items whose use in a crafting grid leaves something behind (a bucket, a bottle): recipes with them are left out. */
    static final Set<String> REMAINDER = Set.of("minecraft:milk_bucket", "minecraft:water_bucket", "minecraft:lava_bucket",
        "minecraft:powder_snow_bucket", "minecraft:honey_bottle", "minecraft:dragon_breath");
    private static final List<String> PRIORITY = List.of("smelting", "blasting", "smoking", "campfire_cooking", "stonecutting",
        "smithing", "transmute", "special");
    private static final Pattern ID_OK = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,128}$");

    // The limits of the server's jar reader
    static final int MAX_RECIPES = 4096, MAX_TAGS = 1024, MAX_ENTRY = 64 * 1024, MAX_JAR_ENTRIES = 200_000;
    static final long MAX_TOTAL = 16L * 1024 * 1024;
    private static final Pattern RECIPE_ENTRY = Pattern.compile("^data/minecraft/recipe/([a-z0-9_/]{1,128})\\.json$");
    private static final Pattern TAG_ENTRY = Pattern.compile("^data/minecraft/tags/item/([a-z0-9_/]{1,128})\\.json$");
    private static final Pattern ITEM_ENTRY = Pattern.compile("^assets/minecraft/items/([a-z0-9_]{1,128})\\.json$");

    /** A table and how many recipes were dropped for an unknown, too deeply nested or empty tag (or a shape that is not known). */
    public record Result(JsonObject table, int dropped, int recipes) {
    }

    private record R(String id, boolean shaped, int w, int h, List<List<String>> cells, String result, int count) {
    }

    // ---------- reading the jar ----------

    /** The raw recipe and tag JSON of a jar, and the item names it has (for naming the item of a result-less special recipe). */
    public record Raw(Map<String, JsonObject> recipes, Map<String, JsonObject> tags, Set<String> items) {
    }

    /** Reads the recipe and item-tag entries of a Minecraft jar (names matched first, sizes capped, nothing else read). */
    public static Raw readJar(Path jar) throws IOException {
        Map<String, JsonObject> recipes = new TreeMap<>(), tags = new TreeMap<>();
        Set<String> items = new TreeSet<>();
        long total = 0;
        int n = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (++n > MAX_JAR_ENTRIES) throw new IOException("jar has too many entries");
                String name = e.getName();
                Matcher rm = RECIPE_ENTRY.matcher(name);
                Matcher tm = rm.matches() ? null : TAG_ENTRY.matcher(name);
                boolean isRecipe = rm.matches();
                if (isRecipe || (tm != null && tm.matches())) {
                    String key = (isRecipe ? rm : tm).group(1);
                    if (isRecipe ? recipes.size() >= MAX_RECIPES : tags.size() >= MAX_TAGS) throw new IOException("too many entries");
                    if (e.getSize() > MAX_ENTRY) throw new IOException("an entry is larger than " + MAX_ENTRY + " bytes");
                    byte[] data;
                    try (InputStream in = zip.getInputStream(e)) {
                        data = in.readNBytes(MAX_ENTRY + 1);
                    }
                    if (data.length > MAX_ENTRY) throw new IOException("an entry is larger than " + MAX_ENTRY + " bytes");
                    total += data.length;
                    if (total > MAX_TOTAL) throw new IOException("recipe data larger than " + MAX_TOTAL + " bytes");
                    try {
                        JsonElement j = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
                        if (j.isJsonObject()) (isRecipe ? recipes : tags).put(key, j.getAsJsonObject());
                    } catch (RuntimeException ex) {
                        // an entry that is not JSON is left out, as the server does
                    }
                } else {
                    Matcher im = ITEM_ENTRY.matcher(name);
                    if (im.matches() && items.size() < 16384) items.add(im.group(1));
                }
            }
        }
        return new Raw(recipes, tags, items);
    }

    /**
     * The jar of the running game: the one a vanilla data file is read from. Null when the data is not in a jar (a folder, or a class
     * loader that hides it).
     */
    public static Path gameJar() {
        try {
            URL url = RecipeBuilder.class.getClassLoader().getResource("data/minecraft/recipe/chest.json");
            if (url == null || !"jar".equals(url.getProtocol())) return null;
            String spec = url.getPath();
            int bang = spec.indexOf("!/");
            if (bang < 0) return null;
            return Path.of(new java.net.URI(spec.substring(0, bang)));
        } catch (java.net.URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** The table of the running game, built from its jar, or null when the jar can't be found or read. Blocking: call it off the game thread. */
    public static Result fromGameJar(String version) {
        Path jar = gameJar();
        if (jar == null) return null;
        try {
            Raw raw = readJar(jar);
            if (raw.recipes().isEmpty()) return null;
            return build(version, raw.recipes(), raw.tags(), raw.items());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---------- the normaliser ----------

    private static final class Norm {
        final Map<String, JsonObject> tags;
        final Map<String, Expanded> expanded = new HashMap<>();
        int dropped;

        Norm(Map<String, JsonObject> tags) {
            this.tags = tags;
        }
    }

    /**
     * @param recipes   file name without ".json" (under data/minecraft/recipe/) to its JSON
     * @param tags      the same for data/minecraft/tags/item/
     * @param itemNames item names (no namespace) the version has, used only to name the item of a result-less special recipe (null: none known)
     */
    public static Result build(String version, Map<String, JsonObject> recipes, Map<String, JsonObject> tags, Set<String> itemNames) {
        Norm n = new Norm(tags);
        List<R> out = new ArrayList<>();
        Set<String> included = new HashSet<>();
        Map<String, Set<String>> stations = new HashMap<>();
        for (Map.Entry<String, JsonObject> e : new TreeMap<>(recipes).entrySet()) {
            String id = "minecraft:" + e.getKey();
            JsonObject json = e.getValue();
            if (!ID_OK.matcher(id).matches()) {
                n.dropped++;
                continue;
            }
            try {
                String type = norm(string(json.get("type")));
                String path = type.substring(type.indexOf(':') + 1);
                String station = null;
                String res0 = resultId(json);
                if (path.equals("smelting") || path.equals("blasting") || path.equals("smoking") || path.equals("campfire_cooking")
                    || path.equals("stonecutting")) {
                    station = path;
                } else if (path.startsWith("smithing")) {
                    station = "smithing";
                } else if (path.equals("crafting_transmute")) {
                    station = "transmute";
                } else if (path.startsWith("crafting_special_") || path.equals("crafting_decorated_pot")) {
                    station = "special";
                    if (res0 == null) {
                        String suffix = path.equals("crafting_decorated_pot") ? "decorated_pot" : path.substring("crafting_special_".length());
                        if (itemNames != null && itemNames.contains(suffix)) res0 = "minecraft:" + suffix;
                    }
                }
                if (station != null) {
                    if (res0 != null) stations.computeIfAbsent(res0, k -> new HashSet<>()).add(station);
                    continue;
                }
                boolean shaped = path.equals("crafting_shaped");
                if (!shaped && !path.equals("crafting_shapeless")) continue;
                R r = crafting(n, id, json, shaped);
                if (r == null) continue;
                if (!ID_OK.matcher(r.result()).matches() || r.cells().stream().anyMatch(c -> c != null && !c.stream().allMatch(a -> ID_OK.matcher(a).matches()))) {
                    n.dropped++;
                    continue;
                }
                out.add(r);
                included.add(r.result());
            } catch (RuntimeException ex) {
                n.dropped++; // a recipe in a shape this normaliser doesn't know
            }
        }
        Map<String, String> other = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : new TreeMap<>(stations).entrySet()) {
            if (included.contains(e.getKey())) continue;
            for (String p : PRIORITY) {
                if (e.getValue().contains(p)) {
                    other.put(e.getKey(), p);
                    break;
                }
            }
        }
        out.sort(Comparator.comparing(R::id));
        return new Result(toJson(version, out, other), n.dropped, out.size());
    }

    /** The table.json object (the shape the server serves and the bots parse). */
    private static JsonObject toJson(String version, List<R> recipes, Map<String, String> other) {
        JsonObject root = new JsonObject();
        root.addProperty("version", version);
        root.addProperty("format", FORMAT);
        JsonArray list = new JsonArray();
        for (R r : recipes) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("type", r.shaped() ? "shaped" : "shapeless");
            if (r.shaped()) {
                o.addProperty("w", r.w());
                o.addProperty("h", r.h());
            }
            JsonArray cells = new JsonArray();
            for (List<String> c : r.cells()) {
                if (c == null) {
                    cells.add(com.google.gson.JsonNull.INSTANCE);
                    continue;
                }
                JsonArray alts = new JsonArray();
                for (String a : c) alts.add(a);
                cells.add(alts);
            }
            o.add(r.shaped() ? "cells" : "ingredients", cells);
            o.addProperty("result", r.result());
            o.addProperty("count", r.count());
            list.add(o);
        }
        root.add("recipes", list);
        JsonObject os = new JsonObject();
        for (Map.Entry<String, String> e : other.entrySet()) os.addProperty(e.getKey(), e.getValue());
        root.add("otherStations", os);
        return root;
    }

    /** A crafting recipe, or null when it is left out (components, remainder, unknown tag). */
    private static R crafting(Norm n, String id, JsonObject json, boolean shaped) {
        JsonElement re = json.get("result");
        String result = resultId(json);
        if (result == null) return null;
        int count = 1;
        if (re != null && re.isJsonObject()) {
            JsonObject ro = re.getAsJsonObject();
            JsonElement comps = ro.get("components");
            if (comps != null && !(comps.isJsonObject() && comps.getAsJsonObject().isEmpty())) return null;
            if (ro.has("count")) count = ro.get("count").getAsInt();
        }
        if (count < 1) return null;
        List<List<String>> cells = new ArrayList<>();
        int w = 0, h = 0;
        if (shaped) {
            JsonObject key = json.getAsJsonObject("key");
            List<String> pattern = new ArrayList<>();
            for (JsonElement p : json.getAsJsonArray("pattern")) pattern.add(p.getAsString());
            String[] rows = shrink(pattern);
            if (rows.length == 0) return null;
            h = rows.length;
            for (String row : rows) w = Math.max(w, row.length());
            if (w > 3 || h > 3) return null; // does not fit a crafting grid
            Map<Character, List<String>> alts = new HashMap<>();
            for (String row : rows) {
                for (int i = 0; i < w; i++) {
                    char c = i < row.length() ? row.charAt(i) : ' ';
                    if (c == ' ') {
                        cells.add(null);
                        continue;
                    }
                    List<String> a = alts.get(c);
                    if (a == null) {
                        JsonElement ing = key.get(String.valueOf(c));
                        if (ing == null) return null;
                        a = ingredient(n, ing);
                        if (a == null) return null;
                        alts.put(c, a);
                    }
                    if (a.isEmpty() || hasRemainder(a)) return null;
                    cells.add(a);
                }
            }
        } else {
            for (JsonElement ing : json.getAsJsonArray("ingredients")) {
                List<String> a = ingredient(n, ing);
                if (a == null || a.isEmpty() || hasRemainder(a)) return null;
                cells.add(a);
            }
            if (cells.isEmpty()) return null;
        }
        return new R(id, shaped, w, h, cells, result, count);
    }

    private static boolean hasRemainder(List<String> alts) {
        for (String s : alts) if (REMAINDER.contains(s)) return true;
        return false;
    }

    /**
     * The pattern with empty outer rows and columns cut away (what the game does when it loads a shaped recipe). Patterns of vanilla
     * recipes are already tight, so this changes nothing there.
     */
    static String[] shrink(List<String> pattern) {
        int minFirst = Integer.MAX_VALUE, maxLast = 0, leadingEmpty = 0, trailingEmpty = 0;
        for (int i = 0; i < pattern.size(); i++) {
            String s = pattern.get(i);
            int first = 0;
            while (first < s.length() && s.charAt(first) == ' ') first++;
            int last = s.length() - 1;
            while (last >= 0 && s.charAt(last) == ' ') last--;
            minFirst = Math.min(minFirst, first);
            maxLast = Math.max(maxLast, last);
            if (last < 0) {
                if (leadingEmpty == i) leadingEmpty++;
                trailingEmpty++;
            } else {
                trailingEmpty = 0;
            }
        }
        if (pattern.size() == trailingEmpty) return new String[0];
        String[] out = new String[pattern.size() - trailingEmpty - leadingEmpty];
        for (int i = 0; i < out.length; i++) {
            String s = pattern.get(leadingEmpty + i);
            out[i] = s.length() <= minFirst ? "" : s.substring(minFirst, Math.min(s.length(), maxLast + 1));
        }
        return out;
    }

    // ---------- ingredients and tags ----------

    /** An ingredient as sorted, unique item ids; null (and counted as dropped) when a tag it uses can't be expanded. */
    private static List<String> ingredient(Norm n, JsonElement e) {
        Set<String> out = new TreeSet<>();
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (!addEntry(n, x, out)) {
                    n.dropped++;
                    return null;
                }
            }
        } else if (!addEntry(n, e, out)) {
            n.dropped++;
            return null;
        }
        return new ArrayList<>(out);
    }

    private static boolean addEntry(Norm n, JsonElement e, Set<String> out) {
        String s;
        if (e.isJsonPrimitive()) {
            s = e.getAsString();
        } else if (e.isJsonObject() && e.getAsJsonObject().has("item")) {
            s = e.getAsJsonObject().get("item").getAsString();
        } else if (e.isJsonObject() && e.getAsJsonObject().has("tag")) {
            s = "#" + e.getAsJsonObject().get("tag").getAsString();
        } else {
            throw new IllegalArgumentException("ingredient entry");
        }
        if (s.startsWith("#")) {
            List<String> items = tag(n, norm(s.substring(1)), 1, new HashSet<>());
            if (items == null || items.isEmpty()) return false;
            out.addAll(items);
        } else {
            out.add(norm(s));
        }
        return true;
    }

    /** A tag's items and how many levels of tags it spans (itself included). */
    private record Expanded(List<String> items, int levels) {
    }

    /** Items of a tag, nested tags included (at most MAX_TAG_DEPTH levels, cycle-safe); null when unknown or too deep. */
    private static List<String> tag(Norm n, String name, int depth, Set<String> visiting) {
        Expanded e = expand(n, name, visiting);
        if (e == null || depth + e.levels() - 1 > MAX_TAG_DEPTH) return null;
        return e.items();
    }

    private static Expanded expand(Norm n, String name, Set<String> visiting) {
        Expanded done = n.expanded.get(name);
        if (done != null) return done;
        if (!name.startsWith("minecraft:") || !visiting.add(name)) return null;
        try {
            JsonObject t = n.tags.get(name.substring("minecraft:".length()));
            if (t == null || !t.has("values") || !t.get("values").isJsonArray()) return null;
            Set<String> out = new TreeSet<>();
            int levels = 1;
            for (JsonElement v : t.getAsJsonArray("values")) {
                String s;
                if (v.isJsonPrimitive()) s = v.getAsString();
                else if (v.isJsonObject() && v.getAsJsonObject().has("id")) s = v.getAsJsonObject().get("id").getAsString();
                else return null;
                if (s.startsWith("#")) {
                    Expanded inner = expand(n, norm(s.substring(1)), visiting);
                    if (inner == null) return null;
                    out.addAll(inner.items());
                    levels = Math.max(levels, 1 + inner.levels());
                } else {
                    out.add(norm(s));
                }
            }
            Expanded r = new Expanded(Collections.unmodifiableList(new ArrayList<>(out)), levels);
            n.expanded.put(name, r);
            return r;
        } finally {
            visiting.remove(name);
        }
    }

    // ---------- small helpers ----------

    static String norm(String s) {
        return s.contains(":") ? s : "minecraft:" + s;
    }

    private static String string(JsonElement e) {
        return e == null || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    /** The result item id: a string, or an object with "id" (or the older "item"); null when there is none. */
    static String resultId(JsonObject o) {
        JsonElement r = o.get("result");
        if (r == null || r.isJsonNull()) return null;
        if (r.isJsonPrimitive()) return norm(r.getAsString());
        JsonObject ro = r.getAsJsonObject();
        if (ro.has("id")) return norm(ro.get("id").getAsString());
        if (ro.has("item")) return norm(ro.get("item").getAsString());
        return null;
    }
}

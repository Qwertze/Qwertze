package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns a version's raw recipe and item-tag JSON into a {@link RecipeTable} (CRAFT-CONTRACT.md, "Normalisation", format
 * 1). It must give the same table as {@code dev/recipes/GenRecipes}. Pure: no I/O, and only the JSON given is read.
 */
final class RecipeNormalizer {
    static final int MAX_TAG_DEPTH = 8;
    /** Items whose use in a crafting grid leaves something behind (a bucket, a bottle): recipes with them are left out. */
    static final Set<String> REMAINDER = Set.of("minecraft:milk_bucket", "minecraft:water_bucket", "minecraft:lava_bucket",
        "minecraft:powder_snow_bucket", "minecraft:honey_bottle", "minecraft:dragon_breath");
    private static final List<String> PRIORITY = List.of("smelting", "blasting", "smoking", "campfire_cooking", "stonecutting",
        "smithing", "transmute", "special");

    /** The table and how many recipes were dropped for an unknown, too deeply nested or empty tag. */
    record Result(RecipeTable table, int dropped) {
    }

    /** Item and recipe ids the table may hold (the same rule the order lines use). */
    private static final java.util.regex.Pattern ID_OK = java.util.regex.Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,128}$");

    private final Map<String, JsonObject> tags;
    private final Map<String, Expanded> expanded = new HashMap<>();
    private int dropped;

    private RecipeNormalizer(Map<String, JsonObject> tags) {
        this.tags = tags;
    }

    /**
     * recipes: file name without ".json" (under data/minecraft/recipe/) to its JSON. tags: the same for
     * data/minecraft/tags/item/. itemNames: item names (no namespace) the version has, used only to name the item of a
     * result-less special recipe (a null set means none are known).
     */
    static Result normalize(String version, Map<String, JsonObject> recipes, Map<String, JsonObject> tags, Set<String> itemNames) {
        RecipeNormalizer n = new RecipeNormalizer(tags);
        List<RecipeTable.Recipe> out = new ArrayList<>();
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
                RecipeTable.Recipe r = n.crafting(id, json, shaped);
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
        return new Result(new RecipeTable(version, RecipeTable.FORMAT, out, other), n.dropped);
    }

    /** A crafting recipe, or null when it is left out (components, remainder, unknown tag). */
    private RecipeTable.Recipe crafting(String id, JsonObject json, boolean shaped) {
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
                        a = ingredient(ing);
                        if (a == null) return null;
                        alts.put(c, a);
                    }
                    if (a.isEmpty() || hasRemainder(a)) return null;
                    cells.add(a);
                }
            }
        } else {
            for (JsonElement ing : json.getAsJsonArray("ingredients")) {
                List<String> a = ingredient(ing);
                if (a == null || a.isEmpty() || hasRemainder(a)) return null;
                cells.add(a);
            }
            if (cells.isEmpty()) return null;
        }
        return new RecipeTable.Recipe(id, shaped, w, h, cells, result, count);
    }

    private static boolean hasRemainder(List<String> alts) {
        for (String s : alts) if (REMAINDER.contains(s)) return true;
        return false;
    }

    /**
     * The pattern with empty outer rows and columns cut away (what the game does when it loads a shaped recipe). Patterns
     * of vanilla recipes are already tight, so this changes nothing there.
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
    private List<String> ingredient(JsonElement e) {
        Set<String> out = new TreeSet<>();
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (!addEntry(x, out)) {
                    dropped++;
                    return null;
                }
            }
        } else if (!addEntry(e, out)) {
            dropped++;
            return null;
        }
        return new ArrayList<>(out);
    }

    private boolean addEntry(JsonElement e, Set<String> out) {
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
            List<String> items = tag(norm(s.substring(1)), 1, new HashSet<>());
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
    private List<String> tag(String name, int depth, Set<String> visiting) {
        Expanded e = expand(name, visiting);
        if (e == null || depth + e.levels() - 1 > MAX_TAG_DEPTH) return null;
        return e.items();
    }

    private Expanded expand(String name, Set<String> visiting) {
        Expanded done = expanded.get(name);
        if (done != null) return done;
        if (!name.startsWith("minecraft:") || !visiting.add(name)) return null;
        try {
            JsonObject t = tags.get(name.substring("minecraft:".length()));
            if (t == null || !t.has("values") || !t.get("values").isJsonArray()) return null;
            Set<String> out = new TreeSet<>();
            int levels = 1;
            for (JsonElement v : t.getAsJsonArray("values")) {
                String s;
                if (v.isJsonPrimitive()) s = v.getAsString();
                else if (v.isJsonObject() && v.getAsJsonObject().has("id")) s = v.getAsJsonObject().get("id").getAsString();
                else return null;
                if (s.startsWith("#")) {
                    Expanded inner = expand(norm(s.substring(1)), visiting);
                    if (inner == null) return null;
                    out.addAll(inner.items());
                    levels = Math.max(levels, 1 + inner.levels());
                } else {
                    out.add(norm(s));
                }
            }
            Expanded r = new Expanded(Collections.unmodifiableList(new ArrayList<>(out)), levels);
            expanded.put(name, r);
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

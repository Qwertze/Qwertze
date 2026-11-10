package com.porter.qwerte.craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The crafting recipes of one Minecraft version. Since 0.4.0-dev.18 no table is shipped in the addon: it is fetched from the Stash
 * Manager server ({@code GET /api/v1/bot/recipes?mc=}, see {@code RecipeStore}) and cached in {@code meteor-client/qwerte}; until
 * there is one, {@link #current()} is null and everything that crafts is off. Clients get no full recipes from a server since 1.21.2,
 * so the game's own recipe manager is never used. Pure: no Minecraft classes, so it can be tested on its own.
 *
 * Only crafting_shaped and crafting_shapeless recipes are in the table. Items that have no such recipe but can be made
 * another way are listed with the station that makes them.
 */
public final class Recipes {
    /**
     * @param cells shaped: w*h cells in row order, null for an empty cell; shapeless: one entry per ingredient. Each cell is the list
     *              of items that may be put there.
     */
    public record Recipe(String id, boolean shaped, int w, int h, List<List<String>> cells, String result, int count) {
        /** How many cells hold an ingredient. */
        public int filled() {
            int n = 0;
            for (List<String> c : cells) if (c != null) n++;
            return n;
        }

        /** Cells that take the same alternatives, with how many there are of each. */
        public Map<List<String>, Integer> groups() {
            Map<List<String>, Integer> out = new LinkedHashMap<>();
            for (List<String> c : cells) if (c != null) out.merge(c, 1, Integer::sum);
            return out;
        }

        /** Where cell {@code i} sits in the 3x3 grid, 0 to 8 in row order. */
        public int gridIndex(int i) {
            return shaped ? (i / w) * 3 + (i % w) : i;
        }

        /** The cells that take an item, in order (the empty cells of a shaped recipe left out). */
        public List<List<String>> ingredients() {
            List<List<String>> out = new ArrayList<>();
            for (List<String> c : cells) if (c != null && !c.isEmpty()) out.add(c);
            return out;
        }

        /** Some cell of the recipe accepts this item id. */
        public boolean accepts(String item) {
            for (List<String> c : cells) if (c != null && c.contains(item)) return true;
            return false;
        }
    }

    private final String version, hash;
    private final Map<String, List<Recipe>> byResult = new HashMap<>();
    private final Map<String, Recipe> byId = new LinkedHashMap<>();
    private final Map<String, String> stations = new HashMap<>();
    private int size;

    private Recipes(String version, String hash) {
        this.version = version;
        this.hash = hash;
    }

    private static volatile Recipes current;

    /**
     * The table the addon crafts by: the one fetched from the server and cached (see {@code RecipeStore}), or null while there is none.
     * With null nothing is crafted (shulker boxes included) and no craft orders are accepted. Replaced whole, read from any thread.
     */
    public static Recipes current() {
        return current;
    }

    public static synchronized void setCurrent(Recipes table) {
        current = table;
    }

    /** Sets the table only if there is none (a compare-and-set): the local build never replaces what the server or the cache gave. @return true when set */
    public static synchronized boolean setIfAbsent(Recipes table) {
        if (current != null) return false;
        current = table;
        return true;
    }

    /** A table with no recipe at all (what a craft order is checked against when there is none: nothing matches). */
    public static Recipes empty() {
        return new Recipes("?", "");
    }

    public String version() {
        return version;
    }

    /** The tableHash of this table (CRAFT-CONTRACT.md), computed from the JSON it was read from. */
    public String hash() {
        return hash;
    }

    public int size() {
        return size;
    }

    /** Every recipe in the table. */
    public Collection<Recipe> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    /** The recipe with this id ("minecraft:spruce_stairs"), or null. */
    public Recipe byId(String id) {
        return byId.get(id);
    }

    /** The recipes that make this item, in the table's order. */
    public List<Recipe> producing(String item) {
        return byResult.getOrDefault(item, List.of());
    }

    /** The recipes that make this item ("chest" or "minecraft:chest"), by recipe id. Empty when it isn't crafted. */
    public List<Recipe> forResult(String item) {
        return producing(ns(item));
    }

    /** "chest" or "minecraft:chest" as "minecraft:chest". */
    public static String ns(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /** "minecraft:oak_log" as "Oak Log" (small words stay small: "Totem of Undying"). */
    public static String pretty(String id) {
        String bare = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        StringBuilder out = new StringBuilder();
        for (String word : bare.split("_")) {
            if (word.isEmpty()) continue;
            boolean small = out.length() > 0 && (word.equals("of") || word.equals("the") || word.equals("on") || word.equals("a") || word.equals("and"));
            if (out.length() > 0) out.append(' ');
            out.append(small ? word : word.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + word.substring(1));
        }
        return out.toString();
    }

    /** The other station that makes the item (smelting, blasting, smoking, campfire_cooking, stonecutting, smithing, transmute, special), or null. */
    public String station(String item) {
        return stations.get(item);
    }

    /**
     * The contract's tableHash (CRAFT-CONTRACT.md "tableHash"): the lowercase hex SHA-1 of the UTF-8 text with one line per recipe,
     * sorted by id (String.compareTo): {@code id TAB type TAB w TAB h TAB result TAB count TAB body NEWLINE}. For a shaped recipe the
     * body is its cells joined by "|", each "-" when empty, else its alternatives joined by ","; for a shapeless one each
     * ingredient's alternatives joined by ",", those strings sorted and joined by "|". w and h are 0 for shapeless recipes.
     * {@code otherStations} and everything else in the table is not hashed. A port of the plugin's {@code Recipes.tableHash}.
     */
    public static String tableHash(JsonObject root) {
        List<String[]> rows = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("recipes")) {
            JsonObject o = e.getAsJsonObject();
            String type = o.get("type").getAsString();
            boolean shaped = type.equals("shaped");
            if (!shaped && !type.equals("shapeless")) continue;
            List<String> parts = new ArrayList<>();
            for (JsonElement c : o.getAsJsonArray(shaped ? "cells" : "ingredients")) {
                if (c.isJsonNull()) {
                    parts.add("-");
                    continue;
                }
                List<String> alts = new ArrayList<>();
                for (JsonElement a : c.getAsJsonArray()) alts.add(a.getAsString());
                parts.add(String.join(",", alts));
            }
            if (!shaped) Collections.sort(parts);
            int count = o.has("count") && !o.get("count").isJsonNull() ? o.get("count").getAsInt() : 1;
            String id = o.get("id").getAsString();
            String line = id + "\t" + type + "\t" + (shaped ? o.get("w").getAsInt() : 0) + "\t" + (shaped ? o.get("h").getAsInt() : 0)
                + "\t" + o.get("result").getAsString() + "\t" + count + "\t" + String.join("|", parts) + "\n";
            rows.add(new String[] {id, line});
        }
        rows.sort((a, b) -> a[0].compareTo(b[0]));
        StringBuilder text = new StringBuilder();
        for (String[] r : rows) text.append(r[1]);
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Reads a table. @throws IllegalArgumentException when it is not one */
    public static Recipes parse(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) throw new IllegalArgumentException("not an object");
            return fromJson(root.getAsJsonObject());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a recipe table (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /** A table from its parsed JSON (the hash is computed from it). A missing {@code count} is 1, here and in {@link #tableHash}. */
    public static Recipes fromJson(JsonObject top) {
        Recipes out = new Recipes(top.has("version") && top.get("version").isJsonPrimitive() ? top.get("version").getAsString() : "", tableHash(top));
        for (JsonElement el : top.getAsJsonArray("recipes")) {
            JsonObject r = el.getAsJsonObject();
            String type = r.get("type").getAsString();
            boolean shaped = type.equals("shaped");
            if (!shaped && !type.equals("shapeless")) continue;
            List<List<String>> cells = new ArrayList<>();
            JsonArray raw = r.getAsJsonArray(shaped ? "cells" : "ingredients");
            for (JsonElement c : raw) cells.add(c.isJsonNull() ? null : ids(c.getAsJsonArray()));
            int w = shaped ? r.get("w").getAsInt() : cells.size(), h = shaped ? r.get("h").getAsInt() : 1;
            int count = Math.max(1, r.has("count") && !r.get("count").isJsonNull() ? r.get("count").getAsInt() : 1);
            Recipe recipe = new Recipe(r.get("id").getAsString(), shaped, w, h, Collections.unmodifiableList(cells), r.get("result").getAsString(), count);
            out.byResult.computeIfAbsent(recipe.result(), k -> new ArrayList<>()).add(recipe);
            out.byId.put(recipe.id(), recipe);
            out.size++;
        }
        // The same order whatever order the file lists them in (the plugin's Recipes does this too)
        for (List<Recipe> list : out.byResult.values()) list.sort((a, b) -> a.id().compareTo(b.id()));
        if (top.has("otherStations") && top.get("otherStations").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : top.getAsJsonObject("otherStations").entrySet()) {
                if (e.getValue().isJsonPrimitive()) out.stations.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return out;
    }

    private static List<String> ids(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : a) out.add(e.getAsString());
        return Collections.unmodifiableList(out);
    }
}

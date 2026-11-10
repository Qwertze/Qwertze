package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The crafting-table recipes of one Minecraft version in the shape the bots ship (CRAFT-CONTRACT.md, "table.json"),
 * with the contract's {@code tableHash}. Immutable. Shaped recipes have {@code w*h} cells (row-major, null = empty);
 * shapeless ones a list of ingredients. Every cell or ingredient is a sorted, de-duplicated list of item ids.
 */
final class RecipeTable {
    static final int FORMAT = 1;

    /** One recipe. For shapeless recipes w and h are 0 and cells holds the ingredients. */
    record Recipe(String id, boolean shaped, int w, int h, List<List<String>> cells, String result, int count) {
        /** The non-empty cells (shaped) or all ingredients (shapeless), in order. */
        List<List<String>> slots() {
            List<List<String>> out = new ArrayList<>();
            for (List<String> c : cells) if (c != null) out.add(c);
            return out;
        }
    }

    final String version;
    final int format;
    private final List<Recipe> recipes;
    private final Map<String, String> otherStations;
    private final Map<String, List<Recipe>> byResult = new HashMap<>();
    private final Map<String, Recipe> byId = new HashMap<>();
    private final String hash;

    RecipeTable(String version, int format, List<Recipe> recipes, Map<String, String> otherStations) {
        this.version = version;
        this.format = format;
        List<Recipe> sorted = new ArrayList<>(recipes);
        sorted.sort(Comparator.comparing(Recipe::id));
        this.recipes = List.copyOf(sorted);
        this.otherStations = Map.copyOf(new TreeMap<>(otherStations));
        for (Recipe r : this.recipes) {
            byResult.computeIfAbsent(r.result(), k -> new ArrayList<>()).add(r);
            byId.put(r.id(), r);
        }
        this.hash = computeHash(this.recipes);
    }

    List<Recipe> recipes() {
        return recipes;
    }

    Map<String, String> otherStations() {
        return otherStations;
    }

    Recipe byId(String id) {
        return byId.get(id);
    }

    List<Recipe> making(String result) {
        return byResult.getOrDefault(result, List.of());
    }

    String tableHash() {
        return hash;
    }

    private volatile byte[] jsonBytes;

    /** table.json as served to the bots (built once). The array is shared: don't change it. */
    byte[] jsonBytes() {
        byte[] b = jsonBytes;
        if (b == null) jsonBytes = b = toJsonText().getBytes(StandardCharsets.UTF_8);
        return b;
    }

    // ---------- the hash ----------

    /** Lowercase hex SHA-1 of "id TAB type TAB w TAB h TAB result TAB count TAB body LF" per recipe, sorted by id. */
    static String computeHash(List<Recipe> list) {
        List<Recipe> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparing(Recipe::id));
        StringBuilder sb = new StringBuilder();
        for (Recipe r : sorted) {
            sb.append(r.id()).append('\t').append(r.shaped() ? "shaped" : "shapeless").append('\t').append(r.w()).append('\t').append(r.h())
                .append('\t').append(r.result()).append('\t').append(r.count()).append('\t');
            if (r.shaped()) {
                for (int i = 0; i < r.cells().size(); i++) {
                    if (i > 0) sb.append('|');
                    List<String> c = r.cells().get(i);
                    sb.append(c == null ? "-" : String.join(",", c));
                }
            } else {
                List<String> parts = new ArrayList<>();
                for (List<String> c : r.cells()) parts.add(String.join(",", c));
                parts.sort(null);
                sb.append(String.join("|", parts));
            }
            sb.append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- JSON ----------

    /** The table.json text: {version, format, recipes: [...], otherStations: {...}}, recipes sorted by id. */
    String toJsonText() {
        StringBuilder sb = new StringBuilder(recipes.size() * 160 + 256);
        sb.append("{\"version\":").append(Json.GSON.toJson(version)).append(",\"format\":").append(format).append(",\"recipes\":[\n");
        boolean first = true;
        for (Recipe r : recipes) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append("{\"id\":").append(q(r.id())).append(",\"type\":\"").append(r.shaped() ? "shaped" : "shapeless").append('"');
            if (r.shaped()) {
                sb.append(",\"w\":").append(r.w()).append(",\"h\":").append(r.h()).append(",\"cells\":[");
                for (int i = 0; i < r.cells().size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(list(r.cells().get(i)));
                }
                sb.append(']');
            } else {
                sb.append(",\"ingredients\":[");
                for (int i = 0; i < r.cells().size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(list(r.cells().get(i)));
                }
                sb.append(']');
            }
            sb.append(",\"result\":").append(q(r.result())).append(",\"count\":").append(r.count()).append('}');
        }
        sb.append("\n],\"otherStations\":{");
        boolean f2 = true;
        for (Map.Entry<String, String> e : new TreeMap<>(otherStations).entrySet()) {
            if (!f2) sb.append(',');
            f2 = false;
            sb.append(q(e.getKey())).append(':').append(q(e.getValue()));
        }
        sb.append("}}\n");
        return sb.toString();
    }

    private static String q(String s) {
        return Json.GSON.toJson(s);
    }

    private static String list(List<String> l) {
        if (l == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(q(l.get(i)));
        }
        return sb.append(']').toString();
    }

    /** Parses a table.json (also the older shipped tables without "format"). Throws IllegalArgumentException when it isn't one. */
    static RecipeTable parse(String text) {
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            String version = root.get("version").getAsString();
            int format = root.has("format") ? root.get("format").getAsInt() : 0;
            List<Recipe> out = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("recipes")) {
                JsonObject o = e.getAsJsonObject();
                String id = o.get("id").getAsString();
                boolean shaped = switch (o.get("type").getAsString()) {
                    case "shaped" -> true;
                    case "shapeless" -> false;
                    default -> throw new IllegalArgumentException("recipe type");
                };
                int w = 0, h = 0;
                JsonArray arr;
                if (shaped) {
                    w = o.get("w").getAsInt();
                    h = o.get("h").getAsInt();
                    arr = o.getAsJsonArray("cells");
                    if (w < 1 || h < 1 || arr.size() != w * h) throw new IllegalArgumentException("shape of " + id);
                } else {
                    arr = o.getAsJsonArray("ingredients");
                }
                List<List<String>> cells = new ArrayList<>();
                for (JsonElement c : arr) {
                    if (c.isJsonNull()) {
                        if (!shaped) throw new IllegalArgumentException("empty ingredient in " + id);
                        cells.add(null);
                        continue;
                    }
                    List<String> alts = new ArrayList<>();
                    for (JsonElement a : c.getAsJsonArray()) alts.add(a.getAsString());
                    if (alts.isEmpty()) throw new IllegalArgumentException("empty alternatives in " + id);
                    cells.add(List.copyOf(alts));
                }
                int count = o.has("count") ? o.get("count").getAsInt() : 1;
                if (count < 1) throw new IllegalArgumentException("count of " + id);
                out.add(new Recipe(id, shaped, w, h, cells, o.get("result").getAsString(), count));
            }
            Map<String, String> stations = new TreeMap<>();
            if (root.has("otherStations")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("otherStations").entrySet()) stations.put(e.getKey(), e.getValue().getAsString());
            }
            return new RecipeTable(version, format, out, stations);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a recipe table: " + e.getMessage());
        }
    }
}

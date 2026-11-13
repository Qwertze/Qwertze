package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.porter.qwerte.core.enchant.McData;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The enchant fields of an index item entry (ENCHANT-CONTRACT v1.0, "Index additions"): {@code ench} (the stored enchantments of
 * a book or an enchanted item), {@code rc} (the stored repair cost, one scalar for the entry) and {@code named} (custom-named).
 * Index values are unverified claims: a bad field is dropped (never the whole entry), and what stays is rewritten in one canonical
 * form. Nothing here echoes the bad value.
 */
final class EnchantIndex {
    /** An enchantment or item id as the index writes it. */
    static final Pattern ID = Pattern.compile("^[a-z0-9_.:/-]{1,64}$");
    static final int MAX_ENCH = 64;
    static final int MAX_LEVEL = 255;
    static final String BOOK = "enchanted_book";

    private EnchantIndex() {
    }

    /**
     * What an index entry says about its stacks. ench is keyed by the plain enchantment id ("sharpness") and null when the entry
     * has no (valid) {@code ench}; rc is -1 when unknown.
     */
    record Entry(Map<String, Integer> ench, int rc, boolean named) {
        boolean known() {
            return ench != null;
        }
    }

    /** Whether any item of the index carries one of the three fields (even a bad one): such a post needs player == owner. */
    static boolean carries(JsonObject index) {
        JsonElement items = index.get("items");
        if (items == null || !items.isJsonArray()) return false;
        for (JsonElement e : items.getAsJsonArray()) {
            if (!(e instanceof JsonObject item)) continue;
            for (String f : new String[]{"ench", "rc", "named"}) {
                if (item.has(f) && !item.get(f).isJsonNull()) return true;
            }
        }
        return false;
    }

    /**
     * Cleans one item entry in place (a bad field is removed, a good one rewritten as {@code [{"id":"minecraft:sharpness","lvl":5}]},
     * a whole number, a boolean) and returns what it says.
     */
    static Entry clean(JsonObject item) {
        Map<String, Integer> ench = null;
        if (item.has("ench")) {
            ench = parseEnch(item.get("ench"));
            if (ench == null) {
                item.remove("ench");
            } else {
                JsonArray out = new JsonArray();
                for (Map.Entry<String, Integer> e : ench.entrySet()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", namespaced(e.getKey()));
                    o.addProperty("lvl", e.getValue());
                    out.add(o);
                }
                item.add("ench", out);
            }
        }
        int rc = -1;
        if (item.has("rc")) {
            long v = whole(item.get("rc"), 0, Integer.MAX_VALUE);
            if (v < 0) item.remove("rc");
            else {
                rc = (int) v;
                item.addProperty("rc", rc);
            }
        }
        boolean named = false;
        if (item.has("named")) {
            JsonElement n = item.get("named");
            if (n instanceof JsonPrimitive p && p.isBoolean()) named = p.getAsBoolean();
            else item.remove("named");
        }
        return new Entry(ench == null ? null : java.util.Collections.unmodifiableMap(ench), rc, named);
    }

    /** "sharpness" is "minecraft:sharpness"; an id with another namespace is kept as it is. */
    static String namespaced(String plain) {
        return plain.indexOf(':') >= 0 ? plain : "minecraft:" + plain;
    }

    /** At most 64 entries, ids of the id grammar, levels 1..255, no duplicate id after normalising; else null (the field is dropped). */
    private static Map<String, Integer> parseEnch(JsonElement e) {
        if (e == null || !e.isJsonArray()) return null;
        JsonArray a = e.getAsJsonArray();
        if (a.size() > MAX_ENCH) return null;
        Map<String, Integer> out = new TreeMap<>();
        for (JsonElement x : a) {
            if (!(x instanceof JsonObject o)) return null;
            JsonElement id = o.get("id");
            if (!(id instanceof JsonPrimitive ip) || !ip.isString() || !ID.matcher(ip.getAsString()).matches()) return null;
            long lvl = whole(o.get("lvl"), 1, MAX_LEVEL);
            if (lvl < 0) return null;
            String norm = McData.norm(ip.getAsString());
            if (norm.isEmpty() || out.put(norm, (int) lvl) != null) return null;
        }
        return out;
    }

    /** A whole number lo..hi (1.0 is fine, 1.5 isn't), else -1. Parsed with BigDecimal, never as a double. */
    static long whole(JsonElement e, long lo, long hi) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber() || p.getAsString().length() > 24) return -1;
        try {
            BigDecimal d = p.getAsBigDecimal();
            if (d.stripTrailingZeros().scale() > 0) return -1;
            if (d.compareTo(BigDecimal.valueOf(lo)) < 0 || d.compareTo(BigDecimal.valueOf(hi)) > 0) return -1;
            return d.longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return -1;
        }
    }

    /** {id, lvl} list for an enchantment map, ids namespaced (the wire form of the contract). */
    static JsonArray list(Map<String, Integer> ench) {
        JsonArray out = new JsonArray();
        for (Map.Entry<String, Integer> e : new LinkedHashMap<>(ench).entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", namespaced(e.getKey()));
            o.addProperty("lvl", e.getValue());
            out.add(o);
        }
        return out;
    }
}

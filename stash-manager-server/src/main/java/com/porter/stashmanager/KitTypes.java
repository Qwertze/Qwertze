package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The shared kit type table (KIT-CONTRACT-v2, "kit-types.json"): seven fixed types, which items serve which type. The
 * file is a byte-for-byte copy of the one the plugin and the addon ship. A table that is unreadable or fails the types
 * check is never used: {@link #load} answers null and no box is classified.
 */
final class KitTypes {
    /** The seven kit types, in tie-break order: id, name on the pages, noun in the reasons. */
    static final List<String> IDS = List.of("pvp", "travel", "redstone", "farming", "building", "starter", "other");
    private static final Map<String, String> NAMES = Map.of("pvp", "PvP / Crystal", "travel", "Travel", "redstone", "Redstone",
        "farming", "Farming / Food", "building", "Building", "starter", "Starter / Base", "other", "Other kit");
    private static final Map<String, String> NOUNS = Map.of("pvp", "PvP", "travel", "travel", "redstone", "redstone",
        "farming", "farming or food", "building", "building", "starter", "starter", "other", "collectible");
    /** The shipped table's fingerprint (a report's classifier.table is compared with it). */
    static final String FINGERPRINT = "c3ffa89424c6998aebcc40339e9d750df410952beb31e5e2dc05d7a59d663e3e";
    static final String RESOURCE = "/kits/kit-types.json";

    record Type(String id, String name, String noun, int minKinds, List<String> words) {
    }

    private final List<Type> types;
    private final Map<String, List<String>> items;
    private final Map<String, List<String>> suffixes;
    private final Map<String, List<String>> prefixes;
    private final String fingerprint;

    private KitTypes(List<Type> types, Map<String, List<String>> items, Map<String, List<String>> suffixes,
                     Map<String, List<String>> prefixes, String fingerprint) {
        this.types = types;
        this.items = items;
        this.suffixes = suffixes;
        this.prefixes = prefixes;
        this.fingerprint = fingerprint;
    }

    static String name(String type) {
        return NAMES.get(type);
    }

    static String noun(String type) {
        return NOUNS.get(type);
    }

    static boolean isType(String id) {
        return IDS.contains(id);
    }

    private static volatile KitTypes shipped;
    private static volatile boolean tried;

    /** The table in the jar, or null when it is missing or invalid (logged once). */
    static KitTypes shipped() {
        if (!tried) {
            synchronized (KitTypes.class) {
                if (!tried) {
                    try (InputStream in = KitTypes.class.getResourceAsStream(RESOURCE)) {
                        if (in == null) throw new IOException("missing");
                        shipped = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    } catch (IOException | RuntimeException e) {
                        System.err.println("Kit types: the table can't be read (" + e.getMessage() + "); kits are not classified.");
                    }
                    tried = true;
                }
            }
        }
        return shipped;
    }

    /** Parses a table; throws when it is not exactly the seven types in order. */
    static KitTypes parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<Type> types = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("types")) {
            JsonObject t = e.getAsJsonObject();
            List<String> words = new ArrayList<>();
            for (JsonElement w : t.getAsJsonArray("words")) words.add(w.getAsString());
            String id = t.get("id").getAsString();
            types.add(new Type(id, NAMES.getOrDefault(id, id), NOUNS.getOrDefault(id, id), t.get("minKinds").getAsInt(), List.copyOf(words)));
        }
        List<String> ids = new ArrayList<>();
        for (Type t : types) ids.add(t.id());
        if (!ids.equals(IDS)) throw new IllegalArgumentException("the types are not the seven kit types in order");
        Map<String, List<String>> items = map(root.getAsJsonObject("items"));
        Map<String, List<String>> suffixes = map(root.getAsJsonObject("suffixes"));
        Map<String, List<String>> prefixes = map(root.getAsJsonObject("prefixes"));
        return new KitTypes(List.copyOf(types), items, suffixes, prefixes, fingerprint(types, items, suffixes, prefixes));
    }

    private static Map<String, List<String>> map(JsonObject o) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            List<String> l = new ArrayList<>();
            for (JsonElement t : (JsonArray) e.getValue()) {
                if (!IDS.contains(t.getAsString())) throw new IllegalArgumentException("unknown type " + t.getAsString());
                l.add(t.getAsString());
            }
            out.put(e.getKey(), List.copyOf(l));
        }
        return out;
    }

    private static String fingerprint(List<Type> types, Map<String, List<String>> items, Map<String, List<String>> suffixes,
                                      Map<String, List<String>> prefixes) {
        StringBuilder sb = new StringBuilder();
        for (Type t : types) {
            List<String> w = new ArrayList<>(t.words());
            java.util.Collections.sort(w);
            sb.append("type:").append(t.id()).append(':').append(t.minKinds()).append(':').append(String.join(",", w)).append('\n');
        }
        for (var e : new TreeMap<>(items).entrySet()) sb.append("item:").append(e.getKey()).append(':').append(String.join(",", e.getValue())).append('\n');
        for (var e : new TreeMap<>(suffixes).entrySet()) sb.append("suffix:").append(e.getKey()).append(':').append(String.join(",", e.getValue())).append('\n');
        for (var e : new TreeMap<>(prefixes).entrySet()) sb.append("prefix:").append(e.getKey()).append(':').append(String.join(",", e.getValue())).append('\n');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    String fingerprint() {
        return fingerprint;
    }

    List<Type> types() {
        return types;
    }

    Type type(String id) {
        for (Type t : types) if (t.id().equals(id)) return t;
        return null;
    }

    /** The types of an item id (the first is its primary type); empty for stock material. */
    List<String> typesOf(String id) {
        String full = id.indexOf(':') >= 0 ? id : "minecraft:" + id;
        List<String> direct = items.get(full);
        if (direct != null) return direct;
        int colon = full.indexOf(':');
        if (!full.substring(0, colon).equals("minecraft")) return List.of();
        String path = full.substring(colon + 1);
        String bestKey = null;
        List<String> best = null;
        for (var e : suffixes.entrySet()) {
            if (path.endsWith(e.getKey()) && (bestKey == null || e.getKey().length() > bestKey.length())) {
                bestKey = e.getKey();
                best = e.getValue();
            }
        }
        if (best != null) return best;
        for (var e : prefixes.entrySet()) {
            if (path.startsWith(e.getKey()) && (bestKey == null || e.getKey().length() > bestKey.length())) {
                bestKey = e.getKey();
                best = e.getValue();
            }
        }
        return best == null ? List.of() : best;
    }
}

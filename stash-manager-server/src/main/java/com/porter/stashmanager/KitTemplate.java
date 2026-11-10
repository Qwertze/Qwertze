package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * A kit template of the Kit Builder (KIT-CONTRACT-v2, "Kit templates" and revision 4): 27 slots of {key, match, count},
 * a category, an optional chest, a shulker colour and a restock level. The canonical string and its SHA-1 are what the
 * settings hash covers.
 */
final class KitTemplate {
    static final int SLOTS = 27;
    static final int MAX_TEMPLATES = 50;
    static final int MAX_CATEGORIES = 16;
    static final int MAX_NAME = 50;
    static final int MAX_CATEGORY_NAME = 32;
    /** The ids a bot may be given; the server makes "t-" and 8 lowercase hex digits (the contract's own vectors use t-0000mini). */
    static final Pattern ID = Pattern.compile("^t-[0-9a-z]{8}$");
    static final Pattern CATEGORY = Pattern.compile("^c:[a-z0-9-]{1,24}$");
    /** An index key (an exact variant) or a plain id with "#*" (any variant). */
    static final Pattern KEY = Pattern.compile("^[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}(#[0-9a-f]{1,64}|#\\*)?$");
    /** A match key: the same grammar as an index key. */
    static final Pattern MATCH = Pattern.compile("^[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}(#[0-9a-f]{1,64})?$");
    static final Set<String> COLORS = Set.of("plain", "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final SecureRandom RANDOM = new SecureRandom();

    record Slot(String key, String match, int count) {
        boolean anyVariant() {
            return key.endsWith("#*");
        }

        String base() {
            int h = key.indexOf('#');
            return h < 0 ? key : key.substring(0, h);
        }
    }

    /** slots has exactly 27 entries, null for an empty one. */
    record Template(String id, String name, String category, String chest, String color, int keep, List<Slot> slots) {
        String canonical() {
            StringBuilder sb = new StringBuilder("tpl-v2\n").append(id).append('\n').append(name).append('\n').append(category).append('\n')
                .append(chest == null ? "" : chest).append('\n').append(color == null ? "" : color).append('\n').append(keep).append('\n');
            for (int i = 0; i < slots.size(); i++) {
                if (i > 0) sb.append(',');
                Slot s = slots.get(i);
                if (s != null) sb.append(s.key()).append(' ').append(s.match()).append(' ').append(s.count());
            }
            return sb.append('\n').toString();
        }

        String hash() {
            return sha1(canonical());
        }

        /** The multiset that decides a match: match key (or the "#*" key) to total count. */
        Map<String, Integer> contents() {
            Map<String, Integer> m = new TreeMap<>();
            for (Slot s : slots) if (s != null) m.merge(s.match(), s.count(), Integer::sum);
            return m;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("name", name);
            o.addProperty("category", category);
            o.addProperty("chest", chest);
            o.addProperty("color", color);
            o.addProperty("keep", keep);
            JsonArray a = new JsonArray();
            for (Slot s : slots) {
                if (s == null) {
                    a.add(JsonNull.INSTANCE);
                    continue;
                }
                JsonObject so = new JsonObject();
                so.addProperty("key", s.key());
                so.addProperty("match", s.match());
                so.addProperty("count", s.count());
                a.add(so);
            }
            o.add("slots", a);
            return o;
        }
    }

    /** What the item list says about a key. matchOf: its match key (null: not in the index); maxStack: its stack size (0: unknown). */
    interface Items {
        String matchOf(String key);

        int maxStack(String key);
    }

    static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "t-" and 8 random lowercase hex digits that are not in taken. */
    static String newId(Set<String> taken) {
        while (true) {
            byte[] b = new byte[4];
            RANDOM.nextBytes(b);
            String id = "t-" + HexFormat.of().formatHex(b);
            if (!taken.contains(id)) return id;
        }
    }

    /** Whether a name can be a template name or a category name (length 1..max, no section sign, no control or invisible characters). */
    static String checkName(String raw, int max, String what) {
        if (raw == null) throw new IllegalArgumentException(what + " must be text");
        String name = raw.strip();
        if (name.isEmpty()) throw new IllegalArgumentException(what + " must not be empty");
        if (name.length() > max) throw new IllegalArgumentException(what + " can be at most " + max + " characters");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            int type = Character.getType(c);
            if (c == '§') throw new IllegalArgumentException(what + " can't contain §");
            if (Character.isISOControl(c)) throw new IllegalArgumentException(what + " can't contain control characters");
            if (type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException(what + " can't contain invisible or direction-changing characters");
            }
        }
        if (!KitNames.wellFormed(name)) throw new IllegalArgumentException(what + " has a broken character");
        return name;
    }

    /**
     * Reads and checks a template from a request body (KIT-CONTRACT-v2). id is the template's id (new or replaced); the
     * caller checks the name and the contents against the stash's other templates and the category against the stash.
     * Throws IllegalArgumentException with the message of the 400.
     */
    static Template parse(JsonObject o, String id, Items items) {
        String name = checkName(text(o, "name"), MAX_NAME, "name");
        if (KitNames.normalize(name) == null) throw new IllegalArgumentException("name must have a letter or digit in it");
        String category = text(o, "category");
        if (category == null) throw new IllegalArgumentException("category is required");
        String chest = null;
        if (o.has("chest") && !o.get("chest").isJsonNull()) {
            chest = text(o, "chest");
            if (!KitNames.validId(chest)) throw new IllegalArgumentException("chest must be an id like \"120,64,-33\" or null");
        }
        String color = null;
        if (o.has("color") && !o.get("color").isJsonNull()) {
            color = text(o, "color");
            if (!COLORS.contains(color)) throw new IllegalArgumentException("color must be null, plain or a dye colour like light_blue");
        }
        int keep = 0;
        if (o.has("keep") && !o.get("keep").isJsonNull()) {
            Integer k = whole(o.get("keep"));
            if (k == null || k < 0 || k > 27) throw new IllegalArgumentException("keep must be a whole number from 0 to 27");
            keep = k;
        }
        JsonElement se = o.get("slots");
        if (se == null || !se.isJsonArray() || se.getAsJsonArray().size() != SLOTS) {
            throw new IllegalArgumentException("slots must be a list of exactly " + SLOTS + " entries (null for an empty slot)");
        }
        List<Slot> slots = new ArrayList<>();
        java.util.Set<String> bases = new java.util.HashSet<>();
        for (JsonElement e : se.getAsJsonArray()) {
            if (e.isJsonNull()) {
                slots.add(null);
                continue;
            }
            if (!e.isJsonObject()) throw new IllegalArgumentException("every slot must be null or {key, count}");
            JsonObject s = e.getAsJsonObject();
            String key = text(s, "key");
            if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("a slot key must be an item key like minecraft:bread or minecraft:bow#*");
            String base = key.indexOf('#') < 0 ? key : key.substring(0, key.indexOf('#'));
            if (IndexStore.OrderCounts.isShulkerBox(base)) throw new IllegalArgumentException("a shulker box can't be in a kit");
            Integer count = whole(s.get("count"));
            if (count == null || count < 1) throw new IllegalArgumentException("a slot count must be a whole number from 1 up to the item's stack size");
            String match;
            if (key.endsWith("#*")) {
                match = key;
            } else {
                match = items.matchOf(key);
                if (match == null) {
                    if (key.indexOf('#') < 0) {
                        match = key; // a plain key matches itself
                    } else if (s.has("match") && !s.get("match").isJsonNull()) {
                        match = text(s, "match");
                        if (match == null || !MATCH.matcher(match).matches() || !(match.equals(base) || match.startsWith(base + "#"))) {
                            throw new IllegalArgumentException("a slot match must be an item key of the same item");
                        }
                    } else {
                        throw new IllegalArgumentException("that item variant isn't in the stash index; pick it again or use any variant");
                    }
                }
            }
            int max = items.maxStack(key);
            if (max <= 0) max = 64;
            if (count > max) throw new IllegalArgumentException("a slot count is at most the stack size (" + max + " for " + base + ")");
            bases.add(base);
            slots.add(new Slot(key, match, count));
        }
        if (bases.size() < 2) throw new IllegalArgumentException("a kit needs at least two kinds of item");
        return new Template(id, name, category, chest, color, keep, java.util.Collections.unmodifiableList(new ArrayList<>(slots)));
    }

    /** A template as it is stored (already checked once); lenient about nothing. */
    static Template fromStored(JsonObject o) {
        List<Slot> slots = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("slots")) {
            if (e.isJsonNull()) {
                slots.add(null);
                continue;
            }
            JsonObject s = e.getAsJsonObject();
            int count = s.get("count").getAsInt();
            String key = s.get("key").getAsString(), match = s.get("match").getAsString();
            if (!KEY.matcher(key).matches() || !(MATCH.matcher(match).matches() || match.equals(key)) || count < 1 || count > 1000) {
                throw new IllegalArgumentException("bad slot");
            }
            slots.add(new Slot(key, match, count));
        }
        if (slots.size() != SLOTS) throw new IllegalArgumentException("bad slots");
        String id = o.get("id").getAsString();
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("bad id");
        String chest = o.get("chest").isJsonNull() ? null : o.get("chest").getAsString();
        if (chest != null && !KitNames.validId(chest)) throw new IllegalArgumentException("bad chest");
        String color = o.get("color").isJsonNull() ? null : o.get("color").getAsString();
        if (color != null && !COLORS.contains(color)) throw new IllegalArgumentException("bad color");
        int keep = o.get("keep").getAsInt();
        if (keep < 0 || keep > 27) throw new IllegalArgumentException("bad keep");
        return new Template(id, checkName(o.get("name").getAsString(), MAX_NAME, "name"), o.get("category").getAsString(), chest, color, keep, java.util.Collections.unmodifiableList(new ArrayList<>(slots)));
    }

    private static String text(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) return null;
        if (!(e instanceof JsonPrimitive p) || !p.isString()) throw new IllegalArgumentException(field + " must be text");
        return p.getAsString();
    }

    private static Integer whole(JsonElement e) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber() || p.getAsString().length() > 20) return null;
        try {
            return p.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    /** A category id for a name: "c:" and a slug of at most 24 characters. */
    static String slug(String name, Set<String> taken) {
        String s = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 20) s = s.substring(0, 20).replaceAll("-+$", "");
        if (s.isEmpty()) s = "kits";
        String id = "c:" + s;
        for (int n = 2; taken.contains(id); n++) id = "c:" + s + "-" + n;
        return id;
    }
}

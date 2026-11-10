package com.porter.qwerte.stash;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The kit settings of one stash (KIT-CONTRACT.md, "Kit settings"): a chest for every kit, a chest per kit name and the kit score's points.
 * Immutable. Everything that comes from the server or from the cache file is checked here, and an object that fails any check is refused whole.
 *
 * @param kitsChest a container id or null
 * @param rules     kit key to container id, at most {@link #MAX_RULES}, sorted by key
 * @param scoring   the points of the kit score and the threshold
 */
public record KitSettings(long rev, String hash, String kitsChest, Map<String, String> rules, Scoring scoring, Types types) {
    /** Settings without the v2 part (an older server, or tests of the v1 parts). */
    public KitSettings(long rev, String hash, String kitsChest, Map<String, String> rules, Scoring scoring) {
        this(rev, hash, kitsChest, rules, scoring, null);
    }

    public static final int MAX_CATEGORIES = 16, MAX_TEMPLATES = 50, MAX_CHESTS = 23, MAX_TREAT_AS = 200;
    private static final Pattern CATEGORY = Pattern.compile("^c:[a-z0-9-]{1,24}$");

    /** A custom category: id {@code c:} and a slug, a name of 1..32 characters. */
    public record Category(String id, String name) {
    }

    /**
     * The v2 part of the settings (KIT-CONTRACT v2): {@code hash} is the v2 hash; {@code chests} category id to container id (the seven types and
     * the custom categories); {@code treatAs} normalised name to a category id or "none"; the custom {@code categories} and the kit {@code templates}.
     */
    public record Types(String hash, List<Category> categories, Map<String, String> chests, Map<String, String> treatAs, List<KitTemplate> templates) {
        public KitTemplate template(String id) {
            for (KitTemplate t : templates) if (t.id().equals(id)) return t;
            return null;
        }

        public boolean hasCategory(String id) {
            for (Category c : categories) if (c.id().equals(id)) return true;
            return false;
        }
    }

    /** A category id: one of the seven types, or the shape of a custom category (that it exists is checked against the settings). */
    public static boolean validCategory(String id) {
        return id != null && (KitTypes.TYPE_IDS.contains(id) || CATEGORY.matcher(id).matches());
    }

    public static final int MAX_RULES = 200;
    private static final Pattern ID = Pattern.compile("^-?[0-9]{1,8},-?[0-9]{1,4},-?[0-9]{1,8}$");
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{40}$");

    /**
     * The kit score's points (each 0..10), the threshold (1..40) and how many OP items the op points need (1..1728).
     */
    public record Scoring(int threshold, int name, int mix, int full, int op, int opMin) {
        public static final Scoring DEFAULT = new Scoring(4, 2, 3, 1, 4, 3);
        public static final int MAX_POINTS = 10, MAX_THRESHOLD = 40, MAX_OP_MIN = 1728;

        /** The most a box can score: the sum of the four point values. */
        public int max() {
            return name + mix + full + op;
        }

        public boolean valid() {
            return threshold >= 1 && threshold <= MAX_THRESHOLD && ok(name) && ok(mix) && ok(full) && ok(op) && opMin >= 1 && opMin <= MAX_OP_MIN;
        }

        private static boolean ok(int v) {
            return v >= 0 && v <= MAX_POINTS;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("threshold", threshold);
            o.addProperty("name", name);
            o.addProperty("mix", mix);
            o.addProperty("full", full);
            o.addProperty("op", op);
            o.addProperty("opMin", opMin);
            return o;
        }
    }

    /** What a stash has when it has nothing set. */
    public static final KitSettings EMPTY = new KitSettings(0, hashOf(null, Scoring.DEFAULT, Map.of()), null, Map.of(), Scoring.DEFAULT);

    /** Why an object was refused, for the log. */
    public static final class Invalid extends Exception {
        public Invalid(String why) {
            super(why);
        }
    }

    /** A position an id names. */
    public record Pos(int x, int y, int z) {
    }

    /** Whether no chest is set: no kits chest, no rule, no category chest and no template chest (the scoring alone routes nothing). */
    public boolean isEmpty() {
        if (types != null) {
            if (!types.chests().isEmpty()) return false;
            for (KitTemplate t : types.templates()) if (t.chest() != null) return false;
        }
        return kitsChest == null && rules.isEmpty();
    }

    /** A container id as the contract has it: decimal x,y,z in range. */
    public static boolean validId(String id) {
        if (id == null || !ID.matcher(id).matches()) return false;
        try {
            String[] p = id.split(",");
            return Math.abs(Long.parseLong(p[0])) <= 30_000_000 && Math.abs(Long.parseLong(p[2])) <= 30_000_000
                && Long.parseLong(p[1]) >= -2048 && Long.parseLong(p[1]) <= 2048;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The id of a position. */
    public static String id(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /** The position an id names, or null for an invalid id. */
    public static Pos position(String id) {
        if (!validId(id)) return null;
        String[] p = id.split(",");
        return new Pos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    /**
     * SHA-1 (lowercase hex) of "v1\n" + the kits chest (or nothing) + "\n" + "score threshold name mix full op opMin\n", then
     * key + "\t" + container + "\n" for each rule in key order (String.compareTo).
     */
    public static String hashOf(String kitsChest, Scoring scoring, Map<String, String> rules) {
        StringBuilder sb = new StringBuilder("v1\n").append(kitsChest == null ? "" : kitsChest).append('\n')
            .append("score ").append(scoring.threshold()).append(' ').append(scoring.name()).append(' ').append(scoring.mix()).append(' ')
            .append(scoring.full()).append(' ').append(scoring.op()).append(' ').append(scoring.opMin()).append('\n');
        for (Map.Entry<String, String> e : new TreeMap<>(rules).entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Checks and reads a settings object ({@code rev}, {@code hash}, {@code kitsChest}, {@code rules}, {@code scoring}). Unknown fields are
     * ignored; a missing {@code scoring} means the defaults.
     * @throws Invalid when anything is wrong: a type, a key that is not a normal name, a bad container id, more than 200 rules, a point
     *                 value out of range
     */
    public static KitSettings parse(JsonElement element) throws Invalid {
        if (element == null || !element.isJsonObject()) throw new Invalid("not an object");
        JsonObject o = element.getAsJsonObject();

        long rev = wholeNumber(o.get("rev"), "rev");
        if (rev < 0) throw new Invalid("rev is negative");

        JsonElement h = o.get("hash");
        if (h == null || !h.isJsonPrimitive() || !h.getAsJsonPrimitive().isString() || !HASH.matcher(h.getAsString()).matches()) throw new Invalid("hash is not 40 lowercase hex digits");
        String hash = h.getAsString();

        String chest = null;
        JsonElement c = o.get("kitsChest");
        if (c != null && !c.isJsonNull()) {
            if (!c.isJsonPrimitive() || !c.getAsJsonPrimitive().isString() || !validId(c.getAsString())) throw new Invalid("kitsChest is not a container id");
            chest = c.getAsString();
        }

        Scoring scoring = Scoring.DEFAULT;
        JsonElement sc = o.get("scoring");
        if (sc != null && !sc.isJsonNull()) {
            if (!sc.isJsonObject()) throw new Invalid("scoring is not an object");
            JsonObject so = sc.getAsJsonObject();
            // A scoring without opMin means 3
            int opMin = so.has("opMin") && !so.get("opMin").isJsonNull() ? points(so, "opMin") : Scoring.DEFAULT.opMin();
            scoring = new Scoring(points(so, "threshold"), points(so, "name"), points(so, "mix"), points(so, "full"), points(so, "op"), opMin);
            if (!scoring.valid()) throw new Invalid("scoring is out of range");
        }

        JsonElement r = o.get("rules");
        if (r == null || !r.isJsonObject()) throw new Invalid("rules is not an object");
        JsonObject ro = r.getAsJsonObject();
        if (ro.size() > MAX_RULES) throw new Invalid("more than " + MAX_RULES + " rules");
        Map<String, String> rules = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : ro.entrySet()) {
            if (!KitNames.validKey(e.getKey())) throw new Invalid("a rule has a key that is not a normal name");
            JsonElement v = e.getValue();
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || !validId(v.getAsString())) throw new Invalid("a rule has a container that is not an id");
            rules.put(e.getKey(), v.getAsString());
        }
        // The hash has to be the one of what is in the object: an object that does not hash to its own hash is refused whole
        if (!hashOf(chest, scoring, rules).equals(hash)) throw new Invalid("hash does not match the settings");
        // The v2 part, when the server sent one: any failure refuses the whole object
        Types types = null;
        JsonElement t = o.get("types");
        if (t != null && !t.isJsonNull()) types = parseTypes(t, hash);
        return new KitSettings(rev, hash, chest, Collections.unmodifiableMap(rules), scoring, types);
    }

    private static Types parseTypes(JsonElement element, String v1Hash) throws Invalid {
        if (!element.isJsonObject()) throw new Invalid("types is not an object");
        JsonObject o = element.getAsJsonObject();
        JsonElement h = o.get("hash");
        if (h == null || !h.isJsonPrimitive() || !h.getAsJsonPrimitive().isString() || !HASH.matcher(h.getAsString()).matches()) throw new Invalid("types.hash is not 40 lowercase hex digits");

        List<Category> categories = new ArrayList<>();
        JsonElement ca = o.get("categories");
        if (ca != null && !ca.isJsonNull()) {
            if (!ca.isJsonArray() || ca.getAsJsonArray().size() > MAX_CATEGORIES) throw new Invalid("categories is not a list of at most " + MAX_CATEGORIES);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (JsonElement e : ca.getAsJsonArray()) {
                if (!e.isJsonObject()) throw new Invalid("a category is not an object");
                String id = str(e.getAsJsonObject().get("id")), name = str(e.getAsJsonObject().get("name"));
                if (id == null || !CATEGORY.matcher(id).matches() || !seen.add(id)) throw new Invalid("a category id is not usable");
                if (name == null || name.isEmpty() || name.length() > 32 || name.indexOf('\u00a7') >= 0 || hasControl(name)) throw new Invalid("a category name is not usable");
                categories.add(new Category(id, name));
            }
        }
        java.util.Set<String> known = new java.util.HashSet<>(KitTypes.TYPE_IDS);
        for (Category c : categories) known.add(c.id());

        Map<String, String> chests = new TreeMap<>();
        JsonElement ch = o.get("chests");
        if (ch != null && !ch.isJsonNull()) {
            if (!ch.isJsonObject() || ch.getAsJsonObject().size() > MAX_CHESTS) throw new Invalid("chests is not an object of at most " + MAX_CHESTS);
            for (Map.Entry<String, JsonElement> e : ch.getAsJsonObject().entrySet()) {
                String v = str(e.getValue());
                if (!known.contains(e.getKey()) || !validId(v)) throw new Invalid("a type chest is not a known category with a container id");
                chests.put(e.getKey(), v);
            }
        }
        Map<String, String> treatAs = new TreeMap<>();
        JsonElement ta = o.get("treatAs");
        if (ta != null && !ta.isJsonNull()) {
            if (!ta.isJsonObject() || ta.getAsJsonObject().size() > MAX_TREAT_AS) throw new Invalid("treatAs is not an object of at most " + MAX_TREAT_AS);
            for (Map.Entry<String, JsonElement> e : ta.getAsJsonObject().entrySet()) {
                String v = str(e.getValue());
                if (!KitNames.validKey(e.getKey()) || v == null || !(v.equals("none") || known.contains(v))) throw new Invalid("a treatAs entry is not a key and a category or none");
                treatAs.put(e.getKey(), v);
            }
        }
        List<KitTemplate> templates = new ArrayList<>();
        JsonElement te = o.get("templates");
        if (te != null && !te.isJsonNull()) {
            if (!te.isJsonArray() || te.getAsJsonArray().size() > MAX_TEMPLATES) throw new Invalid("templates is not a list of at most " + MAX_TEMPLATES);
            java.util.Set<String> ids = new java.util.HashSet<>(), names = new java.util.HashSet<>();
            for (JsonElement e : te.getAsJsonArray()) {
                KitTemplate t;
                try {
                    t = KitTemplate.parse(e);
                } catch (KitTemplate.Invalid ex) {
                    throw new Invalid(ex.getMessage());
                }
                if (!known.contains(t.category())) throw new Invalid("a template's category does not exist");
                if (!ids.add(t.id()) || !names.add(KitNames.normalize(t.name()))) throw new Invalid("two templates share an id or a name");
                templates.add(t);
            }
        }
        String hash = hashV2(v1Hash, categories, chests, treatAs, templates);
        if (!hash.equals(h.getAsString())) throw new Invalid("types.hash does not match the content");
        return new Types(hash, Collections.unmodifiableList(categories), Collections.unmodifiableMap(chests), Collections.unmodifiableMap(treatAs), Collections.unmodifiableList(templates));
    }

    private static String str(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static boolean hasControl(String s) {
        return KitTemplate.hasControl(s);
    }

    /**
     * The v2 hash: SHA-1 (lowercase hex) of {@code "v2\n" + v1 hash + "\n"}, then {@code "chest " + category + " " + id + "\n"} (the seven types in
     * type order, then the custom categories by id), {@code "cat " + id + "\t" + name + "\n"} per custom category by id, {@code "as " + key + "\t" +
     * value + "\n"} per treatAs by key and {@code "tpl " + id + " " + template hash + "\n"} per template by id.
     */
    public static String hashV2(String v1Hash, List<Category> categories, Map<String, String> chests, Map<String, String> treatAs, List<KitTemplate> templates) {
        StringBuilder sb = new StringBuilder("v2\n").append(v1Hash).append('\n');
        for (String type : KitTypes.TYPE_IDS) if (chests.containsKey(type)) sb.append("chest ").append(type).append(' ').append(chests.get(type)).append('\n');
        for (Map.Entry<String, String> e : new TreeMap<>(chests).entrySet()) {
            if (!KitTypes.TYPE_IDS.contains(e.getKey())) sb.append("chest ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        for (Category c : categories.stream().sorted(java.util.Comparator.comparing(Category::id)).toList()) sb.append("cat ").append(c.id()).append('\t').append(c.name()).append('\n');
        for (Map.Entry<String, String> e : new TreeMap<>(treatAs).entrySet()) sb.append("as ").append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        for (KitTemplate t : templates.stream().sorted(java.util.Comparator.comparing(KitTemplate::id)).toList()) sb.append("tpl ").append(t.id()).append(' ').append(t.hash()).append('\n');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int points(JsonObject o, String name) throws Invalid {
        long v = wholeNumber(o.get(name), "scoring." + name);
        if (v < -100000 || v > 100000) throw new Invalid("scoring." + name + " is out of range");
        return (int) v;
    }

    private static long wholeNumber(JsonElement e, String what) throws Invalid {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new Invalid(what + " is not a number");
        JsonPrimitive p = e.getAsJsonPrimitive();
        String text = p.getAsString();
        if (text.length() > 15 || !text.matches("-?[0-9]+")) throw new Invalid(what + " is not a whole number");
        return Long.parseLong(text);
    }

    /** The object the server sends, and the cache file keeps. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("rev", rev);
        o.addProperty("hash", hash);
        if (kitsChest == null) o.add("kitsChest", JsonNull.INSTANCE);
        else o.addProperty("kitsChest", kitsChest);
        JsonObject r = new JsonObject();
        for (Map.Entry<String, String> e : rules.entrySet()) r.addProperty(e.getKey(), e.getValue());
        o.add("rules", r);
        o.add("scoring", scoring.toJson());
        if (types != null) {
            JsonObject t = new JsonObject();
            t.addProperty("hash", types.hash());
            com.google.gson.JsonArray cats = new com.google.gson.JsonArray();
            for (Category c : types.categories()) {
                JsonObject co = new JsonObject();
                co.addProperty("id", c.id());
                co.addProperty("name", c.name());
                cats.add(co);
            }
            t.add("categories", cats);
            JsonObject chests = new JsonObject();
            for (Map.Entry<String, String> e : types.chests().entrySet()) chests.addProperty(e.getKey(), e.getValue());
            t.add("chests", chests);
            JsonObject as = new JsonObject();
            for (Map.Entry<String, String> e : types.treatAs().entrySet()) as.addProperty(e.getKey(), e.getValue());
            t.add("treatAs", as);
            com.google.gson.JsonArray tpl = new com.google.gson.JsonArray();
            for (KitTemplate k : types.templates()) tpl.add(k.toJson());
            t.add("templates", tpl);
            o.add("types", t);
        }
        return o;
    }
}

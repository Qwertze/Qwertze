package com.porter.qwerte.stash;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
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
public record KitSettings(long rev, String hash, String kitsChest, Map<String, String> rules, Scoring scoring) {
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

    /** Whether nothing is set: no kits chest and no rule (the scoring may still differ from the defaults). */
    public boolean isEmpty() {
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
        return new KitSettings(rev, hash, chest, Collections.unmodifiableMap(rules), scoring);
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
        return o;
    }
}

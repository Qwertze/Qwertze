package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * result.enchant (ENCHANT-CONTRACT v1.0, "Result and failure rules"): {made: [{key, wanted, made}], short: [{key, reason}], levels:
 * [{id, lvl}], bottlesUsed, anvilsBroken, returned: [{key, count}]}. The bot's report is checked on arrival and written back in one clean
 * form: the reasons are a closed set, numbers are whole and bounded, lists are short, keys are cut, and the keys and counts are checked
 * against the order's own lines. A wrong shape is an error (400) whose text never repeats what was sent.
 */
final class EnchantResults {
    /** The closed set of {@code short[].reason} (SR2-N2). {@code interrupted} is not used by enchant. */
    static final Set<String> REASONS = Set.of("too_expensive", "books_short", "xp_short", "no_anvil", "inventory_full", "cost_mismatch",
        "mc_unsupported", "mc_mismatch", "busy", "too_complex", "no_base", "anvil_broke", "wrong_result", "module_conflict", "cap",
        "cancelled", "timeout");
    static final int MAX_LIST = 400;
    static final int MAX_KEY = 256;
    private static final long MAX_COUNT = Integer.MAX_VALUE;
    /** An item key as the index writes it (the same shape Redact serves unchanged). */
    static final java.util.regex.Pattern SAFE_KEY = java.util.regex.Pattern.compile("[a-z0-9_.:/-]+(#([0-9a-f]+|[*]))?");

    private EnchantResults() {
    }

    /** Null (absent) or the clean object; throws IllegalArgumentException (a 400) for a wrong shape. */
    static JsonObject parse(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonObject()) throw new IllegalArgumentException("result.enchant must be an object or null");
        JsonObject r = e.getAsJsonObject();
        JsonObject out = new JsonObject();
        JsonArray made = new JsonArray();
        for (JsonObject in : entries(r.get("made"), "made")) {
            long wanted = count(in.get("wanted"), "made.wanted");
            long m = count(in.get("made"), "made.made");
            if (m > wanted) throw new IllegalArgumentException("result.enchant.made.made can't be more than wanted");
            JsonObject o = new JsonObject();
            o.addProperty("key", key(in, "made"));
            o.addProperty("wanted", wanted);
            o.addProperty("made", m);
            made.add(o);
        }
        out.add("made", made);
        JsonArray shorts = new JsonArray();
        for (JsonObject in : entries(r.get("short"), "short")) {
            JsonElement reason = in.get("reason");
            if (!(reason instanceof JsonPrimitive p) || !p.isString() || !REASONS.contains(p.getAsString())) {
                throw new IllegalArgumentException("result.enchant.short.reason must be one of the codes of the contract");
            }
            JsonObject o = new JsonObject();
            o.addProperty("key", key(in, "short"));
            o.addProperty("reason", p.getAsString());
            shorts.add(o);
        }
        out.add("short", shorts);
        JsonArray levels = new JsonArray();
        for (JsonObject in : entries(r.get("levels"), "levels")) {
            JsonElement id = in.get("id");
            if (!(id instanceof JsonPrimitive p) || !p.isString() || !EnchantIndex.ID.matcher(p.getAsString()).matches()) {
                throw new IllegalArgumentException("result.enchant.levels.id must be an enchantment id");
            }
            long lvl = EnchantIndex.whole(in.get("lvl"), 1, EnchantIndex.MAX_LEVEL);
            if (lvl < 0) throw new IllegalArgumentException("result.enchant.levels.lvl must be a whole number from 1 to " + EnchantIndex.MAX_LEVEL);
            JsonObject o = new JsonObject();
            o.addProperty("id", p.getAsString());
            o.addProperty("lvl", lvl);
            levels.add(o);
        }
        out.add("levels", levels);
        out.addProperty("bottlesUsed", r.has("bottlesUsed") && !r.get("bottlesUsed").isJsonNull() ? count(r.get("bottlesUsed"), "bottlesUsed") : 0);
        out.addProperty("anvilsBroken", r.has("anvilsBroken") && !r.get("anvilsBroken").isJsonNull() ? count(r.get("anvilsBroken"), "anvilsBroken") : 0);
        JsonArray returned = new JsonArray();
        for (JsonObject in : entries(r.get("returned"), "returned")) {
            JsonObject o = new JsonObject();
            String rk = key(in, "returned");
            if (!SAFE_KEY.matcher(rk).matches()) throw new IllegalArgumentException("result.enchant.returned keys must be item keys");
            o.addProperty("key", rk);
            o.addProperty("count", count(in.get("count"), "returned.count"));
            returned.add(o);
        }
        out.add("returned", returned);
        return out;
    }

    private static java.util.List<JsonObject> entries(JsonElement e, String name) {
        java.util.List<JsonObject> out = new java.util.ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        if (!e.isJsonArray()) throw new IllegalArgumentException("result.enchant." + name + " must be an array");
        JsonArray a = e.getAsJsonArray();
        if (a.size() > MAX_LIST) throw new IllegalArgumentException("result.enchant." + name + " has more than " + MAX_LIST + " entries");
        for (JsonElement x : a) {
            if (!x.isJsonObject()) throw new IllegalArgumentException("result.enchant." + name + " entries must be objects");
            out.add(x.getAsJsonObject());
        }
        return out;
    }

    private static long count(JsonElement e, String name) {
        long v = EnchantIndex.whole(e, 0, MAX_COUNT);
        if (v < 0) throw new IllegalArgumentException("result.enchant." + name + " must be a whole number from 0 to " + MAX_COUNT);
        return v;
    }

    private static String key(JsonObject in, String list) {
        String k = AddonRoutes.string(in, "key");
        if (k == null || k.isEmpty()) throw new IllegalArgumentException("result.enchant." + list + " entries need a key");
        return Http.truncate(k, MAX_KEY);
    }

    /**
     * Checks a clean result against the order it is for: result.enchant only on an order with enchant lines, made and short keys from
     * those lines, {@code made <= wanted <= line count}, and {@code delivered <= count} for the lines of an enchant key.
     */
    static void crossCheck(OrderStore.Order o, JsonObject result, long reserve) throws OrderStore.OrderException {
        JsonObject en = result.has("enchant") && result.get("enchant").isJsonObject() ? result.getAsJsonObject("enchant") : null;
        boolean enchantOrder = OrderStore.hasEnchant(o);
        if (en == null && !enchantOrder) return;
        if (!enchantOrder) throw new OrderStore.OrderException(400, "result.enchant isn't valid for an order without enchant lines", -1);
        Map<String, Long> sum = new HashMap<>();
        Map<String, Long> largest = new HashMap<>();
        Map<String, Long> allLines = new HashMap<>();
        for (OrderStore.Line l : o.lines) {
            allLines.merge(l.key(), l.count(), Long::sum);
            if (l.type().equals("enchant")) {
                sum.merge(l.key(), l.count(), Long::sum);
                largest.merge(l.key(), l.count(), Math::max);
            }
        }
        if (en != null) {
            // The bot may top up beyond the bottles it held (and dip into the repair reserve), but not without bound.
            long limit = OrderStore.takeOf(o.enchant).getOrDefault(Repair.BOTTLE_KEY, 0L) + reserve + 2048;
            if (en.get("bottlesUsed").getAsLong() > limit) {
                throw new OrderStore.OrderException(400, "result.enchant.bottlesUsed is more than this order can have used", -1);
            }
            Map<String, Long> wantedSum = new HashMap<>();
            for (JsonElement x : en.getAsJsonArray("made")) {
                JsonObject m = x.getAsJsonObject();
                String key = m.get("key").getAsString();
                if (!sum.containsKey(key)) throw new OrderStore.OrderException(400, "result.enchant.made names an item that isn't a line of this order", -1);
                long wanted = m.get("wanted").getAsLong();
                if (wanted > largest.get(key)) throw new OrderStore.OrderException(400, "result.enchant.made.wanted is more than the line's count", -1);
                if (wantedSum.merge(key, wanted, Long::sum) > sum.get(key)) {
                    throw new OrderStore.OrderException(400, "result.enchant.made.wanted adds up to more than the order's count", -1);
                }
            }
            for (JsonElement x : en.getAsJsonArray("short")) {
                if (!sum.containsKey(x.getAsJsonObject().get("key").getAsString())) {
                    throw new OrderStore.OrderException(400, "result.enchant.short names an item that isn't a line of this order", -1);
                }
            }
        }
        if (result.has("lines") && result.get("lines").isJsonArray()) {
            for (JsonElement x : result.getAsJsonArray("lines")) {
                JsonObject l = x.getAsJsonObject();
                String key = l.get("key").getAsString();
                if (sum.containsKey(key) && l.get("delivered").getAsLong() > allLines.get(key)) {
                    throw new OrderStore.OrderException(400, "result.lines delivered is more than the order's count", -1);
                }
            }
        }
    }
}

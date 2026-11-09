package com.porter.qwerte.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The repair rules of the order contract (REPAIR-CONTRACT.md), pure: no Minecraft types, no I/O. The server, the website and the
 * proxy plugin use the same formula, so the numbers agree everywhere.
 * <ul>
 * <li>One XP bottle gives about 7 XP and Mending repairs 2 durability per XP point, so one bottle is about
 * {@value #DURABILITY_PER_BOTTLE} durability: {@link #bottlesFor(int)}.</li>
 * <li>An item can be repaired when it has Mending, is damaged and is a single item (max stack 1): {@link #reason}.</li>
 * <li>{@link #estimate} turns the order's lines into the contract's {@code repairEstimate}.</li>
 * </ul>
 */
public final class RepairEstimate {
    private RepairEstimate() {}

    /** Durability one bottle repairs (about 7 XP, 2 durability per XP point). */
    public static final int DURABILITY_PER_BOTTLE = 14;
    /** The index key of a plain bottle o' enchanting. */
    public static final String BOTTLE_KEY = "minecraft:experience_bottle";

    /** Reasons an item is not repaired. The wording is part of the contract. */
    public static final String NO_MENDING = "no Mending", NOT_DAMAGED = "not damaged", NOT_SINGLE = "not a single item",
        NO_BOTTLES = "no bottles", INTERRUPTED = "interrupted";

    /** The server keeps at most this many entries of each list of a result, keys up to 256 and reasons up to 60 characters. */
    public static final int MAX_ENTRIES = 400, MAX_KEY = 256, MAX_REASON = 60;

    /** Bottles to repair {@code damage} durability: {@code ceil(damage / 14)}, 0 for no damage. */
    public static int bottlesFor(int damage) {
        if (damage <= 0) return 0;
        return damage / DURABILITY_PER_BOTTLE + (damage % DURABILITY_PER_BOTTLE == 0 ? 0 : 1);
    }

    /**
     * What an index entry says about one kind of stack. {@code mending} is the bot's flag, or (older bots) true when a
     * {@code details} entry names Mending; build it with {@link #facts}.
     */
    public record Facts(boolean mending, int damage, int maxStack) {}

    /** The facts of an index entry. Without the bot's {@code mending} flag the details are looked at ("Mending", "Mending I"). */
    public static Facts facts(boolean mendingFlag, List<String> details, int damage, int maxStack) {
        return new Facts(mendingFlag || detailsHaveMending(details), damage, maxStack);
    }

    private static final java.util.regex.Pattern MENDING_DETAIL = java.util.regex.Pattern.compile("(?i)mending(?: (?:[ivxlcdm]+|[0-9]+))?");
    private static final java.util.regex.Pattern SHULKER_KEY = java.util.regex.Pattern.compile("[a-z0-9_.-]+:(?:[a-z_]+_)?shulker_box(?:#.*)?");

    /** An index key of a shulker box item ("minecraft:red_shulker_box"). */
    public static boolean isShulkerKey(String key) {
        return key != null && SHULKER_KEY.matcher(key).matches();
    }

    /** Some entry is "Mending" or "Mending <level>" (case-insensitive, trimmed; one space, then a roman numeral or digits), as the bots write it. */
    public static boolean detailsHaveMending(List<String> details) {
        if (details == null) return false;
        for (String d : details) {
            if (d == null) continue;
            if (MENDING_DETAIL.matcher(d.trim()).matches()) return true;
        }
        return false;
    }

    /** Whether one of the item can be repaired by the contract: Mending, damaged, max stack 1. */
    public static boolean repairable(Facts f) {
        return reason(f) == null;
    }

    /** Why the item is not repaired ({@link #NO_MENDING}, {@link #NOT_DAMAGED}, {@link #NOT_SINGLE}), or null when it is. */
    public static String reason(Facts f) {
        if (f == null || !f.mending()) return NO_MENDING;
        if (f.damage() <= 0) return NOT_DAMAGED;
        if (f.maxStack() != 1) return NOT_SINGLE;
        return null;
    }

    /** As {@link #reason(Facts)} for a line key: an item line for a shulker box is "not a single item". */
    public static String reason(String key, Facts f) {
        return isShulkerKey(key) ? NOT_SINGLE : reason(f);
    }

    /** Bottles to repair one of the item fully: 0 when it is not repairable. */
    public static int bottlesPerItem(Facts f) {
        return repairable(f) ? bottlesFor(f.damage()) : 0;
    }

    /** One line of an estimate. {@code bottles} is the line's total ({@code count} times one item); 0 when not repairable. */
    public record LineEstimate(String key, int count, boolean repairable, String reason, long bottles) {}

    /** The contract's {@code repairEstimate}. {@code needed} is the sum of the lines' bottles, {@code isShort} is {@code available < needed}. */
    public record Estimate(long needed, long available, boolean isShort, List<LineEstimate> lines) {
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("needed", needed);
            o.addProperty("available", available);
            o.addProperty("short", isShort);
            JsonArray array = new JsonArray();
            for (LineEstimate l : lines) {
                JsonObject lo = new JsonObject();
                lo.addProperty("key", l.key());
                lo.addProperty("count", l.count());
                lo.addProperty("repairable", l.repairable());
                if (l.reason() == null) lo.add("reason", com.google.gson.JsonNull.INSTANCE);
                else lo.addProperty("reason", l.reason());
                lo.addProperty("bottles", l.bottles());
                array.add(lo);
            }
            o.add("lines", array);
            return o;
        }
    }

    /** Whether the line may be wanted for repair: an item line, and the cart-wide switch or its own toggle (damage not looked at). */
    public static boolean wanted(Order.Line line, boolean repairAll) {
        return Order.ITEM.equals(line.type()) && (repairAll || line.repair());
    }

    /** v1.2: an item line with its own toggle, or under the cart-wide switch one whose item is damaged ({@code f} may be null). */
    public static boolean wanted(Order.Line line, boolean repairAll, Facts f) {
        return Order.ITEM.equals(line.type()) && (line.repair() || (repairAll && f != null && f.damage() > 0));
    }

    /**
     * The estimate for an order, or null when no line is wanted for repair.
     * @param index the facts of an index key, or null when the key is not in the index (then it is "no Mending")
     * @param availableBottles bottles available now; below 0 counts as 0
     */
    public static Estimate estimate(List<Order.Line> lines, boolean repairAll, Function<String, Facts> index, long availableBottles) {
        List<LineEstimate> out = new ArrayList<>();
        long needed = 0;
        for (Order.Line l : lines) {
            Facts f = index == null ? null : index.apply(l.key());
            if (!wanted(l, repairAll, f)) continue;
            String reason = reason(l.key(), f);
            long bottles = reason == null ? (long) l.count() * bottlesFor(f.damage()) : 0;
            needed += bottles;
            out.add(new LineEstimate(l.key(), l.count(), reason == null, reason, bottles));
        }
        if (out.isEmpty()) return null;
        long available = Math.max(0, availableBottles);
        return new Estimate(needed, available, available < needed, List.copyOf(out));
    }

    /** Reads an estimate the server (or the local store) wrote. Null when it is absent or not an object. Forgiving: wrong types give defaults. */
    public static Estimate parseEstimate(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        List<LineEstimate> lines = new ArrayList<>();
        JsonElement arr = o.get("lines");
        if (arr != null && arr.isJsonArray()) {
            for (JsonElement le : arr.getAsJsonArray()) {
                if (!le.isJsonObject() || lines.size() >= MAX_ENTRIES) continue;
                JsonObject lo = le.getAsJsonObject();
                String key = text(lo, "key");
                if (key.isEmpty() || key.length() > MAX_KEY) continue;
                String reason = lo.has("reason") && lo.get("reason").isJsonPrimitive() ? clip(lo.get("reason").getAsString(), MAX_REASON) : null;
                lines.add(new LineEstimate(key, (int) Math.max(0, Math.min(Integer.MAX_VALUE, number(lo, "count"))), bool(lo, "repairable"), reason,
                    Math.max(0, number(lo, "bottles"))));
            }
        }
        return new Estimate(Math.max(0, number(o, "needed")), Math.max(0, number(o, "available")), bool(o, "short"), List.copyOf(lines));
    }

    /** An item that was delivered not fully repaired: what damage was left. */
    public record Partial(String key, int damage, int maxDamage) {}

    /** An item wanted for repair that was not tried (or given up on), and why. */
    public record Skipped(String key, String reason) {}

    /** The contract's {@code result.repair}: how the bot's repairs went. */
    public record Result(int bottlesUsed, int repaired, List<Partial> partial, List<Skipped> skipped) {
        public static final Result NONE = new Result(0, 0, List.of(), List.of());

        public boolean any() {
            return bottlesUsed > 0 || repaired > 0 || !partial.isEmpty() || !skipped.isEmpty();
        }

        /** Number of items delivered not fully repaired (partial plus skipped). */
        public int notFullyRepaired() {
            return partial.size() + skipped.size();
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("bottlesUsed", Math.max(0, bottlesUsed));
            o.addProperty("repaired", Math.max(0, repaired));
            JsonArray p = new JsonArray();
            for (Partial x : partial) {
                if (p.size() >= MAX_ENTRIES || x.key().length() > MAX_KEY) continue;
                JsonObject po = new JsonObject();
                po.addProperty("key", x.key());
                po.addProperty("damage", Math.max(0, x.damage()));
                po.addProperty("maxDamage", Math.max(0, x.maxDamage()));
                p.add(po);
            }
            o.add("partial", p);
            JsonArray s = new JsonArray();
            for (Skipped x : skipped) {
                if (s.size() >= MAX_ENTRIES || x.key().length() > MAX_KEY) continue;
                JsonObject so = new JsonObject();
                so.addProperty("key", x.key());
                so.addProperty("reason", clip(x.reason(), MAX_REASON));
                s.add(so);
            }
            o.add("skipped", s);
            return o;
        }

        /** The note added to the order's message: "3 item(s) not fully repaired: no bottles, interrupted", or "" when all is well. */
        public String note() {
            int n = notFullyRepaired();
            if (n == 0) return "";
            List<String> reasons = new ArrayList<>();
            for (Skipped x : skipped) if (!reasons.contains(x.reason())) reasons.add(x.reason());
            if (!partial.isEmpty() && reasons.isEmpty()) reasons.add("not enough bottles");
            return n + " item(s) not fully repaired" + (reasons.isEmpty() ? "" : ": " + String.join(", ", reasons));
        }
    }

    /** Reads a {@code result.repair}. Null when absent or not an object. */
    public static Result parseResult(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        List<Partial> partial = new ArrayList<>();
        JsonElement pa = o.get("partial");
        if (pa != null && pa.isJsonArray()) {
            for (JsonElement x : pa.getAsJsonArray()) {
                if (!x.isJsonObject() || partial.size() >= MAX_ENTRIES) continue;
                JsonObject xo = x.getAsJsonObject();
                String key = text(xo, "key");
                if (key.isEmpty() || key.length() > MAX_KEY) continue;
                partial.add(new Partial(key, (int) Math.max(0, Math.min(Integer.MAX_VALUE, number(xo, "damage"))),
                    (int) Math.max(0, Math.min(Integer.MAX_VALUE, number(xo, "maxDamage")))));
            }
        }
        List<Skipped> skipped = new ArrayList<>();
        JsonElement sa = o.get("skipped");
        if (sa != null && sa.isJsonArray()) {
            for (JsonElement x : sa.getAsJsonArray()) {
                if (!x.isJsonObject() || skipped.size() >= MAX_ENTRIES) continue;
                JsonObject xo = x.getAsJsonObject();
                String key = text(xo, "key");
                if (key.isEmpty() || key.length() > MAX_KEY) continue;
                skipped.add(new Skipped(key, clip(text(xo, "reason"), MAX_REASON)));
            }
        }
        return new Result((int) Math.max(0, Math.min(Integer.MAX_VALUE, number(o, "bottlesUsed"))),
            (int) Math.max(0, Math.min(Integer.MAX_VALUE, number(o, "repaired"))), List.copyOf(partial), List.copyOf(skipped));
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : "";
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static long number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return 0;
        double d = e.getAsDouble();
        return Double.isFinite(d) ? (long) d : 0;
    }
}

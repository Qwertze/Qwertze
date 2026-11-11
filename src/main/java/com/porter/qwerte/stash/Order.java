package com.porter.qwerte.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An order from the Stash Manager server: what to take from the stash, as lines. Also the packer that turns item lines
 * into shulker loads. Nothing here touches Minecraft types, so it can be tested and ported on its own.
 */
public final class Order {
    public static final String ITEM = "item", SHULKER = "shulker", CRAFT = "craft", KIT = "kit";
    /** The most steps and take entries a craft block may have (CRAFT-CONTRACT.md "Orders"). */
    public static final int MAX_CRAFT_STEPS = 64, MAX_CRAFT_TAKE = 64;
    /** A line asking for more than this is not read (it shows up as undelivered); the server caps them as well. */
    public static final int MAX_LINE_COUNT = 1_000_000;
    /** The most shulkers one order may need. */
    public static final int MAX_BINS = 54;
    public static final int SLOTS = 27;

    /**
     * One line: {@code type} is "item" (key is an items[] key), "shulker" (key is a shulkers[] group key) or "craft" (0.4.0-dev.18: key is a
     * plain item id the stash can make; the order's {@link Craft} block has the plan).
     */
    public record Line(String type, String key, int count, boolean repair) {
        public Line(String type, String key, int count) {
            this(type, key, count, false);
        }

        /** The same line with another count (its repair toggle is kept). */
        public Line withCount(int n) {
            return new Line(type, key, n, repair);
        }
    }

    /**
     * The stacks to put in one slot: an item key and how many, and (0.4.0-dev.18) whether it is a load of a craft line: still to be made before
     * its bin is built, or made (in hand, not in the stash, never topped up from it).
     */
    public record Lot(String key, int count, int craftState) {
        /** An ordinary load, taken from the stash; a load still to be crafted (not in the stash); a load that was crafted (in hand, not in the stash). */
        public static final int NONE = 0, PENDING = 1, CRAFTED = 2;

        public Lot(String key, int count) {
            this(key, count, NONE);
        }

        /** A load of a craft line, still to be made before its bin is built. */
        public static Lot toCraft(String key, int count) {
            return new Lot(key, count, PENDING);
        }

        public boolean craft() {
            return craftState == PENDING;
        }

        public boolean crafted() {
            return craftState == CRAFTED;
        }

        /** The same load with another count (a craft load that came out smaller). */
        public Lot withCount(int n) {
            return new Lot(key, n, craftState);
        }

        /** The same load, made: a stack in hand now, not something to craft. */
        public Lot made(int n) {
            return new Lot(key, n, CRAFTED);
        }
    }

    /** An item id and a count: a craft block's lines, inputs, take and leftovers. */
    public record Qty(String id, int count) {}

    /** One recipe made {@code fills} times, as the server planned it: what it makes per fill ({@code count}) and what goes in, by id. */
    public record CraftStep(String recipe, String result, int count, int fills, List<Qty> inputs) {}

    /**
     * The craft block of an order (CRAFT-CONTRACT.md "Stored order"): the plan the server made at placement. {@code take} is the budget:
     * never more of an id is taken from the stash than it says. {@code malformed} is a block that is there but could not be read: every
     * craft line is then short ({@code recipe_mismatch}). Mirrors the plugin's {@code Order.Craft}.
     */
    public record Craft(int v, String mc, String recipes, List<Qty> lines, List<CraftStep> steps, List<Qty> take, List<Qty> leftovers, int fills, boolean malformed) {
        public static Craft unreadable() {
            return new Craft(0, "", "", List.of(), List.of(), List.of(), List.of(), 0, true);
        }

        /** Grid fills of all the steps. */
        public long stepFills() {
            long n = 0;
            for (CraftStep s : steps) n += s.fills();
            return n;
        }
    }

    /** The longest name the anvil takes. */
    public static final int MAX_NAME = 50;
    /** The most names an "each" list may hold. */
    public static final int MAX_NAMES = 54;

    /**
     * What the shulkers of an order are to be called: none, one name for all ("all"), or one per shulker by position
     * ("each", blank or missing means unnamed). Texts are kept as the server sent them (trimmed); {@link #nameFor}
     * says which are usable.
     */
    public record Names(String mode, String all, List<String> each) {
        public static final String ALL = "all", EACH = "each";

        /** Null when there is nothing to name. */
        public static Names of(String mode, String all, List<String> each) {
            if (ALL.equals(mode)) {
                String t = all == null ? "" : all.trim();
                return t.isBlank() ? null : new Names(ALL, t, List.of());
            }
            if (EACH.equals(mode) && each != null) {
                List<String> list = new ArrayList<>();
                boolean any = false;
                for (int i = 0; i < each.size() && i < MAX_NAMES; i++) {
                    String t = each.get(i) == null ? "" : each.get(i).trim();
                    if (!t.isBlank()) any = true;
                    list.add(t);
                }
                return any ? new Names(EACH, "", list) : null;
            }
            return null;
        }

        /** The name wanted for the i-th shulker (0-based), trimmed; null when it is to stay unnamed. May not pass {@link #valid}. */
        public String nameFor(int i) {
            if (i < 0) return null;
            if (ALL.equals(mode)) return all.isBlank() ? null : all;
            if (i >= each.size()) return null;
            String t = each.get(i);
            return t.isBlank() ? null : t.trim();
        }
    }

    /** A name the bot may put on a shulker: 1 to 50 characters, no control characters and no section sign. */
    public static boolean valid(String name) {
        if (name == null) return false;
        String t = name.trim();
        if (t.isEmpty() || t.length() > MAX_NAME) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isISOControl(c) || c == (char) 0xA7) return false;
        }
        return true;
    }

    /** "3 shulker(s) delivered unnamed: no anvil set, not enough XP levels", or "" when none. */
    public static String unnamedNote(int count, Collection<String> reasons) {
        if (count <= 0) return "";
        return count + " shulker(s) delivered unnamed" + (reasons.isEmpty() ? "" : ": " + String.join(", ", reasons));
    }

    public final String id;
    public final List<Line> lines;
    /** Null: no naming. */
    public final Names names;
    /** The cart-wide "Repair all items" switch (a line is wanted for repair when this or its own toggle is on). */
    public final boolean repairAll;
    /** The estimate made when the order was placed (a local order stores it; null when no line is wanted for repair or none was made). */
    public final RepairEstimate.Estimate repairEstimate;
    /** The craft block (0.4.0-dev.18), or null when the order has no craft lines (or an older server sent none). */
    public final Craft craft;
    /** The kit block (kit sorting v2, kit orders): the templates ordered and the items they may take; null when the order has no kit lines or an older server sent none. */
    public final KitOrder.Snapshot kit;

    public Order(String id, List<Line> lines) {
        this(id, lines, null);
    }

    public Order(String id, List<Line> lines, Names names) {
        this(id, lines, names, false, null);
    }

    public Order(String id, List<Line> lines, Names names, boolean repairAll, RepairEstimate.Estimate repairEstimate) {
        this(id, lines, names, repairAll, repairEstimate, null);
    }

    public Order(String id, List<Line> lines, Names names, boolean repairAll, RepairEstimate.Estimate repairEstimate, Craft craft) {
        this(id, lines, names, repairAll, repairEstimate, craft, null);
    }

    public Order(String id, List<Line> lines, Names names, boolean repairAll, RepairEstimate.Estimate repairEstimate, Craft craft, KitOrder.Snapshot kit) {
        this.kit = kit;
        this.id = id;
        this.lines = lines;
        this.names = names;
        this.repairAll = repairAll;
        this.repairEstimate = repairEstimate;
        this.craft = craft;
    }

    /** The craft lines, in line order. */
    public List<Line> craftLines() {
        List<Line> out = new ArrayList<>();
        for (Line l : lines) if (l.type().equals(CRAFT)) out.add(l);
        return out;
    }

    /** Any line is a craft line. */
    public boolean hasCraft() {
        for (Line l : lines) if (l.type().equals(CRAFT)) return true;
        return false;
    }

    /** Any line is a kit line. */
    public boolean hasKit() {
        for (Line l : lines) if (l.type().equals(KIT)) return true;
        return false;
    }

    /** Whether any line is wanted for repair. */
    public boolean wantsRepair() {
        for (Line l : lines) if (RepairEstimate.wanted(l, repairAll)) return true;
        return false;
    }

    /** Whether this line is wanted for repair. */
    public boolean wantsRepair(Line l) {
        return RepairEstimate.wanted(l, repairAll);
    }

    /**
     * Reads an order object from the server. Null when it has no id or no usable line.
     */
    public static Order parse(JsonObject o) {
        if (o == null || !o.has("id") || !o.has("lines") || !o.get("lines").isJsonArray()) return null;
        List<Line> lines = new ArrayList<>();
        JsonArray array = o.getAsJsonArray("lines");
        for (JsonElement e : array) {
            if (!e.isJsonObject()) continue;
            JsonObject l = e.getAsJsonObject();
            try {
                String type = l.get("type").getAsString();
                String key = l.get("key").getAsString();
                int count = l.get("count").getAsInt();
                boolean repair = l.has("repair") && l.get("repair").isJsonPrimitive() && l.get("repair").getAsJsonPrimitive().isBoolean() && l.get("repair").getAsBoolean();
                if ((type.equals(ITEM) || type.equals(SHULKER) || type.equals(CRAFT) || type.equals(KIT)) && !key.isEmpty() && count >= 1 && count <= MAX_LINE_COUNT) lines.add(new Line(type, key, count, repair));
            } catch (RuntimeException ex) {
                // A broken line is left out and shows up as undelivered
            }
        }
        String id = o.get("id").getAsString();
        boolean repairAll = o.has("repairAll") && o.get("repairAll").isJsonPrimitive() && o.get("repairAll").getAsJsonPrimitive().isBoolean() && o.get("repairAll").getAsBoolean();
        boolean kitLines = false;
        for (Line l : lines) if (l.type().equals(KIT)) kitLines = true;
        // Kit lines without a readable kit block: every kit is short (template_mismatch), the rest of the order goes on
        KitOrder.Snapshot kit = kitLines ? KitOrder.parse(o.get("kit")) : null;
        if (kitLines && kit == null) kit = KitOrder.Snapshot.unreadable();
        return id.isEmpty() || lines.isEmpty() ? null : new Order(id, lines, parseNames(o), repairAll, RepairEstimate.parseEstimate(o.get("repairEstimate")), parseCraft(o.get("craft")), kit);
    }

    /** The craft block: null for none (absent or null), {@link Craft#unreadable()} for one that is there but not what the contract says. */
    static Craft parseCraft(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        try {
            if (!e.isJsonObject()) return Craft.unreadable();
            JsonObject c = e.getAsJsonObject();
            int v = c.get("v").getAsInt();
            String mc = c.get("mc").getAsString(), recipes = c.get("recipes").getAsString();
            List<Qty> lines = qtys(c.get("lines"), "key", 200);
            List<Qty> take = qtys(c.get("take"), "id", MAX_CRAFT_TAKE);
            List<Qty> leftovers = qtys(c.get("leftovers"), "id", 4096);
            List<CraftStep> steps = new ArrayList<>();
            JsonArray sa = c.getAsJsonArray("steps");
            if (sa.size() > MAX_CRAFT_STEPS) return Craft.unreadable();
            for (JsonElement s : sa) {
                JsonObject so = s.getAsJsonObject();
                steps.add(new CraftStep(so.get("recipe").getAsString(), so.get("result").getAsString(), so.get("count").getAsInt(), so.get("fills").getAsInt(),
                    qtys(so.get("inputs"), "id", 64)));
            }
            int fills = c.get("fills").getAsInt();
            return new Craft(v, mc, recipes, lines, steps, take, leftovers, fills, false);
        } catch (RuntimeException ex) {
            return Craft.unreadable();
        }
    }

    private static List<Qty> qtys(JsonElement e, String idName, int max) {
        if (e == null || !e.isJsonArray()) throw new IllegalArgumentException("not a list");
        JsonArray a = e.getAsJsonArray();
        if (a.size() > max) throw new IllegalArgumentException("too many");
        List<Qty> out = new ArrayList<>();
        for (JsonElement x : a) {
            JsonObject o = x.getAsJsonObject();
            int count = o.get("count").getAsInt();
            if (count < 0) throw new IllegalArgumentException("negative");
            out.add(new Qty(o.get(idName).getAsString(), count));
        }
        return out;
    }

    private static boolean isText(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    /** The order's names, or null when it has none or they can't be read (then nothing is named). */
    private static Names parseNames(JsonObject o) {
        try {
            if (!o.has("names") || !o.get("names").isJsonObject()) return null;
            JsonObject n = o.getAsJsonObject("names");
            String mode = n.has("mode") && n.get("mode").isJsonPrimitive() ? n.get("mode").getAsString() : "";
            if (Names.ALL.equals(mode)) {
                return Names.of(mode, isText(n.get("name")) ? n.get("name").getAsString() : "", null);
            }
            if (Names.EACH.equals(mode) && n.has("names") && n.get("names").isJsonArray()) {
                List<String> list = new ArrayList<>();
                // Only JSON strings are names: a number or boolean entry is one shulker left unnamed
                for (JsonElement e : n.getAsJsonArray("names")) {
                    if (list.size() >= MAX_NAMES) break;
                    list.add(isText(e) ? e.getAsString() : "");
                }
                return Names.of(mode, "", list);
            }
        } catch (RuntimeException ex) {
            // Unreadable names: the shulkers are delivered unnamed
        }
        return null;
    }

    /** The total asked for per line key and type, lines with the same type and key merged. */
    public Map<String, Integer> requested() {
        Map<String, Integer> out = new LinkedHashMap<>();
        // A kit line's key is a template id, not an item: kits are counted in result.kit
        for (Line l : lines) if (!l.type().equals(KIT)) out.merge(l.key, l.count, Integer::sum);
        return out;
    }

    /**
     * Splits a total of one item into slot loads: full stacks, the remainder last.
     */
    public static List<Lot> expand(String key, int total, int maxStack) {
        List<Lot> lots = new ArrayList<>();
        int max = Math.max(1, maxStack);
        for (int left = total; left > 0; left -= max) lots.add(new Lot(key, Math.min(max, left)));
        return lots;
    }

    /**
     * Packs slot loads into shulkers of 27 slots, any mix of items allowed. Every load takes a slot, so the fewest
     * shulkers is ceil(slots / 27). Loads of one item are kept together, the items with the most loads first, so a shulker
     * mostly holds few kinds of item and needs fewer trips.
     */
    public static List<List<Lot>> pack(List<Lot> lots) {
        Map<String, List<Lot>> byKey = new TreeMap<>();
        for (Lot lot : lots) byKey.computeIfAbsent(lot.key, k -> new ArrayList<>()).add(lot);

        List<List<Lot>> groups = new ArrayList<>(byKey.values());
        groups.sort((a, b) -> b.size() - a.size());

        List<Lot> ordered = new ArrayList<>();
        for (List<Lot> g : groups) {
            g.sort((a, b) -> b.count - a.count);
            ordered.addAll(g);
        }

        List<List<Lot>> bins = new ArrayList<>();
        for (Lot lot : ordered) {
            if (bins.isEmpty() || bins.get(bins.size() - 1).size() >= SLOTS) bins.add(new ArrayList<>());
            bins.get(bins.size() - 1).add(lot);
        }
        return bins;
    }
}

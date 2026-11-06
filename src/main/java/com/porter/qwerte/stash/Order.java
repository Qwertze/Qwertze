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
    public static final String ITEM = "item", SHULKER = "shulker";
    /** The most shulkers one order may need. */
    public static final int MAX_BINS = 54;
    public static final int SLOTS = 27;

    /** One line: {@code type} is "item" (key is an items[] key) or "shulker" (key is a shulkers[] group key). */
    public record Line(String type, String key, int count) {}

    /** The stacks to put in one slot: an item key and how many. */
    public record Lot(String key, int count) {}

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

    public Order(String id, List<Line> lines) {
        this(id, lines, null);
    }

    public Order(String id, List<Line> lines, Names names) {
        this.id = id;
        this.lines = lines;
        this.names = names;
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
                if ((type.equals(ITEM) || type.equals(SHULKER)) && !key.isEmpty() && count >= 1) lines.add(new Line(type, key, count));
            } catch (RuntimeException ex) {
                // A broken line is left out and shows up as undelivered
            }
        }
        String id = o.get("id").getAsString();
        return id.isEmpty() || lines.isEmpty() ? null : new Order(id, lines, parseNames(o));
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
        for (Line l : lines) out.merge(l.key, l.count, Integer::sum);
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

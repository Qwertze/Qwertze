package com.porter.qwerte.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
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

    public final String id;
    public final List<Line> lines;

    public Order(String id, List<Line> lines) {
        this.id = id;
        this.lines = lines;
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
        return id.isEmpty() || lines.isEmpty() ? null : new Order(id, lines);
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

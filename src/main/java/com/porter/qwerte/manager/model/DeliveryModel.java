package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What is already in the delivery storage (the delivery contents report: {@code GET /api/v1/delivery}, or the same JSON built
 * locally from the sorter's saved stash) and where a planned order would go in it. The layout and the fit text are the
 * website's {@code layoutPlan} and {@code renderDeliveryFit}. Names come from players, so they are cleaned. Pure.
 */
public final class DeliveryModel {
    public static final int GRID = CartPlan.MAX_SHULKERS;

    public record Inner(String id, long count) {
    }

    /** One stack in a chest. {@code contents} is null for anything but a shulker, or when it was not reported. */
    public record Item(int slot, String id, String name, int count, int maxStack, boolean shulker, List<Inner> contents,
                       int damage, int maxDamage) {
    }

    /** {@code items} is null when the chest has not been seen. */
    public record Chest(int n, int size, boolean full, long readAt, List<Item> items) {
        public boolean seen() {
            return items != null;
        }
    }

    /**
     * Where the planned shulkers go in the 54-slot preview. {@code existing} (null for a free slot) is what chest 0 already
     * holds; {@code slotUnit[slot]} is the planned unit there or -1; {@code overflow[slot]} marks the ones past the chest's
     * free room; {@code size} is chest 0's size, or 0 when its contents are not known.
     */
    public record Layout(Item[] existing, int[] slotUnit, int[] unitSlot, boolean[] overflow, int size) {
        public boolean anyOverflow() {
            for (boolean b : overflow) if (b) return true;
            return false;
        }
    }

    /** The line under the preview and the warning, "" for none. */
    public record Fit(String text, String warning) {
    }

    public static final long RETRY_MIN_MS = 5_000, RETRY_MAX_MS = 60_000;

    /** How long to wait before asking for the delivery contents again after a failure: 5 s, doubling to 60 s. */
    public static long retryDelay(long previous) {
        return previous <= 0 ? RETRY_MIN_MS : Math.min(RETRY_MAX_MS, previous * 2);
    }

    public static final DeliveryModel EMPTY = new DeliveryModel(List.of(), 0);

    private final List<Chest> chests;
    private final long readAt;

    private DeliveryModel(List<Chest> chests, long readAt) {
        this.chests = chests;
        this.readAt = readAt;
    }

    /** Never null: anything that is not a report gives {@link #EMPTY}. */
    public static DeliveryModel parse(JsonElement body) {
        JsonObject o = Json.obj(body);
        JsonArray array = o == null ? null : Json.arr(o.get("chests"));
        if (array == null) return EMPTY;
        List<Chest> chests = new ArrayList<>();
        for (JsonElement e : array) {
            JsonObject c = Json.obj(e);
            if (c == null || chests.size() >= 16) continue;
            int size = (int) Json.num(c, "size", 0);
            if (size != 27 && size != 54) continue;
            JsonArray items = Json.arr(c.get("items"));
            List<Item> list = null;
            if (items != null) {
                list = new ArrayList<>();
                boolean[] seen = new boolean[size];
                for (JsonElement ie : items) {
                    Item item = parseItem(Json.obj(ie), size);
                    if (item == null || seen[item.slot()]) continue;
                    seen[item.slot()] = true;
                    list.add(item);
                }
            }
            chests.add(new Chest((int) Json.num(c, "n", chests.size()), size, Json.bool(c, "full"), Math.max(0, Json.lng(c, "readAt", 0)), list == null ? null : List.copyOf(list)));
        }
        return new DeliveryModel(List.copyOf(chests), Math.max(0, Json.lng(o, "readAt", 0)));
    }

    private static Item parseItem(JsonObject o, int size) {
        if (o == null) return null;
        int slot = (int) Json.num(o, "slot", -1);
        if (slot < 0 || slot >= size) return null;
        String id = Text.sanitize(Json.str(o, "id"), 100);
        if (id.isEmpty()) return null;
        String name = Text.sanitize(Json.str(o, "name"), 70);
        boolean shulker = Json.bool(o, "shulker");
        List<Inner> inside = null;
        JsonArray contents = Json.arr(o.get("contents"));
        if (shulker && contents != null) {
            inside = new ArrayList<>();
            for (JsonElement ce : contents) {
                JsonObject c = Json.obj(ce);
                if (c == null || inside.size() >= 27) continue;
                String cid = Text.sanitize(Json.str(c, "id"), 100);
                if (!cid.isEmpty()) inside.add(new Inner(cid, Math.max(0, (long) Json.num(c, "count", 0))));
            }
            inside = List.copyOf(inside);
        }
        return new Item(slot, id, name, (int) Math.max(1, Math.min(100_000, Json.num(o, "count", 1))), (int) Math.max(1, Json.num(o, "maxStack", 64)),
            shulker, inside, (int) Math.max(0, Json.num(o, "damage", 0)), (int) Math.max(0, Json.num(o, "maxDamage", 0)));
    }

    public List<Chest> chests() {
        return chests;
    }

    public boolean isEmpty() {
        return chests.isEmpty();
    }

    /** When the newest chest was read, epoch ms; 0 when never. */
    public long readAt() {
        return readAt;
    }

    /**
     * Chest 0 (the one filled first) keeps its items in their slots; the planned shulkers take the free slots after them,
     * then the rest of the grid (the next chest). Those past the chest's free room are marked.
     */
    public Layout layout(List<CartPlan.Unit> units) {
        Chest c0 = !chests.isEmpty() && chests.get(0).n() == 0 && chests.get(0).seen() ? chests.get(0) : null;
        Item[] existing = new Item[GRID];
        int size = c0 != null ? c0.size() : GRID;
        if (c0 != null) for (Item it : c0.items()) if (it.slot() < GRID) existing[it.slot()] = it;
        int[] slotUnit = new int[GRID];
        int[] unitSlot = new int[units.size()];
        boolean[] overflow = new boolean[GRID];
        java.util.Arrays.fill(slotUnit, -1);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < GRID; i++) if (i < size && existing[i] == null) order.add(i);
        int freeInChest = order.size();
        for (int i = 0; i < GRID; i++) if (i >= size) order.add(i);
        int next = 0;
        for (int ui = 0; ui < units.size(); ui++) {
            if (next >= order.size()) {
                unitSlot[ui] = -1;
                continue;
            }
            int slot = order.get(next++);
            slotUnit[slot] = ui;
            unitSlot[ui] = slot;
            if (c0 != null && (slot >= size || ui >= freeInChest)) overflow[slot] = true;
        }
        return new Layout(existing, slotUnit, unitSlot, overflow, c0 != null ? size : 0);
    }

    /**
     * "Delivery chest: N of 54 slots used, M free; this order needs K (as of X ago)", totals over several chests, and a
     * warning when the order will not fit, saying what the bot will do then.
     *
     * @param need       planned shulkers (0 for an empty cart)
     * @param autoExpand the bot's delivery storage has auto expand on
     * @param now        epoch ms for "X ago"
     */
    public Fit fit(int need, Layout layout, boolean autoExpand, long now) {
        if (chests.isEmpty()) return new Fit("", "");
        int known = 0;
        int used = 0;
        int total = 0;
        for (Chest c : chests) {
            if (!c.seen()) continue;
            known++;
            used += c.items().size();
            total += c.size();
        }
        int unknown = chests.size() - known;
        int free = total - used;
        Chest c0 = chests.get(0);
        String asOf = readAt > 0 ? " (as of " + Text.ago(Math.max(0, now - readAt)) + ")" : "";
        String needText = need > 0 ? "; this order needs " + need : "";
        String text;
        if (chests.size() == 1) {
            text = c0.seen()
                ? "Delivery chest: " + c0.items().size() + " of " + c0.size() + " slots used, " + (c0.size() - c0.items().size()) + " free" + needText + asOf + "."
                : "Delivery chest: not seen yet" + needText + ".";
        } else {
            String first = c0.seen()
                ? "the first (filled first) has " + (c0.size() - c0.items().size()) + " of " + c0.size() + " free"
                : "the first is not seen yet";
            text = chests.size() + " delivery chests: " + (known > 0 ? used + " of " + total + " slots used, " + free + " free" : "none seen yet")
                + (unknown > 0 && known > 0 ? " (" + unknown + " not seen yet)" : "") + "; " + first + needText + asOf + ".";
        }
        String warning = "";
        if (need > free && unknown == 0) {
            warning = "This order needs " + need + " slots but only " + free + " are free: "
                + (autoExpand ? "the bot will put up more chests (if it can)." : "the order will pause until a chest is emptied.");
        } else if (layout != null && layout.anyOverflow()) {
            warning = "Not all of it fits in the first chest: the rest goes into the next one.";
        }
        return new Fit(text, warning);
    }
}

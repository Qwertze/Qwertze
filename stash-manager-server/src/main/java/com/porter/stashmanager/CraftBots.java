package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * What each stash's bot said about crafting in its last heartbeats (CRAFT-CONTRACT.md, "Heartbeat"): the
 * {@code craftOrders} capability and the {@code craftHash} of the settings it has cached. In memory only, like the kit
 * report: after a server restart a bot is unknown until its next heartbeat, so no craft order is placed meanwhile.
 */
final class CraftBots {
    static final int MAX_STASHES = 256;
    static final int MAX_SEEN = 8;
    static final int MAX_FILLS = 100_000;
    static final Set<String> TABLE_STATUS = Set.of("unset", "ok", "unloaded", "not_table", "unreachable", "other_dimension", "auto");
    /** Why the bot can't use a table (optional, craftOrders.tableWhy). */
    static final Set<String> TABLE_WHY = Set.of("no_area", "no_dimension", "wrong_dimension", "too_far", "not_table", "no_stand", "path_failed", "not_loaded", "no_material");
    static final Set<String> BOT_TABLE_SOURCES = Set.of("found", "placed", "crafted");

    /** What the bot can do. seen are the crafting tables it noticed in the stash area (positions: never for streamers). */
    record Capability(int v, boolean enabled, String recipes, String mc, String table, int maxFills, List<String> seen,
                      String tableWhy, BotTable botTable) {
    }

    /** The table the bot itself found, placed or crafted when no website table is set (a position). */
    record BotTable(String pos, String source) {
    }

    /** cap is null when the bot sent none (or cleared it); hasHash tells whether it ever sent a craftHash key. */
    record Beat(Capability cap, boolean hasHash, String hash, long at, String okHash, long capAt) {
        /**
         * okHash: the craft-settings hash the bot had cached the last time it reported its table as ok, so a table it only
         * ever called "unloaded" is known not to have been checked. capAt: when a heartbeat last carried a capability.
         */
    }

    private final java.util.function.LongSupplier clock;
    private final LinkedHashMap<IndexStore.StashKey, Beat> beats = new LinkedHashMap<>();

    CraftBots() {
        this(System::currentTimeMillis);
    }

    CraftBots(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    /** The heartbeat's craftOrders object. Throws IllegalArgumentException (a 400) for a wrong shape. */
    static Capability parse(JsonObject o) {
        Integer v = whole(o.get("v"));
        if (v == null || v < 1) throw new IllegalArgumentException("craftOrders.v must be a whole number >= 1");
        JsonElement en = o.get("enabled");
        if (!(en instanceof JsonPrimitive p) || !p.isBoolean()) throw new IllegalArgumentException("craftOrders.enabled must be true or false");
        String recipes = AddonRoutes.string(o, "recipes");
        if (recipes == null || !KitReports.HASH.matcher(recipes).matches()) {
            throw new IllegalArgumentException("craftOrders.recipes must be 40 lowercase hex digits");
        }
        String mc = null;
        if (o.has("mc") && !o.get("mc").isJsonNull()) {
            mc = AddonRoutes.string(o, "mc");
            if (mc == null || !IconService.MC_VERSION.matcher(mc).matches()) throw new IllegalArgumentException("craftOrders.mc must be a version like 1.21.4");
        }
        String table = AddonRoutes.string(o, "table");
        if (table == null || !TABLE_STATUS.contains(table)) {
            throw new IllegalArgumentException("craftOrders.table must be unset, auto, ok, unloaded, not_table, unreachable or other_dimension");
        }
        Integer fills = whole(o.get("maxFills"));
        if (fills == null || fills < 1 || fills > MAX_FILLS) throw new IllegalArgumentException("craftOrders.maxFills must be a whole number from 1 to " + MAX_FILLS);
        List<String> seen = new ArrayList<>();
        if (o.has("seen") && !o.get("seen").isJsonNull()) {
            if (!o.get("seen").isJsonArray() || o.getAsJsonArray("seen").size() > MAX_SEEN) {
                throw new IllegalArgumentException("craftOrders.seen must be a list of at most " + MAX_SEEN + " positions like \"118,64,-30\"");
            }
            for (JsonElement e : o.getAsJsonArray("seen")) {
                if (!(e instanceof JsonPrimitive sp) || !sp.isString() || !KitNames.validId(sp.getAsString())) {
                    throw new IllegalArgumentException("craftOrders.seen must be a list of positions like \"118,64,-30\"");
                }
                if (!seen.contains(sp.getAsString())) seen.add(sp.getAsString());
            }
        }
        String why = null;
        if (o.has("tableWhy") && !o.get("tableWhy").isJsonNull()) {
            why = AddonRoutes.string(o, "tableWhy");
            if (why == null || !TABLE_WHY.contains(why)) throw new IllegalArgumentException("craftOrders.tableWhy must be one of " + new java.util.TreeSet<>(TABLE_WHY) + " or null");
        }
        BotTable bt = null;
        if (o.has("botTable") && !o.get("botTable").isJsonNull()) {
            if (!o.get("botTable").isJsonObject()) throw new IllegalArgumentException("craftOrders.botTable must be {pos, source} or null");
            JsonObject b = o.getAsJsonObject("botTable");
            String pos = AddonRoutes.string(b, "pos"), src = AddonRoutes.string(b, "source");
            if (pos == null || !KitNames.validId(pos) || src == null || !BOT_TABLE_SOURCES.contains(src)) {
                throw new IllegalArgumentException("craftOrders.botTable must be {pos: \"x,y,z\", source: found|placed|crafted}");
            }
            bt = new BotTable(pos, src);
        }
        return new Capability(v, p.getAsBoolean(), recipes, mc, table, fills, List.copyOf(seen), why, bt);
    }

    private static Integer whole(JsonElement e) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber() || p.getAsString().length() > 20) return null;
        try {
            return p.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    /**
     * A heartbeat: hasCap says the body had a craftOrders key (cap null = cleared); hasHash that it had a craftHash key
     * (hash null = the bot has none). Absent keys keep what is stored.
     */
    synchronized void beat(String server, String stash, boolean hasCap, Capability cap, boolean hasHash, String hash) {
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        Beat old = beats.remove(k); // re-inserted last: the oldest stash is dropped first
        Capability c = hasCap ? cap : old == null ? null : old.cap();
        boolean h = hasHash || (old != null && old.hasHash());
        String hh = hasHash ? hash : old == null ? null : old.hash();
        long now = clock.getAsLong();
        long capAt = hasCap ? (cap == null ? 0 : now) : old == null ? 0 : old.capAt();
        String okHash = old == null ? null : old.okHash();
        if (hasCap && cap != null && cap.table().equals("ok") && hh != null) okHash = hh;
        beats.put(k, new Beat(c, h, hh, now, okHash, capAt));
        while (beats.size() > MAX_STASHES) {
            var it = beats.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    synchronized Beat get(String server, String stash) {
        return beats.get(new IndexStore.StashKey(server, stash));
    }

    /** Whether a heartbeat carried an enabled capability within the last ms milliseconds. */
    synchronized boolean capableWithin(String server, String stash, long ms) {
        Beat b = beats.get(new IndexStore.StashKey(server, stash));
        return b != null && b.cap() != null && b.cap().v() >= 1 && b.cap().enabled() && b.capAt() > 0 && clock.getAsLong() - b.capAt() < ms;
    }

    synchronized void forget(String server, String stash) {
        beats.remove(new IndexStore.StashKey(server, stash));
    }

    /** Whether the stash's last craftOrders (this run) says the bot carries out craft orders. */
    synchronized boolean capable(String server, String stash) {
        Beat b = beats.get(new IndexStore.StashKey(server, stash));
        return b != null && b.cap() != null && b.cap().v() >= 1 && b.cap().enabled();
    }

    static JsonArray strings(List<String> l) {
        JsonArray a = new JsonArray();
        l.forEach(a::add);
        return a;
    }
}

package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Set;

/**
 * What each stash's bot said about its area in its last heartbeat (AREA-CONTRACT.md, "Heartbeat"). In memory only: after a
 * restart the page shows the bot as not heard until its next heartbeat.
 */
final class AreaBots {
    static final int MAX_STASHES = 256;
    static final Set<String> WHY = Set.of("busy", "far", "dimension");

    /** The bot's area report. area is the one it uses now (null: none), derived its working box when it has none. */
    record Report(int v, String hash, AreaStore.Area area, int[] derived, boolean edited, String pendingHash, String pendingWhy,
                  int containersOutside, boolean invalid, long at) {
        Report withDerived(int[] d) {
            return new Report(v, hash, area, d, edited, pendingHash, pendingWhy, containersOutside, invalid, at);
        }

        /** The same report with an area that is not acceptable taken out (it is never stored or shown as applied). */
        Report invalidArea() {
            return new Report(v, hash, null, derived, edited, pendingHash, pendingWhy, containersOutside, true, at);
        }
    }

    /** After this long without an area report the bot's support is unknown; also the wait after a server start. */
    static final long FRESH_MS = 120_000;
    static final int STORES_PER_MINUTE = 3;

    private final long startedAt;
    private final java.util.HashMap<IndexStore.StashKey, String> lastEdit = new java.util.HashMap<>();
    private final java.util.HashMap<IndexStore.StashKey, java.util.ArrayDeque<Long>> stores = new java.util.HashMap<>();
    private final java.util.HashSet<String> logged = new java.util.HashSet<>();

    /** True (area reported lately), null (unknown: the report is old, or the server has just started), false (never reported). */
    synchronized Boolean supports(String server, String stash) {
        Report r = reports.get(new IndexStore.StashKey(server, stash));
        long now = clock.getAsLong();
        if (r != null) return now - r.at() < FRESH_MS ? Boolean.TRUE : null;
        return now - startedAt < FRESH_MS ? null : Boolean.FALSE;
    }

    /** The hash of the last area stored from a bot's edit, or null. */
    synchronized String lastEdit(String server, String stash) {
        return lastEdit.get(new IndexStore.StashKey(server, stash));
    }

    synchronized void lastEdit(String server, String stash, String hash) {
        lastEdit.put(new IndexStore.StashKey(server, stash), hash);
        if (lastEdit.size() > 1024) lastEdit.keySet().remove(lastEdit.keySet().iterator().next());
    }

    /** Counts a change of the stored area made from a heartbeat; false above 3 a minute for the stash. */
    synchronized boolean allowStore(String server, String stash) {
        long now = clock.getAsLong();
        java.util.ArrayDeque<Long> q = stores.computeIfAbsent(new IndexStore.StashKey(server, stash), k -> new java.util.ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() >= 60_000) q.pollFirst();
        if (q.size() >= STORES_PER_MINUTE) return false;
        q.addLast(now);
        if (stores.size() > 1024) stores.values().removeIf(d -> d.isEmpty() || now - d.peekLast() >= 60_000);
        return true;
    }

    /** True the first time a message is logged for (stash, reason): the same refusal is not repeated every heartbeat. */
    synchronized boolean firstTime(String server, String stash, String reason) {
        if (logged.size() > 1024) logged.clear();
        return logged.add(server + "\u0000" + stash + "\u0000" + reason);
    }

    private final LinkedHashMap<IndexStore.StashKey, Report> reports = new LinkedHashMap<>();
    private final java.util.function.LongSupplier clock;

    AreaBots() {
        this(System::currentTimeMillis);
    }

    AreaBots(java.util.function.LongSupplier clock) {
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    /** The heartbeat's area object; IllegalArgumentException (a 400) for a wrong shape. */
    Report parse(JsonObject o) {
        JsonElement ve = o.get("v");
        if (!(ve instanceof JsonPrimitive vp) || !vp.isNumber()) throw new IllegalArgumentException("area.v must be a whole number >= 1");
        int v;
        try {
            v = vp.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("area.v must be a whole number >= 1");
        }
        if (v < 1) throw new IllegalArgumentException("area.v must be a whole number >= 1");
        String hash = AddonRoutes.string(o, "hash");
        if (hash == null || !KitReports.HASH.matcher(hash).matches()) throw new IllegalArgumentException("area.hash must be 40 lowercase hex digits");
        // An area that is out of the contract (caps, Y limits, shape) is not a reason to refuse the whole heartbeat: it is
        // reported as invalid, ignored for storage, and the rest of the heartbeat is handled.
        AreaStore.Area area = null;
        boolean invalid = false;
        if (o.has("area") && !o.get("area").isJsonNull()) {
            try {
                area = AreaStore.parse(o.get("area"));
            } catch (IllegalArgumentException e) {
                invalid = true;
            }
        }
        int[] derived = null;
        if (o.has("derived") && !o.get("derived").isJsonNull()) {
            if (!o.get("derived").isJsonArray() || o.getAsJsonArray("derived").size() != 6) throw new IllegalArgumentException("area.derived must be six whole numbers or null");
            derived = new int[6];
            for (int i = 0; i < 6; i++) {
                JsonElement x = o.getAsJsonArray("derived").get(i);
                if (!(x instanceof JsonPrimitive p) || !p.isNumber()) throw new IllegalArgumentException("area.derived must be six whole numbers or null");
                try {
                    derived[i] = p.getAsBigDecimal().intValueExact();
                } catch (ArithmeticException | NumberFormatException ex) {
                    throw new IllegalArgumentException("area.derived must be six whole numbers or null");
                }
                if (Math.abs(derived[i]) > 30_000_000) throw new IllegalArgumentException("area.derived is out of range");
            }
        }
        boolean edited = false;
        if (o.has("edited") && !o.get("edited").isJsonNull()) {
            if (!(o.get("edited") instanceof JsonPrimitive ep) || !ep.isBoolean()) throw new IllegalArgumentException("area.edited must be true or false");
            edited = ep.getAsBoolean();
        }
        String pendingHash = null, pendingWhy = null;
        if (o.has("pending") && !o.get("pending").isJsonNull()) {
            if (!o.get("pending").isJsonObject()) throw new IllegalArgumentException("area.pending must be {hash, why} or null");
            JsonObject p = o.getAsJsonObject("pending");
            pendingHash = AddonRoutes.string(p, "hash");
            pendingWhy = AddonRoutes.string(p, "why");
            if (pendingHash == null || !KitReports.HASH.matcher(pendingHash).matches() || pendingWhy == null || !WHY.contains(pendingWhy)) {
                throw new IllegalArgumentException("area.pending must be {hash: 40 hex, why: busy|far|dimension} or null");
            }
        }
        int outside = 0;
        if (o.has("containersOutside") && !o.get("containersOutside").isJsonNull()) {
            if (!(o.get("containersOutside") instanceof JsonPrimitive cp) || !cp.isNumber()) throw new IllegalArgumentException("area.containersOutside must be a whole number >= 0");
            try {
                outside = cp.getAsBigDecimal().intValueExact();
            } catch (ArithmeticException | NumberFormatException ex) {
                throw new IllegalArgumentException("area.containersOutside must be a whole number >= 0");
            }
            if (outside < 0) throw new IllegalArgumentException("area.containersOutside must be a whole number >= 0");
        }
        return new Report(v, hash, area, derived, edited, pendingHash, pendingWhy, outside, invalid, clock.getAsLong());
    }

    synchronized void put(String server, String stash, Report r) {
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        reports.remove(k);
        reports.put(k, r);
        while (reports.size() > MAX_STASHES) {
            var it = reports.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    synchronized Report get(String server, String stash) {
        return reports.get(new IndexStore.StashKey(server, stash));
    }

    synchronized void forget(String server, String stash) {
        IndexStore.StashKey k = new IndexStore.StashKey(server, stash);
        reports.remove(k);
        lastEdit.remove(k);
        stores.remove(k);
    }
}

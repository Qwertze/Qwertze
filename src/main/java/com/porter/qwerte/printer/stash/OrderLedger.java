package com.porter.qwerte.printer.stash;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The orders the Printer placed and what is still on the way. Kept by the module (not by one run), so a reload does not
 * forget an order that is still open. Pure: times are milliseconds passed in.
 *
 * <p>{@link #covered} is what the build can count on without ordering again: the lines of open orders, plus delivered lines
 * not yet seen in the inventory (for {@link #DELIVERED_EXPIRY_MS}). {@link #observeCarried} notices those arriving.
 */
public final class OrderLedger {
    public static final long DELIVERED_EXPIRY_MS = 20 * 60_000L;

    public record Placed(String id, String stashKey, Map<String, Long> lines, long sentAt) {}

    public enum Kind { DELIVERED, FAILED, CANCELLED, VANISHED }

    /** @param message the bot note for a failure or cancel (not cleaned here) */
    public record Event(Kind kind, Placed order, String message) {}

    /** What the server said about one order. */
    public record Seen(String id, String status, String message) {}

    public static final String DELIVERED = "delivered", FAILED = "failed", CANCELLED = "cancelled";

    private static final class Delivery {
        final Placed placed;
        final long at;
        final Map<String, Long> uncollected;

        Delivery(Placed p, long at) {
            this.placed = p;
            this.at = at;
            this.uncollected = new LinkedHashMap<>(p.lines());
        }
    }

    private final Map<String, Placed> open = new LinkedHashMap<>();
    private final List<Delivery> delivered = new ArrayList<>();
    private final Set<String> cancelledKeys = new HashSet<>();
    private final Map<String, Set<String>> failedKeys = new HashMap<>();
    private final Map<String, String> lastStatus = new HashMap<>();
    private Map<String, Long> prevCarried;
    private long graceUntil;
    private int shulkersThisRun;
    private List<String> pollCache = List.of();
    private boolean pollDirty;

    /** Milliseconds after the Printer stops fetching on its own during which a rise is not counted as a pickup. */
    public static final long GRACE_MS = 2000;

    public void placed(Placed p) {
        open.put(p.id(), p);
        pollDirty = true;
    }

    public boolean hasOpen() {
        return !open.isEmpty();
    }

    public boolean isOpen(String id) {
        return open.containsKey(id);
    }

    /** The order is known to the ledger, open or delivered. */
    public boolean knows(String id) {
        if (open.containsKey(id)) return true;
        for (Delivery d : delivered) if (d.placed.id().equals(id)) return true;
        return false;
    }

    /** True while some order is open or a delivery is not collected yet. */
    public boolean hasAny() {
        return !open.isEmpty() || !delivered.isEmpty();
    }

    public boolean hasOpen(String stashKey) {
        for (Placed p : open.values()) if (p.stashKey().equals(stashKey)) return true;
        return false;
    }

    /** The stashes with an open order, in the order they were first placed. */
    public List<String> stashesToPoll() {
        if (pollDirty) {
            Set<String> keys = new LinkedHashSet<>();
            for (Placed p : open.values()) keys.add(p.stashKey());
            pollCache = List.copyOf(keys);
            pollDirty = false;
        }
        return pollCache;
    }

    /**
     * What the server now says about the orders of one stash. Open orders of ours that it finished are returned as events;
     * one that is no longer listed has vanished (cleared, or the server forgot it) and is dropped.
     */
    public List<Event> update(String stashKey, List<Seen> entries, long now) {
        Map<String, Seen> byId = new HashMap<>();
        for (Seen s : entries) byId.put(s.id(), s);
        List<Event> events = new ArrayList<>();
        for (Iterator<Placed> it = open.values().iterator(); it.hasNext(); ) {
            Placed p = it.next();
            if (!p.stashKey().equals(stashKey)) continue;
            Seen s = byId.get(p.id());
            if (s == null) {
                it.remove();
                pollDirty = true;
                // Last seen being worked on: it was most likely delivered (and cleared since), so its coverage stays
                String last = lastStatus.remove(p.id());
                if ("accepted".equals(last) || "in_progress".equals(last) || DELIVERED.equals(last)) delivered.add(new Delivery(p, now));
                events.add(new Event(Kind.VANISHED, p, ""));
            } else if (DELIVERED.equals(s.status())) {
                it.remove();
                pollDirty = true;
                lastStatus.remove(p.id());
                delivered.add(new Delivery(p, now));
                events.add(new Event(Kind.DELIVERED, p, s.message()));
            } else if (FAILED.equals(s.status())) {
                it.remove();
                pollDirty = true;
                lastStatus.remove(p.id());
                failedKeys.computeIfAbsent(stashKey, k -> new HashSet<>()).addAll(p.lines().keySet());
                events.add(new Event(Kind.FAILED, p, s.message()));
            } else if (CANCELLED.equals(s.status())) {
                it.remove();
                pollDirty = true;
                lastStatus.remove(p.id());
                cancelledKeys.addAll(p.lines().keySet());
                events.add(new Event(Kind.CANCELLED, p, s.message()));
            } else {
                lastStatus.put(p.id(), s.status());
            }
        }
        return events;
    }

    /** Open lines plus delivered lines still to be collected. */
    public Map<String, Long> covered(long now) {
        Map<String, Long> out = new HashMap<>();
        for (Placed p : open.values()) for (Map.Entry<String, Long> e : p.lines().entrySet()) out.merge(e.getKey(), e.getValue(), Long::sum);
        for (Iterator<Delivery> it = delivered.iterator(); it.hasNext(); ) {
            Delivery d = it.next();
            if (now - d.at > DELIVERED_EXPIRY_MS) {
                it.remove();
                continue;
            }
            for (Map.Entry<String, Long> e : d.uncollected.entrySet()) if (e.getValue() > 0) out.merge(e.getKey(), e.getValue(), Long::sum);
        }
        return out;
    }

    /** The stash key of an open order for this item, or of a delivery not collected yet; null when there is none. */
    public String stashFor(String item, long now) {
        for (Placed p : open.values()) if (p.lines().getOrDefault(item, 0L) > 0) return p.stashKey();
        for (Delivery d : delivered) {
            if (now - d.at <= DELIVERED_EXPIRY_MS && d.uncollected.getOrDefault(item, 0L) > 0) return d.placed.stashKey();
        }
        return null;
    }

    /** True when the item is on an order that is still open (not yet delivered). */
    public boolean onOpenOrder(String item) {
        for (Placed p : open.values()) if (p.lines().getOrDefault(item, 0L) > 0) return true;
        return false;
    }

    /**
     * The carried counts (the same map object until the Printer refreshes it, so a repeat call costs nothing). A rise while the
     * Printer is not fetching things itself, and not within {@link #GRACE_MS} of it having done so, counts as delivered stock
     * being picked up, which takes it off the uncollected lines (oldest delivery first, never more than is uncollected).
     */
    public void observeCarried(Map<String, Long> carried, boolean localFetch, long now) {
        if (localFetch) graceUntil = now + GRACE_MS;
        if (carried == prevCarried) return;
        Map<String, Long> prev = prevCarried;
        prevCarried = carried;
        if (prev == null || localFetch || now < graceUntil) return;
        for (Map.Entry<String, Long> e : carried.entrySet()) {
            long gain = e.getValue() - prev.getOrDefault(e.getKey(), 0L);
            if (gain <= 0) continue;
            for (Delivery d : delivered) {
                long left = d.uncollected.getOrDefault(e.getKey(), 0L);
                if (left <= 0) continue;
                long take = Math.min(left, gain);
                d.uncollected.put(e.getKey(), left - take);
                gain -= take;
                if (gain <= 0) break;
            }
        }
    }

    /** Whether this item must not be ordered from this stash again this run (failed there, or an order for it was cancelled). */
    public boolean blocked(String stashKey, String item) {
        if (cancelledKeys.contains(item)) return true;
        Set<String> failed = failedKeys.get(stashKey);
        return failed != null && failed.contains(item);
    }

    public int shulkersThisRun() {
        return shulkersThisRun;
    }

    public void addShulkers(int n) {
        shulkersThisRun += Math.max(0, n);
    }

    /** A new build: the per-run count and the blocked keys start over; orders still open are kept. */
    public void resetRun() {
        shulkersThisRun = 0;
        cancelledKeys.clear();
        failedKeys.clear();
    }

    /** Forgets the orders (another game server). The per-run shulker count and the blocked keys stay: they belong to the build. */
    public void clear() {
        open.clear();
        delivered.clear();
        lastStatus.clear();
        prevCarried = null;
        pollDirty = true;
    }
}

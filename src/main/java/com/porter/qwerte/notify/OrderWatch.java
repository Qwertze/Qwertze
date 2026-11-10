package com.porter.qwerte.notify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the state changes worth a notification in the lists of orders the server sends, and says each one once. Pure; the
 * module feeds it and plays the sounds.
 *
 * <ul>
 *   <li><b>Baseline.</b> The first list seen for a scope (server + stash, under one origin and user) is only remembered:
 *   orders that were already finished, or already delivering, are not announced. Only changes after that are.</li>
 *   <li><b>Dedupe.</b> Each order notifies at most once for "arriving" and once for its final state. A network error or a
 *   reconnect never touches the memory, so nothing is replayed; a change that happened while away is announced once.</li>
 *   <li><b>Memory.</b> Per scope only the orders of its last list are kept (the server keeps at most 100 per stash), so it
 *   can't grow without bound. Another user or origin clears all of it: that is a new baseline.</li>
 * </ul>
 */
public final class OrderWatch {
    /** One thing to tell the player. */
    public record Notice(String scope, OrderSnap order, Kind kind) {}

    private record State(boolean finished, boolean arrived) {}

    private final Map<String, Map<String, State>> scopes = new HashMap<>();
    private String owner = "";

    /** Who the lists are for (origin and user id); another one starts over with a new baseline. */
    public void setOwner(String key) {
        if (!owner.equals(key)) {
            scopes.clear();
            owner = key;
        }
    }

    /** Whether a list was already seen for this scope (so the next one counts as changes). */
    public boolean baselined(String scope) {
        return scopes.containsKey(scope);
    }

    /** Whether an order of this scope was open (not finished) in the last list. */
    public boolean hasOpen(String scope) {
        Map<String, State> m = scopes.get(scope);
        if (m == null) return false;
        for (State s : m.values()) if (!s.finished()) return true;
        return false;
    }

    /** Takes the user's own orders of one scope (a successful answer only) and returns what changed, in list order. */
    public List<Notice> update(String scope, List<OrderSnap> orders) {
        Map<String, State> prev = scopes.get(scope);
        boolean baseline = prev == null;
        Map<String, State> next = new HashMap<>();
        List<Notice> out = new ArrayList<>();
        for (OrderSnap o : orders) {
            boolean finished = o.finished();
            boolean delivering = o.delivering();
            if (baseline) {
                next.put(o.id(), new State(finished, finished || delivering));
                continue;
            }
            State old = prev.get(o.id());
            boolean wasFinished = old != null && old.finished();
            boolean arrived = old != null && old.arrived();
            if (!wasFinished && finished) {
                out.add(new Notice(scope, o, o.outcome()));
                arrived = true;
            } else if (!wasFinished && !arrived && delivering) {
                out.add(new Notice(scope, o, Kind.ARRIVING));
                arrived = true;
            }
            next.put(o.id(), new State(wasFinished || finished, arrived || wasFinished));
        }
        scopes.put(scope, next);
        return out;
    }
}

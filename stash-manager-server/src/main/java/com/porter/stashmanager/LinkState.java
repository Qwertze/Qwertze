package com.porter.stashmanager;

/**
 * Whether the addon has made a request with the current API key yet, for the website's "Link addon" panel.
 * Requests made with an older key (before a regenerate) don't count.
 */
final class LinkState {
    record Linked(String player, String server, String stash, long lastSeen) {
    }

    private static final int MAX_BOTS = 64;

    private long generation;
    private Linked linked;
    /** Every (server, stash) seen with the current key, most recent last. */
    private final java.util.LinkedHashMap<String, Linked> bots = new java.util.LinkedHashMap<>();

    LinkState(long generation) {
        this.generation = generation;
    }

    /** The key was replaced: wait for the addon to use the new one. */
    synchronized void reset(long newGeneration) {
        generation = newGeneration;
        linked = null;
        bots.clear();
    }

    synchronized void seen(long keyGeneration, String server, String stash, String player) {
        if (keyGeneration != generation) return;
        if (player == null && linked != null && linked.server().equals(server) && linked.stash().equals(stash)) {
            player = linked.player();
        }
        String key = server + "\u0000" + stash;
        Linked prev = bots.remove(key);
        if (player == null && prev != null) player = prev.player();
        linked = new Linked(player, server, stash, System.currentTimeMillis());
        bots.put(key, linked);
        if (bots.size() > MAX_BOTS) bots.remove(bots.keySet().iterator().next());
    }

    /** The bots (one per server and stash) that used the current key, most recent first. */
    synchronized java.util.List<Linked> bots() {
        java.util.List<Linked> out = new java.util.ArrayList<>(bots.values());
        java.util.Collections.reverse(out);
        return out;
    }

    synchronized Linked get() {
        return linked;
    }
}

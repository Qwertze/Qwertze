package com.porter.qwerte.manager.model;

import com.google.gson.JsonObject;

/**
 * One entry of {@code GET /api/v1/stashes}. For a user the owner forced into streamer mode, {@code server} is an
 * opaque id the server made up: it is only ever echoed back, never shown or compared with anything local.
 *
 * @param indexedAt        -1 when the server has no index yet
 * @param rescanPending    -1 when no rescan is waiting for the bot
 * @param containersStale  -1 when the server doesn't say
 */
public record StashRef(String server, String stash, String serverLabel, String player, String owner, boolean online,
                       long indexedAt, long lastSeen, long itemTypes, long itemTotal, long rescanPending,
                       String rescanBlocked, long containersTotal, long containersStale, BotInfo bot) {

    /** Without bot details (a local pseudo-stash). */
    public StashRef(String server, String stash, String serverLabel, String player, String owner, boolean online,
                    long indexedAt, long lastSeen, long itemTypes, long itemTotal, long rescanPending,
                    String rescanBlocked, long containersTotal, long containersStale) {
        this(server, stash, serverLabel, player, owner, online, indexedAt, lastSeen, itemTypes, itemTotal, rescanPending,
            rescanBlocked, containersTotal, containersStale, BotInfo.NONE);
    }

    public static StashRef parse(JsonObject o) {
        String server = Json.str(o, "server");
        String stash = Json.str(o, "stash");
        if (server.isEmpty() || stash.isEmpty()) return null;
        JsonObject c = Json.obj(o.get("containers"));
        return new StashRef(server, stash, Json.str(o, "serverLabel"), Json.str(o, "player"), Json.str(o, "owner"),
            Json.bool(o, "online"), Json.lng(o, "indexedAt", -1), Json.lng(o, "lastSeen", -1), Json.lng(o, "itemTypes", 0),
            Json.lng(o, "itemTotal", 0), Json.lng(o, "rescanPending", -1), Json.str(o, "rescanBlocked"),
            c == null ? -1 : Json.lng(c, "total", -1), c == null ? -1 : Json.lng(c, "stale", -1), BotInfo.parse(o));
    }

    public String key() {
        return server + "\u0000" + stash;
    }

    public boolean hasIndex() {
        return indexedAt >= 0;
    }

    /** What the bot is doing about a rescan, as the website's status line says it; "" when nothing. */
    public String rescanStatus() {
        if (rescanPending >= 0) return "Rescan requested";
        if (!rescanBlocked.isEmpty()) return "Can't rescan: " + Text.sanitize(rescanBlocked, 80);
        if (containersStale > 0 && containersTotal > 0) {
            return "Re-reading containers: " + Text.grouped(Math.max(0, containersTotal - containersStale)) + " / " + Text.grouped(containersTotal);
        }
        return "";
    }

    /**
     * "[on] stash - player @ server". With streamer display on the player and the server address are left out; the
     * server's own label ("Server 1") is used instead. Everything is cleaned of formatting codes.
     */
    public String label(boolean streamer) {
        StringBuilder sb = new StringBuilder(online ? "[on] " : "[off] ").append(Text.sanitize(stash, 48));
        if (!streamer) {
            String who = !owner.isEmpty() ? owner : player;
            if (!who.isEmpty()) sb.append(" - ").append(Text.sanitize(who, 24));
            sb.append(" @ ").append(Text.sanitize(server, 48));
        } else if (!serverLabel.isEmpty()) {
            sb.append(" @ ").append(Text.sanitize(serverLabel, 24));
        }
        return sb.toString();
    }
}

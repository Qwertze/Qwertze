package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;

/**
 * Delivery storage contents (PROTOCOL.md, "Additions: delivery storage contents"):
 * <ul>
 *   <li>POST /api/v1/delivery: the bot's report (signed, the index's owner check, at most 256 KiB);</li>
 *   <li>GET /api/v1/delivery?server=&stash=: the report for the Orders tab (view permission, stash scope).</li>
 * </ul>
 * Kept in memory only and never logged.
 */
final class DeliveryRoutes {
    private final DeliveryContents contents;
    private final Owners owners;

    DeliveryRoutes(DeliveryContents contents, Owners owners) {
        this.contents = contents;
        this.owners = owners;
    }

    void register(Router router) {
        router.exact(Router.POST, "/api/v1/delivery", Router.Guard.ADDON_KEY, this::post)
            .exact(Router.GET, "/api/v1/delivery", Router.Guard.SESSION, Router.Perm.VIEW, this::get);
    }

    private void post(HttpExchange ex) throws IOException {
        Http.readBody(ex, DeliveryContents.MAX_BODY); // 413 above 256 KiB
        JsonElement json = AddonRoutes.readJson(ex);
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        DeliveryContents.Snapshot s;
        try {
            s = contents.parse(json.getAsJsonObject());
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        String server = s.json().get("server").getAsString();
        String stash = s.json().get("stash").getAsString();
        JsonElement player = s.json().get("player");
        String owner = owners.check(server, stash, player == null || player.isJsonNull() ? null : player.getAsString(), true);
        if (owner != null) {
            AddonRoutes.conflict(ex, owner);
            return;
        }
        contents.put(server, stash, s);
        Http.json(ex, 200, Http.ok());
    }

    private void get(HttpExchange ex) throws IOException {
        Map<String, String> q = Http.query(ex);
        String server = q.get("server");
        String stash = q.get("stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty()) {
            Http.error(ex, 400, "server and stash are required");
            return;
        }
        server = OrderRoutes.scoped(ex, server, stash);
        if (server == null) return;
        DeliveryContents.Snapshot s = contents.get(server, stash);
        // null: the bot hasn't reported (an older bot, no delivery container, or not yet since the server started).
        Http.json(ex, 200, s == null ? JsonNull.INSTANCE : Redact.on(ex) ? Redact.delivery(s.json()) : s.json());
    }
}

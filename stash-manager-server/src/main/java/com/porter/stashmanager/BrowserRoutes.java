package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** The website's API: stash list, index, and the "Link addon" panel. No login; the access check applies. */
final class BrowserRoutes {
    private final IndexStore store;
    private final Config config;
    private final LinkState link;
    private final List<String> urls;
    private final OrderStore orders;
    private final Owners owners;
    private final SessionStore sessions;

    BrowserRoutes(IndexStore store, Config config, LinkState link, List<String> urls, OrderStore orders, Owners owners,
                  SessionStore sessions) {
        this.sessions = sessions;
        this.orders = orders;
        this.owners = owners;
        this.store = store;
        this.config = config;
        this.link = link;
        this.urls = List.copyOf(urls);
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/stashes", Router.Guard.SESSION, ex -> Http.json(ex, 200, stashes()))
            .exact(Router.POST, "/api/v1/stashes/release", Router.Guard.BROWSER_WRITE, this::release)
            .exact(Router.GET, "/api/v1/index", Router.Guard.SESSION, this::getIndex)
            .exact(Router.GET, "/api/v1/link", Router.Guard.SESSION, this::getLink)
            .exact(Router.POST, "/api/v1/link/regenerate", Router.Guard.BROWSER_WRITE, this::regenerate)
            .exact(Router.POST, "/api/v1/rescan", Router.Guard.BROWSER_WRITE, this::rescan);
    }

    /** POST /api/v1/rescan {server, stash}: asks the bot to re-read every container (PROTOCOL.md, "manual rescan"). */
    private void rescan(HttpExchange ex) throws IOException {
        com.google.gson.JsonElement json = AddonRoutes.readJson(ex);
        JsonObject o = json != null && json.isJsonObject() ? json.getAsJsonObject() : null;
        String server = o == null ? null : AddonRoutes.string(o, "server");
        String stash = o == null ? null : AddonRoutes.string(o, "stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty()) {
            Http.error(ex, 400, "server and stash are required");
            return;
        }
        long id = store.requestRescan(server, stash);
        if (id < 0) {
            Http.error(ex, 404, "no such stash");
            return;
        }
        JsonObject out = Http.ok();
        out.addProperty("rescanId", id);
        Http.json(ex, 200, out);
    }

    /** The stash list plus owner, the last ownership conflict, and the order summary for the bot overview. */
    private JsonArray stashes() {
        JsonArray list = store.listJson();
        long now = System.currentTimeMillis();
        for (com.google.gson.JsonElement e : list) {
            JsonObject o = e.getAsJsonObject();
            String server = o.get("server").getAsString();
            String stash = o.get("stash").getAsString();
            o.addProperty("owner", owners.owner(server, stash));
            Owners.Conflict c = owners.conflict(server, stash);
            if (c != null) {
                JsonObject co = new JsonObject();
                co.addProperty("player", c.player());
                co.addProperty("at", c.at());
                o.add("conflict", co);
            } else {
                o.add("conflict", com.google.gson.JsonNull.INSTANCE);
            }
            o.add("orders", orders.summaryJson(server, stash));
        }
        return list;
    }

    /** POST /api/v1/stashes/release {server, stash}: clears the owner so another bot can take the stash. */
    private void release(HttpExchange ex) throws IOException {
        com.google.gson.JsonElement json = AddonRoutes.readJson(ex);
        JsonObject o = json != null && json.isJsonObject() ? json.getAsJsonObject() : null;
        String server = o == null ? null : AddonRoutes.string(o, "server");
        String stash = o == null ? null : AddonRoutes.string(o, "stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty()) {
            Http.error(ex, 400, "server and stash are required");
            return;
        }
        boolean had = owners.owner(server, stash) != null;
        if (!had && !store.has(server, stash)) {
            Http.error(ex, 404, "no such stash");
            return;
        }
        owners.release(server, stash);
        JsonObject out = Http.ok();
        out.addProperty("released", had);
        Http.json(ex, 200, out);
    }

    private void getIndex(HttpExchange ex) throws IOException {
        Map<String, String> q = Http.query(ex);
        String server = q.get("server");
        String stash = q.get("stash");
        if (server == null || stash == null) {
            Http.error(ex, 400, "server and stash are required");
            return;
        }
        IndexStore.IndexBytes index = store.index(server, stash);
        if (index == null) {
            Http.error(ex, 404, "no index for that stash");
            return;
        }
        // The gzip variant gets its own ETag ("<hash>-gz"), as HTTP requires for different bytes.
        boolean gzip = Http.acceptsGzip(ex);
        String plainTag = index.etag();
        String gzipTag = plainTag.substring(0, plainTag.length() - 1) + "-gz\"";
        ex.getResponseHeaders().set("ETag", gzip ? gzipTag : plainTag);
        ex.getResponseHeaders().set("Vary", "Accept-Encoding");
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        if (inm != null && (inm.contains(plainTag) || inm.contains(gzipTag) || inm.trim().equals("*"))) {
            Http.send(ex, 304, null, null, "private, no-cache");
            return;
        }
        Http.sendJson(ex, 200, index.bytes(), gzip ? index.gzip() : index.bytes(), "private, no-cache");
    }

    private void getLink(HttpExchange ex) throws IOException {
        JsonObject o = new JsonObject();
        // The key itself only in sudo mode (password typed in the last 15 minutes).
        if (sessions.elevated((String) Http.attr(ex, Router.SESSION_TOKEN))) {
            o.addProperty("apiKey", config.apiKey());
        } else {
            o.addProperty("apiKeySet", true);
            o.addProperty("apiKeyHint", "confirm your password to see the key");
        }
        JsonArray list = new JsonArray();
        urls.forEach(list::add);
        o.add("urls", list);
        LinkState.Linked l = link.get();
        if (l == null) {
            o.add("linked", null);
        } else {
            JsonObject lo = new JsonObject();
            lo.addProperty("player", l.player());
            lo.addProperty("server", l.server());
            lo.addProperty("stash", l.stash());
            lo.addProperty("lastSeen", l.lastSeen());
            o.add("linked", lo);
        }
        JsonArray bots = new JsonArray();
        for (LinkState.Linked b : link.bots()) {
            JsonObject bo = new JsonObject();
            bo.addProperty("player", b.player());
            bo.addProperty("server", b.server());
            bo.addProperty("stash", b.stash());
            bo.addProperty("lastSeen", b.lastSeen());
            bots.add(bo);
        }
        o.add("bots", bots);
        Http.json(ex, 200, o);
    }

    private void regenerate(HttpExchange ex) throws IOException {
        if (!sessions.elevated((String) Http.attr(ex, Router.SESSION_TOKEN))) {
            Http.error(ex, 403, "confirm password");
            return;
        }
        String key;
        synchronized (config) {
            key = config.regenerateKey();
            link.reset(config.keyState().generation());
        }
        System.out.println("API key regenerated from the website (the new key is in config.properties and the Link addon panel).");
        JsonObject o = Http.ok();
        o.addProperty("apiKey", key);
        Http.json(ex, 200, o);
    }
}

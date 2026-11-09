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
    private final AuditLog audit;
    private final DeliveryContents delivery;
    private final KitStore kits;
    private final KitReports kitReports;

    BrowserRoutes(IndexStore store, Config config, LinkState link, List<String> urls, OrderStore orders, Owners owners,
                  SessionStore sessions, AuditLog audit) {
        this(store, config, link, urls, orders, owners, sessions, audit, new DeliveryContents());
    }

    BrowserRoutes(IndexStore store, Config config, LinkState link, List<String> urls, OrderStore orders, Owners owners,
                  SessionStore sessions, AuditLog audit, DeliveryContents delivery) {
        this(store, config, link, urls, orders, owners, sessions, audit, delivery, null, null);
    }

    BrowserRoutes(IndexStore store, Config config, LinkState link, List<String> urls, OrderStore orders, Owners owners,
                  SessionStore sessions, AuditLog audit, DeliveryContents delivery, KitStore kits, KitReports kitReports) {
        this.kits = kits;
        this.kitReports = kitReports;
        this.delivery = delivery;
        this.sessions = sessions;
        this.audit = audit;
        this.orders = orders;
        this.owners = owners;
        this.store = store;
        this.config = config;
        this.link = link;
        this.urls = List.copyOf(urls);
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/stashes", Router.Guard.SESSION, Router.Perm.VIEW, ex -> Http.json(ex, 200, stashes(Router.user(ex))))
            .exact(Router.POST, "/api/v1/stashes/release", Router.Guard.BROWSER_WRITE, Router.Perm.LINK, this::release)
            .exact(Router.GET, "/api/v1/index", Router.Guard.SESSION, Router.Perm.VIEW, this::getIndex)
            .exact(Router.GET, "/api/v1/link", Router.Guard.SESSION, Router.Perm.LINK, this::getLink)
            .exact(Router.POST, "/api/v1/link/regenerate", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::regenerate)
            .exact(Router.POST, "/api/v1/rescan", Router.Guard.BROWSER_WRITE, Router.Perm.RESCAN, this::rescan);
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
        server = OrderRoutes.scoped(ex, server, stash);
        if (server == null) return;
        long id = store.requestRescan(server, stash);
        if (id < 0) {
            Http.error(ex, 404, "no such stash");
            return;
        }
        JsonObject out = Http.ok();
        out.addProperty("rescanId", id);
        Http.json(ex, 200, out);
    }

    /** The stash list (those the user may see) plus owner, the last ownership conflict, and the order summary. */
    private JsonArray stashes(Users.Principal who) {
        JsonArray all = store.listJson();
        JsonArray list = new JsonArray();
        boolean forced = Redact.forced(who);
        for (com.google.gson.JsonElement e : all) {
            JsonObject o = e.getAsJsonObject();
            String server = o.get("server").getAsString();
            String stash = o.get("stash").getAsString();
            if (!who.perms().seesStash(server, stash)) continue;
            list.add(o);
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
            // What is already in the delivery storage (the full report: GET /api/v1/delivery). Never in the index.
            DeliveryContents.Snapshot dc = delivery.get(server, stash);
            o.add("deliveryContents", dc == null ? com.google.gson.JsonNull.INSTANCE : dc.summary());
            o.add("kits", kitSummary(server, stash));
            if (forced) Redact.stash(o);
        }
        // "Server 1": what a page in streamer mode shows instead of the address (numbered over the visible stashes).
        java.util.Map<String, String> labels = Redact.serverLabels(list);
        for (com.google.gson.JsonElement e : list) {
            JsonObject o = e.getAsJsonObject();
            o.addProperty("serverLabel", labels.get(o.get("server").getAsString()));
        }
        return list;
    }

    /** {supports, synced, flagged} (numbers and booleans only, KIT-CONTRACT.md), or null when this server has no kit settings. */
    private com.google.gson.JsonElement kitSummary(String server, String stash) {
        if (kits == null || kitReports == null) return com.google.gson.JsonNull.INSTANCE;
        KitStore.Settings s;
        try {
            s = kits.get(server, stash);
        } catch (KitStore.DisabledException e) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        KitReports.Beat beat = kitReports.beat(server, stash);
        JsonObject view = kitReports.view(server, stash, s, kitReports.get(server, stash), beat);
        JsonObject o = new JsonObject();
        boolean supports = beat != null && beat.supports();
        o.addProperty("supports", supports);
        o.addProperty("synced", supports && s.hash().equals(beat.hash()));
        o.addProperty("flagged", KitReports.flagged(view));
        return o;
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
        server = OrderRoutes.scoped(ex, server, stash);
        if (server == null) return;
        boolean had = owners.owner(server, stash) != null;
        if (!had && !store.has(server, stash)) {
            Http.error(ex, 404, "no such stash");
            return;
        }
        owners.release(server, stash);
        audit.add(Router.user(ex), "stash.release", server + " / " + stash, AuthRoutes.address(ex));
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
        server = OrderRoutes.scoped(ex, server, stash);
        if (server == null) return;
        IndexStore.IndexBytes index = store.index(server, stash);
        if (index == null) {
            Http.error(ex, 404, "no index for that stash");
            return;
        }
        if (Redact.on(ex)) {
            // Forced streamer mode: without the player and dimension (and no shared ETag with the full form).
            Http.json(ex, 200, Redact.index(index.bytes()));
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
        // The key itself: the owner only (it lets a bot do anything a bot can, past every user's limits), and only in
        // sudo mode (password typed in the last 15 minutes).
        if (!Router.user(ex).owner()) {
            o.addProperty("apiKeySet", true);
            o.addProperty("apiKeyOwnerOnly", true);
            o.addProperty("apiKeyHint", "only the owner can see the key");
        } else if (sessions.elevated((String) Http.attr(ex, Router.SESSION_TOKEN))) {
            o.addProperty("apiKey", config.apiKey());
        } else {
            o.addProperty("apiKeySet", true);
            o.addProperty("apiKeyHint", "confirm your password to see the key");
        }
        JsonArray list = new JsonArray();
        urls.forEach(list::add);
        o.add("urls", list);
        Users.Perms perms = Router.user(ex).perms();
        LinkState.Linked l = link.get();
        if (l != null && !perms.seesStash(l.server(), l.stash())) l = null;
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
            if (!perms.seesStash(b.server(), b.stash())) continue;
            JsonObject bo = new JsonObject();
            bo.addProperty("player", b.player());
            bo.addProperty("server", b.server());
            bo.addProperty("stash", b.stash());
            bo.addProperty("lastSeen", b.lastSeen());
            bots.add(bo);
        }
        o.add("bots", bots);
        if (Redact.on(ex)) Redact.link(o);
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
        audit.add(Router.user(ex), "apikey.regenerate", null, AuthRoutes.address(ex));
        System.out.println("API key regenerated from the website (the new key is in config.properties and the Link addon panel).");
        JsonObject o = Http.ok();
        o.addProperty("apiKey", key);
        Http.json(ex, 200, o);
    }
}

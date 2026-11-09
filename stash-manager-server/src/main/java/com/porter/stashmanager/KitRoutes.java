package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Kit sorting (KIT-CONTRACT.md v1.1):
 * <ul>
 *   <li>POST /api/v1/kits/report: the bot's report (signed, the index's owner check, at most 512 KiB);</li>
 *   <li>GET /api/v1/kits and POST /api/v1/kits/chest, /rule, /rule/delete, /scoring: the website's Kits tab.</li>
 * </ul>
 * The browser routes show positions and names a player typed, so a user in forced streamer mode gets 403 on all five.
 */
final class KitRoutes {
    static final int MAX_POST = 4096;
    static final int WRITES_PER_MINUTE = 60;
    private static final long WINDOW_MS = 60_000;

    private final IndexStore index;
    private final KitStore store;
    private final KitReports reports;
    private final Owners owners;
    private final AuditLog audit;
    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Long>> writes = new HashMap<>();

    KitRoutes(KitStore store, KitReports reports, Owners owners, AuditLog audit, IndexStore index) {
        this(store, reports, owners, audit, index, System::currentTimeMillis);
    }

    KitRoutes(KitStore store, KitReports reports, Owners owners, AuditLog audit, IndexStore index, LongSupplier clock) {
        this.index = index;
        this.store = store;
        this.reports = reports;
        this.owners = owners;
        this.audit = audit;
        this.clock = clock;
    }

    void register(Router router) {
        router.exact(Router.POST, "/api/v1/kits/report", Router.Guard.ADDON_KEY, this::report)
            .exact(Router.GET, "/api/v1/kits", Router.Guard.SESSION, Router.Perm.VIEW, this::get)
            .exact(Router.POST, "/api/v1/kits/chest", Router.Guard.BROWSER_WRITE, Router.Perm.SETTINGS, this::chest)
            .exact(Router.POST, "/api/v1/kits/rule", Router.Guard.BROWSER_WRITE, Router.Perm.SETTINGS, this::rule)
            .exact(Router.POST, "/api/v1/kits/rule/delete", Router.Guard.BROWSER_WRITE, Router.Perm.SETTINGS, this::deleteRule)
            .exact(Router.POST, "/api/v1/kits/scoring", Router.Guard.BROWSER_WRITE, Router.Perm.SETTINGS, this::scoring);
    }

    // ---------- the bot ----------

    private void report(HttpExchange ex) throws IOException {
        JsonElement json = AddonRoutes.readJson(ex, KitReports.MAX_BODY); // 413 above 512 KiB
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        String server = AddonRoutes.string(o, "server");
        String stash = AddonRoutes.string(o, "stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty()
            || server.length() > KitReports.MAX_FIELD || stash.length() > KitReports.MAX_FIELD
            || !KitNames.wellFormed(server) || !KitNames.wellFormed(stash)) {
            Http.error(ex, 400, "server and stash must be non-empty text");
            return;
        }
        String player = AddonRoutes.string(o, "player");
        if (player == null || player.isEmpty() || player.length() > KitReports.MAX_FIELD) {
            Http.error(ex, 400, "player must be non-empty text");
            return;
        }
        KitReports.Report r;
        try {
            r = reports.parse(o);
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        // Only a stash the server already knows (a heartbeat or index came first): a report can't make up stashes.
        if (!index.has(server, stash)) {
            Http.error(ex, 400, "unknown stash: send a heartbeat first");
            return;
        }
        String owner = owners.check(server, stash, player, true);
        if (owner != null) {
            AddonRoutes.conflict(ex, owner);
            return;
        }
        reports.put(server, stash, r);
        Http.json(ex, 200, Http.ok());
    }

    // ---------- the website ----------

    /** A forced streamer user is refused (403 streamer). True when the request may go on. */
    private static boolean notForced(HttpExchange ex) throws IOException {
        if (!Redact.on(ex)) return true;
        Router.forbid(ex, "use kits in streamer mode (enforced by the owner)", "streamer");
        return false;
    }

    private static JsonObject body(HttpExchange ex) throws IOException {
        JsonElement json = AddonRoutes.readJson(ex, MAX_POST); // 413 above 4 KiB
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return null;
        }
        return json.getAsJsonObject();
    }

    private void get(HttpExchange ex) throws IOException {
        if (!notForced(ex)) return;
        Map<String, String> q = Http.query(ex);
        String server = q.get("server");
        String stash = q.get("stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty()) {
            Http.error(ex, 400, "server and stash are required");
            return;
        }
        server = OrderRoutes.scoped(ex, server, stash);
        if (server == null) return;
        respond(ex, server, stash);
    }

    private void respond(HttpExchange ex, String server, String stash) throws IOException {
        KitStore.Settings s;
        try {
            s = store.get(server, stash);
        } catch (KitStore.DisabledException e) {
            Http.error(ex, 503, "kit settings can't be read (the server's kits.json is damaged)");
            return;
        }
        Http.json(ex, 200, reports.view(server, stash, s, reports.get(server, stash), reports.beat(server, stash)));
    }

    /** The shared start of the three writes: streamer, view, body, scope, rate limit, store state. Null after answering. */
    private record Write(JsonObject body, String server, String stash) {
    }

    private Write begin(HttpExchange ex) throws IOException {
        if (!notForced(ex)) return null;
        Users.Principal who = Router.user(ex);
        if (!who.perms().view()) {
            Router.forbid(ex, "view stashes", "view");
            return null;
        }
        JsonObject o = body(ex);
        if (o == null) return null;
        String server = AddonRoutes.string(o, "server");
        String stash = AddonRoutes.string(o, "stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty()) {
            Http.error(ex, 400, "server and stash are required");
            return null;
        }
        if (server.length() > KitReports.MAX_FIELD || stash.length() > KitReports.MAX_FIELD
            || !KitNames.wellFormed(server) || !KitNames.wellFormed(stash)) {
            Http.error(ex, 400, "server and stash must be text of at most " + KitReports.MAX_FIELD + " characters");
            return null;
        }
        server = OrderRoutes.scoped(ex, server, stash);
        if (server == null) return null;
        // Settings are only made for a stash the server already knows, so the 64-stash limit can't be used up with made-up names.
        if (!index.has(server, stash) && !store.knows(server, stash)) {
            Http.error(ex, 404, "no such stash");
            return null;
        }
        long wait = rateLimit(who.id());
        if (wait > 0) {
            long seconds = Math.max(1, (wait + 999) / 1000);
            ex.getResponseHeaders().set("Retry-After", Long.toString(seconds));
            JsonObject e = new JsonObject();
            e.addProperty("ok", false);
            e.addProperty("error", "too many kit changes, wait a moment");
            e.addProperty("retryAfter", seconds);
            Http.json(ex, 429, e);
            return null;
        }
        if (store.disabled()) {
            Http.error(ex, 503, "kit settings can't be read (the server's kits.json is damaged)");
            return null;
        }
        return new Write(o, server, stash);
    }

    /** 0 when the user may write now (and the write is counted), else the milliseconds until they may. */
    private synchronized long rateLimit(String userId) {
        long now = clock.getAsLong();
        ArrayDeque<Long> q = writes.computeIfAbsent(userId, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) q.pollFirst();
        if (q.size() >= WRITES_PER_MINUTE) return WINDOW_MS - (now - q.peekFirst());
        q.addLast(now);
        if (writes.size() > 256) writes.values().removeIf(d -> d.isEmpty() || now - d.peekLast() >= WINDOW_MS);
        return 0;
    }

    /** A container id from the body ("container"): null when absent or null. Throws when it is the wrong shape. */
    private static String container(JsonObject o, boolean nullOk) {
        JsonElement e = o.get("container");
        if (e == null || e.isJsonNull()) {
            if (nullOk) return null;
            throw new IllegalArgumentException("container is required");
        }
        String id = AddonRoutes.string(o, "container");
        if (id == null || !KitNames.validId(id)) throw new IllegalArgumentException("container must be an id like \"120,64,-33\"");
        return id;
    }

    /** Checks the picked container against the latest report. False after answering 409 or 400. */
    private boolean pickable(HttpExchange ex, String server, String stash, String id) throws IOException {
        KitReports.Report r = reports.get(server, stash);
        if (r == null) {
            Http.error(ex, 409, "the bot hasn't reported its containers yet");
            return false;
        }
        KitReports.Container c = r.container(id);
        if (c == null || !c.pickable()) {
            Http.error(ex, 400, "that container can't take kits");
            return false;
        }
        return true;
    }

    private void chest(HttpExchange ex) throws IOException {
        Write w = begin(ex);
        if (w == null) return;
        String id;
        try {
            id = container(w.body(), true);
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        if (id != null && !pickable(ex, w.server(), w.stash(), id)) return;
        try {
            store.setChest(w.server(), w.stash(), id);
        } catch (KitStore.LimitException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        } catch (KitStore.DisabledException e) {
            Http.error(ex, 503, "kit settings can't be read (the server's kits.json is damaged)");
            return;
        }
        audit.add(Router.user(ex), "kits.chest", w.stash() + ": " + (id == null ? "(none)" : id), AuthRoutes.address(ex));
        respond(ex, w.server(), w.stash());
    }

    private void rule(HttpExchange ex) throws IOException {
        Write w = begin(ex);
        if (w == null) return;
        String key = AddonRoutes.string(w.body(), "key");
        if (!KitNames.validKey(key)) {
            Http.error(ex, 400, "key must be a normalised kit name (as the bot reports it), at most " + KitNames.MAX_KEY + " characters");
            return;
        }
        String id;
        try {
            id = container(w.body(), false);
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        if (!pickable(ex, w.server(), w.stash(), id)) return;
        try {
            store.setRule(w.server(), w.stash(), key, id);
        } catch (KitStore.LimitException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        } catch (KitStore.DisabledException e) {
            Http.error(ex, 503, "kit settings can't be read (the server's kits.json is damaged)");
            return;
        }
        audit.add(Router.user(ex), "kits.rule", w.stash() + ": " + key + " -> " + id, AuthRoutes.address(ex));
        respond(ex, w.server(), w.stash());
    }

    private static Integer whole(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (!(e instanceof com.google.gson.JsonPrimitive p) || !p.isNumber() || p.getAsString().length() > 20) return null;
        try {
            return p.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    private void scoring(HttpExchange ex) throws IOException {
        Write w = begin(ex);
        if (w == null) return;
        Integer t = whole(w.body(), "threshold"), n = whole(w.body(), "name"), m = whole(w.body(), "mix"),
            f = whole(w.body(), "full"), op = whole(w.body(), "op");
        if (t == null || n == null || m == null || f == null || op == null) {
            Http.error(ex, 400, "threshold, name, mix, full and op must be whole numbers");
            return;
        }
        // opMin is optional: a request without it (an older page) means 3 OP items.
        Integer opMin = w.body().has("opMin") && !w.body().get("opMin").isJsonNull() ? whole(w.body(), "opMin") : Integer.valueOf(3);
        if (opMin == null) {
            Http.error(ex, 400, "opMin must be a whole number");
            return;
        }
        KitStore.Scoring sc = new KitStore.Scoring(t, n, m, f, op, opMin);
        if (!sc.valid()) {
            Http.error(ex, 400, "threshold must be 1 to 40, the points 0 to 10 and opMin 1 to 1728");
            return;
        }
        try {
            store.setScoring(w.server(), w.stash(), sc);
        } catch (KitStore.LimitException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        } catch (KitStore.DisabledException e) {
            Http.error(ex, 503, "kit settings can't be read (the server's kits.json is damaged)");
            return;
        }
        audit.add(Router.user(ex), "kits.scoring", w.stash() + ": threshold " + t + ", name " + n + ", mix " + m + ", full " + f + ", op " + op + ", opMin " + opMin,
            AuthRoutes.address(ex));
        respond(ex, w.server(), w.stash());
    }

    private void deleteRule(HttpExchange ex) throws IOException {
        Write w = begin(ex);
        if (w == null) return;
        String key = AddonRoutes.string(w.body(), "key");
        if (!KitNames.validKey(key)) {
            Http.error(ex, 400, "key must be a normalised name (as the bot reports it), at most " + KitNames.MAX_KEY + " characters");
            return;
        }
        KitStore.Deleted removed;
        try {
            removed = store.deleteRule(w.server(), w.stash(), key);
        } catch (KitStore.DisabledException e) {
            Http.error(ex, 503, "kit settings can't be read (the server's kits.json is damaged)");
            return;
        }
        audit.add(Router.user(ex), "kits.rule.delete", w.stash() + ": " + key + " -> " + removed.previous(), AuthRoutes.address(ex));
        respond(ex, w.server(), w.stash());
    }
}

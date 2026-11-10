package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The stash area from the website (AREA-CONTRACT.md): GET /api/v1/area and POST /api/v1/area. The box is a position, so a
 * user in forced streamer mode gets 403 on both.
 */
final class AreaRoutes {
    static final int MAX_POST = 4096;
    static final int WRITES_PER_MINUTE = 30;
    private static final long WINDOW_MS = 60_000;

    private final AreaStore store;
    private final AreaBots bots;
    private final IndexStore index;
    private final AuditLog audit;
    private final AreaGuard guard;
    private final KitReports kitReports;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Long>> writes = new HashMap<>();

    /** A home point can be reset once an hour per stash. */
    static final long HOME_COOLDOWN_MS = 60L * 60 * 1000;
    /** Without a delivery container a reset may move the home at most this far (horizontally). */
    static final int HOME_RESET_BLOCKS = 128;

    AreaRoutes(AreaStore store, AreaBots bots, IndexStore index, AuditLog audit, AreaGuard guard, KitReports kitReports, SessionStore sessions) {
        this(store, bots, index, audit, guard, kitReports, sessions, System::currentTimeMillis);
    }

    AreaRoutes(AreaStore store, AreaBots bots, IndexStore index, AuditLog audit, AreaGuard guard, KitReports kitReports, SessionStore sessions,
               LongSupplier clock) {
        this.sessions = sessions;
        this.store = store;
        this.bots = bots;
        this.index = index;
        this.audit = audit;
        this.guard = guard;
        this.kitReports = kitReports;
        this.clock = clock;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/area", Router.Guard.SESSION, Router.Perm.VIEW, this::get)
            .exact(Router.POST, "/api/v1/area", Router.Guard.BROWSER_WRITE, Router.Perm.SETTINGS, this::post)
            .exact(Router.POST, "/api/v1/area/home", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::home);
    }

    private static boolean notForced(HttpExchange ex) throws IOException {
        if (!Redact.on(ex)) return true;
        Router.forbid(ex, "use the stash area in streamer mode (enforced by the owner)", "streamer");
        return false;
    }

    private static final String UNREADABLE = "area settings can't be read (the server's area.json is damaged)";

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
        if (!index.has(server, stash) && !store.knows(server, stash)) {
            Http.error(ex, 404, "no such stash");
            return;
        }
        respond(ex, server, stash);
    }

    private synchronized long rateWait(String userId) {
        long now = clock.getAsLong();
        ArrayDeque<Long> q = writes.computeIfAbsent(userId, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) q.pollFirst();
        if (q.size() >= WRITES_PER_MINUTE) return WINDOW_MS - (now - q.peekFirst());
        q.addLast(now);
        if (writes.size() > 256) writes.values().removeIf(d -> d.isEmpty() || now - d.peekLast() >= WINDOW_MS);
        return 0;
    }

    private void post(HttpExchange ex) throws IOException {
        if (!notForced(ex)) return;
        Users.Principal who = Router.user(ex);
        if (!who.perms().view()) {
            Router.forbid(ex, "view stashes", "view");
            return;
        }
        JsonElement json = AddonRoutes.readJson(ex, MAX_POST);
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        String server = AddonRoutes.string(o, "server");
        String stash = AddonRoutes.string(o, "stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty()) {
            Http.error(ex, 400, "server and stash are required");
            return;
        }
        if (server.length() > KitReports.MAX_FIELD || stash.length() > KitReports.MAX_FIELD || !KitNames.wellFormed(server) || !KitNames.wellFormed(stash)) {
            Http.error(ex, 400, "server and stash must be text of at most " + KitReports.MAX_FIELD + " characters");
            return;
        }
        server = OrderRoutes.scoped(ex, server, stash);
        if (server == null) return;
        if (!index.has(server, stash) && !store.knows(server, stash)) {
            Http.error(ex, 404, "no such stash");
            return;
        }
        if (!o.has("area")) {
            Http.error(ex, 400, "area is required: {box, dimension}, or null to clear it");
            return;
        }
        long wait = rateWait(who.id());
        if (wait > 0) {
            long seconds = Math.max(1, (wait + 999) / 1000);
            ex.getResponseHeaders().set("Retry-After", Long.toString(seconds));
            JsonObject e = new JsonObject();
            e.addProperty("ok", false);
            e.addProperty("error", "too many area changes, wait a moment");
            e.addProperty("retryAfter", seconds);
            Http.json(ex, 429, e);
            return;
        }
        if (store.disabled()) {
            Http.error(ex, 503, UNREADABLE);
            return;
        }
        AreaStore.Area area = null;
        if (!o.get("area").isJsonNull()) {
            try {
                area = AreaStore.parse(o.get("area"));
            } catch (IllegalArgumentException e) {
                Http.error(ex, 400, e.getMessage());
                return;
            }
            AreaGuard.Problem p = guard.check(server, stash, area, false, true);
            if (p != null) {
                Http.error(ex, p.status(), p.message());
                return;
            }
        }
        // The page sends the revision it was showing; a save made on an older one is refused ("changed meanwhile").
        JsonElement re = o.get("rev");
        try {
            if (re != null && !re.isJsonNull()) {
                if (!(re instanceof com.google.gson.JsonPrimitive rp) || !rp.isNumber()) {
                    Http.error(ex, 400, "rev must be a whole number");
                    return;
                }
                store.setChecked(server, stash, area, "website", who.name(), rp.getAsBigDecimal().longValueExact());
            } else {
                store.set(server, stash, area, "website", who.name());
            }
        } catch (ArithmeticException e) {
            Http.error(ex, 400, "rev must be a whole number");
            return;
        } catch (AreaStore.StaleException e) {
            Http.error(ex, 409, "the area changed meanwhile; reload the page and try again");
            return;
        } catch (AreaStore.LimitException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        } catch (AreaStore.DisabledException e) {
            Http.error(ex, 503, UNREADABLE);
            return;
        }
        audit.add(who, area == null ? "area.clear" : "area.set", stash + ": " + (area == null ? "(none)"
            : area.minX() + "," + area.minY() + "," + area.minZ() + " to " + area.maxX() + "," + area.maxY() + "," + area.maxZ() + " " + area.dimension()),
            AuthRoutes.address(ex));
        respond(ex, server, stash);
    }

    private static JsonElement boxJson(int[] b, String dimension) {
        return new AreaStore.Area(b[0], b[1], b[2], b[3], b[4], b[5], dimension).toJson();
    }

    /** POST /api/v1/area/home {server, stash, reset: true}: puts the stash's home back at its delivery container or the area's centre. */
    private void home(HttpExchange ex) throws IOException {
        if (!notForced(ex)) return;
        Users.Principal who = Router.user(ex);
        if (!who.perms().view()) {
            Router.forbid(ex, "view stashes", "view");
            return;
        }
        JsonElement json = AddonRoutes.readJson(ex, MAX_POST);
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        String server = AddonRoutes.string(o, "server");
        String stash = AddonRoutes.string(o, "stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty()) {
            Http.error(ex, 400, "server and stash are required");
            return;
        }
        if (server.length() > KitReports.MAX_FIELD || stash.length() > KitReports.MAX_FIELD || !KitNames.wellFormed(server) || !KitNames.wellFormed(stash)) {
            Http.error(ex, 400, "server and stash must be text of at most " + KitReports.MAX_FIELD + " characters");
            return;
        }
        server = OrderRoutes.scoped(ex, server, stash);
        if (server == null) return;
        if (!index.has(server, stash) && !store.knows(server, stash)) {
            Http.error(ex, 404, "no such stash");
            return;
        }
        if (!(o.get("reset") instanceof com.google.gson.JsonPrimitive rp) || !rp.isBoolean() || !rp.getAsBoolean()) {
            Http.error(ex, 400, "reset must be true");
            return;
        }
        long wait = rateWait(who.id());
        if (wait > 0) {
            long seconds = Math.max(1, (wait + 999) / 1000);
            ex.getResponseHeaders().set("Retry-After", Long.toString(seconds));
            JsonObject e = new JsonObject();
            e.addProperty("ok", false);
            e.addProperty("error", "too many area changes, wait a moment");
            e.addProperty("retryAfter", seconds);
            Http.json(ex, 429, e);
            return;
        }
        if (store.disabled()) {
            Http.error(ex, 503, UNREADABLE);
            return;
        }
        // Owner only (the route), with Admin unlocked, at most once an hour per stash.
        if (!sessions.elevated((String) Http.attr(ex, Router.SESSION_TOKEN))) {
            Http.error(ex, 403, "confirm password");
            return;
        }
        IndexStore.StashKey key = new IndexStore.StashKey(server, stash);
        long lastReset = store.lastHomeReset(server, stash);
        if (lastReset > 0 && clock.getAsLong() - lastReset < HOME_COOLDOWN_MS) {
            Http.error(ex, 409, "the home point was reset less than an hour ago; try again later");
            return;
        }
        String dim = guard.dimension(server, stash);
        if (dim == null) {
            Http.error(ex, 409, "this stash's dimension isn't known yet, so a home point can't be set");
            return;
        }
        int[] to = guard.delivery(server, stash);
        if (to == null) {
            // No delivery container: only a small move, to the centre of the saved area, from the current home point.
            AreaStore.Home old = store.home(server, stash);
            AreaStore.Settings cur;
            try {
                cur = store.find(server, stash);
            } catch (AreaStore.DisabledException e) {
                Http.error(ex, 503, UNREADABLE);
                return;
            }
            // The area must be in the dimension the stash is in now: its centre means nothing in another one.
            if (cur != null && cur.area() != null && old != null
                && AreaStore.normalizeDimension(cur.area().dimension()).equals(AreaStore.normalizeDimension(dim))) {
                AreaStore.Area a = cur.area();
                int[] c = {Math.floorDiv(a.minX() + a.maxX(), 2), Math.floorDiv(a.minY() + a.maxY(), 2), Math.floorDiv(a.minZ() + a.maxZ(), 2)};
                if (Math.hypot(c[0] - old.x(), c[2] - old.z()) <= HOME_RESET_BLOCKS) to = c;
            }
        }
        if (to == null) {
            Http.error(ex, 409, "the home point can only be reset to the delivery container; the stash has none, and the saved area's centre is not within "
                + HOME_RESET_BLOCKS + " blocks of the current home point");
            return;
        }
        // The cooldown is taken first, atomically (two requests at once can't both pass), and given back if the reset fails.
        long previous;
        try {
            previous = store.reserveHomeReset(server, stash, clock.getAsLong(), HOME_COOLDOWN_MS);
        } catch (AreaStore.DisabledException e) {
            Http.error(ex, 503, UNREADABLE);
            return;
        }
        if (previous == -2) {
            Http.error(ex, 409, "the home point was reset less than an hour ago; try again later");
            return;
        }
        try {
            store.resetHome(server, stash, new AreaStore.Home(to[0], to[1], to[2], false, dim));
        } catch (AreaStore.LimitException e) {
            store.rollbackHomeReset(server, stash, previous);
            Http.error(ex, 400, e.getMessage());
            return;
        } catch (AreaStore.DisabledException e) {
            store.rollbackHomeReset(server, stash, previous);
            Http.error(ex, 503, UNREADABLE);
            return;
        } catch (IOException | RuntimeException e) {
            store.rollbackHomeReset(server, stash, previous);
            throw e;
        }
        audit.add(who, "area.home", stash + ": " + to[0] + "," + to[1] + "," + to[2], AuthRoutes.address(ex));
        respond(ex, server, stash);
    }

    private void respond(HttpExchange ex, String server, String stash) throws IOException {
        AreaStore.Settings s;
        try {
            s = store.find(server, stash);
        } catch (AreaStore.DisabledException e) {
            Http.error(ex, 503, UNREADABLE);
            return;
        }
        String dim = index.dimension(server, stash);
        AreaBots.Report r = bots.get(server, stash);
        JsonObject o = new JsonObject();
        o.addProperty("server", server);
        o.addProperty("stash", stash);
        o.addProperty("rev", s == null ? 0 : s.rev());
        o.addProperty("hash", s == null ? AreaStore.hash(null) : s.hash());
        o.add("area", s == null || s.area() == null ? JsonNull.INSTANCE : s.area().toJson());
        o.addProperty("by", s == null ? null : s.by());
        o.addProperty("user", s == null ? null : s.user());
        o.addProperty("updatedAt", s == null || s.updatedAt() == 0 ? null : s.updatedAt());
        o.addProperty("dimension", AreaStore.normalizeDimension(dim));
        AreaStore.Home home = store.home(server, stash);
        if (home == null) {
            o.add("home", JsonNull.INSTANCE);
            o.add("homeSource", JsonNull.INSTANCE);
        } else {
            JsonArray h = new JsonArray();
            for (int v : home.pos()) h.add(v);
            o.add("home", h);
            // "delivery": at the delivery container; "bot": provisional, from the bot's first report (replaced once a delivery container appears)
            o.addProperty("homeSource", home.provisional() ? "bot" : "delivery");
            o.addProperty("homeStale", home.staleFor(AreaStore.normalizeDimension(dim)));
        }
        JsonObject bot = new JsonObject();
        Boolean supports = bots.supports(server, stash);
        if (supports == null) bot.add("supports", JsonNull.INSTANCE); // not heard lately, or the server has just started
        else bot.addProperty("supports", supports);
        bot.addProperty("online", r != null && index.online(server, stash));
        bot.addProperty("synced", r != null && r.hash().equals(s == null ? AreaStore.hash(null) : s.hash()));
        bot.add("applied", r == null || r.area() == null ? JsonNull.INSTANCE : r.area().toJson());
        bot.add("derived", r == null || r.derived() == null ? JsonNull.INSTANCE : derivedJson(r.derived()));
        if (r == null || r.pendingWhy() == null) {
            bot.add("pending", JsonNull.INSTANCE);
        } else {
            JsonObject p = new JsonObject();
            p.addProperty("why", r.pendingWhy());
            bot.add("pending", p);
        }
        bot.addProperty("containersOutside", r == null ? 0 : r.containersOutside());
        o.add("bot", bot);
        o.add("impact", impact(server, stash, s));
        JsonObject limits = new JsonObject();
        limits.addProperty("maxW", AreaStore.MAX_W);
        limits.addProperty("maxH", AreaStore.MAX_H);
        limits.addProperty("maxD", AreaStore.MAX_D);
        int[] yr = AreaStore.yRange(dim == null ? "minecraft:overworld" : dim);
        limits.addProperty("yMin", yr[0]);
        limits.addProperty("yMax", yr[1]);
        limits.addProperty("farBlocks", AreaStore.FAR_BLOCKS);
        o.add("limits", limits);
        Http.json(ex, 200, o);
    }

    private static JsonArray derivedJson(int[] d) {
        JsonArray a = new JsonArray();
        for (int v : d) a.add(v);
        return a;
    }

    /** {reported, outside} from the latest kit report's containers against the stored area, or null. */
    private JsonElement impact(String server, String stash, AreaStore.Settings s) {
        KitReports.Report kr = kitReports.get(server, stash);
        if (kr == null || s == null || s.area() == null) return JsonNull.INSTANCE;
        int outside = 0;
        for (KitReports.Container c : kr.containers()) {
            String[] p = c.id().split(",");
            if (!s.area().contains(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]))) outside++;
        }
        JsonObject o = new JsonObject();
        o.addProperty("reported", kr.containers().size());
        o.addProperty("outside", outside);
        return o;
    }

    /** The {@code area} entry of GET /api/v1/stashes: {set, synced, pending}, no positions; null when the file is unreadable. */
    JsonElement summaryJson(String server, String stash) {
        AreaStore.Settings s;
        try {
            s = store.find(server, stash);
        } catch (AreaStore.DisabledException e) {
            return JsonNull.INSTANCE;
        }
        AreaBots.Report r = bots.get(server, stash);
        JsonObject o = new JsonObject();
        o.addProperty("set", s != null && s.area() != null);
        o.addProperty("synced", r != null && r.hash().equals(s == null ? AreaStore.hash(null) : s.hash()));
        o.addProperty("pending", r != null && r.pendingWhy() != null);
        return o;
    }
}

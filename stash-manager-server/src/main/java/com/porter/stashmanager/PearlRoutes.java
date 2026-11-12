package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Pearl travel on the wire (PEARL-CONTRACT v1.0 rev 4): the website's GET /api/v1/pearl and its writes, and the plugin loader's
 * signed POST /api/v1/bot/pearls. Every browser route shows stations, player names and the bots' state, so a user in forced
 * streamer mode gets 403 on all of them.
 */
final class PearlRoutes {
    static final int MAX_POST = 4096;
    static final int WRITES_PER_MINUTE = 60;
    static final int TRIPS_PER_MINUTE = 6;
    private static final long WINDOW_MS = 60_000;

    private final PearlEngine engine;
    private final IndexStore index;
    private final BotHub hub;
    private final AuditLog audit;
    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Long>> writes = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> tripWrites = new HashMap<>();

    PearlRoutes(PearlEngine engine, IndexStore index, BotHub hub, AuditLog audit) {
        this(engine, index, hub, audit, System::currentTimeMillis);
    }

    PearlRoutes(PearlEngine engine, IndexStore index, BotHub hub, AuditLog audit, LongSupplier clock) {
        this.engine = engine;
        this.index = index;
        this.hub = hub;
        this.audit = audit;
        this.clock = clock;
    }

    void register(Router router) {
        router.exact(Router.POST, "/api/v1/bot/pearls", Router.Guard.ADDON_KEY, this::botReport)
            .exact(Router.GET, "/api/v1/pearl", Router.Guard.SESSION, Router.Perm.ACTIVITY, this::get)
            .exact(Router.POST, "/api/v1/pearl/stations", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::station)
            .exact(Router.POST, "/api/v1/pearl/stations/delete", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::deleteStation)
            .exact(Router.POST, "/api/v1/pearl/assign", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::assign)
            .exact(Router.POST, "/api/v1/pearl/stations/approve", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::approve)
            .exact(Router.POST, "/api/v1/pearl/stations/dismiss", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::dismiss)
            .exact(Router.POST, "/api/v1/pearl/allowed", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::allowed)
            .exact(Router.POST, "/api/v1/pearl/pins/reset", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::resetPin)
            .exact(Router.POST, "/api/v1/pearl/chambers/reset", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::resetChamber)
            .exact(Router.POST, "/api/v1/pearl/trips", Router.Guard.BROWSER_WRITE, Router.Perm.SETTINGS, this::startTrip)
            .exact(Router.POST, "/api/v1/pearl/trips/stop", Router.Guard.BROWSER_WRITE, Router.Perm.SETTINGS, this::stopTrip)
            .exact(Router.POST, "/api/v1/pearl/away/clear", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::clearAway);
    }

    // ---------- the plugin loader ----------

    private void botReport(HttpExchange ex) throws IOException {
        JsonElement json = AddonRoutes.readJson(ex, 64 * 1024);
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        String botId = AddonRoutes.string(o, "botId");
        BotHub.Bot bot = botId == null ? null : hub.bot(botId);
        if (bot == null) {
            Http.error(ex, botId == null ? 400 : 409, botId == null ? "botId is required" : "say hello first");
            return;
        }
        JsonObject report = o.deepCopy();
        report.remove("botId");
        try {
            Http.json(ex, 200, ok(engine.onLoader(bot.player, "bot:" + botId, report)));
        } catch (PearlEngine.Refused e) {
            Http.error(ex, e.status, e.getMessage());
        }
    }

    private static JsonObject ok(JsonObject extra) {
        JsonObject o = Http.ok();
        extra.entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
        return o;
    }

    // ---------- the website ----------

    private static boolean notForced(HttpExchange ex) throws IOException {
        if (!Redact.on(ex)) return true;
        Router.forbid(ex, "use pearl travel in streamer mode (enforced by the owner)", "streamer");
        return false;
    }

    private synchronized long rateLimit(Map<String, ArrayDeque<Long>> map, int max, String userId) {
        long now = clock.getAsLong();
        ArrayDeque<Long> q = map.computeIfAbsent(userId, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) q.pollFirst();
        if (q.size() >= max) return WINDOW_MS - (now - q.peekFirst());
        q.addLast(now);
        if (map.size() > 256) map.values().removeIf(d -> d.isEmpty() || now - d.peekLast() >= WINDOW_MS);
        return 0;
    }

    private record Write(JsonObject body, Users.Principal who) {
    }

    /** The start of every write: streamer, view (for the stash routes), body, rate limits (trips: the stricter one too), store state. */
    private Write begin(HttpExchange ex, boolean trip) throws IOException {
        if (!notForced(ex)) return null;
        Users.Principal who = Router.user(ex);
        JsonElement json = AddonRoutes.readJson(ex, MAX_POST); // 413 above 4 KiB
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return null;
        }
        long wait = rateLimit(writes, WRITES_PER_MINUTE, who.id());
        if (wait <= 0 && trip) wait = rateLimit(tripWrites, TRIPS_PER_MINUTE, who.id());
        if (wait > 0) {
            long seconds = Math.max(1, (wait + 999) / 1000);
            ex.getResponseHeaders().set("Retry-After", Long.toString(seconds));
            JsonObject e = new JsonObject();
            e.addProperty("ok", false);
            e.addProperty("error", "too many pearl changes, wait a moment");
            e.addProperty("retryAfter", seconds);
            Http.json(ex, 429, e);
            return null;
        }
        if (engine.disabled()) {
            Http.error(ex, 503, "pearl travel is off (the server's pearl.json can't be read)");
            return null;
        }
        return new Write(json.getAsJsonObject(), who);
    }

    private void get(HttpExchange ex) throws IOException {
        if (!notForced(ex)) return;
        if (engine.disabled()) {
            Http.error(ex, 503, "pearl travel is off (the server's pearl.json can't be read)");
            return;
        }
        Http.json(ex, 200, view(Router.user(ex)));
    }

    private JsonObject view(Users.Principal who) {
        // A user sees a station when they can see a stash on its server; trips and travellers by their stash.
        java.util.Set<String> servers = new java.util.HashSet<>();
        for (IndexStore.StashKey k : index.players().keySet()) if (who.owner() || who.perms().seesStash(k.server(), k.stash())) servers.add(k.server());
        return engine.view((s, t) -> who.owner() || who.perms().seesStash(s, t), s -> who.owner() || servers.contains(s), who.owner());
    }

    private void respond(HttpExchange ex, Users.Principal who, JsonObject extra) throws IOException {
        JsonObject v = view(who);
        if (extra != null) extra.entrySet().forEach(e -> v.add(e.getKey(), e.getValue()));
        Http.json(ex, 200, v);
    }

    private static String text(JsonObject o, String f) {
        return AddonRoutes.string(o, f);
    }

    private static void fail(HttpExchange ex, PearlEngine.Refused e) throws IOException {
        Http.error(ex, e.status, e.getMessage());
    }

    private void station(HttpExchange ex) throws IOException {
        Write w = begin(ex, false);
        if (w == null) return;
        JsonObject b = w.body();
        String id = null;
        if (b.has("id") && !b.get("id").isJsonNull()) {
            id = text(b, "id");
            if (id == null || !PearlBodies.STATION.matcher(id).matches()) {
                Http.error(ex, 400, "id looks like ps-3f2a9c1d");
                return;
            }
        }
        String name;
        try {
            name = KitTemplate.checkName(text(b, "name"), 32, "name");
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        String server = text(b, "server");
        if (server == null || server.isEmpty() || server.length() > KitReports.MAX_FIELD || !KitNames.wellFormed(server)) {
            Http.error(ex, 400, "server must be the install's server name");
            return;
        }
        String dim = text(b, "dim");
        if (dim == null || !PearlBodies.DIM.matcher(dim).matches()) {
            Http.error(ex, 400, "dim must be a dimension like minecraft:the_nether");
            return;
        }
        String loader = null;
        if (b.get("loader") instanceof JsonObject lo) loader = AddonRoutes.string(lo, "player");
        if (loader == null || !PearlBodies.PLAYER.matcher(loader).matches()) {
            Http.error(ex, 400, "loader.player must be a player name");
            return;
        }
        String stash = null;
        if (b.has("stash") && !b.get("stash").isJsonNull()) {
            stash = text(b, "stash");
            if (stash == null || stash.isEmpty() || stash.length() > KitReports.MAX_FIELD || !KitNames.wellFormed(stash)) {
                Http.error(ex, 400, "stash must be text or null");
                return;
            }
        }
        try {
            PearlStore.Station st = engine.saveStation(id, name, server, dim, loader, stash);
            audit.add(w.who(), "pearl.station", st.id + " (" + (id == null ? "new" : "edit") + ")", AuthRoutes.address(ex));
            respond(ex, w.who(), idOnly(st.id));
        } catch (PearlEngine.Refused e) {
            fail(ex, e);
        }
    }

    private static JsonObject idOnly(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        return o;
    }

    private void deleteStation(HttpExchange ex) throws IOException {
        Write w = begin(ex, false);
        if (w == null) return;
        String id = text(w.body(), "id");
        if (id == null || !PearlBodies.STATION.matcher(id).matches()) {
            Http.error(ex, 400, "id looks like ps-3f2a9c1d");
            return;
        }
        try {
            engine.deleteStation(id);
            audit.add(w.who(), "pearl.station.delete", id, AuthRoutes.address(ex));
            respond(ex, w.who(), null);
        } catch (PearlEngine.Refused e) {
            fail(ex, e);
        }
    }

    private void assign(HttpExchange ex) throws IOException {
        Write w = begin(ex, false);
        if (w == null) return;
        String station = text(w.body(), "station"), player = text(w.body(), "player");
        if (station == null || !PearlBodies.STATION.matcher(station).matches() || player == null || !PearlBodies.PLAYER.matcher(player).matches()) {
            Http.error(ex, 400, "station and player are required");
            return;
        }
        String chamber = null;
        if (w.body().has("chamber") && !w.body().get("chamber").isJsonNull()) {
            chamber = text(w.body(), "chamber");
            if (chamber == null || !PearlBodies.PEARL_ID.matcher(chamber).matches()) {
                Http.error(ex, 400, "chamber must be a pearl id or null");
                return;
            }
        }
        try {
            engine.assign(station, player, chamber);
            audit.add(w.who(), "pearl.assign", station + ": " + player.toLowerCase(Locale.ROOT) + " -> " + (chamber == null ? "(none)" : chamber), AuthRoutes.address(ex));
            respond(ex, w.who(), null);
        } catch (PearlEngine.Refused e) {
            fail(ex, e);
        }
    }

    private void resetPin(HttpExchange ex) throws IOException {
        Write w = begin(ex, false);
        if (w == null) return;
        String player = text(w.body(), "player"), uuid = text(w.body(), "uuid");
        if (player == null || !PearlBodies.PLAYER.matcher(player).matches() || uuid == null || !PearlBodies.UUID.matcher(uuid).matches()) {
            Http.error(ex, 400, "player and uuid are required");
            return;
        }
        try {
            engine.setPin(player, uuid);
            audit.add(w.who(), "pearl.pin.reset", player.toLowerCase(Locale.ROOT) + " -> " + uuid.toLowerCase(Locale.ROOT), AuthRoutes.address(ex));
            respond(ex, w.who(), null);
        } catch (PearlEngine.Refused e) {
            fail(ex, e);
        }
    }

    private void approve(HttpExchange ex) throws IOException {
        Write w = begin(ex, false);
        if (w == null) return;
        String id = text(w.body(), "id"), source = text(w.body(), "source");
        if (id == null || !PearlBodies.STATION.matcher(id).matches() || source == null || source.isEmpty() || source.length() > 300) {
            Http.error(ex, 400, "id and source are required");
            return;
        }
        try {
            engine.approveLoader(id, source);
            audit.add(w.who(), "pearl.loader.approve", id + " <- " + source, AuthRoutes.address(ex));
            respond(ex, w.who(), null);
        } catch (PearlEngine.Refused e) {
            fail(ex, e);
        }
    }

    private void dismiss(HttpExchange ex) throws IOException {
        Write w = begin(ex, false);
        if (w == null) return;
        String id = text(w.body(), "id"), source = text(w.body(), "source");
        if (id == null || !PearlBodies.STATION.matcher(id).matches() || source == null || source.isEmpty() || source.length() > 300) {
            Http.error(ex, 400, "id and source are required");
            return;
        }
        try {
            engine.dismissSource(id, source);
            audit.add(w.who(), "pearl.loader.dismiss", id + " x " + source, AuthRoutes.address(ex));
            respond(ex, w.who(), null);
        } catch (PearlEngine.Refused e) {
            fail(ex, e);
        }
    }

    private void allowed(HttpExchange ex) throws IOException {
        Write w = begin(ex, false);
        if (w == null) return;
        String station = text(w.body(), "station"), player = text(w.body(), "player");
        if (station == null || !PearlBodies.STATION.matcher(station).matches() || player == null || !PearlBodies.PLAYER.matcher(player).matches()
            || !(w.body().get("allowed") instanceof JsonPrimitive p && p.isBoolean())) {
            Http.error(ex, 400, "station, player and allowed (true or false) are required");
            return;
        }
        boolean on = w.body().get("allowed").getAsBoolean();
        try {
            engine.allowTraveller(station, player, on);
            audit.add(w.who(), "pearl.allowed", station + ": " + player.toLowerCase(Locale.ROOT) + " -> " + on, AuthRoutes.address(ex));
            respond(ex, w.who(), null);
        } catch (PearlEngine.Refused e) {
            fail(ex, e);
        }
    }

    private void resetChamber(HttpExchange ex) throws IOException {
        Write w = begin(ex, false);
        if (w == null) return;
        String station = text(w.body(), "station"), chamber = text(w.body(), "chamber");
        if (station == null || !PearlBodies.STATION.matcher(station).matches() || chamber == null || !PearlBodies.PEARL_ID.matcher(chamber).matches()) {
            Http.error(ex, 400, "station and chamber are required");
            return;
        }
        try {
            engine.resetChamber(station, chamber);
            audit.add(w.who(), "pearl.chamber.reset", station + "/" + chamber, AuthRoutes.address(ex));
            respond(ex, w.who(), null);
        } catch (PearlEngine.Refused e) {
            fail(ex, e);
        }
    }

    private void startTrip(HttpExchange ex) throws IOException {
        Write w = begin(ex, true);
        if (w == null) return;
        JsonObject b = w.body();
        String server = text(b, "server"), stash = text(b, "stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty() || server.length() > KitReports.MAX_FIELD || stash.length() > KitReports.MAX_FIELD
            || !KitNames.wellFormed(server) || !KitNames.wellFormed(stash)) {
            Http.error(ex, 400, "server and stash are required");
            return;
        }
        String action = text(b, "action");
        if (!"go".equals(action) && !"back".equals(action)) {
            Http.error(ex, 400, "action must be go or back");
            return;
        }
        String station = null;
        if (b.has("station") && !b.get("station").isJsonNull()) {
            station = text(b, "station");
            if (station == null || !PearlBodies.STATION.matcher(station).matches()) {
                Http.error(ex, 400, "station looks like ps-3f2a9c1d");
                return;
            }
        }
        if (b.has("oneWay") && !b.get("oneWay").isJsonNull() && !(b.get("oneWay") instanceof JsonPrimitive p && p.isBoolean())) {
            Http.error(ex, 400, "oneWay must be true or false");
            return;
        }
        boolean oneWay = b.get("oneWay") instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
        server = OrderRoutes.scoped(ex, server, stash); // 404 outside the user's scope
        if (server == null) return;
        if (!index.has(server, stash)) {
            Http.error(ex, 404, "no such stash");
            return;
        }
        try {
            PearlStore.Trip t = engine.createTrip(server, stash, action, station, oneWay, "website", w.who().id(), w.who().name(), null, clock.getAsLong());
            audit.add(w.who(), "pearl.trip", t.id + ": " + action + " " + stash, AuthRoutes.address(ex));
            respond(ex, w.who(), idOnly(t.id));
        } catch (PearlEngine.Refused e) {
            fail(ex, e);
        }
    }

    private void stopTrip(HttpExchange ex) throws IOException {
        Write w = begin(ex, true);
        if (w == null) return;
        String id = text(w.body(), "id");
        if (id == null || !PearlBodies.TRIP.matcher(id).matches()) {
            Http.error(ex, 400, "id looks like t-3f2a9c1d0b7e");
            return;
        }
        String where = engine.tripStash(id);
        if (where == null) {
            Http.error(ex, 404, "no such trip");
            return;
        }
        String[] sv = where.split("\n", 2);
        if (!w.who().owner() && !w.who().perms().seesStash(sv[0], sv[1])) {
            Http.error(ex, 404, "no such trip");
            return;
        }
        try {
            engine.stopTrip(id);
            audit.add(w.who(), "pearl.stop", id, AuthRoutes.address(ex));
            respond(ex, w.who(), null);
        } catch (PearlEngine.Refused e) {
            fail(ex, e);
        }
    }

    private void clearAway(HttpExchange ex) throws IOException {
        Write w = begin(ex, false);
        if (w == null) return;
        String stash = text(w.body(), "stash");
        String server = text(w.body(), "server");
        if (stash == null || stash.isEmpty() || stash.length() > KitReports.MAX_FIELD || !KitNames.wellFormed(stash)) {
            Http.error(ex, 400, "stash is required");
            return;
        }
        // The owner may name the server (several stashes can share a name); without it every server's stash of that name.
        for (IndexStore.StashKey k : index.players().keySet()) {
            if (k.stash().equals(stash) && (server == null || k.server().equals(server))) engine.clearAway(k.server(), stash);
        }
        audit.add(w.who(), "pearl.away.clear", stash, AuthRoutes.address(ex));
        respond(ex, w.who(), null);
    }
}

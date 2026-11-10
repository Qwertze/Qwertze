package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Crafting from the stash (CRAFT-CONTRACT.md):
 * <ul>
 *   <li>GET /api/v1/craft and POST /api/v1/craft/table, /never-use: the website's crafting settings (positions, so a user in
 *       forced streamer mode gets 403 on all three);</li>
 *   <li>POST /api/v1/craft/plan: the Printer's quote, nothing is reserved (no positions, so streamers may use it);</li>
 *   <li>GET /api/v1/bot/recipes?mc=: the version's recipe table for the bots (signed, ETag, rate limited).</li>
 * </ul>
 */
final class CraftRoutes {
    static final int MAX_POST = 4096;
    static final int MAX_LIST_POST = 16 * 1024;
    static final int MAX_PLAN_POST = 16 * 1024;
    static final int MAX_WANT = 200;
    static final int WRITES_PER_MINUTE = 60;
    static final int PLANS_PER_MINUTE = 30;
    static final int RECIPES_PER_BOT_PER_MINUTE = 6;
    static final int RECIPES_PER_ADDRESS_PER_MINUTE = 60;
    static final long RECIPES_RETRY_SECONDS = 20;
    private static final long WINDOW_MS = 60_000;

    private final IndexStore index;
    private final CraftService craft;
    private final OrderStore orders;
    private final AuditLog audit;
    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Long>> windows = new HashMap<>();

    CraftRoutes(CraftService craft, OrderStore orders, AuditLog audit, IndexStore index) {
        this(craft, orders, audit, index, System::currentTimeMillis);
    }

    CraftRoutes(CraftService craft, OrderStore orders, AuditLog audit, IndexStore index, LongSupplier clock) {
        this.craft = craft;
        this.orders = orders;
        this.audit = audit;
        this.index = index;
        this.clock = clock;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/craft", Router.Guard.SESSION, Router.Perm.VIEW, this::get)
            .exact(Router.POST, "/api/v1/craft/table", Router.Guard.BROWSER_WRITE, Router.Perm.SETTINGS, this::table)
            .exact(Router.POST, "/api/v1/craft/never-use", Router.Guard.BROWSER_WRITE, Router.Perm.SETTINGS, this::neverUse)
            .exact(Router.POST, "/api/v1/craft/plan", Router.Guard.BROWSER_WRITE, Router.Perm.CRAFT_QUOTE, this::plan)
            .exact(Router.GET, "/api/v1/bot/recipes", Router.Guard.ADDON_KEY, this::recipes);
    }

    // ---------- helpers ----------

    /** A forced streamer user is refused (403 streamer). True when the request may go on. */
    private static boolean notForced(HttpExchange ex) throws IOException {
        if (!Redact.on(ex)) return true;
        Router.forbid(ex, "use crafting settings in streamer mode (enforced by the owner)", "streamer");
        return false;
    }

    private static final String UNREADABLE = "craft settings can't be read (the server's craft.json is damaged)";

    /** 0 when the call may go on (and is counted), else the milliseconds until it may. */
    private synchronized long window(String key, int limit) {
        long now = clock.getAsLong();
        ArrayDeque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) q.pollFirst();
        if (q.size() >= limit) return WINDOW_MS - (now - q.peekFirst());
        q.addLast(now);
        if (windows.size() > 1024) windows.values().removeIf(d -> d.isEmpty() || clock.getAsLong() - d.peekLast() >= WINDOW_MS);
        return 0;
    }

    private static void tooMany(HttpExchange ex, long waitMs, String message) throws IOException {
        long seconds = Math.max(1, (waitMs + 999) / 1000);
        ex.getResponseHeaders().set("Retry-After", Long.toString(seconds));
        JsonObject e = new JsonObject();
        e.addProperty("ok", false);
        e.addProperty("error", message);
        e.addProperty("retryAfter", seconds);
        Http.json(ex, 429, e);
    }

    private record Req(JsonObject body, String server, String stash) {
    }

    /**
     * The shared start of the POSTs: view permission, body (limited), server and stash, scope, and that the server knows the
     * stash. Null after answering.
     */
    private Req begin(HttpExchange ex, int maxBody, boolean knownOnly) throws IOException {
        Users.Principal who = Router.user(ex);
        if (!who.perms().view() && knownOnly) {
            Router.forbid(ex, "view stashes", "view");
            return null;
        }
        JsonElement json = AddonRoutes.readJson(ex, maxBody); // 413 above the limit
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return null;
        }
        JsonObject o = json.getAsJsonObject();
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
        if (!index.has(server, stash) && !craft.store().knows(server, stash)) {
            Http.error(ex, 404, "no such stash");
            return null;
        }
        return new Req(o, server, stash);
    }

    // ---------- GET /api/v1/craft ----------

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
        if (!index.has(server, stash) && !craft.store().knows(server, stash)) {
            Http.error(ex, 404, "no such stash");
            return;
        }
        respond(ex, server, stash);
    }

    private void respond(HttpExchange ex, String server, String stash) throws IOException {
        CraftService.Status st = craft.status(server, stash);
        if (st.settings() == null) {
            Http.error(ex, 503, UNREADABLE);
            return;
        }
        CraftStore.Settings s = st.settings();
        JsonObject o = new JsonObject();
        o.addProperty("server", server);
        o.addProperty("stash", stash);
        o.addProperty("rev", s.rev());
        o.addProperty("hash", s.hash());
        o.add("table", s.table() == null ? com.google.gson.JsonNull.INSTANCE : s.table().toJson());
        o.addProperty("tableStatus", st.tableStatus());
        o.addProperty("tableWhy", st.supports() ? st.beat().cap().tableWhy() : null);
        // The table the bot found, placed or crafted itself (a position: this route is closed to forced-streamer users).
        CraftBots.BotTable bt = st.supports() ? st.beat().cap().botTable() : null;
        if (bt == null) {
            o.add("botTable", com.google.gson.JsonNull.INSTANCE);
        } else {
            JsonObject b = new JsonObject();
            b.addProperty("pos", bt.pos());
            b.addProperty("source", bt.source());
            o.add("botTable", b);
        }
        JsonArray never = new JsonArray();
        s.neverUse().forEach(never::add);
        o.add("neverUse", never);
        JsonObject bot = new JsonObject();
        bot.addProperty("supports", st.supports());
        bot.addProperty("enabled", st.enabled());
        bot.addProperty("online", st.online());
        bot.addProperty("synced", st.synced());
        bot.addProperty("recipesMatch", st.recipesMatch());
        bot.addProperty("maxFills", st.supports() ? st.maxFills() : null);
        o.add("bot", bot);
        JsonObject rec = new JsonObject();
        rec.addProperty("mc", st.mc());
        rec.addProperty("state", st.recipesState());
        o.add("recipes", rec);
        o.add("seen", CraftBots.strings(st.supports() ? st.beat().cap().seen() : List.of()));
        Http.json(ex, 200, o);
    }

    // ---------- POST table / never-use ----------

    private static Integer whole(JsonElement e) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber() || p.getAsString().length() > 20) return null;
        try {
            return p.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    /** A setting write: streamer, permission, body, scope, rate limit, store state. Null after answering. */
    private Req write(HttpExchange ex, int maxBody) throws IOException {
        if (!notForced(ex)) return null;
        Req r = begin(ex, maxBody, true);
        if (r == null) return null;
        long wait = window("w|" + Router.user(ex).id(), WRITES_PER_MINUTE);
        if (wait > 0) {
            tooMany(ex, wait, "too many crafting changes, wait a moment");
            return null;
        }
        if (craft.store().disabled()) {
            Http.error(ex, 503, UNREADABLE);
            return null;
        }
        return r;
    }

    private void table(HttpExchange ex) throws IOException {
        Req r = write(ex, MAX_POST);
        if (r == null) return;
        if (!r.body().has("table")) {
            Http.error(ex, 400, "table is required: {x, y, z, dimension}, or null to clear it");
            return;
        }
        CraftStore.Table t = null;
        JsonElement te = r.body().get("table");
        if (!te.isJsonNull()) {
            if (!te.isJsonObject()) {
                Http.error(ex, 400, "table must be {x, y, z, dimension} or null");
                return;
            }
            JsonObject to = te.getAsJsonObject();
            Integer x = whole(to.get("x")), y = whole(to.get("y")), z = whole(to.get("z"));
            String dim = AddonRoutes.string(to, "dimension");
            if (x == null || y == null || z == null) {
                Http.error(ex, 400, "table.x, y and z must be whole numbers");
                return;
            }
            if (!CraftStore.Table.valid(x, y, z, dim)) {
                Http.error(ex, 400, "table is out of range: |x| and |z| up to 30000000, y from -64 to 320, dimension like minecraft:overworld");
                return;
            }
            t = new CraftStore.Table(x, y, z, dim);
            String why = craft.checkTable(r.server(), r.stash(), t);
            if (why != null) {
                Http.error(ex, 400, why);
                return;
            }
        }
        try {
            craft.store().setTable(r.server(), r.stash(), t);
        } catch (CraftStore.LimitException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        } catch (CraftStore.DisabledException e) {
            Http.error(ex, 503, UNREADABLE);
            return;
        }
        audit.add(Router.user(ex), "craft.table", r.stash() + ": " + (t == null ? "(none)" : t.x() + "," + t.y() + "," + t.z() + " " + t.dimension()),
            AuthRoutes.address(ex));
        respond(ex, r.server(), r.stash());
    }

    private void neverUse(HttpExchange ex) throws IOException {
        Req r = write(ex, MAX_LIST_POST);
        if (r == null) return;
        JsonElement ids = r.body().get("ids");
        if (ids == null || !ids.isJsonArray()) {
            Http.error(ex, 400, "ids must be a list of item ids");
            return;
        }
        List<String> list = new ArrayList<>();
        for (JsonElement e : ids.getAsJsonArray()) {
            if (!(e instanceof JsonPrimitive p) || !p.isString() || !CraftStore.ITEM_ID.matcher(p.getAsString()).matches()) {
                Http.error(ex, 400, "ids must be plain item ids like minecraft:stone");
                return;
            }
            list.add(p.getAsString());
        }
        try {
            craft.store().setNeverUse(r.server(), r.stash(), list);
        } catch (CraftStore.LimitException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        } catch (CraftStore.DisabledException e) {
            Http.error(ex, 503, UNREADABLE);
            return;
        }
        audit.add(Router.user(ex), "craft.neverUse", r.stash() + ": " + new java.util.TreeSet<>(list).size() + " item(s)", AuthRoutes.address(ex));
        respond(ex, r.server(), r.stash());
    }

    // ---------- POST /api/v1/craft/plan ----------

    /** {id, count} entries of a request, merged per id in first-seen order. Throws IllegalArgumentException for a bad one. */
    private static Map<String, Long> counts(JsonElement e, String name, boolean required) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (e == null || e.isJsonNull()) {
            if (required) throw new IllegalArgumentException(name + " must be a list of {id, count}");
            return out;
        }
        if (!e.isJsonArray()) throw new IllegalArgumentException(name + " must be a list of {id, count}");
        if (e.getAsJsonArray().size() > MAX_WANT) throw new IllegalArgumentException("at most " + MAX_WANT + " entries in " + name);
        for (JsonElement x : e.getAsJsonArray()) {
            if (!x.isJsonObject()) throw new IllegalArgumentException(name + " entries must be {id, count}");
            JsonObject o = x.getAsJsonObject();
            String id = AddonRoutes.string(o, "id");
            long count = OrderRoutes.positiveInt(o.get("count"));
            if (id == null || !CraftStore.ITEM_ID.matcher(id).matches()) throw new IllegalArgumentException(name + " ids must be item ids like minecraft:stick");
            if (count < 1) throw new IllegalArgumentException(name + " counts must be whole numbers from 1 to " + Integer.MAX_VALUE);
            out.merge(id, count, (a, b) -> Math.min(a + b, Integer.MAX_VALUE));
        }
        return out;
    }

    /**
     * The Printer's quote: what the stash could craft of the wanted items, as a craft order would be planned right now, with
     * nothing reserved. direct (optional) is what the same order takes as itself (its item lines), so the quote and the order
     * see the same pool.
     */
    private void plan(HttpExchange ex) throws IOException {
        Req r = begin(ex, MAX_PLAN_POST, false);
        if (r == null) return;
        long wait = window("p|" + Router.user(ex).id(), PLANS_PER_MINUTE);
        if (wait > 0) {
            tooMany(ex, wait, "too many crafting quotes, wait a moment");
            return;
        }
        Map<String, Long> want;
        Map<String, Long> direct;
        try {
            want = counts(r.body().get("want"), "want", true);
            direct = counts(r.body().get("direct"), "direct", false);
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        IndexStore.OrderCounts counts = index.orderCounts(r.server(), r.stash());
        if (counts == null) {
            Http.error(ex, 400, "no index for that stash");
            return;
        }
        CraftService.Status st = craft.status(r.server(), r.stash());
        JsonObject out = Http.ok();
        JsonObject c = new JsonObject();
        c.addProperty("canOrder", st.canOrder());
        c.addProperty("reason", st.reason());
        c.addProperty("message", st.message());
        out.add("craft", c);
        JsonArray lines = new JsonArray();
        CraftPlanner.Plan plan = CraftPlanner.Plan.EMPTY;
        if (st.recipes() != null) try (CraftService.Permit permit = craft.acquire()) {
            CraftPlanner.Eligible e = craft.eligible(st.recipes(), st.settings() == null ? java.util.Set.of() : st.settings().neverUse());
            List<CraftPlanner.Row> rows = new ArrayList<>();
            want.forEach((id, n) -> rows.add(new CraftPlanner.Row(id, n, false)));
            Map<String, Long> dir = new HashMap<>();
            direct.forEach((id, n) -> {
                String real = counts.itemIds().get(id);
                if (real != null && real.equals(id)) dir.put(id, n);
            });
            plan = craft.planner(e).plan(CraftService.stock(counts, orders.reservedFor(r.server(), r.stash())), dir, rows);
        }
        int i = 0;
        for (Map.Entry<String, Long> w : want.entrySet()) {
            JsonObject lo = new JsonObject();
            lo.addProperty("id", w.getKey());
            lo.addProperty("want", w.getValue());
            if (i < plan.rows().size()) {
                CraftPlanner.RowResult rr = plan.rows().get(i);
                lo.addProperty("craftable", rr.craftable());
                lo.addProperty("reason", rr.reason());
                lo.add("uses", CraftService.countsJson(rr.uses()));
            } else {
                lo.addProperty("craftable", 0);
                lo.addProperty("reason", st.message());
                lo.add("uses", new JsonArray());
            }
            lines.add(lo);
            i++;
        }
        out.add("lines", lines);
        out.add("take", CraftService.countsJson(plan.take()));
        out.add("leftovers", CraftService.countsJson(plan.leftovers()));
        out.addProperty("fills", plan.fills());
        out.addProperty("truncated", plan.truncated());
        Http.json(ex, 200, out);
    }

    // ---------- GET /api/v1/bot/recipes?mc= ----------

    /**
     * The recipe table of a version for the bots: table.json with ETag "tableHash" (If-None-Match gets 304). 404 when the
     * server has no recipes for the version, 503 + Retry-After while they are being built, 429 above 6 a minute per bot
     * (the botId of the X-SM-Auth header; every address also has an overall limit).
     */
    private void recipes(HttpExchange ex) throws IOException {
        Map<String, String> q = Http.query(ex);
        String mc = q.get("mc");
        if (mc == null || !IconService.MC_VERSION.matcher(mc).matches()) {
            Http.error(ex, 400, "mc must be a Minecraft version like 1.21.4");
            return;
        }
        // The bot id in the X-SM-Auth header ("-" when the bot has none); it is not signed, so every address also has an overall limit.
        Signer.Auth auth = Signer.Auth.parse(ex.getRequestHeaders().getFirst(Signer.REQUEST_HEADER));
        String bot = auth == null || auth.botId().equals("-") ? null : auth.botId();
        String address = AuthRoutes.address(ex);
        long wait = window("ra|" + address, RECIPES_PER_ADDRESS_PER_MINUTE);
        if (wait <= 0) wait = window("r|" + address + "|" + (bot == null ? "" : bot), RECIPES_PER_BOT_PER_MINUTE);
        if (wait > 0) {
            tooMany(ex, wait, "too many recipe requests (at most " + RECIPES_PER_BOT_PER_MINUTE + " a minute); the table is cached by the bot");
            return;
        }
        RecipeSource src = craft.recipes();
        // Only for a version some stash uses: a bot can't make the server fetch jars for versions nobody runs.
        if (index.hasMcVersion(mc)) src.request(mc);
        RecipeTable t = src.table(mc);
        if (t == null) {
            if ("building".equals(src.state(mc))) {
                ex.getResponseHeaders().set("Retry-After", Long.toString(RECIPES_RETRY_SECONDS));
                Http.error(ex, 503, "the recipes for " + mc + " are being built; try again shortly");
            } else {
                Http.error(ex, 404, "this server has no recipes for " + mc);
            }
            return;
        }
        String etag = "\"" + t.tableHash() + "\"";
        ex.getResponseHeaders().set("ETag", etag);
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        if (inm != null && matches(inm, etag)) {
            Http.send(ex, 304, null, null, "no-cache");
            return;
        }
        Http.sendJson(ex, 200, t.jsonBytes(), null, "no-cache");
    }

    private static boolean matches(String header, String etag) {
        for (String part : header.split(",")) {
            String p = part.trim();
            if (p.equals("*") || p.equals(etag) || p.equals("W/" + etag)) return true;
        }
        return false;
    }
}

package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Repair passes (REPAIR-CONTRACT v2.0): POST /api/v1/repair/report (the bot, signed) and the website's GET /api/v1/repair and
 * POST /api/v1/repair/settings, /chest, /run and /stop. The browser routes show container positions and the bot's lists, so a
 * user in forced streamer mode gets 403 on all five.
 */
final class RepairRoutes {
    static final int MAX_POST = 4096;
    static final int SETTINGS_PER_MINUTE = 60;
    static final int RUNS_PER_MINUTE = 6;
    private static final long WINDOW_MS = 60_000;
    private static final String DAMAGED = "repair settings can't be read (the server's repair.json is damaged)";

    private final RepairStore store;
    private final RepairBots bots;
    private final KitReports kitReports;
    private final KitStore kits;
    private final OrderStore orders;
    private final IndexStore index;
    private final Owners owners;
    private final AuditLog audit;
    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Long>> writes = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> runs = new HashMap<>();

    RepairRoutes(RepairStore store, RepairBots bots, KitReports kitReports, KitStore kits, OrderStore orders, IndexStore index, Owners owners,
                 AuditLog audit) {
        this(store, bots, kitReports, kits, orders, index, owners, audit, System::currentTimeMillis);
    }

    RepairRoutes(RepairStore store, RepairBots bots, KitReports kitReports, KitStore kits, OrderStore orders, IndexStore index, Owners owners,
                 AuditLog audit, LongSupplier clock) {
        this.store = store;
        this.bots = bots;
        this.kitReports = kitReports;
        this.kits = kits;
        this.orders = orders;
        this.index = index;
        this.owners = owners;
        this.audit = audit;
        this.clock = clock;
    }

    void register(Router router) {
        router.exact(Router.POST, "/api/v1/repair/report", Router.Guard.ADDON_KEY, this::report)
            .exact(Router.GET, "/api/v1/repair", Router.Guard.SESSION, Router.Perm.VIEW, this::get)
            .exact(Router.POST, "/api/v1/repair/settings", Router.Guard.BROWSER_WRITE, Router.Perm.SETTINGS, this::settings)
            .exact(Router.POST, "/api/v1/repair/chest", Router.Guard.BROWSER_WRITE, Router.Perm.SETTINGS, this::chest)
            .exact(Router.POST, "/api/v1/repair/run", Router.Guard.BROWSER_WRITE, Router.Perm.SETTINGS, this::run)
            .exact(Router.POST, "/api/v1/repair/stop", Router.Guard.BROWSER_WRITE, Router.Perm.SETTINGS, this::stop);
    }

    // ---------- the bot ----------

    private void report(HttpExchange ex) throws IOException {
        JsonElement json = AddonRoutes.readJson(ex, RepairBots.MAX_BODY); // 413 above 512 KiB
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        String server = AddonRoutes.string(o, "server");
        String stash = AddonRoutes.string(o, "stash");
        if (server == null || server.isEmpty() || stash == null || stash.isEmpty() || server.length() > KitReports.MAX_FIELD
            || stash.length() > KitReports.MAX_FIELD || !KitNames.wellFormed(server) || !KitNames.wellFormed(stash)) {
            Http.error(ex, 400, "server and stash must be non-empty text");
            return;
        }
        String player = AddonRoutes.string(o, "player");
        if (player == null || player.isEmpty() || player.length() > KitReports.MAX_FIELD) {
            Http.error(ex, 400, "player must be non-empty text");
            return;
        }
        RepairBots.Report r;
        try {
            r = bots.parse(o);
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        if (!index.has(server, stash)) {
            Http.error(ex, 400, "unknown stash: send a heartbeat first");
            return;
        }
        String owner = owners.check(server, stash, player, true);
        if (owner != null) {
            AddonRoutes.conflict(ex, owner);
            return;
        }
        bots.put(server, stash, r);
        Http.json(ex, 200, Http.ok());
    }

    // ---------- the website ----------

    private static boolean notForced(HttpExchange ex) throws IOException {
        if (!Redact.on(ex)) return true;
        Router.forbid(ex, "use repair settings in streamer mode (enforced by the owner)", "streamer");
        return false;
    }

    private record Write(JsonObject body, String server, String stash) {
    }

    /** The shared start of the four writes: streamer, view, body, scope, rate limit (limiter: 0 settings, 1 run/stop), store state. */
    private Write begin(HttpExchange ex, int limiter) throws IOException {
        if (!notForced(ex)) return null;
        Users.Principal who = Router.user(ex);
        if (!who.perms().view()) {
            Router.forbid(ex, "view stashes", "view");
            return null;
        }
        JsonElement json = AddonRoutes.readJson(ex, MAX_POST); // 413 above 4 KiB
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
        if (server.length() > KitReports.MAX_FIELD || stash.length() > KitReports.MAX_FIELD || !KitNames.wellFormed(server) || !KitNames.wellFormed(stash)) {
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
        long wait = rateLimit(limiter == 0 ? writes : runs, limiter == 0 ? SETTINGS_PER_MINUTE : RUNS_PER_MINUTE, who.id());
        if (wait > 0) {
            long seconds = Math.max(1, (wait + 999) / 1000);
            ex.getResponseHeaders().set("Retry-After", Long.toString(seconds));
            JsonObject e = new JsonObject();
            e.addProperty("ok", false);
            e.addProperty("error", "too many repair changes, wait a moment");
            e.addProperty("retryAfter", seconds);
            Http.json(ex, 429, e);
            return null;
        }
        if (store.disabled()) {
            Http.error(ex, 503, DAMAGED);
            return null;
        }
        return new Write(o, server, stash);
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
        // Like the writes: only a stash the server knows.
        if (!index.has(server, stash) && !store.knows(server, stash)) {
            Http.error(ex, 404, "no such stash");
            return;
        }
        respond(ex, server, stash);
    }

    private void respond(HttpExchange ex, String server, String stash) throws IOException {
        RepairStore.Settings s;
        try {
            s = store.get(server, stash);
        } catch (RepairStore.DisabledException e) {
            Http.error(ex, 503, DAMAGED);
            return;
        }
        Http.json(ex, 200, view(server, stash, s));
    }

    private static Boolean flag(JsonObject o, String f) {
        return o.get(f) instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : null;
    }

    private static Integer whole(JsonObject o, String f) {
        JsonElement e = o.get(f);
        if (!(e instanceof JsonPrimitive p) || !p.isNumber() || p.getAsString().length() > 20) return null;
        try {
            return p.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    private void settings(HttpExchange ex) throws IOException {
        Write w = begin(ex, 0);
        if (w == null) return;
        Boolean enabled = flag(w.body(), "enabled"), auto = flag(w.body(), "auto");
        Integer threshold = whole(w.body(), "threshold"), reserve = whole(w.body(), "reserve");
        if (enabled == null || auto == null) {
            Http.error(ex, 400, "enabled and auto must be true or false");
            return;
        }
        if (threshold == null || threshold < 1 || threshold > 100) {
            Http.error(ex, 400, "threshold must be a whole number from 1 to 100");
            return;
        }
        if (reserve == null || reserve < 0 || reserve > RepairStore.MAX_RESERVE) {
            Http.error(ex, 400, "reserve must be a whole number from 0 to " + RepairStore.MAX_RESERVE);
            return;
        }
        Boolean shulkers = null; // optional: absent or null keeps it
        if (w.body().has("shulkers") && !w.body().get("shulkers").isJsonNull()) {
            shulkers = flag(w.body(), "shulkers");
            if (shulkers == null) {
                Http.error(ex, 400, "shulkers must be true or false");
                return;
            }
        }
        try {
            store.set(w.server(), w.stash(), enabled, auto, threshold, reserve, shulkers);
        } catch (RepairStore.LimitException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        } catch (RepairStore.DisabledException e) {
            Http.error(ex, 503, DAMAGED);
            return;
        }
        audit.add(Router.user(ex), "repair.settings", w.stash() + ": enabled " + enabled + ", auto " + auto + ", threshold " + threshold + ", reserve " + reserve
            + (shulkers == null ? "" : ", shulkers " + shulkers), AuthRoutes.address(ex));
        respond(ex, w.server(), w.stash());
    }

    /** The containers the bot last reported: the newer of its kit report and its repair report. Null when there is neither. */
    private List<RepairBots.Container> containers(String server, String stash) {
        KitReports.Report k = kitReports == null ? null : kitReports.get(server, stash);
        RepairBots.Report r = bots.report(server, stash);
        boolean useKit = k != null && (r == null || r.containers().isEmpty() || k.receivedAt() >= r.receivedAt());
        if (useKit) {
            return k.containers().stream().map(c -> new RepairBots.Container(c.id(), c.label(), c.size(), c.free(), c.pickable(), c.readAt())).toList();
        }
        return r == null || r.containers().isEmpty() ? null : r.containers();
    }

    /** The containers reserved for kits: the general kits chest, rule targets, type, category and template chests. */
    private Set<String> kitReserved(String server, String stash) {
        Set<String> out = new HashSet<>();
        if (kits == null) return out;
        try {
            KitStore.Settings s = kits.get(server, stash);
            if (s.kitsChest() != null) out.add(s.kitsChest());
            out.addAll(s.rules().values());
            out.addAll(s.types().reservedChests());
        } catch (KitStore.DisabledException e) {
            // no kit settings, nothing reserved
        }
        return out;
    }

    private void chest(HttpExchange ex) throws IOException {
        Write w = begin(ex, 0);
        if (w == null) return;
        String id = null;
        JsonElement ce = w.body().get("container");
        if (ce != null && !ce.isJsonNull()) {
            id = AddonRoutes.string(w.body(), "container");
            if (id == null || !KitNames.validId(id)) {
                Http.error(ex, 400, "container must be an id like \"120,64,-33\" or null");
                return;
            }
            List<RepairBots.Container> list = containers(w.server(), w.stash());
            if (list == null) {
                Http.error(ex, 409, "the bot hasn't reported its containers yet");
                return;
            }
            final String want = id;
            RepairBots.Container c = list.stream().filter(x -> x.id().equals(want)).findFirst().orElse(null);
            if (c == null || !c.pickable()) {
                Http.error(ex, 400, "that container can't take items");
                return;
            }
            if (kitReserved(w.server(), w.stash()).contains(id)) {
                Http.error(ex, 400, "that container is reserved for kits");
                return;
            }
        }
        try {
            store.setChest(w.server(), w.stash(), id);
        } catch (RepairStore.LimitException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        } catch (RepairStore.DisabledException e) {
            Http.error(ex, 503, DAMAGED);
            return;
        }
        audit.add(Router.user(ex), "repair.chest", w.stash() + ": " + (id == null ? "(none)" : id), AuthRoutes.address(ex));
        respond(ex, w.server(), w.stash());
    }

    private void run(HttpExchange ex) throws IOException {
        request(ex, 0, "repair.run");
    }

    private void stop(HttpExchange ex) throws IOException {
        request(ex, 1, "repair.stop");
    }

    private void request(HttpExchange ex, int kind, String action) throws IOException {
        Write w = begin(ex, 1);
        if (w == null) return;
        RepairBots.Beat beat = bots.beat(w.server(), w.stash());
        if (beat == null || !beat.supports()) {
            Http.error(ex, 409, "this bot can't run repair passes");
            return;
        }
        bots.request(w.server(), w.stash(), kind);
        audit.add(Router.user(ex), action, w.stash(), AuthRoutes.address(ex));
        respond(ex, w.server(), w.stash());
    }

    // ---------- the GET body ----------

    String chestStatus(String server, String stash, String chest, List<RepairBots.Container> list) {
        if (chest == null) return "unset";
        if (kitReserved(server, stash).contains(chest)) return "reserved";
        RepairBots.Container c = list == null ? null : list.stream().filter(x -> x.id().equals(chest)).findFirst().orElse(null);
        if (c == null || !c.pickable()) return "missing";
        return c.free() == 0 ? "full" : "ok";
    }

    JsonObject view(String server, String stash, RepairStore.Settings s) {
        JsonObject o = new JsonObject();
        o.addProperty("server", server);
        o.addProperty("stash", stash);
        o.addProperty("rev", s.rev());
        o.addProperty("hash", s.hash());
        JsonObject st = new JsonObject();
        st.addProperty("enabled", s.enabled());
        st.addProperty("auto", s.auto());
        st.addProperty("threshold", s.threshold());
        st.addProperty("reserve", s.reserve());
        st.addProperty("needsRepairChest", s.needsRepairChest());
        st.addProperty("shulkers", s.shulkers());
        o.add("settings", st);
        List<RepairBots.Container> list = containers(server, stash);
        o.addProperty("needsRepairChestStatus", chestStatus(server, stash, s.needsRepairChest(), list));
        RepairBots.Beat beat = bots.beat(server, stash);
        JsonObject bot = new JsonObject();
        boolean supports = beat != null && beat.supports();
        bot.addProperty("supports", supports);
        bot.addProperty("synced", supports && s.hash().equals(beat.hash()));
        bot.addProperty("online", beat != null && clock.getAsLong() - beat.at() < IndexStore.ONLINE_WINDOW_MS);
        JsonObject pass = beat == null ? null : beat.pass();
        bot.addProperty("state", pass == null ? null : pass.get("state").getAsString());
        bot.add("why", pass == null ? JsonNull.INSTANCE : pass.get("why"));
        bot.add("progress", pass == null ? JsonNull.INSTANCE : pass.get("progress"));
        bot.addProperty("trigger", pass == null || pass.get("trigger").isJsonNull() ? null : pass.get("trigger").getAsString());
        long runP = bots.pending(server, stash, 0), stopP = bots.pending(server, stash, 1);
        if (runP > 0) bot.addProperty("runPending", runP);
        else bot.add("runPending", JsonNull.INSTANCE);
        if (stopP > 0) bot.addProperty("stopPending", stopP);
        else bot.add("stopPending", JsonNull.INSTANCE);
        bot.add("candidates", pass == null ? JsonNull.INSTANCE : pass.get("candidates"));
        bot.add("bottles", pass == null ? JsonNull.INSTANCE : pass.get("bottles"));
        o.add("bot", bot);
        o.add("estimate", estimate(server, stash, s));
        RepairBots.Report r = bots.report(server, stash);
        if (r == null) {
            o.add("lastPass", JsonNull.INSTANCE);
            o.add("items", new JsonArray());
        } else {
            JsonObject lp = r.pass().deepCopy();
            lp.addProperty("receivedAt", r.receivedAt());
            o.add("lastPass", lp);
            o.add("items", r.items().deepCopy());
        }
        JsonArray cs = new JsonArray();
        if (list != null) {
            for (RepairBots.Container c : list) {
                JsonObject co = new JsonObject();
                co.addProperty("id", c.id());
                co.addProperty("label", c.label());
                co.addProperty("size", c.size());
                co.addProperty("free", c.free());
                co.addProperty("pickable", c.pickable());
                co.addProperty("full", c.free() == 0);
                cs.add(co);
            }
        }
        o.add("containers", cs);
        JsonObject limits = new JsonObject();
        limits.addProperty("thresholdMin", 1);
        limits.addProperty("thresholdMax", 100);
        limits.addProperty("reserveMax", RepairStore.MAX_RESERVE);
        o.add("limits", limits);
        return o;
    }

    /**
     * What the index says would be repaired at the stored threshold (display only; the bot's candidates are authoritative): Mending
     * items and their bottles, items without Mending, those inside shulker boxes (items x group count), and the bottles.
     */
    private JsonElement estimate(String server, String stash, RepairStore.Settings s) {
        IndexStore.IndexBytes ib = index.index(server, stash);
        if (ib == null) return JsonNull.INSTANCE;
        long openOrders = orders == null ? 0 : orders.reservedFor(server, stash).getOrDefault("item:" + Repair.BOTTLE_KEY, 0L);
        // Cached per stash: the same index (etag), threshold, reserve and reserved bottles give the same answer, so a poll costs nothing.
        String cacheKey = ib.etag() + "|" + s.threshold() + "|" + s.reserve() + "|" + openOrders;
        IndexStore.StashKey sk = new IndexStore.StashKey(server, stash);
        synchronized (estimates) {
            Object[] hit = estimates.get(sk);
            if (hit != null && cacheKey.equals(hit[0])) return ((JsonElement) hit[1]).deepCopy();
        }
        JsonElement out = computeEstimate(server, stash, ib, s, openOrders);
        synchronized (estimates) {
            estimates.remove(sk);
            estimates.put(sk, new Object[]{cacheKey, out});
            while (estimates.size() > 64) estimates.remove(estimates.keySet().iterator().next());
        }
        return out.deepCopy();
    }

    private final java.util.LinkedHashMap<IndexStore.StashKey, Object[]> estimates = new java.util.LinkedHashMap<>();

    /**
     * items[].count already includes what is inside shulker boxes (PROTOCOL.md, index): per key the contents x group count are
     * counted as in boxes, and the loose count is what is left.
     */
    private JsonElement computeEstimate(String server, String stash, IndexStore.IndexBytes ib, RepairStore.Settings s, long openOrders) {
        JsonObject idx;
        try {
            idx = JsonParser.parseString(new String(ib.bytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            return JsonNull.INSTANCE;
        }
        Map<String, JsonObject> byKey = new HashMap<>();
        Map<String, Long> inBox = new HashMap<>();
        JsonElement shulkers = idx.get("shulkers");
        if (shulkers != null && shulkers.isJsonArray()) {
            for (JsonElement e : shulkers.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject g = e.getAsJsonObject();
                long groups = count(g.get("count"));
                JsonElement contents = g.get("contents");
                if (contents == null || !contents.isJsonArray()) continue;
                for (JsonElement ce : contents.getAsJsonArray()) {
                    if (!ce.isJsonObject() || !(ce.getAsJsonObject().get("key") instanceof JsonPrimitive kp)) continue;
                    inBox.merge(kp.getAsString(), count(ce.getAsJsonObject().get("count")) * groups, Long::sum);
                }
            }
        }
        long mendItems = 0, mendBottles = 0, needsItems = 0, boxMend = 0, boxNeeds = 0, boxMendBottles = 0;
        JsonElement items = idx.get("items");
        if (items != null && items.isJsonArray()) {
            for (JsonElement e : items.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject it = e.getAsJsonObject();
                String key = it.get("key") instanceof JsonPrimitive kp ? kp.getAsString() : null;
                if (key == null) continue;
                byKey.put(key, it);
                int kind = kind(it, s.threshold());
                if (kind == 0) continue;
                long total = count(it.get("count"));
                long box = Math.min(total, inBox.getOrDefault(key, 0L));
                long loose = total - box;
                long per = kind == 1 ? Repair.bottlesFor(damage(it)) : 0;
                if (kind == 1) {
                    mendItems += loose;
                    mendBottles += loose * per;
                    boxMend += box;
                    boxMendBottles += box * per;
                } else {
                    needsItems += loose;
                    boxNeeds += box;
                }
            }
        }
        IndexStore.OrderCounts counts = index.orderCounts(server, stash);
        long bottles = counts == null ? 0 : Math.max(0, counts.total("item", Repair.BOTTLE_KEY));
        long stashBottles = Math.max(0, bottles - openOrders);
        JsonObject out = new JsonObject();
        JsonObject mend = new JsonObject();
        mend.addProperty("items", mendItems);
        mend.addProperty("bottles", mendBottles);
        mend.addProperty("inShulkers", boxMend);
        mend.addProperty("inShulkersBottles", boxMendBottles);
        out.add("mend", mend);
        JsonObject needs = new JsonObject();
        needs.addProperty("items", needsItems);
        needs.addProperty("inShulkers", boxNeeds);
        out.add("needsRepair", needs);
        JsonObject b = new JsonObject();
        b.addProperty("stash", bottles);
        b.addProperty("openOrders", openOrders);
        b.addProperty("reserve", s.reserve());
        b.addProperty("usable", Math.max(0, stashBottles - s.reserve()));
        out.add("bottles", b);
        return out;
    }

    private static long count(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isNumber() ? Math.max(0, Math.min(p.getAsLong(), 1_000_000_000L)) : 0;
    }

    private static long damage(JsonObject it) {
        return it.get("damage") instanceof JsonPrimitive p && p.isNumber() ? Math.max(0, Math.min(p.getAsLong(), 1_000_000_000L)) : 0;
    }

    /** 1: a low Mending item, 2: a low item without Mending, 0: anything else (single damageable items only, never shulker boxes). */
    private static int kind(JsonObject it, int threshold) {
        long max = it.get("maxDamage") instanceof JsonPrimitive p && p.isNumber() ? p.getAsLong() : 0;
        long maxStack = it.get("maxStack") instanceof JsonPrimitive p && p.isNumber() ? (long) Math.max(1, Math.floor(p.getAsDouble())) : 64;
        String id = it.get("id") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : "";
        if (maxStack != 1 || IndexStore.OrderCounts.isShulkerBox(id) || !Repair.low(max, damage(it), threshold)) return 0;
        return Repair.hasMending(it) ? 1 : 2;
    }

    // ---------- GET /api/v1/stashes ----------

    /** {supports, synced, enabled, state, why} (no positions), or null when the settings can't be read. */
    JsonElement summaryJson(String server, String stash) {
        RepairStore.Settings s;
        try {
            s = store.get(server, stash);
        } catch (RepairStore.DisabledException e) {
            return JsonNull.INSTANCE;
        }
        RepairBots.Beat beat = bots.beat(server, stash);
        boolean supports = beat != null && beat.supports();
        JsonObject o = new JsonObject();
        o.addProperty("supports", supports);
        o.addProperty("synced", supports && s.hash().equals(beat.hash()));
        o.addProperty("enabled", s.enabled());
        JsonObject pass = beat == null ? null : beat.pass();
        o.addProperty("state", pass == null ? null : pass.get("state").getAsString());
        o.add("why", pass == null ? JsonNull.INSTANCE : pass.get("why"));
        return o;
    }
}

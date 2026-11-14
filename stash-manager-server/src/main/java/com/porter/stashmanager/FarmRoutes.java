package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The website's side of farms and tasks (FARM-CONTRACT E4, TASK-CONTRACT "Browser routes"). Every route is owner only
 * ({@link Router.Perm#FARMS}, answered by {@code Router.forbid} before a handler runs) except {@code GET /api/v1/ops},
 * which any user with the Bots view may read: for a non-owner it carries the stop state without the reason, the names,
 * the roles or any task, and for a forced streamer only opaque bot ids.
 */
final class FarmRoutes {
    static final int MAX_POST = 4096;
    static final int WRITES_PER_MINUTE = 60;
    static final int STARTS_PER_MINUTE = 6;
    static final int STOPS_PER_MINUTE = 6;
    static final int REASON_MAX = 100;
    private static final long WINDOW_MS = 60_000;

    private final TaskService svc;
    private final FarmService farms;
    private final AuditLog audit;
    private final Map<String, ArrayDeque<Long>> writes = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> farmActions = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> opsActions = new HashMap<>();

    FarmRoutes(TaskService svc, FarmService farms, AuditLog audit) {
        this.svc = svc;
        this.farms = farms;
        this.audit = audit;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/farms", Router.Guard.SESSION, Router.Perm.FARMS, this::list)
            .exact(Router.POST, "/api/v1/farms", Router.Guard.BROWSER_WRITE, Router.Perm.FARMS, this::save)
            .exact(Router.POST, "/api/v1/farms/delete", Router.Guard.BROWSER_WRITE, Router.Perm.FARMS, this::delete)
            .exact(Router.POST, "/api/v1/farms/start", Router.Guard.BROWSER_WRITE, Router.Perm.FARMS, this::start)
            .exact(Router.POST, "/api/v1/farms/stop", Router.Guard.BROWSER_WRITE, Router.Perm.FARMS, this::stopFarm)
            .exact(Router.POST, "/api/v1/farms/attach", Router.Guard.BROWSER_WRITE, Router.Perm.FARMS, this::attach)
            .exact(Router.GET, "/api/v1/tasks", Router.Guard.SESSION, Router.Perm.FARMS, this::tasks)
            .exact(Router.GET, "/api/v1/ops", Router.Guard.SESSION, Router.Perm.ACTIVITY, this::ops)
            .exact(Router.POST, "/api/v1/ops/stop", Router.Guard.BROWSER_WRITE, Router.Perm.FARMS, this::opsStop)
            .exact(Router.POST, "/api/v1/ops/resume", Router.Guard.BROWSER_WRITE, Router.Perm.FARMS, this::opsResume)
            .exact(Router.POST, "/api/v1/bots/roles", Router.Guard.BROWSER_WRITE, Router.Perm.FARMS, this::roles);
    }

    // ---------- plumbing ----------

    private synchronized long rateLimit(Map<String, ArrayDeque<Long>> map, int max, String userId) {
        long now = svc.now();
        ArrayDeque<Long> q = map.computeIfAbsent(userId, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) q.pollFirst();
        if (q.size() >= max) return WINDOW_MS - (now - q.peekFirst());
        q.addLast(now);
        if (map.size() > 256) map.values().removeIf(d -> d.isEmpty() || now - d.peekLast() >= WINDOW_MS);
        return 0;
    }

    /** 429 with Retry-After when the user is over a limit; true after answering. */
    private boolean limited(HttpExchange ex, Map<String, ArrayDeque<Long>> extra, int extraMax) throws IOException {
        String who = Router.user(ex).id();
        long wait = rateLimit(writes, WRITES_PER_MINUTE, who);
        if (wait <= 0 && extra != null) wait = rateLimit(extra, extraMax, who);
        if (wait <= 0) return false;
        long seconds = Math.max(1, (wait + 999) / 1000);
        ex.getResponseHeaders().set("Retry-After", Long.toString(seconds));
        JsonObject e = new JsonObject();
        e.addProperty("ok", false);
        e.addProperty("error", "too many changes, wait a moment");
        e.addProperty("code", "slow_down");
        e.addProperty("retryAfter", seconds);
        Http.json(ex, 429, e);
        return true;
    }

    /** A write's start: rate limits, then the 4 KiB JSON body clean of coordinates. Null after answering. */
    private JsonObject begin(HttpExchange ex, Map<String, ArrayDeque<Long>> extra, int extraMax) throws IOException {
        if (limited(ex, extra, extraMax)) return null;
        return TaskRoutes.body(ex, MAX_POST);
    }

    private void run(HttpExchange ex, Supplier<JsonObject> work) throws IOException {
        JsonObject out;
        try {
            out = work.get();
        } catch (Fail f) {
            TaskRoutes.answer(ex, f);
            return;
        } catch (java.io.UncheckedIOException e) {
            TaskRoutes.answer(ex, new Fail(503, "storage_error"));
            return;
        }
        Http.json(ex, 200, out);
    }

    /**
     * The answer of a STOP, resume or role change. The change is applied in memory whatever happens to the disk; when the files
     * could not be written the answer is 503 with {@code applied: true, saved: false}: it holds until the server restarts or the
     * next write succeeds (the write is retried by every later call).
     */
    private static JsonObject appliedBody(JsonObject out, TaskService.Applied a) {
        out.addProperty("changed", a.changed());
        if (!a.saved()) {
            out.addProperty("ok", false);
            out.addProperty("code", "storage_error");
            out.addProperty("applied", true);
            out.addProperty("saved", false);
            out.addProperty("error", "applied, not saved: the change is in effect but could not be written to disk; it is retried and is lost if the server restarts first");
        }
        return out;
    }

    private void sendApplied(HttpExchange ex, JsonObject out, TaskService.Applied a) throws IOException {
        Http.json(ex, a.saved() ? 200 : 503, appliedBody(out, a));
    }

    private static JsonObject withOk(JsonObject body) {
        JsonObject o = Http.ok();
        body.entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
        return o;
    }

    private static String farmId(JsonObject o) {
        return Fail.string(o.get("id"), "id", FarmVocab.FARM_ID, 10);
    }

    // ---------- farms ----------

    private void list(HttpExchange ex) throws IOException {
        run(ex, farms::list);
    }

    private void save(HttpExchange ex) throws IOException {
        JsonObject o = begin(ex, null, 0);
        if (o == null) return;
        run(ex, () -> {
            FarmService.Saved s = farms.save(o);
            audit.add(Router.user(ex), "farm.save", s.farm().id + " " + (s.created() ? "new" : "edit") + " mode=" + s.farm().mode,
                AuthRoutes.address(ex));
            JsonObject out = withOk(farms.list());
            out.addProperty("id", s.farm().id);
            return out;
        });
    }

    private void delete(HttpExchange ex) throws IOException {
        JsonObject o = begin(ex, null, 0);
        if (o == null) return;
        run(ex, () -> {
            FarmStore.Farm f = farms.delete(farmId(o));
            audit.add(Router.user(ex), "farm.delete", f.id, AuthRoutes.address(ex));
            return withOk(farms.list());
        });
    }

    private void start(HttpExchange ex) throws IOException {
        JsonObject o = begin(ex, farmActions, STARTS_PER_MINUTE);
        if (o == null) return;
        run(ex, () -> {
            String id = farmId(o);
            Long maxMs = Fail.present(o, "maxMs") ? Fail.integer(o.get("maxMs"), "maxMs", 60_000, 43_200_000) : null;
            TaskStore.Task t = farms.start(id, maxMs, Router.user(ex));
            audit.add(Router.user(ex), "farm.start", id + " " + t.id, AuthRoutes.address(ex));
            JsonObject out = withOk(farms.list());
            out.addProperty("taskId", t.id);
            return out;
        });
    }

    private void stopFarm(HttpExchange ex) throws IOException {
        JsonObject o = begin(ex, farmActions, STOPS_PER_MINUTE);
        if (o == null) return;
        run(ex, () -> {
            String id = farmId(o);
            farms.stopSession(id);
            audit.add(Router.user(ex), "farm.stop", id, AuthRoutes.address(ex));
            return withOk(farms.list());
        });
    }

    private void attach(HttpExchange ex) throws IOException {
        JsonObject o = begin(ex, null, 0);
        if (o == null) return;
        run(ex, () -> {
            String id = farmId(o);
            farms.attach(id);
            audit.add(Router.user(ex), "farm.attach", id, AuthRoutes.address(ex));
            return withOk(farms.list());
        });
    }

    private void tasks(HttpExchange ex) throws IOException {
        Map<String, String> q = Http.query(ex);
        String bot = q.get("bot");
        if (bot != null) bot = Router.botParam(ex, bot);
        String state = q.get("state");
        String botFilter = bot == null && q.get("bot") != null ? "\u0000" : bot; // an unknown id matches nothing
        run(ex, () -> svc.tasksJson(q.get("kind"), botFilter, state));
    }

    // ---------- ops: STOP, resume, roles ----------

    private JsonObject opsBody(HttpExchange ex) {
        return svc.opsJson(Router.user(ex), null);
    }

    private void ops(HttpExchange ex) throws IOException {
        Users.Principal who = Router.user(ex);
        String raw = Http.query(ex).get("bot");
        String only = raw == null ? null : Router.botParam(ex, raw); // forced streamer mode: an opaque id only
        if (raw != null && (only == null || !who.perms().seesBot(only))) {
            Http.error(ex, 404, "no such bot");
            return;
        }
        run(ex, () -> svc.opsJson(who, only));
    }

    /** "all" or a bot id (resolved with Router.botParam, inside the user's bot scope). Null after answering. */
    private String scope(HttpExchange ex, JsonObject o) throws IOException {
        Users.Principal who = Router.user(ex);
        String raw;
        try {
            raw = Fail.string(o.get("scope"), "scope", null, 64);
        } catch (Fail f) {
            TaskRoutes.answer(ex, f);
            return null;
        }
        if (raw.equals("all")) {
            if (who.perms().bots() != null) {
                Router.forbid(ex, Router.Perm.FARMS.what, Router.Perm.FARMS.key);
                return null;
            }
            return "all";
        }
        String id = FarmVocab.BOT_ID.matcher(raw).matches() ? Router.botParam(ex, raw) : null;
        if (id == null || !who.perms().seesBot(id)) {
            TaskRoutes.answer(ex, new Fail(404, "unknown_bot"));
            return null;
        }
        return id;
    }

    private void opsStop(HttpExchange ex) throws IOException {
        JsonObject o = begin(ex, opsActions, STOPS_PER_MINUTE);
        if (o == null) return;
        String scope = scope(ex, o);
        if (scope == null) return;
        String reason = null;
        if (Fail.present(o, "reason")) {
            try {
                reason = Fail.string(o.get("reason"), "reason", null, REASON_MAX);
                if (reason.chars().anyMatch(Character::isISOControl)) throw Fail.badField("reason");
            } catch (Fail f) {
                TaskRoutes.answer(ex, f);
                return;
            }
            if (reason.isBlank()) reason = null;
        }
        String r = reason;
        try {
            TaskService.Applied a = svc.stop(scope, true, r, Router.user(ex));
            if (a.changed()) audit.add(Router.user(ex), "ops.stop", scope + (r == null ? "" : ": " + r), AuthRoutes.address(ex));
            sendApplied(ex, withOk(opsBody(ex)), a);
        } catch (Fail f) {
            TaskRoutes.answer(ex, f);
        }
    }

    private void opsResume(HttpExchange ex) throws IOException {
        JsonObject o = begin(ex, opsActions, STOPS_PER_MINUTE);
        if (o == null) return;
        String scope = scope(ex, o);
        if (scope == null) return;
        try {
            TaskService.Applied a = svc.stop(scope, false, null, Router.user(ex));
            if (a.changed()) audit.add(Router.user(ex), "ops.resume", scope, AuthRoutes.address(ex));
            sendApplied(ex, withOk(opsBody(ex)), a);
        } catch (Fail f) {
            TaskRoutes.answer(ex, f);
        }
    }

    private void roles(HttpExchange ex) throws IOException {
        JsonObject o = begin(ex, null, 0);
        if (o == null) return;
        Users.Principal who = Router.user(ex);
        String botId;
        List<String> roles = new ArrayList<>();
        try {
            String raw = Fail.string(o.get("botId"), "botId", FarmVocab.BOT_ID, 64);
            botId = Router.botParam(ex, raw);
            if (botId == null || !who.perms().seesBot(botId)) throw new Fail(404, "unknown_bot");
            JsonElement r = o.get("roles");
            if (r == null || !r.isJsonArray() || r.getAsJsonArray().size() > 2) throw Fail.badField("roles");
            for (JsonElement e : r.getAsJsonArray()) roles.add(Fail.string(e, "roles", null, 16));
        } catch (Fail f) {
            TaskRoutes.answer(ex, f);
            return;
        }
        String id = botId;
        try {
            TaskService.Applied a = svc.setRoles(id, roles);
            if (a.changed()) audit.add(who, "bot.roles", id + ": " + String.join(",", roles), AuthRoutes.address(ex));
            sendApplied(ex, withOk(opsBody(ex)), a);
        } catch (Fail f) {
            TaskRoutes.answer(ex, f);
        }
    }
}

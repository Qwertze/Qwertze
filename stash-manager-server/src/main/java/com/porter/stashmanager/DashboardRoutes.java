package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The dashboard's browser side (DASHBOARD-DESIGN.md, "Protocol / Browser"): the bot list, settings (read with an ETag,
 * changes checked against the stored schema and queued as jobs), job status, and the console. Reads need a session,
 * writes the browser-write guard.
 */
final class DashboardRoutes {
    private static final Pattern BOT = Pattern.compile("^/api/v1/bots/([A-Za-z0-9_-]{1,64})/(settings|jobs|console|activity)$");
    static final int MAX_COMMAND = 500;
    static final int MAX_STRING = 1000;
    static final int MAX_LIST = 500;
    static final int MAX_CHANGES = 100;

    private final BotHub hub;
    private final IndexStore store;
    private final Owners owners;
    private final SessionStore sessions;

    DashboardRoutes(BotHub hub, IndexStore store, Owners owners, SessionStore sessions) {
        this.sessions = sessions;
        this.hub = hub;
        this.store = store;
        this.owners = owners;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/bots", Router.Guard.SESSION, this::list)
            .pattern(Router.GET, BOT, Router.Guard.SESSION, this::get)
            .pattern(Router.POST, BOT, Router.Guard.BROWSER_WRITE, this::post);
    }

    // ---------- list ----------

    private void list(HttpExchange ex) throws IOException {
        long now = hub.now();
        JsonArray stashes = store.listJson();
        JsonArray out = new JsonArray();
        for (BotHub.Bot b : hub.all()) {
            JsonObject o = new JsonObject();
            synchronized (b) {
                o.addProperty("botId", b.botId);
                o.addProperty("player", b.player);
                o.addProperty("online", BotHub.online(b, now));
                o.addProperty("lastSeen", b.lastSeen);
                o.add("plugin", b.plugin.deepCopy());
                o.add("proxy", b.proxy.deepCopy());
                JsonArray caps = new JsonArray();
                b.caps.forEach(caps::add);
                o.add("caps", caps);
                o.addProperty("summary", Activity.summary(b.activity));
                o.addProperty("activityAt", b.activityAt > 0 ? b.activityAt : null);
                JsonArray scopes = new JsonArray();
                java.util.Set<String> all = new java.util.LinkedHashSet<>(b.declaredScopes);
                all.addAll(b.scopes.keySet());
                all.forEach(scopes::add);
                o.add("scopes", scopes);
            }
            // Its stashes: the ones this player owns or last used.
            JsonArray mine = new JsonArray();
            String player = o.has("player") && !o.get("player").isJsonNull() ? o.get("player").getAsString() : null;
            if (player != null) {
                for (JsonElement e : stashes) {
                    JsonObject s = e.getAsJsonObject();
                    String server = s.get("server").getAsString();
                    String stash = s.get("stash").getAsString();
                    String owner = owners.owner(server, stash);
                    String used = s.has("player") && !s.get("player").isJsonNull() ? s.get("player").getAsString() : null;
                    if (player.equalsIgnoreCase(owner != null ? owner : used == null ? "" : used)) {
                        JsonObject ref = new JsonObject();
                        ref.addProperty("server", server);
                        ref.addProperty("stash", stash);
                        mine.add(ref);
                    }
                }
            }
            o.add("stashes", mine);
            out.add(o);
        }
        Http.json(ex, 200, out);
    }

    // ---------- per bot ----------

    private boolean elevated(HttpExchange ex) {
        return sessions.elevated((String) Http.attr(ex, Router.SESSION_TOKEN));
    }

    private BotHub.Bot bot(HttpExchange ex, Matcher m) throws IOException {
        BotHub.Bot b = hub.bot(m.group(1));
        if (b == null) Http.error(ex, 404, "no such bot");
        return b;
    }

    private void get(HttpExchange ex) throws IOException {
        Matcher m = BOT.matcher(ex.getRequestURI().getRawPath());
        if (!m.matches()) {
            Http.error(ex, 404, "not found");
            return;
        }
        BotHub.Bot b = bot(ex, m);
        if (b == null) return;
        Map<String, String> q = Http.query(ex);
        switch (m.group(2)) {
            case "activity" -> {
                // Read-only status: a login is enough (no sudo).
                hub.watchActivity(b);
                Http.json(ex, 200, hub.activityJson(b));
            }
            case "settings" -> {
                String scope = q.getOrDefault("scope", "plugin");
                // The proxy's and other plugins' configs are shown in sudo mode only (like the console).
                if (!scope.equals("plugin") && !elevated(ex)) {
                    Http.error(ex, 403, "confirm password");
                    return;
                }
                getSettings(ex, b, scope);
            }
            case "jobs" -> {
                hub.watch(b);
                List<String> ids = new ArrayList<>();
                for (String id : q.getOrDefault("ids", "").split(",")) {
                    if (!id.isBlank() && ids.size() < 200) ids.add(id.trim());
                }
                Http.json(ex, 200, hub.jobs(b, ids));
            }
            default -> {
                if (!elevated(ex)) {
                    Http.error(ex, 403, "confirm password");
                    return;
                }
                hub.watch(b);
                long after;
                try {
                    after = Long.parseLong(q.getOrDefault("after", "0"));
                } catch (NumberFormatException e) {
                    after = 0;
                }
                JsonObject out = hub.console(b, after);
                out.addProperty("online", hub.online(b));
                Http.json(ex, 200, out);
            }
        }
    }

    private void getSettings(HttpExchange ex, BotHub.Bot b, String scope) throws IOException {
        hub.watch(b);
        BotHub.Scope s = hub.scope(b, scope);
        boolean online = hub.online(b);
        if (s == null || s.schema == null) {
            // Only "plugin" is pushed unasked: ask the bot for this scope now (answered within about a second).
            boolean asked = BotRoutes.SCOPE.matcher(scope).matches() && hub.requestScope(b, scope);
            JsonObject o = new JsonObject();
            o.addProperty("ok", false);
            o.addProperty("error", asked ? "asking the bot for these settings" : "no settings from this bot yet");
            o.addProperty("loading", asked && online);
            o.addProperty("online", online);
            Http.json(ex, 404, o);
            return;
        }
        JsonObject o = new JsonObject();
        String etag;
        synchronized (b) {
            etag = "\"" + s.schemaHash + "-" + s.valuesHash + "-" + (online ? 1 : 0) + "\"";
            String inm = ex.getRequestHeaders().getFirst("If-None-Match");
            if (etag.equals(inm == null ? null : inm.trim())) {
                ex.getResponseHeaders().set("ETag", etag);
                Http.send(ex, 304, null, null, "private, no-cache");
                return;
            }
            o.addProperty("scope", scope);
            o.addProperty("schemaHash", s.schemaHash);
            o.addProperty("valuesHash", s.valuesHash);
            o.add("schema", s.schema);
            o.add("values", s.values);
            o.addProperty("receivedAt", s.receivedAt);
            o.addProperty("online", online);
            o.addProperty("lastSeen", b.lastSeen);
        }
        ex.getResponseHeaders().set("ETag", etag);
        byte[] body = Json.GSON.toJson(o).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Http.sendJson(ex, 200, body, null, "private, no-cache");
    }

    private void post(HttpExchange ex) throws IOException {
        Matcher m = BOT.matcher(ex.getRequestURI().getRawPath());
        if (!m.matches() || m.group(2).equals("jobs") || m.group(2).equals("activity")) {
            Http.error(ex, 404, "not found");
            return;
        }
        BotHub.Bot b = bot(ex, m);
        if (b == null) return;
        JsonElement json = AddonRoutes.readJson(ex);
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        if (m.group(2).equals("settings")) postSettings(ex, b, o);
        else postConsole(ex, b, o);
    }

    private static void pathError(HttpExchange ex, int status, String message, String path) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        o.addProperty("path", path);
        Http.json(ex, status, o);
    }

    private boolean caps(BotHub.Bot b, String cap) {
        synchronized (b) {
            return b.caps.contains(cap);
        }
    }

    private void postSettings(HttpExchange ex, BotHub.Bot b, JsonObject o) throws IOException {
        if (!caps(b, "settings")) {
            Http.error(ex, 400, "this bot doesn't offer settings");
            return;
        }
        String scope = AddonRoutes.string(o, "scope");
        if (scope == null) scope = "plugin";
        if (!BotRoutes.SCOPE.matcher(scope).matches()) {
            Http.error(ex, 400, "scope must be plugin, proxy or plugin:<id>");
            return;
        }
        if (!scope.equals("plugin")) {
            // The proxy's own config and other plugins' configs: a proxy cap and "sudo mode" (password in the last 15 min).
            if (!caps(b, "proxy")) {
                Http.error(ex, 400, "this bot doesn't offer the proxy settings");
                return;
            }
            if (!sessions.elevated((String) Http.attr(ex, Router.SESSION_TOKEN))) {
                Http.error(ex, 403, "confirm password");
                return;
            }
        }
        BotHub.Scope s = hub.scope(b, scope);
        if (s == null || s.schema == null) {
            Http.error(ex, 409, "no settings from this bot yet");
            return;
        }
        if (!o.has("changes") || !o.get("changes").isJsonArray() || o.getAsJsonArray("changes").isEmpty()
            || o.getAsJsonArray("changes").size() > MAX_CHANGES) {
            Http.error(ex, 400, "changes must be a list of 1-" + MAX_CHANGES + " {path, value, expect}");
            return;
        }
        List<JsonObject> changes = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonElement e : o.getAsJsonArray("changes")) {
            if (!e.isJsonObject()) {
                Http.error(ex, 400, "changes must be objects");
                return;
            }
            JsonObject c = e.getAsJsonObject();
            String path = AddonRoutes.string(c, "path");
            if (path == null) {
                Http.error(ex, 400, "every change needs a path");
                return;
            }
            if (!seen.add(path)) {
                pathError(ex, 400, "the same setting twice", path);
                return;
            }
            JsonObject node;
            synchronized (b) {
                node = s.nodes == null ? null : s.nodes.get(path);
            }
            String problem = check(node, c.has("value") ? c.get("value") : JsonNull.INSTANCE);
            if (problem != null) {
                pathError(ex, 400, problem, path);
                return;
            }
            JsonObject clean = new JsonObject();
            clean.addProperty("path", path);
            clean.add("value", c.has("value") ? c.get("value") : JsonNull.INSTANCE);
            if (c.has("expect")) clean.add("expect", c.get("expect"));
            changes.add(clean);
        }
        if (!hub.online(b)) {
            Http.error(ex, 409, "bot offline");
            return;
        }
        List<String> ids = hub.queueSets(b, scope, changes);
        JsonObject out = Http.ok();
        JsonArray jobs = new JsonArray();
        for (int i = 0; i < ids.size(); i++) {
            JsonObject j = new JsonObject();
            j.addProperty("id", ids.get(i));
            j.addProperty("path", changes.get(i).get("path").getAsString());
            jobs.add(j);
        }
        out.add("jobs", jobs);
        Http.json(ex, 200, out);
    }

    /** Checks a new value against its schema node. Returns a problem or null. */
    static String check(JsonObject node, JsonElement value) {
        if (node == null) return "no such setting";
        String type = AddonRoutes.string(node, "type");
        if ("group".equals(type)) return "that is a group, not a setting";
        if (flag(node, "secret")) return "secrets can't be changed here";
        if (flag(node, "readOnly")) {
            String reason = AddonRoutes.string(node, "reason");
            return "read-only" + (reason == null ? "" : ": " + reason);
        }
        if (value == null || value.isJsonNull()) return flag(node, "nullable") ? null : "a value is required";
        switch (type == null ? "" : type) {
            case "bool" -> {
                return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? null : "must be true or false";
            }
            case "int", "long", "double" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return "must be a number";
                BigDecimal d;
                try {
                    d = value.getAsBigDecimal();
                } catch (NumberFormatException e) {
                    return "must be a number";
                }
                if (!type.equals("double") && d.stripTrailingZeros().scale() > 0) return "must be a whole number";
                BigDecimal lo = type.equals("int") ? BigDecimal.valueOf(Integer.MIN_VALUE) : type.equals("long") ? BigDecimal.valueOf(Long.MIN_VALUE) : null;
                BigDecimal hi = type.equals("int") ? BigDecimal.valueOf(Integer.MAX_VALUE) : type.equals("long") ? BigDecimal.valueOf(Long.MAX_VALUE) : null;
                if (node.has("min")) lo = node.get("min").getAsBigDecimal();
                if (node.has("max")) hi = node.get("max").getAsBigDecimal();
                if (type.equals("double") && !Double.isFinite(d.doubleValue())) return "must be a finite number";
                if (lo != null && d.compareTo(lo) < 0) return "must be at least " + lo.stripTrailingZeros().toPlainString();
                if (hi != null && d.compareTo(hi) > 0) return "must be at most " + hi.stripTrailingZeros().toPlainString();
                return null;
            }
            case "string" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return "must be text";
                return value.getAsString().length() > MAX_STRING ? "at most " + MAX_STRING + " characters" : null;
            }
            case "enum" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return "must be one of the choices";
                if (node.has("options")) {
                    for (JsonElement opt : node.getAsJsonArray("options")) if (opt.getAsString().equals(value.getAsString())) return null;
                }
                return "must be one of the choices";
            }
            case "stringList" -> {
                if (!value.isJsonArray()) return "must be a list of text";
                JsonArray list = value.getAsJsonArray();
                if (list.size() > MAX_LIST) return "at most " + MAX_LIST + " entries";
                for (JsonElement item : list) {
                    if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) return "must be a list of text";
                    if (item.getAsString().length() > MAX_STRING) return "entries can have at most " + MAX_STRING + " characters";
                }
                return null;
            }
            default -> {
                return "unknown setting type";
            }
        }
    }

    private static boolean flag(JsonObject node, String f) {
        JsonElement e = node.get(f);
        return e instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
    }

    private void postConsole(HttpExchange ex, BotHub.Bot b, JsonObject o) throws IOException {
        if (!caps(b, "console")) {
            Http.error(ex, 400, "this bot doesn't offer a console");
            return;
        }
        // Console commands can do anything the proxy can: the password must have been typed in the last 15 minutes.
        if (!sessions.elevated((String) Http.attr(ex, Router.SESSION_TOKEN))) {
            Http.error(ex, 403, "confirm password");
            return;
        }
        String command = AddonRoutes.string(o, "command");
        if (command == null || command.isBlank()) {
            Http.error(ex, 400, "command must be text");
            return;
        }
        command = command.trim();
        if (command.length() > MAX_COMMAND) {
            Http.error(ex, 400, "at most " + MAX_COMMAND + " characters");
            return;
        }
        if (command.chars().anyMatch(Character::isISOControl)) {
            Http.error(ex, 400, "the command has control characters");
            return;
        }
        if (!hub.online(b)) {
            Http.error(ex, 409, "bot offline");
            return;
        }
        JsonObject out = Http.ok();
        out.addProperty("id", hub.queueCommand(b, command));
        Http.json(ex, 200, out);
    }
}

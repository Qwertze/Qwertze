package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The dashboard's bot side (DASHBOARD-DESIGN.md, "Protocol / Bot"): bot/hello, bot/state, bot/next (long-poll) and
 * bot/results, all with the Bearer API key. Every field is checked for type and size.
 */
final class BotRoutes {
    static final Pattern BOT_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    /** plugin (our own settings), proxy (the AquariusProxy config) and plugin:<id> (another plugin's config). */
    static final Pattern SCOPE = Pattern.compile("^(plugin|proxy|plugin:[A-Za-z0-9_.-]{1,56})$");
    /** Setting paths that are treated as secrets whatever the bot says (DASHBOARD-DESIGN.md, "Secrets are never sent"). */
    static final Pattern SECRET_PATH = Pattern.compile(
        "(?i)token|passw|pass|pwd|secret|api_?key|key|auth|bearer|jwt|session|cookie|credential|cred|private|licen[sc]e|dsn|salt|signature|webhook|topic");
    /** A URL with user info ("scheme://user:pass@host"): the value itself is a secret. */
    static final Pattern URL_USERINFO = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://[^/@\\s]+@");
    static final Set<String> TYPES = Set.of("group", "bool", "int", "long", "double", "string", "enum", "stringList");
    private static final int MAX_NODES = 5000;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_PATH = 200;
    private static final int MAX_TEXT = 1000;
    private static final int MAX_VALUES = 5000;
    private static final int MAX_RESULTS = 50;
    private static final int MAX_CAPS = 16;

    private final BotHub hub;
    private java.util.function.Predicate<String> farmers = id -> false;

    /** Tests only: runs between the farmer check and the store of an activity post, to drive the flip race deterministically. */
    static volatile Runnable afterFarmerCheck;

    BotRoutes(BotHub hub) {
        this.hub = hub;
    }

    /** Which bot ids count as farmers (the owner's role or the sticky farmerSeen flag): their activity is scrubbed. */
    BotRoutes farmers(java.util.function.Predicate<String> farmers) {
        this.farmers = farmers;
        return this;
    }

    void register(Router router) {
        router.exact(Router.POST, "/api/v1/bot/hello", Router.Guard.ADDON_KEY, this::hello)
            .exact(Router.POST, "/api/v1/bot/state", Router.Guard.ADDON_KEY, this::state)
            .exact(Router.GET, "/api/v1/bot/next", Router.Guard.ADDON_KEY, this::next)
            .exact(Router.POST, "/api/v1/bot/results", Router.Guard.ADDON_KEY, this::results)
            .exact(Router.POST, "/api/v1/bot/activity", Router.Guard.ADDON_KEY, this::activity);
    }

    private static JsonObject body(HttpExchange ex) throws IOException {
        JsonElement json = AddonRoutes.readJson(ex);
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return null;
        }
        return json.getAsJsonObject();
    }

    /** The bot named in the body, or null after answering (400 for a bad id, 409 for a bot that hasn't said hello). */
    private BotHub.Bot known(HttpExchange ex, String botId) throws IOException {
        if (botId == null || !BOT_ID.matcher(botId).matches()) {
            Http.error(ex, 400, "botId must be 1-64 letters, digits, - or _");
            return null;
        }
        BotHub.Bot b = hub.bot(botId);
        if (b == null) {
            Http.error(ex, 409, "say hello first");
            return null;
        }
        return b;
    }

    private void hello(HttpExchange ex) throws IOException {
        JsonObject o = body(ex);
        if (o == null) return;
        String botId = AddonRoutes.string(o, "botId");
        if (botId == null || !BOT_ID.matcher(botId).matches()) {
            Http.error(ex, 400, "botId must be 1-64 letters, digits, - or _");
            return;
        }
        String player = AddonRoutes.string(o, "player");
        if (o.has("player") && !o.get("player").isJsonNull() && (player == null || player.length() > 64)) {
            Http.error(ex, 400, "player must be a string of at most 64 characters");
            return;
        }
        JsonObject plugin = smallObject(o, "plugin", "id", "version");
        JsonObject proxy = smallObject(o, "proxy", "version", "mc");
        if (plugin == null || proxy == null) {
            Http.error(ex, 400, "plugin must be {id, version} and proxy {version, mc} (strings)");
            return;
        }
        List<String> caps = new ArrayList<>();
        if (o.has("caps") && !o.get("caps").isJsonNull()) {
            if (!o.get("caps").isJsonArray() || o.getAsJsonArray("caps").size() > MAX_CAPS) {
                Http.error(ex, 400, "caps must be an array of strings");
                return;
            }
            for (JsonElement c : o.getAsJsonArray("caps")) {
                if (!c.isJsonPrimitive() || !c.getAsJsonPrimitive().isString() || c.getAsString().length() > 32) {
                    Http.error(ex, 400, "caps must be an array of strings");
                    return;
                }
                caps.add(c.getAsString());
            }
        }
        List<String> scopes = new ArrayList<>();
        if (o.has("scopes") && !o.get("scopes").isJsonNull()) {
            if (!o.get("scopes").isJsonArray() || o.getAsJsonArray("scopes").size() > 50) {
                Http.error(ex, 400, "scopes must be an array of at most 50 scope names");
                return;
            }
            for (JsonElement sc : o.getAsJsonArray("scopes")) {
                if (!sc.isJsonPrimitive() || !sc.getAsJsonPrimitive().isString() || !SCOPE.matcher(sc.getAsString()).matches()) {
                    Http.error(ex, 400, "scopes must be plugin, proxy or plugin:<id>");
                    return;
                }
                if (!scopes.contains(sc.getAsString())) scopes.add(sc.getAsString());
            }
        }
        if (!hub.hello(botId, player, plugin, proxy, caps, scopes)) {
            Http.error(ex, 503, "too many bots (limit " + BotHub.MAX_BOTS + ")");
            return;
        }
        JsonObject out = Http.ok();
        out.addProperty("protocol", 1);
        Http.json(ex, 200, out);
    }

    /** {a, b} with optional short string fields only; missing object = empty. Null when malformed. */
    private static JsonObject smallObject(JsonObject o, String field, String... keys) {
        JsonObject out = new JsonObject();
        if (!o.has(field) || o.get(field).isJsonNull()) return out;
        if (!o.get(field).isJsonObject()) return null;
        JsonObject in = o.getAsJsonObject(field);
        for (String k : keys) {
            if (!in.has(k) || in.get(k).isJsonNull()) continue;
            String v = AddonRoutes.string(in, k);
            if (v == null) return null;
            out.addProperty(k, Http.truncate(v, 64));
        }
        return out;
    }

    private void state(HttpExchange ex) throws IOException {
        JsonObject o = body(ex);
        if (o == null) return;
        BotHub.Bot b = known(ex, AddonRoutes.string(o, "botId"));
        if (b == null) return;
        String scope = AddonRoutes.string(o, "scope");
        String schemaHash = AddonRoutes.string(o, "schemaHash");
        String valuesHash = AddonRoutes.string(o, "valuesHash");
        if (scope == null || !SCOPE.matcher(scope).matches()) {
            Http.error(ex, 400, "scope must be plugin, proxy or plugin:<id>");
            return;
        }
        if (schemaHash == null || schemaHash.isEmpty() || schemaHash.length() > 128 || valuesHash == null || valuesHash.isEmpty() || valuesHash.length() > 128) {
            Http.error(ex, 400, "schemaHash and valuesHash must be short strings");
            return;
        }
        if (!o.has("values") || !o.get("values").isJsonObject() || o.getAsJsonObject("values").size() > MAX_VALUES) {
            Http.error(ex, 400, "values must be an object");
            return;
        }
        JsonObject values = o.getAsJsonObject("values");
        for (String k : values.keySet()) {
            if (k.isEmpty() || k.length() > MAX_PATH) {
                Http.error(ex, 400, "values keys must be setting paths");
                return;
            }
        }
        JsonArray schema = null;
        Map<String, JsonObject> nodes = null;
        if (o.has("schema") && !o.get("schema").isJsonNull()) {
            if (!o.get("schema").isJsonArray()) {
                Http.error(ex, 400, "schema must be an array of nodes");
                return;
            }
            schema = o.getAsJsonArray("schema");
            nodes = new HashMap<>();
            String problem = walk(schema, nodes, 0);
            if (problem != null) {
                Http.error(ex, 400, "schema: " + problem);
                return;
            }
        }
        if (nodes == null) {
            BotHub.Scope stored = hub.scope(b, scope);
            if (stored != null) nodes = stored.nodes;
        }
        values = scrubSecrets(values, nodes);
        JsonArray need = hub.state(b, scope, schemaHash, valuesHash, schema, nodes, values);
        JsonObject out = Http.ok();
        if (!need.isEmpty()) out.add("need", need);
        Http.json(ex, 200, out);
    }

    /**
     * The values without any secret: a buggy bot must not be able to make the server store or serve one. Drops values
     * of nodes marked secret and of every path that looks like a secret.
     */
    static JsonObject scrubSecrets(JsonObject values, Map<String, JsonObject> nodes) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : values.entrySet()) {
            String path = e.getKey();
            JsonObject node = nodes == null ? null : nodes.get(path);
            boolean secret = SECRET_PATH.matcher(path).find()
                || (node != null && node.has("secret") && node.get("secret").isJsonPrimitive() && node.get("secret").getAsBoolean());
            JsonElement v = e.getValue();
            if (!secret && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && URL_USERINFO.matcher(v.getAsString()).find()) {
                secret = true;
                if (node != null) {
                    node.addProperty("secret", true);
                    node.addProperty("readOnly", true);
                    node.addProperty("reason", "secret");
                    node.addProperty("set", true);
                }
            }
            if (!secret) out.add(path, v);
        }
        return out;
    }

    /** Checks the schema tree and indexes its nodes by path. Returns a problem or null. */
    static String walk(JsonArray list, Map<String, JsonObject> nodes, int depth) {
        if (depth > MAX_DEPTH) return "nested too deep";
        for (JsonElement e : list) {
            if (!e.isJsonObject()) return "nodes must be objects";
            if (nodes.size() >= MAX_NODES) return "too many nodes";
            JsonObject n = e.getAsJsonObject();
            String path = AddonRoutes.string(n, "path");
            String type = AddonRoutes.string(n, "type");
            if (path == null || path.isEmpty() || path.length() > MAX_PATH) return "every node needs a path";
            if (type == null || !TYPES.contains(type)) return path + ": unknown type";
            for (String f : new String[]{"label", "help", "reason", "section"}) {
                if (n.has(f) && !n.get(f).isJsonNull() && (AddonRoutes.string(n, f) == null || AddonRoutes.string(n, f).length() > MAX_TEXT)) {
                    return path + ": " + f + " must be a short string";
                }
            }
            for (String f : new String[]{"min", "max"}) {
                if (n.has(f) && !(n.get(f).isJsonPrimitive() && n.get(f).getAsJsonPrimitive().isNumber())) return path + ": " + f + " must be a number";
            }
            for (String f : new String[]{"nullable", "readOnly", "secret", "set"}) {
                if (n.has(f) && !(n.get(f).isJsonPrimitive() && n.get(f).getAsJsonPrimitive().isBoolean())) return path + ": " + f + " must be true or false";
            }
            if (n.has("options")) {
                if (!n.get("options").isJsonArray() || n.getAsJsonArray("options").size() > 500) return path + ": options must be a list";
                for (JsonElement opt : n.getAsJsonArray("options")) {
                    if (!opt.isJsonPrimitive() || !opt.getAsJsonPrimitive().isString()) return path + ": options must be strings";
                }
            }
            if (!type.equals("group") && SECRET_PATH.matcher(path).find()
                && !(n.has("secret") && n.get("secret").isJsonPrimitive() && n.get("secret").getAsBoolean())) {
                // Looks like a secret but the bot didn't say so: shown as one, never editable.
                n.addProperty("secret", true);
                n.addProperty("readOnly", true);
                n.addProperty("reason", "secret");
                if (!n.has("set")) n.addProperty("set", false);
            }
            nodes.put(path, n);
            if (type.equals("group")) {
                if (!n.has("children") || !n.get("children").isJsonArray()) return path + ": a group needs children";
                String problem = walk(n.getAsJsonArray("children"), nodes, depth + 1);
                if (problem != null) return problem;
            }
        }
        return null;
    }

    private void next(HttpExchange ex) throws IOException {
        BotHub.Bot b = known(ex, Http.query(ex).get("botId"));
        if (b == null) return;
        BotHub.Poll p;
        try {
            p = hub.next(b);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Http.send(ex, 204, null, null, "no-store");
            return;
        }
        if (p.status() == 429) {
            ex.getResponseHeaders().set("Retry-After", "3");
            Http.error(ex, 429, "too many bots waiting; poll every 3 s");
        } else if (p.status() == 204) {
            Http.send(ex, 204, null, null, "no-store");
        } else {
            Http.json(ex, 200, p.body());
        }
    }

    private void results(HttpExchange ex) throws IOException {
        JsonObject o = body(ex);
        if (o == null) return;
        BotHub.Bot b = known(ex, AddonRoutes.string(o, "botId"));
        if (b == null) return;
        if (!o.has("results") || !o.get("results").isJsonArray() || o.getAsJsonArray("results").size() > MAX_RESULTS) {
            Http.error(ex, 400, "results must be an array (at most " + MAX_RESULTS + ")");
            return;
        }
        for (JsonElement e : o.getAsJsonArray("results")) {
            if (!e.isJsonObject()) continue;
            JsonObject r = e.getAsJsonObject();
            String id = AddonRoutes.string(r, "id");
            if (id == null || id.length() > 64) continue;
            boolean ok = bool(r, "ok");
            boolean more = bool(r, "more");
            boolean sensitive = bool(r, "sensitive");
            String error = AddonRoutes.string(r, "error");
            JsonElement value = r.get("value");
            JsonObject output = r.has("output") && r.get("output").isJsonObject() ? cleanOutput(r.getAsJsonObject("output")) : null;
            hub.result(b, id, ok, more, value, error == null ? null : Http.truncate(error, 1000), sensitive, output);
        }
        Http.json(ex, 200, Http.ok());
    }

    /** bot/activity {botId, activity}: the latest snapshot of what the proxy is doing (at most 256 KiB). */
    private void activity(HttpExchange ex) throws IOException {
        byte[] bytes = Http.readBody(ex, 256 * 1024); // 413 above that
        JsonElement json;
        try {
            json = com.google.gson.JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            json = null;
        }
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        BotHub.Bot b = known(ex, AddonRoutes.string(o, "botId"));
        if (b == null) return;
        long now = System.currentTimeMillis();
        // A farmer-role bot's snapshot is rebuilt from an allowlist before it is stored (FARM-CONTRACT P5d); its events are dropped.
        boolean farmer = farmers.test(b.botId);
        Runnable seam = afterFarmerCheck;
        if (seam != null) seam.run();
        JsonObject snapshot = farmer ? Activity.cleanFarmer(o.get("activity"), now) : Activity.clean(o.get("activity"), now);
        if (snapshot == null) {
            Http.error(ex, 400, "activity must be an object");
            return;
        }
        hub.activity(b, snapshot, farmer ? new JsonArray() : Activity.events(o.get("activity"), now));
        // The bot may have become a farmer between the check above and the store: what was just stored is then unscrubbed and is
        // cleared again (the flip clears first and publishes the flag before it does, so one of the two always sees the other).
        if (!farmer && farmers.test(b.botId)) hub.clearBotData(b.botId);
        Http.json(ex, 200, Http.ok());
    }

    private static boolean bool(JsonObject o, String f) {
        JsonElement e = o.get(f);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static final Pattern COLOR = Pattern.compile("^(error|success|primary|queue|#[0-9a-fA-F]{6})$");

    /** Keeps only {title, description, color, fields:[{name, value}], lines:[]} with strings, capped. */
    static JsonObject cleanOutput(JsonObject in) {
        JsonObject out = new JsonObject();
        String title = AddonRoutes.string(in, "title");
        String description = AddonRoutes.string(in, "description");
        String color = AddonRoutes.string(in, "color");
        if (title != null) out.addProperty("title", Http.truncate(title, 1000));
        if (description != null) out.addProperty("description", Http.truncate(description, 16_384));
        if (color != null && COLOR.matcher(color).matches()) out.addProperty("color", color);
        JsonArray fields = new JsonArray();
        if (in.has("fields") && in.get("fields").isJsonArray()) {
            for (JsonElement f : in.getAsJsonArray("fields")) {
                if (fields.size() >= 50 || !f.isJsonObject()) break;
                String name = AddonRoutes.string(f.getAsJsonObject(), "name");
                String value = AddonRoutes.string(f.getAsJsonObject(), "value");
                if (name == null || value == null) continue;
                JsonObject fo = new JsonObject();
                fo.addProperty("name", Http.truncate(name, 1000));
                fo.addProperty("value", Http.truncate(value, 8192));
                fields.add(fo);
            }
        }
        out.add("fields", fields);
        JsonArray lines = new JsonArray();
        if (in.has("lines") && in.get("lines").isJsonArray()) {
            for (JsonElement l : in.getAsJsonArray("lines")) {
                if (lines.size() >= 2000) break;
                if (l.isJsonPrimitive() && l.getAsJsonPrimitive().isString()) lines.add(Http.truncate(l.getAsString(), 4096));
            }
        }
        out.add("lines", lines);
        return out;
    }
}

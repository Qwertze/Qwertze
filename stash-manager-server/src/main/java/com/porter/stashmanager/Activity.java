package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Set;

/**
 * Checks and cleans a bot's activity snapshot (PROTOCOL.md, "Additions: activity"): only the documented fields, with
 * their types, strings capped at 300 characters, lists capped. Anything else is dropped, so the page only ever gets
 * the documented shape.
 */
final class Activity {
    static final int MAX_STRING = 300;
    static final int MAX_CURRENT = 50;
    static final int MAX_MODULES = 300;
    static final int MAX_PLUGINS = 100;
    static final int MAX_EVENTS = 100;
    static final Set<String> STATES = Set.of("online", "queue", "connecting", "offline", "disconnected");
    static final Set<String> LEVELS = Set.of("info", "warn", "error");
    static final Set<String> KINDS = Set.of("plugin", "module", "process");

    static final long MAX_PAST_MS = 24L * 60 * 60 * 1000;
    static final long MAX_FUTURE_MS = 5L * 60 * 1000;

    private Activity() {
    }

    /** A plausible time: within 1 day before and 5 minutes after the server received it. */
    static boolean sane(Double at, long now) {
        return at != null && at >= now - MAX_PAST_MS && at <= now + MAX_FUTURE_MS;
    }

    private static String str(JsonObject o, String f) {
        JsonElement e = o.get(f);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? Http.truncate(e.getAsString(), MAX_STRING) : null;
    }

    private static Double num(JsonObject o, String f) {
        JsonElement e = o.get(f);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        double d = e.getAsDouble();
        return Double.isFinite(d) ? d : null;
    }

    private static void copyStr(JsonObject in, JsonObject out, String f) {
        String s = str(in, f);
        if (s != null) out.addProperty(f, s);
    }

    private static void copyNum(JsonObject in, JsonObject out, String f) {
        Double d = num(in, f);
        if (d != null) out.add(f, d == Math.rint(d) && Math.abs(d) < 9e15 ? new JsonPrimitive(d.longValue()) : new JsonPrimitive(d));
    }

    private static String oneOf(JsonObject in, String f, Set<String> allowed, String fallback) {
        String s = str(in, f);
        return s != null && allowed.contains(s) ? s : fallback;
    }

    private static JsonArray array(JsonObject o, String f) {
        JsonElement e = o.get(f);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    /** The cleaned snapshot (without events), or null when it isn't an object. */
    static JsonObject clean(JsonElement raw, long now) {
        if (raw == null || !raw.isJsonObject()) return null;
        JsonObject in = raw.getAsJsonObject();
        JsonObject out = new JsonObject();
        Double at = num(in, "at");
        out.addProperty("at", sane(at, now) ? at.longValue() : now);

        if (in.has("connection") && in.get("connection").isJsonObject()) {
            JsonObject c = in.getAsJsonObject("connection");
            JsonObject co = new JsonObject();
            co.addProperty("state", oneOf(c, "state", STATES, "offline"));
            for (String f : new String[]{"server", "queueEta", "dimension", "controllingPlayer"}) copyStr(c, co, f);
            for (String f : new String[]{"queuePosition", "x", "y", "z", "health", "food", "ping", "uptimeSeconds", "spectators"}) copyNum(c, co, f);
            out.add("connection", co);
        }

        JsonArray current = new JsonArray();
        for (JsonElement e : array(in, "current")) {
            if (current.size() >= MAX_CURRENT || !e.isJsonObject()) continue;
            JsonObject c = e.getAsJsonObject();
            JsonObject co = new JsonObject();
            copyStr(c, co, "source");
            co.addProperty("kind", oneOf(c, "kind", KINDS, "process"));
            copyStr(c, co, "title");
            copyStr(c, co, "detail");
            if (c.has("progress") && c.get("progress").isJsonObject()) {
                JsonObject p = c.getAsJsonObject("progress");
                Double done = num(p, "done"), total = num(p, "total");
                if (done != null && total != null && done >= 0 && total > 0) {
                    JsonObject po = new JsonObject();
                    po.addProperty("done", Math.min(done, total));
                    po.addProperty("total", total);
                    co.add("progress", po);
                }
            }
            Double since = num(c, "since");
            if (sane(since, now)) co.addProperty("since", since.longValue());
            co.addProperty("level", oneOf(c, "level", LEVELS, "info"));
            if (co.has("source") || co.has("title")) current.add(co);
        }
        out.add("current", current);

        JsonArray modules = new JsonArray();
        for (JsonElement e : array(in, "modules")) {
            if (modules.size() >= MAX_MODULES || !e.isJsonObject()) continue;
            JsonObject m = e.getAsJsonObject();
            String name = str(m, "name");
            if (name == null) continue;
            JsonObject mo = new JsonObject();
            mo.addProperty("name", name);
            copyStr(m, mo, "plugin");
            JsonElement en = m.get("enabled");
            mo.addProperty("enabled", en != null && en.isJsonPrimitive() && en.getAsJsonPrimitive().isBoolean() && en.getAsBoolean());
            copyStr(m, mo, "status");
            modules.add(mo);
        }
        out.add("modules", modules);

        JsonArray plugins = new JsonArray();
        for (JsonElement e : array(in, "plugins")) {
            if (plugins.size() >= MAX_PLUGINS || !e.isJsonObject()) continue;
            JsonObject p = e.getAsJsonObject();
            JsonObject po = new JsonObject();
            copyStr(p, po, "id");
            copyStr(p, po, "version");
            copyStr(p, po, "name");
            if (po.has("id") || po.has("name")) plugins.add(po);
        }
        out.add("plugins", plugins);
        return out;
    }

    /** The snapshot's new events, cleaned (at most 100). */
    static JsonArray events(JsonElement raw, long now) {
        JsonArray out = new JsonArray();
        if (raw == null || !raw.isJsonObject()) return out;
        for (JsonElement e : array(raw.getAsJsonObject(), "events")) {
            if (out.size() >= MAX_EVENTS || !e.isJsonObject()) continue;
            JsonObject v = e.getAsJsonObject();
            String text = str(v, "text");
            Double at = num(v, "at");
            if (text == null || at == null) continue;
            JsonObject vo = new JsonObject();
            vo.addProperty("at", sane(at, now) ? at.longValue() : now);
            String source = str(v, "source");
            vo.addProperty("source", source == null ? "" : source);
            vo.addProperty("level", oneOf(v, "level", LEVELS, "info"));
            vo.addProperty("text", text);
            out.add(vo);
        }
        return out;
    }

    /** A one-line "now doing" for the bot list: the first current item, else the connection state. */
    static String summary(JsonObject a) {
        if (a == null) return null;
        JsonArray current = a.has("current") ? a.getAsJsonArray("current") : new JsonArray();
        if (!current.isEmpty()) {
            JsonObject c = current.get(0).getAsJsonObject();
            String source = c.has("source") ? c.get("source").getAsString() : "";
            String title = c.has("title") ? c.get("title").getAsString() : "";
            String s = source.isEmpty() ? title : title.isEmpty() ? source : source + ": " + title;
            return current.size() > 1 ? s + " (+" + (current.size() - 1) + " more)" : s;
        }
        JsonObject c = a.has("connection") ? a.getAsJsonObject("connection") : null;
        if (c == null) return null;
        String state = c.get("state").getAsString();
        if (state.equals("queue") && c.has("queuePosition")) return "In queue #" + c.get("queuePosition").getAsLong();
        return switch (state) {
            case "online" -> "Online" + (c.has("server") ? " on " + c.get("server").getAsString() : "") + ", idle";
            case "connecting" -> "Connecting";
            default -> "Offline";
        };
    }
}

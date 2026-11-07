package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * {@code GET /api/v1/bots/{id}/activity}: what the bot is doing, as the website's Activity page reads it. The answer is
 * {@code {receivedAt, online, activity:{connection, current[], modules[], plugins[]}, events[]}} (the events are beside
 * {@code activity}, newest first). Every text is the bot's, so it is cleaned; a wrong type gives a default. Pure.
 */
public final class ActivityModel {
    private ActivityModel() {
    }

    public static final int MAX_TASKS = 50;
    public static final int MAX_MODULES = 600;
    public static final int MAX_PLUGINS = 100;
    public static final int MAX_EVENTS = 500;
    /** The website shows a report as stale after a minute. */
    public static final long STALE_MS = 60_000;

    /** The levels the website knows; anything else counts as info. */
    public static final List<String> LEVELS = List.of("info", "warn", "error");

    /** Numbers the bot did not send are NaN (queuePosition: -1). */
    public record Connection(String state, int queuePosition, String queueEta, String server, String dimension, boolean hasPosition,
                             double x, double y, double z, double health, double food, double ping, double uptimeSeconds, double spectators,
                             String controllingPlayer) {
        public static final Connection NONE = new Connection("offline", -1, "", "", "", false, 0, 0, 0, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, "");
    }

    /** {@code total} 0 means the task has no progress. */
    public record Task(String source, String title, String detail, String level, long since, long done, long total) {
        public boolean hasProgress() {
            return total > 0;
        }
    }

    public record Module(String name, String plugin, String status, boolean enabled) {
    }

    public record Plugin(String id, String name, String version) {
    }

    public record Event(long at, String source, String level, String text) {
    }

    /** {@code hasActivity} is false until the bot's first report. Events are oldest first. */
    public record Data(boolean online, long receivedAt, boolean hasActivity, Connection connection, List<Task> current, List<Module> modules,
                       List<Plugin> plugins, List<Event> events) {
        public static final Data EMPTY = new Data(false, 0, false, Connection.NONE, List.of(), List.of(), List.of(), List.of());

        public int modulesOn() {
            int n = 0;
            for (Module m : modules) if (m.enabled()) n++;
            return n;
        }
    }

    public static String level(String raw) {
        return LEVELS.contains(raw) ? raw : "info";
    }

    /** Never null. */
    public static Data parse(JsonElement body) {
        JsonObject root = Json.obj(body);
        if (root == null) return Data.EMPTY;
        JsonObject a = Json.obj(root.get("activity"));
        Connection conn = Connection.NONE;
        List<Task> tasks = new ArrayList<>();
        List<Module> modules = new ArrayList<>();
        List<Plugin> plugins = new ArrayList<>();
        if (a != null) {
            conn = connection(Json.obj(a.get("connection")));
            JsonArray cur = Json.arr(a.get("current"));
            if (cur != null) {
                for (JsonElement e : cur) {
                    JsonObject o = Json.obj(e);
                    if (o == null || tasks.size() >= MAX_TASKS) continue;
                    JsonObject pr = Json.obj(o.get("progress"));
                    tasks.add(new Task(Text.sanitize(Json.str(o, "source"), 40), Text.sanitize(Json.str(o, "title"), 200),
                        Text.sanitize(Json.str(o, "detail"), 300), level(Json.str(o, "level")), Json.lng(o, "since", -1),
                        pr == null ? 0 : (long) Json.num(pr, "done", 0), pr == null ? 0 : (long) Json.num(pr, "total", 0)));
                }
            }
            JsonArray mods = Json.arr(a.get("modules"));
            if (mods != null) {
                for (JsonElement e : mods) {
                    JsonObject o = Json.obj(e);
                    if (o == null || modules.size() >= MAX_MODULES) continue;
                    modules.add(new Module(Text.sanitize(Json.str(o, "name"), 80), Text.sanitize(Json.str(o, "plugin"), 60),
                        Text.sanitize(Json.str(o, "status"), 160), Json.bool(o, "enabled")));
                }
            }
            JsonArray pl = Json.arr(a.get("plugins"));
            if (pl != null) {
                for (JsonElement e : pl) {
                    JsonObject o = Json.obj(e);
                    if (o == null || plugins.size() >= MAX_PLUGINS) continue;
                    plugins.add(new Plugin(Text.sanitize(Json.str(o, "id"), 60), Text.sanitize(Json.str(o, "name"), 80),
                        Text.sanitize(Json.str(o, "version"), 30)));
                }
            }
        }
        List<Event> events = new ArrayList<>();
        JsonArray ea = Json.arr(root.get("events"));
        if (ea != null) {
            for (JsonElement e : ea) {
                JsonObject o = Json.obj(e);
                if (o == null || events.size() >= MAX_EVENTS) continue;
                events.add(new Event(Json.lng(o, "at", 0), Text.sanitize(Json.str(o, "source"), 40), level(Json.str(o, "level")),
                    Text.sanitize(Json.str(o, "text"), 400)));
            }
        }
        // oldest first: the log shows the newest at the bottom (the website lists newest first, by time)
        events.sort(Comparator.comparingLong(Event::at));
        return new Data(Json.bool(root, "online"), Json.lng(root, "receivedAt", 0), a != null, conn, List.copyOf(tasks), List.copyOf(modules),
            List.copyOf(plugins), List.copyOf(events));
    }

    private static Connection connection(JsonObject c) {
        if (c == null) return Connection.NONE;
        String state = Text.sanitize(Json.str(c, "state"), 24);
        double qp = Json.num(c, "queuePosition", -1);
        boolean pos = c.has("x") && Double.isFinite(Json.num(c, "x", Double.NaN));
        return new Connection(state.isEmpty() ? "offline" : state, (int) qp, Text.sanitize(Json.str(c, "queueEta"), 24),
            Text.sanitize(Json.str(c, "server"), 120), Text.sanitize(Json.str(c, "dimension"), 60), pos,
            Json.num(c, "x", 0), Json.num(c, "y", 0), Json.num(c, "z", 0), Json.num(c, "health", Double.NaN), Json.num(c, "food", Double.NaN),
            Json.num(c, "ping", Double.NaN), Json.num(c, "uptimeSeconds", Double.NaN), Json.num(c, "spectators", Double.NaN),
            Text.sanitize(Json.str(c, "controllingPlayer"), 32));
    }

    // ---------- text ----------

    /** "In queue #5 (ETA 10m)", "Online", "Offline" as the website's badge says it. */
    public static String badge(Connection c) {
        if (c.state().equals("queue")) {
            return "In queue" + (c.queuePosition() >= 0 ? " #" + c.queuePosition() : "") + (c.queueEta().isEmpty() ? "" : " · ETA " + c.queueEta());
        }
        return c.state().substring(0, 1).toUpperCase(Locale.ROOT) + c.state().substring(1);
    }

    /** The website's durationText: "5s", "3m", "2h 5m", "3d 4h". */
    public static String duration(long ms) {
        long s = Math.max(0, Math.round(ms / 1000.0));
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 48) return h + "h " + (m % 60) + "m";
        return (h / 24) + "d " + (h % 24) + "h";
    }

    /** "last update 5s ago", with the stale note after a minute; "" when the bot never reported. */
    public static String updated(long receivedAt, long serverNow) {
        if (receivedAt <= 0) return "";
        long age = serverNow - receivedAt;
        return "last update " + duration(age) + " ago" + (age > STALE_MS ? " (stale: the bot is not reporting)" : "");
    }

    public static boolean stale(long receivedAt, long serverNow) {
        return receivedAt > 0 && serverNow - receivedAt > STALE_MS;
    }

    /** "overworld" for "minecraft:overworld". */
    public static String dimension(String d) {
        return d.startsWith("minecraft:") ? d.substring(10) : d;
    }

    /** The module group title: "AquariusProxy" for the proxy's own modules. */
    public static String groupTitle(String plugin) {
        return plugin.isEmpty() || plugin.equals("aquarius") ? "AquariusProxy" : plugin;
    }

    /** "12:03:45" in {@code zone}. */
    public static String clock(long at, ZoneId zone) {
        return DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(at).atZone(zone));
    }

    // ---------- filters ----------

    /** The sources of the events, sorted, for the filter's choices. */
    public static List<String> sources(List<Event> events) {
        TreeSet<String> out = new TreeSet<>();
        for (Event e : events) if (!e.source().isEmpty()) out.add(e.source());
        return List.copyOf(out);
    }

    /** The events with this source ("" = any) and level ("" = any), in the same order. */
    public static List<Event> filterEvents(List<Event> events, String source, String level) {
        List<Event> out = new ArrayList<>();
        for (Event e : events) {
            if (!source.isEmpty() && !e.source().equals(source)) continue;
            if (!level.isEmpty() && !e.level().equals(level)) continue;
            out.add(e);
        }
        return out;
    }

    /** The modules matching {@code query} (name, plugin or status, case-insensitive) and "enabled only", grouped by plugin in order of first appearance. */
    public static Map<String, List<Module>> filterModules(List<Module> modules, String query, boolean enabledOnly) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Map<String, List<Module>> groups = new LinkedHashMap<>();
        for (Module m : modules) {
            if (enabledOnly && !m.enabled()) continue;
            if (!q.isEmpty() && !(m.name() + " " + m.plugin() + " " + m.status()).toLowerCase(Locale.ROOT).contains(q)) continue;
            groups.computeIfAbsent(m.plugin().isEmpty() ? "aquarius" : m.plugin(), k -> new ArrayList<>()).add(m);
        }
        return groups;
    }

    // ---------- change detection ----------

    /**
     * Everything the page draws except the events and the ages, as one string. The tab rebuilds its widgets only when this changes.
     * {@code ageBucketMs} is added to the tasks' "since" so their duration text refreshes now and then (0: never).
     */
    public static String structureSignature(Data d, long serverNow, long ageBucketMs) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.online()).append(d.hasActivity());
        sb.append('|').append(connectionSignature(d.connection()));
        for (Task t : d.current()) {
            sb.append('|').append(t.source()).append('\u0001').append(t.title()).append('\u0001').append(t.detail()).append('\u0001')
                .append(t.level()).append('\u0001').append(t.done()).append('/').append(t.total());
            if (t.since() > 0 && ageBucketMs > 0) sb.append('@').append((serverNow - t.since()) / ageBucketMs);
        }
        for (Module m : d.modules()) sb.append('|').append(m.name()).append(m.plugin()).append(m.status()).append(m.enabled());
        for (Plugin p : d.plugins()) sb.append('|').append(p.id()).append(p.name()).append(p.version());
        return sb.toString();
    }

    /**
     * The parts of the connection that change the layout. The numbers that move all the time (position, health, food, ping, uptime,
     * spectators) are left out, except for whether the bot sent them: the tab updates them in place.
     */
    public static String connectionSignature(Connection c) {
        return c.state() + '' + c.queuePosition() + '' + c.queueEta() + '' + c.server() + '' + c.dimension()
            + '' + c.hasPosition() + !Double.isNaN(c.health()) + !Double.isNaN(c.food()) + !Double.isNaN(c.ping())
            + !Double.isNaN(c.uptimeSeconds()) + !Double.isNaN(c.spectators()) + '' + c.controllingPlayer();
    }

    /** The events as one string (changes when one is added). */
    public static String eventsSignature(Data d) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.events().size());
        if (!d.events().isEmpty()) {
            Event last = d.events().get(d.events().size() - 1);
            Event first = d.events().get(0);
            sb.append(':').append(first.at()).append(first.text()).append(':').append(last.at()).append(last.source()).append(last.text());
        }
        return sb.toString();
    }
}

package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The dashboard bots (DASHBOARD-DESIGN.md, phases 2 and 3), in memory: who said hello, their settings per scope with the
 * schema and value hashes, the job queue (settings changes and console commands) the bot long-polls with bot/next,
 * the console history, and whether a browser has the bot open ("watching"). One monitor per bot guards its state; the
 * long-poll waits on it.
 */
final class BotHub {
    static final int MAX_BOTS = 64;
    static final int MAX_WAITERS = 8;
    static final long POLL_WAIT_MS = 25_000;
    static final long ONLINE_MS = 35_000;
    static final long EXPIRE_QUEUED_MS = 10_000;
    static final long UNKNOWN_SENT_MS = 60_000;
    static final long WATCH_MS = 10_000;
    /** The live viewer's chunk radius and frame rate, sent to the bot in bot/next (PROTOCOL.md, "Additions: live viewer"). */
    static final int VIEWER_RADIUS = 8;
    static final int VIEWER_HZ = 5;
    static final int CONSOLE_KEEP = 200;
    static final int EVENTS_KEEP = 500;
    static final int JOBS_KEEP = 500;
    static final int MAX_OUTPUT_PARTS = 50;
    static final int MAX_ENTRY_OUTPUT = 256 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A settings scope as the bot last pushed it. */
    static final class Scope {
        String schemaHash;
        String valuesHash;
        JsonArray schema;              // null until the bot sends one
        Map<String, JsonObject> nodes;  // leaf and group nodes by path
        JsonObject values = new JsonObject();
        long receivedAt;
    }

    static final class Job {
        final String id;
        final String type; // set | command
        final JsonObject payload;
        final long createdAt;
        String status = "queued"; // queued | sent | done | error | expired | unknown
        long sentAt;
        JsonElement value;
        String error;
        ConsoleEntry console;

        Job(String id, String type, JsonObject payload, long createdAt) {
            this.id = id;
            this.type = type;
            this.payload = payload;
            this.createdAt = createdAt;
        }
    }

    static final class ConsoleEntry {
        final long seq;
        final String id;
        String command; // null once the bot says the input was sensitive
        final long at;
        String by; // the website user who sent it (Users.OWNER_ID or a sub-user's id), or null
        String status = "queued";
        String error;
        final List<JsonObject> outputs = new ArrayList<>();
        int outputSize;
        long rev;

        ConsoleEntry(long seq, String id, String command, long at) {
            this.seq = seq;
            this.id = id;
            this.command = command;
            this.at = at;
        }
    }

    static final class Bot {
        final String botId;
        String player;
        JsonObject plugin = new JsonObject();
        JsonObject proxy = new JsonObject();
        Set<String> caps = new LinkedHashSet<>();
        /** The scopes the bot says it can show (bot/hello "scopes"); only "plugin" is pushed unasked. */
        Set<String> declaredScopes = new LinkedHashSet<>();
        long helloAt;
        /** Bumped by every bot/hello: a long-poll from before it (a restarted bot's dangling request) gives up. */
        long helloGen;
        long lastPoll;
        long lastSeen;
        int waiting;
        long watchUntil;
        Boolean watchingSent;
        long needSentAt;
        long watchActivityUntil;
        Boolean watchingActivitySent;
        /** Until when a browser has the live viewer open, and the viewer session (16 hex) the bot must send. */
        long watchViewerUntil;
        String viewerSession;
        Boolean watchingViewerSent;
        String viewerSessionSent;
        JsonObject activity;     // the latest cleaned snapshot, or null
        long activityAt;         // when it arrived
        final ArrayDeque<JsonObject> events = new ArrayDeque<>(); // newest last
        final java.util.HashSet<String> eventKeys = new java.util.HashSet<>(); // (at, source, text) of those kept
        final Map<String, Scope> scopes = new HashMap<>();
        final Set<String> need = new LinkedHashSet<>(); // "scope\u0000what"
        final ArrayDeque<Job> queue = new ArrayDeque<>();
        final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
        final ArrayDeque<ConsoleEntry> console = new ArrayDeque<>();
        long consoleSeq;
        long consoleRev;

        Bot(String botId) {
            this.botId = botId;
        }
    }

    private final LongSupplier clock;
    private final Map<String, Bot> bots = new LinkedHashMap<>();
    private int waiters;

    BotHub() {
        this(System::currentTimeMillis);
    }

    BotHub(LongSupplier clock) {
        this.clock = clock;
    }

    long now() {
        return clock.getAsLong();
    }

    // ---------- bot side ----------

    /** bot/hello: registers or updates a bot. Returns false when the registry is full of online bots. */
    boolean hello(String botId, String player, JsonObject plugin, JsonObject proxy, List<String> caps) {
        return hello(botId, player, plugin, proxy, caps, List.of());
    }

    boolean hello(String botId, String player, JsonObject plugin, JsonObject proxy, List<String> caps, List<String> scopes) {
        long now = now();
        Bot b;
        synchronized (this) {
            b = bots.get(botId);
            if (b == null) {
                if (bots.size() >= MAX_BOTS && !evictOne(now)) return false;
                b = new Bot(botId);
                bots.put(botId, b);
            }
        }
        synchronized (b) {
            if (player != null && !player.isEmpty()) b.player = player;
            b.plugin = plugin;
            b.proxy = proxy;
            b.caps = new LinkedHashSet<>(caps);
            b.declaredScopes = new LinkedHashSet<>(scopes);
            b.helloAt = now;
            b.lastSeen = now;
            b.lastPoll = now;
            b.need.clear(); // a new session pushes everything by itself
            b.watchingSent = null;
            b.watchingActivitySent = null;
            b.watchingViewerSent = null;
            b.viewerSessionSent = null;
            b.helloGen++;
            b.notifyAll(); // ends a long-poll from the bot's previous run, so it can't take what this run must be told
        }
        return true;
    }

    private boolean evictOne(long now) {
        Bot oldest = null;
        for (Bot b : bots.values()) {
            synchronized (b) {
                if (online(b, now)) continue;
                if (oldest == null || b.lastSeen < oldest.lastSeen) oldest = b;
            }
        }
        if (oldest == null) return false;
        bots.remove(oldest.botId);
        return true;
    }

    synchronized Bot bot(String botId) {
        return botId == null ? null : bots.get(botId);
    }

    synchronized List<Bot> all() {
        return new ArrayList<>(bots.values());
    }

    static boolean online(Bot b, long now) {
        return b.waiting > 0 || now - b.lastPoll < ONLINE_MS;
    }

    /**
     * bot/state for one scope. A schema is stored whenever it comes (asked for or not); when the schema hash changed
     * and no schema came, it is asked for. Returns the need list for the reply (possibly empty).
     */
    JsonArray state(Bot b, String scope, String schemaHash, String valuesHash, JsonArray schema, Map<String, JsonObject> nodes,
                    JsonObject values) {
        long now = now();
        synchronized (b) {
            b.lastSeen = now;
            Scope s = b.scopes.computeIfAbsent(scope, k -> new Scope());
            if (schema != null) {
                s.schema = schema;
                s.nodes = nodes;
                s.schemaHash = schemaHash;
                b.need.remove(scope + "\u0000schema");
            } else if (s.schema == null || !schemaHash.equals(s.schemaHash)) {
                b.need.add(scope + "\u0000schema");
            }
            s.values = values;
            s.valuesHash = valuesHash;
            s.receivedAt = now;
            b.need.remove(scope + "\u0000values");
            JsonArray need = new JsonArray();
            for (String n : b.need) {
                String[] p = n.split("\u0000", 2);
                JsonObject o = new JsonObject();
                o.addProperty("scope", p[0]);
                o.addProperty("what", p[1]);
                need.add(o);
            }
            b.notifyAll();
            return need;
        }
    }

    /** The outcome of a long-poll: 200 with a body, 204, or 429 (too many waiting). */
    record Poll(int status, JsonObject body) {
    }

    /** bot/next: hands out queued jobs and needs, waiting up to 25 s for something to happen. */
    Poll next(Bot b) throws InterruptedException {
        synchronized (this) {
            if (waiters >= MAX_WAITERS) return new Poll(429, null);
            waiters++;
        }
        try {
            synchronized (b) {
                b.waiting++;
                long gen = b.helloGen;
                try {
                    long end = now() + POLL_WAIT_MS;
                    while (true) {
                        // A hello since this poll began: it belongs to the bot's previous run (its socket is
                        // probably dead). Answer 204 without handing out jobs or watch changes.
                        if (b.helloGen != gen) return new Poll(204, null);
                        long now = now();
                        b.lastPoll = now;
                        b.lastSeen = now;
                        expire(b, now);
                        boolean watching = b.watchUntil > now;
                        boolean watchingActivity = b.watchActivityUntil > now;
                        boolean watchingViewer = b.watchViewerUntil > now;
                        String viewerSession = watchingViewer ? b.viewerSession : null;
                        boolean watchChanged = b.watchingSent == null || b.watchingSent != watching
                            || b.watchingActivitySent == null || b.watchingActivitySent != watchingActivity
                            || b.watchingViewerSent == null || b.watchingViewerSent != watchingViewer
                            || !java.util.Objects.equals(viewerSession, b.viewerSessionSent);
                        // A need is repeated at most every 10 s, so a bot that can't answer it doesn't spin.
                        boolean needDue = !b.need.isEmpty() && now - b.needSentAt >= 10_000;
                        if (!b.queue.isEmpty() || needDue || watchChanged) {
                            JsonObject out = new JsonObject();
                            JsonArray jobs = new JsonArray();
                            while (!b.queue.isEmpty()) {
                                Job j = b.queue.pollFirst();
                                j.status = "sent";
                                j.sentAt = now;
                                if (j.console != null) touch(b, j.console, "sent");
                                JsonObject jo = j.payload.deepCopy();
                                jo.addProperty("id", j.id);
                                jo.addProperty("type", j.type);
                                jobs.add(jo);
                            }
                            out.add("jobs", jobs);
                            JsonArray need = new JsonArray();
                            for (String n : b.need) {
                                String[] p = n.split("\u0000", 2);
                                JsonObject o = new JsonObject();
                                o.addProperty("scope", p[0]);
                                o.addProperty("what", p[1]);
                                need.add(o);
                            }
                            out.add("need", need);
                            if (!need.isEmpty()) b.needSentAt = now;
                            out.addProperty("watching", watching);
                            out.addProperty("watchingActivity", watchingActivity);
                            out.addProperty("watchingViewer", watchingViewer);
                            if (watchingViewer) {
                                JsonObject v = new JsonObject();
                                v.addProperty("radius", VIEWER_RADIUS);
                                v.addProperty("hz", VIEWER_HZ);
                                v.addProperty("session", viewerSession);
                                out.add("viewer", v);
                            }
                            b.watchingSent = watching;
                            b.watchingActivitySent = watchingActivity;
                            b.watchingViewerSent = watchingViewer;
                            b.viewerSessionSent = viewerSession;
                            return new Poll(200, out);
                        }
                        long left = end - now;
                        if (left <= 0) return new Poll(204, null);
                        // Wake up in time to tell the bot when the browser stops watching.
                        long wait = Math.min(left, watching ? Math.max(1, b.watchUntil - now + 1) : left);
                        if (watchingActivity) wait = Math.min(wait, Math.max(1, b.watchActivityUntil - now + 1));
                        if (watchingViewer) wait = Math.min(wait, Math.max(1, b.watchViewerUntil - now + 1));
                        if (!b.need.isEmpty()) wait = Math.min(wait, Math.max(1, b.needSentAt + 10_000 - now));
                        b.wait(wait);
                    }
                } finally {
                    b.waiting--;
                    b.lastPoll = now();
                }
            }
        } finally {
            synchronized (this) {
                waiters--;
            }
        }
    }

    /** bot/results: one result (a set reply, a command's first reply, or a later output part with more). */
    void result(Bot b, String id, boolean ok, boolean more, JsonElement value, String error, boolean sensitive, JsonObject output) {
        long now = now();
        synchronized (b) {
            b.lastSeen = now;
            Job j = b.jobs.get(id);
            if (j == null) return;
            if (more) {
                if (j.console != null && output != null) addOutput(b, j.console, output);
                return;
            }
            j.status = ok ? "done" : "error";
            j.value = value;
            j.error = error;
            if (j.console != null) {
                ConsoleEntry e = j.console;
                if (sensitive) {
                    e.command = null;
                    if (output != null) {
                        JsonObject only = new JsonObject();
                        if (output.has("title")) only.add("title", output.get("title"));
                        if (output.has("color")) only.add("color", output.get("color"));
                        output = only;
                    }
                }
                e.error = error;
                if (output != null) addOutput(b, e, output);
                touch(b, e, j.status);
            }
            b.notifyAll();
        }
    }

    private static void addOutput(Bot b, ConsoleEntry e, JsonObject output) {
        String text = Json.GSON.toJson(output);
        if (e.outputs.size() >= MAX_OUTPUT_PARTS || e.outputSize + text.length() > MAX_ENTRY_OUTPUT) return;
        e.outputs.add(output);
        e.outputSize += text.length();
        e.rev = ++b.consoleRev;
    }

    private static void touch(Bot b, ConsoleEntry e, String status) {
        e.status = status;
        e.rev = ++b.consoleRev;
    }

    /** Queued jobs nobody took within 10 s expire; sent ones without an answer within 60 s become unknown. */
    private static void expire(Bot b, long now) {
        for (Iterator<Job> it = b.queue.iterator(); it.hasNext(); ) {
            Job j = it.next();
            if (now - j.createdAt >= EXPIRE_QUEUED_MS) {
                it.remove();
                j.status = "expired";
                if (j.console != null) touch(b, j.console, "expired");
            }
        }
        for (Job j : b.jobs.values()) {
            if (j.status.equals("sent") && now - j.sentAt >= UNKNOWN_SENT_MS) {
                j.status = "unknown";
                if (j.console != null) touch(b, j.console, "unknown");
            }
        }
    }

    // ---------- browser side ----------

    /** A browser has this bot open: the bot re-checks its values every 3 s and the long-poll is told at once. */
    void watch(Bot b) {
        synchronized (b) {
            boolean was = b.watchUntil > now();
            b.watchUntil = now() + WATCH_MS;
            if (!was) b.notifyAll();
        }
    }

    /** A browser has this bot's Activity tab open: the bot pushes every 2 s, and the long-poll is told at once. */
    void watchActivity(Bot b) {
        synchronized (b) {
            boolean was = b.watchActivityUntil > now();
            b.watchActivityUntil = now() + WATCH_MS;
            if (!was) b.notifyAll();
        }
    }

    /**
     * A browser has this bot's live viewer open (every open stream calls this every few seconds, and once more when it
     * closes, so watching lingers 10 s over a reconnect). When watching starts, or with fresh=true (the server has no
     * snapshot for this bot), a new viewer session is minted: the bot's next frame must be full. The long-poll is told
     * at once. Returns the current session.
     */
    String watchViewer(Bot b, boolean fresh) {
        synchronized (b) {
            long now = now();
            boolean was = b.watchViewerUntil > now;
            b.watchViewerUntil = now + WATCH_MS;
            if (!was || fresh || b.viewerSession == null) {
                b.viewerSession = newSession();
                b.notifyAll();
            }
            return b.viewerSession;
        }
    }

    /** The viewer session the bot must use now, or null when nobody is watching. */
    String viewerSession(Bot b) {
        synchronized (b) {
            return b.watchViewerUntil > now() ? b.viewerSession : null;
        }
    }

    boolean watchingViewer(Bot b) {
        synchronized (b) {
            return b.watchViewerUntil > now();
        }
    }

    private static String newSession() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** bot/activity: stores the snapshot and adds its new events (de-duplicated by at, source and text). */
    void activity(Bot b, JsonObject snapshot, JsonArray events) {
        long now = now();
        synchronized (b) {
            b.lastSeen = now;
            b.activity = snapshot;
            b.activityAt = now;
            for (JsonElement e : events) {
                JsonObject v = e.getAsJsonObject();
                String key = v.get("at").getAsLong() + "\u0000" + v.get("source").getAsString() + "\u0000" + v.get("text").getAsString();
                if (!b.eventKeys.add(key)) continue;
                b.events.addLast(v);
                while (b.events.size() > EVENTS_KEEP) {
                    JsonObject old = b.events.pollFirst();
                    b.eventKeys.remove(old.get("at").getAsLong() + "\u0000" + old.get("source").getAsString() + "\u0000" + old.get("text").getAsString());
                }
            }
        }
    }

    /** {activity, receivedAt, events (newest first)} for the page. */
    JsonObject activityJson(Bot b) {
        JsonObject out = new JsonObject();
        synchronized (b) {
            out.add("activity", b.activity == null ? JsonNull.INSTANCE : b.activity.deepCopy());
            out.addProperty("receivedAt", b.activityAt > 0 ? b.activityAt : null);
            JsonArray ev = new JsonArray();
            java.util.Iterator<JsonObject> it = b.events.descendingIterator();
            while (it.hasNext()) ev.add(it.next());
            out.add("events", ev);
            out.addProperty("online", online(b, now()));
        }
        return out;
    }

    boolean online(Bot b) {
        synchronized (b) {
            return online(b, now());
        }
    }

    Scope scope(Bot b, String scope) {
        synchronized (b) {
            return b.scopes.get(scope);
        }
    }

    /**
     * A browser opened a scope the server has no schema for: ask the bot for it (schema and values), at once.
     * Only for scopes the bot declared (or already pushed). Returns whether it was asked.
     */
    boolean requestScope(Bot b, String scope) {
        synchronized (b) {
            if (!b.declaredScopes.contains(scope) && !b.scopes.containsKey(scope)) return false;
            boolean added = b.need.add(scope + "\u0000schema");
            if (added) {
                b.needSentAt = 0;
                b.notifyAll();
            }
            return true;
        }
    }

    /** Queues settings changes; returns the job ids in order. */
    List<String> queueSets(Bot b, String scope, List<JsonObject> changes) {
        long now = now();
        List<String> ids = new ArrayList<>();
        synchronized (b) {
            for (JsonObject c : changes) {
                JsonObject p = new JsonObject();
                p.addProperty("scope", scope);
                p.add("path", c.get("path"));
                p.add("value", c.has("value") ? c.get("value") : JsonNull.INSTANCE);
                if (c.has("expect")) p.add("expect", c.get("expect"));
                Job j = new Job(newId(), "set", p, now);
                add(b, j);
                ids.add(j.id);
            }
            b.notifyAll();
        }
        return ids;
    }

    /** Queues a console command; returns its job id. */
    String queueCommand(Bot b, String command) {
        return queueCommand(b, command, null);
    }

    /** The same, remembering which website user sent it (a "list"-mode user only sees their own). */
    String queueCommand(Bot b, String command, String by) {
        long now = now();
        synchronized (b) {
            JsonObject p = new JsonObject();
            p.addProperty("command", command);
            Job j = new Job(newId(), "command", p, now);
            // Commands that obviously carry a secret are hidden from the start, before the bot says the input was
            // sensitive; the bot's sensitive:true hides every other kind once it answers.
            ConsoleEntry e = new ConsoleEntry(++b.consoleSeq, j.id, looksSensitive(command) ? null : command, now);
            e.rev = ++b.consoleRev;
            e.by = by;
            j.console = e;
            b.console.addLast(e);
            while (b.console.size() > CONSOLE_KEEP) b.console.pollFirst();
            add(b, j);
            b.notifyAll();
            return j.id;
        }
    }

    private static void add(Bot b, Job j) {
        b.queue.addLast(j);
        b.jobs.put(j.id, j);
        if (b.jobs.size() > JOBS_KEEP) {
            Iterator<Job> it = b.jobs.values().iterator();
            while (b.jobs.size() > JOBS_KEEP && it.hasNext()) {
                Job old = it.next();
                if (old.status.equals("queued")) continue;
                it.remove();
            }
        }
    }

    /** Status of jobs by id ("unknown" for ids the server doesn't hold). */
    JsonArray jobs(Bot b, List<String> ids) {
        JsonArray out = new JsonArray();
        synchronized (b) {
            expire(b, now());
            for (String id : ids) {
                Job j = b.jobs.get(id);
                JsonObject o = new JsonObject();
                o.addProperty("id", id);
                o.addProperty("status", j == null ? "unknown" : j.status);
                if (j != null) {
                    if (j.value != null) o.add("value", j.value);
                    if (j.error != null) o.addProperty("error", j.error);
                }
                out.add(o);
            }
        }
        return out;
    }

    /** Console entries changed after revision {@code after}, oldest first, plus the newest revision. */
    JsonObject console(Bot b, long after) {
        return console(b, after, null);
    }

    /** The same, only the entries sent by onlyBy (null: everyone's). */
    JsonObject console(Bot b, long after, String onlyBy) {
        JsonObject out = new JsonObject();
        JsonArray entries = new JsonArray();
        synchronized (b) {
            expire(b, now());
            for (ConsoleEntry e : b.console) {
                if (e.rev <= after) continue;
                if (onlyBy != null && !onlyBy.equals(e.by)) continue;
                JsonObject o = new JsonObject();
                o.addProperty("seq", e.seq);
                o.addProperty("rev", e.rev);
                o.addProperty("id", e.id);
                o.addProperty("command", e.command == null ? "(hidden)" : e.command);
                o.addProperty("hidden", e.command == null);
                o.addProperty("at", e.at);
                o.addProperty("status", e.status);
                if (e.error != null) o.addProperty("error", e.error);
                JsonArray outputs = new JsonArray();
                for (JsonObject part : e.outputs) outputs.add(part);
                o.add("output", e.outputs.isEmpty() ? JsonNull.INSTANCE : e.outputs.get(0));
                o.add("outputs", outputs);
                entries.add(o);
            }
            out.addProperty("rev", b.consoleRev);
            out.add("entries", entries);
            // The oldest entry still kept, so a page that missed some (ring overflow) knows to start over.
            out.addProperty("first", b.console.isEmpty() ? 0 : b.console.peekFirst().seq);
        }
        return out;
    }

    private static final java.util.regex.Pattern SENSITIVE = java.util.regex.Pattern.compile(
        "(?i)^\\s*auth\\b|manager\\s+key|manager\\s+url|passw|token|secret|api_?key|webhook|cookie|credential");

    static boolean looksSensitive(String command) {
        return SENSITIVE.matcher(command).find();
    }

    private static String newId() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return "j-" + HexFormat.of().formatHex(bytes);
    }
}

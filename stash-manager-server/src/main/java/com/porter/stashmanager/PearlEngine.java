package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Stash-to-stash pearling (PEARL-CONTRACT v1.0 rev 4): the stations, the trips and the loader and traveller reports, and the rules
 * that carry a trip from the request to the re-arm. The traveller and the loader report; the server decides, from both sides, what
 * has happened, and tells the traveller in its heartbeat reply. One lock guards everything; every change is written to pearl.json.
 * Nothing here ever holds a coordinate.
 */
final class PearlEngine {
    static final int MAX_NON_LOADERS = 16;
    static final int MAX_ALERTS = 50;
    static final long REPORT_INTERVAL_MS = 30_000;
    static final long GO_TIMEOUT_MS = 120_000;
    static final long LATE_MS = 300_000;
    static final long STUCK_MS = 30 * 60_000L;
    static final long BACK_BACKOFF_MS = 60_000;
    static final int BACK_FAILS = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A refusal with the HTTP status the route answers. */
    static final class Refused extends RuntimeException {
        final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    record Alert(String kind, String stash, String station, String trip, long at) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("kind", kind);
            o.addProperty("stash", stash);
            o.addProperty("station", station);
            o.addProperty("trip", trip);
            o.addProperty("at", at);
            return o;
        }
    }

    /** What a loader said last (times already on the server's clock). */
    static final class Loader {
        String player;
        long receivedAt;
        boolean drop, temporaryMode, busy, playerNear;
        String source; // hb:<server>/<stash> or bot:<botId>
        final Map<String, JsonObject> chambers = new LinkedHashMap<>(); // id -> {owner, ownerKnown, presence}
        JsonObject load; // normalised
    }

    /** What a traveller said last. */
    static final class Traveller {
        String server, stash, player, uuid, game = "in", client = "plugin";
        boolean supports, enabled;
        long receivedAt, skew, joinedAt;
        int carried;
        String awayStation, awayTrip;
        long awaySince;
        boolean awayClearPending;
        long awayClearAt;
        boolean uuidMismatch;
        final ArrayDeque<String> localIds = new ArrayDeque<>();
    }

    interface Pending {
        int orders(String server, String stash);
    }

    private final PearlStore store;
    private final LongSupplier clock;
    private final Map<String, Loader> loaders = new LinkedHashMap<>(); // lower-case player -> report (oldest first)
    private final Map<IndexStore.StashKey, Traveller> travellers = new HashMap<>();
    private final Map<String, String> locks = new HashMap<>(); // resource -> trip id
    private final Map<String, ArrayDeque<Long>> loads = new HashMap<>();
    private final Map<IndexStore.StashKey, Long> lastEnd = new HashMap<>();
    private final Map<String, Integer> suspect = new HashMap<>();
    private final Map<String, Integer> pathFails = new HashMap<>();
    private final Map<String, Long> forcedEmpty = new HashMap<>(); // station/chamber -> when
    private final List<Alert> alerts = new ArrayList<>();
    static final int MAX_PENDING = 4;
    static final long AWAY_CLEAR_MAX_MS = 10 * 60_000L;
    private final Map<String, LinkedHashMap<String, Long>> pending = new HashMap<>(); // station id -> sources waiting for the owner (oldest first) with their last-seen time
    private final Map<IndexStore.StashKey, Integer> backFails = new HashMap<>();
    private final Map<IndexStore.StashKey, Long> backNext = new HashMap<>();
    private final java.util.Set<String> shownOnce = new java.util.HashSet<>();
    private boolean dirty;

    PearlEngine(PearlStore store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
        restart();
    }

    PearlEngine(PearlStore store) {
        this(store, System::currentTimeMillis);
    }

    boolean disabled() {
        return store.disabled();
    }

    long now() {
        return clock.getAsLong();
    }

    /** Trips that were open when the server stopped end: before the whisper `server_restarted`, after it `arrival_unconfirmed` (with an alert). */
    private void restart() {
        long now = now();
        for (PearlStore.Trip t : store.trips) {
            if (!t.open()) continue;
            if (isBefore(t.step, "load_sent")) {
                finish(t, "failed", "server_restarted", now);
            } else {
                finish(t, "failed", "arrival_unconfirmed", now);
                alert("arrival_unconfirmed", t, now);
            }
        }
        persist();
    }

    // ---------- helpers ----------

    private static boolean isBefore(String step, String than) {
        return PearlBodies.STEPS.indexOf(step) < PearlBodies.STEPS.indexOf(than);
    }

    private static String key(String res, String a, String b) {
        return res + ":" + a + "/" + b;
    }

    private static IndexStore.StashKey sk(String server, String stash) {
        return new IndexStore.StashKey(server, stash);
    }

    private PearlStore.Station station(String id) {
        for (PearlStore.Station s : store.stations) if (s.id.equals(id)) return s;
        return null;
    }

    private PearlStore.Station homeStation(String server, String stash) {
        for (PearlStore.Station s : store.stations) if (stash.equals(s.stash) && s.server.equals(server)) return s;
        return null;
    }

    private PearlStore.Trip openTrip(String server, String stash) {
        PearlStore.Trip found = null;
        for (PearlStore.Trip t : store.trips) if (t.open() && t.server.equals(server) && t.stash.equals(stash)) found = t;
        return found;
    }

    private PearlStore.Trip trip(String id) {
        for (PearlStore.Trip t : store.trips) if (t.id.equals(id)) return t;
        return null;
    }

    private Traveller travellerByPlayer(String player) {
        for (Traveller t : travellers.values()) if (t.supports && t.player != null && t.player.equalsIgnoreCase(player)) return t;
        return null;
    }

    private void persist() {
        if (!dirty) return;
        dirty = false;
        try {
            store.save();
        } catch (IOException e) {
            System.err.println("Could not save pearl travel: " + e.getMessage());
        }
    }

    private volatile java.util.function.Predicate<String> taskIdUsed = id -> false;

    /** Whether an id is already a task id of the task service (the t- space is shared, FI-13); must not take the engine's lock. */
    void taskIds(java.util.function.Predicate<String> used) {
        this.taskIdUsed = used;
    }

    /** Whether an id is a trip id or one of a trip's child ids. */
    synchronized boolean tripIdUsed(String id) {
        for (PearlStore.Trip t : store.trips) {
            if (id.equals(t.id) || id.equals(t.childLoad) || id.equals(t.childRearm)) return true;
        }
        return false;
    }

    String newId(String prefix, int hex) {
        while (true) {
            byte[] b = new byte[hex / 2];
            RANDOM.nextBytes(b);
            String id = prefix + HexFormat.of().formatHex(b);
            if (prefix.equals("t-") && (taskIdUsed.test(id) || tripIdUsed(id))) continue;
            return id;
        }
    }

    private void alert(String kind, PearlStore.Trip t, long now) {
        for (Alert a : alerts) if (a.kind().equals(kind) && t != null && java.util.Objects.equals(a.trip(), t.id)) return;
        alerts.add(new Alert(kind, t == null ? null : t.stash, t == null ? null : t.station, t == null ? null : t.id, now));
        while (alerts.size() > MAX_ALERTS) alerts.remove(0);
    }

    /** An alert about a stash (no trip), once. */
    private void stashAlert(String kind, String stash, long now) {
        for (Alert a : alerts) if (a.kind().equals(kind) && a.trip() == null && stash.equals(a.stash())) return;
        alerts.add(new Alert(kind, stash, null, null, now));
        while (alerts.size() > MAX_ALERTS) alerts.remove(0);
    }

    private boolean hasAlert(String stash) {
        for (Alert a : alerts) if (stash.equals(a.stash())) return true;
        return false;
    }

    /** Whether the stash's newest trip completed (go or back). */
    private boolean lastTripCompleted(String server, String stash) {
        PearlStore.Trip last = null;
        for (PearlStore.Trip t : store.trips) if (t.server.equals(server) && t.stash.equals(stash)) last = t;
        return last != null && last.state.equals("completed");
    }

    /** The loader's report only when its source is the one the owner approved for the station; a pending source is display only. */
    private Loader loaderFor(PearlStore.Station st) {
        Loader ld = loaders.get(st.loaderPlayer.toLowerCase(Locale.ROOT));
        return ld != null && st.binding != null && st.binding.equals(ld.source) ? ld : null;
    }

    /** Whether the owner approved this name at the station: assigned, listed, or the traveller of a stash with a home station. */
    private boolean approvedFor(PearlStore.Station st, String player, String server, String stash) {
        String n = player.toLowerCase(Locale.ROOT);
        return st.assign.containsKey(n) || st.allowed.contains(n) || homeStation(server, stash) != null;
    }

    private boolean approvedName(String player) {
        String n = player.toLowerCase(Locale.ROOT);
        for (PearlStore.Station s : store.stations) if (s.assign.containsKey(n) || s.allowed.contains(n)) return true;
        for (Traveller t : travellers.values()) {
            if (t.player != null && t.player.equalsIgnoreCase(player) && homeStation(t.server, t.stash) != null) return true;
        }
        return false;
    }

    // ---------- chambers ----------

    /** The chamber T's trips use at a station (assigned, else the only one it owns); null when there is none. */
    private String chamberFor(PearlStore.Station st, String player) {
        String assigned = st.assign.get(player.toLowerCase(Locale.ROOT));
        if (assigned != null) return assigned;
        Loader ld = loaderFor(st);
        if (ld == null) return null;
        String only = null;
        int n = 0;
        for (Map.Entry<String, JsonObject> e : ld.chambers.entrySet()) {
            JsonObject c = e.getValue();
            if (c.get("ownerKnown").getAsBoolean() && c.get("owner").getAsString().equalsIgnoreCase(player)) {
                only = e.getKey();
                n++;
            }
        }
        return n == 1 ? only : null;
    }

    /** The server's view of one chamber (contract "Chamber state"); null chamber id or no report gives unknown. */
    private String chamberState(PearlStore.Station st, String chamber, long now) {
        Loader ld = loaderFor(st);
        if (ld == null || chamber == null) return "unknown";
        JsonObject c = ld.chambers.get(chamber);
        if (c == null) return "unknown";
        String presence = c.get("presence").getAsString();
        Long forced = forcedEmpty.get(st.id + "/" + chamber);
        if (forced != null && forced >= ld.receivedAt) presence = "empty";
        String ownerGame = null;
        if (c.get("ownerKnown").getAsBoolean()) {
            Traveller t = travellerByPlayer(c.get("owner").getAsString());
            if (t != null) ownerGame = t.game;
        }
        return PearlLogic.chamberState(presence, now - ld.receivedAt, st.broken.contains(chamber), ownerGame);
    }

    // ---------- the loader's report ----------

    /**
     * A loader's report. transportPlayer is the player of the transport (the plugin's bot/hello, or the heartbeat's own player);
     * it must be the report's player (409 otherwise). Answers {allow, intervalMs}.
     */
    synchronized JsonObject onLoader(String transportPlayer, String source, JsonObject raw) {
        JsonObject body;
        try {
            body = PearlBodies.loader(raw);
        } catch (IllegalArgumentException e) {
            throw new Refused(400, e.getMessage());
        }
        long now = now();
        String player = body.get("player").getAsString();
        if (transportPlayer == null || !transportPlayer.equalsIgnoreCase(player)) throw new Refused(409, "the report's player isn't this bot's player");
        if (source == null || source.isEmpty() || source.length() > 300) throw new Refused(400, "the report has no source");
        // A station's loader is bound to the first source that reported for it; another source gets 409 (the owner resets it by editing the station).
        for (PearlStore.Station s : store.stations) {
            if (s.loaderPlayer.equalsIgnoreCase(player) && s.binding != null && !s.binding.equals(source)) {
                addPending(s.id, source, now); // the owner may approve the new source on the station
                throw new Refused(409, "this loader is bound to another source; the owner can approve the new source on the station");
            }
        }
        long skew = now - body.get("at").getAsLong();
        Loader ld = new Loader();
        ld.player = player;
        ld.receivedAt = now;
        ld.drop = body.get("dropPearlAfterLoad").getAsBoolean();
        ld.temporaryMode = body.get("temporaryMode").getAsBoolean();
        ld.busy = body.get("busy").getAsBoolean();
        ld.playerNear = body.get("playerNear").getAsBoolean();
        ld.source = source;
        for (JsonElement e : body.getAsJsonArray("chambers")) {
            JsonObject c = e.getAsJsonObject();
            ld.chambers.put(c.get("id").getAsString(), c);
        }
        if (body.get("load") instanceof JsonObject l) {
            JsonObject n = l.deepCopy();
            for (String f : new String[]{"at", "pearlGoneAt", "requesterNearAt"}) {
                if (n.has(f) && !n.get(f).isJsonNull()) n.addProperty(f, n.get(f).getAsLong() + skew);
            }
            if (!n.has("at") || n.get("at").isJsonNull()) n.addProperty("at", now); // a load without a time counts as now
            ld.load = n;
        }
        String lower = player.toLowerCase(Locale.ROOT);
        loaders.remove(lower);
        loaders.put(lower, ld);
        boolean isStationLoader = false;
        for (PearlStore.Station s : store.stations) {
            if (!s.loaderPlayer.equalsIgnoreCase(player)) continue;
            isStationLoader = true;
            if (s.binding == null) addPending(s.id, source, now); // display only until the owner clicks "This is my loader"
            // An assignment whose chamber now shows another owner is dropped.
            if (s.assign.entrySet().removeIf(e -> {
                JsonObject c = ld.chambers.get(e.getValue());
                return c != null && c.get("ownerKnown").getAsBoolean() && !c.get("owner").getAsString().equalsIgnoreCase(e.getKey());
            })) dirty = true;
        }
        // Reports of players that are no station's loader: at most 16, newest kept.
        if (!isStationLoader) {
            List<String> strangers = new ArrayList<>();
            for (String k : loaders.keySet()) {
                boolean station = false;
                for (PearlStore.Station s : store.stations) if (s.loaderPlayer.equalsIgnoreCase(k)) station = true;
                if (!station) strangers.add(k);
            }
            while (strangers.size() > MAX_NON_LOADERS) loaders.remove(strangers.remove(0));
        }
        // A new report replaces a forced "empty" (after a failed re-arm) and may move trips on.
        forcedEmpty.entrySet().removeIf(e -> e.getValue() < now);
        for (PearlStore.Trip t : new ArrayList<>(store.trips)) {
            if (!t.open()) continue;
            PearlStore.Station st = station(t.station);
            if (st != null && st.loaderPlayer.equalsIgnoreCase(player)) advance(t, now);
        }
        sweep(now);
        persist();
        JsonObject out = new JsonObject();
        boolean trusted = false;
        for (PearlStore.Station s : store.stations) if (s.loaderPlayer.equalsIgnoreCase(player) && source.equals(s.binding)) trusted = true;
        out.add("allow", trusted ? allow(player) : new JsonArray());
        out.addProperty("intervalMs", REPORT_INTERVAL_MS);
        return out;
    }

    /**
     * The players this loader may load for: the names the owner approved at its stations (assigned or listed) and the traveller of a
     * stash that has a home station. Each carries the uuid the owner pinned (null when none); a heartbeat can't add or change an entry.
     */
    private JsonArray allow(String loaderPlayer) {
        Map<String, String> names = new TreeMap<>();
        for (PearlStore.Station s : store.stations) {
            if (s.loaderPlayer.equalsIgnoreCase(loaderPlayer)) {
                for (String n : s.assign.keySet()) names.putIfAbsent(n, store.pins.get(n));
                for (String n : s.allowed) names.putIfAbsent(n, store.pins.get(n));
            }
            if (s.stash == null) continue;
            for (Traveller t : travellers.values()) {
                if (t.supports && t.player != null && s.stash.equals(t.stash) && s.server.equals(t.server)) {
                    String n = t.player.toLowerCase(Locale.ROOT);
                    names.putIfAbsent(n, store.pins.get(n));
                }
            }
        }
        JsonArray a = new JsonArray();
        names.forEach((n, u) -> {
            JsonObject o = new JsonObject();
            o.addProperty("name", n);
            o.addProperty("uuid", u);
            a.add(o);
        });
        return a;
    }

    // ---------- the traveller's heartbeat ----------

    /**
     * The traveller's heartbeat. hasKey says the body had a pearlTravel key (tp null = cleared). pendingOrders is the stash's pending
     * order count. Answers the reply fields (pearlTrip, pearlAwayClear), possibly empty.
     */
    synchronized JsonObject onTraveller(String server, String stash, String player, boolean hasKey, JsonObject raw, int pendingOrders) {
        JsonObject reply = new JsonObject();
        long now = now();
        IndexStore.StashKey k = sk(server, stash);
        Traveller tr = travellers.get(k);
        JsonObject tp = null;
        if (hasKey) {
            if (raw != null) {
                try {
                    tp = PearlBodies.travel(raw);
                } catch (IllegalArgumentException e) {
                    throw new Refused(400, e.getMessage());
                }
            }
            if (tr == null) {
                tr = new Traveller();
                travellers.put(k, tr);
            }
            tr.server = server;
            tr.stash = stash;
            tr.player = player;
            if (tp == null) {
                tr.supports = false;
                tr.awayStation = null;
                tr.awayTrip = null;
            } else {
                long skew = now - tp.get("at").getAsLong();
                tr.supports = true;
                tr.enabled = tp.get("enabled").getAsBoolean();
                tr.receivedAt = now;
                tr.skew = skew;
                pin(tr, player, tp, now);
                tr.game = tp.get("game").getAsString();
                tr.client = tp.get("client").getAsString();
                tr.joinedAt = tp.get("joinedAt").isJsonNull() ? 0 : tp.get("joinedAt").getAsLong() + skew;
                tr.carried = tp.getAsJsonObject("pearls").get("carried").getAsInt();
                if (tp.get("away") instanceof JsonObject a) {
                    tr.awayStation = a.get("station").getAsString();
                    tr.awayTrip = a.get("trip").isJsonNull() ? null : a.get("trip").getAsString();
                    tr.awaySince = a.get("since").isJsonNull() ? now : a.get("since").getAsLong() + skew;
                } else {
                    tr.awayStation = null;
                    tr.awayTrip = null;
                    tr.awayClearPending = false;
                }
            }
        } else if (tr != null) {
            tr.receivedAt = now;
            tr.player = player;
        }
        if (tr != null && tr.supports && tp != null) {
            applyLocal(tr, tp, now);
            PearlStore.Trip t = openTrip(server, stash);
            if (t != null && tp.get("trip") instanceof JsonObject rep) applyReport(t, rep, tr, now);
            else if (t == null && tp.get("trip") instanceof JsonObject late) lateReport(late, server, stash, now);
            // The traveller away with an order waiting: bring it home when a return pearl is armed, else tell the owner.
            if (pendingOrders > 0) orderWhileAway(tr, t, now);
            t = openTrip(server, stash);
            if (t != null) {
                t.ordersWait = pendingOrders > 0 && t.action.equals("go") && !t.origin.equals("system");
                t.lastBeatAt = now;
                advance(t, now);
            }
        }
        sweep(now);
        if (tr != null && tr.supports) {
            PearlStore.Trip shown = latestToShow(server, stash);
            if (shown != null && !shown.open()) {
                // The final notice stays until the traveller's own heartbeat no longer carries the trip.
                boolean carried = tp != null && tp.get("trip") instanceof JsonObject c && c.get("id").getAsString().equals(shown.id);
                if (shownOnce.contains(shown.id) && (!hasKey || (tp != null && !carried))) {
                    shown.notified = true;
                    shownOnce.remove(shown.id);
                    shown = null;
                    dirty = true;
                } else {
                    shownOnce.add(shown.id);
                }
            }
            if (shown != null) reply.add("pearlTrip", tripReply(shown, tr));
            if (tr.awayClearPending) {
                // Repeated until the traveller's heartbeat reports away: null (cleared above); old plugins never do, so it is bounded.
                if (now - tr.awayClearAt > AWAY_CLEAR_MAX_MS) {
                    tr.awayClearPending = false;
                    audits.add(new String[]{"pearl.away.clear", tr.stash + " (no acknowledgement after 10 minutes, stopped repeating pearlAwayClear)"});
                } else {
                    reply.addProperty("pearlAwayClear", true);
                }
            }
        }
        persist();
        return reply;
    }

    private void pin(Traveller tr, String player, JsonObject tp, long now) {
        if (tp.get("uuid").isJsonNull()) return;
        String u = tp.get("uuid").getAsString().toLowerCase(Locale.ROOT);
        String lower = player.toLowerCase(Locale.ROOT);
        String pinned = store.pins.get(lower);
        tr.uuid = u; // what the bot last said: only a suggestion for the owner; the owner pins the uuid
        if (pinned == null || pinned.equals(u)) {
            tr.uuidMismatch = false;
        } else {
            tr.uuidMismatch = true; // ignored; the owner sets the new uuid when the player really changed
            stashAlert("uuid_mismatch", tr.stash, now);
        }
    }

    /** A report about a trip that already failed: for 5 minutes a teleport or an unexpected location still counts (the pull came late). */
    private void lateReport(JsonObject rep, String server, String stash, long now) {
        PearlStore.Trip t = trip(rep.get("id").getAsString());
        if (t == null || !t.server.equals(server) || !t.stash.equals(stash) || !t.state.equals("failed") || t.hold) return;
        if (!java.util.Set.of("pull_timeout", "loader_no_answer", "load_aborted").contains(t.errorCode)) return;
        if (now - t.finishedAt > LATE_MS) return;
        boolean teleported = !rep.get("teleportAt").isJsonNull();
        boolean error = !rep.get("error").isJsonNull() && rep.get("error").getAsString().equals("unexpected_location");
        if (!teleported && !error) return;
        t.hold = true;
        t.errorCode = "unexpected_location";
        t.notified = false;
        t.updatedAt = now;
        alert("unexpected_location", t, now);
        dirty = true;
    }

    private PearlStore.Trip latestToShow(String server, String stash) {
        PearlStore.Trip open = openTrip(server, stash);
        if (open != null) return open;
        PearlStore.Trip last = null;
        for (PearlStore.Trip t : store.trips) if (t.server.equals(server) && t.stash.equals(stash)) last = t;
        return last != null && !last.notified ? last : null;
    }

    private void applyLocal(Traveller tr, JsonObject tp, long now) {
        if (!(tp.get("local") instanceof JsonObject l)) return;
        String action = l.get("action").getAsString();
        String id = l.get("localId").isJsonNull() ? null : l.get("localId").getAsString();
        if (id != null) {
            if (tr.localIds.contains(id)) return;
            tr.localIds.addLast(id);
            while (tr.localIds.size() > 20) tr.localIds.removeFirst();
        }
        String origin = tr.client;
        try {
            if (action.equals("home")) {
                // Honoured only when the server also sees the bot away with no trip and nothing the owner must look at; it clears only away_order.
                // Key-holder trust: only after a completed trip, with nothing open and no alert for the stash.
                if (tr.awayStation == null || openTrip(tr.server, tr.stash) != null || hasAlert(tr.stash) || !lastTripCompleted(tr.server, tr.stash)) return;
                doClearAway(tr.server, tr.stash, now, false);
                audits.add(new String[]{"pearl.away.clear", tr.stash + " (" + origin + ")"});
            } else {
                String station = l.get("station").isJsonNull() ? null : l.get("station").getAsString();
                PearlStore.Trip made = createTrip(tr.server, tr.stash, action, station, l.get("oneWay").getAsBoolean(), origin, null, null, id, now);
                audits.add(new String[]{"pearl.trip", made.id + ": " + action + " " + tr.stash + " (" + origin + ")"});
            }
        } catch (Refused e) {
            // a local request that can't run is simply not started; the traveller sees no trip
        }
    }

    /** Audit entries (action, text) made by the engine itself (in-game commands); the routes drain them into the audit log. */
    final List<String[]> audits = new ArrayList<>();

    synchronized List<String[]> drainAudits() {
        List<String[]> out = new ArrayList<>(audits);
        audits.clear();
        return out;
    }

    private void orderWhileAway(Traveller tr, PearlStore.Trip open, long now) {
        boolean away = tr.awayStation != null || (open != null && !isBefore(open.step, "load_sent"));
        // A trip still before the pull waits (busy_order, not counted); only the way back is ordered by an order.
        if (!away || open != null) return;
        IndexStore.StashKey k = sk(tr.server, tr.stash);
        if (backFails.getOrDefault(k, 0) < BACK_FAILS) {
            if (now < backNext.getOrDefault(k, 0L)) return;
            PearlStore.Station home = homeStation(tr.server, tr.stash);
            if (home != null && !store.disabled()) {
                String chamber = chamberFor(home, tr.player);
                if (chamber != null && chamberState(home, chamber, now).equals("armed")) {
                    try {
                        createTrip(tr.server, tr.stash, "back", null, false, "system", null, null, null, now);
                        return;
                    } catch (Refused e) {
                        // fall through to the alert
                    }
                }
            }
        }
        boolean have = false;
        for (Alert a : alerts) if (a.kind().equals("away_order") && tr.stash.equals(a.stash())) have = true;
        if (!have) alerts.add(new Alert("away_order", tr.stash, tr.awayStation, null, now));
    }

    // ---------- applying the traveller's trip report ----------

    private void applyReport(PearlStore.Trip t, JsonObject rep, Traveller tr, long now) {
        if (!rep.get("id").getAsString().equals(t.id)) return;
        if (t.state.equals("pending")) {
            t.state = "running";
            t.phase = "checking";
            dirty = true;
        }
        if (t.state.equals("paused") && "bot_offline".equals(t.pauseReason)) {
            t.state = "running";
            t.pauseReason = null;
            t.pausedSince = 0;
            dirty = true;
        }
        String step = rep.get("step").getAsString();
        String error = rep.get("error").isJsonNull() ? null : rep.get("error").getAsString();
        String pause = rep.get("pause").isJsonNull() ? null : rep.get("pause").getAsString();
        // The traveller's own refusal before the whisper ends the trip with its code.
        if (error != null && isBefore(t.step, "load_sent")) {
            finish(t, "failed", error, now);
            return;
        }
        if (isBefore(t.step, "load_sent") && pause != null && !t.go) {
            if (!t.state.equals("paused") || !"bot".equals(t.pauseBy)) {
                t.state = "paused";
                t.pauseReason = pause;
                t.pauseBy = "bot";
                if (t.pausedSince == 0) t.pausedSince = now;
                dirty = true;
            }
            return;
        }
        if (t.state.equals("paused") && "bot".equals(t.pauseBy) && pause == null) {
            t.state = "running";
            t.pauseReason = null;
            t.pausedSince = 0;
            dirty = true;
        }
        int idx = PearlBodies.STEPS.indexOf(step);
        int cur = PearlBodies.STEPS.indexOf(t.step);
        if (t.before == null && idx >= PearlBodies.STEPS.indexOf("ready")) {
            t.before = tr.carried;
            dirty = true;
        }
        if (rep.get("pearls") instanceof JsonObject p && !p.get("after").isJsonNull()) t.after = p.get("after").getAsInt();
        if (idx >= PearlBodies.STEPS.indexOf("ready") && isBefore(t.step, "ready") && !t.go) {
            t.readyReq = true; // the traveller says its own precheck passed: the server's turn
            dirty = true;
        } else if (t.step.equals("arm_home") && idx >= PearlBodies.STEPS.indexOf("ready")) {
            t.readyReq = true;
        }
        // The whisper: only after the server said go.
        if (t.go && (step.equals("load_sent") || step.equals("awaiting_pull")) && cur <= PearlBodies.STEPS.indexOf("ready") + 0
            && !rep.get("loadSentAt").isJsonNull()) {
            long sent = Math.min(now, rep.get("loadSentAt").getAsLong() + tr.skew);
            t.loadSentAt = sent;
            t.whispers = Math.max(t.whispers, rep.get("whispers").getAsInt());
            t.step = "awaiting_pull";
            t.phase = "waiting for the pull";
            t.pullDeadline = PearlLogic.pullDeadline(sent, null);
            t.loadingAt = 0;
            t.armHome = false;
            loads.computeIfAbsent(t.station, x -> new ArrayDeque<>()).addLast(now);
            dirty = true;
        } else if (t.step.equals("awaiting_pull") && rep.get("whispers").getAsInt() > t.whispers && !rep.get("loadSentAt").isJsonNull()) {
            // the retry whisper
            t.whispers = rep.get("whispers").getAsInt();
            t.loadSentAt = Math.min(now, rep.get("loadSentAt").getAsLong() + tr.skew);
            t.minLoadAt = t.loadSentAt; // an earlier (aborted) load can't match this whisper
            t.retry = false;
            t.pullDeadline = PearlLogic.pullDeadline(t.loadSentAt, null);
            t.loadingAt = 0;
            loads.computeIfAbsent(t.station, x -> new ArrayDeque<>()).addLast(now);
            dirty = true;
        }
        if (t.step.equals("awaiting_pull") && !rep.get("teleportAt").isJsonNull() && t.teleportAt == 0) {
            t.teleportAt = rep.get("teleportAt").getAsLong() + tr.skew;
            t.teleportDim = rep.get("teleportDim").isJsonNull() ? null : rep.get("teleportDim").getAsString();
            t.step = "arrived";
            t.arrivedAt = now;
            t.phase = "arrived, waiting for the loader";
            dirty = true;
        }
        if (!rep.get("rearm").isJsonNull() && (t.step.equals("pickup") || t.step.equals("rearm")) && t.rearmResult == null) {
            t.rearmResult = rep.getAsJsonObject("rearm").get("result").getAsString();
            t.rearmAt = now;
            t.step = "rearm";
            t.phase = "checking the re-armed pearl";
            dirty = true;
        } else if ((t.step.equals("pickup")) && idx >= PearlBodies.STEPS.indexOf("rearm")) {
            t.step = "rearm";
            dirty = true;
        }
    }

    // ---------- advancing a trip ----------

    private enum Kind { OK, ARM_HOME, PAUSE, FAIL }

    private record Result(Kind kind, String code, boolean counted) {
        static Result ok() {
            return new Result(Kind.OK, null, false);
        }

        static Result fail(String code) {
            return new Result(Kind.FAIL, code, false);
        }

        static Result pause(String code) {
            return new Result(Kind.PAUSE, code, true);
        }
    }

    private void advance(PearlStore.Trip t, long now) {
        if (!t.open()) return;
        PearlStore.Station st = station(t.station);
        if (st == null) {
            cancel(t, "stopped", now);
            return;
        }
        Traveller tr = travellers.get(sk(t.server, t.stash));
        Loader ld = loaderFor(st);
        if (t.state.equals("paused") && t.pausedSince > 0 && !"bot_offline".equals(t.pauseReason) && now - t.pausedSince > PearlLogic.PAUSE_MAX_MS) {
            cancel(t, t.pauseReason, now);
            return;
        }
        switch (t.step) {
            case "precheck", "arm_home", "ready" -> {
                if (t.go) {
                    if (t.goAt > 0 && now - t.goAt > GO_TIMEOUT_MS) finish(t, "failed", "go_timeout", now);
                    return;
                }
                if (t.step.equals("arm_home") && now - t.armHomeAt > 90_000) {
                    finish(t, "failed", "no_return_pearl", now);
                    return;
                }
                if (t.ordersWait) {
                    if (!t.state.equals("paused") || !"orders".equals(t.pauseBy)) {
                        t.state = "paused";
                        t.pauseReason = "busy_order";
                        t.pauseBy = "orders";
                        if (t.pausedSince == 0) t.pausedSince = now;
                        t.phase = "waiting: busy_order";
                        dirty = true;
                    }
                    return;
                } else if (t.state.equals("paused") && "orders".equals(t.pauseBy)) {
                    t.state = "running";
                    t.pauseReason = null;
                    t.pausedSince = 0;
                    t.nextTryAt = 0;
                    dirty = true;
                }
                if (!t.readyReq) return;
                if (t.state.equals("paused") && "server".equals(t.pauseBy) && now < t.nextTryAt) return;
                Result r = precheck(t, st, tr, ld, now);
                switch (r.kind()) {
                    case OK -> {
                        takeLocks(t, st);
                        t.go = true;
                        t.goAt = now;
                        t.readyReq = false;
                        t.step = "ready";
                        t.state = "running";
                        t.pauseReason = null;
                        t.pausedSince = 0;
                        t.tries = 0;
                        t.phase = "go";
                        if (tr != null && t.before == null) t.before = tr.carried;
                        dirty = true;
                    }
                    case ARM_HOME -> {
                        t.step = "arm_home";
                        t.armHome = true;
                        t.armedHome = true;
                        t.armHomeAt = now;
                        t.readyReq = false;
                        t.phase = "arming the return pearl";
                        dirty = true;
                    }
                    case PAUSE -> {
                        t.state = "paused";
                        t.pauseReason = r.code();
                        t.pauseBy = "server";
                        if (t.pausedSince == 0) t.pausedSince = now;
                        t.tries++;
                        t.nextTryAt = now + PearlLogic.RETRY_MS;
                        t.phase = "waiting: " + r.code();
                        dirty = true;
                        if (t.tries >= PearlLogic.MAX_TRIES) finish(t, "failed", r.code(), now);
                    }
                    case FAIL -> finish(t, "failed", r.code(), now);
                }
            }
            case "load_sent", "awaiting_pull" -> advanceWaiting(t, st, tr, ld, now);
            case "arrived" -> advanceArrived(t, st, ld, now);
            case "pickup", "rearm" -> advanceRearm(t, st, ld, tr, now);
            default -> {
            }
        }
    }

    private Result precheck(PearlStore.Trip t, PearlStore.Station st, Traveller tr, Loader ld, long now) {
        if (store.disabled()) return Result.fail("stopped");
        if (tr == null || !tr.supports || now - tr.receivedAt > 60_000) return Result.fail("bot_offline");
        if (ld == null || now - ld.receivedAt > PearlLogic.FRESH_MS) return Result.pause("loader_stale");
        if (ld.temporaryMode) return Result.fail("loader_misconfigured");
        if (tr.game.equals("queue")) return Result.fail("in_queue");
        if (!tr.game.equals("in")) return Result.fail("not_ingame");
        if (tr.joinedAt > 0 && now - tr.joinedAt < PearlLogic.JOIN_SETTLE_MS) return Result.pause("just_joined");
        if (tr.carried < 2) return Result.fail("low_pearls");
        String chamber = chamberFor(st, t.player);
        if (chamber == null) return Result.fail("no_pearl_assigned");
        if (tr.uuidMismatch) return Result.fail("uuid_mismatch");
        if (!store.pins.containsKey(t.player.toLowerCase(Locale.ROOT))) return Result.fail("uuid_unpinned");
        if (!approvedFor(st, t.player, t.server, t.stash)) return Result.fail("no_pearl_assigned");
        if (Boolean.FALSE.equals(ownedBy(st, chamber, t.player))) return Result.fail("no_pearl_assigned");
        t.chamber = chamber;
        switch (chamberState(st, chamber, now)) {
            case "armed" -> {
            }
            case "empty" -> {
                return Result.fail("chamber_empty");
            }
            case "owner_offline" -> {
                return Result.pause("owner_offline");
            }
            case "broken" -> {
                return Result.fail("chamber_broken");
            }
            default -> {
                return Result.fail("chamber_unknown");
            }
        }
        if (tr.joinedAt > 0 && ld.receivedAt <= tr.joinedAt) return Result.pause("just_joined");
        if (ld.playerNear) return Result.pause("loader_player_near");
        if (ld.busy) return Result.pause("loader_busy");
        String lc = locks.get(key("chamber", st.id, chamber));
        if (lc != null && !lc.equals(t.id)) return Result.pause("chamber_locked");
        String ll = locks.get("loader:" + st.id);
        if (ll != null && !ll.equals(t.id)) return Result.pause("loader_locked");
        ArrayDeque<Long> q = loads.get(st.id);
        if (q != null) {
            while (!q.isEmpty() && now - q.peekFirst() > 3_600_000L) q.pollFirst();
            if (q.size() >= PearlLogic.LOADS_PER_HOUR) return Result.fail("station_limit");
        }
        t.dropAfterLoad = ld.drop;
        if (t.action.equals("go") && !t.oneWay) {
            PearlStore.Station home = homeStation(t.server, t.stash);
            if (home == null || home == st) return Result.fail("no_return_pearl");
            String hc = chamberFor(home, t.player);
            if (hc == null) return Result.fail("no_return_pearl");
            if (Boolean.FALSE.equals(ownedBy(home, hc, t.player))) return Result.fail("no_pearl_assigned");
            String hs = chamberState(home, hc, now);
            if (!hs.equals("armed")) {
                if (hs.equals("empty") && tr.carried >= 3 && !t.armedHome) return new Result(Kind.ARM_HOME, null, false);
                if (!t.armedHome || now - t.armHomeAt > 90_000) return Result.fail("no_return_pearl");
                return Result.pause("loader_stale");
            }
        }
        return Result.ok();
    }

    /** Whether the loader's report shows the chamber's pearl as this player's (known owner equal); null when the chamber isn't reported. */
    private Boolean ownedBy(PearlStore.Station st, String chamber, String player) {
        Loader ld = loaderFor(st);
        JsonObject c = ld == null || chamber == null ? null : ld.chambers.get(chamber);
        if (c == null) return null;
        return c.get("ownerKnown").getAsBoolean() && c.get("owner").getAsString().equalsIgnoreCase(player);
    }

    private void takeLocks(PearlStore.Trip t, PearlStore.Station st) {
        locks.put(key("chamber", st.id, t.chamber), t.id);
        locks.put("loader:" + st.id, t.id);
        t.locks = true;
    }

    private void releaseLocks(PearlStore.Trip t) {
        locks.values().removeIf(id -> id.equals(t.id));
        t.locks = false;
    }

    private void advanceWaiting(PearlStore.Trip t, PearlStore.Station st, Traveller tr, Loader ld, long now) {
        JsonObject load = ld != null ? matchingLoad(t, ld) : null;
        if (load != null) {
            String state = load.get("state").getAsString();
            if (state.equals("loading") && !load.get("at").isJsonNull()) {
                t.loadingAt = Math.max(t.loadingAt, load.get("at").getAsLong());
                t.pullDeadline = PearlLogic.pullDeadline(t.loadSentAt, t.loadingAt);
            }
            if (state.equals("aborted")) {
                String reason = load.get("reason").isJsonNull() ? "" : load.get("reason").getAsString();
                if (reason.equals("path_failed") && !t.pathCounted) {
                    t.pathCounted = true;
                    int n = pathFails.merge(st.id + "/" + t.chamber, 1, Integer::sum);
                    if (n >= 2) markBroken(st, t, now);
                }
                boolean armed = load.get("pearlPresent").getAsBoolean();
                if (armed && t.whispers < 2 && !t.retry && !t.retried && t.teleportAt == 0) {
                    t.retry = true;
                    t.retried = true;
                    dirty = true;
                } else if (!t.retry) {
                    finish(t, "failed", "load_aborted", now);
                    return;
                }
            }
            if (state.equals("loaded") && !t.suspectCounted && ld.receivedAt > t.loadSentAt + 5_000 && t.teleportAt == 0
                && load.get("pearlPresent").getAsBoolean() && tr != null && tr.game.equals("in")) {
                t.suspectCounted = true;
                int n = suspect.merge(st.id + "/" + t.chamber, 1, Integer::sum);
                if (n >= 2) markBroken(st, t, now);
            }
        }
        if (load == null && t.loadSentAt > 0 && now - t.loadSentAt > PearlLogic.LOAD_ANSWER_MS && !t.retry) {
            finish(t, "failed", "loader_no_answer", now);
            return;
        }
        if (t.pullDeadline > 0 && now > t.pullDeadline + PearlLogic.CONFIRM_MS) {
            finish(t, "failed", "pull_timeout", now);
        }
    }

    private void markBroken(PearlStore.Station st, PearlStore.Trip t, long now) {
        if (st.broken.add(t.chamber)) {
            alerts.add(new Alert("chamber_broken", t.stash, st.id, t.id, now));
            dirty = true;
        }
    }

    /** The loader's load entry for this trip (its requester and pearl, not older than the whisper); null when there is none. */
    private JsonObject matchingLoad(PearlStore.Trip t, Loader ld) {
        JsonObject l = ld.load;
        if (l == null) return null;
        if (!l.get("requester").getAsString().equalsIgnoreCase(t.player) || !l.get("id").getAsString().equals(t.chamber)) return null;
        if (l.get("at").isJsonNull() || l.get("at").getAsLong() < Math.max(t.loadSentAt - 3_000, t.minLoadAt)) return null;
        return l;
    }

    private void advanceArrived(PearlStore.Trip t, PearlStore.Station st, Loader ld, long now) {
        if (t.teleportDim != null && !t.teleportDim.equals(st.dim)) {
            t.hold = true;
            finish(t, "failed", "unexpected_location", now);
            return;
        }
        JsonObject load = ld != null ? matchingLoad(t, ld) : null;
        Long gone = load == null || load.get("pearlGoneAt").isJsonNull() ? null : load.get("pearlGoneAt").getAsLong();
        Long near = load == null || load.get("requesterNearAt").isJsonNull() ? null : load.get("requesterNearAt").getAsLong();
        if (PearlLogic.arrivalConfirmed(t.loadSentAt, t.loadingAt == 0 ? null : t.loadingAt, t.teleportAt, gone, near, 0, 0)) {
            t.rearm = true;
            t.step = "pickup";
            t.phase = "picking up the dropped pearl";
            if (ld != null) t.dropAfterLoad = ld.drop;
            suspect.remove(st.id + "/" + t.chamber);
            pathFails.remove(st.id + "/" + t.chamber);
            dirty = true;
        } else if (now - t.arrivedAt > PearlLogic.CONFIRM_MS) {
            t.hold = true;
            finish(t, "failed", "unexpected_location", now);
        }
    }

    private void advanceRearm(PearlStore.Trip t, PearlStore.Station st, Loader ld, Traveller tr, long now) {
        if (t.rearmResult == null) {
            if (now - t.arrivedAt > 10 * 60_000L) complete(t, "rearm_unverified", now);
            return;
        }
        boolean ok = t.rearmResult.equals("SUCCESS") || t.rearmResult.equals("PARTIAL");
        if (!ok) {
            forcedEmpty.put(st.id + "/" + t.chamber, now);
            alert("rearm_failed", t, now);
            complete(t, "rearm_failed", now);
            return;
        }
        String presence = ld != null && ld.chambers.get(t.chamber) != null ? ld.chambers.get(t.chamber).get("presence").getAsString() : null;
        if ("armed".equals(presence) && ld.receivedAt >= t.rearmAt) complete(t, null, now);
        else if (now - t.rearmAt > PearlLogic.REARM_VERIFY_MS) complete(t, "rearm_unverified", now);
    }

    private void complete(PearlStore.Trip t, String warning, long now) {
        t.step = "done";
        if (warning == null && t.before != null && t.after != null && PearlLogic.countWarning(t.before, t.dropAfterLoad, t.armedHome, t.after)) {
            warning = "pearl_count";
        }
        t.warning = warning;
        finish(t, "completed", null, now);
    }

    private void finish(PearlStore.Trip t, String state, String code, long now) {
        t.state = state;
        t.errorCode = code;
        t.finishedAt = now;
        t.updatedAt = now;
        t.pauseReason = null;
        t.pausedSince = 0;
        t.phase = state.equals("completed") ? "done" : "ended";
        t.go = false;
        t.rearm = false;
        t.armHome = false;
        t.retry = false;
        IndexStore.StashKey bk = sk(t.server, t.stash);
        if (t.action.equals("back") && state.equals("completed")) {
            alerts.removeIf(a -> a.kind().equals("away_order") && t.stash.equals(a.stash()));
        }
        if (t.action.equals("back") && t.origin.equals("system")) {
            if (state.equals("completed")) {
                backFails.remove(bk);
                backNext.remove(bk);
            } else {
                int n = backFails.merge(bk, 1, Integer::sum);
                backNext.put(bk, now + (BACK_BACKOFF_MS << Math.min(n - 1, 10)));
                if (n >= BACK_FAILS) stashAlert("away_order", t.stash, now);
            }
        }
        if ("unexpected_location".equals(code)) {
            t.hold = true;
            alert("unexpected_location", t, now);
        }
        if ("arrival_unconfirmed".equals(code)) alert("arrival_unconfirmed", t, now);
        releaseLocks(t);
        lastEnd.put(sk(t.server, t.stash), now);
        dirty = true;
    }

    private void cancel(PearlStore.Trip t, String code, long now) {
        finish(t, "cancelled", code, now);
    }

    /** Housekeeping on every call: a traveller that stopped sending heartbeats. */
    private void sweep(long now) {
        loads.values().forEach(q -> {
            while (!q.isEmpty() && now - q.peekFirst() > 3_600_000L) q.pollFirst();
        });
        loads.values().removeIf(ArrayDeque::isEmpty);
        for (PearlStore.Trip t : new ArrayList<>(store.trips)) {
            if (!t.open()) continue;
            if (t.go && t.goAt > 0 && isBefore(t.step, "load_sent") && now - t.goAt > GO_TIMEOUT_MS) {
                finish(t, "failed", "go_timeout", now);
                continue;
            }
            Traveller tr = travellers.get(sk(t.server, t.stash));
            long last = tr == null ? t.lastBeatAt : tr.receivedAt;
            if (last == 0) last = t.createdAt;
            if (!isBefore(t.step, "load_sent")) {
                PearlStore.Station st = station(t.station);
                Loader ld = st == null ? null : loaderFor(st);
                if (now - Math.max(last, ld == null ? 0 : ld.receivedAt) > STUCK_MS) {
                    finish(t, "failed", "arrival_unconfirmed", now); // alerts
                    continue;
                }
            }
            if (now - last <= 10 * 60_000L) continue;
            if (isBefore(t.step, "load_sent")) {
                cancel(t, "bot_offline", now);
            } else if (!t.state.equals("paused")) {
                t.state = "paused";
                t.pauseReason = "bot_offline";
                t.pauseBy = "server";
                dirty = true;
            }
        }
        // An unanswered trip nobody took
        for (PearlStore.Trip t : new ArrayList<>(store.trips)) {
            if (t.state.equals("pending") && now - t.createdAt > 10 * 60_000L) cancel(t, "bot_offline", now);
        }
    }

    // ---------- the reply ----------

    private JsonObject tripReply(PearlStore.Trip t, Traveller tr) {
        PearlStore.Station st = station(t.station);
        JsonObject o = new JsonObject();
        o.addProperty("id", t.id);
        o.addProperty("action", t.action);
        o.addProperty("state", t.state);
        o.addProperty("step", t.step);
        o.addProperty("station", t.station);
        o.addProperty("dim", st == null ? null : st.dim);
        o.addProperty("loader", st == null ? null : st.loaderPlayer);
        o.addProperty("pearlId", t.chamber);
        o.addProperty("oneWay", t.oneWay);
        o.addProperty("go", t.go && t.open());
        o.addProperty("armHome", t.armHome && t.open());
        o.addProperty("retry", t.retry && t.open());
        o.addProperty("rearm", t.rearm && t.open());
        o.addProperty("hold", t.hold);
        o.addProperty("cancel", t.state.equals("cancelled") && isBefore(t.step, "load_sent"));
        o.addProperty("whispers", t.whispers);
        if (t.pullDeadline > 0 && t.open()) o.addProperty("pullDeadlineAt", t.pullDeadline - tr.skew);
        else o.add("pullDeadlineAt", JsonNull.INSTANCE);
        String code = t.errorCode != null ? t.errorCode : t.pauseReason;
        o.addProperty("code", code);
        return o;
    }

    static String codeText(String code) {
        return switch (code) {
            case "bot_offline" -> "The bot stopped sending heartbeats.";
            case "not_ingame" -> "The bot is not in the game.";
            case "in_queue" -> "The bot is in the queue.";
            case "just_joined" -> "The bot has only just joined; the chamber report is older than its join.";
            case "low_pearls" -> "The bot carries fewer than 2 pearls.";
            case "no_pearl_assigned" -> "No pearl of this bot is assigned at that station.";
            case "chamber_empty" -> "The chamber has no pearl (its owner is online).";
            case "owner_offline" -> "The pearl's owner is offline, so the pearl is not in the world.";
            case "chamber_unknown" -> "The loader can't see the chamber right now.";
            case "chamber_broken" -> "The chamber is marked broken; the owner must reset it.";
            case "chamber_locked" -> "Another trip holds the chamber.";
            case "loader_locked" -> "Another trip holds the loader.";
            case "loader_stale" -> "The loader hasn't reported in the last minute.";
            case "loader_misconfigured" -> "The loader has PearlPlus temporary mode on.";
            case "no_return_pearl" -> "There is no armed pearl for the way back.";
            case "station_limit" -> "The station has had 20 loads in the last hour.";
            case "cooldown" -> "The bot made a trip less than 5 minutes ago.";
            case "already_away" -> "The bot is already away from its stash.";
            case "already_open" -> "The bot already has an open trip.";
            case "player_near", "loader_player_near" -> "A player is too close.";
            case "loader_busy" -> "The loader is busy.";
            case "low_health", "low_food" -> "The bot is too hurt or hungry.";
            case "loader_no_answer" -> "The loader did not answer the load request.";
            case "load_aborted" -> "The loader gave up the load.";
            case "pull_timeout" -> "No pull arrived in time.";
            case "unexpected_location" -> "The bot arrived somewhere unexpected; it is held.";
            case "arrival_unconfirmed" -> "The arrival could not be confirmed.";
            case "stopped" -> "The trip was stopped.";
            case "order_arrived" -> "An order arrived for the stash.";
            case "busy_order" -> "An order is waiting for the stash.";
            case "go_timeout" -> "The bot did not start the pull within 2 minutes of the go.";
            case "uuid_unpinned" -> "The owner has not confirmed this bot's uuid on the website.";
            case "uuid_mismatch" -> "The bot's uuid differs from the one pinned by the owner.";
            case "server_restarted" -> "The server restarted during the trip.";
            default -> "The trip ended (" + code + ").";
        };
    }

    // ---------- requests from the website and the bots ----------

    /** Creates a trip (website, a local command or the automatic return). Throws Refused with the status. */
    synchronized PearlStore.Trip createTrip(String server, String stash, String action, String stationId, boolean oneWay, String origin,
                                            String userId, String userName, String localId, long now) {
        if (store.disabled()) throw new Refused(503, "pearl travel is off (pearl.json can't be read)");
        Traveller tr = travellers.get(sk(server, stash));
        if (tr == null || !tr.supports || !tr.enabled) throw new Refused(409, "this bot can't travel by pearl");
        if (tr.uuidMismatch) throw new Refused(409, "uuid_mismatch");
        PearlStore.Station st;
        if (action.equals("back")) {
            st = homeStation(server, stash);
            if (st == null) throw new Refused(409, "this stash has no home station");
            if (tr.awayStation == null) throw new Refused(409, "the bot is not away");
        } else {
            if (stationId == null) throw new Refused(400, "station is required");
            st = station(stationId);
            if (st == null) throw new Refused(404, "no such station");
            if (!st.server.equals(server)) throw new Refused(400, "the station and the stash are on different servers");
            if (tr.awayStation != null) throw new Refused(409, "already_away");
        }
        if (openTrip(server, stash) != null) throw new Refused(409, "already_open");
        if (!action.equals("back")) {
            Long end = lastEnd.get(sk(server, stash));
            if (end != null && now - end < PearlLogic.COOLDOWN_MS) throw new Refused(409, "cooldown");
        }
        PearlStore.Trip t = new PearlStore.Trip();
        t.id = newId("t-", 12);
        t.childLoad = newId("t-", 12);
        t.childRearm = newId("t-", 12);
        t.action = action;
        t.origin = origin;
        boolean system = origin.equals("system");
        t.priority = system ? 400 : 200;
        t.band = system ? "high" : "normal";
        t.server = server;
        t.stash = stash;
        t.botId = null;
        t.player = tr.player;
        t.oneWay = oneWay;
        t.station = st.id;
        t.chamber = chamberFor(st, tr.player);
        t.createdById = userId == null ? origin : userId;
        t.createdByName = userName == null ? origin : userName;
        t.createdAt = now;
        t.updatedAt = now;
        t.localId = localId;
        t.lastBeatAt = now;
        store.trips.add(t);
        // The oldest finished trips go beyond 100; open ones never.
        for (int i = 0; i < store.trips.size() && store.trips.size() > PearlStore.MAX_TRIPS; ) {
            if (!store.trips.get(i).open()) store.trips.remove(i);
            else i++;
        }
        dirty = true;
        persist();
        return t;
    }

    /** Stops a trip: up to `ready` it is cancelled; later 409 (a pull can't be undone). */
    synchronized PearlStore.Trip stopTrip(String id) {
        PearlStore.Trip t = trip(id);
        if (t == null) throw new Refused(404, "no such trip");
        if (!t.open()) throw new Refused(409, "the trip is already " + t.state);
        if (!isBefore(t.step, "load_sent")) throw new Refused(409, "a pull can't be undone");
        cancel(t, "stopped", now());
        persist();
        return t;
    }

    /** The owner's "home": clears every alert of the stash. */
    synchronized void clearAway(String server, String stash) {
        doClearAway(server, stash, now(), true);
        persist();
    }

    /** all = the owner's clear (every alert); otherwise (a bot's own `home`) only away_order goes. */
    private void doClearAway(String server, String stash, long now, boolean all) {
        Traveller tr = travellers.get(sk(server, stash));
        if (tr != null && tr.supports) {
            tr.awayClearPending = true;
            tr.awayClearAt = now;
        }
        alerts.removeIf(a -> stash.equals(a.stash()) && (all || a.kind().equals("away_order")));
        IndexStore.StashKey k = sk(server, stash);
        backFails.remove(k);
        backNext.remove(k);
        // A held trip is over; an open one stays (the owner said "home", not "stop").
    }

    /** The owner pins a uuid to an approved name (assigned, listed at a station, or the traveller of a home stash). */
    synchronized void setPin(String player, String uuid) {
        if (store.disabled()) throw new Refused(503, "pearl travel is off (pearl.json can't be read)");
        if (uuid == null || !PearlBodies.UUID.matcher(uuid).matches()) throw new Refused(400, "uuid must look like 069a79f4-44e9-4726-a5be-fca90e38aaf5");
        if (!approvedName(player)) throw new Refused(400, "approve the name first: assign it, list it as an allowed traveller, or give its stash a home station");
        String u = uuid.toLowerCase(Locale.ROOT);
        store.pins.put(player.toLowerCase(Locale.ROOT), u);
        for (Traveller t : travellers.values()) {
            if (t.player != null && t.player.equalsIgnoreCase(player)) {
                t.uuidMismatch = t.uuid != null && !t.uuid.equals(u);
                if (!t.uuidMismatch) alerts.removeIf(a -> "uuid_mismatch".equals(a.kind()) && t.stash.equals(a.stash()));
            }
        }
        dirty = true;
        persist();
    }

    /** The owner lists (or removes) a traveller at a station: the station's loader then allows the name. */
    synchronized void allowTraveller(String stationId, String player, boolean on) {
        PearlStore.Station st = station(stationId);
        if (st == null) throw new Refused(404, "no such station");
        String n = player.toLowerCase(Locale.ROOT);
        if (on) {
            if (!st.allowed.contains(n) && st.allowed.size() >= 16) throw new Refused(400, "at most 16 allowed travellers per station");
            st.allowed.add(n);
        } else {
            st.allowed.remove(n);
        }
        dirty = true;
        persist();
    }

    /** "This is my loader": the owner approves the source that is waiting at the station (it must still be the one shown). */
    synchronized void approveLoader(String stationId, String source) {
        PearlStore.Station st = station(stationId);
        if (st == null) throw new Refused(404, "no such station");
        LinkedHashMap<String, Long> waiting = pending.get(stationId);
        if (waiting == null || !waiting.containsKey(source)) throw new Refused(409, "that source is no longer waiting for approval");
        st.binding = source;
        pending.remove(stationId);
        dirty = true;
        persist();
    }

    /** The owner dismisses a pending source (it may show up again when it reports again). */
    synchronized void dismissSource(String stationId, String source) {
        if (station(stationId) == null) throw new Refused(404, "no such station");
        LinkedHashMap<String, Long> waiting = pending.get(stationId);
        if (waiting == null || waiting.remove(source) == null) throw new Refused(409, "that source is not waiting");
        if (waiting.isEmpty()) pending.remove(stationId);
    }

    /** Up to 4 distinct pending sources per station; the oldest (least recently seen) goes first. */
    private void addPending(String stationId, String source, long now) {
        LinkedHashMap<String, Long> m = pending.computeIfAbsent(stationId, k -> new LinkedHashMap<>());
        m.remove(source);
        m.put(source, now);
        while (m.size() > MAX_PENDING) m.remove(m.keySet().iterator().next());
    }

    synchronized PearlStore.Station saveStation(String id, String name, String server, String dim, String loaderPlayer, String stash) {
        if (store.disabled()) throw new Refused(503, "pearl travel is off (pearl.json can't be read)");
        PearlStore.Station st = id == null ? null : station(id);
        if (id != null && st == null) throw new Refused(404, "no such station");
        if (st == null && store.stations.size() >= PearlStore.MAX_STATIONS) throw new Refused(400, "at most " + PearlStore.MAX_STATIONS + " stations");
        if (stash != null) {
            for (PearlStore.Station o : store.stations) {
                if (o != st && stash.equals(o.stash) && o.server.equals(server)) throw new Refused(409, "that stash already has a home station");
            }
        }
        if (st != null && !st.loaderPlayer.equalsIgnoreCase(loaderPlayer)) {
            for (PearlStore.Trip t : store.trips) if (t.open() && t.station.equals(st.id)) throw new Refused(409, "a trip is using that station");
        }
        PearlStore.Station copy = new PearlStore.Station();
        if (st == null) {
            copy.id = newId("ps-", 8);
        } else {
            copy.id = st.id;
            copy.assign.putAll(st.assign);
            copy.broken.addAll(st.broken);
            copy.allowed.addAll(st.allowed);
            if (st.loaderPlayer.equalsIgnoreCase(loaderPlayer)) copy.binding = st.binding; // an approved loader survives an edit unless its name changes
            else pending.remove(st.id);
        }
        copy.name = name;
        copy.server = server;
        copy.dim = dim;
        copy.loaderPlayer = loaderPlayer;
        copy.stash = stash;
        if (st == null) store.stations.add(copy);
        else store.stations.set(store.stations.indexOf(st), copy);
        dirty = true;
        persistOrRollback(st, copy);
        return copy;
    }

    private void persistOrRollback(PearlStore.Station old, PearlStore.Station now) {
        try {
            dirty = false;
            store.save();
        } catch (IOException e) {
            int i = store.stations.indexOf(now);
            if (i >= 0) {
                if (old == null) store.stations.remove(i);
                else store.stations.set(i, old);
            }
            throw new Refused(503, "pearl.json can't be written");
        }
    }

    synchronized void deleteStation(String id) {
        PearlStore.Station st = station(id);
        if (st == null) return;
        for (PearlStore.Trip t : store.trips) if (t.open() && t.station.equals(id)) throw new Refused(409, "a trip is using that station");
        store.stations.remove(st);
        pending.remove(id);
        dirty = true;
        persist();
    }

    synchronized void assign(String stationId, String player, String chamber) {
        PearlStore.Station st = station(stationId);
        if (st == null) throw new Refused(404, "no such station");
        String key = player.toLowerCase(Locale.ROOT);
        if (chamber == null) {
            st.assign.remove(key);
        } else {
            Loader ld = loaders.get(st.loaderPlayer.toLowerCase(Locale.ROOT));
            JsonObject c = ld == null ? null : ld.chambers.get(chamber);
            if (c == null) throw new Refused(400, "the loader hasn't reported that chamber");
            if (!c.get("ownerKnown").getAsBoolean() || !c.get("owner").getAsString().equalsIgnoreCase(player)) {
                throw new Refused(400, "that chamber's pearl is not this traveller's");
            }
            st.assign.put(key, chamber);
        }
        dirty = true;
        persist();
    }

    synchronized void resetChamber(String stationId, String chamber) {
        PearlStore.Station st = station(stationId);
        if (st == null) throw new Refused(404, "no such station");
        st.broken.remove(chamber);
        suspect.remove(stationId + "/" + chamber);
        pathFails.remove(stationId + "/" + chamber);
        alerts.removeIf(a -> "chamber_broken".equals(a.kind()) && stationId.equals(a.station()));
        dirty = true;
        persist();
    }

    // ---------- views ----------

    synchronized String tripStash(String id) {
        PearlStore.Trip t = trip(id);
        return t == null ? null : t.server + "\n" + t.stash;
    }

    /** Whether the engine knows a traveller for the stash (so a heartbeat without the key still gets the reply fields). */
    synchronized boolean knows(String server, String stash) {
        Traveller t = travellers.get(sk(server, stash));
        return t != null && t.supports;
    }

    synchronized boolean hasStation(String id) {
        return station(id) != null;
    }

    synchronized JsonObject stationJson(PearlStore.Station st) {
        return stationJson(st, false); // fail closed: the owner-only fields need the explicit form
    }

    synchronized JsonObject stationJson(PearlStore.Station st, boolean owner) {
        long now = now();
        JsonObject o = st.toJson();
        o.remove("broken");
        Loader ld = loaders.get(st.loaderPlayer.toLowerCase(Locale.ROOT));
        JsonArray chambers = new JsonArray();
        if (ld != null) {
            for (Map.Entry<String, JsonObject> e : ld.chambers.entrySet()) {
                JsonObject c = e.getValue();
                JsonObject co = new JsonObject();
                co.addProperty("id", e.getKey());
                co.add("owner", c.get("owner"));
                co.add("ownerKnown", c.get("ownerKnown"));
                co.add("presence", c.get("presence"));
                co.addProperty("state", chamberState(st, e.getKey(), now));
                co.addProperty("reportedAt", ld.receivedAt);
                co.addProperty("suspect", suspect.getOrDefault(st.id + "/" + e.getKey(), 0));
                chambers.add(co);
            }
        }
        for (String b : st.broken) {
            boolean seen = false;
            for (JsonElement c : chambers) if (c.getAsJsonObject().get("id").getAsString().equals(b)) seen = true;
            if (!seen) {
                JsonObject co = new JsonObject();
                co.addProperty("id", b);
                co.add("owner", JsonNull.INSTANCE);
                co.addProperty("ownerKnown", false);
                co.addProperty("presence", "unknown");
                co.addProperty("state", "broken");
                co.addProperty("reportedAt", 0);
                co.addProperty("suspect", 0);
                chambers.add(co);
            }
        }
        o.add("chambers", chambers);
        JsonObject ls = new JsonObject();
        boolean online = ld != null && now - ld.receivedAt <= PearlLogic.FRESH_MS;
        ls.addProperty("online", online);
        ls.addProperty("reportedAt", ld == null ? 0 : ld.receivedAt);
        ls.addProperty("busy", ld != null && ld.busy);
        ls.addProperty("playerNear", ld != null && ld.playerNear);
        ls.addProperty("dropPearlAfterLoad", ld != null && ld.drop);
        ls.addProperty("temporaryMode", ld != null && ld.temporaryMode);
        ls.addProperty("approved", ld != null && st.binding != null && st.binding.equals(ld.source));
        if (owner) {
            ls.addProperty("source", ld == null ? null : ld.source);
            JsonArray pa = new JsonArray();
            LinkedHashMap<String, Long> waiting = pending.get(st.id);
            if (waiting != null) {
                waiting.forEach((src, seen) -> {
                    JsonObject po = new JsonObject();
                    po.addProperty("source", src);
                    po.addProperty("lastSeen", seen);
                    int slash = src.startsWith("hb:") ? src.lastIndexOf('/') : -1;
                    po.addProperty("stash", slash >= 0 ? src.substring(slash + 1) : null);
                    pa.add(po);
                });
            }
            ls.add("pending", pa);
            ls.addProperty("bound", st.binding != null);
            JsonArray al = new JsonArray();
            for (String n : st.allowed) {
                JsonObject ao = new JsonObject();
                ao.addProperty("name", n);
                ao.addProperty("uuid", store.pins.get(n));
                al.add(ao);
            }
            o.add("allowed", al);
        }
        ls.addProperty("warning", ld != null && ld.temporaryMode ? "temporaryMode" : null);
        o.add("loaderState", ls);
        ArrayDeque<Long> q = loads.get(st.id);
        int n = 0;
        if (q != null) for (long at : q) if (now - at <= 3_600_000L) n++;
        o.addProperty("loadsLastHour", n);
        return o;
    }

    /** The GET body; visibleStash says which stashes the user may see (stations of a server are shown when one of its stashes is visible). */
    synchronized JsonObject view(java.util.function.BiPredicate<String, String> visibleStash, java.util.function.Predicate<String> visibleServer) {
        return view(visibleStash, visibleServer, false); // fail closed
    }

    synchronized JsonObject view(java.util.function.BiPredicate<String, String> visibleStash, java.util.function.Predicate<String> visibleServer, boolean owner) {
        JsonObject o = new JsonObject();
        JsonArray stations = new JsonArray();
        for (PearlStore.Station st : store.stations) if (visibleServer.test(st.server)) stations.add(stationJson(st, owner));
        o.add("stations", stations);
        JsonArray trips = new JsonArray();
        for (int i = store.trips.size() - 1; i >= 0; i--) {
            PearlStore.Trip t = store.trips.get(i);
            if (visibleStash.test(t.server, t.stash)) trips.add(t.toJson());
        }
        o.add("trips", trips);
        JsonArray ts = new JsonArray();
        long now = now();
        for (Traveller t : travellers.values()) {
            if (!visibleStash.test(t.server, t.stash)) continue;
            JsonObject to = new JsonObject();
            to.addProperty("server", t.server);
            to.addProperty("stash", t.stash);
            to.addProperty("player", t.player);
            to.addProperty("supports", t.supports);
            to.addProperty("enabled", t.enabled);
            to.addProperty("uuidMismatch", t.uuidMismatch);
            if (owner) {
                to.addProperty("reportedUuid", t.uuid);
                boolean conflict = false;
                for (Traveller o2 : travellers.values()) {
                    if (o2 != t && o2.player != null && t.player != null && o2.player.equalsIgnoreCase(t.player) && o2.uuid != null && t.uuid != null && !o2.uuid.equals(t.uuid)) conflict = true;
                }
                to.addProperty("uuidConflict", conflict); // two stashes report different uuids for the same name
                to.addProperty("pinnedUuid", t.player == null ? null : store.pins.get(t.player.toLowerCase(Locale.ROOT)));
            }
            if (t.awayStation == null) {
                to.add("away", JsonNull.INSTANCE);
            } else {
                JsonObject a = new JsonObject();
                a.addProperty("station", t.awayStation);
                a.addProperty("since", t.awaySince);
                to.add("away", a);
            }
            JsonObject p = new JsonObject();
            p.addProperty("carried", t.carried);
            to.add("pearls", p);
            to.addProperty("game", t.game);
            to.addProperty("online", now - t.receivedAt <= IndexStore.ONLINE_WINDOW_MS);
            PearlStore.Trip open = openTrip(t.server, t.stash);
            to.addProperty("trip", open == null ? null : open.id);
            ts.add(to);
        }
        o.add("travellers", ts);
        JsonArray as = new JsonArray();
        for (Alert a : alerts) if (a.stash() == null || visibleStash.test(serverOf(a), a.stash())) as.add(a.toJson());
        o.add("alerts", as);
        JsonObject limits = new JsonObject();
        limits.addProperty("maxStations", PearlStore.MAX_STATIONS);
        limits.addProperty("maxChambers", PearlBodies.MAX_CHAMBERS);
        limits.addProperty("maxTrips", PearlStore.MAX_TRIPS);
        limits.addProperty("tripsPerMinute", 6);
        limits.addProperty("loadsPerHour", PearlLogic.LOADS_PER_HOUR);
        o.add("limits", limits);
        return o;
    }

    private String serverOf(Alert a) {
        for (Traveller t : travellers.values()) if (t.stash.equals(a.stash())) return t.server;
        for (PearlStore.Trip t : store.trips) if (t.stash.equals(a.stash())) return t.server;
        return "";
    }

    /** {away, trip} for GET /api/v1/stashes (no names, no ids). */
    synchronized JsonObject summary(String server, String stash) {
        JsonObject o = new JsonObject();
        Traveller t = travellers.get(sk(server, stash));
        PearlStore.Trip open = openTrip(server, stash);
        o.addProperty("away", (t != null && t.awayStation != null) || (open != null && !isBefore(open.step, "load_sent")));
        o.addProperty("trip", open == null ? null : open.state);
        return o;
    }

    /** Test and route hook: the trip by id as JSON, or null. */
    synchronized JsonObject tripJson(String id) {
        PearlStore.Trip t = trip(id);
        return t == null ? null : t.toJson();
    }

    /** Forgets a stash that was dropped from the server. */
    synchronized void forget(String server, String stash) {
        travellers.remove(sk(server, stash));
    }
}

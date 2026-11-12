package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pearl travel's stations and trips (PEARL-CONTRACT v1.0 rev 2), stored in data/pearl.json: atomic, owner only, with a .bak from a
 * clean previous file. When the file and its backup are both unreadable pearl travel is off ({@link #disabled()}) and nothing is
 * written over them. The store holds the object graph; {@code PearlEngine} owns the lock and is its only writer.
 */
final class PearlStore {
    static final int MAX_STATIONS = 16;
    static final int MAX_TRIPS = 100;

    /** A station as the owner registered it (chambers and the loader's state are in memory, from the loader's reports). */
    static final class Station {
        String id;
        String name;
        String server;
        String dim;
        String loaderPlayer;
        String stash; // the home station of this stash, or null
        String binding; // the first accepted loader source (hb:<server>/<stash> or bot:<botId>); null until one reported
        final TreeMap<String, String> assign = new TreeMap<>();
        final TreeSet<String> broken = new TreeSet<>();
        final TreeSet<String> allowed = new TreeSet<>(); // owner-approved travellers (lower-case names) for this station

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("name", name);
            o.addProperty("server", server);
            o.addProperty("dim", dim);
            JsonObject l = new JsonObject();
            l.addProperty("player", loaderPlayer);
            o.add("loader", l);
            o.addProperty("stash", stash);
            JsonObject a = new JsonObject();
            assign.forEach(a::addProperty);
            o.add("assign", a);
            JsonArray b = new JsonArray();
            broken.forEach(b::add);
            o.add("broken", b);
            return o;
        }

        /** The stored form: the browser form plus the loader binding. */
        JsonObject toStored() {
            JsonObject o = toJson();
            o.addProperty("binding", binding);
            JsonArray al = new JsonArray();
            allowed.forEach(al::add);
            o.add("allowed", al);
            return o;
        }

        static Station fromJson(JsonObject o) {
            Station s = new Station();
            s.id = o.get("id").getAsString();
            s.name = o.get("name").getAsString();
            s.server = o.get("server").getAsString();
            s.dim = o.get("dim").getAsString();
            s.loaderPlayer = o.getAsJsonObject("loader").get("player").getAsString();
            s.binding = o.has("binding") && !o.get("binding").isJsonNull() ? o.get("binding").getAsString() : null;
            if (!PearlBodies.STATION.matcher(s.id).matches() || !PearlBodies.DIM.matcher(s.dim).matches()
                || !PearlBodies.PLAYER.matcher(s.loaderPlayer).matches() || s.name.length() > 64 || s.server.length() > 128
                || (s.binding != null && s.binding.length() > 300)) {
                throw new IllegalArgumentException("a station breaks the format");
            }
            s.stash = o.get("stash").isJsonNull() ? null : o.get("stash").getAsString();
            for (var e : o.getAsJsonObject("assign").entrySet()) {
                if (!PearlBodies.PLAYER.matcher(e.getKey()).matches() || !PearlBodies.PEARL_ID.matcher(e.getValue().getAsString()).matches()) {
                    throw new IllegalArgumentException("an assignment breaks the format");
                }
                s.assign.put(e.getKey(), e.getValue().getAsString());
            }
            if (o.has("allowed")) {
                for (JsonElement e : o.getAsJsonArray("allowed")) {
                    if (!PearlBodies.PLAYER.matcher(e.getAsString()).matches() || s.allowed.size() >= 16) throw new IllegalArgumentException("an allowed name breaks the format");
                    s.allowed.add(e.getAsString().toLowerCase(java.util.Locale.ROOT));
                }
            }
            if (o.has("broken")) {
                for (JsonElement e : o.getAsJsonArray("broken")) {
                    if (!PearlBodies.PEARL_ID.matcher(e.getAsString()).matches()) throw new IllegalArgumentException("a chamber id breaks the format");
                    s.broken.add(e.getAsString());
                }
            }
            return s;
        }
    }

    /** A trip: a Task record (kind travel) plus what the engine needs to carry it on. Times are server milliseconds. */
    static final class Trip {
        String id;
        String action;          // go | back
        String state = "pending";
        String pauseReason;
        String origin;          // website | plugin | addon | system
        int priority = 200;
        String band = "normal";
        String server;
        String stash;
        String botId;
        String player;          // the traveller
        boolean oneWay;
        String station;
        String chamber;
        boolean locks;          // the two locks are held
        String step = "precheck";
        int whispers;
        long loadSentAt;        // 0 = none
        long teleportAt;        // 0 = none
        String teleportDim;
        long loadingAt;         // when the loader said loading (server clock), 0 = not yet
        long pullDeadline;      // server clock, 0 = none
        Integer before;
        Integer after;
        String errorCode;
        String warning;
        String phase = "waiting for the bot";
        String createdById;
        String createdByName;
        long createdAt;
        long updatedAt;
        long finishedAt;
        String childLoad;
        String childRearm;
        // what the server tells the traveller
        boolean go, armHome, retry, rearm, hold, notified;
        String pauseBy;          // bot | server
        boolean readyReq;        // the traveller said its own precheck passed
        boolean retried;
        boolean pathCounted;
        // bookkeeping
        int tries;
        long nextTryAt;
        long pausedSince;
        long arrivedAt;
        String rearmResult;
        long rearmAt;
        long armHomeAt;
        boolean armedHome;
        boolean suspectCounted;
        boolean dropAfterLoad;
        long lastBeatAt;
        String localId;
        long goAt;              // when the server said go, 0 = not yet
        long minLoadAt;         // a load entry older than this belongs to an earlier whisper
        boolean ordersWait;     // an order is pending (not stored)

        boolean open() {
            return state.equals("pending") || state.equals("running") || state.equals("paused");
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("kind", "travel");
            o.addProperty("title", (action.equals("go") ? "Go to " : "Back to ") + "station");
            o.addProperty("action", action);
            o.addProperty("state", state);
            o.addProperty("pauseReason", pauseReason);
            o.addProperty("interrupt", interruptsAtCheckpoint() ? "checkpoint" : "never");
            o.addProperty("origin", origin);
            o.addProperty("priority", priority);
            o.addProperty("band", band);
            JsonObject scope = new JsonObject();
            scope.addProperty("server", server);
            scope.addProperty("stash", stash);
            scope.addProperty("local", !origin.equals("website") && !origin.equals("system"));
            o.add("scope", scope);
            JsonObject target = new JsonObject();
            target.addProperty("botId", botId);
            target.addProperty("player", player);
            target.addProperty("role", "traveller");
            o.add("target", target);
            JsonObject params = new JsonObject();
            params.addProperty("oneWay", oneWay);
            JsonObject route = new JsonObject();
            JsonArray legs = new JsonArray();
            JsonObject leg = new JsonObject();
            leg.addProperty("method", "pearl");
            leg.addProperty("station", station);
            leg.addProperty("chamber", chamber);
            legs.add(leg);
            route.add("legs", legs);
            params.add("route", route);
            o.add("params", params);
            JsonArray lk = new JsonArray();
            if (locks) {
                for (String res : new String[]{"chamber:" + station + "/" + chamber, "loader:" + station}) {
                    JsonObject l = new JsonObject();
                    l.addProperty("res", res);
                    l.addProperty("mode", "exclusive");
                    lk.add(l);
                }
            }
            o.add("locks", lk);
            JsonObject cp = new JsonObject();
            cp.addProperty("step", step);
            JsonObject data = new JsonObject();
            data.addProperty("whispers", whispers);
            data.addProperty("loadSentAt", loadSentAt == 0 ? null : loadSentAt);
            cp.add("data", data);
            o.add("checkpoint", cp);
            JsonArray ch = new JsonArray();
            ch.add(child(childLoad, "pearl.load", null, loadState()));
            ch.add(child(childRearm, "pearl.rearm", player, rearmState()));
            o.add("children", ch);
            o.addProperty("phase", phase);
            if (errorCode == null) {
                o.add("error", JsonNull.INSTANCE);
            } else {
                JsonObject e = new JsonObject();
                e.addProperty("code", errorCode);
                e.addProperty("text", PearlEngine.codeText(errorCode));
                o.add("error", e);
            }
            o.addProperty("warning", warning);
            JsonObject p = new JsonObject();
            p.addProperty("before", before);
            p.addProperty("after", after);
            o.add("pearls", p);
            JsonObject by = new JsonObject();
            by.addProperty("id", createdById);
            by.addProperty("name", createdByName);
            o.add("createdBy", by);
            o.addProperty("createdAt", createdAt);
            o.addProperty("updatedAt", updatedAt);
            o.addProperty("finishedAt", finishedAt);
            return o;
        }

        private JsonObject child(String cid, String kind, String player, String childState) {
            JsonObject c = new JsonObject();
            c.addProperty("id", cid);
            c.addProperty("parent", id);
            c.addProperty("kind", kind);
            JsonObject t = new JsonObject();
            t.addProperty("player", player);
            c.add("target", t);
            c.addProperty("state", childState);
            return c;
        }

        boolean interruptsAtCheckpoint() {
            return List.of("precheck", "arm_home", "ready").contains(step);
        }

        private String loadState() {
            if (!open()) return state.equals("completed") ? "completed" : state;
            return switch (step) {
                case "load_sent", "awaiting_pull" -> "running";
                case "arrived", "pickup", "rearm", "done" -> "completed";
                default -> "pending";
            };
        }

        private String rearmState() {
            if (state.equals("completed")) return "completed";
            if (!open()) return state.equals("cancelled") ? "cancelled" : "failed";
            return switch (step) {
                case "pickup", "rearm" -> "running";
                default -> "pending";
            };
        }

        /** The whole record, with the bookkeeping, for pearl.json. */
        JsonObject toStored() {
            JsonObject o = toJson();
            JsonObject rt = new JsonObject();
            rt.addProperty("whispers", whispers);
            rt.addProperty("loadSentAt", loadSentAt);
            rt.addProperty("teleportAt", teleportAt);
            rt.addProperty("teleportDim", teleportDim);
            rt.addProperty("loadingAt", loadingAt);
            rt.addProperty("pullDeadline", pullDeadline);
            rt.addProperty("locks", locks);
            rt.addProperty("go", go);
            rt.addProperty("armHome", armHome);
            rt.addProperty("retry", retry);
            rt.addProperty("rearm", rearm);
            rt.addProperty("hold", hold);
            rt.addProperty("notified", notified);
            rt.addProperty("tries", tries);
            rt.addProperty("nextTryAt", nextTryAt);
            rt.addProperty("pausedSince", pausedSince);
            rt.addProperty("arrivedAt", arrivedAt);
            rt.addProperty("rearmResult", rearmResult);
            rt.addProperty("rearmAt", rearmAt);
            rt.addProperty("armHomeAt", armHomeAt);
            rt.addProperty("armedHome", armedHome);
            rt.addProperty("suspectCounted", suspectCounted);
            rt.addProperty("dropAfterLoad", dropAfterLoad);
            rt.addProperty("lastBeatAt", lastBeatAt);
            rt.addProperty("localId", localId);
            rt.addProperty("pauseBy", pauseBy);
            rt.addProperty("readyReq", readyReq);
            rt.addProperty("retried", retried);
            rt.addProperty("pathCounted", pathCounted);
            rt.addProperty("childLoad", childLoad);
            rt.addProperty("childRearm", childRearm);
            rt.addProperty("goAt", goAt);
            rt.addProperty("minLoadAt", minLoadAt);
            o.add("rt", rt);
            return o;
        }

        static Trip fromStored(JsonObject o) {
            Trip t = new Trip();
            t.id = o.get("id").getAsString();
            t.action = o.get("action").getAsString();
            t.state = o.get("state").getAsString();
            t.pauseReason = str(o, "pauseReason");
            t.origin = o.get("origin").getAsString();
            t.priority = o.get("priority").getAsInt();
            t.band = o.get("band").getAsString();
            JsonObject scope = o.getAsJsonObject("scope");
            t.server = scope.get("server").getAsString();
            t.stash = scope.get("stash").getAsString();
            JsonObject target = o.getAsJsonObject("target");
            t.botId = str(target, "botId");
            t.player = target.get("player").getAsString();
            JsonObject params = o.getAsJsonObject("params");
            t.oneWay = params.get("oneWay").getAsBoolean();
            JsonObject leg = params.getAsJsonObject("route").getAsJsonArray("legs").get(0).getAsJsonObject();
            t.station = leg.get("station").getAsString();
            t.chamber = str(leg, "chamber");
            t.step = o.getAsJsonObject("checkpoint").get("step").getAsString();
            t.phase = str(o, "phase");
            if (o.get("error") instanceof JsonObject e) t.errorCode = e.get("code").getAsString();
            t.warning = str(o, "warning");
            JsonObject p = o.getAsJsonObject("pearls");
            t.before = p.get("before").isJsonNull() ? null : p.get("before").getAsInt();
            t.after = p.get("after").isJsonNull() ? null : p.get("after").getAsInt();
            JsonObject by = o.getAsJsonObject("createdBy");
            t.createdById = str(by, "id");
            t.createdByName = str(by, "name");
            t.createdAt = o.get("createdAt").getAsLong();
            t.updatedAt = o.get("updatedAt").getAsLong();
            t.finishedAt = o.get("finishedAt").getAsLong();
            JsonObject rt = o.getAsJsonObject("rt");
            t.whispers = rt.get("whispers").getAsInt();
            t.loadSentAt = rt.get("loadSentAt").getAsLong();
            t.teleportAt = rt.get("teleportAt").getAsLong();
            t.teleportDim = str(rt, "teleportDim");
            t.loadingAt = rt.get("loadingAt").getAsLong();
            t.pullDeadline = rt.get("pullDeadline").getAsLong();
            t.locks = rt.get("locks").getAsBoolean();
            t.go = rt.get("go").getAsBoolean();
            t.armHome = rt.get("armHome").getAsBoolean();
            t.retry = rt.get("retry").getAsBoolean();
            t.rearm = rt.get("rearm").getAsBoolean();
            t.hold = rt.get("hold").getAsBoolean();
            t.notified = rt.get("notified").getAsBoolean();
            t.tries = rt.get("tries").getAsInt();
            t.nextTryAt = rt.get("nextTryAt").getAsLong();
            t.pausedSince = rt.get("pausedSince").getAsLong();
            t.arrivedAt = rt.get("arrivedAt").getAsLong();
            t.rearmResult = str(rt, "rearmResult");
            t.rearmAt = rt.get("rearmAt").getAsLong();
            t.armHomeAt = rt.get("armHomeAt").getAsLong();
            t.armedHome = rt.get("armedHome").getAsBoolean();
            t.suspectCounted = rt.get("suspectCounted").getAsBoolean();
            t.dropAfterLoad = rt.get("dropAfterLoad").getAsBoolean();
            t.lastBeatAt = rt.get("lastBeatAt").getAsLong();
            t.localId = str(rt, "localId");
            t.pauseBy = str(rt, "pauseBy");
            t.readyReq = rt.get("readyReq").getAsBoolean();
            t.retried = rt.get("retried").getAsBoolean();
            t.pathCounted = rt.get("pathCounted").getAsBoolean();
            t.childLoad = str(rt, "childLoad");
            t.childRearm = str(rt, "childRearm");
            t.goAt = rt.has("goAt") ? rt.get("goAt").getAsLong() : 0;
            t.minLoadAt = rt.has("minLoadAt") ? rt.get("minLoadAt").getAsLong() : 0;
            if (!PearlBodies.TRIP.matcher(t.id).matches() || !PearlBodies.STATION.matcher(t.station).matches()
                || !PearlBodies.PLAYER.matcher(t.player).matches() || (t.chamber != null && !PearlBodies.PEARL_ID.matcher(t.chamber).matches())
                || !PearlBodies.STEPS.contains(t.step) || !List.of("go", "back").contains(t.action)
                || !List.of("pending", "running", "paused", "completed", "failed", "cancelled").contains(t.state)
                || (t.childLoad != null && !PearlBodies.TRIP.matcher(t.childLoad).matches())
                || (t.childRearm != null && !PearlBodies.TRIP.matcher(t.childRearm).matches())) {
                throw new IllegalArgumentException("a trip breaks the format");
            }
            return t;
        }
    }

    private static String str(JsonObject o, String f) {
        JsonElement e = o.get(f);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private final Path file;
    private final Path backup;
    final List<Station> stations = new ArrayList<>();
    final List<Trip> trips = new ArrayList<>(); // oldest first
    /** Player name (lower case) to the uuid it was first seen with. */
    final TreeMap<String, String> pins = new TreeMap<>();
    static final int MAX_PINS = 4096; // a sanity bound when reading; pins are the owner's, for approved names
    private boolean disabled;
    private boolean loadedFromBackup;

    PearlStore(Path dataDir) {
        this.file = dataDir.resolve("pearl.json");
        this.backup = dataDir.resolve("pearl.json.bak");
    }

    boolean disabled() {
        return disabled;
    }

    void load() {
        stations.clear();
        trips.clear();
        pins.clear();
        disabled = false;
        loadedFromBackup = false;
        if (Files.exists(backup)) AtomicFiles.ownerOnly(backup);
        if (!Files.exists(file)) {
            if (!Files.exists(backup)) return;
            try {
                read(backup);
                loadedFromBackup = true;
                System.err.println("Pearl travel restored from " + backup.getFileName() + " (" + file.getFileName() + " was missing).");
            } catch (IOException | RuntimeException e) {
                off();
            }
            return;
        }
        try {
            read(file);
            AtomicFiles.ownerOnly(file);
            return;
        } catch (IOException | RuntimeException e) {
            System.err.println("Can't read " + file.getFileName() + " (" + e.getMessage() + "); trying the backup.");
        }
        try {
            stations.clear();
            trips.clear();
            pins.clear();
            read(backup);
            loadedFromBackup = true;
            System.err.println("Pearl travel restored from " + backup.getFileName() + ".");
        } catch (IOException | RuntimeException e) {
            off();
        }
    }

    private void off() {
        disabled = true;
        stations.clear();
        trips.clear();
        pins.clear();
        System.err.println("Pearl travel is off: " + file.getFileName() + " and its backup can't be read, and neither will be overwritten.");
    }

    private void read(Path path) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!(root.get("version") instanceof JsonPrimitive v) || !v.isNumber() || v.getAsInt() != 1) throw new IOException("unknown version");
        JsonArray ss = root.getAsJsonArray("stations");
        JsonArray ts = root.getAsJsonArray("trips");
        if (ss.size() > MAX_STATIONS || ts.size() > MAX_TRIPS + 50) throw new IOException("too many entries");
        List<Station> st = new ArrayList<>();
        for (JsonElement e : ss) st.add(Station.fromJson(e.getAsJsonObject()));
        List<Trip> tr = new ArrayList<>();
        for (JsonElement e : ts) tr.add(Trip.fromStored(e.getAsJsonObject()));
        TreeMap<String, String> pn = new TreeMap<>();
        if (root.get("pins") instanceof JsonObject po) {
            if (po.size() > MAX_PINS) throw new IOException("too many pins");
            for (var e : po.entrySet()) {
                String u = e.getValue().getAsString();
                if (!PearlBodies.PLAYER.matcher(e.getKey()).matches() || !PearlBodies.UUID.matcher(u).matches()) throw new IOException("a pin breaks the format");
                pn.put(e.getKey().toLowerCase(java.util.Locale.ROOT), u);
            }
        }
        stations.addAll(st);
        trips.addAll(tr);
        pins.putAll(pn);
    }

    /** Writes the whole store. Throws IOException when the file can't be written (memory is the caller's to put back). */
    void save() throws IOException {
        if (disabled) throw new IOException("pearl travel is off: pearl.json can't be read");
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray ss = new JsonArray();
        stations.forEach(s -> ss.add(s.toStored()));
        root.add("stations", ss);
        JsonObject pj = new JsonObject();
        pins.forEach(pj::addProperty);
        root.add("pins", pj);
        JsonArray ts = new JsonArray();
        trips.forEach(t -> ts.add(t.toStored()));
        root.add("trips", ts);
        if (Files.exists(file) && !loadedFromBackup) {
            boolean good;
            try {
                PearlStore probe = new PearlStore(file.getParent());
                probe.read(file);
                good = true;
            } catch (IOException | RuntimeException e) {
                good = false;
            }
            if (good) writeRetry(backup, Files.readAllBytes(file));
        }
        writeRetry(file, root.toString().getBytes(StandardCharsets.UTF_8));
        loadedFromBackup = false;
    }

    private static void writeRetry(Path path, byte[] bytes) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                AtomicFiles.write(path, bytes, true);
                return;
            } catch (java.nio.file.AccessDeniedException e) {
                if (attempt >= 5) throw e;
                try {
                    Thread.sleep(25L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}

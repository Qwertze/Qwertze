package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Bot profiles (bots.json) and the STOP state (ops.json) of TASK-CONTRACT v1.0 (slice). The store is plain data; the
 * {@link TaskService} owns the lock and is its only writer. Both files keep a {@code .bak} (the last readable file, copied
 * before each write) like farms.json.
 *
 * <p>Failure rules (fail closed). An unreadable {@code ops.json} never turns a STOP off: the state comes from the backup when
 * there is one, and in every case the global STOP is forced <b>on</b> (by "server") until the owner resumes. An unreadable
 * {@code bots.json} is restored from its backup (roles and the sticky farmer flags with it); with no readable backup the
 * profiles start empty (one log line, no content) and the farmer bots recreate their flags with their next sync.
 */
final class OpsStore {
    static final int MAX_BOTS = 64;

    /** A STOP: global (ops.json stopAll) or one bot's (its profile). */
    static final class Stop {
        boolean on;
        String by;
        long at;
        String reason;
        long rev;
        /** The ids of the tasks that were leased or running when the STOP was pressed (the delivery rule, mitigation (a)). */
        List<String> tasks = new ArrayList<>();
    }

    static final class Profile {
        String botId;
        String type;
        String player;
        String server;
        String contract;
        List<String> roles = new ArrayList<>();
        List<String> caps = new ArrayList<>();
        Stop stop = new Stop();
        /** Health, food, slots, totems, dim, inGame and "at"; never a position (G1). */
        JsonObject lastStatus;
        long firstSeen;
        long lastSync;
        long stopAck;
        /** Sticky (FARM P5b): the bot has reported farmer.enabled once. Never cleared in M2. */
        boolean farmerSeen;
        /** What the bot said about itself: informational only (mitigation (d)). */
        JsonObject farmer;
        JsonArray programs;
        JsonObject draft;
        /** The task id of the last sync's running object, or null. */
        String runningTask;

        boolean hasRole(String role) {
            return roles.contains(role);
        }

        /** A bot the owner made a farmer, or one that has ever said it is one (owner-only reads, activity scrub). */
        boolean farmer() {
            return farmerSeen || roles.contains("farmer");
        }
    }

    private final Path botsFile;
    private final Path botsBak;
    private final Path opsFile;
    private final Path opsBak;
    private final LongSupplier clock;
    final Map<String, Profile> bots = new LinkedHashMap<>();
    final Stop stopAll = new Stop();
    long rev;
    /** Set by load() when a file had to be repaired (the service writes the repaired state out at once). */
    boolean repaired;
    /** Set by loadBots() when bots.json had to be repaired, restored from its backup or is gone: the STOP is then forced on. */
    private boolean botsLost;

    OpsStore(Path dataDir, LongSupplier clock) {
        this.botsFile = dataDir == null ? null : dataDir.resolve("bots.json");
        this.botsBak = dataDir == null ? null : dataDir.resolve("bots.json.bak");
        this.opsFile = dataDir == null ? null : dataDir.resolve("ops.json");
        this.opsBak = dataDir == null ? null : dataDir.resolve("ops.json.bak");
        this.clock = clock;
    }

    void load() {
        if (botsFile == null) return;
        loadBots();
        loadOps();
    }

    private boolean readBots(Path p) {
        JsonObject root = StateFile.read(p);
        if (root == null) return false;
        Map<String, Profile> tmp = new LinkedHashMap<>();
        try {
            for (JsonElement e : root.getAsJsonArray("bots")) {
                Profile pr = StateFile.STORE.fromJson(e, Profile.class);
                if (pr == null || pr.botId == null || !FarmVocab.BOT_ID.matcher(pr.botId).matches() || tmp.size() >= MAX_BOTS) continue;
                if (pr.stop == null) pr.stop = new Stop();
                if (pr.stop.tasks == null) pr.stop.tasks = new ArrayList<>();
                if (pr.roles == null) pr.roles = new ArrayList<>();
                if (pr.caps == null) pr.caps = new ArrayList<>();
                tmp.put(pr.botId, pr);
            }
        } catch (RuntimeException e) {
            return false;
        }
        bots.clear();
        bots.putAll(tmp);
        return true;
    }

    private void loadBots() {
        boolean exists = Files.exists(botsFile);
        if (exists && readBots(botsFile)) {
            AtomicFiles.ownerOnly(botsFile);
            return;
        }
        boolean bakOk = Files.exists(botsBak) && readBots(botsBak);
        if (exists) StateFile.moveAside(botsFile, clock.getAsLong());
        if (exists || bakOk) {
            repaired = true;
            System.err.println(bakOk ? "bots.json unreadable, restored from bots.json.bak" : "bots.json unreadable, roles reset");
        }
        // Fail closed: per-bot STOPs and acknowledgements may be gone or stale. A server that has run before (ops.json is there)
        // and lost bots.json counts too; a first run (no file at all) does not.
        if (exists || bakOk || Files.exists(opsFile) || Files.exists(opsBak)) {
            botsLost = true;
            repaired = true;
            if (!exists && !bakOk) System.err.println("bots.json is missing, roles reset");
        }
    }

    private boolean readOps(Path p) {
        JsonObject root = StateFile.read(p);
        if (root == null) return false;
        try {
            long r = root.get("rev").getAsLong();
            Stop s = StateFile.STORE.fromJson(root.get("stopAll"), Stop.class);
            if (s == null) return false;
            rev = r;
            stopAll.on = s.on;
            stopAll.by = s.by;
            stopAll.at = s.at;
            stopAll.reason = s.reason;
            stopAll.rev = s.rev;
            stopAll.tasks = s.tasks == null ? new ArrayList<>() : s.tasks;
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void loadOps() {
        boolean exists = Files.exists(opsFile);
        boolean ok = exists && readOps(opsFile);
        boolean bakOk = false;
        if (ok) {
            AtomicFiles.ownerOnly(opsFile);
        } else {
            bakOk = Files.exists(opsBak) && readOps(opsBak);
            if (exists) StateFile.moveAside(opsFile, clock.getAsLong());
        }
        boolean opsLost = !ok && (exists || bakOk);
        if (!opsLost && !botsLost) return;
        // Fail closed: whatever was lost, the global STOP is on until the owner resumes.
        if (!stopAll.on || exists || botsLost) {
            // The counter must pass everything a bot may already have acknowledged, or a stale ack would look like this STOP's.
            long hi = rev;
            for (Profile p : bots.values()) hi = Math.max(hi, Math.max(p.stopAck, p.stop.rev));
            rev = hi + 1;
            stopAll.on = true;
            stopAll.by = "server";
            stopAll.at = clock.getAsLong();
            stopAll.reason = opsLost ? "ops.json was unreadable" : "bots.json was unreadable";
            stopAll.rev = rev;
            stopAll.tasks = new ArrayList<>();
        }
        repaired = true;
        System.err.println(!opsLost ? "bots.json was lost, STOP forced on (resume it on the website)"
            : bakOk ? "ops.json unreadable, restored from ops.json.bak and STOP forced on"
            : "ops.json unreadable, STOP forced on (resume it on the website)");
    }

    void saveBots() throws IOException {
        if (botsFile == null) return;
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray list = new JsonArray();
        for (Profile p : bots.values()) list.add(StateFile.STORE.toJsonTree(p));
        root.add("bots", list);
        StateFile.writeWithBackup(botsFile, botsBak, root);
    }

    void saveOps() throws IOException {
        if (opsFile == null) return;
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("rev", rev);
        root.add("stopAll", StateFile.STORE.toJsonTree(stopAll));
        // ops.json without bots.json would read as "bots.json was lost" at the next start: keep the two together.
        if (botsFile != null && !Files.exists(botsFile)) saveBots();
        StateFile.writeWithBackup(opsFile, opsBak, root);
    }
}

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

/**
 * The farm records (automation/farms.json, FARM-CONTRACT E1): { version, rev, farms, pendingForget }. Written with
 * AtomicFiles (owner only) before a call returns; farms.json.bak is the last clean file. When both are unreadable the farm
 * routes answer 503 and no farm task is offered, and neither file is overwritten. A farm holds no coordinate: names,
 * ids, counts and codes only.
 */
final class FarmStore {
    static final int MAX_FARMS = 64;
    static final long FORGET_MS = 7L * 24 * 60 * 60 * 1000;

    static final class Program {
        String hash;
        String mode;
        long at;
        long ticks;
        long loop;
        long anchors;
        long unsupported;
    }

    static final class Produce {
        String key;
        long perHour;
    }

    static final class Need {
        String key;
        long min;
        long max;
    }

    static final class Session {
        long minMs;
        long maxMs = 7_200_000;
    }

    static final class Thresholds {
        int healthStop = 10;
        int food = 6;
        int totems = 1;
        int playerRadius = 16;
        int playerSeconds = 10;
        int invFullPct = 90;
    }

    static final class Travel {
        String preset = "out_then_pearl_home";
        String out = "walk";
        String back = "pearl";
        String station;
        JsonElement route; // must be null in M2
    }

    static final class LastSession {
        String taskId;
        String endedBy;
        String state;
        long at;
        long loops;
        long recoveries;
        long runtimeMs;
    }

    static final class Farm {
        String id;
        String name;
        String server;
        String dim = "minecraft:overworld";
        String mode;
        String botId;
        boolean enabled = true;
        Program program;
        List<Produce> produces = new ArrayList<>();
        List<Need> needs = new ArrayList<>();
        Session session = new Session();
        Thresholds thresholds = new Thresholds();
        Travel travel = new Travel();
        JsonElement dropStash;
        JsonElement supplyStash;
        JsonElement schedule;
        boolean offlineOk;
        LastSession lastSession;
        long rev = 1;
        long createdAt;
        long updatedAt;

        /** Consecutive syncs whose programs[] did not list this farm (two clear the program). Not stored. */
        transient int missing;

        JsonObject toJson() {
            return Json.GSON.toJsonTree(this).getAsJsonObject();
        }
    }

    /** A pending attach order for a bot: bind your draft (this hash) to this farm. */
    static final class Attach {
        String farm;
        String draftHash;
        long at;
    }

    private final Path file;
    private final Path backup;
    final List<Farm> farms = new ArrayList<>();
    long rev = 1;
    /** botId to the farm ids it must delete its program and location for. */
    final Map<String, List<String>> pendingForget = new LinkedHashMap<>();
    /** farm id to when the forget was queued (7 days). */
    final Map<String, Long> forgetAt = new LinkedHashMap<>();
    final Map<String, Attach> pendingAttach = new LinkedHashMap<>();
    private boolean disabled;
    private boolean loadedFromBackup;

    FarmStore(Path dataDir) {
        this.file = dataDir == null ? null : dataDir.resolve("automation").resolve("farms.json");
        this.backup = dataDir == null ? null : dataDir.resolve("automation").resolve("farms.json.bak");
    }

    boolean disabled() {
        return disabled;
    }

    Farm get(String id) {
        for (Farm f : farms) if (f.id.equals(id)) return f;
        return null;
    }

    void load() {
        if (file == null) return;
        farms.clear();
        pendingForget.clear();
        forgetAt.clear();
        pendingAttach.clear();
        disabled = false;
        loadedFromBackup = false;
        if (!Files.exists(file) && !Files.exists(backup)) return;
        if (Files.exists(backup)) AtomicFiles.ownerOnly(backup);
        if (Files.exists(file)) {
            try {
                read(StateFile.read(file));
                AtomicFiles.ownerOnly(file);
                return;
            } catch (RuntimeException e) {
                System.err.println("Can't read " + file.getFileName() + "; trying the backup.");
            }
        }
        try {
            farms.clear();
            pendingForget.clear();
            forgetAt.clear();
            pendingAttach.clear();
            read(StateFile.read(backup));
            loadedFromBackup = true;
            System.err.println("Farms restored from " + backup.getFileName() + ".");
        } catch (RuntimeException e) {
            disabled = true;
            farms.clear();
            System.err.println("Farms are off: " + file.getFileName() + " and its backup can't be read, and neither will be overwritten.");
        }
    }

    private void read(JsonObject root) {
        if (root == null) throw new IllegalArgumentException("unreadable");
        rev = Math.max(1, root.get("rev").getAsLong());
        JsonArray list = root.getAsJsonArray("farms");
        if (list.size() > MAX_FARMS) throw new IllegalArgumentException("too many farms");
        for (JsonElement e : list) {
            Farm f = StateFile.STORE.fromJson(e, Farm.class);
            if (f == null || f.id == null || !FarmVocab.FARM_ID.matcher(f.id).matches() || f.name == null || f.mode == null) {
                throw new IllegalArgumentException("a farm breaks the format");
            }
            if (f.produces == null) f.produces = new ArrayList<>();
            if (f.needs == null) f.needs = new ArrayList<>();
            if (f.session == null) f.session = new Session();
            if (f.thresholds == null) f.thresholds = new Thresholds();
            if (f.travel == null) f.travel = new Travel();
            farms.add(f);
        }
        if (root.get("pendingForget") instanceof JsonObject pf) {
            for (Map.Entry<String, JsonElement> en : pf.entrySet()) {
                List<String> ids = new ArrayList<>();
                for (JsonElement id : en.getValue().getAsJsonArray()) ids.add(id.getAsString());
                pendingForget.put(en.getKey(), ids);
            }
        }
        if (root.get("forgetAt") instanceof JsonObject fa) {
            for (Map.Entry<String, JsonElement> en : fa.entrySet()) forgetAt.put(en.getKey(), en.getValue().getAsLong());
        }
        if (root.get("pendingAttach") instanceof JsonObject pa) {
            for (Map.Entry<String, JsonElement> en : pa.entrySet()) pendingAttach.put(en.getKey(), StateFile.STORE.fromJson(en.getValue(), Attach.class));
        }
    }

    /** Puts a snapshot from {@link #toRoot()} back (a failed mutation). */
    void restore(JsonObject root) {
        farms.clear();
        pendingForget.clear();
        forgetAt.clear();
        pendingAttach.clear();
        read(root);
    }

    /** Writes the whole store (the last clean file becomes the backup first). */
    void save() throws IOException {
        if (file == null) return;
        if (disabled) throw new IOException("farms are off: farms.json can't be read");
        JsonObject root = toRoot();
        if (Files.exists(file) && !loadedFromBackup && StateFile.read(file) != null) {
            StateFile.writeBackup(backup, Files.readAllBytes(file));
        }
        StateFile.write(file, root);
        loadedFromBackup = false;
    }

    JsonObject toRoot() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("rev", rev);
        JsonArray list = new JsonArray();
        for (Farm f : farms) list.add(StateFile.STORE.toJsonTree(f));
        root.add("farms", list);
        root.add("pendingForget", StateFile.STORE.toJsonTree(pendingForget));
        root.add("forgetAt", StateFile.STORE.toJsonTree(forgetAt));
        root.add("pendingAttach", StateFile.STORE.toJsonTree(pendingAttach));
        return root;
    }
}

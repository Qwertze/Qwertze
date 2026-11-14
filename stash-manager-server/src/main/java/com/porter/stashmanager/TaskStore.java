package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** The task records (tasks.json, TASK-CONTRACT v1.0 slice): open tasks plus the newest 2,000 finished ones. */
final class TaskStore {
    static final int KEEP_FINISHED = 2000;
    static final int MAX_TASKS = 4000;

    /** A task. Only {@code farm.session} exists in this slice. {@link #toJson()} is the API form; the rest is internal. */
    static final class Task {
        String id;
        String kind = FarmVocab.KIND_FARM;
        String title = FarmVocab.FARM_TITLE;
        String state = "pending";
        String pauseReason;
        int priority = 100;
        String band = "low";
        String interrupt = "timed";
        long minRunMs;
        String server;
        String botId;
        String role = "farmer";
        List<String> caps = new ArrayList<>(List.of("farm"));
        JsonObject params = new JsonObject();
        String farmId;
        String leaseBot;
        long leaseExpires;
        int attempts;
        int maxAttempts = 3;
        long nextAttemptAt;
        String phase;
        long progressDone;
        long progressTotal;
        JsonObject checkpoint;
        JsonObject result;
        String errorCode;
        String errorText;
        String origin = "website";
        String createdById;
        String createdByName;
        long createdAt;
        long updatedAt;
        long finishedAt;
        // internal bookkeeping (not in the API form)
        int epoch;
        long lastSeq;
        long pausedSince;
        long pausedTotal;

        boolean open() {
            return state.equals("pending") || state.equals("running") || state.equals("paused");
        }

        boolean terminal() {
            return !open();
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("kind", kind);
            o.addProperty("title", title);
            o.addProperty("state", state);
            o.addProperty("pauseReason", pauseReason);
            o.addProperty("priority", priority);
            o.addProperty("band", band);
            o.addProperty("interrupt", interrupt);
            o.addProperty("minRunMs", minRunMs);
            JsonObject scope = new JsonObject();
            scope.addProperty("server", server);
            o.add("scope", scope);
            JsonObject target = new JsonObject();
            target.addProperty("botId", botId);
            target.addProperty("role", role);
            JsonArray c = new JsonArray();
            caps.forEach(c::add);
            target.add("caps", c);
            o.add("target", target);
            o.add("params", params.deepCopy());
            JsonObject ref = new JsonObject();
            ref.addProperty("farmId", farmId);
            o.add("ref", ref);
            if (leaseBot == null) {
                o.add("lease", JsonNull.INSTANCE);
            } else {
                o.add("lease", leaseJson());
            }
            o.addProperty("attempts", attempts);
            o.addProperty("maxAttempts", maxAttempts);
            o.addProperty("nextAttemptAt", nextAttemptAt);
            o.addProperty("phase", phase);
            JsonObject p = new JsonObject();
            p.addProperty("done", progressDone);
            p.addProperty("total", progressTotal);
            o.add("progress", p);
            o.add("checkpoint", checkpoint == null ? JsonNull.INSTANCE : checkpoint.deepCopy());
            o.add("result", result == null ? JsonNull.INSTANCE : result.deepCopy());
            if (errorCode == null) {
                o.add("error", JsonNull.INSTANCE);
            } else {
                JsonObject e = new JsonObject();
                e.addProperty("code", errorCode);
                e.addProperty("text", errorText);
                o.add("error", e);
            }
            o.addProperty("origin", origin);
            JsonObject by = new JsonObject();
            by.addProperty("id", createdById);
            by.addProperty("name", createdByName);
            o.add("createdBy", by);
            o.addProperty("createdAt", createdAt);
            o.addProperty("updatedAt", updatedAt);
            if (finishedAt == 0) o.add("finishedAt", JsonNull.INSTANCE);
            else o.addProperty("finishedAt", finishedAt);
            return o;
        }

        JsonObject leaseJson() {
            JsonObject l = new JsonObject();
            l.addProperty("taskId", id);
            l.addProperty("botId", leaseBot);
            l.addProperty("epoch", epoch);
            l.addProperty("expiresAt", leaseExpires);
            return l;
        }
    }

    private final Path file;
    private volatile LongSupplier clock;
    final List<Task> tasks = new ArrayList<>(); // oldest first
    /** When tasks.json was last written (so the time a task sat paused while the server was down is not counted). */
    long savedAt;

    /** The service's clock (savedAt and the time a task sat paused are on the same one). */
    void clock(LongSupplier clock) {
        this.clock = clock;
    }

    TaskStore(Path dataDir, LongSupplier clock) {
        this.file = dataDir == null ? null : dataDir.resolve("tasks.json");
        this.clock = clock;
    }

    void load() {
        if (file == null) return;
        JsonObject root = StateFile.read(file);
        if (root == null) {
            if (java.nio.file.Files.exists(file)) {
                System.err.println("tasks.json unreadable, tasks reset");
                StateFile.moveAside(file, clock.getAsLong());
            }
            return;
        }
        try {
            savedAt = root.has("savedAt") ? root.get("savedAt").getAsLong() : 0;
            for (JsonElement e : root.getAsJsonArray("tasks")) {
                Task t = StateFile.STORE.fromJson(e, Task.class);
                if (t == null || t.id == null || !FarmVocab.TASK_ID.matcher(t.id).matches() || tasks.size() >= MAX_TASKS) continue;
                if (t.params == null) t.params = new JsonObject();
                if (t.caps == null) t.caps = new ArrayList<>(List.of("farm"));
                tasks.add(t);
            }
            AtomicFiles.ownerOnly(file);
        } catch (RuntimeException e) {
            tasks.clear();
            System.err.println("tasks.json unreadable, tasks reset");
            StateFile.moveAside(file, clock.getAsLong());
        }
    }

    Task get(String id) {
        for (Task t : tasks) if (t.id.equals(id)) return t;
        return null;
    }

    boolean has(String id) {
        return get(id) != null;
    }

    /** The whole store as it is written (also the snapshot a failed farm mutation goes back to). */
    JsonObject toRoot() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("savedAt", clock.getAsLong());
        JsonArray list = new JsonArray();
        for (Task t : tasks) list.add(StateFile.STORE.toJsonTree(t));
        root.add("tasks", list);
        return root;
    }

    /** Puts a snapshot from {@link #toRoot()} back. */
    void restore(JsonObject root) {
        tasks.clear();
        for (JsonElement e : root.getAsJsonArray("tasks")) tasks.add(StateFile.STORE.fromJson(e, Task.class));
    }

    void save() throws IOException {
        if (file == null) return;
        // Drop finished tasks beyond the newest 2,000 (oldest first in the list).
        int finished = 0;
        for (Task t : tasks) if (t.terminal()) finished++;
        if (finished > KEEP_FINISHED) {
            int drop = finished - KEEP_FINISHED;
            for (java.util.Iterator<Task> it = tasks.iterator(); it.hasNext() && drop > 0; ) {
                if (it.next().terminal()) {
                    it.remove();
                    drop--;
                }
            }
        }
        StateFile.write(file, toRoot());
    }
}

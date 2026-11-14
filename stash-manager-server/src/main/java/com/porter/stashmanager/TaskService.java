package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The task machinery of TASK-CONTRACT v1.0 (slice): bot profiles and roles, STOP, {@code tasks/sync}, claim and report with
 * epoch and seq fencing, leases, and the closed vocabularies of the farm kind. One monitor guards every record; a call
 * validates its whole body before it changes anything and writes the files before it returns.
 *
 * <p>Trust (TASK "Identity and trust"): a holder of the API key is trusted; leases fence stale writers only. What a bot
 * says about itself ({@code farmer}, {@code programs}, {@code draft}, {@code caps}, {@code status}) never grants a role or
 * a permission (mitigation d). The one thing that tightens: a bot that has ever reported {@code farmer.enabled} counts as
 * a farmer for owner-only reads and the activity allowlist.
 *
 * <p>Writes. A dirty flag is cleared only after its file was written; a failed write leaves the flag set (and the other
 * files are still tried), and {@link #housekeeping} retries. Farm mutations run through {@link #transaction}: on a failed
 * write the farm and task state go back to what they were and the caller gets {@code 503 storage_error}. STOP and role
 * changes stay applied in memory (a STOP must never be lost to a full disk) and the answer says "applied, not saved".
 *
 * <p>Hot paths. {@link #isFarmer}, {@link #rolesOf} and {@link #heartbeatOps} read volatile snapshots and never wait for the
 * monitor, so activity and heartbeat requests are not queued behind a sync.
 */
final class TaskService {
    static final long LEASE_MS = 90_000;
    static final long PAUSE_TIMEOUT_MS = 60 * 60_000;
    static final long ONLINE_MS = 45_000;
    static final long SYNC_MIN_MS = 1_000;
    static final long STOP_CONFIRM_MS = 30_000;
    /** A task nobody claimed (pending, no lease) is cancelled after this long: offer_timeout. */
    static final long OFFER_TTL_MS = 10 * 60_000;
    /** Dirty files are retried this often in the background (an "applied, not saved" STOP must not wait for another call). */
    static final long FLUSH_RETRY_MS = 30_000;
    static final int UNKNOWN_PER_HOUR = 4;
    /** Distinct unknown bot ids per hour over all addresses (one profile slot each, so about as many as MAX_BOTS). */
    static final int UNKNOWN_GLOBAL_PER_HOUR = 64;
    static final int MAX_UNKNOWN_ADDRESSES = 1024;
    static final long HOUR_MS = 60 * 60_000;
    static final int MAX_CANCEL = 20;
    static final int MAX_BODY = 64 * 1024;
    /** Lease renewals are written at most this often (a restart extends every lease anyway). */
    static final long LEASE_SAVE_MS = 30_000;
    /** A sync that changed nothing but lastSync and lastStatus is written to bots.json at most this often. */
    static final long BOTS_SAVE_MS = 60_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern STATUS_DIM = FarmVocab.NAMESPACED;
    /** A sync's (and a farm's) game server: a host name with an optional port. */
    static final Pattern SERVER = Pattern.compile("^[A-Za-z0-9.-]{1,253}(:\\d{1,5})?$");

    final OpsStore ops;
    final TaskStore tasks;
    final FarmStore farms;
    private final BotHub hub;
    private final AuditLog audit;
    private volatile LongSupplier clock = System::currentTimeMillis;
    private DoubleSupplier jitter = () -> 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
    private Predicate<String> otherIds = id -> false;
    private final List<Consumer<String>> flipHooks = new ArrayList<>();
    /** Every task id ever used here (lock-free: the pearl engine asks while it holds its own lock). */
    private final Set<String> taskIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Published snapshots for the lock-free readers. */
    private volatile Set<String> farmerIds = Set.of();
    private volatile Map<String, List<String>> rolesSnap = Map.of();
    private volatile boolean stopAllOn;
    private volatile long opsRev;
    /** Source address (IPv6 by /64) to (unknown bot id to when it was first seen), for the new-profile rate limit (mitigation b). */
    private final Map<String, Map<String, Long>> unknown = new HashMap<>();
    private final Map<String, Long> unknownGlobal = new HashMap<>();
    private boolean dirtyTasks, dirtyOps, dirtyBots, dirtyFarms;
    private long lastLeaseSave, lastBotsSave;

    TaskService(OpsStore ops, TaskStore tasks, FarmStore farms, BotHub hub, AuditLog audit) {
        this.ops = ops;
        this.tasks = tasks;
        this.farms = farms;
        this.hub = hub;
        this.audit = audit;
        for (TaskStore.Task t : tasks.tasks) taskIds.add(t.id);
        synchronized (this) {
            refreshSnapshots();
        }
    }

    void clock(LongSupplier clock) {
        this.clock = clock;
        tasks.clock(clock);
    }

    void jitter(DoubleSupplier jitter) {
        this.jitter = jitter;
    }

    /** Ids already used by other task-like records (pearl trips share the t- id space, FI-13). */
    void otherIds(Predicate<String> otherIds) {
        this.otherIds = otherIds;
    }

    /** Called with the bot id when a bot first becomes a farmer (the stored activity, state and results must go). */
    void onFarmerFlip(Consumer<String> hook) {
        flipHooks.add(hook);
    }

    long now() {
        return clock.getAsLong();
    }

    AuditLog audit() {
        return audit;
    }

    BotHub hub() {
        return hub;
    }

    /**
     * After the stores were loaded (once at start): every open leased task gets a fresh lease, so a restart does not fail a
     * task on its last attempt before its bot can sync; the time a task sat paused while the server was down is not counted;
     * and a repaired file (see {@link OpsStore}) is written out at once. Safe to call again.
     */
    synchronized void recoverAfterLoad() {
        long now = now();
        for (TaskStore.Task t : tasks.tasks) {
            if (!t.open()) continue;
            if (t.leaseBot != null) t.leaseExpires = now + LEASE_MS;
            if (t.state.equals("paused") && t.pausedSince > 0) {
                if (tasks.savedAt >= t.pausedSince) t.pausedTotal += tasks.savedAt - t.pausedSince;
                t.pausedSince = now;
            }
            dirtyTasks = true;
        }
        // A STOP applies (a forced one included): nobody will report a task that holds no lease, so it ends here.
        for (TaskStore.Task t : new ArrayList<>(tasks.tasks)) {
            if (t.open() && t.leaseBot == null && stopApplies(ops.bots.get(t.botId))) finish(t, "cancelled", "ops_stop", null, now);
        }
        tasks.savedAt = 0;
        if (ops.repaired) dirtyOps = dirtyBots = true;
        refreshSnapshots();
        tryFlush();
    }

    // ---------- snapshots for the lock-free readers ----------

    /** Rebuilds the farmer set, the roles and the STOP counter (monitor held, or the constructor). */
    private void refreshSnapshots() {
        Set<String> f = new java.util.HashSet<>();
        Map<String, List<String>> r = new HashMap<>();
        for (OpsStore.Profile p : ops.bots.values()) {
            if (p.farmer()) f.add(p.botId);
            r.put(p.botId, List.copyOf(p.roles));
        }
        farmerIds = Set.copyOf(f);
        rolesSnap = Map.copyOf(r);
        stopAllOn = ops.stopAll.on;
        opsRev = ops.rev;
    }

    // ---------- dirty flags and the write ----------

    void dirtyTasks() {
        dirtyTasks = true;
    }

    void dirtyOps() {
        dirtyOps = true;
    }

    void dirtyBots() {
        dirtyBots = true;
    }

    void dirtyFarms() {
        dirtyFarms = true;
    }

    private boolean anyDirty() {
        return dirtyTasks || dirtyOps || dirtyBots || dirtyFarms;
    }

    /**
     * Writes whatever changed. A flag is cleared only after its file was written; on a failure it is set again, the other files
     * are still tried, and the first failure is thrown at the end.
     */
    void flush() {
        IOException first = null;
        if (dirtyBots) {
            dirtyBots = false;
            try {
                ops.saveBots();
                lastBotsSave = now();
            } catch (IOException e) {
                dirtyBots = true;
                first = e;
            }
        }
        if (dirtyOps) {
            dirtyOps = false;
            try {
                ops.saveOps();
            } catch (IOException e) {
                dirtyOps = true;
                if (first == null) first = e;
            }
        }
        if (dirtyTasks) {
            dirtyTasks = false;
            try {
                tasks.save();
                lastLeaseSave = now();
            } catch (IOException e) {
                dirtyTasks = true;
                if (first == null) first = e;
            }
        }
        if (dirtyFarms) {
            dirtyFarms = false;
            if (!farms.disabled()) {
                try {
                    farms.save();
                } catch (IOException e) {
                    dirtyFarms = true;
                    if (first == null) first = e;
                }
            }
        }
        if (first != null) throw new UncheckedIOException(first);
    }

    /** The background retry: writes whatever is still dirty (a failed write stays dirty and is tried again next time). */
    synchronized void retryFlush() {
        try {
            if (anyDirty()) tryFlush();
        } catch (RuntimeException e) {
            System.err.println("state_flush_failed");
        }
    }

    /** {@link #flush()} that reports instead of throwing: false when something could not be written (it stays dirty). */
    boolean tryFlush() {
        try {
            flush();
            return true;
        } catch (UncheckedIOException e) {
            return false;
        }
    }

    /**
     * A farm mutation as one step (monitor held): housekeeping first, then the body, then the write. When the write fails the
     * farm and task records go back to what they were before the body ran, they are marked dirty so the restored state is
     * written again later, and the caller gets 503 storage_error. A Fail from the body still writes what housekeeping changed.
     */
    <T> T transaction(Supplier<T> body) {
        synchronized (this) {
            housekeeping(now());
            JsonObject fs = farms.toRoot();
            JsonObject ts = tasks.toRoot();
            T result;
            try {
                result = body.get();
            } catch (RuntimeException e) {
                if (!(e instanceof Fail)) {
                    farms.restore(fs);
                    tasks.restore(ts);
                }
                tryFlush();
                throw e;
            }
            // Only the files a farm mutation can change count: a STOP file that is failing for its own reasons is not this call's failure.
            tryFlush();
            if (dirtyTasks || (dirtyFarms && !farms.disabled())) {
                farms.restore(fs);
                tasks.restore(ts);
                dirtyFarms = true;
                dirtyTasks = true;
                throw new Fail(503, "storage_error");
            }
            return result;
        }
    }

    // ---------- queries other routes need ----------

    /** Whether the bot counts as a farmer: the owner's role, or it has ever reported farmer.enabled (sticky). Lock-free. */
    boolean isFarmer(String botId) {
        return botId != null && farmerIds.contains(botId);
    }

    /** A bot's roles (lock-free). */
    List<String> rolesOf(String botId) {
        return rolesSnap.getOrDefault(botId, List.of());
    }

    /** The heartbeat's "ops" field: whether everything is stopped, and the counter. Lock-free. */
    JsonObject heartbeatOps() {
        JsonObject o = new JsonObject();
        o.addProperty("stop", stopAllOn);
        o.addProperty("rev", opsRev);
        return o;
    }

    /** Whether a task id exists (lock-free; the pearl engine checks its new ids with it). */
    boolean taskIdUsed(String id) {
        return taskIds.contains(id);
    }

    boolean stopApplies(OpsStore.Profile p) {
        return ops.stopAll.on || (p != null && p.stop.on);
    }

    /** All the bots' ids, for the opaque-id map. */
    synchronized List<String> botIds() {
        return new ArrayList<>(ops.bots.keySet());
    }

    // ---------- housekeeping: lease expiry and the pause timeout ----------

    void housekeeping(long now) {
        if (anyDirty()) tryFlush(); // a write that failed earlier is retried
        for (TaskStore.Task t : new ArrayList<>(tasks.tasks)) {
            if (!t.open()) continue;
            if (t.state.equals("pending") && t.leaseBot == null && t.createdAt > 0 && now - t.createdAt > OFFER_TTL_MS) {
                finish(t, "cancelled", "offer_timeout", null, now); // offered, never claimed
                continue;
            }
            if (t.leaseBot != null && t.leaseExpires <= now) {
                t.leaseBot = null;
                if (stopApplies(ops.bots.get(t.botId))) {
                    finish(t, "cancelled", "ops_stop", null, now);
                    continue;
                }
                if (t.attempts >= t.maxAttempts) {
                    finish(t, "failed", "lease_lost", null, now);
                    continue;
                }
                setState(t, "paused", "lease_lost", now);
                t.nextAttemptAt = now + (long) (60_000.0 * (1L << Math.min(t.attempts, 10)) * jitter.getAsDouble());
                dirtyTasks = true;
            }
            if (t.state.equals("paused") && t.pausedSince > 0 && t.pausedTotal + (now - t.pausedSince) > PAUSE_TIMEOUT_MS) {
                finish(t, "cancelled", "pause_timeout", null, now);
            }
        }
    }

    void setState(TaskStore.Task t, String state, String pauseReason, long now) {
        if (t.state.equals("paused") && !state.equals("paused")) {
            t.pausedTotal += Math.max(0, now - t.pausedSince);
            t.pausedSince = 0;
        }
        if (state.equals("paused") && !t.state.equals("paused")) t.pausedSince = now;
        t.state = state;
        t.pauseReason = state.equals("paused") ? pauseReason : null;
        t.updatedAt = now;
        dirtyTasks = true;
    }

    /** Ends a task for good (completed, failed or cancelled) and records it on the farm. */
    void finish(TaskStore.Task t, String state, String code, JsonObject result, long now) {
        setState(t, state, null, now);
        t.errorCode = code;
        t.errorText = FarmVocab.text(code);
        t.result = result;
        t.finishedAt = now;
        t.leaseBot = null;
        FarmStore.Farm f = t.farmId == null ? null : farms.get(t.farmId);
        if (f != null) {
            FarmStore.LastSession ls = new FarmStore.LastSession();
            ls.taskId = t.id;
            ls.state = state;
            ls.at = now;
            String ended = code;
            if (result != null && result.get("endedBy") != null) ended = result.get("endedBy").getAsString();
            if (ended == null) ended = state.equals("completed") ? FarmVocab.COMPLETED_CODE : state;
            ls.endedBy = ended;
            if (result != null) {
                ls.loops = result.get("loops").getAsLong();
                ls.recoveries = result.get("recoveries").getAsLong();
                ls.runtimeMs = result.get("runtimeMs").getAsLong();
            }
            f.lastSession = ls;
            f.updatedAt = now;
            dirtyFarms = true;
        }
        dirtyTasks = true;
    }

    // ---------- ids ----------

    String newTaskId() {
        while (true) {
            byte[] b = new byte[6];
            RANDOM.nextBytes(b);
            String id = "t-" + HexFormat.of().formatHex(b);
            if (!taskIds.contains(id) && !tasks.has(id) && !otherIds.test(id)) {
                taskIds.add(id);
                return id;
            }
        }
    }

    // ---------- profiles ----------

    /** The profile of a bot the server knows, creating one for a bot that said hello (never for a stranger). Null otherwise. */
    OpsStore.Profile profile(String botId, long now, boolean create) {
        OpsStore.Profile p = ops.bots.get(botId);
        if (p != null || !create) return p;
        if (hub.bot(botId) == null || ops.bots.size() >= OpsStore.MAX_BOTS) return null;
        p = new OpsStore.Profile();
        p.botId = botId;
        p.firstSeen = now;
        ops.bots.put(botId, p);
        dirtyBots = true;
        refreshSnapshots();
        return p;
    }

    /** The bot just became a farmer: the snapshot already says so (readers see it), then what was stored for it is cleared. */
    private void becameFarmer(String botId) {
        for (Consumer<String> h : flipHooks) h.accept(botId);
    }

    /** A signature of what a sync can change in a profile apart from lastSync and lastStatus (those are written on a timer). */
    private static String signature(OpsStore.Profile p) {
        return p.type + "|" + p.player + "|" + p.server + "|" + p.contract + "|" + p.caps + "|" + p.stopAck + "|" + p.farmerSeen + "|"
            + p.farmer + "|" + p.programs + "|" + p.draft + "|" + p.runningTask + "|" + p.roles;
    }

    // ---------- tasks/sync ----------

    private record Running(String taskId, int epoch, String state, String phase, long done, long total, boolean hasProgress) {
    }

    private enum Renewal { STALE, RENEWED, KEPT }

    /** Runs a bot call and writes its changes; a refusal still writes what housekeeping changed, a failed write throws UncheckedIOException. */
    private <T> T flushing(Supplier<T> body) {
        int before = dirtyMask(); // a file that was already failing before this call is not this call's failure
        T r;
        try {
            r = body.get();
        } catch (RuntimeException e) {
            tryFlush();
            throw e;
        }
        if (!tryFlush() && (dirtyMask() & ~before) != 0) throw new UncheckedIOException(new IOException("write failed"));
        return r;
    }

    private int dirtyMask() {
        return (dirtyBots ? 1 : 0) | (dirtyOps ? 2 : 0) | (dirtyTasks ? 4 : 0) | (dirtyFarms && !farms.disabled() ? 8 : 0);
    }

    synchronized JsonObject sync(JsonObject o, String headerBot, String address) {
        return flushing(() -> syncLocked(o, headerBot, address));
    }

    private JsonObject syncLocked(JsonObject o, String headerBot, String address) {
        long now = now();
        // ---- validate everything first (nothing changes on a refusal) ----
        String botId = Fail.string(o.get("botId"), "botId", FarmVocab.BOT_ID, 64);
        if (headerBot == null || headerBot.equals("-") || !headerBot.equals(botId)) throw new Fail(400, "botid_mismatch");
        String type = Fail.string(o.get("type"), "type", null, 8);
        if (!type.equals("addon") && !type.equals("plugin")) throw Fail.badField("type");
        String player = Fail.present(o, "player") ? Fail.string(o.get("player"), "player", FarmVocab.PLAYER, 16) : null;
        String server = Fail.present(o, "server") ? serverName(o.get("server"), "server") : null;
        String contract = Fail.string(o.get("contract"), "contract", null, 8);
        List<String> caps = new ArrayList<>();
        if (Fail.present(o, "caps")) {
            if (!o.get("caps").isJsonArray() || o.getAsJsonArray("caps").size() > 16) throw Fail.badField("caps");
            for (JsonElement c : o.getAsJsonArray("caps")) caps.add(Fail.string(c, "caps", null, 32));
        }
        JsonObject status = Fail.present(o, "status") ? parseStatus(o.get("status"), now) : null;
        Running running = Fail.present(o, "running") ? parseRunning(o.get("running")) : null;
        long stopAck = Fail.present(o, "stopAck") ? Fail.integer(o.get("stopAck"), "stopAck", 0, FarmVocab.MAX_INT) : -1;
        FarmService.FarmerIn farmerIn = Fail.present(o, "farmer") ? FarmService.parseFarmer(o) : null;

        // ---- who is it ----
        OpsStore.Profile p = ops.bots.get(botId);
        if (p == null) p = profile(botId, now, true);
        if (p == null) {
            housekeeping(now);
            return unknownReply(botId, address, now);
        }
        if (p.lastSync > 0 && now - p.lastSync < SYNC_MIN_MS) throw new Fail(429, "slow_down", null, 2, null);

        // ---- the bot's own task is renewed before anything expires (a late sync keeps its lease) ----
        Renewal renewal = running == null ? null : renew(botId, running, now);
        housekeeping(now);

        // ---- the profile ----
        String before = signature(p);
        p.type = type;
        p.player = player;
        p.server = server;
        p.contract = contract;
        p.caps = caps;
        p.lastStatus = status;
        p.lastSync = now;
        if (stopAck >= 0) p.stopAck = Math.max(p.stopAck, stopAck);
        if (farmerIn != null) FarmService.applyFarmer(this, p, farmerIn, now);
        p.runningTask = running == null ? null : running.taskId;
        if (!before.equals(signature(p)) || now - lastBotsSave >= BOTS_SAVE_MS) dirtyBots = true;

        // ---- the reply ----
        Set<String> cancelSet = new LinkedHashSet<>();
        JsonObject lease = null;
        if (running != null) {
            if (renewal == Renewal.STALE) {
                cancelSet.add(running.taskId);
            } else {
                TaskStore.Task t = tasks.get(running.taskId);
                if (renewal == Renewal.RENEWED && t != null && t.leaseBot != null) lease = t.leaseJson();
            }
        }
        boolean stop = stopApplies(p);
        if (stop) {
            for (TaskStore.Task t : tasks.tasks) {
                if (t.open() && botId.equals(t.botId) && cancelSet.size() < MAX_CANCEL) cancelSet.add(t.id);
            }
        }
        if (lease == null && renewal != Renewal.KEPT) {
            for (TaskStore.Task t : tasks.tasks) {
                if (t.open() && botId.equals(t.leaseBot)) {
                    lease = t.leaseJson();
                    break;
                }
            }
        }
        List<String> cancel = cancelSet.stream().limit(MAX_CANCEL).toList();

        // ---- the offer (mitigation c: only to a bot the owner made a farmer) ----
        // Never in a reply that also cancels something: the bot has to drop the old task first (a new session of the same farm
        // would otherwise be offered while the cancelled one is still parked), so the offer waits for the next sync.
        JsonObject offer = null;
        if (!stop && lease == null && cancel.isEmpty() && renewal != Renewal.KEPT && p.hasRole("farmer") && "1.0".equals(contract) && !farms.disabled()) {
            for (TaskStore.Task t : tasks.tasks) {
                if (!t.open() || !botId.equals(t.botId) || t.leaseBot != null) continue;
                boolean fresh = t.state.equals("pending");
                boolean retry = t.state.equals("paused") && now >= t.nextAttemptAt && t.attempts < t.maxAttempts;
                if (!fresh && !retry) continue;
                offer = new JsonObject();
                offer.addProperty("id", t.id);
                offer.addProperty("kind", t.kind);
                offer.addProperty("title", t.title);
                offer.add("params", t.params.deepCopy());
                JsonObject target = new JsonObject();
                target.addProperty("botId", t.botId);
                target.addProperty("role", t.role);
                JsonArray c = new JsonArray();
                t.caps.forEach(c::add);
                target.add("caps", c);
                offer.add("target", target);
                break;
            }
        }

        JsonObject out = base(p, now);
        out.add("lease", lease == null ? JsonNull.INSTANCE : lease);
        out.add("offer", offer == null ? JsonNull.INSTANCE : offer);
        JsonArray cj = new JsonArray();
        cancel.forEach(cj::add);
        out.add("cancel", cj);
        if (farmerIn != null) FarmService.syncReply(this, p, farmerIn, out);
        return out;
    }

    /**
     * Applies a sync's running object to the bot's own task. STALE: not the bot's task, another epoch, unknown or finished (the
     * bot must drop it). RENEWED: the lease runs to now + 90 s. KEPT: the bot is right about the task but it was parked by a lost
     * lease and the bot says it is parked too: the lease is not renewed (its next report decides), nothing is cancelled.
     */
    private Renewal renew(String botId, Running r, long now) {
        TaskStore.Task t = tasks.get(r.taskId);
        OpsStore.Profile owner = ops.bots.get(botId);
        if (t == null || !botId.equals(t.botId) || t.epoch != r.epoch || t.terminal() || owner == null || !owner.hasRole("farmer")) {
            return Renewal.STALE;
        }
        boolean lost = t.state.equals("paused") && "lease_lost".equals(t.pauseReason);
        if (lost && !r.state.equals("running")) return Renewal.KEPT;
        t.leaseBot = botId;
        t.leaseExpires = now + LEASE_MS;
        if (lost) setState(t, "running", null, now);
        if (r.phase != null) t.phase = r.phase;
        if (r.hasProgress) {
            t.progressDone = r.done;
            t.progressTotal = r.total;
        }
        if (now - lastLeaseSave >= LEASE_SAVE_MS) dirtyTasks = true;
        return Renewal.RENEWED;
    }

    /** "12.3.4.5" or an IPv6 address: the key of the stranger limit (IPv6 by its /64, so one subnet is one source). */
    static String addressKey(String address) {
        if (address == null) return "?";
        int zone = address.indexOf('%');
        String a = zone >= 0 ? address.substring(0, zone) : address;
        if (!a.contains(":")) return a;
        String[] g = a.split(":", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, g.length); i++) sb.append(i == 0 ? "" : ":").append(g[i]);
        return sb.toString();
    }

    /** The reply to a bot with no profile (an unknown id is never stored): no roles, no offer. */
    private JsonObject unknownReply(String botId, String address, long now) {
        unknownGlobal.values().removeIf(at -> now - at >= HOUR_MS);
        String key = addressKey(address);
        Map<String, Long> seen = unknown.get(key);
        if (seen == null && unknown.size() >= MAX_UNKNOWN_ADDRESSES) {
            unknown.values().removeIf(m -> m.values().stream().allMatch(at -> now - at >= HOUR_MS));
            if (unknown.size() >= MAX_UNKNOWN_ADDRESSES) throw new Fail(429, "slow_down", null, 60, null);
        }
        if (seen == null) {
            seen = new HashMap<>();
            unknown.put(key, seen);
        }
        seen.values().removeIf(at -> now - at >= HOUR_MS);
        if (!seen.containsKey(botId)) {
            if (seen.size() >= UNKNOWN_PER_HOUR) throw new Fail(429, "slow_down", null, 60, null);
            if (!unknownGlobal.containsKey(botId) && unknownGlobal.size() >= UNKNOWN_GLOBAL_PER_HOUR) throw new Fail(429, "slow_down", null, 60, null);
            seen.put(botId, now);
            unknownGlobal.putIfAbsent(botId, now);
        }
        JsonObject out = base(null, now);
        out.add("lease", JsonNull.INSTANCE);
        out.add("offer", JsonNull.INSTANCE);
        out.add("cancel", new JsonArray());
        return out;
    }

    private JsonObject base(OpsStore.Profile p, long now) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("now", now);
        JsonArray roles = new JsonArray();
        if (p != null) p.roles.forEach(roles::add);
        out.add("roles", roles);
        JsonObject stop = new JsonObject();
        boolean bot = p != null && p.stop.on;
        stop.addProperty("global", ops.stopAll.on);
        stop.addProperty("bot", bot);
        stop.addProperty("rev", ops.rev);
        String reason = ops.stopAll.on ? ops.stopAll.reason : bot ? p.stop.reason : null;
        stop.addProperty("reason", reason);
        out.add("stop", stop);
        return out;
    }

    /** A game server (host name, optional port): the pattern of the sync and of a farm. */
    static String serverName(JsonElement e, String field) {
        String s = Fail.string(e, field, SERVER, 64);
        return s;
    }

    private static JsonObject parseStatus(JsonElement e, long now) {
        JsonObject s = Fail.object(e, "status");
        JsonObject out = new JsonObject();
        if (Fail.present(s, "inGame")) out.addProperty("inGame", Fail.bool(s.get("inGame"), "status.inGame"));
        if (Fail.present(s, "dim")) out.addProperty("dim", Fail.string(s.get("dim"), "status.dim", STATUS_DIM, 64));
        if (Fail.present(s, "health")) out.addProperty("health", Fail.integer(s.get("health"), "status.health", 0, 20));
        if (Fail.present(s, "food")) out.addProperty("food", Fail.integer(s.get("food"), "status.food", 0, 20));
        if (Fail.present(s, "freeSlots")) out.addProperty("freeSlots", Fail.integer(s.get("freeSlots"), "status.freeSlots", 0, 41));
        if (Fail.present(s, "totems")) out.addProperty("totems", Fail.integer(s.get("totems"), "status.totems", 0, 64));
        out.addProperty("at", now);
        return out;
    }

    private static Running parseRunning(JsonElement e) {
        JsonObject r = Fail.object(e, "running");
        String taskId = Fail.string(r.get("taskId"), "running.taskId", FarmVocab.TASK_ID, 14);
        int epoch = (int) Fail.integer(r.get("epoch"), "running.epoch", 1, FarmVocab.MAX_INT);
        String state = Fail.string(r.get("state"), "running.state", null, 16);
        if (!state.equals("running") && !state.equals("paused")) throw Fail.badField("running.state");
        if (Fail.present(r, "seq")) Fail.integer(r.get("seq"), "running.seq", 1, FarmVocab.MAX_INT);
        String phase = null;
        if (Fail.present(r, "phase")) phase = phase(r.get("phase"), "running.phase");
        boolean has = Fail.present(r, "progress");
        long done = 0, total = 0;
        if (has) {
            long[] pr = progress(r.get("progress"), "running.progress");
            done = pr[0];
            total = pr[1];
        }
        return new Running(taskId, epoch, state, phase, done, total, has);
    }

    /** A farm phase: one of the closed list, else bad_vocab (the value is never repeated). */
    static String phase(JsonElement e, String field) {
        if (!(e instanceof JsonPrimitive p) || !p.isString() || !FarmVocab.PHASES.contains(p.getAsString())) throw Fail.badVocab(field);
        return p.getAsString();
    }

    private static long[] progress(JsonElement e, String field) {
        JsonObject p = Fail.object(e, field);
        return new long[]{Fail.integer(p.get("done"), field, 0, 1_000_000), Fail.integer(p.get("total"), field, 0, 1_000_000)};
    }

    // ---------- claim ----------

    /** What a claim changes on a task, kept so a claim whose write failed can be taken back (the bot gets 503 and retries). */
    private record ClaimSnap(TaskStore.Task t, int epoch, long lastSeq, int attempts, String leaseBot, long leaseExpires, String state,
                             String pauseReason, long pausedSince, long pausedTotal, long updatedAt) {
        static ClaimSnap of(TaskStore.Task t) {
            return new ClaimSnap(t, t.epoch, t.lastSeq, t.attempts, t.leaseBot, t.leaseExpires, t.state, t.pauseReason, t.pausedSince,
                t.pausedTotal, t.updatedAt);
        }

        void restore() {
            t.epoch = epoch;
            t.lastSeq = lastSeq;
            t.attempts = attempts;
            t.leaseBot = leaseBot;
            t.leaseExpires = leaseExpires;
            t.state = state;
            t.pauseReason = pauseReason;
            t.pausedSince = pausedSince;
            t.pausedTotal = pausedTotal;
            t.updatedAt = updatedAt;
        }
    }

    synchronized JsonObject claim(String taskId, JsonObject body, String headerBot) {
        ClaimSnap[] snap = new ClaimSnap[1];
        try {
            return flushing(() -> claimLocked(taskId, body, headerBot, snap));
        } catch (UncheckedIOException e) {
            // The claim was not saved and the bot is told 503: it must not find itself holding a lease it never got. The restored
            // state is written again with the next successful flush.
            if (snap[0] != null) snap[0].restore();
            dirtyTasks = true;
            throw e;
        }
    }

    private JsonObject claimLocked(String taskId, JsonObject body, String headerBot, ClaimSnap[] snap) {
        {
            long now = now();
            housekeeping(now);
            String botId = Fail.string(body.get("botId"), "botId", FarmVocab.BOT_ID, 64);
            if (headerBot == null || headerBot.equals("-") || !headerBot.equals(botId)) throw new Fail(400, "botid_mismatch");
            TaskStore.Task t = tasks.get(taskId);
            if (t == null) throw new Fail(404, "no_task");
            if (t.terminal()) throw new Fail(409, "not_claimable");
            OpsStore.Profile p = ops.bots.get(botId);
            if (!botId.equals(t.botId) || p == null || !p.hasRole("farmer")) throw new Fail(409, "not_target");
            if (stopApplies(p)) throw new Fail(409, "stopped");
            // A live lease is never taken over, not even by the bot that holds it (a restarted bot waits for it to lapse).
            if (t.leaseBot != null) throw new Fail(409, "taken");
            if (t.state.equals("paused") && now < t.nextAttemptAt) throw new Fail(409, "not_claimable");
            if (t.attempts >= t.maxAttempts) throw new Fail(409, "not_claimable");
            snap[0] = ClaimSnap.of(t);
            t.epoch++;
            t.lastSeq = 0;
            t.attempts++;
            t.leaseBot = botId;
            t.leaseExpires = now + LEASE_MS;
            setState(t, "pending", null, now);
            JsonObject out = Http.ok();
            out.add("task", t.toJson());
            out.add("lease", t.leaseJson());
            return out;
        }
    }

    // ---------- report ----------

    synchronized JsonObject report(String taskId, JsonObject o, String headerBot, String address) {
        return flushing(() -> reportLocked(taskId, o, headerBot, address));
    }

    private JsonObject reportLocked(String taskId, JsonObject o, String headerBot, String address) {
        long now = now();
        // ---- validate ----
        String botId = Fail.string(o.get("botId"), "botId", FarmVocab.BOT_ID, 64);
        if (headerBot == null || headerBot.equals("-") || !headerBot.equals(botId)) throw new Fail(400, "botid_mismatch");
        int epoch = (int) Fail.integer(o.get("epoch"), "epoch", 1, FarmVocab.MAX_INT);
        long seq = Fail.integer(o.get("seq"), "seq", 1, FarmVocab.MAX_INT);
        String state = Fail.string(o.get("state"), "state", null, 16);
        if (!Set.of("running", "paused", "completed", "failed", "cancelled").contains(state)) throw Fail.badField("state");
        String phase = Fail.present(o, "phase") ? phase(o.get("phase"), "phase") : null;
        boolean hasProgress = Fail.present(o, "progress");
        long[] progress = hasProgress ? progress(o.get("progress"), "progress") : null;
        JsonObject checkpoint = Fail.present(o, "checkpoint") ? checkpoint(o.get("checkpoint")) : null;
        JsonObject result = Fail.present(o, "result") ? result(o.get("result")) : null;
        String code = Fail.present(o, "error") ? errorCode(o.get("error")) : null;
        // A result belongs to an end: running and paused reports carry none.
        if (result != null && (state.equals("running") || state.equals("paused"))) throw Fail.badField("result");
        switch (state) {
            case "failed" -> {
                if (code == null || !FarmVocab.FAIL_CODES.contains(code)) throw Fail.badField("error");
            }
            case "cancelled" -> {
                if (code != null && !FarmVocab.CANCEL_CODES.contains(code)) throw Fail.badField("error");
            }
            case "paused" -> {
                if (code != null && !FarmVocab.PARK_CODES.contains(code)) throw Fail.badField("error");
            }
            default -> {
                if (code != null) throw Fail.badField("error");
            }
        }
        // ---- the task ----
        housekeeping(now);
        TaskStore.Task t = tasks.get(taskId);
        if (t == null) throw new Fail(404, "no_task");
        if (!botId.equals(t.botId)) throw new Fail(409, "not_target");
        OpsStore.Profile owner = ops.bots.get(botId);
        if (!t.terminal() && (owner == null || !owner.hasRole("farmer"))) throw new Fail(409, "not_target"); // the role was taken away
        if (epoch != t.epoch) throw new Fail(409, "lease_lost");
        if (t.terminal()) {
            if (state.equals(t.state)) return dup();
            throw new Fail(409, "bad_transition");
        }
        if (seq <= t.lastSeq) return dup();
        boolean allowed = switch (t.state) {
            case "pending" -> Set.of("running", "failed", "cancelled").contains(state);
            default -> true; // running and paused: every state in the vocabulary
        };
        if (!allowed) throw new Fail(409, "bad_transition");
        // ---- apply ----
        t.lastSeq = seq;
        if (phase != null) t.phase = phase;
        if (progress != null) {
            t.progressDone = progress[0];
            t.progressTotal = progress[1];
        }
        if (checkpoint != null) t.checkpoint = checkpoint;
        t.updatedAt = now;
        dirtyTasks = true;
        switch (state) {
            case "running", "paused" -> {
                t.leaseBot = botId;
                t.leaseExpires = now + LEASE_MS;
                setState(t, state, code, now);
            }
            default -> {
                finish(t, state, code, result, now);
                String ended = result != null ? result.get("endedBy").getAsString() : code != null ? code
                    : state.equals("completed") ? FarmVocab.COMPLETED_CODE : state;
                audit.add(null, "bot " + botId, "farm.session.end", t.farmId + " endedBy=" + ended, address);
            }
        }
        return Http.ok();
    }

    private static JsonObject dup() {
        JsonObject o = Http.ok();
        o.addProperty("dup", true);
        return o;
    }

    private static JsonObject checkpoint(JsonElement e) {
        if (!e.isJsonObject()) throw Fail.badVocab("checkpoint");
        JsonObject c = e.getAsJsonObject();
        for (String k : c.keySet()) if (!k.equals("step") && !k.equals("data")) throw Fail.badVocab("checkpoint");
        if (!(c.get("step") instanceof JsonPrimitive s) || !s.isString() || !FarmVocab.CHECKPOINT_STEPS.contains(s.getAsString())) {
            throw Fail.badVocab("checkpoint");
        }
        JsonObject data = new JsonObject();
        if (Fail.present(c, "data")) {
            if (!c.get("data").isJsonObject()) throw Fail.badVocab("checkpoint");
            for (Map.Entry<String, JsonElement> en : c.getAsJsonObject("data").entrySet()) {
                String k = en.getKey();
                if (FarmVocab.DATA_MAX.containsKey(k)) {
                    Long v = Fail.integerOrNull(en.getValue(), 0, FarmVocab.DATA_MAX.get(k));
                    if (v == null) throw Fail.badVocab("checkpoint");
                    data.addProperty(k, v);
                } else if (k.equals("anchorId")) {
                    // "anchor" is a G1 key and never accepted; the id of an anchor is a0 to a63.
                    if (!(en.getValue() instanceof JsonPrimitive a) || !a.isString() || !FarmVocab.ANCHOR_ID.matcher(a.getAsString()).matches()) {
                        throw Fail.badVocab("checkpoint");
                    }
                    data.addProperty("anchorId", a.getAsString());
                } else {
                    throw Fail.badVocab("checkpoint");
                }
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("step", c.get("step").getAsString());
        out.add("data", data);
        return out;
    }

    private static JsonObject result(JsonElement e) {
        if (!e.isJsonObject()) throw Fail.badVocab("result");
        JsonObject r = e.getAsJsonObject();
        Set<String> keys = Set.of("loops", "recoveries", "runtimeMs", "endedBy", "gained");
        if (!r.keySet().equals(keys)) throw Fail.badVocab("result");
        JsonObject out = new JsonObject();
        for (String k : new String[]{"loops", "recoveries", "runtimeMs"}) {
            Long v = Fail.integerOrNull(r.get(k), 0, FarmVocab.MAX_INT);
            if (v == null) throw Fail.badVocab("result");
            out.addProperty(k, v);
        }
        if (!(r.get("endedBy") instanceof JsonPrimitive s) || !s.isString() || !FarmVocab.ALL_CODES.contains(s.getAsString())) {
            throw Fail.badVocab("result");
        }
        out.addProperty("endedBy", s.getAsString());
        if (!r.get("gained").isJsonArray() || !r.getAsJsonArray("gained").isEmpty()) throw Fail.badVocab("result");
        out.add("gained", new JsonArray());
        return out;
    }

    /** The code of an error object ({ code } and, ignored, a text of the bot's own), else bad_vocab. */
    private static String errorCode(JsonElement e) {
        if (!e.isJsonObject()) throw Fail.badVocab("error");
        JsonObject o = e.getAsJsonObject();
        if (!(o.get("code") instanceof JsonPrimitive c) || !c.isString() || !FarmVocab.ALL_CODES.contains(c.getAsString())) {
            throw Fail.badVocab("error");
        }
        return c.getAsString();
    }

    // ---------- STOP and roles (owner routes call these) ----------

    /** What a STOP or role change did: whether it changed anything, and whether the files could be written. */
    record Applied(boolean changed, boolean saved) {
    }

    /**
     * Turns a STOP on or off for "all" or one bot. It is applied in memory first and never rolled back (a STOP must not be lost
     * to a full disk); {@code saved} says whether the files were written. Setting a STOP finishes, as cancelled with
     * {@code ops_stop}, every open task of the scope that holds no lease and is not running (a pending task nobody claimed, a
     * paused one with no lease): nobody would ever report those. Leased and running tasks are listed for the delivery rule.
     * The caller audits whatever the outcome.
     */
    synchronized Applied stop(String scope, boolean on, String reason, Users.Principal who) {
        boolean changed = false;
        boolean saved;
        try {
            long now = now();
            housekeeping(now);
            OpsStore.Stop s;
            OpsStore.Profile p = null;
            if (scope.equals("all")) {
                s = ops.stopAll;
            } else {
                p = ops.bots.get(scope);
                if (p == null) throw new Fail(404, "unknown_bot");
                s = p.stop;
            }
            if (s.on != on) {
                changed = true;
                s.on = on;
                s.rev = ++ops.rev;
                s.at = now;
                s.by = who.name();
                s.tasks = new ArrayList<>();
                if (on) {
                    s.reason = reason;
                    for (TaskStore.Task t : new ArrayList<>(tasks.tasks)) {
                        if (!t.open() || (p != null && !p.botId.equals(t.botId))) continue;
                        if (t.leaseBot != null || t.state.equals("running")) s.tasks.add(t.id);
                        else finish(t, "cancelled", "ops_stop", null, now);
                    }
                } else {
                    s.reason = null;
                }
                dirtyOps = true;
                if (p != null) dirtyBots = true;
                refreshSnapshots();
            }
        } finally {
            saved = tryFlush();
        }
        return new Applied(changed, saved);
    }

    /** Replaces a bot's roles (owner only). A bot that becomes a farmer is cleared (P5e). Applied in memory even when the write fails. */
    synchronized Applied setRoles(String botId, List<String> roles) {
        boolean changed = false;
        boolean saved;
        try {
            long now = now();
            if (roles.size() > 2 || new java.util.HashSet<>(roles).size() != roles.size()) throw Fail.badField("roles");
            for (String r : roles) if (!FarmVocab.ROLES.contains(r)) throw Fail.badField("roles");
            OpsStore.Profile p = profile(botId, now, true);
            if (p == null) throw new Fail(404, "unknown_bot");
            if (!new java.util.HashSet<>(p.roles).equals(new java.util.HashSet<>(roles))) {
                changed = true;
                boolean was = p.farmer();
                boolean hadRole = p.hasRole("farmer");
                p.roles = new ArrayList<>(roles);
                dirtyBots = true;
                if (hadRole && !p.hasRole("farmer")) {
                    // Without the role the bot may not run sessions: every open one ends now (cancelled, role_removed).
                    for (TaskStore.Task t : new ArrayList<>(tasks.tasks)) {
                        if (t.open() && botId.equals(t.botId)) finish(t, "cancelled", "role_removed", null, now);
                    }
                }
                refreshSnapshots();
                if (!was && p.farmer()) becameFarmer(botId);
            }
        } finally {
            saved = tryFlush();
        }
        return new Applied(changed, saved);
    }

    /** Sets the sticky flag from a sync; clears stored data on the first flip. Called with the lock held. */
    void markFarmerSeen(OpsStore.Profile p) {
        if (p.farmerSeen) return;
        boolean was = p.farmer();
        p.farmerSeen = true;
        dirtyBots = true;
        refreshSnapshots(); // readers see the flag before anything is cleared
        if (!was) becameFarmer(p.botId);
    }

    // ---------- views ----------

    boolean online(OpsStore.Profile p, long now) {
        if (p.lastSync > 0 && now - p.lastSync < ONLINE_MS) return true;
        BotHub.Bot b = hub.bot(p.botId);
        return b != null && hub.online(b);
    }

    /** "delivered", "sent", "unconfirmed" (30 s without) or "unsupported" (never synced); null when no STOP applies (mitigation a). */
    String delivered(OpsStore.Profile p, long now) {
        boolean g = ops.stopAll.on, b = p.stop.on;
        if (!g && !b) return null;
        if (p.lastSync == 0) return "unsupported";
        long since = Math.max(g ? ops.stopAll.at : 0, b ? p.stop.at : 0);
        long rev = Math.max(g ? ops.stopAll.rev : 0, b ? p.stop.rev : 0);
        Set<String> ids = new LinkedHashSet<>();
        if (g) ids.addAll(ops.stopAll.tasks);
        if (b) ids.addAll(p.stop.tasks);
        boolean tasksOk = true, any = false;
        for (String id : ids) {
            TaskStore.Task t = tasks.get(id);
            if (t == null || !p.botId.equals(t.botId)) continue;
            any = true;
            // Only the leased or running tasks of the STOP are listed. They count once cancelled by the bot (ops_stop, or a bare
            // "cancelled" report), or when they ended some other way before the STOP.
            boolean cancelled = "cancelled".equals(t.state) && (t.errorCode == null || "ops_stop".equals(t.errorCode));
            boolean ended = t.terminal() && (cancelled || t.finishedAt <= since);
            if (!ended) tasksOk = false;
        }
        boolean synced = p.lastSync >= since && p.runningTask == null;
        boolean ok = synced && tasksOk && (any || p.stopAck >= rev);
        if (ok) return "delivered";
        return now - since > STOP_CONFIRM_MS ? "unconfirmed" : "sent";
    }

    private JsonObject stopJson(OpsStore.Stop s, boolean owner) {
        JsonObject o = new JsonObject();
        o.addProperty("on", s.on);
        o.addProperty("rev", s.rev);
        o.addProperty("at", s.at);
        if (owner) {
            o.addProperty("by", s.by);
            o.addProperty("reason", s.reason); // only the owner sees the text (draft 2.3)
        }
        return o;
    }

    /**
     * GET /api/v1/ops (also the answer of the ops and roles writes). A non-owner gets no reason, no roles, no names, no tasks. The
     * bot ids are raw here: for a forced streamer {@code Http.json} turns every "botId" into its opaque id on the way out.
     */
    synchronized JsonObject opsJson(Users.Principal who, String onlyBot) {
        long now = now();
        housekeeping(now);
        boolean owner = who.owner();
        JsonObject out = new JsonObject();
        out.add("stopAll", stopJson(ops.stopAll, owner));
        JsonArray list = new JsonArray();
        List<OpsStore.Profile> sorted = new ArrayList<>(ops.bots.values());
        sorted.sort(java.util.Comparator.comparing(p -> p.botId));
        for (OpsStore.Profile p : sorted) {
            if (!who.perms().seesBot(p.botId)) continue;
            if (onlyBot != null && !onlyBot.equals(p.botId)) continue;
            JsonObject b = new JsonObject();
            b.addProperty("botId", p.botId);
            b.add("stop", stopJson(p.stop, owner));
            b.addProperty("stopAck", p.stopAck);
            b.addProperty("online", online(p, now));
            b.addProperty("delivered", delivered(p, now));
            if (owner) {
                JsonArray roles = new JsonArray();
                p.roles.forEach(roles::add);
                b.add("roles", roles);
                b.addProperty("farmer", p.farmer());
            }
            list.add(b);
        }
        out.add("bots", list);
        out.addProperty("rev", ops.rev);
        return out;
    }

    /** GET /api/v1/tasks: the farm sessions, newest first, at most 200 (owner only). */
    synchronized JsonObject tasksJson(String kind, String botId, String state) {
        housekeeping(now());
        JsonArray list = new JsonArray();
        for (int i = tasks.tasks.size() - 1; i >= 0 && list.size() < 200; i--) {
            TaskStore.Task t = tasks.tasks.get(i);
            if (kind != null && !kind.equals(t.kind)) continue;
            if (botId != null && !botId.equals(t.botId)) continue;
            if (state != null && !state.equals(t.state)) continue;
            list.add(t.toJson());
        }
        JsonObject out = new JsonObject();
        out.add("tasks", list);
        return out;
    }

    /** The open farm session of a farm, or null. */
    TaskStore.Task openSessionOf(String farmId) {
        for (TaskStore.Task t : tasks.tasks) if (t.open() && farmId.equals(t.farmId)) return t;
        return null;
    }

    /** An open task of a bot (any kind), or null. */
    TaskStore.Task openTaskOf(String botId) {
        for (TaskStore.Task t : tasks.tasks) if (t.open() && botId.equals(t.botId)) return t;
        return null;
    }
}

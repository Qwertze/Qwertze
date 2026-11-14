package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The live viewer (PROTOCOL.md, "Additions: live viewer"), in memory only: per bot, the latest state the bot sent
 * (map tiles, its own position, nearby entities, HUD, a chat ring) and the browser streams showing it. A frame POST
 * updates the {@link Live} and queues the change for every stream under the Live's monitor, then wakes them; each
 * stream coalesces (newest tile, latest entities and HUD win) and holds at most 2 MiB before it falls back to a fresh
 * snapshot. A Live is dropped 60 s after its last stream closes. Nothing here is written to disk, and no position,
 * name or chat line is ever logged; the audit only records who opened and closed the viewer of which bot.
 *
 * <p>Lock order: this hub's monitor, then a Live's monitor (never the other way), then a BotHub.Bot's (never held
 * while waiting on a Live).
 */
final class ViewerHub implements AutoCloseable {
    static final int MAX_STREAMS = 8;
    static final int MAX_PER_USER = 2;
    /**
     * Pool threads a user's / the server's viewer handlers may hold, counted from entry to exit (a replaced stream can
     * still be finishing a write): a little over the stream caps, so a reused vs can't pile up blocked threads.
     */
    static final int MAX_THREADS_PER_USER = MAX_PER_USER + 1;
    static final int MAX_THREADS = MAX_STREAMS + 2;
    /** A write that doesn't finish in this long frees its pool thread (the dedicated writer finishes or fails it). */
    static final long WRITE_TIMEOUT_MS = 8_000;
    /** Writer threads in all (a stuck write holds one until the JDK server gives up on the connection). */
    static final int MAX_WRITERS = 16;
    /** Stuck (detached) writes allowed per user and in all; past them new streams get 429, so writers stay free. */
    static final int MAX_DETACHED_PER_USER = 2;
    static final int MAX_DETACHED = 8;
    /** A stream to a slow reader is cut this long after its rotate time. */
    static final long ROTATE_GRACE_MS = 15_000;
    /** A ping comment this often when nothing else was sent, so a closed tab is noticed within seconds. */
    static final long PING_MS = 3_000;
    /** Session and permission are re-checked this often (at most). */
    static final long AUTH_MS = 5_000;
    /** At most this many tiles per bot; past it the farthest from the bot go and every stream gets a snapshot. */
    static final int MAX_LIVE_TILES = 1024;
    /** A tile or drop farther than this from the bot (blocks, per axis) is ignored. */
    static final int PLAUSIBLE_BLOCKS = (BotHub.VIEWER_RADIUS + 2) * 16;
    /** A delta whose seq jumps further than this is not applied (resync). */
    static final long MAX_SEQ_JUMP = 100_000;
    /** viewer.open is audited at most once per user and bot in this long. */
    static final long AUDIT_OPEN_MS = 15 * 60_000;
    static final int OPENS_PER_MINUTE = 12;
    static final int NEW_VS_PER_HOUR = 20;
    static final int MAX_PER_BOT = 4;
    static final long ROTATE_MS = 45_000;
    static final long HEARTBEAT_MS = 10_000;
    /** How often an open stream refreshes BotHub's viewer watch (well inside BotHub.WATCH_MS). */
    static final long WATCH_REFRESH_MS = 4_000;
    static final long STALE_MS = 3_000;
    static final long LIVE_KEEP_MS = 60_000;
    static final long VS_CLOSE_MS = 15_000;
    static final long MAX_PENDING = 2L * 1024 * 1024;
    static final int CHAT_KEEP = 100;
    static final int MAX_FRAMES_PER_S = 15;
    static final int MAX_BODY = 512 * 1024;
    static final int MAX_TILES = 625;
    static final int MAX_DROP = 625;
    static final int MAX_ENTS = 256;
    static final int MAX_CHAT = 20;
    /** A full frame carries the bot's chat ring. */
    static final int MAX_CHAT_FULL = 50;
    static final int MAX_NAME = 16;
    static final int MAX_TYPE = 64;
    static final int MAX_MESSAGE = 256;
    static final int MAX_ITEM_ID = 64;
    static final int MAX_DAMAGE = 100_000;
    static final Pattern SESSION = Pattern.compile("^[0-9a-f]{16}$");
    static final Pattern RESOURCE_ID = Pattern.compile("^[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,63}$");
    static final Set<String> ENT_KINDS = Set.of("player", "mob", "item", "other");
    static final Set<String> CHAT_KINDS = Set.of("public", "whisper", "system", "death");
    private static final int C_B64 = 344;  // base64 of 256 bytes
    private static final int H_B64 = 684;  // base64 of 512 bytes
    private static final double MAX_XZ = 32_000_000;
    private static final double MAX_Y = 8192;
    private static final int MAX_CHUNK = 2_000_000;

    /** One 16x16 map tile as the bot sent it (base64 forwarded as is). */
    static final class Tile {
        final int x;
        final int z;
        final String c;
        final String h;

        Tile(int x, int z, String c, String h) {
            this.x = x;
            this.z = z;
            this.c = c;
            this.h = h;
        }

        long key() {
            return key(x, z);
        }

        static long key(int x, int z) {
            return ((long) x << 32) | (z & 0xffffffffL);
        }

        int size() {
            return c.length() + h.length() + 48;
        }

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("x", x);
            o.addProperty("z", z);
            o.addProperty("c", c);
            o.addProperty("h", h);
            return o;
        }
    }

    /** A checked frame (bot/viewer). Missing parts are null: unchanged. */
    record Frame(String botId, String session, long seq, long t, boolean full, JsonObject world, JsonObject self,
                 List<Tile> tiles, List<long[]> drop, JsonArray ents, JsonObject hud, List<JsonObject> chat) {
    }

    /** A refused frame (400). */
    static final class BadFrame extends Exception {
        BadFrame(String message) {
            super(message);
        }
    }

    record FrameResult(boolean watching, boolean resync) {
    }

    /** What the server holds for one bot. Guarded by its own monitor. */
    static final class Live {
        final String botId;
        String session;
        boolean hasSnapshot;
        long seq;
        long rev;
        long lastFrameAt;
        JsonObject world;
        JsonObject self;
        JsonArray ents = new JsonArray();
        JsonObject hud;
        final LinkedHashMap<Long, Tile> tiles = new LinkedHashMap<>();
        final ArrayDeque<JsonObject> chat = new ArrayDeque<>();
        /** seq, t and receive time of the latest frame that changed each part (for the latency figures). */
        long lastT, tilesSeq, tilesT, tilesRt, entsSeq, entsT, entsRt, hudSeq, hudT, hudRt;
        final List<Client> clients = new ArrayList<>();
        long emptySince;

        Live(String botId, long now) {
            this.botId = botId;
            this.emptySince = now;
        }
    }

    /** One open stream: what it still has to send. Guarded by its Live's monitor. */
    static final class Client {
        final String userId;
        final String vs;
        final String botId;
        final LinkedHashMap<Long, Tile> tiles = new LinkedHashMap<>();
        final LinkedHashSet<Long> drops = new LinkedHashSet<>();
        final ArrayDeque<JsonObject> chat = new ArrayDeque<>();
        long pendingBytes;
        boolean needSnapshot;
        boolean entsDirty;
        boolean hudDirty;
        /** The stream must end now: "capacity" (replaced by a newer stream of the same viewer) or null. */
        String bye;
        String statusSent;

        Client(String userId, String vs, String botId) {
            this.userId = userId;
            this.vs = vs;
            this.botId = botId;
        }

        boolean hasWork() {
            return needSnapshot || entsDirty || hudDirty || !tiles.isEmpty() || !drops.isEmpty() || !chat.isEmpty() || bye != null;
        }

        void clearQueues() {
            tiles.clear();
            drops.clear();
            chat.clear();
            pendingBytes = 0;
            entsDirty = false;
            hudDirty = false;
        }

        void queueTile(Tile t) {
            if (needSnapshot) return;
            Tile old = tiles.put(t.key(), t);
            pendingBytes += t.size() - (old == null ? 0 : old.size());
            if (drops.remove(t.key())) pendingBytes -= 24;
            bound();
        }

        void queueDrop(long key) {
            if (needSnapshot) return;
            Tile old = tiles.remove(key);
            if (old != null) pendingBytes -= old.size();
            if (drops.add(key)) pendingBytes += 24;
            bound();
        }

        void queueChat(JsonObject line) {
            if (needSnapshot) return;
            chat.addLast(line);
            pendingBytes += chatSize(line);
            while (chat.size() > CHAT_KEEP) pendingBytes -= chatSize(chat.pollFirst());
            bound();
        }

        /** Over 2 MiB pending (a slow reader): drop the queues and send one fresh snapshot instead. */
        private void bound() {
            if (pendingBytes > MAX_PENDING) {
                clearQueues();
                needSnapshot = true;
            }
        }

        private static int chatSize(JsonObject line) {
            JsonElement m = line.get("m");
            return 64 + (m != null && m.isJsonPrimitive() ? m.getAsString().length() : 0);
        }
    }

    /**
     * One user watching one bot (any number of pages and reconnects): viewer.open when it starts (at most once per 15
     * minutes per user and bot), viewer.close when it has had no stream for 15 s (only when its open was audited).
     */
    private static final class ViewerSession {
        final String userId;
        final String userName;
        final String botId;
        final String address;
        final long openedAt;
        int streams;
        long lastClosedAt;
        boolean audited;

        ViewerSession(String userId, String userName, String botId, String address, long openedAt) {
            this.userId = userId;
            this.userName = userName;
            this.botId = botId;
            this.address = address;
            this.openedAt = openedAt;
        }
    }

    final long rotateMs;
    final long heartbeatMs;
    private final BotHub bots;
    private final AuditLog audit;
    private final LongSupplier clock;
    private final Map<String, Live> lives = new HashMap<>();
    private final List<Client> streams = new ArrayList<>();
    private final Map<String, ViewerSession> viewers = new HashMap<>(); // userId \0 botId -> session
    private final Map<String, Long> openAudited = new HashMap<>();      // userId \0 botId -> when
    private final Map<String, ArrayDeque<Long>> rates = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> opens = new HashMap<>(); // userId -> recent open times
    private final Map<String, LinkedHashMap<String, Long>> seenVs = new HashMap<>(); // userId -> vs -> first seen
    private final Map<String, Integer> userThreads = new HashMap<>();
    private int threads;
    /** Writes the handler gave up on that are still running on a writer thread, per user and in all. */
    private final Map<String, Integer> userDetached = new HashMap<>();
    private int detached;
    private ScheduledExecutorService sweeper;
    private final java.util.concurrent.ThreadPoolExecutor writers;
    private boolean closed;
    /** Tests lower these. */
    long writeTimeoutMs = WRITE_TIMEOUT_MS;
    int opensPerMinute = OPENS_PER_MINUTE;
    int newVsPerHour = NEW_VS_PER_HOUR;

    ViewerHub(BotHub bots, AuditLog audit) {
        this(bots, audit, ROTATE_MS, HEARTBEAT_MS, System::currentTimeMillis);
    }

    ViewerHub(BotHub bots, AuditLog audit, long rotateMs, long heartbeatMs, LongSupplier clock) {
        this.bots = bots;
        this.audit = audit;
        this.rotateMs = rotateMs;
        this.heartbeatMs = heartbeatMs;
        this.clock = clock;
        AtomicInteger n = new AtomicInteger();
        this.writers = new java.util.concurrent.ThreadPoolExecutor(0, MAX_WRITERS, 30, TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "stash-manager-viewer-writer-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
    }

    /** The writer threads (a viewer stream writes through them, so a stuck write never holds a pool thread). */
    java.util.concurrent.ThreadPoolExecutor writers() {
        return writers;
    }

    // ---------- handler threads ----------

    /** Counts a viewer handler's pool thread, or answers why not (429): per user and server-wide hard caps. */
    synchronized String enter(String userId) {
        if (detached >= MAX_DETACHED) return "the server is busy finishing stalled viewer streams; try again in a minute";
        if (userDetached.getOrDefault(userId, 0) >= MAX_DETACHED_PER_USER) {
            return "your earlier viewer connections stopped reading; try again in a minute";
        }
        int mine = userThreads.getOrDefault(userId, 0);
        if (threads >= MAX_THREADS) return "the server is busy with viewer streams; try again in a few seconds";
        if (mine >= MAX_THREADS_PER_USER) return "you already have " + mine + " viewer connections; close one";
        userThreads.put(userId, mine + 1);
        threads++;
        return null;
    }

    synchronized void exit(String userId) {
        int mine = userThreads.getOrDefault(userId, 0) - 1;
        if (mine <= 0) userThreads.remove(userId);
        else userThreads.put(userId, mine);
        threads = Math.max(0, threads - 1);
    }

    synchronized int threadCount() {
        return threads;
    }

    /** A handler gave up on a write that is still running on a writer thread. */
    synchronized void writerDetached(String userId) {
        userDetached.merge(userId, 1, Integer::sum);
        detached++;
    }

    /** That write ended (finished or failed). */
    synchronized void writerFinished(String userId) {
        int mine = userDetached.getOrDefault(userId, 0) - 1;
        if (mine <= 0) userDetached.remove(userId);
        else userDetached.put(userId, mine);
        detached = Math.max(0, detached - 1);
    }

    synchronized int detachedCount() {
        return detached;
    }

    long now() {
        return clock.getAsLong();
    }

    BotHub bots() {
        return bots;
    }

    // ---------- streams: caps, open, close ----------

    /** Why a stream can't open (429), or null when it was registered. */
    record Opened(Client client, Live live, String refused) {
    }

    /**
     * Registers a stream for this user and viewer page, within the caps (8 in all, 3 per user, 4 per bot). A stream of
     * the same page that is still open (a reconnect that overlaps the old response) is told to end and doesn't count.
     */
    Opened open(Users.Principal who, String vs, BotHub.Bot b, String address) {
        long now = now();
        Client kicked = null;
        Client c;
        Live live;
        boolean fresh = false;
        boolean auditOpen = false;
        synchronized (this) {
            if (closed) return new Opened(null, null, "the server is stopping");
            // Opens per user: a page reconnecting in a loop, or minting new viewer pages, is refused for a while.
            ArrayDeque<Long> recent = opens.computeIfAbsent(who.id(), k -> new ArrayDeque<>());
            while (!recent.isEmpty() && now - recent.peekFirst() >= 60_000) recent.pollFirst();
            if (recent.size() >= opensPerMinute) return new Opened(null, null, "too many viewer connections in the last minute; wait a little");
            LinkedHashMap<String, Long> pages = seenVs.computeIfAbsent(who.id(), k -> new LinkedHashMap<>());
            pages.values().removeIf(t -> now - t >= 3_600_000);
            if (!pages.containsKey(vs) && pages.size() >= newVsPerHour) return new Opened(null, null, "too many viewer pages opened in the last hour; wait a little");
            for (Client other : streams) {
                if (other.vs.equals(vs) && other.userId.equals(who.id()) && other.botId.equals(b.botId)) kicked = other;
            }
            int total = 0;
            int mine = 0;
            int bot = 0;
            for (Client other : streams) {
                if (other == kicked) continue;
                total++;
                if (other.userId.equals(who.id())) mine++;
                if (other.botId.equals(b.botId)) bot++;
            }
            if (total >= MAX_STREAMS) return new Opened(null, null, "the server already has " + MAX_STREAMS + " viewer streams open");
            if (mine >= MAX_PER_USER) return new Opened(null, null, "you already have " + MAX_PER_USER + " viewers open; close one");
            if (bot >= MAX_PER_BOT) return new Opened(null, null, "this bot already has " + MAX_PER_BOT + " viewers open");
            recent.addLast(now);
            pages.putIfAbsent(vs, now);
            if (kicked != null) streams.remove(kicked);
            c = new Client(who.id(), vs, b.botId);
            streams.add(c);
            live = lives.get(b.botId);
            if (live == null) {
                live = new Live(b.botId, now);
                lives.put(b.botId, live);
                fresh = true;
            }
            String key = who.id() + "\u0000" + b.botId;
            ViewerSession v = viewers.get(key);
            if (v == null) {
                v = new ViewerSession(who.id(), who.name(), b.botId, address, now);
                viewers.put(key, v);
                Long last = openAudited.get(key);
                if (last == null || now - last >= AUDIT_OPEN_MS) {
                    openAudited.put(key, now);
                    v.audited = true;
                    auditOpen = true;
                }
            }
            v.streams++;
            if (kicked != null) v.streams--;
            // Registered with the Live under the same lock as the streams and Lives, so sweep() can't drop the Live
            // in between.
            synchronized (live) {
                if (kicked != null) {
                    kicked.bye = "capacity";
                    live.notifyAll();
                }
                live.clients.add(c);
                c.needSnapshot = live.hasSnapshot;
            }
            startSweeper();
        }
        if (auditOpen) audit.add(who.id(), who.name(), "viewer.open", b.botId, address);
        // A new session when watching starts, or when there's no snapshot to show (the bot's next frame is full).
        bots.watchViewer(b, fresh);
        return new Opened(c, live, null);
    }

    /** A stream ended (any reason). Its watch lingers 10 s from now, for the reconnect. */
    void close(Client c, Live live, BotHub.Bot b) {
        long now = now();
        synchronized (live) {
            live.clients.remove(c);
            if (live.clients.isEmpty()) live.emptySince = now;
        }
        synchronized (this) {
            boolean counted = streams.remove(c);
            ViewerSession v = viewers.get(c.userId + "\u0000" + c.botId);
            if (v != null && counted) {
                v.streams--;
                if (v.streams <= 0) v.lastClosedAt = now;
            }
        }
        if (b != null) bots.watchViewer(b, false);
    }

    synchronized int streamCount() {
        return streams.size();
    }

    synchronized int streamCount(String botId) {
        int n = 0;
        for (Client c : streams) if (c.botId.equals(botId)) n++;
        return n;
    }

    synchronized int userStreamCount(String userId) {
        int n = 0;
        for (Client c : streams) if (c.userId.equals(userId)) n++;
        return n;
    }

    synchronized Live live(String botId) {
        return lives.get(botId);
    }

    /** Drops what the viewer holds for a bot (a bot that has just become a farmer, FARM-CONTRACT P5e). */
    synchronized void forget(String botId) {
        lives.remove(botId);
    }

    private void startSweeper() {
        if (sweeper != null) return;
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stash-manager-viewer-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(this::sweep, 1, 1, TimeUnit.SECONDS);
    }

    /** Drops Lives unused for 60 s and closes (audits) viewer pages without a stream for 15 s. */
    void sweep() {
        long now = now();
        List<ViewerSession> ended = new ArrayList<>();
        synchronized (this) {
            for (Iterator<Live> it = lives.values().iterator(); it.hasNext(); ) {
                Live l = it.next();
                synchronized (l) {
                    if (l.clients.isEmpty() && now - l.emptySince >= LIVE_KEEP_MS) it.remove();
                }
            }
            for (Iterator<ViewerSession> it = viewers.values().iterator(); it.hasNext(); ) {
                ViewerSession v = it.next();
                if (v.streams <= 0 && now - v.lastClosedAt >= VS_CLOSE_MS) {
                    it.remove();
                    if (v.audited) ended.add(v);
                }
            }
            rates.values().removeIf(q -> q.isEmpty() || now - q.peekLast() > 2000);
            opens.values().removeIf(q -> q.isEmpty() || now - q.peekLast() >= 60_000);
            seenVs.values().removeIf(m -> {
                m.values().removeIf(t -> now - t >= 3_600_000);
                return m.isEmpty();
            });
            openAudited.values().removeIf(t -> now - t >= AUDIT_OPEN_MS);
        }
        // Outside the hub's monitor: the audit log writes a file.
        for (ViewerSession v : ended) {
            long secs = Math.max(0, (v.lastClosedAt - v.openedAt) / 1000);
            audit.add(v.userId, v.userName, "viewer.close", v.botId + " after " + secs + " s", v.address);
        }
    }

    @Override
    public void close() {
        List<Live> all;
        synchronized (this) {
            closed = true;
            if (sweeper != null) sweeper.shutdownNow();
            writers.shutdown();
            all = new ArrayList<>(lives.values());
        }
        for (Live l : all) {
            synchronized (l) {
                for (Client c : l.clients) c.bye = "capacity";
                l.notifyAll();
            }
        }
    }

    // ---------- frames ----------

    /** The 15 frames/s per bot limit: 0 when this frame may go on, else how many ms to wait. Counts every attempt. */
    long rateLimit(String botId) {
        long now = now();
        synchronized (this) {
            ArrayDeque<Long> q = rates.computeIfAbsent(botId, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() >= 1000) q.pollFirst();
            if (q.size() >= MAX_FRAMES_PER_S) return Math.max(1, q.peekFirst() + 1000 - now);
            q.addLast(now);
            return 0;
        }
    }

    /**
     * Takes a checked frame: applied when its session is the bot's current one and (for a delta) the server holds the
     * snapshot it builds on; otherwise resync (the next frame must be full).
     */
    FrameResult frame(BotHub.Bot b, Frame f) {
        long rt = now();
        String current = bots.viewerSession(b);
        boolean watching = current != null;
        if (!watching) return new FrameResult(false, false);
        Live live;
        synchronized (this) {
            live = lives.get(b.botId);
        }
        if (live == null || !current.equals(f.session())) return new FrameResult(true, true);
        synchronized (live) {
            if (!f.full() && (!live.hasSnapshot || !f.session().equals(live.session))) return new FrameResult(true, true);
            if (!f.full() && f.seq() <= live.seq) return new FrameResult(true, false); // a repeat or late frame
            if (!f.full() && f.seq() > live.seq + MAX_SEQ_JUMP) return new FrameResult(true, true); // nonsense: start over
            apply(live, f, rt);
            live.notifyAll();
        }
        return new FrameResult(true, false);
    }

    /** Applies a frame to a Live and queues it for its streams. Caller holds the Live's monitor. */
    static void apply(Live live, Frame f, long rt) {
        JsonObject self = f.self() != null ? f.self() : live.self;
        List<Tile> newTiles = plausibleTiles(f.tiles(), self);
        List<long[]> newDrops = plausibleDrops(f.drop(), self);
        live.rev++;
        live.seq = f.seq();
        live.lastFrameAt = rt;
        live.lastT = f.t();
        if (f.full()) {
            live.session = f.session();
            live.hasSnapshot = true;
            live.world = f.world();
            live.self = f.self();
            live.ents = f.ents();
            live.hud = f.hud();
            live.tiles.clear();
            for (Tile t : newTiles) live.tiles.put(t.key(), t);
            capTiles(live);
            live.chat.clear();
            if (f.chat() != null) for (JsonObject line : f.chat()) addChat(live, line);
            live.tilesT = live.entsT = live.hudT = f.t();
            live.tilesRt = live.entsRt = live.hudRt = rt;
            live.tilesSeq = live.entsSeq = live.hudSeq = f.seq();
            for (Client c : live.clients) {
                c.clearQueues();
                c.needSnapshot = true;
            }
            return;
        }
        if (f.world() != null) live.world = f.world();
        boolean ents = false;
        if (f.self() != null && !f.self().equals(live.self)) {
            live.self = f.self();
            ents = true;
        }
        if (f.ents() != null && !f.ents().equals(live.ents)) {
            live.ents = f.ents();
            ents = true;
        }
        if (ents) {
            live.entsSeq = f.seq();
            live.entsT = f.t();
            live.entsRt = rt;
        }
        boolean hud = f.hud() != null && !f.hud().equals(live.hud);
        if (hud) {
            live.hud = f.hud();
            live.hudSeq = f.seq();
            live.hudT = f.t();
            live.hudRt = rt;
        }
        boolean tiles = (newTiles != null && !newTiles.isEmpty()) || (newDrops != null && !newDrops.isEmpty());
        if (tiles) {
            live.tilesSeq = f.seq();
            live.tilesT = f.t();
            live.tilesRt = rt;
        }
        if (newTiles != null) for (Tile t : newTiles) live.tiles.put(t.key(), t);
        if (newDrops != null) for (long[] d : newDrops) live.tiles.remove(Tile.key((int) d[0], (int) d[1]));
        if (f.chat() != null) for (JsonObject line : f.chat()) addChat(live, line);
        boolean evicted = capTiles(live);
        for (Client c : live.clients) {
            if (evicted) {
                c.clearQueues();
                c.needSnapshot = true;
                continue;
            }
            if (newTiles != null) for (Tile t : newTiles) c.queueTile(t);
            if (newDrops != null) for (long[] d : newDrops) c.queueDrop(Tile.key((int) d[0], (int) d[1]));
            if (f.chat() != null) for (JsonObject line : f.chat()) c.queueChat(line);
            if (c.needSnapshot) continue;
            if (ents) c.entsDirty = true;
            if (hud) c.hudDirty = true;
        }
    }

    private static boolean near(int cx, int cz, JsonObject self) {
        if (self == null || !self.has("x") || !self.has("z")) return true; // nothing to compare with yet
        double x = self.get("x").getAsDouble();
        double z = self.get("z").getAsDouble();
        return Math.abs(cx * 16.0 - x) <= PLAUSIBLE_BLOCKS && Math.abs(cz * 16.0 - z) <= PLAUSIBLE_BLOCKS;
    }

    /** The tiles near the bot (a tile far from it can't be in its view radius); null stays null. */
    static List<Tile> plausibleTiles(List<Tile> in, JsonObject self) {
        if (in == null) return null;
        List<Tile> out = new ArrayList<>(in.size());
        for (Tile t : in) if (near(t.x, t.z, self)) out.add(t);
        return out;
    }

    static List<long[]> plausibleDrops(List<long[]> in, JsonObject self) {
        if (in == null) return null;
        List<long[]> out = new ArrayList<>(in.size());
        for (long[] d : in) if (near((int) d[0], (int) d[1], self)) out.add(d);
        return out;
    }

    /** Keeps at most MAX_LIVE_TILES, dropping the farthest from the bot. Returns whether any went. */
    static boolean capTiles(Live live) {
        if (live.tiles.size() <= MAX_LIVE_TILES) return false;
        double x = live.self != null && live.self.has("x") ? live.self.get("x").getAsDouble() : 0;
        double z = live.self != null && live.self.has("z") ? live.self.get("z").getAsDouble() : 0;
        List<Tile> all = new ArrayList<>(live.tiles.values());
        all.sort((a, b) -> Double.compare(dist2(b, x, z), dist2(a, x, z))); // farthest first
        for (int i = 0; live.tiles.size() > MAX_LIVE_TILES; i++) live.tiles.remove(all.get(i).key());
        return true;
    }

    private static double dist2(Tile t, double x, double z) {
        double dx = t.x * 16.0 + 8 - x;
        double dz = t.z * 16.0 + 8 - z;
        return dx * dx + dz * dz;
    }

    private static void addChat(Live live, JsonObject line) {
        live.chat.addLast(line);
        while (live.chat.size() > CHAT_KEEP) live.chat.pollFirst();
    }

    // ---------- events ----------
    // Built under the Live's monitor from references to its parts (which are replaced, never changed), serialized
    // to text after the monitor is released.

    /** One event to send: its name, its id (or null) and its data. */
    record Ev(String name, String id, JsonElement data) {
        String text() {
            StringBuilder sb = new StringBuilder(64);
            sb.append("event: ").append(name).append('\n');
            if (id != null) sb.append("id: ").append(id).append('\n');
            sb.append("data: ").append(Json.GSON.toJson(data)).append("\n\n");
            return sb.toString();
        }
    }

    /** "event: name\nid: session:rev\ndata: json\n\n". */
    static String event(Live live, String name, JsonElement data) {
        return ev(live, name, data).text();
    }

    static Ev ev(Live live, String name, JsonElement data) {
        return new Ev(name, live != null && live.session != null ? live.session + ":" + live.rev : null, data);
    }

    static Ev snapshot(Live live, long st) {
        JsonObject o = new JsonObject();
        o.addProperty("session", live.session);
        o.addProperty("seq", live.seq);
        o.addProperty("t", live.lastT);
        o.addProperty("rt", live.lastFrameAt);
        o.addProperty("st", st);
        o.add("world", live.world == null ? JsonNull.INSTANCE : live.world);
        o.add("self", live.self == null ? JsonNull.INSTANCE : live.self);
        JsonArray tiles = new JsonArray();
        for (Tile t : live.tiles.values()) tiles.add(t.json());
        o.add("tiles", tiles);
        o.add("ents", live.ents == null ? new JsonArray() : live.ents);
        o.add("hud", live.hud == null ? JsonNull.INSTANCE : live.hud);
        JsonArray chat = new JsonArray();
        for (JsonObject line : live.chat) chat.add(line);
        o.add("chat", chat);
        return ev(live, "snapshot", o);
    }

    /** Everything this stream still has to send, as SSE text, and its queues emptied. Caller holds the Live's monitor. */
    static List<Ev> drain(Live live, Client c, long st) {
        List<Ev> out = new ArrayList<>(4);
        if (c.needSnapshot) {
            c.needSnapshot = false;
            c.clearQueues();
            if (live.hasSnapshot) out.add(snapshot(live, st));
            return out;
        }
        if (!c.tiles.isEmpty() || !c.drops.isEmpty()) {
            JsonObject o = meta(live.tilesSeq, live.tilesT, live.tilesRt, st);
            JsonArray tiles = new JsonArray();
            for (Tile t : c.tiles.values()) tiles.add(t.json());
            JsonArray drop = new JsonArray();
            for (long k : c.drops) {
                JsonArray d = new JsonArray();
                d.add((int) (k >> 32));
                d.add((int) k);
                drop.add(d);
            }
            o.add("tiles", tiles);
            o.add("drop", drop);
            out.add(ev(live, "tiles", o));
        }
        if (c.entsDirty) {
            JsonObject o = meta(live.entsSeq, live.entsT, live.entsRt, st);
            o.add("self", live.self == null ? JsonNull.INSTANCE : live.self);
            o.add("ents", live.ents == null ? new JsonArray() : live.ents);
            out.add(ev(live, "ents", o));
        }
        if (c.hudDirty) {
            JsonObject o = meta(live.hudSeq, live.hudT, live.hudRt, st);
            o.add("hud", live.hud == null ? JsonNull.INSTANCE : live.hud);
            out.add(ev(live, "hud", o));
        }
        if (!c.chat.isEmpty()) {
            JsonObject o = new JsonObject();
            JsonArray lines = new JsonArray();
            for (JsonObject line : c.chat) lines.add(line);
            o.add("lines", lines);
            out.add(ev(live, "chat", o));
        }
        c.clearQueues();
        return out;
    }

    private static JsonObject meta(long seq, long t, long rt, long st) {
        JsonObject o = new JsonObject();
        o.addProperty("seq", seq);
        o.addProperty("t", t);
        o.addProperty("rt", rt);
        o.addProperty("st", st);
        return o;
    }

    /** waiting | live | stale | offline. Caller holds the Live's monitor. */
    static String status(Live live, boolean online, long now) {
        if (!online) return "offline";
        if (!live.hasSnapshot) return "waiting";
        return now - live.lastFrameAt > STALE_MS ? "stale" : "live";
    }

    // ---------- frame validation ----------

    /** Checks a bot/viewer body against the contract and returns it cleaned (only the known fields), or throws. */
    static Frame parse(JsonObject o) throws BadFrame {
        String botId = AddonRoutes.string(o, "botId");
        if (botId == null || !BotRoutes.BOT_ID.matcher(botId).matches()) throw new BadFrame("botId must be 1-64 letters, digits, - or _");
        if (!(o.get("v") instanceof JsonPrimitive v) || !v.isNumber() || v.getAsDouble() != 1) throw new BadFrame("v must be 1");
        String session = AddonRoutes.string(o, "session");
        if (session == null || !SESSION.matcher(session).matches()) throw new BadFrame("session must be 16 hex digits");
        long seq = whole(o, "seq", 0, Long.MAX_VALUE);
        long t = whole(o, "t", 1, Long.MAX_VALUE);
        boolean full = false;
        if (o.has("full") && !o.get("full").isJsonNull()) {
            if (!(o.get("full") instanceof JsonPrimitive p) || !p.isBoolean()) throw new BadFrame("full must be true or false");
            full = p.getAsBoolean();
        }
        JsonObject world = present(o, "world") ? world(o.get("world")) : null;
        JsonObject self = present(o, "self") ? self(o.get("self")) : null;
        List<Tile> tiles = present(o, "tiles") ? tiles(o.get("tiles")) : null;
        List<long[]> drop = present(o, "drop") ? drop(o.get("drop")) : null;
        JsonArray ents = present(o, "ents") ? ents(o.get("ents")) : null;
        JsonObject hud = present(o, "hud") ? hud(o.get("hud")) : null;
        List<JsonObject> chat = present(o, "chat") ? chat(o.get("chat"), full ? MAX_CHAT_FULL : MAX_CHAT) : null;
        // hud may be missing for a moment at login: the page shows "-" until it comes.
        if (full && (world == null || self == null || tiles == null || ents == null)) {
            throw new BadFrame("a full frame needs world, self, tiles and ents");
        }
        return new Frame(botId, session, seq, t, full, world, self, tiles, drop, ents, hud, chat);
    }

    private static boolean present(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull();
    }

    private static double number(JsonElement e, String what, double min, double max) throws BadFrame {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber()) throw new BadFrame(what + " must be a number");
        double d = p.getAsDouble();
        if (!Double.isFinite(d) || d < min || d > max) throw new BadFrame(what + " is out of range");
        return d;
    }

    private static long whole(JsonElement e, String what, long min, long max) throws BadFrame {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber()) throw new BadFrame(what + " must be a whole number");
        java.math.BigDecimal d;
        try {
            d = p.getAsBigDecimal();
        } catch (NumberFormatException ex) {
            throw new BadFrame(what + " must be a whole number");
        }
        if (d.stripTrailingZeros().scale() > 0) throw new BadFrame(what + " must be a whole number");
        if (d.compareTo(java.math.BigDecimal.valueOf(min)) < 0 || d.compareTo(java.math.BigDecimal.valueOf(max)) > 0) {
            throw new BadFrame(what + " is out of range");
        }
        return d.longValueExact();
    }

    private static long whole(JsonObject o, String key, long min, long max) throws BadFrame {
        return whole(o.get(key), key, min, max);
    }

    private static JsonObject object(JsonElement e, String what) throws BadFrame {
        if (e == null || !e.isJsonObject()) throw new BadFrame(what + " must be an object");
        return e.getAsJsonObject();
    }

    private static JsonArray array(JsonElement e, String what, int max) throws BadFrame {
        if (e == null || !e.isJsonArray()) throw new BadFrame(what + " must be a list");
        JsonArray a = e.getAsJsonArray();
        if (a.size() > max) throw new BadFrame(what + " can have at most " + max + " entries");
        return a;
    }

    private static double tenth(double d) {
        return Math.round(d * 10) / 10.0;
    }

    private static JsonObject world(JsonElement e) throws BadFrame {
        JsonObject in = object(e, "world");
        String dim = AddonRoutes.string(in, "dim");
        if (dim != null) dim = dim.toLowerCase(java.util.Locale.ROOT);
        if (dim == null || dim.length() > 64 || !RESOURCE_ID.matcher(dim).matches()) throw new BadFrame("world.dim must be a dimension id");
        JsonObject out = new JsonObject();
        out.addProperty("dim", dim);
        out.addProperty("minY", whole(in.get("minY"), "world.minY", -4096, 4096));
        out.addProperty("height", whole(in.get("height"), "world.height", 1, 8192));
        return out;
    }

    private static JsonObject self(JsonElement e) throws BadFrame {
        JsonObject in = object(e, "self");
        JsonObject out = new JsonObject();
        out.addProperty("x", tenth(number(in.get("x"), "self.x", -MAX_XZ, MAX_XZ)));
        out.addProperty("y", tenth(number(in.get("y"), "self.y", -MAX_Y, MAX_Y)));
        out.addProperty("z", tenth(number(in.get("z"), "self.z", -MAX_XZ, MAX_XZ)));
        out.addProperty("yaw", Math.round(number(in.get("yaw"), "self.yaw", -1e6, 1e6)));
        return out;
    }

    private static List<Tile> tiles(JsonElement e) throws BadFrame {
        JsonArray a = array(e, "tiles", MAX_TILES);
        List<Tile> out = new ArrayList<>(a.size());
        Base64.Decoder dec = Base64.getDecoder();
        for (JsonElement item : a) {
            JsonObject t = object(item, "every tile");
            int x = (int) whole(t.get("x"), "tile x", -MAX_CHUNK, MAX_CHUNK);
            int z = (int) whole(t.get("z"), "tile z", -MAX_CHUNK, MAX_CHUNK);
            String c = AddonRoutes.string(t, "c");
            String h = AddonRoutes.string(t, "h");
            if (c == null || c.length() != C_B64 || decodedLength(dec, c) != 256) throw new BadFrame("tile c must be base64 of 256 bytes");
            if (h == null || h.length() != H_B64 || decodedLength(dec, h) != 512) throw new BadFrame("tile h must be base64 of 512 bytes");
            out.add(new Tile(x, z, c, h));
        }
        return out;
    }

    private static int decodedLength(Base64.Decoder dec, String s) {
        try {
            return dec.decode(s).length;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private static List<long[]> drop(JsonElement e) throws BadFrame {
        JsonArray a = array(e, "drop", MAX_DROP);
        List<long[]> out = new ArrayList<>(a.size());
        for (JsonElement item : a) {
            if (!item.isJsonArray() || item.getAsJsonArray().size() != 2) throw new BadFrame("drop must be a list of [x, z]");
            JsonArray p = item.getAsJsonArray();
            out.add(new long[]{whole(p.get(0), "drop x", -MAX_CHUNK, MAX_CHUNK), whole(p.get(1), "drop z", -MAX_CHUNK, MAX_CHUNK)});
        }
        return out;
    }

    private static String optString(JsonObject in, String key, int max, String what) throws BadFrame {
        JsonElement v = in.get(key);
        if (v == null || v.isJsonNull()) return null;
        if (!(v instanceof JsonPrimitive p) || !p.isString()) throw new BadFrame(what + " must be text");
        String s = p.getAsString();
        if (s.length() > max) throw new BadFrame(what + " can have at most " + max + " characters");
        return s;
    }

    private static JsonArray ents(JsonElement e) throws BadFrame {
        JsonArray a = array(e, "ents", MAX_ENTS);
        JsonArray out = new JsonArray();
        for (JsonElement item : a) {
            JsonObject in = object(item, "every entity");
            String k = AddonRoutes.string(in, "k");
            if (k == null || !ENT_KINDS.contains(k)) throw new BadFrame("entity k must be player, mob, item or other");
            JsonObject o = new JsonObject();
            o.addProperty("id", whole(in.get("id"), "entity id", Integer.MIN_VALUE, Integer.MAX_VALUE));
            o.addProperty("k", k);
            String n = optString(in, "n", MAX_NAME, "entity n");
            if (n != null) o.addProperty("n", clean(n));
            String t = optString(in, "t", MAX_TYPE, "entity t");
            if (t != null) o.addProperty("t", clean(t));
            if (present(in, "c")) o.addProperty("c", whole(in.get("c"), "entity c", 0, 1_000_000));
            o.addProperty("x", tenth(number(in.get("x"), "entity x", -MAX_XZ, MAX_XZ)));
            o.addProperty("y", tenth(number(in.get("y"), "entity y", -MAX_Y, MAX_Y)));
            o.addProperty("z", tenth(number(in.get("z"), "entity z", -MAX_XZ, MAX_XZ)));
            if (present(in, "yaw")) o.addProperty("yaw", Math.round(number(in.get("yaw"), "entity yaw", -1e6, 1e6)));
            out.add(o);
        }
        return out;
    }

    private static JsonElement item(JsonElement e, String what) throws BadFrame {
        if (e == null || e.isJsonNull()) return JsonNull.INSTANCE;
        JsonObject in = object(e, what);
        String id = AddonRoutes.string(in, "id");
        if (id != null) id = id.toLowerCase(java.util.Locale.ROOT);
        if (id == null || id.length() > MAX_ITEM_ID || !RESOURCE_ID.matcher(id).matches()) throw new BadFrame(what + " id must be an item id");
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("c", present(in, "c") ? whole(in.get("c"), what + " c", 0, 1_000_000) : 1);
        // Durability (damageable items only): both or neither, 0 <= d <= md <= 100,000.
        boolean hasD = present(in, "d"), hasMd = present(in, "md");
        if (hasD != hasMd) throw new BadFrame(what + " needs both d and md, or neither");
        if (hasMd) {
            long md = whole(in.get("md"), what + " md", 1, MAX_DAMAGE);
            long d = whole(in.get("d"), what + " d", 0, MAX_DAMAGE);
            if (d > md) throw new BadFrame(what + " d can't be above md");
            o.addProperty("d", d);
            o.addProperty("md", md);
        }
        return o;
    }

    private static JsonArray items(JsonElement e, String what, int size) throws BadFrame {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != size) throw new BadFrame(what + " must have exactly " + size + " entries");
        JsonArray out = new JsonArray();
        for (JsonElement item : e.getAsJsonArray()) out.add(item(item, what + " entry"));
        return out;
    }

    private static JsonObject hud(JsonElement e) throws BadFrame {
        JsonObject in = object(e, "hud");
        JsonObject o = new JsonObject();
        optNumber(in, o, "hp", 0, 4096);
        optWhole(in, o, "food", 0, 1000);
        optNumber(in, o, "sat", 0, 1000);
        optWhole(in, o, "xp", 0, 100_000_000);
        optNumber(in, o, "xpP", 0, 1);
        optWhole(in, o, "armor", 0, 1000);
        optWhole(in, o, "held", 0, 8);
        o.add("hotbar", items(in.get("hotbar"), "hud.hotbar", 9));
        if (present(in, "armorSlots")) o.add("armorSlots", items(in.get("armorSlots"), "hud.armorSlots", 4));
        if (in.has("offhand")) o.add("offhand", item(in.get("offhand"), "hud.offhand"));
        // Main inventory (slots 9-35, top left to bottom right); optional: older plugins don't send it.
        if (present(in, "inv")) o.add("inv", items(in.get("inv"), "hud.inv", 27));
        return o;
    }

    private static void optNumber(JsonObject in, JsonObject out, String key, double min, double max) throws BadFrame {
        if (!present(in, key)) return;
        out.addProperty(key, Math.round(number(in.get(key), "hud." + key, min, max) * 100) / 100.0);
    }

    private static void optWhole(JsonObject in, JsonObject out, String key, long min, long max) throws BadFrame {
        if (!present(in, key)) return;
        out.addProperty(key, whole(in.get(key), "hud." + key, min, max));
    }

    private static List<JsonObject> chat(JsonElement e, int max) throws BadFrame {
        JsonArray a = array(e, "chat", max);
        List<JsonObject> out = new ArrayList<>(a.size());
        for (JsonElement item : a) {
            JsonObject in = object(item, "every chat line");
            String k = AddonRoutes.string(in, "k");
            if (k == null || !CHAT_KINDS.contains(k)) throw new BadFrame("chat k must be public, whisper, system or death");
            JsonElement m = in.get("m");
            if (!(m instanceof JsonPrimitive mp) || !mp.isString()) throw new BadFrame("chat m must be text");
            JsonObject o = new JsonObject();
            o.addProperty("t", whole(in.get("t"), "chat t", 0, Long.MAX_VALUE));
            o.addProperty("k", k);
            o.addProperty("s", cut(in, "s", MAX_NAME));
            o.addProperty("to", cut(in, "to", MAX_NAME));
            o.addProperty("m", Http.truncate(clean(mp.getAsString()), MAX_MESSAGE));
            out.add(o);
        }
        return out;
    }

    /** A sender or recipient: text cut to max, or null. */
    private static String cut(JsonObject in, String key, int max) throws BadFrame {
        JsonElement v = in.get(key);
        if (v == null || v.isJsonNull()) return null;
        if (!(v instanceof JsonPrimitive p) || !p.isString()) throw new BadFrame("chat " + key + " must be text or null");
        return Http.truncate(clean(p.getAsString()), max);
    }

    /** Control characters become spaces (a chat line is always one line of plain text). */
    static String clean(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isISOControl(ch) || ch == ' ' || ch == ' ') {
                if (sb == null) sb = new StringBuilder(s);
                sb.setCharAt(i, ' ');
            }
        }
        return sb == null ? s : sb.toString();
    }
}

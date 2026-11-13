package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * POST /api/v1/enchant/plan (ENCHANT-CONTRACT v1.0): a browser route (order permission, 4 KiB) that plans one enchanted item against the
 * stash as it is now and answers with the plan, what the stash holds for it and whether it can be ordered. The answer is advisory: order
 * create re-plans and re-checks stock and reservations itself (SR2-N5).
 *
 * <p>Check order: scope, validation, rate, cache, single-flight, permit, plan. A permit is never taken before validation. Planning runs on
 * this route's own pool (2 threads, no queue, AbortPolicy); the permit is released in the worker's finally, so a request that gave up
 * waiting can't free a slot a running plan still holds. Order create has its own slots and is never blocked by a plan flood.
 */
final class EnchantRoutes {
    static final int MAX_BODY = 4096;
    static final int POOL = 2;
    static final int CACHE_SIZE = 256;
    static final long CACHE_TTL_MS = 30_000;
    /** A plan cut off by the clock is not authoritative: it is reused only this briefly. */
    static final long CACHE_TTL_TIMED_OUT_MS = 3_000;
    /** The HTTP thread waits the plan's clock plus this. */
    static final long WAIT_EXTRA_MS = 250;

    /** What a finished plan request answers: the status and the body. */
    private record Reply(int status, JsonObject body, long at, long ttl, boolean cacheable) {
        JsonObject copy() {
            return body.deepCopy();
        }
    }

    private final EnchantOrders eo;
    private final OrderStore orders;
    private final IndexStore store;
    private final EnchantLimits limits;
    private final AtomicInteger threadNo = new AtomicInteger();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(POOL, POOL, 0, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
        Thread t = new Thread(r, "stash-manager-enchant-" + threadNo.incrementAndGet());
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.AbortPolicy());
    private final Semaphore permits = new Semaphore(POOL);
    private final Map<String, Reply> cache = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Reply> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private final Map<String, CompletableFuture<Reply>> inflight = new java.util.HashMap<>();
    private volatile LongSupplier clock = System::currentTimeMillis;
    /** The plan's clock (Limits.maxMillis); tests change it. */
    volatile long planMillis = EnchantOrders.PLAN_MILLIS;
    /** Test hook: runs in the worker before it plans (so a test can hold the pool's threads). */
    volatile Runnable beforePlan;

    EnchantRoutes(EnchantOrders eo, OrderStore orders, IndexStore store, EnchantLimits limits) {
        this.eo = eo;
        this.orders = orders;
        this.store = store;
        this.limits = limits;
    }

    void clock(LongSupplier clock) {
        this.clock = clock;
    }

    void close() {
        pool.shutdownNow();
    }

    void register(Router router) {
        router.exact(Router.POST, "/api/v1/enchant/plan", Router.Guard.BROWSER_WRITE, Router.Perm.ORDER, this::plan);
    }

    // ---------- the route ----------

    private static void refusal(HttpExchange ex, int status, String code, String message) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("code", code);
        o.addProperty("message", message);
        o.add("plan", com.google.gson.JsonNull.INSTANCE);
        Http.json(ex, status, o);
    }

    private static void busy(HttpExchange ex, long retryAfterSeconds) throws IOException {
        ex.getResponseHeaders().set("Retry-After", Long.toString(Math.max(1, retryAfterSeconds)));
        refusal(ex, 503, "busy", "The server is busy planning; try again in a moment");
    }

    private static void tooMany(HttpExchange ex, long waitMs) throws IOException {
        long seconds = Math.max(1, (waitMs + 999) / 1000);
        ex.getResponseHeaders().set("Retry-After", Long.toString(seconds));
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("code", "rate_limited");
        o.addProperty("message", "Too many plans; wait a moment");
        o.add("plan", com.google.gson.JsonNull.INSTANCE);
        o.addProperty("retryAfter", seconds);
        Http.json(ex, 429, o);
    }

    private void plan(HttpExchange ex) throws IOException {
        JsonElement json = AddonRoutes.readJson(ex, MAX_BODY); // 413 above 4 KiB
        if (json == null || !json.isJsonObject()) {
            refusal(ex, 400, "bad_request", "The body must be a JSON object");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        String server = AddonRoutes.string(o, "server");
        String stash = AddonRoutes.string(o, "stash");
        if (server == null || server.isEmpty() || server.length() > 256 || stash == null || stash.isEmpty() || stash.length() > 256) {
            refusal(ex, 400, "bad_request", "server and stash are required");
            return;
        }
        // 1. Scope: an unknown or out-of-scope stash is a 404, the same as everywhere (and a streamer's opaque id is mapped back).
        server = OrderRoutes.scoped(ex, server, stash);
        if (server == null) return;
        IndexStore.OrderCounts counts = store.orderCounts(server, stash);
        IndexStore.IndexBytes index = store.index(server, stash);
        if (counts == null || index == null) {
            Http.error(ex, 404, "no such stash");
            return;
        }
        // 2. Validation, before any rate, cache or permit. The answers never repeat what was sent.
        String key = AddonRoutes.string(o, "key");
        if (!EnchantOrders.validItem(key)) {
            refusal(ex, 400, "bad_request", "key must be an item id like minecraft:diamond_sword");
            return;
        }
        List<OrderStore.EnchantWant> wants = EnchantOrders.parseWants(o.get("enchants"));
        if (wants == null) {
            refusal(ex, 400, "bad_request", "enchants must be a list of at most 64 entries, each {id, lvl} with a level from 1 to " + EnchantIndex.MAX_LEVEL);
            return;
        }
        // 2b. The cheap game-rule checks come before the rate and any permit: a refusal costs the user nothing.
        EnchantOrders.Answer cheap = eo.precheck(server, stash, key, wants);
        if (cheap != null) {
            send(ex, reply(cheap));
            return;
        }
        String user = Router.user(ex).id();
        // 3. Rate: could a cache-miss plan start for this user now? (Nothing is drawn yet.)
        long wait = limits.peekPlan(user);
        if (wait > 0) {
            tooMany(ex, wait);
            return;
        }
        // 4. Cache: the same stash state (index etag, reservations, usable bottles), bot readiness and request.
        Map<String, Long> reserved = orders.reservedFor(server, stash);
        String mc = eo.supportedMc(server, stash);
        long usable = eo.bottlesUsable(server, stash, counts, reserved);
        boolean ready = mc != null && eo.botReady(server, stash, mc);
        boolean crafts = mc != null && eo.baseCraft(server, stash) != null; // a missing base is crafted only then (draft 3.4)
        int carry = eo.bots().carry(server, stash);
        String cacheKey = digest(server + "\0" + stash + "\0" + index.etag() + "\0" + new TreeMap<>(reserved) + "\0" + key + "\0" + wants
            + "\0" + usable + "\0" + ready + "\0" + crafts + "\0" + carry + "\0" + mc);
        long now = clock.getAsLong();
        synchronized (cache) {
            Reply hit = cache.get(cacheKey);
            if (hit != null && now - hit.at() < hit.ttl()) {
                send(ex, hit);
                return;
            }
        }
        // 5. Single flight: identical requests wait for one computation (only the first draws a permit and the user's limits).
        CompletableFuture<Reply> mine = new CompletableFuture<>();
        CompletableFuture<Reply> existing;
        synchronized (inflight) {
            existing = inflight.putIfAbsent(cacheKey, mine);
        }
        long waitMs = planMillis + WAIT_EXTRA_MS;
        if (existing != null) {
            try {
                send(ex, existing.get(waitMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException | ExecutionException e) {
                busy(ex, 2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                busy(ex, 2);
            }
            return;
        }
        // 6. Permit, then the draw on the user's limits (a race with the peek above is answered 429), then the plan on the pool.
        if (!permits.tryAcquire()) {
            finish(cacheKey, mine, null);
            busy(ex, 2);
            return;
        }
        long drawWait = limits.drawPlan(user);
        if (drawWait > 0) {
            permits.release();
            finish(cacheKey, mine, null);
            tooMany(ex, drawWait);
            return;
        }
        final String fServer = server;
        final String fStash = stash;
        final long maxMillis = planMillis;
        try {
            execute(() -> {
                long startNs = System.nanoTime();
                Reply reply = null;
                Throwable fatal = null;
                try {
                    Runnable hook = beforePlan;
                    if (hook != null) hook.run();
                    EnchantOrders.Answer a = eo.planOne(fServer, fStash, counts, reserved, key, wants, maxMillis);
                    reply = reply(a);
                } catch (Throwable t) {
                    // Whatever the library threw: the class name is logged (never the message), and the request answers busy.
                    System.err.println("Enchant plan failed: " + t.getClass().getName());
                    if (t instanceof VirtualMachineError) fatal = t; // rethrown after the cleanup below
                } finally {
                    // The worker's finally: the permit, the user's in-flight mark and the single-flight entry end here, whatever
                    // the HTTP thread did meanwhile.
                    limits.planDone(user, (System.nanoTime() - startNs) / 1_000_000L);
                    permits.release();
                    finish(cacheKey, mine, reply);
                }
                if (fatal instanceof VirtualMachineError vme) throw vme;
            });
        } catch (RuntimeException e) {
            limits.planDone(user, 0);
            permits.release();
            finish(cacheKey, mine, null);
            busy(ex, 2);
            return;
        }
        try {
            send(ex, mine.get(waitMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException | ExecutionException e) {
            busy(ex, 2); // the worker stops through the library's deadline
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            busy(ex, 2);
        }
    }

    /** The pool takes a task when a thread is free; a thread that just ended its task may not be back yet, so one retry after a yield. */
    private void execute(Runnable task) {
        try {
            pool.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Thread.yield();
            pool.execute(task);
        }
    }

    /** Free permits (tests). */
    int freePermits() {
        return permits.availablePermits();
    }

    /** Ends a single flight: followers get the reply (null: they answer busy), and a cacheable reply is remembered. */
    private void finish(String cacheKey, CompletableFuture<Reply> f, Reply reply) {
        if (reply != null && reply.cacheable()) {
            synchronized (cache) {
                cache.put(cacheKey, reply);
            }
        }
        synchronized (inflight) {
            inflight.remove(cacheKey, f);
        }
        if (reply == null) f.completeExceptionally(new IllegalStateException("no plan"));
        else f.complete(reply);
    }

    private Reply reply(EnchantOrders.Answer a) {
        JsonObject o = new JsonObject();
        int status = 200;
        boolean timedOut = a.timedOut();
        if (a.ok()) {
            o.addProperty("ok", true);
            o.addProperty("estimate", a.estimate());
            o.addProperty("canOrder", a.canOrder());
            o.addProperty("mc", a.mc());
            o.add("plan", a.plan());
            o.add("availability", a.availability());
            o.addProperty("truncated", a.truncated() || a.timedOut());
        } else {
            o.addProperty("ok", false);
            o.addProperty("code", a.code());
            o.addProperty("message", a.message());
            o.add("plan", com.google.gson.JsonNull.INSTANCE);
            if ("bad_request".equals(a.code())) status = 400;
        }
        // A plan the clock cut off is never authoritative and is cached only briefly; a refusal from a cut-off search likewise.
        return new Reply(status, o, clock.getAsLong(), timedOut ? CACHE_TTL_TIMED_OUT_MS : CACHE_TTL_MS, true);
    }

    private static void send(HttpExchange ex, Reply r) throws IOException {
        JsonObject body = r.copy();
        if (Redact.on(ex)) Redact.enchantPlan(body); // index keys come from the bot: anything but an item key reads (hidden)
        Http.json(ex, r.status(), body);
    }

    private static String digest(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** How many entries the cache holds (tests). */
    int cacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }
}

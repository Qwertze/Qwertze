package com.porter.stashmanager;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The per-user limits of the enchant planner (ENCHANT-CONTRACT v1.0, "Server implementation requirements", TL3-S2 / TL4-B2):
 * <ul>
 * <li>the plan route: 1 plan in flight, 12 cache-miss plans a minute with a burst of 5 (a token bucket that refills at 12 a minute),
 * and a bucket of 4 s of planner time a minute;</li>
 * <li>order create, retry and accept-curse: each counts once against the same 12 a minute and draws from neither the burst nor the
 * time bucket (they are bounded by the per-order budget and the order caps).</li>
 * </ul>
 * Every method answers 0 when the call may go on, else the milliseconds to wait (the route answers 429 with Retry-After).
 */
final class EnchantLimits {
    static final int PER_MINUTE = 12;
    static final double BURST = 5;
    static final long PLAN_MS_PER_MINUTE = 4000;
    private static final long MINUTE = 60_000;
    private static final int MAX_USERS = 1024;

    private static final class User {
        final ArrayDeque<Long> window = new ArrayDeque<>();
        double tokens = BURST;
        long tokensAt;
        double planMs = PLAN_MS_PER_MINUTE;
        long planMsAt;
        boolean inFlight;
        boolean orderBusy; // an order create, retry or accept-curse is being planned
        long lastUse;
    }

    private volatile LongSupplier clock;
    private final Map<String, User> users = new LinkedHashMap<>();

    EnchantLimits() {
        this(System::currentTimeMillis);
    }

    EnchantLimits(LongSupplier clock) {
        this.clock = clock;
    }

    void clock(LongSupplier clock) {
        this.clock = clock;
    }

    private User user(String id, long now) {
        User u = users.remove(id); // re-inserted last: the least recently used user is dropped first
        if (u == null) {
            u = new User();
            u.tokensAt = now;
            u.planMsAt = now;
        }
        u.lastUse = now;
        users.put(id, u);
        while (users.size() > MAX_USERS) {
            var it = users.entrySet().iterator();
            Map.Entry<String, User> eldest = it.next();
            if (eldest.getValue().inFlight || eldest.getValue().orderBusy) break; // never forget a plan that is still running
            it.remove();
        }
        while (!u.window.isEmpty() && now - u.window.peekFirst() >= MINUTE) u.window.pollFirst();
        u.tokens = Math.min(BURST, u.tokens + (now - u.tokensAt) * PER_MINUTE / (double) MINUTE);
        u.tokensAt = now;
        u.planMs = Math.min(PLAN_MS_PER_MINUTE, u.planMs + (now - u.planMsAt) * PLAN_MS_PER_MINUTE / (double) MINUTE);
        u.planMsAt = now;
        return u;
    }

    private static long untilWindow(User u, long now) {
        return u.window.size() >= PER_MINUTE ? Math.max(1, MINUTE - (now - u.window.peekFirst())) : 0;
    }

    private static long planWait(User u, long now) {
        if (u.inFlight) return 1000;
        long w = untilWindow(u, now);
        if (u.tokens < 1) w = Math.max(w, (long) Math.ceil((1 - u.tokens) * MINUTE / PER_MINUTE));
        if (u.planMs <= 0) w = Math.max(w, (long) Math.ceil(-u.planMs * MINUTE / PLAN_MS_PER_MINUTE) + 1);
        return w;
    }

    /** Whether a cache-miss plan could start now (nothing is drawn). The route asks this before it looks at the cache. */
    synchronized long peekPlan(String userId) {
        long now = clock.getAsLong();
        return planWait(user(userId, now), now);
    }

    /** Takes what a cache-miss plan costs (one of the 12, one token) and marks the plan in flight; 0 or the wait. */
    synchronized long drawPlan(String userId) {
        long now = clock.getAsLong();
        User u = user(userId, now);
        long w = planWait(u, now);
        if (w > 0) return w;
        u.window.addLast(now);
        u.tokens -= 1;
        u.inFlight = true;
        return 0;
    }

    /** The worker's end (its finally): the plan is no longer in flight and its planner time is paid. */
    synchronized void planDone(String userId, long elapsedMs) {
        long now = clock.getAsLong();
        User u = user(userId, now);
        u.inFlight = false;
        u.planMs = Math.max(-PLAN_MS_PER_MINUTE, u.planMs - Math.max(0, elapsedMs));
    }

    /**
     * The start of an order create, retry or accept-curse: one at a time per user (the next is refused until {@link #endOrder}), and it counts
     * once against the 12 a minute. 0 or the wait. The caller ends it in a finally.
     */
    synchronized long startOrder(String userId) {
        long now = clock.getAsLong();
        User u = user(userId, now);
        if (u.orderBusy) return 1000;
        long w = untilWindow(u, now);
        if (w > 0) return w;
        u.window.addLast(now);
        u.orderBusy = true;
        return 0;
    }

    synchronized void endOrder(String userId) {
        user(userId, clock.getAsLong()).orderBusy = false;
    }

    /** Order create, retry or accept-curse: counts once against the 12 a minute only. 0 or the wait. */
    synchronized long countOrder(String userId) {
        long now = clock.getAsLong();
        User u = user(userId, now);
        long w = untilWindow(u, now);
        if (w > 0) return w;
        u.window.addLast(now);
        return 0;
    }
}

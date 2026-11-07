package com.porter.stashmanager;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Password rate limits (login, password change, password confirm):
 * <ul>
 *   <li>per address: after 5 failures, that address is locked out for 1 minute, doubling with each further lockout up
 *       to 15 minutes; a success resets it. IPv6 addresses count per /64, since one host usually owns a whole /64.</li>
 *   <li>global: 20 failures from anywhere within 10 minutes pause every non-loopback attempt for 15 minutes, doubling
 *       with each further global pause up to 1 hour; a success resets the escalation.</li>
 *   <li>the server PC itself (loopback) is exempt from both, so its owner can always log in.</li>
 * </ul>
 */
final class LoginLimiter {
    static final int FAILURES_BEFORE_LOCK = 5;
    /** Failures from one address across every account (within the lock windows) before the address itself locks. */
    static final int ADDRESS_FAILURES = 10;
    static final long FIRST_LOCK_MS = 60_000;
    static final long MAX_LOCK_MS = 15 * 60_000;
    static final int GLOBAL_FAILURES = 20;
    static final long GLOBAL_WINDOW_MS = 10 * 60_000;
    static final long GLOBAL_LOCK_MS = 15 * 60_000;
    static final long MAX_GLOBAL_LOCK_MS = 60 * 60_000;
    private static final int MAX_TRACKED = 10_000;

    private static final class Entry {
        int failures;
        long lockedUntil;
        long nextLockMs = FIRST_LOCK_MS;
        long lastAttempt;
    }

    private final LongSupplier clock;
    private final Map<String, Entry> byAddress = new HashMap<>();
    private final ArrayDeque<Long> recentFailures = new ArrayDeque<>();
    private long globalLockedUntil;
    private long nextGlobalLockMs = GLOBAL_LOCK_MS;

    LoginLimiter() {
        this(System::currentTimeMillis);
    }

    LoginLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /** The rate-limit key of an address: the address itself, or its /64 for IPv6. */
    static String key(InetAddress address) {
        if (address == null) return "?";
        if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            StringBuilder sb = new StringBuilder("v6:");
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", b[i]));
            return sb.append("/64").toString();
        }
        return address.getHostAddress();
    }

    /**
     * Call before checking a password. Returns 0 when the attempt may go ahead, otherwise how many milliseconds to
     * wait (the reply is 429 with Retry-After). Loopback is never limited.
     */
    synchronized long tryAcquire(String key, boolean loopback) {
        if (loopback) return 0;
        long now = clock.getAsLong();
        if (globalLockedUntil > now) return globalLockedUntil - now;
        Entry e = byAddress.get(key);
        if (e != null && e.lockedUntil > now) return e.lockedUntil - now;
        if (e == null) {
            if (byAddress.size() >= MAX_TRACKED) prune(now);
            e = new Entry();
            byAddress.put(key, e);
        }
        e.lastAttempt = now;
        return 0;
    }

    synchronized void failure(String key, boolean loopback) {
        if (loopback) return;
        long now = clock.getAsLong();
        Entry e = byAddress.computeIfAbsent(key, k -> new Entry());
        e.lastAttempt = now;
        if (++e.failures >= FAILURES_BEFORE_LOCK) {
            e.lockedUntil = now + e.nextLockMs;
            e.nextLockMs = Math.min(e.nextLockMs * 2, MAX_LOCK_MS);
            e.failures = 0;
        }
        while (!recentFailures.isEmpty() && now - recentFailures.peekFirst() >= GLOBAL_WINDOW_MS) recentFailures.pollFirst();
        recentFailures.addLast(now);
        if (recentFailures.size() >= GLOBAL_FAILURES) {
            globalLockedUntil = now + nextGlobalLockMs;
            System.err.println(java.time.Instant.now() + " Website login paused for " + nextGlobalLockMs / 60_000
                + " min after " + GLOBAL_FAILURES + " wrong passwords in 10 min (the server PC itself can still log in).");
            nextGlobalLockMs = Math.min(nextGlobalLockMs * 2, MAX_GLOBAL_LOCK_MS);
            recentFailures.clear();
        }
    }

    /**
     * A failure from this address whatever account it was for. After {@link #ADDRESS_FAILURES} of them the address
     * itself locks (doubling like an account lock). A success never resets this count; it ages out with the address
     * entry. Not counted toward the global pause ({@link #failure} already does that once per attempt).
     */
    synchronized void addressFailure(String addressKey, boolean loopback) {
        if (loopback) return;
        long now = clock.getAsLong();
        Entry e = byAddress.computeIfAbsent(addressKey, k -> new Entry());
        e.lastAttempt = now;
        if (++e.failures >= ADDRESS_FAILURES) {
            e.lockedUntil = now + e.nextLockMs;
            e.nextLockMs = Math.min(e.nextLockMs * 2, MAX_LOCK_MS);
            e.failures = 0;
        }
    }

    synchronized void success(String key) {
        byAddress.remove(key);
        nextGlobalLockMs = GLOBAL_LOCK_MS;
    }

    private void prune(long now) {
        for (Iterator<Entry> it = byAddress.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.lockedUntil <= now && now - e.lastAttempt > MAX_LOCK_MS * 2) it.remove();
        }
    }
}

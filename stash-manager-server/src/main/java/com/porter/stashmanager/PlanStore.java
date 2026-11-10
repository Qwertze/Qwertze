package com.porter.stashmanager;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Build plans (PROTOCOL.md, "Additions: build planner"): the material list computed from an uploaded schematic, kept
 * in memory only. The schematic file itself is never kept: only what is here. A plan belongs to the user who
 * uploaded it. Plans end after 30 minutes unused or 2 hours in all, when their user has no session left, when the
 * user deletes them, and on restart. At most 3 per user (the oldest goes) and 50 in all.
 */
final class PlanStore {
    static final long IDLE_MS = 30L * 60 * 1000;
    static final long MAX_AGE_MS = 2L * 60 * 60 * 1000;
    static final int MAX_PER_USER = 3;
    static final int MAX_TOTAL = 50;
    static final int MAX_FILE_NAME = 64;
    static final Pattern ID = Pattern.compile("p-[0-9a-f]{12}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** One computed material list. Everything but lastUsed and the frozen order split is fixed. */
    static final class Plan {
        final String id;
        final String owner;
        final String fileName;
        final String format;
        final int formatVersion;
        final int dataVersion;
        final long size;
        final int sizeX;
        final int sizeY;
        final int sizeZ;
        final int regions;
        final long blocks;
        final String tableVersion;
        final int tableDataVersion;
        final Map<String, Long> items;
        final Map<String, Long> fluids;
        final Map<String, Long> containerItems;
        final Map<String, Long> unknown;
        final long unknownOmitted;
        /** Stack sizes from the block-items table, for every id in items, fluids and containerItems. */
        final Map<String, Integer> maxStacks;
        final long createdAt;
        long lastUsed;
        /** The order split the last dry run or order used (see PlannerRoutes), or null. Guarded by the plan. */
        PlannerRoutes.Split split;
        /** The craft planner's last answer for this plan (see PlannerRoutes.craftView), or null. */
        volatile PlannerRoutes.CraftCacheEntry craftCache;
        /** One craft computation per plan at a time (identical requests wait for it and then use its result). */
        final Object craftLock = new Object();

        Plan(String id, String owner, String fileName, String format, int formatVersion, int dataVersion, long size,
             int sizeX, int sizeY, int sizeZ, int regions, long blocks, String tableVersion, int tableDataVersion,
             Map<String, Long> items, Map<String, Long> fluids, Map<String, Long> containerItems, Map<String, Long> unknown,
             long unknownOmitted, Map<String, Integer> maxStacks, long createdAt) {
            this.id = id;
            this.owner = owner;
            this.fileName = fileName;
            this.format = format;
            this.formatVersion = formatVersion;
            this.dataVersion = dataVersion;
            this.size = size;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.regions = regions;
            this.blocks = blocks;
            this.tableVersion = tableVersion;
            this.tableDataVersion = tableDataVersion;
            this.items = Map.copyOf(items);
            this.fluids = Map.copyOf(fluids);
            this.containerItems = Map.copyOf(containerItems);
            this.unknown = Map.copyOf(unknown);
            this.unknownOmitted = unknownOmitted;
            this.maxStacks = Map.copyOf(maxStacks);
            this.createdAt = createdAt;
            this.lastUsed = createdAt;
        }

        int maxStack(String id) {
            return maxStacks.getOrDefault(id, 64);
        }

        long expiresAt() {
            synchronized (this) {
                return Math.min(lastUsed + IDLE_MS, createdAt + MAX_AGE_MS);
            }
        }
    }

    private final LongSupplier clock;
    private final Map<String, Plan> plans = new LinkedHashMap<>(); // id -> plan, oldest first
    private volatile Predicate<String> hasSession = id -> true;

    PlanStore() {
        this(System::currentTimeMillis);
    }

    PlanStore(LongSupplier clock) {
        this.clock = clock;
    }

    long now() {
        return clock.getAsLong();
    }

    /** Plans of a user with no session left are dropped by the next prune (whatever ended the sessions). */
    void sessionCheck(Predicate<String> hasSession) {
        this.hasSession = hasSession;
    }

    /** A new plan id (not yet stored). */
    synchronized String newId() {
        while (true) {
            byte[] b = new byte[6];
            RANDOM.nextBytes(b);
            String id = "p-" + HexFormat.of().formatHex(b);
            if (!plans.containsKey(id)) return id;
        }
    }

    /** Stores a plan, dropping the owner's oldest beyond 3 and the oldest overall beyond 50. */
    synchronized void add(Plan p) {
        prune();
        plans.put(p.id, p);
        List<String> mine = new ArrayList<>();
        for (Plan q : plans.values()) if (q.owner.equals(p.owner)) mine.add(q.id);
        for (int i = 0; i < mine.size() - MAX_PER_USER; i++) plans.remove(mine.get(i));
        Iterator<String> it = plans.keySet().iterator();
        while (plans.size() > MAX_TOTAL && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    /** The user's plan (now counted as used), or null for an unknown, expired or someone else's plan. */
    synchronized Plan get(String id, String owner) {
        prune();
        Plan p = plans.get(id);
        if (p == null || !p.owner.equals(owner)) return null;
        synchronized (p) {
            p.lastUsed = now();
        }
        return p;
    }

    /** The user's plans, oldest first. */
    synchronized List<Plan> list(String owner) {
        prune();
        List<Plan> out = new ArrayList<>();
        for (Plan p : plans.values()) if (p.owner.equals(owner)) out.add(p);
        return out;
    }

    /** Deletes the user's plan; false when there is no such plan of theirs. */
    synchronized boolean delete(String id, String owner) {
        Plan p = plans.get(id);
        if (p == null || !p.owner.equals(owner)) return false;
        plans.remove(id);
        return true;
    }

    /** Drops every plan of a user (their sessions were ended). */
    synchronized int dropUser(String owner) {
        int before = plans.size();
        plans.values().removeIf(p -> p.owner.equals(owner));
        return before - plans.size();
    }

    /** Drops expired plans and plans of users with no session left now (after a logout). */
    synchronized void pruneNow() {
        prune();
    }

    synchronized int size() {
        prune();
        return plans.size();
    }

    private void prune() {
        long now = now();
        Map<String, Boolean> live = new LinkedHashMap<>();
        plans.values().removeIf(p -> {
            long last;
            synchronized (p) {
                last = p.lastUsed;
            }
            if (now - last > IDLE_MS || now - p.createdAt > MAX_AGE_MS) return true;
            return !live.computeIfAbsent(p.owner, o -> hasSession.test(o));
        });
    }

    /**
     * The file name as shown: everything up to the last / or \ removed, control, format (bidi, zero-width) and other
     * unprintable characters dropped, trimmed, at most 64 characters; "schematic" when nothing is left.
     */
    static String cleanFileName(String raw) {
        if (raw == null) return "schematic";
        String s = raw;
        int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (cut >= 0) s = s.substring(cut + 1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            boolean bad = Character.isISOControl(cp) || type == Character.FORMAT || type == Character.SURROGATE
                || type == Character.PRIVATE_USE || type == Character.UNASSIGNED || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
            if (!bad) b.appendCodePoint(Character.isWhitespace(cp) ? ' ' : cp);
        }
        String out = Http.truncate(b.toString().strip(), MAX_FILE_NAME).strip();
        return out.isEmpty() ? "schematic" : out;
    }
}

package com.porter.qwerte.stash;

import java.util.ArrayList;
import java.util.List;

/**
 * The pure parts of repairing the items of an order with XP bottles: which items to take in one batch, what the guard watching the
 * throwing decides (a player came close, orbs vanished, the time cap), and the tally that becomes the contract's {@code result.repair}.
 * No Minecraft types, so all of it is tested on its own.
 */
public final class RepairRun {
    private RepairRun() {}

    /** The most items taken for one batch, and the most bottles (4 stacks), and the spare bottles added to what is needed. */
    public static final int MAX_BATCH_ITEMS = 4, MAX_BATCH_BOTTLES = 256, SPARE_BOTTLES = 2;

    /** How many of the items (in order) one batch takes, and how many bottles to have for them. */
    public record Batch(int items, int bottles) {}

    /**
     * @param needs bottles each pending item needs (in order), all above 0
     * @param availableBottles bottles the stash and the inventory can give
     * @return the first items whose bottles (plus a few spare) fit what is available and the batch limits; at least one item when
     *         there is any bottle at all (it is then repaired as far as the bottles go); 0 items when there are none
     */
    public static Batch pickBatch(List<Integer> needs, long availableBottles) {
        if (needs.isEmpty() || availableBottles <= 0) return new Batch(0, 0);
        int items = 0;
        long sum = 0;
        for (int need : needs) {
            if (items >= MAX_BATCH_ITEMS) break;
            long next = sum + Math.max(1, need);
            if (items > 0 && (next + SPARE_BOTTLES > availableBottles || next + SPARE_BOTTLES > MAX_BATCH_BOTTLES)) break;
            sum = next;
            items++;
        }
        long want = Math.min(MAX_BATCH_BOTTLES, sum + SPARE_BOTTLES);
        return new Batch(items, (int) Math.min(want, availableBottles));
    }

    /** What the guard says about the throwing. */
    public enum Verdict { CONTINUE, PAUSE, GIVE_UP }

    /**
     * Watches the throwing: a player within {@value #PLAYER_RADIUS} blocks, or orbs that vanished without repairing, stop it and it is
     * tried once more ({@link #MAX_RETRIES}); the second time, or {@link #CAP_TICKS} of throwing (about 2 minutes), the item is delivered as
     * it is ("interrupted"). Ticks are game ticks.
     */
    public static final class Guard {
        public static final int PLAYER_RADIUS = 8;
        public static final int MAX_RETRIES = 1;
        public static final int CAP_TICKS = 20 * 120;
        /** Quiet ticks (nobody near) before a paused throw carries on. */
        public static final int CALM_TICKS = 40;

        private int retries;
        private int throwTicks;
        private int calm;
        private boolean paused;

        /** One tick of throwing or waiting for orbs counts towards the cap. */
        public void countThrowTick() {
            throwTicks++;
        }

        /** Throwing time already used earlier in the same order. */
        public void preload(int ticks) {
            throwTicks = Math.max(0, ticks);
        }

        /** Retries already used earlier in the same order (the retry is once per order, not per batch). */
        public void preloadRetries(int used) {
            retries = Math.max(0, used);
        }

        public int retries() {
            return retries;
        }

        public int throwTicks() {
            return throwTicks;
        }

        public boolean capped() {
            return throwTicks >= CAP_TICKS;
        }

        public boolean paused() {
            return paused;
        }

        /** A player is near (or not) while throwing. */
        public Verdict playerNear(boolean near) {
            if (capped()) return Verdict.GIVE_UP;
            if (paused) return resume(near);
            if (!near) return Verdict.CONTINUE;
            return incident();
        }

        /** The orbs are gone and the item is no better: someone else probably took them. */
        public Verdict orbsVanished() {
            if (capped()) return Verdict.GIVE_UP;
            return incident();
        }

        private Verdict incident() {
            if (retries >= MAX_RETRIES) return Verdict.GIVE_UP;
            retries++;
            paused = true;
            calm = 0;
            return Verdict.PAUSE;
        }

        private Verdict resume(boolean near) {
            if (near) {
                calm = 0;
                return Verdict.PAUSE;
            }
            if (++calm >= CALM_TICKS) {
                paused = false;
                return Verdict.CONTINUE;
            }
            return Verdict.PAUSE;
        }
    }

    /** How one item of a run ended. */
    public enum Status { FULL, PARTIAL, UNTOUCHED }

    /**
     * @param key the key the item had in the order (damaged)
     * @param newKey the index key it has now, or null when it could not be found any more
     * @param damage damage left, {@code maxDamage} its maximum
     * @param reason for UNTOUCHED: why ("no bottles", "interrupted")
     */
    public record ItemResult(String key, String newKey, Status status, int damage, int maxDamage, String reason) {}

    /** What a run of the thrower did. */
    public record Outcome(int bottlesUsed, List<ItemResult> items, int throwTicks, int retries) {
        public Outcome(int bottlesUsed, List<ItemResult> items, int throwTicks) {
            this(bottlesUsed, items, throwTicks, 0);
        }
    }

    /** Collects an order's repair results into the contract's {@code result.repair}. */
    public static final class Tally {
        private int bottles, repaired;
        private final List<RepairEstimate.Partial> partial = new ArrayList<>();
        private final List<RepairEstimate.Skipped> skipped = new ArrayList<>();
        private final List<String> reasons = new ArrayList<>();
        private int notFull;

        public void skip(String key, String reason) {
            skip(key, reason, 1);
        }

        /** {@code items} items of this key were not repaired: one entry and one count each (v1.2: per item, not per lot). */
        public void skip(String key, String reason, int items) {
            for (int i = 0; i < Math.max(1, items); i++) {
                notFull++;
                if (!reasons.contains(reason)) reasons.add(reason);
                if (skipped.size() < RepairEstimate.MAX_ENTRIES) skipped.add(new RepairEstimate.Skipped(key, reason));
            }
        }

        /** The reasons so far, in the order they came (for saving). */
        public List<String> reasons() {
            return List.copyOf(reasons);
        }

        public int bottlesUsed() {
            return bottles;
        }

        /** Takes over an earlier state (a resumed order). */
        public void load(int bottlesUsed, int repairedCount, List<RepairEstimate.Partial> partials, List<RepairEstimate.Skipped> skips, List<String> savedReasons) {
            bottles = Math.max(0, bottlesUsed);
            repaired = Math.max(0, repairedCount);
            partial.clear();
            partial.addAll(partials);
            skipped.clear();
            skipped.addAll(skips);
            reasons.clear();
            reasons.addAll(savedReasons);
            notFull = partial.size() + skipped.size();
        }

        /** The note for the order's message: "2 item(s) not fully repaired: no bottles, interrupted", or "" when all went well. */
        public String note() {
            if (notFull == 0) return "";
            return notFull + " item(s) not fully repaired: " + String.join(", ", reasons);
        }

        public void bottles(int n) {
            bottles += Math.max(0, n);
        }

        public void add(Outcome o) {
            bottles(o.bottlesUsed());
            for (ItemResult r : o.items()) add(r);
        }

        public void add(ItemResult r) {
            switch (r.status()) {
                case FULL -> repaired++;
                case PARTIAL -> {
                    notFull++;
                    String why = r.reason() == null ? RepairEstimate.NO_BOTTLES : r.reason();
                    if (!reasons.contains(why)) reasons.add(why);
                    if (partial.size() < RepairEstimate.MAX_ENTRIES) partial.add(new RepairEstimate.Partial(r.key(), r.damage(), r.maxDamage()));
                }
                case UNTOUCHED -> skip(r.key(), r.reason() == null ? RepairEstimate.INTERRUPTED : r.reason());
            }
        }

        /** Null when repairs were not wanted or nothing was recorded. */
        public RepairEstimate.Result result() {
            RepairEstimate.Result r = new RepairEstimate.Result(bottles, repaired, List.copyOf(partial), List.copyOf(skipped));
            return r.any() ? r : null;
        }
    }
}

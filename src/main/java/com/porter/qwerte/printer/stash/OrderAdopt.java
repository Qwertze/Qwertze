package com.porter.qwerte.printer.stash;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Finds an order that was placed but whose answer was lost. No clock is compared (the server and this client can differ): the
 * order must be open, ours, hold exactly the lines that were sent, and not be one the ledger already knows. Pure.
 */
public final class OrderAdopt {
    private OrderAdopt() {}

    /**
     * @param lines item key to count, the item lines of the listed order
     * @param craft item key to count, its craft lines
     */
    public record Listed(String id, boolean open, boolean hasPlacedBy, String placedById, Map<String, Long> lines, Map<String, Long> craft) {
        public Listed(String id, boolean open, boolean hasPlacedBy, String placedById, Map<String, Long> lines) {
            this(id, open, hasPlacedBy, placedById, lines, Map.of());
        }
    }

    /** The id of the listed order that matches, or null. */
    public static String find(List<Listed> listed, String me, Map<String, Long> sent, Predicate<String> known) {
        return find(listed, me, sent, Map.of(), known);
    }

    /** The id of the listed order that holds exactly these item lines and these craft lines, or null. */
    public static String find(List<Listed> listed, String me, Map<String, Long> sent, Map<String, Long> sentCraft, Predicate<String> known) {
        for (Listed e : listed) {
            if (!e.open() || known.test(e.id())) continue;
            // An order with no placedBy is from before there were users: it is the owner's
            if (e.hasPlacedBy() && (me == null || me.isEmpty() || !e.placedById().equals(me))) continue;
            if (e.lines().equals(sent) && e.craft().equals(sentCraft)) return e.id();
        }
        return null;
    }

    /**
     * @param id             the adopted order, or null
     * @param chargeShulkers what to count against the run limit (the planned shulkers when nothing matched, else 0)
     * @param skipStash      the stash should rest for a while (nothing matched)
     */
    public record Outcome(String id, int chargeShulkers, boolean skipStash) {}

    /** {@link #find} and what to do when it finds nothing: the order may still exist, so it is charged and the stash skipped. */
    public static Outcome resolve(List<Listed> listed, String me, Map<String, Long> sent, Predicate<String> known, int plannedShulkers) {
        return resolve(listed, me, sent, Map.of(), known, plannedShulkers);
    }

    /** As above for an order that also had craft lines. */
    public static Outcome resolve(List<Listed> listed, String me, Map<String, Long> sent, Map<String, Long> sentCraft, Predicate<String> known,
                                  int plannedShulkers) {
        String id = find(listed, me, sent, sentCraft, known);
        return id != null ? new Outcome(id, 0, false) : new Outcome(null, Math.max(0, plannedShulkers), true);
    }

    /** Failures of looking for the order after which it is given up on as not confirmed. */
    public static final int MAX_FAILURES = 3;

    /** Whether to stop looking: the stash is gone (not found), or the look-up failed {@link #MAX_FAILURES} times. */
    public static boolean giveUp(boolean notFound, int failures) {
        return notFound || failures >= MAX_FAILURES;
    }
}

package com.porter.qwerte.printer.place;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks what to place in one tick: up to N candidates (best first, as the orderer ranked them) that can share ONE look,
 * so the tick sends one rotation packet. Pure.
 *
 * <p>Packet costs: a placement is a use-item packet plus a swing; changing the hotbar slot costs one; pressing or
 * releasing sneak costs one; the look costs one. The first candidate decides the sneak state of the batch; later
 * candidates join only when their look window overlaps the running overlap, they have the same sneak state, and the
 * budget still has room. Candidates that do not join stay for the next tick.
 */
public final class TickBatcher {
    private TickBatcher() {}

    public static final int COST_LOOK = 1;
    public static final int COST_USE = 1;
    public static final int COST_SWING = 1;
    public static final int COST_SLOT = 1;
    public static final int COST_SNEAK = 1;

    /**
     * @param extraPackets more packets this placement sends (extra placement clicks, use clicks)
     * @param slot         the hotbar slot that holds the item
     */
    public record Candidate(int id, LookWindow look, boolean sneak, int slot, int extraPackets) {
        public Candidate {
            if (look == null) look = LookWindow.any();
        }
    }

    /**
     * @param ids           the chosen candidates' ids, in placement order
     * @param window        the shared look window; null when the batch is empty
     * @param packets       the packets the batch will send, look included
     * @param sneak         the sneak state the batch needs
     * @param sneakChange   the batch needs a sneak press or release first
     * @param budgetLimited something was left out, or the batch is empty, only because of the packet budget
     */
    public record Batch(List<Integer> ids, LookWindow window, int packets, boolean sneak, boolean sneakChange, boolean budgetLimited) {
        public boolean empty() {
            return ids.isEmpty();
        }
    }

    public static final Batch EMPTY = new Batch(List.of(), null, 0, false, false, false);

    /** Candidates examined after the first; keeps a tick cheap when many are ready. */
    public static final int LOOKAHEAD = 64;

    /**
     * @param ordered       candidates, best first
     * @param maxBlocks     most placements this tick (blocks per tick)
     * @param swing         whether each placement swings (one more packet)
     * @param currentSlot   the hotbar slot selected now
     * @param currentSneak  whether the player is sneaking now
     * @param budget        packets that may be sent this tick; negative for no limit
     */
    public static Batch next(List<Candidate> ordered, int maxBlocks, boolean swing, int currentSlot, boolean currentSneak, int budget) {
        if (ordered.isEmpty() || maxBlocks <= 0) return EMPTY;
        Candidate first = ordered.get(0);
        int sneakCost = first.sneak() == currentSneak ? 0 : COST_SNEAK;
        int packets = COST_LOOK + sneakCost + placeCost(first, swing) + (first.slot() == currentSlot ? 0 : COST_SLOT);
        if (budget >= 0 && packets > budget) return new Batch(List.of(), null, 0, first.sneak(), sneakCost > 0, true);
        List<Integer> ids = new ArrayList<>();
        ids.add(first.id());
        LookWindow window = first.look();
        int slot = first.slot();
        boolean limited = false;
        int end = Math.min(ordered.size(), 1 + LOOKAHEAD);
        for (int i = 1; i < end && ids.size() < maxBlocks; i++) {
            Candidate c = ordered.get(i);
            if (c.sneak() != first.sneak()) continue;
            LookWindow w = window.intersect(c.look());
            if (w == null) continue;
            int add = placeCost(c, swing) + (c.slot() == slot ? 0 : COST_SLOT);
            if (budget >= 0 && packets + add > budget) {
                limited = true;
                continue;
            }
            ids.add(c.id());
            packets += add;
            window = w;
            slot = c.slot();
        }
        return new Batch(List.copyOf(ids), window, packets, first.sneak(), sneakCost > 0, limited);
    }

    private static int placeCost(Candidate c, boolean swing) {
        return COST_USE + (swing ? COST_SWING : 0) + Math.max(0, c.extraPackets());
    }
}

package com.porter.qwerte.pearl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trapdoor clicks that wait for the server. A trapdoor click toggles the block, and the answer (the block update)
 * takes at least one round trip. A second click on the same trapdoor before the answer is there toggles it back. So a
 * clicked trapdoor is pending and is not clicked again until it has been judged: by the server's block update at its
 * position, or, when none comes, by the client's state after a ping-aware timeout. Only a judged click that did not
 * give the wanted state counts as a failure.
 */
public final class TrapClicks {
    public enum Verdict { NOT_PENDING, PENDING, OK, FAILED }

    public record Click(long key, boolean wantOpen, long tick) {}

    private final Map<Long, Click> pending = new LinkedHashMap<>();
    private final Map<Long, Integer> failures = new HashMap<>();

    /** Ticks to wait for the answer: twice the round trip plus one, and at least 3. */
    public static int timeoutTicks(int pingTicks) {
        return Math.max(3, 2 * pingTicks + 1);
    }

    public boolean isPending(long key) {
        return pending.containsKey(key);
    }

    public boolean anyPending() {
        return !pending.isEmpty();
    }

    public List<Click> pendingClicks() {
        return new ArrayList<>(pending.values());
    }

    /** A click was sent. The caller checks {@link #isPending} first: a pending trapdoor is not clicked. */
    public void clicked(long key, boolean wantOpen, long tick) {
        pending.put(key, new Click(key, wantOpen, tick));
    }

    /**
     * @param stateIsWanted  the client's block state is the wanted one now
     * @param updateSeenTick the tick a block update for this position arrived, or -1 if none since the click
     */
    public Verdict judge(long key, long now, boolean stateIsWanted, long updateSeenTick, int timeoutTicks) {
        Click c = pending.get(key);
        if (c == null) return Verdict.NOT_PENDING;
        // The update is applied on the game thread a tick after it was received
        boolean answered = updateSeenTick >= c.tick() && now > updateSeenTick;
        boolean timedOut = now - c.tick() >= timeoutTicks;
        if (!answered && !timedOut) return Verdict.PENDING;
        pending.remove(key);
        if (stateIsWanted) {
            failures.remove(key);
            return Verdict.OK;
        }
        failures.merge(key, 1, Integer::sum);
        return Verdict.FAILED;
    }

    /** Judged clicks on this trapdoor that did not give the wanted state, since its last success. */
    public int failures(long key) {
        return failures.getOrDefault(key, 0);
    }

    public void clear() {
        pending.clear();
        failures.clear();
    }
}

package com.porter.qwerte.manager.local;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The recent clicks on container blocks, so a menu that opens can be matched to the click that opened it. If more than
 * one container was clicked within the window the match is ambiguous and nothing is captured. Pure.
 */
public final class ClickLog {
    /** A click on a container block; {@code menu} is whatever menu was open at the click (compared by identity). */
    public record Click(int x, int y, int z, long tick, Object menu) {}

    private final Deque<Click> clicks = new ArrayDeque<>();

    public void add(int x, int y, int z, long tick, Object menu) {
        expire(tick);
        clicks.add(new Click(x, y, z, tick, menu));
    }

    /** Forgets clicks older than the window. */
    public void expire(long now) {
        while (!clicks.isEmpty() && now - clicks.peekFirst().tick() > CaptureRules.INTERACT_WINDOW) clicks.pollFirst();
    }

    public boolean isEmpty() {
        return clicks.isEmpty();
    }

    /**
     * The click that opened a menu that appeared now, or null when there is none or it is ambiguous (clicks on two
     * different containers within the window). The log is emptied either way.
     */
    public Click take(long now) {
        expire(now);
        Click last = clicks.peekLast();
        if (last == null) return null;
        boolean ambiguous = false;
        for (Click c : clicks) {
            if (c.x() != last.x() || c.y() != last.y() || c.z() != last.z()) {
                ambiguous = true;
                break;
            }
        }
        clicks.clear();
        return ambiguous ? null : last;
    }
}

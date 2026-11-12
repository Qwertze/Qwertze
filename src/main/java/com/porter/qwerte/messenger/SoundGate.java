package com.porter.qwerte.messenger;

/** Lets something through at most once per {@code gapMs}. Pure. */
public final class SoundGate {
    private final long gapMs;
    private long last = Long.MIN_VALUE;

    public SoundGate(long gapMs) {
        this.gapMs = gapMs;
    }

    /** True (and the time is taken) if the last pass was at least the gap ago, or none yet. A clock that went back lets it through. */
    public synchronized boolean pass(long now) {
        if (last != Long.MIN_VALUE && now >= last && now - last < gapMs) return false;
        last = now;
        return true;
    }
}

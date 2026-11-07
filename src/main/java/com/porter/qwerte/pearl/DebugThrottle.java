package com.porter.qwerte.pearl;

import java.util.HashMap;
import java.util.Map;

/** Lets a message key through at most once per interval, so a per-tick reason can't spam the chat. */
public final class DebugThrottle {
    private final int intervalTicks;
    private final Map<String, Long> last = new HashMap<>();

    public DebugThrottle(int intervalTicks) {
        this.intervalTicks = intervalTicks;
    }

    /** True (and remembers the tick) if the key has not been let through in the last interval. */
    public boolean allow(long tick, String key) {
        Long at = last.get(key);
        if (at != null && tick - at < intervalTicks && tick >= at) return false;
        last.put(key, tick);
        return true;
    }

    public void clear() {
        last.clear();
    }
}

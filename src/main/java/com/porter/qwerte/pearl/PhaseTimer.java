package com.porter.qwerte.pearl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Marks along a run (pull detected, each phase's end, cover closed, verified) and the summary of the time between them. */
public final class PhaseTimer {
    public record Mark(String label, long tick, long nanos) {}

    private final List<Mark> marks = new ArrayList<>();

    public PhaseTimer(long tick, long nanos) {
        marks.add(new Mark("pull detected", tick, nanos));
    }

    public void mark(String label, long tick, long nanos) {
        marks.add(new Mark(label, tick, nanos));
    }

    public boolean has(String label) {
        return find(label) != null;
    }

    private Mark find(String label) {
        for (Mark m : marks) if (m.label().equals(label)) return m;
        return null;
    }

    /** Ticks from the pull to the mark, or -1 if there is no such mark. */
    public long ticksTo(String label) {
        Mark m = find(label);
        return m == null ? -1 : m.tick() - marks.get(0).tick();
    }

    public static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.0f ms", nanos / 1_000_000.0);
    }

    /** One line per mark: the time since the one before, and since the pull. */
    public List<String> summary() {
        List<String> lines = new ArrayList<>();
        Mark first = marks.get(0), prev = first;
        for (int i = 1; i < marks.size(); i++) {
            Mark m = marks.get(i);
            lines.add(String.format(Locale.ROOT, "%s: +%d ticks (%s), %d ticks (%s) after the pull", m.label(), m.tick() - prev.tick(),
                ms(m.nanos() - prev.nanos()), m.tick() - first.tick(), ms(m.nanos() - first.nanos())));
            prev = m;
        }
        return lines;
    }
}

package com.porter.qwerte.messenger;

import java.util.List;

/** Two message lists, each oldest first, as one list by time (the first list first on equal times). Pure. */
final class TimeMerge {
    private TimeMerge() {
    }

    /** Clears {@code out} and fills it with the messages of {@code a} and {@code b} in time order. */
    static void merge(List<Message> a, List<Message> b, List<Message> out) {
        out.clear();
        int i = 0;
        int j = 0;
        while (i < a.size() || j < b.size()) {
            boolean takeA = j >= b.size() || (i < a.size() && a.get(i).time() <= b.get(j).time());
            out.add(takeA ? a.get(i++) : b.get(j++));
        }
    }
}

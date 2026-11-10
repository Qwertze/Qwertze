package com.porter.qwerte.messenger;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A cached, case-insensitive set of the names on the tab list, behind the online/offline dots. Pure: the caller feeds it
 * the names (at most every few ticks) and asks per row, which allocates nothing.
 */
public final class OnlineStatus {
    private Set<String> names = Set.of();

    /** Replaces the set with the given names (null or empty means nobody is online). */
    public void update(Collection<String> online) {
        if (online == null || online.isEmpty()) {
            names = Set.of();
            return;
        }
        Set<String> next = new HashSet<>(online.size() * 2);
        for (String n : online) if (n != null) next.add(n.toLowerCase(Locale.ROOT));
        names = next;
    }

    /** For callers that keep the lowercase form of the name (Locale.ROOT) themselves: allocates nothing. */
    public boolean isOnlineKey(String lowerName) {
        return lowerName != null && !names.isEmpty() && names.contains(lowerName);
    }

    public boolean isOnline(String name) {
        return name != null && !names.isEmpty() && names.contains(name.toLowerCase(Locale.ROOT));
    }
}

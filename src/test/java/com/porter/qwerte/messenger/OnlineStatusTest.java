package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OnlineStatusTest {
    @Test
    void emptyMeansOffline() {
        OnlineStatus s = new OnlineStatus();
        assertFalse(s.isOnline("Steve"));
        s.update(null);
        assertFalse(s.isOnline("Steve"));
        assertFalse(s.isOnline(null));
    }

    @Test
    void matchesCaseInsensitively() {
        OnlineStatus s = new OnlineStatus();
        s.update(List.of("Steve", "ALEX_1"));
        assertTrue(s.isOnline("steve"));
        assertTrue(s.isOnline("STEVE"));
        assertTrue(s.isOnline("alex_1"));
        assertFalse(s.isOnline("Steve2"));
    }

    @Test
    void statusChangesWhenTheSetChanges() {
        OnlineStatus s = new OnlineStatus();
        s.update(List.of("Steve"));
        assertTrue(s.isOnline("Steve"));
        s.update(List.of("Alex"));
        assertFalse(s.isOnline("Steve"));
        assertTrue(s.isOnline("alex"));
        s.update(List.of());
        assertFalse(s.isOnline("Alex"));
    }

    @Test
    void ignoresNullEntries() {
        OnlineStatus s = new OnlineStatus();
        s.update(java.util.Arrays.asList("Steve", null));
        assertTrue(s.isOnline("steve"));
    }
}

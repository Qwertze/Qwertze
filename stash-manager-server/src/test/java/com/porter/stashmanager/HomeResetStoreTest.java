package com.porter.stashmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The once-an-hour home reset cooldown: atomic, rolled back on failure, and kept in area.json across a restart. */
class HomeResetStoreTest {
    static final long HOUR = 60L * 60 * 1000;

    @TempDir
    Path dir;

    AreaStore open() {
        AreaStore s = new AreaStore(dir);
        s.load();
        return s;
    }

    @Test
    void theCooldownSurvivesARestartAndRollsBack() throws Exception {
        AreaStore s = open();
        assertEquals(0, s.reserveHomeReset("srv", "st", 1_000_000L, HOUR), "the first reset is allowed (no previous)");
        assertEquals(-2, s.reserveHomeReset("srv", "st", 1_000_000L + 60_000, HOUR), "a minute later: refused");
        assertEquals(1_000_000L, s.lastHomeReset("srv", "st"));
        AreaStore again = open();
        assertEquals(1_000_000L, again.lastHomeReset("srv", "st"), "read back from area.json");
        assertEquals(-2, again.reserveHomeReset("srv", "st", 1_000_000L + 10 * 60_000, HOUR), "a restart does not clear the cooldown");
        // A failed reset gives the hour back.
        again.rollbackHomeReset("srv", "st", 0);
        assertEquals(0, open().lastHomeReset("srv", "st"));
        assertEquals(0, again.reserveHomeReset("srv", "st", 5_000_000L, HOUR));
        assertEquals(5_000_000L, again.reserveHomeReset("srv", "st", 5_000_000L + HOUR, HOUR), "after the hour: allowed, and the previous time is answered");
        // Another stash is not affected, and forgetting drops the entry.
        assertEquals(0, again.reserveHomeReset("srv", "other", 5_000_000L, HOUR));
        again.forget("srv", "st");
        assertEquals(0, open().lastHomeReset("srv", "st"));
    }

    @Test
    void aFailedWriteGivesTheHourBack() throws Exception {
        AreaStore s = open();
        // area.json is a folder with something in it: the file can't be written.
        Path blocker = dir.resolve("area.json");
        java.nio.file.Files.createDirectory(blocker);
        java.nio.file.Files.writeString(blocker.resolve("x"), "x");
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> s.reserveHomeReset("srv", "st", 1_000_000L, HOUR));
        assertEquals(0, s.lastHomeReset("srv", "st"), "memory is rolled back too, so the owner can try again");
    }

    @Test
    void aCooldownDatedInTheFutureIsDroppedOnLoad() throws Exception {
        AreaStore s = open();
        long now = System.currentTimeMillis();
        s.reserveHomeReset("srv", "far", now + 2 * 24 * HOUR, HOUR);
        s.reserveHomeReset("srv", "soon", now + HOUR, HOUR);
        s.reserveHomeReset("srv", "past", now - 10 * 60_000, HOUR);
        AreaStore again = open();
        assertEquals(0, again.lastHomeReset("srv", "far"), "two days ahead: not a cooldown set here");
        assertEquals(now + HOUR, again.lastHomeReset("srv", "soon"));
        assertEquals(now - 10 * 60_000, again.lastHomeReset("srv", "past"));
    }

    @Test
    void twoRequestsAtOnceCannotBothPass() throws Exception {
        AreaStore s = open();
        AtomicInteger passed = new AtomicInteger();
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                try {
                    if (s.reserveHomeReset("srv", "st", 9_000_000L, HOUR) != -2) passed.incrementAndGet();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        assertEquals(1, passed.get());
        assertTrue(s.lastHomeReset("srv", "st") > 0);
    }
}

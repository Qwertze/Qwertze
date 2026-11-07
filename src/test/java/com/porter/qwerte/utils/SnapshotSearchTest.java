package com.porter.qwerte.utils;

import org.junit.jupiter.api.Test;

import java.util.AbstractCollection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tab-list lookup that a worker thread runs while the render thread changes the map. */
class SnapshotSearchTest {
    /** A collection whose iterator throws a ConcurrentModificationException for the first few tries. */
    private static final class Flaky extends AbstractCollection<String> {
        final AtomicInteger failures;

        Flaky(int failures) {
            this.failures = new AtomicInteger(failures);
        }

        @Override
        public Iterator<String> iterator() {
            if (failures.getAndDecrement() > 0) throw new ConcurrentModificationException();
            return List.of("Steve", "Alex").iterator();
        }

        @Override
        public int size() {
            return 2;
        }
    }

    @Test
    void findsCaseInsensitively() {
        assertEquals("Alex", SnapshotSearch.find(List.of("Steve", "Alex"), s -> s.equalsIgnoreCase("ALEX")));
        assertNull(SnapshotSearch.find(List.of("Steve"), s -> s.equalsIgnoreCase("Bob")));
    }

    @Test
    void retriesWhenTheCopyRaces() {
        assertEquals("Alex", SnapshotSearch.find(new Flaky(SnapshotSearch.ATTEMPTS - 1), s -> s.equals("Alex")));
    }

    @Test
    void givesUpWithoutThrowing() {
        assertNull(SnapshotSearch.find(new Flaky(SnapshotSearch.ATTEMPTS), s -> true));
        assertNull(SnapshotSearch.copy(new Flaky(1000)));
    }

    /** The real crash: a HashMap changed by one thread while another walks its values. */
    @Test
    void survivesAWriterOnAnotherThread() throws Exception {
        Map<Integer, String> map = new HashMap<>();
        for (int i = 0; i < 300; i++) map.put(i, "p" + i);
        AtomicBoolean stop = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            int n = 1000;
            while (!stop.get()) {
                map.put(n, "x");
                map.remove(n);
                n++;
            }
        });
        writer.start();
        try {
            for (int i = 0; i < 20000; i++) {
                // never throws; a name that is never in the map gives null
                SnapshotSearch.find(map.values(), s -> s.equals("nobody"));
            }
        } finally {
            stop.set(true);
            writer.join();
        }
        assertTrue(map.size() >= 300);
    }
}

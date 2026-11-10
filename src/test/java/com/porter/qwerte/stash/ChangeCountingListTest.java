package com.porter.qwerte.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The structural counter of the stash's box list: a remove and an add in the same pass leave the size alone but not the counter. */
class ChangeCountingListTest {
    @Test
    void aRemoveAndAnAddKeepTheSizeButMoveTheCounter() {
        ChangeCountingList<String> l = new ChangeCountingList<>();
        l.add("a");
        l.add("b");
        int size = l.size(), before = l.changes();
        l.removeIf(s -> s.equals("a"));
        l.add("a2");
        assertEquals(size, l.size());
        assertTrue(l.changes() > before, "the size did not change, the counter did");
    }

    @Test
    void everyWayOfChangingTheListCounts() {
        ChangeCountingList<Integer> l = new ChangeCountingList<>();
        int c = l.changes();
        l.add(1);
        assertTrue(l.changes() > c);
        c = l.changes();
        l.add(0, 2);
        assertTrue(l.changes() > c);
        c = l.changes();
        l.addAll(List.of(3, 4));
        assertTrue(l.changes() > c);
        c = l.changes();
        l.remove(Integer.valueOf(3));
        assertTrue(l.changes() > c);
        c = l.changes();
        l.remove(0);
        assertTrue(l.changes() > c);
        c = l.changes();
        l.set(0, 9);
        assertTrue(l.changes() > c);
        c = l.changes();
        l.removeAll(List.of(9));
        assertTrue(l.changes() > c);
        c = l.changes();
        l.addAll(List.of(5, 6, 7));
        c = l.changes();
        l.retainAll(List.of(5, 6));
        assertTrue(l.changes() > c);
        c = l.changes();
        var it = l.iterator();
        it.next();
        it.remove();
        assertTrue(l.changes() > c, "an iterator's remove counts too");
        c = l.changes();
        l.sort(null);
        assertTrue(l.changes() > c);
        c = l.changes();
        l.replaceAll(x -> x + 1);
        assertTrue(l.changes() > c);
        c = l.changes();
        l.clear();
        assertTrue(l.changes() > c);
        // reading never counts
        l.add(1);
        c = l.changes();
        l.contains(1);
        l.size();
        l.get(0);
        assertEquals(c, l.changes());
    }
}

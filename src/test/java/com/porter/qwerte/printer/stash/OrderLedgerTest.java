package com.porter.qwerte.printer.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.stash.OrderLedger.Event;
import com.porter.qwerte.printer.stash.OrderLedger.Kind;
import com.porter.qwerte.printer.stash.OrderLedger.Placed;
import com.porter.qwerte.printer.stash.OrderLedger.Seen;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderLedgerTest {
    private static final long T = 100_000;
    private static final String S1 = "srv\u0000one", S2 = "srv\u0000two";

    private static Placed order(String id, String stash, Map<String, Long> lines) {
        return new Placed(id, stash, lines, 0);
    }

    @Test
    void anOpenOrderCoversItsLines() {
        OrderLedger l = new OrderLedger();
        assertFalse(l.hasOpen());
        l.placed(order("o-1", S1, Map.of("a", 100L)));
        assertTrue(l.hasOpen());
        assertTrue(l.hasOpen(S1));
        assertFalse(l.hasOpen(S2));
        assertEquals(Map.of("a", 100L), l.covered(0));
        assertEquals(List.of(S1), l.stashesToPoll());
        assertEquals(S1, l.stashFor("a", 0));
        assertNull(l.stashFor("b", 0));
    }

    @Test
    void aDeliveredOrderStaysCoveredUntilCollected() {
        OrderLedger l = new OrderLedger();
        l.placed(order("o-1", S1, Map.of("a", 100L)));
        List<Event> ev = l.update(S1, List.of(new Seen("o-1", "delivered", "")), 1000);
        assertEquals(1, ev.size());
        assertEquals(Kind.DELIVERED, ev.get(0).kind());
        assertFalse(l.hasOpen());
        assertEquals(Map.of("a", 100L), l.covered(2000));
        // seen in the inventory (not fetched by the Printer): 60 collected
        l.observeCarried(Map.of("a", 0L), false, T);
        l.observeCarried(Map.of("a", 60L), false, T);
        assertEquals(Map.of("a", 40L), l.covered(3000));
        l.observeCarried(Map.of("a", 100L), false, T);
        assertTrue(l.covered(4000).isEmpty());
    }

    @Test
    void aRiseWhileFetchingLocallyIsNotACollection() {
        OrderLedger l = new OrderLedger();
        l.placed(order("o-1", S1, Map.of("a", 100L)));
        l.update(S1, List.of(new Seen("o-1", "delivered", "")), 0);
        l.observeCarried(Map.of("a", 0L), false, T);
        l.observeCarried(Map.of("a", 100L), true, T);
        assertEquals(Map.of("a", 100L), l.covered(1));
    }

    @Test
    void aRiseOfAnotherItemDoesNotCount() {
        OrderLedger l = new OrderLedger();
        l.placed(order("o-1", S1, Map.of("a", 100L)));
        l.update(S1, List.of(new Seen("o-1", "delivered", "")), 0);
        l.observeCarried(Map.of(), false, T);
        l.observeCarried(Map.of("b", 500L), false, T);
        assertEquals(Map.of("a", 100L), l.covered(1));
    }

    @Test
    void anUncollectedDeliveryExpiresAfterTwentyMinutes() {
        OrderLedger l = new OrderLedger();
        l.placed(order("o-1", S1, Map.of("a", 100L)));
        l.update(S1, List.of(new Seen("o-1", "delivered", "")), 1000);
        assertFalse(l.covered(1000 + OrderLedger.DELIVERED_EXPIRY_MS).isEmpty());
        assertTrue(l.covered(1001 + OrderLedger.DELIVERED_EXPIRY_MS).isEmpty());
    }

    @Test
    void failedOrdersBlockTheirKeysAtThatStashOnly() {
        OrderLedger l = new OrderLedger();
        l.placed(order("o-1", S1, Map.of("a", 100L)));
        List<Event> ev = l.update(S1, List.of(new Seen("o-1", "failed", "no room")), 0);
        assertEquals(Kind.FAILED, ev.get(0).kind());
        assertEquals("no room", ev.get(0).message());
        assertTrue(l.blocked(S1, "a"));
        assertFalse(l.blocked(S2, "a"));
        assertFalse(l.blocked(S1, "b"));
        assertTrue(l.covered(0).isEmpty());
    }

    @Test
    void cancelledOrdersBlockTheirKeysEverywhereForTheRun() {
        OrderLedger l = new OrderLedger();
        l.placed(order("o-1", S1, Map.of("a", 100L)));
        List<Event> ev = l.update(S1, List.of(new Seen("o-1", "cancelled", "by the owner")), 0);
        assertEquals(Kind.CANCELLED, ev.get(0).kind());
        assertTrue(l.blocked(S1, "a"));
        assertTrue(l.blocked(S2, "a"));
        l.resetRun();
        assertFalse(l.blocked(S2, "a"));
    }

    @Test
    void anOrderThatIsNoLongerListedHasVanished() {
        OrderLedger l = new OrderLedger();
        l.placed(order("o-1", S1, Map.of("a", 100L)));
        List<Event> ev = l.update(S1, List.of(new Seen("o-9", "pending", "")), 0);
        assertEquals(Kind.VANISHED, ev.get(0).kind());
        assertFalse(l.hasOpen());
        assertTrue(l.covered(0).isEmpty());
        assertFalse(l.blocked(S1, "a"));
    }

    @Test
    void openAndUnknownStatusesChangeNothing() {
        OrderLedger l = new OrderLedger();
        l.placed(order("o-1", S1, Map.of("a", 100L)));
        assertTrue(l.update(S1, List.of(new Seen("o-1", "in_progress", "")), 0).isEmpty());
        assertTrue(l.hasOpen());
    }

    @Test
    void anUpdateForAnotherStashLeavesOrdersAlone() {
        OrderLedger l = new OrderLedger();
        l.placed(order("o-1", S1, Map.of("a", 100L)));
        assertTrue(l.update(S2, List.of(), 0).isEmpty());
        assertTrue(l.hasOpen());
    }

    @Test
    void theRunCountIsKeptUntilReset() {
        OrderLedger l = new OrderLedger();
        l.addShulkers(5);
        l.addShulkers(-3);
        assertEquals(5, l.shulkersThisRun());
        l.placed(order("o-1", S1, Map.of("a", 1L)));
        l.resetRun();
        assertEquals(0, l.shulkersThisRun());
        assertTrue(l.hasOpen());
        l.clear();
        assertFalse(l.hasOpen());
    }

    @Test
    void aVanishedOrderLastSeenInProgressKeepsItsCoverage() {
        OrderLedger l = new OrderLedger();
        l.placed(order("o-1", S1, Map.of("a", 100L)));
        l.update(S1, List.of(new Seen("o-1", "in_progress", "")), 0);
        List<Event> ev = l.update(S1, List.of(), 10);
        assertEquals(Kind.VANISHED, ev.get(0).kind());
        assertFalse(l.hasOpen());
        assertEquals(Map.of("a", 100L), l.covered(20));
    }

    @Test
    void aVanishedOrderLastSeenPendingIsForgotten() {
        OrderLedger l = new OrderLedger();
        l.placed(order("o-1", S1, Map.of("a", 100L)));
        l.update(S1, List.of(new Seen("o-1", "pending", "")), 0);
        l.update(S1, List.of(), 10);
        assertTrue(l.covered(20).isEmpty());
    }

    @Test
    void aRiseRightAfterLocalFetchingIsNotACollection() {
        OrderLedger l = new OrderLedger();
        l.placed(order("o-1", S1, Map.of("a", 100L)));
        l.update(S1, List.of(new Seen("o-1", "delivered", "")), 0);
        l.observeCarried(Map.of("a", 0L), false, T);
        l.observeCarried(Map.of("a", 0L), true, T + 1000);
        // inside the grace time the rise (an unpacked box finishing) is not a pickup
        l.observeCarried(Map.of("a", 50L), false, T + 1000 + OrderLedger.GRACE_MS - 1);
        assertEquals(Map.of("a", 100L), l.covered(T));
        l.observeCarried(Map.of("a", 80L), false, T + 1000 + OrderLedger.GRACE_MS + 1);
        assertEquals(Map.of("a", 70L), l.covered(T));
    }

    @Test
    void theSameMapIsNotReadTwice() {
        OrderLedger l = new OrderLedger();
        l.placed(order("o-1", S1, Map.of("a", 100L)));
        l.update(S1, List.of(new Seen("o-1", "delivered", "")), 0);
        Map<String, Long> m = Map.of("a", 30L);
        l.observeCarried(Map.of("a", 0L), false, T);
        l.observeCarried(m, false, T);
        l.observeCarried(m, false, T);
        assertEquals(Map.of("a", 70L), l.covered(T));
    }

    @Test
    void clearingKeepsTheRunCountAndBlockedKeys() {
        OrderLedger l = new OrderLedger();
        l.addShulkers(7);
        l.placed(order("o-1", S1, Map.of("a", 100L)));
        l.update(S1, List.of(new Seen("o-1", "failed", "")), 0);
        l.placed(order("o-2", S1, Map.of("b", 1L)));
        l.clear();
        assertFalse(l.hasOpen());
        assertEquals(7, l.shulkersThisRun());
        assertTrue(l.blocked(S1, "a"));
    }

    @Test
    void knowsOpenAndDeliveredIds() {
        OrderLedger l = new OrderLedger();
        l.placed(order("o-1", S1, Map.of("a", 1L)));
        l.placed(order("o-2", S1, Map.of("a", 1L)));
        l.update(S1, List.of(new Seen("o-1", "delivered", ""), new Seen("o-2", "pending", "")), 0);
        assertTrue(l.knows("o-1"));
        assertTrue(l.knows("o-2"));
        assertFalse(l.knows("o-3"));
    }

    @Test
    void thePollListChangesWithTheOrders() {
        OrderLedger l = new OrderLedger();
        assertTrue(l.stashesToPoll().isEmpty());
        l.placed(order("o-1", S1, Map.of("a", 1L)));
        assertEquals(List.of(S1), l.stashesToPoll());
        l.update(S1, List.of(new Seen("o-1", "delivered", "")), 0);
        assertTrue(l.stashesToPoll().isEmpty());
    }
}

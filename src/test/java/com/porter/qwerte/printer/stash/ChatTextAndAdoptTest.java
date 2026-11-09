package com.porter.qwerte.printer.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.stash.OrderAdopt.Listed;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatTextAndAdoptTest {
    @Test
    void chatTextDropsStylingAndFormatCharacters() {
        String out = ChatText.safe("(highlight)(bold)x%s§c\n", 100);
        assertFalse(out.contains("("));
        assertFalse(out.contains(")"));
        assertFalse(out.contains("%"));
        assertFalse(out.contains("§"));
        assertFalse(out.contains("\n"));
        assertTrue(out.contains("highlight"));
        assertEquals("[highlight][bold]xsc ", out);
    }

    @Test
    void chatTextIsCutAndNullSafe() {
        assertEquals("", ChatText.safe(null, 10));
        assertEquals(5, ChatText.safe("abcdefghij", 5).length());
    }

    private static Listed listed(String id, boolean open, boolean hasBy, String by, Map<String, Long> lines) {
        return new Listed(id, open, hasBy, by, lines);
    }

    @Test
    void anOrderIsAdoptedWhateverItsCreationTime() {
        // no time is compared at all: a server clock far from ours cannot hide the order
        List<Listed> l = List.of(listed("o-1", true, true, "me", Map.of("a", 64L)));
        assertEquals("o-1", OrderAdopt.find(l, "me", Map.of("a", 64L), id -> false));
    }

    @Test
    void notOursNotOpenOrDifferentLinesDoNotMatch() {
        Map<String, Long> sent = Map.of("a", 64L);
        assertNull(OrderAdopt.find(List.of(listed("o-1", true, true, "other", sent)), "me", sent, id -> false));
        assertNull(OrderAdopt.find(List.of(listed("o-1", false, true, "me", sent)), "me", sent, id -> false));
        assertNull(OrderAdopt.find(List.of(listed("o-1", true, true, "me", Map.of("a", 63L))), "me", sent, id -> false));
        assertNull(OrderAdopt.find(List.of(listed("o-1", true, true, "me", Map.of("a", 64L, "b", 1L))), "me", sent, id -> false));
        assertNull(OrderAdopt.find(List.of(listed("o-1", true, true, "me", sent)), "", sent, id -> false));
    }

    @Test
    void noMatchChargesTheRunAndSkipsTheStash() {
        Map<String, Long> sent = Map.of("a", 64L);
        OrderAdopt.Outcome none = OrderAdopt.resolve(List.of(), "me", sent, id -> false, 3);
        assertNull(none.id());
        assertEquals(3, none.chargeShulkers());
        assertTrue(none.skipStash());
        OrderAdopt.Outcome found = OrderAdopt.resolve(List.of(listed("o-1", true, true, "me", sent)), "me", sent, id -> false, 3);
        assertEquals("o-1", found.id());
        assertEquals(0, found.chargeShulkers());
        assertFalse(found.skipStash());
    }

    @Test
    void anOrderTheLedgerKnowsIsNotAdoptedTwice() {
        Map<String, Long> sent = Map.of("a", 64L);
        List<Listed> l = List.of(listed("o-1", true, true, "me", sent), listed("o-2", true, true, "me", sent));
        assertEquals("o-2", OrderAdopt.find(l, "me", sent, "o-1"::equals));
    }

    @Test
    void anOrderWithNoPlacedByIsTheOwnersAndMatches() {
        Map<String, Long> sent = Map.of("a", 64L);
        assertEquals("o-1", OrderAdopt.find(List.of(listed("o-1", true, false, "", sent)), "me", sent, id -> false));
    }

    @Test
    void lookingForTheOrderIsGivenUpOnAfterThreeFailuresOrWhenTheStashIsGone() {
        assertFalse(OrderAdopt.giveUp(false, 0));
        assertFalse(OrderAdopt.giveUp(false, OrderAdopt.MAX_FAILURES - 1));
        assertTrue(OrderAdopt.giveUp(false, OrderAdopt.MAX_FAILURES));
        assertTrue(OrderAdopt.giveUp(true, 1));
    }
}

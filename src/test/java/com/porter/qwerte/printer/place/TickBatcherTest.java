package com.porter.qwerte.printer.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import com.porter.qwerte.printer.place.TickBatcher.Batch;
import com.porter.qwerte.printer.place.TickBatcher.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TickBatcherTest {
    private static Candidate c(int id, LookWindow w) {
        return new Candidate(id, w, false, 0, 0);
    }

    @Test
    void emptyInputAndZeroBlocksGiveAnEmptyBatch() {
        assertTrue(TickBatcher.next(List.of(), 3, true, 0, false, -1).empty());
        assertTrue(TickBatcher.next(List.of(c(1, LookWindow.any())), 0, true, 0, false, -1).empty());
        assertNull(TickBatcher.EMPTY.window());
    }

    @Test
    void oneBlockPerTickTakesOnlyTheFirst() {
        Batch b = TickBatcher.next(List.of(c(1, LookWindow.any()), c(2, LookWindow.any())), 1, true, 0, false, -1);
        assertEquals(List.of(1), b.ids());
        assertEquals(TickBatcher.COST_LOOK + TickBatcher.COST_USE + TickBatcher.COST_SWING, b.packets());
        assertFalse(b.budgetLimited());
    }

    @Test
    void cheapestPlacementWithoutSwingIsTwoPackets() {
        Batch b = TickBatcher.next(List.of(c(1, LookWindow.any())), 1, false, 0, false, -1);
        assertEquals(2, b.packets());
    }

    @Test
    void candidatesJoinOnlyWhileTheirLooksShareAWindow() {
        LookWindow south = LookWindow.quadrant(Dir.SOUTH);
        LookWindow north = LookWindow.quadrant(Dir.NORTH);
        Batch b = TickBatcher.next(List.of(c(1, south), c(2, north), c(3, LookWindow.any()), c(4, south)), 4, true, 0, false, -1);
        // 2 is skipped (opposite quadrant) but 3 (any) and 4 (south) share the south window
        assertEquals(List.of(1, 3, 4), b.ids());
        assertTrue(b.window().intersects(south));
        assertFalse(b.window().intersects(north));
    }

    @Test
    void theSharedWindowKeepsNarrowingAcrossTheSeam() {
        LookWindow a = new LookWindow(170, 30, -90, 90);
        LookWindow b = new LookWindow(-170, 15, -90, 90);
        LookWindow far = new LookWindow(150, 5, -90, 90); // overlaps a (140..200) but not the a-b overlap (175..200)
        Batch r = TickBatcher.next(List.of(c(1, a), c(2, b), c(3, far)), 3, true, 0, false, -1);
        assertEquals(List.of(1, 2), r.ids());
        assertTrue(r.window().contains(180, 0));
    }

    @Test
    void maxBlocksCapsTheBatchAndLeavesTheRestForLater() {
        List<Candidate> list = List.of(c(1, LookWindow.any()), c(2, LookWindow.any()), c(3, LookWindow.any()), c(4, LookWindow.any()));
        Batch b = TickBatcher.next(list, 3, true, 0, false, -1);
        assertEquals(List.of(1, 2, 3), b.ids());
        assertEquals(1 + 3 * 2, b.packets());
    }

    @Test
    void sneakStateIsDecidedByTheFirstCandidateAndChangesCostAPacket() {
        Candidate sneaky = new Candidate(1, LookWindow.any(), true, 0, 0);
        Candidate plain = c(2, LookWindow.any());
        Candidate sneaky2 = new Candidate(3, LookWindow.any(), true, 0, 0);
        Batch b = TickBatcher.next(List.of(sneaky, plain, sneaky2), 3, true, 0, false, -1);
        assertEquals(List.of(1, 3), b.ids());
        assertTrue(b.sneak());
        assertTrue(b.sneakChange());
        assertEquals(1 + TickBatcher.COST_SNEAK + 2 + 2, b.packets());
        Batch already = TickBatcher.next(List.of(sneaky, sneaky2), 3, true, 0, true, -1);
        assertFalse(already.sneakChange());
        assertEquals(1 + 2 + 2, already.packets());
    }

    @Test
    void slotChangesCostAPacketEach() {
        Candidate a = new Candidate(1, LookWindow.any(), false, 2, 0);
        Candidate b = new Candidate(2, LookWindow.any(), false, 2, 0);
        Candidate c = new Candidate(3, LookWindow.any(), false, 5, 0);
        Batch r = TickBatcher.next(List.of(a, b, c), 3, true, 0, false, -1);
        // look 1, a: 2 + slot 1, b: 2 (same slot), c: 2 + slot 1
        assertEquals(1 + 3 + 2 + 3, r.packets());
        Batch same = TickBatcher.next(List.of(a, b), 2, true, 2, false, -1);
        assertEquals(1 + 2 + 2, same.packets());
    }

    @Test
    void extraPacketsFromClicksAreCounted() {
        Candidate candles = new Candidate(1, LookWindow.any(), false, 0, 3);
        assertEquals(1 + 2 + 3, TickBatcher.next(List.of(candles), 1, true, 0, false, -1).packets());
    }

    @Test
    void budgetStopsTheBatchAndFlagsIt() {
        List<Candidate> list = List.of(c(1, LookWindow.any()), c(2, LookWindow.any()), c(3, LookWindow.any()));
        Batch b = TickBatcher.next(list, 3, true, 0, false, 5); // look + one placement = 3, the second would make 5? no: 3 + 2 = 5 fits, 7 does not
        assertEquals(List.of(1, 2), b.ids());
        assertEquals(5, b.packets());
        assertTrue(b.budgetLimited());
    }

    @Test
    void aCheaperLaterCandidateCanStillFitWhenAnExpensiveOneDoesNot() {
        Candidate first = c(1, LookWindow.any());
        Candidate pricey = new Candidate(2, LookWindow.any(), false, 0, 10);
        Candidate cheap = c(3, LookWindow.any());
        Batch b = TickBatcher.next(List.of(first, pricey, cheap), 3, true, 0, false, 6);
        assertEquals(List.of(1, 3), b.ids());
        assertTrue(b.budgetLimited());
    }

    @Test
    void ifEvenTheFirstDoesNotFitTheBatchIsEmptyAndBudgetLimited() {
        Batch b = TickBatcher.next(List.of(c(1, LookWindow.any())), 3, true, 0, false, 2);
        assertTrue(b.empty());
        assertTrue(b.budgetLimited());
        assertNull(b.window());
        Batch ok = TickBatcher.next(List.of(c(1, LookWindow.any())), 3, true, 0, false, 3);
        assertEquals(List.of(1), ok.ids());
        assertFalse(ok.budgetLimited());
    }

    @Test
    void zeroBudgetMeansNothingFitsAndNegativeMeansNoLimit() {
        assertTrue(TickBatcher.next(List.of(c(1, LookWindow.any())), 1, true, 0, false, 0).budgetLimited());
        Batch b = TickBatcher.next(List.of(c(1, LookWindow.any()), c(2, LookWindow.any())), 2, true, 0, false, -1);
        assertEquals(2, b.ids().size());
    }

    @Test
    void lookaheadIsBounded() {
        java.util.ArrayList<Candidate> list = new java.util.ArrayList<>();
        list.add(c(0, LookWindow.quadrant(Dir.SOUTH)));
        for (int i = 1; i <= TickBatcher.LOOKAHEAD; i++) list.add(c(i, LookWindow.quadrant(Dir.NORTH)));
        list.add(c(999, LookWindow.quadrant(Dir.SOUTH))); // beyond the lookahead
        Batch b = TickBatcher.next(list, 5, true, 0, false, -1);
        assertEquals(List.of(0), b.ids());
        assertNotNull(b.window());
    }

    @Test
    void identicalInputGivesIdenticalBatches() {
        List<Candidate> list = List.of(c(1, LookWindow.quadrant(Dir.EAST)), c(2, LookWindow.any()), c(3, LookWindow.quadrant(Dir.EAST)));
        assertEquals(TickBatcher.next(list, 3, true, 0, false, 100), TickBatcher.next(list, 3, true, 0, false, 100));
    }
}

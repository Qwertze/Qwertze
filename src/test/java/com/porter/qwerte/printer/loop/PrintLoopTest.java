package com.porter.qwerte.printer.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.loop.PrintLoop.Act;
import com.porter.qwerte.printer.loop.PrintLoop.Obs;
import com.porter.qwerte.printer.loop.PrintLoop.Phase;
import com.porter.qwerte.printer.loop.PrintLoop.Step;
import com.porter.qwerte.printer.loop.PrintLoop.Verdict;
import com.porter.qwerte.printer.loop.PrintLoop.Wrong;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class PrintLoopTest {
    /** Observations are built from a quiet one by changing what a test cares about. */
    private static Obs obs(UnaryOperator<B> f) {
        return f.apply(new B()).build();
    }

    private static final class B {
        boolean worldChanged, cancelled, loaded = true, scanDone = true, breaking, placeable, walkTarget, lacking, resupplyActive, sessionActive,
            inventoryFull, baritoneBusy, supplyPossible = true, canWalk = true, waiting, disableWhenOut;
        String layerHold;
        String loadError, wrongInfo, lackingInfo, resupplyFailed, orderWait, orderProblem;
        int pending = 10, wrongPending;
        Wrong wrongMode = Wrong.SKIP_AND_LIST;

        B world() { worldChanged = true; return this; }
        B cancel() { cancelled = true; return this; }
        B pending(int n) { pending = n; return this; }
        B place() { placeable = true; return this; }
        B walk() { walkTarget = true; return this; }
        B lack() { lacking = true; lackingInfo = "stone"; return this; }
        B wrong(Wrong m) { wrongPending = 1; wrongInfo = "dirt at 1, 2, 3"; wrongMode = m; return this; }
        B full() { inventoryFull = true; return this; }
        B busy() { baritoneBusy = true; return this; }
        B noSupply() { supplyPossible = false; return this; }
        B order(String what) { orderWait = what; return this; }
        B problem(String what) { orderProblem = what; return this; }

        Obs build() {
            return new Obs(worldChanged, cancelled, loaded, loadError, scanDone, pending, wrongPending, wrongInfo, breaking, wrongMode, placeable,
                walkTarget, lacking, lackingInfo, supplyPossible, resupplyActive, resupplyFailed, sessionActive, inventoryFull, baritoneBusy, canWalk, waiting, orderWait, orderProblem, disableWhenOut, layerHold);
        }
    }

    /** A loop that has loaded, scanned and is building. */
    private static PrintLoop building() {
        PrintLoop loop = new PrintLoop();
        assertEquals(Act.LOAD, loop.tick(Obs.idle()).act());
        assertEquals(Act.SCAN, loop.tick(obs(b -> b)).act());
        assertEquals(Act.PLAN, loop.tick(obs(b -> b)).act());
        assertEquals(Phase.BUILDING, loop.phase());
        return loop;
    }

    @Test
    void loadsThenScansThenPlans() {
        PrintLoop loop = new PrintLoop();
        assertEquals(Act.LOAD, loop.tick(Obs.idle()).act());
        assertEquals(Phase.LOADING, loop.phase());
        Obs notYet = obs(b -> { b.loaded = false; return b; });
        assertEquals(Act.NONE, loop.tick(notYet).act());
        assertEquals(Act.SCAN, loop.tick(obs(b -> b)).act());
        Obs scanning = obs(b -> { b.scanDone = false; return b; });
        assertEquals(Act.SCAN, loop.tick(scanning).act());
        assertEquals(Act.SCAN, loop.tick(scanning).act());
        assertEquals(Act.PLAN, loop.tick(obs(b -> b)).act());
    }

    @Test
    void aLoadErrorEndsTheRun() {
        PrintLoop loop = new PrintLoop();
        loop.tick(Obs.idle());
        Step s = loop.tick(obs(b -> { b.loaded = false; b.loadError = "no such file"; return b; }));
        assertEquals(Act.ABORT, s.act());
        assertEquals("no such file", s.msg());
        assertTrue(s.ends());
        assertTrue(loop.ended());
    }

    @Test
    void placesWhatIsInReach() {
        PrintLoop loop = building();
        assertEquals(Act.PLACE, loop.tick(obs(B::place)).act());
    }

    @Test
    void noMaterialsLeadsToResupply() {
        PrintLoop loop = building();
        Step s = loop.tick(obs(B::lack));
        assertEquals(Act.RESUPPLY, s.act());
        // while the resupply runs the loop leaves the world alone
        Obs running = obs(b -> { b.resupplyActive = true; b.lacking = true; return b; });
        assertEquals(Act.STOP_WALK, loop.tick(running).act());
        assertEquals(Phase.RESUPPLYING, loop.phase());
        assertEquals(Act.NONE, loop.tick(running).act());
        // it ended with materials: back to placing
        assertEquals(Act.PLACE, loop.tick(obs(B::place)).act());
        assertEquals(Phase.BUILDING, loop.phase());
    }

    @Test
    void noMaterialsAndNoSourcePausesWithAMessageOnce() {
        PrintLoop loop = building();
        Step s = loop.tick(obs(b -> b.lack().noSupply()));
        assertEquals(Phase.PAUSED, loop.phase());
        assertNotNull(s.msg());
        assertTrue(s.msg().contains("stone"));
        // the same condition says nothing again
        assertNull(loop.tick(obs(b -> b.lack().noSupply())).msg());
        // the player adds some: it resumes by itself and says so
        Step resumed = loop.tick(obs(B::place));
        assertEquals(Act.PLACE, resumed.act());
        assertEquals("Printing resumed.", resumed.msg());
        assertEquals(Phase.BUILDING, loop.phase());
    }

    @Test
    void aFailedResupplyPausesAndTriesAgainLater() {
        PrintLoop loop = building();
        assertEquals(Act.RESUPPLY, loop.tick(obs(B::lack)).act());
        Step failed = loop.tick(obs(b -> { b.lacking = true; b.resupplyFailed = "no source holds stone."; return b; }));
        assertEquals(Phase.PAUSED, loop.phase());
        assertTrue(failed.msg().contains("no source holds stone."));
        for (int i = 0; i < PrintLoop.RESUPPLY_RETRY - 2; i++) assertEquals(Act.NONE, loop.tick(obs(B::lack)).act());
        Act again = null;
        for (int i = 0; i < 4 && again != Act.RESUPPLY; i++) again = loop.tick(obs(B::lack)).act();
        assertEquals(Act.RESUPPLY, again);
    }

    @Test
    void noSourceTextNamesTheNewOptions() {
        PrintLoop loop = building();
        Step s = loop.tick(obs(b -> b.lack().noSupply()));
        assertTrue(s.msg().contains("use-ender-chest"));
        assertTrue(s.msg().contains("order-from-stash"));
        assertFalse(s.msg().contains("use-stash-index"));
    }

    @Test
    void outOfMaterialsWithAnOrderOnTheWayPausesOnceThenResumes() {
        PrintLoop loop = building();
        Step s = loop.tick(obs(b -> b.lack().noSupply().order("the order from stash Main")));
        assertEquals(Phase.PAUSED, loop.phase());
        assertEquals(Act.STOP_WALK, s.act());
        assertTrue(s.msg().contains("Waiting for the order from stash Main"));
        assertTrue(s.msg().contains("stone"));
        // the same condition says nothing again
        assertNull(loop.tick(obs(b -> b.lack().noSupply().order("the order from stash Main"))).msg());
        // the delivery is picked up: it resumes by itself and says so
        Step resumed = loop.tick(obs(B::place));
        assertEquals(Act.PLACE, resumed.act());
        assertEquals("Printing resumed.", resumed.msg());
        assertEquals(Phase.BUILDING, loop.phase());
    }

    @Test
    void aSourceIsStillUsedBeforeWaitingForAnOrder() {
        PrintLoop loop = building();
        assertEquals(Act.RESUPPLY, loop.tick(obs(b -> b.lack().order("the order from stash Main"))).act());
    }

    @Test
    void theFailedResupplyNoteMentionsAnOrderOnTheWay() {
        PrintLoop loop = building();
        assertEquals(Act.RESUPPLY, loop.tick(obs(B::lack)).act());
        Step failed = loop.tick(obs(b -> { b.lacking = true; b.resupplyFailed = "no source holds stone."; b.orderWait = "the order from stash Main"; return b; }));
        assertTrue(failed.msg().contains("Waiting for the order from stash Main."));
        // during the wait it stays quiet, an order wait or not
        assertEquals(Act.NONE, loop.tick(obs(b -> b.lack().order("the order from stash Main"))).act());
        assertNull(loop.tick(obs(b -> b.lack().order("the order from stash Main"))).msg());
    }

    @Test
    void aStableOrderWaitPrintsOnceEvenWhenItsWordingChanges() {
        PrintLoop loop = building();
        assertNotNull(loop.tick(obs(b -> b.lack().noSupply().order("the stash order to be placed"))).msg());
        assertNull(loop.tick(obs(b -> b.lack().noSupply().order("the order from stash Main"))).msg());
        assertNull(loop.tick(obs(b -> b.lack().noSupply().order("your open stash order(s) to finish"))).msg());
        assertEquals(Phase.PAUSED, loop.phase());
    }

    @Test
    void whenOrdersCantHelpTheRealProblemIsShownOnce() {
        PrintLoop loop = building();
        Step s = loop.tick(obs(b -> b.lack().noSupply().problem("you are not logged in to the Stash Manager server")));
        assertTrue(s.msg().contains("not logged in"));
        assertFalse(s.msg().contains("use-stash-index"));
        assertNull(loop.tick(obs(b -> b.lack().noSupply().problem("you are not logged in to the Stash Manager server"))).msg());
    }

    @Test
    void theRetryDelaySurvivesTheSupplyFlipping() {
        PrintLoop loop = building();
        assertEquals(Act.RESUPPLY, loop.tick(obs(B::lack)).act());
        loop.tick(obs(b -> { b.lacking = true; b.resupplyFailed = "no source holds stone."; return b; }));
        // the supply goes away (a different pause) and comes back: the retry time still holds
        loop.tick(obs(b -> b.lack().noSupply()));
        for (int i = 0; i < 100; i++) assertEquals(Act.NONE, loop.tick(obs(B::lack)).act());
    }

    @Test
    void aFullInventoryPausesWithAMessage() {
        PrintLoop loop = building();
        Step s = loop.tick(obs(b -> b.lack().full()));
        assertEquals(Phase.PAUSED, loop.phase());
        assertEquals(Act.STOP_WALK, s.act());
        assertTrue(s.msg().toLowerCase().contains("full"));
        // a slot is freed
        assertEquals(Act.RESUPPLY, loop.tick(obs(B::lack)).act());
        assertEquals(Phase.BUILDING, loop.phase());
    }

    @Test
    void wrongBlockSkipAndList() {
        PrintLoop loop = building();
        assertEquals(Act.SKIP_WRONG, loop.tick(obs(b -> b.wrong(Wrong.SKIP_AND_LIST).place())).act());
        assertEquals(Phase.BUILDING, loop.phase());
    }

    @Test
    void wrongBlockBreakAndReplace() {
        PrintLoop loop = building();
        assertEquals(Act.BREAK_WRONG, loop.tick(obs(b -> b.wrong(Wrong.BREAK_AND_REPLACE).place())).act());
        // a break in progress keeps going even when nothing else is pending
        Obs breaking = obs(b -> { b.wrongMode = Wrong.BREAK_AND_REPLACE; b.breaking = true; b.pending = 0; return b; });
        assertEquals(Act.BREAK_WRONG, loop.tick(breaking).act());
    }

    @Test
    void wrongBlockPauseWaitsUntilItIsGone() {
        PrintLoop loop = building();
        Step s = loop.tick(obs(b -> b.wrong(Wrong.PAUSE).place()));
        assertEquals(Phase.PAUSED, loop.phase());
        assertTrue(s.msg().contains("dirt at 1, 2, 3"));
        assertEquals(Act.NONE, loop.tick(obs(b -> b.wrong(Wrong.PAUSE).place())).act());
        // the player removes it
        assertEquals(Act.PLACE, loop.tick(obs(B::place)).act());
        assertEquals(Phase.BUILDING, loop.phase());
    }

    @Test
    void changingTheWrongBlockModeWhilePausedTakesEffect() {
        PrintLoop loop = building();
        loop.tick(obs(b -> b.wrong(Wrong.PAUSE)));
        assertEquals(Act.SKIP_WRONG, loop.tick(obs(b -> b.wrong(Wrong.SKIP_AND_LIST))).act());
        assertEquals(Phase.BUILDING, loop.phase());
    }

    @Test
    void baritoneBusyPausesInsteadOfWalking() {
        PrintLoop loop = building();
        Step s = loop.tick(obs(b -> b.walk().busy()));
        assertEquals(Phase.PAUSED, loop.phase());
        assertEquals(Act.STOP_WALK, s.act());
        assertTrue(s.msg().contains("Baritone"));
        // never a walk while it is busy
        assertEquals(Act.NONE, loop.tick(obs(b -> b.walk().busy())).act());
        // idle again
        assertEquals(Act.WALK, loop.tick(obs(B::walk)).act());
        assertEquals(Phase.BUILDING, loop.phase());
    }

    @Test
    void busyBaritoneDoesNotStopPlacingInReach() {
        PrintLoop loop = building();
        assertEquals(Act.PLACE, loop.tick(obs(b -> b.place().busy())).act());
    }

    @Test
    void walkingOffPauses() {
        PrintLoop loop = building();
        Step s = loop.tick(obs(b -> { b.walk().canWalk = false; return b; }));
        assertEquals(Phase.PAUSED, loop.phase());
        assertTrue(s.msg().contains("walking"));
    }

    @Test
    void aWorldChangeAbortsFromEveryPhase() {
        PrintLoop loading = new PrintLoop();
        loading.tick(Obs.idle());
        assertEquals(Act.ABORT, loading.tick(obs(b -> b.world())).act());

        PrintLoop loop = building();
        Step s = loop.tick(obs(b -> b.world().place()));
        assertEquals(Act.ABORT, s.act());
        assertTrue(s.ends());
        assertEquals(Phase.STOPPED, loop.phase());
        // and stays over
        assertEquals(Act.NONE, loop.tick(obs(B::place)).act());

        PrintLoop paused = building();
        paused.tick(obs(b -> b.lack().noSupply()));
        assertEquals(Act.ABORT, paused.tick(obs(b -> b.world())).act());
    }

    @Test
    void cancelEndsTheRunAndDropsPendingChecks() {
        PrintLoop loop = building();
        loop.placed(5);
        Step s = loop.tick(obs(b -> b.cancel().place()));
        assertEquals(Act.ABORT, s.act());
        assertTrue(s.ends());
        assertEquals(0, loop.verifying());
        assertEquals(Act.NONE, loop.tick(obs(B::place)).act());
    }

    @Test
    void stopLeavesNothingToStartAgain() {
        PrintLoop loop = building();
        loop.placed(1);
        loop.stop();
        assertTrue(loop.ended());
        assertEquals(0, loop.verifying());
        assertEquals(Act.NONE, loop.tick(obs(B::place)).act());
    }

    @Test
    void finishesWhenNothingIsLeft() {
        PrintLoop loop = building();
        Step s = loop.tick(obs(b -> b.pending(0)));
        assertEquals(Act.FINISH, s.act());
        assertEquals(Phase.DONE, loop.phase());
    }

    @Test
    void doesNotFinishWhileAPlacedBlockWaitsToBeChecked() {
        PrintLoop loop = building();
        loop.placed(9);
        assertEquals(Act.NONE, loop.tick(obs(b -> b.pending(0))).act());
        assertFalse(loop.ended());
    }

    @Test
    void whenNothingCanBeBuiltForAWhileItPausesOnceInsteadOfEnding() {
        PrintLoop loop = building();
        Step said = null;
        int messages = 0;
        for (int i = 0; i < PrintLoop.STUCK_TICKS * 4; i++) {
            Step s = loop.tick(obs(b -> b));
            assertFalse(s.ends());
            if (s.msg() != null) {
                messages++;
                said = s;
            }
        }
        assertEquals(1, messages);
        assertTrue(said.msg().startsWith(PrintLoop.STUCK_PREFIX));
        assertTrue(said.msg().contains("10 cell"));
        assertEquals(Phase.PAUSED, loop.phase());
    }

    @Test
    void aStuckPauseResumesByItselfWhenSomethingBecomesPlaceableOrWalkable() {
        PrintLoop loop = building();
        for (int i = 0; i < PrintLoop.STUCK_TICKS + 1; i++) loop.tick(obs(b -> b));
        assertEquals(Phase.PAUSED, loop.phase());
        Step s = loop.tick(obs(B::place));
        assertEquals(Act.PLACE, s.act());
        assertEquals(Phase.BUILDING, loop.phase());
        assertEquals("Printing resumed.", s.msg());

        for (int i = 0; i < PrintLoop.STUCK_TICKS + 1; i++) loop.tick(obs(b -> b));
        assertEquals(Phase.PAUSED, loop.phase());
        assertEquals(Act.WALK, loop.tick(obs(B::walk)).act());
        assertEquals(Phase.BUILDING, loop.phase());
    }

    @Test
    void leavingAPauseForAWaitingBlipSaysNothingAndTheStuckMessageSaysItStaysOn() {
        PrintLoop loop = building();
        Step stuck = null;
        for (int i = 0; i < PrintLoop.STUCK_TICKS + 1; i++) {
            Step s = loop.tick(obs(b -> b));
            if (s.msg() != null) stuck = s;
        }
        assertTrue(stuck.msg().contains("stays on"));
        assertTrue(stuck.msg().contains("Turn it off yourself"));
        Step blip = loop.tick(obs(b -> { b.waiting = true; return b; }));
        assertEquals(Act.NONE, blip.act());
        assertNull(blip.msg());
        assertEquals(Phase.BUILDING, loop.phase());

        PrintLoop layer = building();
        layer.tick(obs(b -> { b.layerHold = "Layer done"; return b.pending(0); }));
        assertEquals(Phase.PAUSED, layer.phase());
        Step quiet = layer.tick(obs(b -> { b.waiting = true; return b.pending(0); }));
        assertNull(quiet.msg());
        assertEquals(Phase.BUILDING, layer.phase());
    }

    @Test
    void onlyEverythingBuiltEndsTheRun() {
        PrintLoop loop = building();
        for (int i = 0; i < PrintLoop.STUCK_TICKS + 1; i++) loop.tick(obs(b -> b));
        Step done = loop.tick(obs(b -> b.pending(0)));
        assertEquals(Act.FINISH, done.act());
    }

    @Test
    void jobsThatCannotProgressDoNotHoldTheEnd() {
        // pending 0 and the module reports nothing in progress (an axe-less strip job is not "waiting"): the run finishes
        PrintLoop loop = building();
        assertEquals(Act.FINISH, loop.tick(obs(b -> b.pending(0))).act());
        // while a job can progress the module reports waiting and the run stays
        PrintLoop held = building();
        for (int i = 0; i < 5; i++) assertEquals(Act.NONE, held.tick(obs(b -> { b.waiting = true; return b.pending(0); })).act());
        assertFalse(held.ended());
    }

    @Test
    void whenTheLayerIsDoneItPausesAndResumesWhenTheLayersChange() {
        PrintLoop loop = building();
        String hold = "Layer done: move Litematica's render layer to continue (40 block(s) outside it).";
        Step s = loop.tick(obs(b -> { b.layerHold = hold; return b.pending(0); }));
        assertFalse(s.ends());
        assertEquals(hold, s.msg());
        assertEquals(Phase.PAUSED, loop.phase());
        for (int i = 0; i < PrintLoop.STUCK_TICKS * 2; i++) assertFalse(loop.tick(obs(b -> { b.layerHold = hold; return b.pending(0); })).ends());
        // the layer moved: blocks inside the new range are placeable again
        assertEquals(Act.PLACE, loop.tick(obs(b -> b.place())).act());
        assertEquals(Phase.BUILDING, loop.phase());
    }

    @Test
    void theLayerPauseIsNotUsedWhileBlocksRemainInTheRange() {
        PrintLoop loop = building();
        assertEquals(Act.PLACE, loop.tick(obs(b -> { b.layerHold = "x"; return b.place(); })).act());
    }

    @Test
    void verifyIsDueAfterFourTicks() {
        PrintLoop loop = building();
        loop.placed(42);
        for (int i = 0; i < PrintLoop.VERIFY_TICKS - 1; i++) {
            loop.tick(obs(B::place));
            assertTrue(loop.due().isEmpty());
        }
        loop.tick(obs(B::place));
        assertEquals(List.of(42L), loop.due());
        // asked once
        assertTrue(loop.due().isEmpty());
        assertEquals(Verdict.OK, loop.verdict(42, true));
        assertEquals(0, loop.attemptsOf(42));
    }

    @Test
    void verifyRetriesTwiceThenFails() {
        PrintLoop loop = building();
        loop.placed(7);
        for (int round = 1; round <= 3; round++) {
            for (int i = 0; i < PrintLoop.VERIFY_TICKS; i++) loop.tick(obs(B::place));
            assertEquals(List.of(7L), loop.due(), "round " + round);
            Verdict v = loop.verdict(7, false);
            if (round < 3) {
                assertEquals(Verdict.RETRY, v, "round " + round);
                loop.placed(7); // the module places it again
                assertEquals(round + 1, loop.attemptsOf(7));
            }
            else {
                assertEquals(Verdict.FAILED, v);
                assertEquals(0, loop.attemptsOf(7));
            }
        }
        assertEquals(0, loop.verifying());
    }

    @Test
    void aRetryThatWorksIsOk() {
        PrintLoop loop = building();
        loop.placed(3);
        for (int i = 0; i < PrintLoop.VERIFY_TICKS; i++) loop.tick(obs(B::place));
        loop.due();
        assertEquals(Verdict.RETRY, loop.verdict(3, false));
        loop.placed(3);
        for (int i = 0; i < PrintLoop.VERIFY_TICKS; i++) loop.tick(obs(B::place));
        assertEquals(List.of(3L), loop.due());
        assertEquals(Verdict.OK, loop.verdict(3, true));
    }

    @Test
    void forgetDropsACell() {
        PrintLoop loop = building();
        loop.placed(3);
        loop.forget(3);
        assertEquals(0, loop.verifying());
        assertEquals(0, loop.attemptsOf(3));
    }

    @Test
    void aSearchThatIsNotFinishedNeitherEndsTheRunNorCountsAsStuck() {
        PrintLoop loop = building();
        Obs searching = obs(b -> { b.waiting = true; return b; });
        for (int i = 0; i < PrintLoop.STUCK_TICKS * 3; i++) assertEquals(Act.NONE, loop.tick(searching).act());
        assertFalse(loop.ended());
        // the search ends without finding anything: the stuck count starts only now
        Step last = null;
        for (int i = 0; i < PrintLoop.STUCK_TICKS + 1; i++) last = loop.tick(obs(b -> b));
        assertEquals(Phase.PAUSED, loop.phase());
        assertFalse(loop.ended());
    }

    @Test
    void farTargetsAreWalkedToForever() {
        PrintLoop loop = building();
        for (int i = 0; i < 500; i++) assertEquals(Act.WALK, loop.tick(obs(B::walk)).act());
        assertFalse(loop.ended());
    }

    @Test
    void finishWaitsForNoteClicksStillToSend() {
        PrintLoop loop = building();
        Obs notes = obs(b -> { b.pending(0).waiting = true; return b; });
        for (int i = 0; i < 5; i++) assertEquals(Act.NONE, loop.tick(notes).act());
        assertFalse(loop.ended());
        assertEquals(Act.FINISH, loop.tick(obs(b -> b.pending(0))).act());
    }

    @Test
    void sessionActiveLeavesTheWorldAlone() {
        PrintLoop loop = building();
        Obs session = obs(b -> { b.sessionActive = true; b.place().lack(); return b; });
        assertEquals(Act.STOP_WALK, loop.tick(session).act());
        assertEquals(Act.NONE, loop.tick(session).act());
        assertEquals(Phase.RESUPPLYING, loop.phase());
    }

    @Test
    void targetBitsTrackCellsAcrossSectionsAndNegatives() {
        TargetBits t = new TargetBits();
        assertTrue(t.add(-1, -64, 17));
        assertFalse(t.add(-1, -64, 17));
        assertTrue(t.add(300000, 319, -30000000));
        assertEquals(2, t.size());
        assertTrue(t.contains(-1, -64, 17));
        assertFalse(t.contains(0, -64, 17));
        assertTrue(t.contains(300000, 319, -30000000));
        assertTrue(t.remove(-1, -64, 17));
        assertFalse(t.remove(-1, -64, 17));
        assertFalse(t.contains(-1, -64, 17));
        assertEquals(1, t.size());
        t.clear();
        assertEquals(0, t.size());
        assertFalse(t.contains(300000, 319, -30000000));
    }

    @Test
    void outOfMaterialsPausesAndNeverEndsWhileTheSettingIsOff() {
        PrintLoop loop = building();
        Step s = loop.tick(obs(b -> b.lack().noSupply()));
        assertEquals(Phase.PAUSED, loop.phase());
        assertFalse(s.ends());
        for (int i = 0; i < PrintLoop.STUCK_TICKS * 2; i++) assertFalse(loop.tick(obs(b -> b.lack().noSupply())).ends());
        // and goes on by itself when the item turns up
        assertEquals(Act.PLACE, loop.tick(obs(B::place)).act());
        assertEquals(Phase.BUILDING, loop.phase());
    }

    @Test
    void outOfMaterialsEndsTheRunWhenTheSettingIsOnAndNothingCanSupplyIt() {
        PrintLoop loop = building();
        Step s = loop.tick(obs(b -> { b.disableWhenOut = true; return b.lack().noSupply(); }));
        assertEquals(Act.ABORT, s.act());
        assertTrue(s.msg().contains("disable-when-out-of-materials"));
        assertTrue(loop.ended());
    }

    @Test
    void withTheSettingOnOtherBlocksAreStillPlacedFirstAndASourceIsStillTried() {
        PrintLoop loop = building();
        // a block with its item is ready: the starved ones are skipped, nothing ends
        assertEquals(Act.PLACE, loop.tick(obs(b -> { b.disableWhenOut = true; return b.place().lack().noSupply(); })).act());
        // a source is set up: fetch from it
        assertEquals(Act.RESUPPLY, loop.tick(obs(b -> { b.disableWhenOut = true; return b.lack(); })).act());
    }

    @Test
    void withTheSettingOnAStashOrderIsStillWaitedFor() {
        PrintLoop loop = building();
        Step s = loop.tick(obs(b -> { b.disableWhenOut = true; return b.lack().noSupply().order("the order from stash Main"); }));
        assertEquals(Phase.PAUSED, loop.phase());
        assertFalse(s.ends());
        assertTrue(s.msg().contains("the order from stash Main"));
    }

    @Test
    void aFailedFetchEndsTheRunOnlyWhenTheSettingIsOnAndNoOrderIsOpen() {
        PrintLoop on = building();
        assertEquals(Act.RESUPPLY, on.tick(obs(b -> { b.disableWhenOut = true; return b.lack(); })).act());
        assertEquals(Act.ABORT, on.tick(obs(b -> { b.disableWhenOut = true; b.lacking = true; b.resupplyFailed = "no source holds stone."; return b; })).act());

        PrintLoop ordered = building();
        ordered.tick(obs(b -> { b.disableWhenOut = true; return b.lack(); }));
        Step s = ordered.tick(obs(b -> { b.disableWhenOut = true; b.lacking = true; b.resupplyFailed = "no source holds stone."; b.orderWait = "the order from stash Main"; return b; }));
        assertFalse(s.ends());
        assertEquals(Phase.PAUSED, ordered.phase());
    }
}

package com.porter.qwerte.tweaks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.tweaks.SmartPlaceFlow.Act;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Config;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Kind;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Level;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Mining;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Move;
import com.porter.qwerte.tweaks.SmartPlaceFlow.NoSilk;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Obs;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Plan;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Pre;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Step;
import org.junit.jupiter.api.Test;

class SmartPlaceFlowTest {
    /** The world as the module would report it, one field at a time. */
    private static class W {
        boolean worldChanged, cancelled, menuOpen, ownedOpen, expectedOpen, cursorEmpty = true, blockOurs, blockClear,
            freeSlot = true, inReach = true, hasTool = true, dropNearby, dropInRange, canWalk = true;
        int itemCount = 5;

        Obs obs() {
            return new Obs(worldChanged, cancelled, menuOpen, ownedOpen, expectedOpen, cursorEmpty, blockOurs, blockClear,
                itemCount, freeSlot, inReach, hasTool, dropNearby, dropInRange, canWalk);
        }
    }

    private static SmartPlaceFlow flow(Mining mining, int delay, boolean walk) {
        return new SmartPlaceFlow(new Config(mining, delay, 20 * 20, walk, "shulker box", "1, 64, 2"));
    }

    private static Step tick(SmartPlaceFlow f, W w) {
        return f.tick(w.obs());
    }

    /** Ticks until the flow does something, at most 200 times. */
    private static Step until(SmartPlaceFlow f, W w, Act act) {
        for (int i = 0; i < 200; i++) {
            Step s = tick(f, w);
            if (s.act() == act) return s;
            if (s.ends() && act != s.act()) throw new AssertionError("ended with " + s + " while waiting for " + act);
        }
        throw new AssertionError("never saw " + act + " in stage " + f.stageName());
    }

    /** Drives the flow to the point where it is mining, with the screen closed. */
    private static W placedAndClosed(SmartPlaceFlow f) {
        W w = new W();
        w.menuOpen = true;
        assertEquals(Act.CLOSE_SCREEN, tick(f, w).act());
        w.menuOpen = false;
        until(f, w, Act.PLACE);
        w.blockOurs = true;
        until(f, w, Act.OPEN);
        w.menuOpen = true;
        w.expectedOpen = true;
        w.ownedOpen = true;
        until(f, w, Act.RESTORE);
        w.menuOpen = false;
        w.expectedOpen = false;
        w.ownedOpen = false;
        return w;
    }

    // Plan

    private static Pre pre(Kind kind, NoSilk noSilk, boolean silk, boolean pick, boolean inHotbar, boolean fromInv, int empty, int swap) {
        return new Pre(kind, noSilk, true, null, silk, pick, inHotbar, fromInv, empty, swap);
    }

    @Test
    void noSpotMeansNoAction() {
        Plan p = SmartPlaceFlow.plan(new Pre(Kind.SHULKER, NoSilk.REFUSE, false, "nothing within reach", false, false, true, true, 0, 0));
        assertFalse(p.ok());
        assertEquals(Move.REFUSE, p.move());
        assertTrue(p.error().contains("nothing within reach"));
    }

    @Test
    void echestRefuseWithoutSilkFailsBeforeAnyMove() {
        Plan p = SmartPlaceFlow.plan(pre(Kind.ECHEST, NoSilk.REFUSE, false, true, false, true, 3, 4));
        assertFalse(p.ok());
        assertEquals(Move.REFUSE, p.move());
        assertTrue(p.error().contains("silk touch"));
    }

    @Test
    void echestWithSilkMinesWithSilkWhateverTheMode() {
        for (NoSilk mode : NoSilk.values()) {
            Plan p = SmartPlaceFlow.plan(pre(Kind.ECHEST, mode, true, true, true, true, -1, -1));
            assertTrue(p.ok());
            assertEquals(Mining.SILK, p.mining());
            assertNull(p.warning());
        }
    }

    @Test
    void placeAndLeaveAndMineAnywayWithoutSilk() {
        Plan leave = SmartPlaceFlow.plan(pre(Kind.ECHEST, NoSilk.PLACE_AND_LEAVE, false, true, true, true, -1, -1));
        assertTrue(leave.ok());
        assertEquals(Mining.LEAVE, leave.mining());

        Plan lossy = SmartPlaceFlow.plan(pre(Kind.ECHEST, NoSilk.MINE_ANYWAY, false, true, true, true, -1, -1));
        assertEquals(Mining.LOSSY, lossy.mining());
        assertTrue(lossy.warning().contains("obsidian"));

        Plan noPick = SmartPlaceFlow.plan(pre(Kind.ECHEST, NoSilk.MINE_ANYWAY, false, false, true, true, -1, -1));
        assertEquals(Mining.LEAVE, noPick.mining());
        assertTrue(noPick.warning().contains("No pickaxe"));
    }

    @Test
    void shulkerNeedsNoSilk() {
        Plan p = SmartPlaceFlow.plan(pre(Kind.SHULKER, NoSilk.REFUSE, false, false, true, true, -1, -1));
        assertTrue(p.ok());
        assertEquals(Mining.NORMAL, p.mining());
    }

    @Test
    void hotbarChoicePrefersWhatIsAlreadyThereThenEmptyThenSwap() {
        assertEquals(Move.NONE, SmartPlaceFlow.plan(pre(Kind.SHULKER, NoSilk.REFUSE, false, false, true, true, 2, 3)).move());

        Plan empty = SmartPlaceFlow.plan(pre(Kind.SHULKER, NoSilk.REFUSE, false, false, false, true, 2, 3));
        assertEquals(Move.EMPTY, empty.move());
        assertEquals(2, empty.hotbar());

        Plan swap = SmartPlaceFlow.plan(pre(Kind.SHULKER, NoSilk.REFUSE, false, false, false, true, -1, 3));
        assertEquals(Move.SWAP, swap.move());
        assertEquals(3, swap.hotbar());
    }

    @Test
    void aContainerSourceWithAFullHotbarIsRefused() {
        Plan p = SmartPlaceFlow.plan(pre(Kind.SHULKER, NoSilk.REFUSE, false, false, false, false, -1, 3));
        assertFalse(p.ok());
        assertEquals(Move.REFUSE, p.move());
        assertTrue(p.error().contains("container"));
    }

    @Test
    void aContainerSourceMayUseAnEmptyHotbarSlot() {
        Plan p = SmartPlaceFlow.plan(pre(Kind.SHULKER, NoSilk.REFUSE, false, false, false, false, 1, -1));
        assertTrue(p.ok());
        assertEquals(Move.EMPTY, p.move());
    }

    @Test
    void aFullHotbarOfProtectedItemsIsRefused() {
        assertFalse(SmartPlaceFlow.plan(pre(Kind.SHULKER, NoSilk.REFUSE, false, false, false, true, -1, -1)).ok());
    }

    // Flow

    @Test
    void happyPath() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, true);
        W w = placedAndClosed(f);
        assertTrue(f.placed());

        Step mine = until(f, w, Act.MINE);
        assertEquals(Act.MINE, mine.act());

        // The block is gone for 10 ticks, then the drop is picked up
        w.blockOurs = false;
        w.blockClear = true;
        w.dropNearby = true;
        w.dropInRange = true;
        for (int i = 0; i < SmartPlaceFlow.CLEAR_TICKS + 2; i++) tick(f, w);
        w.itemCount = 6;
        Step end = tick(f, w);
        assertEquals(Act.FINISH, end.act());
        assertTrue(f.done());
        assertFalse(f.placed());
    }

    @Test
    void theRestoreWaitsForAnEmptyCursor() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, false);
        W w = new W();
        until(f, w, Act.PLACE);
        w.blockOurs = true;
        until(f, w, Act.OPEN);
        w.menuOpen = w.expectedOpen = w.ownedOpen = true;
        w.cursorEmpty = false;
        for (int i = 0; i < 10; i++) assertEquals(Act.NONE, tick(f, w).act());
        w.cursorEmpty = true;
        assertEquals(Act.RESTORE, until(f, w, Act.RESTORE).act());
    }

    @Test
    void aScreenClosedBeforeTheRestoreStillGetsOne() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, false);
        W w = new W();
        until(f, w, Act.PLACE);
        w.blockOurs = true;
        until(f, w, Act.OPEN);
        w.menuOpen = w.expectedOpen = w.ownedOpen = true;
        w.cursorEmpty = false;
        tick(f, w);
        w.menuOpen = w.expectedOpen = w.ownedOpen = false;
        assertEquals(Act.RESTORE, tick(f, w).act());
    }

    @Test
    void oneActionPerTickAndTheDelayIsKept() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 3, false);
        W w = new W();
        // Entering the place stage waits the delay before the first click
        int ticks = 0;
        Step s;
        do {
            s = tick(f, w);
            ticks++;
        } while (s.act() != Act.PLACE && ticks < 20);
        assertEquals(Act.PLACE, s.act());
        assertTrue(ticks >= 4, "placed after " + ticks + " ticks");
        // It then waits before retrying
        for (int i = 0; i < SmartPlaceFlow.RETRY - 1; i++) assertEquals(Act.NONE, tick(f, w).act());
        assertEquals(Act.PLACE, tick(f, w).act());
    }

    @Test
    void placingRetriesThenGivesUpWithNothingLost() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, false);
        W w = new W();
        int places = 0;
        Step s = null;
        for (int i = 0; i < SmartPlaceFlow.PLACE_TIMEOUT + 10; i++) {
            s = tick(f, w);
            if (s.act() == Act.PLACE) places++;
            if (s.ends()) break;
        }
        assertEquals(Act.ABORT, s.act());
        assertEquals(Level.ERROR, s.level());
        assertTrue(places >= 2);
        assertFalse(f.placed());
    }

    @Test
    void anOpenThatNeverWorksMinesItAnyway() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, false);
        W w = new W();
        until(f, w, Act.PLACE);
        w.blockOurs = true;
        until(f, w, Act.OPEN);
        Step warn = null;
        for (int i = 0; i < SmartPlaceFlow.OPEN_TIMEOUT + 5 && warn == null; i++) {
            Step s = tick(f, w);
            if (s.level() == Level.WARN) warn = s;
        }
        assertTrue(warn.msg().contains("Could not open"));
        assertEquals(Act.MINE, until(f, w, Act.MINE).act());
    }

    @Test
    void placeAndLeaveHasNoMineStep() {
        SmartPlaceFlow f = flow(Mining.LEAVE, 0, true);
        W w = new W();
        until(f, w, Act.PLACE);
        w.blockOurs = true;
        until(f, w, Act.OPEN);
        w.menuOpen = w.expectedOpen = w.ownedOpen = true;
        until(f, w, Act.RESTORE);
        w.menuOpen = w.expectedOpen = w.ownedOpen = false;
        Step end = null;
        for (int i = 0; i < 50 && end == null; i++) {
            Step s = tick(f, w);
            assertTrue(s.act() != Act.MINE && s.act() != Act.WALK_REACH);
            if (s.ends()) end = s;
        }
        assertEquals(Act.FINISH, end.act());
        assertTrue(end.msg().contains("1, 64, 2"));
    }

    @Test
    void lossyMiningEndsWithAWarningAndNoCollect() {
        SmartPlaceFlow f = flow(Mining.LOSSY, 0, false);
        W w = placedAndClosed(f);
        until(f, w, Act.MINE);
        w.blockOurs = false;
        w.blockClear = true;
        Step end = null;
        for (int i = 0; i < 20 && end == null; i++) {
            Step s = tick(f, w);
            if (s.ends()) end = s;
        }
        assertEquals(Act.FINISH, end.act());
        assertEquals(Level.WARN, end.level());
        assertTrue(end.msg().contains("obsidian"));
    }

    @Test
    void aFullInventoryWaitsWarnsThenLeavesAnOrphan() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, false);
        W w = placedAndClosed(f);
        w.freeSlot = false;
        int warnings = 0;
        Step end = null;
        for (int i = 0; i < SmartPlaceFlow.FULL_LIMIT + 5 && end == null; i++) {
            Step s = tick(f, w);
            assertTrue(s.act() != Act.MINE, "must not mine with no room");
            if (s.level() == Level.WARN && s.act() == Act.NONE) warnings++;
            if (s.ends()) end = s;
        }
        assertEquals(Act.ORPHAN, end.act());
        assertTrue(warnings >= 25, "warned " + warnings + " times");
        assertTrue(end.msg().contains("1, 64, 2"));
    }

    @Test
    void aFullInventoryThatFreesUpGoesOnMining() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, false);
        W w = placedAndClosed(f);
        w.freeSlot = false;
        for (int i = 0; i < 50; i++) tick(f, w);
        w.freeSlot = true;
        assertEquals(Act.MINE, until(f, w, Act.MINE).act());
    }

    @Test
    void aBlockBrokenBySomeoneElseWhileOpenIsCollected() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, true);
        W w = new W();
        until(f, w, Act.PLACE);
        w.blockOurs = true;
        until(f, w, Act.OPEN);
        w.menuOpen = w.expectedOpen = w.ownedOpen = true;
        until(f, w, Act.RESTORE);

        w.blockOurs = false;
        w.blockClear = true;
        Step s = tick(f, w);
        assertEquals(Level.WARN, s.level());
        assertEquals(Act.NONE, s.act());

        // The drop turns up and is picked up
        w.dropNearby = true;
        w.dropInRange = true;
        w.itemCount = 6;
        Step end = tick(f, w);
        assertEquals(Act.FINISH, end.act());
    }

    @Test
    void noDropWithin40TicksWarnsWithTheCoordinates() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, true);
        W w = placedAndClosed(f);
        w.blockOurs = false;
        w.blockClear = true;
        Step end = null;
        for (int i = 0; i < 200 && end == null; i++) {
            Step s = tick(f, w);
            if (s.ends()) end = s;
        }
        assertEquals(Act.ABORT, end.act());
        assertEquals(Level.WARN, end.level());
        assertTrue(end.msg().contains("1, 64, 2"));
    }

    @Test
    void aReplacedBlockIsMinedAgainUpToThreeTimes() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, false);
        W w = placedAndClosed(f);
        int mined = 0;
        Step end = null;
        for (int round = 0; round < 6 && end == null; round++) {
            w.blockOurs = true;
            w.blockClear = false;
            until(f, w, Act.MINE);
            mined++;
            w.blockOurs = false;
            w.blockClear = true;
            for (int i = 0; i < SmartPlaceFlow.CLEAR_TICKS + 1; i++) tick(f, w);
            // Put back by someone else
            w.blockOurs = true;
            w.blockClear = false;
            Step s = tick(f, w);
            if (s.ends()) end = s;
        }
        assertEquals(1 + SmartPlaceFlow.MAX_REMINES, mined);
        assertEquals(Act.ORPHAN, end.act());
    }

    @Test
    void aDimensionChangeAfterPlacingLeavesAnOrphan() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, true);
        W w = placedAndClosed(f);
        w.worldChanged = true;
        Step s = tick(f, w);
        assertEquals(Act.ORPHAN, s.act());
        assertTrue(s.msg().contains("1, 64, 2"));
        assertTrue(f.done());
        assertEquals(Act.NONE, tick(f, new W()).act());
    }

    @Test
    void aDisconnectBeforePlacingLosesNothing() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, true);
        W w = new W();
        tick(f, w);
        w.worldChanged = true;
        assertEquals(Act.ABORT, tick(f, w).act());
    }

    @Test
    void cancelWhileWalkingStopsAndRemembersTheItem() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, true);
        W w = placedAndClosed(f);
        w.inReach = false;
        assertEquals(Act.WALK_REACH, until(f, w, Act.WALK_REACH).act());
        w.cancelled = true;
        Step s = tick(f, w);
        assertEquals(Act.ORPHAN, s.act());
        assertTrue(s.msg().contains("despawn after 5 minutes"));
        assertTrue(f.done());
        // Nothing more is asked of the module
        w.cancelled = false;
        assertEquals(Act.NONE, tick(f, w).act());
    }

    @Test
    void cancelBeforePlacingJustAborts() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, true);
        W w = new W();
        w.cancelled = true;
        assertEquals(Act.ABORT, tick(f, w).act());
    }

    @Test
    void aWalkBackIntoReachIsStoppedBeforeMining() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, true);
        W w = placedAndClosed(f);
        w.inReach = false;
        until(f, w, Act.WALK_REACH);
        w.inReach = true;
        assertEquals(Act.STOP_WALK, tick(f, w).act());
        assertEquals(Act.MINE, until(f, w, Act.MINE).act());
    }

    @Test
    void outOfReachWithoutBaritoneLeavesAnOrphan() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, false);
        W w = placedAndClosed(f);
        w.inReach = false;
        Step end = null;
        for (int i = 0; i < 20 && end == null; i++) {
            Step s = tick(f, w);
            if (s.ends()) end = s;
        }
        assertEquals(Act.ORPHAN, end.act());
    }

    @Test
    void aDropOutOfReachIsWalkedToAndRepathedEvery60Ticks() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, true);
        W w = placedAndClosed(f);
        until(f, w, Act.MINE);
        w.blockOurs = false;
        w.blockClear = true;
        w.dropNearby = true;
        w.dropInRange = false;

        int walks = 0;
        int firstWalk = -1;
        for (int i = 0; i < 200; i++) {
            Step s = tick(f, w);
            if (s.act() == Act.WALK_DROP) {
                walks++;
                if (firstWalk < 0) firstWalk = i;
            }
            if (s.ends()) break;
        }
        assertTrue(walks >= 3, "walks " + walks);
        assertTrue(firstWalk >= SmartPlaceFlow.CLEAR_TICKS, "walk began at " + firstWalk);
    }

    @Test
    void aCollectTimeoutWarnsAndLeavesAnOrphan() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, false);
        W w = placedAndClosed(f);
        until(f, w, Act.MINE);
        w.blockOurs = false;
        w.blockClear = true;
        w.dropNearby = true;
        w.dropInRange = false;
        Step end = null;
        for (int i = 0; i < 20 * 20 + 100 && end == null; i++) {
            Step s = tick(f, w);
            if (s.ends()) end = s;
        }
        assertEquals(Act.ORPHAN, end.act());
        assertEquals(Level.WARN, end.level());
        assertTrue(end.msg().contains("1, 64, 2"));
    }

    @Test
    void aMissingToolLeavesTheBlockAndSaysSo() {
        SmartPlaceFlow f = flow(Mining.SILK, 0, false);
        W w = placedAndClosed(f);
        w.hasTool = false;
        Step end = null;
        for (int i = 0; i < 10 && end == null; i++) {
            Step s = tick(f, w);
            if (s.ends()) end = s;
        }
        assertEquals(Act.ORPHAN, end.act());
    }

    @Test
    void aBlockThatWillNotBreakGivesUp() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, false);
        W w = placedAndClosed(f);
        Step end = null;
        int mines = 0;
        for (int i = 0; i < SmartPlaceFlow.BREAK_TIMEOUT + 10 && end == null; i++) {
            Step s = tick(f, w);
            if (s.act() == Act.MINE) mines++;
            if (s.ends()) end = s;
        }
        assertEquals(Act.ORPHAN, end.act());
        assertTrue(mines > 100);
    }

    @Test
    void outOfReachWithBaritoneBusyWarnsThenLeavesAnOrphan() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, true);
        W w = placedAndClosed(f);
        w.inReach = false;
        w.canWalk = false;
        boolean warned = false;
        Step end = null;
        for (int i = 0; i < 20 * 20 + 50 && end == null; i++) {
            Step s = tick(f, w);
            assertTrue(s.act() != Act.WALK_REACH);
            if (s.level() == Level.WARN && s.act() == Act.NONE) warned = true;
            if (s.ends()) end = s;
        }
        assertTrue(warned);
        assertEquals(Act.ORPHAN, end.act());
        assertTrue(end.msg().contains("1, 64, 2"));
    }

    @Test
    void theCollectBaselineIsTakenAtTheBreakNotAtThePlacement() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, false);
        W w = placedAndClosed(f);
        // Something else was picked up between placing and breaking
        w.itemCount = 9;
        until(f, w, Act.MINE);
        w.blockOurs = false;
        w.blockClear = true;
        w.dropNearby = true;
        w.dropInRange = true;
        Step end = null;
        for (int i = 0; i < SmartPlaceFlow.CLEAR_TICKS + 5 && end == null; i++) {
            Step s = tick(f, w);
            if (s.ends()) end = s;
        }
        assertNull(end, "an earlier gain must not count as the pickup");
        w.itemCount = 10;
        assertEquals(Act.FINISH, tick(f, w).act());
    }

    @Test
    void aPickupDuringTheClearWaitStillCounts() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, false);
        W w = placedAndClosed(f);
        until(f, w, Act.MINE);
        w.blockOurs = false;
        w.blockClear = true;
        tick(f, w);
        w.itemCount = 6;
        Step end = null;
        for (int i = 0; i < SmartPlaceFlow.CLEAR_TICKS + 3 && end == null; i++) {
            Step s = tick(f, w);
            if (s.ends()) end = s;
        }
        assertEquals(Act.FINISH, end.act());
    }

    @Test
    void aPlaceTimeoutIsFlaggedSoTheModuleCanWatchForALateBlock() {
        SmartPlaceFlow f = flow(Mining.NORMAL, 0, false);
        W w = new W();
        Step s = null;
        for (int i = 0; i < SmartPlaceFlow.PLACE_TIMEOUT + 10 && (s == null || !s.ends()); i++) s = tick(f, w);
        assertEquals(Act.ABORT, s.act());
        assertTrue(f.placeTimedOut());
    }

    @Test
    void onlyTheMenuOurOpenClickMadeIsOwned() {
        // Started from inside a shulker screen: that menu is the start menu and never ours
        assertFalse(SmartPlaceFlow.ownsMenu(false, false, true, true));
        assertFalse(SmartPlaceFlow.ownsMenu(false, true, true, true));
        // Before the open click, any menu is not ours
        assertFalse(SmartPlaceFlow.ownsMenu(false, false, true, false));
        // After the click, the new menu of the right type is
        assertTrue(SmartPlaceFlow.ownsMenu(false, true, true, false));
        // Once taken it is not replaced, and the wrong type is ignored
        assertFalse(SmartPlaceFlow.ownsMenu(true, true, true, false));
        assertFalse(SmartPlaceFlow.ownsMenu(false, true, false, false));
    }
}

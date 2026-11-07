package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.pearl.ElytraSwap.Action;
import com.porter.qwerte.pearl.ElytraSwap.Kind;
import com.porter.qwerte.pearl.ElytraSwap.Restore;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ElytraTest {
    private static Map<Integer, Kind> full() {
        Map<Integer, Kind> m = new HashMap<>();
        for (int id = 9; id <= 44; id++) m.put(id, Kind.OTHER);
        return m;
    }

    @Test
    void chestplateInTheInventoryIsSwappedIn() {
        Map<Integer, Kind> m = full();
        m.put(20, Kind.EMPTY);
        m.put(30, Kind.CHESTPLATE);
        ElytraSwap.Plan p = ElytraSwap.plan(Kind.ELYTRA, id -> m.get(id));
        assertEquals(Action.SWAP_CHESTPLATE, p.action());
        assertEquals(30, p.slot());
    }

    @Test
    void freeSlotWhenNoChestplate() {
        Map<Integer, Kind> m = full();
        m.put(40, Kind.EMPTY);
        m.put(12, Kind.EMPTY);
        ElytraSwap.Plan p = ElytraSwap.plan(Kind.ELYTRA, id -> m.get(id));
        assertEquals(Action.MOVE_FREE, p.action());
        assertEquals(12, p.slot(), "main inventory before the hotbar");
        m.put(12, Kind.OTHER);
        assertEquals(40, ElytraSwap.plan(Kind.ELYTRA, id -> m.get(id)).slot());
    }

    @Test
    void fullInventoryNoChestplateHasNoRoom() {
        Map<Integer, Kind> m = full();
        assertEquals(Action.NO_ROOM, ElytraSwap.plan(Kind.ELYTRA, id -> m.get(id)).action());
    }

    @Test
    void nothingToDoUnlessTheElytraIsWorn() {
        // An elytra in the offhand or the inventory, another chest piece, or nothing on the chest
        for (Kind chest : new Kind[] { Kind.EMPTY, Kind.CHESTPLATE, Kind.OTHER }) {
            assertEquals(Action.NONE, ElytraSwap.plan(chest, id -> Kind.EMPTY).action());
        }
    }

    @Test
    void restoreChecksBothSlots() {
        ElytraSwap.Swapped withPlate = new ElytraSwap.Swapped(30, true, "minecraft:diamond_chestplate");
        assertEquals(Restore.RESTORE, ElytraSwap.restore(withPlate, Kind.CHESTPLATE, "minecraft:diamond_chestplate", Kind.ELYTRA));
        assertEquals(Restore.CHANGED, ElytraSwap.restore(withPlate, Kind.CHESTPLATE, "minecraft:iron_chestplate", Kind.ELYTRA));
        assertEquals(Restore.CHANGED, ElytraSwap.restore(withPlate, Kind.EMPTY, "", Kind.ELYTRA));
        assertEquals(Restore.LOST, ElytraSwap.restore(withPlate, Kind.CHESTPLATE, "minecraft:diamond_chestplate", Kind.OTHER));
        assertEquals(Restore.DONE, ElytraSwap.restore(withPlate, Kind.ELYTRA, "minecraft:elytra", Kind.CHESTPLATE));

        ElytraSwap.Swapped moved = new ElytraSwap.Swapped(12, false, "");
        assertEquals(Restore.RESTORE, ElytraSwap.restore(moved, Kind.EMPTY, "", Kind.ELYTRA));
        assertEquals(Restore.CHANGED, ElytraSwap.restore(moved, Kind.CHESTPLATE, "x", Kind.ELYTRA), "something else was put on");
        assertEquals(Restore.LOST, ElytraSwap.restore(moved, Kind.EMPTY, "", Kind.EMPTY));
    }

    @Test
    void jumpIsHeldOnlyOnGroundOrInWater() {
        assertTrue(ElytraGuard.mayHoldJump(false, true, false));
        assertTrue(ElytraGuard.mayHoldJump(true, false, false));
        assertFalse(ElytraGuard.mayHoldJump(false, false, false), "airborne out of the water starts a glide");
        assertFalse(ElytraGuard.mayHoldJump(false, true, true), "already gliding");
        assertFalse(ElytraGuard.mayHoldJump(true, false, true));
    }

    @Test
    void modes() {
        assertTrue(ElytraMode.Auto.swaps() && ElytraMode.Auto.guards());
        assertTrue(ElytraMode.SwapOff.swaps() && !ElytraMode.SwapOff.guards());
        assertTrue(!ElytraMode.GuardOnly.swaps() && ElytraMode.GuardOnly.guards());
        assertTrue(!ElytraMode.Off.swaps() && !ElytraMode.Off.guards());
    }

    @Test
    void climbDiagnosis() {
        assertTrue(ClimbDiagnosis.reason(true, true, true, 99).startsWith("you glided"));
        assertTrue(ClimbDiagnosis.reason(false, true, true, 99).contains("bubble column"));
        assertTrue(ClimbDiagnosis.reason(false, false, true, 99).contains("trapdoor"));
        assertTrue(ClimbDiagnosis.reason(false, false, false, 40).contains("stuck"));
        assertEquals("out of time", ClimbDiagnosis.reason(false, false, false, 5));
    }
}

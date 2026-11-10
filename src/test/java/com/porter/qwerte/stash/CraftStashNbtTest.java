package com.porter.qwerte.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** The craft parts of the saved stash written and read back: the order table slot, the tables placed and the fills of the last hour, a lot's craft state and the ledger. */
class CraftStashNbtTest {
    @Test
    void theOrderTableSlotTheTableStampsAndTheFillsRoundTrip() {
        Stash a = new Stash("s", "2b2t.org");
        a.orderTablePos = new BlockPos(5, 64, -7);
        a.orderTableSource = "crafted";
        a.craftTables.add(new long[] {1_700_000_000_000L, 1});
        a.craftFills.add(new long[] {1_700_000_001_000L, 40});
        a.craftFills.add(new long[] {1_700_000_002_000L, 12});
        CompoundTag root = new CompoundTag();
        Stash.writeCraft(a, root);
        Stash b = new Stash("s", "2b2t.org");
        Stash.readCraft(b, root);
        assertEquals(new BlockPos(5, 64, -7), b.orderTablePos);
        assertEquals("crafted", b.orderTableSource);
        assertEquals(1, b.craftTables.size());
        assertEquals(1_700_000_000_000L, b.craftTables.get(0)[0]);
        assertEquals(2, b.craftFills.size());
        assertEquals(12, b.craftFills.get(1)[1]);
    }

    @Test
    void whileTheOrderSlotIsInTheSlotIsWhatIsSavedNotTheUsersTable() {
        Stash a = new Stash("s", "2b2t.org");
        a.craftingTablePos = new BlockPos(500, 64, 500);
        a.craftingTableSource = "set";
        a.useOrderSlot();
        a.craftingTablePos = new BlockPos(9, 64, 9);
        a.craftingTableSource = "placed";
        CompoundTag root = new CompoundTag();
        Stash.writeCraft(a, root);
        Stash b = new Stash("s", "2b2t.org");
        Stash.readCraft(b, root);
        assertEquals(new BlockPos(9, 64, 9), b.orderTablePos);
        assertEquals("placed", b.orderTableSource);
    }

    @Test
    void nothingSetWritesNothingAndHostileStampsAreLeftOut() {
        CompoundTag empty = new CompoundTag();
        Stash.writeCraft(new Stash("s", "x"), empty);
        assertTrue(empty.isEmpty());
        CompoundTag root = new CompoundTag();
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < com.porter.qwerte.craft.CraftFills.MAX_STAMPS + 50; i++) {
            CompoundTag e = new CompoundTag();
            e.putString("at", Integer.toString(i));
            e.putInt("n", 1);
            list.add(e);
        }
        CompoundTag bad = new CompoundTag();
        bad.putString("at", "not a number");
        bad.putInt("n", 1);
        list.add(bad);
        root.put("craftFills", list);
        Stash b = new Stash("s", "x");
        Stash.readCraft(b, root);
        assertEquals(com.porter.qwerte.craft.CraftFills.MAX_STAMPS, b.craftFills.size());
    }

    @Test
    void aLotsCraftStateAndTheLedgerRoundTripAndAHugeLedgerCountsAsMissing() {
        Stash.ActiveOrder.UnitPlan u = new Stash.ActiveOrder.UnitPlan();
        u.lotKeys.add("minecraft:dirt");
        u.lotKeys.add("minecraft:spruce_stairs");
        u.lotCraft.add(0);
        u.lotCraft.add(2);
        List<CompoundTag> lots = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            CompoundTag lot = new CompoundTag();
            Stash.writeLotCraft(u, i, lot);
            lots.add(lot);
        }
        assertTrue(lots.get(0).isEmpty(), "a plain lot writes nothing");
        Stash.ActiveOrder.UnitPlan back = new Stash.ActiveOrder.UnitPlan();
        for (CompoundTag lot : lots) Stash.readLotCraft(lot, back);
        assertEquals(List.of(0, 2), back.lotCraft);

        Stash.ActiveOrder a = new Stash.ActiveOrder();
        a.craftLedger = "{\"fills\":3}";
        CompoundTag order = new CompoundTag();
        Stash.writeLedger(a, order);
        Stash.ActiveOrder read = new Stash.ActiveOrder();
        Stash.readLedger(order, read);
        assertEquals("{\"fills\":3}", read.craftLedger);
        CompoundTag huge = new CompoundTag();
        huge.putString("craftLedger", "x".repeat(Stash.MAX_LEDGER_CHARS + 1));
        Stash.ActiveOrder none = new Stash.ActiveOrder();
        Stash.readLedger(huge, none);
        assertEquals("", none.craftLedger);
    }
}

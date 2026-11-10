package com.porter.qwerte.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.porter.qwerte.stash.Order;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.stash.StashIndex;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Round three of the review: the order-table slot beside the user's hand-set table, the codes, the sticky failure's scope, the charge split, plain-only and the caps. */
class CraftSideAndCapsTest {
    // The hand-set table out of the limits

    @Test
    void theUsersHandSetTableIsKeptAndOrdersGetASlotOfTheirOwnWhenItIsOutOfTheLimits() {
        assertEquals(CraftSide.Use.NONE, CraftSide.decide(false, "", false));
        assertEquals(CraftSide.Use.SHARED, CraftSide.decide(true, "set", false), "within the limits: orders share it");
        assertEquals(CraftSide.Use.SHARED, CraftSide.decide(true, "", false));
        assertEquals(CraftSide.Use.SLOT, CraftSide.decide(true, "set", true), "hand-set and too far: a slot of their own");
        assertEquals(CraftSide.Use.SLOT, CraftSide.decide(true, "", true));
        assertEquals(CraftSide.Use.FORGET, CraftSide.decide(true, "placed", true), "one the bot picked and out of bounds is forgotten");
        assertEquals(CraftSide.Use.FORGET, CraftSide.decide(true, "found", true));
        assertEquals(CraftSide.Use.FORGET, CraftSide.decide(true, "crafted", true));
        assertEquals(CraftSide.Use.SHARED, CraftSide.decide(true, "crafted", false));
    }

    @Test
    void theOrderSlotNeverOverwritesTheUsersTableAndKeepsWhatTheBotPutDown() {
        Stash st = new Stash("s", "2b2t.org");
        BlockPos mine = new BlockPos(500, 64, 500), placed = new BlockPos(5, 64, 5);
        st.craftingTablePos = mine;
        st.craftingTableSource = "set";
        // The order table stands in: nothing known yet
        st.useOrderSlot();
        assertTrue(st.orderSlotInUse());
        assertNull(st.craftingTablePos);
        assertEquals("", st.craftingTableSource);
        assertEquals(mine, st.tableSwap.persistedPos(st.craftingTablePos), "what is saved is the user's own");
        assertEquals("set", st.tableSwap.persistedSource(st.craftingTableSource));
        // The bot places a table: it lands in the live fields, which are the slot
        st.craftingTablePos = placed;
        st.craftingTableSource = "placed";
        assertEquals(mine, st.tableSwap.persistedPos(st.craftingTablePos));
        st.restoreTable();
        assertEquals(mine, st.craftingTablePos);
        assertEquals("set", st.craftingTableSource);
        assertEquals(placed, st.orderTablePos);
        assertEquals("placed", st.orderTableSource);
        assertFalse(st.orderSlotInUse());
        // The next order starts from the slot
        st.useOrderSlot();
        assertEquals(placed, st.craftingTablePos);
        assertEquals("placed", st.craftingTableSource);
        st.restoreTable();
        assertEquals(mine, st.craftingTablePos);
        // The website's table, if there is one, swaps in and out without touching the slot
        st.overrideTable(new BlockPos(1, 2, 3));
        assertEquals(mine, st.tableSwap.persistedPos(st.craftingTablePos));
        st.restoreTable();
        assertEquals(mine, st.craftingTablePos);
        assertEquals(placed, st.orderTablePos);
    }

    // The codes

    @Test
    void noMaterialIsTheCodeForNothingToMakeATableFromAndTooFarIsTheHandSetOne() {
        assertEquals("no_material", CraftAuto.NO_MATERIAL);
        assertTrue(CraftAuto.WHY.contains("no_material"));
        assertEquals(CraftAuto.NO_MATERIAL, CraftAuto.why(new CraftAuto.State(false, "unset", true, true, false, null)));
        assertEquals(CraftAuto.TOO_FAR, CraftAuto.why(new CraftAuto.State(false, "unset", true, true, false, null, true)), "the user's table is out of the limits and nothing else can be had");
        assertNull(CraftAuto.why(new CraftAuto.State(false, "unset", true, true, true, null, true)), "orders get a table of their own: the stash can still make one");
        assertEquals(CraftAuto.NO_AREA, CraftAuto.why(new CraftAuto.State(false, "unset", false, true, true, null)));
        assertEquals(CraftAuto.NO_DIMENSION, CraftAuto.why(new CraftAuto.State(false, "unset", true, false, true, null)));
        // A failure that travelled with its code is said as it is
        for (String code : new String[] {CraftAuto.NO_STAND, CraftAuto.PATH_FAILED, CraftAuto.NOT_LOADED}) {
            assertEquals(code, CraftAuto.why(new CraftAuto.State(false, "unset", true, true, true, code)));
        }
    }

    @Test
    void theHeartbeatIsSentWheneverCraftingAndCraftOrdersAreOnAndARecipeTableIsHeld() {
        for (int bits = 0; bits < 4; bits++) {
            boolean crafting = (bits & 1) != 0, recipes = (bits & 2) != 0;
            assertEquals(bits == 3, CraftCapability.sendOrders(crafting, recipes), "bits " + bits);
        }
        // With craft-orders off the object is still sent, with enabled false and the hashes (as the plugin does), not a null
        StashIndex.Craft off = CraftCapability.heartbeat(true, false, "a".repeat(40), "1.21.4", "auto", 2048, List.of(), "b".repeat(40), null, null);
        byte[] body = StashIndex.heartbeat(new StashIndex.Built("s", "t", "Bot", "ix", new byte[0]), null, null, -1, null, null, null, null, null, null, null, null, off);
        JsonObject o = com.google.gson.JsonParser.parseString(new String(body, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(o.getAsJsonObject("craftOrders").get("enabled").getAsBoolean());
        assertEquals("a".repeat(40), o.getAsJsonObject("craftOrders").get("recipes").getAsString());
        assertEquals("b".repeat(40), o.get("craftHash").getAsString());
        // Not sent at all: an explicit null
        assertNull(CraftCapability.heartbeat(false, false, null, null, null, 0, null, null, null, null).orders());
        // A claim carries the flag while a craft order is held, whatever the table is for the moment
        JsonObject held = new JsonObject();
        CraftCapability.claimFlag(held, false, true);
        assertEquals(1, held.get("craftOrders").getAsInt());
        JsonObject none = new JsonObject();
        CraftCapability.claimFlag(none, false, false);
        assertFalse(none.has("craftOrders"));
    }

    // The sticky failure

    @Test
    void aTableFailureIsRememberedOnlyOnTheOrderPathAndLapses() {
        AutoWhy w = new AutoWhy();
        w.failed(false, CraftAuto.NO_STAND, 1000);
        assertNull(w.current(1001), "a shulker craft or the chest expansion failing does not count");
        w.failed(true, CraftAuto.NO_STAND, 1000);
        assertEquals(CraftAuto.NO_STAND, w.current(1001));
        assertEquals(CraftAuto.NO_STAND, w.current(1000 + AutoWhy.STICKY_MS));
        assertNull(w.current(1000 + AutoWhy.STICKY_MS + 1));
        w.failed(true, null, 5);
        assertEquals(CraftAuto.PATH_FAILED, w.current(6));
        w.cleared();
        assertNull(w.current(7));
    }

    // The charge

    @Test
    void aMadeSurplusNeverOffsetsAGatheredCountAndATakenOverSurplusIsNeverCharged() {
        String planks = "minecraft:spruce_planks", logs = "minecraft:spruce_log";
        // The session gathered a stack of 64 planks into slot 3 and 5 logs into slot 4; it made 8 planks (slot 9, surplus) and the load (slot 10)
        List<CraftSplit.Slot> held = List.of(new CraftSplit.Slot(3, planks, 61), new CraftSplit.Slot(4, logs, 2), new CraftSplit.Slot(9, planks, 8),
            new CraftSplit.Slot(10, "minecraft:spruce_stairs", 8));
        CraftSplit.Sorted s = CraftSplit.sort("minecraft:spruce_stairs", held, Set.of(3, 4));
        assertEquals(10, s.lotSlot());
        assertEquals(8, s.lotCount());
        assertEquals(Map.of(3, planks, 4, logs), s.stock());
        assertEquals(Map.of(9, planks), s.made());
        // Charged: gathered less the stock still carried. The 8 made planks are not counted among what is carried of the gathered 64
        Map<String, Long> gathered = Map.of(planks, 64L, logs, 5L);
        Map<String, Long> back = Map.of(planks, 61L, logs, 2L);
        assertEquals(Map.of(planks, 3L, logs, 3L), CraftCharge.charge(gathered, 2, back));
        // A later chunk takes over the made surplus (not stock): nothing is charged for it when it is used up
        CraftSplit.Sorted next = CraftSplit.sort("minecraft:spruce_slab", List.of(new CraftSplit.Slot(9, planks, 2), new CraftSplit.Slot(11, "minecraft:spruce_slab", 6)), Set.of());
        assertEquals(11, next.lotSlot());
        assertEquals(Map.of(9, planks), next.made());
        assertTrue(next.stock().isEmpty());
        assertTrue(CraftCharge.charge(Map.of(), 1, Map.of()).isEmpty());
        // The same size: the made stack is the load
        CraftSplit.Sorted tie = CraftSplit.sort(planks, List.of(new CraftSplit.Slot(1, planks, 30), new CraftSplit.Slot(2, planks, 30)), Set.of(1));
        assertEquals(2, tie.lotSlot());
        assertFalse(tie.lotFromStock());
        assertEquals(Map.of(1, planks), tie.stock());
    }

    // Plain stacks

    @Test
    void aPlainOnlyRequestTakesNoNamedOrPatchedStack() {
        assertTrue(CraftPlain.allowed(true, true));
        assertFalse(CraftPlain.allowed(true, false));
        assertTrue(CraftPlain.allowed(false, false), "other requests are as they were");
        assertTrue(CraftPlain.allowed(false, true));
    }

    // The table caps

    @Test
    void atMostOneTablePerOrderAndThreeAnHourAndOnlyWhenPlaced() {
        CraftTableCap cap = new CraftTableCap();
        long t = System.currentTimeMillis() - 10_000;
        assertTrue(cap.allows("o-1", t), "planned is not placed");
        assertTrue(cap.allows("o-1", t));
        cap.placed("o-1", t);
        assertFalse(cap.allows("o-1", t + 1), "one per order");
        assertTrue(cap.allows("o-2", t + 1));
        cap.placed("o-2", t + 2);
        cap.placed("o-3", t + 3);
        assertFalse(cap.allows("o-4", t + 4), "three an hour");
        // Kept across a restart
        CraftTableCap back = new CraftTableCap();
        back.load(cap.export(t + 5));
        assertFalse(back.allows("o-5", t + 6));
        assertTrue(cap.allows("o-4", t + 3_600_001 + 3), "and the hour passes");
        assertEquals(1, CraftTableCap.PER_ORDER);
        assertEquals(3, CraftTableCap.PER_HOUR);
    }

    @Test
    void theLinesOwnItemGatheredAndMadeIsTheBiggestStackAndWhatItHoldsBeyondTheLoadGoesBack() {
        String planks = "minecraft:spruce_planks";
        // A load of 8 planks: 61 were gathered as itself (the take names planks) and 8 more were made (slot 9)
        CraftSplit.Sorted s = CraftSplit.sort(planks, List.of(new CraftSplit.Slot(3, planks, 61), new CraftSplit.Slot(9, planks, 8)), Set.of(3));
        assertEquals(3, s.lotSlot());
        assertEquals(61, s.lotCount());
        assertTrue(s.lotFromStock());
        assertEquals(Map.of(9, planks), s.made());
        long made = Math.min(8, s.lotCount());
        assertEquals(8, made, "the load is the stock the order took as itself, up to what is wanted");
        // Charged: 64 gathered, 53 of the stack beyond the load goes back, the 8 made are not counted against it
        Map<String, Long> back = new java.util.HashMap<>();
        back.merge(planks, s.lotCount() - made, Long::sum);
        assertEquals(Map.of(planks, 11L), CraftCharge.charge(Map.of(planks, 64L), 2, back));
        // A made stack merged onto a gathered one is still found: it is in a stock slot, not missed
        CraftSplit.Sorted merged = CraftSplit.sort(planks, List.of(new CraftSplit.Slot(3, planks, 70)), Set.of(3));
        assertEquals(70, merged.lotCount());
        assertTrue(merged.lotFromStock());
        assertEquals(-1, CraftSplit.sort(planks, List.of(new CraftSplit.Slot(1, "minecraft:stick", 4)), Set.of()).lotSlot());
    }

    @Test
    void theWebsiteTableIsNeverCopiedIntoTheOrderTableSlot() {
        Stash st = new Stash("s", "2b2t.org");
        BlockPos mine = new BlockPos(500, 64, 500), website = new BlockPos(1, 2, 3);
        st.craftingTablePos = mine;
        st.craftingTableSource = "set";
        st.useOrderSlot();
        // The website table is put in while the slot is in (the sorter lets go first; the stash is safe by itself too)
        st.overrideTable(website);
        st.restoreTable();
        assertEquals(mine, st.craftingTablePos);
        assertNull(st.orderTablePos, "the slot never held the website table");
        assertEquals("", st.orderTableSource);
    }

    @Test
    void anOldFormatSaveIsFoundByItsContent() {
        Set<String> craftOnly = Set.of("minecraft:spruce_stairs");
        assertTrue(CraftLots.oldFormatLot(Order.Lot.NONE, "minecraft:spruce_stairs", craftOnly, true));
        assertFalse(CraftLots.oldFormatLot(Order.Lot.NONE, "minecraft:spruce_stairs", craftOnly, false), "no craft ledger: nothing to compare with");
        assertFalse(CraftLots.oldFormatLot(Order.Lot.PENDING, "minecraft:spruce_stairs", craftOnly, true));
        assertFalse(CraftLots.oldFormatLot(Order.Lot.CRAFTED, "minecraft:spruce_stairs", craftOnly, true));
        assertFalse(CraftLots.oldFormatLot(Order.Lot.NONE, "minecraft:dirt", craftOnly, true), "an item an item line asks for");
    }

    @Test
    void theClaimFlagIsForTheHeldOrdersLedgerOnly() {
        assertEquals("o-1", CraftCapability.heldId("o-1", true, null));
        assertEquals("o-1", CraftCapability.heldId("o-1", true, "o-1"));
        assertNull(CraftCapability.heldId("o-1", true, "o-2"), "a ledger of another order");
        assertNull(CraftCapability.heldId("o-1", false, "o-1"));
        assertNull(CraftCapability.heldId(null, true, null));
    }
}

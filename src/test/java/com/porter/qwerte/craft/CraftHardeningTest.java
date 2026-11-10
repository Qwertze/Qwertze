package com.porter.qwerte.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.stash.Order;
import com.porter.qwerte.stash.StashIndex;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The review fixes of the craft runner: the saved ledger is checked, recipes are eligible, the circuit breaker, the hourly fills kept, and the auto table. */
class CraftHardeningTest {
    private static final Recipes R = TestRecipes.table();
    private static final String LOG = "minecraft:spruce_log", STAIRS = "minecraft:spruce_stairs", PLANKS = "minecraft:spruce_planks";

    private static CraftLedger ledger() {
        Order.Craft c = new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty(STAIRS, 8)), List.of(), List.of(new Order.Qty(LOG, 10)), List.of(), 0, false);
        return new CraftLedger(c, List.of(new Order.Line(Order.CRAFT, STAIRS, 8)), R, 100);
    }

    private static JsonObject saved(CraftLedger l) {
        l.madeChunk(STAIRS, 6, 5, Map.of(LOG, 3L));
        l.addShort(STAIRS, 2, CraftOrder.TIMEOUT);
        l.setSurplus(Map.of(PLANKS, 3L));
        return l.toSaved();
    }

    // The saved ledger is checked; a damaged one is refused and never replaced by a fresh budget

    @Test
    void aGoodLedgerIsRestoredAndTheNumbersAreClamped() {
        JsonObject o = saved(ledger());
        CraftLedger back = ledger();
        assertTrue(back.restore(o));
        assertEquals(6, back.made(STAIRS));
        assertEquals(7L, back.budget().get(LOG));
        // Fills past the cap, and made or short past what was asked, are cut
        JsonObject big = o.deepCopy();
        big.addProperty("fills", 9_999);
        big.getAsJsonObject("made").addProperty(STAIRS, 500);
        big.getAsJsonObject("short").addProperty(STAIRS, 500);
        CraftLedger clamped = ledger();
        assertTrue(clamped.restore(big));
        assertEquals(100, clamped.fills());
        assertEquals(8, clamped.made(STAIRS));
        assertNull(clamped.note(), "nothing is short once made is clamped to what was asked");
    }

    @Test
    void aDamagedLedgerIsRefusedWholeAndLeavesTheLedgerAsItWas() {
        List<java.util.function.Consumer<JsonObject>> breaks = new ArrayList<>();
        breaks.add(o -> o.addProperty("fills", -1));
        breaks.add(o -> o.getAsJsonObject("made").addProperty(STAIRS, -4));
        breaks.add(o -> o.getAsJsonObject("budget").addProperty("not an id", 4));
        breaks.add(o -> o.getAsJsonObject("used").addProperty("minecraft:spruce_log#ab", 4));
        breaks.add(o -> o.getAsJsonObject("made").addProperty("minecraft:diamond", 4));
        breaks.add(o -> o.getAsJsonObject("reasons").addProperty(STAIRS, "because"));
        breaks.add(o -> o.getAsJsonObject("reasons").addProperty("minecraft:diamond", CraftOrder.CAP));
        breaks.add(o -> o.remove("budget"));
        breaks.add(o -> o.addProperty("fills", "many"));
        breaks.add(o -> {
            JsonObject surplus = new JsonObject();
            for (int i = 0; i < 300; i++) surplus.addProperty("minecraft:item_" + i, 1);
            o.add("surplus", surplus);
        });
        for (var b : breaks) {
            JsonObject o = saved(ledger());
            b.accept(o);
            CraftLedger l = ledger();
            assertFalse(l.restore(o), o.toString());
            assertEquals(0, l.fills(), "left as it was");
            assertEquals(10L, l.budget().get(LOG), "the full budget is not handed on by a failed restore: the caller refuses the lines");
        }
        // The caller's answer to a refused record: every craft line short, nothing crafted
        CraftLedger refused = CraftLedger.refused(List.of(new Order.Line(Order.CRAFT, STAIRS, 8)), R, 100, CraftOrder.RECIPE_MISMATCH);
        assertTrue(refused.refusedAtStart());
        assertEquals("8 item(s) not crafted: recipe_mismatch", refused.note());
    }

    // Recipes the planner would not use cannot be named by an order

    @Test
    void aShulkerBoxRecipeOrARecolouringIsNotOneAnOrderMayName() {
        Order.CraftStep box = new Order.CraftStep("minecraft:shulker_box", "minecraft:shulker_box", 1, 1, List.of(new Order.Qty("minecraft:chest", 1)));
        Order.Craft c = new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty("minecraft:shulker_box", 1)), List.of(box),
            List.of(new Order.Qty("minecraft:chest", 1), new Order.Qty("minecraft:shulker_shell", 2)), List.of(), 1, false);
        String why = CraftOrder.check(c, R, 2048);
        assertNotNull(why);
        assertTrue(why.contains("not one a craft order may use"), why);
        Order.CraftStep dye = new Order.CraftStep("minecraft:dye_white_bed", "minecraft:white_bed", 1, 1, List.of(new Order.Qty("minecraft:red_bed", 1)));
        assertNotNull(CraftOrder.check(new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty("minecraft:white_bed", 1)), List.of(dye),
            List.of(new Order.Qty("minecraft:red_bed", 1)), List.of(), 1, false), R, 2048));
        // The sub-plan does not use such a recipe even when the steps name it
        CraftSub.Session s = new CraftSub.Session(R, java.util.Set.of("minecraft:shulker_box"), Map.of("minecraft:chest", 1L, "minecraft:shulker_shell", 2L),
            Map.of("minecraft:chest", 1L, "minecraft:shulker_shell", 2L), Map.of());
        assertFalse(s.plan("minecraft:shulker_box", 1).ok());
    }

    // The circuit breaker

    @Test
    void threeChunksInARowThatMadeNothingTripTheBreakerAndAnyMakingResetsIt() {
        CraftBreaker b = new CraftBreaker();
        assertFalse(b.record(0));
        assertFalse(b.record(0));
        assertTrue(b.record(0));
        assertFalse(b.record(0), "it starts again after tripping");
        assertFalse(b.record(5));
        assertFalse(b.record(0));
        assertFalse(b.record(0));
        assertFalse(b.record(7), "made something: the count starts again");
        assertFalse(b.record(0));
        b.reset();
        assertFalse(b.record(0));
        assertFalse(b.record(0));
        assertTrue(b.record(0));
    }

    // The hourly fills are kept across a restart

    @Test
    void theFillsOfTheLastHourAreExportedAndLoadedClamped() {
        long now = System.currentTimeMillis();
        CraftFills f = new CraftFills();
        f.add(now - 1_000, 100);
        f.add(now - 2_000, 40);
        List<long[]> out = f.export(now);
        assertEquals(2, out.size());
        CraftFills back = new CraftFills();
        back.load(out);
        assertEquals(140, back.lastHour(now));
        // Damaged or hostile stamps: negative, absurd, too old, from the future, wrong shape
        List<long[]> bad = new ArrayList<>();
        bad.add(new long[] {now - 1_000, -5});
        bad.add(new long[] {now - 1_000, 2_000_000_000L});
        bad.add(new long[] {now - 2 * 3_600_000L, 50});
        bad.add(new long[] {now + 86_400_000L, 10});
        bad.add(new long[] {1});
        bad.add(null);
        CraftFills odd = new CraftFills();
        odd.load(bad);
        assertEquals(10, odd.lastHour(now + 1000), "only the future one counts, as now");
        // Past the limit no more are read
        List<long[]> many = new ArrayList<>();
        for (int i = 0; i < CraftFills.MAX_STAMPS + 100; i++) many.add(new long[] {now, 1});
        CraftFills capped = new CraftFills();
        capped.load(many);
        assertEquals(CraftFills.MAX_STAMPS, capped.lastHour(now));
    }

    // The auto table

    private static CraftAuto.State state(boolean website, String status, boolean area, boolean dim, boolean obtainable, String why) {
        return new CraftAuto.State(website, status, area, dim, obtainable, why);
    }

    @Test
    void withNoWebsiteTableTheAutoPathIsAvailableWhenATableCanBeHadInAnAreaAndADimension() {
        CraftAuto.State ok = state(false, "unset", true, true, true, null);
        assertTrue(CraftAuto.autoAvailable(ok));
        assertEquals("auto", CraftAuto.status(ok));
        assertTrue(CraftAuto.usable(ok));
        assertTrue(CraftAuto.tableOk(ok));
        assertNull(CraftAuto.why(ok));
        // Each missing piece makes it unavailable, with a code
        assertEquals("unset", CraftAuto.status(state(false, "unset", false, true, true, null)));
        assertEquals(CraftAuto.NO_AREA, CraftAuto.why(state(false, "unset", false, true, true, null)));
        assertEquals(CraftAuto.NO_DIMENSION, CraftAuto.why(state(false, "unset", true, false, true, null)));
        assertEquals(CraftAuto.NO_MATERIAL, CraftAuto.why(state(false, "unset", true, true, false, null)), "no table, none in the stash, nothing to make one from");
        assertFalse(CraftAuto.tableOk(state(false, "unset", true, true, false, null)));
        // A try that failed is said, and the path is not offered until it clears
        CraftAuto.State failed = state(false, "unset", true, true, true, CraftAuto.NO_STAND);
        assertFalse(CraftAuto.autoAvailable(failed));
        assertEquals(CraftAuto.NO_STAND, CraftAuto.why(failed));
        assertEquals("unset", CraftAuto.status(failed));
        assertTrue(CraftAuto.WHY.contains(CraftAuto.PATH_FAILED) && CraftAuto.WHY.contains(CraftAuto.NOT_LOADED) && CraftAuto.WHY.contains(CraftAuto.TOO_FAR));
    }

    @Test
    void aWebsiteTableHasPriorityAndItsStateIsReportedAsItIs() {
        CraftAuto.State site = state(true, "ok", true, true, true, null);
        assertEquals("ok", CraftAuto.status(site));
        assertTrue(CraftAuto.usable(site));
        assertNull(CraftAuto.why(site));
        assertTrue(CraftAuto.usable(state(true, "unloaded", true, true, false, null)));
        // Broken: the state and the reason are the website table's, and the bot is still offered by the auto path when that is available
        CraftAuto.State far = state(true, "unreachable", true, true, true, null);
        assertEquals("unreachable", CraftAuto.status(far));
        assertFalse(CraftAuto.usable(far));
        assertEquals(CraftAuto.TOO_FAR, CraftAuto.why(far));
        assertFalse(CraftAuto.tableOk(far), "a broken website table is not replaced by the auto path (the plugin does the same)");
        assertEquals(CraftAuto.WRONG_DIMENSION, CraftAuto.why(state(true, "other_dimension", true, true, false, null)));
        assertEquals(CraftAuto.NOT_TABLE, CraftAuto.why(state(true, "not_table", true, true, false, null)));
        assertEquals(CraftAuto.NO_AREA, CraftAuto.why(state(true, "unreachable", false, true, true, null)));
        assertFalse(CraftAuto.tableOk(state(true, "not_table", true, true, false, null)));
    }

    @Test
    void onlyATableTheBotFoundPlacedOrCraftedIsReportedAsItsOwn() {
        assertEquals("found", CraftAuto.botSource("found"));
        assertEquals("placed", CraftAuto.botSource("placed"));
        assertEquals("crafted", CraftAuto.botSource("crafted"));
        assertNull(CraftAuto.botSource("set"));
        assertNull(CraftAuto.botSource(""));
        assertNull(CraftAuto.botSource(null));
    }

    @Test
    void theHeartbeatCarriesTheAutoFieldsExactly() {
        StashIndex.Built built = new StashIndex.Built("s", "t", "Bot", "ix", new byte[0]);
        StashIndex.Craft usable = CraftCapability.heartbeat(true, "a".repeat(40), "1.21.4", "auto", 2048, List.of(), null, null, new StashIndex.BotTable("1,2,3", "placed"));
        JsonObject co = JsonParser.parseString(new String(StashIndex.heartbeat(built, null, null, -1, null, null, null, null, null, null, null, null, usable), StandardCharsets.UTF_8))
            .getAsJsonObject().getAsJsonObject("craftOrders");
        assertEquals("auto", co.get("table").getAsString());
        assertTrue(co.has("tableWhy") && co.get("tableWhy").isJsonNull());
        assertEquals("1,2,3", co.getAsJsonObject("botTable").get("pos").getAsString());
        assertEquals("placed", co.getAsJsonObject("botTable").get("source").getAsString());
        StashIndex.Craft bad = CraftCapability.heartbeat(true, "a".repeat(40), "1.21.4", "unreachable", 2048, List.of(), null, CraftAuto.TOO_FAR, null);
        JsonObject cb = JsonParser.parseString(new String(StashIndex.heartbeat(built, null, null, -1, null, null, null, null, null, null, null, null, bad), StandardCharsets.UTF_8))
            .getAsJsonObject().getAsJsonObject("craftOrders");
        assertEquals("too_far", cb.get("tableWhy").getAsString());
        assertTrue(cb.get("botTable").isJsonNull());
    }

    @Test
    void theCapabilityNeedsCraftingTheSettingTheRecipesAndATable() {
        for (int bits = 0; bits < 16; bits++) {
            boolean crafting = (bits & 1) != 0, setting = (bits & 2) != 0, recipes = (bits & 4) != 0, table = (bits & 8) != 0;
            assertEquals(bits == 15, CraftCapability.capable(crafting, setting, recipes, table), "bits " + bits);
        }
    }
}

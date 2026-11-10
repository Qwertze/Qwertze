package com.porter.qwerte.manager.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftModelTest {
    private static final String CRAFT_PLAN = """
        {"id":"p1","fileName":"House.litematic","format":"litematic","size":1,"dimensions":{"x":1,"y":1,"z":1},"blocks":10,
         "options":{"multiplier":1,"exclude":[],"fluids":false,"containerItems":false,"round":"none","craft":true},
         "stash":{"server":"s","stash":"main","indexed":true,"mcVersion":"1.21.4"},
         "craft":{"available":true,"canOrder":true,"reason":null,"message":null,"recipes":{"mc":"1.21.4","state":"ready"},
                  "tableStatus":"ok","bot":{"supports":true,"recipesMatch":true},"truncated":false},
         "items":[
           {"id":"minecraft:spruce_stairs","name":"Spruce Stairs","need":8,"inIndex":true,"inStash":0,"available":0,"missing":8,"want":0,"maxStack":64,
            "craftable":8,"missingAfterCraft":0,"craftStatus":"full","craftReason":null,
            "craftUses":[{"id":"minecraft:spruce_log","count":6}],
            "craftSteps":[{"recipe":"minecraft:spruce_planks","result":"minecraft:spruce_planks","made":24,"fills":6,
                           "inputs":[{"id":"minecraft:spruce_log","count":6}]},
                          {"recipe":"minecraft:spruce_stairs","result":"minecraft:spruce_stairs","made":8,"fills":2,
                           "inputs":[{"id":"minecraft:spruce_planks","count":12}]}],
            "craftLeftovers":[{"id":"minecraft:spruce_planks","count":3}]},
           {"id":"minecraft:spruce_fence","need":3,"inIndex":true,"inStash":0,"available":0,"missing":3,"want":0,"maxStack":64,
            "craftable":0,"missingAfterCraft":3,"craftStatus":"short","craftReason":"not enough Spruce Log","craftUses":[],"craftSteps":[],"craftLeftovers":[]},
           {"id":"minecraft:oak_planks","need":10,"inIndex":true,"inStash":4,"available":4,"missing":6,"want":4,"maxStack":64,
            "craftable":4,"missingAfterCraft":2,"craftStatus":"partial","craftReason":"not enough Oak Log"},
           {"id":"minecraft:stone","need":5,"inIndex":true,"inStash":50,"available":50,"missing":0,"want":5,"maxStack":64,
            "craftable":0,"missingAfterCraft":0,"craftStatus":"none"}
         ],
         "totals":{"need":26,"available":54,"missing":17,"want":9,"lines":2,"frozen":false,"slots":2,"shulkers":1,"parts":1,
                   "craftable":12,"craftKinds":2,"missingAfterCraft":5,"craftFills":3,"wantWithCraft":21}}
        """;

    private static final String OLD_PLAN = """
        {"id":"p1","fileName":"House.litematic","format":"litematic","size":1,"dimensions":{"x":1,"y":1,"z":1},"blocks":10,
         "options":{"multiplier":1,"exclude":[],"fluids":false,"containerItems":false,"round":"none"},
         "stash":{"server":"s","stash":"main","indexed":true,"mcVersion":"1.21.4"},
         "items":[{"id":"minecraft:oak_planks","need":10,"inIndex":true,"inStash":4,"available":4,"missing":6,"want":4,"maxStack":64}],
         "totals":{"need":10,"available":4,"missing":6,"want":4,"lines":1,"frozen":false,"slots":1,"shulkers":1,"parts":1}}
        """;

    private static PlanModel.Plan plan(String json) {
        return PlanModel.parsePlan(JsonParser.parseString(json));
    }

    // ---------- parsing ----------

    @Test
    void parsesTheCraftFieldsOfAPlan() {
        PlanModel.Plan p = plan(CRAFT_PLAN);
        CraftModel.Info c = p.craft();
        assertNotNull(c);
        assertTrue(c.available());
        assertTrue(c.canOrder());
        assertEquals("", c.reason());
        assertEquals("1.21.4", c.mc());
        assertEquals("ready", c.state());
        assertEquals("ok", c.tableStatus());
        assertTrue(c.botSupports());
        assertTrue(c.recipesMatch());
        assertFalse(c.truncated());
        assertTrue(p.options().craft());

        PlanModel.Row stairs = p.items().get(0);
        CraftModel.RowCraft k = stairs.craft();
        assertNotNull(k);
        assertEquals(8, k.craftable());
        assertEquals(0, k.missingAfterCraft());
        assertEquals("full", k.status());
        assertEquals(new PlanModel.Count("minecraft:spruce_log", 6), k.uses().get(0));
        assertEquals(2, k.steps().size());
        assertEquals("minecraft:spruce_planks", k.steps().get(0).result());
        assertEquals(24, k.steps().get(0).made());
        assertEquals(6, k.steps().get(0).fills());
        assertEquals(new PlanModel.Count("minecraft:spruce_log", 6), k.steps().get(0).inputs().get(0));
        assertEquals(new PlanModel.Count("minecraft:spruce_planks", 3), k.leftovers().get(0));
        assertEquals(8, stairs.missing());

        CraftModel.Totals t = p.totals().craft();
        assertNotNull(t);
        assertEquals(12, t.craftable());
        assertEquals(2, t.craftKinds());
        assertEquals(5, t.missingAfterCraft());
        assertEquals(3, t.craftFills());
        assertEquals(21, t.wantWithCraft());
        assertEquals(5, p.totals().missingNow());
    }

    @Test
    void anOldServerGivesNoCraftAnywhere() {
        PlanModel.Plan p = plan(OLD_PLAN);
        assertNull(p.craft());
        assertNull(p.totals().craft());
        assertNull(p.items().get(0).craft());
        assertFalse(p.options().craft());
        // everything reads as before
        assertEquals(6, p.totals().missingNow());
        assertEquals(6, CraftModel.missingNow(p.items().get(0)));
        assertEquals("6", CraftModel.missingCell(p.items().get(0)));
        assertEquals("", CraftModel.craftCell(p.items().get(0), null));
        assertEquals("", CraftModel.tooltip(p.items().get(0)));
        assertTrue(CraftModel.banners(null).isEmpty());
        assertEquals(1, PlanModel.visible(p.items(), "", true, PlanModel.Sort.CREATIVE, false).size());
        assertTrue(PlanModel.missingText(p).contains("Oak Planks: 6"));
    }

    @Test
    void absentFieldsOfARowAreForgiven() {
        JsonObject row = JsonParser.parseString("{\"id\":\"minecraft:x\",\"craftable\":3,\"missing\":5}").getAsJsonObject();
        CraftModel.RowCraft k = CraftModel.parseRow(row);
        assertNotNull(k);
        assertEquals(3, k.craftable());
        // missingAfterCraft absent: need - available - craftable is what the server would have said, here missing - craftable
        assertEquals(2, k.missingAfterCraft());
        assertEquals("", k.status());
        assertTrue(k.uses().isEmpty());
        assertTrue(k.steps().isEmpty());
        assertNull(CraftModel.parseRow(JsonParser.parseString("{\"id\":\"minecraft:x\",\"missing\":5}").getAsJsonObject()));
        assertNull(CraftModel.parseInfo(null));
        assertNull(CraftModel.parseTotals(JsonParser.parseString("{\"need\":1}").getAsJsonObject()));
    }

    @Test
    void wrongTypesDoNotThrow() {
        JsonObject row = JsonParser.parseString("{\"craftable\":\"x\",\"craftSteps\":5,\"craftUses\":[1,{\"id\":3}],\"craftStatus\":[]}").getAsJsonObject();
        CraftModel.RowCraft k = CraftModel.parseRow(row);
        assertNotNull(k);
        assertEquals(0, k.craftable());
        assertTrue(k.steps().isEmpty());
        assertTrue(k.uses().isEmpty());
    }

    @Test
    void optionsSendCraftAndKeepItAcrossChanges() {
        assertTrue(PlanModel.Options.DEFAULT.craft());
        JsonObject b = new JsonObject();
        PlanModel.Options.DEFAULT.withCraft(false).addTo(b);
        assertFalse(b.get("craft").getAsBoolean());
        assertTrue(PlanModel.Options.DEFAULT.withMultiplier(3).craft());
        assertFalse(PlanModel.Options.DEFAULT.withCraft(false).withRound("stack").craft());
        // the five-argument form is the options of before crafting
        assertFalse(new PlanModel.Options(1, List.of(), false, false, "none").craft());
    }

    // ---------- the table ----------

    @Test
    void theMissingCellSaysCraftableOrWhatStaysMissing() {
        PlanModel.Plan p = plan(CRAFT_PLAN);
        assertEquals("craftable", CraftModel.missingCell(p.items().get(0)));
        assertEquals("3", CraftModel.missingCell(p.items().get(1)));
        assertEquals("2 (+4 craftable)", CraftModel.missingCell(p.items().get(2)));
        assertEquals("0", CraftModel.missingCell(p.items().get(3)));
        assertFalse(CraftModel.stillMissing(p.items().get(0)));
        assertTrue(CraftModel.stillMissing(p.items().get(1)));
        assertTrue(CraftModel.stillMissing(p.items().get(2)));
    }

    @Test
    void theCraftCellShowsNumbersDashesAndLoading() {
        PlanModel.Plan p = plan(CRAFT_PLAN);
        assertEquals("8", CraftModel.craftCell(p.items().get(0), p.craft()));
        assertEquals("0", CraftModel.craftCell(p.items().get(1), p.craft()));
        assertEquals("-", CraftModel.craftCell(p.items().get(3), p.craft()));
        CraftModel.Info building = new CraftModel.Info(false, false, "", "", "1.21.4", "building", "", false, true, false);
        assertEquals("...", CraftModel.craftCell(new PlanModel.Row("minecraft:x", "", 1, 1, 1, 0, 0, true, 0, 0, 1, 0, 64, 0, false), building));
    }

    @Test
    void theHoverTextListsUsesStepsAndLeftovers() {
        PlanModel.Plan p = plan(CRAFT_PLAN);
        String tip = CraftModel.tooltip(p.items().get(0));
        assertTrue(tip.contains("Uses from the stash: 6 Spruce Log"), tip);
        assertTrue(tip.contains("24 Spruce Planks from 6 Spruce Log; 8 Spruce Stairs from 12 Spruce Planks"), tip);
        assertTrue(tip.contains("Back to the stash: 3 Spruce Planks"), tip);
        // a row that can't be made says why
        assertEquals("Not enough Spruce Log.", CraftModel.tooltip(p.items().get(1)));
        // a partial row has both
        assertTrue(CraftModel.tooltip(p.items().get(2)).contains("Not enough Oak Log."));
    }

    @Test
    void onlyMissingAndCopyMissingUseWhatStaysMissingAfterCrafting() {
        PlanModel.Plan p = plan(CRAFT_PLAN);
        List<PlanModel.Row> shown = PlanModel.visible(p.items(), "", true, PlanModel.Sort.CREATIVE, false);
        // stairs are fully craftable and stone is there: only the fence and the planks stay missing
        assertEquals(List.of("minecraft:spruce_fence", "minecraft:oak_planks"), shown.stream().map(PlanModel.Row::id).toList());
        String text = PlanModel.missingText(p);
        assertTrue(text.startsWith("Missing materials (2 kinds)"), text);
        assertTrue(text.contains("Spruce Fence: 3"), text);
        assertTrue(text.contains("Oak Planks: 2"), text);
        assertFalse(text.contains("Spruce Stairs"), text);
        // sorting by Missing uses it too
        List<PlanModel.Row> sorted = PlanModel.visible(p.items(), "", false, PlanModel.Sort.MISSING, true);
        assertEquals("minecraft:spruce_fence", sorted.get(0).id());
    }

    // ---------- banners, tiles, the box ----------

    private static CraftModel.Info info(boolean available, boolean canOrder, String reason, String state, String table, boolean truncated) {
        return new CraftModel.Info(available, canOrder, reason, "", "1.21.4", state, table, true, true, truncated);
    }

    @Test
    void oneBannerPerState() {
        assertTrue(CraftModel.banners(info(true, true, "", "ready", "ok", false)).isEmpty());

        List<CraftModel.Banner> building = CraftModel.banners(info(false, false, "no_recipes", "building", "", false));
        assertEquals("Loading recipes for 1.21.4...", building.get(0).text());
        assertFalse(building.get(0).warn());

        List<CraftModel.Banner> none = CraftModel.banners(info(false, false, "no_recipes", "none", "", false));
        assertEquals("Crafting info isn't available for 1.21.4.", none.get(0).text());

        assertTrue(CraftModel.banners(info(true, false, "no_bot", "ready", "unset", false)).get(0).text().contains("needs StashSorter 1.15.0+"));
        assertTrue(CraftModel.banners(info(true, false, "recipes_differ", "ready", "ok", false)).get(0).text().contains("recipe table differs"));
        assertTrue(CraftModel.banners(info(true, false, "no_table", "ready", "unset", false)).get(0).text().startsWith("Set a crafting table for this stash"));
        assertEquals("The crafting table isn't usable: not table.", CraftModel.banners(info(true, false, "table_bad", "ready", "not_table", false)).get(0).text());

        List<CraftModel.Banner> cut = CraftModel.banners(info(true, true, "", "ready", "ok", true));
        assertEquals(1, cut.size());
        assertTrue(cut.get(0).text().contains("cut short"));
        // cut short and not orderable: both
        assertEquals(2, CraftModel.banners(info(true, false, "no_table", "ready", "unset", true)).size());
    }

    @Test
    void theCraftBoxIsUsableOnlyWhenTheStashCanTakeCraftOrders() {
        assertTrue(CraftModel.orderable(info(true, true, "", "ready", "ok", false)));
        assertFalse(CraftModel.orderable(info(true, false, "no_table", "ready", "unset", false)));
        assertFalse(CraftModel.orderable(info(false, true, "", "building", "", false)));
        assertFalse(CraftModel.orderable(null));
        CraftModel.Totals t = new CraftModel.Totals(12, 2, 5, 3, 21);
        assertEquals("Craft what the stash can make (12 items from the stash)", CraftModel.boxLabel(info(true, true, "", "ready", "ok", false), t));
        assertEquals("Craft what the stash can make", CraftModel.boxLabel(info(true, false, "no_table", "ready", "unset", false), t));
    }

    @Test
    void theCraftableTileSaysKindsOrWhyNot() {
        CraftModel.Totals t = new CraftModel.Totals(12, 2, 5, 3, 21);
        assertEquals("2 kinds, from the stash", CraftModel.tileSub(t, info(true, true, "", "ready", "ok", false)));
        assertEquals("2 kinds, can't be ordered: no crafting table", CraftModel.tileSub(t, info(true, false, "no_table", "ready", "unset", false)));
        assertEquals("loading recipes", CraftModel.tileSub(null, info(false, false, "no_recipes", "building", "", false)));
        assertTrue(info(false, false, "", "building", "", false).showsColumn());
        assertFalse(info(false, false, "", "none", "", false).showsColumn());
    }

    // ---------- dry run ----------

    @Test
    void theDryRunParsesCraftPartsAndMissingAfterCraft() {
        PlanModel.Dry d = PlanModel.parseDry(JsonParser.parseString("""
            {"parts":[{"part":1,"lineCount":3,"shulkers":1,"items":40,"order":"","craft":{"lines":2,"fills":5,"take":[]}},
                      {"part":2,"lineCount":1,"shulkers":1,"items":9,"order":"","craft":null}],
             "partsTotal":2,"remaining":[1,2],"again":[],"notAvailable":[],
             "missing":[{"id":"minecraft:a","missing":10,"missingAfterCraft":0},{"id":"minecraft:b","missing":4,"missingAfterCraft":4}]}
            """));
        assertEquals(2, d.parts().get(0).craftLines());
        assertEquals(5, d.parts().get(0).craftFills());
        assertEquals(0, d.parts().get(1).craftLines());
        // a kind that crafting covers drops out of "still missing"
        assertEquals(List.of(new PlanModel.Count("minecraft:b", 4)), d.missing());
        // an old server's missing list is read as before
        PlanModel.Dry old = PlanModel.parseDry(JsonParser.parseString("{\"parts\":[],\"missing\":[{\"id\":\"minecraft:a\",\"missing\":10}]}"));
        assertEquals(List.of(new PlanModel.Count("minecraft:a", 10)), old.missing());
    }

    // ---------- orders ----------

    @Test
    void anOrderShowsItsCraftLinesAndResult() {
        OrderModel.Entry e = OrderModel.parseEntry(JsonParser.parseString("""
            {"id":"o1","status":"delivered","lines":[{"type":"item","key":"minecraft:oak_planks","count":64},
                                                      {"type":"craft","key":"minecraft:spruce_stairs","count":8},
                                                      {"type":"craft","key":"minecraft:spruce_fence","count":3}],
             "craft":{"v":1,"mc":"1.21.4","recipes":"abc","lines":[],"steps":[],"take":[{"id":"minecraft:spruce_log","count":6}],
                      "leftovers":[{"id":"minecraft:spruce_planks","count":3}],"fills":11},
             "result":{"shulkers":1,"craft":{"fills":11,"made":[{"key":"minecraft:spruce_stairs","wanted":8,"made":8}],
                       "short":[{"key":"minecraft:spruce_fence","count":3,"reason":"ingredients_short"}],
                       "used":[{"id":"minecraft:spruce_log","count":6}],"returned":[{"id":"minecraft:spruce_planks","count":3}]}}}
            """).getAsJsonObject());
        assertNotNull(e);
        // item lines stay item lines
        assertEquals(1, e.lines().size());
        CraftModel.OrderCraft c = e.craft();
        assertNotNull(c);
        assertEquals(List.of("Craft: 8 Spruce Stairs", "Craft: 3 Spruce Fence"), CraftModel.orderLines(c, 5));
        assertEquals(1, CraftModel.orderLines(c, 1).size());
        assertEquals(11, c.fills());
        assertEquals(new PlanModel.Count("minecraft:spruce_log", 6), c.take().get(0));
        assertEquals("Crafted: 8 Spruce Stairs", CraftModel.madeText(c.result()));
        assertEquals("Not crafted: 3 Spruce Fence (the ingredients ran short)", CraftModel.shortText(c.result()));
        assertEquals("Returned to the stash: 3 Spruce Planks", CraftModel.returnedText(c.result()));
    }

    @Test
    void anOrderWithoutCraftHasNone() {
        OrderModel.Entry e = OrderModel.parseEntry(JsonParser.parseString(
            "{\"id\":\"o1\",\"status\":\"pending\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:stone\",\"count\":64}],\"craft\":null}").getAsJsonObject());
        assertNotNull(e);
        assertNull(e.craft());
        assertEquals("", CraftModel.madeText(null));
        assertEquals("", CraftModel.shortText(null));
        assertTrue(CraftModel.orderLines(null, 3).isEmpty());
    }

    @Test
    void shortReasonsAreInPlainWords() {
        for (String code : List.of("no_table", "table_missing", "table_blocked", "ingredients_short", "inventory_full", "recipe_mismatch", "grid_stuck",
            "timeout", "interrupted", "cap", "cancelled")) {
            String words = CraftModel.shortReason(code);
            assertFalse(words.contains("_"), code + " -> " + words);
            assertFalse(words.isEmpty());
        }
        assertEquals("(hidden)", CraftModel.shortReason("(hidden)"));
        assertEquals("unknown reason", CraftModel.shortReason(""));
        assertEquals("some new thing", CraftModel.shortReason("some_new_thing"));
    }
}

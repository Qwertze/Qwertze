package com.porter.qwerte.printer.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import com.porter.qwerte.printer.place.ToolRules.Choice;
import com.porter.qwerte.printer.place.ToolRules.Verdict;
import com.porter.qwerte.printer.place.ToolRules.WorldState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolRulesTest {
    /** A fake table: what the game's tables hold for a few blocks, plus the hand-coded hoe and shears entries. */
    private static final ToolRules.Table TABLE = HandTables.addTo(new ToolRules.Builder()
        .add(Tool.AXE, "minecraft:oak_log", "minecraft:stripped_oak_log")
        .add(Tool.AXE, "minecraft:oak_wood", "minecraft:stripped_oak_wood")
        .add(Tool.SHOVEL, "minecraft:grass_block", "minecraft:dirt_path")
        .add(Tool.SHOVEL, "minecraft:dirt", "minecraft:dirt_path")
        .add(Tool.SHOVEL, "minecraft:rooted_dirt", "minecraft:dirt_path")
        .add(Tool.HONEYCOMB, "minecraft:cut_copper_stairs", "minecraft:waxed_cut_copper_stairs")
        .add(Tool.PLANT, HandTables.FLOWER_POT, "minecraft:potted_cactus")
        .add(Tool.PLANT, HandTables.FLOWER_POT, "minecraft:potted_fern")).build();

    private static StateSpec spec(String id, StateKind kind, String... kv) {
        Map<String, String> props = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) props.put(kv[i], kv[i + 1]);
        return StateSpec.of(id, kind, props, false);
    }

    private static Verdict classify(StateSpec want, String haveId, String... kv) {
        Map<String, String> props = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) props.put(kv[i], kv[i + 1]);
        return ToolRules.classify(want, haveId, props, TABLE);
    }

    // The strip cases, migrated from StripRules

    @Test
    void theSameBlockIsLeftToTheUsualPropertyCheck() {
        StateSpec log = spec("minecraft:stripped_oak_log", StateKind.AXIS, "axis", "x");
        assertEquals(WorldState.SAME, classify(log, "minecraft:stripped_oak_log", "axis", "x").state());
        assertEquals(WorldState.SAME, classify(log, "minecraft:stripped_oak_log", "axis", "y").state());
    }

    @Test
    void theUnstrippedBlockWithTheWantedAxisNeedsTheAxe() {
        Verdict v = classify(spec("minecraft:stripped_oak_log", StateKind.AXIS, "axis", "x"), "minecraft:oak_log", "axis", "x");
        assertEquals(WorldState.NEEDS_TOOL, v.state());
        assertEquals(new ToolAction(Tool.AXE, "minecraft:oak_log"), v.action());
        assertEquals(WorldState.NEEDS_TOOL, classify(spec("minecraft:stripped_oak_wood", StateKind.AXIS, "axis", "y"), "minecraft:oak_wood", "axis", "y").state());
    }

    @Test
    void theWrongAxisOrAnotherBlockIsAWrongBlock() {
        StateSpec log = spec("minecraft:stripped_oak_log", StateKind.AXIS, "axis", "x");
        assertEquals(WorldState.OTHER, classify(log, "minecraft:oak_log", "axis", "y").state());
        assertEquals(WorldState.OTHER, classify(log, "minecraft:birch_log", "axis", "x").state());
        assertEquals(WorldState.OTHER, classify(log, "minecraft:stone").state());
        assertEquals(WorldState.OTHER, classify(spec("minecraft:stone", StateKind.FULL), "minecraft:oak_log", "axis", "x").state());
    }

    @Test
    void theItemIsPreferredThenTheRawOneWithOrWithoutTheTool() {
        assertEquals(Choice.TARGET, ToolRules.choose(true, true, true));
        assertEquals(Choice.TARGET, ToolRules.choose(true, false, false));
        assertEquals(Choice.RAW_THEN_TOOL, ToolRules.choose(false, true, true));
        assertEquals(Choice.RAW_NO_TOOL, ToolRules.choose(false, true, false));
        assertEquals(Choice.NONE, ToolRules.choose(false, false, true));
    }

    @Test
    void aToolClicksTheFaceTowardTheEyes() {
        assertEquals(Dir.UP, ToolRules.faceToward(0.5, 5.5, 0.5, 0, 0, 0));
        assertEquals(Dir.DOWN, ToolRules.faceToward(0.5, -4.5, 0.5, 0, 0, 0));
        assertEquals(Dir.EAST, ToolRules.faceToward(5.5, 1.0, 0.5, 0, 0, 0));
        assertEquals(Dir.WEST, ToolRules.faceToward(-4.5, 1.0, 0.5, 0, 0, 0));
        assertEquals(Dir.SOUTH, ToolRules.faceToward(0.5, 1.0, 5.5, 0, 0, 0));
        assertEquals(Dir.NORTH, ToolRules.faceToward(0.5, 1.0, -4.5, 0, 0, 0));
        assertEquals(Dir.UP, ToolRules.faceToward(3.5, 3.5, 3.5, 0, 0, 0));
        assertEquals(Dir.EAST, ToolRules.faceToward(3.5, 0.5, 3.5, 0, 0, 0));
    }

    // Shovel: dirt paths and doused campfires

    @Test
    void aDirtPathIsMadeFromGrassDirtOrRootedDirtWithAShovel() {
        StateSpec path = spec("minecraft:dirt_path", StateKind.FULL);
        for (String raw : List.of("minecraft:grass_block", "minecraft:dirt", "minecraft:rooted_dirt")) {
            Verdict v = classify(path, raw);
            assertEquals(WorldState.NEEDS_TOOL, v.state(), raw);
            assertEquals(Tool.SHOVEL, v.action().tool());
        }
        assertEquals(WorldState.OTHER, classify(path, "minecraft:stone").state());
        assertEquals(WorldState.SAME, classify(path, "minecraft:dirt_path").state());
    }

    @Test
    void aShovelAndAHoeNeedAirAboveAndNeverClickTheBottom() {
        ToolAction hoe = new ToolAction(Tool.HOE, "minecraft:dirt");
        ToolAction shovel = new ToolAction(Tool.SHOVEL, "minecraft:grass_block");
        ToolAction douse = new ToolAction(Tool.SHOVEL, "minecraft:campfire");
        assertTrue(hoe.needsAirAbove());
        assertTrue(shovel.needsAirAbove());
        assertFalse(douse.needsAirAbove(), "dousing a campfire needs no air above");
        assertFalse(new ToolAction(Tool.AXE, "minecraft:oak_log").needsAirAbove());
        // The eye is below the block: the bottom face is not clicked, a side is
        assertEquals(Dir.EAST, ToolRules.clickFace(hoe, null, 3.5, -4.5, 0.5, 0, 0, 0));
        assertEquals(Dir.NORTH, ToolRules.clickFace(shovel, null, 0.5, -4.5, -3.5, 0, 0, 0));
        // Above: the top. The axe and the others still take the bottom when the eye is below.
        assertEquals(Dir.UP, ToolRules.clickFace(hoe, null, 0.5, 4.5, 0.5, 0, 0, 0));
        assertEquals(Dir.DOWN, ToolRules.clickFace(new ToolAction(Tool.AXE, "minecraft:oak_log"), null, 0.5, -4.5, 0.5, 0, 0, 0));
    }

    @Test
    void theCellAboveWaitsForAToolThatNeedsAirAboveOnlyWhileItIsCarried() {
        ToolAction hoe = new ToolAction(Tool.HOE, "minecraft:dirt");
        assertTrue(ToolRules.holdsAbove(hoe, true));
        assertFalse(ToolRules.holdsAbove(hoe, false), "no hoe: the cells above must not wait for nothing");
        assertFalse(ToolRules.holdsAbove(new ToolAction(Tool.AXE, "minecraft:oak_log"), true));
        assertFalse(ToolRules.holdsAbove(null, true));
    }

    // Hoe: the hand-coded TILLABLES

    @Test
    void farmlandIsTilledFromDirtGrassOrADirtPathWhateverItsMoisture() {
        StateSpec farm = spec("minecraft:farmland", StateKind.FULL, "moisture", "7");
        assertTrue(farm.matched().isEmpty(), "moisture is derived, not compared");
        for (String raw : List.of("minecraft:dirt", "minecraft:grass_block", "minecraft:dirt_path")) {
            Verdict v = classify(farm, raw);
            assertEquals(WorldState.NEEDS_TOOL, v.state(), raw);
            assertEquals(Tool.HOE, v.action().tool());
        }
        assertEquals(WorldState.OTHER, classify(farm, "minecraft:coarse_dirt").state());
        assertEquals(WorldState.OTHER, classify(farm, "minecraft:podzol").state());
        assertEquals(WorldState.SAME, classify(farm, "minecraft:farmland", "moisture", "0").state());
    }

    @Test
    void coarseDirtAndRootedDirtAreWrongBlocksForWantedDirt() {
        // Not a tool job: otherwise every plain dirt cell would count as a tool source (dev.15 behaviour)
        StateSpec dirt = spec("minecraft:dirt", StateKind.FULL);
        assertEquals(WorldState.OTHER, classify(dirt, "minecraft:coarse_dirt").state());
        assertEquals(WorldState.OTHER, classify(dirt, "minecraft:rooted_dirt").state());
        assertEquals(WorldState.OTHER, classify(dirt, "minecraft:grass_block").state());
        assertTrue(TABLE.actionsFor("minecraft:dirt").isEmpty());
    }

    @Test
    void dirtIsTheRawBlockToPlaceWhenThereIsAChoice() {
        assertEquals("minecraft:dirt", ToolRules.preferred(TABLE.actionsFor("minecraft:farmland")).rawId());
        assertEquals("minecraft:dirt", ToolRules.preferred(TABLE.actionsFor("minecraft:dirt_path")).rawId());
        assertNull(ToolRules.preferred(List.of()));
        assertTrue(HandTables.toolOnly("minecraft:farmland"));
        assertTrue(HandTables.toolOnly("minecraft:dirt_path"));
        assertFalse(HandTables.toolOnly("minecraft:stripped_oak_log"));
        assertEquals("minecraft:dirt", HandTables.PLACED_FROM.get("minecraft:farmland"));
    }

    // Shears: carved pumpkins

    @Test
    void aCarvedPumpkinIsAPumpkinWithShearsWhateverItsFacing() {
        StateSpec carved = spec("minecraft:carved_pumpkin", StateKind.HFACING, "facing", "west");
        Verdict v = classify(carved, "minecraft:pumpkin");
        assertEquals(WorldState.NEEDS_TOOL, v.state(), "a pumpkin has no facing to compare");
        assertEquals(Tool.SHEARS, v.action().tool());
        assertEquals(WorldState.SAME, classify(carved, "minecraft:carved_pumpkin", "facing", "east").state());
    }

    @Test
    void theShearsClickTheSideTheCarvedFaceLooksAt() {
        ToolAction shears = new ToolAction(Tool.SHEARS, "minecraft:pumpkin");
        // The eye is east of the block, the face must point west: the west side is clicked (PumpkinBlock uses the clicked side)
        assertEquals(Dir.WEST, ToolRules.clickFace(shears, Dir.WEST, 5.5, 0.5, 0.5, 0, 0, 0));
        assertEquals(Dir.NORTH, ToolRules.clickFace(shears, Dir.NORTH, 0.5, 5.5, 0.5, 0, 0, 0));
        // No facing known: the usual side toward the eyes
        assertEquals(Dir.UP, ToolRules.clickFace(shears, null, 0.5, 5.5, 0.5, 0, 0, 0));
        assertTrue(Tool.SHEARS.needsNoSneak());
    }

    // Honeycomb

    @Test
    void waxedCopperIsTheUnwaxedBlockWithTheSamePropertiesAndHoneycomb() {
        StateSpec waxed = spec("minecraft:waxed_cut_copper_stairs", StateKind.STAIRS, "facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "false");
        Verdict v = classify(waxed, "minecraft:cut_copper_stairs", "facing", "north", "half", "bottom", "shape", "inner_left", "waterlogged", "false");
        assertEquals(WorldState.NEEDS_TOOL, v.state());
        assertEquals(Tool.HONEYCOMB, v.action().tool());
        assertEquals(WorldState.OTHER, classify(waxed, "minecraft:cut_copper_stairs", "facing", "south", "half", "bottom").state());
        assertEquals(WorldState.SAME, classify(waxed, "minecraft:waxed_cut_copper_stairs", "facing", "north", "half", "bottom").state());
    }

    @Test
    void honeycombIsNotWastedOnPlacingCopperThatWouldAge() {
        assertFalse(ToolRules.placeWithoutTool(Tool.HONEYCOMB));
        for (Tool t : Tool.values()) if (t != Tool.HONEYCOMB) assertTrue(ToolRules.placeWithoutTool(t), t.name());
    }

    // Flint and steel, campfires

    @Test
    void aLitCandleIsAnUnlitOneUntilFlintAndSteelLightsIt() {
        StateSpec lit = spec("minecraft:red_candle", StateKind.COUNT, "candles", "2", "lit", "true", "waterlogged", "false");
        Verdict v = classify(lit, "minecraft:red_candle", "candles", "2", "lit", "false", "waterlogged", "false");
        assertEquals(WorldState.NEEDS_TOOL, v.state());
        assertEquals(new ToolAction(Tool.FLINT_AND_STEEL, "minecraft:red_candle"), v.action());
        assertEquals(WorldState.SAME, classify(lit, "minecraft:red_candle", "lit", "true").state());
        // An unlit candle wanted unlit needs nothing
        assertEquals(WorldState.SAME, classify(spec("minecraft:candle", StateKind.COUNT, "lit", "false"), "minecraft:candle", "lit", "false").state());
        // A waterlogged candle cannot be lit, and flint and steel on it would set the next block on fire
        assertEquals(WorldState.SAME, classify(lit, "minecraft:red_candle", "lit", "false", "waterlogged", "true").state());
        assertNull(ToolRules.propertyTool("minecraft:red_candle", Map.of("lit", "true", "waterlogged", "true")));
        // Candle cakes and furnaces are not touched
        assertNull(ToolRules.propertyTool("minecraft:red_candle_cake", Map.of("lit", "true")));
        assertNull(ToolRules.propertyTool("minecraft:furnace", Map.of("lit", "true")));
    }

    @Test
    void aCampfireThatIsWantedOutIsDousedWithAShovel() {
        StateSpec out = spec("minecraft:campfire", StateKind.HFACING_LOOK, "facing", "north", "lit", "false", "waterlogged", "false");
        Verdict v = classify(out, "minecraft:campfire", "facing", "north", "lit", "true");
        assertEquals(WorldState.NEEDS_TOOL, v.state());
        assertEquals(Tool.SHOVEL, v.action().tool());
        assertFalse(v.action().needsAirAbove());
        assertEquals(WorldState.SAME, classify(out, "minecraft:campfire", "lit", "false").state());
        // Placed lit as it is wanted: nothing to do
        assertNull(ToolRules.propertyTool("minecraft:campfire", Map.of("lit", "true")));
        assertEquals(Tool.SHOVEL, ToolRules.propertyTool("minecraft:soul_campfire", Map.of("lit", "false")));
    }

    // Flower pots

    @Test
    void anEmptyPotNeedsThePlantAndIsNotAWrongBlock() {
        StateSpec cactus = spec("minecraft:potted_cactus", StateKind.FULL);
        Verdict v = classify(cactus, HandTables.FLOWER_POT);
        assertEquals(WorldState.NEEDS_TOOL, v.state());
        assertEquals(new ToolAction(Tool.PLANT, HandTables.FLOWER_POT), v.action());
        assertTrue(Tool.PLANT.needsNoSneak(), "the pot's own right-click code is skipped while sneaking");
    }

    @Test
    void aPotHoldingAnotherPlantIsAWrongBlockAndTheRightOneIsBuilt() {
        StateSpec cactus = spec("minecraft:potted_cactus", StateKind.FULL);
        assertEquals(WorldState.OTHER, classify(cactus, "minecraft:potted_fern").state());
        assertEquals(WorldState.SAME, classify(cactus, "minecraft:potted_cactus").state());
        assertEquals(WorldState.OTHER, classify(cactus, "minecraft:stone").state());
        // An empty pot wanted is not satisfied by a filled one (the plant would have to be broken out)
        assertEquals(WorldState.OTHER, classify(spec(HandTables.FLOWER_POT, StateKind.FULL), "minecraft:potted_cactus").state());
        assertEquals(WorldState.SAME, classify(spec(HandTables.FLOWER_POT, StateKind.FULL), HandTables.FLOWER_POT).state());
    }
}

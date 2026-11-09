package com.porter.qwerte.printer.place;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import com.porter.qwerte.printer.place.StripRules.Choice;
import com.porter.qwerte.printer.place.StripRules.WorldState;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class StripRulesTest {
    private static final Map<String, String> RAW_OF = Map.of(
        "minecraft:stripped_oak_log", "minecraft:oak_log",
        "minecraft:stripped_oak_wood", "minecraft:oak_wood",
        "minecraft:stripped_crimson_stem", "minecraft:crimson_stem");
    private static final UnaryOperator<String> RAW = RAW_OF::get;

    @Test
    void theSameBlockIsLeftToTheUsualPropertyCheck() {
        assertEquals(WorldState.SAME_BLOCK, StripRules.classify("minecraft:stripped_oak_log", "x", "minecraft:stripped_oak_log", "x", RAW));
        assertEquals(WorldState.SAME_BLOCK, StripRules.classify("minecraft:stripped_oak_log", "x", "minecraft:stripped_oak_log", "y", RAW));
    }

    @Test
    void theUnstrippedBlockWithTheWantedAxisIsRightBlockNeedsStrip() {
        assertEquals(WorldState.NEEDS_STRIP, StripRules.classify("minecraft:stripped_oak_log", "x", "minecraft:oak_log", "x", RAW));
        assertEquals(WorldState.NEEDS_STRIP, StripRules.classify("minecraft:stripped_oak_wood", "y", "minecraft:oak_wood", "y", RAW));
        assertEquals(WorldState.NEEDS_STRIP, StripRules.classify("minecraft:stripped_crimson_stem", "z", "minecraft:crimson_stem", "z", RAW));
    }

    @Test
    void theWrongAxisOrAnotherBlockIsAWrongBlock() {
        assertEquals(WorldState.OTHER, StripRules.classify("minecraft:stripped_oak_log", "x", "minecraft:oak_log", "y", RAW));
        assertEquals(WorldState.OTHER, StripRules.classify("minecraft:stripped_oak_log", "x", "minecraft:birch_log", "x", RAW));
        assertEquals(WorldState.OTHER, StripRules.classify("minecraft:stripped_oak_log", "x", "minecraft:stone", null, RAW));
        assertEquals(WorldState.OTHER, StripRules.classify("minecraft:stripped_oak_log", null, "minecraft:oak_log", "x", RAW));
        // a block nothing strips into has no unstripped form
        assertEquals(WorldState.OTHER, StripRules.classify("minecraft:stone", "x", "minecraft:oak_log", "x", RAW));
    }

    @Test
    void theStrippedItemIsPreferredThenTheUnstrippedOneWithOrWithoutAnAxe() {
        assertEquals(Choice.STRIPPED, StripRules.choose(true, true, true));
        assertEquals(Choice.STRIPPED, StripRules.choose(true, false, false));
        assertEquals(Choice.RAW_THEN_STRIP, StripRules.choose(false, true, true));
        assertEquals(Choice.RAW_NO_AXE, StripRules.choose(false, true, false));
        assertEquals(Choice.NONE, StripRules.choose(false, false, true));
    }

    @Test
    void theAxeClicksTheFaceTowardTheEyes() {
        assertEquals(Dir.UP, StripRules.faceToward(0.5, 5.5, 0.5, 0, 0, 0));
        assertEquals(Dir.DOWN, StripRules.faceToward(0.5, -4.5, 0.5, 0, 0, 0));
        assertEquals(Dir.EAST, StripRules.faceToward(5.5, 1.0, 0.5, 0, 0, 0));
        assertEquals(Dir.WEST, StripRules.faceToward(-4.5, 1.0, 0.5, 0, 0, 0));
        assertEquals(Dir.SOUTH, StripRules.faceToward(0.5, 1.0, 5.5, 0, 0, 0));
        assertEquals(Dir.NORTH, StripRules.faceToward(0.5, 1.0, -4.5, 0, 0, 0));
        // a tie goes to the vertical axis, then x
        assertEquals(Dir.UP, StripRules.faceToward(3.5, 3.5, 3.5, 0, 0, 0));
        assertEquals(Dir.EAST, StripRules.faceToward(3.5, 0.5, 3.5, 0, 0, 0));
    }
}

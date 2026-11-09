package com.porter.qwerte.printer.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import com.porter.qwerte.printer.place.PropertyRules.Action;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PropertyRulesTest {
    private static StateSpec spec(String id, StateKind k, String... kv) {
        java.util.TreeMap<String, String> m = new java.util.TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return StateSpec.of(id, k, m, false);
    }

    private static Action action(StateKind k, String id, String prop, String value) {
        return PropertyRules.rule(k, id, prop, value).action();
    }

    @Test
    void stairsMatchFacingAndHalfAndDeriveTheShape() {
        StateSpec s = spec("minecraft:oak_stairs", StateKind.STAIRS, "facing", "north", "half", "top", "shape", "inner_left", "waterlogged", "false");
        assertEquals(Map.of("facing", "north", "half", "top"), s.matched());
        assertTrue(s.clicked().isEmpty());
        assertTrue(s.placeable());
        assertEquals(Action.DERIVED, action(StateKind.STAIRS, "minecraft:oak_stairs", "shape", "outer_right"));
    }

    @Test
    void waterloggedTrueIsSkippedWithAReasonAndFalseIsDerived() {
        StateSpec wet = spec("minecraft:oak_stairs", StateKind.STAIRS, "facing", "north", "half", "top", "waterlogged", "true");
        assertTrue(wet.skipped());
        assertEquals(StateKind.SKIP, wet.kind());
        assertTrue(wet.skipReason().contains("waterlogged"));
        assertEquals(Action.DERIVED, action(StateKind.STAIRS, "minecraft:oak_stairs", "waterlogged", "false"));
    }

    @Test
    void slabDoubleIsReachedByAClickAndHalvesAreMatched() {
        assertEquals(Action.CLICK, action(StateKind.SLAB, "minecraft:stone_slab", "type", "double"));
        assertEquals(Action.MATCH, action(StateKind.SLAB, "minecraft:stone_slab", "type", "top"));
        assertEquals(Action.MATCH, action(StateKind.SLAB, "minecraft:stone_slab", "type", "bottom"));
        StateSpec d = spec("minecraft:stone_slab", StateKind.SLAB, "type", "double", "waterlogged", "false");
        assertTrue(d.matched().isEmpty());
        assertEquals(Map.of("type", "double"), d.clicked());
    }

    @Test
    void doorUpperHalfAndBedHeadAreParts() {
        StateSpec upper = spec("minecraft:oak_door", StateKind.DOOR, "facing", "east", "half", "upper", "hinge", "left", "open", "false", "powered", "false");
        assertTrue(upper.part());
        assertFalse(upper.placeable());
        StateSpec lower = spec("minecraft:oak_door", StateKind.DOOR, "facing", "east", "half", "lower", "hinge", "left", "open", "true", "powered", "false");
        assertEquals(StateKind.DOOR, lower.kind());
        assertEquals(Map.of("facing", "east", "hinge", "left"), lower.matched());
        assertEquals(Map.of("open", "true"), lower.clicked());
        StateSpec head = spec("minecraft:red_bed", StateKind.BED, "facing", "north", "part", "head", "occupied", "false");
        assertTrue(head.part());
        StateSpec foot = spec("minecraft:red_bed", StateKind.BED, "facing", "north", "part", "foot", "occupied", "false");
        assertEquals(StateKind.BED, foot.kind());
        assertEquals(Map.of("facing", "north"), foot.matched());
        assertTrue(spec("minecraft:sunflower", StateKind.SUPPORTED_FLOOR, "half", "upper").part());
        assertEquals(StateKind.SUPPORTED_FLOOR, spec("minecraft:sunflower", StateKind.SUPPORTED_FLOOR, "half", "lower").kind());
    }

    @Test
    void ironDoorsAndTrapdoorsOnlyOpenWithRedstone() {
        assertEquals(Action.SKIP, action(StateKind.DOOR, "minecraft:iron_door", "open", "true"));
        assertEquals(Action.DERIVED, action(StateKind.DOOR, "minecraft:iron_door", "open", "false"));
        assertEquals(Action.SKIP, action(StateKind.TRAPDOOR, "minecraft:iron_trapdoor", "open", "true"));
        assertEquals(Action.CLICK, action(StateKind.TRAPDOOR, "minecraft:oak_trapdoor", "open", "true"));
        assertEquals(Action.CLICK, action(StateKind.FENCE_GATE, "minecraft:oak_fence_gate", "open", "false"));
        assertTrue(PropertyRules.rule(StateKind.DOOR, "minecraft:iron_door", "open", "true").reason().contains("iron"));
    }

    @Test
    void doubleChestHalvesAreSkippedAndSingleIsMatched() {
        StateSpec single = spec("minecraft:chest", StateKind.CHEST, "facing", "south", "type", "single", "waterlogged", "false");
        assertEquals(Map.of("facing", "south", "type", "single"), single.matched());
        for (String t : new String[] { "left", "right" }) {
            StateSpec half = spec("minecraft:chest", StateKind.CHEST, "facing", "south", "type", t, "waterlogged", "false");
            assertTrue(half.skipped());
            assertTrue(half.skipReason().contains("double chest"));
        }
    }

    @Test
    void countKindsReachTheirCountByClicksAndIgnoreLightingAndWater() {
        StateSpec c = spec("minecraft:red_candle", StateKind.COUNT, "candles", "3", "lit", "true", "waterlogged", "false");
        assertTrue(c.matched().isEmpty());
        assertEquals(Map.of("candles", "3"), c.clicked());
        assertEquals(Action.CLICK, action(StateKind.COUNT, "minecraft:sea_pickle", "pickles", "4"));
        assertEquals(Action.CLICK, action(StateKind.COUNT, "minecraft:turtle_egg", "eggs", "2"));
        assertEquals(Action.DERIVED, action(StateKind.COUNT, "minecraft:turtle_egg", "hatch", "1"));
        assertEquals(Action.CLICK, action(StateKind.COUNT, "minecraft:snow", "layers", "5"));
    }

    @Test
    void repeatersComparatorsNotesAndDaylightUseClicks() {
        StateSpec r = spec("minecraft:repeater", StateKind.REPEATER, "delay", "3", "facing", "north", "locked", "false", "powered", "true");
        assertEquals(Map.of("facing", "north"), r.matched());
        assertEquals(Map.of("delay", "3"), r.clicked());
        assertEquals(Action.CLICK, action(StateKind.COMPARATOR, "minecraft:comparator", "mode", "subtract"));
        assertEquals(Action.CLICK, action(StateKind.NOTE, "minecraft:note_block", "note", "12"));
        assertEquals(Action.DERIVED, action(StateKind.NOTE, "minecraft:note_block", "instrument", "harp"));
        assertEquals(Action.CLICK, action(StateKind.DAYLIGHT, "minecraft:daylight_detector", "inverted", "true"));
    }

    @Test
    void railShapeIsListedOnMismatchRedstoneConnectionsAreDerived() {
        assertEquals(Action.LISTED, action(StateKind.SUPPORTED_FLOOR, "minecraft:rail", "shape", "ascending_east"));
        StateSpec rail = spec("minecraft:powered_rail", StateKind.SUPPORTED_FLOOR, "powered", "true", "shape", "east_west", "waterlogged", "false");
        assertEquals(Map.of("shape", "east_west"), rail.listed());
        StateSpec wire = spec("minecraft:redstone_wire", StateKind.SUPPORTED_FLOOR, "north", "side", "east", "none", "south", "up", "west", "none", "power", "9");
        assertTrue(wire.placeable());
        assertTrue(wire.matched().isEmpty());
    }

    @Test
    void connectionsPowerAndLeavesAreDerivedOnFullBlocks() {
        StateSpec pane = spec("minecraft:glass_pane", StateKind.FULL, "north", "true", "east", "false", "south", "true", "west", "false", "waterlogged", "false");
        assertTrue(pane.placeable());
        assertTrue(pane.matched().isEmpty());
        StateSpec leaves = spec("minecraft:oak_leaves", StateKind.FULL, "distance", "3", "persistent", "true", "waterlogged", "false");
        assertTrue(leaves.placeable());
        StateSpec grass = spec("minecraft:grass_block", StateKind.FULL, "snowy", "false");
        assertTrue(grass.placeable());
        StateSpec lamp = spec("minecraft:redstone_lamp", StateKind.FULL, "lit", "true");
        assertTrue(lamp.placeable());
        StateSpec hopper = spec("minecraft:hopper", StateKind.HOPPER, "facing", "east", "enabled", "false");
        assertEquals(Map.of("facing", "east"), hopper.matched());
        StateSpec crop = spec("minecraft:wheat", StateKind.SUPPORTED_FLOOR, "age", "7");
        assertTrue(crop.placeable());
        StateSpec bee = spec("minecraft:beehive", StateKind.HFACING, "facing", "west", "honey_level", "5");
        assertEquals(Map.of("facing", "west"), bee.matched());
        StateSpec furnace = spec("minecraft:furnace", StateKind.HFACING, "facing", "west", "lit", "true");
        assertEquals(Map.of("facing", "west"), furnace.matched());
    }

    @Test
    void theSkippedListHasTheRightReasons() {
        assertEquals(Action.SKIP, action(StateKind.FULL, "minecraft:end_portal_frame", "eye", "true"));
        assertEquals(Action.DERIVED, action(StateKind.HFACING, "minecraft:end_portal_frame", "eye", "false"));
        assertEquals(Action.SKIP, action(StateKind.FULL, "minecraft:cake", "bites", "2"));
        assertEquals(Action.DERIVED, action(StateKind.FULL, "minecraft:cake", "bites", "0"));
        assertEquals(Action.SKIP, action(StateKind.FACING6_LOOK, "minecraft:piston", "extended", "true"));
        assertEquals(Action.DERIVED, action(StateKind.FACING6_LOOK, "minecraft:piston", "extended", "false"));
        assertEquals(Action.SKIP, action(StateKind.HOPPER, "minecraft:hopper", "facing", "up"));
        assertEquals(Action.SKIP, action(StateKind.HFACING, "minecraft:furnace", "facing", "up"));
        assertEquals(Action.SKIP, action(StateKind.FULL, "minecraft:light", "level", "7"));
    }

    @Test
    void everySkipRuleCarriesAReason() {
        String[] props = { "waterlogged", "eye", "bites", "extended", "facing", "type", "open", "level", "flower_amount", "whatever" };
        String[] values = { "true", "false", "0", "3", "up", "left", "x" };
        for (StateKind k : StateKind.values()) {
            for (String p : props) {
                for (String v : values) {
                    PropertyRules.Rule r = PropertyRules.rule(k, "minecraft:iron_door", p, v);
                    assertNotNull(r);
                    if (r.action() == Action.SKIP) assertFalse(r.reason().isBlank(), k + " " + p + "=" + v);
                }
            }
        }
    }

    @Test
    void unknownPropertiesAreSkippedAndListedRatherThanGuessed() {
        StateSpec s = spec("minecraft:weird", StateKind.FULL, "flavour", "sweet");
        assertTrue(s.skipped());
        assertTrue(s.skipReason().contains("flavour"));
        assertNull(StateSpec.of("minecraft:stone", StateKind.FULL, Map.of(), false).skipReason());
    }

    @Test
    void skippedSpecsAlwaysCarryAReason() {
        StateSpec s = StateSpec.skipped("minecraft:water", Map.of("level", "0"), "fluids cannot be placed");
        assertEquals(StateKind.SKIP, s.kind());
        assertEquals("fluids cannot be placed", s.skipReason());
        assertThrows(IllegalArgumentException.class, () -> StateSpec.of("minecraft:water", StateKind.SKIP, Map.of(), false));
        assertThrows(IllegalArgumentException.class, () -> new StateSpec("a", StateKind.SKIP, new java.util.TreeMap<>(), new java.util.TreeMap<>(), new java.util.TreeMap<>(), new java.util.TreeMap<>(), false, null));
        assertThrows(IllegalArgumentException.class, () -> new StateSpec("a", StateKind.FULL, new java.util.TreeMap<>(), new java.util.TreeMap<>(), new java.util.TreeMap<>(), new java.util.TreeMap<>(), false, "x"));
    }

    @Test
    void orderingMetadataFollowsTheKindAndProperties() {
        assertEquals(Dir.DOWN, spec("minecraft:oak_door", StateKind.DOOR, "facing", "north", "half", "lower", "hinge", "left").supportDir());
        assertEquals(Dir.UP, spec("minecraft:oak_door", StateKind.DOOR, "facing", "north", "half", "lower", "hinge", "left").partnerDir());
        assertEquals(Dir.EAST, spec("minecraft:red_bed", StateKind.BED, "facing", "east", "part", "foot").partnerDir());
        assertEquals(Dir.SOUTH, spec("minecraft:wall_torch", StateKind.WALL_OR_STANDING, "facing", "north").supportDir());
        assertEquals(Dir.WEST, spec("minecraft:stone_button", StateKind.ATTACH_FACE, "face", "wall", "facing", "east", "powered", "false").supportDir());
        assertEquals(Dir.UP, spec("minecraft:lever", StateKind.ATTACH_FACE, "face", "ceiling", "facing", "east").supportDir());
        assertEquals(Dir.UP, spec("minecraft:lantern", StateKind.HANGING, "hanging", "true").supportDir());
        assertEquals(Dir.DOWN, spec("minecraft:lantern", StateKind.HANGING, "hanging", "false").supportDir());
        assertEquals(Dir.EAST, spec("minecraft:bell", StateKind.BELL, "attachment", "single_wall", "facing", "east").supportDir());
        assertNull(spec("minecraft:stone", StateKind.FULL).supportDir());
        assertEquals(BuildOrderer.Role.SOLID, spec("minecraft:stone", StateKind.FULL).role());
        assertEquals(BuildOrderer.Role.ATTACHABLE, spec("minecraft:oak_pressure_plate", StateKind.SUPPORTED_FLOOR, "powered", "false").role());
        assertEquals(BuildOrderer.Role.GRAVITY, StateSpec.of("minecraft:sand", StateKind.FULL, Map.of(), true).role());
        // a gravity door half is not a thing: parts are never gravity
        assertEquals(BuildOrderer.Role.SOLID, StateSpec.of("minecraft:x", StateKind.FULL, Map.of(), false).role());
    }
}

package com.porter.qwerte.printer.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import com.porter.qwerte.printer.place.PlacementMapper.NeighbourView;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** The wall-attached blocks: the item is the floor item, the click is on the side face of the block they hang on. */
class WallMountedTest {
    private static NeighbourView view(Set<Dir> clickable) {
        return new NeighbourView() {
            @Override
            public boolean clickable(Dir d) {
                return clickable.contains(d);
            }

            @Override
            public boolean interactive(Dir d) {
                return false;
            }
        };
    }

    private static final NeighbourView ALL = view(EnumSet.allOf(Dir.class));

    private static StateSpec spec(String id, StateKind k, String... kv) {
        TreeMap<String, String> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return StateSpec.of(id, k, m, false);
    }

    private static List<PlaceRecipe> neighbourOnly(List<PlaceRecipe> rs) {
        return rs.stream().filter(r -> !r.airplace()).toList();
    }

    @Test
    void everyWallFamilyClicksTheSideFaceOfTheBlockBehindIt() {
        String[] ids = { "minecraft:redstone_wall_torch", "minecraft:wall_torch", "minecraft:soul_wall_torch", "minecraft:copper_wall_torch",
            "minecraft:oak_wall_sign", "minecraft:white_wall_banner", "minecraft:skeleton_wall_skull", "minecraft:player_wall_head",
            "minecraft:ladder", "minecraft:tripwire_hook", "minecraft:tube_coral_wall_fan" };
        for (String id : ids) {
            for (Dir f : Dir.HORIZONTALS) {
                StateSpec s = spec(id, StateKind.WALL_OR_STANDING, "facing", f.lower());
                assertEquals(f.opposite(), s.supportDir(), id);
                assertEquals(BuildOrderer.Role.ATTACHABLE, s.role(), id);
                List<PlaceRecipe> rs = neighbourOnly(PlacementMapper.recipes(s, ALL, true));
                assertEquals(1, rs.size(), id + " " + f);
                PlaceRecipe r = rs.get(0);
                assertEquals(f.opposite(), r.site(), id + " " + f);
                assertEquals(f, r.face(), id + " " + f); // the clicked face is the one the block will face
                // the hit lies on the plane shared with the block behind
                double[] at = r.hitAt(0, 0, 0);
                assertEquals(f.opposite().dx == 0 ? 0.5 : (f.opposite().dx > 0 ? 1 : 0), at[0], 1e-9, id + " x");
                assertEquals(0.5, at[1], 1e-9, id + " y");
                assertEquals(f.opposite().dz == 0 ? 0.5 : (f.opposite().dz > 0 ? 1 : 0), at[2], 1e-9, id + " z");
            }
        }
    }

    @Test
    void theRedstoneWallTorchMatchesItsFacingAndLeavesLitToTheGame() {
        StateSpec s = spec("minecraft:redstone_wall_torch", StateKind.WALL_OR_STANDING, "facing", "east", "lit", "true");
        assertTrue(s.placeable());
        assertEquals(java.util.Map.of("facing", "east"), s.matched());
        assertTrue(s.clicked().isEmpty());
        // the same block as a floor torch has no facing at all and is not a wall recipe
        StateSpec floor = spec("minecraft:redstone_torch", StateKind.SUPPORTED_FLOOR, "lit", "true");
        assertEquals(Dir.DOWN, floor.supportDir());
    }

    @Test
    void withoutAClickableBackingBlockTheWallBlockIsAirPlacedLookingAtTheSupport() {
        NeighbourView none = view(EnumSet.noneOf(Dir.class));
        StateSpec s = spec("minecraft:redstone_wall_torch", StateKind.WALL_OR_STANDING, "facing", "north", "lit", "true");
        assertTrue(PlacementMapper.recipes(s, none, false).isEmpty());
        PlaceRecipe air = PlacementMapper.recipes(s, none, true).get(0);
        assertTrue(air.airplace());
        assertEquals(Dir.NORTH, air.face());
        assertEquals(LookWindow.lookCone(Dir.SOUTH), air.look());
    }

    @Test
    void aTripwireHookIsAWallBlockWithItsSwitchesDerived() {
        StateSpec s = spec("minecraft:tripwire_hook", StateKind.WALL_OR_STANDING, "facing", "west", "attached", "false", "powered", "false");
        assertTrue(s.placeable());
        assertEquals(java.util.Map.of("facing", "west"), s.matched());
    }

    @Test
    void buttonsAndLeversOnWallsAndCeilingsClickTheirSupport() {
        PlaceRecipe wall = neighbourOnly(PlacementMapper.recipes(spec("minecraft:lever", StateKind.ATTACH_FACE, "face", "wall", "facing", "east", "powered", "false"), ALL, false)).get(0);
        assertEquals(Dir.WEST, wall.site());
        assertEquals(Dir.EAST, wall.face());
        PlaceRecipe ceiling = neighbourOnly(PlacementMapper.recipes(spec("minecraft:stone_button", StateKind.ATTACH_FACE, "face", "ceiling", "facing", "south", "powered", "false"), ALL, false)).get(0);
        assertEquals(Dir.UP, ceiling.site());
        assertEquals(Dir.DOWN, ceiling.face());
        assertEquals(LookWindow.quadrant(Dir.SOUTH), ceiling.look());
    }

    @Test
    void aWallHangingSignIsClickedBesideItNeverOnItsFacingAxis() {
        StateSpec s = spec("minecraft:oak_wall_hanging_sign", StateKind.WALL_HANGING, "facing", "north", "waterlogged", "false");
        assertTrue(s.placeable());
        assertEquals(java.util.Map.of("facing", "north"), s.matched());
        List<PlaceRecipe> rs = neighbourOnly(PlacementMapper.recipes(s, ALL, false));
        assertFalse(rs.isEmpty());
        for (PlaceRecipe r : rs) {
            assertFalse(r.site().axis() == 'z', "site " + r.site());
            assertEquals(LookWindow.quadrant(Dir.SOUTH), r.look()); // facing = look.opposite
        }
    }

    @Test
    void cocoaFacesTheLogItHangsOn() {
        StateSpec s = spec("minecraft:cocoa", StateKind.COCOA, "age", "0", "facing", "east");
        assertTrue(s.placeable());
        assertEquals(Dir.EAST, s.supportDir());
        PlaceRecipe r = neighbourOnly(PlacementMapper.recipes(s, ALL, false)).get(0);
        assertEquals(Dir.EAST, r.site());
        assertEquals(Dir.WEST, r.face()); // clicked on the log's side that looks at the pod
    }
}

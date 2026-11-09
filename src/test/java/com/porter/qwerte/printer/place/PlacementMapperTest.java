package com.porter.qwerte.printer.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import com.porter.qwerte.printer.place.PlacementMapper.NeighbourView;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PlacementMapperTest {
    private static NeighbourView view(Set<Dir> clickable, Set<Dir> interactive) {
        return new NeighbourView() {
            @Override
            public boolean clickable(Dir d) {
                return clickable.contains(d);
            }

            @Override
            public boolean interactive(Dir d) {
                return interactive.contains(d);
            }
        };
    }

    private static final NeighbourView ALL = view(EnumSet.allOf(Dir.class), EnumSet.noneOf(Dir.class));
    private static final NeighbourView FLOOR_ONLY = view(EnumSet.of(Dir.DOWN), EnumSet.noneOf(Dir.class));

    private static StateSpec spec(String id, StateKind k, String... kv) {
        TreeMap<String, String> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return StateSpec.of(id, k, m, false);
    }

    private static List<PlaceRecipe> neighbourOnly(List<PlaceRecipe> rs) {
        return rs.stream().filter(r -> !r.airplace()).toList();
    }

    private static PlaceRecipe at(List<PlaceRecipe> rs, Dir site) {
        return rs.stream().filter(r -> r.site() == site).findFirst().orElse(null);
    }

    @Test
    void skippedAndPartSpecsGetNoRecipes() {
        assertTrue(PlacementMapper.recipes(StateSpec.skipped("minecraft:water", Map.of(), "fluids"), ALL, true).isEmpty());
        assertTrue(PlacementMapper.recipes(spec("minecraft:oak_door", StateKind.DOOR, "facing", "north", "half", "upper", "hinge", "left"), ALL, true).isEmpty());
    }

    @Test
    void noClickableNeighbourAndNoAirplaceMeansNoRecipe() {
        NeighbourView none = view(EnumSet.noneOf(Dir.class), EnumSet.noneOf(Dir.class));
        assertTrue(PlacementMapper.recipes(spec("minecraft:stone", StateKind.FULL), none, false).isEmpty());
        List<PlaceRecipe> air = PlacementMapper.recipes(spec("minecraft:stone", StateKind.FULL), none, true);
        assertEquals(1, air.size());
        assertTrue(air.get(0).airplace());
    }

    @Test
    void neighbourClicksComeBeforeAirplaceAndPlainBeforeSneak() {
        NeighbourView v = view(EnumSet.of(Dir.DOWN, Dir.NORTH), EnumSet.of(Dir.DOWN));
        List<PlaceRecipe> rs = PlacementMapper.recipes(spec("minecraft:stone", StateKind.FULL), v, true);
        assertEquals(3, rs.size());
        assertEquals(Dir.NORTH, rs.get(0).site());
        assertFalse(rs.get(0).sneak());
        assertEquals(Dir.DOWN, rs.get(1).site());
        assertTrue(rs.get(1).sneak());
        assertTrue(rs.get(2).airplace());
    }

    @Test
    void everyRecipeIsInternallyConsistentForEveryKindAndBothViews() {
        String[][] cases = {
            { "minecraft:stone", "FULL" }, { "minecraft:oak_log", "AXIS", "axis", "x" }, { "minecraft:furnace", "HFACING", "facing", "east" },
            { "minecraft:campfire", "HFACING_LOOK", "facing", "west" }, { "minecraft:anvil", "HFACING_CW", "facing", "south" },
            { "minecraft:piston", "FACING6_LOOK", "facing", "up" }, { "minecraft:observer", "FACING6_LOOK_SAME", "facing", "down" },
            { "minecraft:end_rod", "FACING6_FACE", "facing", "east" }, { "minecraft:hopper", "HOPPER", "facing", "south" },
            { "minecraft:oak_stairs", "STAIRS", "facing", "east", "half", "top" }, { "minecraft:stone_slab", "SLAB", "type", "double" },
            { "minecraft:oak_trapdoor", "TRAPDOOR", "facing", "west", "half", "top", "open", "true" },
            { "minecraft:oak_door", "DOOR", "facing", "south", "half", "lower", "hinge", "right", "open", "true" },
            { "minecraft:red_bed", "BED", "facing", "north", "part", "foot" }, { "minecraft:oak_fence_gate", "FENCE_GATE", "facing", "east", "open", "true" },
            { "minecraft:lever", "ATTACH_FACE", "face", "floor", "facing", "west" }, { "minecraft:stone_button", "ATTACH_FACE", "face", "wall", "facing", "south" },
            { "minecraft:wall_torch", "WALL_OR_STANDING", "facing", "east" }, { "minecraft:oak_sign", "ROTATION16", "rotation", "5" },
            { "minecraft:lantern", "HANGING", "hanging", "true" }, { "minecraft:repeater", "REPEATER", "facing", "north", "delay", "4" },
            { "minecraft:comparator", "COMPARATOR", "facing", "south", "mode", "subtract" }, { "minecraft:note_block", "NOTE", "note", "7" },
            { "minecraft:daylight_detector", "DAYLIGHT", "inverted", "true" }, { "minecraft:candle", "COUNT", "candles", "4" },
            { "minecraft:bell", "BELL", "attachment", "ceiling", "facing", "north" }, { "minecraft:bell", "BELL", "attachment", "double_wall", "facing", "east" },
            { "minecraft:chest", "CHEST", "facing", "west", "type", "single" }, { "minecraft:carpet", "SUPPORTED_FLOOR" },
        };
        for (String[] c : cases) {
            String[] kv = new String[c.length - 2];
            System.arraycopy(c, 2, kv, 0, kv.length);
            StateKind kind = StateKind.valueOf(c[1]);
            StateSpec s = spec(c[0], kind, kv);
            assertTrue(s.placeable(), c[0]);
            for (NeighbourView v : List.of(ALL, FLOOR_ONLY)) {
                for (PlaceRecipe r : PlacementMapper.recipes(s, v, true)) {
                    String what = c[0] + " " + kind + " " + r;
                    assertNotNull(r.look(), what);
                    assertTrue(r.hx() >= 0 && r.hx() <= 1 && r.hy() >= 0 && r.hy() <= 1 && r.hz() >= 0 && r.hz() <= 1, what);
                    if (r.site() != null) {
                        assertEquals(r.site().opposite(), r.face(), what);
                        assertTrue(v.clickable(r.site()), what);
                        // the hit lies on the plane shared with the clicked block
                        double[] h = r.hitAt(0, 0, 0);
                        switch (r.site().axis()) {
                            case 'x' -> assertEquals(r.site() == Dir.EAST ? 1 : 0, h[0], 0, what);
                            case 'y' -> assertEquals(r.site() == Dir.UP ? 1 : 0, h[1], 0, what);
                            default -> assertEquals(r.site() == Dir.SOUTH ? 1 : 0, h[2], 0, what);
                        }
                    }
                }
                assertFalse(PlacementMapper.recipes(s, v, true).isEmpty(), c[0] + " " + kind + " should be placeable from " + v);
            }
        }
    }

    // ----- facing from the look -----

    @Test
    void furnaceFacingNorthNeedsTheSouthQuadrant() {
        PlaceRecipe r = PlacementMapper.recipes(spec("minecraft:furnace", StateKind.HFACING, "facing", "north", "lit", "false"), FLOOR_ONLY, false).get(0);
        assertEquals(Dir.DOWN, r.site());
        assertEquals(LookWindow.quadrant(Dir.SOUTH), r.look());
        assertTrue(r.look().contains(0, 0));
        assertFalse(r.look().contains(180, 0));
    }

    @Test
    void campfireFacesTheLookAndAnvilFacesItsClockwiseNeighbour() {
        PlaceRecipe c = PlacementMapper.recipes(spec("minecraft:campfire", StateKind.HFACING_LOOK, "facing", "west"), FLOOR_ONLY, false).get(0);
        assertEquals(LookWindow.quadrant(Dir.WEST), c.look());
        // anvil facing = H.clockwise: facing east needs H = north
        PlaceRecipe a = PlacementMapper.recipes(spec("minecraft:anvil", StateKind.HFACING_CW, "facing", "east"), FLOOR_ONLY, false).get(0);
        assertEquals(LookWindow.quadrant(Dir.NORTH), a.look());
    }

    @Test
    void pistonFacesTheOppositeOfTheLookAndObserverFacesTheLook() {
        PlaceRecipe piston = PlacementMapper.recipes(spec("minecraft:piston", StateKind.FACING6_LOOK, "facing", "north", "extended", "false"), FLOOR_ONLY, false).get(0);
        assertEquals(LookWindow.lookCone(Dir.SOUTH), piston.look()); // looking south puts the piston face north
        PlaceRecipe up = PlacementMapper.recipes(spec("minecraft:dropper", StateKind.FACING6_LOOK, "facing", "up", "triggered", "false"), FLOOR_ONLY, false).get(0);
        assertEquals(LookWindow.lookCone(Dir.DOWN), up.look()); // look down, it faces up
        PlaceRecipe obs = PlacementMapper.recipes(spec("minecraft:observer", StateKind.FACING6_LOOK_SAME, "facing", "north", "powered", "false"), FLOOR_ONLY, false).get(0);
        assertEquals(LookWindow.lookCone(Dir.NORTH), obs.look());
    }

    @Test
    void facingByFaceNeedsTheMatchingNeighbour() {
        // end rod facing east: the clicked face is east, so the clicked block is on the west side
        List<PlaceRecipe> rs = PlacementMapper.recipes(spec("minecraft:end_rod", StateKind.FACING6_FACE, "facing", "east"), ALL, false);
        assertEquals(1, rs.size());
        assertEquals(Dir.WEST, rs.get(0).site());
        assertEquals(Dir.EAST, rs.get(0).face());
        assertTrue(rs.get(0).look().fullYaw());
        // with only a floor to click the end rod cannot face east, unless air-placed
        assertTrue(PlacementMapper.recipes(spec("minecraft:end_rod", StateKind.FACING6_FACE, "facing", "east"), FLOOR_ONLY, false).isEmpty());
        assertEquals(Dir.EAST, PlacementMapper.recipes(spec("minecraft:end_rod", StateKind.FACING6_FACE, "facing", "east"), FLOOR_ONLY, true).get(0).face());
    }

    @Test
    void axisBlocksClickAFaceOnTheirAxis() {
        List<PlaceRecipe> rs = PlacementMapper.recipes(spec("minecraft:oak_log", StateKind.AXIS, "axis", "x"), ALL, false);
        assertEquals(Set.of(Dir.EAST, Dir.WEST), Set.copyOf(rs.stream().map(PlaceRecipe::site).toList()));
        assertTrue(PlacementMapper.recipes(spec("minecraft:oak_log", StateKind.AXIS, "axis", "x"), FLOOR_ONLY, false).isEmpty());
        assertEquals(Dir.EAST, PlacementMapper.recipes(spec("minecraft:oak_log", StateKind.AXIS, "axis", "x"), FLOOR_ONLY, true).get(0).face());
    }

    @Test
    void hoppersPointAtTheClickedBlockAndVerticalClicksGiveDown() {
        PlaceRecipe east = PlacementMapper.recipes(spec("minecraft:hopper", StateKind.HOPPER, "facing", "east", "enabled", "true"), ALL, false).get(0);
        assertEquals(Dir.EAST, east.site());
        assertEquals(Dir.WEST, east.face());
        Set<Dir> down = Set.copyOf(PlacementMapper.recipes(spec("minecraft:hopper", StateKind.HOPPER, "facing", "down"), ALL, false).stream().map(PlaceRecipe::site).toList());
        assertEquals(Set.of(Dir.UP, Dir.DOWN), down);
    }

    // ----- halves -----

    @Test
    void stairsHalfComesFromTheFaceOrTheClickHeight() {
        StateSpec top = spec("minecraft:oak_stairs", StateKind.STAIRS, "facing", "north", "half", "top", "shape", "straight");
        List<PlaceRecipe> rs = PlacementMapper.recipes(top, ALL, true);
        PlaceRecipe above = at(rs, Dir.UP);
        assertNotNull(above); // face DOWN on the block above gives the top half
        assertEquals(0.5, above.hx());
        assertEquals(1.0, above.hy());
        assertNull(at(rs, Dir.DOWN)); // face UP would give the bottom half
        PlaceRecipe side = at(rs, Dir.EAST);
        assertEquals(0.75, side.hy()); // side face, upper half of the face
        assertEquals(1.0, side.hx()); // plane shared with the east neighbour
        assertEquals(LookWindow.quadrant(Dir.NORTH), side.look()); // stairs facing = look
        PlaceRecipe air = rs.get(rs.size() - 1);
        assertTrue(air.airplace());
        assertEquals(Dir.DOWN, air.face());

        List<PlaceRecipe> bottom = PlacementMapper.recipes(spec("minecraft:oak_stairs", StateKind.STAIRS, "facing", "north", "half", "bottom"), ALL, true);
        assertNotNull(at(bottom, Dir.DOWN));
        assertNull(at(bottom, Dir.UP));
        assertEquals(0.25, at(bottom, Dir.NORTH).hy());
        assertEquals(0.0, at(bottom, Dir.NORTH).hz()); // north neighbour: plane at z = 0
        assertEquals(Dir.UP, bottom.get(bottom.size() - 1).face());
    }

    @Test
    void sideClicksOnZPlanesPutTheHeightOnTheYCoordinate() {
        List<PlaceRecipe> rs = PlacementMapper.recipes(spec("minecraft:stone_slab", StateKind.SLAB, "type", "top"), ALL, false);
        PlaceRecipe south = at(rs, Dir.SOUTH);
        assertEquals(0.5, south.hx());
        assertEquals(0.75, south.hy());
        assertEquals(1.0, south.hz());
        PlaceRecipe west = at(rs, Dir.WEST);
        assertEquals(0.0, west.hx());
        assertEquals(0.75, west.hy());
        assertEquals(0.5, west.hz());
    }

    @Test
    void doubleSlabIsABottomSlabPlusASecondPlacementOnItself() {
        List<PlaceRecipe> rs = PlacementMapper.recipes(spec("minecraft:stone_slab", StateKind.SLAB, "type", "double", "waterlogged", "false"), ALL, true);
        assertFalse(rs.isEmpty());
        for (PlaceRecipe r : rs) assertEquals(1, r.extraPlaces());
        assertNotNull(at(rs, Dir.DOWN));
        assertNull(at(rs, Dir.UP));
        List<PlaceRecipe> single = PlacementMapper.recipes(spec("minecraft:stone_slab", StateKind.SLAB, "type", "bottom"), ALL, true);
        for (PlaceRecipe r : single) assertEquals(0, r.extraPlaces());
    }

    @Test
    void trapdoorSideClickFacesTheClickedFaceAndFloorClickFacesTheOppositeOfTheLook() {
        StateSpec s = spec("minecraft:oak_trapdoor", StateKind.TRAPDOOR, "facing", "north", "half", "top", "open", "true", "powered", "false", "waterlogged", "false");
        List<PlaceRecipe> rs = PlacementMapper.recipes(s, ALL, true);
        PlaceRecipe side = at(rs, Dir.SOUTH); // facing north = clicked face north = clicked block on the south side
        assertEquals(Dir.NORTH, side.face());
        assertEquals(0.75, side.hy());
        assertTrue(side.look().fullYaw());
        assertEquals(1, side.useClicks()); // open by a use click
        PlaceRecipe ceiling = at(rs, Dir.UP); // top half from a face-DOWN click
        assertEquals(LookWindow.quadrant(Dir.SOUTH), ceiling.look()); // facing = H.opposite, so H = south
        assertNull(at(rs, Dir.DOWN));
        assertEquals(Dir.DOWN, rs.get(rs.size() - 1).face()); // air-place: top half
        // a bottom trapdoor
        List<PlaceRecipe> low = PlacementMapper.recipes(spec("minecraft:oak_trapdoor", StateKind.TRAPDOOR, "facing", "east", "half", "bottom", "open", "false"), ALL, false);
        assertEquals(0.25, at(low, Dir.WEST).hy());
        assertEquals(0, at(low, Dir.WEST).useClicks());
        assertNotNull(at(low, Dir.DOWN));
        assertEquals(LookWindow.quadrant(Dir.WEST), at(low, Dir.DOWN).look());
    }

    @Test
    void ironTrapdoorsAreNeverOpenedByAClick() {
        StateSpec s = spec("minecraft:iron_trapdoor", StateKind.TRAPDOOR, "facing", "north", "half", "bottom", "open", "false");
        for (PlaceRecipe r : PlacementMapper.recipes(s, ALL, true)) assertEquals(0, r.useClicks());
    }

    // ----- doors -----

    @Test
    void doorHingeComesFromTheClickOffsetAndMatchesVanillasRule() {
        // vanilla: facing south (stepZ > 0): right when the click is on the west half (dx < 0.5), else left
        assertEquals("right", PlacementMapper.hingeByClick(Dir.SOUTH, 0.25, 0.5));
        assertEquals("left", PlacementMapper.hingeByClick(Dir.SOUTH, 0.75, 0.5));
        assertEquals("right", PlacementMapper.hingeByClick(Dir.NORTH, 0.75, 0.5));
        assertEquals("left", PlacementMapper.hingeByClick(Dir.NORTH, 0.25, 0.5));
        assertEquals("right", PlacementMapper.hingeByClick(Dir.EAST, 0.5, 0.75));
        assertEquals("left", PlacementMapper.hingeByClick(Dir.EAST, 0.5, 0.25));
        assertEquals("right", PlacementMapper.hingeByClick(Dir.WEST, 0.5, 0.25));
        assertEquals("left", PlacementMapper.hingeByClick(Dir.WEST, 0.5, 0.75));
        for (Dir f : Dir.HORIZONTALS) {
            for (String hinge : new String[] { "left", "right" }) {
                StateSpec s = spec("minecraft:oak_door", StateKind.DOOR, "facing", f.lower(), "half", "lower", "hinge", hinge, "open", "true", "powered", "false");
                List<PlaceRecipe> rs = PlacementMapper.recipes(s, ALL, true);
                assertFalse(rs.isEmpty(), f + " " + hinge);
                for (PlaceRecipe r : rs) {
                    assertEquals(hinge, PlacementMapper.hingeByClick(f, r.hx(), r.hz()), f + " " + hinge + " " + r);
                    assertEquals(LookWindow.quadrant(f), r.look()); // door facing = look
                    assertEquals(1, r.useClicks());
                    assertTrue(r.site() == Dir.DOWN || r.airplace());
                }
            }
        }
    }

    @Test
    void doorsNeedTheBlockBelowToBeClickableUnlessAirPlaced() {
        StateSpec s = spec("minecraft:oak_door", StateKind.DOOR, "facing", "north", "half", "lower", "hinge", "left", "open", "false");
        NeighbourView side = view(EnumSet.of(Dir.NORTH), EnumSet.noneOf(Dir.class));
        assertTrue(PlacementMapper.recipes(s, side, false).isEmpty());
        assertTrue(PlacementMapper.recipes(s, side, true).stream().allMatch(PlaceRecipe::airplace));
    }

    @Test
    void fenceGatesAndBedsFaceTheLook() {
        PlaceRecipe g = PlacementMapper.recipes(spec("minecraft:oak_fence_gate", StateKind.FENCE_GATE, "facing", "south", "open", "true", "in_wall", "false"), FLOOR_ONLY, false).get(0);
        assertEquals(LookWindow.quadrant(Dir.SOUTH), g.look());
        assertEquals(1, g.useClicks());
        PlaceRecipe bed = PlacementMapper.recipes(spec("minecraft:red_bed", StateKind.BED, "facing", "east", "part", "foot"), FLOOR_ONLY, false).get(0);
        assertEquals(LookWindow.quadrant(Dir.EAST), bed.look());
    }

    // ----- attached blocks -----

    @Test
    void attachFaceFloorAndCeilingFaceTheLookWallsFollowTheClickedFace() {
        PlaceRecipe floor = PlacementMapper.recipes(spec("minecraft:lever", StateKind.ATTACH_FACE, "face", "floor", "facing", "west", "powered", "false"), ALL, false).get(0);
        assertEquals(Dir.DOWN, floor.site());
        assertEquals(LookWindow.quadrant(Dir.WEST), floor.look());
        PlaceRecipe ceiling = PlacementMapper.recipes(spec("minecraft:stone_button", StateKind.ATTACH_FACE, "face", "ceiling", "facing", "north"), ALL, false).get(0);
        assertEquals(Dir.UP, ceiling.site());
        assertEquals(LookWindow.quadrant(Dir.NORTH), ceiling.look());
        // a wall button facing north is on the south block's north face
        PlaceRecipe wall = PlacementMapper.recipes(spec("minecraft:stone_button", StateKind.ATTACH_FACE, "face", "wall", "facing", "north"), ALL, false).get(0);
        assertEquals(Dir.SOUTH, wall.site());
        assertEquals(Dir.NORTH, wall.face());
        assertTrue(wall.look().fullYaw());
    }

    @Test
    void airPlacedAttachBlocksNeedToLookAtTheirSupport() {
        NeighbourView none = view(EnumSet.noneOf(Dir.class), EnumSet.noneOf(Dir.class));
        PlaceRecipe floor = PlacementMapper.recipes(spec("minecraft:lever", StateKind.ATTACH_FACE, "face", "floor", "facing", "west"), none, true).get(0);
        assertTrue(floor.airplace());
        LookWindow expect = LookWindow.quadrant(Dir.WEST).intersect(LookWindow.lookCone(Dir.DOWN));
        assertEquals(expect, floor.look());
        PlaceRecipe wall = PlacementMapper.recipes(spec("minecraft:wall_torch", StateKind.WALL_OR_STANDING, "facing", "east"), none, true).get(0);
        assertEquals(LookWindow.lookCone(Dir.WEST), wall.look()); // the support of an east-facing torch is on the west
    }

    @Test
    void wallMountedBlocksClickTheBlockBehindThem() {
        PlaceRecipe torch = PlacementMapper.recipes(spec("minecraft:wall_torch", StateKind.WALL_OR_STANDING, "facing", "east"), ALL, false).get(0);
        assertEquals(Dir.WEST, torch.site());
        assertEquals(Dir.EAST, torch.face());
        assertEquals(1, PlacementMapper.recipes(spec("minecraft:ladder", StateKind.WALL_OR_STANDING, "facing", "south", "waterlogged", "false"), ALL, false).size());
    }

    @Test
    void signsAndBannersUseYawPlus180AndSkullsUseTheYaw() {
        PlaceRecipe sign = PlacementMapper.recipes(spec("minecraft:oak_sign", StateKind.ROTATION16, "rotation", "4", "waterlogged", "false"), FLOOR_ONLY, false).get(0);
        assertEquals(LookWindow.rotation16(4, 180), sign.look());
        assertEquals(LookWindow.wrap(4 * 22.5 + 180), sign.look().yawCenter(), 1e-9);
        assertEquals(-90, sign.look().yawCenter(), 1e-9);
        PlaceRecipe banner = PlacementMapper.recipes(spec("minecraft:white_banner", StateKind.ROTATION16, "rotation", "0"), FLOOR_ONLY, false).get(0);
        assertEquals(-180, banner.look().yawCenter(), 1e-9); // a banner at segment 0 is placed looking north
        PlaceRecipe skull = PlacementMapper.recipes(spec("minecraft:skeleton_skull", StateKind.ROTATION16, "rotation", "4", "powered", "false"), FLOOR_ONLY, false).get(0);
        assertEquals(LookWindow.rotation16(4, 0), skull.look());
        assertEquals(90, skull.look().yawCenter(), 1e-9);
        assertTrue(PlacementMapper.skullLike("minecraft:player_head"));
        assertTrue(PlacementMapper.skullLike("minecraft:wither_skeleton_skull"));
        assertFalse(PlacementMapper.skullLike("minecraft:oak_sign"));
    }

    @Test
    void lanternsHangFromTheBlockAboveOrStandOnTheOneBelow() {
        PlaceRecipe hang = PlacementMapper.recipes(spec("minecraft:lantern", StateKind.HANGING, "hanging", "true", "waterlogged", "false"), ALL, false).get(0);
        assertEquals(Dir.UP, hang.site());
        PlaceRecipe stand = PlacementMapper.recipes(spec("minecraft:lantern", StateKind.HANGING, "hanging", "false"), ALL, false).get(0);
        assertEquals(Dir.DOWN, stand.site());
        PlaceRecipe air = PlacementMapper.recipes(spec("minecraft:lantern", StateKind.HANGING, "hanging", "true"), view(EnumSet.noneOf(Dir.class), EnumSet.noneOf(Dir.class)), true).get(0);
        assertEquals(LookWindow.lookCone(Dir.UP), air.look());
    }

    @Test
    void bellsFloorCeilingAndWall() {
        PlaceRecipe floor = PlacementMapper.recipes(spec("minecraft:bell", StateKind.BELL, "attachment", "floor", "facing", "south", "powered", "false"), ALL, false).get(0);
        assertEquals(Dir.DOWN, floor.site());
        assertEquals(LookWindow.quadrant(Dir.SOUTH), floor.look());
        PlaceRecipe ceil = PlacementMapper.recipes(spec("minecraft:bell", StateKind.BELL, "attachment", "ceiling", "facing", "south"), ALL, false).get(0);
        assertEquals(Dir.UP, ceil.site());
        PlaceRecipe wall = PlacementMapper.recipes(spec("minecraft:bell", StateKind.BELL, "attachment", "single_wall", "facing", "east"), ALL, false).get(0);
        assertEquals(Dir.EAST, wall.site()); // vanilla: facing = clicked face opposite
    }

    // ----- clicks -----

    @Test
    void repeaterFacesTheOppositeOfTheLookAndTakesDelayMinusOneClicks() {
        PlaceRecipe r = PlacementMapper.recipes(spec("minecraft:repeater", StateKind.REPEATER, "facing", "north", "delay", "4", "locked", "false", "powered", "false"), FLOOR_ONLY, false).get(0);
        assertEquals(LookWindow.quadrant(Dir.SOUTH), r.look());
        assertEquals(3, r.useClicks());
        PlaceRecipe one = PlacementMapper.recipes(spec("minecraft:repeater", StateKind.REPEATER, "facing", "north", "delay", "1"), FLOOR_ONLY, false).get(0);
        assertEquals(0, one.useClicks());
    }

    @Test
    void comparatorModeAndDaylightAreOneClick() {
        assertEquals(1, PlacementMapper.recipes(spec("minecraft:comparator", StateKind.COMPARATOR, "facing", "east", "mode", "subtract"), FLOOR_ONLY, false).get(0).useClicks());
        assertEquals(0, PlacementMapper.recipes(spec("minecraft:comparator", StateKind.COMPARATOR, "facing", "east", "mode", "compare"), FLOOR_ONLY, false).get(0).useClicks());
        assertEquals(1, PlacementMapper.recipes(spec("minecraft:daylight_detector", StateKind.DAYLIGHT, "inverted", "true", "power", "0"), FLOOR_ONLY, false).get(0).useClicks());
    }

    @Test
    void noteBlocksClickTheirNoteAfterTheBlockBelowExists() {
        PlaceRecipe n = PlacementMapper.recipes(spec("minecraft:note_block", StateKind.NOTE, "note", "7", "instrument", "harp", "powered", "false"), ALL, false).get(0);
        assertEquals(7, n.useClicks());
        assertTrue(n.deferUse());
        PlaceRecipe zero = PlacementMapper.recipes(spec("minecraft:note_block", StateKind.NOTE, "note", "0"), ALL, false).get(0);
        assertEquals(0, zero.useClicks());
        assertFalse(zero.deferUse());
    }

    @Test
    void candlesPicklesEggsAndSnowTakeCountMinusOneExtraPlacements() {
        assertEquals(2, PlacementMapper.recipes(spec("minecraft:candle", StateKind.COUNT, "candles", "3", "lit", "false"), ALL, false).get(0).extraPlaces());
        assertEquals(3, PlacementMapper.recipes(spec("minecraft:sea_pickle", StateKind.COUNT, "pickles", "4"), ALL, false).get(0).extraPlaces());
        assertEquals(0, PlacementMapper.recipes(spec("minecraft:turtle_egg", StateKind.COUNT, "eggs", "1"), ALL, false).get(0).extraPlaces());
        PlaceRecipe snow = PlacementMapper.recipes(spec("minecraft:snow", StateKind.COUNT, "layers", "8"), ALL, false).get(0);
        assertEquals(7, snow.extraPlaces());
        assertEquals(Dir.DOWN, snow.site());
    }

    @Test
    void chestsAlwaysSneakSoTheyStaySingleAndFaceTheOppositeOfTheLook() {
        List<PlaceRecipe> rs = PlacementMapper.recipes(spec("minecraft:chest", StateKind.CHEST, "facing", "east", "type", "single", "waterlogged", "false"), ALL, true);
        assertFalse(rs.isEmpty());
        for (PlaceRecipe r : rs) {
            assertTrue(r.sneak());
            assertEquals(LookWindow.quadrant(Dir.WEST), r.look());
        }
    }

    @Test
    void interactiveNeighboursForceSneak() {
        NeighbourView v = view(EnumSet.of(Dir.DOWN), EnumSet.of(Dir.DOWN));
        assertTrue(PlacementMapper.recipes(spec("minecraft:stone", StateKind.FULL), v, false).get(0).sneak());
        assertFalse(PlacementMapper.recipes(spec("minecraft:stone", StateKind.FULL), ALL, true).get(0).sneak());
    }

    @Test
    void recipeRecordRejectsBrokenInput() {
        LookWindow any = LookWindow.any();
        assertThrows(IllegalArgumentException.class, () -> new PlaceRecipe(Dir.DOWN, Dir.DOWN, 0.5, 0, 0.5, any, false, 0, 0, false, ""));
        assertThrows(IllegalArgumentException.class, () -> new PlaceRecipe(Dir.DOWN, Dir.UP, 1.5, 0, 0.5, any, false, 0, 0, false, ""));
        assertThrows(IllegalArgumentException.class, () -> new PlaceRecipe(null, null, 0.5, 0, 0.5, any, false, 0, 0, false, ""));
        assertThrows(IllegalArgumentException.class, () -> new PlaceRecipe(null, Dir.UP, 0.5, 1, 0.5, any, false, -1, 0, false, ""));
        PlaceRecipe r = new PlaceRecipe(Dir.DOWN, Dir.UP, 0.5, 0, 0.5, null, false, 0, 0, false, null);
        assertTrue(r.look().fullYaw());
        assertArrayEqualsInts(new int[] { 3, 4, 5 }, r.clickedBlock(3, 5, 5));
        assertEquals(10.5, r.hitAt(10, 20, 30)[0]);
        assertEquals(20.0, r.hitAt(10, 20, 30)[1]);
    }

    private static void assertArrayEqualsInts(int[] expected, int[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}

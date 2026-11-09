package com.porter.qwerte.printer.schematic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.schematic.Transform.Mir;
import com.porter.qwerte.printer.schematic.Transform.Rot;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransformTest {
    private static SchematicData.Region region(int px, int py, int pz, int sx, int sy, int sz) {
        return new SchematicData.Region("r", px, py, pz, sx, sy, sz, new String[] { "minecraft:stone" }, new long[0], 2);
    }

    @Test
    void mirrorsNegateOneAxisAndRotationsFollowMinecraftsClockwise() {
        assertArrayEquals(new int[] { 1, 2, -3 }, Transform.apply(1, 2, 3, Mir.LEFT_RIGHT, Rot.NONE));
        assertArrayEquals(new int[] { -1, 2, 3 }, Transform.apply(1, 2, 3, Mir.FRONT_BACK, Rot.NONE));
        // clockwise seen from above: north (0,0,-1) turns to east (1,0,0), east to south, south to west
        assertArrayEquals(new int[] { 1, 0, 0 }, Transform.apply(0, 0, -1, Mir.NONE, Rot.CLOCKWISE_90));
        assertArrayEquals(new int[] { 0, 0, 1 }, Transform.apply(1, 0, 0, Mir.NONE, Rot.CLOCKWISE_90));
        assertArrayEquals(new int[] { -3, 2, 1 }, Transform.apply(1, 2, 3, Mir.NONE, Rot.CLOCKWISE_90));
        assertArrayEquals(new int[] { -1, 2, -3 }, Transform.apply(1, 2, 3, Mir.NONE, Rot.CLOCKWISE_180));
        assertArrayEquals(new int[] { 3, 2, -1 }, Transform.apply(1, 2, 3, Mir.NONE, Rot.COUNTERCLOCKWISE_90));
        // the mirror is applied before the rotation
        assertArrayEquals(new int[] { 3, 2, 1 }, Transform.apply(1, 2, 3, Mir.LEFT_RIGHT, Rot.CLOCKWISE_90));
    }

    @Test
    void twelveRotationMirrorPairsGiveEightDistinctMapsAndEachInvertsExactly() {
        Set<List<Integer>> maps = new HashSet<>();
        for (Rot r : Rot.values()) {
            for (Mir m : Mir.values()) {
                int[] a = Transform.apply(1, 0, 0, m, r), b = Transform.apply(0, 0, 1, m, r), c = Transform.apply(0, 1, 0, m, r);
                maps.add(List.of(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]));
                for (int[] p : new int[][] { { 0, 0, 0 }, { 3, -4, 5 }, { -7, 2, 1 } }) {
                    int[] w = Transform.apply(p[0], p[1], p[2], m, r);
                    assertArrayEquals(p, Transform.unapply(w[0], w[1], w[2], m, r), r + " " + m);
                }
            }
        }
        assertEquals(8, maps.size());
    }

    @Test
    void aRegionsBoxLandsWhereTheRotationAndMirrorSayForAllTwelvePairs() {
        // a 2 x 1 x 3 region at the origin, placed at (100, 64, 200): the box of world cells per pair
        Object[][] expected = {
            // rot, mir, minX, maxX, minZ, maxZ
            { Rot.NONE, Mir.NONE, 100, 101, 200, 202 },
            { Rot.NONE, Mir.LEFT_RIGHT, 100, 101, 198, 200 },
            { Rot.NONE, Mir.FRONT_BACK, 99, 100, 200, 202 },
            { Rot.CLOCKWISE_90, Mir.NONE, 98, 100, 200, 201 },
            { Rot.CLOCKWISE_90, Mir.LEFT_RIGHT, 100, 102, 200, 201 },
            { Rot.CLOCKWISE_90, Mir.FRONT_BACK, 98, 100, 199, 200 },
            { Rot.CLOCKWISE_180, Mir.NONE, 99, 100, 198, 200 },
            { Rot.CLOCKWISE_180, Mir.LEFT_RIGHT, 99, 100, 200, 202 },
            { Rot.CLOCKWISE_180, Mir.FRONT_BACK, 100, 101, 198, 200 },
            { Rot.COUNTERCLOCKWISE_90, Mir.NONE, 100, 102, 199, 200 },
            { Rot.COUNTERCLOCKWISE_90, Mir.LEFT_RIGHT, 98, 100, 199, 200 },
            { Rot.COUNTERCLOCKWISE_90, Mir.FRONT_BACK, 100, 102, 200, 201 },
        };
        SchematicData.Region r = region(0, 0, 0, 2, 1, 3);
        for (Object[] e : expected) {
            Transform t = Transform.of(r, 100, 64, 200, (Rot) e[0], (Mir) e[1]);
            int[] lo = { Integer.MAX_VALUE, Integer.MAX_VALUE }, hi = { Integer.MIN_VALUE, Integer.MIN_VALUE };
            Set<List<Integer>> cells = new HashSet<>();
            for (int x = 0; x < 2; x++) {
                for (int z = 0; z < 3; z++) {
                    int[] w = t.toWorld(x, 0, z);
                    assertEquals(64, w[1]);
                    cells.add(List.of(w[0], w[2]));
                    lo[0] = Math.min(lo[0], w[0]);
                    hi[0] = Math.max(hi[0], w[0]);
                    lo[1] = Math.min(lo[1], w[2]);
                    hi[1] = Math.max(hi[1], w[2]);
                    assertArrayEquals(new int[] { x, 0, z }, t.toLocal(w[0], w[1], w[2]));
                }
            }
            String label = e[0] + " " + e[1];
            assertEquals(6, cells.size(), label);
            assertEquals((int) e[2], lo[0], label);
            assertEquals((int) e[3], hi[0], label);
            assertEquals((int) e[4], lo[1], label);
            assertEquals((int) e[5], hi[1], label);
        }
    }

    @Test
    void theRegionsOffsetFromTheOriginIsTransformedByTheMainPlacementOnly() {
        // posMinRel (10, 0, 5): a main quarter turn moves it to (-5, 0, 10); cell (0,0,0) lands exactly there
        Transform t = new Transform(0, 0, 0, Rot.CLOCKWISE_90, Mir.NONE, Rot.NONE, Mir.NONE, 10, 0, 5);
        assertArrayEquals(new int[] { -5, 0, 10 }, t.toWorld(0, 0, 0));
        // a sub-region turn leaves the offset alone and turns the cells about their own minimum corner
        Transform s = new Transform(0, 0, 0, Rot.NONE, Mir.NONE, Rot.CLOCKWISE_90, Mir.NONE, 10, 0, 5);
        assertArrayEquals(new int[] { 10, 0, 5 }, s.toWorld(0, 0, 0));
        assertArrayEquals(new int[] { 10 - 2, 0, 5 + 1 }, s.toWorld(1, 0, 2));
    }

    @Test
    void everyMainAndSubRotationMirrorCombinationIsABijectionThatInverts() {
        int sx = 3, sy = 2, sz = 4;
        for (Rot mr : Rot.values()) {
            for (Mir mm : Mir.values()) {
                for (Rot sr : Rot.values()) {
                    for (Mir sm : Mir.values()) {
                        Transform t = new Transform(7, -3, 11, mr, mm, sr, sm, -2, 5, 9);
                        Set<List<Integer>> seen = new HashSet<>();
                        for (int x = 0; x < sx; x++) {
                            for (int y = 0; y < sy; y++) {
                                for (int z = 0; z < sz; z++) {
                                    int[] w = t.toWorld(x, y, z);
                                    assertTrue(seen.add(List.of(w[0], w[1], w[2])), "collision " + mr + mm + sr + sm);
                                    assertArrayEquals(new int[] { x, y, z }, t.toLocal(w[0], w[1], w[2]), "" + mr + mm + sr + sm);
                                }
                            }
                        }
                        assertEquals(sx * sy * sz, seen.size());
                    }
                }
            }
        }
    }

    @Test
    void aSubRegionMirrorSwapsItsAxisForStatesUnderAQuarterTurnOfTheMainPlacement() {
        Transform quarter = new Transform(0, 0, 0, Rot.CLOCKWISE_90, Mir.FRONT_BACK, Rot.NONE, Mir.LEFT_RIGHT, 0, 0, 0);
        assertArrayEquals(new Mir[] { Mir.FRONT_BACK, Mir.FRONT_BACK }, quarter.stateMirrors());
        Transform ccw = new Transform(0, 0, 0, Rot.COUNTERCLOCKWISE_90, Mir.NONE, Rot.NONE, Mir.FRONT_BACK, 0, 0, 0);
        assertArrayEquals(new Mir[] { Mir.NONE, Mir.LEFT_RIGHT }, ccw.stateMirrors());
        Transform half = new Transform(0, 0, 0, Rot.CLOCKWISE_180, Mir.NONE, Rot.NONE, Mir.LEFT_RIGHT, 0, 0, 0);
        assertArrayEquals(new Mir[] { Mir.NONE, Mir.LEFT_RIGHT }, half.stateMirrors());
        Transform none = new Transform(0, 0, 0, Rot.NONE, Mir.NONE, Rot.NONE, Mir.NONE, 0, 0, 0);
        assertArrayEquals(new Mir[] { Mir.NONE, Mir.NONE }, none.stateMirrors());
        // the positions are not swapped: the cell mapping uses the sub mirror as given
        Transform pos = new Transform(0, 0, 0, Rot.CLOCKWISE_90, Mir.NONE, Rot.NONE, Mir.LEFT_RIGHT, 0, 0, 0);
        assertArrayEquals(Transform.apply(Transform.apply(1, 0, 2, Mir.NONE, Rot.CLOCKWISE_90)[0], 0, Transform.apply(1, 0, 2, Mir.NONE, Rot.CLOCKWISE_90)[2], Mir.LEFT_RIGHT, Rot.NONE), pos.toWorld(1, 0, 2));
    }

    @Test
    void stateRotationAddsTheMainAndSubTurns() {
        assertEquals(Rot.CLOCKWISE_180, new Transform(0, 0, 0, Rot.CLOCKWISE_90, Mir.NONE, Rot.CLOCKWISE_90, Mir.NONE, 0, 0, 0).stateRotation());
        assertEquals(Rot.NONE, new Transform(0, 0, 0, Rot.CLOCKWISE_90, Mir.NONE, Rot.COUNTERCLOCKWISE_90, Mir.NONE, 0, 0, 0).stateRotation());
        assertEquals(Rot.CLOCKWISE_90, new Transform(0, 0, 0, Rot.CLOCKWISE_180, Mir.NONE, Rot.COUNTERCLOCKWISE_90, Mir.NONE, 0, 0, 0).stateRotation());
        assertEquals(Rot.COUNTERCLOCKWISE_90, new Transform(0, 0, 0, Rot.NONE, Mir.NONE, Rot.COUNTERCLOCKWISE_90, Mir.NONE, 0, 0, 0).stateRotation());
    }

    @Test
    void aNegativeSizeOnEachAxisPutsCellZeroAtTheMinimumCornerAndTheFarCellAtThePosition() {
        // position (5, 6, 7); an axis with a negative size runs from position + size + 1 up to the position
        SchematicData.Region x = region(5, 6, 7, -3, 2, 4);
        assertArrayEquals(new int[] { 3, 6, 7 }, Transform.of(x, 0, 0, 0, Rot.NONE, Mir.NONE).toWorld(0, 0, 0));
        assertArrayEquals(new int[] { 5, 7, 10 }, Transform.of(x, 0, 0, 0, Rot.NONE, Mir.NONE).toWorld(2, 1, 3));
        SchematicData.Region y = region(5, 6, 7, 3, -2, 4);
        assertArrayEquals(new int[] { 5, 5, 7 }, Transform.of(y, 0, 0, 0, Rot.NONE, Mir.NONE).toWorld(0, 0, 0));
        assertArrayEquals(new int[] { 7, 6, 10 }, Transform.of(y, 0, 0, 0, Rot.NONE, Mir.NONE).toWorld(2, 1, 3));
        SchematicData.Region z = region(5, 6, 7, 3, 2, -4);
        assertArrayEquals(new int[] { 5, 6, 4 }, Transform.of(z, 0, 0, 0, Rot.NONE, Mir.NONE).toWorld(0, 0, 0));
        assertArrayEquals(new int[] { 7, 7, 7 }, Transform.of(z, 0, 0, 0, Rot.NONE, Mir.NONE).toWorld(2, 1, 3));
        SchematicData.Region all = region(5, 6, 7, -3, -2, -4);
        assertArrayEquals(new int[] { 3, 5, 4 }, Transform.of(all, 0, 0, 0, Rot.NONE, Mir.NONE).toWorld(0, 0, 0));
        // and the whole box turns with the main rotation about the origin, not about the region
        assertArrayEquals(new int[] { -4, 5, 3 }, Transform.of(all, 0, 0, 0, Rot.CLOCKWISE_90, Mir.NONE).toWorld(0, 0, 0));
    }
}

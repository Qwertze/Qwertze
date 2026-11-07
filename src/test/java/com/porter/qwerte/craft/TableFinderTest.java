package com.porter.qwerte.craft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.craft.TableFinder.Decision;
import com.porter.qwerte.craft.TableFinder.Material;
import com.porter.qwerte.craft.TableFinder.Memory;
import com.porter.qwerte.craft.TableFinder.Pos;
import com.porter.qwerte.craft.TableFinder.Source;
import com.porter.qwerte.craft.TableFinder.Step;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TableFinderTest {
    private static TableFinder.World world(Set<Pos> tables, Pos found, boolean stock, Material craft) {
        return new TableFinder.World() {
            @Override public boolean isTable(Pos p) { return tables.contains(p); }
            @Override public Pos search() { return found; }
            @Override public boolean stockHasTable() { return stock; }
            @Override public Material craftMaterial() { return craft; }
        };
    }

    @Test
    void findsAPlacedTableWithinTheRadius() {
        Pos near = new Pos(3, 64, 0), far = new Pos(40, 64, 0);
        Pos hit = TableFinder.nearest(List.of(far, near), new Pos(0, 64, 0), p -> Math.abs(p.x()) <= 10);
        assertEquals(near, hit);
        Decision d = TableFinder.decide(null, world(Set.of(near), hit, true, Material.PLANKS));
        assertEquals(Step.FOUND, d.step());
        assertEquals(near, d.pos());
        assertEquals(Source.FOUND, d.source());
    }

    @Test
    void ignoresATableOutsideThePerimeterOrOneNobodyCanStandNextTo() {
        Pos outside = new Pos(40, 64, 0), boxedIn = new Pos(2, 64, 0);
        assertNull(TableFinder.nearest(List.of(outside), new Pos(0, 64, 0), p -> p.x() < 20));
        // Inside the perimeter, but no cell beside it to stand in: the next nearest is taken
        Pos ok = new Pos(9, 64, 0);
        assertEquals(ok, TableFinder.nearest(List.of(boxedIn, ok), new Pos(0, 64, 0), p -> !p.equals(boxedIn)));
        Decision d = TableFinder.decide(null, world(Set.of(outside), null, false, Material.NONE));
        assertEquals(Step.FAIL, d.step());
    }

    @Test
    void placesFromStockBeforeCrafting() {
        Decision d = TableFinder.decide(null, world(Set.of(), null, true, Material.PLANKS));
        assertEquals(Step.PLACE_FROM_STOCK, d.step());
        assertEquals(Source.PLACED, d.source());
    }

    @Test
    void craftsFromPlanks() {
        Decision d = TableFinder.decide(null, world(Set.of(), null, false, Material.PLANKS));
        assertEquals(Step.CRAFT, d.step());
        assertEquals(Material.PLANKS, d.material());
        assertEquals(Source.CRAFTED, d.source());
    }

    @Test
    void craftsFromLogs() {
        Decision d = TableFinder.decide(null, world(Set.of(), null, false, Material.LOGS));
        assertEquals(Step.CRAFT, d.step());
        assertEquals(Material.LOGS, d.material());
    }

    @Test
    void materialFollowsTheRecipesOfBothVersions() {
        for (String v : List.of("1.21.4", "1.21.11")) {
            Recipes r = Recipes.load(v);
            assertNotNull(r, v);
            // Any wood type: four planks of one kind, or one log of another kind
            assertEquals(Material.PLANKS, TableFinder.material(r, Map.of("minecraft:cherry_planks", 4L), Set.of()), v);
            assertEquals(Material.NONE, TableFinder.material(r, Map.of("minecraft:cherry_planks", 3L), Set.of()), v);
            assertEquals(Material.LOGS, TableFinder.material(r, Map.of("minecraft:spruce_log", 1L), Set.of()), v);
            assertEquals(Material.LOGS, TableFinder.material(r, Map.of("minecraft:stripped_crimson_stem", 1L), Set.of()), v);
            assertEquals(Material.PLANKS, TableFinder.material(r, Map.of("minecraft:oak_planks", 5L, "minecraft:oak_log", 3L), Set.of()), v);
            assertEquals(Material.NONE, TableFinder.material(r, Map.of("minecraft:cobblestone", 64L), Set.of()), v);
            // Items on the never-use list are left alone
            assertEquals(Material.NONE, TableFinder.material(r, Map.of("minecraft:oak_log", 4L), Set.of("minecraft:oak_log")), v);
            assertEquals(Material.NONE, TableFinder.material(null, Map.of("minecraft:oak_planks", 4L), Set.of()), v);
        }
    }

    @Test
    void failsWithTheExactReason() {
        Decision d = TableFinder.decide(null, world(Set.of(), null, false, Material.NONE));
        assertEquals(Step.FAIL, d.step());
        assertEquals("no crafting table available: no planks or logs in the stash", d.failure());
        assertTrue(d.failure().startsWith(TableFinder.NONE));
        assertEquals("no crafting table available", TableFinder.failure(null));
        assertEquals("no crafting table available: no room to place it", TableFinder.failure("no room to place it"));
        assertEquals(Source.NONE, d.source());
    }

    @Test
    void searchesAgainWhenTheRememberedTableIsGone() {
        Pos old = new Pos(1, 64, 1), fresh = new Pos(5, 64, 5);
        Decision d = TableFinder.decide(new Memory(old, Source.FOUND), world(Set.of(fresh), fresh, false, Material.NONE));
        assertTrue(d.forget());
        assertEquals(Step.FOUND, d.step());
        assertEquals(fresh, d.pos());
        Decision p = TableFinder.decide(new Memory(old, Source.CRAFTED), world(Set.of(), null, true, Material.NONE));
        assertTrue(p.forget());
        assertEquals(Step.PLACE_FROM_STOCK, p.step());
        Decision c = TableFinder.decide(new Memory(old, Source.PLACED), world(Set.of(), null, false, Material.LOGS));
        assertTrue(c.forget());
        assertEquals(Step.CRAFT, c.step());
    }

    @Test
    void aSetTableKeepsPriorityAndIsUsedAsIs() {
        Pos set = new Pos(1, 64, 1), other = new Pos(2, 64, 2);
        Decision d = TableFinder.decide(new Memory(set, Source.SET), world(Set.of(set, other), other, true, Material.PLANKS));
        assertEquals(Step.USE, d.step());
        assertEquals(set, d.pos());
        assertEquals(Source.SET, d.source());
        assertFalse(d.forget());
        // A remembered one keeps how it got there
        assertEquals(Source.CRAFTED, TableFinder.decide(new Memory(set, Source.CRAFTED), world(Set.of(set), null, false, Material.NONE)).source());
    }

    @Test
    void aShortLivedMemoDoesNotPlanAgainWithinItsTime() {
        TableFinder.Memo<String> m = new TableFinder.Memo<>();
        AtomicInteger calls = new AtomicInteger();
        assertEquals("a", m.get(0, 3_000, () -> { calls.incrementAndGet(); return "a"; }));
        assertEquals("a", m.get(2_999, 3_000, () -> { calls.incrementAndGet(); return "b"; }));
        assertEquals("b", m.get(3_000, 3_000, () -> { calls.incrementAndGet(); return "b"; }));
        m.reset();
        assertEquals("c", m.get(3_001, 3_000, () -> { calls.incrementAndGet(); return "c"; }));
        assertEquals(3, calls.get());
    }

    @Test
    void theSearchIsBoundedAndThrottled() {
        assertArrayEquals(new int[] { -10, 10 }, TableFinder.span(-10, 10, 0));
        int[] wide = TableFinder.span(-500, 500, 100);
        assertEquals(TableFinder.SEARCH_SPAN, wide[1] - wide[0] + 1);
        assertTrue(wide[0] <= 100 && wide[1] >= 100);
        int[] edge = TableFinder.span(0, 1000, 0);
        assertArrayEquals(new int[] { 0, 127 }, edge);

        TableFinder.Throttle t = new TableFinder.Throttle();
        AtomicInteger calls = new AtomicInteger();
        assertNull(t.get(1_000, () -> { calls.incrementAndGet(); return null; }));
        assertNull(t.get(5_000, () -> { calls.incrementAndGet(); return new Pos(1, 1, 1); }));
        assertEquals(1, calls.get());
        assertEquals(new Pos(1, 1, 1), t.get(11_000, () -> { calls.incrementAndGet(); return new Pos(1, 1, 1); }));
        assertEquals(2, calls.get());
    }
}

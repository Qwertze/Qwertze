package com.porter.qwerte.printer.materials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.materials.MaterialPlanner.Allocation;
import com.porter.qwerte.printer.materials.MaterialPlanner.Cost;
import com.porter.qwerte.printer.materials.MaterialPlanner.Line;
import com.porter.qwerte.printer.materials.MaterialPlanner.Source;
import com.porter.qwerte.printer.materials.MaterialPlanner.Supply;
import com.porter.qwerte.printer.materials.MaterialPlanner.Tally;
import com.porter.qwerte.printer.place.StateKind;
import com.porter.qwerte.printer.place.StateSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaterialPlannerTest {
    /** minecraft:x to minecraft:x, except for the blocks that are placed from another item or from none. */
    private static final MaterialPlanner.Items ITEMS = id -> switch (id) {
        case "minecraft:wall_torch" -> "minecraft:torch";
        case "minecraft:redstone_wire" -> "minecraft:redstone";
        case "minecraft:nether_portal", "minecraft:moving_piston" -> null;
        default -> id;
    };

    private static StateSpec spec(String id, StateKind kind, String... kv) {
        Map<String, String> props = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) props.put(kv[i], kv[i + 1]);
        return StateSpec.of(id, kind, props, false);
    }

    @Test
    void aPlainBlockCostsOneOfItsItem() {
        Cost c = MaterialPlanner.cost(spec("minecraft:stone", StateKind.FULL), ITEMS);
        assertEquals("minecraft:stone", c.item());
        assertEquals(1, c.count());
        assertTrue(!c.skipped() && !c.free());
    }

    @Test
    void theItemMayDifferFromTheBlock() {
        Cost c = MaterialPlanner.cost(spec("minecraft:wall_torch", StateKind.WALL_OR_STANDING, "facing", "north"), ITEMS);
        assertEquals("minecraft:torch", c.item());
    }

    @Test
    void aDoubleSlabCostsTwoAndASingleOneOne() {
        assertEquals(2, MaterialPlanner.cost(spec("minecraft:stone_slab", StateKind.SLAB, "type", "double", "waterlogged", "false"), ITEMS).count());
        assertEquals(1, MaterialPlanner.cost(spec("minecraft:stone_slab", StateKind.SLAB, "type", "top", "waterlogged", "false"), ITEMS).count());
        assertEquals(1, MaterialPlanner.cost(spec("minecraft:stone_slab", StateKind.SLAB, "type", "bottom", "waterlogged", "false"), ITEMS).count());
    }

    @Test
    void candlesPicklesEggsAndSnowCostTheirCount() {
        assertEquals(3, MaterialPlanner.cost(spec("minecraft:candle", StateKind.COUNT, "candles", "3", "lit", "false", "waterlogged", "false"), ITEMS).count());
        assertEquals(4, MaterialPlanner.cost(spec("minecraft:candle", StateKind.COUNT, "candles", "4"), ITEMS).count());
        assertEquals(2, MaterialPlanner.cost(spec("minecraft:sea_pickle", StateKind.COUNT, "pickles", "2"), ITEMS).count());
        assertEquals(4, MaterialPlanner.cost(spec("minecraft:turtle_egg", StateKind.COUNT, "eggs", "4"), ITEMS).count());
        assertEquals(5, MaterialPlanner.cost(spec("minecraft:snow", StateKind.COUNT, "layers", "5"), ITEMS).count());
        assertEquals(1, MaterialPlanner.cost(spec("minecraft:candle", StateKind.COUNT, "candles", "1"), ITEMS).count());
    }

    @Test
    void aBadCountFallsBackToOne() {
        assertEquals(1, MaterialPlanner.cost(spec("minecraft:candle", StateKind.COUNT, "candles", "many"), ITEMS).count());
        assertEquals(64, MaterialPlanner.cost(spec("minecraft:snow", StateKind.COUNT, "layers", "9999"), ITEMS).count());
    }

    @Test
    void partCellsCostNothing() {
        StateSpec top = spec("minecraft:oak_door", StateKind.DOOR, "half", "upper", "facing", "north", "hinge", "left", "open", "false", "powered", "false");
        assertTrue(top.part(), "the upper half of a door is a PART");
        Cost c = MaterialPlanner.cost(top, ITEMS);
        assertEquals(0, c.count());
        assertNull(c.item());
        assertTrue(c.free());

        StateSpec head = spec("minecraft:red_bed", StateKind.BED, "part", "head", "facing", "north", "occupied", "false");
        assertTrue(head.part());
        assertEquals(0, MaterialPlanner.cost(head, ITEMS).count());

        StateSpec foot = spec("minecraft:red_bed", StateKind.BED, "part", "foot", "facing", "north", "occupied", "false");
        assertEquals(1, MaterialPlanner.cost(foot, ITEMS).count());
    }

    @Test
    void skippedStatesAndBlocksWithoutAnItemAreCountedWithTheirReason() {
        Tally t = new Tally();
        t.add(spec("minecraft:stone", StateKind.FULL), ITEMS);
        t.add(StateSpec.skipped("minecraft:water", Map.of("level", "0"), "fluids cannot be placed"), ITEMS);
        t.add(StateSpec.skipped("minecraft:water", Map.of("level", "1"), "fluids cannot be placed"), ITEMS);
        Cost nope = t.add(spec("minecraft:nether_portal", StateKind.FULL), ITEMS);
        assertTrue(nope.skipped());
        assertEquals("minecraft:nether_portal has no item to place it with", nope.skip());

        assertEquals(Map.of("minecraft:stone", 1L), t.need());
        assertEquals(2, t.skipped().get("fluids cannot be placed"));
        assertEquals(1, t.skipped().get("minecraft:nether_portal has no item to place it with"));
        assertEquals(1, t.total());
    }

    @Test
    void theTallyAddsAndRemoves() {
        Tally t = new Tally();
        Cost a = t.add(spec("minecraft:stone", StateKind.FULL), ITEMS);
        t.add(spec("minecraft:stone", StateKind.FULL), ITEMS);
        Cost slab = t.add(spec("minecraft:stone_slab", StateKind.SLAB, "type", "double"), ITEMS);
        t.add(spec("minecraft:oak_door", StateKind.DOOR, "half", "upper"), ITEMS);
        assertEquals(2, t.needOf("minecraft:stone"));
        assertEquals(2, t.needOf("minecraft:stone_slab"));
        t.remove(a);
        t.remove(slab);
        assertEquals(1, t.needOf("minecraft:stone"));
        assertEquals(0, t.needOf("minecraft:stone_slab"));
        assertTrue(!t.need().containsKey("minecraft:stone_slab"));
        t.remove(null);
        t.remove(MaterialPlanner.cost(StateSpec.skipped("minecraft:water", Map.of(), "x"), ITEMS));
        assertEquals(1, t.total());
    }

    @Test
    void needMinusHave() {
        Map<String, Long> need = new LinkedHashMap<>();
        need.put("minecraft:stone", 100L);
        need.put("minecraft:glass", 30L);
        need.put("minecraft:torch", 8L);
        Map<String, Long> have = Map.of("minecraft:stone", 40L, "minecraft:glass", 64L, "minecraft:dirt", 500L);

        List<Line> lines = MaterialPlanner.lines(need, have);
        // the biggest shortfall first, then by id
        assertEquals(List.of("minecraft:stone", "minecraft:torch", "minecraft:glass"), lines.stream().map(Line::item).toList());
        assertEquals(new Line("minecraft:stone", 100, 40, 60), lines.get(0));
        assertEquals(new Line("minecraft:torch", 8, 0, 8), lines.get(1));
        assertEquals(new Line("minecraft:glass", 30, 64, 0), lines.get(2));

        Map<String, Long> missing = MaterialPlanner.missing(need, have);
        assertEquals(Map.of("minecraft:stone", 60L, "minecraft:torch", 8L), missing);
    }

    @Test
    void sourcesAreUsedInTheOrderGiven() {
        Map<String, Long> missing = new LinkedHashMap<>();
        missing.put("minecraft:stone", 100L);
        missing.put("minecraft:glass", 10L);
        missing.put("minecraft:torch", 5L);
        Source shulkers = new Source("shulkers", Map.of("minecraft:stone", 64L));
        Source chests = new Source("containers", Map.of("minecraft:stone", 200L, "minecraft:glass", 4L));
        Source stash = new Source("stash", Map.of("minecraft:glass", 100L));

        Supply s = MaterialPlanner.allocate(missing, List.of(shulkers, chests, stash));
        assertEquals(List.of(
            new Allocation("shulkers", "minecraft:stone", 64),
            new Allocation("containers", "minecraft:stone", 36),
            new Allocation("containers", "minecraft:glass", 4),
            new Allocation("stash", "minecraft:glass", 6)), s.allocations());
        assertEquals(Map.of("minecraft:torch", 5L), s.unmet());

        // another order, another split
        Supply s2 = MaterialPlanner.allocate(Map.of("minecraft:stone", 100L), List.of(chests, shulkers));
        assertEquals(List.of(new Allocation("containers", "minecraft:stone", 100)), s2.allocations());
        assertTrue(s2.unmet().isEmpty());
    }

    @Test
    void nothingIsAllocatedFromEmptySources() {
        Supply s = MaterialPlanner.allocate(Map.of("minecraft:stone", 5L), List.of());
        assertTrue(s.allocations().isEmpty());
        assertEquals(Map.of("minecraft:stone", 5L), s.unmet());
    }
}

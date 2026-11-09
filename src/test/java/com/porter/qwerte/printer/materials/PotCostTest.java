package com.porter.qwerte.printer.materials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.printer.materials.MaterialPlanner.Cost;
import com.porter.qwerte.printer.materials.MaterialPlanner.Tally;
import com.porter.qwerte.printer.place.StateKind;
import com.porter.qwerte.printer.place.StateSpec;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A potted plant costs the empty pot and the plant; farmland costs dirt. */
class PotCostTest {
    private static final MaterialPlanner.Items ITEMS = id -> switch (id) {
        case "minecraft:potted_cactus" -> "minecraft:flower_pot";
        case "minecraft:farmland" -> "minecraft:dirt";
        default -> id;
    };

    @Test
    void aPottedPlantCountsThePotAndThePlant() {
        Cost base = MaterialPlanner.cost(StateSpec.of("minecraft:potted_cactus", StateKind.FULL, Map.of(), false), ITEMS);
        assertEquals("minecraft:flower_pot", base.item());
        Cost c = base.withExtra("minecraft:cactus", 1);
        Tally t = new Tally();
        t.add(c);
        t.add(c);
        assertEquals(2, t.needOf("minecraft:flower_pot"));
        assertEquals(2, t.needOf("minecraft:cactus"));
        assertEquals(4, t.total());
        t.remove(c);
        assertEquals(1, t.needOf("minecraft:flower_pot"));
        assertEquals(1, t.needOf("minecraft:cactus"));
        t.remove(c);
        assertTrue(t.need().isEmpty());
    }

    @Test
    void theShortfallListsThePlantToo() {
        Tally t = new Tally();
        t.add(new Cost("minecraft:flower_pot", 1, null).withExtra("minecraft:fern", 1));
        Map<String, Long> missing = MaterialPlanner.missing(t.need(), Map.of("minecraft:flower_pot", 1L));
        assertEquals(Map.of("minecraft:fern", 1L), missing);
    }

    @Test
    void farmlandIsCountedAsDirt() {
        Cost c = MaterialPlanner.cost(StateSpec.of("minecraft:farmland", StateKind.FULL, Map.of("moisture", "7"), false), ITEMS);
        assertEquals("minecraft:dirt", c.item());
        assertEquals(1, c.count());
        assertTrue(!c.skipped());
    }
}

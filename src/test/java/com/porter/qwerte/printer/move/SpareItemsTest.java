package com.porter.qwerte.printer.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpareItemsTest {
    private static List<String> pick(Map<String, Integer> have, Set<String> build, Set<String> prot, Set<String> spare) {
        return SpareItems.pick(have, build::contains, prot::contains, spare::contains, s -> s);
    }

    @Test
    void mostPlentifulFirstThenByName() {
        Map<String, Integer> have = new LinkedHashMap<>();
        have.put("minecraft:stone", 10);
        have.put("minecraft:dirt", 64);
        have.put("minecraft:cobblestone", 64);
        assertEquals(List.of("minecraft:cobblestone", "minecraft:dirt", "minecraft:stone"),
            pick(have, Set.of(), Set.of(), Set.of("minecraft:stone", "minecraft:dirt", "minecraft:cobblestone")));
    }

    @Test
    void buildItemsProtectedItemsAndNonSpareBlocksAreLeftOut() {
        Map<String, Integer> have = new LinkedHashMap<>();
        have.put("a", 5);
        have.put("b", 5);
        have.put("c", 5);
        have.put("d", 5);
        assertEquals(List.of("d"), pick(have, Set.of("a"), Set.of("b"), Set.of("a", "b", "d")));
    }

    @Test
    void emptyStacksAreLeftOut() {
        Map<String, Integer> have = new LinkedHashMap<>();
        have.put("a", 0);
        have.put("b", 1);
        assertEquals(List.of("b"), pick(have, Set.of(), Set.of(), Set.of("a", "b")));
        assertTrue(pick(Map.of(), Set.of(), Set.of(), Set.of("a")).isEmpty());
    }

    @Test
    void totalCountsOnlyTheSpareOnes() {
        Map<String, Integer> have = Map.of("a", 5, "b", 7, "c", 100);
        assertEquals(12, SpareItems.total(have, List.of("a", "b")));
        assertEquals(0, SpareItems.total(have, List.of()));
    }
}

package com.porter.qwerte.core.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataTablesTest {
    private final McData v4 = McData.load("1.21.4");
    private final McData v11 = McData.load("1.21.11");

    @Test
    void bothTablesLoad() {
        assertEquals(42, v4.enchantments().size());
        assertEquals(43, v11.enchantments().size());
        assertEquals("1.21.4", v4.mc());
        assertEquals("1.21.11", v11.mc());
        assertTrue(v4.source().contains("data pack"));
        assertThrows(IllegalArgumentException.class, () -> McData.load("1.20.1"));
        assertThrows(IllegalArgumentException.class, () -> McData.load("../x"));
    }

    @Test
    void lungeExistsOnlyIn12111() {
        assertNull(v4.get("lunge"));
        assertNotNull(v11.get("lunge"));
        for (String id : v4.enchantments().keySet()) {
            assertNotNull(v11.get(id), id + " must exist in both");
        }
        McData.Enchantment lunge = v11.get("lunge");
        assertEquals(3, lunge.maxLevel());
        assertEquals(2, lunge.itemMult());
        assertEquals(1, lunge.bookMult());
        assertEquals(null, lunge.exclusiveSet(), "Lunge has no exclusive set");
        for (String other : v11.enchantments().keySet()) {
            assertFalse(v11.conflicts("lunge", other), "lunge conflicts with " + other);
        }
        assertEquals(List.of("copper_spear", "diamond_spear", "golden_spear", "iron_spear", "netherite_spear", "stone_spear",
                "wooden_spear"), new ArrayList<>(v11.itemsFor("lunge")));
        assertFalse(v11.supports("lunge", "diamond_sword"));
    }

    @Test
    void sharedEnchantmentsMatchExceptWhichItemsTakeThem() {
        for (McData.Enchantment a : v4.enchantments().values()) {
            McData.Enchantment b = v11.get(a.id());
            assertEquals(a.maxLevel(), b.maxLevel(), a.id());
            assertEquals(a.itemMult(), b.itemMult(), a.id());
            assertEquals(a.bookMult(), b.bookMult(), a.id());
            assertEquals(a.exclusiveSet(), b.exclusiveSet(), a.id());
            assertEquals(Math.max(1, a.itemMult() / 2), a.bookMult(), a.id());
        }
        assertEquals(v4.exclusiveSets(), v11.exclusiveSets());
    }

    @Test
    void meleeEnchantsTakeSpearsOnlyIn12111() {
        for (String id : List.of("sharpness", "smite", "bane_of_arthropods", "fire_aspect", "knockback", "looting")) {
            assertTrue(v11.supports(id, "diamond_spear"), id + " on a spear in 1.21.11");
            assertTrue(v11.supports(id, "diamond_sword"), id + " on a sword in 1.21.11");
            assertFalse(v4.supports(id, "diamond_spear"), id + " spears do not exist in 1.21.4");
            assertTrue(v4.supports(id, "diamond_sword"), id + " on a sword in 1.21.4");
        }
        assertTrue(v11.supports("sweeping_edge", "diamond_sword"));
        assertFalse(v11.supports("sweeping_edge", "diamond_spear"), "Sweeping Edge stays swords only");
        // copper tools and armour are new items in 1.21.11
        assertTrue(v11.supports("efficiency", "copper_pickaxe"));
        assertFalse(v4.supports("efficiency", "copper_pickaxe"));
    }

    @Test
    void multipliersMatchThePlanTable() {
        Map<String, int[]> expected = Map.ofEntries(
                Map.entry("sharpness", new int[]{5, 1, 1}), Map.entry("efficiency", new int[]{5, 1, 1}),
                Map.entry("protection", new int[]{4, 1, 1}), Map.entry("unbreaking", new int[]{3, 2, 1}),
                Map.entry("knockback", new int[]{2, 2, 1}), Map.entry("looting", new int[]{3, 4, 2}),
                Map.entry("mending", new int[]{1, 4, 2}), Map.entry("sweeping_edge", new int[]{3, 4, 2}),
                Map.entry("silk_touch", new int[]{1, 8, 4}), Map.entry("thorns", new int[]{3, 8, 4}),
                Map.entry("binding_curse", new int[]{1, 8, 4}), Map.entry("fortune", new int[]{3, 4, 2}));
        for (McData d : List.of(v4, v11)) {
            for (Map.Entry<String, int[]> e : expected.entrySet()) {
                McData.Enchantment en = d.get(e.getKey());
                assertEquals(e.getValue()[0], en.maxLevel(), e.getKey());
                assertEquals(e.getValue()[1], en.itemMult(), e.getKey());
                assertEquals(e.getValue()[2], en.bookMult(), e.getKey());
            }
        }
    }

    @Test
    void exclusiveSetsMatchThePlan() {
        for (McData d : List.of(v4, v11)) {
            assertConflicts(d, List.of("sharpness", "smite", "bane_of_arthropods", "impaling", "density", "breach"));
            assertConflicts(d, List.of("protection", "blast_protection", "fire_protection", "projectile_protection"));
            assertConflicts(d, List.of("frost_walker", "depth_strider"));
            assertConflicts(d, List.of("infinity", "mending"));
            assertConflicts(d, List.of("riptide", "loyalty"));
            assertConflicts(d, List.of("riptide", "channeling"));
            assertFalse(d.conflicts("loyalty", "channeling"), "loyalty and channeling are compatible");
            assertConflicts(d, List.of("fortune", "silk_touch"));
            assertConflicts(d, List.of("multishot", "piercing"));
            assertFalse(d.conflicts("sharpness", "sharpness"));
            assertFalse(d.conflicts("sharpness", "looting"));
            assertFalse(d.conflicts("mending", "unbreaking"));
            assertTrue(d.conflicts("minecraft:sharpness", "Smite"), "ids are normalised");
        }
    }

    private static void assertConflicts(McData d, List<String> ids) {
        for (int i = 0; i < ids.size(); i++) {
            for (int j = 0; j < ids.size(); j++) {
                if (i != j) {
                    assertTrue(d.conflicts(ids.get(i), ids.get(j)), ids.get(i) + " vs " + ids.get(j));
                }
            }
        }
    }

    @Test
    void conflictsAreSymmetricAndEveryEnchantFitsSomeItem() {
        for (McData d : List.of(v4, v11)) {
            for (String a : d.enchantments().keySet()) {
                assertFalse(d.itemsFor(a).isEmpty(), a);
                for (String b : d.enchantments().keySet()) {
                    assertEquals(d.conflicts(a, b), d.conflicts(b, a), a + "/" + b);
                }
            }
        }
    }
}

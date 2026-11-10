package com.porter.qwerte.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The recipe table (a small test table in the shape the server sends): parsing, its tableHash (the contract's, the plugin's) and the held table. */
class RecipesTest {
    private final Recipes r = TestRecipes.table();

    private static final String SYNTHETIC = "{\"version\":\"9.9\",\"format\":1,\"recipes\":["
        + "{\"id\":\"minecraft:b_stairs\",\"type\":\"shaped\",\"w\":2,\"h\":2,\"cells\":[[\"minecraft:p\"],null,[\"minecraft:p\",\"minecraft:q\"],[\"minecraft:p\"]],\"result\":\"minecraft:b_stairs\",\"count\":4},"
        + "{\"id\":\"minecraft:a_planks\",\"type\":\"shapeless\",\"ingredients\":[[\"minecraft:z_log\",\"minecraft:y_log\"],[\"minecraft:a\"]],\"result\":\"minecraft:a_planks\",\"count\":4}],"
        + "\"otherStations\":{\"minecraft:stone\":\"smelting\"}}";

    @Test
    void theTestTableLoads() {
        assertEquals("1.21.4", r.version());
        assertTrue(r.size() > 20, "recipes: " + r.size());
        assertEquals(40, r.hash().length());
        assertEquals(r.size(), r.all().size());
    }

    @Test
    void theTableHashIsTheContractsOnASmallSyntheticTable() {
        // Lines sorted by id, tab separated, "-" for an empty cell, a shapeless body sorted by ingredient: SHA-1 of
        //   minecraft:a_planks TAB shapeless TAB 0 TAB 0 TAB minecraft:a_planks TAB 4 TAB minecraft:a|minecraft:z_log,minecraft:y_log NL
        //   minecraft:b_stairs TAB shaped TAB 2 TAB 2 TAB minecraft:b_stairs TAB 4 TAB minecraft:p|-|minecraft:p,minecraft:q|minecraft:p NL
        // (the value the plugin's own test asserts for this table, so both bots hash alike)
        Recipes t = Recipes.parse(SYNTHETIC);
        assertEquals("1ebfe58776a2663ed3a6d59f7f031b828d0cdcc6", t.hash());
        // otherStations and the order of the recipes in the file do not matter
        assertEquals(t.hash(), Recipes.tableHash(JsonParser.parseString(SYNTHETIC.replace("\"minecraft:stone\":\"smelting\"", "")).getAsJsonObject()));
        assertEquals("smelting", t.station("minecraft:stone"));
    }

    @Test
    void aChangedCellOrCountChangesTheHash() {
        String h = Recipes.parse(SYNTHETIC).hash();
        assertFalse(h.equals(Recipes.parse(SYNTHETIC.replace("\"count\":4}],", "\"count\":3}],")).hash()));
        assertFalse(h.equals(Recipes.parse(SYNTHETIC.replace("null,[\"minecraft:p\",\"minecraft:q\"]", "[\"minecraft:p\"],[\"minecraft:p\",\"minecraft:q\"]")).hash()));
    }

    @Test
    void aMissingCountIsOneInTheHashAndInTheParse() {
        String with = "{\"version\":\"1.21.4\",\"recipes\":[{\"id\":\"minecraft:a\",\"type\":\"shapeless\",\"ingredients\":[[\"minecraft:b\"]],\"result\":\"minecraft:a\",\"count\":1}]}";
        String without = with.replace(",\"count\":1", "");
        Recipes a = Recipes.parse(with), b = Recipes.parse(without);
        assertEquals(a.hash(), b.hash());
        assertEquals(1, b.producing("minecraft:a").get(0).count());
    }

    @Test
    void notATableIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Recipes.parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> Recipes.parse("not json"));
        assertThrows(IllegalArgumentException.class, () -> Recipes.parse("{\"version\":\"1\"}"));
    }

    @Test
    void aShulkerBoxIsOneWideAndThreeHigh() {
        List<Recipes.Recipe> box = r.producing("minecraft:shulker_box");
        assertEquals(1, box.size());
        Recipes.Recipe b = box.get(0);
        assertTrue(b.shaped());
        assertEquals(1, b.w());
        assertEquals(3, b.h());
        assertEquals(List.of("minecraft:shulker_shell"), b.cells().get(0));
        assertEquals(List.of("minecraft:chest"), b.cells().get(1));
        assertEquals(3, b.filled());
        assertSame(b, r.byId("minecraft:shulker_box"));
        assertNull(r.byId("minecraft:nope"));
        assertTrue(b.accepts("minecraft:chest"));
        assertFalse(b.accepts("minecraft:oak_planks"));
    }

    @Test
    void aChestTakesAnyPlanksAndPlanksComeFromLogsFourAtATime() {
        Recipes.Recipe chest = r.producing("minecraft:chest").get(0);
        assertEquals(8, chest.filled());
        assertNull(chest.cells().get(4));
        Recipes.Recipe planks = r.producing("minecraft:oak_planks").get(0);
        assertFalse(planks.shaped());
        assertEquals(4, planks.count());
        assertTrue(planks.cells().get(0).contains("minecraft:oak_log"));
    }

    @Test
    void aTableIsHeldWholeAndReplacedWhole() {
        Recipes before = Recipes.current();
        try {
            Recipes.setCurrent(null);
            assertNull(Recipes.current());
            Recipes.setCurrent(r);
            assertEquals(r.hash(), Recipes.current().hash());
            assertNotNull(Recipes.empty());
            assertEquals(0, Recipes.empty().size());
        } finally {
            Recipes.setCurrent(before);
        }
    }

    @Test
    void noTableIsShippedInTheJar() {
        assertNull(RecipesTest.class.getResource("/assets/qwerte/recipes/1.21.4.json"));
        assertNull(RecipesTest.class.getResource("/assets/qwerte/recipes/1.21.11.json"));
    }
}

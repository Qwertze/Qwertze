package com.porter.stashmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.stashmanager.assets.GameAssets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity (CRAFT-CONTRACT.md, "tableHash"): the server's table from a vanilla client jar has the same tableHash as the
 * tables the addon and the plugin shipped before the tables were fetched from the server. The constants below were
 * recorded from those shipped tables (addon src/main/resources/assets/qwerte/recipes/{1.21.4,1.21.11}.json and the
 * plugin's recipes/1.21.4.json, all identical for 1.21.4) before they were deleted. The jar-backed tests are skipped when
 * no client jar of that version is on the machine; run them locally before each release.
 */
class RecipeParityTest {
    static final String HASH_1_21_4 = "202980a8ab599d1b4ae340108e0d734a700f74bc";
    static final int RECIPES_1_21_4 = 913;
    static final int STATIONS_1_21_4 = 89;
    static final String HASH_1_21_11 = "190d2f3ed0f133215ffaf5cfadda5e6f6888cc59";
    static final int RECIPES_1_21_11 = 1007;
    static final int STATIONS_1_21_11 = 93;

    static RecipeNormalizer.Result fromJar(String v) throws Exception {
        Optional<Path> jar = GameAssets.locate(v, null);
        Assumptions.assumeTrue(jar.isPresent(), "no client jar for " + v + " on this machine");
        RecipePacks.Raw raw = RecipePacks.readJar(jar.get());
        Map<String, JsonObject> recipes = new TreeMap<>();
        Map<String, JsonObject> tags = new TreeMap<>();
        raw.recipes().forEach((k, b) -> recipes.put(k, JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject()));
        raw.tags().forEach((k, b) -> tags.put(k, JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject()));
        return RecipeNormalizer.normalize(v, recipes, tags, raw.items());
    }

    @Test
    void jarTable_1_21_4_hasTheShippedHash() throws Exception {
        RecipeNormalizer.Result r = fromJar("1.21.4");
        assertEquals(HASH_1_21_4, r.table().tableHash());
        assertEquals(RECIPES_1_21_4, r.table().recipes().size());
        assertEquals(STATIONS_1_21_4, r.table().otherStations().size());
        assertEquals(0, r.dropped());
    }

    @Test
    void jarTable_1_21_11_hasTheShippedHash() throws Exception {
        RecipeNormalizer.Result r = fromJar("1.21.11");
        assertEquals(HASH_1_21_11, r.table().tableHash());
        assertEquals(RECIPES_1_21_11, r.table().recipes().size());
        assertEquals(STATIONS_1_21_11, r.table().otherStations().size());
        assertEquals(0, r.dropped());
    }

    /** While the old shipped files still exist in this checkout, they must match the server's table exactly. */
    @Test
    void shippedFilesStillAroundMatchTheJarTable() throws Exception {
        for (String v : new String[]{"1.21.4", "1.21.11"}) {
            Path shipped = Path.of("..", "src", "main", "resources", "assets", "qwerte", "recipes", v + ".json");
            Assumptions.assumeTrue(Files.isRegularFile(shipped), "the addon's shipped table is gone (fetched from the server now)");
            RecipeTable old = RecipeTable.parse(Files.readString(shipped, StandardCharsets.UTF_8));
            assertEquals(v.equals("1.21.4") ? HASH_1_21_4 : HASH_1_21_11, old.tableHash(), "the recorded constant");
            RecipeNormalizer.Result r = fromJar(v);
            assertEquals(old.tableHash(), r.table().tableHash());
            assertEquals(old.otherStations(), r.table().otherStations());
            assertEquals(old.recipes(), r.table().recipes());
        }
    }

    /**
     * The contract's test vectors on the real 1.21.4 table (a jar-backed copy of CraftPlannerTest, for the vectors the
     * synthetic table can only approximate).
     */
    @Test
    void contractVectorsOnTheRealTable() throws Exception {
        RecipeTable t = fromJar("1.21.4").table();
        CraftPlanner.Eligible e = new CraftPlanner.Eligible(t, java.util.Set.of());
        // 1
        CraftPlanner.Plan p = CraftPlannerTest.run(e, CraftPlannerTest.stock("spruce_log", 10), Map.of(), CraftPlannerTest.THREE);
        assertEquals(java.util.List.of(8L, 6L, 3L), CraftPlannerTest.craftable(p));
        assertEquals(CraftPlannerTest.stock("spruce_log", 6), CraftPlannerTest.counts(p.take()));
        assertEquals(CraftPlannerTest.stock("spruce_planks", 3, "stick", 2), CraftPlannerTest.counts(p.leftovers()));
        assertEquals(java.util.List.of("minecraft:spruce_planks", "minecraft:spruce_stairs", "minecraft:spruce_slab", "minecraft:stick", "minecraft:spruce_fence"),
            p.steps().stream().map(CraftPlanner.Step::recipe).toList());
        assertEquals(java.util.List.of(6L, 2L, 1L, 1L, 1L), p.steps().stream().map(CraftPlanner.Step::fills).toList());
        assertEquals(11, p.fills());
        // 2
        p = CraftPlannerTest.run(e, CraftPlannerTest.stock("spruce_log", 4), Map.of(), CraftPlannerTest.THREE);
        assertEquals(java.util.List.of(8L, 6L, 0L), CraftPlannerTest.craftable(p));
        assertEquals("not enough Spruce Log", p.rows().get(2).reason());
        assertEquals(CraftPlannerTest.stock("spruce_planks", 1), CraftPlannerTest.counts(p.leftovers()));
        // 3
        p = CraftPlannerTest.run(e, CraftPlannerTest.stock("spruce_log", 2), Map.of(), java.util.List.of(CraftPlannerTest.row("spruce_stairs", 8)));
        assertEquals(java.util.List.of(4L), CraftPlannerTest.craftable(p));
        assertEquals(CraftPlannerTest.stock("spruce_planks", 2), CraftPlannerTest.counts(p.leftovers()));
        // 4
        p = CraftPlannerTest.run(e, CraftPlannerTest.stock("spruce_planks", 10, "spruce_log", 2), Map.of("minecraft:spruce_planks", 4L),
            java.util.List.of(CraftPlannerTest.row("spruce_planks", 5), CraftPlannerTest.row("spruce_stairs", 4)));
        assertEquals(java.util.List.of(0L, 4L), CraftPlannerTest.craftable(p));
        assertEquals(CraftPlannerTest.stock("spruce_planks", 1, "spruce_log", 2), CraftPlannerTest.counts(p.take()));
        assertEquals(CraftPlannerTest.stock("spruce_planks", 3), CraftPlannerTest.counts(p.leftovers()));
        // 4b
        p = CraftPlannerTest.run(e, CraftPlannerTest.stock("spruce_planks", 10), Map.of("minecraft:spruce_planks", 4L),
            java.util.List.of(CraftPlannerTest.row("spruce_planks", 5), CraftPlannerTest.row("spruce_stairs", 4)));
        assertEquals(java.util.List.of(0L, 0L), CraftPlannerTest.craftable(p));
        assertTrue(p.take().isEmpty());
        // 5a, 5b
        p = CraftPlannerTest.run(e, CraftPlannerTest.stock("oak_planks", 4, "bamboo", 14), Map.of(), java.util.List.of(CraftPlannerTest.row("ladder", 3)));
        assertEquals(java.util.List.of(3L), CraftPlannerTest.craftable(p));
        assertEquals(CraftPlannerTest.stock("oak_planks", 4), CraftPlannerTest.counts(p.take()));
        assertEquals(CraftPlannerTest.stock("stick", 1), CraftPlannerTest.counts(p.leftovers()));
        // 5b as the contract states it (oak_planks 2, bamboo 14) is wrong on the real table: 9 bamboo make a bamboo block, which
        // makes 2 bamboo planks, so the planks route succeeds with bamboo 9 + oak_planks 2. With one oak plank the 2 planks
        // from a bamboo block are not enough (18 bamboo would be needed for 3), the planks route fails, is restored, and the
        // bamboo route takes all 14.
        p = CraftPlannerTest.run(e, CraftPlannerTest.stock("oak_planks", 2, "bamboo", 14), Map.of(), java.util.List.of(CraftPlannerTest.row("ladder", 3)));
        assertEquals(java.util.List.of(3L), CraftPlannerTest.craftable(p));
        assertEquals(CraftPlannerTest.stock("bamboo", 9, "oak_planks", 2), CraftPlannerTest.counts(p.take()));
        assertEquals(CraftPlannerTest.stock("stick", 1), CraftPlannerTest.counts(p.leftovers()));
        p = CraftPlannerTest.run(e, CraftPlannerTest.stock("oak_planks", 1, "bamboo", 14), Map.of(), java.util.List.of(CraftPlannerTest.row("ladder", 3)));
        assertEquals(java.util.List.of(3L), CraftPlannerTest.craftable(p));
        assertEquals(CraftPlannerTest.stock("bamboo", 14), CraftPlannerTest.counts(p.take()));
        assertTrue(p.leftovers().isEmpty());
        // 6a, 6b
        p = CraftPlannerTest.run(e, CraftPlannerTest.stock("iron_block", 1), Map.of(), java.util.List.of(CraftPlannerTest.row("iron_bars", 16)));
        assertEquals(java.util.List.of(16L), CraftPlannerTest.craftable(p));
        assertEquals(CraftPlannerTest.stock("iron_block", 1), CraftPlannerTest.counts(p.take()));
        assertEquals(CraftPlannerTest.stock("iron_ingot", 3), CraftPlannerTest.counts(p.leftovers()));
        p = CraftPlannerTest.run(e, CraftPlannerTest.stock("iron_nugget", 54), Map.of(), java.util.List.of(CraftPlannerTest.row("iron_bars", 16)));
        assertEquals(java.util.List.of(16L), CraftPlannerTest.craftable(p));
        assertEquals(CraftPlannerTest.stock("iron_nugget", 54), CraftPlannerTest.counts(p.take()));
        assertTrue(p.leftovers().isEmpty());
        // 7
        p = CraftPlannerTest.run(e, CraftPlannerTest.stock("red_bed", 1, "white_dye", 1), Map.of(), java.util.List.of(CraftPlannerTest.row("white_bed", 1)));
        assertEquals(java.util.List.of(0L), CraftPlannerTest.craftable(p));
        assertEquals("not enough White Wool", p.rows().get(0).reason());
        assertTrue(p.take().isEmpty());
        // 8
        p = CraftPlannerTest.run(e, CraftPlannerTest.stock("spruce_log", 10), Map.of(),
            java.util.List.of(new CraftPlannerTest.R("spruce_stairs", 8, true), CraftPlannerTest.row("spruce_slab", 6), CraftPlannerTest.row("spruce_fence", 3)));
        assertEquals(java.util.List.of(0L, 6L, 3L), CraftPlannerTest.craftable(p));
        assertEquals(CraftPlannerTest.stock("spruce_log", 3), CraftPlannerTest.counts(p.take()));
        assertEquals(CraftPlannerTest.stock("spruce_planks", 3, "stick", 2), CraftPlannerTest.counts(p.leftovers()));
        // 9
        p = CraftPlannerTest.run(e, CraftPlannerTest.stock("spruce_log", 10), Map.of(), java.util.List.of(CraftPlannerTest.row("spruce_slab", 10)));
        assertEquals(java.util.List.of(10L), CraftPlannerTest.craftable(p));
        assertEquals(CraftPlannerTest.stock("spruce_log", 2), CraftPlannerTest.counts(p.take()));
        assertEquals(CraftPlannerTest.stock("spruce_planks", 2, "spruce_slab", 2), CraftPlannerTest.counts(p.leftovers()));
        // 10
        p = CraftPlannerTest.run(e, CraftPlannerTest.stock("spruce_log", 10), Map.of("minecraft:spruce_log", 6L), CraftPlannerTest.THREE);
        assertEquals(java.util.List.of(8L, 6L, 0L), CraftPlannerTest.craftable(p));
        assertEquals(CraftPlannerTest.stock("spruce_log", 4), CraftPlannerTest.counts(p.take()));
        // 11
        p = CraftPlannerTest.run(e, CraftPlannerTest.stock("oak_log", 1, "birch_log", 1), Map.of(), java.util.List.of(CraftPlannerTest.row("chest", 1)));
        assertEquals(java.util.List.of(1L), CraftPlannerTest.craftable(p));
        assertEquals(CraftPlannerTest.stock("birch_log", 1, "oak_log", 1), CraftPlannerTest.counts(p.take()));
        assertTrue(p.leftovers().isEmpty());
    }
}

package com.porter.qwerte.craft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The format-1 normaliser (ported from the server's {@code RecipeNormalizer}) on small hand-written recipes, and, when a Minecraft client jar
 * is on this machine, the parity test: the tableHash of the table built from the jar is the one the server records for that version
 * (CRAFT-CONTRACT.md "tableHash"). Nothing from the game is stored in the repository.
 */
class RecipeBuilderTest {
    private static JsonObject j(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static RecipeBuilder.Result build(Map<String, JsonObject> recipes, Map<String, JsonObject> tags) {
        return RecipeBuilder.build("9.9", recipes, tags, Set.of("firework_star"));
    }

    private static Map<String, JsonObject> map(Object... kv) {
        Map<String, JsonObject> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], j((String) kv[i + 1]));
        return m;
    }

    @Test
    void shapedAndShapelessAreReadWithTagsExpandedAndSorted() {
        RecipeBuilder.Result r = build(map(
            "oak_stairs", "{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"#  \",\"## \",\"###\"],\"key\":{\"#\":\"minecraft:oak_planks\"},\"result\":{\"id\":\"minecraft:oak_stairs\",\"count\":4}}",
            "oak_planks", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"#minecraft:oak_logs\"],\"result\":{\"id\":\"minecraft:oak_planks\",\"count\":4}}",
            "chest", "{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"###\",\"# #\",\"###\"],\"key\":{\"#\":\"#minecraft:planks\"},\"result\":{\"id\":\"minecraft:chest\"}}"),
            map("oak_logs", "{\"values\":[\"minecraft:oak_log\",\"minecraft:oak_wood\"]}",
                "planks", "{\"values\":[\"minecraft:oak_planks\",\"minecraft:birch_planks\",\"#minecraft:more\"]}",
                "more", "{\"values\":[\"minecraft:acacia_planks\",\"minecraft:oak_planks\"]}"));
        assertEquals(3, r.recipes());
        assertEquals(0, r.dropped());
        Recipes t = Recipes.fromJson(r.table());
        Recipes.Recipe stairs = t.byId("minecraft:oak_stairs");
        assertTrue(stairs.shaped());
        assertEquals(3, stairs.w());
        assertEquals(3, stairs.h());
        assertEquals(4, stairs.count());
        assertEquals(List.of("minecraft:oak_planks"), stairs.cells().get(0));
        assertNull(stairs.cells().get(1));
        Recipes.Recipe planks = t.byId("minecraft:oak_planks");
        assertFalse(planks.shaped());
        assertEquals(List.of("minecraft:oak_log", "minecraft:oak_wood"), planks.cells().get(0));
        // a nested tag, de-duplicated, sorted
        assertEquals(List.of("minecraft:acacia_planks", "minecraft:birch_planks", "minecraft:oak_planks"), t.byId("minecraft:chest").cells().get(0));
        assertEquals(1, t.byId("minecraft:chest").count(), "count defaults to 1");
        assertEquals(1, r.table().get("format").getAsInt());
    }

    @Test
    void theHashIsOfTheTableAndNotOfTheOrderTheFilesWereRead() {
        Map<String, JsonObject> a = map(
            "a", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:x\"],\"result\":{\"id\":\"minecraft:a\"}}",
            "b", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:y\",\"minecraft:x\"],\"result\":{\"id\":\"minecraft:b\",\"count\":2}}");
        String h = Recipes.fromJson(build(a, Map.of()).table()).hash();
        assertEquals(h, Recipes.tableHash(build(a, Map.of()).table()));
        // A shapeless recipe's ingredient order is not part of the hash
        Map<String, JsonObject> swapped = map(
            "a", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:x\"],\"result\":{\"id\":\"minecraft:a\"}}",
            "b", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:x\",\"minecraft:y\"],\"result\":{\"id\":\"minecraft:b\",\"count\":2}}");
        assertEquals(h, Recipes.fromJson(build(swapped, Map.of()).table()).hash());
    }

    @Test
    void whatTheContractLeavesOutIsLeftOut() {
        RecipeBuilder.Result r = build(map(
            // components on the result
            "stew", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:bowl\"],\"result\":{\"id\":\"minecraft:suspicious_stew\",\"components\":{\"minecraft:x\":1}}}",
            // a crafting remainder (a bucket)
            "cake", "{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"M\"],\"key\":{\"M\":\"minecraft:milk_bucket\"},\"result\":{\"id\":\"minecraft:cake\"}}",
            "honey", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[[\"minecraft:honey_bottle\",\"minecraft:sugar\"]],\"result\":{\"id\":\"minecraft:sugar\"}}",
            // an unknown tag
            "tagged", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"#minecraft:nope\"],\"result\":{\"id\":\"minecraft:tagged\"}}",
            // other stations
            "iron", "{\"type\":\"minecraft:smelting\",\"ingredient\":\"minecraft:raw_iron\",\"result\":{\"id\":\"minecraft:iron_ingot\"}}",
            "bricks", "{\"type\":\"minecraft:stonecutting\",\"ingredient\":\"minecraft:stone\",\"result\":{\"id\":\"minecraft:stone_bricks\",\"count\":1}}",
            "bed", "{\"type\":\"minecraft:crafting_transmute\",\"input\":\"minecraft:white_bed\",\"result\":\"minecraft:red_bed\"}",
            "firework_star", "{\"type\":\"minecraft:crafting_special_firework_star\"}",
            "kept", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:a\"],\"result\":{\"id\":\"minecraft:kept\"}}"), Map.of());
        assertEquals(1, r.recipes());
        assertEquals(1, r.dropped(), "only the unknown tag is counted");
        Recipes t = Recipes.fromJson(r.table());
        assertNotNull(t.byId("minecraft:kept"));
        assertNull(t.byId("minecraft:cake"));
        assertEquals("smelting", t.station("minecraft:iron_ingot"));
        assertEquals("stonecutting", t.station("minecraft:stone_bricks"));
        assertEquals("transmute", t.station("minecraft:red_bed"));
        assertEquals("special", t.station("minecraft:firework_star"));
    }

    @Test
    void anItemMadeBothWaysIsNotListedAsMadeElsewhere() {
        RecipeBuilder.Result r = build(map(
            "ingot_from_nuggets", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:nugget\"],\"result\":{\"id\":\"minecraft:ingot\"}}",
            "ingot_smelted", "{\"type\":\"minecraft:smelting\",\"ingredient\":\"minecraft:ore\",\"result\":{\"id\":\"minecraft:ingot\"}}"), Map.of());
        assertNull(Recipes.fromJson(r.table()).station("minecraft:ingot"));
    }

    @Test
    void aShapedPatternIsShrunkToItsUsedRowsAndColumns() {
        assertArrayEquals(new String[] {"##", "##"}, RecipeBuilder.shrink(List.of("   ", " ##", " ##")));
        assertArrayEquals(new String[] {"#"}, RecipeBuilder.shrink(List.of("#  ")));
        assertArrayEquals(new String[] {"# #", "###"}, RecipeBuilder.shrink(List.of("# #", "###")));
        assertArrayEquals(new String[0], RecipeBuilder.shrink(List.of("   ", "   ")));
        RecipeBuilder.Result r = build(map("loose", "{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"   \",\" ##\",\" ##\"],\"key\":{\"#\":\"minecraft:x\"},\"result\":{\"id\":\"minecraft:loose\"}}"), Map.of());
        Recipes.Recipe loose = Recipes.fromJson(r.table()).byId("minecraft:loose");
        assertEquals(2, loose.w());
        assertEquals(2, loose.h());
        assertEquals(4, loose.filled());
    }

    @Test
    void tagsNestAtMostEightLevelsAndLoopsAreSafe() {
        Map<String, JsonObject> tags = new TreeMap<>();
        for (int i = 1; i <= 9; i++) tags.put("t" + i, j("{\"values\":[" + (i < 9 ? "\"#minecraft:t" + (i + 1) + "\"," : "") + "\"minecraft:leaf" + i + "\"]}"));
        tags.put("loop", j("{\"values\":[\"#minecraft:loop\",\"minecraft:a\"]}"));
        Map<String, JsonObject> recipes = map(
            "deep", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"#minecraft:t2\"],\"result\":{\"id\":\"minecraft:deep\"}}",
            "toodeep", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"#minecraft:t1\"],\"result\":{\"id\":\"minecraft:toodeep\"}}",
            "loops", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"#minecraft:loop\"],\"result\":{\"id\":\"minecraft:loops\"}}");
        Recipes t = Recipes.fromJson(build(recipes, tags).table());
        assertNotNull(t.byId("minecraft:deep"), "8 levels (t2 to t9) are allowed");
        assertNull(t.byId("minecraft:toodeep"), "9 levels are not");
        assertNull(t.byId("minecraft:loops"), "a tag that contains itself is unknown");
    }

    @Test
    void aRecipeInAShapeItDoesNotKnowIsDroppedNotACrash() {
        RecipeBuilder.Result r = build(map(
            "broken", "{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"##\"],\"result\":{\"id\":\"minecraft:broken\"}}",
            "ok", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:a\"],\"result\":{\"id\":\"minecraft:ok\"}}"), Map.of());
        assertEquals(1, r.recipes());
        assertEquals(1, r.dropped());
    }

    @Test
    void theBuiltTableIsOneTheStoreAccepts() throws Exception {
        RecipeBuilder.Result r = build(map("ok", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:a\"],\"result\":{\"id\":\"minecraft:ok\"}}"), Map.of());
        Path dir = Files.createTempDirectory("recipes-built");
        try {
            RecipeStore store = new RecipeStore(dir);
            Recipes accepted = store.accept("9.9", r.table().toString(), null);
            assertEquals(Recipes.tableHash(r.table()), accepted.hash());
            assertEquals(accepted.hash(), store.load("9.9").hash());
        } finally {
            try (var s = Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    // Parity with the server, against the jar on this machine

    /** What the server records for each version (PROTOCOL.md; the server's tests assert them against the same jars). */
    private static final Map<String, String[]> RECORDED = Map.of(
        "1.21.4", new String[] {"202980a8ab599d1b4ae340108e0d734a700f74bc", "913"},
        "1.21.11", new String[] {"190d2f3ed0f133215ffaf5cfadda5e6f6888cc59", "1007"});

    @Test
    void theTableBuiltFromTheClientJarHasTheServersHash() throws Exception {
        int checked = 0;
        Path cache = Path.of(System.getProperty("user.home"), ".gradle", "caches", "fabric-loom");
        for (Map.Entry<String, String[]> e : RECORDED.entrySet()) {
            Path jar = cache.resolve(e.getKey()).resolve("minecraft-client.jar");
            if (!Files.isRegularFile(jar)) continue;
            RecipeBuilder.Raw raw = RecipeBuilder.readJar(jar);
            RecipeBuilder.Result r = RecipeBuilder.build(e.getKey(), raw.recipes(), raw.tags(), raw.items());
            assertEquals(e.getValue()[0], Recipes.tableHash(r.table()), "tableHash of the " + e.getKey() + " jar");
            assertEquals(Integer.parseInt(e.getValue()[1]), r.recipes(), "recipes of " + e.getKey());
            // And the table the bot would use reads back with the same hash
            assertEquals(e.getValue()[0], Recipes.fromJson(r.table()).hash());
            checked++;
        }
        assumeTrue(checked > 0, "no Minecraft client jar on this machine: the parity test is skipped");
    }

    @Test
    void theRunningGamesOwnJarGivesATableToo() {
        Path jar = RecipeBuilder.gameJar();
        assumeTrue(jar != null, "the game's data is not in a jar here");
        RecipeBuilder.Raw raw;
        try {
            raw = RecipeBuilder.readJar(jar);
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
        assertFalse(raw.recipes().isEmpty());
        assertTrue(RECORDED.values().stream().anyMatch(v -> v[0].equals(Recipes.tableHash(RecipeBuilder.build("x", raw.recipes(), raw.tags(), raw.items()).table()))),
            "the game jar on the test classpath is one of the two recorded versions");
    }
}

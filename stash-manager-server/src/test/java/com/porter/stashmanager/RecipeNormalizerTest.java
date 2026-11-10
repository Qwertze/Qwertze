package com.porter.stashmanager;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** The format-1 normaliser and the table hash, on synthetic recipes. */
class RecipeNormalizerTest {
    static RecipeTable.Recipe get(RecipeTable t, String id) {
        RecipeTable.Recipe r = t.byId(id);
        assertNotNull(r, id);
        return r;
    }

    @Test
    void keepsShapedAndShapelessAndExpandsTagsSorted() {
        RecipeNormalizer.Result res = RecipeFixtures.normalized("1.99.9");
        RecipeTable t = res.table();
        RecipeTable.Recipe planks = get(t, "minecraft:spruce_planks");
        assertFalse(planks.shaped());
        assertEquals(0, planks.w());
        assertEquals(0, planks.h());
        assertEquals(4, planks.count());
        assertEquals(List.of(List.of("minecraft:spruce_log", "minecraft:spruce_wood", "minecraft:stripped_spruce_log", "minecraft:stripped_spruce_wood")),
            planks.cells());
        RecipeTable.Recipe stairs = get(t, "minecraft:spruce_stairs");
        assertTrue(stairs.shaped());
        assertEquals(3, stairs.w());
        assertEquals(3, stairs.h());
        assertEquals(9, stairs.cells().size());
        assertEquals(List.of("minecraft:spruce_planks"), stairs.cells().get(0));
        assertNull(stairs.cells().get(1));
        assertNull(stairs.cells().get(2));
        assertNull(stairs.cells().get(5));
        // A tag in a key: sorted, unique alternatives.
        assertEquals(List.of("minecraft:acacia_planks", "minecraft:birch_planks", "minecraft:oak_planks", "minecraft:spruce_planks"),
            get(t, "minecraft:stick").cells().get(0));
        assertEquals("minecraft:white_bed", get(t, "minecraft:dye_white_bed").result(), "dye recipes stay in the table (the planner skips them)");
    }

    @Test
    void dropsComponentsRemaindersAndUnknownOrLoopingTags() {
        RecipeNormalizer.Result res = RecipeFixtures.normalized("1.99.9");
        RecipeTable t = res.table();
        assertNull(t.byId("minecraft:cake"), "a milk bucket leaves a remainder");
        assertNull(t.byId("minecraft:mystery_stew"), "the result has components");
        assertNull(t.byId("minecraft:lost_tag"));
        assertNull(t.byId("minecraft:loop_tag"));
        assertEquals(2, res.dropped(), "an unknown tag and a tag loop are counted");
        for (String s : RecipeNormalizer.REMAINDER) assertTrue(s.startsWith("minecraft:"));
    }

    @Test
    void otherStationsFirstMatchAndNeverForCraftableItems() {
        Map<String, String> other = RecipeFixtures.table().otherStations();
        assertEquals("smelting", other.get("minecraft:stone"));
        assertEquals("stonecutting", other.get("minecraft:stone_bricks"));
        assertEquals("transmute", other.get("minecraft:red_shulker_box"));
        assertEquals("smithing", other.get("minecraft:netherite_sword"));
        assertEquals("special", other.get("minecraft:firework_star"), "a result-less special recipe named after an item");
        assertNull(other.get("minecraft:iron_ingot"), "iron ingots are craftable, so no station");
        assertFalse(other.containsKey("minecraft:armordye"));
    }

    @Test
    void stationPriorityIsSmeltingBeforeStonecutting() {
        Map<String, JsonObject> r = new TreeMap<>();
        r.put("a", RecipeFixtures.j("{\"type\":\"minecraft:stonecutting\",\"ingredient\":\"minecraft:x\",\"result\":{\"id\":\"minecraft:thing\"}}"));
        r.put("b", RecipeFixtures.j("{\"type\":\"minecraft:blasting\",\"ingredient\":\"minecraft:x\",\"result\":{\"id\":\"minecraft:thing\"}}"));
        r.put("c", RecipeFixtures.j(RecipeFixtures.shapeless("[\"minecraft:x\"]", "minecraft:other", 1)));
        RecipeTable t = RecipeNormalizer.normalize("1.99.9", r, Map.of(), Set.of()).table();
        assertEquals("blasting", t.otherStations().get("minecraft:thing"));
        assertEquals(1, t.recipes().size());
    }

    @Test
    void shapedRecipesAreShrunkAndPaddedLikeTheGame() {
        assertArrayEquals(new String[]{"#", "#"}, RecipeNormalizer.shrink(List.of(" # ", " # ")));
        assertArrayEquals(new String[]{"##"}, RecipeNormalizer.shrink(List.of("   ", "## ", "   ")));
        assertArrayEquals(new String[0], RecipeNormalizer.shrink(List.of("  ", "  ")));
        assertArrayEquals(new String[]{"#  ", "###"}, RecipeNormalizer.shrink(List.of("#  ", "###")));
        Map<String, JsonObject> r = new TreeMap<>();
        r.put("x", RecipeFixtures.j(RecipeFixtures.shaped("{\"#\":\"minecraft:a\"}", "[\" # \",\" # \"]", "minecraft:y", 1)));
        RecipeTable.Recipe x = RecipeNormalizer.normalize("1.99.9", r, Map.of(), Set.of()).table().byId("minecraft:x");
        assertEquals(1, x.w());
        assertEquals(2, x.h());
    }

    @Test
    void tagsNestAtMostEightLevelsAndNeverLoop() {
        Map<String, JsonObject> tags = new TreeMap<>();
        for (int i = 1; i <= 9; i++) {
            tags.put("t" + i, RecipeFixtures.j(i == 9 ? "{\"values\":[\"minecraft:leaf\"]}" : "{\"values\":[\"#minecraft:t" + (i + 1) + "\"]}"));
        }
        Map<String, JsonObject> r = new TreeMap<>();
        r.put("deep", RecipeFixtures.j(RecipeFixtures.shapeless("[\"#minecraft:t2\"]", "minecraft:out", 1)));
        r.put("too_deep", RecipeFixtures.j(RecipeFixtures.shapeless("[\"#minecraft:t1\"]", "minecraft:out2", 1)));
        RecipeNormalizer.Result res = RecipeNormalizer.normalize("1.99.9", r, tags, Set.of());
        assertNotNull(res.table().byId("minecraft:deep"), "eight levels (t2..t9) are fine");
        assertNull(res.table().byId("minecraft:too_deep"), "nine levels are not");
        assertEquals(1, res.dropped());
        assertEquals(List.of("minecraft:leaf"), res.table().byId("minecraft:deep").cells().get(0));
    }

    @Test
    void nestedTagsGiveTheUnionOfItsItems() {
        Map<String, JsonObject> r = new TreeMap<>();
        r.put("burn", RecipeFixtures.j(RecipeFixtures.shapeless("[\"#minecraft:logs_that_burn\"]", "minecraft:charcoal_thing", 1)));
        RecipeTable.Recipe x = RecipeNormalizer.normalize("1.99.9", r, RecipeFixtures.tags(), Set.of()).table().byId("minecraft:burn");
        assertEquals(8, x.cells().get(0).size());
        assertEquals("minecraft:oak_log", x.cells().get(0).get(0));
    }

    @Test
    void hashFollowsTheContractAndIgnoresOrderOfInput() {
        RecipeTable t = RecipeFixtures.table();
        assertEquals(40, t.tableHash().length());
        // Shapeless: the ingredients are sorted before joining; shaped: cell order matters.
        RecipeTable.Recipe a = new RecipeTable.Recipe("minecraft:x", false, 0, 0, List.of(List.of("minecraft:b"), List.of("minecraft:a", "minecraft:c")), "minecraft:r", 2);
        RecipeTable.Recipe b = new RecipeTable.Recipe("minecraft:x", false, 0, 0, List.of(List.of("minecraft:a", "minecraft:c"), List.of("minecraft:b")), "minecraft:r", 2);
        assertEquals(RecipeTable.computeHash(List.of(a)), RecipeTable.computeHash(List.of(b)));
        java.util.ArrayList<List<String>> c1 = new java.util.ArrayList<>(java.util.Arrays.asList(List.of("minecraft:a"), null));
        java.util.ArrayList<List<String>> c2 = new java.util.ArrayList<>(java.util.Arrays.asList(null, List.of("minecraft:a")));
        RecipeTable.Recipe s1 = new RecipeTable.Recipe("minecraft:s", true, 2, 1, c1, "minecraft:r", 1);
        RecipeTable.Recipe s2 = new RecipeTable.Recipe("minecraft:s", true, 2, 1, c2, "minecraft:r", 1);
        assertNotEquals(RecipeTable.computeHash(List.of(s1)), RecipeTable.computeHash(List.of(s2)));
        // The line format, spelled out: a one-recipe table hashes this exact text.
        String line = "minecraft:s\tshaped\t2\t1\tminecraft:r\t1\tminecraft:a|-\n";
        assertEquals(sha1(line), RecipeTable.computeHash(List.of(s1)));
        String line2 = "minecraft:x\tshapeless\t0\t0\tminecraft:r\t2\tminecraft:a,minecraft:c|minecraft:b\n";
        assertEquals(sha1(line2), RecipeTable.computeHash(List.of(a)));
        // The input order of the recipes does not matter.
        assertEquals(RecipeTable.computeHash(List.of(a, s1)), RecipeTable.computeHash(List.of(s1, a)));
        assertEquals(sha1("minecraft:s\tshaped\t2\t1\tminecraft:r\t1\tminecraft:a|-\nminecraft:x\tshapeless\t0\t0\tminecraft:r\t2\tminecraft:a,minecraft:c|minecraft:b\n"),
            RecipeTable.computeHash(List.of(a, s1)));
    }

    static String sha1(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void tableJsonRoundTripsWithTheSameHash() {
        RecipeTable t = RecipeFixtures.table();
        String text = t.toJsonText();
        RecipeTable back = RecipeTable.parse(text);
        assertEquals(t.tableHash(), back.tableHash());
        assertEquals(t.recipes().size(), back.recipes().size());
        assertEquals(t.otherStations(), back.otherStations());
        assertEquals(RecipeTable.FORMAT, back.format);
        assertEquals("1.99.9", back.version);
        assertTrue(text.contains("\"format\":1"));
        assertThrows(IllegalArgumentException.class, () -> RecipeTable.parse("{\"version\":\"1\"}"));
        assertThrows(IllegalArgumentException.class, () -> RecipeTable.parse("[1]"));
    }

    @Test
    void recipesWithBadIdsOrTooBigPatternsAreDropped() {
        Map<String, JsonObject> r = new TreeMap<>();
        r.put("good", RecipeFixtures.j(RecipeFixtures.shapeless("[\"minecraft:a\"]", "minecraft:b", 1)));
        r.put("bad_result", RecipeFixtures.j(RecipeFixtures.shapeless("[\"minecraft:a\"]", "minecraft:B d", 1)));
        r.put("bad_ingredient", RecipeFixtures.j(RecipeFixtures.shapeless("[\"minecraft:a/../x y\"]", "minecraft:c", 1)));
        r.put("big", RecipeFixtures.j(RecipeFixtures.shaped("{\"#\":\"minecraft:a\"}", "[\"####\",\"####\"]", "minecraft:d", 1)));
        r.put("tall", RecipeFixtures.j(RecipeFixtures.shaped("{\"#\":\"minecraft:a\"}", "[\"#\",\"#\",\"#\",\"#\"]", "minecraft:e", 1)));
        r.put("Bad Name", RecipeFixtures.j(RecipeFixtures.shapeless("[\"minecraft:a\"]", "minecraft:f", 1)));
        r.put("ok3", RecipeFixtures.j(RecipeFixtures.shaped("{\"#\":\"minecraft:a\"}", "[\"###\",\"###\",\"###\"]", "minecraft:g", 1)));
        RecipeNormalizer.Result res = RecipeNormalizer.normalize("1.99.9", r, Map.of(), Set.of());
        assertEquals(Set.of("minecraft:good", "minecraft:ok3"), res.table().recipes().stream().map(RecipeTable.Recipe::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(3, res.dropped(), "a bad result, a bad ingredient and a bad recipe name are counted; oversize patterns are just left out");
    }

}

package com.porter.qwerte.stash;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kit settings: the hash vectors, everything the server sends is checked, and the cache file. */
class KitSettingsTest {
    private static final String ZERO = "0".repeat(40);

    private static KitSettings parse(String json) throws KitSettings.Invalid {
        return KitSettings.parse(JsonParser.parseString(json));
    }

    /** A settings object; ZERO as the hash means the right hash for the rest (a server sends the true one, and parse checks it). */
    private static String settings(String rev, String hash, String chest, String rules) {
        if (hash.equals(ZERO)) hash = trueHash(chest, rules);
        return "{\"rev\":" + rev + ",\"hash\":\"" + hash + "\",\"kitsChest\":" + chest + ",\"rules\":" + rules + "}";
    }

    private static String trueHash(String chest, String rules) {
        try {
            com.google.gson.JsonElement c = JsonParser.parseString(chest), r = JsonParser.parseString(rules);
            Map<String, String> map = new java.util.TreeMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> e : r.getAsJsonObject().entrySet()) map.put(e.getKey(), e.getValue().getAsString());
            return KitSettings.hashOf(c.isJsonNull() ? null : c.getAsString(), KitSettings.Scoring.DEFAULT, map);
        } catch (RuntimeException e) {
            return ZERO;
        }
    }

    @Test
    void hashVectors() {
        KitSettings.Scoring d = KitSettings.Scoring.DEFAULT;
        assertEquals("cf9dcdb21f6fc7551bde30826e49358bd74d06ef", KitSettings.hashOf(null, d, Map.of()), "v1, an empty chest line, the score line with opMin");
        assertEquals(KitSettings.EMPTY.hash(), KitSettings.hashOf(null, d, Map.of()));
        assertEquals("51f1510ce6282be5d046b67bf54619d4bdec7dd3", KitSettings.hashOf("120,64,-33", d, Map.of("pvp kit", "118,64,-33")));
        assertEquals("b3e1201106d9e4fa2b6f0d92e2da7ec9a049759e", KitSettings.hashOf(null, new KitSettings.Scoring(5, 1, 2, 3, 4, 6), Map.of()), "the scoring is in the hash");
        Map<String, String> unsorted = new LinkedHashMap<>();
        unsorted.put("b kit", "4,5,6");
        unsorted.put("a kit", "1,2,3");
        assertEquals(KitSettings.hashOf(null, d, new java.util.TreeMap<>(unsorted)), KitSettings.hashOf(null, d, unsorted), "rules are hashed in key order");
        assertNotEquals(KitSettings.hashOf(null, d, Map.of()), KitSettings.hashOf(null, d, unsorted));
    }

    @Test
    void aValidObjectIsReadAndUnknownFieldsAreIgnored() throws Exception {
        String hash = KitSettings.hashOf("120,64,-33", KitSettings.Scoring.DEFAULT, Map.of("pvp kit", "118,64,-33"));
        KitSettings s = parse("{\"rev\":7,\"hash\":\"" + hash + "\",\"kitsChest\":\"120,64,-33\",\"rules\":{\"pvp kit\":\"118,64,-33\"},\"future\":[1]}");
        assertEquals(7, s.rev());
        assertEquals("120,64,-33", s.kitsChest());
        assertEquals(Map.of("pvp kit", "118,64,-33"), s.rules());
        assertEquals(hash, s.hash(), "the hash the server said is kept, so the heartbeat compares like for like");
        assertFalse(s.isEmpty());
    }

    @Test
    void aNullKitsChestAndNoRulesIsEmpty() throws Exception {
        KitSettings s = parse(settings("0", ZERO, "null", "{}"));
        assertNull(s.kitsChest());
        assertTrue(s.isEmpty());
    }

    @Test
    void theWholeObjectIsRefusedForAnyBadPart() throws Exception {
        String ok = "{\"pvp kit\":\"1,2,3\"}";
        assertThrows(KitSettings.Invalid.class, () -> parse("[]"));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("\"7\"", ZERO, "null", ok)));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("-1", ZERO, "null", ok)));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1.5", ZERO, "null", ok)));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", "ABC", "null", ok)));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO.toUpperCase().replace('0', 'A'), "null", ok)));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO, "\"nope\"", ok)));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO, "5", ok)));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO, "null", "[]")));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO, "null", "{\"Pvp Kit\":\"1,2,3\"}")), "a key that is not normal");
        assertEquals(Map.of("toolkit", "1,2,3"), parse(settings("1", ZERO, "null", "{\"toolkit\":\"1,2,3\"}")).rules(), "v1.1: a rule key needs no kit word");
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO, "null", "{\"pvp kit\":\"1, 2, 3\"}")));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO, "null", "{\"pvp kit\":7}")));
        assertThrows(KitSettings.Invalid.class, () -> parse("{\"rev\":1,\"hash\":\"" + ZERO + "\"}"));
    }

    @Test
    void containerIdsFollowTheContractPatternAndRanges() {
        assertTrue(KitSettings.validId("0,0,0"));
        assertTrue(KitSettings.validId("-30000000,-2048,30000000"));
        assertTrue(KitSettings.validId("30000000,2048,-30000000"));
        assertFalse(KitSettings.validId("30000001,0,0"));
        assertFalse(KitSettings.validId("0,2049,0"));
        assertFalse(KitSettings.validId("0,-2049,0"));
        assertFalse(KitSettings.validId("0,0,-30000001"));
        assertFalse(KitSettings.validId("123456789,0,0"), "more than eight digits");
        assertFalse(KitSettings.validId("0,12345,0"), "more than four digits for y");
        assertFalse(KitSettings.validId("1,2"));
        assertFalse(KitSettings.validId("1,2,3,4"));
        assertFalse(KitSettings.validId("+1,2,3"));
        assertFalse(KitSettings.validId("1, 2,3"));
        assertFalse(KitSettings.validId("1.0,2,3"));
        assertFalse(KitSettings.validId(""));
        assertFalse(KitSettings.validId(null));
        assertNull(KitSettings.position("nonsense"));
        assertEquals(-33, KitSettings.position("120,64,-33").z());
    }

    @Test
    void atMostTwoHundredRules() throws Exception {
        assertEquals(200, parse(settings("1", ZERO, "null", rules(200))).rules().size());
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO, "null", rules(201))));
    }

    private static String rules(int n) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < n; i++) sb.append(i > 0 ? "," : "").append("\"kit ").append(i).append("\":\"").append(i).append(",64,0\"");
        return sb.append('}').toString();
    }

    @Test
    void jsonRoundTrip() throws Exception {
        KitSettings s = parse(settings("3", ZERO, "\"1,2,3\"", "{\"a kit\":\"4,5,6\"}"));
        assertEquals(s, KitSettings.parse(s.toJson()));
    }

    // The cache file

    @Test
    void theCacheRoundTripsThroughTheFileAndIsWrittenAtomically(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sub").resolve("kit-settings.json");
        KitStore store = new KitStore(file);
        assertNull(store.get("2b2t.org", "stash"), "nothing cached yet");
        int v0 = store.version();

        KitSettings s = parse(settings("3", ZERO, "\"1,2,3\"", "{\"a kit\":\"4,5,6\"}"));
        assertTrue(store.put("2b2t.org", "stash", s));
        assertTrue(store.version() > v0);
        assertFalse(store.put("2b2t.org", "stash", s), "the same settings change nothing");
        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(file.resolveSibling("kit-settings.json.tmp")), "no temp file is left behind");

        KitStore again = new KitStore(file);
        assertEquals(s, again.get("2b2t.org", "stash"));
        assertNull(again.get("2b2t.org", "other"), "per server and stash");
        assertNull(again.get("other.org", "stash"));
    }

    @Test
    void anUnreadableFileMeansNoCacheAndIsReplacedByTheNextSave(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kit-settings.json");
        Files.writeString(file, "{ this is not json");
        KitStore store = new KitStore(file);
        assertNull(store.get("s", "t"));
        assertTrue(store.put("s", "t", KitSettings.EMPTY));
        assertEquals(KitSettings.EMPTY, new KitStore(file).get("s", "t"));
    }

    @Test
    void aBadEntryInTheFileIsDroppedAndTheGoodOnesKept(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kit-settings.json");
        JsonObject good = parse(settings("1", ZERO, "null", "{\"a kit\":\"1,2,3\"}")).toJson();
        good.addProperty("server", "s");
        good.addProperty("stash", "good");
        JsonObject bad = parse(settings("1", ZERO, "null", "{\"a kit\":\"1,2,3\"}")).toJson();
        bad.getAsJsonObject("rules").addProperty("Not Normal", "1,2,3");
        bad.addProperty("server", "s");
        bad.addProperty("stash", "bad");
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        array.add(bad);
        array.add(good);
        root.add("stashes", array);
        Files.writeString(file, root.toString());

        KitStore store = new KitStore(file);
        assertNull(store.get("s", "bad"));
        assertNotNull(store.get("s", "good"));
    }

    @Test
    void atMostSixtyFourStashesAreKept(@TempDir Path dir) throws IOException {
        KitStore store = new KitStore(dir.resolve("kit-settings.json"));
        for (int i = 0; i < 70; i++) store.put("s", "stash" + i, KitSettings.EMPTY);
        assertNull(store.get("s", "stash0"), "the oldest goes first");
        assertNotNull(store.get("s", "stash69"));
        assertEquals(KitStore.MAX_STASHES, new KitStore(dir.resolve("kit-settings.json")).size());
    }

    @Test
    void anObjectWhoseHashIsNotItsOwnIsRefusedWhole() throws Exception {
        String rules = "{\"pvp kit\":\"1,2,3\"}";
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", "1".repeat(40), "null", rules)), "a well formed hash that is not the settings'");
        // the right hash of other settings is no better
        String other = KitSettings.hashOf("9,9,9", KitSettings.Scoring.DEFAULT, Map.of("pvp kit", "1,2,3"));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", other, "null", rules)));
        // scoring is part of the hash
        String scored = "{\"rev\":1,\"hash\":\"" + KitSettings.hashOf(null, new KitSettings.Scoring(5, 1, 2, 3, 4, 6), Map.of())
            + "\",\"kitsChest\":null,\"rules\":{},\"scoring\":{\"threshold\":5,\"name\":1,\"mix\":2,\"full\":3,\"op\":4,\"opMin\":6}}";
        assertEquals(new KitSettings.Scoring(5, 1, 2, 3, 4, 6), parse(scored).scoring());
        assertThrows(KitSettings.Invalid.class, () -> parse(scored.replace("\"opMin\":6", "\"opMin\":7")));
    }

    @Test
    void aScoringWithoutOpMinMeansThree() throws Exception {
        KitSettings.Scoring s = new KitSettings.Scoring(5, 1, 2, 3, 4, 3);
        String json = "{\"rev\":1,\"hash\":\"" + KitSettings.hashOf(null, s, Map.of())
            + "\",\"kitsChest\":null,\"rules\":{},\"scoring\":{\"threshold\":5,\"name\":1,\"mix\":2,\"full\":3,\"op\":4}}";
        assertEquals(3, parse(json).scoring().opMin());
        assertEquals(s, parse(json).scoring());
    }

    @Test
    void opMinIsOneToSeventeenTwentyEight() {
        assertTrue(new KitSettings.Scoring(4, 2, 3, 1, 4, 1).valid());
        assertTrue(new KitSettings.Scoring(4, 2, 3, 1, 4, 1728).valid());
        assertFalse(new KitSettings.Scoring(4, 2, 3, 1, 4, 0).valid());
        assertFalse(new KitSettings.Scoring(4, 2, 3, 1, 4, 1729).valid());
        assertEquals(10, KitSettings.Scoring.DEFAULT.max(), "opMin is not a point value");
        assertEquals(KitSettings.EMPTY.hash(), "cf9dcdb21f6fc7551bde30826e49358bd74d06ef");
    }
}

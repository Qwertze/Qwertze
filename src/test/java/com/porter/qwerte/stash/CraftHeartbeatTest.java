package com.porter.qwerte.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The craft fields of the heartbeat and its reply, the craft settings and their cache (CRAFT-CONTRACT.md "Heartbeat"). Ported from the
 * plugin's CraftHeartbeatTest, with the same hashes, so both bots agree on the bytes.
 */
class CraftHeartbeatTest {
    private static final String HASH = "a".repeat(40);
    private static final String TABLE_HASH = "fef8d74b69d3c5d41db659eb326a7e9a5d78cc85";
    private static final StashIndex.Built BUILT = new StashIndex.Built("2b2t.org", "stash", "Bot", "ix", new byte[0]);

    private static JsonObject body(StashIndex.Built b, StashIndex.Craft craft) {
        byte[] bytes = StashIndex.heartbeat(b, null, null, -1, null, null, null, null, null, null, null, null, craft);
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject body(StashIndex.Craft craft) {
        return body(BUILT, craft);
    }

    @Test
    void aBotWithATableSendsCraftOrdersAndTheHashOfItsSettings() {
        StashIndex.CraftOrders c = new StashIndex.CraftOrders(true, HASH, "1.21.4", "ok", 2048, List.of("118,64,-30"));
        JsonObject o = body(new StashIndex.Craft(c, TABLE_HASH));
        JsonObject co = o.getAsJsonObject("craftOrders");
        assertEquals(1, co.get("v").getAsInt());
        assertTrue(co.get("enabled").getAsBoolean());
        assertEquals(HASH, co.get("recipes").getAsString());
        assertEquals("1.21.4", co.get("mc").getAsString());
        assertEquals("ok", co.get("table").getAsString());
        assertEquals(2048, co.get("maxFills").getAsInt());
        assertEquals("118,64,-30", co.getAsJsonArray("seen").get(0).getAsString());
        assertEquals(TABLE_HASH, o.get("craftHash").getAsString());
        assertEquals("Bot", o.get("player").getAsString());
    }

    @Test
    void withNoCachedSettingsCraftHashIsAnExplicitNullSoTheServerSendsThem() {
        JsonObject o = body(new StashIndex.Craft(new StashIndex.CraftOrders(true, HASH, "1.21.4", "unset", 2048, List.of()), null));
        assertTrue(o.has("craftHash") && o.get("craftHash").isJsonNull());
        assertFalse(o.getAsJsonObject("craftOrders").has("seen"), "no tables seen: the list is left out");
    }

    @Test
    void aBotThatCannotCraftOrdersSaysNullAndSendsNoHash() {
        JsonObject o = body(new StashIndex.Craft(null, null));
        assertTrue(o.has("craftOrders") && o.get("craftOrders").isJsonNull());
        assertFalse(o.has("craftHash"));
    }

    @Test
    void everyHeartbeatWithCraftFieldsCarriesThePlayerAndWithoutOneTheyAreLeftOut() {
        StashIndex.Craft craft = new StashIndex.Craft(new StashIndex.CraftOrders(true, HASH, "1.21.4", "ok", 10, List.of()), null);
        JsonObject with = body(craft);
        assertTrue(with.has("craftOrders") && with.has("craftHash"));
        JsonObject without = body(new StashIndex.Built("2b2t.org", "stash", "", "ix", new byte[0]), craft);
        assertFalse(without.has("craftOrders"));
        assertFalse(without.has("craftHash"));
        // an explicit null is a craft field too
        assertFalse(body(new StashIndex.Built("2b2t.org", "stash", "", "ix", new byte[0]), new StashIndex.Craft(null, null)).has("craftOrders"));
    }

    @Test
    void withoutCraftSupportNeitherKeyIsSentAndTheRestIsUnchanged() {
        JsonObject o = body(null);
        assertFalse(o.has("craftOrders"));
        assertFalse(o.has("craftHash"));
        assertEquals("ix", o.get("indexHash").getAsString());
        // the older overloads still work
        byte[] old = StashIndex.heartbeat(BUILT, null, null, -1, null, null, null, null);
        assertFalse(new String(old, StandardCharsets.UTF_8).contains("craft"));
    }

    @Test
    void atMostEightTablesAreSeenAndTheTableStatesPassThrough() {
        List<String> many = List.of("1,1,1", "2,2,2", "3,3,3", "4,4,4", "5,5,5", "6,6,6", "7,7,7", "8,8,8", "9,9,9", "10,10,10");
        JsonObject o = body(new StashIndex.Craft(new StashIndex.CraftOrders(true, HASH, "1.21.4", "ok", 5, many), HASH));
        assertEquals(8, o.getAsJsonObject("craftOrders").getAsJsonArray("seen").size());
        for (String tableState : new String[] {StashIndex.TABLE_UNSET, StashIndex.TABLE_OK, StashIndex.TABLE_UNLOADED, StashIndex.TABLE_NOT_TABLE,
            StashIndex.TABLE_UNREACHABLE, StashIndex.TABLE_OTHER_DIMENSION}) {
            assertEquals(tableState, body(new StashIndex.Craft(new StashIndex.CraftOrders(false, HASH, "1.21.4", tableState, 1, List.of()), null))
                .getAsJsonObject("craftOrders").get("table").getAsString());
        }
        // maxFills stays in the contract's 1..100000
        assertEquals(100000, body(new StashIndex.Craft(new StashIndex.CraftOrders(true, HASH, "1.21.4", "ok", 5_000_000, List.of()), null)).getAsJsonObject("craftOrders").get("maxFills").getAsInt());
        assertEquals(1, body(new StashIndex.Craft(new StashIndex.CraftOrders(true, HASH, "1.21.4", "ok", 0, List.of()), null)).getAsJsonObject("craftOrders").get("maxFills").getAsInt());
    }

    // The reply

    private static JsonObject settings(String table) {
        JsonObject o = new JsonObject();
        o.addProperty("rev", 3);
        CraftSettings.Table t = null;
        if (table != null) {
            JsonObject to = new JsonObject();
            to.addProperty("x", 118);
            to.addProperty("y", 64);
            to.addProperty("z", -30);
            to.addProperty("dimension", "minecraft:overworld");
            o.add("table", to);
            t = new CraftSettings.Table(118, 64, -30, "minecraft:overworld");
        } else {
            o.add("table", com.google.gson.JsonNull.INSTANCE);
        }
        o.addProperty("hash", CraftSettings.hashOf(t));
        o.add("neverUse", new com.google.gson.JsonArray());
        return o;
    }

    @Test
    void theHashIsTheContractsOverTheTablePosition() {
        assertEquals(TABLE_HASH, CraftSettings.hashOf(new CraftSettings.Table(118, 64, -30, "minecraft:overworld")));
        assertEquals("d356a9e31dd89167760395bb70f7d8f1204e84c0", CraftSettings.hashOf(null));
        assertEquals(CraftSettings.EMPTY.hash(), CraftSettings.hashOf(null));
        assertEquals("118,64,-30", new CraftSettings.Table(118, 64, -30, "minecraft:overworld").id());
    }

    @Test
    void aReplyWithSettingsIsReadAndOneWithTheHashAloneIsToo() throws Exception {
        JsonObject reply = new JsonObject();
        reply.addProperty("craftHash", TABLE_HASH);
        reply.addProperty("recipesHash", HASH);
        reply.add("craft", settings("t"));
        StashIndex.CraftReply r = StashIndex.craftReply(reply.toString());
        assertNull(r.invalid());
        assertEquals(TABLE_HASH, r.hash());
        assertEquals(HASH, r.recipesHash());
        assertNotNull(r.settings());
        assertEquals(3, r.settings().rev());
        assertEquals(new CraftSettings.Table(118, 64, -30, "minecraft:overworld"), r.settings().table());
        // A cleared table
        reply.add("craft", settings(null));
        assertNull(StashIndex.craftReply(reply.toString()).settings().table());
        // An older server says nothing
        StashIndex.CraftReply old = StashIndex.craftReply("{\"ok\":true}");
        assertNull(old.hash());
        assertNull(old.settings());
        assertNull(old.recipesHash());
        assertNull(old.invalid());
        // Garbage is nothing, not a crash
        assertNull(StashIndex.craftReply("not json").settings());
        assertNull(StashIndex.craftReply("[]").hash());
        // A hash that is not 40 lowercase hex digits is not one
        assertNull(StashIndex.craftReply("{\"craftHash\":\"ABC\",\"recipesHash\":5}").hash());
    }

    @Test
    void aSettingsObjectThatFailsAnyCheckIsRefusedWhole() {
        String[] bad = {
            // a hash that is not the content's
            "{\"rev\":1,\"hash\":\"" + HASH + "\",\"table\":null}",
            // a position out of range
            "{\"rev\":1,\"hash\":\"" + HASH + "\",\"table\":{\"x\":30000001,\"y\":64,\"z\":0,\"dimension\":\"minecraft:overworld\"}}",
            "{\"rev\":1,\"hash\":\"" + HASH + "\",\"table\":{\"x\":0,\"y\":2049,\"z\":0,\"dimension\":\"minecraft:overworld\"}}",
            "{\"rev\":1,\"hash\":\"" + HASH + "\",\"table\":{\"x\":0,\"y\":321,\"z\":0,\"dimension\":\"minecraft:overworld\"}}",
            "{\"rev\":1,\"hash\":\"" + HASH + "\",\"table\":{\"x\":0,\"y\":-65,\"z\":0,\"dimension\":\"minecraft:overworld\"}}",
            // a bad dimension
            "{\"rev\":1,\"hash\":\"" + HASH + "\",\"table\":{\"x\":0,\"y\":64,\"z\":0,\"dimension\":\"Over World\"}}",
            // wrong types
            "{\"rev\":\"1\",\"hash\":\"" + HASH + "\",\"table\":null}",
            "{\"rev\":1,\"hash\":5,\"table\":null}",
            "{\"rev\":1,\"hash\":\"" + HASH + "\",\"table\":\"x\"}",
            "{\"rev\":1.5,\"hash\":\"" + HASH + "\",\"table\":null}",
        };
        for (String b : bad) {
            StashIndex.CraftReply r = StashIndex.craftReply("{\"craft\":" + b + "}");
            assertNull(r.settings(), b);
            assertNotNull(r.invalid(), b);
        }
    }

    @Test
    void theWebsiteTablesHeightIsBetweenMinus64And320() {
        for (int y : new int[] {-65, 321}) assertFalse(CraftSettings.validTable(0, y, 0, "minecraft:overworld"), "y " + y);
        for (int y : new int[] {-64, 0, 320}) assertTrue(CraftSettings.validTable(0, y, 0, "minecraft:overworld"), "y " + y);
    }

    @Test
    void theCacheIsWrittenAtomicallyReadBackAndABadEntryIsDropped(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("craft-settings.json");
        CraftStore store = new CraftStore(file);
        assertNull(store.get("2b2t.org", "main"));
        CraftSettings s = CraftSettings.parse(settings("t"));
        assertTrue(store.put("2b2t.org", "main", s));
        assertFalse(store.put("2b2t.org", "main", s), "the same settings again change nothing");
        assertEquals(1, store.version());
        assertFalse(Files.exists(dir.resolve("craft-settings.json.tmp")), "no temp file is left");
        CraftStore again = new CraftStore(file);
        assertEquals(s, again.get("2b2t.org", "main"));
        // An entry whose table was changed by hand no longer matches its hash: it is dropped, the rest stays
        Files.writeString(file, Files.readString(file).replace("\"x\":118", "\"x\":119"));
        assertNull(new CraftStore(file).get("2b2t.org", "main"));
        Files.writeString(file, "garbage");
        assertEquals(0, new CraftStore(file).size());
    }

    @Test
    void theCacheKeepsAtMost64StashesAndTheNewestWin(@TempDir Path dir) {
        CraftStore store = new CraftStore(dir.resolve("c.json"));
        for (int i = 0; i < CraftStore.MAX_STASHES + 3; i++) store.put("s", "stash" + i, new CraftSettings(i + 1, CraftSettings.hashOf(null), null));
        assertEquals(CraftStore.MAX_STASHES, store.size());
        assertNull(store.get("s", "stash0"));
        assertNotNull(store.get("s", "stash" + (CraftStore.MAX_STASHES + 2)));
    }
}

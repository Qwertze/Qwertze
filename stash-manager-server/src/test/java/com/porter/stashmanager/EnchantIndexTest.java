package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The index fields (ench, rc, named), the heartbeat enchant block, the capability and the reply (ENCHANT-CONTRACT v1.0). */
class EnchantIndexTest extends EnchantHarness {

    JsonObject served(String key) throws Exception {
        JsonObject idx = ok(get(owner, "/api/v1/index?server=s&stash=a"));
        for (JsonElement i : idx.getAsJsonArray("items")) if (i.getAsJsonObject().get("key").getAsString().equals(key)) return i.getAsJsonObject();
        return null;
    }

    // ---------- index fields ----------

    @Test
    void goodFieldsAreKeptInOneCanonicalForm() throws Exception {
        start();
        // A plain id, a float level and a float rc are accepted and rewritten; the table key is the plain enchantment id.
        store.putIndex(json(index(MC11, "Bob", List.of(
            it("minecraft:enchanted_book#c1", "minecraft:enchanted_book", 1, 1, "\"ench\":[{\"id\":\"sharpness\",\"lvl\":5.0}],\"rc\":3.0,\"named\":false")))));
        JsonObject b = served("minecraft:enchanted_book#c1");
        assertEquals("minecraft:sharpness", b.getAsJsonArray("ench").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(5, b.getAsJsonArray("ench").get(0).getAsJsonObject().get("lvl").getAsInt());
        assertEquals("3", b.get("rc").toString());
        IndexStore.OrderCounts c = store.orderCounts("s", "a");
        EnchantIndex.Entry en = c.enchant().get("minecraft:enchanted_book#c1");
        assertEquals(5, en.ench().get("sharpness"));
        assertEquals(3, en.rc());
        assertFalse(en.named());
    }

    @Test
    void aBadFieldIsDroppedAndTheEntryStays() throws Exception {
        start();
        List<String> badEnch = new ArrayList<>(List.of(
            "\"ench\":\"x\"",
            "\"ench\":[{\"id\":\"Sharpness\",\"lvl\":5}]",
            "\"ench\":[{\"id\":\"\",\"lvl\":5}]",
            "\"ench\":[{\"id\":\"sharp ness\",\"lvl\":5}]",
            "\"ench\":[{\"id\":5,\"lvl\":5}]",
            "\"ench\":[{\"id\":\"sharpness\",\"lvl\":0}]",
            "\"ench\":[{\"id\":\"sharpness\",\"lvl\":256}]",
            "\"ench\":[{\"id\":\"sharpness\",\"lvl\":1.5}]",
            "\"ench\":[{\"id\":\"sharpness\",\"lvl\":\"5\"}]",
            "\"ench\":[{\"id\":\"sharpness\"}]",
            "\"ench\":[5]",
            // duplicates after normalising drop the whole field
            "\"ench\":[{\"id\":\"sharpness\",\"lvl\":5},{\"id\":\"minecraft:sharpness\",\"lvl\":4}]"));
        StringBuilder many = new StringBuilder("\"ench\":[");
        for (int i = 0; i < 65; i++) many.append(i == 0 ? "" : ",").append("{\"id\":\"e").append(i).append("\",\"lvl\":1}");
        badEnch.add(many.append("]").toString());
        List<String> items = new ArrayList<>();
        for (int i = 0; i < badEnch.size(); i++) items.add(it("minecraft:enchanted_book#b" + i, "minecraft:enchanted_book", 2, 1, badEnch.get(i) + ",\"rc\":1"));
        String[] badRc = {"-1", "2147483648", "1.5", "\"7\"", "true", "null"};
        for (int i = 0; i < badRc.length; i++) items.add(it("minecraft:diamond_sword#r" + i, "minecraft:diamond_sword", 1, 1, "\"ench\":[],\"rc\":" + badRc[i]));
        items.add(it("minecraft:diamond_sword#n1", "minecraft:diamond_sword", 1, 1, "\"named\":\"yes\""));
        items.add(it("minecraft:diamond_sword#n2", "minecraft:diamond_sword", 1, 1, "\"named\":1"));
        store.putIndex(json(index(MC11, "Bob", items)));
        for (int i = 0; i < badEnch.size(); i++) {
            JsonObject b = served("minecraft:enchanted_book#b" + i);
            assertNotNull(b, "the entry stays: " + badEnch.get(i).substring(0, Math.min(30, badEnch.get(i).length())));
            assertEquals(2, b.get("count").getAsInt());
            assertFalse(b.has("ench"), "ench dropped: " + badEnch.get(i).substring(0, Math.min(40, badEnch.get(i).length())));
            assertEquals("1", b.get("rc").toString(), "the good rc is kept");
            assertFalse(store.orderCounts("s", "a").enchant().get("minecraft:enchanted_book#b" + i).known());
        }
        for (int i = 0; i < badRc.length; i++) {
            JsonObject s = served("minecraft:diamond_sword#r" + i);
            assertNotNull(s);
            assertFalse(s.has("rc") && !s.get("rc").isJsonNull() && s.get("rc").isJsonPrimitive(), "rc dropped: " + badRc[i]);
            assertTrue(s.has("ench"), "the good ench is kept");
        }
        for (String k : new String[]{"minecraft:diamond_sword#n1", "minecraft:diamond_sword#n2"}) {
            assertFalse(served(k).has("named"));
            assertFalse(store.orderCounts("s", "a").enchant().containsKey(k));
        }
        // Nothing of the bad values reaches the disk copy either.
        String disk = new String(java.nio.file.Files.readAllBytes(java.nio.file.Files.list(data.resolve("indexes")).findFirst().orElseThrow()));
        assertFalse(disk.contains("sharp ness") && disk.contains("\"yes\""));
    }

    @Test
    void anIndexWithEnchantFieldsMustComeFromTheOwner() throws Exception {
        start();
        ok(heartbeat(null)); // Bob becomes the owner
        // Without a player the post is refused (the fields can't be attributed); an index without the fields is as before.
        assertEquals(400, botPost("/api/v1/index", index(MC11, null, items())).status);
        assertEquals(200, botPost("/api/v1/index", index(MC11, null, List.of(it("minecraft:dirt", "minecraft:dirt", 5, 64, null)))).status);
        // Another player is refused whether or not the post carries the fields (the existing ownership rule) and stores nothing.
        Res other = botPost("/api/v1/index", index(MC11, "Mallory", items()));
        assertEquals(409, other.status);
        assertEquals("Bob", other.obj().get("owner").getAsString());
        assertNull(store.orderCounts("s", "a").enchant().get("minecraft:diamond_sword"));
        assertEquals(200, botPost("/api/v1/index", index(MC11, "Bob", items())).status);
        assertEquals(0, store.orderCounts("s", "a").enchant().get("minecraft:diamond_sword").rc());
        assertEquals(200, botPost("/api/v1/index", index(MC11, "bob", items())).status, "player names compare case-insensitively like the owner rule");
    }

    @Test
    void aLevelAboveTheTableMaximumIsKeptButTheEntryIsUnplannable() throws Exception {
        startEnchant();
        ready();
        reindex(List.of(sword(1), book("minecraft:enchanted_book#hi", 1, 0, e("sharpness", 9)), bottles(3000), anvils(3)));
        assertEquals(9, store.orderCounts("s", "a").enchant().get("minecraft:enchanted_book#hi").ench().get("sharpness"));
        Res r = placeEnchant(line("minecraft:diamond_sword", 1, e("sharpness", 5)));
        assertEquals(400, r.status, r.body);
        assertEquals("books_short", r.obj().get("enchant").getAsString());
    }

    // ---------- heartbeat ----------

    @Test
    void theHeartbeatBlockIsValidated() throws Exception {
        startEnchant();
        String[] bad = {
            "5", "[]", "\"x\"", "{}",
            "{\"v\":2,\"enabled\":true,\"mc\":\"1.21.11\"}", "{\"v\":\"1\",\"enabled\":true,\"mc\":\"1.21.11\"}", "{\"v\":1.5,\"enabled\":true,\"mc\":\"1.21.11\"}",
            "{\"v\":1,\"enabled\":\"true\",\"mc\":\"1.21.11\"}", "{\"v\":1,\"mc\":\"1.21.11\"}", "{\"v\":1,\"enabled\":1,\"mc\":\"1.21.11\"}",
            "{\"v\":1,\"enabled\":true,\"mc\":\"1.20.1\"}", "{\"v\":1,\"enabled\":true}", "{\"v\":1,\"enabled\":true,\"mc\":11}",
            "{\"v\":1,\"enabled\":true,\"mc\":\"../../etc\"}",
            "{\"v\":1,\"enabled\":true,\"mc\":\"1.21.11\",\"carry\":37}", "{\"v\":1,\"enabled\":true,\"mc\":\"1.21.11\",\"carry\":-1}",
            "{\"v\":1,\"enabled\":true,\"mc\":\"1.21.11\",\"carry\":1.5}", "{\"v\":1,\"enabled\":true,\"mc\":\"1.21.11\",\"carry\":\"20\"}"};
        for (String b : bad) {
            Res r = heartbeat("\"enchant\":" + b);
            assertEquals(400, r.status, b + " -> " + r.body);
            assertFalse(r.body.contains("etc"), "the answer doesn't repeat the input: " + r.body);
        }
    }

    @Test
    void thePlayerIsRequiredAndKeptInMemoryOnly() throws Exception {
        startEnchant();
        Res noPlayer = botPost("/api/v1/heartbeat", "{" + KEY + ",\"enchant\":" + block(true, MC11, null) + "}");
        assertEquals(400, noPlayer.status);
        Res badPlayer = botPost("/api/v1/heartbeat", "{" + KEY + ",\"player\":\"x\",\"enchant\":" + block(true, MC11, null) + "}");
        assertEquals(400, badPlayer.status);
        assertFalse(routes.enchant().bots().known("s", "a"));
        ready();
        assertTrue(routes.enchant().bots().known("s", "a"));
        // The block is in memory only: nothing about it is written under the data folder.
        try (var files = java.nio.file.Files.walk(data)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) files.filter(java.nio.file.Files::isRegularFile)::iterator) {
                assertFalse(new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.ISO_8859_1).contains("\"carry\""), p.toString());
            }
        }
    }

    JsonObject stashEntry() throws Exception {
        JsonElement stashes = com.google.gson.JsonParser.parseString(get(owner, "/api/v1/stashes").body);
        return stashes.getAsJsonArray().get(0).getAsJsonObject();
    }

    @Test
    void nullClearsAbsentKeepsAndTheStashListCarriesTheSummary() throws Exception {
        startEnchant();
        JsonObject before = stashEntry().getAsJsonObject("enchant");
        assertFalse(before.get("supports").getAsBoolean());
        assertFalse(before.get("canOrder").getAsBoolean());
        ready();
        JsonObject en = stashEntry().getAsJsonObject("enchant");
        assertTrue(en.get("supports").getAsBoolean());
        assertEquals(MC11, en.get("mc").getAsString());
        assertTrue(en.get("canOrder").getAsBoolean());
        ok(heartbeat(null)); // an absent key keeps
        assertTrue(stashEntry().getAsJsonObject("enchant").get("canOrder").getAsBoolean());
        ok(enchantBeat(block(false, MC11, null))); // disabled: not capable
        assertFalse(stashEntry().getAsJsonObject("enchant").get("canOrder").getAsBoolean());
        ready();
        ok(heartbeat("\"enchant\":null")); // null clears
        JsonObject cleared = stashEntry().getAsJsonObject("enchant");
        assertFalse(cleared.get("supports").getAsBoolean());
        assertTrue(cleared.get("mc").isJsonNull());
    }

    @Test
    void capabilityNeedsAFreshHeartbeatAndTheIndexVersion() throws Exception {
        startEnchant();
        ready();
        assertTrue(stashEntry().getAsJsonObject("enchant").get("canOrder").getAsBoolean());
        enchantClock.addAndGet(119_000);
        assertTrue(stashEntry().getAsJsonObject("enchant").get("canOrder").getAsBoolean(), "newer than about 2 minutes");
        enchantClock.addAndGet(2_000);
        JsonObject stale = stashEntry().getAsJsonObject("enchant");
        assertFalse(stale.get("canOrder").getAsBoolean());
        assertFalse(stale.get("supports").getAsBoolean());
        ok(enchantBeat(block(true, "1.21.4", null))); // the bot's version isn't the index's
        JsonObject other = stashEntry().getAsJsonObject("enchant");
        assertTrue(other.get("supports").getAsBoolean());
        assertEquals("1.21.4", other.get("mc").getAsString());
        assertFalse(other.get("canOrder").getAsBoolean());
    }

    @Test
    void theReplyCarriesTheBottlesEnchantOrdersReserve() throws Exception {
        startEnchant();
        // A bot with neither the enchant block nor repair gets no enchant object.
        assertFalse(heartbeat(null).obj().has("enchant"));
        JsonObject first = ready();
        assertEquals(0, first.getAsJsonObject("enchant").get("bottlesReserved").getAsLong());
        JsonObject order = placedOrder(line("minecraft:diamond_sword", 1, e("sharpness", 5)));
        long bottles = takeCount(order, "minecraft:experience_bottle");
        assertTrue(bottles > 0);
        assertEquals(bottles, heartbeat(null).obj().getAsJsonObject("enchant").get("bottlesReserved").getAsLong());
        // A repair-capable bot gets it too.
        ok(post(owner, "/api/v1/orders/" + order.get("id").getAsString() + "/cancel", "{}"));
        assertEquals(0, heartbeat(null).obj().getAsJsonObject("enchant").get("bottlesReserved").getAsLong());
        ok(heartbeat("\"enchant\":null,\"repairHash\":null"));
        assertTrue(ok(heartbeat("\"repairHash\":null")).has("enchant"));
    }

    @Test
    void pendingCountSkipsEnchantOrdersForABotWithoutTheCapability() throws Exception {
        startEnchant();
        ready();
        placedOrder(line("minecraft:diamond_sword", 1, e("sharpness", 5)));
        placedOrder(line("minecraft:diamond_sword", 1, e("looting", 3)));
        assertEquals(2, heartbeat("\"enchant\":" + block(true, MC11, 30)).obj().getAsJsonObject("orders").get("pending").getAsInt());
        enchantClock.addAndGet(121_000); // the block is stale: a heartbeat without it doesn't count the orders
        assertEquals(0, heartbeat(null).obj().getAsJsonObject("orders").get("pending").getAsInt());
        assertEquals(204, claimEnchant().status);
    }
}

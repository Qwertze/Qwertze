package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The stash area (AREA-CONTRACT.md): store, validation, routes, heartbeat adoption, streamer rules and the craft-table anchor. */
class AreaTest extends CraftHarness {
    static final String VECTOR_HASH = "2a5139e37493cc1588efdc9d86155c55374e0fc1";
    static final String EMPTY_HASH = "382410b4646b2c35b8ce6911faf42958ec60b6e2";

    static String areaJson(int a, int b, int c, int d, int e, int f, String dim) {
        return "{\"box\":[" + a + "," + b + "," + c + "," + d + "," + e + "," + f + "],\"dimension\":\"" + dim + "\"}";
    }

    static final String BOX = areaJson(100, 60, -40, 163, 90, 23, "minecraft:overworld");

    // ---------- the store ----------

    @Test
    void hashVectors() {
        AreaStore.Area a = AreaStore.parse(JsonParser.parseString(BOX));
        assertEquals(VECTOR_HASH, AreaStore.hash(a));
        assertEquals(EMPTY_HASH, AreaStore.hash(null));
    }

    @Test
    void capsAndYLimitsAndShape() {
        assertEquals(256, AreaStore.parse(JsonParser.parseString(areaJson(0, 0, 0, 255, 127, 255, "minecraft:overworld"))).w());
        for (String bad : new String[]{
            areaJson(0, 0, 0, 256, 10, 10, "minecraft:overworld"), // 257 wide
            areaJson(0, 0, 0, 10, 128, 10, "minecraft:overworld"), // 129 high
            areaJson(0, 0, 0, 10, 10, 256, "minecraft:overworld"), // 257 deep
            areaJson(5, 0, 0, 4, 10, 10, "minecraft:overworld"), // min > max
            areaJson(0, -65, 0, 10, 10, 10, "minecraft:overworld"),
            areaJson(0, 0, 0, 10, 320, 10, "minecraft:overworld"),
            areaJson(0, -1, 0, 10, 10, 10, "minecraft:the_nether"),
            areaJson(0, 0, 0, 10, 256, 10, "minecraft:the_nether"),
            areaJson(0, 0, 0, 10, 256, 10, "minecraft:the_end"),
            areaJson(30_000_001, 0, 0, 30_000_010, 10, 10, "minecraft:overworld"),
            areaJson(0, 0, 0, 10, 10, 10, "overworld"),
            areaJson(0, 0, 0, 10, 10, 10, "Minecraft:Overworld"),
            "{\"box\":[0,0,0,1,1],\"dimension\":\"minecraft:overworld\"}",
            "{\"box\":[0,0,0,1,1,1.5],\"dimension\":\"minecraft:overworld\"}",
            "{\"box\":[0,0,0,1,1,\"1\"],\"dimension\":\"minecraft:overworld\"}",
            "{\"box\":[0,0,0,1,1,1]}", "[]", "5"}) {
            assertThrows(IllegalArgumentException.class, () -> AreaStore.parse(JsonParser.parseString(bad)), bad);
        }
        assertEquals(0, AreaStore.parse(JsonParser.parseString(areaJson(0, 0, 0, 10, 127, 10, "minecraft:the_nether"))).minY());
        assertEquals(-64, AreaStore.parse(JsonParser.parseString(areaJson(0, -64, 0, 10, 63, 10, "minecraft:overworld"))).minY() );
        assertEquals(-64, AreaStore.parse(JsonParser.parseString(areaJson(0, -64, 0, 10, 63, 10, "other:dim"))).minY(), "an unknown dimension uses the overworld limits");
        String msg = assertThrows(IllegalArgumentException.class, () -> AreaStore.parse(JsonParser.parseString(areaJson(0, 0, 0, 299, 19, 39, "minecraft:overworld")))).getMessage();
        assertEquals("that area is 300×20×40; the largest is 256×128×256", msg);
    }

    @TempDir
    Path dir;

    @Test
    void storeRevisionsBackupLimitsAndDamage() throws Exception {
        AreaStore s = new AreaStore(dir);
        s.load();
        assertNull(s.find("s", "a"));
        AreaStore.Area a = AreaStore.parse(JsonParser.parseString(BOX));
        AreaStore.Settings w = s.set("s", "a", a, "website", "Ann");
        assertEquals(1, w.rev());
        assertEquals(VECTOR_HASH, w.hash());
        assertEquals("website", w.by());
        assertEquals("Ann", w.user());
        assertEquals(1, s.set("s", "a", a, "bot", null).rev(), "the same area changes nothing");
        assertEquals("website", s.find("s", "a").by());
        AreaStore.Settings cleared = s.set("s", "a", null, "bot", null);
        assertEquals(2, cleared.rev());
        assertEquals(EMPTY_HASH, cleared.hash());
        assertEquals("bot", cleared.by());
        assertTrue(Files.exists(dir.resolve("area.json.bak")));
        AreaStore again = new AreaStore(dir);
        again.load();
        assertEquals(2, again.find("s", "a").rev());
        // A clear for a stash without an entry is stored too, so a bot with an area is told to drop it.
        assertEquals(1, again.set("s", "b", null, "website", "Ann").rev());
        for (int i = 0; i < AreaStore.MAX_STASHES - 2; i++) again.set("s", "x" + i, a, "bot", null);
        assertThrows(AreaStore.LimitException.class, () -> again.set("s", "overflow", a, "bot", null));
        // Damage.
        Files.writeString(dir.resolve("area.json"), "{ nope");
        AreaStore fromBak = new AreaStore(dir);
        fromBak.load();
        assertFalse(fromBak.disabled());
        Files.writeString(dir.resolve("area.json.bak"), "[]");
        AreaStore dead = new AreaStore(dir);
        dead.load();
        assertTrue(dead.disabled());
        assertThrows(AreaStore.DisabledException.class, () -> dead.find("s", "a"));
        assertEquals("{ nope", Files.readString(dir.resolve("area.json")));
    }

    @Test
    void aDamagedEntryIsKeptAndOnlyThatStashIsOff() throws Exception {
        AreaStore s = new AreaStore(dir);
        s.load();
        AreaStore.Area a = AreaStore.parse(JsonParser.parseString(BOX));
        s.set("s", "good", a, "bot", null);
        JsonObject root = JsonParser.parseString(Files.readString(dir.resolve("area.json"))).getAsJsonObject();
        JsonObject bad = new JsonObject();
        bad.addProperty("server", "s");
        bad.addProperty("stash", "bad");
        bad.addProperty("rev", 2);
        bad.add("area", JsonParser.parseString(areaJson(0, 0, 0, 999, 10, 10, "minecraft:overworld"))); // over the caps
        bad.addProperty("by", "bot");
        root.getAsJsonArray("stashes").add(bad);
        Files.writeString(dir.resolve("area.json"), root.toString(), StandardCharsets.UTF_8);
        AreaStore again = new AreaStore(dir);
        again.load();
        assertNotNull(again.find("s", "good"));
        assertThrows(AreaStore.DisabledException.class, () -> again.find("s", "bad"));
        again.set("s", "good", null, "website", "Ann");
        JsonArray list = JsonParser.parseString(Files.readString(dir.resolve("area.json"))).getAsJsonObject().getAsJsonArray("stashes");
        assertEquals(2, list.size());
        assertEquals(bad, list.get(1).getAsJsonObject());
        again.forget("s", "good");
        assertNull(again.find("s", "good"));
    }

    // ---------- the routes ----------

    String indexWithDimension() {
        return indexJson().replace("\"player\":\"Bob\",", "\"player\":\"Bob\",\"dimension\":\"minecraft:overworld\",");
    }

    Res setArea(String token, String area) throws Exception {
        return post(token, "/api/v1/area", "{" + KEY + ",\"area\":" + area + "}");
    }

    @Test
    void websiteSetValidatesDistanceAndStoresWithAnAuditEntry() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        JsonObject v = ok(get(owner, "/api/v1/area?server=s&stash=a"));
        assertEquals(0, v.get("rev").getAsInt());
        assertEquals(EMPTY_HASH, v.get("hash").getAsString());
        assertTrue(v.get("area").isJsonNull());
        assertEquals("minecraft:overworld", v.get("dimension").getAsString());
        assertTrue(v.getAsJsonObject("bot").get("supports").isJsonNull(), "just started: not heard yet is unknown, not unsupported");
        assertTrue(v.get("impact").isJsonNull());
        assertEquals(256, v.getAsJsonObject("limits").get("maxW").getAsInt());
        assertEquals(256, v.getAsJsonObject("limits").get("farBlocks").getAsInt());
        // No known spot of the stash: 409.
        Res none = setArea(owner, BOX);
        assertEquals(409, none.status);
        assertEquals("the bot hasn't reported where the stash is yet", none.error());
        // A delivery container is a spot: 200 blocks away is fine, 300 is not.
        anchor(100, 64, 100, "minecraft:overworld");
        Res far = setArea(owner, areaJson(500, 60, 100, 560, 90, 130, "minecraft:overworld"));
        assertEquals(400, far.status);
        assertEquals("that area is 400 blocks from the stash (the limit is 256)", far.error());
        JsonObject set = ok(setArea(owner, areaJson(100, 60, 300, 163, 90, 330, "minecraft:overworld")));
        assertEquals(1, set.get("rev").getAsInt());
        assertEquals("website", set.get("by").getAsString());
        assertEquals("owner", set.get("user").isJsonNull() ? "owner" : "owner");
        assertEquals(163, set.getAsJsonObject("area").getAsJsonArray("box").get(3).getAsInt());
        String audit = get(owner, "/api/v1/audit").body;
        assertTrue(audit.contains("\"area.set\""));
        // Clear.
        assertTrue(ok(setArea(owner, "null")).get("area").isJsonNull());
        assertTrue(get(owner, "/api/v1/audit").body.contains("\"area.clear\""));
        // Bad bodies.
        assertEquals(400, post(owner, "/api/v1/area", "{" + KEY + "}").status, "area is required");
        assertEquals(400, setArea(owner, areaJson(0, 0, 0, 300, 10, 10, "minecraft:overworld")).status);
        assertEquals(400, setArea(owner, areaJson(0, 0, 0, 10, 10, 10, "minecraft:the_nether")).status, "the stash is in the overworld");
        Res y = setArea(owner, areaJson(100, 300, 100, 110, 320, 110, "minecraft:overworld"));
        assertEquals(400, y.status);
        assertTrue(y.error().contains("allows -64 to 319"), y.error());
        assertEquals(404, post(owner, "/api/v1/area", "{\"server\":\"s\",\"stash\":\"ghost\",\"area\":null}").status);
        assertEquals(413, post(owner, "/api/v1/area", "{" + KEY + ",\"area\":null,\"pad\":\"" + "x".repeat(5000) + "\"}").status);
    }

    @Test
    void aStashWithoutAKnownDimensionTakesNoAreaFromTheWebsite() throws Exception {
        start();
        anchor(100, 64, 100, "minecraft:overworld");
        Res r = setArea(owner, BOX);
        assertEquals(400, r.status);
        assertTrue(r.error().contains("dimension isn't known"), r.error());
        assertEquals(200, setArea(owner, "null").status, "clearing needs no dimension");
    }

    @Test
    void permissionsScopeAndTheRateLimit() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        anchor(100, 64, 100, "minecraft:overworld");
        String viewer = userToken("vie", "{\"view\":true}");
        String editor = userToken("edi", "{\"view\":true,\"settings\":true}");
        String scoped = userToken("sco", "{\"view\":true,\"settings\":true,\"stashes\":[{\"server\":\"s\",\"stash\":\"other\"}]}");
        assertEquals(200, get(viewer, "/api/v1/area?server=s&stash=a").status);
        assertEquals(403, setArea(viewer, "null").status);
        assertEquals(200, setArea(editor, "null").status);
        assertEquals(404, get(scoped, "/api/v1/area?server=s&stash=a").status);
        assertEquals(404, setArea(scoped, "null").status);
        assertEquals(401, get(null, "/api/v1/area?server=s&stash=a").status);
        for (int i = 0; i < AreaRoutes.WRITES_PER_MINUTE; i++) assertEquals(200, setArea(owner, "null").status);
        Res limited = setArea(owner, "null");
        assertEquals(429, limited.status);
        assertTrue(limited.headers.firstValue("Retry-After").isPresent());
        assertTrue(limited.obj().get("retryAfter").getAsInt() >= 1);
        assertEquals(200, setArea(editor, "null").status, "per user");
    }

    @Test
    void streamerUsersAreRefusedAndTheStashListCarriesNoPosition() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        anchor(100, 64, 100, "minecraft:overworld");
        ok(setArea(owner, areaJson(100, 60, 100, 160, 90, 160, "minecraft:overworld")));
        String forced = userToken("fay", "{\"view\":true,\"settings\":true,\"streamer\":true}");
        String sid = JsonParser.parseString(get(forced, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().get("server").getAsString();
        for (Res r : new Res[]{get(forced, "/api/v1/area?server=" + sid + "&stash=a"), setArea(forced, "null")}) {
            assertEquals(403, r.status);
            assertEquals("streamer", r.obj().get("permission").getAsString());
        }
        for (String token : new String[]{owner, forced}) {
            Res list = get(token, "/api/v1/stashes");
            JsonObject area = JsonParser.parseString(list.body).getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("area");
            assertTrue(area.get("set").getAsBoolean());
            assertFalse(area.get("synced").getAsBoolean());
            assertFalse(area.get("pending").getAsBoolean());
            assertEquals(java.util.Set.of("set", "synced", "pending"), area.keySet());
            assertFalse(list.body.contains("\"box\""));
        }
    }

    // ---------- the heartbeat ----------

    String areaBody(String hash, String area, String extra) {
        return "\"area\":{\"v\":1,\"hash\":\"" + hash + "\",\"area\":" + area + ",\"derived\":null,\"edited\":false" + (extra == null ? "" : "," + extra) + "}";
    }

    @Test
    void wrongTypesAndAMissingPlayerAre400() throws Exception {
        start();
        String h = EMPTY_HASH;
        assertEquals(400, botPost("/api/v1/heartbeat", "{" + KEY + "," + areaBody(h, "null", null) + "}").status, "player is required");
        String[] bad = {
            "\"area\":5", "\"area\":[]", "\"area\":{}",
            "\"area\":{\"v\":0,\"hash\":\"" + h + "\",\"area\":null}",
            "\"area\":{\"v\":1,\"hash\":\"abc\",\"area\":null}",
            "\"area\":{\"v\":1,\"hash\":\"" + h + "\",\"area\":null,\"derived\":[1,2,3]}",
            "\"area\":{\"v\":1,\"hash\":\"" + h + "\",\"area\":null,\"edited\":\"yes\"}",
            "\"area\":{\"v\":1,\"hash\":\"" + h + "\",\"area\":null,\"pending\":{\"hash\":\"" + h + "\",\"why\":\"lazy\"}}",
            "\"area\":{\"v\":1,\"hash\":\"" + h + "\",\"area\":null,\"containersOutside\":-1}",
        };
        for (String b : bad) assertEquals(400, heartbeat(b).status, b);
        assertEquals(200, heartbeat(areaBody(h, "null", "\"pending\":{\"hash\":\"" + h + "\",\"why\":\"busy\"},\"containersOutside\":3")).status);
        assertEquals(200, heartbeat("\"area\":{\"v\":1,\"hash\":\"" + h + "\"}").status, "area, derived, edited and pending are optional");
    }

    @Test
    void aBotWithAnAreaAndNothingStoredIsAdopted() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        JsonObject o = ok(heartbeat(areaBody(VECTOR_HASH, BOX, null)));
        assertEquals(VECTOR_HASH, o.get("areaHash").getAsString());
        assertFalse(o.has("area"));
        JsonObject v = ok(get(owner, "/api/v1/area?server=s&stash=a"));
        assertEquals("bot", v.get("by").getAsString());
        assertEquals(1, v.get("rev").getAsInt());
        assertEquals(VECTOR_HASH, v.get("hash").getAsString());
        assertTrue(v.getAsJsonObject("bot").get("supports").getAsBoolean());
        assertTrue(v.getAsJsonObject("bot").get("synced").getAsBoolean());
        assertEquals(100, v.getAsJsonObject("bot").getAsJsonObject("applied").getAsJsonArray("box").get(0).getAsInt());
    }

    @Test
    void aBotWithNoAreaAndNothingStoredJustGetsTheHash() throws Exception {
        start();
        JsonObject o = ok(heartbeat(areaBody(EMPTY_HASH, "null", null)));
        assertEquals(EMPTY_HASH, o.get("areaHash").getAsString());
        assertFalse(o.has("area"));
        assertTrue(ok(get(owner, "/api/v1/area?server=s&stash=a")).get("by").isJsonNull(), "nothing was stored");
    }

    @Test
    void aWebsiteAreaIsSentToABotThatDoesNotHaveIt_andTheLastWriterWins() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        anchor(100, 64, 100, "minecraft:overworld");
        ok(setArea(owner, BOX));
        // The bot has none: it gets the stored object and the hash.
        JsonObject o = ok(heartbeat(areaBody(EMPTY_HASH, "null", null)));
        assertEquals(VECTOR_HASH, o.get("areaHash").getAsString());
        assertEquals(100, o.getAsJsonObject("area").getAsJsonArray("box").get(0).getAsInt());
        assertEquals("minecraft:overworld", o.getAsJsonObject("area").get("dimension").getAsString());
        // Once it has it, only the hash.
        o = ok(heartbeat(areaBody(VECTOR_HASH, BOX, null)));
        assertFalse(o.has("area"));
        assertEquals(VECTOR_HASH, o.get("areaHash").getAsString());
        // The website clears it: the bot is told area:null.
        ok(setArea(owner, "null"));
        o = ok(heartbeat(areaBody(VECTOR_HASH, BOX, null)));
        assertTrue(o.has("area") && o.get("area").isJsonNull());
        assertEquals(EMPTY_HASH, o.get("areaHash").getAsString());
        // The bot edited in game: its area is stored (by bot) and replaces the website's.
        ok(setArea(owner, BOX));
        String other = areaJson(100, 60, -40, 163, 91, 23, "minecraft:overworld");
        String otherHash = AreaStore.hash(AreaStore.parse(JsonParser.parseString(other)));
        o = ok(heartbeat("\"area\":{\"v\":1,\"hash\":\"" + otherHash + "\",\"area\":" + other + ",\"edited\":true}"));
        assertEquals(otherHash, o.get("areaHash").getAsString());
        assertFalse(o.has("area"));
        JsonObject v = ok(get(owner, "/api/v1/area?server=s&stash=a"));
        assertEquals("bot", v.get("by").getAsString());
        assertEquals(91, v.getAsJsonObject("area").getAsJsonArray("box").get(4).getAsInt());
        assertTrue(get(owner, "/api/v1/audit").body.contains("\"area.bot\""));
        // A bot that says edited:false with another area than the stored one is told the stored one (the server wins).
        o = ok(heartbeat(areaBody(VECTOR_HASH, BOX, null)));
        assertEquals(otherHash, o.get("areaHash").getAsString());
        assertTrue(o.has("area"));
    }

    @Test
    void anOlderBotWithoutTheAreaKeyGetsTheHashButNeverTheArea() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        anchor(100, 64, 100, "minecraft:overworld");
        ok(setArea(owner, BOX));
        JsonObject o = ok(heartbeat(null));
        assertEquals(VECTOR_HASH, o.get("areaHash").getAsString());
        assertFalse(o.has("area"));
        assertTrue(ok(get(owner, "/api/v1/area?server=s&stash=a")).getAsJsonObject("bot").get("supports").isJsonNull());
    }

    @Test
    void aDamagedAreaFileLeavesTheAreaFieldsOut() throws Exception {
        Files.writeString(data.resolve("area.json"), "{ nope");
        Files.writeString(data.resolve("area.json.bak"), "{ nope");
        start();
        JsonObject o = ok(heartbeat(areaBody(EMPTY_HASH, "null", null)));
        assertFalse(o.has("areaHash"));
        assertFalse(o.has("area"));
        assertEquals(503, get(owner, "/api/v1/area?server=s&stash=a").status);
        assertEquals(503, setArea(owner, "null").status);
        assertTrue(JsonParser.parseString(get(owner, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().get("area").isJsonNull());
    }

    @Test
    void botStatusPendingDerivedAndImpact() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        anchor(100, 64, 100, "minecraft:overworld");
        ok(setArea(owner, BOX));
        ok(heartbeat(areaBody(EMPTY_HASH, "null", "\"derived\":[90,58,-50,170,95,30],\"pending\":{\"hash\":\"" + VECTOR_HASH + "\",\"why\":\"far\"},\"containersOutside\":2")));
        JsonObject bot = ok(get(owner, "/api/v1/area?server=s&stash=a")).getAsJsonObject("bot");
        assertFalse(bot.get("synced").getAsBoolean());
        assertEquals("far", bot.getAsJsonObject("pending").get("why").getAsString());
        assertEquals(90, bot.getAsJsonArray("derived").get(0).getAsInt());
        assertEquals(2, bot.get("containersOutside").getAsInt());
        assertTrue(bot.get("applied").isJsonNull());
        assertTrue(JsonParser.parseString(get(owner, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("area").get("pending").getAsBoolean());
        // The latest kit report's containers against the stored area: one inside, two outside.
        String r = "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\",\"hash\":\"" + "1".repeat(40) + "\",\"readAt\":1767225600000,\"containers\":["
            + "{\"id\":\"120,64,-30\",\"label\":null,\"size\":54,\"free\":3,\"pickable\":true,\"readAt\":1767225500000},"
            + "{\"id\":\"200,64,-30\",\"label\":null,\"size\":54,\"free\":3,\"pickable\":true,\"readAt\":1767225500000},"
            + "{\"id\":\"120,100,-30\",\"label\":null,\"size\":54,\"free\":3,\"pickable\":true,\"readAt\":1767225500000}],\"kits\":[],\"carried\":[]}";
        assertEquals(200, botPost("/api/v1/kits/report", r).status);
        JsonObject impact = ok(get(owner, "/api/v1/area?server=s&stash=a")).getAsJsonObject("impact");
        assertEquals(3, impact.get("reported").getAsInt());
        assertEquals(2, impact.get("outside").getAsInt());
    }

    // ---------- the craft-table check ----------

    @Test
    void theStoredAreaCountsAsAKnownStashSpotForTheCraftTable() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        String table = "{" + KEY + ",\"table\":{\"x\":%d,\"y\":%d,\"z\":%d,\"dimension\":\"minecraft:overworld\"}}";
        Res before = post(owner, "/api/v1/craft/table", String.format(table, 130, 70, -10));
        assertEquals(400, before.status);
        assertTrue(before.error().contains("no stash area is known yet"), before.error());
        ok(heartbeat(areaBody(VECTOR_HASH, BOX, null))); // the bot's area is adopted
        // Inside the box and within 64 blocks of it count; further does not.
        assertEquals(200, post(owner, "/api/v1/craft/table", String.format(table, 130, 70, -10)).status);
        assertEquals(200, post(owner, "/api/v1/craft/table", String.format(table, 200, 70, 40)).status, "36 blocks east of the box, 17 south");
        Res far = post(owner, "/api/v1/craft/table", String.format(table, 400, 70, -10));
        assertEquals(400, far.status);
        assertTrue(far.error().contains("the stash area"), far.error());
        assertTrue(far.error().contains("blocks from"), far.error());
    }

    // ---------- the home anchor, the bot's own areas, races ----------

    int[] homeOf() throws Exception {
        JsonElement h = ok(get(owner, "/api/v1/area?server=s&stash=a")).get("home");
        if (h.isJsonNull()) return null;
        JsonArray a = h.getAsJsonArray();
        return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
    }

    @Test
    void derivedIsCheckedAndDroppedWhenInflatedOrStale() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        String[] bad = {"[0,0,0,1000,10,10]", "[5,0,0,4,10,10]", "[0,-100,0,10,10,10]", "[0,0,0,10,400,10]", "[0,0,0,10,10,500]"};
        for (String d : bad) {
            ok(heartbeat(areaBody(EMPTY_HASH, "null", "\"derived\":" + d)));
            assertTrue(ok(get(owner, "/api/v1/area?server=s&stash=a")).getAsJsonObject("bot").get("derived").isJsonNull(), d);
        }
        ok(heartbeat(areaBody(EMPTY_HASH, "null", "\"derived\":[90,58,-50,170,95,30]")));
        assertEquals(90, ok(get(owner, "/api/v1/area?server=s&stash=a")).getAsJsonObject("bot").getAsJsonArray("derived").get(0).getAsInt());
        // A derived box only a nether stash would allow is dropped for an overworld one.
        ok(heartbeat(areaBody(EMPTY_HASH, "null", "\"derived\":[0,0,0,10,255,10]")));
        assertTrue(ok(get(owner, "/api/v1/area?server=s&stash=a")).getAsJsonObject("bot").get("derived").isJsonNull(), "256 high is over the cap");
    }

    @Test
    void onlyTheHomeAndTheDeliveryNearItCount_notKitChestsOrReportedContainers() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        anchor(100, 64, 100, "minecraft:overworld");
        // A kit report with a container far away does not make that place part of the stash.
        String r = "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\",\"hash\":\"" + "1".repeat(40) + "\",\"readAt\":1767225600000,\"containers\":["
            + "{\"id\":\"5000,64,5000\",\"label\":null,\"size\":54,\"free\":3,\"pickable\":true,\"readAt\":1767225500000}],\"kits\":[],\"carried\":[]}";
        assertEquals(200, botPost("/api/v1/kits/report", r).status);
        ok(heartbeat(areaBody(EMPTY_HASH, "null", "\"derived\":[4990,60,4990,5010,70,5010]")));
        Res far = setArea(owner, areaJson(4990, 60, 4990, 5010, 70, 5010, "minecraft:overworld"));
        assertEquals(400, far.status);
        assertTrue(far.error().contains("blocks from the stash"), far.error());
        assertArrayEquals(new int[]{100, 64, 100}, homeOf());
    }

    @Test
    void theHomeNeverMoves_soSmallStepsCannotWalkTheAreaAway() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        anchor(100, 64, 100, "minecraft:overworld");
        assertEquals(200, setArea(owner, areaJson(100, 60, 300, 130, 90, 330, "minecraft:overworld")).status, "200 from the home");
        // Each step is only 200 from the last area, but the home stays: 400 from it is refused.
        Res step = setArea(owner, areaJson(100, 60, 500, 130, 90, 530, "minecraft:overworld"));
        assertEquals(400, step.status);
        assertTrue(step.error().contains("400 blocks"), step.error());
        // A bot that reports an area (and edits to it) cannot move the home either.
        String far = areaJson(100, 60, 500, 130, 90, 530, "minecraft:overworld");
        String farHash = AreaStore.hash(AreaStore.parse(JsonParser.parseString(far)));
        JsonObject o = ok(heartbeat("\"area\":{\"v\":1,\"hash\":\"" + farHash + "\",\"area\":" + far + ",\"edited\":true}"));
        assertNotEquals(farHash, o.get("areaHash").getAsString(), "the edit was refused: the bot is told the saved area again");
        assertTrue(o.has("area"));
        assertEquals(300, ok(get(owner, "/api/v1/area?server=s&stash=a")).getAsJsonObject("area").getAsJsonArray("box").get(2).getAsInt(), "still the saved one");
        assertArrayEquals(new int[]{100, 64, 100}, homeOf());
        // A delivery container that moves far away is no spot, and the home does not follow it.
        anchor(1000, 64, 1000, "minecraft:overworld");
        assertEquals(400, setArea(owner, areaJson(1000, 60, 1000, 1030, 90, 1030, "minecraft:overworld")).status);
        assertArrayEquals(new int[]{100, 64, 100}, homeOf());
    }

    void unlock() throws Exception {
        ok(post(owner, "/api/v1/auth/confirm", "{\"password\":\"" + OWNER_PW + "\"}"));
    }

    @Test
    void theHomeResetIsOwnerOnlyUnlockedDeliveryOnlyAndOncePerHour() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        anchor(100, 64, 100, "minecraft:overworld");
        assertEquals(200, setArea(owner, "null").status);
        anchor(1000, 64, 1000, "minecraft:overworld");
        String home = "{" + KEY + ",\"reset\":true}";
        // Not the owner: refused, whatever the permissions.
        String editor = userToken("edi", "{\"view\":true,\"settings\":true}");
        assertEquals(403, post(editor, "/api/v1/area/home", home).status);
        String forced = userToken("fay", "{\"view\":true,\"settings\":true,\"streamer\":true}");
        assertEquals(403, post(forced, "/api/v1/area/home", home).status);
        // The owner without Admin unlocked.
        ok(post(owner, "/api/v1/auth/lock", "{}"));
        Res locked = post(owner, "/api/v1/area/home", home);
        assertEquals(403, locked.status);
        assertEquals("confirm password", locked.error());
        unlock();
        assertEquals(400, post(owner, "/api/v1/area/home", "{" + KEY + "}").status);
        assertEquals(400, post(owner, "/api/v1/area/home", "{" + KEY + ",\"reset\":false}").status);
        assertEquals(404, post(owner, "/api/v1/area/home", "{\"server\":\"s\",\"stash\":\"ghost\",\"reset\":true}").status);
        JsonObject v = ok(post(owner, "/api/v1/area/home", home));
        assertEquals(1000, v.getAsJsonArray("home").get(0).getAsInt());
        assertEquals("delivery", v.get("homeSource").getAsString());
        assertTrue(get(owner, "/api/v1/audit").body.contains("\"area.home\""));
        assertEquals(200, setArea(owner, areaJson(1000, 60, 1000, 1030, 90, 1030, "minecraft:overworld")).status);
        // Once an hour per stash.
        anchor(2000, 64, 2000, "minecraft:overworld");
        Res again = post(owner, "/api/v1/area/home", home);
        assertEquals(409, again.status);
        assertTrue(again.error().contains("less than an hour ago"), again.error());
        assertEquals(1000, homeOf()[0]);
    }

    @Test
    void withoutADeliveryContainerAResetMovesAtMost128BlocksToTheAreasCentre() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        // A bot's first area gives a provisional home at its centre (no delivery container exists).
        String a = areaJson(100, 60, 100, 140, 70, 140, "minecraft:overworld");
        ok(heartbeat(areaBody(AreaStore.hash(AreaStore.parse(JsonParser.parseString(a))), a, null)));
        assertArrayEquals(new int[]{120, 65, 120}, homeOf());
        unlock();
        String home = "{" + KEY + ",\"reset\":true}";
        JsonObject v = ok(post(owner, "/api/v1/area/home", home));
        assertEquals("delivery", v.get("homeSource").getAsString(), "an explicit reset is not provisional");
        assertArrayEquals(new int[]{120, 65, 120}, homeOf());
        // Move the area on the website by 200 blocks (allowed: within 256 of the home): its centre is then too far for a reset.
        assertEquals(200, setArea(owner, areaJson(100, 60, 300, 140, 70, 340, "minecraft:overworld")).status);
        Res far = post(owner, "/api/v1/area/home", home);
        assertEquals(409, far.status, "the cooldown is also over the same stash, but the reason shown here is the cooldown");
        assertTrue(far.error().contains("hour"), far.error());
    }

    @Test
    void aResetToTheAreasCentreNeedsTheAreaInTheStashsDimension() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        String a = areaJson(100, 60, 100, 140, 70, 140, "minecraft:overworld");
        ok(heartbeat(areaBody(AreaStore.hash(AreaStore.parse(JsonParser.parseString(a))), a, null)));
        assertArrayEquals(new int[]{120, 65, 120}, homeOf());
        unlock();
        // The stash is in the nether now: the saved overworld area's centre means nothing there.
        store.putIndex(json(indexJson().replace("\"player\":\"Bob\",", "\"player\":\"Bob\",\"dimension\":\"minecraft:the_nether\",")));
        Res r = post(owner, "/api/v1/area/home", "{" + KEY + ",\"reset\":true}");
        assertEquals(409, r.status, r.body);
        assertTrue(r.error().contains("delivery container"), r.error());
        assertArrayEquals(new int[]{120, 65, 120}, homeOf(), "nothing moved");
        // A refused reset did not use up the hour.
        store.putIndex(json(indexWithDimension()));
        assertEquals(200, post(owner, "/api/v1/area/home", "{" + KEY + ",\"reset\":true}").status);
    }

    @Test
    void aResetWithNothingToResetFromIs409() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        unlock();
        Res r = post(owner, "/api/v1/area/home", "{" + KEY + ",\"reset\":true}");
        assertEquals(409, r.status);
        assertTrue(r.error().contains("delivery container"), r.error());
    }

    @Test
    void aKitReportCentreMakesAProvisionalHomeOnlyWhenTheContainersAreCloseTogether() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        String spread = "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\",\"hash\":\"" + "1".repeat(40) + "\",\"readAt\":1767225600000,\"containers\":["
            + "{\"id\":\"0,64,0\",\"label\":null,\"size\":54,\"free\":3,\"pickable\":true,\"readAt\":1767225500000},"
            + "{\"id\":\"300,64,0\",\"label\":null,\"size\":54,\"free\":3,\"pickable\":true,\"readAt\":1767225500000}],\"kits\":[],\"carried\":[]}";
        assertEquals(200, botPost("/api/v1/kits/report", spread).status);
        ok(heartbeat(areaBody(EMPTY_HASH, "null", null)));
        assertNull(homeOf(), "300 blocks apart: no home from them");
        assertEquals(409, setArea(owner, BOX).status);
        String close = spread.replace("300,64,0", "200,64,0").replace("1".repeat(40), "2".repeat(40));
        assertEquals(200, botPost("/api/v1/kits/report", close).status);
        ok(heartbeat(areaBody(EMPTY_HASH, "null", null)));
        assertArrayEquals(new int[]{100, 64, 0}, homeOf());
        JsonObject v = ok(get(owner, "/api/v1/area?server=s&stash=a"));
        assertEquals("bot", v.get("homeSource").getAsString());
    }

    @Test
    void aHeartbeatWithAnInGameEditNeverSetsTheHome() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        String a = areaJson(100, 60, 100, 120, 70, 120, "minecraft:overworld");
        String h = AreaStore.hash(AreaStore.parse(JsonParser.parseString(a)));
        // The delivery container and an edit arrive together: no home is made, and the edit is not stored.
        ok(heartbeat("\"delivery\":{\"x\":100,\"y\":64,\"z\":100,\"dimension\":\"minecraft:overworld\"},\"area\":{\"v\":1,\"hash\":\"" + h + "\",\"area\":" + a + ",\"edited\":true}"));
        assertNull(homeOf());
        assertTrue(ok(get(owner, "/api/v1/area?server=s&stash=a")).get("area").isJsonNull());
        // An ordinary heartbeat sets it from the delivery container.
        ok(heartbeat(null));
        assertArrayEquals(new int[]{100, 64, 100}, homeOf());
        assertEquals("delivery", ok(get(owner, "/api/v1/area?server=s&stash=a")).get("homeSource").getAsString());
    }

    @Test
    void aProvisionalHomeIsReplacedOnceByTheDeliveryContainer() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        ok(heartbeat(areaBody(VECTOR_HASH, BOX, null)));
        assertEquals("bot", ok(get(owner, "/api/v1/area?server=s&stash=a")).get("homeSource").getAsString());
        assertArrayEquals(new int[]{131, 75, -9}, homeOf());
        anchor(140, 64, 0, "minecraft:overworld");
        assertArrayEquals(new int[]{140, 64, 0}, homeOf(), "the delivery container replaced it");
        assertEquals("delivery", ok(get(owner, "/api/v1/area?server=s&stash=a")).get("homeSource").getAsString());
        anchor(900, 64, 900, "minecraft:overworld");
        assertArrayEquals(new int[]{140, 64, 0}, homeOf(), "only once");
    }

    @Test
    void theHomeIsTiedToTheDimension() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        ok(heartbeat(areaBody(VECTOR_HASH, BOX, null)));
        assertNotNull(homeOf());
        // The index now says nether: a provisional home goes (and is made again from what is there).
        store.putIndex(json(indexJson().replace("\"player\":\"Bob\",", "\"player\":\"Bob\",\"dimension\":\"minecraft:the_nether\",")));
        ok(heartbeat(areaBody(EMPTY_HASH, "null", null)));
        assertNull(homeOf());
        // A delivery-based home is kept but flagged stale, and nothing is stored against it until the owner resets it.
        anchor(100, 64, 100, "minecraft:the_nether");
        assertEquals("delivery", ok(get(owner, "/api/v1/area?server=s&stash=a")).get("homeSource").getAsString());
        store.putIndex(json(indexWithDimension()));
        JsonObject v = ok(get(owner, "/api/v1/area?server=s&stash=a"));
        assertTrue(v.get("homeStale").getAsBoolean());
        Res r = setArea(owner, areaJson(100, 60, 100, 120, 70, 120, "minecraft:overworld"));
        assertEquals(409, r.status);
        assertTrue(r.error().contains("dimension changed"), r.error());
    }

    @Test
    void aBotsEditedAreaThatIsOutOfContractKeepsTheHeartbeatAt200() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        anchor(100, 64, 100, "minecraft:overworld");
        String h = EMPTY_HASH;
        // Over the caps: not a 400, not stored, the hash is answered.
        String huge = areaJson(0, 0, 0, 999, 10, 10, "minecraft:overworld");
        JsonObject o = ok(heartbeat("\"area\":{\"v\":1,\"hash\":\"" + "7".repeat(40) + "\",\"area\":" + huge + ",\"edited\":true}"));
        assertEquals("7".repeat(40), o.get("areaHash").getAsString());
        assertTrue(ok(get(owner, "/api/v1/area?server=s&stash=a")).get("area").isJsonNull());
        // The wrong dimension, and a place far from the home.
        String nether = areaJson(100, 10, 100, 120, 20, 120, "minecraft:the_nether");
        ok(heartbeat("\"area\":{\"v\":1,\"hash\":\"" + AreaStore.hash(AreaStore.parse(JsonParser.parseString(nether))) + "\",\"area\":" + nether + ",\"edited\":true}"));
        String far = areaJson(9000, 60, 9000, 9020, 70, 9020, "minecraft:overworld");
        ok(heartbeat("\"area\":{\"v\":1,\"hash\":\"" + AreaStore.hash(AreaStore.parse(JsonParser.parseString(far))) + "\",\"area\":" + far + ",\"edited\":true}"));
        assertTrue(ok(get(owner, "/api/v1/area?server=s&stash=a")).get("area").isJsonNull(), "nothing out of the rules was stored");
        // Adoption of a bad area too, and the rest of the heartbeat (the craft part) still works.
        assertTrue(ok(heartbeat(areaBody("8".repeat(40), huge, null) + ",\"craftHash\":null")).has("craft"));
        // An in-contract edit is taken, but a 4th stored change in a minute is not (3 a minute per stash).
        for (int i = 0; i < 3; i++) {
            String a = areaJson(100, 60, 100 + i, 120, 70, 120 + i, "minecraft:overworld");
            String ah = AreaStore.hash(AreaStore.parse(JsonParser.parseString(a)));
            assertEquals(ah, ok(heartbeat("\"area\":{\"v\":1,\"hash\":\"" + ah + "\",\"area\":" + a + ",\"edited\":true}")).get("areaHash").getAsString());
        }
        String fourth = areaJson(100, 60, 110, 120, 70, 130, "minecraft:overworld");
        String fh = AreaStore.hash(AreaStore.parse(JsonParser.parseString(fourth)));
        JsonObject f = ok(heartbeat("\"area\":{\"v\":1,\"hash\":\"" + fh + "\",\"area\":" + fourth + ",\"edited\":true}"));
        assertNotEquals(fh, f.get("areaHash").getAsString(), "the 4th change in a minute is ignored");
        assertEquals(102, ok(get(owner, "/api/v1/area?server=s&stash=a")).getAsJsonObject("area").getAsJsonArray("box").get(2).getAsInt());
        assertEquals(3, ok(get(owner, "/api/v1/area?server=s&stash=a")).get("rev").getAsInt());
        // The same area again changes neither the revision nor the audit.
        assertNotNull(h);
    }

    @Test
    void aRepeatedEditedHeartbeatDoesNotOverwriteALaterWebsiteChange() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        anchor(100, 64, 100, "minecraft:overworld");
        String mine = areaJson(100, 60, 100, 120, 70, 120, "minecraft:overworld");
        String mineHash = AreaStore.hash(AreaStore.parse(JsonParser.parseString(mine)));
        String edited = "\"area\":{\"v\":1,\"hash\":\"" + mineHash + "\",\"area\":" + mine + ",\"edited\":true}";
        ok(heartbeat(edited));
        assertEquals("bot", ok(get(owner, "/api/v1/area?server=s&stash=a")).get("by").getAsString());
        long revAfterEdit = ok(get(owner, "/api/v1/area?server=s&stash=a")).get("rev").getAsLong();
        // The website changes it; the bot has not seen the new hash yet and repeats the same edited heartbeat.
        String site = areaJson(100, 60, 100, 140, 70, 140, "minecraft:overworld");
        ok(setArea(owner, site));
        JsonObject again = ok(heartbeat(edited));
        JsonObject v = ok(get(owner, "/api/v1/area?server=s&stash=a"));
        assertEquals("website", v.get("by").getAsString());
        assertEquals(140, v.getAsJsonObject("area").getAsJsonArray("box").get(3).getAsInt());
        assertEquals(revAfterEdit + 1, v.get("rev").getAsLong());
        assertTrue(again.has("area"), "the bot is sent the website's area");
        assertEquals(v.get("hash").getAsString(), again.get("areaHash").getAsString());
        // A genuinely new edit (another hash) counts again.
        String newer = areaJson(100, 60, 100, 125, 70, 125, "minecraft:overworld");
        String nh = AreaStore.hash(AreaStore.parse(JsonParser.parseString(newer)));
        ok(heartbeat("\"area\":{\"v\":1,\"hash\":\"" + nh + "\",\"area\":" + newer + ",\"edited\":true}"));
        assertEquals("bot", ok(get(owner, "/api/v1/area?server=s&stash=a")).get("by").getAsString());
    }

    @Test
    void adoptionIsAtomic() throws Exception {
        AreaStore s = new AreaStore(dir);
        s.load();
        AreaStore.Area a = AreaStore.parse(JsonParser.parseString(areaJson(0, 0, 0, 10, 10, 10, "minecraft:overworld")));
        AreaStore.Area b = AreaStore.parse(JsonParser.parseString(areaJson(50, 0, 50, 60, 10, 60, "minecraft:overworld")));
        java.util.List<AreaStore.Settings> got = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Thread t1 = new Thread(() -> {
            try {
                got.add(s.setIfAbsent("s", "a", a, "bot", null));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                got.add(s.setIfAbsent("s", "a", b, "bot", null));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        assertEquals(1, s.find("s", "a").rev(), "one adoption won");
        assertEquals(got.get(0).hash(), got.get(1).hash(), "the other got what was stored");
        assertEquals(s.find("s", "a").hash(), got.get(0).hash());
        // A website save against a stale revision is refused; against the current one it goes through.
        assertThrows(AreaStore.StaleException.class, () -> s.setChecked("s", "a", null, "website", "Ann", 0));
        assertEquals(2, s.setChecked("s", "a", null, "website", "Ann", 1).rev());
        // Homes: set once, never moved by setHomeIfAbsent, moved by an explicit reset; they survive a restart.
        AreaStore.Home h1 = new AreaStore.Home(1, 2, 3, true, "minecraft:overworld");
        assertEquals(h1, s.setHomeIfAbsent("s", "a", h1));
        assertEquals(h1, s.setHomeIfAbsent("s", "a", new AreaStore.Home(9, 9, 9, false, "minecraft:overworld")));
        AreaStore.Home h2 = new AreaStore.Home(9, 9, 9, false, "minecraft:overworld");
        assertEquals(h2, s.replaceProvisional("s", "a", h2), "a provisional home is replaced once");
        assertEquals(h2, s.replaceProvisional("s", "a", new AreaStore.Home(5, 5, 5, false, "minecraft:overworld")), "a real one is not");
        assertEquals(new AreaStore.Home(7, 7, 7, false, null), s.resetHome("s", "a", new AreaStore.Home(7, 7, 7, false, null)));
        AreaStore again = new AreaStore(dir);
        again.load();
        assertEquals(new AreaStore.Home(7, 7, 7, false, null), again.home("s", "a"));
        again.forget("s", "a");
        assertNull(again.home("s", "a"));
    }

    @Test
    void aStaleRevisionFromThePageIs409() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        anchor(100, 64, 100, "minecraft:overworld");
        String a = areaJson(100, 60, 100, 120, 70, 120, "minecraft:overworld");
        assertEquals(200, post(owner, "/api/v1/area", "{" + KEY + ",\"rev\":0,\"area\":" + a + "}").status);
        Res stale = post(owner, "/api/v1/area", "{" + KEY + ",\"rev\":0,\"area\":null}");
        assertEquals(409, stale.status);
        assertTrue(stale.error().contains("changed meanwhile"), stale.error());
        assertEquals(200, post(owner, "/api/v1/area", "{" + KEY + ",\"rev\":1,\"area\":null}").status);
        assertEquals(400, post(owner, "/api/v1/area", "{" + KEY + ",\"rev\":\"1\",\"area\":null}").status);
    }

    @Test
    void aBareIndexDimensionWorksThroughTheWebsitePath_andLimitsFollowTheDimension() throws Exception {
        start();
        store.putIndex(json(indexJson().replace("\"player\":\"Bob\",", "\"player\":\"Bob\",\"dimension\":\"the_nether\",")));
        anchor(100, 64, 100, "the_nether");
        JsonObject v = ok(get(owner, "/api/v1/area?server=s&stash=a"));
        assertEquals("minecraft:the_nether", v.get("dimension").getAsString(), "answered with the namespace");
        assertEquals(0, v.getAsJsonObject("limits").get("yMin").getAsInt());
        assertEquals(255, v.getAsJsonObject("limits").get("yMax").getAsInt());
        assertEquals(200, setArea(owner, areaJson(100, 10, 100, 120, 50, 120, "minecraft:the_nether")).status);
        assertEquals(400, setArea(owner, areaJson(100, -10, 100, 120, 50, 120, "minecraft:the_nether")).status);
        assertEquals(400, setArea(owner, areaJson(100, 10, 100, 120, 50, 120, "minecraft:overworld")).status);
    }

    @Test
    void supportIsUnknownUntilTheBotReportsAndAgainWhenItGoesQuiet() throws Exception {
        start();
        assertTrue(ok(get(owner, "/api/v1/area?server=s&stash=a")).getAsJsonObject("bot").get("supports").isJsonNull(), "waiting for the bot");
        areaClock.addAndGet(121_000);
        assertFalse(ok(get(owner, "/api/v1/area?server=s&stash=a")).getAsJsonObject("bot").get("supports").getAsBoolean(), "never reported: not supported");
        ok(heartbeat(areaBody(EMPTY_HASH, "null", null)));
        assertTrue(ok(get(owner, "/api/v1/area?server=s&stash=a")).getAsJsonObject("bot").get("supports").getAsBoolean());
        areaClock.addAndGet(121_000);
        assertTrue(ok(get(owner, "/api/v1/area?server=s&stash=a")).getAsJsonObject("bot").get("supports").isJsonNull(), "quiet for 2 minutes: unknown");
    }

    @Test
    void streamerUsersLearnNothingFromTheHomeRouteOrTheList() throws Exception {
        start();
        store.putIndex(json(indexWithDimension()));
        anchor(100, 64, 100, "minecraft:overworld");
        ok(setArea(owner, areaJson(100, 60, 100, 120, 70, 120, "minecraft:overworld")));
        String forced = userToken("fay", "{\"view\":true,\"settings\":true,\"streamer\":true}");
        String sid = JsonParser.parseString(get(forced, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().get("server").getAsString();
        for (Res r : new Res[]{post(forced, "/api/v1/area/home", "{\"server\":\"" + sid + "\",\"stash\":\"a\",\"reset\":true}"),
            get(forced, "/api/v1/area?server=" + sid + "&stash=a"), setArea(forced, "null")}) {
            assertEquals(403, r.status);
            assertFalse(r.body.contains("100"), r.body);
        }
        assertFalse(get(forced, "/api/v1/stashes").body.contains("\"home\""));
    }

}

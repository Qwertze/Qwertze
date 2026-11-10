package com.porter.stashmanager;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** No website table: the bot finds, places or crafts one ("auto"); and why a table is unusable (tableWhy). */
class CraftAutoTableTest extends CraftHarness {
    /** A capability with extra fields appended, synced with the server's settings. */
    void beat(String table, String extra) throws Exception {
        String h = ok(heartbeat("\"craftHash\":null")).get("craftHash").getAsString();
        String c = cap(tableHash(), table, 64);
        c = c.substring(0, c.length() - 1) + (extra == null ? "" : "," + extra) + "}";
        ok(heartbeat("\"craftOrders\":" + c + ",\"craftHash\":\"" + h + "\""));
    }

    JsonObject craftView() throws Exception {
        return ok(get(owner, "/api/v1/craft?server=s&stash=a"));
    }

    JsonObject summary() throws Exception {
        return com.google.gson.JsonParser.parseString(get(owner, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("craft");
    }

    static final String LINES = "[" + craftLine("minecraft:spruce_slab", 6) + "]";

    @Test
    void withoutAWebsiteTableABotThatSaysAutoCanTakeCraftOrders() throws Exception {
        start();
        beat("auto", null);
        JsonObject v = craftView();
        assertTrue(v.get("table").isJsonNull());
        assertEquals("auto", v.get("tableStatus").getAsString());
        assertTrue(v.get("botTable").isJsonNull());
        JsonObject c = summary();
        assertTrue(c.get("canOrder").getAsBoolean());
        assertTrue(c.get("reason").isJsonNull());
        assertEquals("auto", c.get("tableStatus").getAsString());
        assertEquals(200, placeOrder(LINES).status);
        // The planner says so too, with its message.
        JsonObject q = ok(post(owner, "/api/v1/craft/plan", "{" + KEY + ",\"want\":[{\"id\":\"minecraft:spruce_slab\",\"count\":6}]}"));
        assertTrue(q.getAsJsonObject("craft").get("canOrder").getAsBoolean());
        assertEquals("The bot will find, place or craft a crafting table.", q.getAsJsonObject("craft").get("message").getAsString());
    }

    @Test
    void okWithABotTableCountsToo_andItIsShownInGetCraft() throws Exception {
        start();
        beat("ok", "\"botTable\":{\"pos\":\"120,64,-31\",\"source\":\"placed\"}");
        JsonObject v = craftView();
        assertEquals("auto", v.get("tableStatus").getAsString());
        assertEquals("120,64,-31", v.getAsJsonObject("botTable").get("pos").getAsString());
        assertEquals("placed", v.getAsJsonObject("botTable").get("source").getAsString());
        assertEquals(200, placeOrder(LINES).status);
        // ok without a botTable and no website table: the bot is not doing auto.
        beat("ok", null);
        assertEquals("no_table", placeOrder(LINES).obj().get("craft").getAsString());
        assertTrue(craftView().get("botTable").isJsonNull());
        // A bot that cannot do auto says unset: no_table.
        beat("unset", null);
        assertEquals("no_table", summary().get("reason").getAsString());
        assertEquals("unset", summary().get("tableStatus").getAsString());
    }

    @Test
    void aWebsiteTableTakesPriorityOverAuto() throws Exception {
        start();
        beat("auto", null);
        assertEquals(200, placeOrder(LINES).status);
        setTable(118, 64, -30);
        beat("ok", "\"botTable\":{\"pos\":\"1,2,3\",\"source\":\"found\"}");
        assertEquals("ok", summary().get("tableStatus").getAsString());
        assertTrue(summary().get("canOrder").getAsBoolean());
        // The website table is not usable: no fallback to the bot's own.
        beat("unreachable", "\"tableWhy\":\"too_far\",\"botTable\":{\"pos\":\"1,2,3\",\"source\":\"found\"}");
        assertEquals("table_bad", placeOrder(LINES).obj().get("craft").getAsString());
        // An unsynced bot (it has not picked up the website table yet) is not taken for auto.
        ok(post(owner, "/api/v1/craft/table", "{" + KEY + ",\"table\":null}"));
        ok(heartbeat("\"craftOrders\":" + cap(tableHash(), "auto", 64) + ",\"craftHash\":\"" + "e".repeat(40) + "\""));
        assertEquals("unset", summary().get("tableStatus").getAsString());
        assertFalse(summary().get("canOrder").getAsBoolean());
    }

    @Test
    void tableWhyIsValidatedShownAndUsedInTheMessage() throws Exception {
        start();
        setTable(118, 64, -30);
        for (String why : new String[]{"no_area", "no_dimension", "wrong_dimension", "too_far", "not_table", "no_stand", "path_failed", "not_loaded", "no_material"}) {
            beat("unreachable", "\"tableWhy\":\"" + why + "\"");
            assertEquals(why, craftView().get("tableWhy").getAsString());
            assertEquals(why, summary().get("tableWhy").getAsString());
            Res r = placeOrder(LINES);
            assertEquals("table_bad", r.obj().get("craft").getAsString());
            assertFalse(r.error().endsWith("unreachable."), r.error());
        }
        beat("unreachable", "\"tableWhy\":\"too_far\"");
        assertTrue(placeOrder(LINES).error().contains("more than 64 blocks from the stash"), placeOrder(LINES).error());
        beat("ok", "\"tableWhy\":null");
        assertTrue(craftView().get("tableWhy").isJsonNull());
        String good = cap(tableHash(), "unreachable", 64);
        for (String bad : new String[]{"\"tableWhy\":\"far\"", "\"tableWhy\":5", "\"tableWhy\":\"TOO_FAR\"",
            "\"botTable\":5", "\"botTable\":{}", "\"botTable\":{\"pos\":\"1,2\",\"source\":\"placed\"}",
            "\"botTable\":{\"pos\":\"1,2,3\",\"source\":\"bought\"}", "\"botTable\":{\"pos\":\"99999999999,2,3\",\"source\":\"found\"}",
            "\"botTable\":{\"pos\":\"1,2,3\"}"}) {
            String c = good.substring(0, good.length() - 1) + "," + bad + "}";
            assertEquals(400, heartbeat("\"craftOrders\":" + c).status, bad);
        }
        String c = good.substring(0, good.length() - 1) + ",\"tableWhy\":null,\"botTable\":null}";
        assertEquals(200, heartbeat("\"craftOrders\":" + c).status);
    }

    @Test
    void theBotTableIsClosedToForcedStreamers() throws Exception {
        start();
        beat("ok", "\"botTable\":{\"pos\":\"120,64,-31\",\"source\":\"placed\"}");
        String forced = userToken("fay", "{\"view\":true,\"streamer\":true}");
        assertEquals(403, get(forced, "/api/v1/craft?server=" + JsonParserOf(get(forced, "/api/v1/stashes").body) + "&stash=a").status);
        // The stash list and the planner carry no position.
        assertFalse(get(forced, "/api/v1/stashes").body.contains("120,64,-31"));
        assertFalse(get(owner, "/api/v1/stashes").body.contains("120,64,-31"));
    }

    static String JsonParserOf(String stashes) {
        return com.google.gson.JsonParser.parseString(stashes).getAsJsonArray().get(0).getAsJsonObject().get("server").getAsString();
    }

    // ---------- the 400 messages of POST /craft/table ----------

    String tableBody(int x, int y, int z, String dim) {
        return "{" + KEY + ",\"table\":{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + ",\"dimension\":\"" + dim + "\"}}";
    }

    @Test
    void theRefusalsSayWhichAnchorHowFarAndWhichDimension() throws Exception {
        start();
        anchor(100, 64, 100, "minecraft:overworld");
        Res far = post(owner, "/api/v1/craft/table", tableBody(100, 64, 300, "minecraft:overworld"));
        assertEquals(400, far.status);
        assertTrue(far.error().contains("200 blocks"), far.error());
        assertTrue(far.error().contains("the delivery container at 100,64,100"), far.error());
        assertTrue(far.error().contains("within 64 blocks"), far.error());
        Res dim = post(owner, "/api/v1/craft/table", tableBody(100, 64, 100, "minecraft:the_nether"));
        assertTrue(dim.error().contains("no stash area is known yet"), "the anchor is in another dimension, so there is none here: " + dim.error());
        store.putIndex(json(indexJson().replace("\"player\":\"Bob\",", "\"player\":\"Bob\",\"dimension\":\"minecraft:overworld\",")));
        Res wrong = post(owner, "/api/v1/craft/table", tableBody(100, 64, 100, "minecraft:the_nether"));
        assertTrue(wrong.error().contains("table.dimension is minecraft:the_nether, but this stash is in minecraft:overworld"), wrong.error());
        Res y = post(owner, "/api/v1/craft/table", tableBody(100, 400, 100, "minecraft:overworld"));
        assertTrue(y.error().contains("table.y is 400"), y.error());
    }

    @Test
    void theIndexDimensionMayBeWrittenWithoutTheNamespace() throws Exception {
        start();
        store.putIndex(json(indexJson().replace("\"player\":\"Bob\",", "\"player\":\"Bob\",\"dimension\":\"overworld\",")));
        anchor(10, 64, 10, "overworld");
        assertEquals(200, post(owner, "/api/v1/craft/table", tableBody(10, 64, 10, "minecraft:overworld")).status);
        Res bad = post(owner, "/api/v1/craft/table", tableBody(10, 64, 10, "minecraft:the_nether"));
        assertEquals(400, bad.status);
        assertTrue(bad.error().contains("this stash is in minecraft:overworld"), bad.error());
    }

    @Test
    void noAnchorSaysSoPlainly() throws Exception {
        start();
        Res r = post(owner, "/api/v1/craft/table", tableBody(10, 64, 10, "minecraft:overworld"));
        assertEquals(400, r.status);
        assertTrue(r.error().contains("no stash area is known yet"), r.error());
        assertTrue(r.error().contains("has not reported one"), r.error());
    }

    // ---------- unsynced races and shapes ----------

    String staleAuto() {
        return "\"craftOrders\":" + cap(tableHash(), "auto", 64) + ",\"craftHash\":\"" + CraftStore.hash(null) + "\"";
    }

    @Test
    void autoThenSet_theBotsOldWordIsNotTakenForTheNewTable() throws Exception {
        start();
        beat("auto", null);
        assertTrue(summary().get("canOrder").getAsBoolean());
        setTable(118, 64, -30); // the bot still has the old settings and says auto
        ok(heartbeat(staleAuto()));
        JsonObject c = summary();
        assertFalse(c.get("canOrder").getAsBoolean());
        assertEquals("unknown", c.get("tableStatus").getAsString());
        assertEquals("table_bad", placeOrder(LINES).obj().get("craft").getAsString());
    }

    @Test
    void autoThenClear_aStaleOkWithABotTableWaitsForTheSync() throws Exception {
        start();
        setTable(118, 64, -30);
        beat("ok", null);
        assertTrue(summary().get("canOrder").getAsBoolean());
        ok(post(owner, "/api/v1/craft/table", "{" + KEY + ",\"table\":null}"));
        // The bot still holds the website table's settings, reports ok and has found a table of its own.
        String oldHash = CraftStore.hash(new CraftStore.Table(118, 64, -30, "minecraft:overworld"));
        String c = cap(tableHash(), "ok", 64);
        c = c.substring(0, c.length() - 1) + ",\"botTable\":{\"pos\":\"5,64,5\",\"source\":\"found\"}}";
        ok(heartbeat("\"craftOrders\":" + c + ",\"craftHash\":\"" + oldHash + "\""));
        Res r = placeOrder(LINES);
        assertEquals(409, r.status);
        assertEquals("no_table", r.obj().get("craft").getAsString());
        assertTrue(r.error().contains("Waiting for the bot to pick up the latest settings."), r.error());
        // Once it has the new settings it is auto.
        beat("ok", "\"botTable\":{\"pos\":\"5,64,5\",\"source\":\"found\"}");
        assertTrue(summary().get("canOrder").getAsBoolean());
    }

    @Test
    void aStaleAutoAfterTheTableChangedAgainStaysOutUntilTheBotCatchesUp() throws Exception {
        start();
        beat("auto", null);
        setTable(118, 64, -30);
        anchor(120, 64, -30, "minecraft:overworld");
        ok(post(owner, "/api/v1/craft/table", tableBody(120, 64, -30, "minecraft:overworld")));
        ok(heartbeat(staleAuto()));
        assertFalse(summary().get("canOrder").getAsBoolean());
        ok(post(owner, "/api/v1/craft/table", "{" + KEY + ",\"table\":null}"));
        ok(heartbeat(staleAuto())); // the hash is that of "no table", but is now the server's again: synced
        assertTrue(summary().get("canOrder").getAsBoolean(), "back to the settings the bot has");
    }

    @Test
    void autoWhileAWebsiteTableIsSetShowsTheUnknownWording() throws Exception {
        start();
        setTable(118, 64, -30);
        beat("auto", "\"botTable\":{\"pos\":\"5,64,5\",\"source\":\"placed\"}");
        JsonObject c = summary();
        assertEquals("unknown", c.get("tableStatus").getAsString());
        assertFalse(c.get("canOrder").getAsBoolean());
        assertEquals("table_bad", c.get("reason").getAsString());
        assertFalse(placeOrder(LINES).error().contains("auto"));
    }

    @Test
    void explicitNullsForMcAndSeenTogetherAreFine() throws Exception {
        start();
        String c = cap(tableHash(), "auto", 64).replace(",\"mc\":\"1.21.4\"", ",\"mc\":null").replace("[\"118,64,-30\"]", "null");
        assertEquals(200, heartbeat("\"craftOrders\":" + c).status);
        assertTrue(craftView().getAsJsonArray("seen").isEmpty());
        assertEquals(400, heartbeat("\"craftOrders\":" + cap(tableHash(), "auto", 64).replace("\"table\":\"auto\"", "\"table\":\"autox\"")).status);
    }

    @Test
    void aForeignNamespaceIsRefusedAndABareIndexDimensionIsAccepted() throws Exception {
        start();
        anchor(10, 64, 10, "foo:overworld");
        assertEquals(400, post(owner, "/api/v1/craft/table", tableBody(10, 64, 10, "foo:overworld")).status, "not one of the three vanilla dimensions");
        store.putIndex(json(indexJson().replace("\"player\":\"Bob\",", "\"player\":\"Bob\",\"dimension\":\"overworld\",")));
        anchor(10, 64, 10, "overworld");
        assertEquals(400, post(owner, "/api/v1/craft/table", tableBody(10, 64, 10, "foo:overworld")).status, "bare overworld means minecraft:overworld");
        assertEquals(200, post(owner, "/api/v1/craft/table", tableBody(10, 64, 10, "minecraft:overworld")).status);
    }

    // ---------- no position in the planner or the orders ----------

    static byte[] build() {
        java.util.Map<String, Integer> blocks = new java.util.LinkedHashMap<>();
        blocks.put("minecraft:spruce_slab", 6);
        return com.porter.stashmanager.planner.PlannerFixtures.structure(4189, blocks, null);
    }

    String upload(String token, String serverParam) throws Exception {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/v1/planner/upload?server=" + serverParam + "&stash=a&name=T.nbt"))
            .header("X-Stash-Manager", "1").header("Content-Type", "application/octet-stream")
            .header("Cookie", SessionStore.COOKIE + "=" + token).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(build())).build();
        java.net.http.HttpResponse<String> r = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        assertNoPosition(r.body());
        return com.google.gson.JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("plan").get("id").getAsString();
    }

    static void assertNoPosition(String body) {
        assertFalse(body.contains("botTable"), body);
        for (String pos : new String[]{"120,64,-31", "118,64,-30", "\"x\":118", "\"x\":120"}) assertFalse(body.contains(pos), pos);
    }

    @Test
    void plannerViewDryRunAndOrderNeverCarryAPositionEvenForStreamers() throws Exception {
        start();
        setTable(118, 64, -30);
        beat("ok", "\"botTable\":{\"pos\":\"120,64,-31\",\"source\":\"placed\"}");
        String forced = userToken("fay", "{\"view\":true,\"planner\":true,\"plannerOrder\":true,\"order\":true,\"streamer\":true}");
        String sid = JsonParserOf(get(forced, "/api/v1/stashes").body);
        for (String[] who : new String[][]{{owner, "s"}, {forced, sid}}) {
            String id = upload(who[0], who[1]);
            String key = "{\"server\":\"" + who[1] + "\",\"stash\":\"a\"";
            Res view = post(who[0], "/api/v1/planner/" + id + "/view", key + ",\"craft\":true}");
            assertEquals(200, view.status, view.body);
            assertNoPosition(view.body);
            Res dry = post(who[0], "/api/v1/planner/" + id + "/order", key + ",\"craft\":true,\"dryRun\":true}");
            assertEquals(200, dry.status, dry.body);
            assertNoPosition(dry.body);
            Res placed = post(who[0], "/api/v1/planner/" + id + "/order", key + ",\"craft\":true}");
            assertEquals(200, placed.status, placed.body);
            assertNoPosition(placed.body);
            Res list = get(who[0], "/api/v1/orders?server=" + who[1] + "&stash=a");
            assertNoPosition(list.body);
        }
        Res quote = post(forced, "/api/v1/craft/plan", "{\"server\":\"" + sid + "\",\"stash\":\"a\",\"want\":[{\"id\":\"minecraft:spruce_slab\",\"count\":6}]}");
        assertNoPosition(quote.body);
    }

    @Test
    void noMaterialWithAnUnsetTableShowsTheReasonAndStaysUnorderable() throws Exception {
        start();
        beat("unset", "\"tableWhy\":\"no_material\"");
        JsonObject c = summary();
        assertTrue(c.get("supports").getAsBoolean());
        assertTrue(c.get("enabled").getAsBoolean());
        assertFalse(c.get("canOrder").getAsBoolean());
        assertEquals("no_table", c.get("reason").getAsString(), "not no_bot: the bot can craft, it has no table route");
        assertEquals("no_material", c.get("tableWhy").getAsString());
        assertEquals("no_material", craftView().get("tableWhy").getAsString());
        Res r = placeOrder(LINES);
        assertEquals(409, r.status);
        assertEquals("no_table", r.obj().get("craft").getAsString());
        assertTrue(r.error().contains("no crafting table, planks or logs to make one"), r.error());
        JsonObject q = ok(post(owner, "/api/v1/craft/plan", "{" + KEY + ",\"want\":[{\"id\":\"minecraft:spruce_slab\",\"count\":6}]}"));
        assertEquals("no_table", q.getAsJsonObject("craft").get("reason").getAsString());
        // The same bot with a website table set: the table decides, and the why is still shown when it is not usable.
        setTable(118, 64, -30);
        beat("unreachable", "\"tableWhy\":\"no_material\"");
        assertEquals("table_bad", summary().get("reason").getAsString());
    }

}

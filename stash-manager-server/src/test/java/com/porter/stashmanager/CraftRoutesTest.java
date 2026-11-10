package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crafting settings, the heartbeat fields, the quote and the recipe route through the real routes (CRAFT-CONTRACT.md):
 * permissions, scope, streamer, CSRF, limits, audit entries, and a damaged craft.json.
 */
class CraftRoutesTest extends CraftHarness {
    static final String EMPTY_HASH = CraftStore.hash(null);

    // ---------- heartbeat ----------

    @Test
    void anOlderBotsHeartbeatWorksAndGetsTheServersHash() throws Exception {
        start();
        JsonObject o = ok(heartbeat(null));
        assertTrue(o.get("ok").getAsBoolean());
        assertTrue(o.has("needIndex") && o.has("needDelivery") && o.has("orders"));
        assertEquals(EMPTY_HASH, o.get("craftHash").getAsString());
        assertFalse(o.has("craft"), "an older bot never asked for the settings");
        assertEquals(tableHash(), o.get("recipesHash").getAsString(), "the server's table for the stash's version");
        assertEquals(RecipeNormalizerTest.sha1("craft-v1\n\n"), EMPTY_HASH);
        // Nothing of the bot was learned.
        JsonObject view = ok(get(owner, "/api/v1/craft?server=s&stash=a"));
        assertFalse(view.getAsJsonObject("bot").get("supports").getAsBoolean());
        assertTrue(view.getAsJsonArray("seen").isEmpty());
    }

    @Test
    void theCraftSettingsAreSentOnlyToABotThatAsksAndDiffers() throws Exception {
        start();
        // craftHash null: the bot has none, so it gets the settings.
        JsonObject o = ok(heartbeat("\"craftHash\":null"));
        assertEquals(0, o.getAsJsonObject("craft").get("rev").getAsInt());
        assertEquals(EMPTY_HASH, o.getAsJsonObject("craft").get("hash").getAsString());
        assertTrue(o.getAsJsonObject("craft").get("table").isJsonNull());
        assertFalse(o.getAsJsonObject("craft").has("neverUse"), "server side only");
        // The same hash: nothing more.
        assertFalse(ok(heartbeat("\"craftHash\":\"" + EMPTY_HASH + "\"")).has("craft"));
        // New settings: a stale hash gets the new object.
        setTable(118, 64, -30);
        o = ok(heartbeat("\"craftHash\":\"" + EMPTY_HASH + "\""));
        JsonObject c = o.getAsJsonObject("craft");
        assertEquals(1, c.get("rev").getAsInt());
        assertEquals(118, c.getAsJsonObject("table").get("x").getAsInt());
        assertEquals("minecraft:overworld", c.getAsJsonObject("table").get("dimension").getAsString());
        assertEquals(CraftStore.hash(new CraftStore.Table(118, 64, -30, "minecraft:overworld")), c.get("hash").getAsString());
        assertEquals(c.get("hash").getAsString(), o.get("craftHash").getAsString());
        assertFalse(ok(heartbeat("\"craftHash\":\"" + c.get("hash").getAsString() + "\"")).has("craft"));
    }

    @Test
    void wrongTypesAreA400() throws Exception {
        start();
        String good = cap(hash40('a'), "ok", 2048);
        assertEquals(200, heartbeat("\"craftOrders\":" + good).status);
        String[] bad = {
            "\"craftOrders\":5", "\"craftOrders\":\"x\"", "\"craftOrders\":[]",
            "\"craftOrders\":" + good.replace("\"v\":1", "\"v\":0"),
            "\"craftOrders\":" + good.replace("\"v\":1", "\"v\":1.5"),
            "\"craftOrders\":" + good.replace("\"v\":1", "\"v\":\"1\""),
            "\"craftOrders\":" + good.replace("\"enabled\":true", "\"enabled\":\"yes\""),
            "\"craftOrders\":" + good.replace(hash40('a'), "abc"),
            "\"craftOrders\":" + good.replace(hash40('a'), hash40('A')),
            "\"craftOrders\":" + good.replace("\"table\":\"ok\"", "\"table\":\"fine\""),
            "\"craftOrders\":" + good.replace("\"maxFills\":2048", "\"maxFills\":0"),
            "\"craftOrders\":" + good.replace("\"maxFills\":2048", "\"maxFills\":100001"),
            "\"craftOrders\":" + good.replace("\"maxFills\":2048", "\"maxFills\":\"many\""),
            "\"craftOrders\":" + good.replace("\"mc\":\"1.21.4\"", "\"mc\":5"),
            "\"craftOrders\":" + good.replace("[\"118,64,-30\"]", "\"118,64,-30\""),
            "\"craftOrders\":" + good.replace("[\"118,64,-30\"]", "[\"here\"]"),
            "\"craftOrders\":" + good.replace("[\"118,64,-30\"]", "[" + "\"1,1,1\",\"2,2,2\",\"3,3,3\",\"4,4,4\",\"5,5,5\",\"6,6,6\",\"7,7,7\",\"8,8,8\",\"9,9,9\"" + "]"),
            "\"craftOrders\":{}",
            "\"craftHash\":5", "\"craftHash\":\"abc\"", "\"craftHash\":\"" + hash40('A') + "\"", "\"craftHash\":{}", "\"craftHash\":true",
        };
        for (String b : bad) assertEquals(400, heartbeat(b).status, b);
        // Eight positions are fine, and the optional fields may be left out or null.
        assertEquals(200, heartbeat("\"craftOrders\":" + good.replace("[\"118,64,-30\"]", "[\"1,1,1\",\"2,2,2\",\"3,3,3\",\"4,4,4\",\"5,5,5\",\"6,6,6\",\"7,7,7\",\"8,8,8\"]")).status);
        assertEquals(200, heartbeat("\"craftOrders\":" + good.replace(",\"seen\":[\"118,64,-30\"]", "").replace(",\"mc\":\"1.21.4\"", "")).status);
        assertEquals(200, heartbeat("\"craftOrders\":" + good.replace("[\"118,64,-30\"]", "null") + ",\"craftHash\":null").status);
    }

    @Test
    void theCapabilityIsKeptWhenAbsentAndClearedByNull() throws Exception {
        start();
        ok(heartbeat("\"craftOrders\":" + cap(tableHash(), "ok", 64)));
        assertTrue(ok(get(owner, "/api/v1/craft?server=s&stash=a")).getAsJsonObject("bot").get("supports").getAsBoolean());
        ok(heartbeat(null)); // an absent key keeps the stored value
        JsonObject bot = ok(get(owner, "/api/v1/craft?server=s&stash=a")).getAsJsonObject("bot");
        assertTrue(bot.get("supports").getAsBoolean());
        assertEquals(64, bot.get("maxFills").getAsInt());
        assertTrue(bot.get("recipesMatch").getAsBoolean());
        ok(heartbeat("\"craftOrders\":null"));
        bot = ok(get(owner, "/api/v1/craft?server=s&stash=a")).getAsJsonObject("bot");
        assertFalse(bot.get("supports").getAsBoolean());
        assertTrue(bot.get("maxFills").isJsonNull());
    }

    @Test
    void aServerWithoutRecipesYetOmitsRecipesHash() throws Exception {
        start();
        recipes.state = "building";
        assertFalse(ok(heartbeat(null)).has("recipesHash"));
        recipes.state = "ready";
        assertTrue(ok(heartbeat(null)).has("recipesHash"));
        assertTrue(recipes.requests.get() > 0, "the version was asked for");
    }

    @Test
    void aDamagedSettingsFileLeavesTheCraftFieldsOutAndThatStash503() throws Exception {
        Files.writeString(data.resolve("craft.json"), "{ nope");
        Files.writeString(data.resolve("craft.json.bak"), "{ nope");
        start();
        JsonObject o = ok(heartbeat("\"craftHash\":null"));
        assertFalse(o.has("craftHash"));
        assertFalse(o.has("craft"));
        assertTrue(o.has("recipesHash"), "the recipes don't depend on the settings");
        assertEquals(503, get(owner, "/api/v1/craft?server=s&stash=a").status);
        assertEquals(503, post(owner, "/api/v1/craft/table", "{" + KEY + ",\"table\":null}").status);
        assertEquals(503, post(owner, "/api/v1/craft/never-use", "{" + KEY + ",\"ids\":[]}").status);
        JsonArray stashes = JsonParserHelper.array(get(owner, "/api/v1/stashes").body);
        assertTrue(stashes.get(0).getAsJsonObject().get("craft").isJsonNull());
        assertEquals("{ nope", Files.readString(data.resolve("craft.json")), "never overwritten");
    }

    // ---------- GET /api/v1/craft and the writes ----------

    @Test
    void getBodyAndStatuses() throws Exception {
        start();
        JsonObject v = ok(get(owner, "/api/v1/craft?server=s&stash=a"));
        assertEquals("s", v.get("server").getAsString());
        assertEquals("a", v.get("stash").getAsString());
        assertEquals(0, v.get("rev").getAsInt());
        assertEquals(EMPTY_HASH, v.get("hash").getAsString());
        assertTrue(v.get("table").isJsonNull());
        assertEquals("unset", v.get("tableStatus").getAsString());
        assertTrue(v.getAsJsonArray("neverUse").isEmpty());
        assertEquals(MC, v.getAsJsonObject("recipes").get("mc").getAsString());
        assertEquals("ready", v.getAsJsonObject("recipes").get("state").getAsString());
        assertFalse(v.getAsJsonObject("bot").get("online").getAsBoolean());
        // The table, then a bot that has not picked it up: unknown. Once it has: the bot's own word.
        setTable(118, 64, -30);
        ok(heartbeat("\"craftOrders\":" + cap(tableHash(), "unloaded", 2048)));
        v = ok(get(owner, "/api/v1/craft?server=s&stash=a"));
        assertEquals("unknown", v.get("tableStatus").getAsString());
        assertFalse(v.getAsJsonObject("bot").get("synced").getAsBoolean());
        assertTrue(v.getAsJsonObject("bot").get("online").getAsBoolean());
        capableBeat("unloaded", 2048);
        v = ok(get(owner, "/api/v1/craft?server=s&stash=a"));
        assertEquals("unloaded", v.get("tableStatus").getAsString());
        assertTrue(v.getAsJsonObject("bot").get("synced").getAsBoolean());
        assertTrue(v.getAsJsonObject("bot").get("supports").getAsBoolean());
        assertTrue(v.getAsJsonObject("bot").get("enabled").getAsBoolean());
        assertEquals("118,64,-30", v.getAsJsonArray("seen").get(0).getAsString());
        assertEquals(118, v.getAsJsonObject("table").get("x").getAsInt());
        // A different recipe table in the bot.
        ok(heartbeat("\"craftOrders\":" + cap(hash40('f'), "ok", 2048)));
        assertFalse(ok(get(owner, "/api/v1/craft?server=s&stash=a")).getAsJsonObject("bot").get("recipesMatch").getAsBoolean());
        // Unknown stash and missing parameters.
        assertEquals(404, get(owner, "/api/v1/craft?server=s&stash=nope").status);
        assertEquals(400, get(owner, "/api/v1/craft?server=s").status);
        assertEquals(401, get(null, "/api/v1/craft?server=s&stash=a").status);
    }

    @Test
    void tableWritesValidateAndAudit() throws Exception {
        start();
        anchor(-5, 70, 12, "minecraft:the_nether");
        String t = "{\"x\":-5,\"y\":70,\"z\":12,\"dimension\":\"minecraft:the_nether\"}";
        JsonObject v = ok(post(owner, "/api/v1/craft/table", "{" + KEY + ",\"table\":" + t + "}"));
        assertEquals(1, v.get("rev").getAsInt());
        assertEquals(-5, v.getAsJsonObject("table").get("x").getAsInt());
        assertEquals("minecraft:the_nether", v.getAsJsonObject("table").get("dimension").getAsString());
        // Clearing works, and needs the key.
        assertTrue(ok(post(owner, "/api/v1/craft/table", "{" + KEY + ",\"table\":null}")).get("table").isJsonNull());
        assertEquals(400, post(owner, "/api/v1/craft/table", "{" + KEY + "}").status);
        String[] bad = {
            "5", "\"here\"", "[]", "{}",
            "{\"x\":1.5,\"y\":1,\"z\":1,\"dimension\":\"minecraft:overworld\"}",
            "{\"x\":\"1\",\"y\":1,\"z\":1,\"dimension\":\"minecraft:overworld\"}",
            "{\"x\":30000001,\"y\":1,\"z\":1,\"dimension\":\"minecraft:overworld\"}",
            "{\"x\":1,\"y\":2049,\"z\":1,\"dimension\":\"minecraft:overworld\"}",
            "{\"x\":1,\"y\":1,\"z\":-30000001,\"dimension\":\"minecraft:overworld\"}",
            "{\"x\":99999999999999999999,\"y\":1,\"z\":1,\"dimension\":\"minecraft:overworld\"}",
            "{\"x\":1,\"y\":1,\"z\":1,\"dimension\":\"overworld\"}",
            "{\"x\":1,\"y\":1,\"z\":1,\"dimension\":5}",
            "{\"x\":1,\"y\":1,\"z\":1}",
        };
        for (String b : bad) assertEquals(400, post(owner, "/api/v1/craft/table", "{" + KEY + ",\"table\":" + b + "}").status, b);
        assertEquals(400, post(owner, "/api/v1/craft/table", "{\"server\":\"s\"}").status);
        assertEquals(404, post(owner, "/api/v1/craft/table", "{\"server\":\"s\",\"stash\":\"ghost\",\"table\":null}").status);
        assertEquals(400, post(owner, "/api/v1/craft/table", "[1]").status);
        // Over 4 KiB.
        assertEquals(413, post(owner, "/api/v1/craft/table", "{" + KEY + ",\"table\":null,\"pad\":\"" + "x".repeat(5000) + "\"}").status);
        String audit = get(owner, "/api/v1/audit").body;
        assertTrue(audit.contains("\"craft.table\""));
        assertTrue(audit.contains("-5,70,12 minecraft:the_nether"));
    }

    @Test
    void neverUseWritesValidateAndAudit() throws Exception {
        start();
        JsonObject v = ok(post(owner, "/api/v1/craft/never-use", "{" + KEY + ",\"ids\":[\"minecraft:stone\",\"minecraft:dirt\",\"minecraft:stone\"]}"));
        assertEquals(2, v.getAsJsonArray("neverUse").size());
        assertEquals("minecraft:dirt", v.getAsJsonArray("neverUse").get(0).getAsString());
        assertEquals(EMPTY_HASH, v.get("hash").getAsString(), "not part of the hash");
        assertEquals(400, post(owner, "/api/v1/craft/never-use", "{" + KEY + ",\"ids\":\"minecraft:stone\"}").status);
        assertEquals(400, post(owner, "/api/v1/craft/never-use", "{" + KEY + ",\"ids\":[5]}").status);
        assertEquals(400, post(owner, "/api/v1/craft/never-use", "{" + KEY + ",\"ids\":[\"stone\"]}").status);
        assertEquals(400, post(owner, "/api/v1/craft/never-use", "{" + KEY + ",\"ids\":[\"minecraft:stone#ab\"]}").status);
        assertEquals(400, post(owner, "/api/v1/craft/never-use", "{" + KEY + "}").status);
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 201; i++) ids.append(i == 0 ? "" : ",").append("\"minecraft:item_").append(i).append('"');
        assertEquals(400, post(owner, "/api/v1/craft/never-use", "{" + KEY + ",\"ids\":[" + ids + "]}").status, "a 201st id");
        ids.setLength(0);
        for (int i = 0; i < 200; i++) ids.append(i == 0 ? "" : ",").append("\"minecraft:item_").append(i).append('"');
        assertEquals(200, post(owner, "/api/v1/craft/never-use", "{" + KEY + ",\"ids\":[" + ids + "]}").status, "200 ids fit the body limit");
        assertTrue(get(owner, "/api/v1/audit").body.contains("\"craft.neverUse\""));
    }

    @Test
    void permissionsScopeCsrfAndTheRateLimit() throws Exception {
        start();
        String viewer = userToken("vie", "{\"view\":true}");
        String settingsOnly = userToken("set", "{\"settings\":true}");
        String editor = userToken("edi", "{\"view\":true,\"settings\":true}");
        String scoped = userToken("sco", "{\"view\":true,\"settings\":true,\"stashes\":[{\"server\":\"s\",\"stash\":\"other\"}]}");
        String body = "{" + KEY + ",\"table\":null}";
        assertEquals(200, get(viewer, "/api/v1/craft?server=s&stash=a").status);
        assertEquals(403, post(viewer, "/api/v1/craft/table", body).status);
        assertEquals(403, post(viewer, "/api/v1/craft/never-use", "{" + KEY + ",\"ids\":[]}").status);
        assertEquals(403, get(settingsOnly, "/api/v1/craft?server=s&stash=a").status, "reading needs view");
        assertEquals(403, post(settingsOnly, "/api/v1/craft/table", body).status, "writing needs view as well");
        assertEquals(200, post(editor, "/api/v1/craft/table", body).status);
        // A stash outside the scope answers like one that does not exist.
        assertEquals(404, get(scoped, "/api/v1/craft?server=s&stash=a").status);
        assertEquals(404, post(scoped, "/api/v1/craft/table", body).status);
        // The CSRF checks of a browser write.
        java.net.http.HttpRequest plain = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/v1/craft/table"))
            .header("Content-Type", "application/json").header("Cookie", SessionStore.COOKIE + "=" + owner)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(403, http.send(plain, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode(), "no X-Stash-Manager header");
        assertEquals(401, post(null, "/api/v1/craft/table", body).status);
        // 60 writes a minute per user, then 429 with Retry-After; another user is not affected.
        for (int i = 0; i < CraftRoutes.WRITES_PER_MINUTE; i++) assertEquals(200, post(owner, "/api/v1/craft/table", body).status);
        Res limited = post(owner, "/api/v1/craft/table", body);
        assertEquals(429, limited.status);
        assertTrue(limited.obj().get("retryAfter").getAsInt() >= 1);
        assertTrue(limited.headers.firstValue("Retry-After").isPresent());
        assertEquals(429, post(owner, "/api/v1/craft/never-use", "{" + KEY + ",\"ids\":[]}").status, "writes share the limit");
        assertEquals(200, post(editor, "/api/v1/craft/table", body).status);
    }

    @Test
    void streamerUsersAreRefusedTheSettingsButMayAskForAQuote() throws Exception {
        start();
        String forced = userToken("fay", "{\"view\":true,\"settings\":true,\"order\":true,\"streamer\":true}");
        for (String[] call : new String[][]{{"GET", "/api/v1/craft?server=s&stash=a"}, {"POST", "/api/v1/craft/table"}, {"POST", "/api/v1/craft/never-use"}}) {
            Res r = call[0].equals("GET") ? get(forced, call[1]) : post(forced, call[1], "{" + KEY + ",\"table\":null,\"ids\":[]}");
            assertEquals(403, r.status, call[1]);
            assertEquals("streamer", r.obj().get("permission").getAsString());
        }
        // The quote carries no positions, so it works (with the opaque server id the stash list gives).
        String sid = JsonParserHelper.array(get(forced, "/api/v1/stashes").body).get(0).getAsJsonObject().get("server").getAsString();
        assertNotEquals("s", sid);
        Res q = post(forced, "/api/v1/craft/plan", "{\"server\":\"" + sid + "\",\"stash\":\"a\",\"want\":[{\"id\":\"minecraft:spruce_slab\",\"count\":6}]}");
        assertEquals(200, q.status, q.body);
        assertEquals(6, q.obj().getAsJsonArray("lines").get(0).getAsJsonObject().get("craftable").getAsInt());
        assertFalse(q.body.contains("118"), "no positions");
        // A raw server address is not accepted from a forced user.
        assertEquals(404, post(forced, "/api/v1/craft/plan", "{" + KEY + ",\"want\":[{\"id\":\"minecraft:spruce_slab\",\"count\":6}]}").status);
    }

    @Test
    void theStashListGetsACraftEntry() throws Exception {
        start();
        JsonObject c = JsonParserHelper.array(get(owner, "/api/v1/stashes").body).get(0).getAsJsonObject().getAsJsonObject("craft");
        assertFalse(c.get("supports").getAsBoolean());
        assertFalse(c.get("enabled").getAsBoolean());
        assertFalse(c.get("synced").getAsBoolean());
        assertFalse(c.get("recipesMatch").getAsBoolean());
        assertEquals("unset", c.get("tableStatus").getAsString());
        assertFalse(c.get("canOrder").getAsBoolean());
        assertEquals("no_bot", c.get("reason").getAsString());
        assertTrue(c.get("maxFills").isJsonNull());
        ready(300);
        c = JsonParserHelper.array(get(owner, "/api/v1/stashes").body).get(0).getAsJsonObject().getAsJsonObject("craft");
        assertTrue(c.get("supports").getAsBoolean());
        assertTrue(c.get("synced").getAsBoolean());
        assertTrue(c.get("recipesMatch").getAsBoolean());
        assertEquals("ok", c.get("tableStatus").getAsString());
        assertTrue(c.get("canOrder").getAsBoolean());
        assertTrue(c.get("reason").isJsonNull());
        assertEquals(300, c.get("maxFills").getAsInt());
        // The positions of the table are not in the list.
        assertFalse(get(owner, "/api/v1/stashes").body.contains("118,64,-30"));
    }

    // ---------- POST /api/v1/craft/plan ----------

    @Test
    void theQuoteSaysWhatCanBeMadeAndReservesNothing() throws Exception {
        start();
        ready(2048);
        String want = "[{\"id\":\"minecraft:spruce_stairs\",\"count\":8},{\"id\":\"minecraft:spruce_slab\",\"count\":6},{\"id\":\"minecraft:spruce_fence\",\"count\":3}]";
        JsonObject q = ok(post(owner, "/api/v1/craft/plan", "{" + KEY + ",\"want\":" + want + "}"));
        assertTrue(q.getAsJsonObject("craft").get("canOrder").getAsBoolean());
        assertTrue(q.getAsJsonObject("craft").get("reason").isJsonNull());
        JsonArray lines = q.getAsJsonArray("lines");
        assertEquals(3, lines.size());
        assertEquals("minecraft:spruce_stairs", lines.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(8, lines.get(0).getAsJsonObject().get("want").getAsInt());
        assertEquals(8, lines.get(0).getAsJsonObject().get("craftable").getAsInt());
        assertTrue(lines.get(0).getAsJsonObject().get("reason").isJsonNull());
        assertEquals("minecraft:spruce_log", lines.get(0).getAsJsonObject().getAsJsonArray("uses").get(0).getAsJsonObject().get("id").getAsString());
        // The pool has 10 spruce logs and 2 spruce planks: the planks are used first.
        assertFalse(q.getAsJsonArray("take").isEmpty());
        assertTrue(q.get("fills").getAsInt() > 0);
        boolean usesPlanks = false;
        for (JsonElement t : q.getAsJsonArray("take")) usesPlanks |= t.getAsJsonObject().get("id").getAsString().equals("minecraft:spruce_planks");
        assertTrue(usesPlanks, "the two planks in the stash are used before logs are cut");
        assertFalse(q.get("truncated").getAsBoolean());
        assertEquals(10L, availableItem("minecraft:spruce_log").getAsLong(), "nothing was reserved");
        // Too much: partial lines carry the reason.
        JsonObject big = ok(post(owner, "/api/v1/craft/plan", "{" + KEY + ",\"want\":[{\"id\":\"minecraft:spruce_slab\",\"count\":1000}]}"));
        JsonObject l = big.getAsJsonArray("lines").get(0).getAsJsonObject();
        assertTrue(l.get("craftable").getAsInt() > 0 && l.get("craftable").getAsInt() < 1000);
        assertTrue(l.get("reason").getAsString().startsWith("not enough"));
        // direct: what the same order takes as itself; the logs are used up by it.
        JsonObject withDirect = ok(post(owner, "/api/v1/craft/plan", "{" + KEY + ",\"want\":[{\"id\":\"minecraft:spruce_slab\",\"count\":6}],"
            + "\"direct\":[{\"id\":\"minecraft:spruce_log\",\"count\":10},{\"id\":\"minecraft:spruce_planks\",\"count\":2}]}"));
        assertEquals(0, withDirect.getAsJsonArray("lines").get(0).getAsJsonObject().get("craftable").getAsInt());
        // Duplicates are merged.
        JsonObject merged = ok(post(owner, "/api/v1/craft/plan", "{" + KEY + ",\"want\":[{\"id\":\"minecraft:spruce_slab\",\"count\":3},{\"id\":\"minecraft:spruce_slab\",\"count\":3}]}"));
        assertEquals(1, merged.getAsJsonArray("lines").size());
        assertEquals(6, merged.getAsJsonArray("lines").get(0).getAsJsonObject().get("want").getAsInt());
    }

    @Test
    void theQuoteSaysWhyAnOrderCannotBePlacedButStillShowsNumbers() throws Exception {
        start();
        String want = "{" + KEY + ",\"want\":[{\"id\":\"minecraft:spruce_slab\",\"count\":6}]}";
        JsonObject q = ok(post(owner, "/api/v1/craft/plan", want));
        assertFalse(q.getAsJsonObject("craft").get("canOrder").getAsBoolean());
        assertEquals("no_bot", q.getAsJsonObject("craft").get("reason").getAsString());
        assertEquals(6, q.getAsJsonArray("lines").get(0).getAsJsonObject().get("craftable").getAsInt());
        recipes.state = "building";
        q = ok(post(owner, "/api/v1/craft/plan", want));
        assertEquals("no_recipes", q.getAsJsonObject("craft").get("reason").getAsString());
        assertEquals(0, q.getAsJsonArray("lines").get(0).getAsJsonObject().get("craftable").getAsInt());
    }

    @Test
    void theQuotePermissionsAndLimits() throws Exception {
        start();
        String want = "{" + KEY + ",\"want\":[{\"id\":\"minecraft:spruce_slab\",\"count\":6}]}";
        String viewer = userToken("vie", "{\"view\":true}");
        String orderer = userToken("ord", "{\"order\":true}");
        String planner = userToken("pla", "{\"plannerOrder\":true,\"planner\":true}");
        assertEquals(403, post(viewer, "/api/v1/craft/plan", want).status);
        assertEquals(200, post(orderer, "/api/v1/craft/plan", want).status);
        assertEquals(200, post(planner, "/api/v1/craft/plan", want).status);
        assertEquals(401, post(null, "/api/v1/craft/plan", want).status);
        String[] bad = {
            "{" + KEY + "}",
            "{" + KEY + ",\"want\":5}",
            "{" + KEY + ",\"want\":[5]}",
            "{" + KEY + ",\"want\":[{\"id\":\"stick\",\"count\":1}]}",
            "{" + KEY + ",\"want\":[{\"id\":\"minecraft:stick#ab\",\"count\":1}]}",
            "{" + KEY + ",\"want\":[{\"id\":\"minecraft:stick\",\"count\":0}]}",
            "{" + KEY + ",\"want\":[{\"id\":\"minecraft:stick\",\"count\":1.5}]}",
            "{" + KEY + ",\"want\":[{\"id\":\"minecraft:stick\",\"count\":2147483648}]}",
            "{" + KEY + ",\"want\":[{\"id\":\"minecraft:stick\"}]}",
            "{" + KEY + ",\"want\":[{\"id\":\"minecraft:stick\",\"count\":1}],\"direct\":3}",
        };
        for (String b : bad) assertEquals(400, post(owner, "/api/v1/craft/plan", b).status, b);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 201; i++) many.append(i == 0 ? "" : ",").append("{\"id\":\"minecraft:item_").append(i).append("\",\"count\":1}");
        assertEquals(400, post(owner, "/api/v1/craft/plan", "{" + KEY + ",\"want\":[" + many + "]}").status, "a 201st entry");
        assertEquals(413, post(owner, "/api/v1/craft/plan", "{" + KEY + ",\"want\":[],\"pad\":\"" + "x".repeat(17_000) + "\"}").status);
        assertEquals(404, post(owner, "/api/v1/craft/plan", "{\"server\":\"s\",\"stash\":\"ghost\",\"want\":[]}").status);
        // 30 a minute per user.
        for (int i = 0; i < CraftRoutes.PLANS_PER_MINUTE; i++) {
            int status = post(planner, "/api/v1/craft/plan", want).status;
            if (i < CraftRoutes.PLANS_PER_MINUTE - 1) assertEquals(200, status);
        }
        Res limited = post(planner, "/api/v1/craft/plan", want);
        assertEquals(429, limited.status);
        assertTrue(limited.headers.firstValue("Retry-After").isPresent());
        assertEquals(200, post(orderer, "/api/v1/craft/plan", want).status);
    }

    // ---------- GET /api/v1/bot/recipes ----------

    @Test
    void theRecipeRouteServesTheTableWithAnEtag() throws Exception {
        start();
        String hash = tableHash();
        Res r = botSend("GET", "/api/v1/bot/recipes?mc=" + MC, null);
        assertEquals(200, r.status, r.body);
        assertEquals("\"" + hash + "\"", r.headers.firstValue("ETag").orElseThrow());
        RecipeTable t = RecipeTable.parse(r.body);
        assertEquals(hash, t.tableHash());
        assertEquals(RecipeTable.FORMAT, t.format);
        assertEquals(recipes.table.recipes().size(), t.recipes().size());
        assertTrue(r.headers.firstValue("X-SM-Sig").isPresent(), "the answer to a signed request is signed");
        // If-None-Match: 304 for the same, a full answer for another.
        Res same = botSend("GET", "/api/v1/bot/recipes?mc=" + MC, null, "-", "\"" + hash + "\"");
        assertEquals(304, same.status);
        assertEquals("", same.body);
        assertEquals(304, botSend("GET", "/api/v1/bot/recipes?mc=" + MC, null, "-", "W/\"" + hash + "\"").status);
        assertEquals(304, botSend("GET", "/api/v1/bot/recipes?mc=" + MC, null, "-", "\"zzz\", \"" + hash + "\"").status);
        assertEquals(200, botSend("GET", "/api/v1/bot/recipes?mc=" + MC, null, "-", "\"" + hash.replace('0', '1') + "x\"").status);
    }

    @Test
    void theRecipeRouteAnswers404And503And400AndWantsASignature() throws Exception {
        start();
        int asked = recipes.requests.get();
        assertEquals(404, botSend("GET", "/api/v1/bot/recipes?mc=1.20.1", null).status, "a version with no recipes");
        assertEquals(asked, recipes.requests.get(), "a version no stash uses is never built for a bot");
        recipes.state = "building";
        Res building = botSend("GET", "/api/v1/bot/recipes?mc=" + MC, null);
        assertEquals(503, building.status);
        assertTrue(building.headers.firstValue("Retry-After").isPresent());
        recipes.state = "failed";
        assertEquals(404, botSend("GET", "/api/v1/bot/recipes?mc=" + MC, null).status);
        recipes.state = "ready";
        assertEquals(400, botSend("GET", "/api/v1/bot/recipes", null).status);
        assertEquals(400, botSend("GET", "/api/v1/bot/recipes?mc=..%2F..%2Fetc", null).status);
        assertEquals(400, botSend("GET", "/api/v1/bot/recipes?mc=abc", null).status);
        // Unsigned, and with the old Bearer key: 401 (bot routes never take Bearer).
        assertEquals(401, get(null, "/api/v1/bot/recipes?mc=" + MC).status);
        assertEquals(401, get(owner, "/api/v1/bot/recipes?mc=" + MC).status, "a website login is no bot key");
        assertEquals(405, post(owner, "/api/v1/bot/recipes", "{}").status);
    }

    @Test
    void theRecipeRouteIsLimitedPerBot() throws Exception {
        start();
        for (int i = 0; i < CraftRoutes.RECIPES_PER_BOT_PER_MINUTE; i++) assertEquals(200, botSend("GET", "/api/v1/bot/recipes?mc=" + MC, null, "botA", null).status);
        Res limited = botSend("GET", "/api/v1/bot/recipes?mc=" + MC, null, "botA", null);
        assertEquals(429, limited.status);
        assertTrue(limited.headers.firstValue("Retry-After").isPresent());
        assertTrue(limited.obj().get("retryAfter").getAsInt() >= 1);
        assertEquals(200, botSend("GET", "/api/v1/bot/recipes?mc=" + MC, null, "botB", null).status, "another bot has its own allowance");
        assertEquals(429, botSend("GET", "/api/v1/bot/recipes?mc=" + MC, null, "botA", "\"x\"").status, "a conditional request counts too");
    }

    static final class JsonParserHelper {
        static JsonArray array(String s) {
            JsonElement e = com.google.gson.JsonParser.parseString(s);
            return e.getAsJsonArray();
        }
    }
}

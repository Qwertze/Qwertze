package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The owner-only rules and the server-side privacy rules of FARM-CONTRACT P5: who may use the farm and STOP routes, the
 * farmer bot's dashboard routes (R4), the activity allowlist, the viewer refusal, and clearing what a bot pushed before it
 * was known as a farmer.
 */
class FarmPrivacyTest extends FarmHarness {
    static final String OWNER_ONLY_TEXT = "you don't have permission to manage farms (owner only)";

    /** Owner-only route calls: method, path, body. */
    static final String[][] OWNER_ROUTES = {
        {"GET", "/api/v1/farms", null},
        {"POST", "/api/v1/farms", "{\"name\":\"F\",\"server\":\"s\",\"mode\":\"custom\"}"},
        {"POST", "/api/v1/farms/delete", "{\"id\":\"f-00000000\"}"},
        {"POST", "/api/v1/farms/start", "{\"id\":\"f-00000000\"}"},
        {"POST", "/api/v1/farms/stop", "{\"id\":\"f-00000000\"}"},
        {"POST", "/api/v1/farms/attach", "{\"id\":\"f-00000000\"}"},
        {"GET", "/api/v1/tasks", null},
        {"POST", "/api/v1/ops/stop", "{\"scope\":\"all\",\"reason\":\"nope\"}"},
        {"POST", "/api/v1/ops/resume", "{\"scope\":\"all\"}"},
        {"POST", "/api/v1/bots/roles", "{\"botId\":\"farmer\",\"roles\":[\"farmer\"]}"},
    };

    Res call(String token, String[] r) throws Exception {
        return r[0].equals("GET") ? get(token, r[1]) : post(token, r[1], r[2]);
    }

    @Test
    void everyFarmAndStopRouteIsOwnerOnlyAndAnswersTheSameForAnyNonOwner() throws Exception {
        makeFarmer("farmer");
        String id = farmWithProgram("farmer", "custom");
        String uma = userToken("uma", ALL_BUT_OWNER);
        String fay = userToken("fay", ALL_BUT_OWNER + ",\"streamer\":true");
        for (String[] r : OWNER_ROUTES) {
            for (String token : new String[]{uma, fay}) {
                Res res = call(token, r);
                assertEquals(403, res.status, r[1] + " " + res.body);
                assertEquals("owner", res.obj().get("permission").getAsString(), r[1]);
                assertEquals(OWNER_ONLY_TEXT, res.obj().get("error").getAsString(), r[1]);
                assertFalse(res.obj().get("ok").getAsBoolean());
            }
            // Not logged in: 401.
            Res anon = call(null, r);
            assertEquals(401, anon.status, r[1] + " " + anon.body);
        }
        // The same answer for a real id and for a missing one: nothing leaks.
        Res real = post(uma, "/api/v1/farms/start", "{\"id\":\"" + id + "\"}");
        Res none = post(uma, "/api/v1/farms/start", "{\"id\":\"f-00000000\"}");
        assertEquals(real.body, none.body);
        // And no state changed.
        assertEquals("idle", farmOf(id).getAsJsonObject("status").get("state").getAsString());
        assertFalse(ok(get(owner, "/api/v1/ops")).getAsJsonObject("stopAll").get("on").getAsBoolean());
    }

    @Test
    void theOwnerCanUseThemAll() throws Exception {
        makeFarmer("farmer");
        for (String[] r : OWNER_ROUTES) {
            if (r[1].equals("/api/v1/farms")) continue; // covered by the farm tests
            assertNotEquals(403, call(owner, r).status, r[1]);
        }
    }

    @Test
    void streamIsReadOnlyForNonOwnersWithoutReasonRolesOrNames() throws Exception {
        makeFarmer("farmer");
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\",\"reason\":\"secret words\"}"));
        String uma = userToken("uma", ALL_BUT_OWNER);
        JsonObject theirs = ok(get(uma, "/api/v1/ops"));
        assertTrue(theirs.getAsJsonObject("stopAll").get("on").getAsBoolean());
        assertFalse(theirs.getAsJsonObject("stopAll").has("reason"), "the field is left out entirely");
        assertFalse(theirs.getAsJsonObject("stopAll").has("by"));
        assertFalse(theirs.toString().contains("secret words"));
        JsonObject bot = theirs.getAsJsonArray("bots").get(0).getAsJsonObject();
        assertFalse(bot.has("roles") || bot.has("farmer"), "no roles for a non-owner");
        assertFalse(bot.getAsJsonObject("stop").has("reason"));
        assertTrue(bot.has("delivered") && bot.has("online") && bot.has("stopAck"));
        assertFalse(theirs.toString().contains("Bone"), "no farm names");
        // The owner sees the reason and the roles.
        JsonObject mine = ok(get(owner, "/api/v1/ops"));
        assertEquals("secret words", mine.getAsJsonObject("stopAll").get("reason").getAsString());
        assertEquals("farmer", mine.getAsJsonArray("bots").get(0).getAsJsonObject().getAsJsonArray("roles").get(0).getAsString());
        // A per-bot STOP: the same rule.
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"farmer\",\"reason\":\"per bot text\"}"));
        assertFalse(ok(get(uma, "/api/v1/ops")).toString().contains("per bot text"));
        assertTrue(ok(get(owner, "/api/v1/ops")).toString().contains("per bot text"));
    }

    @Test
    void aForcedStreamerSeesOnlyOpaqueBotIdsAndNoReason() throws Exception {
        makeFarmer("farmer");
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"farmer\",\"reason\":\"hidden reason\"}"));
        String fay = userToken("fay", "\"view\":true,\"activity\":true,\"streamer\":true");
        Res r = get(fay, "/api/v1/ops");
        assertEquals(200, r.status, r.body);
        String opaque = router.ids().bid("farmer");
        assertFalse(r.body.contains("farmer"), "the raw bot id is never in the answer: " + r.body);
        assertFalse(r.body.contains("hidden reason"));
        assertFalse(r.obj().getAsJsonObject("stopAll").has("reason"));
        JsonObject bot = r.obj().getAsJsonArray("bots").get(0).getAsJsonObject();
        assertEquals(opaque, bot.get("botId").getAsString());
        assertTrue(bot.getAsJsonObject("stop").get("on").getAsBoolean());
        // A raw id is refused (it would be an oracle); the opaque id works.
        assertEquals(404, get(fay, "/api/v1/ops?bot=farmer").status);
        Res one = get(fay, "/api/v1/ops?bot=" + opaque);
        assertEquals(200, one.status, one.body);
        assertEquals(1, one.obj().getAsJsonArray("bots").size());
        assertEquals(opaque, one.obj().getAsJsonArray("bots").get(0).getAsJsonObject().get("botId").getAsString());
        // STOP and resume from a forced streamer and from a plain non-owner are both 403 owner.
        String uma = userToken("uma", ALL_BUT_OWNER);
        for (String token : new String[]{fay, uma}) {
            for (String path : new String[]{"/api/v1/ops/stop", "/api/v1/ops/resume"}) {
                Res w = post(token, path, "{\"scope\":\"farmer\"}");
                assertEquals(403, w.status);
                assertEquals("owner", w.obj().get("permission").getAsString());
            }
        }
        assertTrue(ok(get(owner, "/api/v1/ops")).getAsJsonArray("bots").get(0).getAsJsonObject().getAsJsonObject("stop").get("on").getAsBoolean(),
            "no non-owner changed the STOP");
    }

    @Test
    void aUserWhoseScopeExcludesABotDoesNotSeeItInOps() throws Exception {
        makeFarmer("farmer");
        hello("other");
        ok(sync("other", null));
        String scoped = userToken("sco", "\"activity\":true,\"bots\":[\"other\"]");
        JsonObject o = ok(get(scoped, "/api/v1/ops"));
        assertEquals(1, o.getAsJsonArray("bots").size());
        assertEquals("other", o.getAsJsonArray("bots").get(0).getAsJsonObject().get("botId").getAsString());
        assertEquals(404, get(scoped, "/api/v1/ops?bot=farmer").status);
    }

    @Test
    void botListShowsRolesToTheOwnerOnly() throws Exception {
        makeFarmer("farmer");
        String uma = userToken("uma", ALL_BUT_OWNER);
        JsonArray mine = JsonParser.parseString(get(owner, "/api/v1/bots").body).getAsJsonArray();
        assertEquals("farmer", mine.get(0).getAsJsonObject().getAsJsonArray("roles").get(0).getAsString());
        JsonArray theirs = JsonParser.parseString(get(uma, "/api/v1/bots").body).getAsJsonArray();
        assertFalse(theirs.get(0).getAsJsonObject().has("roles"));
    }

    // ---------- R4: a farmer bot's dashboard routes ----------

    private static final String[][] BOT_ROUTES = {
        {"GET", "settings", null}, {"GET", "jobs", null}, {"GET", "console", null}, {"GET", "activity", null},
        {"POST", "settings", "{\"changes\":[{\"path\":\"a\",\"value\":1}]}"}, {"POST", "console", "{\"command\":\"stash tasks resume\"}"},
        {"POST", "jobs", "{}"}, {"POST", "activity", "{}"},
    };

    private Res botRoute(String token, String botId, String[] r) throws Exception {
        String path = "/api/v1/bots/" + botId + "/" + r[1];
        return r[0].equals("GET") ? get(token, path) : post(token, path, r[2]);
    }

    @Test
    void aFarmerBotsPerBotRoutesAreOwnerOnlyBeforeAnythingElseIsChecked() throws Exception {
        makeFarmer("farmer"); // the owner's role
        hello("seen"); // no role, but it says it is a farmer (sticky)
        ok(sync("seen", "\"farmer\":{\"enabled\":true,\"modes\":[\"custom\"],\"farmsRev\":0},\"programs\":[],\"draft\":null"));
        hello("plain");
        ok(sync("plain", null));
        // A user with every non-owner permission, console included, and one with none of the console sub-permissions.
        String uma = userToken("uma", ALL_BUT_OWNER);
        String lowly = userToken("low", "\"activity\":true");
        for (String botId : new String[]{"farmer", "seen"}) {
            for (String token : new String[]{uma, lowly}) {
                for (String[] r : BOT_ROUTES) {
                    Res res = botRoute(token, botId, r);
                    assertEquals(403, res.status, botId + " " + r[0] + " " + r[1] + " " + res.body);
                    assertEquals("owner", res.obj().get("permission").getAsString(), r[1]);
                    assertEquals(OWNER_ONLY_TEXT, res.obj().get("error").getAsString());
                }
            }
            // The owner is not refused (the answers are the normal ones: 200, 404 for no settings yet, 403 confirm password).
            for (String[] r : BOT_ROUTES) {
                Res res = botRoute(owner, botId, r);
                assertFalse(res.status == 403 && res.body.contains("\"permission\":\"owner\""), botId + " " + r[1] + " " + res.body);
            }
        }
        // A bot that is not a farmer keeps its old answers: activity 200, the console asks for the password (not "owner").
        assertEquals(200, botRoute(uma, "plain", BOT_ROUTES[3]).status);
        Res console = botRoute(uma, "plain", BOT_ROUTES[2]);
        assertEquals(200, console.status, "the same user gets the normal console of a bot that is not a farmer");
        assertEquals(403, botRoute(lowly, "plain", BOT_ROUTES[2]).status);
        assertTrue(botRoute(lowly, "plain", BOT_ROUTES[2]).body.contains("\"permission\":\"console\""));
        // An unknown id keeps its 404.
        assertEquals(404, botRoute(uma, "ghost", BOT_ROUTES[3]).status);
    }

    @Test
    void theFarmerCheckResolvesTheOpaqueIdFirstAndKeepsScopeAnswers() throws Exception {
        makeFarmer("farmer");
        String fay = userToken("fay", ALL_BUT_OWNER + ",\"streamer\":true");
        String opaque = router.ids().bid("farmer");
        Res viaOpaque = get(fay, "/api/v1/bots/" + opaque + "/activity");
        assertEquals(403, viaOpaque.status);
        assertEquals("owner", viaOpaque.obj().get("permission").getAsString());
        // The raw id is not accepted from a forced streamer: the unchanged 404.
        assertEquals(404, get(fay, "/api/v1/bots/farmer/activity").status);
        // A user whose bot scope excludes the farmer sees the unchanged 404, not a 403 that would confirm it.
        hello("other");
        String scoped = userToken("sco", "\"activity\":true,\"bots\":[\"other\"]");
        assertEquals(404, get(scoped, "/api/v1/bots/farmer/activity").status);
    }

    @Test
    void theOwnerGivingTheRoleMakesTheBotOwnerOnlyAndClearsItsStoredData() throws Exception {
        hello("b1");
        BotHub.Bot b = hub.bot("b1");
        // What it pushed while it was a normal bot: an activity snapshot with events, a settings scope, a command and its result.
        long now = System.currentTimeMillis();
        JsonObject raw = JsonParser.parseString("{\"at\":" + now + ",\"connection\":{\"state\":\"online\",\"x\":1234,\"y\":64,\"z\":-5678},"
            + "\"current\":[{\"source\":\"Pathfinder\",\"kind\":\"process\",\"title\":\"Walking to 1234, 64, -5678\"}],"
            + "\"events\":[{\"at\":" + now + ",\"source\":\"chat\",\"text\":\"at 1234 64 -5678\"}]}").getAsJsonObject();
        hub.activity(b, Activity.clean(raw, now), Activity.events(raw, now));
        JsonObject values = new JsonObject();
        values.addProperty("home", "1234 64 -5678");
        hub.state(b, "plugin", "s1", "v1", null, null, values);
        String job = hub.queueCommand(b, "stash where");
        hub.result(b, job, true, false, null, null, false, JsonParser.parseString("{\"title\":\"1234 64 -5678\",\"fields\":[],\"lines\":[]}").getAsJsonObject());
        assertNotNull(b.activity);
        assertFalse(b.events.isEmpty());
        assertFalse(b.scopes.isEmpty());
        assertFalse(b.console.isEmpty());
        assertFalse(b.jobs.isEmpty());
        // Flip by the owner's role.
        ok(post(owner, "/api/v1/bots/roles", "{\"botId\":\"b1\",\"roles\":[\"farmer\"]}"));
        assertEmpty(b);
    }

    @Test
    void theFirstFarmerEnabledSyncClearsAndTheFlagIsSticky() throws Exception {
        hello("b1");
        BotHub.Bot b = hub.bot("b1");
        long now = System.currentTimeMillis();
        hub.activity(b, Activity.clean(JsonParser.parseString("{\"at\":" + now + ",\"connection\":{\"state\":\"online\"}}"), now),
            Activity.events(JsonParser.parseString("{\"events\":[{\"at\":" + now + ",\"source\":\"c\",\"text\":\"t\"}]}"), now));
        JsonObject values = new JsonObject();
        values.addProperty("home", "1234 64 -5678");
        hub.state(b, "plugin", "s1", "v1", null, null, values);
        String job = hub.queueCommand(b, "stash where");
        hub.result(b, job, true, false, null, null, false, null);
        ok(sync("b1", null)); // not a farmer yet: nothing happens
        assertNotNull(b.activity);
        assertFalse(tasks.isFarmer("b1"));
        ok(sync("b1", "\"farmer\":{\"enabled\":true,\"farmsRev\":0},\"programs\":[],\"draft\":null"));
        assertTrue(tasks.isFarmer("b1"));
        assertTrue(tasks.ops.bots.get("b1").farmerSeen);
        assertEmpty(b);
        // After the flip what it pushes (scrubbed) stays; later syncs do not clear again, and false does not clear the flag.
        hub.activity(b, Activity.cleanFarmer(JsonParser.parseString("{\"connection\":{\"state\":\"online\"}}"), now), new JsonArray());
        ok(sync("b1", "\"farmer\":{\"enabled\":false,\"farmsRev\":0},\"programs\":[],\"draft\":null"));
        assertNotNull(b.activity);
        assertTrue(tasks.isFarmer("b1"), "sticky in M2");
        restart();
        assertTrue(tasks.isFarmer("b1"), "persisted");
    }

    private static void assertEmpty(BotHub.Bot b) {
        synchronized (b) {
            assertNull(b.activity);
            assertEquals(0, b.activityAt);
            assertTrue(b.events.isEmpty());
            assertTrue(b.eventKeys.isEmpty());
            assertTrue(b.scopes.isEmpty());
            assertTrue(b.console.isEmpty());
            assertTrue(b.jobs.isEmpty());
            assertTrue(b.queue.isEmpty());
        }
    }

    // ---------- the activity allowlist (P5d) ----------

    private Res activity(String botId, String activity) throws Exception {
        return botAnon("/api/v1/bot/activity", "{\"botId\":\"" + botId + "\",\"activity\":" + activity + "}");
    }

    private static final String POS = "12 64 -30";

    @Test
    void aFarmersActivityIsRebuiltFromTheAllowlist() throws Exception {
        makeFarmer("farmer");
        long now = System.currentTimeMillis();
        ok(activity("farmer", "{\"at\":" + now + ",\"foo\":\"" + POS + "\",\"connection\":{\"state\":\"online\",\"x\":98765,\"y\":64,\"z\":-30,"
            + "\"dimension\":\"minecraft:the_nether\",\"server\":\"2b2t.org\",\"queueEta\":\"" + POS + "\",\"controllingPlayer\":\"Steve\","
            + "\"queuePosition\":5,\"health\":20,\"food\":18,\"ping\":40,\"uptimeSeconds\":3600,\"spectators\":1},"
            + "\"current\":[{\"source\":\"Pathfinder\",\"kind\":\"process\",\"title\":\"Walking to " + POS + "\",\"detail\":\"" + POS + "\","
            + "\"level\":\"warn\",\"progress\":{\"done\":3,\"total\":10},\"since\":" + now + "}],"
            + "\"modules\":[{\"name\":\"AutoEat\",\"enabled\":true,\"status\":\"at " + POS + "\"}],\"plugins\":[{\"id\":\"p\",\"version\":\"1\"}],"
            + "\"events\":[{\"at\":" + now + ",\"source\":\"chat\",\"text\":\"Steve joined at " + POS + "\"}]}"));
        BotHub.Bot b = hub.bot("farmer");
        JsonObject stored = b.activity;
        assertEquals(Set.of("at", "connection", "current", "modules", "plugins", "eventCount"), stored.keySet());
        assertEquals(Set.of("state", "queuePosition", "health", "food", "ping", "uptimeSeconds", "spectators"), stored.getAsJsonObject("connection").keySet());
        JsonObject c = stored.getAsJsonArray("current").get(0).getAsJsonObject();
        assertEquals(Set.of("source", "kind", "level", "progress", "since"), c.keySet());
        assertEquals("Farmer", c.get("source").getAsString());
        assertEquals(Set.of("done", "total"), c.getAsJsonObject("progress").keySet());
        assertEquals(0, stored.getAsJsonArray("modules").size());
        assertEquals(0, stored.getAsJsonArray("plugins").size());
        assertEquals(1, stored.get("eventCount").getAsInt());
        assertTrue(b.events.isEmpty(), "event texts are never stored");
        // Served to the owner: no marker anywhere.
        String served = get(owner, "/api/v1/bots/farmer/activity").body;
        for (String bad : new String[]{POS, "98765", "Steve", "nether", "2b2t", "Walking", "AutoEat", "\"x\"", "\"y\"", "\"z\""}) {
            assertFalse(served.contains(bad), bad + " in " + served);
            assertFalse(stored.toString().contains(bad), bad + " in the stored snapshot");
        }
        // A normal bot's activity keeps its title (the scrub is for farmers only).
        hello("plain");
        ok(activity("plain", "{\"at\":" + now + ",\"current\":[{\"source\":\"Pathfinder\",\"kind\":\"process\",\"title\":\"Walking\"}]}"));
        assertEquals("Walking", hub.bot("plain").activity.getAsJsonArray("current").get(0).getAsJsonObject().get("title").getAsString());
    }

    @Test
    void aPositionInsideEachAllowedKeyIsDroppedNotStored() throws Exception {
        makeFarmer("farmer");
        long now = System.currentTimeMillis();
        String[][] cases = {
            {"\"connection\":{\"state\":\"" + POS + "\"}", "connection.state"},
            {"\"connection\":{\"queuePosition\":\"" + POS + "\"}", "queuePosition"},
            {"\"connection\":{\"health\":{\"x\":1,\"y\":2,\"z\":3}}", "health"},
            {"\"connection\":{\"food\":[12,64,-30]}", "food"},
            {"\"connection\":{\"ping\":\"" + POS + "\"}", "ping"},
            {"\"connection\":{\"uptimeSeconds\":12.5}", "uptimeSeconds"},
            {"\"connection\":{\"spectators\":true}", "spectators"},
            {"\"connection\":{\"queuePosition\":100001,\"health\":21,\"food\":-1,\"ping\":60001}", "range"},
            {"\"current\":[{\"kind\":\"" + POS + "\"}]", "kind"},
            {"\"current\":[{\"level\":\"" + POS + "\"}]", "level"},
            {"\"current\":[{\"progress\":{\"x\":1,\"y\":2,\"z\":3}}]", "progress"},
            {"\"current\":[{\"progress\":{\"done\":\"" + POS + "\",\"total\":5}}]", "progress"},
            {"\"current\":[{\"progress\":[12,64,-30]}]", "progress"},
            {"\"current\":[{\"since\":\"" + POS + "\"}]", "since"},
            {"\"current\":[{\"since\":1.5}]", "since"},
            {"\"at\":\"" + POS + "\"", "at"},
        };
        for (String[] k : cases) {
            ok(activity("farmer", "{" + k[0] + "}"));
            JsonObject stored = hub.bot("farmer").activity;
            String s = stored.toString();
            assertFalse(s.contains("12") && s.contains("64") && s.contains("-30") && s.contains(POS), k[1] + " " + s);
            assertFalse(s.contains(POS), k[1] + " " + s);
            assertFalse(s.contains("\"x\"") || s.contains("\"z\""), k[1] + " " + s);
            // Dropped values are absent, not clamped.
            if (k[1].equals("range")) {
                assertEquals(Set.of("state"), stored.getAsJsonObject("connection").keySet(), s);
            }
            if (k[1].equals("progress")) {
                assertFalse(stored.getAsJsonArray("current").get(0).getAsJsonObject().has("progress"), s);
            }
            assertEquals(now / 1000 > 0, stored.get("at").getAsLong() > 0);
        }
        // Kept values are typed and in range.
        ok(activity("farmer", "{\"at\":" + now + ",\"connection\":{\"state\":\"queue\",\"queuePosition\":100000,\"health\":0,\"spectators\":1000},"
            + "\"current\":[{\"kind\":\"module\",\"level\":\"error\",\"progress\":{\"done\":0,\"total\":1000000,\"extra\":1}}]}"));
        JsonObject stored = hub.bot("farmer").activity;
        assertEquals("queue", stored.getAsJsonObject("connection").get("state").getAsString());
        assertEquals(100000, stored.getAsJsonObject("connection").get("queuePosition").getAsInt());
        assertEquals(Set.of("done", "total"), stored.getAsJsonArray("current").get(0).getAsJsonObject().getAsJsonObject("progress").keySet());
        // At most 20 items, whatever the bot sends.
        StringBuilder many = new StringBuilder("{\"current\":[");
        for (int i = 0; i < 40; i++) many.append(i == 0 ? "" : ",").append("{\"kind\":\"plugin\"}");
        ok(activity("farmer", many + "]}"));
        assertEquals(20, hub.bot("farmer").activity.getAsJsonArray("current").size());
    }

    @Test
    void aFarmersViewerFramesAreRefused() throws Exception {
        makeFarmer("farmer");
        hello("plain");
        Res r = botAnon("/api/v1/bot/viewer", "{\"botId\":\"farmer\",\"seq\":1,\"x\":1}");
        assertEquals(403, r.status, r.body);
        assertEquals("farmer", r.code());
        assertNotEquals(403, botAnon("/api/v1/bot/viewer", "{\"botId\":\"plain\"}").status, "other bots are not affected");
        // Even when the bot id is not first in the body.
        assertEquals(403, botAnon("/api/v1/bot/viewer", "{\"seq\":1,\"botId\":\"farmer\"}").status);
        // The flag set by a sync (no role) refuses too.
        hello("seen");
        ok(sync("seen", "\"farmer\":{\"enabled\":true},\"programs\":[],\"draft\":null"));
        assertEquals(403, botAnon("/api/v1/bot/viewer", "{\"botId\":\"seen\"}").status);
    }

    @Test
    void noPositionIsEverStoredAnywhereInTheServerFiles() throws Exception {
        String[] s = runningSession();
        ok(report("farmer", s[1], 1, 1, "running", "\"phase\":\"loop\",\"checkpoint\":{\"step\":\"loop\",\"data\":{\"loop\":3,\"anchorId\":\"a2\",\"tick\":900}}"));
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\",\"reason\":\"tidy up\"}"));
        for (String f : new String[]{"bots.json", "ops.json", "tasks.json", "automation/farms.json", "audit.json"}) {
            String text = java.nio.file.Files.readString(data.resolve(f));
            assertNoPositionString(JsonParser.parseString(text), f);
        }
    }

    /** Every string value in the file passes the G2 pattern (key names are the server's own, e.g. a task's "origin": "website"). */
    private static void assertNoPositionString(JsonElement e, String where) {
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) assertNoPositionString(en.getValue(), where);
        } else if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray()) assertNoPositionString(c, where);
        } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            assertFalse(PositionGuard.looksLikePosition(e.getAsString()), where + ": " + e.getAsString());
        }
    }
}

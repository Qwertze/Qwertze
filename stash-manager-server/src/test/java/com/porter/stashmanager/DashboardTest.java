package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings checks against the schema, the job lifecycle and the console history. */
class DashboardTest {
    private static JsonObject node(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void valueChecks() {
        JsonObject interval = node("{\"path\":\"a\",\"type\":\"int\",\"min\":5,\"max\":600}");
        assertNull(DashboardRoutes.check(interval, JsonParser.parseString("30")));
        assertTrue(DashboardRoutes.check(interval, JsonParser.parseString("4")).contains("at least 5"));
        assertTrue(DashboardRoutes.check(interval, JsonParser.parseString("601")).contains("at most 600"));
        assertTrue(DashboardRoutes.check(interval, JsonParser.parseString("5.5")).contains("whole"));
        assertTrue(DashboardRoutes.check(interval, JsonParser.parseString("\"7\"")).contains("number"));
        assertTrue(DashboardRoutes.check(interval, JsonParser.parseString("null")).contains("required"));

        JsonObject nullable = node("{\"path\":\"b\",\"type\":\"int\",\"nullable\":true}");
        assertNull(DashboardRoutes.check(nullable, JsonParser.parseString("null")));
        assertTrue(DashboardRoutes.check(nullable, JsonParser.parseString("3000000000")).contains("at most"));

        JsonObject mode = node("{\"path\":\"m\",\"type\":\"enum\",\"options\":[\"SAFE\",\"FAST\"]}");
        assertNull(DashboardRoutes.check(mode, JsonParser.parseString("\"FAST\"")));
        assertTrue(DashboardRoutes.check(mode, JsonParser.parseString("\"fast\"")).contains("choices"));

        JsonObject list = node("{\"path\":\"l\",\"type\":\"stringList\"}");
        assertNull(DashboardRoutes.check(list, JsonParser.parseString("[\"a\",\"b\"]")));
        assertTrue(DashboardRoutes.check(list, JsonParser.parseString("[\"a\",1]")).contains("text"));

        assertTrue(DashboardRoutes.check(node("{\"path\":\"k\",\"type\":\"string\",\"secret\":true,\"set\":true,\"readOnly\":true}"),
            JsonParser.parseString("\"x\"")).contains("secret"));
        assertTrue(DashboardRoutes.check(node("{\"path\":\"u\",\"type\":\"string\",\"readOnly\":true,\"reason\":\"fixed\"}"),
            JsonParser.parseString("\"x\"")).contains("read-only: fixed"));
        assertTrue(DashboardRoutes.check(null, JsonParser.parseString("1")).contains("no such"));
        assertTrue(DashboardRoutes.check(node("{\"path\":\"b\",\"type\":\"bool\"}"), JsonParser.parseString("1")).contains("true or false"));
        assertTrue(DashboardRoutes.check(node("{\"path\":\"s\",\"type\":\"string\"}"),
            JsonParser.parseString("\"" + "x".repeat(1001) + "\"")).contains("1000"));
    }

    @Test
    void schemaWalkIndexesAndRejectsBadNodes() {
        Map<String, JsonObject> nodes = new HashMap<>();
        JsonArray ok = JsonParser.parseString("[{\"path\":\"section:A\",\"type\":\"group\",\"children\":[{\"path\":\"x\",\"type\":\"bool\"}]}]").getAsJsonArray();
        assertNull(BotRoutes.walk(ok, nodes, 0));
        assertTrue(nodes.containsKey("x") && nodes.containsKey("section:A"));
        assertTrue(BotRoutes.walk(JsonParser.parseString("[{\"path\":\"x\",\"type\":\"map\"}]").getAsJsonArray(), new HashMap<>(), 0).contains("unknown type"));
        assertTrue(BotRoutes.walk(JsonParser.parseString("[{\"path\":\"g\",\"type\":\"group\"}]").getAsJsonArray(), new HashMap<>(), 0).contains("children"));
    }

    @Test
    void jobsExpireUnpickedAndBecomeUnknownUnanswered() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        BotHub hub = new BotHub(now::get);
        assertTrue(hub.hello("bot-1", "Bob", new JsonObject(), new JsonObject(), List.of("settings", "console")));
        BotHub.Bot b = hub.bot("bot-1");
        String expired = hub.queueSets(b, "plugin", List.of(node("{\"path\":\"x\",\"value\":true}"))).get(0);
        now.addAndGet(BotHub.EXPIRE_QUEUED_MS);
        assertEquals("expired", hub.jobs(b, List.of(expired)).get(0).getAsJsonObject().get("status").getAsString());

        String sent = hub.queueCommand(b, "help");
        BotHub.Poll p = hub.next(b);
        assertEquals(200, p.status());
        assertEquals(sent, p.body().getAsJsonArray("jobs").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("sent", hub.jobs(b, List.of(sent)).get(0).getAsJsonObject().get("status").getAsString());
        now.addAndGet(BotHub.UNKNOWN_SENT_MS);
        assertEquals("unknown", hub.jobs(b, List.of(sent)).get(0).getAsJsonObject().get("status").getAsString());
    }

    @Test
    void consoleAppendsLaterPartsAndHidesSensitiveCommands() throws Exception {
        AtomicLong now = new AtomicLong(5_000_000);
        BotHub hub = new BotHub(now::get);
        hub.hello("bot-2", "Al", new JsonObject(), new JsonObject(), List.of("console"));
        BotHub.Bot b = hub.bot("bot-2");
        String slow = hub.queueCommand(b, "slow");
        String secret = hub.queueCommand(b, "setpw hunter2");
        String auth = hub.queueCommand(b, "auth login x");
        hub.next(b);
        JsonObject first = node("{\"title\":\"Working\",\"fields\":[],\"lines\":[\"started\"]}");
        hub.result(b, slow, true, false, null, null, false, first);
        hub.result(b, slow, true, true, null, null, false, node("{\"title\":\"Done\",\"fields\":[],\"lines\":[]}"));
        hub.result(b, secret, true, false, null, null, true, node("{\"title\":\"Saved\",\"color\":\"success\",\"description\":\"pw hunter2\",\"fields\":[],\"lines\":[\"x\"]}"));

        JsonObject c = hub.console(b, 0);
        JsonArray entries = c.getAsJsonArray("entries");
        assertEquals(3, entries.size());
        JsonObject e0 = entries.get(0).getAsJsonObject();
        assertEquals("done", e0.get("status").getAsString());
        assertEquals(2, e0.getAsJsonArray("outputs").size(), "the later part is appended");
        JsonObject e1 = entries.get(1).getAsJsonObject();
        assertEquals("(hidden)", e1.get("command").getAsString());
        assertFalse(e1.toString().contains("hunter2"), "only the title and colour of a sensitive reply are kept");
        assertEquals("(hidden)", entries.get(2).getAsJsonObject().get("command").getAsString(), "auth is hidden before the bot answers");

        long rev = c.get("rev").getAsLong();
        assertEquals(0, hub.console(b, rev).getAsJsonArray("entries").size(), "nothing new since that revision");
        hub.result(b, slow, true, true, null, null, false, node("{\"title\":\"Later\",\"fields\":[],\"lines\":[]}"));
        assertEquals(1, hub.console(b, rev).getAsJsonArray("entries").size(), "an appended part shows up as a change");
    }

    @Test
    void secretsAreDroppedAndMarked() {
        Map<String, JsonObject> nodes = new HashMap<>();
        JsonArray schema = JsonParser.parseString("[{\"path\":\"discord.token\",\"type\":\"string\",\"secret\":true,\"set\":true},"
            + "{\"path\":\"notifications.ntfy.topic\",\"type\":\"string\"},{\"path\":\"client.name\",\"type\":\"string\"},"
            + "{\"path\":\"weird.secretThing\",\"type\":\"string\"}]").getAsJsonArray();
        assertNull(BotRoutes.walk(schema, nodes, 0));
        assertTrue(nodes.get("notifications.ntfy.topic").get("secret").getAsBoolean(), "a secret-looking path is marked secret");
        assertTrue(nodes.get("notifications.ntfy.topic").get("readOnly").getAsBoolean());
        JsonObject values = node("{\"discord.token\":\"LEAK\",\"notifications.ntfy.topic\":\"LEAK\",\"client.name\":\"ok\","
            + "\"database.password\":\"LEAK\",\"weird.secretThing\":\"LEAK\",\"x.apiKey\":\"LEAK\"}");
        JsonObject clean = BotRoutes.scrubSecrets(values, nodes);
        assertEquals(1, clean.size());
        assertEquals("ok", clean.get("client.name").getAsString());
        assertFalse(clean.toString().contains("LEAK"));
        assertEquals(1, BotRoutes.scrubSecrets(values, null).size(), "the path rule works without a schema too");
        Map<String, JsonObject> n2 = new HashMap<>();
        assertNull(BotRoutes.walk(JsonParser.parseString("[{\"path\":\"database.url\",\"type\":\"string\"},{\"path\":\"web.host\",\"type\":\"string\"}]").getAsJsonArray(), n2, 0));
        JsonObject withUrl = BotRoutes.scrubSecrets(node("{\"database.url\":\"postgres://admin:hunter2@db:5432/x\",\"web.host\":\"http://example.com/a@b\","
            + "\"server.sessionTimeout\":5,\"license\":\"x\"}"), n2);
        assertFalse(withUrl.has("database.url"), "a URL with user info is a secret");
        assertTrue(n2.get("database.url").get("secret").getAsBoolean(), "and its node is marked");
        assertTrue(withUrl.has("web.host"), "an @ after the path is no user info");
        assertFalse(withUrl.has("server.sessionTimeout") || withUrl.has("license"), "the wider name list");
        assertTrue(BotRoutes.SCOPE.matcher("plugin:Auto-Reply_2").matches());
        assertFalse(BotRoutes.SCOPE.matcher("other").matches());
    }

    @Test
    void activityTimesOutOfRangeBecomeTheReceiveTime() {
        long now = 1_800_000_000_000L;
        JsonObject raw = node("{\"at\":5,\"current\":[{\"source\":\"A\",\"since\":5},{\"source\":\"B\",\"since\":" + (now - 60_000) + "}],"
            + "\"events\":[{\"at\":5,\"source\":\"x\",\"text\":\"old\"},{\"at\":" + (now + 3_600_000) + ",\"source\":\"x\",\"text\":\"future\"},"
            + "{\"at\":" + (now - 1000) + ",\"source\":\"x\",\"text\":\"fine\"}]}");
        JsonObject a = Activity.clean(raw, now);
        assertEquals(now, a.get("at").getAsLong());
        assertFalse(a.getAsJsonArray("current").get(0).getAsJsonObject().has("since"), "a nonsense since is dropped");
        assertEquals(now - 60_000, a.getAsJsonArray("current").get(1).getAsJsonObject().get("since").getAsLong());
        JsonArray ev = Activity.events(raw, now);
        assertEquals(now, ev.get(0).getAsJsonObject().get("at").getAsLong());
        assertEquals(now, ev.get(1).getAsJsonObject().get("at").getAsLong());
        assertEquals(now - 1000, ev.get(2).getAsJsonObject().get("at").getAsLong());
    }

    @Test
    void stateAsksForAMissingSchema() {
        BotHub hub = new BotHub();
        hub.hello("bot-3", null, new JsonObject(), new JsonObject(), List.of("settings"));
        BotHub.Bot b = hub.bot("bot-3");
        JsonArray need = hub.state(b, "plugin", "h1", "v1", null, null, new JsonObject());
        assertEquals("schema", need.get(0).getAsJsonObject().get("what").getAsString());
        JsonArray schema = new JsonArray();
        assertEquals(0, hub.state(b, "plugin", "h1", "v1", schema, new HashMap<>(), new JsonObject()).size(), "an unrequested schema is taken too");
        assertEquals(1, hub.state(b, "plugin", "h2", "v2", null, null, new JsonObject()).size(), "a new schema hash without the schema asks again");
    }
}

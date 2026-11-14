package com.porter.stashmanager;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The farm records and the Farms routes (FARM-CONTRACT E1 to E5), owner only. */
class FarmRoutesTest extends FarmHarness {
    /** POST /farms/start with the clock moved past the 6-a-minute limit first. */
    Res startFarm(String body) throws Exception {
        advance(11_000);
        for (OpsStore.Profile p : tasks.ops.bots.values()) p.lastSync = clk.get(); // the farmer is still there
        return post(owner, "/api/v1/farms/start", body);
    }

    @Test
    void createReadEditAndTheOptimisticLock() throws Exception {
        makeFarmer("farmer");
        JsonObject created = ok(post(owner, "/api/v1/farms", "{\"name\":\"Bone farm\",\"server\":\"2b2t.org\",\"mode\":\"custom\","
            + "\"produces\":[{\"key\":\"minecraft:bone_meal\",\"perHour\":100}],\"needs\":[{\"key\":\"minecraft:bone_meal\",\"min\":64,\"max\":256}],"
            + "\"thresholds\":{\"healthStop\":8},\"travel\":{\"preset\":\"walk\"}}"));
        String id = created.get("id").getAsString();
        assertTrue(FarmVocab.FARM_ID.matcher(id).matches());
        JsonObject farm = farmOf(id);
        assertEquals("farmer", farm.get("botId").getAsString(), "the only farmer is taken when botId is left out");
        assertEquals("minecraft:overworld", farm.get("dim").getAsString());
        assertEquals(1, farm.get("rev").getAsInt());
        assertEquals(8, farm.getAsJsonObject("thresholds").get("healthStop").getAsInt());
        assertEquals(6, farm.getAsJsonObject("thresholds").get("food").getAsInt());
        assertEquals(7_200_000L, farm.getAsJsonObject("session").get("maxMs").getAsLong());
        assertTrue(farm.get("program").isJsonNull());
        assertTrue(farm.get("dropStash").isJsonNull() && farm.get("supplyStash").isJsonNull() && farm.get("schedule").isJsonNull());
        assertEquals("idle", farm.getAsJsonObject("status").get("state").getAsString());
        // The list body.
        JsonObject list = ok(get(owner, "/api/v1/farms"));
        assertEquals("farmer", list.getAsJsonObject("farmer").get("botId").getAsString());
        assertTrue(list.getAsJsonObject("farmer").get("online").getAsBoolean());
        assertEquals(2, list.getAsJsonObject("farmer").getAsJsonArray("modes").size());
        assertTrue(list.get("draft").isJsonNull());
        assertEquals(64, list.getAsJsonObject("limits").get("maxFarms").getAsInt());
        // Edit with the rev it read.
        JsonObject edit = ok(post(owner, "/api/v1/farms", "{\"id\":\"" + id + "\",\"rev\":1,\"name\":\"Bone farm 2\",\"enabled\":false}"));
        assertEquals(id, edit.get("id").getAsString());
        assertEquals(2, farmOf(id).get("rev").getAsInt());
        assertFalse(farmOf(id).get("enabled").getAsBoolean());
        assertEquals(8, farmOf(id).getAsJsonObject("thresholds").get("healthStop").getAsInt(), "an edit keeps what it does not name");
        // A stale rev is a conflict that carries the current farm.
        Res conflict = post(owner, "/api/v1/farms", "{\"id\":\"" + id + "\",\"rev\":1,\"name\":\"Other\"}");
        assertEquals(409, conflict.status);
        assertEquals("rev_conflict", conflict.code());
        assertEquals(2, conflict.obj().getAsJsonObject("farm").get("rev").getAsInt());
        // The server is immutable.
        assertEquals("server", post(owner, "/api/v1/farms", "{\"id\":\"" + id + "\",\"rev\":2,\"server\":\"other.net\"}").field());
        // The registry rev moved for each create and edit.
        assertEquals(3, ok(get(owner, "/api/v1/farms")).get("rev").getAsInt());
    }

    @Test
    void namesFollowTheRules() throws Exception {
        makeFarmer("farmer");
        for (String bad : new String[]{"", "   ", "Farm 123", "1a2b3c", "x".repeat(33), "Bad\\u00a7name", "tab\\tname", "12 64 -30", "Farm 1 2 3"}) {
            Res r = post(owner, "/api/v1/farms", "{\"name\":\"" + bad + "\",\"server\":\"s\",\"mode\":\"custom\"}");
            assertEquals(400, r.status, bad + " " + r.body);
            assertTrue(r.code().equals("bad_field") || r.code().equals("position_like"), bad + " " + r.body);
            assertFalse(r.body.contains(bad.replace("\\u00a7", "§")) && !bad.isEmpty(), "no echo of " + bad);
        }
        for (String good : new String[]{"Farm 2", "Zombie piglin trap", "a".repeat(32), "  padded  ", "Farm 12"}) {
            ok(post(owner, "/api/v1/farms", "{\"name\":\"" + good + "\",\"server\":\"s\",\"mode\":\"custom\"}"));
        }
        // Unique per install, whatever the case or the padding.
        Res dup = post(owner, "/api/v1/farms", "{\"name\":\"PADDED\",\"server\":\"s\",\"mode\":\"custom\"}");
        assertEquals(409, dup.status);
        assertEquals("name_in_use", dup.code());
        // A name only changes to its own spelling.
        String id = farmIdOf("Farm 2");
        ok(post(owner, "/api/v1/farms", "{\"id\":\"" + id + "\",\"rev\":1,\"name\":\"farm 2\"}"));
    }

    private String farmIdOf(String name) throws Exception {
        for (var e : ok(get(owner, "/api/v1/farms")).getAsJsonArray("farms")) {
            if (e.getAsJsonObject().get("name").getAsString().equals(name)) return e.getAsJsonObject().get("id").getAsString();
        }
        throw new AssertionError("no farm " + name);
    }

    @Test
    void fieldsAreValidated() throws Exception {
        makeFarmer("farmer");
        String base = "\"name\":\"F\",\"server\":\"s\",\"mode\":\"custom\"";
        String[][] bad = {
            {"\"mode\":\"fly\"", "mode"}, {"\"dim\":\"Overworld\"", "dim"}, {"\"botId\":\"nobody\"", "botId"},
            {"\"enabled\":\"yes\"", "enabled"}, {"\"offlineOk\":1", "offlineOk"},
            {"\"produces\":[{\"key\":\"bad key\",\"perHour\":1}]", "produces"},
            {"\"produces\":[{\"key\":\"minecraft:a\",\"perHour\":1000001}]", "produces"},
            {"\"needs\":[{\"key\":\"minecraft:a\",\"min\":9,\"max\":3}]", "needs"},
            {"\"needs\":[{\"key\":\"minecraft:a\",\"min\":0,\"max\":100001}]", "needs"},
            {"\"session\":{\"maxMs\":59999}", "session"}, {"\"session\":{\"minMs\":5000000,\"maxMs\":60000}", "session"},
            {"\"thresholds\":{\"healthStop\":0}", "thresholds"}, {"\"thresholds\":{\"healthStop\":20}", "thresholds"},
            {"\"thresholds\":{\"playerRadius\":7}", "thresholds"}, {"\"thresholds\":{\"invFullPct\":49}", "thresholds"},
            {"\"thresholds\":{\"food\":1.5}", "thresholds"},
            {"\"travel\":{\"preset\":\"teleport\"}", "travel.preset"}, {"\"travel\":{\"out\":\"fly\"}", "travel.out"},
            {"\"travel\":{\"preset\":\"pearl_both\"}", "travel.station"}, {"\"travel\":{\"station\":\"st-1\"}", "travel.station"},
        };
        for (String[] b : bad) {
            Res r = post(owner, "/api/v1/farms", "{" + base + "," + b[0] + "}");
            assertEquals(400, r.status, b[0] + " " + r.body);
            assertEquals("bad_field", r.code(), b[0] + " " + r.body);
            assertEquals(b[1], r.field(), b[0]);
        }
        // The hooks must be null in M2 (reserved_field), and so must a route; the browser never writes the program.
        for (String[] b : new String[][]{{"\"dropStash\":{}", "dropStash"}, {"\"supplyStash\":\"x\"", "supplyStash"}, {"\"schedule\":[]", "schedule"},
            {"\"travel\":{\"route\":[]}", "travel.route"}, {"\"program\":{\"hash\":\"" + HASH_A + "\"}", "program"}, {"\"lastSession\":{}", "lastSession"}}) {
            Res r = post(owner, "/api/v1/farms", "{" + base + "," + b[0] + "}");
            assertEquals(400, r.status, b[0]);
            assertEquals("reserved_field", r.code(), b[0]);
            assertEquals(b[1], r.field());
        }
        ok(post(owner, "/api/v1/farms", "{" + base + ",\"dropStash\":null,\"travel\":{\"route\":null,\"preset\":\"pearl_both\",\"station\":\"ps-0a0b0c0d\"}}"));
        // No farmer role: no botId can be inferred.
        hello("plain");
        ok(post(owner, "/api/v1/bots/roles", "{\"botId\":\"farmer\",\"roles\":[]}"));
        assertEquals("botId", post(owner, "/api/v1/farms", "{\"name\":\"G\",\"server\":\"s\",\"mode\":\"custom\"}").field());
        // Body limit: 4 KiB.
        assertEquals(413, post(owner, "/api/v1/farms", "{\"name\":\"" + "a".repeat(5000) + "\"}").status);
    }

    @Test
    void aFarmHoldsNoPositionAndTheGuardRunsFirst() throws Exception {
        makeFarmer("farmer");
        Res r = post(owner, "/api/v1/farms", "{\"name\":\"F\",\"server\":\"s\",\"mode\":\"custom\",\"travel\":{\"x\":1,\"station\":null}}");
        assertEquals(400, r.status);
        assertEquals("position_field", r.code());
        r = post(owner, "/api/v1/farms", "{\"name\":\"F\",\"server\":\"s\",\"mode\":\"custom\",\"produces\":[{\"key\":\"-12,64,30\",\"perHour\":1}]}");
        assertEquals("position_like", r.code());
        assertFalse(r.body.contains("64"));
        assertEquals(0, ok(get(owner, "/api/v1/farms")).getAsJsonArray("farms").size());
    }

    @Test
    void theProgramBlockIsWrittenOnlyByTheFarmersSync() throws Exception {
        makeFarmer("farmer");
        String id = createFarm("Bone farm", "custom", "farmer");
        assertTrue(farmOf(id).get("program").isJsonNull());
        ok(syncPrograms("farmer", "[" + program(id, HASH_A, "custom") + "]", "null"));
        JsonObject p = farmOf(id).getAsJsonObject("program");
        assertEquals(HASH_A, p.get("hash").getAsString());
        assertEquals(2400, p.get("ticks").getAsInt());
        assertEquals(clk.get(), p.get("at").getAsLong(), "the server's time of receipt");
        // A program for a farm that is not assigned to this bot is ignored.
        makeFarmer("farmer2");
        ok(syncPrograms("farmer2", "[" + program(id, HASH_B, "custom") + "]", "null"));
        assertEquals(HASH_A, farmOf(id).getAsJsonObject("program").get("hash").getAsString());
        // A new hash replaces it; a farm missing from the list loses its program after two syncs.
        ok(syncPrograms("farmer", "[" + program(id, HASH_B, "custom") + "]", "null"));
        assertEquals(HASH_B, farmOf(id).getAsJsonObject("program").get("hash").getAsString());
        ok(syncPrograms("farmer", "[]", "null"));
        assertFalse(farmOf(id).get("program").isJsonNull(), "one sync without it is not enough");
        ok(syncPrograms("farmer", "[]", "null"));
        assertTrue(farmOf(id).get("program").isJsonNull());
    }

    @Test
    void startRefusalsInTheContractsOrder() throws Exception {
        makeFarmer("farmer");
        String id = createFarm("Bone farm", "custom", "farmer");
        assertEquals("no_farm", startFarm("{\"id\":\"f-00000000\"}").code());
        assertEquals("no_program", startFarm("{\"id\":\"" + id + "\"}").code());
        ok(syncPrograms("farmer", "[" + program(id, HASH_A, "afk_still") + "]", "null"));
        assertEquals("program_mode_mismatch", startFarm("{\"id\":\"" + id + "\"}").code());
        ok(syncPrograms("farmer", "[" + program(id, HASH_A, "custom") + "]", "null"));
        advance(TaskService.ONLINE_MS + 1000);
        assertEquals("farmer_offline", post(owner, "/api/v1/farms/start", "{\"id\":\"" + id + "\"}").code());
        ok(syncPrograms("farmer", "[" + program(id, HASH_A, "custom") + "]", "null"));
        ok(post(owner, "/api/v1/farms", "{\"id\":\"" + id + "\",\"rev\":1,\"enabled\":false}"));
        assertEquals("disabled", startFarm("{\"id\":\"" + id + "\"}").code());
        ok(post(owner, "/api/v1/farms", "{\"id\":\"" + id + "\",\"rev\":2,\"enabled\":true}"));
        assertEquals("bad_field", startFarm("{\"id\":\"" + id + "\",\"maxMs\":1000}").code());
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\"}"));
        assertEquals("stopped", startFarm("{\"id\":\"" + id + "\"}").code());
        ok(post(owner, "/api/v1/ops/resume", "{\"scope\":\"all\"}"));
        // A start works, takes its own time limit, and a second is busy.
        JsonObject started = ok(startFarm("{\"id\":\"" + id + "\",\"maxMs\":120000}"));
        assertTrue(FarmVocab.TASK_ID.matcher(started.get("taskId").getAsString()).matches());
        assertEquals("pending", farmOf(id).getAsJsonObject("status").get("state").getAsString());
        assertEquals(120000, tasksOf("farmer").get(0).getAsJsonObject().getAsJsonObject("params").get("maxMs").getAsLong());
        assertEquals("busy", startFarm("{\"id\":\"" + id + "\"}").code());
        // Another farm of the same bot: the bot has a task already.
        String other = createFarm("Other farm", "custom", "farmer");
        ok(syncPrograms("farmer", "[" + program(id, HASH_A, "custom") + "," + program(other, HASH_B, "custom") + "]", "null"));
        assertEquals("farmer_busy", startFarm("{\"id\":\"" + other + "\"}").code());
        // A bot with no farmer role has no farm to start.
        ok(post(owner, "/api/v1/bots/roles", "{\"botId\":\"farmer\",\"roles\":[]}"));
        assertEquals("no_farmer", startFarm("{\"id\":\"" + other + "\"}").code());
    }

    @Test
    void onlyCustomAndAfkStillRunInM2EvenWhenTheFarmerSaysItCanDoMore() throws Exception {
        makeFarmer("farmer");
        for (String mode : FarmVocab.MODES) {
            if (FarmVocab.M2_MODES.contains(mode)) continue;
            String id = createFarm("F " + mode.charAt(0) + mode.length(), mode, "farmer");
            ok(sync("farmer", "\"farmer\":{\"enabled\":true,\"modes\":[\"" + String.join("\",\"", FarmVocab.MODES) + "\"],\"farmsRev\":9},"
                + "\"programs\":[" + program(id, HASH_A, mode) + "],\"draft\":null"));
            Res r = startFarm("{\"id\":\"" + id + "\"}");
            assertEquals(409, r.status, mode);
            assertEquals("mode_not_ready", r.code(), mode);
            ok(post(owner, "/api/v1/farms/delete", "{\"id\":\"" + id + "\"}"));
        }
        // A mode M2 runs, but the farmer's build does not list it: also refused.
        String id = createFarm("Still", "afk_still", "farmer");
        ok(sync("farmer", "\"farmer\":{\"enabled\":true,\"modes\":[\"custom\"],\"farmsRev\":9},\"programs\":[" + program(id, HASH_A, "afk_still") + "],\"draft\":null"));
        assertEquals("mode_not_ready", startFarm("{\"id\":\"" + id + "\"}").code());
        ok(sync("farmer", "\"farmer\":{\"enabled\":true,\"modes\":[\"custom\",\"afk_still\"],\"farmsRev\":9},\"programs\":[" + program(id, HASH_A, "afk_still") + "],\"draft\":null"));
        ok(startFarm("{\"id\":\"" + id + "\"}"));
    }

    @Test
    void stopCancelsTheOpenSession() throws Exception {
        String[] s = runningSession();
        assertEquals("no_farm", post(owner, "/api/v1/farms/stop", "{\"id\":\"f-00000000\"}").code());
        ok(post(owner, "/api/v1/farms/stop", "{\"id\":\"" + s[0] + "\"}"));
        JsonObject t = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("cancelled", t.get("state").getAsString());
        assertEquals("stopped", t.getAsJsonObject("error").get("code").getAsString());
        assertEquals("stopped", farmOf(s[0]).getAsJsonObject("lastSession").get("endedBy").getAsString());
        Res none = post(owner, "/api/v1/farms/stop", "{\"id\":\"" + s[0] + "\"}");
        assertEquals(409, none.status);
        assertEquals("no_session", none.code());
        // The bot is told to drop the task by its next sync, and its own report of the same end is accepted.
        JsonObject sy = ok(sync("farmer", "\"running\":{\"taskId\":\"" + s[1] + "\",\"epoch\":1,\"state\":\"running\",\"phase\":\"loop\",\"seq\":1}"));
        assertEquals(s[1], sy.getAsJsonArray("cancel").get(0).getAsString());
        assertTrue(ok(report("farmer", s[1], 1, 2, "cancelled", "\"error\":{\"code\":\"stopped\"}")).get("dup").getAsBoolean());
    }

    @Test
    void deleteQueuesAForgetThatTheFarmerAcknowledges() throws Exception {
        makeFarmer("farmer");
        String id = farmWithProgram("farmer", "custom");
        String task = ok(startFarm("{\"id\":\"" + id + "\"}")).get("taskId").getAsString();
        Res open = post(owner, "/api/v1/farms/delete", "{\"id\":\"" + id + "\"}");
        assertEquals(409, open.status);
        assertEquals("session_open", open.code());
        ok(post(owner, "/api/v1/farms/stop", "{\"id\":\"" + id + "\"}"));
        ok(post(owner, "/api/v1/farms/delete", "{\"id\":\"" + id + "\"}"));
        assertNull(farmOf(id));
        // The sync reply asks the bot to forget it, until the bot says it has.
        JsonObject r = ok(sync("farmer", farmerPart("[\"custom\"]", 99)));
        assertEquals(id, r.getAsJsonArray("forget").get(0).getAsString());
        r = ok(sync("farmer", "\"farmer\":{\"enabled\":true,\"farmsRev\":99,\"forgot\":[\"" + id + "\"]},\"programs\":[],\"draft\":null"));
        assertEquals(0, r.getAsJsonArray("forget").size());
        assertEquals(task, tasksOf("farmer").get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(Files.readString(data.resolve("audit.json")).contains("farm.delete"));
    }

    @Test
    void aSyncGetsTheFarmListOnlyWhenItIsBehind() throws Exception {
        makeFarmer("farmer");
        String id = createFarm("Bone farm", "custom", "farmer");
        JsonObject r = ok(sync("farmer", farmerPart("[\"custom\"]", 0)));
        assertEquals(2, r.get("farmsRev").getAsInt(), "the registry rev (1 at the start, +1 for the create)");
        JsonObject f = r.getAsJsonArray("farms").get(0).getAsJsonObject();
        assertEquals(id, f.get("id").getAsString());
        assertEquals("Bone farm", f.get("name").getAsString(), "the farmer gets names, it is the owner's trusted bot");
        assertTrue(f.has("thresholds") && f.has("travel") && f.has("session"));
        assertFalse(f.has("program") || f.has("lastSession"));
        r = ok(sync("farmer", farmerPart("[\"custom\"]", 2)));
        assertTrue(r.get("farms").isJsonNull());
        // A farm assigned to another bot is not in the list.
        makeFarmer("farmer2");
        assertEquals(0, ok(sync("farmer2", farmerPart("[\"custom\"]", 0))).getAsJsonArray("farms").size());
        assertTrue(r.get("attach").isJsonNull());
    }

    @Test
    void attachBindsTheDraftThroughTheNextSync() throws Exception {
        makeFarmer("farmer");
        String id = createFarm("Bone farm", "custom", "farmer");
        assertEquals("no_draft", post(owner, "/api/v1/farms/attach", "{\"id\":\"" + id + "\"}").code());
        String draft = "{\"hash\":\"" + HASH_B + "\",\"mode\":\"custom\",\"ticks\":2400,\"loop\":2120,\"anchors\":4}";
        ok(syncPrograms("farmer", "[]", draft));
        assertEquals(HASH_B, ok(get(owner, "/api/v1/farms")).getAsJsonObject("draft").get("hash").getAsString());
        ok(post(owner, "/api/v1/farms/attach", "{\"id\":\"" + id + "\"}"));
        JsonObject r = ok(syncPrograms("farmer", "[]", draft));
        assertEquals(id, r.getAsJsonObject("attach").get("farm").getAsString());
        assertEquals(HASH_B, r.getAsJsonObject("attach").get("draftHash").getAsString());
        // The bot re-sealed the draft under the farm id and reports it: the order is cleared.
        r = ok(syncPrograms("farmer", "[" + program(id, HASH_B, "custom") + "]", "null"));
        assertTrue(r.get("attach").isJsonNull());
        assertEquals(HASH_B, farmOf(id).getAsJsonObject("program").get("hash").getAsString());
        // Not while a session is open.
        ok(startFarm("{\"id\":\"" + id + "\"}"));
        ok(syncPrograms("farmer", "[" + program(id, HASH_B, "custom") + "]", draft));
        assertEquals("busy", post(owner, "/api/v1/farms/attach", "{\"id\":\"" + id + "\"}").code());
    }

    @Test
    void farmsAreLimitedAndStoredWithABackup() throws Exception {
        makeFarmer("farmer");
        for (int i = 0; i < FarmStore.MAX_FARMS; i++) {
            advance(1100); // 60 writes a minute
            ok(post(owner, "/api/v1/farms", "{\"name\":\"Farm " + (char) ('a' + i % 26) + (char) ('a' + i / 26) + "\",\"server\":\"s\",\"mode\":\"custom\"}"));
        }
        Res over = post(owner, "/api/v1/farms", "{\"name\":\"One more\",\"server\":\"s\",\"mode\":\"custom\"}");
        assertEquals(409, over.status);
        assertEquals("farm_limit", over.code());
        Path file = data.resolve("automation").resolve("farms.json");
        assertTrue(Files.exists(file));
        assertTrue(Files.exists(data.resolve("automation").resolve("farms.json.bak")), "the last clean file is kept");
        // The file and its backup are corrupt: the routes answer 503, nothing is offered, nothing is overwritten.
        restart();
        assertEquals(64, ok(get(owner, "/api/v1/farms")).getAsJsonArray("farms").size());
        viewer.close();
        server.stop(0);
        Files.writeString(file, "{ not json");
        Files.writeString(data.resolve("automation").resolve("farms.json.bak"), "also not json");
        startAt(data);
        Res down = get(owner, "/api/v1/farms");
        assertEquals(503, down.status);
        assertEquals("farms_unavailable", down.code());
        assertEquals(503, post(owner, "/api/v1/farms", "{\"name\":\"X\",\"server\":\"s\",\"mode\":\"custom\"}").status);
        assertEquals("{ not json", Files.readString(file));
    }

    @Test
    void nothingInTheFarmRecordOrTheAuditHoldsAPositionOrAName() throws Exception {
        makeFarmer("farmer");
        String id = createFarm("Secret name", "custom", "farmer");
        ok(post(owner, "/api/v1/farms", "{\"id\":\"" + id + "\",\"rev\":1,\"mode\":\"afk_still\"}"));
        ok(post(owner, "/api/v1/farms/delete", "{\"id\":\"" + id + "\"}"));
        String audit = Files.readString(data.resolve("audit.json"));
        assertTrue(audit.contains("farm.save") && audit.contains(id + " new mode=custom") && audit.contains(id + " edit mode=afk_still"));
        assertFalse(audit.contains("Secret name"));
        String tasksJson = Files.exists(data.resolve("tasks.json")) ? Files.readString(data.resolve("tasks.json")) : "";
        assertFalse(tasksJson.contains("Secret name"));
    }
}

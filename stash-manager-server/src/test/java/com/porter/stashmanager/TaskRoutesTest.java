package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The task slice through the real routes (TASK-CONTRACT v1.0 slice): sync, claim and report with leases and epoch and seq
 * fencing, the profile and offer rules (mitigations b, c, d), the closed farm vocabularies and their no-echo guarantee,
 * the coordinate guard (G1, G2), and STOP with its delivery rule (mitigation a).
 */
class TaskRoutesTest extends FarmHarness {
    // ---------- profiles, roles, offers (mitigations b, c, d) ----------

    @Test
    void anUnknownBotIsAnsweredButNeverStored() throws Exception {
        JsonObject r = ok(sync("ghost", farmerPart("[\"custom\"]", 0)));
        assertEquals(0, r.getAsJsonArray("roles").size());
        assertTrue(r.get("offer").isJsonNull());
        assertTrue(r.get("lease").isJsonNull());
        assertFalse(tasks.ops.bots.containsKey("ghost"), "a stranger gets no profile");
        // And its farmer.enabled did not make it a farmer.
        assertFalse(tasks.isFarmer("ghost"));
    }

    @Test
    void newProfilesAreRateLimitedPerAddress() throws Exception {
        for (int i = 1; i <= TaskService.UNKNOWN_PER_HOUR; i++) ok(sync("ghost" + i, null));
        Res over = sync("ghost5", null);
        assertEquals(429, over.status, over.body);
        assertEquals("slow_down", over.code());
        // A repeat of an id already counted is still answered.
        ok(sync("ghost1", null));
        // An hour later the window has passed.
        advance(TaskService.HOUR_MS + 1000);
        ok(sync("ghost5", null));
    }

    @Test
    void aBotThatSaidHelloGetsAProfileAtItsFirstSync() throws Exception {
        hello("plug");
        assertFalse(tasks.ops.bots.containsKey("plug"));
        ok(sync("plug", null));
        OpsStore.Profile p = tasks.ops.bots.get("plug");
        assertNotNull(p);
        assertEquals(List.of(), p.roles);
        assertFalse(p.farmerSeen);
        assertEquals("2b2t.org", p.server);
        assertNull(p.lastStatus.get("x"));
        assertTrue(Files.exists(data.resolve("bots.json")), "written before the call returned");
    }

    @Test
    void aBotCannotGiveItselfARoleOrAnOfferByClaimingOne() throws Exception {
        hello("plug");
        JsonObject r = ok(sync("plug", "\"roles\":[\"farmer\"],\"farmer\":{\"enabled\":true,\"modes\":[\"custom\",\"afk_still\",\"raid\"],\"farmsRev\":0},"
            + "\"programs\":[],\"draft\":null"));
        assertEquals(0, r.getAsJsonArray("roles").size(), "a roles field in a sync is ignored");
        assertTrue(r.get("offer").isJsonNull());
        // The claim is not enough for the owner's actions either: the farmer list is the owner's roles only.
        assertTrue(tasks.isFarmer("plug"), "but it counts as a farmer for owner-only reads (one-way tightening)");
        assertFalse(tasks.ops.bots.get("plug").hasRole("farmer"));
        Res create = post(owner, "/api/v1/farms", "{\"name\":\"Mine\",\"server\":\"2b2t.org\",\"mode\":\"custom\",\"botId\":\"plug\"}");
        assertEquals(400, create.status, "a bot that claims to be a farmer can't be assigned a farm");
        assertEquals("bad_field", create.code());
    }

    @Test
    void modesAndProgramsReportedByABotNeverAuthoriseAnOwnerAction() throws Exception {
        makeFarmer("farmer");
        // The bot says it can run everything, and the owner makes a farm for a mode M2 does not run.
        ok(sync("farmer", "\"farmer\":{\"enabled\":true,\"modes\":[\"" + String.join("\",\"", FarmVocab.MODES) + "\"],\"farmsRev\":0},"
            + "\"programs\":[],\"draft\":null"));
        String id = createFarm("Swing farm", "afk_swing", "farmer");
        ok(syncPrograms("farmer", "[" + program(id, HASH_A, "afk_swing") + "]", "null"));
        Res start = post(owner, "/api/v1/farms/start", "{\"id\":\"" + id + "\"}");
        assertEquals(409, start.status);
        assertEquals("mode_not_ready", start.code());
    }

    @Test
    void anOfferGoesOnlyToABotTheOwnerMadeAFarmer() throws Exception {
        makeFarmer("farmer");
        String farm = farmWithProgram("farmer", "custom");
        JsonObject s = ok(post(owner, "/api/v1/farms/start", "{\"id\":\"" + farm + "\"}"));
        String task = s.get("taskId").getAsString();
        JsonObject offered = ok(sync("farmer", null)).getAsJsonObject("offer");
        assertEquals(task, offered.get("id").getAsString());
        assertEquals("farm.session", offered.get("kind").getAsString());
        assertEquals("Farm session", offered.get("title").getAsString());
        assertEquals(farm, offered.getAsJsonObject("params").get("farm").getAsString());
        assertEquals(HASH_A, offered.getAsJsonObject("params").get("programHash").getAsString());
        assertEquals("website", offered.getAsJsonObject("params").get("origin").getAsString());
        assertEquals("farmer", offered.getAsJsonObject("target").get("botId").getAsString());
        assertFalse(offered.toString().contains("Bone farm"), "a farm name never reaches a task");
        // Another bot, even one that says it is a farmer, is offered nothing.
        hello("other");
        ok(sync("other", farmerPart("[\"custom\"]", 0)));
        assertTrue(ok(sync("other", null)).get("offer").isJsonNull());
        // The owner takes the role away: no more offers, and the old task can't be claimed.
        ok(post(owner, "/api/v1/bots/roles", "{\"botId\":\"farmer\",\"roles\":[]}"));
        assertTrue(ok(sync("farmer", null)).get("offer").isJsonNull());
        Res c = bot("farmer", "/api/v1/tasks/" + task + "/claim", "{\"botId\":\"farmer\"}");
        assertEquals(409, c.status);
        assertEquals("not_claimable", c.code(), "the session ended (role_removed) when the role went");
    }

    @Test
    void theHeaderBotIdMustBePresentAndEqualTheBody() throws Exception {
        hello("plug");
        Res dash = bot("-", "/api/v1/tasks/sync", syncBody("plug", null));
        assertEquals(400, dash.status);
        assertEquals("botid_mismatch", dash.code());
        Res other = bot("someoneelse", "/api/v1/tasks/sync", syncBody("plug", null));
        assertEquals("botid_mismatch", other.code());
    }

    @Test
    void syncFieldsAreCheckedForTypeAndRange() throws Exception {
        hello("plug");
        assertEquals("type", fieldOf(bot("plug", "/api/v1/tasks/sync", syncBody("plug", null).replace("\"plugin\"", "\"robot\""))));
        assertEquals("status.health", fieldOf(bot("plug", "/api/v1/tasks/sync", syncBody("plug", null).replace("\"health\":20", "\"health\":21"))));
        assertEquals("status.dim", fieldOf(bot("plug", "/api/v1/tasks/sync", syncBody("plug", null).replace("minecraft:overworld", "Not A Dim"))));
        assertEquals("status.totems", fieldOf(bot("plug", "/api/v1/tasks/sync", syncBody("plug", null).replace("\"totems\":1", "\"totems\":1.5"))));
        assertEquals("stopAck", fieldOf(bot("plug", "/api/v1/tasks/sync", syncBody("plug", "\"stopAck\":-1"))));
        assertEquals("contract", fieldOf(bot("plug", "/api/v1/tasks/sync", syncBody("plug", null).replace("\"1.0\"", "\"1.0.0.0.0\""))));
        assertEquals("player", fieldOf(bot("plug", "/api/v1/tasks/sync", syncBody("plug", null).replace("Farmer1", "A very long player name"))));
        assertEquals("farmer.modes", fieldOf(bot("plug", "/api/v1/tasks/sync", syncBody("plug", "\"farmer\":{\"enabled\":true,\"modes\":[\"nope\"]}"))));
        assertEquals("programs", fieldOf(bot("plug", "/api/v1/tasks/sync", syncBody("plug", "\"farmer\":{\"enabled\":true},\"programs\":[{\"farm\":\"f-1\"}]"))));
        // Nothing was stored by any refusal.
        assertFalse(tasks.ops.bots.containsKey("plug"));
    }

    private static String fieldOf(Res r) {
        assertEquals(400, r.status, r.body);
        assertEquals("bad_field", r.code(), r.body);
        return r.obj().get("field").getAsString();
    }

    @Test
    void aSyncOverOnePerSecondIsSlowedDown() throws Exception {
        hello("plug");
        ok(bot("plug", "/api/v1/tasks/sync", syncBody("plug", null)));
        advance(300);
        Res again = bot("plug", "/api/v1/tasks/sync", syncBody("plug", null));
        assertEquals(429, again.status);
        assertEquals("slow_down", again.code());
        advance(1000);
        ok(bot("plug", "/api/v1/tasks/sync", syncBody("plug", null)));
    }

    // ---------- claim, report, leases, fencing ----------

    @Test
    void aSessionRunsFromOfferToTheEnd() throws Exception {
        String[] s = runningSession();
        String farm = s[0], task = s[1];
        // After the claim: pending with a lease, epoch 1.
        JsonObject t = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("pending", t.get("state").getAsString());
        assertEquals(1, t.getAsJsonObject("lease").get("epoch").getAsInt());
        // No second offer while the lease is held.
        assertTrue(ok(sync("farmer", null)).get("offer").isJsonNull());
        ok(report("farmer", task, 1, 1, "running", "\"phase\":\"setup\",\"progress\":{\"done\":0,\"total\":0}"));
        ok(report("farmer", task, 1, 2, "running", "\"phase\":\"loop\",\"progress\":{\"done\":2,\"total\":0},"
            + "\"checkpoint\":{\"step\":\"loop\",\"data\":{\"loop\":2,\"anchorId\":\"a1\",\"tick\":120,\"recoveries\":0}}"));
        JsonObject running = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("running", running.get("state").getAsString());
        assertEquals("loop", running.get("phase").getAsString());
        assertEquals("a1", running.getAsJsonObject("checkpoint").getAsJsonObject("data").get("anchorId").getAsString());
        assertEquals("running", farmOf(farm).getAsJsonObject("status").get("state").getAsString());
        assertEquals(2, farmOf(farm).getAsJsonObject("status").get("loops").getAsInt());
        // A sync with a matching running object renews the lease and echoes it.
        JsonObject sy = ok(sync("farmer", "\"running\":{\"taskId\":\"" + task + "\",\"epoch\":1,\"state\":\"running\",\"phase\":\"loop\","
            + "\"progress\":{\"done\":3,\"total\":0},\"seq\":2}"));
        assertEquals(task, sy.getAsJsonObject("lease").get("taskId").getAsString());
        assertEquals(clk.get() + TaskService.LEASE_MS, sy.getAsJsonObject("lease").get("expiresAt").getAsLong());
        assertEquals(0, sy.getAsJsonArray("cancel").size());
        assertEquals(3, tasksOf("farmer").get(0).getAsJsonObject().getAsJsonObject("progress").get("done").getAsInt());
        // The end.
        ok(report("farmer", task, 1, 3, "completed", RESULT_TIME_UP));
        JsonObject done = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("completed", done.get("state").getAsString());
        assertTrue(done.get("lease").isJsonNull());
        assertFalse(done.get("finishedAt").isJsonNull());
        JsonObject last = farmOf(farm).getAsJsonObject("lastSession");
        assertEquals(task, last.get("taskId").getAsString());
        assertEquals("time_up", last.get("endedBy").getAsString());
        assertEquals(3, last.get("loops").getAsInt());
        assertEquals("idle", farmOf(farm).getAsJsonObject("status").get("state").getAsString());
        // The bot's own end is audited under its own name; ids and codes only.
        String audit = Files.readString(data.resolve("audit.json"));
        assertTrue(audit.contains("farm.session.end"));
        assertTrue(audit.contains("bot farmer"));
        assertTrue(audit.contains(farm + " endedBy=time_up"));
        assertFalse(audit.contains("Bone farm"));
    }

    @Test
    void claimRules() throws Exception {
        makeFarmer("farmer");
        String farm = farmWithProgram("farmer", "custom");
        String task = ok(post(owner, "/api/v1/farms/start", "{\"id\":\"" + farm + "\"}")).get("taskId").getAsString();
        Res unknown = bot("farmer", "/api/v1/tasks/t-000000000000/claim", "{\"botId\":\"farmer\"}");
        assertEquals(404, unknown.status);
        assertEquals("no_task", unknown.code());
        // Another farmer-role bot is not the target.
        makeFarmer("farmer2");
        Res wrong = bot("farmer2", "/api/v1/tasks/" + task + "/claim", "{\"botId\":\"farmer2\"}");
        assertEquals(409, wrong.status);
        assertEquals("not_target", wrong.code());
        // The header must match.
        assertEquals("botid_mismatch", bot("farmer2", "/api/v1/tasks/" + task + "/claim", "{\"botId\":\"farmer\"}").code());
        // A STOP finishes the task nobody claimed (cancelled, ops_stop): there is nothing left to claim.
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"farmer\"}"));
        Res stopped = bot("farmer", "/api/v1/tasks/" + task + "/claim", "{\"botId\":\"farmer\"}");
        assertEquals(409, stopped.status);
        assertEquals("not_claimable", stopped.code());
        ok(post(owner, "/api/v1/ops/resume", "{\"scope\":\"farmer\"}"));
        task = ok(post(owner, "/api/v1/farms/start", "{\"id\":\"" + farm + "\"}")).get("taskId").getAsString();
        claim("farmer", task);
        // A finished task is not claimable.
        ok(report("farmer", task, 1, 1, "failed", "\"error\":{\"code\":\"low_health\"}"));
        Res done = bot("farmer", "/api/v1/tasks/" + task + "/claim", "{\"botId\":\"farmer\"}");
        assertEquals(409, done.status);
        assertEquals("not_claimable", done.code());
    }

    @Test
    void aReclaimBumpsTheEpochAndFencesTheOldRun() throws Exception {
        String[] s = runningSession();
        String task = s[1];
        ok(report("farmer", task, 1, 1, "running", "\"phase\":\"loop\""));
        // A live lease is never taken over, not even by its own bot, and nothing is reset by the try.
        Res live = bot("farmer", "/api/v1/tasks/" + task + "/claim", "{\"botId\":\"farmer\"}");
        assertEquals(409, live.status);
        assertEquals("taken", live.code());
        assertEquals("running", tasksOf("farmer").get(0).getAsJsonObject().get("state").getAsString());
        assertEquals(1, tasksOf("farmer").get(0).getAsJsonObject().getAsJsonObject("lease").get("epoch").getAsInt());
        // The bot restarted: it waits for the lease to lapse and for the backoff, then claims again.
        advance(TaskService.LEASE_MS + 1000);
        ok(sync("farmer", null));
        Res early = bot("farmer", "/api/v1/tasks/" + task + "/claim", "{\"botId\":\"farmer\"}");
        assertEquals(409, early.status, "paused by a lost lease: not before nextAttemptAt");
        assertEquals("not_claimable", early.code());
        advance(60_000L * 2);
        JsonObject again = claim("farmer", task);
        assertEquals(2, again.getAsJsonObject("lease").get("epoch").getAsInt());
        assertEquals(2, again.getAsJsonObject("task").get("attempts").getAsInt());
        Res stale = report("farmer", task, 1, 2, "running", "\"phase\":\"loop\"");
        assertEquals(409, stale.status);
        assertEquals("lease_lost", stale.code());
        // A sync of the old run is told to stop it, and gets no lease.
        JsonObject sy = ok(sync("farmer", "\"running\":{\"taskId\":\"" + task + "\",\"epoch\":1,\"state\":\"running\",\"phase\":\"loop\",\"seq\":2}"));
        assertEquals(task, sy.getAsJsonArray("cancel").get(0).getAsString());
        // seq restarts with the epoch.
        ok(report("farmer", task, 2, 1, "running", "\"phase\":\"loop\""));
    }

    @Test
    void aRepeatedOrOlderSeqChangesNothing() throws Exception {
        String[] s = runningSession();
        String task = s[1];
        ok(report("farmer", task, 1, 5, "running", "\"phase\":\"loop\",\"progress\":{\"done\":4,\"total\":0}"));
        JsonObject dup = ok(report("farmer", task, 1, 5, "running", "\"phase\":\"recover\",\"progress\":{\"done\":9,\"total\":0}"));
        assertTrue(dup.get("dup").getAsBoolean());
        JsonObject old = ok(report("farmer", task, 1, 3, "paused", "\"error\":{\"code\":\"disconnected\"}"));
        assertTrue(old.get("dup").getAsBoolean());
        JsonObject t = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("running", t.get("state").getAsString());
        assertEquals("loop", t.get("phase").getAsString());
        assertEquals(4, t.getAsJsonObject("progress").get("done").getAsInt());
        assertEquals("bad_field", report("farmer", task, 1, 0, "running", null).code());
    }

    @Test
    void stateChangesFollowTheTable() throws Exception {
        String[] s = runningSession();
        String task = s[1];
        // pending (claimed) may not go straight to paused or completed.
        assertEquals("bad_transition", report("farmer", task, 1, 1, "paused", "\"error\":{\"code\":\"disconnected\"}").code());
        assertEquals("bad_transition", report("farmer", task, 1, 1, "completed", RESULT_TIME_UP).code());
        ok(report("farmer", task, 1, 1, "running", "\"phase\":\"setup\""));
        ok(report("farmer", task, 1, 2, "paused", "\"phase\":\"parked\",\"error\":{\"code\":\"controller_connected\"}"));
        JsonObject paused = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("paused", paused.get("state").getAsString());
        assertEquals("controller_connected", paused.get("pauseReason").getAsString());
        ok(report("farmer", task, 1, 3, "running", "\"phase\":\"loop\""));
        assertTrue(tasksOf("farmer").get(0).getAsJsonObject().get("pauseReason").isJsonNull());
        // A park code is only for paused, an end code only for its state.
        assertEquals("error", report("farmer", task, 1, 4, "paused", "\"error\":{\"code\":\"low_health\"}").field());
        assertEquals("error", report("farmer", task, 1, 4, "failed", "\"error\":{\"code\":\"time_up\"}").field());
        assertEquals("error", report("farmer", task, 1, 4, "failed", null).field());
        assertEquals("error", report("farmer", task, 1, 4, "running", "\"error\":{\"code\":\"error\"}").field());
        ok(report("farmer", task, 1, 4, "failed", "\"error\":{\"code\":\"no_food\",\"text\":\"free text from the bot\"}"));
        JsonObject done = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("failed", done.get("state").getAsString());
        assertEquals("no_food", done.getAsJsonObject("error").get("code").getAsString());
        assertEquals("Out of food", done.getAsJsonObject("error").get("text").getAsString(), "the server's own text, never the bot's");
        // A terminal state never changes again; repeating it is fine.
        assertEquals("bad_transition", report("farmer", task, 1, 5, "running", "\"phase\":\"loop\"").code());
        assertTrue(ok(report("farmer", task, 1, 6, "failed", "\"error\":{\"code\":\"no_food\"}")).get("dup").getAsBoolean());
    }

    @Test
    void aLostLeaseParksTheTaskAndOffersItAgainAfterABackoff() throws Exception {
        String[] s = runningSession();
        String task = s[1];
        ok(report("farmer", task, 1, 1, "running", "\"phase\":\"loop\""));
        advance(TaskService.LEASE_MS + 1000);
        JsonObject sy = ok(sync("farmer", null));
        JsonObject t = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("paused", t.get("state").getAsString());
        assertEquals("lease_lost", t.get("pauseReason").getAsString());
        assertTrue(t.get("lease").isJsonNull());
        assertTrue(sy.get("offer").isJsonNull(), "not before the backoff (60 s x 2^attempts)");
        advance(60_000L * 2);
        JsonObject again = ok(sync("farmer", null)).getAsJsonObject("offer");
        assertEquals(task, again.get("id").getAsString());
        JsonObject re = claim("farmer", task);
        assertEquals(2, re.getAsJsonObject("lease").get("epoch").getAsInt());
        assertEquals("pending", re.getAsJsonObject("task").get("state").getAsString());
    }

    @Test
    void aBotThatComesBackWithTheSameEpochTakesItsLeaseBack() throws Exception {
        String[] s = runningSession();
        String task = s[1];
        ok(report("farmer", task, 1, 1, "running", "\"phase\":\"loop\""));
        advance(TaskService.LEASE_MS + 1000); // the server was away or the bot was cut off
        ok(sync("farmer", "\"running\":{\"taskId\":\"" + task + "\",\"epoch\":1,\"state\":\"running\",\"phase\":\"loop\",\"seq\":1}"));
        JsonObject t = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("running", t.get("state").getAsString());
        assertFalse(t.get("lease").isJsonNull());
    }

    @Test
    void aTaskIsFailedWhenItCanNotBeOfferedAnyMore() throws Exception {
        String[] s = runningSession();
        String task = s[1];
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (attempt > 1) claim("farmer", task);
            ok(report("farmer", task, attempt, 1, "running", "\"phase\":\"loop\""));
            advance(TaskService.LEASE_MS + 1000);
            ok(sync("farmer", null));
            advance(60_000L * 16);
        }
        JsonObject t = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("failed", t.get("state").getAsString());
        assertEquals("lease_lost", t.getAsJsonObject("error").get("code").getAsString());
        assertEquals("failed", farmOf(s[0]).getAsJsonObject("lastSession").get("state").getAsString());
    }

    @Test
    void aTaskParkedForAnHourIsCancelled() throws Exception {
        String[] s = runningSession();
        String task = s[1];
        ok(report("farmer", task, 1, 1, "running", "\"phase\":\"loop\""));
        ok(report("farmer", task, 1, 2, "paused", "\"phase\":\"parked\",\"error\":{\"code\":\"disconnected\"}"));
        String running = "\"running\":{\"taskId\":\"" + task + "\",\"epoch\":1,\"state\":\"paused\",\"phase\":\"parked\",\"seq\":2}";
        for (int i = 0; i < 44; i++) {
            advance(80_000); // each sync renews the lease
            ok(sync("farmer", running));
        }
        assertEquals("paused", tasksOf("farmer").get(0).getAsJsonObject().get("state").getAsString());
        advance(80_000);
        ok(sync("farmer", running));
        JsonObject t = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("cancelled", t.get("state").getAsString());
        assertEquals("pause_timeout", t.getAsJsonObject("error").get("code").getAsString());
    }

    // ---------- closed vocabularies: bad_vocab and no echo ----------

    @Test
    void aFarmReportTakesOnlyClosedValuesAndNeverEchoesTheRest() throws Exception {
        String[] s = runningSession();
        String task = s[1];
        String marker = "ZQXMARKERZQX";
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        PrintStream so = System.out, se = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        List<Res> answers = new ArrayList<>();
        try {
            int seq = 1;
            String[] extras = {
                "\"phase\":\"" + marker + "\"",
                "\"checkpoint\":{\"step\":\"" + marker + "\",\"data\":{}}",
                "\"checkpoint\":{\"step\":\"loop\",\"data\":{\"" + marker + "\":1}}",
                "\"checkpoint\":{\"step\":\"loop\",\"data\":{\"loop\":\"" + marker + "\"}}",
                "\"checkpoint\":{\"step\":\"loop\",\"data\":{\"anchorId\":\"" + marker + "\"}}",
                "\"checkpoint\":{\"step\":\"loop\",\"" + marker + "\":{}}",
                "\"result\":{\"loops\":1,\"recoveries\":0,\"runtimeMs\":1,\"endedBy\":\"time_up\",\"gained\":[],\"" + marker + "\":1}",
                "\"result\":{\"loops\":1,\"recoveries\":0,\"runtimeMs\":1,\"endedBy\":\"" + marker + "\",\"gained\":[]}",
                "\"result\":{\"loops\":\"" + marker + "\",\"recoveries\":0,\"runtimeMs\":1,\"endedBy\":\"time_up\",\"gained\":[]}",
                "\"result\":{\"loops\":1,\"recoveries\":0,\"runtimeMs\":1,\"endedBy\":\"time_up\",\"gained\":[\"" + marker + "\"]}",
                "\"error\":{\"code\":\"" + marker + "\"}",
            };
            for (String x : extras) {
                String state = x.startsWith("\"result\"") ? "completed" : x.startsWith("\"error\"") ? "failed" : "running";
                answers.add(report("farmer", task, 1, seq++, state, x));
            }
            answers.add(bot("farmer", "/api/v1/tasks/sync", syncBody("farmer", "\"running\":{\"taskId\":\"" + task
                + "\",\"epoch\":1,\"state\":\"running\",\"phase\":\"" + marker + "\"}")));
            advance(1100);
            answers.add(bot("farmer", "/api/v1/tasks/sync", syncBody("farmer", null).replace("minecraft:overworld", marker)));
        } finally {
            System.setOut(so);
            System.setErr(se);
        }
        for (Res r : answers) {
            assertEquals(400, r.status, r.body);
            // (the anchor marker is refused earlier still, by the coordinate guard: the key name is G1's)
            assertTrue(r.code().equals("bad_vocab") || r.code().equals("bad_field") || r.code().equals("position_field"), r.body);
            assertFalse(r.body.contains(marker), "the 400 repeats the value: " + r.body);
        }
        assertEquals("bad_vocab", answers.get(0).code());
        assertEquals("phase", answers.get(0).obj().get("field").getAsString());
        assertEquals("bad_vocab", answers.get(answers.size() - 2).code());
        assertEquals("running.phase", answers.get(answers.size() - 2).obj().get("field").getAsString());
        assertFalse(out.toString(StandardCharsets.UTF_8).contains(marker), "stdout");
        assertFalse(err.toString(StandardCharsets.UTF_8).contains(marker), "stderr");
        assertFalse(Files.readString(data.resolve("audit.json")).contains(marker));
        assertFalse(Files.readString(data.resolve("tasks.json")).contains(marker));
        // Nothing from any of them was stored.
        assertEquals("pending", tasksOf("farmer").get(0).getAsJsonObject().get("state").getAsString());
    }

    @Test
    void theClosedVocabulariesAcceptTheirOwnValues() throws Exception {
        String[] s = runningSession();
        String task = s[1];
        int seq = 1;
        for (String phase : new String[]{"setup", "loop", "recover", "stop_steps", "parked", "waiting"}) {
            ok(report("farmer", task, 1, seq++, "running", "\"phase\":\"" + phase + "\""));
        }
        ok(report("farmer", task, 1, seq++, "running", "\"checkpoint\":{\"step\":\"recover\",\"data\":{\"loop\":1000000,\"anchorId\":\"a63\",\"tick\":72000,\"recoveries\":1000}}"));
        assertEquals("bad_vocab", report("farmer", task, 1, seq++, "running", "\"checkpoint\":{\"step\":\"loop\",\"data\":{\"anchorId\":\"a64\"}}").code());
        assertEquals("bad_vocab", report("farmer", task, 1, seq++, "running", "\"checkpoint\":{\"step\":\"loop\",\"data\":{\"loop\":1000001}}").code());
        assertEquals("bad_vocab", report("farmer", task, 1, seq++, "running", "\"checkpoint\":{\"step\":\"waiting\",\"data\":{}}").code(), "waiting is a phase, not a step");
        assertEquals("bad_vocab", report("farmer", task, 1, seq++, "completed", "\"result\":{\"loops\":1,\"recoveries\":0,\"runtimeMs\":1,\"endedBy\":\"time_up\",\"gained\":[]}".replace("\"gained\":[]", "\"gained\":[{\"key\":\"x\"}]")).code());
        assertEquals("bad_field", report("farmer", task, 1, seq++, "running", "\"progress\":{\"done\":1000001,\"total\":0}").code());
        ok(report("farmer", task, 1, seq, "cancelled", "\"error\":{\"code\":\"ops_stop\"}"));
        assertEquals("cancelled", tasksOf("farmer").get(0).getAsJsonObject().get("state").getAsString());
    }

    // ---------- the coordinate guard (G1, G2) ----------

    /** The shared vectors of farm-vectors.json serverGuard (copied: the test must not depend on files outside the repo). */
    @Test
    void theFieldNameGuardMatchesTheSharedVectors() throws Exception {
        hello("plug");
        for (String body : new String[]{
            "{\"a\":{\"b\":[{\"z\":1}]}}",
            "{\"checkpoint\":{\"data\":{\"Origin\":\"a0\"}}}",
            "{\"X\":1}", "{\"Pos\":[1,2,3]}", "{\"coords\":null}", "{\"anchor\":\"a0\"}", "{\"position\":{}}", "{\"y\":1}", "{\"pos\":1}"}) {
            JsonObject o = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            PositionGuard.Hit hit = PositionGuard.check(o);
            assertNotNull(hit, body);
            assertEquals("position_field", hit.code(), body);
        }
        for (String body : new String[]{"{\"anchors\":3,\"anchorId\":\"a0\",\"xp\":1}", "{\"phase\":\"loop\",\"progress\":{\"done\":3,\"total\":0}}"}) {
            assertNull(PositionGuard.check(com.google.gson.JsonParser.parseString(body)), body);
        }
        // Through the route: in a sync and a report, at depth, the answer is 400 position_field and never holds the value.
        Res r = bot("plug", "/api/v1/tasks/sync", "{\"botId\":\"plug\",\"type\":\"plugin\",\"contract\":\"1.0\",\"status\":{\"inGame\":true,\"Z\":98765}}");
        assertEquals(400, r.status);
        assertEquals("position_field", r.code());
        assertFalse(r.body.contains("98765"));
        Res nested = bot("plug", "/api/v1/tasks/t-000000000000/report", "{\"botId\":\"plug\",\"epoch\":1,\"seq\":1,\"state\":\"running\","
            + "\"checkpoint\":{\"step\":\"loop\",\"data\":{\"list\":[{\"coords\":[1,2,3]}]}}}");
        assertEquals("position_field", nested.code(), "the guard runs before the task is looked up");
    }

    @Test
    void theStringGuardMatchesTheSharedVectors() throws Exception {
        String[][] strings = {
            {"Bone meal farm", "false"}, {"Farm 2", "false"}, {"x=1 y=2 z=3", "true"}, {"1 2 3", "true"}, {"-10,64,20", "true"},
            {"v1.2.3", "false"}, {"12 20", "false"},
            // Decimal coordinates are NOT matched by the pattern (the log filter denies that line by source, FARM P4).
            {"[Client] Server teleport 7 to: 1.5,64.0,-3.2", "false"}};
        for (String[] v : strings) assertEquals(Boolean.parseBoolean(v[1]), PositionGuard.looksLikePosition(v[0]), v[0]);
        hello("plug");
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        PrintStream so = System.out, se = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        Res r;
        try {
            r = bot("plug", "/api/v1/tasks/sync", syncBody("plug", null).replace("2b2t.org", "12 64 -30"));
        } finally {
            System.setOut(so);
            System.setErr(se);
        }
        assertEquals(400, r.status);
        assertEquals("position_like", r.code());
        assertFalse(r.body.contains("64"));
        assertFalse(out.toString(StandardCharsets.UTF_8).contains("64 -30"));
        assertFalse(err.toString(StandardCharsets.UTF_8).contains("64 -30"));
        assertFalse(tasks.ops.bots.containsKey("plug"), "a body with a coordinate stores nothing");
        // A key that is a position is refused too.
        assertEquals("position_like", bot("plug", "/api/v1/tasks/sync", syncBody("plug", "\"extra\":{\"1 2 3\":true}")).code());
    }

    // ---------- STOP (mitigation a) ----------

    @Test
    void stopCancelsRunningTasksAndTheDeliveryNeedsTheEffect() throws Exception {
        String[] s = runningSession();
        String task = s[1];
        ok(report("farmer", task, 1, 1, "running", "\"phase\":\"loop\""));
        String running = "\"running\":{\"taskId\":\"" + task + "\",\"epoch\":1,\"state\":\"running\",\"phase\":\"loop\",\"seq\":1}";
        JsonObject st = ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\",\"reason\":\"checking the farm\"}"));
        assertTrue(st.get("changed").getAsBoolean());
        int rev = st.get("rev").getAsInt();
        assertEquals(1, rev);
        // The next sync carries the STOP, the reason (the owner's text), no offer, and the task in cancel.
        JsonObject sy = ok(sync("farmer", running));
        assertTrue(sy.getAsJsonObject("stop").get("global").getAsBoolean());
        assertEquals(rev, sy.getAsJsonObject("stop").get("rev").getAsInt());
        assertEquals("checking the farm", sy.getAsJsonObject("stop").get("reason").getAsString());
        assertEquals(task, sy.getAsJsonArray("cancel").get(0).getAsString());
        assertTrue(sy.get("offer").isJsonNull());
        // A forged ack with the task still running is not "delivered".
        ok(sync("farmer", running + ",\"stopAck\":" + rev));
        assertEquals("sent", deliveredOf(ok(get(owner, "/api/v1/ops")), "farmer"));
        advance(TaskService.STOP_CONFIRM_MS + 1000);
        ok(sync("farmer", running + ",\"stopAck\":" + rev));
        assertEquals("unconfirmed", deliveredOf(ok(get(owner, "/api/v1/ops")), "farmer"));
        // running: null but the task is still open on the server: still not delivered.
        ok(sync("farmer", "\"stopAck\":" + rev));
        assertEquals("unconfirmed", deliveredOf(ok(get(owner, "/api/v1/ops")), "farmer"));
        // The bot parks and reports the cancellation: delivered.
        ok(report("farmer", task, 1, 2, "cancelled", "\"error\":{\"code\":\"ops_stop\"}"));
        ok(sync("farmer", "\"stopAck\":" + rev));
        assertEquals("delivered", deliveredOf(ok(get(owner, "/api/v1/ops")), "farmer"));
        // Claims and starts are refused while it applies; reports are still accepted (the cancel above).
        Res start = post(owner, "/api/v1/farms/start", "{\"id\":\"" + s[0] + "\"}");
        assertEquals("stopped", start.code());
        // Resume turns it off, bumps the counter, and does not restart anything.
        JsonObject res = ok(post(owner, "/api/v1/ops/resume", "{\"scope\":\"all\"}"));
        assertTrue(res.get("changed").getAsBoolean());
        assertEquals(2, res.get("rev").getAsInt());
        assertFalse(res.getAsJsonObject("stopAll").get("on").getAsBoolean());
        assertEquals("cancelled", tasksOf("farmer").get(0).getAsJsonObject().get("state").getAsString());
        // A repeat is a no-op and does not move the counter.
        JsonObject again = ok(post(owner, "/api/v1/ops/resume", "{\"scope\":\"all\"}"));
        assertFalse(again.get("changed").getAsBoolean());
        assertEquals(2, again.get("rev").getAsInt());
        JsonObject stopAgain = ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\"}"));
        assertTrue(stopAgain.get("changed").getAsBoolean());
        assertFalse(ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\"}")).get("changed").getAsBoolean());
        String audit = Files.readString(data.resolve("audit.json"));
        assertTrue(audit.contains("ops.stop") && audit.contains("all: checking the farm") && audit.contains("ops.resume"));
    }

    private static String deliveredOf(JsonObject ops, String botId) {
        for (JsonElement e : ops.getAsJsonArray("bots")) {
            if (e.getAsJsonObject().get("botId").getAsString().equals(botId)) {
                JsonElement d = e.getAsJsonObject().get("delivered");
                return d.isJsonNull() ? null : d.getAsString();
            }
        }
        throw new AssertionError("no such bot in /ops");
    }

    @Test
    void aStopWithNoRunningTaskIsDeliveredByTheAck() throws Exception {
        makeFarmer("farmer");
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"farmer\"}"));
        assertEquals("sent", deliveredOf(ok(get(owner, "/api/v1/ops")), "farmer"));
        ok(sync("farmer", "\"stopAck\":1"));
        assertEquals("delivered", deliveredOf(ok(get(owner, "/api/v1/ops")), "farmer"));
        // A bot that never synced cannot be stopped remotely.
        hello("lonely");
        ok(post(owner, "/api/v1/bots/roles", "{\"botId\":\"lonely\",\"roles\":[\"loader\"]}"));
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"lonely\"}"));
        assertEquals("unsupported", deliveredOf(ok(get(owner, "/api/v1/ops")), "lonely"));
        assertEquals("404", String.valueOf(post(owner, "/api/v1/ops/stop", "{\"scope\":\"nobody\"}").status));
    }

    @Test
    void stopAndRolesSurviveARestart() throws Exception {
        makeFarmer("farmer");
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\",\"reason\":\"night\"}"));
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"farmer\"}"));
        String farm = farmWithProgram("farmer", "custom");
        restart();
        JsonObject ops = ok(get(owner, "/api/v1/ops"));
        assertTrue(ops.getAsJsonObject("stopAll").get("on").getAsBoolean());
        assertEquals("night", ops.getAsJsonObject("stopAll").get("reason").getAsString());
        assertEquals(2, ops.get("rev").getAsInt());
        assertTrue(ops.getAsJsonArray("bots").get(0).getAsJsonObject().getAsJsonObject("stop").get("on").getAsBoolean());
        assertEquals("farmer", ops.getAsJsonArray("bots").get(0).getAsJsonObject().getAsJsonArray("roles").get(0).getAsString());
        assertNotNull(farmOf(farm), "farms survive too");
        assertTrue(tasks.isFarmer("farmer"));
    }

    @Test
    void theHeartbeatCarriesTheGlobalStop() throws Exception {
        JsonObject hb = ok(botAnon("/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\"}"));
        assertFalse(hb.getAsJsonObject("ops").get("stop").getAsBoolean());
        assertEquals(0, hb.getAsJsonObject("ops").get("rev").getAsInt());
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\"}"));
        hb = ok(botAnon("/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\"}"));
        assertTrue(hb.getAsJsonObject("ops").get("stop").getAsBoolean());
        assertEquals(1, hb.getAsJsonObject("ops").get("rev").getAsInt());
    }

    @Test
    void aTaskIdNeverCollidesWithAPearlTripId() throws Exception {
        makeFarmer("farmer");
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        tasks.otherIds(id -> asked.incrementAndGet() <= 5);
        String id = tasks.newTaskId();
        assertEquals(6, asked.get(), "five ids were taken by pearl trips and skipped");
        assertTrue(FarmVocab.TASK_ID.matcher(id).matches());
    }
}

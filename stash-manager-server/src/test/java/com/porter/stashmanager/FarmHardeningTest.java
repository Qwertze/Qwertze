package com.porter.stashmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review fixes of server 1.5.0: failed writes (fault injection per store), STOP delivery, leases across a restart, the flip
 * race, claims, sync ordering, backups that fail closed, and the smaller hardening rules.
 */
class FarmHardeningTest extends FarmHarness {
    private static Predicate<Path> faultOn(String prefix) {
        return p -> p.getFileName().toString().startsWith(prefix);
    }

    private String read(String name) throws Exception {
        return Files.readString(data.resolve(name));
    }

    private String startFarm(String farm) throws Exception {
        return ok(post(owner, "/api/v1/farms/start", "{\"id\":\"" + farm + "\"}")).get("taskId").getAsString();
    }

    // ---------- failed writes ----------

    @Test
    void aFailedBotsWriteIs503AndIsRetriedByTheNextCall() throws Exception {
        hello("plug");
        StateFile.writeFault = faultOn("bots.json");
        Res r = sync("plug", null);
        assertEquals(503, r.status, r.body);
        assertEquals("storage_error", r.code());
        assertFalse(Files.exists(data.resolve("bots.json")));
        StateFile.writeFault = null;
        ok(get(owner, "/api/v1/ops")); // housekeeping retries every dirty flag
        assertTrue(read("bots.json").contains("plug"), "the flag stayed set until the write worked");
    }

    @Test
    void aFailedOpsWriteKeepsTheStopAndSaysAppliedNotSaved() throws Exception {
        makeFarmer("farmer");
        StateFile.writeFault = faultOn("ops.json");
        Res r = post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\",\"reason\":\"night\"}");
        assertEquals(503, r.status, r.body);
        JsonObject o = r.obj();
        assertEquals("storage_error", o.get("code").getAsString());
        assertTrue(o.get("applied").getAsBoolean());
        assertFalse(o.get("saved").getAsBoolean());
        assertTrue(o.get("changed").getAsBoolean());
        assertTrue(o.get("error").getAsString().startsWith("applied, not saved"));
        assertTrue(read("audit.json").contains("ops.stop"), "audited whatever the disk did");
        // The STOP is in force (a bot is told), and the heartbeat says so too.
        assertTrue(ok(get(owner, "/api/v1/ops")).getAsJsonObject("stopAll").get("on").getAsBoolean());
        assertTrue(ok(sync("farmer", null)).getAsJsonObject("stop").get("global").getAsBoolean());
        assertTrue(ok(botAnon("/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\"}")).getAsJsonObject("ops").get("stop").getAsBoolean());
        assertFalse(Files.exists(data.resolve("ops.json")));
        // Once the disk works, a call that changes nothing still writes what is dirty.
        StateFile.writeFault = null;
        JsonObject again = ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\"}"));
        assertFalse(again.get("changed").getAsBoolean());
        assertTrue(read("ops.json").contains("\"on\":true"));
    }

    @Test
    void aFailedTasksWriteGoesBackAndTheFarmIsUntouched() throws Exception {
        makeFarmer("farmer");
        String farm = farmWithProgram("farmer", "custom");
        StateFile.writeFault = faultOn("tasks.json");
        Res r = post(owner, "/api/v1/farms/start", "{\"id\":\"" + farm + "\"}");
        assertEquals(503, r.status, r.body);
        assertEquals("storage_error", r.code());
        assertEquals(0, tasksOf("farmer").size(), "no task survived the failed write");
        assertEquals("idle", farmOf(farm).getAsJsonObject("status").get("state").getAsString());
        StateFile.writeFault = null;
        advance(11_000);
        startFarm(farm);
        assertEquals(1, tasksOf("farmer").size());
    }

    @Test
    void aFailedFarmsWriteGoesBack() throws Exception {
        makeFarmer("farmer");
        int rev = ok(get(owner, "/api/v1/farms")).get("rev").getAsInt();
        StateFile.writeFault = faultOn("farms.json");
        Res r = post(owner, "/api/v1/farms", "{\"name\":\"Bone farm\",\"server\":\"2b2t.org\",\"mode\":\"custom\"}");
        assertEquals(503, r.status, r.body);
        assertEquals("storage_error", r.code());
        JsonObject list = ok(get(owner, "/api/v1/farms"));
        assertEquals(0, list.getAsJsonArray("farms").size());
        assertEquals(rev, list.get("rev").getAsInt(), "the registry counter went back too");
        StateFile.writeFault = null;
        String id = createFarm("Bone farm", "custom", "farmer");
        // A delete that fails restores the farm and does not queue a forget.
        StateFile.writeFault = faultOn("farms.json");
        assertEquals(503, post(owner, "/api/v1/farms/delete", "{\"id\":\"" + id + "\"}").status);
        StateFile.writeFault = null;
        assertNotNull(farmOf(id));
        assertEquals(0, ok(sync("farmer", farmerPart("[\"custom\"]", 0))).getAsJsonArray("forget").size());
    }

    @Test
    void aFailedWriteOfOneFileDoesNotStopTheOthers() throws Exception {
        makeFarmer("farmer");
        StateFile.writeFault = faultOn("ops.json");
        post(owner, "/api/v1/ops/stop", "{\"scope\":\"farmer\"}"); // ops.json fails, bots.json (the per-bot STOP) is written
        assertTrue(read("bots.json").contains("\"on\":true"));
        assertFalse(Files.exists(data.resolve("ops.json")));
    }

    // ---------- STOP delivery ----------

    @Test
    void aStopFinishesTasksThatNobodyWillReport() throws Exception {
        makeFarmer("farmer");
        String farm = farmWithProgram("farmer", "custom");
        startFarm(farm); // offered, never claimed: pending with no lease
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"farmer\"}"));
        JsonObject t = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("cancelled", t.get("state").getAsString());
        assertEquals("ops_stop", t.getAsJsonObject("error").get("code").getAsString());
        assertEquals("ops_stop", farmOf(farm).getAsJsonObject("lastSession").get("endedBy").getAsString());
        assertEquals("idle", farmOf(farm).getAsJsonObject("status").get("state").getAsString());
        ok(sync("farmer", "\"stopAck\":1"));
        assertEquals("delivered", ok(get(owner, "/api/v1/ops")).getAsJsonArray("bots").get(0).getAsJsonObject().get("delivered").getAsString());
    }

    @Test
    void aStopFinishesAPausedTaskThatHasNoLease() throws Exception {
        String[] s = runningSession();
        ok(report("farmer", s[1], 1, 1, "running", "\"phase\":\"loop\""));
        advance(TaskService.LEASE_MS + 1000);
        ok(sync("farmer", null)); // paused, lease_lost, no lease
        assertEquals("paused", tasksOf("farmer").get(0).getAsJsonObject().get("state").getAsString());
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\"}"));
        JsonObject t = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("cancelled", t.get("state").getAsString());
        assertEquals("ops_stop", t.getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    void aBareCancelledReportAfterAStopCountsForDelivery() throws Exception {
        String[] s = runningSession();
        ok(report("farmer", s[1], 1, 1, "running", "\"phase\":\"loop\""));
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\"}"));
        // A leased task is the bot's to cancel: still open, not delivered.
        assertEquals("running", tasksOf("farmer").get(0).getAsJsonObject().get("state").getAsString());
        ok(sync("farmer", "\"stopAck\":1"));
        assertEquals("sent", ok(get(owner, "/api/v1/ops")).getAsJsonArray("bots").get(0).getAsJsonObject().get("delivered").getAsString());
        ok(report("farmer", s[1], 1, 2, "cancelled", null)); // no error code
        ok(sync("farmer", "\"stopAck\":1"));
        assertEquals("delivered", ok(get(owner, "/api/v1/ops")).getAsJsonArray("bots").get(0).getAsJsonObject().get("delivered").getAsString());
    }

    @Test
    void aLeaseThatLapsesWhileStoppedEndsTheTaskAsStopped() throws Exception {
        String[] s = runningSession();
        ok(report("farmer", s[1], 1, 1, "running", "\"phase\":\"loop\""));
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"farmer\"}"));
        advance(TaskService.LEASE_MS + 1000);
        ok(sync("farmer", null));
        JsonObject t = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("cancelled", t.get("state").getAsString());
        assertEquals("ops_stop", t.getAsJsonObject("error").get("code").getAsString());
    }

    // ---------- leases across a restart ----------

    @Test
    void aRestartKeepsAnOpenLeasedTaskOnItsLastAttempt() throws Exception {
        String[] s = runningSession(); // attempt 1
        String task = s[1];
        for (int attempt = 2; attempt <= 3; attempt++) {
            advance(TaskService.LEASE_MS + 1000);
            ok(sync("farmer", null));
            advance(60_000L * 16);
            claim("farmer", task);
        }
        ok(report("farmer", task, 3, 1, "running", "\"phase\":\"loop\""));
        advance(10 * 60_000L); // the server is down for ten minutes
        restart();
        JsonObject t = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("running", t.get("state").getAsString(), "not failed before its bot could sync");
        assertEquals(3, t.get("attempts").getAsInt());
        assertEquals(clk.get() + TaskService.LEASE_MS, t.getAsJsonObject("lease").get("expiresAt").getAsLong());
        JsonObject sy = ok(sync("farmer", "\"running\":{\"taskId\":\"" + task + "\",\"epoch\":3,\"state\":\"running\",\"phase\":\"loop\",\"seq\":1}"));
        assertEquals(task, sy.getAsJsonObject("lease").get("taskId").getAsString());
        assertEquals("running", tasksOf("farmer").get(0).getAsJsonObject().get("state").getAsString());
    }

    @Test
    void downtimeIsNotCountedAsPausedTime() throws Exception {
        String[] s = runningSession();
        ok(report("farmer", s[1], 1, 1, "running", "\"phase\":\"loop\""));
        ok(report("farmer", s[1], 1, 2, "paused", "\"phase\":\"parked\",\"error\":{\"code\":\"disconnected\"}"));
        advance(2 * 60 * 60_000L); // two hours down
        restart();
        JsonObject t = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("paused", t.get("state").getAsString());
        assertEquals("disconnected", t.get("pauseReason").getAsString());
    }

    @Test
    void aSyncRenewsTheLeaseBeforeAnythingExpires() throws Exception {
        String[] s = runningSession();
        ok(report("farmer", s[1], 1, 1, "running", "\"phase\":\"loop\""));
        advance(TaskService.LEASE_MS + 500); // just past the lease, the bot's sync is late
        JsonObject sy = ok(sync("farmer", "\"running\":{\"taskId\":\"" + s[1] + "\",\"epoch\":1,\"state\":\"running\",\"phase\":\"loop\",\"seq\":1}"));
        assertFalse(sy.get("lease").isJsonNull());
        assertEquals("running", tasksOf("farmer").get(0).getAsJsonObject().get("state").getAsString());
    }

    @Test
    void aLostLeaseTaskIsOnlyRenewedByABotThatIsRunning() throws Exception {
        String[] s = runningSession();
        String task = s[1];
        ok(report("farmer", task, 1, 1, "running", "\"phase\":\"loop\""));
        advance(TaskService.LEASE_MS + 1000);
        ok(sync("farmer", null)); // paused, lease_lost
        // The bot says it is parked too: nothing is renewed, nothing is cancelled, the report decides.
        JsonObject parked = ok(sync("farmer", "\"running\":{\"taskId\":\"" + task + "\",\"epoch\":1,\"state\":\"paused\",\"phase\":\"parked\",\"seq\":1}"));
        assertTrue(parked.get("lease").isJsonNull());
        assertEquals(0, parked.getAsJsonArray("cancel").size());
        assertTrue(parked.get("offer").isJsonNull());
        JsonObject t = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("paused", t.get("state").getAsString());
        assertEquals("lease_lost", t.get("pauseReason").getAsString());
        // The bot says it is running: back to running with a lease.
        JsonObject back = ok(sync("farmer", "\"running\":{\"taskId\":\"" + task + "\",\"epoch\":1,\"state\":\"running\",\"phase\":\"loop\",\"seq\":1}"));
        assertFalse(back.get("lease").isJsonNull());
        assertEquals("running", tasksOf("farmer").get(0).getAsJsonObject().get("state").getAsString());
    }

    // ---------- the flip race and the viewer routes ----------

    @Test
    void aBotThatBecomesAFarmerWhileItPostsActivityLeavesNothingUnscrubbed() throws Exception {
        hello("b1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            long now = System.currentTimeMillis();
            String body = "{\"botId\":\"b1\",\"activity\":{\"at\":" + now + ",\"connection\":{\"state\":\"online\"},"
                + "\"current\":[{\"source\":\"P\",\"kind\":\"process\",\"title\":\"Walking to 1234 64 -5678\"}]}}";
            Future<?> poster = pool.submit(() -> {
                try {
                    for (int i = 0; i < 150; i++) botAnon("/api/v1/bot/activity", body);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Thread.sleep(40);
            ok(post(owner, "/api/v1/bots/roles", "{\"botId\":\"b1\",\"roles\":[\"farmer\"]}"));
            poster.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        JsonObject act = hub.bot("b1").activity;
        assertTrue(act == null || !act.toString().contains("Walking"), "an unscrubbed snapshot survived the flip: " + act);
        assertTrue(hub.bot("b1").events.isEmpty());
    }

    @Test
    void theViewerBrowserRoutesRefuseAFarmerBot() throws Exception {
        makeFarmer("farmer");
        hello("plain");
        Res state = get(owner, "/api/v1/bots/farmer/viewer");
        assertEquals(403, state.status, state.body);
        assertEquals("farmer", state.code());
        Res stream = get(owner, "/api/v1/bots/farmer/viewer/stream");
        assertEquals(403, stream.status, stream.body);
        assertEquals("farmer", stream.code());
        assertNotEqualsStatus(403, get(owner, "/api/v1/bots/plain/viewer"));
    }

    private static void assertNotEqualsStatus(int status, Res r) {
        assertFalse(r.status == status, r.body);
    }

    // ---------- hot paths ----------

    @Test
    void theFarmerChecksNeverWaitForTheMonitor() throws Exception {
        makeFarmer("farmer");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            synchronized (tasks) { // a long sync holds the monitor
                Future<Boolean> f = pool.submit(() -> tasks.isFarmer("farmer") && !tasks.isFarmer("nobody")
                    && tasks.rolesOf("farmer").contains("farmer") && !tasks.heartbeatOps().get("stop").getAsBoolean() && !tasks.taskIdUsed("t-000000000000"));
                assertTrue(f.get(3, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aSyncThatChangesNothingButTimeDoesNotRewriteBotsJson() throws Exception {
        makeFarmer("farmer");
        AtomicInteger writes = new AtomicInteger();
        StateFile.writeFault = p -> {
            if (p.getFileName().toString().equals("bots.json")) writes.incrementAndGet();
            return false;
        };
        for (int i = 0; i < 3; i++) ok(sync("farmer", farmerPart("[\"custom\",\"afk_still\"]", 0)));
        assertEquals(0, writes.get());
        advance(61_000);
        ok(sync("farmer", farmerPart("[\"custom\",\"afk_still\"]", 0)));
        assertEquals(1, writes.get(), "written on the timer");
        ok(sync("farmer", farmerPart("[\"custom\"]", 0)));
        assertEquals(2, writes.get(), "and at once when something real changed");
    }

    // ---------- anchorId ----------

    @Test
    void theAnchorKeyIsAlwaysAPositionFieldAndAnchorIdIsTheName() throws Exception {
        String[] s = runningSession();
        Res bad = report("farmer", s[1], 1, 1, "running", "\"checkpoint\":{\"step\":\"loop\",\"data\":{\"anchor\":\"a1\"}}");
        assertEquals(400, bad.status);
        assertEquals("position_field", bad.code(), "no exception any more");
        ok(report("farmer", s[1], 1, 1, "running", "\"checkpoint\":{\"step\":\"loop\",\"data\":{\"anchorId\":\"a63\"}}"));
        assertEquals("bad_vocab", report("farmer", s[1], 1, 2, "running", "\"checkpoint\":{\"step\":\"loop\",\"data\":{\"anchorId\":\"a64\"}}").code());
        assertEquals("bad_vocab", report("farmer", s[1], 1, 2, "running", "\"checkpoint\":{\"step\":\"loop\",\"data\":{\"anchorId\":\"a064\"}}").code());
        assertEquals("position_like", report("farmer", s[1], 1, 2, "running", "\"checkpoint\":{\"step\":\"loop\",\"data\":{\"anchorId\":\"12 64 -30\"}}").code());
        for (String ok : new String[]{"a0", "a9", "a10", "a59", "a60", "a63"}) assertTrue(FarmVocab.ANCHOR_ID.matcher(ok).matches(), ok);
        for (String no : new String[]{"a64", "a99", "a", "a-1", "A1", "a001"}) assertFalse(FarmVocab.ANCHOR_ID.matcher(no).matches(), no);
    }

    // ---------- claims, ordering, results ----------

    @Test
    void unsafeViewerIsAFailedCode() throws Exception {
        String[] s = runningSession();
        ok(report("farmer", s[1], 1, 1, "failed", "\"error\":{\"code\":\"unsafe_viewer\"}"));
        JsonObject t = tasksOf("farmer").get(0).getAsJsonObject();
        assertEquals("failed", t.get("state").getAsString());
        assertEquals("unsafe_viewer", t.getAsJsonObject("error").get("code").getAsString());
        assertEquals("unsafe_viewer", farmOf(s[0]).getAsJsonObject("lastSession").get("endedBy").getAsString());
    }

    @Test
    void aNewSessionIsOfferedOnlyAfterTheCancelledOneWasDropped() throws Exception {
        String[] s = runningSession();
        ok(report("farmer", s[1], 1, 1, "running", "\"phase\":\"loop\""));
        ok(post(owner, "/api/v1/farms/stop", "{\"id\":\"" + s[0] + "\"}"));
        String next = startFarm(s[0]);
        String running = "\"running\":{\"taskId\":\"" + s[1] + "\",\"epoch\":1,\"state\":\"running\",\"phase\":\"loop\",\"seq\":1}";
        JsonObject first = ok(sync("farmer", running));
        assertEquals(s[1], first.getAsJsonArray("cancel").get(0).getAsString());
        assertTrue(first.get("offer").isJsonNull(), "the offer waits for the next sync");
        JsonObject second = ok(sync("farmer", null));
        assertEquals(next, second.getAsJsonObject("offer").get("id").getAsString());
        assertEquals(0, second.getAsJsonArray("cancel").size());
    }

    @Test
    void aResultBelongsToAnEnd() throws Exception {
        String[] s = runningSession();
        Res r = report("farmer", s[1], 1, 1, "running", RESULT_TIME_UP);
        assertEquals(400, r.status);
        assertEquals("bad_field", r.code());
        assertEquals("result", r.field());
        assertEquals("result", report("farmer", s[1], 1, 1, "paused", RESULT_TIME_UP + ",\"error\":{\"code\":\"disconnected\"}").field());
        ok(report("farmer", s[1], 1, 1, "running", "\"phase\":\"loop\""));
        ok(report("farmer", s[1], 1, 2, "completed", RESULT_TIME_UP));
    }

    // ---------- strangers ----------

    @Test
    void theStrangerLimitCountsIpv6BySubnetAndAllAddressesTogether() throws Exception {
        assertEquals(TaskService.addressKey("2001:db8:0:1:aaaa:bbbb:cccc:1"), TaskService.addressKey("2001:db8:0:1:1111:2222:3333:4"));
        assertFalse(TaskService.addressKey("2001:db8:0:1::1").equals(TaskService.addressKey("2001:db8:0:2::1")));
        assertEquals("10.1.2.3", TaskService.addressKey("10.1.2.3"));
        assertEquals("fe80:0:0:1", TaskService.addressKey("fe80:0:0:1:2:3:4:5%eth0"));
        // Five hosts of one /64: the fifth is over the per-address limit.
        for (int i = 1; i <= 4; i++) strangerSync("v6s" + i, "2001:db8:0:1:0:0:0:" + i);
        assertEquals(429, assertThrows(Fail.class, () -> strangerSync("v6s5", "2001:db8:0:1:0:0:0:9")).status);
        // Sixty-four ids from sixteen addresses are the global limit; the next one, from a new address, is refused.
        for (int i = 0; i < TaskService.UNKNOWN_GLOBAL_PER_HOUR - 4; i++) strangerSync("g" + i, "10.9." + (i / 4) + ".1");
        Fail over = assertThrows(Fail.class, () -> strangerSync("one-more", "10.200.0.1"));
        assertEquals(429, over.status);
        assertEquals("slow_down", over.code);
        advance(TaskService.HOUR_MS + 1000);
        strangerSync("one-more", "10.200.0.1");
    }

    private void strangerSync(String id, String address) {
        advance(10);
        tasks.sync(JsonParser.parseString(syncBody(id, null)).getAsJsonObject(), id, address);
    }

    // ---------- fields ----------

    @Test
    void theServerFieldIsAHostName() throws Exception {
        hello("plug");
        for (String bad : new String[]{"bad server!", "a/b", "host:123456", "x".repeat(65), "under_score.org", "a b"}) {
            Res r = bot("plug", "/api/v1/tasks/sync", syncBody("plug", null).replace("2b2t.org", bad));
            assertEquals("server", r.field(), bad);
            Res f = post(owner, "/api/v1/farms", "{\"name\":\"F\",\"server\":\"" + bad + "\",\"mode\":\"custom\"}");
            assertEquals(400, f.status, bad);
            assertEquals("server", f.field(), bad);
        }
        advance(1100);
        ok(bot("plug", "/api/v1/tasks/sync", syncBody("plug", null).replace("2b2t.org", "2b2t.org:25565")));
        makeFarmer("farmer");
        ok(post(owner, "/api/v1/farms", "{\"name\":\"F\",\"server\":\"play.example-server.net:25565\",\"mode\":\"custom\"}"));
    }

    @Test
    void namesAreNormalisedAndHiddenCharactersRefused() throws Exception {
        makeFarmer("farmer");
        for (String bad : new String[]{"ab\\uFF11\\uFF12\\uFF13", "Farm\\u200Bone", "A\\u202Ebc", "A\\u2028B", "A\\u2029B", "A\\u2066B", "A\\u0000B", "A\\uE000B", "A\\u00adB"}) {
            Res r = post(owner, "/api/v1/farms", "{\"name\":\"" + bad + "\",\"server\":\"s\",\"mode\":\"custom\"}");
            assertEquals(400, r.status, bad + " " + r.body);
            assertEquals("name", r.field(), bad);
        }
        // Full-width letters are normalised (and stored so); the uniqueness check sees the normalised, lower-cased text.
        JsonObject created = ok(post(owner, "/api/v1/farms", "{\"name\":\"\\uFF21\\uFF22 Farm\",\"server\":\"s\",\"mode\":\"custom\"}"));
        assertEquals("AB Farm", farmOf(created.get("id").getAsString()).get("name").getAsString());
        Res dup = post(owner, "/api/v1/farms", "{\"name\":\"ab farm\",\"server\":\"s\",\"mode\":\"custom\"}");
        assertEquals("name_in_use", dup.code());
        // Two digits are fine, even full-width ones.
        ok(post(owner, "/api/v1/farms", "{\"name\":\"Farm \\uFF11\\uFF12\",\"server\":\"s\",\"mode\":\"custom\"}"));
    }

    @Test
    void theFarmerBlockOfTheListIsNullSafe() throws Exception {
        makeFarmer("farmer");
        // A sync whose farmer object has no version or modes.
        ok(sync("farmer", "\"farmer\":{\"enabled\":true,\"farmsRev\":0},\"programs\":[],\"draft\":null"));
        JsonObject f = ok(get(owner, "/api/v1/farms")).getAsJsonObject("farmer");
        assertTrue(f.get("version").isJsonNull());
        assertFalse(f.get("acceptUploads").getAsBoolean());
        // A profile loaded from a file that lacks the keys is fine too.
        tasks.ops.bots.get("farmer").farmer = new JsonObject();
        f = ok(get(owner, "/api/v1/farms")).getAsJsonObject("farmer");
        assertTrue(f.get("version").isJsonNull());
        assertEquals(0, f.getAsJsonArray("modes").size());
    }

    // ---------- pearl trips share the id space ----------

    @Test
    void thePearlEngineAndTheTaskServiceCheckEachOthersIds() throws Exception {
        PearlStore store = new PearlStore(data.resolve("pearl-unit"));
        PearlEngine engine = new PearlEngine(store);
        AtomicInteger asked = new AtomicInteger();
        engine.taskIds(id -> asked.incrementAndGet() <= 3);
        String id = engine.newId("t-", 12);
        assertTrue(FarmVocab.TASK_ID.matcher(id).matches());
        assertEquals(4, asked.get(), "three ids were task ids and were skipped");
        asked.set(100);
        String station = engine.newId("ps-", 8);
        assertTrue(station.startsWith("ps-"));
        PearlStore.Trip t = new PearlStore.Trip();
        t.id = "t-aaaaaaaaaaaa";
        t.childLoad = "t-bbbbbbbbbbbb";
        store.trips.add(t);
        assertTrue(engine.tripIdUsed("t-aaaaaaaaaaaa"));
        assertTrue(engine.tripIdUsed("t-bbbbbbbbbbbb"));
        assertFalse(engine.tripIdUsed("t-cccccccccccc"));
        // The real wiring: a task id made by the service is known to it, lock-free.
        makeFarmer("farmer");
        String task = startFarm(farmWithProgram("farmer", "custom"));
        assertTrue(tasks.taskIdUsed(task));
    }

    // ---------- backups that fail closed ----------

    @Test
    void anUnreadableOpsFileNeverTurnsAStopOff() throws Exception {
        makeFarmer("farmer");
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\"}"));
        ok(post(owner, "/api/v1/ops/resume", "{\"scope\":\"all\"}"));
        assertTrue(Files.exists(data.resolve("ops.json.bak")));
        viewer.close();
        server.stop(0);
        Files.writeString(data.resolve("ops.json"), "{ garbage");
        Files.delete(data.resolve("ops.json.bak"));
        startAt(data);
        JsonObject ops = ok(get(owner, "/api/v1/ops"));
        assertTrue(ops.getAsJsonObject("stopAll").get("on").getAsBoolean(), "the STOP is on, not off");
        assertEquals("server", ops.getAsJsonObject("stopAll").get("by").getAsString());
        assertEquals("ops.json was unreadable", ops.getAsJsonObject("stopAll").get("reason").getAsString());
        assertTrue(ok(sync("farmer", null)).getAsJsonObject("stop").get("global").getAsBoolean());
        assertTrue(read("ops.json").contains("\"on\":true"), "the repaired state was written out");
        // The owner resumes it on the website.
        assertTrue(ok(post(owner, "/api/v1/ops/resume", "{\"scope\":\"all\"}")).get("changed").getAsBoolean());
        // A first run (no file, no backup) starts with the STOP off.
        viewer.close();
        server.stop(0);
        Files.delete(data.resolve("ops.json"));
        Files.delete(data.resolve("ops.json.bak"));
        startAt(data);
        assertFalse(ok(get(owner, "/api/v1/ops")).getAsJsonObject("stopAll").get("on").getAsBoolean());
    }

    @Test
    void anOpsFileThatIsUnreadableWithAGoodBackupStillForcesTheStopOn() throws Exception {
        makeFarmer("farmer");
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\"}"));
        ok(post(owner, "/api/v1/ops/resume", "{\"scope\":\"all\"}"));
        viewer.close();
        server.stop(0);
        Files.writeString(data.resolve("ops.json"), "not json");
        startAt(data); // the backup says "off" (the state before the last write): the STOP is forced on anyway
        assertTrue(ok(get(owner, "/api/v1/ops")).getAsJsonObject("stopAll").get("on").getAsBoolean());
        assertTrue(ok(get(owner, "/api/v1/ops")).get("rev").getAsInt() >= 2);
    }

    @Test
    void anUnreadableBotsFileIsRestoredFromItsBackup() throws Exception {
        makeFarmer("farmer");
        assertTrue(Files.exists(data.resolve("bots.json.bak")));
        viewer.close();
        server.stop(0);
        Files.writeString(data.resolve("bots.json"), "{ garbage");
        startAt(data);
        assertTrue(tasks.isFarmer("farmer"), "role and flags came back from the backup");
        assertEquals("farmer", ok(get(owner, "/api/v1/ops")).getAsJsonArray("bots").get(0).getAsJsonObject().getAsJsonArray("roles").get(0).getAsString());
        // Both unreadable: the profiles start empty (the farmer recreates its flag with its next sync).
        viewer.close();
        server.stop(0);
        Files.writeString(data.resolve("bots.json"), "{ garbage");
        Files.writeString(data.resolve("bots.json.bak"), "{ garbage");
        startAt(data);
        assertFalse(tasks.isFarmer("farmer"));
        assertNull(tasks.ops.bots.get("farmer"));
    }

    // ---------- the signer floor ----------

    @Test
    void theSignerFloorAllowsAClockThatRunsBehind() throws Exception {
        assertEquals(1_000_000 - Signer.MAX_SKEW_MS, StashManagerServer.signerFloor(1_000_000));
        long now = System.currentTimeMillis();
        router.signer().notBefore(StashManagerServer.signerFloor(now + 30_000)); // "started" 30 s from now: the floor is 30 s ago
        String hello = "{\"botId\":\"plug\",\"player\":\"P\",\"proxy\":{\"version\":\"1\",\"mc\":\"1.21.4\"}}";
        assertEquals(200, signed("POST", "/api/v1/bot/hello", hello, "-", now - 20_000, k).status(), "20 s behind: accepted");
        assertEquals(401, signed("POST", "/api/v1/bot/hello", hello, "-", now - 40_000, k).status(), "older than the floor: refused");
    }

    // ---------- the fix round after the 1.5.0 reviews ----------

    @Test
    void aClaimWhoseWriteFailedIsTakenBackSoTheRetryWorks() throws Exception {
        makeFarmer("farmer");
        String task = startFarm(farmWithProgram("farmer", "custom"));
        StateFile.writeFault = faultOn("tasks.json");
        Res r = bot("farmer", "/api/v1/tasks/" + task + "/claim", "{\"botId\":\"farmer\"}");
        assertEquals(503, r.status, r.body);
        assertEquals("storage_error", r.code());
        TaskStore.Task t = tasks.tasks.get(task);
        assertEquals(0, t.epoch, "the epoch went back");
        assertEquals(0, t.attempts);
        assertNull(t.leaseBot, "no lease the bot never got");
        StateFile.writeFault = null;
        JsonObject again = ok(bot("farmer", "/api/v1/tasks/" + task + "/claim", "{\"botId\":\"farmer\"}"));
        assertEquals(1, again.getAsJsonObject("lease").get("epoch").getAsInt());
        assertEquals(1, tasks.tasks.get(task).attempts);
    }

    @Test
    void aFreshServerWithNoFilesHasNoStopButALostBotsFileForcesIt() throws Exception {
        assertFalse(ok(get(owner, "/api/v1/ops")).getAsJsonObject("stopAll").get("on").getAsBoolean(), "a first run");
        makeFarmer("farmer");
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\"}"));
        ok(post(owner, "/api/v1/ops/resume", "{\"scope\":\"all\"}"));
        assertTrue(Files.exists(data.resolve("ops.json")));
        long rev = ok(get(owner, "/api/v1/ops")).get("rev").getAsLong();
        viewer.close();
        server.stop(0);
        Files.delete(data.resolve("bots.json"));
        Files.delete(data.resolve("bots.json.bak"));
        startAt(data);
        JsonObject stop = ok(get(owner, "/api/v1/ops")).getAsJsonObject("stopAll");
        assertTrue(stop.get("on").getAsBoolean(), "bots.json is gone: the STOP is on until the owner resumes");
        assertEquals("server", stop.get("by").getAsString());
        assertTrue(stop.get("rev").getAsLong() > rev, "the counter moved on");
        assertTrue(read("ops.json").contains("\"on\":true"), "written out at once");
        assertTrue(ok(post(owner, "/api/v1/ops/resume", "{\"scope\":\"all\"}")).get("changed").getAsBoolean());
    }

    @Test
    void aBotsFileRestoredFromItsBackupForcesTheStopOn() throws Exception {
        makeFarmer("farmer");
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\"}"));
        ok(post(owner, "/api/v1/ops/resume", "{\"scope\":\"all\"}"));
        viewer.close();
        server.stop(0);
        Files.writeString(data.resolve("bots.json"), "{ garbage");
        startAt(data);
        assertTrue(tasks.isFarmer("farmer"), "restored from the backup");
        JsonObject stop = ok(get(owner, "/api/v1/ops")).getAsJsonObject("stopAll");
        assertTrue(stop.get("on").getAsBoolean());
        assertEquals("server", stop.get("by").getAsString());
        assertEquals("bots.json was unreadable", stop.get("reason").getAsString());
    }

    @Test
    void aFailingBackupDoesNotBlockThePrimaryWrite() throws Exception {
        makeFarmer("farmer");
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\"}")); // ops.json now exists
        String backupBefore = Files.exists(data.resolve("ops.json.bak")) ? read("ops.json.bak") : null;
        StateFile.writeFault = p -> p.getFileName().toString().endsWith(".bak");
        JsonObject r = ok(post(owner, "/api/v1/ops/resume", "{\"scope\":\"all\"}")); // 200, saved
        assertTrue(r.get("changed").getAsBoolean());
        assertTrue(read("ops.json").contains("\"on\":false"), "the primary file was written");
        assertEquals(backupBefore, Files.exists(data.resolve("ops.json.bak")) ? read("ops.json.bak") : null, "the old backup is untouched");
        // The same for farms.json.
        createFarm("Bone farm", "custom", "farmer");
        createFarm("Cactus", "afk_still", "farmer");
        assertTrue(read("automation/farms.json").contains("Cactus"));
        // A damaged primary never replaces a good backup (the rule stays).
        StateFile.writeFault = null;
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\"}"));
        String good = read("ops.json.bak");
        Files.writeString(data.resolve("ops.json"), "{ damaged");
        ok(post(owner, "/api/v1/ops/resume", "{\"scope\":\"all\"}"));
        assertEquals(good, read("ops.json.bak"));
        assertTrue(read("ops.json").contains("\"on\":false"));
    }

    @Test
    void anUnclaimedSessionIsCancelledAfterTenMinutes() throws Exception {
        makeFarmer("farmer");
        String farm = farmWithProgram("farmer", "custom");
        String task = startFarm(farm);
        advance(9 * 60_000);
        ok(sync("farmer", null));
        assertEquals("pending", tasks.tasks.get(task).state, "9 minutes: still waiting");
        advance(61_000);
        ok(get(owner, "/api/v1/farms"));
        TaskStore.Task t = tasks.tasks.get(task);
        assertEquals("cancelled", t.state);
        assertEquals("offer_timeout", t.errorCode);
        assertEquals("offer_timeout", farmOf(farm).getAsJsonObject("lastSession").get("endedBy").getAsString());
        assertEquals("idle", farmOf(farm).getAsJsonObject("status").get("state").getAsString());
        assertTrue(ok(sync("farmer", null)).get("offer").isJsonNull(), "no offer for a cancelled task");
    }

    @Test
    void aForcedStopAtStartEndsTheTasksNobodyHolds() throws Exception {
        makeFarmer("farmer");
        ok(post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\"}"));
        ok(post(owner, "/api/v1/ops/resume", "{\"scope\":\"all\"}")); // ops.json exists
        String farm = farmWithProgram("farmer", "custom");
        String task = startFarm(farm); // pending, no lease
        viewer.close();
        server.stop(0);
        Files.writeString(data.resolve("ops.json"), "{ garbage");
        Files.deleteIfExists(data.resolve("ops.json.bak"));
        startAt(data);
        TaskStore.Task t = tasks.tasks.get(task);
        assertEquals("cancelled", t.state);
        assertEquals("ops_stop", t.errorCode);
        assertEquals("ops_stop", farmOf(farm).getAsJsonObject("lastSession").get("endedBy").getAsString());
    }

    @Test
    void takingTheFarmerRoleAwayEndsTheOpenSessions() throws Exception {
        String[] s = runningSession(); // claimed, pending with a lease
        ok(report("farmer", s[1], 1, 1, "running", null));
        ok(post(owner, "/api/v1/bots/roles", "{\"botId\":\"farmer\",\"roles\":[]}"));
        TaskStore.Task t = tasks.tasks.get(s[1]);
        assertEquals("cancelled", t.state);
        assertEquals("role_removed", t.errorCode);
        assertEquals("role_removed", farmOf(s[0]).getAsJsonObject("lastSession").get("endedBy").getAsString());
        // The bot that still reports it running is told to drop it, and its reports are refused.
        JsonObject reply = ok(sync("farmer", "\"running\":{\"taskId\":\"" + s[1] + "\",\"epoch\":1,\"state\":\"running\",\"seq\":2}"));
        assertTrue(reply.getAsJsonArray("cancel").toString().contains(s[1]));
        assertTrue(reply.get("lease").isJsonNull());
        assertEquals(409, report("farmer", s[1], 1, 3, "running", null).status);
    }

    @Test
    void leaseRenewalReportAndClaimRecheckTheRole() throws Exception {
        String[] s = runningSession();
        ok(report("farmer", s[1], 1, 1, "running", null));
        synchronized (tasks) { // the role is gone without the tasks having been ended (a restored file, say)
            tasks.ops.bots.get("farmer").roles.clear();
        }
        long lease = tasks.tasks.get(s[1]).leaseExpires;
        JsonObject reply = ok(sync("farmer", "\"running\":{\"taskId\":\"" + s[1] + "\",\"epoch\":1,\"state\":\"running\",\"seq\":2}"));
        assertEquals(lease, tasks.tasks.get(s[1]).leaseExpires, "no renewal without the role");
        assertTrue(reply.getAsJsonArray("cancel").toString().contains(s[1]));
        Res rep = report("farmer", s[1], 1, 3, "running", null);
        assertEquals(409, rep.status, rep.body);
        assertEquals("not_target", rep.code());
        assertEquals(409, bot("farmer", "/api/v1/tasks/" + s[1] + "/claim", "{\"botId\":\"farmer\"}").status);
    }

    @Test
    void aFarmWriteThatHitOneFileAndFailedOnTheOtherIsRolledBackAndConverges() throws Exception {
        String[] s = runningSession();
        StateFile.writeFault = faultOn("tasks.json");
        Res r = post(owner, "/api/v1/farms/stop", "{\"id\":\"" + s[0] + "\"}"); // the session ends: farms.json and tasks.json change
        assertEquals(503, r.status, r.body);
        assertEquals("storage_error", r.code());
        assertTrue(read("automation/farms.json").contains("lastSession"), "farms.json took the change that tasks.json refused");
        assertTrue(tasks.tasks.get(s[1]).open(), "memory went back: the session is still open");
        StateFile.writeFault = null;
        ok(get(owner, "/api/v1/farms")); // the next call writes both files again
        assertFalse(read("automation/farms.json").contains("lastSession"), "the files agree with memory again");
        TaskStore disk = new TaskStore(data, clk::get);
        disk.load();
        assertTrue(disk.get(s[1]).open());
        FarmStore farms = new FarmStore(data);
        farms.load();
        assertNull(farms.get(s[0]).lastSession);
        // The user's retry works.
        ok(post(owner, "/api/v1/farms/stop", "{\"id\":\"" + s[0] + "\"}"));
        assertFalse(tasks.tasks.get(s[1]).open());
    }

    @Test
    void theBackgroundRetryWritesAStopThatWasAppliedButNotSaved() throws Exception {
        makeFarmer("farmer");
        StateFile.writeFault = faultOn("ops.json");
        assertEquals(503, post(owner, "/api/v1/ops/stop", "{\"scope\":\"all\"}").status);
        assertFalse(Files.exists(data.resolve("ops.json")));
        tasks.retryFlush(); // the disk is still failing: nothing changes, nothing throws
        assertFalse(Files.exists(data.resolve("ops.json")));
        StateFile.writeFault = null;
        tasks.retryFlush(); // what the 30 s scheduler calls: no other request needed
        assertTrue(read("ops.json").contains("\"on\":true"));
        assertEquals(30_000, TaskService.FLUSH_RETRY_MS);
    }

    @Test
    void theFlipRaceIsClosedByTheCheckAfterTheStore() throws Exception {
        hello("b1");
        long now = System.currentTimeMillis();
        String body = "{\"botId\":\"b1\",\"activity\":{\"at\":" + now + ",\"connection\":{\"state\":\"online\"},"
            + "\"current\":[{\"source\":\"P\",\"kind\":\"process\",\"title\":\"Walking to 1234 64 -5678\"}],"
            + "\"events\":[{\"at\":" + now + ",\"source\":\"P\",\"level\":\"info\",\"text\":\"at 1234\"}]}}";
        try {
            // The bot is not a farmer at the check; the owner makes it one right after (the exact window of the race).
            BotRoutes.afterFarmerCheck = () -> {
                BotRoutes.afterFarmerCheck = null;
                tasks.setRoles("b1", java.util.List.of("farmer"));
            };
            ok(botAnon("/api/v1/bot/activity", body));
        } finally {
            BotRoutes.afterFarmerCheck = null;
        }
        assertTrue(tasks.isFarmer("b1"));
        JsonObject act = hub.bot("b1").activity;
        assertTrue(act == null || !act.toString().contains("Walking"), "an unscrubbed snapshot survived the flip: " + act);
        assertTrue(hub.bot("b1").events.isEmpty());
    }
}

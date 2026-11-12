package com.porter.stashmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pearl travel engine (PEARL-CONTRACT v1.0 rev 4) without HTTP: a fake clock, the traveller's heartbeats and the loaders' reports
 * go in as the bots would send them, and the trip's states come out.
 */
class PearlEngineTest {
    static final String S = "srv", STASH = "stash", T = "Qwerttze";

    @TempDir
    Path dir;
    final AtomicLong clk = new AtomicLong(10_000_000L);
    PearlStore store;
    PearlEngine eng;
    String far, home;
    long joined;
    int carried = 3;
    boolean drop = true;
    String uuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    String chOwner = T;

    void start() {
        store = new PearlStore(dir);
        store.load();
        eng = new PearlEngine(store, clk::get);
    }

    /** A far station "Nether hub" and the stash's home station, both with the loader's chamber armed for T. */
    void world() {
        start();
        far = eng.saveStation(null, "Nether hub", S, "minecraft:the_nether", "FarBot", null).id;
        home = eng.saveStation(null, "Home", S, "minecraft:overworld", "HomeBot", STASH).id;
        joined = clk.get() - 60_000;
        beat(null, null, null);
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        loaderReport("HomeBot", "pearl-h", "armed", null, false);
        eng.approveLoader(far, "b-FarBot"); // "This is my loader"
        eng.approveLoader(home, "b-HomeBot");
        eng.setPin(T, uuid); // the owner confirms the uuid
        clk.addAndGet(1_000);
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        loaderReport("HomeBot", "pearl-h", "armed", null, false);
    }

    static JsonObject j(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    JsonObject tripReport(String id, String step, String extra) {
        return j("{\"id\":\"" + id + "\",\"step\":\"" + step + "\",\"pause\":null,\"error\":null,\"whispers\":" + (extra != null && extra.contains("\"whispers\"") ? 0 : 0)
            + (extra == null ? "" : "," + extra) + "}");
    }

    /** The traveller's heartbeat; trip is the "trip" object (as JSON text) or null; away is the station id or null. */
    JsonObject beat(String tripJson, String away, String game) {
        return beatAt(clk.get(), 0, tripJson, away, game, null);
    }

    JsonObject beatAt(long botClock, long unused, String tripJson, String away, String game, String local) {
        String g = game == null ? "in" : game;
        JsonObject tp = j("{\"v\":1,\"enabled\":true,\"at\":" + botClock + ",\"uuid\":\"" + uuid + "\",\"game\":\"" + g + "\","
            + "\"joinedAt\":" + (g.equals("in") ? joined - clk.get() + botClock : "null") + ",\"client\":\"plugin\","
            + "\"away\":" + (away == null ? "null" : "{\"station\":\"" + away + "\",\"trip\":null,\"since\":" + botClock + "}") + ","
            + "\"pearls\":{\"carried\":" + carried + "},\"trip\":" + (tripJson == null ? "null" : tripJson) + ",\"local\":" + (local == null ? "null" : local) + "}");
        return eng.onTraveller(S, STASH, T, true, tp, 0);
    }

    String trip(String id, String step, String extra) {
        return "{\"id\":\"" + id + "\",\"step\":\"" + step + "\",\"pause\":null,\"error\":null,\"whispers\":0,\"loadSentAt\":null,\"teleportAt\":null,"
            + "\"teleportDim\":null,\"rearm\":null,\"pearls\":{\"before\":null,\"after\":null}" + "}".replace("}", "") + (extra == null ? "" : "," + extra) + "}";
    }

    JsonObject loaderReport(String player, String chamber, String presence, String loadJson, boolean busy) {
        return loaderReportAt(player, clk.get(), chamber, presence, loadJson, busy, false);
    }

    JsonObject loaderReportAt(String player, long botClock, String chamber, String presence, String loadJson, boolean busy, boolean temporary) {
        JsonObject body = j("{\"v\":1,\"player\":\"" + player + "\",\"at\":" + botClock + ",\"dropPearlAfterLoad\":" + drop + ",\"temporaryMode\":" + temporary
            + ",\"busy\":" + busy + ",\"playerNear\":false,\"chambers\":[{\"id\":\"" + chamber + "\",\"owner\":\"" + chOwner + "\",\"ownerKnown\":true,\"presence\":\""
            + presence + "\",\"since\":" + (botClock - 60_000) + "}],\"load\":" + (loadJson == null ? "null" : loadJson) + "}");
        return eng.onLoader(player, "b-" + player, body);
    }

    String load(String state, long at, Long gone, Long near, boolean present) {
        return "{\"id\":\"pearl-1\",\"requester\":\"" + T + "\",\"state\":\"" + state + "\",\"reason\":" + (state.equals("aborted") ? "\"path_failed\"" : "null") + ",\"at\":" + at
            + ",\"pearlGoneAt\":" + gone + ",\"requesterNearAt\":" + near + ",\"pearlPresent\":" + present + ",\"reopened\":null}";
    }

    String state(String id) {
        return eng.tripJson(id).get("state").getAsString();
    }

    String step(String id) {
        return eng.tripJson(id).getAsJsonObject("checkpoint").get("step").getAsString();
    }

    String code(String id) {
        JsonObject o = eng.tripJson(id);
        return o.get("error").isJsonNull() ? null : o.getAsJsonObject("error").get("code").getAsString();
    }

    String warning(String id) {
        JsonObject o = eng.tripJson(id);
        return o.get("warning").isJsonNull() ? null : o.get("warning").getAsString();
    }

    String newTrip() {
        return eng.createTrip(S, STASH, "go", far, false, "website", "owner", "owner", null, clk.get()).id;
    }

    /** Runs a trip up to the point where the server said go. Returns the id. */
    String toGo() {
        String id = newTrip();
        JsonObject r = beat(null, null, null);
        assertEquals("pending", r.getAsJsonObject("pearlTrip").get("state").getAsString());
        beat(trip(id, "precheck", null), null, null);
        r = beat(trip(id, "ready", null), null, null);
        assertTrue(r.getAsJsonObject("pearlTrip").get("go").getAsBoolean(), r.toString());
        assertEquals("pearl-1", r.getAsJsonObject("pearlTrip").get("pearlId").getAsString());
        assertEquals("FarBot", r.getAsJsonObject("pearlTrip").get("loader").getAsString());
        return id;
    }

    // ---------- the whole way ----------

    @Test
    void aTripGoesThroughEveryStepAndCompletes() {
        world();
        String id = toGo();
        assertEquals("running", state(id));
        assertEquals("ready", step(id));
        assertTrue(eng.tripJson(id).getAsJsonArray("locks").size() == 2, "both locks are held from go");
        long sent = clk.get();
        beat(trip(id, "load_sent", "\"whispers\":1").replace("\"whispers\":0,\"loadSentAt\":null", "\"whispers\":1,\"loadSentAt\":" + sent), null, null);
        // (the replace above makes the report carry the whisper time)
        assertEquals("awaiting_pull", step(id));
        // L says loading 2 s later: the deadline moves to loading + 150 s
        clk.addAndGet(2_000);
        JsonObject r = loaderReport("FarBot", "pearl-1", "armed", load("loading", clk.get(), null, null, true), true);
        assertTrue(r.getAsJsonArray("allow").size() >= 1);
        // The pull: T teleports 6 s after the whisper; L saw the pearl go and T arrive.
        clk.addAndGet(3_500);
        long gone = clk.get();
        clk.addAndGet(500);
        long tele = clk.get();
        clk.addAndGet(1_000);
        loaderReport("FarBot", "pearl-1", "empty", load("loaded", gone - 3_500, gone, clk.get(), false), false);
        String arrived = trip(id, "awaiting_pull", null).replace("\"loadSentAt\":null,\"teleportAt\":null,\"teleportDim\":null",
            "\"loadSentAt\":" + sent + ",\"teleportAt\":" + tele + ",\"teleportDim\":\"minecraft:the_nether\"").replace("\"whispers\":0", "\"whispers\":1");
        r = beat(arrived, home, null);
        assertEquals("pickup", step(id));
        assertTrue(r.getAsJsonObject("pearlTrip").get("rearm").getAsBoolean());
        // T re-arms; L says armed again; the trip completes. Count: before 3, the dropped pearl +1, the re-arm -1: 3.
        clk.addAndGet(8_000);
        String rearmed = trip(id, "rearm", "\"rearm\":{\"result\":\"SUCCESS\"}").replace("\"pearls\":{\"before\":null,\"after\":null}", "\"pearls\":{\"before\":3,\"after\":3}");
        beat(rearmed, home, null);
        assertEquals("rearm", step(id));
        clk.addAndGet(2_000);
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        assertEquals("completed", state(id));
        assertNull(warning(id));
        assertEquals(3, eng.tripJson(id).getAsJsonObject("pearls").get("before").getAsInt());
        assertEquals(0, eng.tripJson(id).getAsJsonArray("locks").size(), "released");
        // The reply says the trip is over, once.
        JsonObject last = beat(null, home, null);
        assertEquals("completed", last.getAsJsonObject("pearlTrip").get("state").getAsString());
        assertFalse(last.getAsJsonObject("pearlTrip").get("rearm").getAsBoolean(), "finish clears rearm");
        assertFalse(last.getAsJsonObject("pearlTrip").get("go").getAsBoolean());
        // The notice stays while T's heartbeat still carries the trip, and goes with the first one that doesn't.
        assertTrue(beat(trip(id, "done", null), home, null).has("pearlTrip"));
        assertFalse(beat(null, home, null).has("pearlTrip"));
        assertFalse(beat(null, home, null).has("pearlTrip"));
    }

    @Test
    void anUnconfirmedPullAndTheWrongDimensionFailWithHold() {
        world();
        String id = toGo();
        long sent = clk.get();
        beat(trip(id, "load_sent", null).replace("\"whispers\":0,\"loadSentAt\":null", "\"whispers\":1,\"loadSentAt\":" + sent), null, null);
        clk.addAndGet(5_000);
        loaderReport("FarBot", "pearl-1", "armed", load("loading", clk.get(), null, null, true), true);
        clk.addAndGet(3_000);
        long tele = clk.get();
        String arrived = trip(id, "awaiting_pull", null).replace("\"loadSentAt\":null,\"teleportAt\":null,\"teleportDim\":null",
            "\"loadSentAt\":" + sent + ",\"teleportAt\":" + tele + ",\"teleportDim\":\"minecraft:the_end\"");
        JsonObject r = beat(arrived, home, null);
        assertEquals("failed", state(id));
        assertEquals("unexpected_location", code(id));
        assertTrue(r.getAsJsonObject("pearlTrip").get("hold").getAsBoolean());
        assertTrue(eng.view((a, b) -> true, a -> true).getAsJsonArray("alerts").toString().contains("unexpected_location"));
        // The owner says "it's home": the next reply tells T once, and the alert goes.
        eng.clearAway(S, STASH);
        assertTrue(beat(null, home, null).get("pearlAwayClear").getAsBoolean());
        assertFalse(eng.view((a, b) -> true, a -> true).getAsJsonArray("alerts").toString().contains("unexpected_location"));
        assertTrue(beat(null, home, null).get("pearlAwayClear").getAsBoolean(), "a lost reply is repeated while T still reports away");
        assertFalse(beat(null, null, null).has("pearlAwayClear"), "away: null acknowledges");
        assertFalse(beat(null, home, null).has("pearlAwayClear"), "stays acknowledged");
    }

    @Test
    void theAwayClearRepeatsUntilAcknowledgedThenStopsAndIsBoundedForOldPlugins() {
        world();
        beat(null, home, null);
        eng.clearAway(S, STASH);
        eng.drainAudits();
        for (int i = 0; i < 5; i++) {
            clk.addAndGet(30_000);
            assertTrue(beat(null, home, null).has("pearlAwayClear"), "repeat " + i);
        }
        // an old plugin never acknowledges: after 10 minutes the repeat stops and says so in the audit
        clk.addAndGet(10 * 60_000L);
        assertFalse(beat(null, home, null).has("pearlAwayClear"));
        var audits = eng.drainAudits();
        assertEquals(1, audits.size());
        assertTrue(audits.get(0)[1].contains("no acknowledgement"), audits.get(0)[1]);
        assertFalse(beat(null, home, null).has("pearlAwayClear"));
        assertEquals(0, eng.drainAudits().size());
    }

    @Test
    void sameDimensionWithoutTheLoadersConfirmationIsUnexpectedAfter20Seconds() {
        world();
        String id = toGo();
        long sent = clk.get();
        beat(trip(id, "load_sent", null).replace("\"whispers\":0,\"loadSentAt\":null", "\"whispers\":1,\"loadSentAt\":" + sent), null, null);
        clk.addAndGet(4_000);
        loaderReport("FarBot", "pearl-1", "armed", load("loading", clk.get(), null, null, true), true);
        clk.addAndGet(2_000);
        long tele = clk.get();
        beat(trip(id, "awaiting_pull", null).replace("\"loadSentAt\":null,\"teleportAt\":null,\"teleportDim\":null",
            "\"loadSentAt\":" + sent + ",\"teleportAt\":" + tele + ",\"teleportDim\":\"minecraft:the_nether\""), home, null);
        assertEquals("arrived", step(id));
        clk.addAndGet(21_000);
        beat(null, home, null);
        assertEquals("failed", state(id));
        assertEquals("unexpected_location", code(id));
    }

    @Test
    void theLoaderNeverAnsweringFailsAfter15SecondsAndALateReportIsFine() {
        world();
        String id = toGo();
        long sent = clk.get();
        beat(trip(id, "load_sent", null).replace("\"whispers\":0,\"loadSentAt\":null", "\"whispers\":1,\"loadSentAt\":" + sent), null, null);
        clk.addAndGet(14_000);
        beat(trip(id, "awaiting_pull", null).replace("\"whispers\":0", "\"whispers\":1"), home, null);
        assertEquals("running", state(id));
        clk.addAndGet(2_000);
        JsonObject r = beat(trip(id, "awaiting_pull", null).replace("\"whispers\":0", "\"whispers\":1"), home, null);
        assertEquals("failed", state(id));
        assertEquals("loader_no_answer", code(id));
        assertEquals("loader_no_answer", r.getAsJsonObject("pearlTrip").get("code").getAsString());
        assertEquals(0, eng.tripJson(id).getAsJsonArray("locks").size());
    }

    @Test
    void anAbortedLoadWithThePearlStillThereIsRetriedOnceThenFails() {
        world();
        String id = toGo();
        long sent = clk.get();
        beat(trip(id, "load_sent", null).replace("\"whispers\":0,\"loadSentAt\":null", "\"whispers\":1,\"loadSentAt\":" + sent), null, null);
        clk.addAndGet(3_000);
        loaderReport("FarBot", "pearl-1", "armed", load("aborted", clk.get(), null, null, true), false);
        JsonObject r = beat(trip(id, "awaiting_pull", null).replace("\"whispers\":0", "\"whispers\":1"), home, null);
        assertTrue(r.getAsJsonObject("pearlTrip").get("retry").getAsBoolean());
        // T whispers again (whispers 2, a new whisper time).
        clk.addAndGet(1_000);
        long second = clk.get();
        r = beat(trip(id, "awaiting_pull", null).replace("\"whispers\":0,\"loadSentAt\":null", "\"whispers\":2,\"loadSentAt\":" + second), home, null);
        assertFalse(r.getAsJsonObject("pearlTrip").get("retry").getAsBoolean());
        loaderReport("FarBot", "pearl-1", "armed", load("aborted", second - 2_000, null, null, true), false);
        assertEquals("running", state(id), "the earlier aborted load doesn't match the second whisper");
        clk.addAndGet(2_000);
        loaderReport("FarBot", "pearl-1", "armed", load("aborted", clk.get(), null, null, true), false);
        assertEquals("failed", state(id));
        assertEquals("load_aborted", code(id));
    }

    // ---------- the server precheck ----------

    @Test
    void aCountedPauseIsRetriedThreeTimesThirtySecondsApartThenFails() {
        world();
        String id = newTrip();
        beat(null, null, null);
        beat(trip(id, "precheck", null), null, null);
        // the loader's report goes stale (a loader that stopped reporting)
        clk.addAndGet(70_000);
        beat(trip(id, "ready", null), null, null);
        assertEquals("paused", state(id));
        assertEquals("loader_stale", eng.tripJson(id).get("pauseReason").getAsString());
        clk.addAndGet(10_000);
        beat(trip(id, "ready", null), null, null);
        assertEquals("paused", state(id), "30 s have not passed: no new try");
        clk.addAndGet(25_000);
        beat(trip(id, "ready", null), null, null);
        assertEquals("paused", state(id));
        clk.addAndGet(31_000);
        beat(trip(id, "ready", null), null, null);
        assertEquals("failed", state(id));
        assertEquals("loader_stale", code(id));
    }

    @Test
    void emptyOwnerOfflineBrokenUnknownAndMisconfiguredAreRefusedWithTheirCodes() {
        world();
        // empty (the owner is online)
        loaderReport("FarBot", "pearl-1", "empty", null, false);
        String id = newTrip();
        beat(null, null, null);
        beat(trip(id, "ready", null), null, null);
        assertEquals("chamber_empty", code(id));
        // T itself in the queue: L's empty reads as owner_offline (a counted pause); the in_queue refusal wins at the precheck's start.
        clk.addAndGet(400_000);
        loaderReport("FarBot", "pearl-1", "empty", null, false);
        loaderReport("HomeBot", "pearl-h", "armed", null, false);
        String id2 = newTrip();
        beat(null, null, "queue");
        beat(trip(id2, "ready", null), null, "queue");
        assertEquals("in_queue", code(id2));
        // temporaryMode on the loader
        clk.addAndGet(400_000);
        loaderReportAt("FarBot", clk.get(), "pearl-1", "armed", null, false, true);
        loaderReport("HomeBot", "pearl-h", "armed", null, false);
        String id3 = newTrip();
        beat(null, null, null);
        beat(trip(id3, "ready", null), null, null);
        assertEquals("loader_misconfigured", code(id3));
        assertTrue(eng.view((a, b) -> true, a -> true).getAsJsonArray("stations").toString().contains("\"warning\":\"temporaryMode\""));
        // no pearl assigned: a loader with only someone else's pearl
        clk.addAndGet(400_000);
        eng.onLoader("FarBot", "b-FarBot", j("{\"v\":1,\"player\":\"FarBot\",\"at\":" + clk.get() + ",\"dropPearlAfterLoad\":true,\"chambers\":[{\"id\":\"pearl-x\",\"owner\":\"Someone\",\"ownerKnown\":true,\"presence\":\"armed\"}],\"load\":null}"));
        loaderReport("HomeBot", "pearl-h", "armed", null, false);
        String id4 = newTrip();
        beat(null, null, null);
        beat(trip(id4, "ready", null), null, null);
        assertEquals("no_pearl_assigned", code(id4));
    }

    @Test
    void theHomeChamberMustBeArmedOrTheTripIsRefusedOrArmsItFirst() {
        world();
        // the home chamber is empty and T carries 3 pearls: it may arm it first
        loaderReport("HomeBot", "pearl-h", "empty", null, false);
        String id = newTrip();
        beat(null, null, null);
        JsonObject r = beat(trip(id, "ready", null), null, null);
        assertEquals("arm_home", step(id));
        assertTrue(r.getAsJsonObject("pearlTrip").get("armHome").getAsBoolean());
        // T arms it; the loader says armed; T reports ready again and the trip goes
        clk.addAndGet(10_000);
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        loaderReport("HomeBot", "pearl-h", "armed", null, false);
        r = beat(trip(id, "ready", null), null, null);
        assertTrue(r.getAsJsonObject("pearlTrip").get("go").getAsBoolean(), r.toString());
        // with 2 pearls it can't arm first: refused
        carried = 2;
        clk.addAndGet(400_000);
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        loaderReport("HomeBot", "pearl-h", "empty", null, false);
        assertEquals("go_timeout", code(id), "T never whispered: the go ran out");
        clk.addAndGet(PearlLogic.COOLDOWN_MS + 1_000);
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        loaderReport("HomeBot", "pearl-h", "empty", null, false);
        String id2 = newTrip();
        beat(null, null, null);
        beat(trip(id2, "ready", null), null, null);
        assertEquals("no_return_pearl", code(id2));
        // one-way needs no return pearl
        clk.addAndGet(PearlLogic.COOLDOWN_MS + 1_000);
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        loaderReport("HomeBot", "pearl-h", "empty", null, false);
        String id3 = eng.createTrip(S, STASH, "go", far, true, "website", "owner", "owner", null, clk.get()).id;
        beat(null, null, null);
        JsonObject g = beat(trip(id3, "ready", null), null, null);
        assertTrue(g.getAsJsonObject("pearlTrip").get("go").getAsBoolean(), g.toString());
    }

    @Test
    void aTripNeedsTheChamberReportToBeNewerThanTheJoinAndTenSecondsInGame() {
        world();
        clk.addAndGet(2_000);
        joined = clk.get(); // T joins now, after the loaders' reports
        String id = newTrip();
        beat(null, null, null);
        beat(trip(id, "ready", null), null, null);
        assertEquals("paused", state(id));
        assertEquals("just_joined", eng.tripJson(id).get("pauseReason").getAsString(), "less than 10 s in the game");
        // 31 s later it has been in the game long enough, but both chamber reports are older than the join
        clk.addAndGet(31_000);
        beat(trip(id, "ready", null), null, null);
        assertEquals("paused", state(id));
        assertEquals("just_joined", eng.tripJson(id).get("pauseReason").getAsString(), "the report is older than the join");
        clk.addAndGet(31_000);
        loaderReport("HomeBot", "pearl-h", "armed", null, false); // the home report first: the far one moves the trip on
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        JsonObject r = beat(trip(id, "ready", null), null, null);
        assertTrue(r.getAsJsonObject("pearlTrip").get("go").getAsBoolean(), r.toString());
    }

    @Test
    void theLocksKeepTwoTripsApartAndOnlyOneTripIsOpenPerBot() {
        world();
        String id = toGo();
        assertThrows(PearlEngine.Refused.class, this::newTrip, "one open trip per bot");
        // a second traveller on the same loader cannot take the locks
        JsonObject tp2 = j("{\"v\":1,\"enabled\":true,\"at\":" + clk.get() + ",\"game\":\"in\",\"joinedAt\":" + (clk.get() - 60_000) + ",\"away\":null,\"pearls\":{\"carried\":3},\"trip\":null,\"local\":null}");
        eng.onTraveller(S, "other", "Bobby", true, tp2, 0);
        String otherSt = eng.saveStation(null, "Other home", S, "minecraft:overworld", "OtherHome", "other").id;
        eng.onLoader("OtherHome", "b-OtherHome", j("{\"v\":1,\"player\":\"OtherHome\",\"at\":" + clk.get() + ",\"dropPearlAfterLoad\":true,\"chambers\":[{\"id\":\"pearl-b\",\"owner\":\"Bobby\",\"ownerKnown\":true,\"presence\":\"armed\"}],\"load\":null}"));
        eng.approveLoader(otherSt, "b-OtherHome");
        eng.setPin("Bobby", "22222222-2222-2222-2222-222222222222");
        eng.onTraveller(S, "other", "Bobby", true, j(tp2.toString().replace("\"enabled\":true", "\"enabled\":true,\"uuid\":\"22222222-2222-2222-2222-222222222222\"")), 0);
        eng.onLoader("FarBot", "b-FarBot", j("{\"v\":1,\"player\":\"FarBot\",\"at\":" + clk.get() + ",\"dropPearlAfterLoad\":true,\"chambers\":[{\"id\":\"pearl-1\",\"owner\":\"Qwerttze\",\"ownerKnown\":true,\"presence\":\"armed\"},"
            + "{\"id\":\"pearl-b\",\"owner\":\"Bobby\",\"ownerKnown\":true,\"presence\":\"armed\"}],\"load\":null}"));
        PearlStore.Trip t2 = eng.createTrip(S, "other", "go", far, true, "website", "owner", "owner", null, clk.get());
        eng.onTraveller(S, "other", "Bobby", true, tp2, 0);
        String rep = "{\"id\":\"" + t2.id + "\",\"step\":\"ready\",\"pause\":null,\"error\":null,\"whispers\":0,\"loadSentAt\":null,\"teleportAt\":null,\"teleportDim\":null,\"rearm\":null,\"pearls\":{\"before\":null,\"after\":null}}";
        eng.onTraveller(S, "other", "Bobby", true, j(tp2.toString().replace("\"trip\":null", "\"trip\":" + rep).replace("{\"v\":1", "{\"v\":1")), 0);
        // (the second trip is for another chamber of the same loader: the loader lock holds it)
        assertEquals("paused", eng.tripJson(t2.id).get("state").getAsString());
        assertEquals("loader_locked", eng.tripJson(t2.id).get("pauseReason").getAsString());
        eng.stopTrip(id);
        assertEquals(0, eng.tripJson(id).getAsJsonArray("locks").size());
    }

    @Test
    void aStopWorksBeforeTheWhisperAndNotAfter() {
        world();
        String early = newTrip();
        beat(null, null, null);
        eng.stopTrip(early);
        assertEquals("cancelled", state(early));
        assertEquals("stopped", code(early));
        assertTrue(beat(null, null, null).getAsJsonObject("pearlTrip").get("cancel").getAsBoolean(), "T is told to stop, once");
        clk.addAndGet(PearlLogic.COOLDOWN_MS + 1_000);
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        loaderReport("HomeBot", "pearl-h", "armed", null, false);
        String id = toGo();
        long sent = clk.get();
        beat(trip(id, "load_sent", null).replace("\"whispers\":0,\"loadSentAt\":null", "\"whispers\":1,\"loadSentAt\":" + sent), null, null);
        PearlEngine.Refused e = assertThrows(PearlEngine.Refused.class, () -> eng.stopTrip(id));
        assertEquals(409, e.status);
        assertTrue(e.getMessage().contains("can't be undone"));
    }

    // ---------- skew, restart, offline ----------

    @Test
    void clocksThirtySecondsApartStillAgreeOnTheArrival() {
        world();
        String id = toGo();
        long skewT = -30_000; // T's clock is 30 s ahead
        long sent = clk.get();
        long tSent = sent - skewT;
        beatAt(tSent, 0, trip(id, "load_sent", null).replace("\"whispers\":0,\"loadSentAt\":null", "\"whispers\":1,\"loadSentAt\":" + tSent), null, null, null);
        clk.addAndGet(3_000);
        loaderReport("FarBot", "pearl-1", "armed", load("loading", clk.get(), null, null, true), true);
        clk.addAndGet(2_000);
        long gone = clk.get();
        long tele = clk.get() + 500;
        clk.addAndGet(1_500);
        loaderReport("FarBot", "pearl-1", "empty", load("loaded", gone - 2_000, gone, clk.get(), false), false);
        long tTele = tele - skewT;
        String arrived = trip(id, "awaiting_pull", null).replace("\"loadSentAt\":null,\"teleportAt\":null,\"teleportDim\":null",
            "\"loadSentAt\":" + tSent + ",\"teleportAt\":" + tTele + ",\"teleportDim\":\"minecraft:the_nether\"");
        JsonObject r = beatAt(clk.get() - skewT, 0, arrived, home, null, null);
        assertEquals("pickup", step(id), "normalised, the times agree: " + r);
    }

    @Test
    void aServerRestartEndsOpenTripsAndPastTheWhisperAlertsTheOwner() {
        world();
        String past = toGo();
        long sent = clk.get();
        beat(trip(past, "load_sent", null).replace("\"whispers\":0,\"loadSentAt\":null", "\"whispers\":1,\"loadSentAt\":" + sent), null, null);
        PearlStore reopened = new PearlStore(dir);
        reopened.load();
        PearlEngine fresh = new PearlEngine(reopened, clk::get);
        JsonObject t = fresh.tripJson(past);
        assertEquals("failed", t.get("state").getAsString());
        assertEquals("arrival_unconfirmed", t.getAsJsonObject("error").get("code").getAsString());
        assertTrue(fresh.view((a, b) -> true, a -> true).getAsJsonArray("alerts").toString().contains("arrival_unconfirmed"), "an owner alert");
    }

    @Test
    void aTripBeforeTheWhisperEndsServerRestartedAfterARestart() {
        world();
        String id = newTrip();
        PearlStore reopened = new PearlStore(dir);
        reopened.load();
        PearlEngine fresh = new PearlEngine(reopened, clk::get);
        assertEquals("server_restarted", fresh.tripJson(id).getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    void aSilentTravellerCancelsABeforeTheWhisperTripAndPausesALaterOneUntilItReturns() {
        world();
        String early = newTrip();
        beat(null, null, null);
        clk.addAndGet(11 * 60_000L);
        loaderReport("FarBot", "pearl-1", "armed", null, false); // any call sweeps
        assertEquals("cancelled", state(early));
        assertEquals("bot_offline", code(early));
        // past the whisper the trip is paused, not failed
        clk.addAndGet(PearlLogic.COOLDOWN_MS + 1_000);
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        loaderReport("HomeBot", "pearl-h", "armed", null, false);
        beat(null, null, null);
        String late = toGo();
        long sent = clk.get();
        beat(trip(late, "load_sent", null).replace("\"whispers\":0,\"loadSentAt\":null", "\"whispers\":1,\"loadSentAt\":" + sent), null, null);
        clk.addAndGet(11 * 60_000L);
        loaderReport("HomeBot", "pearl-h", "armed", null, false);
        assertEquals("paused", state(late));
        assertEquals("bot_offline", eng.tripJson(late).get("pauseReason").getAsString());
    }

    // ---------- orders while away, local commands ----------

    @Test
    void anOrderWhileAwayBringsTheBotHomeWhenAReturnPearlIsArmedElseAlerts() {
        world();
        // T is away at the far station (it went there earlier): a pending order, home chamber armed: an automatic back trip
        JsonObject tp = j("{\"v\":1,\"enabled\":true,\"at\":" + clk.get() + ",\"game\":\"in\",\"joinedAt\":" + joined + ",\"away\":{\"station\":\"" + far + "\",\"trip\":null,\"since\":" + clk.get()
            + "},\"pearls\":{\"carried\":3},\"trip\":null,\"local\":null}");
        eng.onTraveller(S, STASH, T, true, tp, 2);
        JsonObject view = eng.view((a, b) -> true, a -> true);
        JsonObject back = view.getAsJsonArray("trips").get(0).getAsJsonObject();
        assertEquals("back", back.get("action").getAsString());
        assertEquals("system", back.get("origin").getAsString());
        assertEquals(400, back.get("priority").getAsInt());
        assertEquals("high", back.get("band").getAsString());
        // no armed return pearl: the owner is alerted instead
        eng.stopTrip(back.get("id").getAsString());
        clk.addAndGet(400_000);
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        loaderReport("HomeBot", "pearl-h", "empty", null, false);
        eng.onTraveller(S, STASH, T, true, j(tp.toString().replace("\"at\":" + tp.get("at").getAsLong(), "\"at\":" + clk.get())), 2);
        assertTrue(eng.view((a, b) -> true, a -> true).getAsJsonArray("alerts").toString().contains("away_order"));
    }

    @Test
    void anOrderBeforeTheWhisperMakesTheTripWaitAsBusyOrderAndItGoesOnWhenTheOrderIsDone() {
        world();
        String id = newTrip();
        beat(null, null, null);
        eng.onTraveller(S, STASH, T, true, plainTp(), 1);
        assertEquals("paused", state(id));
        assertEquals("busy_order", eng.tripJson(id).get("pauseReason").getAsString());
        assertNull(code(id), "not cancelled");
        // not counted: it stays paused however often T reports while the order waits
        for (int i = 0; i < 5; i++) {
            clk.addAndGet(31_000);
            loaderReport("FarBot", "pearl-1", "armed", null, false);
            loaderReport("HomeBot", "pearl-h", "armed", null, false);
            eng.onTraveller(S, STASH, T, true, plainTp(), 1);
        }
        assertEquals("paused", state(id));
        clk.addAndGet(1_000);
        eng.onTraveller(S, STASH, T, true, plainTp(), 0);
        assertEquals("running", state(id));
    }

    @Test
    void aLocalGoStartsATripOnceAndALocalHomeIsAuditedWithTheClient() {
        world();
        String local = "{\"localId\":\"l-ab12cd34\",\"action\":\"go\",\"station\":\"" + far + "\",\"oneWay\":false}";
        JsonObject r = beatAt(clk.get(), 0, null, null, null, local);
        assertEquals("plugin", eng.view((a, b) -> true, a -> true).getAsJsonArray("trips").get(0).getAsJsonObject().get("origin").getAsString());
        assertEquals("pending", r.getAsJsonObject("pearlTrip").get("state").getAsString());
        beatAt(clk.get(), 0, null, null, null, local);
        assertEquals(1, eng.view((a, b) -> true, a -> true).getAsJsonArray("trips").size(), "the same localId gives the same trip");
        eng.stopTrip(eng.view((a, b) -> true, a -> true).getAsJsonArray("trips").get(0).getAsJsonObject().get("id").getAsString());
        beatAt(clk.get(), 0, null, far, null, "{\"localId\":null,\"action\":\"home\",\"station\":null,\"oneWay\":false}");
        var audits = eng.drainAudits();
        assertEquals(1, audits.size(), "the last trip didn't complete: the bot's home is not honoured");
        assertEquals("pearl.trip", audits.get(0)[0]);
        assertTrue(audits.get(0)[1].contains("plugin") && audits.get(0)[1].contains("go " + STASH), audits.get(0)[1]);
    }

    // ---------- stations, reports ----------

    @Test
    void stationsKeepOneHomePerStashAssignChecksTheOwnerAndReportsAreBoundToThePlayer() {
        world();
        assertEquals(409, assertThrows(PearlEngine.Refused.class, () -> eng.saveStation(null, "Second home", S, "minecraft:overworld", "Other", STASH)).status);
        eng.assign(far, T, "pearl-1");
        assertEquals(400, assertThrows(PearlEngine.Refused.class, () -> eng.assign(far, "Someone", "pearl-1")).status, "not that traveller's pearl");
        assertEquals(400, assertThrows(PearlEngine.Refused.class, () -> eng.assign(far, T, "pearl-zzz")).status, "not reported");
        eng.assign(far, T, null);
        // identity: the transport player is not the report's player
        PearlEngine.Refused e = assertThrows(PearlEngine.Refused.class, () -> eng.onLoader("OtherBot", "b", j("{\"v\":1,\"player\":\"FarBot\",\"at\":1,\"dropPearlAfterLoad\":true,\"chambers\":[],\"load\":null}")));
        assertEquals(409, e.status);
        // a player that is no station's loader is accepted and gets an empty allow list... or the travellers' names: allow is the same
        JsonObject ok = eng.onLoader("Nobody", "b-Nobody", j("{\"v\":1,\"player\":\"Nobody\",\"at\":" + clk.get() + ",\"dropPearlAfterLoad\":false,\"chambers\":[],\"load\":null}"));
        assertTrue(ok.has("allow"));
        // at most 16 such players are kept
        for (int i = 0; i < 30; i++) {
            String p = "Stranger" + (10 + i);
            eng.onLoader(p, "b", j("{\"v\":1,\"player\":\"" + p + "\",\"at\":" + clk.get() + ",\"dropPearlAfterLoad\":false,\"chambers\":[],\"load\":null}"));
        }
        // a station's own loader is still known
        assertTrue(eng.stationJson(eng.view((a, b) -> true, a -> true) == null ? null : storeStation(far)).getAsJsonObject("loaderState").get("online").getAsBoolean());
    }

    PearlStore.Station storeStation(String id) {
        for (PearlStore.Station s : store.stations) if (s.id.equals(id)) return s;
        return null;
    }

    @Test
    void aBrokenChamberStaysBrokenUntilTheOwnerResetsIt() {
        world();
        // two loads that "clicked" while the pearl is still there for an online owner
        for (int n = 0; n < 2; n++) {
            String id = toGo();
            long sent = clk.get();
            beat(trip(id, "load_sent", null).replace("\"whispers\":0,\"loadSentAt\":null", "\"whispers\":1,\"loadSentAt\":" + sent), null, null);
            clk.addAndGet(3_000);
            loaderReport("FarBot", "pearl-1", "armed", load("loaded", clk.get(), null, null, true), false);
            clk.addAndGet(8_000);
            loaderReport("FarBot", "pearl-1", "armed", load("loaded", clk.get() - 8_000, null, null, true), false);
            clk.addAndGet(200_000); // no pull ever: the trip ends at its deadline
            loaderReport("FarBot", "pearl-1", "armed", load("loaded", clk.get() - 208_000, null, null, true), false);
            assertEquals("failed", state(id));
            assertEquals("pull_timeout", code(id));
            clk.addAndGet(PearlLogic.COOLDOWN_MS + 1_000);
            loaderReport("FarBot", "pearl-1", "armed", null, false);
            loaderReport("HomeBot", "pearl-h", "armed", null, false);
            beat(null, null, null);
        }
        String id3 = newTrip();
        beat(null, null, null);
        beat(trip(id3, "ready", null), null, null);
        assertEquals("chamber_broken", code(id3));
        assertTrue(eng.view((a, b) -> true, a -> true).getAsJsonArray("alerts").toString().contains("chamber_broken"));
        // the owner resets it
        eng.resetChamber(far, "pearl-1");
        clk.addAndGet(PearlLogic.COOLDOWN_MS + 1_000);
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        loaderReport("HomeBot", "pearl-h", "armed", null, false);
        beat(null, null, null);
        String id4 = newTrip();
        beat(null, null, null);
        JsonObject r = beat(trip(id4, "ready", null), null, null);
        assertTrue(r.getAsJsonObject("pearlTrip").get("go").getAsBoolean(), r.toString());
    }

    // ---------- review fixes (rev 4) ----------

    JsonObject plainTp() {
        return j("{\"v\":1,\"enabled\":true,\"at\":" + clk.get() + ",\"uuid\":\"" + uuid + "\",\"game\":\"in\",\"joinedAt\":" + joined + ",\"client\":\"plugin\",\"away\":null,"
            + "\"pearls\":{\"carried\":3},\"trip\":null,\"local\":null}");
    }

    JsonObject awayTp(String station) {
        return j("{\"v\":1,\"enabled\":true,\"at\":" + clk.get() + ",\"uuid\":\"" + uuid + "\",\"game\":\"in\",\"joinedAt\":" + joined + ",\"client\":\"plugin\",\"away\":{\"station\":\"" + station
            + "\",\"trip\":null,\"since\":" + clk.get() + "},\"pearls\":{\"carried\":3},\"trip\":null,\"local\":null}");
    }

    String alerts() {
        return eng.view((a, b) -> true, a -> true).getAsJsonArray("alerts").toString();
    }

    String allowNames(JsonObject loaderAnswer) {
        StringBuilder b = new StringBuilder();
        loaderAnswer.getAsJsonArray("allow").forEach(e -> b.append(e.getAsJsonObject().get("name").getAsString()).append(' '));
        return b.toString();
    }

    @Test
    void aNameIsPinnedByTheOwnerAndADifferentReportedUuidIsIgnoredWithAnAlert() {
        world();
        JsonObject ans = loaderReport("FarBot", "pearl-1", "armed", null, false);
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", ans.getAsJsonArray("allow").get(0).getAsJsonObject().get("uuid").getAsString());
        String pinned = uuid;
        uuid = "11111111-2222-3333-4444-555555555555";
        beat(null, null, null);
        assertTrue(alerts().contains("uuid_mismatch"));
        assertEquals(409, assertThrows(PearlEngine.Refused.class, this::newTrip).status, "a bot with another uuid can't start a trip");
        ans = loaderReport("FarBot", "pearl-1", "armed", null, false);
        assertEquals(pinned, ans.getAsJsonArray("allow").get(0).getAsJsonObject().get("uuid").getAsString(), "the pin wins");
        PearlStore reopened = new PearlStore(dir);
        reopened.load();
        assertEquals(pinned, reopened.pins.get("qwerttze"));
        // the owner supplies the new uuid; the heartbeat never does
        assertEquals(400, assertThrows(PearlEngine.Refused.class, () -> eng.setPin(T, "nope")).status);
        eng.setPin(T, uuid);
        assertFalse(alerts().contains("uuid_mismatch"));
        assertEquals(uuid, store.pins.get("qwerttze"));
        beat(null, null, null);
        assertNotNull(newTrip());
    }

    @Test
    void aHeartbeatNeverCreatesAPinAndOnlyApprovedNamesGetOne() {
        world();
        eng.onTraveller(S, "other", "Bobby", true, j("{\"v\":1,\"enabled\":true,\"at\":" + clk.get() + ",\"uuid\":\"22222222-2222-2222-2222-222222222222\",\"game\":\"in\",\"joinedAt\":null,\"away\":null,\"pearls\":{\"carried\":3},\"trip\":null,\"local\":null}"), 0);
        assertFalse(store.pins.containsKey("bobby"), "a heartbeat pins nothing");
        assertEquals(400, assertThrows(PearlEngine.Refused.class, () -> eng.setPin("Bobby", "22222222-2222-2222-2222-222222222222")).status, "not approved yet");
        eng.allowTraveller(far, "Bobby", true);
        eng.setPin("Bobby", "22222222-2222-2222-2222-222222222222");
        assertEquals("22222222-2222-2222-2222-222222222222", store.pins.get("bobby"));
        // a bot nobody pinned can't travel: the loader would have no uuid to check
        store.pins.remove("qwerttze");
        String id = newTrip();
        beat(null, null, null);
        beat(trip(id, "ready", null), null, null);
        assertEquals("uuid_unpinned", code(id));
    }

    @Test
    void anAllowedTravellerIsKnownOnlyToThatStationsLoader() {
        world();
        eng.onTraveller(S, "other", "Bobby", true, j("{\"v\":1,\"enabled\":true,\"at\":" + clk.get() + ",\"uuid\":\"22222222-2222-2222-2222-222222222222\",\"game\":\"in\",\"joinedAt\":null,\"away\":null,\"pearls\":{\"carried\":3},\"trip\":null,\"local\":null}"), 0);
        eng.allowTraveller(far, "Bobby", true);
        eng.setPin("Bobby", "22222222-2222-2222-2222-222222222222");
        JsonObject farAns = loaderReport("FarBot", "pearl-1", "armed", null, false);
        assertTrue(allowNames(farAns).contains("bobby"), allowNames(farAns));
        assertTrue(allowNames(farAns).contains("qwerttze"), "a home-stash traveller is known to every approved loader");
        JsonObject homeAns = loaderReport("HomeBot", "pearl-h", "armed", null, false);
        assertFalse(allowNames(homeAns).contains("bobby"), allowNames(homeAns));
        eng.allowTraveller(far, "Bobby", false);
        assertFalse(allowNames(loaderReport("FarBot", "pearl-1", "armed", null, false)).contains("bobby"));
        assertTrue(storeStation(far).allowed.isEmpty());
        for (int i = 0; i < 16; i++) eng.allowTraveller(far, "Player" + (10 + i), true);
        assertEquals(400, assertThrows(PearlEngine.Refused.class, () -> eng.allowTraveller(far, "Onetoomany", true)).status);
    }

    @Test
    void allowListsOnlyApprovedTravellersAndOnlyGoesToApprovedStationLoaders() {
        world();
        eng.onTraveller(S, "other", "Bobby", true, j("{\"v\":1,\"enabled\":true,\"at\":" + clk.get() + ",\"uuid\":\"22222222-2222-2222-2222-222222222222\",\"game\":\"in\",\"joinedAt\":null,\"away\":null,\"pearls\":{\"carried\":3},\"trip\":null,\"local\":null}"), 0);
        JsonObject ans = loaderReport("FarBot", "pearl-1", "armed", null, false);
        assertTrue(allowNames(ans).contains("qwerttze"), allowNames(ans));
        assertFalse(allowNames(ans).contains("bobby"), allowNames(ans));
        eng.assign(far, T, "pearl-1");
        assertTrue(allowNames(loaderReport("FarBot", "pearl-1", "armed", null, false)).contains("qwerttze"));
        JsonObject stranger = eng.onLoader("Nobody", "b-Nobody", j("{\"v\":1,\"player\":\"Nobody\",\"at\":" + clk.get() + ",\"dropPearlAfterLoad\":false,\"chambers\":[],\"load\":null}"));
        assertEquals(0, stranger.getAsJsonArray("allow").size());
        // a station's loader that the owner has not approved gets nothing either
        String third = eng.saveStation(null, "Third", S, "minecraft:overworld", "ThirdBot", null).id;
        String tb = "{\"v\":1,\"player\":\"ThirdBot\",\"at\":" + clk.get() + ",\"dropPearlAfterLoad\":false,\"chambers\":[],\"load\":null}";
        assertEquals(0, eng.onLoader("ThirdBot", "b-ThirdBot", j(tb)).getAsJsonArray("allow").size());
        assertEquals("b-ThirdBot", eng.stationJson(storeStation(third), true).getAsJsonObject("loaderState").getAsJsonArray("pending").get(0).getAsJsonObject().get("source").getAsString());
        eng.approveLoader(third, "b-ThirdBot");
        assertTrue(eng.onLoader("ThirdBot", "b-ThirdBot", j(tb)).getAsJsonArray("allow").size() >= 1);
    }

    @Test
    void aLoaderNeedsTheOwnersApprovalAndAnotherSourceIs409UntilApproved() {
        start();
        far = eng.saveStation(null, "Nether hub", S, "minecraft:the_nether", "FarBot", null).id;
        String body = "{\"v\":1,\"player\":\"FarBot\",\"at\":" + clk.get() + ",\"dropPearlAfterLoad\":true,\"chambers\":[{\"id\":\"pearl-1\",\"owner\":\"Qwerttze\",\"ownerKnown\":true,\"presence\":\"armed\",\"since\":1}],\"load\":null}";
        // the first reporter is pending: shown, never used
        assertEquals(0, eng.onLoader("FarBot", "b-FarBot", j(body)).getAsJsonArray("allow").size());
        JsonObject ls = eng.stationJson(storeStation(far), true).getAsJsonObject("loaderState");
        assertFalse(ls.get("approved").getAsBoolean());
        assertEquals("b-FarBot", ls.getAsJsonArray("pending").get(0).getAsJsonObject().get("source").getAsString());
        assertEquals("unknown", eng.stationJson(storeStation(far), true).getAsJsonArray("chambers").get(0).getAsJsonObject().get("state").getAsString(), "display only");
        assertEquals(409, assertThrows(PearlEngine.Refused.class, () -> eng.approveLoader(far, "b-Other")).status, "only the source shown");
        eng.approveLoader(far, "b-FarBot");
        assertEquals("armed", eng.stationJson(storeStation(far), true).getAsJsonArray("chambers").get(0).getAsJsonObject().get("state").getAsString());
        // another source: 409 with the way out, shown as pending
        PearlEngine.Refused r = assertThrows(PearlEngine.Refused.class, () -> eng.onLoader("FarBot", "hb:srv/other", j(body)));
        assertEquals(409, r.status);
        assertTrue(r.getMessage().contains("owner can approve the new source on the station"), r.getMessage());
        assertEquals("hb:srv/other", eng.stationJson(storeStation(far), true).getAsJsonObject("loaderState").getAsJsonArray("pending").get(0).getAsJsonObject().get("source").getAsString());
        eng.onLoader("FarBot", "b-FarBot", j(body)); // the approved source still works
        // the approval is stored and survives a restart and a station edit
        PearlStore reopened = new PearlStore(dir);
        reopened.load();
        assertEquals("b-FarBot", reopened.stations.get(0).binding);
        eng.saveStation(far, "Renamed", S, "minecraft:the_nether", "FarBot", null);
        assertEquals("b-FarBot", storeStation(far).binding, "an edit keeps the approval");
        // a new loader name starts over
        eng.saveStation(far, "Renamed", S, "minecraft:the_nether", "NewBot", null);
        assertNull(storeStation(far).binding);
        // the owner approves a new source for a bound loader
        eng.saveStation(far, "Renamed", S, "minecraft:the_nether", "FarBot", null);
        eng.onLoader("FarBot", "b-FarBot", j(body));
        eng.approveLoader(far, "b-FarBot");
        assertThrows(PearlEngine.Refused.class, () -> eng.onLoader("FarBot", "hb:srv/other", j(body)));
        eng.approveLoader(far, "hb:srv/other");
        assertEquals("hb:srv/other", storeStation(far).binding);
    }

    @Test
    void theChambersOwnerMustBeTheTravellerAndAnAssignmentOfAnotherOwnerIsDropped() {
        world();
        eng.assign(far, T, "pearl-1");
        // the far chamber now shows another owner: the assignment goes, the trip is refused
        chOwner = "Someone";
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        assertTrue(storeStation(far).assign.isEmpty());
        chOwner = T;
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        // an assigned chamber that someone else owns (assigned while it was T's)
        eng.assign(far, T, "pearl-1");
        // the return pearl is someone else's: refused too
        chOwner = "Someone";
        loaderReport("HomeBot", "pearl-h", "armed", null, false);
        storeStation(home).assign.put("qwerttze", "pearl-h"); // (as it was before the chamber changed hands)
        chOwner = T;
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        String id = newTrip();
        beat(null, null, null);
        beat(trip(id, "ready", null), null, null);
        assertEquals("no_pearl_assigned", code(id));
    }

    @Test
    void aBotsOwnHomeNeedsACompletedLastTripNoOpenTripAndNoAlert() {
        world();
        String homeCmd = "{\"localId\":null,\"action\":\"home\",\"station\":null,\"oneWay\":false}";
        // the last trip was stopped, not completed: not honoured
        PearlStore.Trip done = eng.createTrip(S, STASH, "go", far, true, "website", "owner", "owner", null, clk.get());
        eng.stopTrip(done.id);
        JsonObject r = eng.onTraveller(S, STASH, T, true, j(awayTp(far).toString().replace("\"local\":null", "\"local\":" + homeCmd)), 0);
        assertFalse(r.has("pearlAwayClear"), "no completed trip");
        assertEquals(0, eng.drainAudits().size());
        // a completed trip, then away and home: honoured and audited
        done.state = "completed";
        done.step = "done";
        done.finishedAt = clk.get();
        r = eng.onTraveller(S, STASH, T, true, j(awayTp(far).toString().replace("\"local\":null", "\"local\":" + homeCmd)), 0);
        assertTrue(r.has("pearlAwayClear"));
        var audits = eng.drainAudits();
        assertEquals(1, audits.size());
        assertTrue(audits.get(0)[0].equals("pearl.away.clear") && audits.get(0)[1].contains("plugin"), audits.get(0)[1]);
        eng.onTraveller(S, STASH, T, true, plainTp(), 0); // T acknowledges (away: null)
        // an alert for the stash blocks it
        loaderReport("HomeBot", "pearl-h", "empty", null, false);
        eng.onTraveller(S, STASH, T, true, awayTp(far), 1);
        assertTrue(alerts().contains("away_order"));
        r = eng.onTraveller(S, STASH, T, true, j(awayTp(far).toString().replace("\"local\":null", "\"local\":" + homeCmd)), 0);
        assertFalse(r.has("pearlAwayClear"), "an alert is open");
        assertTrue(alerts().contains("away_order"));
        // not away on the server's side: ignored
        eng.clearAway(S, STASH);
        eng.onTraveller(S, STASH, T, true, plainTp(), 0);
        r = eng.onTraveller(S, STASH, T, true, j(plainTp().toString().replace("\"local\":null", "\"local\":" + homeCmd)), 0);
        assertFalse(r.has("pearlAwayClear"));
    }

    @Test
    void theAutomaticWayBackBacksOffAndStopsAfterThreeFailures() {
        world();
        eng.onTraveller(S, STASH, T, true, awayTp(far), 1);
        assertEquals(1, eng.view((a, b) -> true, a -> true).getAsJsonArray("trips").size());
        eng.stopTrip(eng.view((a, b) -> true, a -> true).getAsJsonArray("trips").get(0).getAsJsonObject().get("id").getAsString());
        eng.onTraveller(S, STASH, T, true, awayTp(far), 1);
        assertEquals(1, eng.view((a, b) -> true, a -> true).getAsJsonArray("trips").size(), "60 s back-off");
        long[] waits = {61_000, 121_000};
        int expected = 1;
        for (long w : waits) {
            clk.addAndGet(w);
            loaderReport("FarBot", "pearl-1", "armed", null, false);
            loaderReport("HomeBot", "pearl-h", "armed", null, false);
            eng.onTraveller(S, STASH, T, true, awayTp(far), 1);
            expected++;
            var trips = eng.view((a, b) -> true, a -> true).getAsJsonArray("trips");
            assertEquals(expected, trips.size());
            eng.stopTrip(trips.get(0).getAsJsonObject().get("id").getAsString());
        }
        assertTrue(alerts().contains("away_order"), "three failures: the owner is told");
        clk.addAndGet(3_600_000);
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        loaderReport("HomeBot", "pearl-h", "armed", null, false);
        eng.onTraveller(S, STASH, T, true, awayTp(far), 1);
        assertEquals(3, eng.view((a, b) -> true, a -> true).getAsJsonArray("trips").size(), "no fourth try");
        // a back trip that completes clears the order alert (the owner says home too)
        eng.clearAway(S, STASH);
        clk.addAndGet(1_000);
        loaderReport("FarBot", "pearl-1", "armed", null, false);
        loaderReport("HomeBot", "pearl-h", "armed", null, false);
        eng.onTraveller(S, STASH, T, true, awayTp(far), 1);
        assertEquals(4, eng.view((a, b) -> true, a -> true).getAsJsonArray("trips").size(), "the owner's clear starts over");
    }

    @Test
    void aGoWithoutTheWhisperEndsAfterTwoMinutesWithGoTimeout() {
        world();
        String id = toGo();
        clk.addAndGet(119_000);
        beat(trip(id, "ready", null), null, null);
        assertEquals("running", state(id));
        clk.addAndGet(2_000);
        JsonObject r = beat(trip(id, "ready", null), null, null);
        assertEquals("failed", state(id));
        assertEquals("go_timeout", code(id));
        assertEquals(0, eng.tripJson(id).getAsJsonArray("locks").size(), "released");
        assertFalse(r.getAsJsonObject("pearlTrip").get("go").getAsBoolean(), "finish clears go");
    }

    @Test
    void aLateTeleportAfterAFailureIsAcceptedForFiveMinutes() {
        world();
        String id = toGo();
        long sent = clk.get();
        beat(trip(id, "load_sent", null).replace("\"whispers\":0,\"loadSentAt\":null", "\"whispers\":1,\"loadSentAt\":" + sent), null, null);
        clk.addAndGet(16_000);
        beat(trip(id, "awaiting_pull", null).replace("\"whispers\":0", "\"whispers\":1"), home, null);
        assertEquals("loader_no_answer", code(id));
        // the pull comes after all
        clk.addAndGet(30_000);
        long tele = clk.get();
        JsonObject r = beat(trip(id, "awaiting_pull", null).replace("\"teleportAt\":null", "\"teleportAt\":" + tele).replace("\"whispers\":0", "\"whispers\":1"), home, null);
        assertEquals("unexpected_location", code(id));
        assertTrue(r.getAsJsonObject("pearlTrip").get("hold").getAsBoolean());
        assertTrue(alerts().contains("unexpected_location"));
    }

    @Test
    void aTeleportReportedMoreThanFiveMinutesAfterTheFailureChangesNothing() {
        world();
        String id = toGo();
        long sent = clk.get();
        beat(trip(id, "load_sent", null).replace("\"whispers\":0,\"loadSentAt\":null", "\"whispers\":1,\"loadSentAt\":" + sent), null, null);
        clk.addAndGet(16_000);
        beat(trip(id, "awaiting_pull", null).replace("\"whispers\":0", "\"whispers\":1"), home, null);
        assertEquals("loader_no_answer", code(id));
        clk.addAndGet(PearlLogic.COOLDOWN_MS + 1_000);
        beat(trip(id, "awaiting_pull", null).replace("\"teleportAt\":null", "\"teleportAt\":" + clk.get()).replace("\"whispers\":0", "\"whispers\":1"), home, null);
        assertEquals("loader_no_answer", code(id));
        assertFalse(alerts().contains("unexpected_location"));
    }

    @Test
    void aTripPastTheWhisperThatNobodyAnswersForThirtyMinutesEndsWithAnAlert() {
        world();
        String id = toGo();
        long sent = clk.get();
        beat(trip(id, "load_sent", null).replace("\"whispers\":0,\"loadSentAt\":null", "\"whispers\":1,\"loadSentAt\":" + sent), null, null);
        clk.addAndGet(31 * 60_000L);
        loaderReport("HomeBot", "pearl-h", "armed", null, false); // another loader's report sweeps
        assertEquals("failed", state(id));
        assertEquals("arrival_unconfirmed", code(id));
        assertTrue(alerts().contains("arrival_unconfirmed"));
    }

    @Test
    void readingTheViewNeverCancelsATripAndTheSweepPrunesTheLoads() {
        world();
        String id = newTrip();
        beat(null, null, null);
        clk.addAndGet(11 * 60_000L);
        eng.view((a, b) -> true, a -> true);
        assertTrue(eng.tripJson(id).get("finishedAt").getAsLong() == 0, "a GET does not sweep: " + state(id));
    }

    @Test
    void aStationsLoaderCantChangeWhileATripUsesItAndTheBrowserViewHasNoBotId() {
        world();
        String id = newTrip();
        assertEquals(409, assertThrows(PearlEngine.Refused.class, () -> eng.saveStation(far, "Nether hub", S, "minecraft:the_nether", "Other", null)).status);
        eng.stopTrip(id);
        eng.saveStation(far, "Nether hub", S, "minecraft:the_nether", "Other", null);
        assertFalse(eng.stationJson(storeStation(far), true).getAsJsonObject("loaderState").has("botId"));
        assertFalse(eng.stationJson(storeStation(far), false).toString().contains("b-FarBot"), "only the owner sees the source");
    }

    @Test
    void pearlJsonIsCheckedAgainstTheFormatsWhenLoaded() throws Exception {
        String good = "{\"version\":1,\"stations\":[{\"id\":\"ps-0123abcd\",\"name\":\"x\",\"server\":\"srv\",\"dim\":\"minecraft:overworld\",\"loader\":{\"player\":\"FarBot\"},"
            + "\"stash\":null,\"assign\":{},\"broken\":[]}],\"trips\":[],\"pins\":{\"qwerttze\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\"}}";
        Path file = dir.resolve("pearl.json");
        Files.writeString(file, good);
        PearlStore ok = new PearlStore(dir);
        ok.load();
        assertFalse(ok.disabled());
        assertEquals(1, ok.stations.size());
        for (String bad : new String[]{good.replace("ps-0123abcd", "ps-zzzz"), good.replace("FarBot", "Far Bot!"), good.replace("minecraft:overworld", "no dim"),
            good.replace("\"assign\":{}", "\"assign\":{\"qwerttze\":\"bad id!\"}"), good.replace("069a79f4-44e9-4726-a5be-fca90e38aaf5", "not-a-uuid")}) {
            Files.writeString(file, bad);
            PearlStore off = new PearlStore(dir);
            off.load();
            assertTrue(off.disabled(), bad);
        }
    }

    @Test
    void theFinalNoticeAlsoGoesWhenTheHeartbeatHasNoPearlTravelKeyAndThePageShowsPinsToTheOwnerOnly() {
        world();
        String id = newTrip();
        beat(null, null, null);
        eng.stopTrip(id);
        assertTrue(beat(null, null, null).has("pearlTrip"), "told once");
        // a heartbeat without the pearlTravel key counts as "no longer carries the trip"
        assertTrue(eng.onTraveller(S, STASH, T, false, null, 0).has("pearlTrip") == false);
        assertFalse(beat(null, null, null).has("pearlTrip"));
        // owner-only fields
        JsonObject ownerView = eng.view((a, b) -> true, a -> true, true);
        JsonObject other = eng.view((a, b) -> true, a -> true, false);
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", ownerView.getAsJsonArray("travellers").get(0).getAsJsonObject().get("pinnedUuid").getAsString());
        assertFalse(other.toString().contains("069a79f4"), "no uuid for anyone but the owner");
        assertFalse(other.getAsJsonArray("stations").get(0).getAsJsonObject().getAsJsonObject("loaderState").has("pending"));
        assertFalse(eng.view((a, b) -> true, a -> true).toString().contains("069a79f4"), "the two-argument view is the non-owner one");
        assertFalse(eng.stationJson(storeStation(far)).getAsJsonObject("loaderState").has("source"), "so is stationJson(st)");
    }

    @Test
    void upTo4PendingSourcesAreKeptOldestDroppedAndTheOwnerCanDismissOne() {
        start();
        far = eng.saveStation(null, "Nether hub", S, "minecraft:the_nether", "FarBot", null).id;
        String body = "{\"v\":1,\"player\":\"FarBot\",\"at\":" + clk.get() + ",\"dropPearlAfterLoad\":true,\"chambers\":[],\"load\":null}";
        for (String src : new String[]{"hb:srv/one", "hb:srv/two", "bot:b3", "hb:srv/four", "hb:srv/five"}) {
            clk.addAndGet(1_000);
            eng.onLoader("FarBot", src, j(body));
        }
        var pend = eng.stationJson(storeStation(far), true).getAsJsonObject("loaderState").getAsJsonArray("pending");
        assertEquals(4, pend.size());
        assertEquals("hb:srv/two", pend.get(0).getAsJsonObject().get("source").getAsString(), "the oldest was dropped");
        assertEquals("two", pend.get(0).getAsJsonObject().get("stash").getAsString(), "hb sources show their stash");
        assertTrue(pend.get(1).getAsJsonObject().get("stash").isJsonNull(), "a bot source has no stash");
        assertTrue(pend.get(3).getAsJsonObject().get("lastSeen").getAsLong() > pend.get(0).getAsJsonObject().get("lastSeen").getAsLong());
        // a source that reports again moves to the end instead of taking a second slot
        clk.addAndGet(1_000);
        eng.onLoader("FarBot", "hb:srv/two", j(body));
        pend = eng.stationJson(storeStation(far), true).getAsJsonObject("loaderState").getAsJsonArray("pending");
        assertEquals(4, pend.size());
        assertEquals("hb:srv/two", pend.get(3).getAsJsonObject().get("source").getAsString());
        // dismiss one; approval goes by source string
        eng.dismissSource(far, "bot:b3");
        assertEquals(3, eng.stationJson(storeStation(far), true).getAsJsonObject("loaderState").getAsJsonArray("pending").size());
        assertEquals(409, assertThrows(PearlEngine.Refused.class, () -> eng.dismissSource(far, "bot:b3")).status);
        assertEquals(409, assertThrows(PearlEngine.Refused.class, () -> eng.approveLoader(far, "bot:b3")).status, "dismissed");
        eng.approveLoader(far, "hb:srv/four");
        assertEquals("hb:srv/four", storeStation(far).binding);
        assertTrue(eng.stationJson(storeStation(far), true).getAsJsonObject("loaderState").getAsJsonArray("pending").size() == 0, "approval clears the others");
    }

    @Test
    void twoStashesReportingDifferentUuidsForOneNameAreFlaggedForTheOwner() {
        world();
        eng.onTraveller(S, "other", T, true, j("{\"v\":1,\"enabled\":true,\"at\":" + clk.get() + ",\"uuid\":\"33333333-3333-3333-3333-333333333333\",\"game\":\"in\",\"joinedAt\":null,"
            + "\"away\":null,\"pearls\":{\"carried\":3},\"trip\":null,\"local\":null}"), 0);
        var ts = eng.view((a, b) -> true, a -> true, true).getAsJsonArray("travellers");
        for (int i = 0; i < ts.size(); i++) assertTrue(ts.get(i).getAsJsonObject().get("uuidConflict").getAsBoolean(), ts.toString());
        assertEquals(uuid, ts.get(0).getAsJsonObject().get("reportedUuid").getAsString().equals(uuid) ? uuid : ts.get(1).getAsJsonObject().get("reportedUuid").getAsString());
        assertFalse(eng.view((a, b) -> true, a -> true).toString().contains("33333333"), "not for non-owners");
    }

    @Test
    void theMismatchTextSaysPinnedByTheOwner() {
        assertTrue(PearlEngine.codeText("uuid_mismatch").contains("pinned by the owner"));
    }
}

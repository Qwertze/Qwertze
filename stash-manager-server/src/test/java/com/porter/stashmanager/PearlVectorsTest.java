package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pearl-vectors.json (PEARL-CONTRACT v1.0 rev 3), shared with the plugin and the addon: the traveller state machine (a Java reference
 * runs every case step by step), and what the server derives from the loader's reports.
 */
class PearlVectorsTest {
    static JsonObject vectors() throws Exception {
        try (InputStream in = PearlVectorsTest.class.getResourceAsStream("/pearl/pearl-vectors.json")) {
            assertNotNull(in);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** The traveller's state machine as the contract describes it (PEARL-CONTRACT "Who does what" and "Failures after load_sent"). */
    static final class Machine {
        static final Set<String> NONCOUNT = Set.of("busy_order", "busy_repair", "sorter_busy");
        final String action;
        String step = "idle", state = "pending", code, warning, pearl;
        int whispers, tries;
        boolean away, watch;

        Machine(String action) {
            this.action = action;
            this.away = action.equals("back");
        }

        List<String> failSent(boolean stillHere, String why, boolean report) {
            state = "failed";
            List<String> a = new ArrayList<>();
            if (stillHere) {
                code = why;
                away = false;
                watch = true;
                a.add("awayOff");
                a.add("watch");
                if (report) a.add("report:error:" + why);
            } else {
                code = "unexpected_location";
                watch = false;
                a.add("hold");
                a.add("report:error:unexpected_location");
            }
            return a;
        }

        static boolean b(JsonObject o, String f) {
            return o.has(f) && !o.get(f).isJsonNull() && o.get(f).getAsBoolean();
        }

        List<String> on(JsonObject ev) {
            String e = ev.get("e").getAsString();
            List<String> a = new ArrayList<>();
            switch (e) {
                case "offer" -> {
                    if (step.equals("idle")) {
                        step = "precheck";
                        state = "running";
                        a.add("runPrecheck");
                    }
                }
                case "precheck" -> {
                    if (step.equals("precheck") && state.equals("running")) {
                        if (ev.get("ok").getAsBoolean()) {
                            step = "ready";
                            a.add("report:ready");
                        } else {
                            state = "paused";
                            code = ev.get("code").getAsString();
                            if (!NONCOUNT.contains(code)) tries++;
                            a.add("report:pause:" + code);
                        }
                    }
                }
                case "retryTick" -> {
                    if (state.equals("paused") && step.equals("precheck") && (NONCOUNT.contains(code) || tries < 3)) {
                        state = "running";
                        a.add("runPrecheck");
                    }
                }
                case "armHomeDone" -> {
                    if (step.equals("arm_home")) {
                        step = "ready";
                        a.add("report:ready");
                    }
                }
                case "reply" -> {
                    if (b(ev, "cancel") && List.of("precheck", "arm_home", "ready").contains(step) && (state.equals("running") || state.equals("paused"))) {
                        state = "cancelled";
                        a.add("report:cancelled");
                    } else if (ev.has("state") && ev.get("state").getAsString().equals("paused") && List.of("precheck", "ready").contains(step)) {
                        state = "paused";
                        code = ev.get("code").getAsString();
                    } else if (b(ev, "armHome") && List.of("precheck", "ready").contains(step)) {
                        step = "arm_home";
                        a.add("armHome");
                    } else if (b(ev, "go") && step.equals("ready") && (state.equals("running") || state.equals("paused"))) {
                        step = "awaiting_pull";
                        state = "running";
                        whispers = 1;
                        pearl = ev.get("pearlId").getAsString();
                        if (!away) a.add("awayOn");
                        a.add("whisper:load " + pearl);
                        a.add("report:load_sent");
                        away = true;
                    } else if (ev.has("state") && ev.get("state").getAsString().equals("failed") && step.equals("awaiting_pull") && state.equals("running") && !b(ev, "hold")) {
                        a = failSent(!ev.has("stillHere") || ev.get("stillHere").getAsBoolean(), ev.get("code").getAsString(), false);
                    } else if (b(ev, "retry") && step.equals("awaiting_pull") && whispers < 2) {
                        whispers = 2;
                        a.add("whisper:load " + pearl);
                        a.add("report:load_sent");
                    } else if (ev.has("pullDeadlineAt") && step.equals("awaiting_pull")) {
                        a.add("setDeadline");
                    } else if (b(ev, "hold") && step.equals("arrived")) {
                        state = "failed";
                        code = ev.get("code").getAsString();
                        a.add("hold");
                    } else if (b(ev, "rearm") && step.equals("arrived")) {
                        step = "pickup";
                        a.add("pickup");
                    }
                }
                case "teleport" -> {
                    boolean tele = b(ev, "jump") || b(ev, "dimChange");
                    if (step.equals("awaiting_pull") && state.equals("running") && tele) {
                        step = "arrived";
                        a.add("report:arrived");
                    } else if (state.equals("failed") && watch && tele) {
                        code = "unexpected_location";
                        away = true;
                        watch = false;
                        a.add("awayOn");
                        a.add("hold");
                        a.add("report:error:unexpected_location");
                    }
                }
                case "pullDeadline" -> {
                    if (step.equals("awaiting_pull") && state.equals("running")) a = failSent(!ev.has("stillHere") || ev.get("stillHere").getAsBoolean(), "pull_timeout", true);
                }
                case "watchEnd" -> watch = false;
                case "reconnect" -> {
                    if (step.equals("awaiting_pull") && state.equals("running") && b(ev, "teleported")) {
                        step = "arrived";
                        a.add("report:arrived");
                    }
                }
                case "pickupDone" -> {
                    if (step.equals("pickup")) {
                        step = "rearm";
                        a.add("rearm");
                    }
                }
                case "rearmResult" -> {
                    if (step.equals("rearm")) {
                        step = "done";
                        state = "completed";
                        String r = ev.get("result").getAsString();
                        if (r.equals("SUCCESS") || r.equals("PARTIAL")) {
                            if (action.equals("back")) {
                                away = false;
                                a.add("awayOff");
                                a.add("requestRescan");
                            }
                            a.add("report:done");
                        } else {
                            warning = "rearm_failed";
                            a.add("report:done");
                            a.add("alert:rearm_failed");
                        }
                    }
                }
                default -> {
                    // disconnect and anything else: nothing happens
                }
            }
            return a;
        }
    }

    @Test
    void theTravellerMachineGivesTheSharedVectors() throws Exception {
        JsonArray cases = vectors().getAsJsonObject("machine").getAsJsonArray("cases");
        assertTrue(cases.size() >= 20, "the contract's cases are all there");
        for (JsonElement ce : cases) {
            JsonObject c = ce.getAsJsonObject();
            Machine m = new Machine(c.get("action").getAsString());
            int i = 0;
            for (JsonElement se : c.getAsJsonArray("steps")) {
                JsonObject s = se.getAsJsonObject();
                List<String> got = m.on(s.getAsJsonObject("event"));
                JsonObject x = s.getAsJsonObject("expect");
                String where = c.get("name").getAsString() + " #" + i++;
                assertEquals(x.get("step").getAsString(), m.step, where + " step");
                assertEquals(x.get("state").getAsString(), m.state, where + " state");
                assertEquals(x.get("whispers").getAsInt(), m.whispers, where + " whispers");
                assertEquals(x.get("away").getAsBoolean(), m.away, where + " away");
                assertEquals(x.get("watch").getAsBoolean(), m.watch, where + " watch");
                List<String> actions = new ArrayList<>();
                x.getAsJsonArray("actions").forEach(a -> actions.add(a.getAsString()));
                assertEquals(actions, got, where + " actions");
                if (x.has("tries")) assertEquals(x.get("tries").getAsInt(), m.tries, where + " tries");
                if (x.has("code")) assertEquals(x.get("code").getAsString(), m.code, where + " code");
                if (x.has("warning")) assertEquals(x.get("warning").getAsString(), m.warning, where + " warning");
            }
        }
    }

    @Test
    void chamberStatesArrivalCountsAndBodiesFollowTheVectors() throws Exception {
        JsonObject r = vectors().getAsJsonObject("reports");
        for (JsonElement ce : r.getAsJsonObject("chamberState").getAsJsonArray("cases")) {
            JsonObject c = ce.getAsJsonObject();
            assertEquals(c.get("state").getAsString(), PearlLogic.chamberState(c.get("presence").getAsString(), c.get("ageMs").getAsLong(),
                c.get("broken").getAsBoolean(), c.get("ownerGame").getAsString()), c.toString());
        }
        for (JsonElement ce : r.getAsJsonObject("arrival").getAsJsonObject().getAsJsonArray("cases")) {
            JsonObject c = ce.getAsJsonObject();
            Long loading = c.get("loadingAt").isJsonNull() ? null : c.get("loadingAt").getAsLong();
            Long gone = c.get("pearlGoneAt").isJsonNull() ? null : c.get("pearlGoneAt").getAsLong();
            Long near = c.get("requesterNearAt").isJsonNull() ? null : c.get("requesterNearAt").getAsLong();
            assertEquals(c.get("confirmed").getAsBoolean(), PearlLogic.arrivalConfirmed(c.get("loadSentAt").getAsLong(), loading,
                c.get("teleportAt").getAsLong(), gone, near, c.get("tSkewMs").getAsLong(), c.get("lSkewMs").getAsLong()), c.get("name").getAsString());
        }
        for (JsonElement ce : r.getAsJsonObject("pearlCount").getAsJsonArray("cases")) {
            JsonObject c = ce.getAsJsonObject();
            assertEquals(c.get("warning").getAsBoolean(), PearlLogic.countWarning(c.get("before").getAsInt(), c.get("dropAfterLoad").getAsBoolean(),
                c.get("armedHome").getAsBoolean(), c.get("after").getAsInt()), c.toString());
        }
        for (JsonElement ce : r.getAsJsonObject("bodies").getAsJsonArray("cases")) {
            JsonObject c = ce.getAsJsonObject();
            if (c.get("ok").getAsBoolean()) PearlBodies.loader(c.getAsJsonObject("body"));
            else assertThrows(IllegalArgumentException.class, () -> PearlBodies.loader(c.getAsJsonObject("body")), c.get("name").getAsString());
        }
    }

    @Test
    void theClockSkewWorksOnTheServersTimes() {
        // 30 s ahead on T's clock: the same moment, normalised, agrees with L.
        assertTrue(PearlLogic.arrivalConfirmed(1_030_000L, null, 1_034_000L, 1_003_500L, 1_005_000L, -30_000, 0));
        assertFalse(PearlLogic.arrivalConfirmed(1_030_000L, null, 1_034_000L, 1_003_500L, 1_005_000L, 0, 0));
        assertEquals(1_150_000L, PearlLogic.pullDeadline(1_000_000L, null));
        assertEquals(1_210_000L, PearlLogic.pullDeadline(1_000_000L, 1_060_000L));
        assertEquals(1_150_000L, PearlLogic.pullDeadline(1_000_000L, 990_000L), "never earlier than the whisper + 150 s");
    }
}

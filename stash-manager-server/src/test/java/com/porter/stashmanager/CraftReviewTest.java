package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.stashmanager.planner.PlannerFixtures;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Hardening of the crafting features: table positions, the settings reply, claims, planning limits, truncation. */
class CraftReviewTest extends CraftHarness {
    String tableBody(int x, int y, int z, String dim) {
        return "{" + KEY + ",\"table\":{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + ",\"dimension\":\"" + dim + "\"}}";
    }

    Res setTableRaw(int x, int y, int z, String dim) throws Exception {
        return post(owner, "/api/v1/craft/table", tableBody(x, y, z, dim));
    }

    // ---------- the table position ----------

    @Test
    void theHeightIsLimited() throws Exception {
        start();
        anchor(10, 64, 10, "minecraft:overworld");
        for (int y : new int[]{321, -65}) {
            Res r = setTableRaw(10, y, 10, "minecraft:overworld");
            assertEquals(400, r.status, "y " + y);
            assertTrue(r.error().contains("-64 to 320"), r.error());
        }
        anchor(10, 320, 10, "minecraft:overworld");
        assertEquals(200, setTableRaw(10, 320, 10, "minecraft:overworld").status);
        anchor(10, -64, 10, "minecraft:overworld");
        assertEquals(200, setTableRaw(10, -64, 10, "minecraft:overworld").status);
    }

    @Test
    void theDimensionIsTheStashsOrOneOfThree() throws Exception {
        start();
        anchor(10, 64, 10, "minecraft:overworld");
        assertEquals(400, setTableRaw(10, 64, 10, "minecraft:the_moon").status, "no index dimension: only the three");
        assertEquals(400, setTableRaw(10, 64, 10, "other:overworld").status);
        // With a dimension in the index it must be that one.
        store.putIndex(json(indexJson().replace("\"player\":\"Bob\",", "\"player\":\"Bob\",\"dimension\":\"minecraft:the_nether\",")));
        Res r = setTableRaw(10, 64, 10, "minecraft:overworld");
        assertEquals(400, r.status);
        assertTrue(r.error().contains("minecraft:the_nether"), r.error());
        anchor(10, 64, 10, "minecraft:the_nether");
        assertEquals(200, setTableRaw(10, 64, 10, "minecraft:the_nether").status);
    }

    @Test
    void theTableMustBeNearAnAnchor() throws Exception {
        start();
        // No anchor at all: refused.
        Res none = setTableRaw(100, 64, 100, "minecraft:overworld");
        assertEquals(400, none.status);
        assertTrue(none.error().contains("has seen"), none.error());
        // A delivery container is an anchor: 60 blocks away is fine, 70 is not.
        anchor(100, 64, 100, "minecraft:overworld");
        assertEquals(200, setTableRaw(100, 64, 160, "minecraft:overworld").status);
        Res far = setTableRaw(100, 64, 170, "minecraft:overworld");
        assertEquals(400, far.status);
        assertTrue(far.error().contains("within 64 blocks"), far.error());
        assertEquals(400, setTableRaw(100 + 40, 64 + 40, 100 + 40, "minecraft:overworld").status, "the distance is in all three axes: 69 blocks");
        // An anchor in another dimension does not count.
        assertEquals(400, setTableRaw(100, 64, 100, "minecraft:the_nether").status);
        // Clearing is always allowed.
        assertEquals(200, post(owner, "/api/v1/craft/table", "{" + KEY + ",\"table\":null}").status);
    }

    @Test
    void withoutKitsOrDeliveryOnlyATableTheBotSawIsAccepted() throws Exception {
        start();
        ok(heartbeat("\"craftOrders\":" + cap(tableHash(), "unset", 64).replace("118,64,-30", "10,64,10")));
        assertEquals(400, setTableRaw(12, 64, 10, "minecraft:overworld").status, "near, but not one the bot saw");
        assertEquals(200, setTableRaw(10, 64, 10, "minecraft:overworld").status);
        // Once a delivery container is known, near a seen table counts too.
        anchor(500, 64, 500, "minecraft:overworld");
        assertEquals(200, setTableRaw(12, 64, 10, "minecraft:overworld").status);
        assertEquals(400, setTableRaw(300, 64, 300, "minecraft:overworld").status);
    }

    @Test
    void anUnloadedTableIsNotUsableUntilTheBotHasSeenItOk() throws Exception {
        start();
        setTable(118, 64, -30);
        capableBeat("unloaded", 64);
        JsonObject c = JsonParser.parseString(get(owner, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("craft");
        assertFalse(c.get("canOrder").getAsBoolean());
        assertEquals("table_bad", c.get("reason").getAsString());
        assertEquals("unloaded", c.get("tableStatus").getAsString());
        assertEquals("table_bad", placeOrder("[" + craftLine("minecraft:spruce_slab", 6) + "]").obj().get("craft").getAsString());
        capableBeat("ok", 64);
        assertEquals(200, placeOrder("[" + craftLine("minecraft:spruce_slab", 6) + "]").status);
        capableBeat("unloaded", 64);
        assertEquals(200, placeOrder("[" + craftLine("minecraft:spruce_slab", 3) + "]").status);
        // A different table is a different thing to have seen.
        anchor(130, 64, -30, "minecraft:overworld");
        ok(post(owner, "/api/v1/craft/table", tableBody(130, 64, -30, "minecraft:overworld")));
        capableBeat("unloaded", 64);
        assertEquals("table_bad", placeOrder("[" + craftLine("minecraft:spruce_slab", 3) + "]").obj().get("craft").getAsString());
    }

    // ---------- the settings reply ----------

    @Test
    void craftFieldsNeedAPlayerAndTheSettingsGoOnlyToTheOwner() throws Exception {
        start();
        assertEquals(400, botPost("/api/v1/heartbeat", "{" + KEY + ",\"craftHash\":null}").status);
        assertEquals(400, botPost("/api/v1/heartbeat", "{" + KEY + ",\"craftOrders\":null}").status);
        assertEquals(400, botPost("/api/v1/heartbeat", "{" + KEY + ",\"player\":\"\",\"craftHash\":null}").status);
        assertEquals(200, botPost("/api/v1/heartbeat", "{" + KEY + "}").status, "an older bot without the fields is unaffected");
        setTable(118, 64, -30);
        assertTrue(ok(heartbeat("\"craftHash\":null")).has("craft"));
        // Another player on the same stash name: refused, and the settings (coordinates) are not in the answer.
        Res eve = botPost("/api/v1/heartbeat", "{" + KEY + ",\"player\":\"Eve\",\"craftHash\":null}");
        assertEquals(409, eve.status);
        assertFalse(eve.body.contains("118"));
        assertFalse(eve.body.contains("\"craft\""));
    }

    // ---------- claims ----------

    static final String FLAGLESS = "{" + KEY + ",\"player\":\"Bob\"}";
    static final String FLAGGED = "{" + KEY + ",\"player\":\"Bob\",\"craftOrders\":1}";

    String heldCraftOrder() throws Exception {
        ready(2048);
        String id = placed("[" + craftLine("minecraft:spruce_slab", 6) + "]");
        ok(botPost("/api/v1/orders/claim", FLAGGED));
        orderClock.addAndGet(5 * 60_000); // the server has been up for a while
        botClock.addAndGet(5 * 60_000);
        ok(heartbeat("\"craftOrders\":" + cap(tableHash(), "ok", 2048)));
        return id;
    }

    @Test
    void threeFlaglessClaimsCloseTogetherNeverFailTheHeldOrder() throws Exception {
        start();
        String id = heldCraftOrder();
        for (int i = 0; i < 6; i++) {
            assertEquals(204, botPost("/api/v1/orders/claim", FLAGLESS).status);
            orderClock.addAndGet(5_000);
        }
        assertEquals("accepted", statusOf(id));
    }

    @Test
    void twoFlaglessClaimsFarApartDoNotFailItEitherButTheThirdAfterTheGraceDoes() throws Exception {
        start();
        String id = heldCraftOrder();
        assertEquals(204, botPost("/api/v1/orders/claim", FLAGLESS).status);
        orderClock.addAndGet(200_000);
        botClock.addAndGet(200_000);
        assertEquals(204, botPost("/api/v1/orders/claim", FLAGLESS).status, "the second, even long after the first: the plugin drops its ledger only on the third");
        assertEquals("accepted", statusOf(id));
        assertEquals(204, botPost("/api/v1/orders/claim", FLAGLESS).status, "the third, more than 120 s after the first, no capable heartbeat since: failed");
        assertEquals("failed", statusOf(id));
    }

    @Test
    void aCapableHeartbeatInTheWindowKeepsTheOrder() throws Exception {
        start();
        String id = heldCraftOrder();
        assertEquals(204, botPost("/api/v1/orders/claim", FLAGLESS).status);
        orderClock.addAndGet(100_000);
        botClock.addAndGet(100_000);
        assertEquals(204, botPost("/api/v1/orders/claim", FLAGLESS).status);
        orderClock.addAndGet(100_000);
        botClock.addAndGet(100_000);
        ok(heartbeat("\"craftOrders\":" + cap(tableHash(), "ok", 2048))); // the bot is alive and capable
        assertEquals(204, botPost("/api/v1/orders/claim", FLAGLESS).status);
        assertEquals("accepted", statusOf(id));
    }

    @Test
    void aFlaggedClaimStartsTheCountOver() throws Exception {
        start();
        String id = heldCraftOrder();
        assertEquals(204, botPost("/api/v1/orders/claim", FLAGLESS).status);
        assertEquals(204, botPost("/api/v1/orders/claim", FLAGLESS).status);
        Res back = botPost("/api/v1/orders/claim", FLAGGED);
        assertEquals(id, back.obj().getAsJsonObject("order").get("id").getAsString());
        orderClock.addAndGet(200_000);
        botClock.addAndGet(200_000);
        assertEquals(204, botPost("/api/v1/orders/claim", FLAGLESS).status, "a new first claim");
        assertEquals(204, botPost("/api/v1/orders/claim", FLAGLESS).status);
        assertEquals("accepted", statusOf(id));
    }

    @Test
    void afterARestartTheGraceCountsFromTheStartOfTheProcess() throws Exception {
        start();
        String id = heldCraftOrder();
        // A new process on the same data: the held order is read from disk, no bot has been heard yet.
        OrderStore restarted = new OrderStore(data, store);
        restarted.load();
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        restarted.clock(clock::get);
        for (int i = 0; i < 4; i++) {
            clock.addAndGet(20_000); // 80 s in all: still inside the grace
            assertNull(restarted.claim("s", "a", false));
        }
        assertEquals("accepted", restarted.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject().get("status").getAsString());
        clock.addAndGet(70_000); // 150 s after the start, 130 s after the first claim
        assertNull(restarted.claim("s", "a", false));
        assertEquals("failed", restarted.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject().get("status").getAsString());
        assertNotNull(id);
    }

    String placed(String lines) throws Exception {
        return ok(placeOrder(lines)).getAsJsonObject("order").get("id").getAsString();
    }

    String statusOf(String id) throws Exception {
        for (JsonElement e : ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders")) {
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject().get("status").getAsString();
        }
        return null;
    }

    // ---------- planning limits ----------

    @Test
    void whenEveryPlanningSlotIsTakenQuotesAndOrdersAnswer503() throws Exception {
        start();
        ready(2048);
        List<CraftService.Permit> held = new ArrayList<>();
        for (int i = 0; i < CraftService.PLAN_SLOTS; i++) held.add(craft.acquire());
        String want = "{" + KEY + ",\"want\":[{\"id\":\"minecraft:spruce_slab\",\"count\":6}]}";
        Res quote = post(owner, "/api/v1/craft/plan", want);
        assertEquals(503, quote.status);
        assertTrue(quote.headers.firstValue("Retry-After").isPresent());
        Res order = placeOrder("[" + craftLine("minecraft:spruce_slab", 6) + "]");
        assertEquals(503, order.status);
        assertTrue(order.headers.firstValue("Retry-After").isPresent());
        assertEquals(10L, availableItem("minecraft:spruce_log").getAsLong(), "nothing was placed");
        // Orders without craft lines don't need a slot.
        assertEquals(200, placeOrder("[" + itemLine("minecraft:oak_log", 1) + "]").status);
        held.forEach(CraftService.Permit::close);
        assertEquals(200, post(owner, "/api/v1/craft/plan", want).status);
        assertEquals(200, placeOrder("[" + craftLine("minecraft:spruce_slab", 6) + "]").status);
    }

    static byte[] build() {
        Map<String, Integer> blocks = new LinkedHashMap<>();
        blocks.put("minecraft:spruce_stairs", 8);
        blocks.put("minecraft:spruce_slab", 6);
        return PlannerFixtures.structure(4189, blocks, null);
    }

    String upload() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/v1/planner/upload?server=s&stash=a&name=T.nbt"))
            .header("X-Stash-Manager", "1").header("Content-Type", "application/octet-stream")
            .header("Cookie", SessionStore.COOKIE + "=" + owner).POST(HttpRequest.BodyPublishers.ofByteArray(build())).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("plan").get("id").getAsString();
    }

    Res view(String id, String options) throws Exception {
        return post(owner, "/api/v1/planner/" + id + "/view", "{" + KEY + (options == null ? "" : "," + options) + "}");
    }

    @Test
    void thePlannerViewAnswers503WhenBusyAndLimitsComputationsPerUser() throws Exception {
        start();
        ready(2048);
        String id = upload();
        List<CraftService.Permit> held = new ArrayList<>();
        for (int i = 0; i < CraftService.PLAN_SLOTS; i++) held.add(craft.acquire());
        // (the upload already planned the default options and cached them)
        assertEquals(200, view(id, null).status, "from the cache: no slot needed");
        for (int i = 0; i < 5; i++) assertEquals(503, view(id, "\"multiplier\":2").status, "a 503 costs no quota");
        Res busy = view(id, "\"multiplier\":2");
        assertEquals(503, busy.status);
        assertTrue(busy.headers.firstValue("Retry-After").isPresent());
        held.forEach(CraftService.Permit::close);
        assertEquals(200, view(id, "\"multiplier\":3").status);
        assertEquals(200, view(id, "\"multiplier\":3").status, "from the cache: not counted");
        // 30 computations a minute: the upload and multiplier 3 are two (the busy tries are not counted); 28 more fit.
        for (int m = 4; m <= 31; m++) assertEquals(200, view(id, "\"multiplier\":" + m).status, "multiplier " + m);
        Res limited = view(id, "\"multiplier\":32");
        assertEquals(429, limited.status);
        assertTrue(limited.headers.firstValue("Retry-After").isPresent());
        assertTrue(limited.obj().get("retryAfter").getAsInt() >= 1);
        assertEquals(200, view(id, "\"multiplier\":31").status, "the cached one is still answered");
    }

    @Test
    void aPlanThatRanOutOfTimeIsMarkedTruncatedAndNeverCached() throws Exception {
        start();
        ready(2048);
        String id = upload();
        craft.planNanos = -1; // the deadline has already passed
        JsonObject plan = ok(view(id, "\"craft\":true")).getAsJsonObject("plan");
        assertTrue(plan.getAsJsonObject("craft").get("truncated").getAsBoolean());
        assertEquals(0, plan.getAsJsonObject("totals").get("craftable").getAsLong());
        JsonObject row = null;
        for (JsonElement e : plan.getAsJsonArray("items")) if (e.getAsJsonObject().get("id").getAsString().equals("minecraft:spruce_slab")) row = e.getAsJsonObject();
        assertEquals("plan too large", row.get("craftReason").getAsString());
        int n = craft.planners.get();
        ok(view(id, "\"craft\":true"));
        assertEquals(n, craft.planners.get(), "within the negative-cache time the cut result is reused, not planned again");
        // Once that time is over the request is planned again; with the time back it gives the real answer.
        craft.truncatedTtlMs = 0;
        ok(view(id, "\"craft\":true"));
        assertTrue(craft.planners.get() > n, "a truncated result is not kept for good");
        craft.planNanos = CraftPlanner.PLAN_NANOS;
        JsonObject good = ok(view(id, "\"craft\":true")).getAsJsonObject("plan");
        assertFalse(good.getAsJsonObject("craft").get("truncated").getAsBoolean());
        assertEquals(14, good.getAsJsonObject("totals").get("craftable").getAsLong());
        int m = craft.planners.get();
        ok(view(id, "\"craft\":true"));
        assertEquals(m, craft.planners.get(), "a complete one is cached");
    }

    @Test
    void waitersBehindATruncatedPlanReuseItInsteadOfEachSpendingTheBudget() throws Exception {
        start();
        ready(2048);
        String id = upload();
        craft.planNanos = -1;
        int before = craft.planners.get();
        List<Thread> threads = new ArrayList<>();
        List<Boolean> cut = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 5; i++) {
            Thread t = new Thread(() -> {
                try {
                    Res r = view(id, "\"multiplier\":2");
                    cut.add(r.status == 200 && r.obj().getAsJsonObject("plan").getAsJsonObject("craft").get("truncated").getAsBoolean());
                } catch (Exception e) {
                    cut.add(false);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        assertEquals(List.of(true, true, true, true, true), cut);
        assertEquals(before + 1, craft.planners.get(), "one computation, four reused answers");
    }

    @Test
    void aBusyAnswerFromPlaceLeavesNoOrderBehind() throws Exception {
        start();
        ready(2048);
        List<CraftService.Permit> held = new ArrayList<>();
        for (int i = 0; i < CraftService.PLAN_SLOTS; i++) held.add(craft.acquire());
        assertEquals(503, placeOrder("[" + itemLine("minecraft:oak_log", 1) + "," + craftLine("minecraft:spruce_slab", 6) + "]").status);
        assertEquals(0, orders.listJson("s", "a").getAsJsonArray("orders").size());
        assertEquals(3L, availableItem("minecraft:oak_log").getAsLong());
        held.forEach(CraftService.Permit::close);
    }

    @Test
    void thePlacementGivesUpWith409AfterThreeTriesWhenTheOrdersKeepChanging() throws Exception {
        start();
        ready(2048);
        int[] runs = {0};
        orders.afterPlan = () -> {
            runs[0]++;
            try {
                orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:bamboo", 1)), null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Res r = placeOrder("[" + craftLine("minecraft:spruce_slab", 6) + "]");
        assertEquals(409, r.status, r.body);
        assertTrue(r.error().contains("changed while"), r.error());
        assertEquals(3, runs[0], "three plans were made");
        long craftOrders = 0;
        for (JsonElement e : orders.listJson("s", "a").getAsJsonArray("orders")) {
            if (!e.getAsJsonObject().get("craft").isJsonNull()) craftOrders++;
        }
        assertEquals(0, craftOrders);
        // Two changes are survived: the third plan is the one that holds.
        orders.afterPlan = new Runnable() {
            int n;

            @Override
            public void run() {
                if (n++ < 2) {
                    try {
                        orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:bamboo", 1)), null);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        };
        assertEquals(200, placeOrder("[" + craftLine("minecraft:spruce_slab", 6) + "]").status);
    }

    @Test
    void identicalRequestsAtOnceWaitForOnePlan() throws Exception {
        start();
        ready(2048);
        String id = upload();
        int before = craft.planners.get();
        List<Thread> threads = new ArrayList<>();
        List<Integer> statuses = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                try {
                    statuses.add(view(id, "\"multiplier\":2").status);
                } catch (Exception e) {
                    statuses.add(-1);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        assertEquals(List.of(200, 200, 200, 200), statuses);
        assertEquals(before + 1, craft.planners.get(), "one computation served all four");
    }
}

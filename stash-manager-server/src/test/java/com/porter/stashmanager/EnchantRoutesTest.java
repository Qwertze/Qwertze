package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.porter.qwerte.core.enchant.EnchantRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** POST /api/v1/enchant/plan (ENCHANT-CONTRACT v1.0): the answer, refusals, validation, scope, limits, cache, single flight and the pool. */
class EnchantRoutesTest extends EnchantHarness {
    static final String SWORD = "minecraft:diamond_sword";
    static final String PATH = "/api/v1/enchant/plan";
    static final int B55 = EnchantRules.bottles(55);

    static String req(String key, String... enchants) {
        return "{" + KEY + ",\"key\":\"" + key + "\",\"enchants\":" + ench(enchants) + "}";
    }

    Res plan(String token, String key, String... enchants) throws Exception {
        return post(token, PATH, req(key, enchants));
    }

    // ---------- the answer ----------

    @Test
    void theAnswerHasTheContractShape() throws Exception {
        startEnchant();
        ready();
        JsonObject r = ok(plan(owner, SWORD, e("sharpness", 5)));
        assertTrue(r.get("ok").getAsBoolean());
        assertFalse(r.get("estimate").getAsBoolean());
        assertTrue(r.get("canOrder").getAsBoolean());
        assertEquals(MC11, r.get("mc").getAsString());
        assertFalse(r.get("truncated").getAsBoolean());
        JsonObject p = r.getAsJsonObject("plan");
        assertEquals(2, p.getAsJsonArray("materials").size());
        assertEquals("base", p.getAsJsonArray("materials").get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals(SWORD, p.getAsJsonArray("materials").get(0).getAsJsonObject().get("key").getAsString());
        assertEquals(SHARP, p.getAsJsonArray("materials").get(1).getAsJsonObject().get("key").getAsString());
        assertEquals(0, p.getAsJsonArray("steps").get(0).getAsJsonObject().get("left").getAsInt());
        assertEquals(1, p.getAsJsonArray("steps").get(0).getAsJsonObject().get("right").getAsInt());
        assertEquals(5, p.getAsJsonArray("steps").get(0).getAsJsonObject().get("cost").getAsInt());
        assertEquals(1, p.getAsJsonArray("steps").get(0).getAsJsonObject().get("rc").getAsInt());
        assertEquals("minecraft:sharpness", p.getAsJsonArray("levels").get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(p.getAsJsonArray("extras").isEmpty() && p.getAsJsonArray("curse").isEmpty());
        assertEquals(5, p.get("totalLevels").getAsInt());
        assertEquals(55, p.get("points").getAsInt());
        assertEquals(B55, p.get("bottles").getAsInt());
        assertEquals(1, p.get("finalRc").getAsInt());
        JsonObject a = r.getAsJsonObject("availability");
        assertEquals(SHARP, a.getAsJsonArray("books").get(0).getAsJsonObject().get("key").getAsString());
        assertEquals(1, a.getAsJsonArray("books").get(0).getAsJsonObject().get("need").getAsInt());
        assertEquals(2, a.getAsJsonArray("books").get(0).getAsJsonObject().get("have").getAsInt());
        assertEquals(3, a.getAsJsonObject("base").get("have").getAsInt());
        assertEquals(2, a.getAsJsonObject("anvil").get("need").getAsInt());
        assertEquals(3, a.getAsJsonObject("anvil").get("have").getAsInt());
        assertEquals(B55, a.getAsJsonObject("bottles").get("need").getAsInt());
        assertEquals(3000 - 1728, a.getAsJsonObject("bottles").get("usable").getAsInt(), "before this order's own reservation");
        assertEquals(0, ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").size(), "a plan is never stored");
        // Unknown fields (the old allowExtras) are ignored.
        assertTrue(ok(post(owner, PATH, "{" + KEY + ",\"key\":\"" + SWORD + "\",\"allowExtras\":true,\"junk\":[1],\"enchants\":" + ench(e("sharpness", 5)) + "}")).get("ok").getAsBoolean());
    }

    @Test
    void withoutACapableBotItIsAnEstimateThatCannotBeOrdered() throws Exception {
        startEnchant();
        JsonObject r = ok(plan(owner, SWORD, e("sharpness", 5)));
        assertTrue(r.get("ok").getAsBoolean());
        assertTrue(r.get("estimate").getAsBoolean());
        assertFalse(r.get("canOrder").getAsBoolean());
        assertEquals(55, r.getAsJsonObject("plan").get("points").getAsInt());
        // Entries with no repair cost are planned as 0 and flagged too, even with a bot.
        ready();
        reindex(List.of(sword(1), it(SHARP, "minecraft:enchanted_book", 2, 1, "\"ench\":" + ench(e("sharpness", 5))), bottles(3000), anvils(3)));
        JsonObject est = ok(plan(owner, SWORD, e("sharpness", 5)));
        assertTrue(est.get("estimate").getAsBoolean());
        assertFalse(est.get("canOrder").getAsBoolean());
    }

    @Test
    void refusalsCarryTheCodeAndNoPlan() throws Exception {
        startEnchant();
        ready();
        String[][] cases = {
            {"nothing_wanted", SWORD}, {"unknown_enchant", SWORD, e("nope", 1)}, {"invalid_level", SWORD, e("sharpness", 9)},
            {"duplicate", SWORD, e("sharpness", 5), e("sharpness", 4)}, {"wrong_item", SWORD, e("efficiency", 5)},
            {"conflict", SWORD, e("sharpness", 5), e("smite", 5)}, {"too_complex", SWORD, e("a", 1), e("b", 1), e("c", 1), e("d", 1), e("e", 1),
            e("f", 1), e("g", 1), e("h", 1), e("i", 1), e("j", 1), e("k", 1), e("l", 1), e("m", 1)}};
        for (String[] c : cases) {
            String[] enchants = java.util.Arrays.copyOfRange(c, 2, c.length);
            Res r = plan(owner, c[1], enchants);
            assertEquals(200, r.status, c[0] + r.body);
            JsonObject o = r.obj();
            assertFalse(o.get("ok").getAsBoolean());
            assertEquals(c[0], o.get("code").getAsString());
            assertTrue(o.get("plan").isJsonNull());
            assertFalse(o.get("message").getAsString().isEmpty());
            limitClock.addAndGet(6_000);
        }
        // Planning refusals.
        reindex(List.of(bottles(3000), anvils(3)));
        assertEquals("no_base", plan(owner, SWORD, e("sharpness", 5)).obj().get("code").getAsString());
        limitClock.addAndGet(6_000);
        reindex(List.of(sword(1), bottles(3000), anvils(3)));
        assertEquals("books_short", plan(owner, SWORD, e("sharpness", 5)).obj().get("code").getAsString());
        limitClock.addAndGet(6_000);
        List<String> items = items();
        items.set(3, bottles(100));
        reindex(items);
        assertEquals("xp_short", plan(owner, SWORD, e("sharpness", 5)).obj().get("code").getAsString());
        limitClock.addAndGet(6_000);
        reindex(items());
        ok(enchantBeat(block(true, MC11, 4)));
        assertEquals("inventory_full", plan(owner, SWORD, e("sharpness", 5)).obj().get("code").getAsString());
        limitClock.addAndGet(60_000);
        ok(botPost("/api/v1/index", index("1.20.1", "Bob", items())));
        assertEquals("mc_unsupported", plan(owner, SWORD, e("sharpness", 5)).obj().get("code").getAsString());
    }

    // ---------- validation, scope, permission ----------

    @Test
    void invalidRequestsAreA400WithoutEchoAndBeforeAnyPermit() throws Exception {
        startEnchant();
        ready();
        // Hold both pool threads: validation must still answer, so no permit is taken before it.
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        routes.enchantRoutes().planMillis = 5000;
        routes.enchantRoutes().beforePlan = () -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        };
        String uma = userToken("uma", USER_PERMS);
        CompletableFuture<Res> a = CompletableFuture.supplyAsync(() -> call(() -> plan(owner, SWORD, e("sharpness", 5))));
        CompletableFuture<Res> b = CompletableFuture.supplyAsync(() -> call(() -> plan(uma, SWORD, e("looting", 3))));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        String s = "SENTINEL-Q9";
        String[] bad = {
            "5", "[]", "{}", "{" + KEY + "}",
            "{" + KEY + ",\"key\":\"" + SWORD + "\"}",
            "{" + KEY + ",\"key\":\"" + SWORD + "\",\"enchants\":\"x\"}",
            "{" + KEY + ",\"key\":\"" + s + "\",\"enchants\":" + ench(e("sharpness", 5)) + "}",
            "{" + KEY + ",\"key\":\"minecraft:diamond_sword#ab12\",\"enchants\":" + ench(e("sharpness", 5)) + "}",
            "{" + KEY + ",\"key\":\"diamond_sword\",\"enchants\":" + ench(e("sharpness", 5)) + "}",
            "{" + KEY + ",\"key\":5,\"enchants\":" + ench(e("sharpness", 5)) + "}",
            "{" + KEY + ",\"key\":\"" + SWORD + "\",\"enchants\":[{\"id\":\"" + s + " x\",\"lvl\":5}]}",
            "{" + KEY + ",\"key\":\"" + SWORD + "\",\"enchants\":[{\"id\":\"sharpness\",\"lvl\":\"5\"}]}",
            "{" + KEY + ",\"key\":\"" + SWORD + "\",\"enchants\":[{\"id\":\"sharpness\",\"lvl\":0}]}",
            "{" + KEY + ",\"key\":\"" + SWORD + "\",\"enchants\":[{\"id\":\"sharpness\",\"lvl\":256}]}",
            "{" + KEY + ",\"key\":\"" + SWORD + "\",\"enchants\":[{\"id\":\"sharpness\",\"lvl\":1.5}]}",
            "{" + KEY + ",\"key\":\"" + SWORD + "\",\"enchants\":[7]}"};
        for (String body : bad) {
            Res r = post(owner, PATH, body);
            assertEquals(400, r.status, body + " -> " + r.body);
            assertFalse(r.body.contains(s), r.body);
            assertEquals("bad_request", r.obj().get("code").getAsString());
            assertTrue(r.obj().get("plan").isJsonNull());
        }
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 65; i++) many.append(i == 0 ? "" : ",").append(e("sharpness", 1));
        assertEquals(400, post(owner, PATH, "{" + KEY + ",\"key\":\"" + SWORD + "\",\"enchants\":[" + many + "]}").status);
        release.countDown();
        assertEquals(200, a.get(5, TimeUnit.SECONDS).status);
        assertEquals(200, b.get(5, TimeUnit.SECONDS).status);
    }

    Res call(ThrowingSupplier s) {
        try {
            return s.get();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    interface ThrowingSupplier {
        Res get() throws Exception;
    }

    @Test
    void theRouteNeedsALoginTheOrderPermissionAndABrowserWrite() throws Exception {
        startEnchant();
        ready();
        String viewer = userToken("viewer", "{\"view\":true}");
        assertEquals(401, post(null, PATH, req(SWORD, e("sharpness", 5))).status);
        assertEquals(403, plan(viewer, SWORD, e("sharpness", 5)).status, "order permission");
        java.net.http.HttpResponse<String> noHeader = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + PATH))
            .header("Content-Type", "application/json").header("Cookie", SessionStore.COOKIE + "=" + owner)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(req(SWORD, e("sharpness", 5)))).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noHeader.statusCode(), "needs X-Stash-Manager");
        java.net.http.HttpResponse<String> form = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + PATH))
            .header("X-Stash-Manager", "1").header("Content-Type", "text/plain").header("Cookie", SessionStore.COOKIE + "=" + owner)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(req(SWORD, e("sharpness", 5)))).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(403, form.statusCode(), "needs JSON");
        // 4 KiB.
        String big = "{" + KEY + ",\"key\":\"" + SWORD + "\",\"enchants\":[],\"pad\":\"" + "x".repeat(5000) + "\"}";
        assertEquals(413, post(owner, PATH, big).status);
        assertEquals(200, plan(owner, SWORD, e("sharpness", 5)).status);
    }

    @Test
    void theStashIsResolvedThroughTheScopeAndTheOpaqueServerId() throws Exception {
        startEnchant();
        ready();
        String lost = userToken("lost", "{\"view\":true,\"order\":true,\"stashes\":[{\"server\":\"other\",\"stash\":\"x\"}]}");
        assertEquals(404, plan(lost, SWORD, e("sharpness", 5)).status, "out of scope");
        assertEquals(404, post(owner, PATH, "{\"server\":\"s\",\"stash\":\"nope\",\"key\":\"" + SWORD + "\",\"enchants\":" + ench(e("sharpness", 5)) + "}").status);
        String fay = userToken("fay", "{\"view\":true,\"order\":true,\"streamer\":true}");
        String sid = com.google.gson.JsonParser.parseString(get(fay, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().get("server").getAsString();
        assertEquals(404, plan(fay, SWORD, e("sharpness", 5)).status, "a raw address is not accepted from a forced streamer");
        Res forced = post(fay, PATH, "{\"server\":\"" + sid + "\",\"stash\":\"a\",\"key\":\"" + SWORD + "\",\"enchants\":" + ench(e("sharpness", 5)) + "}");
        Res full = plan(owner, SWORD, e("sharpness", 5));
        assertEquals(200, forced.status, forced.body);
        assertEquals(full.obj(), forced.obj(), "the same answer for a streamer: no positions, no names");
        assertFalse(forced.body.contains("\"s\"") && forced.body.contains("Bob"));
    }

    // ---------- cache, single flight ----------

    @Test
    void identicalRequestsAreServedFromTheCacheUntilTheStashChanges() throws Exception {
        startEnchant();
        ready();
        AtomicInteger runs = new AtomicInteger();
        routes.enchantRoutes().beforePlan = runs::incrementAndGet;
        Res first = plan(owner, SWORD, e("sharpness", 5));
        Res second = plan(owner, SWORD, e("sharpness", 5));
        assertEquals(1, runs.get());
        assertEquals(first.obj(), second.obj());
        assertEquals(1, routes.enchantRoutes().cacheSize());
        // A cache hit costs the user nothing: ten more asks stay under the limits.
        for (int i = 0; i < 10; i++) assertEquals(200, plan(owner, SWORD, e("sharpness", 5)).status);
        assertEquals(1, runs.get());
        // A different request, a reservation, a new index and a different bottle count all give a new answer.
        plan(owner, SWORD, e("looting", 3));
        assertEquals(2, runs.get());
        placedOrder(line(SWORD, 1, e("sharpness", 5)));
        JsonObject after = ok(plan(owner, SWORD, e("sharpness", 5)));
        assertEquals(3, runs.get());
        assertEquals(1, after.getAsJsonObject("availability").getAsJsonArray("books").get(0).getAsJsonObject().get("have").getAsInt());
        limitClock.addAndGet(10_000);
        reindex(items()); // a new index post (its receivedAt differs): a new etag
        plan(owner, SWORD, e("sharpness", 5));
        assertEquals(4, runs.get());
        List<String> items = items();
        items.set(3, bottles(2500));
        reindex(items);
        limitClock.addAndGet(10_000);
        plan(owner, SWORD, e("sharpness", 5));
        assertEquals(5, runs.get());
        // The TTL.
        enchantClock.addAndGet(EnchantRoutes.CACHE_TTL_MS + 1);
        ok(enchantBeat(block(true, MC11, 30)));
        limitClock.addAndGet(10_000);
        plan(owner, SWORD, e("sharpness", 5));
        assertEquals(6, runs.get());
    }

    @Test
    void identicalRequestsInFlightShareOneComputation() throws Exception {
        startEnchant();
        ready();
        String uma = userToken("uma", USER_PERMS);
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        routes.enchantRoutes().planMillis = 5000;
        routes.enchantRoutes().beforePlan = () -> {
            runs.incrementAndGet();
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        };
        CompletableFuture<Res> a = CompletableFuture.supplyAsync(() -> call(() -> plan(owner, SWORD, e("sharpness", 5))));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<Res> b = CompletableFuture.supplyAsync(() -> call(() -> plan(uma, SWORD, e("sharpness", 5))));
        Thread.sleep(300);
        release.countDown();
        assertEquals(200, a.get(5, TimeUnit.SECONDS).status);
        assertEquals(200, b.get(5, TimeUnit.SECONDS).status);
        assertEquals(1, runs.get(), "one computation for both");
    }

    // ---------- limits and the pool ----------

    @Test
    void aBurstOfFiveThenTwelveAMinute() throws Exception {
        startEnchant();
        ready();
        // Distinct requests (each a cache miss): 5 pass at once, the sixth is refused with Retry-After.
        String[] ids = {"sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect", "looting", "unbreaking", "mending", "sweeping_edge", "fire_aspect"};
        for (int i = 0; i < 5; i++) assertEquals(200, plan(owner, SWORD, e(ids[i], 1)).status, ids[i]);
        Res refused = plan(owner, SWORD, e(ids[5], 1));
        assertEquals(429, refused.status, refused.body);
        assertTrue(refused.headers.firstValue("Retry-After").isPresent());
        assertTrue(Integer.parseInt(refused.headers.firstValue("Retry-After").get()) >= 1);
        // Another user is not affected.
        String uma = userToken("uma", USER_PERMS);
        assertEquals(200, plan(uma, SWORD, e(ids[5], 1)).status);
        // The bucket refills 12 a minute (one per 5 s).
        limitClock.addAndGet(5_000);
        assertEquals(200, plan(owner, SWORD, e(ids[6], 1)).status);
        assertEquals(429, plan(owner, SWORD, e(ids[7], 1)).status);
        // The rate check comes before the cache: a user who is over the limit is refused even for an answer that is cached.
        assertEquals(429, plan(owner, SWORD, e(ids[0], 1)).status);
    }

    @Test
    void twelveAMinuteIsAWindowSharedWithOrderCreate() throws Exception {
        startEnchant();
        ready();
        List<String> items = items();
        items.set(0, sword(100));
        items.set(1, book(SHARP, 100, 0, e("sharpness", 5)));
        reindex(items);
        String want = line(SWORD, 1, e("sharpness", 5));
        // Order create counts once against the 12 but never draws from the burst: 6 orders pass (the burst would stop at 5).
        for (int i = 0; i < 6; i++) ok(post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":[" + want + "]}"));
        for (int i = 0; i < 5; i++) assertEquals(200, plan(owner, SWORD, e(new String[]{"smite", "bane_of_arthropods", "knockback", "fire_aspect", "looting"}[i], 1)).status);
        // 6 orders + 5 plans are 11 of the minute: the 12th passes, the 13th is refused.
        assertEquals(200, post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":[" + want + "]}").status, "the 12th");
        Res over = post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":[" + want + "]}");
        assertEquals(429, over.status, over.body);
        assertTrue(over.headers.firstValue("Retry-After").isPresent());
        // A plan is refused too (the same window), and orders without enchant lines are never counted.
        assertEquals(429, plan(owner, SWORD, e("mending", 1)).status);
        ok(post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]}"));
        limitClock.addAndGet(60_001);
        ok(post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":[" + want + "]}"));
    }

    @Test
    void retryAndAcceptCountOnceEachAgainstTheSameTwelve() throws Exception {
        startEnchant();
        ready();
        String want = line(SWORD, 1, e("sharpness", 5));
        String id1 = ok(post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":[" + want + "]}")).getAsJsonObject("order").get("id").getAsString();
        ok(post(owner, "/api/v1/orders/" + id1 + "/cancel", "{}"));
        String id2 = ok(post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":[" + want + "]}")).getAsJsonObject("order").get("id").getAsString();
        ok(post(owner, "/api/v1/orders/" + id2 + "/cancel", "{}"));
        for (int i = 0; i < 9; i++) assertEquals(0, routes.enchantLimits().countOrder("owner"));
        assertEquals(200, post(owner, "/api/v1/orders/" + id1 + "/retry", "{}").status, "the 12th of the minute");
        Res over = post(owner, "/api/v1/orders/" + id2 + "/retry", "{}");
        assertEquals(429, over.status, over.body);
        assertTrue(over.headers.firstValue("Retry-After").isPresent());
        limitClock.addAndGet(60_001);
        assertEquals(200, post(owner, "/api/v1/orders/" + id2 + "/retry", "{}").status);
        // Accept-curse counts too.
        List<String> cursed = items();
        cursed.set(1, book("minecraft:enchanted_book#c9999999", 5, 0, e("sharpness", 5), e("vanishing_curse", 1)));
        cursed.set(0, sword(20));
        reindex(cursed);
        String held = ok(post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":[" + want + "]}")).getAsJsonObject("order").get("id").getAsString();
        for (int i = 0; i < 9; i++) assertEquals(0, routes.enchantLimits().countOrder("owner"));
        assertEquals(200, post(owner, "/api/v1/orders/" + held + "/accept-curse", "{\"curse\":[\"minecraft:vanishing_curse\"]}").status, "the 12th");
        assertEquals(429, post(owner, "/api/v1/orders", "{" + KEY + ",\"lines\":[" + want + "]}").status);
    }

    @Test
    void aPlanFloodNeverBlocksOrdersAndTheThirdPlanGets503() throws Exception {
        startEnchant();
        ready();
        String uma = userToken("uma", USER_PERMS);
        String boss = userToken("boss", USER_PERMS);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        routes.enchantRoutes().planMillis = 5000;
        routes.enchantRoutes().beforePlan = () -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        };
        CompletableFuture<Res> a = CompletableFuture.supplyAsync(() -> call(() -> plan(owner, SWORD, e("sharpness", 5))));
        CompletableFuture<Res> b = CompletableFuture.supplyAsync(() -> call(() -> plan(uma, SWORD, e("looting", 3))));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        // The pool (2 threads, no queue) is full: a third plan is a 503 with Retry-After and the refusal shape.
        Res third = plan(boss, SWORD, e("unbreaking", 3));
        assertEquals(503, third.status, third.body);
        assertTrue(third.headers.firstValue("Retry-After").isPresent());
        assertEquals("busy", third.obj().get("code").getAsString());
        assertTrue(third.obj().get("plan").isJsonNull());
        // The same user can't have a second plan in flight (a 429), whatever the pool says.
        assertEquals(429, plan(owner, SWORD, e("mending", 1)).status);
        // Orders keep their own slot: placing one still works while the plan pool is full.
        JsonObject order = ok(placeEnchant(line(SWORD, 1, e("sharpness", 5)))).getAsJsonObject("order");
        assertEquals("pending", order.get("status").getAsString());
        release.countDown();
        assertEquals(200, a.get(5, TimeUnit.SECONDS).status);
        assertEquals(200, b.get(5, TimeUnit.SECONDS).status);
        routes.enchantRoutes().beforePlan = null;
        limitClock.addAndGet(10_000);
        assertEquals(200, plan(boss, SWORD, e("unbreaking", 3)).status, "the permits came back");
    }

    @Test
    void aRequestThatGivesUpWaitingAnswers503AndTheWorkerStillFreesItsPermit() throws Exception {
        startEnchant();
        ready();
        routes.enchantRoutes().planMillis = 50; // the HTTP thread waits 300 ms
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        routes.enchantRoutes().beforePlan = () -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        };
        Res slow = plan(owner, SWORD, e("sharpness", 5));
        assertEquals(503, slow.status, slow.body);
        assertTrue(slow.headers.firstValue("Retry-After").isPresent());
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        // The worker is still running: the user's plan is still in flight (429), but the permit is not freed early.
        assertEquals(429, plan(owner, SWORD, e("looting", 3)).status);
        release.countDown();
        routes.enchantRoutes().beforePlan = null;
        Thread.sleep(300);
        limitClock.addAndGet(10_000);
        routes.enchantRoutes().planMillis = EnchantOrders.PLAN_MILLIS;
        assertEquals(200, plan(owner, SWORD, e("looting", 3)).status, "freed in the worker's finally");
    }

    @Test
    void theUsersPlannerTimeIsABucketOfFourSecondsAMinute() throws Exception {
        startEnchant();
        ready();
        // Each plan takes about a second of real time in the worker; the clock is moved 2 s between them (a plan a few times a minute).
        routes.enchantRoutes().beforePlan = () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        };
        routes.enchantRoutes().planMillis = 5000;
        String[] ids = {"sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect", "looting", "unbreaking", "mending"};
        int ok = 0;
        Res last = null;
        for (String id : ids) {
            last = plan(owner, SWORD, e(id, 1));
            if (last.status != 200) break;
            ok++;
            limitClock.addAndGet(2_000);
        }
        assertTrue(ok >= 4 && ok < ids.length, "4 s of planner time a minute: " + ok + " plans passed");
        assertEquals(429, last.status);
    }

    // ---------- review fixes ----------

    @Test
    void aGameRuleRefusalCostsNoRateTokenAndNoPermit() throws Exception {
        startEnchant();
        ready();
        // Hold both workers; refusals still answer, and never draw from the user's limits.
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        routes.enchantRoutes().planMillis = 5000;
        routes.enchantRoutes().beforePlan = () -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        };
        String uma = userToken("uma", USER_PERMS);
        String boss = userToken("boss", USER_PERMS);
        CompletableFuture<Res> a = CompletableFuture.supplyAsync(() -> call(() -> plan(uma, SWORD, e("sharpness", 5))));
        CompletableFuture<Res> b = CompletableFuture.supplyAsync(() -> call(() -> plan(boss, SWORD, e("looting", 3))));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(0, routes.enchantRoutes().freePermits(), "both pool slots are taken");
        for (int i = 0; i < 9; i++) {
            Res r = plan(owner, SWORD, e("sharpness", 9));
            assertEquals(200, r.status, r.body);
            assertEquals("invalid_level", r.obj().get("code").getAsString());
        }
        assertEquals("nothing_wanted", plan(owner, SWORD).obj().get("code").getAsString());
        // Two users hold the two slots: a third gets 503.
        Res third = plan(owner, SWORD, e("unbreaking", 3));
        assertEquals(503, third.status, third.body);
        release.countDown();
        assertEquals(200, a.get(5, TimeUnit.SECONDS).status);
        assertEquals(200, b.get(5, TimeUnit.SECONDS).status);
        routes.enchantRoutes().beforePlan = null;
        assertEquals(2, routes.enchantRoutes().freePermits());
        // The refusals took nothing from the owner: a full burst of 5 real plans still passes.
        String[] ids = {"sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect"};
        for (String id : ids) assertEquals(200, plan(owner, SWORD, e(id, 1)).status, id);
    }

    @Test
    void anErrorInTheWorkerIsABusyAnswerAndTheCleanupStillRuns() throws Exception {
        startEnchant();
        ready();
        routes.enchantRoutes().beforePlan = () -> {
            throw new AssertionError("boom-SENTINEL");
        };
        Res r = plan(owner, SWORD, e("sharpness", 5));
        assertEquals(503, r.status, r.body);
        assertFalse(r.body.contains("SENTINEL"));
        Thread.sleep(200);
        assertEquals(2, routes.enchantRoutes().freePermits(), "the permit came back");
        routes.enchantRoutes().beforePlan = null;
        limitClock.addAndGet(2_000);
        assertEquals(200, plan(owner, SWORD, e("sharpness", 5)).status, "the user's in-flight mark came back too");
    }

    @Test
    void aForcedStreamerDoesNotSeeIndexKeysThatAreNotItemKeys() throws Exception {
        startEnchant();
        ready();
        String odd = "Steve's book 1234 64 -5678";
        reindex(List.of(sword(3), book(odd, 2, 0, e("sharpness", 5)), bottles(3000), anvils(3)));
        String fay = userToken("fay", "{\"view\":true,\"order\":true,\"streamer\":true}");
        String sid = com.google.gson.JsonParser.parseString(get(fay, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().get("server").getAsString();
        Res forced = post(fay, PATH, "{\"server\":\"" + sid + "\",\"stash\":\"a\",\"key\":\"" + SWORD + "\",\"enchants\":" + ench(e("sharpness", 5)) + "}");
        assertEquals(200, forced.status, forced.body);
        assertFalse(forced.body.contains("Steve"), forced.body);
        assertEquals(Redact.HIDDEN, forced.obj().getAsJsonObject("plan").getAsJsonArray("materials").get(1).getAsJsonObject().get("key").getAsString());
        assertEquals(Redact.HIDDEN, forced.obj().getAsJsonObject("availability").getAsJsonArray("books").get(0).getAsJsonObject().get("key").getAsString());
        assertTrue(plan(owner, SWORD, e("sharpness", 5)).body.contains("Steve's book"), "the owner of the site sees it");
    }
}

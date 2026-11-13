package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwerte.core.enchant.EnchantRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enchant lines in orders (ENCHANT-CONTRACT v1.0): checks, planning, the take and its reservations, no merging, retry, reload, the
 * order caps and budget, the claim binding, results, force-cancel and the audit.
 */
class EnchantOrdersTest extends EnchantHarness {
    static final String SWORD = "minecraft:diamond_sword";
    static final int B55 = EnchantRules.bottles(55);

    long available(String key) throws Exception {
        JsonElement n = availableItem(key);
        return n == null ? -1 : n.getAsLong();
    }

    String audit() throws Exception {
        return get(owner, "/api/v1/audit").body;
    }

    // ---------- placement ----------

    @Test
    void anEnchantLineIsPlannedReservedAndReleased() throws Exception {
        startEnchant();
        ready();
        JsonObject o = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        assertEquals("pending", o.get("status").getAsString());
        assertTrue(o.get("craft").isJsonNull());
        JsonObject line = o.getAsJsonArray("lines").get(0).getAsJsonObject();
        assertEquals("enchant", line.get("type").getAsString());
        assertEquals("minecraft:sharpness", line.getAsJsonArray("enchants").get(0).getAsJsonObject().get("id").getAsString());
        JsonObject en = o.getAsJsonObject("enchant");
        assertEquals(1, en.get("v").getAsInt());
        assertEquals(MC11, en.get("mc").getAsString());
        JsonObject plan = en.getAsJsonArray("lines").get(0).getAsJsonObject().getAsJsonArray("plans").get(0).getAsJsonObject();
        assertEquals("base", plan.getAsJsonArray("materials").get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals(SWORD, plan.getAsJsonArray("materials").get(0).getAsJsonObject().get("key").getAsString());
        assertEquals(SHARP, plan.getAsJsonArray("materials").get(1).getAsJsonObject().get("key").getAsString());
        assertEquals(5, plan.getAsJsonArray("steps").get(0).getAsJsonObject().get("cost").getAsInt());
        assertEquals(55, plan.get("points").getAsInt());
        assertEquals(B55, plan.get("bottles").getAsInt());
        assertTrue(plan.getAsJsonArray("curse").isEmpty());
        // The take is exact index keys: the base, the book, two anvils (none stands at the stash) and the bottles.
        assertEquals(1, takeCount(o, SWORD));
        assertEquals(1, takeCount(o, SHARP));
        assertEquals(2, takeCount(o, "minecraft:anvil"));
        assertEquals(B55, takeCount(o, "minecraft:experience_bottle"));
        // It is reserved like item lines: the enchant line itself has no ref of its own.
        assertEquals(2, available(SWORD));
        assertEquals(1, available(SHARP));
        assertEquals(1, available("minecraft:anvil"));
        assertEquals(3000 - B55, available("minecraft:experience_bottle"));
        assertEquals(-1, available("enchant:" + SWORD));
        assertTrue(audit().contains("order.place"));
        // A second order takes the second book; a third has no book left (count minus reservations).
        placedOrder(line(SWORD, 1, e("sharpness", 5)));
        Res none = placeEnchant(line(SWORD, 1, e("sharpness", 5)));
        assertEquals(400, none.status, none.body);
        assertEquals("books_short", none.obj().get("enchant").getAsString());
        assertEquals(0, none.obj().get("line").getAsInt());
        ok(post(owner, "/api/v1/orders/" + o.get("id").getAsString() + "/cancel", "{}"));
        assertEquals(1, available(SHARP));
        assertEquals(2, available("minecraft:anvil"));
        placedOrder(line(SWORD, 1, e("sharpness", 5)));
    }

    @Test
    void anAnvilThatStandsNeedsOnlyOneSpare() throws Exception {
        startEnchant();
        ready();
        ok(heartbeat("\"naming\":{\"anvil\":true,\"xpLevels\":-1,\"anvilSource\":\"set\"}"));
        assertEquals(1, takeCount(placedOrder(line(SWORD, 1, e("sharpness", 5))), "minecraft:anvil"));
    }

    @Test
    void countGivesOnePlanPerItemAndLinesNeverMerge() throws Exception {
        startEnchant();
        ready();
        List<String> items = items();
        items.set(0, sword(10));
        items.set(1, book(SHARP, 4, 0, e("sharpness", 5)));
        reindex(items);
        JsonObject o = placedOrder(line(SWORD, 2, e("sharpness", 5)), line(SWORD, 1, e("sharpness", 5)));
        assertEquals(2, o.getAsJsonArray("lines").size(), "the same key twice stays two lines");
        JsonArray lines = o.getAsJsonObject("enchant").getAsJsonArray("lines");
        assertEquals(2, lines.get(0).getAsJsonObject().getAsJsonArray("plans").size());
        assertEquals(1, lines.get(1).getAsJsonObject().getAsJsonArray("plans").size());
        assertEquals(3, takeCount(o, SWORD));
        assertEquals(3, takeCount(o, SHARP));
        assertEquals(3L * B55, takeCount(o, "minecraft:experience_bottle"));
        // Two items need two books but only one is left: the second item of the line has none.
        Res r = placeEnchant(line(SWORD, 2, e("sharpness", 5)));
        assertEquals(400, r.status, r.body);
        assertEquals("books_short", r.obj().get("enchant").getAsString());
    }

    @Test
    void theStockIsDrawnFromIdenticalEntriesInKeyOrderLimitedByReservations() throws Exception {
        startEnchant();
        ready();
        String a = "minecraft:enchanted_book#a0000001", b = "minecraft:enchanted_book#b0000002";
        reindex(List.of(sword(5), book(b, 1, 0, e("sharpness", 5)), book(a, 1, 0, e("sharpness", 5)), bottles(3000), anvils(3)));
        // One item takes the first key in sorted order, whatever order the index listed them in.
        JsonObject one = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        assertEquals(1, takeCount(one, a));
        assertEquals(0, takeCount(one, b));
        // The next one finds that entry reserved and takes the other.
        JsonObject two = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        assertEquals(0, takeCount(two, a));
        assertEquals(1, takeCount(two, b));
        ok(post(owner, "/api/v1/orders/" + one.get("id").getAsString() + "/cancel", "{}"));
        // Two items in one order draw both keys.
        ok(post(owner, "/api/v1/orders/" + two.get("id").getAsString() + "/cancel", "{}"));
        JsonObject both = placedOrder(line(SWORD, 2, e("sharpness", 5)));
        assertEquals(1, takeCount(both, a));
        assertEquals(1, takeCount(both, b));
    }

    @Test
    void theOrderChecksNameTheCodeAndTheLine() throws Exception {
        startEnchant();
        ready();
        String item = "{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}";
        String[][] cases = {
            {"nothing_wanted", line(SWORD, 1)},
            {"unknown_enchant", line(SWORD, 1, e("sharpnessx", 1))},
            {"invalid_level", line(SWORD, 1, e("sharpness", 9))},
            {"duplicate", line(SWORD, 1, e("sharpness", 5), e("sharpness", 4))},
            {"wrong_item", line(SWORD, 1, e("efficiency", 5))},
            {"conflict", line(SWORD, 1, e("sharpness", 5), e("smite", 5))},
            {"bad_request", line(SWORD, 5, e("sharpness", 5))},
            {"bad_request", line(SWORD, 0, e("sharpness", 5))},
            {"bad_request", line("diamond_sword", 1, e("sharpness", 5))},
            {"bad_request", line("minecraft:diamond_sword#ab12", 1, e("sharpness", 5))},
            {"bad_request", "{\"type\":\"enchant\",\"key\":\"" + SWORD + "\",\"count\":1,\"enchants\":\"sharpness\"}"},
            {"bad_request", "{\"type\":\"enchant\",\"key\":\"" + SWORD + "\",\"count\":1}"},
            {"bad_request", "{\"type\":\"enchant\",\"key\":\"" + SWORD + "\",\"count\":1,\"enchants\":[{\"id\":\"sharpness\",\"lvl\":\"5\"}]}"},
            {"bad_request", "{\"type\":\"enchant\",\"key\":\"" + SWORD + "\",\"count\":1,\"enchants\":[{\"id\":\"<script>alert(7)</script>\",\"lvl\":5}]}"},
            {"bad_request", "{\"type\":\"enchant\",\"key\":\"" + SWORD + "\",\"count\":1,\"enchants\":[{\"id\":\"sharpness\",\"lvl\":300}]}"},
            {"too_complex", line(SWORD, 1, e("sharpness", 1), e("a1", 1), e("a2", 1), e("a3", 1), e("a4", 1), e("a5", 1), e("a6", 1),
                e("a7", 1), e("a8", 1), e("a9", 1), e("a10", 1), e("a11", 1), e("a12", 1))},
        };
        for (String[] c : cases) {
            Res r = placeOrder("[" + item + "," + c[1] + "]");
            assertEquals(400, r.status, c[1] + " -> " + r.body);
            assertEquals(c[0], r.obj().get("enchant").getAsString(), c[1] + " -> " + r.body);
            assertEquals(1, r.obj().get("line").getAsInt(), c[1]);
            assertFalse(r.body.contains("script") || r.body.contains("alert"), "no echo of unvalidated input: " + r.body);
        }
        assertEquals(0, ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").size(), "nothing was stored");
        // An unknown line type is still refused as before.
        assertEquals(400, placeOrder("[{\"type\":\"bake\",\"key\":\"minecraft:stick\",\"count\":1}]").status);
    }

    @Test
    void theCapsAreCheckedBeforeAnythingIsPlanned() throws Exception {
        startEnchant();
        ready();
        String l = line(SWORD, 1, e("sharpness", 5));
        Res nine = placeEnchant(l, l, l, l, l, l, l, l, l);
        assertEquals(400, nine.status);
        assertEquals("too_complex", nine.obj().get("enchant").getAsString());
        assertEquals(8, nine.obj().get("line").getAsInt());
        String four = line(SWORD, 4, e("sharpness", 5));
        Res twenty = placeEnchant(four, four, four, four, four);
        assertEquals(400, twenty.status);
        assertEquals("too_complex", twenty.obj().get("enchant").getAsString());
        // 16 items in 4 lines pass the caps (and then run out of books).
        Res sixteen = placeEnchant(four, four, four, four);
        assertEquals("books_short", sixteen.obj().get("enchant").getAsString());
    }

    @Test
    void noBotAndAnUnsupportedVersionAreA409() throws Exception {
        startEnchant();
        Res noBot = placeEnchant(line(SWORD, 1, e("sharpness", 5)));
        assertEquals(409, noBot.status, noBot.body);
        assertEquals("no_bot", noBot.obj().get("enchant").getAsString());
        assertEquals(0, noBot.obj().get("line").getAsInt());
        // Other orders are never affected.
        ok(placeOrder("[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]"));
        ready();
        placedOrder(line(SWORD, 1, e("sharpness", 5)));
        ok(botPost("/api/v1/index", index("1.20.1", "Bob", items())));
        Res old = placeEnchant(line(SWORD, 1, e("sharpness", 5)));
        assertEquals(409, old.status, old.body);
        assertEquals("mc_unsupported", old.obj().get("enchant").getAsString());
    }

    @Test
    void planningRefusalsAreAll400WithTheCode() throws Exception {
        startEnchant();
        ready();
        String want = line(SWORD, 1, e("sharpness", 5));
        // no_base: no sword at all, or only a custom-named one, or one with no enchantment data.
        reindex(List.of(book(SHARP, 2, 0, e("sharpness", 5)), bottles(3000), anvils(3)));
        assertEquals("no_base", placeEnchant(want).obj().get("enchant").getAsString());
        reindex(List.of(it(SWORD, "minecraft:diamond_sword", 1, 1, "\"ench\":[],\"rc\":0,\"named\":true"), book(SHARP, 2, 0, e("sharpness", 5)), bottles(3000), anvils(3)));
        assertEquals("no_base", placeEnchant(want).obj().get("enchant").getAsString());
        reindex(List.of(it(SWORD, "minecraft:diamond_sword", 1, 1, null), book(SHARP, 2, 0, e("sharpness", 5)), bottles(3000), anvils(3)));
        Res nodata = placeEnchant(want);
        assertEquals("no_base", nodata.obj().get("enchant").getAsString());
        assertTrue(nodata.error().contains("no enchantment data"), nodata.error());
        // base_unusable: it carries an enchantment that was not ordered.
        reindex(List.of(it(SWORD, "minecraft:diamond_sword", 1, 1, "\"ench\":" + ench(e("unbreaking", 3)) + ",\"rc\":0"), book(SHARP, 2, 0, e("sharpness", 5)), bottles(3000), anvils(3)));
        assertEquals("base_unusable", placeEnchant(want).obj().get("enchant").getAsString());
        // books_short: no book.
        reindex(List.of(sword(1), bottles(3000), anvils(3)));
        assertEquals("books_short", placeEnchant(want).obj().get("enchant").getAsString());
        // too_expensive: a base whose stored repair cost makes every step 40 or more.
        reindex(List.of(it(SWORD, "minecraft:diamond_sword", 1, 1, "\"ench\":[],\"rc\":40"), book(SHARP, 2, 0, e("sharpness", 5)), bottles(3000), anvils(3)));
        assertEquals("too_expensive", placeEnchant(want).obj().get("enchant").getAsString());
        // An entry without rc is planned as 0 and the plan is an estimate: it can't be ordered.
        reindex(List.of(sword(1), it(SHARP, "minecraft:enchanted_book", 2, 1, "\"ench\":" + ench(e("sharpness", 5))), bottles(3000), anvils(3)));
        Res estimate = placeEnchant(want);
        assertEquals(400, estimate.status);
        assertEquals("books_short", estimate.obj().get("enchant").getAsString());
        assertTrue(estimate.error().contains("repair-cost"), estimate.error());
        assertEquals(0, ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").size());
    }

    @Test
    void theBottlesMustBeFreeAboveTheRepairReserve() throws Exception {
        startEnchant();
        ready();
        String want = line(SWORD, 1, e("sharpness", 5));
        List<String> items = items();
        items.set(3, bottles(1728 + B55 - 1));
        reindex(items);
        Res r = placeEnchant(want);
        assertEquals(400, r.status, r.body);
        assertEquals("xp_short", r.obj().get("enchant").getAsString());
        items.set(3, bottles(1728 + B55));
        reindex(items);
        placedOrder(want);
        // The order's own item line counts: bottles it orders as items are not free for the enchant take.
        reindex(items());
        Res mixed = placeOrder("[{\"type\":\"item\",\"key\":\"minecraft:experience_bottle\",\"count\":2990}," + want + "]");
        assertEquals(400, mixed.status, mixed.body);
        assertEquals("xp_short", mixed.obj().get("enchant").getAsString());
        assertEquals(1, mixed.obj().get("line").getAsInt());
    }

    @Test
    void theInventoryFormulaUsesTheBotsCarrySlots() throws Exception {
        startEnchant();
        String want = line(SWORD, 1, e("sharpness", 5));
        // 1 base + 1 book + 2 anvils + 1 shulker + ceil(bottles / 64) slots.
        int slots = 1 + 1 + 2 + 1 + (B55 + 63) / 64;
        ok(enchantBeat(block(true, MC11, slots - 1)));
        Res r = placeEnchant(want);
        assertEquals(400, r.status, r.body);
        assertEquals("inventory_full", r.obj().get("enchant").getAsString());
        ok(enchantBeat(block(true, MC11, slots)));
        placedOrder(want);
        // No carry reported: 20 is assumed.
        ok(enchantBeat(block(true, MC11, null)));
        placedOrder(want);
    }

    @Test
    void thePerOrderBudgetRefusesAndStoresNothing() throws Exception {
        startEnchant();
        ready();
        String want = line(SWORD, 1, e("sharpness", 5));
        routes.enchant().orderUnits = 1;
        Res units = placeEnchant(want);
        assertEquals(400, units.status, units.body);
        assertEquals("too_complex", units.obj().get("enchant").getAsString());
        routes.enchant().orderUnits = EnchantOrders.ORDER_UNITS;
        routes.enchant().orderMillis = 0;
        assertEquals("too_complex", placeEnchant(want).obj().get("enchant").getAsString());
        routes.enchant().orderMillis = EnchantOrders.ORDER_MILLIS;
        assertEquals(0, ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").size());
        assertEquals(3, available(SWORD));
        placedOrder(want);
    }

    @Test
    void theUnitsAreSummedOverTheItemsOfAnOrder() throws Exception {
        startEnchant();
        ready();
        List<String> items = items();
        items.set(1, book(SHARP, 4, 0, e("sharpness", 5)));
        reindex(items);
        // One item fits a budget that two items do not: the sum counts, not each item alone.
        long one = routes.enchant().planOne("s", "a", store.orderCounts("s", "a"), java.util.Map.of(), SWORD,
            List.of(new OrderStore.EnchantWant("minecraft:sharpness", 5)), 1000).units();
        assertTrue(one > 0);
        routes.enchant().orderUnits = one + one / 2;
        placedOrder(line(SWORD, 1, e("sharpness", 5)));
        Res two = placeEnchant(line(SWORD, 2, e("sharpness", 5)));
        assertEquals(400, two.status, two.body);
        assertEquals("too_complex", two.obj().get("enchant").getAsString());
    }

    @Test
    void anUndamagedBaseIsPreferred() throws Exception {
        startEnchant();
        ready();
        reindex(List.of(it("minecraft:diamond_sword#a1", "minecraft:diamond_sword", 1, 1, "\"ench\":[],\"rc\":0,\"damage\":300"),
            it("minecraft:diamond_sword#z9", "minecraft:diamond_sword", 1, 1, "\"ench\":[],\"rc\":0"),
            book(SHARP, 2, 0, e("sharpness", 5)), bottles(3000), anvils(3)));
        JsonObject o = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        assertEquals(1, takeCount(o, "minecraft:diamond_sword#z9"));
        assertEquals(0, takeCount(o, "minecraft:diamond_sword#a1"));
        // The damaged one is used when it is all there is.
        JsonObject next = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        assertEquals(1, takeCount(next, "minecraft:diamond_sword#a1"));
    }

    @Test
    void aChangeWhilePlanningMakesTheOrderBePlannedAgain() throws Exception {
        startEnchant();
        ready();
        String a = "minecraft:enchanted_book#a0000001", b = "minecraft:enchanted_book#b0000002";
        reindex(List.of(sword(5), book(a, 1, 0, e("sharpness", 5)), book(b, 1, 0, e("sharpness", 5)), bottles(3000), anvils(3)));
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        orders.afterPlan = () -> {
            if (!once.compareAndSet(false, true)) return;
            try {
                // Between planning and storing, another order takes the book this plan drew (the book's version moves on).
                orders.place("s", "a", List.of(new OrderStore.Line("enchant", SWORD, 1, false, List.of(new OrderStore.EnchantWant("minecraft:sharpness", 5)))), null);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        };
        JsonObject mine = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        orders.afterPlan = null;
        assertEquals(0, takeCount(mine, a), "re-planned: the first book is reserved by the other order now");
        assertEquals(1, takeCount(mine, b));
        assertEquals(2, ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").size());
        assertEquals(0, available(a) + available(b));
    }

    // ---------- storage ----------

    @Test
    void enchantsAndPlansSurviveAReloadAndStillReserve() throws Exception {
        startEnchant();
        ready();
        JsonObject o = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        OrderStore again = new OrderStore(data, store);
        again.load();
        assertEquals(o, again.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject());
        assertEquals(1L, again.reservedFor("s", "a").get("item:" + SHARP));
        assertEquals((long) B55, again.reservedFor("s", "a").get("item:minecraft:experience_bottle"));
        assertNull(again.reservedFor("s", "a").get("enchant:" + SWORD));
        // The bot binding is on disk but never in what a page or a bot reads.
        ok(claimEnchant());
        String disk = new String(java.nio.file.Files.readAllBytes(java.nio.file.Files.list(data.resolve("orders")).findFirst().orElseThrow()));
        assertTrue(disk.contains("\"claimedBy\": \"Bob\"") || disk.contains("\"claimedBy\":\"Bob\""), disk);
        assertFalse(get(owner, "/api/v1/orders?server=s&stash=a").body.contains("claimedBy"));
    }

    @Test
    void aRetryPlansAgainstTheCurrentIndex() throws Exception {
        startEnchant();
        ready();
        JsonObject o = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        ok(post(owner, "/api/v1/orders/" + o.get("id").getAsString() + "/cancel", "{}"));
        reindex(List.of(sword(1), bottles(3000), anvils(3))); // the books are gone
        Res gone = post(owner, "/api/v1/orders/" + o.get("id").getAsString() + "/retry", "{}");
        assertEquals(400, gone.status, gone.body);
        assertEquals("books_short", gone.obj().get("enchant").getAsString());
        String other = "minecraft:enchanted_book#d4444444";
        reindex(List.of(sword(1), book(other, 1, 0, e("sharpness", 5)), bottles(3000), anvils(3)));
        JsonObject again = ok(post(owner, "/api/v1/orders/" + o.get("id").getAsString() + "/retry", "{}")).getAsJsonObject("order");
        assertEquals(1, takeCount(again, other), "re-planned with the book that is there now");
        assertEquals("enchant", again.getAsJsonArray("lines").get(0).getAsJsonObject().get("type").getAsString());
        assertTrue(audit().contains("order.retry"));
        // Once only.
        assertEquals(409, post(owner, "/api/v1/orders/" + o.get("id").getAsString() + "/retry", "{}").status);
    }

    @Test
    void theShulkerCountTakesOneSlotPerEnchantedItem() throws Exception {
        startEnchant();
        ready();
        List<String> items = items();
        items.set(0, sword(100));
        items.set(1, book(SHARP, 100, 0, e("sharpness", 5)));
        reindex(items);
        String four = line(SWORD, 4, e("sharpness", 5));
        // 16 items are 16 slots: one shulker. Adding 12 shulker-sized item lines of dirt stays within 54 shulkers only while slots fit.
        JsonObject o = placedOrder(four, four, four, four);
        assertEquals(16, o.getAsJsonObject("enchant").getAsJsonArray("lines").size() * 4);
        assertEquals(16L, takeCount(o, SWORD));
        assertEquals(1, OrderStore.shulkersNeeded(OrderStore.Order.fromJson(o).lines, store.orderCounts("s", "a")));
        assertEquals(2, OrderStore.shulkersNeeded(List.of(new OrderStore.Line("enchant", SWORD, 16), new OrderStore.Line("item", "minecraft:dirt", 64 * 12)),
            store.orderCounts("s", "a")), "16 + 12 slots need two shulkers");
    }

    // ---------- curses ----------

    List<String> cursedItems() {
        List<String> l = items();
        l.set(1, book("minecraft:enchanted_book#c9999999", 1, 0, e("sharpness", 5), e("vanishing_curse", 1)));
        return l;
    }

    @Test
    void aCursedPlanWaitsForApprovalAndReservesNothing() throws Exception {
        startEnchant();
        ready();
        reindex(cursedItems());
        JsonObject o = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        assertEquals("held_curse", o.get("status").getAsString());
        assertEquals("curse", o.getAsJsonObject("hold").get("reason").getAsString());
        assertEquals("minecraft:vanishing_curse", o.getAsJsonObject("hold").getAsJsonArray("curse").get(0).getAsString());
        assertEquals("minecraft:vanishing_curse", o.getAsJsonObject("enchant").getAsJsonArray("curse").get(0).getAsString());
        assertTrue(o.getAsJsonObject("enchant").getAsJsonArray("take").isEmpty(), "reserves nothing");
        assertEquals(3, available(SWORD));
        assertEquals(3000, available("minecraft:experience_bottle"));
        assertEquals(1, orders.summaryJson("s", "a").get("open").getAsInt(), "it counts as open and is shown");
        assertTrue(orders.summaryJson("s", "a").get("active").isJsonNull());
        // The bot is told of nothing and a claim finds nothing.
        assertEquals(0, heartbeat("\"enchant\":" + block(true, MC11, 30)).obj().getAsJsonObject("orders").get("pending").getAsInt());
        assertEquals(204, claimEnchant().status);
        // Cancel is immediate.
        JsonObject c = ok(post(owner, "/api/v1/orders/" + o.get("id").getAsString() + "/cancel", "{}")).getAsJsonObject("order");
        assertEquals("cancelled", c.get("status").getAsString());
        assertTrue(c.get("hold") == null || c.get("hold").isJsonNull());
    }

    @Test
    void awaitingOrdersCountTowardTheOpenLimitAndAreThreePerUser() throws Exception {
        startEnchant();
        ready();
        reindex(cursedItems());
        String want = line(SWORD, 1, e("sharpness", 5));
        for (int i = 0; i < 3; i++) assertEquals("held_curse", placedOrder(want).get("status").getAsString());
        Res fourth = placeEnchant(want);
        assertEquals(400, fourth.status, fourth.body);
        assertTrue(fourth.error().contains("curse approval"), fourth.error());
        String uma = userToken("uma", USER_PERMS);
        Res theirs = post(uma, "/api/v1/orders", "{" + KEY + ",\"lines\":[" + want + "]}");
        assertEquals("held_curse", ok(theirs).getAsJsonObject("order").get("status").getAsString(), "the cap is per user");
        // 4 awaiting + 16 pending item orders fill the 20 open.
        for (int i = 0; i < 16; i++) ok(placeOrder("[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]"));
        Res full = placeOrder("[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]");
        assertEquals(400, full.status);
        assertTrue(full.error().contains("too many open orders"), full.error());
    }

    @Test
    void onlyTheOrdererMayAcceptAndTheChecksRunInOrder() throws Exception {
        startEnchant();
        ready();
        reindex(cursedItems());
        String uma = userToken("uma", USER_PERMS);
        String boss = userToken("boss", "{\"view\":true,\"order\":true,\"ordersAll\":true}");
        String lost = userToken("lost", "{\"view\":true,\"order\":true,\"stashes\":[{\"server\":\"other\",\"stash\":\"x\"}]}");
        String viewer = userToken("viewer", "{\"view\":true}");
        JsonObject o = ok(post(uma, "/api/v1/orders", "{" + KEY + ",\"lines\":[" + line(SWORD, 1, e("sharpness", 5)) + "]}")).getAsJsonObject("order");
        String id = o.get("id").getAsString();
        String body = "{\"curse\":[\"minecraft:vanishing_curse\"]}";
        String path = "/api/v1/orders/" + id + "/accept-curse";
        assertEquals(404, post(owner, "/api/v1/orders/o-000000000000/accept-curse", body).status, "unknown order");
        assertEquals(404, post(lost, path, body).status, "out of scope: seesStash comes first");
        assertEquals(403, post(owner, path, body).status, "the owner of the site is not the orderer");
        assertEquals(403, post(boss, path, body).status, "ordersAll can cancel but not accept");
        assertEquals(403, post(viewer, path, body).status, "needs the order permission");
        assertEquals("held_curse", orderById(id).get("status").getAsString());
        assertEquals(200, post(boss, "/api/v1/orders/" + id + "/cancel", "{}").status, "an ordersAll admin can cancel");
        // A pending order of the caller's own: 409, not 403.
        JsonObject p = ok(post(uma, "/api/v1/orders", "{" + KEY + ",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]}")).getAsJsonObject("order");
        assertEquals(409, post(uma, "/api/v1/orders/" + p.get("id").getAsString() + "/accept-curse", body).status);
        assertEquals(409, post(uma, path, body).status, "cancelled is not awaiting");
    }

    @Test
    void theListMustEqualTheStoredOne() throws Exception {
        startEnchant();
        ready();
        reindex(cursedItems());
        JsonObject o = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        String id = o.get("id").getAsString();
        String path = "/api/v1/orders/" + id + "/accept-curse";
        for (String bad : new String[]{"{\"curse\":[]}", "{\"curse\":[\"minecraft:binding_curse\"]}",
            "{\"curse\":[\"minecraft:vanishing_curse\",\"minecraft:binding_curse\"]}", "{\"curse\":[\"minecraft:vanishing_curse\",\"minecraft:vanishing_curse\"]}"}) {
            Res r = post(owner, path, bad);
            assertEquals(409, r.status, bad + " -> " + r.body);
            assertEquals("minecraft:vanishing_curse", r.obj().getAsJsonArray("curse").get(0).getAsString(), "the current list is returned");
            assertEquals(1, r.obj().getAsJsonArray("curse").size());
        }
        for (String malformed : new String[]{"{}", "[]", "{\"curse\":\"x\"}", "{\"curse\":[5]}", "{\"curse\":[\"Bad Id\"]}"}) {
            assertEquals(400, post(owner, path, malformed).status, malformed);
        }
        assertEquals("held_curse", orderById(id).get("status").getAsString());
        assertEquals(3000, available("minecraft:experience_bottle"), "nothing was reserved by the failed asks");
    }

    @Test
    void acceptingReplansAndReserves() throws Exception {
        startEnchant();
        ready();
        reindex(cursedItems());
        String id = placedOrder(line(SWORD, 1, e("sharpness", 5))).get("id").getAsString();
        JsonObject done = ok(post(owner, "/api/v1/orders/" + id + "/accept-curse", "{\"curse\":[\"minecraft:vanishing_curse\"]}")).getAsJsonObject("order");
        assertEquals("pending", done.get("status").getAsString());
        assertTrue(done.get("hold") == null || done.get("hold").isJsonNull());
        assertEquals(1, takeCount(done, "minecraft:enchanted_book#c9999999"));
        assertEquals(1, takeCount(done, SWORD));
        assertEquals(2, takeCount(done, "minecraft:anvil"));
        assertEquals(2, available(SWORD));
        assertTrue(audit().contains("order.accept-curse"));
        assertEquals(1, heartbeat("\"enchant\":" + block(true, MC11, 30)).obj().getAsJsonObject("orders").get("pending").getAsInt());
        assertEquals("accepted", ok(claimEnchant()).getAsJsonObject("order").get("status").getAsString());
        // Accepting twice: it is no longer awaiting.
        assertEquals(409, post(owner, "/api/v1/orders/" + id + "/accept-curse", "{\"curse\":[\"minecraft:vanishing_curse\"]}").status);
    }

    @Test
    void aPlanThatNowNeedsAnotherCurseStaysAwaitingWithTheNewList() throws Exception {
        startEnchant();
        ready();
        // Binding applies to armour only, so this runs on a chestplate (a sword drops it for free and needs no question).
        String chest = "minecraft:diamond_chestplate";
        String fresh = it(chest, chest, 1, 1, "\"ench\":[],\"rc\":0");
        reindex(List.of(fresh, book("minecraft:enchanted_book#c9999999", 1, 0, e("protection", 4), e("vanishing_curse", 1)), bottles(3000), anvils(3)));
        String id = placedOrder(line(chest, 1, e("protection", 4))).get("id").getAsString();
        assertEquals("held_curse", orderById(id).get("status").getAsString());
        reindex(List.of(fresh, book("minecraft:enchanted_book#c8888888", 1, 0, e("protection", 4), e("binding_curse", 1)), bottles(3000), anvils(3)));
        Res r = post(owner, "/api/v1/orders/" + id + "/accept-curse", "{\"curse\":[\"minecraft:vanishing_curse\"]}");
        assertEquals(409, r.status, r.body);
        assertEquals("minecraft:binding_curse", r.obj().getAsJsonArray("curse").get(0).getAsString());
        JsonObject now = orderById(id);
        assertEquals("held_curse", now.get("status").getAsString());
        assertEquals("minecraft:binding_curse", now.getAsJsonObject("hold").getAsJsonArray("curse").get(0).getAsString());
        assertTrue(now.getAsJsonObject("enchant").getAsJsonArray("take").isEmpty());
        assertEquals(3000, available("minecraft:experience_bottle"));
        // The new list is the one to accept.
        assertEquals("pending", ok(post(owner, "/api/v1/orders/" + id + "/accept-curse", "{\"curse\":[\"minecraft:binding_curse\"]}"))
            .getAsJsonObject("order").get("status").getAsString());
    }

    @Test
    void aCurseFreePlanIsAcceptedAndAnInfeasibleOneFailsTheOrder() throws Exception {
        startEnchant();
        ready();
        reindex(cursedItems());
        String clean = placedOrder(line(SWORD, 1, e("sharpness", 5))).get("id").getAsString();
        reindex(items()); // clean books are back
        JsonObject ok = ok(post(owner, "/api/v1/orders/" + clean + "/accept-curse", "{\"curse\":[\"minecraft:vanishing_curse\"]}")).getAsJsonObject("order");
        assertEquals("pending", ok.get("status").getAsString(), "a curse-free plan needs no consent");
        assertEquals(1, takeCount(ok, SHARP));
        ok(post(owner, "/api/v1/orders/" + clean + "/cancel", "{}"));
        reindex(cursedItems());
        String doomed = placedOrder(line(SWORD, 1, e("sharpness", 5))).get("id").getAsString();
        reindex(List.of(sword(1), bottles(3000), anvils(3))); // no book at all now
        JsonObject failed = ok(post(owner, "/api/v1/orders/" + doomed + "/accept-curse", "{\"curse\":[\"minecraft:vanishing_curse\"]}")).getAsJsonObject("order");
        assertEquals("failed", failed.get("status").getAsString());
        assertFalse(failed.get("message").getAsString().isEmpty());
        assertEquals(3000, available("minecraft:experience_bottle"));
        assertEquals(409, post(owner, "/api/v1/orders/" + doomed + "/accept-curse", "{\"curse\":[\"minecraft:vanishing_curse\"]}").status);
    }

    @Test
    void acceptingChecksTheBooksVersionAfterPlanningOutsideTheLock() throws Exception {
        startEnchant();
        ready();
        reindex(cursedItems());
        String want = line(SWORD, 1, e("sharpness", 5));
        String id = placedOrder(want).get("id").getAsString();
        List<String> items = cursedItems();
        items.set(1, book("minecraft:enchanted_book#c9999999", 2, 0, e("sharpness", 5), e("vanishing_curse", 1)));
        reindex(items);
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        orders.afterPlan = () -> {
            if (!once.compareAndSet(false, true)) return;
            try {
                orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:dirt", 1)), null); // the orders changed meanwhile
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        };
        JsonObject done = ok(post(owner, "/api/v1/orders/" + id + "/accept-curse", "{\"curse\":[\"minecraft:vanishing_curse\"]}")).getAsJsonObject("order");
        orders.afterPlan = null;
        assertEquals("pending", done.get("status").getAsString());
        assertTrue(once.get());
        assertEquals(1, takeCount(done, "minecraft:enchanted_book#c9999999"));
    }

    // ---------- claim and status ----------

    @Test
    void anEnchantOrderIsHandedOutOnlyToAVerifiedCapableBot() throws Exception {
        startEnchant();
        String id = null;
        ready();
        id = placedOrder(line(SWORD, 1, e("sharpness", 5))).get("id").getAsString();
        enchantClock.addAndGet(121_000);
        // The flag alone is not enough: no flag, no heartbeat block newer than about 2 minutes, the wrong version, no player.
        assertEquals(204, claim(null).status, "no flag");
        assertEquals(204, claim("\"enchantOrders\":0").status, "flag 0");
        assertEquals(204, claim("\"enchantOrders\":\"1\"").status, "a string is no flag");
        assertEquals(204, claimEnchant().status, "a stale heartbeat block");
        ok(enchantBeat(block(true, "1.21.4", null)));
        assertEquals(204, claimEnchant().status, "mc differs from the index");
        ok(enchantBeat(block(false, MC11, null)));
        assertEquals(204, claimEnchant().status, "disabled");
        ready();
        assertEquals(204, botPost("/api/v1/orders/claim", "{" + KEY + ",\"enchantOrders\":1}").status, "no player");
        // A forged claim: another player is not the stash owner (the same 409 as every bot route), and gets nothing.
        Res forged = botPost("/api/v1/orders/claim", "{" + KEY + ",\"player\":\"Mallory\",\"enchantOrders\":1}");
        assertEquals(409, forged.status);
        assertEquals("pending", orderById(id).get("status").getAsString());
        // The owner's bot, with everything in place, gets it.
        JsonObject got = ok(claimEnchant()).getAsJsonObject("order");
        assertEquals(id, got.get("id").getAsString());
        assertEquals("accepted", got.get("status").getAsString());
        assertFalse(got.toString().contains("claimedBy"));
    }

    @Test
    void anOlderBotNeverGetsAnEnchantOrderAndOtherOrdersAreUnchanged() throws Exception {
        startEnchant();
        ready();
        placedOrder(line(SWORD, 1, e("sharpness", 5)));
        JsonObject item = ok(placeOrder("[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]")).getAsJsonObject("order");
        // The legacy claim (no flags) skips the enchant order and takes the item order, as before.
        assertEquals(item.get("id").getAsString(), ok(claim(null)).getAsJsonObject("order").get("id").getAsString());
        assertEquals(200, status(item.get("id").getAsString(), null, "\"status\":\"delivered\"").status, "an order with no claimedBy needs no player");
        assertEquals(204, claim(null).status, "the enchant order stays pending for a bot that doesn't say it can enchant");
    }

    @Test
    void statusMustComeFromTheClaimingPlayer() throws Exception {
        startEnchant();
        ready();
        String id = placedOrder(line(SWORD, 1, e("sharpness", 5))).get("id").getAsString();
        ok(claimEnchant());
        assertEquals(409, status(id, "Mallory", "\"status\":\"in_progress\"").status, "another player is not the owner");
        Res none = status(id, null, "\"status\":\"in_progress\"");
        assertEquals(409, none.status, "no player at all");
        assertTrue(none.error().contains("another bot"), none.error());
        assertEquals("accepted", orderById(id).get("status").getAsString());
        assertEquals(200, status(id, "Bob", "\"status\":\"in_progress\",\"phase\":\"preflight\"").status);
        assertEquals(200, status(id, "bob", "\"status\":\"in_progress\",\"phase\":\"step 1\"").status, "names compare case-insensitively");
        assertEquals("in_progress", orderById(id).get("status").getAsString());
        // A held enchant order is handed back to its claimant only.
        assertEquals(id, ok(claimEnchant()).getAsJsonObject("order").get("id").getAsString());
    }

    @Test
    void aHeldEnchantOrderIsFailedWhenTheBotLosesTheCapabilityForGood() throws Exception {
        startEnchant();
        ready();
        String id = placedOrder(line(SWORD, 1, e("sharpness", 5))).get("id").getAsString();
        ok(claimEnchant());
        orderClock.addAndGet(200_000); // past the restart grace
        // The claims come without the flag; the grace rule of craft and kit orders covers enchant orders too.
        assertEquals(204, claim(null).status);
        assertEquals("accepted", orderById(id).get("status").getAsString());
        orderClock.addAndGet(130_000);
        enchantClock.addAndGet(130_000);
        assertEquals(204, claim(null).status);
        assertEquals(204, claim(null).status);
        JsonObject failed = orderById(id);
        assertEquals("failed", failed.get("status").getAsString());
        assertTrue(failed.get("message").getAsString().contains("can no longer enchant"));
        assertEquals(3, available(SWORD));
    }

    // ---------- results ----------

    String result(String enchantJson) {
        return "\"status\":\"delivered\",\"result\":{\"shulkers\":1,\"lines\":[{\"key\":\"" + SWORD + "\",\"delivered\":1}],\"enchant\":" + enchantJson + "}";
    }

    String goodResult() {
        return "{\"made\":[{\"key\":\"" + SWORD + "\",\"wanted\":1,\"made\":1}],\"short\":[],\"levels\":[{\"id\":\"minecraft:sharpness\",\"lvl\":5}],"
            + "\"bottlesUsed\":14,\"anvilsBroken\":0,\"returned\":[{\"key\":\"minecraft:anvil\",\"count\":2}]}";
    }

    @Test
    void aValidResultIsStoredInOneCleanForm() throws Exception {
        startEnchant();
        ready();
        String id = placedOrder(line(SWORD, 1, e("sharpness", 5))).get("id").getAsString();
        ok(claimEnchant());
        Res r = status(id, "Bob", result("{\"made\":[{\"key\":\"" + SWORD + "\",\"wanted\":1,\"made\":1,\"extra\":\"x\"}],\"short\":[{\"key\":\"" + SWORD
            + "\",\"reason\":\"cap\"}],\"levels\":[{\"id\":\"minecraft:sharpness\",\"lvl\":5}],\"bottlesUsed\":14,\"returned\":[],\"junk\":1}"));
        JsonObject en = ok(r).getAsJsonObject("order").getAsJsonObject("result").getAsJsonObject("enchant");
        assertEquals(1, en.getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        assertFalse(en.getAsJsonArray("made").get(0).getAsJsonObject().has("extra"));
        assertFalse(en.has("junk"));
        assertEquals(0, en.get("anvilsBroken").getAsInt());
        assertEquals("cap", en.getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
        assertEquals("delivered", orderById(id).get("status").getAsString());
        assertEquals(3, available(SWORD), "finished: released");
    }

    @Test
    void aBadResultIsA400AndNeverEchoesTheInput() throws Exception {
        startEnchant();
        ready();
        String sentinel = "SENTINEL-ZZ7";
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 401; i++) many.append(i == 0 ? "" : ",").append("{\"key\":\"").append(SWORD).append("\",\"count\":1}");
        many.append("]");
        String[] bad = {
            "5", "[]", "\"" + sentinel + "\"",
            // reasons: a closed set; interrupted is not one of them
            "{\"made\":[],\"short\":[{\"key\":\"" + SWORD + "\",\"reason\":\"interrupted\"}]}",
            "{\"made\":[],\"short\":[{\"key\":\"" + SWORD + "\",\"reason\":\"" + sentinel + "\"}]}",
            "{\"made\":[],\"short\":[{\"key\":\"" + SWORD + "\"}]}",
            "{\"made\":[],\"short\":[{\"key\":\"" + SWORD + "\",\"reason\":5}]}",
            // made <= wanted <= the line's count
            "{\"made\":[{\"key\":\"" + SWORD + "\",\"wanted\":1,\"made\":2}]}",
            "{\"made\":[{\"key\":\"" + SWORD + "\",\"wanted\":2,\"made\":1}]}",
            "{\"made\":[{\"key\":\"" + SWORD + "\",\"wanted\":-1,\"made\":0}]}",
            "{\"made\":[{\"key\":\"" + SWORD + "\",\"wanted\":1.5,\"made\":1}]}",
            "{\"made\":[{\"key\":\"" + SWORD + "\",\"wanted\":2147483648,\"made\":1}]}",
            // keys come from the order's lines
            "{\"made\":[{\"key\":\"" + sentinel + "\",\"wanted\":1,\"made\":1}]}",
            "{\"made\":[],\"short\":[{\"key\":\"" + sentinel + "\",\"reason\":\"cap\"}]}",
            "{\"made\":[{\"wanted\":1,\"made\":1}]}",
            // levels and counts
            "{\"levels\":[{\"id\":\"minecraft:sharpness\",\"lvl\":0}]}", "{\"levels\":[{\"id\":\"minecraft:sharpness\",\"lvl\":256}]}",
            "{\"levels\":[{\"id\":\"" + sentinel + "\",\"lvl\":1}]}", "{\"levels\":[{\"id\":\"minecraft:sharpness\"}]}",
            "{\"bottlesUsed\":-1}", "{\"bottlesUsed\":\"5\"}", "{\"anvilsBroken\":2147483648}",
            "{\"returned\":[{\"key\":\"minecraft:anvil\",\"count\":-1}]}", "{\"returned\":[{\"count\":1}]}",
            // lists at most 400
            "{\"returned\":" + many + "}",
            "{\"made\":\"x\"}", "{\"short\":{}}", "{\"levels\":[5]}"};
        for (String b : bad) {
            String id = placedOrder(line(SWORD, 1, e("sharpness", 5))).get("id").getAsString();
            ok(claimEnchant());
            Res r = status(id, "Bob", result(b));
            assertEquals(400, r.status, b.substring(0, Math.min(80, b.length())) + " -> " + r.body);
            assertFalse(r.body.contains(sentinel), "no echo: " + r.body);
            assertEquals("accepted", orderById(id).get("status").getAsString(), "nothing changed");
            ok(post(owner, "/api/v1/orders/" + id + "/cancel", "{}"));
            assertEquals(200, status(id, "Bob", "\"status\":\"cancelled\"").status);
        }
    }

    @Test
    void deliveredIsCheckedAgainstTheLineAndEnchantResultsNeedEnchantLines() throws Exception {
        startEnchant();
        ready();
        String id = placedOrder(line(SWORD, 1, e("sharpness", 5))).get("id").getAsString();
        ok(claimEnchant());
        Res over = status(id, "Bob", "\"status\":\"delivered\",\"result\":{\"shulkers\":1,\"lines\":[{\"key\":\"" + SWORD + "\",\"delivered\":2}]}");
        assertEquals(400, over.status, over.body);
        assertEquals(200, status(id, "Bob", "\"status\":\"delivered\",\"result\":{\"shulkers\":1,\"lines\":[{\"key\":\"" + SWORD + "\",\"delivered\":1}]}").status);
        String item = ok(placeOrder("[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]")).getAsJsonObject("order").get("id").getAsString();
        ok(claim(null));
        Res wrong = status(item, null, "\"status\":\"delivered\",\"result\":{\"shulkers\":1,\"lines\":[],\"enchant\":" + goodResult() + "}");
        assertEquals(400, wrong.status, wrong.body);
    }

    // ---------- force-cancel ----------

    String startedOrder() throws Exception {
        String id = placedOrder(line(SWORD, 1, e("sharpness", 5))).get("id").getAsString();
        ok(claimEnchant());
        assertEquals(200, status(id, "Bob", "\"status\":\"in_progress\",\"phase\":\"step 1\"").status);
        return id;
    }

    @Test
    void forceCancelNeedsConfirmAndKeepsTheReservationsUntilTheBotAcknowledges() throws Exception {
        startEnchant();
        ready();
        String id = startedOrder();
        String fc = "/api/v1/orders/" + id + "/force-cancel";
        assertEquals(409, post(owner, fc, "{\"confirm\":true}").status, "a cancel must be requested first");
        ok(post(owner, "/api/v1/orders/" + id + "/cancel", "{}"));
        for (String bad : new String[]{"{}", "{\"confirm\":false}", "{\"confirm\":\"true\"}", "{\"confirm\":1}", "[]"}) {
            assertEquals(400, post(owner, fc, bad).status, bad);
        }
        assertEquals("in_progress", orderById(id).get("status").getAsString());
        JsonObject done = ok(post(owner, fc, "{\"confirm\":true}")).getAsJsonObject("order");
        assertEquals("cancelled", done.get("status").getAsString());
        assertTrue(done.get("reservationsHeld").getAsBoolean(), "the page shows a note while the hold is active");
        assertFalse(done.toString().contains("forceHoldAt"));
        assertTrue(orderById(id).get("reservationsHeld").getAsBoolean());
        assertTrue(audit().contains("order.force-cancel") && audit().contains("enchant: true"), audit());
        // The reservations stay: the bot may still be using the books.
        assertEquals(1, available(SHARP));
        assertEquals(1L * B55, heartbeat(null).obj().getAsJsonObject("enchant").get("bottlesReserved").getAsLong());
        assertEquals(1, orders.reservedFor("s", "a").get("item:" + SWORD) == null ? 0 : 1);
        // The bot's next status call is the acknowledgement: answered 409, and the books are free again.
        assertEquals(409, status(id, "Bob", "\"status\":\"in_progress\"").status);
        assertEquals(2, available(SHARP));
        assertFalse(orderById(id).has("reservationsHeld"), "no note once released");
        assertEquals(0, heartbeat(null).obj().getAsJsonObject("enchant").get("bottlesReserved").getAsLong());
        assertEquals(409, status(id, "Bob", "\"status\":\"cancelled\"").status);
    }

    @Test
    void aSilentBotsReservationsEndTwoMinutesAfterItsHeartbeatGoesStale() throws Exception {
        startEnchant();
        ready();
        String id = startedOrder();
        ok(post(owner, "/api/v1/orders/" + id + "/cancel", "{}"));
        ok(post(owner, "/api/v1/orders/" + id + "/force-cancel", "{\"confirm\":true}"));
        assertEquals(1, available(SHARP));
        enchantClock.addAndGet(200_000); // stale for 80 s: still held
        assertEquals(1, available(SHARP));
        // ...and a bot that keeps sending heartbeats keeps it held.
        ok(enchantBeat(block(true, MC11, 30)));
        enchantClock.addAndGet(200_000);
        assertEquals(1, available(SHARP));
        enchantClock.addAndGet(50_000); // 250 s without a heartbeat: 2 minutes past stale
        assertEquals(2, available(SHARP));
        // "Clear" does not drop a held order either, before that.
    }

    @Test
    void forceCancelGuardsRunInOrder() throws Exception {
        startEnchant();
        ready();
        String uma = userToken("uma", USER_PERMS);
        String boss = userToken("boss", "{\"view\":true,\"order\":true,\"ordersAll\":true}");
        String lost = userToken("lost", "{\"view\":true,\"order\":true,\"stashes\":[{\"server\":\"other\",\"stash\":\"x\"}]}");
        String id = startedOrder();
        ok(post(owner, "/api/v1/orders/" + id + "/cancel", "{}"));
        String fc = "/api/v1/orders/" + id + "/force-cancel";
        assertEquals(404, post(lost, fc, "{\"confirm\":true}").status, "seesStash first");
        assertEquals(403, post(uma, fc, "{\"confirm\":true}").status, "another user's order");
        assertEquals(400, post(boss, fc, "{}").status, "an ordersAll admin may force-cancel, with confirm");
        assertEquals(200, post(boss, fc, "{\"confirm\":true}").status);
        assertEquals(409, post(boss, fc, "{\"confirm\":true}").status, "already finished");
    }

    @Test
    void forceCancelOfOtherOrdersIsAsBefore() throws Exception {
        startEnchant();
        ready();
        String item = ok(placeOrder("[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]")).getAsJsonObject("order").get("id").getAsString();
        ok(claim(null));
        ok(post(owner, "/api/v1/orders/" + item + "/cancel", "{}"));
        assertEquals(200, post(owner, "/api/v1/orders/" + item + "/force-cancel", "{}").status, "no confirm needed without enchant lines");
        assertFalse(audit().contains("enchant: true"));
    }

    // ---------- streamers ----------

    @Test
    void aForcedStreamerGetsTheRedactedResultAndNeverTheBindingOrTheNames() throws Exception {
        startEnchant();
        ready();
        String fay = userToken("fay", "{\"view\":true,\"order\":true,\"streamer\":true}");
        String sid = com.google.gson.JsonParser.parseString(get(fay, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().get("server").getAsString();
        assertNotEquals("s", sid);
        Res placed = post(fay, "/api/v1/orders", "{\"server\":\"" + sid + "\",\"stash\":\"a\",\"lines\":[" + line(SWORD, 1, e("sharpness", 5)) + "]}");
        JsonObject o = ok(placed).getAsJsonObject("order");
        String id = o.get("id").getAsString();
        assertEquals(sid, o.get("server").getAsString());
        ok(claimEnchant());
        String where = "Steve's chest 1234 64 -5678";
        // A returned key that is not an item key is refused when the bot reports it...
        assertEquals(400, status(id, "Bob", result("{\"made\":[],\"returned\":[{\"key\":\"" + where + "\",\"count\":1}]}")).status);
        ok(status(id, "Bob", result("{\"made\":[{\"key\":\"" + SWORD + "\",\"wanted\":1,\"made\":1}],\"short\":[{\"key\":\"" + SWORD
            + "\",\"reason\":\"cap\"}],\"levels\":[{\"id\":\"minecraft:sharpness\",\"lvl\":5}],\"bottlesUsed\":14,"
            + "\"returned\":[{\"key\":\"minecraft:anvil\",\"count\":2}]}")));
        String forced = get(fay, "/api/v1/orders?server=" + sid + "&stash=a").body;
        assertFalse(forced.contains("Steve") || forced.contains("1234") || forced.contains("claimedBy"), forced);
        JsonObject en = JsonParserOf(forced).getAsJsonArray("orders").get(0).getAsJsonObject().getAsJsonObject("result").getAsJsonObject("enchant");
        assertEquals("minecraft:anvil", en.getAsJsonArray("returned").get(0).getAsJsonObject().get("key").getAsString(), "safe keys are served");
        assertEquals("cap", en.getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
        assertFalse(get(owner, "/api/v1/orders?server=s&stash=a").body.contains("claimedBy"));
    }

    @Test
    void indexKeysThatAreNotItemKeysAreHiddenInAForcedStreamersOrders() throws Exception {
        startEnchant();
        ready();
        String odd = "Steve's book 1234 64 -5678";
        reindex(List.of(sword(3), book(odd, 2, 0, e("sharpness", 5)), bottles(3000), anvils(3)));
        String fay = userToken("fay", "{\"view\":true,\"order\":true,\"streamer\":true}");
        String sid = com.google.gson.JsonParser.parseString(get(fay, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().get("server").getAsString();
        Res placed = post(fay, "/api/v1/orders", "{\"server\":\"" + sid + "\",\"stash\":\"a\",\"lines\":[" + line(SWORD, 1, e("sharpness", 5)) + "]}");
        assertFalse(placed.body.contains("Steve"), placed.body);
        assertTrue(placed.body.contains(Redact.HIDDEN));
        assertTrue(get(owner, "/api/v1/orders?server=s&stash=a").body.contains("Steve's book"), "the owner of the site sees the real key");
    }

    @Test
    void theRedactionHidesAReasonOutsideTheClosedSet() {
        JsonObject order = com.google.gson.JsonParser.parseString("{\"result\":{\"enchant\":{\"short\":[{\"key\":\"minecraft:x\",\"reason\":\"at 12 64 90\"},"
            + "{\"key\":\"a b\",\"reason\":\"cap\"}],\"made\":[{\"key\":\"Steve\",\"wanted\":1,\"made\":1}],\"levels\":[{\"id\":\"Steve\",\"lvl\":1}],\"returned\":[]}}}").getAsJsonObject();
        Redact.enchantResult(order);
        JsonObject en = order.getAsJsonObject("result").getAsJsonObject("enchant");
        assertEquals(Redact.HIDDEN, en.getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
        assertEquals("minecraft:x", en.getAsJsonArray("short").get(0).getAsJsonObject().get("key").getAsString());
        assertEquals(Redact.HIDDEN, en.getAsJsonArray("short").get(1).getAsJsonObject().get("key").getAsString());
        assertEquals("cap", en.getAsJsonArray("short").get(1).getAsJsonObject().get("reason").getAsString());
        assertEquals(Redact.HIDDEN, en.getAsJsonArray("made").get(0).getAsJsonObject().get("key").getAsString());
        assertEquals(Redact.HIDDEN, en.getAsJsonArray("levels").get(0).getAsJsonObject().get("id").getAsString());
    }

    static JsonObject JsonParserOf(String s) {
        return com.google.gson.JsonParser.parseString(s).getAsJsonObject();
    }

    // ---------- the repair estimate ----------

    @Test
    void theRepairEstimateCoversTheEnchantBottles() throws Exception {
        startEnchant();
        ready();
        placedOrder(line(SWORD, 1, e("sharpness", 5)));
        JsonObject bottles = ok(get(owner, "/api/v1/repair?server=s&stash=a")).getAsJsonObject("estimate").getAsJsonObject("bottles");
        assertEquals(B55, bottles.get("openOrders").getAsInt());
        assertEquals(3000 - B55 - 1728, bottles.get("usable").getAsInt());
    }

    // ---------- review fixes: the hold cap, prune, accept with craft takes, Errors, budgets, reload ----------

    String forceCancelledOrder() throws Exception {
        String id = startedOrder();
        ok(post(owner, "/api/v1/orders/" + id + "/cancel", "{}"));
        ok(post(owner, "/api/v1/orders/" + id + "/force-cancel", "{\"confirm\":true}"));
        return id;
    }

    @Test
    void aHeldReservationEndsAfterTenMinutesWhateverTheBotSays() throws Exception {
        startEnchant();
        ready();
        forceCancelledOrder();
        assertEquals(1, available(SHARP));
        // The bot keeps heartbeating every 30 s: the hold stays for the first 9 minutes...
        for (int i = 0; i < 18; i++) {
            enchantClock.addAndGet(30_000);
            ok(enchantBeat(block(true, MC11, 30)));
        }
        assertEquals(1, available(SHARP), "9 minutes: still held");
        assertEquals(0, ok(post(owner, "/api/v1/orders/clear", "{" + KEY + "}")).get("removed").getAsInt(), "clear keeps a held order");
        // ...and is gone after 10 minutes (11 here), heartbeats or not.
        for (int i = 0; i < 4; i++) {
            enchantClock.addAndGet(30_000);
            ok(enchantBeat(block(true, MC11, 30)));
        }
        assertEquals(2, available(SHARP));
        assertEquals(0, heartbeat(null).obj().getAsJsonObject("enchant").get("bottlesReserved").getAsLong());
        assertEquals(1, ok(post(owner, "/api/v1/orders/clear", "{" + KEY + "}")).get("removed").getAsInt());
    }

    @Test
    void pruneNeverDropsAnOrderWhoseHoldIsActive() throws Exception {
        startEnchant();
        ready();
        String id = forceCancelledOrder();
        // More than KEEP (100) finished orders after it: the oldest finished ones go, but not the held one.
        for (int i = 0; i < OrderStore.KEEP + 5; i++) {
            String o = ok(placeOrder("[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]")).getAsJsonObject("order").get("id").getAsString();
            ok(post(owner, "/api/v1/orders/" + o + "/cancel", "{}"));
        }
        assertNotNull(orderById(id), "the held order is still there");
        assertEquals(1, available(SHARP));
        enchantClock.addAndGet(OrderStore.HOLD_MAX_MS + 1);
        assertEquals(2, available(SHARP));
        ok(placeOrder("[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]")); // the next placement prunes it
        assertNull(orderById(id));
    }

    @Test
    void acceptingDrawsAroundTheOrdersOwnCraftTake() throws Exception {
        startEnchant();
        ready();
        String a = "minecraft:enchanted_book#c0000001", b = "minecraft:enchanted_book#c0000002";
        reindex(List.of(sword(5), book(a, 1, 0, e("sharpness", 5), e("vanishing_curse", 1)), book(b, 1, 0, e("sharpness", 5), e("vanishing_curse", 1)), bottles(3000), anvils(3)));
        String id = placedOrder(line(SWORD, 1, e("sharpness", 5))).get("id").getAsString();
        // Give the held order a craft plan that takes the first book (as an order with a craft line and a shared stock would have).
        java.nio.file.Path file;
        try (var files = java.nio.file.Files.list(data.resolve("orders"))) {
            file = files.findFirst().orElseThrow();
        }
        JsonObject root = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
        JsonObject order = root.getAsJsonArray("orders").get(0).getAsJsonObject();
        order.add("craft", com.google.gson.JsonParser.parseString("{\"v\":1,\"take\":[{\"id\":\"" + a + "\",\"count\":1}],\"lines\":[],\"steps\":[]}"));
        java.nio.file.Files.writeString(file, root.toString());
        OrderStore again = new OrderStore(data, store);
        again.enchant(routes.enchant());
        again.load();
        OrderStore.Order done = again.acceptCurse(id, List.of("minecraft:vanishing_curse"));
        assertEquals("pending", done.status, done.message);
        assertEquals(1, OrderStore.takeOf(done.enchant).getOrDefault(b, 0L), "drew the book the craft take left");
        assertEquals(0, OrderStore.takeOf(done.enchant).getOrDefault(a, 0L));
        assertEquals(1L, again.reservedFor("s", "a").get("item:" + a));
    }

    @Test
    void anErrorFromThePlannerIsACleanBusyAndNothingIsHalfStored() throws Exception {
        startEnchant();
        ready();
        String want = line(SWORD, 1, e("sharpness", 5));
        routes.enchant().beforePlan = () -> {
            throw new AssertionError("boom-SENTINEL");
        };
        Res r = placeEnchant(want);
        assertEquals(503, r.status, r.body);
        assertFalse(r.body.contains("SENTINEL"));
        assertEquals(0, ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders").size());
        routes.enchant().beforePlan = null;
        // The per-user guard was released in the finally: the next create works.
        assertEquals(200, placeEnchant(want).status);
        // Accept: the order stays awaiting.
        reindex(cursedItems());
        String id = placedOrder(want).get("id").getAsString();
        routes.enchant().beforePlan = () -> {
            throw new AssertionError("boom");
        };
        assertEquals(503, post(owner, "/api/v1/orders/" + id + "/accept-curse", "{\"curse\":[\"minecraft:vanishing_curse\"]}").status);
        routes.enchant().beforePlan = null;
        assertEquals("held_curse", orderById(id).get("status").getAsString());
        limitClock.addAndGet(6_000);
        assertEquals(200, post(owner, "/api/v1/orders/" + id + "/accept-curse", "{\"curse\":[\"minecraft:vanishing_curse\"]}").status);
    }

    @Test
    void theOrderBudgetIsKeptAcrossTheReplansOfOnePlacement() throws Exception {
        startEnchant();
        ready();
        List<String> items = items();
        items.set(0, sword(10));
        items.set(1, book(SHARP, 10, 0, e("sharpness", 5)));
        reindex(items);
        long one = routes.enchant().planOne("s", "a", store.orderCounts("s", "a"), java.util.Map.of(), SWORD,
            List.of(new OrderStore.EnchantWant("minecraft:sharpness", 5)), 1000).units();
        routes.enchant().orderUnits = one + one / 2;
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        orders.afterPlan = () -> {
            if (!once.compareAndSet(false, true)) return;
            try {
                orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:dirt", 1)), null); // the book moves on: a re-plan
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        };
        Res r = placeEnchant(line(SWORD, 1, e("sharpness", 5)));
        orders.afterPlan = null;
        assertEquals(400, r.status, r.body);
        assertEquals("too_complex", r.obj().get("enchant").getAsString(), "two attempts are two items' worth of units, not a fresh budget");
    }

    @Test
    void oneCreateAtATimePerUser() throws Exception {
        startEnchant();
        ready();
        String want = line(SWORD, 1, e("sharpness", 5));
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        routes.enchant().beforePlan = () -> {
            entered.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        };
        java.util.concurrent.CompletableFuture<Res> first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return placeOrder("[" + want + "]");
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        Res second = placeOrder("[" + want + "]");
        assertEquals(429, second.status, second.body);
        release.countDown();
        assertEquals(200, first.get(5, java.util.concurrent.TimeUnit.SECONDS).status);
        routes.enchant().beforePlan = null;
        limitClock.addAndGet(6_000);
        assertEquals(200, placeOrder("[" + want + "]").status, "released in the finally");
    }

    @Test
    void aResultLineNeedsItsKeyAndDelivered() throws Exception {
        startEnchant();
        ready();
        String id = startedOrder();
        for (String l : new String[]{"{\"delivered\":1}", "{\"key\":\"" + SWORD + "\"}", "{\"key\":\"" + SWORD + "\",\"delivered\":-1}", "5"}) {
            Res r = status(id, "Bob", "\"status\":\"delivered\",\"result\":{\"shulkers\":1,\"lines\":[" + l + "]}");
            assertEquals(400, r.status, l + r.body);
        }
        for (String k : new String[]{"Steve's chest", "minecraft:anvil#zz", "A:B", ""}) {
            assertEquals(400, status(id, "Bob", result("{\"returned\":[{\"key\":\"" + k + "\",\"count\":1}]}")).status, k);
        }
        assertEquals("in_progress", orderById(id).get("status").getAsString());
    }

    // ---------- reload ----------

    OrderStore reloaded() throws Exception {
        OrderStore again = new OrderStore(data, store);
        again.enchant(routes.enchant());
        again.load();
        return again;
    }

    @Test
    void claimedByAndTheHoldSurviveARestart() throws Exception {
        startEnchant();
        ready();
        String id = startedOrder();
        OrderStore again = reloaded();
        // claimedBy is enforced after a restart.
        assertEquals(409, assertThrows(OrderStore.OrderException.class, () -> again.status(id, "in_progress", null, null, null, null, null)).status);
        assertEquals(409, assertThrows(OrderStore.OrderException.class, () -> again.status(id, "in_progress", null, null, null, null, "Mallory")).status);
        assertEquals("in_progress", again.status(id, "in_progress", null, null, null, null, "bob").status);
        ok(post(owner, "/api/v1/orders/" + id + "/cancel", "{}"));
        ok(post(owner, "/api/v1/orders/" + id + "/force-cancel", "{\"confirm\":true}"));
        // The hold is still active after a restart, ends with the stale heartbeat, and the cap counts from the force-cancel.
        OrderStore after = reloaded();
        assertEquals(1L, after.reservedFor("s", "a").get("item:" + SHARP));
        assertEquals((long) B55, after.enchantBottlesReserved("s", "a"));
        enchantClock.addAndGet(250_000);
        assertNull(reloaded().reservedFor("s", "a").get("item:" + SHARP));
    }

    @Test
    void aReleasedHoldNeverSwitchesBackOn() throws Exception {
        startEnchant();
        ready();
        forceCancelledOrder();
        assertEquals(1, available(SHARP));
        enchantClock.addAndGet(250_000); // quiet for more than 4 minutes: released
        assertEquals(2, available(SHARP));
        // The bot is heard again within the 10-minute cap: the reservation stays released.
        ok(enchantBeat(block(true, MC11, 30)));
        assertEquals(2, available(SHARP));
        assertEquals(0, heartbeat(null).obj().getAsJsonObject("enchant").get("bottlesReserved").getAsLong());
        ok(enchantBeat(block(true, MC11, 30)));
        enchantClock.addAndGet(30_000);
        ok(enchantBeat(block(true, MC11, 30)));
        assertEquals(2, available(SHARP));
    }

    @Test
    void bottlesUsedIsBoundedByTheTakePlusTheReservePlus2048() throws Exception {
        startEnchant();
        ready();
        String id = startedOrder();
        long limit = B55 + 1728 + 2048;
        assertEquals(400, status(id, "Bob", result("{\"bottlesUsed\":" + (limit + 1) + "}")).status);
        assertEquals("in_progress", orderById(id).get("status").getAsString());
        assertEquals(200, status(id, "Bob", result("{\"bottlesUsed\":" + limit + "}")).status, "a top-up beyond the held bottles is fine");
    }

    @Test
    void anAwaitingOrderAndItsHoldSurviveARestart() throws Exception {
        startEnchant();
        ready();
        reindex(cursedItems());
        String id = placedOrder(line(SWORD, 1, e("sharpness", 5))).get("id").getAsString();
        OrderStore again = reloaded();
        JsonObject o = again.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("held_curse", o.get("status").getAsString());
        assertEquals("minecraft:vanishing_curse", o.getAsJsonObject("hold").getAsJsonArray("curse").get(0).getAsString());
        assertTrue(again.reservedFor("s", "a").isEmpty(), "it reserves nothing");
        assertThrows(OrderStore.OrderException.class, () -> again.acceptCurse(id, List.of()));
        assertEquals("pending", again.acceptCurse(id, List.of("minecraft:vanishing_curse")).status);
        assertEquals(1L, again.reservedFor("s", "a").get("item:minecraft:enchanted_book#c9999999"));
    }
}

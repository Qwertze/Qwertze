package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crafting a missing base item for an enchant order (ENCHANT-CONTRACT draft 3.4, item 1.9): the craft and the enchant plan share the
 * order's reservations and budget; the base is made, not delivered; it needs both the craft and the enchant capability at claim.
 * The stash has no sword; a diamond sword is two diamonds and a stick (the synthetic recipe table below).
 */
class EnchantBaseCraftTest extends EnchantHarness {
    static final String SWORD = "minecraft:diamond_sword";
    static final String DIAMOND = "minecraft:diamond";
    static final String STICK = "minecraft:stick";
    static final String CURSED = "minecraft:enchanted_book#c9999999";

    /** The fixtures' recipes plus a diamond sword made from the given pattern (ingredient id for the "#" cell). */
    static RecipeTable table(String firstIngredient) {
        Map<String, com.google.gson.JsonObject> r = RecipeFixtures.recipes();
        r.put("diamond_sword", RecipeFixtures.j(RecipeFixtures.shaped("{\"#\":\"" + firstIngredient + "\",\"S\":\"minecraft:stick\"}",
            firstIngredient.equals(DIAMOND) ? "[\"#\",\"#\",\"S\"]" : "[\"#\",\"S\"]", SWORD, 1)));
        return RecipeNormalizer.normalize("1.99.9", r, RecipeFixtures.tags(), RecipeFixtures.items()).table();
    }

    /** Two Sharpness V books, bottles, anvils, diamonds and sticks; no sword. */
    List<String> stock(long swords, long diamonds, long anvils) {
        List<String> l = new ArrayList<>();
        if (swords > 0) l.add(sword(swords));
        l.add(book(SHARP, 2, 0, e("sharpness", 5)));
        l.add(bottles(3000));
        l.add(anvils(anvils));
        l.add(item(DIAMOND, (int) diamonds, 64));
        l.add(item(STICK, 10, 64));
        return l;
    }


    @Override
    List<String> items() {
        return stock(0, 10, 3);
    }

    /** A server whose stash can enchant and craft (the recipe table is for 1.21.11). */
    void startCrafty(RecipeTable t) throws Exception {
        startEnchant();
        recipes.mc = MC11;
        recipes.table = t;
        ready();
        ready(50);
    }

    long available(String key) throws Exception {
        JsonElement n = availableItem(key);
        return n == null ? -1 : n.getAsLong();
    }

    static JsonObject plan0(JsonObject order) {
        return order.getAsJsonObject("enchant").getAsJsonArray("lines").get(0).getAsJsonObject().getAsJsonArray("plans").get(0).getAsJsonObject();
    }

    static Map<String, Long> craftTake(JsonObject order) {
        Map<String, Long> m = new TreeMap<>();
        for (JsonElement t : order.getAsJsonObject("craft").getAsJsonArray("take")) m.put(t.getAsJsonObject().get("id").getAsString(), t.getAsJsonObject().get("count").getAsLong());
        return m;
    }

    static long forEnchant(JsonObject order, String key) {
        for (JsonElement t : order.getAsJsonObject("craft").getAsJsonArray("forEnchant")) {
            if (t.getAsJsonObject().get("key").getAsString().equals(key)) return t.getAsJsonObject().get("count").getAsLong();
        }
        return 0;
    }

    static long madeBySteps(JsonObject order, String result) {
        long n = 0;
        for (JsonElement s : order.getAsJsonObject("craft").getAsJsonArray("steps")) {
            JsonObject o = s.getAsJsonObject();
            if (o.get("result").getAsString().equals(result)) n += o.get("count").getAsLong() * o.get("fills").getAsLong();
        }
        return n;
    }

    // ---------- the plan ----------

    @Test
    void aMissingBaseIsCraftedAndTheEnchantPlanUsesIt() throws Exception {
        startCrafty(table(DIAMOND));
        JsonObject o = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        assertEquals("pending", o.get("status").getAsString());
        // The craft block makes one sword (a stick and two diamonds) and says it is for the enchant plans; no craft line was ordered.
        JsonObject craft = o.getAsJsonObject("craft");
        assertEquals(1, craft.getAsJsonArray("lines").size());
        assertEquals(SWORD, craft.getAsJsonArray("lines").get(0).getAsJsonObject().get("key").getAsString());
        assertEquals(1, craft.getAsJsonArray("lines").get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(1, forEnchant(o, SWORD));
        assertEquals(1, madeBySteps(o, SWORD));
        assertEquals(Map.of(DIAMOND, 2L, STICK, 1L), craftTake(o));
        assertEquals(1, o.getAsJsonArray("lines").size(), "the order's own lines are only the enchant line");
        assertEquals("enchant", o.getAsJsonArray("lines").get(0).getAsJsonObject().get("type").getAsString());
        // The enchant plan starts from a fresh base that is flagged crafted; the take has no sword and no book of the sword.
        JsonObject base = plan0(o).getAsJsonArray("materials").get(0).getAsJsonObject();
        assertEquals("base", base.get("kind").getAsString());
        assertEquals(SWORD, base.get("key").getAsString());
        assertTrue(base.get("crafted").getAsBoolean());
        assertEquals(0, base.getAsJsonArray("ench").size());
        assertEquals(0, base.get("rc").getAsInt());
        assertFalse(plan0(o).getAsJsonArray("materials").get(1).getAsJsonObject().has("crafted"));
        assertEquals(0, takeCount(o, SWORD));
        assertEquals(1, takeCount(o, SHARP));
        assertEquals(2, takeCount(o, "minecraft:anvil"));
        // Both takes are reserved like item lines.
        assertEquals(8, available(DIAMOND));
        assertEquals(9, available(STICK));
        assertEquals(1, available(SHARP));
        assertEquals(-1, available(SWORD), "no sword in the stash");
        // It is a plain enchanted-item order for the shulker count: one slot, nothing delivered for the craft.
        assertEquals(1, OrderStore.shulkersNeeded(OrderStore.Order.fromJson(o).lines, store.orderCounts("s", "a")));
        // Cancelling releases both.
        ok(post(owner, "/api/v1/orders/" + o.get("id").getAsString() + "/cancel", "{}"));
        assertEquals(10, available(DIAMOND));
        assertEquals(2, available(SHARP));
    }

    @Test
    void oneBaseIsCraftedPerMissingItemAndARealOneIsUsedFirst() throws Exception {
        startCrafty(table(DIAMOND));
        reindex(stock(1, 10, 3)); // one real sword; two Sharpness V books, so two items
        JsonObject o = placedOrder(line(SWORD, 2, e("sharpness", 5)));
        assertEquals(1, forEnchant(o, SWORD));
        assertEquals(1, takeCount(o, SWORD), "the real sword is drawn, the second is crafted");
        assertEquals(2, takeCount(o, SHARP));
        JsonArray plans = o.getAsJsonObject("enchant").getAsJsonArray("lines").get(0).getAsJsonObject().getAsJsonArray("plans");
        int crafted = 0;
        for (JsonElement p : plans) {
            JsonObject b = p.getAsJsonObject().getAsJsonArray("materials").get(0).getAsJsonObject();
            if (b.has("crafted")) crafted++;
        }
        assertEquals(1, crafted, "exactly one plan has a crafted base");
        assertEquals(0, available(SWORD));
        assertEquals(8, available(DIAMOND));
        // Two items with no sword at all: two bases, one craft line of 2.
        ok(post(owner, "/api/v1/orders/" + o.get("id").getAsString() + "/cancel", "{}"));
        reindex(stock(0, 10, 6));
        JsonObject two = placedOrder(line(SWORD, 2, e("sharpness", 5)));
        assertEquals(2, forEnchant(two, SWORD));
        assertEquals(2, two.getAsJsonObject("craft").getAsJsonArray("lines").get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(2, madeBySteps(two, SWORD));
        assertEquals(Map.of(DIAMOND, 4L, STICK, 2L), craftTake(two));
    }

    @Test
    void theCraftAndTheEnchantTakeNeverUseTheSameStock() throws Exception {
        // A synthetic recipe: a sword is an anvil and a stick. The enchant take needs 2 anvils (none stands at the stash).
        startCrafty(table("minecraft:anvil"));
        reindex(stock(0, 0, 3));
        JsonObject o = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        assertEquals(2, takeCount(o, "minecraft:anvil"));
        assertEquals(Map.of("minecraft:anvil", 1L, STICK, 1L), craftTake(o));
        assertEquals(0, available("minecraft:anvil"), "2 for the anvil work and 1 for the craft: all 3 are reserved, none twice");
        // With only 2 anvils the craft has nothing left to use: the craft error, not an overdrawn stash.
        ok(post(owner, "/api/v1/orders/" + o.get("id").getAsString() + "/cancel", "{}"));
        reindex(stock(0, 0, 2));
        Res r = placeEnchant(line(SWORD, 1, e("sharpness", 5)));
        assertEquals(400, r.status, r.body);
        assertTrue(r.error().contains("craftable"), r.error());
        assertFalse(r.obj().has("enchant"), "the craft error shape, not an enchant code");
        assertEquals(0, r.obj().get("line").getAsInt());
        assertEquals(2, available("minecraft:anvil"), "nothing was stored");
    }

    @Test
    void aCraftShortageIsTheCraftError() throws Exception {
        startCrafty(table(DIAMOND));
        reindex(stock(0, 1, 3)); // one diamond: a sword needs two
        Res r = placeEnchant(line(SWORD, 1, e("sharpness", 5)));
        assertEquals(400, r.status, r.body);
        assertTrue(r.error().startsWith("only 0 craftable for"), r.error());
        assertEquals(0, r.obj().get("line").getAsInt());
        assertFalse(r.obj().has("enchant"));
        assertEquals(1, available(DIAMOND), "nothing was reserved");
    }

    @Test
    void craftedBasesHeldForLaterItemsCountAgainstTheCarrySlots() throws Exception {
        startCrafty(table(DIAMOND));
        List<String> l = stock(0, 10, 3);
        l.set(0, book(SHARP, 3, 0, e("sharpness", 5)));
        reindex(l);
        // Each item needs 1 + 1 book + 2 anvils + 1 shulker + 1 bottle slot = 6; the bot holds the bases of the items after it: 6 + 2 = 8 for the first.
        ok(enchantBeat(block(true, MC11, 7)));
        Res r = placeEnchant(line(SWORD, 3, e("sharpness", 5)));
        assertEquals(400, r.status, r.body);
        assertEquals("inventory_full", r.obj().get("enchant").getAsString());
        assertTrue(r.error().contains("8 inventory slots"), r.error());
        assertEquals(10, available(DIAMOND), "nothing was stored");
        // Two crafted bases (the second item holds none for later) fit 7, and 3 fit 8.
        assertEquals(1, forEnchant(placedOrder(line(SWORD, 2, e("sharpness", 5))), SWORD) - 1);
        ok(enchantBeat(block(true, MC11, 8)));
        ok(post(owner, "/api/v1/orders/" + orders.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject().get("id").getAsString() + "/cancel", "{}"));
        assertEquals(3, forEnchant(placedOrder(line(SWORD, 3, e("sharpness", 5))), SWORD));
    }

    // ---------- refusals ----------

    @Test
    void withoutTheCraftCapabilityAMissingBaseIsNoBase() throws Exception {
        startEnchant();
        recipes.mc = MC11;
        recipes.table = table(DIAMOND);
        ready(); // the enchant capability only: the bot never said it can craft
        Res r = placeEnchant(line(SWORD, 1, e("sharpness", 5)));
        assertEquals(400, r.status, r.body);
        assertEquals("no_base", r.obj().get("enchant").getAsString());
        assertFalse(r.error().contains("crafted"), "the existing message");
    }

    @Test
    void aNetheriteBaseIsNeverCraftedButOneInTheStashIsFine() throws Exception {
        startCrafty(table(DIAMOND));
        Res r = placeEnchant(line("minecraft:netherite_sword", 1, e("sharpness", 5)));
        assertEquals(400, r.status, r.body);
        assertEquals("no_base", r.obj().get("enchant").getAsString());
        assertTrue(r.error().contains("smithing"), r.error());
        // A netherite sword that is in the stash is used as before, with no craft block.
        List<String> l = stock(0, 10, 3);
        l.add(it("minecraft:netherite_sword", "minecraft:netherite_sword", 1, 1, "\"ench\":[],\"rc\":0"));
        reindex(l);
        JsonObject o = placedOrder(line("minecraft:netherite_sword", 1, e("sharpness", 5)));
        assertTrue(o.get("craft").isJsonNull());
        assertEquals(1, takeCount(o, "minecraft:netherite_sword"));
    }

    @Test
    void anItemWithNoRecipeIsNoBaseToo() throws Exception {
        startCrafty(table(DIAMOND));
        Res r = placeEnchant(line("minecraft:trident", 1, e("loyalty", 3)));
        assertEquals(400, r.status, r.body);
        assertEquals("no_base", r.obj().get("enchant").getAsString());
        assertTrue(r.error().contains("no crafting recipe"), r.error());
    }

    @Test
    void aBaseThatCarriesOtherEnchantmentsIsReplacedByACraftedOne() throws Exception {
        startCrafty(table(DIAMOND));
        List<String> l = stock(0, 10, 3);
        l.add(it(SWORD, SWORD, 1, 1, "\"ench\":" + ench(e("unbreaking", 3)) + ",\"rc\":0"));
        reindex(l);
        // The sword has Unbreaking, which was not ordered: it is no usable base, so one is crafted (the stash's sword stays).
        JsonObject o = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        assertEquals(1, forEnchant(o, SWORD));
        assertEquals(0, takeCount(o, SWORD));
    }

    // ---------- re-plans keep the craft ----------

    @Test
    void retryPlansTheCraftAgain() throws Exception {
        startCrafty(table(DIAMOND));
        JsonObject o = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        String id = o.get("id").getAsString();
        ok(post(owner, "/api/v1/orders/" + id + "/cancel", "{}"));
        assertEquals(10, available(DIAMOND));
        JsonObject again = ok(post(owner, "/api/v1/orders/" + id + "/retry", "{}")).getAsJsonObject("order");
        assertEquals(1, forEnchant(again, SWORD));
        assertTrue(plan0(again).getAsJsonArray("materials").get(0).getAsJsonObject().get("crafted").getAsBoolean());
        assertEquals(8, available(DIAMOND), "the craft take is reserved once, by the new order");
        assertEquals(0, takeCount(again, SWORD));
    }

    @Test
    void acceptingACurseKeepsTheCraft() throws Exception {
        startCrafty(table(DIAMOND));
        List<String> l = stock(0, 10, 3);
        l.set(0, book(CURSED, 1, 0, e("sharpness", 5), e("vanishing_curse", 1)));
        reindex(l);
        JsonObject held = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        String id = held.get("id").getAsString();
        assertEquals("held_curse", held.get("status").getAsString());
        assertEquals(1, forEnchant(held, SWORD), "the craft is planned with the held order");
        assertTrue(plan0(held).getAsJsonArray("materials").get(0).getAsJsonObject().get("crafted").getAsBoolean());
        assertTrue(held.getAsJsonObject("enchant").getAsJsonArray("take").isEmpty());
        assertEquals(10, available(DIAMOND), "a held order reserves nothing");
        JsonObject done = ok(post(owner, "/api/v1/orders/" + id + "/accept-curse", "{\"curse\":[\"minecraft:vanishing_curse\"]}")).getAsJsonObject("order");
        assertEquals("pending", done.get("status").getAsString());
        assertEquals(held.getAsJsonObject("craft"), done.getAsJsonObject("craft"), "the craft block is the one planned at placement");
        assertTrue(plan0(done).getAsJsonArray("materials").get(0).getAsJsonObject().get("crafted").getAsBoolean());
        assertEquals(0, takeCount(done, SWORD));
        assertEquals(1, takeCount(done, CURSED));
        assertEquals(8, available(DIAMOND), "the craft take is reserved from now on");
        assertEquals(9, available(STICK));
    }

    @Test
    void acceptingFailsWhenTheCraftStockIsGone() throws Exception {
        startCrafty(table(DIAMOND));
        List<String> l = stock(0, 10, 3);
        l.set(0, book(CURSED, 1, 0, e("sharpness", 5), e("vanishing_curse", 1)));
        reindex(l);
        String id = placedOrder(line(SWORD, 1, e("sharpness", 5))).get("id").getAsString();
        List<String> poorer = stock(0, 1, 3);
        poorer.set(0, book(CURSED, 1, 0, e("sharpness", 5), e("vanishing_curse", 1)));
        reindex(poorer);
        JsonObject failed = ok(post(owner, "/api/v1/orders/" + id + "/accept-curse", "{\"curse\":[\"minecraft:vanishing_curse\"]}")).getAsJsonObject("order");
        assertEquals("failed", failed.get("status").getAsString());
        assertEquals(1, available(DIAMOND));
    }

    // ---------- claim ----------

    @Test
    void theClaimNeedsBothFlags() throws Exception {
        startCrafty(table(DIAMOND));
        String id = placedOrder(line(SWORD, 1, e("sharpness", 5))).get("id").getAsString();
        assertEquals(204, claim("\"enchantOrders\":1").status, "enchant alone");
        assertEquals(204, claim("\"craftOrders\":1").status, "craft alone");
        assertEquals(204, claim("\"enchantOrders\":1,\"craftOrders\":0").status, "craft flag 0");
        assertEquals("pending", orderById(id).get("status").getAsString());
        JsonObject got = ok(claim("\"enchantOrders\":1,\"craftOrders\":1")).getAsJsonObject("order");
        assertEquals(id, got.get("id").getAsString());
        assertEquals("accepted", got.get("status").getAsString());
        assertEquals(1, forEnchant(got, SWORD), "the bot gets the craft block and the plans");
    }

    // ---------- persistence ----------

    @Test
    void theCraftAndTheCraftedFlagSurviveARestart() throws Exception {
        startCrafty(table(DIAMOND));
        JsonObject o = placedOrder(line(SWORD, 1, e("sharpness", 5)));
        OrderStore again = new OrderStore(data, store);
        again.craft(craft);
        again.enchant(routes.enchant());
        again.load();
        JsonObject back = null;
        for (JsonElement x : again.listJson("s", "a").getAsJsonArray("orders")) {
            if (x.getAsJsonObject().get("id").getAsString().equals(o.get("id").getAsString())) back = x.getAsJsonObject();
        }
        assertNotNull(back);
        assertEquals(o.getAsJsonObject("craft"), back.getAsJsonObject("craft"));
        assertEquals(o.getAsJsonObject("enchant"), back.getAsJsonObject("enchant"));
        assertEquals(1, forEnchant(back, SWORD));
        assertTrue(plan0(back).getAsJsonArray("materials").get(0).getAsJsonObject().get("crafted").getAsBoolean());
        assertEquals(2L, again.reservedFor("s", "a").get("item:" + DIAMOND), "the craft take is reserved after a restart");
        assertEquals(Map.of(SWORD, 1), OrderStore.forEnchant(back.getAsJsonObject("craft")));
    }

    // ---------- the plan route ----------

    @Test
    void thePreviewSaysTheBaseWillBeCrafted() throws Exception {
        startCrafty(table(DIAMOND));
        JsonObject d = ok(post(owner, "/api/v1/enchant/plan", "{" + KEY + ",\"key\":\"" + SWORD + "\",\"enchants\":" + ench(e("sharpness", 5)) + "}"));
        assertTrue(d.get("ok").getAsBoolean());
        assertTrue(d.get("canOrder").getAsBoolean());
        assertTrue(d.getAsJsonObject("plan").getAsJsonArray("materials").get(0).getAsJsonObject().get("crafted").getAsBoolean());
        assertEquals(0, d.getAsJsonObject("availability").getAsJsonObject("base").get("have").getAsInt());
        assertTrue(d.getAsJsonObject("availability").getAsJsonObject("base").get("craft").getAsBoolean());
        // A netherite sword: the refusal says why.
        JsonObject n = ok(post(owner, "/api/v1/enchant/plan", "{" + KEY + ",\"key\":\"minecraft:netherite_sword\",\"enchants\":" + ench(e("sharpness", 5)) + "}"));
        assertFalse(n.get("ok").getAsBoolean());
        assertEquals("no_base", n.get("code").getAsString());
        assertTrue(n.get("message").getAsString().contains("smithing"));
    }

    @Test
    void thePreviewWithoutTheCraftCapabilityHasNoCraft() throws Exception {
        startEnchant();
        recipes.mc = MC11;
        recipes.table = table(DIAMOND);
        ready();
        JsonObject n = ok(post(owner, "/api/v1/enchant/plan", "{" + KEY + ",\"key\":\"" + SWORD + "\",\"enchants\":" + ench(e("sharpness", 5)) + "}"));
        assertFalse(n.get("ok").getAsBoolean());
        assertEquals("no_base", n.get("code").getAsString());
        // The same request after the bot says it can craft is a plan, and the cache did not keep the old answer.
        ready(50);
        JsonObject y = ok(post(owner, "/api/v1/enchant/plan", "{" + KEY + ",\"key\":\"" + SWORD + "\",\"enchants\":" + ench(e("sharpness", 5)) + "}"));
        assertTrue(y.get("ok").getAsBoolean());
    }
}

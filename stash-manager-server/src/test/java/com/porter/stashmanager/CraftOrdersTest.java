package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Craft lines in orders: placement, reservations, the 409 codes, claims, results and redaction (CRAFT-CONTRACT.md). */
class CraftOrdersTest extends CraftHarness {
    static final String CLAIM = "{" + KEY + ",\"player\":\"Bob\"}";

    JsonObject placed(String lines) throws Exception {
        return ok(placeOrder(lines)).getAsJsonObject("order");
    }

    static long count(JsonArray list, String id) {
        for (JsonElement e : list) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject().get("count").getAsLong();
        return 0;
    }

    // ---------- placement ----------

    @Test
    void aCraftLineIsPlannedAtPlacementAndStoredWithTheOrder() throws Exception {
        start();
        ready(2048);
        JsonObject o = placed("[" + craftLine("minecraft:spruce_slab", 6) + "]");
        assertEquals("pending", o.get("status").getAsString());
        assertEquals("craft", o.getAsJsonArray("lines").get(0).getAsJsonObject().get("type").getAsString());
        JsonObject c = o.getAsJsonObject("craft");
        assertEquals(1, c.get("v").getAsInt());
        assertEquals(MC, c.get("mc").getAsString());
        assertEquals(tableHash(), c.get("recipes").getAsString());
        assertEquals("minecraft:spruce_slab", c.getAsJsonArray("lines").get(0).getAsJsonObject().get("key").getAsString());
        assertEquals(6, c.getAsJsonArray("lines").get(0).getAsJsonObject().get("count").getAsInt());
        // 6 slabs need 3 planks: the 2 in the stash and 1 more from a log (4 planks made, 3 over).
        assertEquals(1, count(c.getAsJsonArray("take"), "minecraft:spruce_log"));
        assertEquals(2, count(c.getAsJsonArray("take"), "minecraft:spruce_planks"));
        assertEquals(3, count(c.getAsJsonArray("leftovers"), "minecraft:spruce_planks"));
        assertEquals(2, c.get("fills").getAsInt());
        JsonObject planks = c.getAsJsonArray("steps").get(0).getAsJsonObject();
        assertEquals("minecraft:spruce_planks", planks.get("recipe").getAsString());
        assertEquals("minecraft:spruce_planks", planks.get("result").getAsString());
        assertEquals(4, planks.get("count").getAsInt());
        assertEquals(1, planks.get("fills").getAsInt());
        assertEquals("minecraft:spruce_log", planks.getAsJsonArray("inputs").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("minecraft:spruce_slab", c.getAsJsonArray("steps").get(1).getAsJsonObject().get("recipe").getAsString());
        assertFalse(c.toString().contains("118"), "no positions in the order");
        // Other orders and old ones: craft is null.
        JsonObject item = placed("[" + itemLine("minecraft:spruce_log", 1) + "]");
        assertTrue(item.get("craft").isJsonNull());
        // The take is reserved like item lines: GET orders and a new item order both see it.
        assertEquals(8L, availableItem("minecraft:spruce_log").getAsLong(), "10 - 1 crafted - 1 ordered");
        assertEquals(0L, availableItem("minecraft:spruce_planks").getAsLong());
        Res tooMany = placeOrder("[" + itemLine("minecraft:spruce_log", 9) + "]");
        assertEquals(400, tooMany.status);
        assertTrue(tooMany.error().contains("only 8 available"), tooMany.error());
        // Cancelling releases the take.
        ok(post(owner, "/api/v1/orders/" + o.get("id").getAsString() + "/cancel", "{}"));
        assertEquals(9L, availableItem("minecraft:spruce_log").getAsLong());
        assertEquals(2L, availableItem("minecraft:spruce_planks").getAsLong());
    }

    @Test
    void theItemLinesOfTheSameOrderAreTheDirectTakes() throws Exception {
        start();
        ready(2048);
        JsonObject o = placed("[" + itemLine("minecraft:spruce_planks", 2) + "," + craftLine("minecraft:spruce_slab", 6) + "]");
        JsonObject c = o.getAsJsonObject("craft");
        assertEquals(0, count(c.getAsJsonArray("take"), "minecraft:spruce_planks"), "the planks go out as planks");
        assertEquals(1, count(c.getAsJsonArray("take"), "minecraft:spruce_log"));
        assertEquals(1, count(c.getAsJsonArray("leftovers"), "minecraft:spruce_planks"), "4 made, 3 used");
        // Lines of the same key are merged; the count is the sum.
        JsonObject merged = placed("[" + craftLine("minecraft:spruce_slab", 3) + "," + craftLine("minecraft:spruce_slab", 3) + "]");
        assertEquals(1, merged.getAsJsonArray("lines").size());
        assertEquals(6, merged.getAsJsonArray("lines").get(0).getAsJsonObject().get("count").getAsInt());
    }

    @Test
    void aLineThatIsNotFullyCraftableIsRefusedWithTheReason() throws Exception {
        start();
        ready(2048);
        Res r = placeOrder("[" + itemLine("minecraft:oak_log", 1) + "," + craftLine("minecraft:spruce_slab", 100000) + "]");
        assertEquals(400, r.status);
        assertTrue(r.error().startsWith("only "), r.error());
        assertTrue(r.error().contains("craftable for Spruce Slab: not enough Spruce Log"), r.error());
        assertEquals(1, r.obj().get("line").getAsInt());
        assertEquals(10L, availableItem("minecraft:spruce_log").getAsLong(), "nothing was placed");
        // Two craft lines: the second does not fit after the first.
        r = placeOrder("[" + craftLine("minecraft:spruce_stairs", 8) + "," + craftLine("minecraft:spruce_fence", 3000) + "]");
        assertEquals(400, r.status);
        assertEquals(1, r.obj().get("line").getAsInt());
        assertTrue(r.error().contains("Spruce Fence"), r.error());
    }

    @Test
    void itemsThatCannotBeCraftedHereAreRefused() throws Exception {
        start();
        ready(2048);
        for (String id : new String[]{"minecraft:diamond", "minecraft:red_shulker_box", "minecraft:stone", "minecraft:white_bed"}) {
            Res r = placeOrder("[" + craftLine(id, 1) + "]");
            assertEquals(400, r.status, id);
            assertEquals(0, r.obj().get("line").getAsInt());
        }
        assertTrue(placeOrder("[" + craftLine("minecraft:diamond", 1) + "]").error().contains("can't be crafted here"));
        // Bad lines.
        for (String bad : new String[]{craftLine("stick", 1), craftLine("minecraft:stick#ab", 1), craftLine("minecraft:stick", 0),
            "{\"type\":\"craft\",\"count\":1}", "{\"type\":\"craft\",\"key\":5,\"count\":1}",
            "{\"type\":\"bake\",\"key\":\"minecraft:stick\",\"count\":1}"}) {
            assertEquals(400, placeOrder("[" + bad + "]").status, bad);
        }
        assertEquals(400, placeOrder("[{\"type\":\"craft\",\"key\":\"minecraft:stick\",\"count\":2147483648}]").status);
        assertEquals(400, placeOrder("[{\"type\":\"craft\",\"key\":\"minecraft:stick\",\"count\":1.5}]").status);
    }

    @Test
    void theGridFillCapIsEnforced() throws Exception {
        start();
        ready(1);
        Res r = placeOrder("[" + craftLine("minecraft:spruce_slab", 6) + "]");
        assertEquals(400, r.status);
        assertEquals("this bot crafts at most 1 grid fills per order", r.error());
        assertTrue(r.obj().get("line").isJsonNull());
        capableBeat("ok", 2);
        assertEquals(200, placeOrder("[" + craftLine("minecraft:spruce_slab", 6) + "]").status);
    }

    @Test
    void aCraftPlanThatWouldNeedMoreThan64StepsOrIngredientsIsRefused() throws Exception {
        start();
        java.util.Map<String, JsonObject> rs = new java.util.TreeMap<>();
        StringBuilder ings = new StringBuilder();
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            rs.put("make_" + i, RecipeFixtures.j(RecipeFixtures.shapeless("[\"minecraft:raw_" + i + "\"]", "minecraft:mid_" + i, 1)));
            ings.append(i == 0 ? "" : ",").append("\"minecraft:mid_").append(i).append('"');
            items.append(i == 0 ? "" : ",").append(item("minecraft:raw_" + i, 5, 64));
        }
        rs.put("big", RecipeFixtures.j(RecipeFixtures.shapeless("[" + ings + "]", "minecraft:big", 1)));
        recipes.table = RecipeNormalizer.normalize(MC, rs, java.util.Map.of(), java.util.Set.of()).table();
        store.putIndex(json("{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\",\"mcVersion\":\"" + MC + "\",\"items\":[" + items + "]}"));
        ready(100_000);
        Res r = placeOrder("[" + craftLine("minecraft:big", 1) + "]");
        assertEquals(400, r.status, r.body);
        assertTrue(r.error().contains("too big for one order"), r.error());
        assertEquals(64, CraftService.MAX_STEPS);
        assertEquals(64, CraftService.MAX_TAKE);
        // 60 of them fit.
        recipes.table = RecipeNormalizer.normalize(MC, java.util.Map.of("small", RecipeFixtures.j(RecipeFixtures.shapeless("[\"minecraft:mid_0\"]", "minecraft:small", 1)),
            "make_0", rs.get("make_0")), java.util.Map.of(), java.util.Set.of()).table();
        ready(100_000);
        JsonObject o = placed("[" + craftLine("minecraft:small", 3) + "]");
        assertEquals(2, o.getAsJsonObject("craft").getAsJsonArray("steps").size());
    }

    // ---------- the 409 codes ----------

    @Test
    void everyReasonAnOrderCannotBePlacedHasItsCode() throws Exception {
        start();
        String lines = "[" + craftLine("minecraft:spruce_slab", 6) + "]";
        // no_bot: nothing heard from the bot (also after a restart: the capability is in memory only).
        Res r = placeOrder(lines);
        assertEquals(409, r.status);
        assertEquals("no_bot", r.obj().get("craft").getAsString());
        assertTrue(r.error().contains("can't take craft orders"));
        // A bot that said it can't (enabled false, or nothing).
        ok(heartbeat("\"craftOrders\":" + cap(tableHash(), "ok", 64).replace("\"enabled\":true", "\"enabled\":false")));
        Res off = placeOrder(lines);
        assertEquals("no_bot", off.obj().get("craft").getAsString());
        assertTrue(off.error().contains("crafting turned off"), off.error());
        ok(heartbeat("\"craftOrders\":null"));
        assertEquals("no_bot", placeOrder(lines).obj().get("craft").getAsString());
        // recipes_differ.
        ok(heartbeat("\"craftOrders\":" + cap(hash40('e'), "ok", 64)));
        assertEquals("recipes_differ", placeOrder(lines).obj().get("craft").getAsString());
        // no_table: a capable bot, no table set on the website.
        ok(heartbeat("\"craftOrders\":" + cap(tableHash(), "unset", 64)));
        r = placeOrder(lines);
        assertEquals(409, r.status);
        assertEquals("no_table", r.obj().get("craft").getAsString());
        // table_bad: set, synced, but the bot says it is not a table (or unreachable, or another dimension).
        setTable(118, 64, -30);
        for (String bad : new String[]{"not_table", "unreachable", "other_dimension", "unset"}) {
            capableBeat(bad, 64);
            assertEquals("table_bad", placeOrder(lines).obj().get("craft").getAsString(), bad);
        }
        // Not yet synced: the bot's word is not about this table.
        ok(heartbeat("\"craftOrders\":" + cap(tableHash(), "ok", 64) + ",\"craftHash\":\"" + EMPTY_HASH() + "\""));
        assertEquals("table_bad", placeOrder(lines).obj().get("craft").getAsString());
        // unloaded counts only for a table the bot has reported as ok at least once; ok is always fine.
        capableBeat("unloaded", 64);
        assertEquals("table_bad", placeOrder(lines).obj().get("craft").getAsString(), "never verified");
        capableBeat("ok", 64);
        assertEquals(200, placeOrder(lines).status);
        capableBeat("unloaded", 64);
        assertEquals(200, placeOrder(lines).status, "verified once, now its chunk is not loaded");
        capableBeat("ok", 64);
        assertEquals(200, placeOrder("[" + craftLine("minecraft:spruce_slab", 3) + "]").status);
        // no_recipes: last in the story, first in the checks.
        recipes.state = "building";
        assertEquals("no_recipes", placeOrder(lines).obj().get("craft").getAsString());
        recipes.state = "failed";
        assertEquals("no_recipes", placeOrder(lines).obj().get("craft").getAsString());
        recipes.state = "ready";
        // Orders without craft lines never care.
        ok(heartbeat("\"craftOrders\":null"));
        assertEquals(200, placeOrder("[" + itemLine("minecraft:spruce_log", 1) + "]").status);
    }

    static String EMPTY_HASH() {
        return CraftStore.hash(null);
    }

    @Test
    void aStashWithoutAnIndexTakesNoCraftOrders() throws Exception {
        start(false);
        ok(heartbeat("\"craftOrders\":" + cap(tableHash(), "ok", 64)));
        Res r = placeOrder("[" + craftLine("minecraft:spruce_slab", 6) + "]");
        assertEquals(400, r.status);
        assertEquals("no index for that stash", r.error());
    }

    // ---------- claims ----------

    @Test
    void anOlderBotIsNeverGivenACraftOrderAndAHeldOneIsFailed() throws Exception {
        start();
        ready(2048);
        JsonObject craftOrder = placed("[" + craftLine("minecraft:spruce_slab", 6) + "]");
        JsonObject itemOrder = placed("[" + itemLine("minecraft:oak_log", 1) + "]");
        // The heartbeat of a bot without craft support does not announce the craft order.
        ok(heartbeat("\"craftOrders\":null"));
        assertEquals(1, ok(heartbeat(null)).getAsJsonObject("orders").get("pending").getAsInt());
        // A claim without the flag skips the craft order and returns the next claimable one; the craft order stays pending.
        Res claim = botPost("/api/v1/orders/claim", CLAIM);
        assertEquals(200, claim.status);
        assertEquals(itemOrder.get("id").getAsString(), claim.obj().getAsJsonObject("order").get("id").getAsString());
        assertEquals("pending", craftStatus(craftOrder.get("id").getAsString()));
        // The flag has to be a whole number >= 1; 0, true and "1" don't count.
        for (String flag : new String[]{"0", "true", "\"1\"", "null", "-1"}) {
            Res held = botPost("/api/v1/orders/claim", "{" + KEY + ",\"player\":\"Bob\",\"craftOrders\":" + flag + "}");
            assertEquals(200, held.status, flag); // the held item order again
            assertEquals(itemOrder.get("id").getAsString(), held.obj().getAsJsonObject("order").get("id").getAsString(), flag);
        }
        ok(post(owner, "/api/v1/orders/" + itemOrder.get("id").getAsString() + "/cancel", "{}"));
        ok(botPost("/api/v1/orders/" + itemOrder.get("id").getAsString() + "/status", "{\"status\":\"cancelled\",\"player\":\"Bob\"}"));
        assertEquals(204, botPost("/api/v1/orders/claim", CLAIM).status, "only the craft order is left, and this bot can't take it");
        // With the flag the craft order is handed out and held.
        Res withFlag = botPost("/api/v1/orders/claim", "{" + KEY + ",\"player\":\"Bob\",\"craftOrders\":1}");
        assertEquals(200, withFlag.status);
        assertEquals(craftOrder.get("id").getAsString(), withFlag.obj().getAsJsonObject("order").get("id").getAsString());
        assertEquals("accepted", craftStatus(craftOrder.get("id").getAsString()));
        assertNotNull(withFlag.obj().getAsJsonObject("order").get("craft").getAsJsonObject().get("steps"));
        assertEquals(9L, availableItem("minecraft:spruce_log").getAsLong(), "the held order still reserves its logs");
    }

    String craftStatus(String id) throws Exception {
        for (JsonElement e : ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders")) {
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject().get("status").getAsString();
        }
        return null;
    }

    @Test
    void aHeldCraftOrderClaimedAgainWithoutTheFlagFailsAndReleasesItsTake() throws Exception {
        start();
        ready(2048);
        JsonObject craftOrder = placed("[" + craftLine("minecraft:spruce_slab", 6) + "]");
        String id = craftOrder.get("id").getAsString();
        JsonObject next = placed("[" + itemLine("minecraft:oak_log", 1) + "]");
        assertEquals(9L, availableItem("minecraft:spruce_log").getAsLong());
        Res first = botPost("/api/v1/orders/claim", "{" + KEY + ",\"player\":\"Bob\",\"craftOrders\":1}");
        assertEquals(id, first.obj().getAsJsonObject("order").get("id").getAsString());
        ok(botPost("/api/v1/orders/" + id + "/status", "{\"status\":\"in_progress\",\"player\":\"Bob\"}"));
        // The bot was downgraded and restarted: it claims without the flag.
        for (int i = 0; i < 3; i++) {
            assertEquals(204, botPost("/api/v1/orders/claim", CLAIM).status, "claim " + (i + 1) + ": the order is kept, nothing is handed out");
            assertEquals("in_progress", craftStatus(id));
        }
        // Two minutes and more later (and no capable heartbeat since) the next flagless claim fails it.
        orderClock.addAndGet(130_000);
        botClock.addAndGet(130_000);
        Res again = botPost("/api/v1/orders/claim", CLAIM);
        assertEquals(200, again.status);
        assertEquals(next.get("id").getAsString(), again.obj().getAsJsonObject("order").get("id").getAsString(), "the next claimable order");
        JsonObject failed = null;
        for (JsonElement e : ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders")) {
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) failed = e.getAsJsonObject();
        }
        assertNotNull(failed);
        assertEquals("failed", failed.get("status").getAsString());
        assertEquals("the bot can no longer craft (was it downgraded?); retry the order", failed.get("message").getAsString());
        assertEquals(10L, availableItem("minecraft:spruce_log").getAsLong(), "the reservation is released");
        // The bot's late report about it is refused: finished orders can't change.
        assertEquals(409, botPost("/api/v1/orders/" + id + "/status", "{\"status\":\"delivered\",\"player\":\"Bob\"}").status);
        // Retry plans the craft lines again (the bot is capable again).
        Res retry = post(owner, "/api/v1/orders/" + id + "/retry", "{}");
        assertEquals(200, retry.status, retry.body);
        JsonObject again2 = retry.obj().getAsJsonObject("order");
        assertNotEquals(id, again2.get("id").getAsString());
        assertEquals("craft", again2.getAsJsonArray("lines").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(1, count(again2.getAsJsonObject("craft").getAsJsonArray("take"), "minecraft:spruce_log"));
    }

    @Test
    void ordersWithoutCraftLinesAreClaimedAsBefore() throws Exception {
        start();
        JsonObject a = placed("[" + itemLine("minecraft:spruce_log", 1) + "]");
        Res claim = botPost("/api/v1/orders/claim", CLAIM);
        assertEquals(200, claim.status);
        assertEquals(a.get("id").getAsString(), claim.obj().getAsJsonObject("order").get("id").getAsString());
        assertTrue(claim.obj().getAsJsonObject("order").get("craft").isJsonNull());
        assertEquals(200, botPost("/api/v1/orders/claim", CLAIM).status, "held: handed out again");
    }

    // ---------- results ----------

    static final String GOOD_RESULT = "{\"fills\":2,\"made\":[{\"key\":\"minecraft:spruce_slab\",\"wanted\":6,\"made\":6}],"
        + "\"short\":[{\"key\":\"minecraft:spruce_fence\",\"count\":3,\"reason\":\"ingredients_short\"}],"
        + "\"used\":[{\"id\":\"minecraft:spruce_log\",\"count\":1}],\"returned\":[{\"id\":\"minecraft:spruce_planks\",\"count\":1}]}";

    String claimedCraftOrder() throws Exception {
        ready(2048);
        String id = placed("[" + craftLine("minecraft:spruce_slab", 6) + "]").get("id").getAsString();
        ok(botPost("/api/v1/orders/claim", "{" + KEY + ",\"player\":\"Bob\",\"craftOrders\":1}"));
        return id;
    }

    String statusBody(String craft) {
        return "{\"status\":\"delivered\",\"player\":\"Bob\",\"message\":\"2 item(s) not crafted: ingredients_short\",\"result\":{\"shulkers\":1,\"lines\":"
            + "[{\"key\":\"minecraft:spruce_slab\",\"delivered\":6}]" + (craft == null ? "" : ",\"craft\":" + craft) + "}}";
    }

    @Test
    void resultCraftIsValidatedAndStored() throws Exception {
        start();
        String id = claimedCraftOrder();
        // Wrong shapes are a 400 and change nothing.
        String[] bad = {
            "5", "[]", "\"x\"",
            "{\"fills\":-1}", "{\"fills\":1.5}", "{\"fills\":\"2\"}",
            "{\"made\":{}}", "{\"made\":[5]}", "{\"made\":[{\"wanted\":1,\"made\":1}]}", "{\"made\":[{\"key\":\"a\",\"wanted\":-1,\"made\":1}]}",
            "{\"made\":[{\"key\":\"a\",\"wanted\":1}]}",
            "{\"short\":[{\"key\":\"a\",\"count\":1}]}", "{\"short\":[{\"key\":\"a\",\"count\":1,\"reason\":5}]}",
            "{\"used\":[{\"id\":\"a\",\"count\":1.5}]}", "{\"used\":[{\"count\":1}]}",
            "{\"returned\":\"none\"}", "{\"returned\":[{\"id\":\"\",\"count\":1}]}",
        };
        for (String b : bad) assertEquals(400, botPost("/api/v1/orders/" + id + "/status", statusBody(b)).status, b);
        assertEquals("accepted", craftStatus(id));
        Res good = botPost("/api/v1/orders/" + id + "/status", statusBody(GOOD_RESULT));
        assertEquals(200, good.status, good.body);
        JsonObject res = good.obj().getAsJsonObject("order").getAsJsonObject("result").getAsJsonObject("craft");
        assertEquals(2, res.get("fills").getAsInt());
        assertEquals(6, res.getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        assertEquals("ingredients_short", res.getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
        assertEquals(1, count(res.getAsJsonArray("used"), "minecraft:spruce_log"));
        assertEquals(1, count(res.getAsJsonArray("returned"), "minecraft:spruce_planks"));
        // Absent or null: no craft result at all (older bots).
        String id2 = placed("[" + itemLine("minecraft:oak_log", 1) + "]").get("id").getAsString();
        ok(botPost("/api/v1/orders/claim", CLAIM));
        Res plain = botPost("/api/v1/orders/" + id2 + "/status", statusBody(null));
        assertFalse(plain.obj().getAsJsonObject("order").getAsJsonObject("result").has("craft"));
    }

    @Test
    void resultCraftListsAreCutAt200AndKeysAt256() throws Exception {
        start();
        String id = claimedCraftOrder();
        StringBuilder made = new StringBuilder();
        for (int i = 0; i < 250; i++) made.append(i == 0 ? "" : ",").append("{\"key\":\"").append(i == 0 ? "k".repeat(300) : "minecraft:item_" + i).append("\",\"wanted\":1,\"made\":1}");
        Res r = botPost("/api/v1/orders/" + id + "/status", statusBody("{\"fills\":0,\"made\":[" + made + "]}"));
        assertEquals(200, r.status, r.body);
        JsonArray list = r.obj().getAsJsonObject("order").getAsJsonObject("result").getAsJsonObject("craft").getAsJsonArray("made");
        assertEquals(200, list.size());
        assertEquals(256, list.get(0).getAsJsonObject().get("key").getAsString().length());
    }

    @Test
    void streamerUsersSeeOnlyKnownReasonsAndItemKeys() throws Exception {
        start();
        String id = claimedCraftOrder();
        String result = "{\"fills\":1,\"made\":[{\"key\":\"minecraft:spruce_slab\",\"wanted\":6,\"made\":6}],"
            + "\"short\":[{\"key\":\"minecraft:spruce_fence\",\"count\":3,\"reason\":\"ingredients_short\"},"
            + "{\"key\":\"minecraft:stick\",\"count\":1,\"reason\":\"no table at 118, 64, -30\"},"
            + "{\"key\":\"my chest at 118 64 -30\",\"count\":1,\"reason\":\"table_blocked\"}],"
            + "\"used\":[],\"returned\":[]}";
        ok(botPost("/api/v1/orders/" + id + "/status", statusBody(result)));
        String forced = userToken("fay", "{\"view\":true,\"order\":true,\"streamer\":true}");
        String sid = JsonParser.parseString(get(forced, "/api/v1/stashes").body).getAsJsonArray().get(0).getAsJsonObject().get("server").getAsString();
        JsonObject order = null;
        for (JsonElement e : ok(get(forced, "/api/v1/orders?server=" + sid + "&stash=a")).getAsJsonArray("orders")) {
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) order = e.getAsJsonObject();
        }
        assertNotNull(order);
        JsonArray shorts = order.getAsJsonObject("result").getAsJsonObject("craft").getAsJsonArray("short");
        assertEquals("ingredients_short", shorts.get(0).getAsJsonObject().get("reason").getAsString());
        assertEquals("(hidden)", shorts.get(1).getAsJsonObject().get("reason").getAsString());
        assertEquals("table_blocked", shorts.get(2).getAsJsonObject().get("reason").getAsString());
        assertEquals("minecraft:spruce_fence", shorts.get(0).getAsJsonObject().get("key").getAsString());
        assertEquals("(hidden)", shorts.get(2).getAsJsonObject().get("key").getAsString());
        assertEquals("(hidden)", order.get("message").getAsString(), "the bot's note is hidden as before");
        assertFalse(order.toString().contains("118,64") || order.toString().contains("118, 64"));
        // The owner sees it all.
        JsonObject mine = null;
        for (JsonElement e : ok(get(owner, "/api/v1/orders?server=s&stash=a")).getAsJsonArray("orders")) {
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) mine = e.getAsJsonObject();
        }
        assertEquals("no table at 118, 64, -30", mine.getAsJsonObject("result").getAsJsonObject("craft").getAsJsonArray("short").get(1).getAsJsonObject().get("reason").getAsString());
        // Every reason code of the contract passes.
        for (String code : CraftService.SHORT_REASONS) assertTrue(java.util.Set.of("no_table", "table_missing", "table_blocked", "ingredients_short",
            "inventory_full", "recipe_mismatch", "grid_stuck", "timeout", "interrupted", "cap", "cancelled").contains(code));
        assertEquals(11, CraftService.SHORT_REASONS.size());
    }

    @Test
    void aCancelledOrderKeepsTheResultTheBotSent() throws Exception {
        start();
        String id = claimedCraftOrder();
        Res r = botPost("/api/v1/orders/" + id + "/status", "{\"status\":\"cancelled\",\"player\":\"Bob\",\"result\":{\"shulkers\":0,\"lines\":[],\"craft\":" + GOOD_RESULT + "}}");
        assertEquals(200, r.status, r.body);
        JsonObject o = r.obj().getAsJsonObject("order");
        assertEquals("cancelled", o.get("status").getAsString());
        assertEquals(2, o.getAsJsonObject("result").getAsJsonObject("craft").get("fills").getAsInt());
    }

    // ---------- storage ----------

    @Test
    void theCraftPlanSurvivesARestartAndOldOrdersLoadWithoutOne() throws Exception {
        start();
        ready(2048);
        String id = placed("[" + craftLine("minecraft:spruce_slab", 6) + "]").get("id").getAsString();
        placed("[" + itemLine("minecraft:oak_log", 1) + "]");
        OrderStore again = new OrderStore(data, store);
        again.load();
        JsonObject list = again.listJson("s", "a");
        JsonObject craftOrder = null, plain = null;
        for (JsonElement e : list.getAsJsonArray("orders")) {
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) craftOrder = e.getAsJsonObject();
            else plain = e.getAsJsonObject();
        }
        assertEquals(2, craftOrder.getAsJsonObject("craft").get("fills").getAsInt());
        assertTrue(plain.get("craft").isJsonNull());
        assertEquals("craft", craftOrder.getAsJsonArray("lines").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(9L, list.getAsJsonObject("available").getAsJsonObject("items").get("minecraft:spruce_log").getAsLong(), "the take is reserved again on load");
    }

    @Test
    void shulkerSlotsForCraftLinesUseTheStackSize() {
        IndexStore.OrderCounts c = new IndexStore.OrderCounts(java.util.Map.of("minecraft:ender_pearl", 5L), java.util.Map.of("minecraft:ender_pearl", "minecraft:ender_pearl"),
            java.util.Map.of("minecraft:ender_pearl", 16), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        // 27 slots a shulker: 16 pearls a slot from the index, 64 a slot when the index doesn't know the item.
        List<OrderStore.Line> lines = new ArrayList<>();
        lines.add(new OrderStore.Line("craft", "minecraft:ender_pearl", 16L * 27));
        assertEquals(1, OrderStore.shulkersNeeded(lines, c));
        lines.add(new OrderStore.Line("craft", "minecraft:ender_pearl", 1));
        assertEquals(2, OrderStore.shulkersNeeded(lines, c));
        List<OrderStore.Line> other = List.of(new OrderStore.Line("craft", "minecraft:stick", 64L * 27 + 1));
        assertEquals(2, OrderStore.shulkersNeeded(other, c));
        assertEquals("craft:minecraft:stick", other.get(0).ref());
    }
}

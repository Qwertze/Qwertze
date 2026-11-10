package com.porter.qwerte.printer.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.manager.model.PlanModel;
import com.porter.qwerte.manager.net.ApiError;
import com.porter.qwerte.manager.net.ApiResult;
import com.porter.qwerte.manager.net.Session;
import com.porter.qwerte.manager.net.SmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The StashOrders state machine against a fake server: crafting quotes, craft lines in the order, and every way the server can
 * answer instead.
 */
class StashOrdersTest {
    private static final String STAIRS = "minecraft:spruce_stairs";
    private static final String PLANKS = "minecraft:oak_planks";
    private static final String QUOTE = "/api/v1/craft/plan";
    private static final String ORDERS = "/api/v1/orders";

    record Call(String method, String path, Map<String, String> query, JsonObject body) {}

    /** A server that answers from a table of routes, at once, on the caller's thread. */
    static final class FakeNet implements StashOrders.Net {
        Session session = new Session("tok", "http://sm.test", "me");
        final List<Call> calls = new ArrayList<>();
        final Map<String, Function<Call, ApiResult>> routes = new HashMap<>();
        boolean sessionGone;

        @Override public boolean present() { return true; }
        @Override public boolean connect() { return true; }
        @Override public boolean hasUrl() { return true; }
        @Override public Session session() { return sessionGone ? null : session; }
        @Override public String lastStash() { return null; }
        @Override public boolean streamerDisplay() { return false; }

        @Override
        public <T> void get(String path, Map<String, String> query, SmClient.Parser<T> parser, BiConsumer<ApiResult, T> done) {
            Call c = new Call("GET", path, query, null);
            calls.add(c);
            ApiResult r = routes.get("GET " + path).apply(c);
            done.accept(r, r.ok() ? parser.parse(r) : null);
        }

        @Override
        public void post(String path, JsonObject body, Consumer<ApiResult> done) {
            Call c = new Call("POST", path, null, body);
            calls.add(c);
            done.accept(routes.get("POST " + path).apply(c));
        }

        List<Call> to(String method, String path) {
            List<Call> out = new ArrayList<>();
            for (Call c : calls) if (c.method().equals(method) && c.path().equals(path)) out.add(c);
            return out;
        }
    }

    static final class TestView implements StashOrders.View {
        Map<String, Long> need = new LinkedHashMap<>();
        Map<String, Long> carried = new HashMap<>();
        final List<String> said = new ArrayList<>();

        @Override public Map<String, Long> need() { return need; }
        @Override public Map<String, Long> carried() { return carried; }
        @Override public int maxStack(String itemId) { return 64; }
        @Override public boolean localFetch() { return false; }
        @Override public String itemName(String itemId) { return PlanModel.pretty(itemId); }
        @Override public String server() { return "srv"; }
        @Override public void say(boolean warn, String text) { said.add((warn ? "warn: " : "") + text); }

        boolean saidAnything(String part) {
            for (String s : said) if (s.contains(part)) return true;
            return false;
        }
    }

    private FakeNet net;
    private TestView view;
    private StashOrders orders;
    private long now = 1_000_000;
    private boolean craftOn;
    private String available;
    private int orderSeq;
    /** The orders the fake server holds: what a list answers, so a placed order does not vanish. */
    private final JsonArray listed = new JsonArray();

    private static ApiResult json(String path, int status, String body) {
        JsonElement b = JsonParser.parseString(body);
        return new ApiResult(status, b, null, 0, ApiError.classify(path, status, b));
    }

    @BeforeEach
    void setUp() {
        craftOn = true;
        available = "{}";
        orderSeq = 0;
        phase2 = false;
        while (listed.size() > 0) listed.remove(0);
        net = new FakeNet();
        view = new TestView();
        view.need.put(STAIRS, 8L);
        net.routes.put("GET /api/v1/auth/state", c -> json(c.path(), 200, "{\"loggedIn\":true}"));
        net.routes.put("GET /api/v1/stashes", c -> json(c.path(), 200, "[{\"server\":\"srv\",\"stash\":\"main\",\"online\":true,\"indexedAt\":1000}]"));
        net.routes.put("GET " + ORDERS, c -> json(c.path(), 200, "{\"orders\":" + listed + ",\"available\":{\"items\":" + available + "}}"));
        net.routes.put("POST " + ORDERS, c -> placed(c));
        net.routes.put("POST " + QUOTE, c -> json(c.path(), 200, quote(8, "")));
        orders = new StashOrders(new StashOrders.Config() {
            @Override public int threshold() { return 64; }
            @Override public int maxShulkersPerOrder() { return 9; }
            @Override public int maxShulkersPerRun() { return 27; }
            @Override public boolean craftMissing() { return craftOn; }
        }, net);
    }

    private ApiResult placed(Call c) {
        JsonObject order = new JsonObject();
        order.addProperty("id", "o-" + (++orderSeq));
        order.addProperty("status", "pending");
        order.add("lines", c.body().get("lines"));
        listed.add(order);
        JsonObject ok = new JsonObject();
        ok.addProperty("ok", true);
        ok.add("order", order);
        return json(c.path(), 200, ok.toString());
    }

    private static String quote(long craftable, String reason) {
        return "{\"ok\":true,\"craft\":{\"canOrder\":true,\"reason\":null,\"message\":null},\"lines\":[{\"id\":\"" + STAIRS + "\",\"want\":8,\"craftable\":"
            + craftable + ",\"reason\":" + (reason.isEmpty() ? "null" : "\"" + reason + "\"") + ",\"uses\":[{\"id\":\"minecraft:spruce_log\",\"count\":2}]}],"
            + "\"take\":[],\"leftovers\":[],\"fills\":2,\"truncated\":false}";
    }

    /** Runs the Printer's tick every two seconds of game-less time. */
    private void run(int ticks) {
        for (int i = 0; i < ticks; i++) {
            now += 2000;
            orders.tick(now, view);
        }
    }

    private static JsonArray lines(Call c) {
        return c.body().getAsJsonArray("lines");
    }

    private static List<String> types(Call c) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : lines(c)) out.add(e.getAsJsonObject().get("type").getAsString() + ":" + e.getAsJsonObject().get("key").getAsString());
        return out;
    }

    // ---------- the plain flow ----------

    @Test
    void aQuoteBecomesCraftLinesInTheOrderAndTheLedgerCountsThemAsSupply() {
        run(60);
        List<Call> quotes = net.to("POST", QUOTE);
        assertEquals(1, quotes.size());
        assertEquals("main", quotes.get(0).body().get("stash").getAsString());
        assertEquals(STAIRS, quotes.get(0).body().getAsJsonArray("want").get(0).getAsJsonObject().get("id").getAsString());
        List<Call> posts = net.to("POST", ORDERS);
        assertEquals(1, posts.size());
        assertEquals(List.of("craft:" + STAIRS), types(posts.get(0)));
        assertEquals(8, lines(posts.get(0)).get(0).getAsJsonObject().get("count").getAsInt());
        assertTrue(view.saidAnything("Ordered crafting 8 Spruce Stairs (from 2 Spruce Log)"), view.said.toString());
        assertEquals(8L, orders.ledger().covered(now).get(STAIRS));
        // nothing is asked or ordered again while that order is on its way
        run(200);
        assertEquals(1, net.to("POST", QUOTE).size());
        assertEquals(1, net.to("POST", ORDERS).size());
    }

    @Test
    void itemLinesComeFirstThenCraftLinesAndTheQuoteKnowsTheItemLines() {
        view.need.put(PLANKS, 100L);
        available = "{\"" + PLANKS + "\":100}";
        run(60);
        Call quote = net.to("POST", QUOTE).get(0);
        assertEquals(100, quote.body().getAsJsonArray("direct").get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(List.of("item:" + PLANKS, "craft:" + STAIRS), types(net.to("POST", ORDERS).get(0)));
        assertTrue(view.saidAnything("Ordered 100 Oak Planks + crafting 8 Spruce Stairs (from 2 Spruce Log)"), view.said.toString());
    }

    @Test
    void withTheSettingOffNothingIsQuoted() {
        craftOn = false;
        run(100);
        assertEquals(0, net.to("POST", QUOTE).size());
        assertEquals(0, net.to("POST", ORDERS).size());
    }

    // ---------- H1: 404 ----------

    @Test
    void aMissingRouteTurnsCraftingOffForTheSessionAndSaysSoOnce() {
        view.need.put(PLANKS, 100L);
        available = "{\"" + PLANKS + "\":100}";
        net.routes.put("POST " + QUOTE, c -> json(c.path(), 404, "{}"));
        run(200);
        assertEquals(1, net.to("POST", QUOTE).size());
        assertEquals(List.of("item:" + PLANKS), types(net.to("POST", ORDERS).get(0)));
        long said = view.said.stream().filter(s -> s.contains("does not support crafting")).count();
        assertEquals(1, said);
    }

    @Test
    void aNoSuchStashAnswerOnlyBlocksThatStash() {
        net.routes.put("POST " + QUOTE, c -> json(c.path(), 404, "{\"error\":\"no such stash\"}"));
        run(100);
        assertEquals(1, net.to("POST", QUOTE).size());
        assertFalse(view.saidAnything("does not support"), view.said.toString());
        // crafting is not off: another server address, or later, it is asked again. The stash rests for five minutes.
        run(200);
        assertTrue(net.to("POST", QUOTE).size() >= 2, "asked again after the stash rested");
    }

    @Test
    void aSwitchedOffCraftingWithoutAKnownAddressDoesNotComeBackAtOnce() {
        net.routes.put("POST " + QUOTE, c -> {
            net.sessionGone = true;
            return json(c.path(), 404, "{}");
        });
        run(60);
        assertEquals(1, net.to("POST", QUOTE).size());
        net.sessionGone = false;
        run(300);
        assertEquals(1, net.to("POST", QUOTE).size());
    }

    // ---------- H2 and the blocked items ----------

    @Test
    void anItemWhoseOrderWasCancelledIsNotOrderedAsACraft() {
        orders.ledger().placed(new OrderLedger.Placed("old", "srv\u0000main", Map.of(STAIRS, 8L), 0));
        orders.ledger().update("srv\u0000main", List.of(new OrderLedger.Seen("old", "cancelled", "")), now);
        run(100);
        assertEquals(0, net.to("POST", QUOTE).size());
        assertEquals(0, net.to("POST", ORDERS).size());
    }

    @Test
    void aQuotedItemThatIsBlockedAtTheStashIsLeftOut() {
        // Applying the quote skips blocked ids too (the stash answered after the item was blocked)
        CraftQuote.Quote q = CraftQuote.parse(JsonParser.parseString(quote(8, "")).getAsJsonObject());
        assertFalse(CraftOrders.apply(q, new LinkedHashMap<>(Map.of(STAIRS, 8L)), id -> id.equals(STAIRS)).any());
        assertTrue(CraftOrders.apply(q, new LinkedHashMap<>(Map.of(STAIRS, 8L)), id -> false).any());
    }

    @Test
    void nothingCraftableIsRememberedPerStashAndItem() {
        net.routes.put("POST " + QUOTE, c -> json(c.path(), 200, quote(0, "not enough Spruce Log")));
        run(120);
        assertEquals(1, net.to("POST", QUOTE).size());
        assertEquals(0, net.to("POST", ORDERS).size());
    }

    @Test
    void aReplyThatIsNotAQuoteIsRememberedToo() {
        net.routes.put("POST " + QUOTE, c -> json(c.path(), 200, "{\"ok\":true}"));
        run(120);
        assertEquals(1, net.to("POST", QUOTE).size());
    }

    // ---------- the busy stash ----------

    @Test
    void craftingWaitsWhileAStashThatMightHoldTheItemIsBusy() {
        net.routes.put("GET /api/v1/stashes", c -> json(c.path(), 200,
            "[{\"server\":\"srv\",\"stash\":\"main\",\"online\":true,\"indexedAt\":1000},{\"server\":\"srv\",\"stash\":\"other\",\"online\":true,\"indexedAt\":1000}]"));
        orders.ledger().placed(new OrderLedger.Placed("busy", "srv\u0000other", Map.of("minecraft:stone", 64L), 0));
        net.routes.put("GET " + ORDERS, c -> json(c.path(), 200, "other".equals(c.query().get("stash"))
            ? "{\"orders\":[{\"id\":\"busy\",\"status\":\"in_progress\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:stone\",\"count\":64}]}],\"available\":{\"items\":{}}}"
            : "{\"orders\":[],\"available\":{\"items\":{}}}"));
        run(100);
        assertEquals(0, net.to("POST", QUOTE).size());
        assertEquals(0, net.to("POST", ORDERS).size());
        // and it is not reported as something no stash has
        assertFalse(view.saidAnything("No stash bot"), view.said.toString());
    }


    // ---------- busy stashes defer only the items they may hold ----------

    private static final String MAIN = "srv\u0000main";
    private static final String OTHER = "srv\u0000other";

    /** Two stashes; "other" is busy with an order of ours in the second phase. {@code otherStairs}: what "other" says it can give. */
    private void twoStashes(long otherStairs) {
        net.routes.put("GET /api/v1/stashes", c -> json(c.path(), 200,
            "[{\"server\":\"srv\",\"stash\":\"main\",\"online\":true,\"indexedAt\":1000},"
            + "{\"server\":\"srv\",\"stash\":\"other\",\"online\":true,\"indexedAt\":1000}]"));
        net.routes.put("GET " + ORDERS, c -> {
            boolean other = "other".equals(c.query().get("stash"));
            String items = other && otherStairs > 0 ? "{\"" + STAIRS + "\":" + otherStairs + "}" : "{}";
            String list = other && phase2 ? "[{\"id\":\"busy\",\"status\":\"in_progress\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:stone\",\"count\":64}]}]" : other ? "[]" : listed.toString();
            return json(c.path(), 200, "{\"orders\":" + list + ",\"available\":{\"items\":" + items + "}}");
        });
    }

    private boolean phase2;

    /** Phase 1 looks at both stashes with crafting off (so each says what it holds); phase 2 makes "other" busy and turns crafting on. */
    private void phase1ThenBusy() {
        craftOn = false;
        run(40);
        // up to just before the five minutes "no stash has it" is remembered end: no cycle in between asks it again
        run(130);
        craftOn = true;
        phase2 = true;
        orders.ledger().placed(new OrderLedger.Placed("busy", OTHER, Map.of("minecraft:stone", 64L), 0));
    }

    @Test
    void aBusyStashThatHoldsNothingRelevantDoesNotStopTheOtherFromCrafting() {
        twoStashes(0);
        phase1ThenBusy();
        run(60);
        List<Call> quotes = net.to("POST", QUOTE);
        assertEquals(1, quotes.size());
        assertEquals("main", quotes.get(0).body().get("stash").getAsString());
        assertEquals(List.of("craft:" + STAIRS), types(net.to("POST", ORDERS).get(0)));
    }

    @Test
    void aBusyStashThatHoldsTheItemMakesTheOtherWaitForIt() {
        twoStashes(5);
        // "other" holds stairs but a failed order there keeps them out of the plan
        orders.ledger().placed(new OrderLedger.Placed("f", OTHER, Map.of(STAIRS, 5L), 0));
        orders.ledger().update(OTHER, List.of(new OrderLedger.Seen("f", "failed", "")), now);
        phase1ThenBusy();
        run(40);
        assertEquals(0, net.to("POST", QUOTE).size());
        assertTrue(view.saidAnything("Crafting Spruce Stairs waits a little"), view.said.toString());
    }

    @Test
    void theWaitForABusyStashEndsAfterThreeMinutes() {
        twoStashes(5);
        orders.ledger().placed(new OrderLedger.Placed("f", OTHER, Map.of(STAIRS, 5L), 0));
        orders.ledger().update(OTHER, List.of(new OrderLedger.Seen("f", "failed", "")), now);
        phase1ThenBusy();
        run(50);
        assertEquals(0, net.to("POST", QUOTE).size(), "still waiting");
        run(100);
        assertTrue(net.to("POST", QUOTE).size() >= 1, "quoted anyway after the cap");
    }

    // ---------- what the order POST can answer ----------

    @Test
    void aRefusedCraftCodeDropsTheCraftLinesAndOrdersTheItems() {
        view.need.put(PLANKS, 100L);
        available = "{\"" + PLANKS + "\":100}";
        net.routes.put("POST " + ORDERS, c -> {
            for (JsonElement e : lines(c)) {
                if ("craft".equals(e.getAsJsonObject().get("type").getAsString())) {
                    return json(c.path(), 409, "{\"ok\":false,\"error\":\"no table\",\"line\":1,\"craft\":\"no_table\"}");
                }
            }
            return placed(c);
        });
        run(100);
        List<Call> posts = net.to("POST", ORDERS);
        assertEquals(2, posts.size());
        assertEquals(List.of("item:" + PLANKS, "craft:" + STAIRS), types(posts.get(0)));
        assertEquals(List.of("item:" + PLANKS), types(posts.get(1)));
        assertTrue(view.saidAnything("no crafting table is set"), view.said.toString());
        assertFalse(orders.ledger().covered(now).containsKey(STAIRS));
    }

    @Test
    void aRefusedCraftLineIsFoundByItsPositionAfterTheItemLines() {
        view.need.put(PLANKS, 100L);
        available = "{\"" + PLANKS + "\":100}";
        net.routes.put("POST " + ORDERS, c -> {
            if (lines(c).size() == 2) return json(c.path(), 400, "{\"error\":\"only 3 craftable for Spruce Stairs: not enough Spruce Log\",\"line\":1}");
            return placed(c);
        });
        run(100);
        List<Call> posts = net.to("POST", ORDERS);
        assertEquals(2, posts.size());
        assertEquals(List.of("item:" + PLANKS), types(posts.get(1)));
        assertTrue(view.saidAnything("could not craft Spruce Stairs"), view.said.toString());
        // the item order went through: no stash was skipped for the refused craft line
        assertTrue(orders.ledger().hasOpen("srv\u0000main"));
    }

    @Test
    void aCraftOnlyOrderThatIsRefusedIsSkipped() {
        net.routes.put("POST " + ORDERS, c -> json(c.path(), 400, "{\"error\":\"only 3 craftable\",\"line\":0}"));
        run(100);
        assertEquals(1, net.to("POST", ORDERS).size());
        assertFalse(orders.ledger().hasAny());
    }

    @Test
    void aBusyPlannerIsRetriedThreeTimesThenTheItemsGoWithoutCrafting() {
        view.need.put(PLANKS, 100L);
        available = "{\"" + PLANKS + "\":100}";
        net.routes.put("POST " + ORDERS, c -> {
            for (JsonElement e : lines(c)) {
                if ("craft".equals(e.getAsJsonObject().get("type").getAsString())) return json(c.path(), 503, "{\"error\":\"busy\"}");
            }
            return placed(c);
        });
        run(200);
        List<Call> posts = net.to("POST", ORDERS);
        assertEquals(4, posts.size());
        for (int i = 0; i < 3; i++) assertEquals(List.of("item:" + PLANKS, "craft:" + STAIRS), types(posts.get(i)));
        assertEquals(List.of("item:" + PLANKS), types(posts.get(3)));
        assertTrue(view.saidAnything("after 3 tries"), view.said.toString());
    }

    @Test
    void aServerErrorWithoutAJsonErrorIsLookedForNotRetriedBlindly() {
        // Not a planner "busy" answer: the order may have been placed, so the Printer looks for it before anything else
        net.routes.put("POST " + ORDERS, c -> new ApiResult(503, JsonNull.INSTANCE, null, 0, ApiError.Kind.SERVER));
        run(60);
        assertEquals(1, net.to("POST", ORDERS).size());
        // the look-up asked the order list of that stash again after the post
        List<Call> gets = net.to("GET", ORDERS);
        assertTrue(gets.size() >= 2, "looked for the order");
    }

    @Test
    void anAmbiguousPostWithCraftLinesIsAdoptedWhenTheOrderIsListed() {
        view.need.put(PLANKS, 100L);
        available = "{\"" + PLANKS + "\":100}";
        String order = "{\"id\":\"o-9\",\"status\":\"pending\",\"placedBy\":{\"id\":\"owner\",\"name\":\"me\"},\"lines\":[{\"type\":\"item\",\"key\":\"" + PLANKS
            + "\",\"count\":100},{\"type\":\"craft\",\"key\":\"" + STAIRS + "\",\"count\":8}]}";
        String decoy = "{\"id\":\"o-8\",\"status\":\"pending\",\"placedBy\":{\"id\":\"owner\",\"name\":\"me\"},\"lines\":[{\"type\":\"item\",\"key\":\"" + PLANKS
            + "\",\"count\":100}]}";
        net.routes.put("POST " + ORDERS, c -> {
            // the order is made, but the answer is lost; from now on it is on the server's list
            net.routes.put("GET " + ORDERS, g -> json(g.path(), 200, "{\"orders\":[" + decoy + "," + order + "],\"available\":{\"items\":{}}}"));
            return ApiResult.network();
        });
        run(60);
        assertEquals(1, net.to("POST", ORDERS).size());
        assertTrue(orders.ledger().knows("o-9"), "adopted the order with the craft line, not the decoy");
        assertFalse(orders.ledger().knows("o-8"));
        assertEquals(8L, orders.ledger().covered(now).get(STAIRS));
        assertTrue(view.saidAnything("Order found: 100 Oak Planks + crafting 8 Spruce Stairs"), view.said.toString());
    }

    // ---------- 429 ----------

    @Test
    void aBusyQuoteServerOrdersTheItemsNowAndAsksAgainLater() {
        net.routes.put("POST " + QUOTE, c -> new ApiResult(429, JsonParser.parseString("{\"error\":\"slow down\",\"retryAfter\":120}"), null, 120, ApiError.Kind.RATE_LIMIT));
        run(40);
        assertEquals(1, net.to("POST", QUOTE).size());
        run(30);
        assertEquals(1, net.to("POST", QUOTE).size(), "not asked again before Retry-After");
        // never reported as "no stash has it" while the answer is only late
        assertFalse(view.saidAnything("No stash bot"), view.said.toString());
        run(200);
        assertTrue(net.to("POST", QUOTE).size() >= 2, "asked again after the wait");
    }
}

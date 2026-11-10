package com.porter.qwerte.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderWatchTest {
    private static final String SCOPE = OrderSnaps.scope("srv", "base");
    private static final OrderSnaps.User ME = new OrderSnaps.User(true, "u1", false);
    private static final OrderSnaps.User OWNER = new OrderSnaps.User(true, "owner", true);

    private static OrderSnap order(String id, String status) {
        return new OrderSnap(id, status, "", "", List.of());
    }

    private static OrderSnap order(String id, String status, String phase) {
        return new OrderSnap(id, status, phase, "", List.of());
    }

    private static List<OrderSnap> parse(String json, OrderSnaps.User user) {
        return OrderSnaps.parseOrders(JsonParser.parseString(json), user);
    }

    // ---------- baseline and dedupe ----------

    @Test
    void firstListIsOnlyABaseline() {
        OrderWatch w = new OrderWatch();
        assertTrue(w.update(SCOPE, List.of(order("a", "delivered"), order("b", "failed"), order("c", "in_progress", "delivering 1/2"))).isEmpty());
        assertTrue(w.baselined(SCOPE));
    }

    @Test
    void anOrderThatFinishesAfterTheBaselineNotifiesOnce() {
        OrderWatch w = new OrderWatch();
        w.update(SCOPE, List.of(order("a", "in_progress", "gathering 1/4")));
        List<OrderWatch.Notice> n = w.update(SCOPE, List.of(order("a", "delivered")));
        assertEquals(1, n.size());
        assertEquals(Kind.COMPLETE, n.get(0).kind());
        assertTrue(w.update(SCOPE, List.of(order("a", "delivered"))).isEmpty());
        assertTrue(w.update(SCOPE, List.of(order("a", "delivered"))).isEmpty());
    }

    @Test
    void anOrderPlacedAndFinishedBetweenPollsStillNotifies() {
        OrderWatch w = new OrderWatch();
        w.update(SCOPE, List.of());
        List<OrderWatch.Notice> n = w.update(SCOPE, List.of(order("new", "failed")));
        assertEquals(List.of(Kind.FAILED), n.stream().map(OrderWatch.Notice::kind).toList());
    }

    @Test
    void anEmptyFirstListIsAlsoABaseline() {
        OrderWatch w = new OrderWatch();
        w.update(SCOPE, List.of());
        assertEquals(1, w.update(SCOPE, List.of(order("a", "cancelled"))).size());
    }

    @Test
    void scopesHaveTheirOwnBaseline() {
        OrderWatch w = new OrderWatch();
        String other = OrderSnaps.scope("srv", "other");
        w.update(SCOPE, List.of());
        assertTrue(w.update(other, List.of(order("x", "delivered"))).isEmpty());
        assertEquals(1, w.update(SCOPE, List.of(order("x", "delivered"))).size());
    }

    @Test
    void aFailedFetchChangesNothingSoNothingReplaysAfterReconnect() {
        // The module simply does not call update() for an error; the next good list is compared to the last good one
        OrderWatch w = new OrderWatch();
        w.update(SCOPE, List.of(order("a", "in_progress"), order("old", "delivered")));
        List<OrderWatch.Notice> n = w.update(SCOPE, List.of(order("a", "delivered"), order("old", "delivered")));
        assertEquals(1, n.size());
        assertEquals("a", n.get(0).order().id());
    }

    @Test
    void anotherUserOrOriginStartsANewBaseline() {
        OrderWatch w = new OrderWatch();
        w.setOwner("o\u0000u1");
        w.update(SCOPE, List.of(order("a", "in_progress")));
        w.setOwner("o\u0000u1");
        assertTrue(w.baselined(SCOPE));
        w.setOwner("o\u0000u2");
        assertFalse(w.baselined(SCOPE));
        assertTrue(w.update(SCOPE, List.of(order("a", "delivered"))).isEmpty());
    }

    @Test
    void ordersLeavingTheListAreForgotten() {
        OrderWatch w = new OrderWatch();
        w.update(SCOPE, List.of(order("a", "delivered"), order("b", "pending")));
        w.update(SCOPE, List.of(order("b", "pending")));
        assertTrue(w.hasOpen(SCOPE));
        w.update(SCOPE, List.of(order("b", "failed")));
        assertFalse(w.hasOpen(SCOPE));
    }

    // ---------- arriving ----------

    @Test
    void arrivingNotifiesOnceWhenTheDeliveryTripStarts() {
        OrderWatch w = new OrderWatch();
        w.update(SCOPE, List.of(order("a", "in_progress", "gathering 3/3")));
        List<OrderWatch.Notice> n = w.update(SCOPE, List.of(order("a", "in_progress", "delivering 1/2")));
        assertEquals(List.of(Kind.ARRIVING), n.stream().map(OrderWatch.Notice::kind).toList());
        assertTrue(w.update(SCOPE, List.of(order("a", "in_progress", "delivering 2/2"))).isEmpty());
        // Back to checking and delivering again does not repeat it
        assertTrue(w.update(SCOPE, List.of(order("a", "in_progress", "checking delivery 2/2"))).isEmpty());
        assertTrue(w.update(SCOPE, List.of(order("a", "in_progress", "delivering 2/2"))).isEmpty());
        // And the end is still announced
        assertEquals(List.of(Kind.COMPLETE), w.update(SCOPE, List.of(order("a", "delivered"))).stream().map(OrderWatch.Notice::kind).toList());
    }

    @Test
    void anOrderAlreadyDeliveringAtBaselineIsNotAnnounced() {
        OrderWatch w = new OrderWatch();
        w.update(SCOPE, List.of(order("a", "in_progress", "delivering 1/2")));
        assertTrue(w.update(SCOPE, List.of(order("a", "in_progress", "delivering 2/2"))).isEmpty());
    }

    @Test
    void checkingDeliveryOrAnotherStatusIsNotArriving() {
        assertFalse(order("a", "in_progress", "checking delivery 1/2").delivering());
        assertFalse(order("a", "accepted", "delivering 1/2").delivering());
        assertFalse(order("a", "in_progress", "(hidden)").delivering());
        assertTrue(order("a", "in_progress", "Delivering 1/2").delivering());
    }

    @Test
    void finishingStraightFromTheTripGivesOnlyTheOutcome() {
        OrderWatch w = new OrderWatch();
        w.update(SCOPE, List.of(order("a", "accepted")));
        List<OrderWatch.Notice> n = w.update(SCOPE, List.of(order("a", "delivered")));
        assertEquals(1, n.size());
    }

    // ---------- outcome classification ----------

    @Test
    void deliveredInFullIsComplete() {
        List<OrderSnap> l = parse("""
            {"orders":[{"id":"o-1","status":"delivered","lines":[{"type":"item","key":"minecraft:stone","count":64}],
            "result":{"shulkers":1,"lines":[{"key":"minecraft:stone","delivered":64}]}}]}""", OWNER);
        assertEquals(Kind.COMPLETE, l.get(0).outcome());
    }

    @Test
    void aLineDeliveredShortIsPartial() {
        List<OrderSnap> l = parse("""
            {"orders":[{"id":"o-1","status":"delivered","lines":[{"type":"item","key":"minecraft:stone","count":64}],
            "result":{"shulkers":1,"lines":[{"key":"minecraft:stone","delivered":50}]}}]}""", OWNER);
        assertEquals(Kind.PARTIAL, l.get(0).outcome());
        assertEquals(List.of(new OrderSnap.Short("minecraft:stone", 14)), l.get(0).shorts());
    }

    @Test
    void aCraftShortIsPartial() {
        List<OrderSnap> l = parse("""
            {"orders":[{"id":"o-1","status":"delivered","lines":[{"type":"craft","key":"minecraft:oak_fence","count":8}],
            "result":{"shulkers":1,"lines":[{"key":"minecraft:oak_fence","delivered":8}],
            "craft":{"fills":1,"made":[],"short":[{"key":"minecraft:oak_fence","count":3,"reason":"ingredients_short"}]}}}]}""", OWNER);
        assertEquals(Kind.PARTIAL, l.get(0).outcome());
    }

    @Test
    void aKitShortIsPartial() {
        List<OrderSnap> l = parse("""
            {"orders":[{"id":"o-1","status":"delivered","lines":[],
            "result":{"shulkers":1,"lines":[],"kit":{"made":[],"short":[{"template":"t-1","kits":2,"items":[{"key":"minecraft:totem_of_undying","match":"exact","count":2}],"stored":0,"reason":"short"}],"notes":[]}}}]}""", OWNER);
        assertEquals(Kind.PARTIAL, l.get(0).outcome());
        assertEquals("minecraft:totem_of_undying", l.get(0).shorts().get(0).key());
    }

    @Test
    void anEmptyCraftShortListStaysComplete() {
        List<OrderSnap> l = parse("""
            {"orders":[{"id":"o-1","status":"delivered","lines":[],"result":{"shulkers":1,"lines":[],"craft":{"fills":1,"short":[]},"kit":{"made":[],"short":[]}}}]}""", OWNER);
        assertEquals(Kind.COMPLETE, l.get(0).outcome());
    }

    @Test
    void deliveredWithoutAResultIsComplete() {
        assertEquals(Kind.COMPLETE, parse("{\"orders\":[{\"id\":\"o-1\",\"status\":\"delivered\",\"lines\":[]}]}", OWNER).get(0).outcome());
    }

    @Test
    void failedAndCancelledAreTheirOwnKinds() {
        List<OrderSnap> l = parse("{\"orders\":[{\"id\":\"o-1\",\"status\":\"failed\"},{\"id\":\"o-2\",\"status\":\"cancelled\"},{\"id\":\"o-3\",\"status\":\"pending\"}]}", OWNER);
        assertEquals(Kind.FAILED, l.get(0).outcome());
        assertEquals(Kind.CANCELLED, l.get(1).outcome());
        assertEquals(null, l.get(2).outcome());
    }

    // ---------- whose orders ----------

    @Test
    void onlyTheUsersOwnOrdersCount() {
        String json = """
            {"orders":[{"id":"o-1","status":"failed","placedBy":{"id":"u1","name":"me"}},
            {"id":"o-2","status":"failed","placedBy":{"id":"u2","name":"you"}},
            {"id":"o-3","status":"failed"}]}""";
        assertEquals(List.of("o-1"), parse(json, ME).stream().map(OrderSnap::id).toList());
        assertEquals(List.of("o-3"), parse(json, OWNER).stream().map(OrderSnap::id).toList());
    }

    @Test
    void theUserOfAServerWithoutSubUsersIsTheOwner() {
        OrderSnaps.User u = OrderSnaps.User.parse(JsonParser.parseString("{\"loggedIn\":true}"));
        assertTrue(u.loggedIn());
        assertTrue(u.owner());
        assertFalse(OrderSnaps.User.parse(JsonParser.parseString("{\"loggedIn\":false}")).loggedIn());
        OrderSnaps.User sub = OrderSnaps.User.parse(JsonParser.parseString("{\"loggedIn\":true,\"user\":{\"id\":\"u9\",\"owner\":false}}"));
        assertEquals("u9", sub.id());
        assertFalse(sub.owner());
    }

    @Test
    void garbageIsSkippedNotThrown() {
        assertNull(parse("[]", OWNER));
        assertNull(parse("{}", OWNER));
        assertNull(parse("{\"orders\":{}}", OWNER));
        assertTrue(parse("{\"orders\":[]}", OWNER).isEmpty());
        assertTrue(parse("{\"orders\":[1,null,{\"status\":\"failed\"},{\"id\":\"x\"}]}", OWNER).isEmpty());
        assertTrue(OrderSnaps.parseStashes(JsonParser.parseString("{}")).isEmpty());
    }

    // ---------- stash list and poll plan ----------

    @Test
    void thePlanFetchesNewAndBusyStashesAndEverythingEverySixthCycle() {
        OrderWatch w = new OrderWatch();
        List<OrderSnaps.StashEntry> stashes = OrderSnaps.parseStashes(JsonParser.parseString(
            "[{\"server\":\"s\",\"stash\":\"a\",\"orders\":{\"open\":0}},{\"server\":\"s\",\"stash\":\"b\",\"orders\":{\"open\":2}},{\"server\":\"s\",\"stash\":\"c\"}]"));
        assertEquals(3, PollPlan.scopes(stashes, w, 1).size());
        for (OrderSnaps.StashEntry e : stashes) w.update(e.scope(), List.of());
        // a is quiet and known; b has open orders; c did not say
        assertEquals(List.of(OrderSnaps.scope("s", "b"), OrderSnaps.scope("s", "c")), PollPlan.scopes(stashes, w, 1));
        assertEquals(3, PollPlan.scopes(stashes, w, PollPlan.FULL_EVERY).size());
        // a had an open order at the last look
        w.update(OrderSnaps.scope("s", "a"), List.of(order("x", "in_progress")));
        assertTrue(PollPlan.scopes(stashes, w, 1).contains(OrderSnaps.scope("s", "a")));
    }

    // ---------- text ----------

    @Test
    void streamerTextHasNoStashNameOrBotNote() {
        OrderSnap o = new OrderSnap("o-1", "failed", "", "cannot reach 100 64 -200 (x)", List.of());
        OrderWatch.Notice n = new OrderWatch.Notice(SCOPE, o, Kind.FAILED);
        NoticeText.Text normal = NoticeText.of(n, "base", false);
        assertTrue(normal.body().contains("base"));
        assertTrue(normal.body().contains("cannot reach"));
        assertFalse(normal.body().contains("("));
        NoticeText.Text streamer = NoticeText.of(n, "base", true);
        assertEquals("o-1", streamer.body());
    }

    @Test
    void partialTextNamesTheItems() {
        OrderSnap o = new OrderSnap("o-1", "delivered", "", "", List.of(new OrderSnap.Short("minecraft:oak_planks#1a2b3c4d", 12), new OrderSnap.Short("minecraft:stone", 0),
            new OrderSnap.Short("a", 1), new OrderSnap.Short("b", 1), new OrderSnap.Short("c", 1)));
        String body = NoticeText.of(new OrderWatch.Notice(SCOPE, o, Kind.PARTIAL), null, true).body();
        assertEquals("o-1. Short: oak planks x12, stone, a x1 and 2 more", body);
    }

    @Test
    void textIsSafeForChat() {
        assertEquals("100pct  x", NoticeText.safe("100% (x)", 40));
    }
}

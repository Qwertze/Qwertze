package com.porter.qwerte.manager.model;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderModelTest {
    private static final String ORDERS = """
        {"orders":[
          {"id":"o-1","status":"in_progress","phase":"gathering 3/10","progress":{"done":3,"total":10},"message":"","cancelRequested":false,"createdAt":1000,"updatedAt":2000,
           "placedBy":{"id":"u1","name":"alice"},"names":{"mode":"all","name":"Loot"},
           "lines":[{"type":"item","key":"minecraft:diamond","count":640},{"type":"shulker","key":"s:abc","count":2},{"type":"bogus","key":"x","count":1},{"type":"item","key":"y","count":0}]},
          {"id":"o-2","status":"failed","message":"no space\\u00a7c","lines":[{"type":"item","key":"minecraft:apple","count":1}],"names":null,"retriedAs":"o-9"},
          {"id":"o-3","status":"cancelled","lines":[{"type":"item","key":"minecraft:apple","count":1}],"placedBy":{"id":"u2","name":"bob"}},
          {"id":"o-4","status":"in_progress","cancelRequested":true,"cancelRequestedAt":10000,"updatedAt":9000,"lines":[{"type":"item","key":"a","count":1}],"placedBy":{"id":"u1","name":"alice"}},
          {"id":"","status":"pending","lines":[]},
          {"status":"pending"},
          {"id":"o-5","status":"delivered","lines":[{"type":"item","key":"a","count":1}],"result":{"shulkers":4}}
        ],"available":{"items":{"minecraft:diamond":120,"bad":"x"},"shulkers":{"s:abc":4}}}
        """;

    private static OrderModel.Listing listing() {
        return OrderModel.parse(JsonParser.parseString(ORDERS));
    }

    private static AuthState auth(String json) {
        return AuthState.parse(JsonParser.parseString(json));
    }

    private static AuthState user(String id, String perms) {
        return auth("{\"loggedIn\":true,\"user\":{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"owner\":false,\"perms\":" + perms + "}}");
    }

    private static OrderModel.Entry entry(String id) {
        return listing().orders().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void parsesOrdersAndSkipsBrokenOnes() {
        OrderModel.Listing l = listing();
        assertEquals(5, l.orders().size());
        OrderModel.Entry o = l.orders().get(0);
        assertEquals("o-1", o.id());
        assertEquals(2, o.lines().size());
        assertEquals(3, o.done());
        assertEquals(10, o.total());
        assertEquals("all", o.namesMode());
        assertEquals("Loot", o.namesAll());
        assertEquals("alice", o.placedByName());
        assertEquals("no spaceC".replace("C", "c"), entry("o-2").message());
        assertEquals(4, entry("o-5").shulkers());
        assertTrue(entry("o-5").hasResult());
    }

    @Test
    void availabilityByTypeAndKey() {
        OrderModel.Listing l = listing();
        assertEquals(120L, l.available("item", "minecraft:diamond"));
        assertEquals(4L, l.available("shulker", "s:abc"));
        assertNull(l.available("item", "minecraft:apple"));
        assertNull(l.available("item", "bad"));
        assertEquals(3, l.finishedCount());
    }

    @Test
    void junkGivesAnEmptyListing() {
        assertTrue(OrderModel.parse(JsonParser.parseString("[]")).orders().isEmpty());
        assertTrue(OrderModel.parse(JsonParser.parseString("null")).availableItems().isEmpty());
    }

    @Test
    void ownerManagesEverythingAndOwnsOrdersWithoutPlacedBy() {
        AuthState owner = auth("{\"loggedIn\":true}");
        assertTrue(OrderModel.canManage(entry("o-1"), owner));
        assertTrue(OrderModel.canManage(entry("o-5"), owner));
        assertTrue(OrderModel.canClear(owner));
        assertTrue(OrderModel.canPlace(owner));
    }

    @Test
    void anOrdererManagesOnlyTheirOwnOrders() {
        AuthState alice = user("u1", "{\"order\":true,\"view\":true}");
        assertTrue(OrderModel.canManage(entry("o-1"), alice));
        assertFalse(OrderModel.canManage(entry("o-3"), alice));
        // an order from before users belongs to the owner
        assertFalse(OrderModel.canManage(entry("o-2"), alice));
        assertTrue(OrderModel.canCancel(entry("o-1"), alice));
        assertFalse(OrderModel.canCancel(entry("o-4"), alice));
        assertFalse(OrderModel.canClear(alice));
        assertTrue(OrderModel.canPlace(alice));
    }

    @Test
    void plannerOrderCoversOwnOrdersButDoesNotPlace() {
        AuthState alice = user("u1", "{\"plannerOrder\":true,\"view\":true}");
        assertTrue(OrderModel.canManage(entry("o-1"), alice));
        assertFalse(OrderModel.canPlace(alice));
    }

    @Test
    void ordersAllManagesAnyOrderAndClears() {
        AuthState op = user("u9", "{\"ordersAll\":true,\"order\":true}");
        assertTrue(OrderModel.canManage(entry("o-3"), op));
        assertTrue(OrderModel.canClear(op));
    }

    @Test
    void aViewerCanDoNothing() {
        AuthState viewer = user("u1", "{\"view\":true}");
        assertFalse(OrderModel.canManage(entry("o-1"), viewer));
        assertFalse(OrderModel.canPlace(viewer));
    }

    @Test
    void retryOnlyForFailedOrCancelledAndNotRetriedBefore() {
        AuthState owner = auth("{\"loggedIn\":true}");
        assertFalse(OrderModel.canRetry(entry("o-2"), owner), "already retried as o-9");
        assertTrue(OrderModel.canRetry(entry("o-3"), owner));
        assertFalse(OrderModel.canRetry(entry("o-1"), owner));
        assertFalse(OrderModel.canRetry(entry("o-5"), owner));
    }

    @Test
    void cancelNeedsAnOpenOrderWithNoCancelRequested() {
        AuthState owner = auth("{\"loggedIn\":true}");
        assertTrue(OrderModel.canCancel(entry("o-1"), owner));
        assertFalse(OrderModel.canCancel(entry("o-4"), owner));
        assertFalse(OrderModel.canCancel(entry("o-5"), owner));
    }

    @Test
    void forceCancelAfterAMinuteOrWhileTheBotIsOffline() {
        AuthState owner = auth("{\"loggedIn\":true}");
        OrderModel.Entry o = entry("o-4");
        assertFalse(OrderModel.canForceCancel(o, owner, true, 10_000 + 59_999));
        assertTrue(OrderModel.canForceCancel(o, owner, true, 10_000 + 60_000));
        assertTrue(OrderModel.canForceCancel(o, owner, false, 10_001));
        // no cancel requested: never
        assertFalse(OrderModel.canForceCancel(entry("o-1"), owner, false, Long.MAX_VALUE));
        // falls back to updatedAt when the cancel time is missing
        OrderModel.Entry noStamp = new OrderModel.Entry("o", "accepted", "", "", 0, 0, true, 0, 5_000, 0, false, "", "", "", java.util.List.of(), "", "", java.util.List.of(), 0, false);
        assertFalse(OrderModel.canForceCancel(noStamp, owner, true, 64_999));
        assertTrue(OrderModel.canForceCancel(noStamp, owner, true, 65_000));
        assertNotNull(OrderModel.progressText(1, 2, 10));
    }

    @Test
    void progressText() {
        assertEquals("[#####-----] 5/10", OrderModel.progressText(5, 10, 10));
        assertEquals("[##########] 10/10", OrderModel.progressText(99, 10, 10));
        assertEquals("", OrderModel.progressText(0, 0, 10));
    }
}

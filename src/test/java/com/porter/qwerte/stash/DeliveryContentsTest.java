package com.porter.qwerte.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.stash.DeliveryBook.Pos;
import com.porter.qwerte.stash.DeliveryContents.Chest;
import com.porter.qwerte.stash.DeliveryContents.Inner;
import com.porter.qwerte.stash.DeliveryContents.Item;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The delivery contents report: its shape, its hash, and when it is sent. */
class DeliveryContentsTest {
    @Test
    void sentLogIsThrottledButAlwaysLogsTheFirstSuccessAfterAFailure() {
        DeliveryContents.SentLog log = new DeliveryContents.SentLog();
        assertTrue(log.sent(1_000));
        assertFalse(log.sent(2_000));
        assertFalse(log.sent(1_000 + DeliveryContents.SentLog.INTERVAL_MS - 1));
        assertTrue(log.sent(1_000 + DeliveryContents.SentLog.INTERVAL_MS));
        log.failed();
        assertTrue(log.sent(61_500));
        assertFalse(log.sent(62_000));
        assertEquals("Delivery report sent: 1 chest", DeliveryContents.SentLog.line(1));
        assertEquals("Delivery report sent: 3 chests", DeliveryContents.SentLog.line(3));
    }

    @Test
    void builtCountsTheChestsInTheReport() {
        List<Chest> chests = new ArrayList<>();
        chests.add(new Chest(27, false, 0, null));
        chests.add(new Chest(54, true, 0, null));
        assertEquals(2, DeliveryContents.build("s", "st", "p", chests, 0).chests());
    }

    private static Item stone(int slot, int count) {
        return new Item(slot, "minecraft:cobblestone", "Cobblestone", count, 64, null, 0, 0);
    }

    private static JsonObject json(DeliveryContents.Built b) {
        return JsonParser.parseString(new String(b.json(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static DeliveryContents.Built build(List<Chest> chests) {
        return DeliveryContents.build("2b2t.org", "stash", "Qwerttze", chests, 1L);
    }

    @Test
    void fillOrderListsChestsWithRoomFirstThenFullOnes() {
        DeliveryBook b = new DeliveryBook();
        b.syncPrimary(List.of(new Pos(10, 64, 20)));
        DeliveryBook.Entry second = b.add(List.of(new Pos(10, 65, 20)));
        DeliveryBook.Entry third = b.add(List.of(new Pos(10, 66, 20)));
        b.markFull(b.primary(), 1);
        assertEquals(List.of(second, third, b.primary()), b.fillOrder());
        b.markRoom(b.primary(), 2);
        assertEquals(List.of(b.primary(), second, third), b.fillOrder());
    }

    @Test
    void chestsAreNumberedInTheGivenOrderAndCappedAt16() {
        List<Chest> chests = new ArrayList<>();
        for (int i = 0; i < 20; i++) chests.add(new Chest(54, i == 1, 100 + i, List.of(stone(0, 1))));
        JsonArray out = json(build(chests)).getAsJsonArray("chests");
        assertEquals(16, out.size());
        for (int i = 0; i < 16; i++) assertEquals(i, out.get(i).getAsJsonObject().get("n").getAsInt());
        assertTrue(out.get(1).getAsJsonObject().get("full").getAsBoolean());
        assertFalse(out.get(0).getAsJsonObject().get("full").getAsBoolean());
    }

    @Test
    void anUnreadChestHasNoItemsAndReadAtZero() {
        JsonObject c = json(build(List.of(new Chest(54, true, 0, null)))).getAsJsonArray("chests").get(0).getAsJsonObject();
        assertEquals(0, c.get("readAt").getAsLong());
        assertTrue(c.get("items").isJsonNull());
        assertEquals(54, c.get("size").getAsInt());
    }

    @Test
    void slotsAreCappedToTheSizeAndOnlyUsedOnesListedOnce() {
        List<Item> items = List.of(stone(30, 1), stone(26, 5), stone(26, 9), stone(-1, 3), stone(3, 0), stone(0, 2));
        JsonArray out = json(build(List.of(new Chest(27, false, 5, items)))).getAsJsonArray("chests").get(0).getAsJsonObject().getAsJsonArray("items");
        assertEquals(2, out.size());
        assertEquals(0, out.get(0).getAsJsonObject().get("slot").getAsInt());
        assertEquals(26, out.get(1).getAsJsonObject().get("slot").getAsInt());
        assertEquals(5, out.get(1).getAsJsonObject().get("count").getAsInt());
    }

    @Test
    void shulkerContentsAreGroupedByIdAndCappedAt27() {
        List<Inner> inside = new ArrayList<>();
        inside.add(new Inner("minecraft:diamond", 64));
        inside.add(new Inner("minecraft:emerald", 3));
        inside.add(new Inner("minecraft:diamond", 64));
        assertEquals(List.of(new Inner("minecraft:diamond", 128), new Inner("minecraft:emerald", 3)), DeliveryContents.group(inside));

        List<Inner> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(new Inner("minecraft:item_" + i, 1));
        assertEquals(27, DeliveryContents.group(many).size());

        Item shulker = new Item(4, "minecraft:red_shulker_box", "Gems", 1, 1, inside, 0, 0);
        JsonObject o = json(build(List.of(new Chest(54, false, 5, List.of(shulker, stone(5, 64)))))).getAsJsonArray("chests").get(0).getAsJsonObject()
            .getAsJsonArray("items").get(0).getAsJsonObject();
        assertTrue(o.get("shulker").getAsBoolean());
        assertEquals(2, o.getAsJsonArray("contents").size());
        assertEquals(128, o.getAsJsonArray("contents").get(0).getAsJsonObject().get("count").getAsInt());
        assertFalse(json(build(List.of(new Chest(54, false, 5, List.of(stone(5, 64)))))).getAsJsonArray("chests").get(0).getAsJsonObject()
            .getAsJsonArray("items").get(0).getAsJsonObject().has("shulker"));
        // An empty shulker is still a shulker, with no contents
        Item empty = new Item(0, "minecraft:shulker_box", "Shulker Box", 1, 1, List.of(), 0, 0);
        JsonObject e = json(build(List.of(new Chest(27, false, 5, List.of(empty))))).getAsJsonArray("chests").get(0).getAsJsonObject()
            .getAsJsonArray("items").get(0).getAsJsonObject();
        assertTrue(e.get("shulker").getAsBoolean());
        assertEquals(0, e.getAsJsonArray("contents").size());
    }

    @Test
    void namesAreCutAtSixtyFourCharactersAndLoseControlCharacters() {
        assertEquals(64, DeliveryContents.cut("x".repeat(100)).length());
        assertTrue(DeliveryContents.cut("x".repeat(100)).endsWith("…"));
        assertEquals("x".repeat(64), DeliveryContents.cut("x".repeat(64)));
        assertEquals("ab", DeliveryContents.cut("a\u0000b\n"));
        assertEquals("", DeliveryContents.cut(null));
        // Not through the middle of a surrogate pair
        String emoji = "x".repeat(62) + "😀" + "yy";
        assertEquals(63, DeliveryContents.cut(emoji).length());
        assertTrue(DeliveryContents.cut(emoji).endsWith("…"));
    }

    @Test
    void theHashIgnoresReadAtButNotContents() {
        List<Item> items = List.of(stone(0, 10));
        DeliveryContents.Built a = build(List.of(new Chest(54, false, 100, items)));
        DeliveryContents.Built b = build(List.of(new Chest(54, false, 999, items)));
        assertEquals(a.hash(), b.hash());
        assertEquals(40, a.hash().length());
        assertNotEquals(a.hash(), build(List.of(new Chest(54, false, 100, List.of(stone(0, 11))))).hash());
        assertNotEquals(a.hash(), build(List.of(new Chest(54, true, 100, items))).hash());
        assertEquals(999, b.readAt());
        assertEquals(999, json(b).get("readAt").getAsLong());
        // Built twice from the same things: the same bytes
        assertEquals(new String(a.json(), StandardCharsets.UTF_8), new String(build(List.of(new Chest(54, false, 100, items))).json(), StandardCharsets.UTF_8));
    }

    @Test
    void noPositionsAreInTheReport() {
        String text = new String(build(List.of(new Chest(54, false, 5, List.of(stone(0, 1))))).json(), StandardCharsets.UTF_8);
        for (String key : List.of("\"x\"", "\"y\"", "\"z\"", "\"pos\"", "\"at\"", "\"dimension\"")) assertFalse(text.contains(key), key);
        JsonObject chest = JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("chests").get(0).getAsJsonObject();
        assertEquals(java.util.Set.of("n", "size", "full", "readAt", "items"), chest.keySet());
    }

    @Test
    void theReportIsSentOnlyWhenTheHashChangedOrTheServerAsked() {
        DeliveryContents.Sync s = new DeliveryContents.Sync();
        // Nothing sent yet: the first build goes out at once
        assertTrue(s.due(0));
        assertTrue(s.built("h1", 0));
        s.sent("h1");
        // A read with nothing changed: built, not sent
        s.changed();
        assertFalse(s.due(1_000));
        assertTrue(s.due(5_000));
        assertFalse(s.built("h1", 5_000));
        // A change
        s.changed();
        assertTrue(s.due(10_000));
        assertTrue(s.built("h2", 10_000));
    }

    @Test
    void buildsAreDebouncedToFiveSeconds() {
        DeliveryContents.Sync s = new DeliveryContents.Sync();
        s.built("h1", 1_000);
        s.sent("h1");
        s.changed();
        assertFalse(s.due(1_001));
        assertFalse(s.due(5_999));
        assertTrue(s.due(6_000));
        s.built("h2", 6_000);
        assertFalse(s.due(7_000));
        // Nothing changed and nobody asked: nothing to build
        assertFalse(s.due(60_000));
    }

    @Test
    void needDeliveryMakesTheNextBuildSendEvenWithTheSameHash() {
        DeliveryContents.Sync s = new DeliveryContents.Sync();
        s.built("h1", 0);
        s.sent("h1");
        assertFalse(s.due(100_000));
        s.ask();
        assertTrue(s.due(100_000));
        assertTrue(s.built("h1", 100_000));
        s.sent("h1");
        assertFalse(s.due(200_000));
        // Asked inside the debounce: waits for it
        s.ask();
        s.built("h1", 300_000);
        s.sent("h1");
        s.ask();
        assertFalse(s.due(302_000));
        assertTrue(s.due(305_000));
    }

    @Test
    void noContainerClearsTheAskAndAStashNameRefusalForgetsTheSentHash() {
        DeliveryContents.Sync s = new DeliveryContents.Sync();
        assertTrue(s.built("h1", 0));
        s.ask();
        s.none(10_000);
        assertFalse(s.due(20_000));
        s.sent("h1");
        s.forget();
        assertTrue(s.built("h1", 30_000));
    }

    @Test
    void nothingIsSentUntilAHeartbeatReplyCarriesNeedDelivery() {
        DeliveryContents.Sync s = new DeliveryContents.Sync();
        assertFalse(s.canSend(1_000_000));
        // An older server: replies without the key, however many
        s.onHeartbeatReply("{\"ok\":true,\"needIndex\":false}");
        s.onHeartbeatReply("not json");
        s.onHeartbeatReply("[]");
        assertFalse(s.enabled());
        assertFalse(s.canSend(2_000_000));
        // A new server always has it, even false
        s.onHeartbeatReply("{\"needIndex\":false,\"needDelivery\":false}");
        assertTrue(s.enabled());
        assertTrue(s.canSend(0));
    }

    @Test
    void needDeliveryInAHeartbeatReplyAsksForTheReport() {
        DeliveryContents.Sync s = new DeliveryContents.Sync();
        s.built("h1", 0);
        s.sent("h1");
        assertFalse(s.due(100_000));
        s.onHeartbeatReply("{\"needDelivery\":true}");
        assertTrue(s.due(100_000));
        assertTrue(s.built("h1", 100_000));
    }

    @Test
    void aFailureHasItsOwnRetryTimerDoublingFromSixtySecondsToTenMinutes() {
        DeliveryContents.Sync s = new DeliveryContents.Sync();
        s.onHeartbeatReply("{\"needDelivery\":false}");
        s.retryLater(1_000);
        assertFalse(s.canSend(60_999));
        assertTrue(s.canSend(61_000));
        s.retryLater(61_000);
        assertFalse(s.canSend(180_999));
        assertTrue(s.canSend(181_000));
        for (int i = 0; i < 10; i++) s.retryLater(0);
        assertFalse(s.canSend(599_999));
        assertTrue(s.canSend(600_000));
        s.sent("h1");
        assertTrue(s.canSend(0));
    }

    @Test
    void aRejectedHashIsNotSentAgainEvenWhenAskedUntilItChanges() {
        DeliveryContents.Sync s = new DeliveryContents.Sync();
        assertTrue(s.built("h1", 0));
        s.rejected("h1");
        s.ask();
        assertFalse(s.built("h1", 5_000));
        s.ask();
        assertFalse(s.built("h1", 10_000));
        // The refused ask is spent: no rebuild every five seconds
        assertFalse(s.due(100_000));
        assertTrue(s.built("h2", 15_000));
    }

    private static Item shulkerWith(int slot, String idPrefix, int idLength) {
        List<Inner> inside = new ArrayList<>();
        for (int i = 0; i < 10; i++) inside.add(new Inner(idPrefix + i + "x".repeat(idLength), 64));
        return new Item(slot, "minecraft:red_shulker_box" + "x".repeat(idLength), "n".repeat(64), 1, 1, inside, 0, 0);
    }

    private static List<Chest> fullOfShulkers(int idLength) {
        List<Chest> chests = new ArrayList<>();
        for (int c = 0; c < 16; c++) {
            List<Item> items = new ArrayList<>();
            for (int slot = 0; slot < 54; slot++) items.add(shulkerWith(slot, "minecraft:item_", idLength));
            chests.add(new Chest(54, true, 100 + c, items));
        }
        return chests;
    }

    @Test
    void aTooBigReportDropsShulkerContentsFirst() {
        DeliveryContents.Built b = build(fullOfShulkers(0));
        assertTrue(b.json().length <= 230_000, "size " + b.json().length);
        JsonArray chests = json(b).getAsJsonArray("chests");
        JsonObject first = chests.get(0).getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject();
        assertTrue(first.get("shulker").getAsBoolean());
        assertFalse(first.has("contents"));
        // Every chest keeps its items
        for (int i = 0; i < 16; i++) assertFalse(chests.get(i).getAsJsonObject().get("items").isJsonNull());
    }

    @Test
    void ifThatIsNotEnoughTheLastChestsLoseTheirItems() {
        DeliveryContents.Built b = build(fullOfShulkers(120));
        assertTrue(b.json().length <= 230_000, "size " + b.json().length);
        JsonArray chests = json(b).getAsJsonArray("chests");
        assertFalse(chests.get(0).getAsJsonObject().get("items").isJsonNull());
        assertTrue(chests.get(15).getAsJsonObject().get("items").isJsonNull());
        assertEquals(0, chests.get(15).getAsJsonObject().get("readAt").getAsLong());
        assertEquals(16, chests.size());
    }
}

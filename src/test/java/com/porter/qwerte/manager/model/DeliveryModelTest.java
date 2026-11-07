package com.porter.qwerte.manager.model;

import com.google.gson.JsonParser;
import com.porter.qwerte.manager.model.CartPlan.Kind;
import com.porter.qwerte.manager.model.CartPlan.Unit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryModelTest {
    private static final long NOW = 1_767_225_600_000L;

    /** The PROTOCOL sample: chest 0 has a named shulker in slot 0 and cobblestone in slot 5; chest 1 is full and not seen. */
    private static final String ONE_CHEST = """
        {"version":1,"readAt":1767225500000,"chests":[
          {"n":0,"size":54,"full":false,"readAt":1767225500000,
           "items":[{"slot":0,"id":"minecraft:red_shulker_box","name":"Gems","count":1,"maxStack":1,"shulker":true,"contents":[{"id":"minecraft:diamond","count":1728}]},
                    {"slot":5,"id":"minecraft:cobblestone","name":"Cobblestone","count":64,"maxStack":64}]}]}
        """;

    private static final String TWO_CHESTS = """
        {"readAt":1767225500000,"chests":[
          {"n":0,"size":27,"full":false,"readAt":1767225500000,"items":[{"slot":0,"id":"minecraft:stone","name":"Stone","count":1,"maxStack":64}]},
          {"n":1,"size":54,"full":true,"readAt":0,"items":null}]}
        """;

    private static List<Unit> units(int n) {
        List<Unit> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Unit(Kind.EMPTY, "minecraft:shulker_box", List.of()));
        return out;
    }

    private static DeliveryModel parse(String json) {
        return DeliveryModel.parse(JsonParser.parseString(json));
    }

    @Test
    void parsesTheReportAndCleansNames() {
        DeliveryModel m = parse(ONE_CHEST);
        assertEquals(1, m.chests().size());
        DeliveryModel.Chest c = m.chests().get(0);
        assertEquals(54, c.size());
        assertEquals(2, c.items().size());
        assertTrue(c.items().get(0).shulker());
        assertEquals(1728, c.items().get(0).contents().get(0).count());
        DeliveryModel evil = parse("{\"chests\":[{\"n\":0,\"size\":27,\"items\":[{\"slot\":1,\"id\":\"minecraft:stone\",\"name\":\"\\u00a7cRed\\u0007\",\"count\":1}]}]}");
        assertEquals("cRed", evil.chests().get(0).items().get(0).name());
    }

    @Test
    void junkIsEmptyAndBadChestsAreSkipped() {
        assertTrue(DeliveryModel.parse(JsonParser.parseString("null")).isEmpty());
        assertTrue(parse("{\"chests\":\"x\"}").isEmpty());
        DeliveryModel m = parse("{\"chests\":[{\"n\":0,\"size\":30,\"items\":[]},{\"n\":1,\"size\":27,\"items\":[{\"slot\":27,\"id\":\"a:b\"},{\"slot\":2,\"id\":\"a:b\"},{\"slot\":2,\"id\":\"a:c\"}]}]}");
        assertEquals(1, m.chests().size());
        assertEquals(1, m.chests().get(0).items().size());
    }

    @Test
    void plannedShulkersTakeTheFreeSlotsAfterWhatIsThere() {
        DeliveryModel m = parse(ONE_CHEST);
        DeliveryModel.Layout lay = m.layout(units(3));
        assertNotNull(lay.existing()[0]);
        assertNotNull(lay.existing()[5]);
        // free slots are 1, 2, 3, 4, 6, ...
        assertEquals(0, lay.slotUnit()[1]);
        assertEquals(1, lay.slotUnit()[2]);
        assertEquals(2, lay.slotUnit()[3]);
        assertEquals(-1, lay.slotUnit()[0]);
        assertEquals(54, lay.size());
        assertFalse(lay.anyOverflow());
        assertEquals(1, lay.unitSlot()[0]);
    }

    @Test
    void withNoReportTheUnitsFillFromTheFirstSlot() {
        DeliveryModel.Layout lay = DeliveryModel.EMPTY.layout(units(2));
        assertEquals(0, lay.slotUnit()[0]);
        assertEquals(1, lay.slotUnit()[1]);
        assertEquals(0, lay.size());
        assertFalse(lay.anyOverflow());
    }

    @Test
    void unitsPastTheChestsFreeRoomAreMarkedOverflow() {
        // a 27-slot chest with 1 used has 26 free: the 27th unit goes to the next chest
        DeliveryModel m = parse(TWO_CHESTS);
        DeliveryModel.Layout lay = m.layout(units(28));
        assertNull(lay.existing()[1]);
        assertTrue(lay.anyOverflow());
        int overflowing = 0;
        for (boolean b : lay.overflow()) if (b) overflowing++;
        assertEquals(2, overflowing);
        assertEquals(27, lay.size());
        // the 27th unit (index 26) is the first that does not fit: it is put at slot 27, outside the chest
        assertEquals(27, lay.unitSlot()[26]);
        assertTrue(lay.overflow()[27]);
    }

    @Test
    void fitTextForOneChest() {
        DeliveryModel m = parse(ONE_CHEST);
        DeliveryModel.Fit fit = m.fit(3, m.layout(units(3)), false, NOW);
        assertEquals("Delivery chest: 2 of 54 slots used, 52 free; this order needs 3 (as of 1 min ago).", fit.text());
        assertEquals("", fit.warning());
    }

    @Test
    void fitTextForSeveralChestsAndUnseenOnes() {
        DeliveryModel m = parse(TWO_CHESTS);
        DeliveryModel.Fit fit = m.fit(0, m.layout(units(0)), false, NOW);
        assertEquals("2 delivery chests: 1 of 27 slots used, 26 free (1 not seen yet); the first (filled first) has 26 of 27 free (as of 1 min ago).", fit.text());
        DeliveryModel unseen = parse("{\"chests\":[{\"n\":0,\"size\":54,\"items\":null}]}");
        assertEquals("Delivery chest: not seen yet; this order needs 4.", unseen.fit(4, unseen.layout(units(4)), false, NOW).text());
        DeliveryModel none = parse("{\"chests\":[{\"n\":0,\"size\":54,\"items\":null},{\"n\":1,\"size\":54,\"items\":null}]}");
        assertTrue(none.fit(0, none.layout(units(0)), false, NOW).text().startsWith("2 delivery chests: none seen yet; the first is not seen yet"));
    }

    @Test
    void warnsWhenTheOrderWontFitAndSaysWhatTheBotDoes() {
        // 27-slot chest with 26 free, but the other chest was not seen: no "won't fit" claim, only the overflow note
        DeliveryModel unseenSecond = parse(TWO_CHESTS);
        assertEquals("Not all of it fits in the first chest: the rest goes into the next one.", unseenSecond.fit(28, unseenSecond.layout(units(28)), true, NOW).warning());

        DeliveryModel full = parse("{\"readAt\":1,\"chests\":[{\"n\":0,\"size\":27,\"items\":[{\"slot\":0,\"id\":\"a:b\",\"count\":1}]}]}");
        DeliveryModel.Fit expand = full.fit(30, full.layout(units(30)), true, NOW);
        assertEquals("This order needs 30 slots but only 26 are free: the bot will put up more chests (if it can).", expand.warning());
        DeliveryModel.Fit pause = full.fit(30, full.layout(units(30)), false, NOW);
        assertEquals("This order needs 30 slots but only 26 are free: the order will pause until a chest is emptied.", pause.warning());
    }

    @Test
    void noChestsNoText() {
        DeliveryModel.Fit fit = DeliveryModel.EMPTY.fit(3, DeliveryModel.EMPTY.layout(units(3)), true, NOW);
        assertEquals("", fit.text());
        assertEquals("", fit.warning());
    }

    @Test
    void aFailedDeliveryFetchIsRetriedAfterFiveSecondsDoublingToAMinute() {
        long d = DeliveryModel.retryDelay(0);
        assertEquals(5_000, d);
        d = DeliveryModel.retryDelay(d);
        assertEquals(10_000, d);
        d = DeliveryModel.retryDelay(d);
        assertEquals(20_000, d);
        d = DeliveryModel.retryDelay(d);
        assertEquals(40_000, d);
        d = DeliveryModel.retryDelay(d);
        assertEquals(60_000, d);
        assertEquals(60_000, DeliveryModel.retryDelay(d));
        assertEquals(5_000, DeliveryModel.retryDelay(-1));
    }
}

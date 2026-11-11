package com.porter.qwerte.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Kit orders and restock (KIT-CONTRACT v2, revision 4): the kit block, the plan from the stash, the result, the order's lines and the restock record. */
class KitOrdersTest {
    private static final String T = "t-0000aaaa";

    private static KitTemplate template(String id, String color, int totems, int crystals, String crystalKey) {
        List<KitTemplate.Slot> slots = new ArrayList<>();
        slots.add(new KitTemplate.Slot("minecraft:totem_of_undying", "minecraft:totem_of_undying", totems));
        slots.add(new KitTemplate.Slot(crystalKey, crystalKey, crystals));
        while (slots.size() < 27) slots.add(null);
        return new KitTemplate(id, "Crystal Pack", "pvp", null, color, 0, slots);
    }

    private static KitTemplate template() {
        return template(T, null, 1, 6, "minecraft:end_crystal");
    }

    private static KitOrder.Snapshot snap(KitTemplate t, int count, int ready, List<KitOrder.Take> take) {
        return new KitOrder.Snapshot(1, List.of(new KitOrder.Line(t, count, ready, count - ready)), take, false);
    }

    private static List<KitOrder.Take> take(int totems, int crystals) {
        return List.of(new KitOrder.Take("minecraft:totem_of_undying", totems), new KitOrder.Take("minecraft:end_crystal", crystals));
    }

    private static KitPlan.Scan scan(long totems, long crystals, Map<String, List<KitPlan.Copy>> ready, Map<String, List<KitPlan.Copy>> bases) {
        Map<String, Long> count = new HashMap<>();
        count.put("minecraft:totem_of_undying", totems);
        count.put("minecraft:end_crystal", crystals);
        Map<String, String> match = new HashMap<>();
        match.put("minecraft:totem_of_undying", "minecraft:totem_of_undying");
        match.put("minecraft:end_crystal", "minecraft:end_crystal");
        return new KitPlan.Scan(count, match, new HashMap<>(), new HashSet<>(), new HashSet<>(), ready, bases);
    }

    private static int stack(String id) {
        return id.contains("sword") ? 1 : 64;
    }

    // The kit block

    @Test
    void theKitBlockIsReadAndCheckedAndAnythingElseIsUnreadable() {
        JsonObject slot0 = new JsonObject(), slot1 = new JsonObject();
        slot0.addProperty("key", "minecraft:totem_of_undying");
        slot0.addProperty("match", "minecraft:totem_of_undying");
        slot0.addProperty("count", 1);
        slot1.addProperty("key", "minecraft:end_crystal");
        slot1.addProperty("match", "minecraft:end_crystal");
        slot1.addProperty("count", 6);
        JsonArray slots = new JsonArray();
        slots.add(slot0);
        slots.add(slot1);
        for (int i = 2; i < 27; i++) slots.add(JsonNull.INSTANCE);
        JsonObject t = new JsonObject();
        t.addProperty("id", T);
        t.addProperty("name", "Crystal Pack");
        t.addProperty("category", "pvp");
        t.add("chest", JsonNull.INSTANCE);
        t.add("color", JsonNull.INSTANCE);
        t.addProperty("keep", 0);
        t.add("slots", slots);
        JsonObject line = new JsonObject();
        line.add("template", t);
        line.addProperty("count", 2);
        line.addProperty("ready", 1);
        line.addProperty("build", 1);
        JsonObject take = new JsonObject();
        take.addProperty("match", "minecraft:totem_of_undying");
        take.addProperty("count", 1);
        JsonObject block = new JsonObject();
        block.addProperty("v", 1);
        block.add("lines", new JsonArray());
        block.getAsJsonArray("lines").add(line);
        block.add("take", new JsonArray());
        block.getAsJsonArray("take").add(take);
        KitOrder.Snapshot s = KitOrder.parse(block);
        assertFalse(s.malformed());
        assertEquals(1, s.lines().size());
        assertEquals(1, s.lines().get(0).build());
        assertEquals(2, s.kits());
        assertNull(KitOrder.check(s, KitPlanTest::stackOf));
        // A take entry that is no slot of a template, or a ready + build below the count, is not a kit order's
        take.addProperty("match", "minecraft:stick");
        assertTrue(KitOrder.parse(block).malformed());
        take.addProperty("match", "minecraft:totem_of_undying");
        line.addProperty("build", 0);
        assertTrue(KitOrder.parse(block).malformed());
        assertNull(KitOrder.parse(null));
        assertTrue(KitOrder.parse(new JsonArray()).malformed());
        assertEquals("the kit block is not readable", KitOrder.check(KitOrder.Snapshot.unreadable(), KitPlanTest::stackOf));
        // A slot over the stack size is refused before any click
        KitTemplate big = template(T, null, 5, 6, "minecraft:end_crystal");
        assertEquals("a slot holds more than a stack", KitOrder.check(snap(big, 1, 0, take(5, 6)), id -> 1));
    }

    private static final class KitPlanTest {
        static int stackOf(String id) {
            return 64;
        }
    }

    // The plan

    @Test
    void readyCopiesComeFirstThenBuildsFromAnEmptyShulkerWithTheItemsGathered() {
        KitPlan.Copy copy = new KitPlan.Copy("s:ready", List.of(new KitPlan.Stack("minecraft:totem_of_undying", "minecraft:totem_of_undying", "minecraft:totem_of_undying", 1)));
        KitPlan.Scan scan = scan(10, 100, Map.of(T, List.of(copy)), Map.of());
        KitPlan.Plan plan = KitPlan.plan(snap(template(), 3, 1, take(2, 12)), scan, KitOrdersTest::stack, 5, Set.of(), 54, false);
        assertEquals(1, plan.ready().size());
        assertEquals("s:ready", plan.ready().get(0).group());
        assertEquals(2, plan.builds().size());
        KitPlan.BuildUnit b = plan.builds().get(0);
        assertFalse(b.kitShort());
        assertNull(b.base());
        assertEquals(2, b.lots().size());
        assertEquals(1, b.lots().get(0).count());
        assertEquals(6, b.lots().get(1).count());
        assertTrue(plan.shorts().isEmpty());
        assertEquals("Crystal Pack", b.name());
    }

    @Test
    void theTakeBudgetIsNeverExceeded() {
        // The order may take 1 totem and 6 crystals: the second kit finds its budget gone and has nothing to start from
        KitPlan.Plan plan = KitPlan.plan(snap(template(), 2, 0, take(1, 6)), scan(10, 100, Map.of(), Map.of()), KitOrdersTest::stack, 5, Set.of(), 54, false);
        assertEquals(1, plan.builds().size());
        assertEquals(1, plan.shorts().size());
        assertEquals(KitOrder.ITEMS_SHORT, plan.shorts().get(0).reason());
        assertEquals(KitResult.STORED_RETURNED, plan.shorts().get(0).stored());
    }

    @Test
    void aShortKitOfTwoKindsIsPlannedAndNeverDeliveredAndOneKindIsReturned() {
        // 1 totem and only 2 of the 6 crystals: planned short (two kinds), stored as an incomplete copy
        KitPlan.Plan two = KitPlan.plan(snap(template(), 1, 0, take(1, 6)), scan(5, 2, Map.of(), Map.of()), KitOrdersTest::stack, 5, Set.of(), 54, false);
        assertEquals(1, two.builds().size());
        assertTrue(two.builds().get(0).kitShort());
        assertEquals(4, two.builds().get(0).missing().get(0).count());
        assertEquals("minecraft:end_crystal", two.builds().get(0).missing().get(0).match());
        // no crystals at all: one kind is not a kit, nothing is started, nothing is taken
        KitPlan.Plan one = KitPlan.plan(snap(template(), 1, 0, take(1, 6)), scan(5, 0, Map.of(), Map.of()), KitOrdersTest::stack, 5, Set.of(), 54, false);
        assertTrue(one.builds().isEmpty());
        assertEquals(KitResult.STORED_RETURNED, one.shorts().get(0).stored());
    }

    @Test
    void noEmptyShulkerAndTheCapAreShortWithTheirReasons() {
        KitPlan.Plan none = KitPlan.plan(snap(template(), 2, 0, take(2, 12)), scan(10, 100, Map.of(), Map.of()), KitOrdersTest::stack, 1, Set.of(), 54, false);
        assertEquals(1, none.builds().size());
        assertEquals(KitOrder.NO_SHULKER, none.shorts().get(0).reason());
        assertNull(none.shorts().get(0).stored(), "nothing was started");
        KitPlan.Plan cap = KitPlan.plan(snap(template(), 3, 0, take(3, 18)), scan(10, 100, Map.of(), Map.of()), KitOrdersTest::stack, 9, Set.of(), 1, false);
        assertEquals(1, cap.builds().size());
        assertEquals(KitOrder.CAP, cap.shorts().get(0).reason());
        assertEquals(2, cap.shorts().get(0).kits());
    }

    @Test
    void anIncompleteCopyIsTheBaseAndNeedsNoEmptyShulker() {
        // The copy holds the totem and 2 crystals; the kit needs 6: 4 more are gathered, and no empty shulker is used
        KitPlan.Copy base = new KitPlan.Copy("s:half", List.of(new KitPlan.Stack("minecraft:totem_of_undying", "minecraft:totem_of_undying", "minecraft:totem_of_undying", 1),
            new KitPlan.Stack("minecraft:end_crystal", "minecraft:end_crystal", "minecraft:end_crystal", 2)));
        KitPlan.Plan plan = KitPlan.plan(snap(template(), 1, 0, take(0, 4)), scan(0, 100, Map.of(), Map.of(T, List.of(base))), KitOrdersTest::stack, 0, Set.of(), 54, false);
        assertEquals(1, plan.builds().size());
        KitPlan.BuildUnit b = plan.builds().get(0);
        assertEquals("s:half", b.base());
        assertEquals(2, b.held().size());
        assertEquals(1, b.lots().size());
        assertEquals(4, b.lots().get(0).count());
        assertFalse(b.kitShort());
        // A copy that does not fit the template (a stick in it) is not a base
        KitPlan.Copy odd = new KitPlan.Copy("s:odd", List.of(new KitPlan.Stack("minecraft:stick", "minecraft:stick", "minecraft:stick", 1)));
        assertNull(KitPlan.fitBase(template(), odd));
    }

    @Test
    void anyVariantSlotsTakePlainFirstAndRestockNeverTakesEnchantedOnes() {
        KitTemplate t = template(T, null, 1, 1, "minecraft:diamond_sword#*");
        Map<String, Long> count = new HashMap<>();
        count.put("minecraft:totem_of_undying", 5L);
        count.put("minecraft:diamond_sword", 3L);
        count.put("minecraft:diamond_sword#aaaaaaaa", 3L);
        Map<String, String> match = new HashMap<>();
        match.put("minecraft:totem_of_undying", "minecraft:totem_of_undying");
        match.put("minecraft:diamond_sword", "minecraft:diamond_sword");
        match.put("minecraft:diamond_sword#aaaaaaaa", "minecraft:diamond_sword#aaaaaaaa");
        KitPlan.Scan sc = new KitPlan.Scan(count, match, new HashMap<>(), new HashSet<>(), Set.of("minecraft:diamond_sword#aaaaaaaa"), Map.of(), Map.of());
        KitTemplate.Slot any = t.slots().get(1);
        assertEquals(List.of("minecraft:diamond_sword", "minecraft:diamond_sword#aaaaaaaa"), KitPlan.candidates(any, sc, false));
        assertEquals(List.of("minecraft:diamond_sword"), KitPlan.candidates(any, sc, true));
    }

    // The result

    @Test
    void theResultHasWhatWasMadeWhatIsShortAndTheNotes() {
        KitResult r = new KitResult();
        r.line(T, "Crystal Pack", 3);
        r.ready(T);
        r.built(T);
        r.delivered(T);
        r.delivered(T);
        r.addShort(T, 1, List.of(new KitResult.Miss("minecraft:end_crystal", "minecraft:end_crystal", 4)), KitOrder.ITEMS_SHORT, KitResult.STORED_INCOMPLETE);
        r.note(KitResult.COLOUR_SUBSTITUTED);
        r.note(KitResult.COLOUR_SUBSTITUTED);
        JsonObject o = r.toJson();
        JsonObject made = o.getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals(3, made.get("wanted").getAsInt());
        assertEquals(1, made.get("ready").getAsInt());
        assertEquals(1, made.get("built").getAsInt());
        assertEquals(2, made.get("delivered").getAsInt());
        JsonObject sh = o.getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals("incomplete", sh.get("stored").getAsString());
        assertEquals(4, sh.getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(1, o.getAsJsonArray("notes").size(), "a note once");
        assertEquals("1 kit not made: items_short", r.note());
        assertTrue(new KitResult().note() == null && !new KitResult().any());
    }

    // The order's lines

    @Test
    void anOrderWithKitLinesKeepsThemAndTheirBlockAndNeverCountsThemAsItems() {
        JsonObject o = JsonParser.parseString("{\"id\":\"o-1\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:stick\",\"count\":4},{\"type\":\"kit\",\"key\":\"" + T + "\",\"count\":2}]}").getAsJsonObject();
        Order order = Order.parse(o);
        assertNotNull(order);
        assertTrue(order.hasKit());
        // Kit lines without a readable kit block: every kit is short, the order goes on
        assertTrue(order.kit.malformed());
        assertEquals(Map.of("minecraft:stick", 4), order.requested(), "a template id is not an item key");
        Order plain = Order.parse(JsonParser.parseString("{\"id\":\"o-2\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:stick\",\"count\":4}]}").getAsJsonObject());
        assertFalse(plain.hasKit());
        assertNull(plain.kit);
    }

    // The restock record

    @Test
    void theRestockStampsCountTheLastHourSurviveARestartAndForgetTheOld(@TempDir Path dir) {
        Path file = dir.resolve("kit-restock.json");
        KitRestockStamps stamps = new KitRestockStamps(file);
        long now = System.currentTimeMillis();
        assertEquals(0, stamps.lastHour(now));
        stamps.add(now - 2 * KitRestockStamps.HOUR_MS);
        stamps.add(now - 1000);
        stamps.add(now);
        assertEquals(2, stamps.lastHour(now), "the stamp of two hours ago is gone");
        assertEquals(2, new KitRestockStamps(file).lastHour(now), "kept across a restart");
        // A damaged file is an empty record; a stamp far in the future is dropped
        try {
            Files.writeString(file, "not json");
            assertEquals(0, new KitRestockStamps(file).lastHour(now));
            Files.writeString(file, "[" + (now + 10 * KitRestockStamps.HOUR_MS) + "," + (now - 5) + "]");
            assertEquals(1, new KitRestockStamps(file).lastHour(now));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void restockTakesWhatOneCopyNeedsAndNeverTheBottleReserve() {
        List<KitTemplate.Slot> slots = new ArrayList<>();
        slots.add(new KitTemplate.Slot("minecraft:experience_bottle", "minecraft:experience_bottle", 64));
        slots.add(new KitTemplate.Slot("minecraft:totem_of_undying", "minecraft:totem_of_undying", 1));
        slots.add(new KitTemplate.Slot("minecraft:totem_of_undying", "minecraft:totem_of_undying", 2));
        while (slots.size() < 27) slots.add(null);
        KitTemplate t = new KitTemplate(T, "Mender", "pvp", null, null, 2, slots);
        List<KitOrder.Take> all = KitRestock.takeFor(t, 10_000);
        assertEquals(64, all.stream().filter(x -> x.match().equals("minecraft:experience_bottle")).findFirst().get().count());
        assertEquals(3, all.stream().filter(x -> x.match().equals("minecraft:totem_of_undying")).findFirst().get().count());
        // Fewer unreserved bottles than the copy needs: the take is cut, and none left gives none
        assertEquals(10, KitRestock.takeFor(t, 10).stream().filter(x -> x.match().equals("minecraft:experience_bottle")).findFirst().get().count());
        assertTrue(KitRestock.takeFor(t, 0).stream().noneMatch(x -> x.match().equals("minecraft:experience_bottle")));
        assertEquals(0, KitRestock.bottlesUsable(1000));
        assertEquals(272, KitRestock.bottlesUsable(2000));
        // At most 16 templates with a keep count, by id
        List<KitTemplate> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) many.add(new KitTemplate(String.format("t-%08d", i), "K" + i, "pvp", null, null, i == 3 ? 0 : 1, t.slots()));
        List<KitTemplate> picked = KitRestock.candidates(many);
        assertEquals(16, picked.size());
        assertTrue(picked.stream().noneMatch(x -> x.keep() == 0));
        assertEquals("t-00000000", picked.get(0).id());
        // The job's start and stop rules
        assertFalse(KitRestock.mayStart(true, false, true, true, false));
        assertFalse(KitRestock.mayStart(false, false, false, true, false), "not before the first heartbeat reply");
        assertTrue(KitRestock.mayStart(false, false, true, true, false));
        assertTrue(KitRestock.mustStop(true, true, true, true, false));
        assertFalse(KitRestock.mustStop(false, true, true, true, false));
    }

    // Review fixes: identical copies, the cap, the craft capability

    private static KitPlan.Copy completeCopy() {
        return new KitPlan.Copy("s:same", List.of(new KitPlan.Stack("minecraft:totem_of_undying", "minecraft:totem_of_undying", "minecraft:totem_of_undying", 1),
            new KitPlan.Stack("minecraft:end_crystal", "minecraft:end_crystal", "minecraft:end_crystal", 6)));
    }

    @Test
    void threeIdenticalReadyCopiesFillAThreeKitLine() {
        // Identical complete copies share one group key: each is its own kit
        KitPlan.Scan sc = scan(0, 0, Map.of(T, List.of(completeCopy(), completeCopy(), completeCopy())), Map.of());
        KitPlan.Plan plan = KitPlan.plan(snap(template(), 3, 3, take(0, 0)), sc, KitOrdersTest::stack, 0, Set.of(), 54, false);
        assertEquals(3, plan.ready().size());
        assertTrue(plan.builds().isEmpty());
        assertTrue(plan.shorts().isEmpty());
        // Two copies for a line that wants 3 ready: the third is a build
        KitPlan.Plan two = KitPlan.plan(snap(template(), 3, 3, take(1, 6)), scan(5, 50, Map.of(T, List.of(completeCopy(), completeCopy())), Map.of()), KitOrdersTest::stack, 3, Set.of(), 54, false);
        assertEquals(2, two.ready().size());
        assertEquals(1, two.builds().size());
    }

    @Test
    void twoIdenticalIncompleteCopiesAreTwoBases() {
        KitPlan.Copy half1 = new KitPlan.Copy("s:half", List.of(new KitPlan.Stack("minecraft:totem_of_undying", "minecraft:totem_of_undying", "minecraft:totem_of_undying", 1),
            new KitPlan.Stack("minecraft:end_crystal", "minecraft:end_crystal", "minecraft:end_crystal", 2)));
        KitPlan.Copy half2 = new KitPlan.Copy("s:half", half1.stacks());
        KitPlan.Plan plan = KitPlan.plan(snap(template(), 2, 0, take(0, 8)), scan(0, 100, Map.of(), Map.of(T, List.of(half1, half2))), KitOrdersTest::stack, 0, Set.of(), 54, false);
        assertEquals(2, plan.builds().size());
        assertEquals("s:half", plan.builds().get(0).base());
        assertEquals("s:half", plan.builds().get(1).base(), "the second kit has its own copy to build on, no empty shulker is used");
        assertTrue(plan.shorts().isEmpty());
    }

    @Test
    void readyAndBuildAreNeverMoreThanTheKitsAskedAndTheCapHoldsForReadyUnits() {
        String block = "{\"v\":1,\"lines\":[{\"template\":" + templateJson() + ",\"count\":2,\"ready\":9,\"build\":0}],\"take\":[]}";
        assertTrue(KitOrder.parse(JsonParser.parseString(block)).malformed(), "ready above count");
        String block2 = "{\"v\":1,\"lines\":[{\"template\":" + templateJson() + ",\"count\":2,\"ready\":0,\"build\":9}],\"take\":[]}";
        assertTrue(KitOrder.parse(JsonParser.parseString(block2)).malformed(), "build above count");
        // The plan takes no more ready copies than the line asks for, nor more units than the cap
        KitPlan.Scan sc = scan(0, 0, Map.of(T, List.of(completeCopy(), completeCopy(), completeCopy(), completeCopy())), Map.of());
        KitOrder.Snapshot lying = new KitOrder.Snapshot(1, List.of(new KitOrder.Line(template(), 2, 50, 0)), take(0, 0), false);
        assertEquals(2, KitPlan.plan(lying, sc, KitOrdersTest::stack, 0, Set.of(), 54, false).ready().size());
        KitPlan.Plan capped = KitPlan.plan(snap(template(), 4, 4, take(0, 0)), sc, KitOrdersTest::stack, 0, Set.of(), 2, false);
        assertEquals(2, capped.ready().size());
        assertEquals(KitOrder.CAP, capped.shorts().get(0).reason());
        assertEquals(2, capped.shorts().get(0).kits());
    }

    private static String templateJson() {
        return "{\"id\":\"" + T + "\",\"name\":\"Crystal Pack\",\"category\":\"pvp\",\"chest\":null,\"color\":null,\"keep\":0,\"slots\":["
            + "{\"key\":\"minecraft:totem_of_undying\",\"match\":\"minecraft:totem_of_undying\",\"count\":1},{\"key\":\"minecraft:end_crystal\",\"match\":\"minecraft:end_crystal\",\"count\":6},"
            + "null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null]}";
    }

    @Test
    void craftIsNeverAdvertisedForKitsUntilCraftedLoadsFeedThem() {
        StashIndex.KitOrdersCap on = StashIndex.KitOrdersCap.of(true, true);
        assertTrue(on.enabled() && on.restock() && on.importer());
        assertFalse(on.craft(), "a kit shortfall is not crafted: the server must not plan craft steps for a kit");
        assertFalse(StashIndex.KitOrdersCap.of(false, true).restock(), "restock needs kit orders");
        assertFalse(StashIndex.KitOrdersCap.of(true, false).restock());
        assertFalse(StashIndex.KitOrdersCap.of(false, false).enabled());
    }
}

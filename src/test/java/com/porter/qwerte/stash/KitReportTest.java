package com.porter.qwerte.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.stash.KitReport.BoxInfo;
import com.porter.qwerte.stash.KitReport.Shulker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kit report: the shape the server checks, grouping, scores, the hash and the limits. */
class KitReportTest {
    private static final KitScore.Result TEN = new KitScore.Result(2, 3, 1, 4, 10, true);
    private static final KitScore.Result EIGHT = new KitScore.Result(0, 3, 1, 4, 8, true);
    private static final KitScore.Result FIVE = new KitScore.Result(0, 0, 1, 4, 5, true);
    private static final KitScore.Result NO = new KitScore.Result(0, 0, 0, 1, 1, false);

    private static Shulker kit(String name, KitScore.Result r) {
        return new Shulker(name, r);
    }

    private static BoxInfo box(String id, int free, boolean pickable, long readAt, Shulker... shulkers) {
        return new BoxInfo(id, null, 27, free, pickable, readAt, List.of(shulkers));
    }

    private static JsonObject json(KitReport.Built b) {
        return JsonParser.parseString(new String(b.json(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static KitReport.Built build(List<BoxInfo> boxes, List<Shulker> carried) {
        return KitReport.build("2b2t.org", "stash", "Qwerttze", boxes, carried);
    }

    @Test
    void theShapeFollowsTheContract() {
        BoxInfo b = new BoxInfo("118,64,-33", "PvP", 54, 3, true, 1000, List.of(kit("PvP Kit", TEN), kit("pvp kit", EIGHT), kit("PvP Kit", TEN)));
        KitReport.Built built = build(List.of(b, box("120,64,-33", 27, true, 0)), List.of(kit("PvP Kit", TEN)));
        JsonObject o = json(built);
        assertEquals(1, o.get("version").getAsInt());
        assertEquals("2b2t.org", o.get("server").getAsString());
        assertEquals("Qwerttze", o.get("player").getAsString());
        assertEquals(built.hash(), o.get("hash").getAsString());
        assertTrue(built.hash().matches("[0-9a-f]{40}"));
        assertEquals(1000, o.get("readAt").getAsLong());
        JsonArray containers = o.getAsJsonArray("containers");
        assertEquals(2, containers.size());
        JsonObject c = containers.get(0).getAsJsonObject();
        assertEquals("118,64,-33", c.get("id").getAsString());
        assertEquals("PvP", c.get("label").getAsString());
        assertEquals(54, c.get("size").getAsInt());
        assertEquals(3, c.get("free").getAsInt());
        assertTrue(c.get("pickable").getAsBoolean());

        JsonArray kits = o.getAsJsonArray("kits");
        assertEquals(1, kits.size(), "one entry per (key, container): the names differ only in case");
        JsonObject k = kits.get(0).getAsJsonObject();
        assertEquals("pvp kit", k.get("key").getAsString());
        assertEquals("PvP Kit", k.get("name").getAsString(), "the smallest original name (capitals sort first)");
        assertEquals(3, k.get("count").getAsInt());
        assertEquals(10, k.getAsJsonObject("score").get("total").getAsInt(), "the breakdown of the highest total");
        assertEquals(2, k.getAsJsonObject("score").get("name").getAsInt());
        assertEquals(8, k.get("minTotal").getAsInt(), "the lowest total");

        JsonObject carried = o.getAsJsonArray("carried").get(0).getAsJsonObject();
        assertEquals("pvp kit", carried.get("key").getAsString());
        assertEquals(1, carried.get("count").getAsInt());
        assertEquals(10, carried.getAsJsonObject("score").get("total").getAsInt());
    }

    @Test
    void unnamedKitsAreGroupedUnderANullKey() {
        BoxInfo b = box("1,64,1", 20, true, 5, kit(null, EIGHT), kit(null, FIVE), kit("Base Kit", TEN));
        JsonArray kits = json(build(List.of(b), List.of(kit(null, FIVE), kit(null, EIGHT)))).getAsJsonArray("kits");
        assertEquals(2, kits.size());
        JsonObject unnamed = kits.get(0).getAsJsonObject();
        assertTrue(unnamed.get("key").isJsonNull(), "unnamed first");
        assertTrue(unnamed.get("name").isJsonNull());
        assertEquals(2, unnamed.get("count").getAsInt());
        assertEquals(8, unnamed.getAsJsonObject("score").get("total").getAsInt());
        assertEquals(5, unnamed.get("minTotal").getAsInt());
        assertEquals("base kit", kits.get(1).getAsJsonObject().get("key").getAsString());

        JsonArray carried = json(build(List.of(b), List.of(kit(null, FIVE), kit(null, EIGHT)))).getAsJsonArray("carried");
        assertEquals(1, carried.size(), "null allowed once");
        assertTrue(carried.get(0).getAsJsonObject().get("key").isJsonNull());
        assertEquals(2, carried.get(0).getAsJsonObject().get("count").getAsInt());
        assertFalse(carried.get(0).getAsJsonObject().has("minTotal"));
    }

    @Test
    void onlyBoxesThatScoreAsKitsAreListed() {
        BoxInfo b = box("1,64,1", 20, true, 5, kit("Not A", NO), kit("A Kit", TEN));
        KitReport.Built built = build(List.of(b), List.of(kit("x", NO)));
        assertEquals(1, built.kits());
        assertEquals(0, built.carried());
    }

    @Test
    void theNameIsCleanedAndCutAndKeysAreNormal() {
        String long80 = "kit " + "a".repeat(100);
        BoxInfo b = box("1,64,1", 20, true, 5, kit("§6PvP\tKit" + (char) 0x200B, TEN), kit(long80, TEN));
        JsonArray kits = json(build(List.of(b), List.of())).getAsJsonArray("kits");
        JsonObject first = kits.get(0).getAsJsonObject();
        assertEquals("kit " + "a".repeat(60), first.get("key").getAsString());
        assertEquals(64, first.get("name").getAsString().length());
        assertTrue(first.get("name").getAsString().endsWith("…"));
        assertTrue(KitNames.validKey(first.get("key").getAsString()));
        JsonObject second = kits.get(1).getAsJsonObject();
        assertEquals("pvp kit", second.get("key").getAsString());
    }

    @Test
    void labelsAreCleanedAndCutAndBlankIsNull() {
        BoxInfo a = new BoxInfo("1,64,1", "  ", 27, 0, true, 0, List.of());
        BoxInfo b = new BoxInfo("2,64,2", "x".repeat(50) + (char) 7, 27, 0, true, 0, List.of());
        JsonArray containers = json(build(List.of(a, b), List.of())).getAsJsonArray("containers");
        assertTrue(containers.get(0).getAsJsonObject().get("label").isJsonNull());
        assertEquals(32, containers.get(1).getAsJsonObject().get("label").getAsString().length());
    }

    @Test
    void containersAreUniqueAndOnlyValidIdsStay() {
        List<BoxInfo> boxes = List.of(box("1,64,1", 1, true, 0), box("1,64,1", 2, true, 0), box("nonsense", 3, true, 0), box("2,64,2", 4, false, 0));
        KitReport.Built built = build(boxes, List.of());
        assertEquals(2, built.containers());
        assertEquals(1, json(built).getAsJsonArray("containers").get(0).getAsJsonObject().get("free").getAsInt(), "the first one counts");
        assertFalse(json(built).getAsJsonArray("containers").get(1).getAsJsonObject().get("pickable").getAsBoolean());
    }

    @Test
    void freeIsWithinTheSizeAndCountsAreCapped() {
        List<Shulker> many = new ArrayList<>();
        for (int i = 0; i < 70; i++) many.add(kit("Big Kit", TEN));
        BoxInfo b = new BoxInfo("1,64,1", null, 27, 99, true, 0, many);
        JsonObject o = json(build(List.of(b), many));
        assertEquals(27, o.getAsJsonArray("containers").get(0).getAsJsonObject().get("free").getAsInt());
        assertEquals(54, o.getAsJsonArray("kits").get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(36, o.getAsJsonArray("carried").get(0).getAsJsonObject().get("count").getAsInt());
    }

    @Test
    void theHashIgnoresReadTimesButNotContent() {
        KitReport.Built a = build(List.of(box("1,64,1", 5, true, 100, kit("A Kit", TEN))), List.of());
        KitReport.Built sameLaterRead = build(List.of(box("1,64,1", 5, true, 999, kit("A Kit", TEN))), List.of());
        assertEquals(a.hash(), sameLaterRead.hash());
        assertNotEquals(a.json().length == 0, true);
        assertNotEquals(a.hash(), build(List.of(box("1,64,1", 4, true, 100, kit("A Kit", TEN))), List.of()).hash(), "room changed");
        assertNotEquals(a.hash(), build(List.of(box("1,64,1", 5, true, 100, kit("A Kit", EIGHT))), List.of()).hash(), "score changed");
        assertNotEquals(a.hash(), build(List.of(box("1,64,1", 5, true, 100, kit("A Kit", TEN))), List.of(kit("A Kit", TEN))).hash(), "a carried kit");
    }

    @Test
    void overTheLimitsTheContainersThatHoldKitsStay() {
        List<BoxInfo> boxes = new ArrayList<>();
        for (int i = 0; i < 5000; i++) boxes.add(box(i + ",64,0", 1, true, 1));
        boxes.add(box("9999,64,0", 0, false, 1, kit("Last Kit", TEN)));
        KitReport.Built built = build(boxes, List.of());
        assertEquals(KitReport.MAX_CONTAINERS, built.containers());
        assertEquals(1, built.kits(), "the container with the kit was kept although it sorts last");
        assertTrue(built.json().length <= KitReport.MAX_BYTES);
    }

    @Test
    void anUnnamedKitAndAKitNamedNullStaySeparateInTheSameContainer() {
        BoxInfo b = box("1,64,1", 20, true, 5, kit(null, EIGHT), kit("null", TEN), kit(null, FIVE));
        KitReport.Built built = build(List.of(b), List.of(kit(null, EIGHT), kit("null", TEN)));
        JsonArray kits = json(built).getAsJsonArray("kits");
        assertEquals(2, kits.size());
        assertTrue(kits.get(0).getAsJsonObject().get("key").isJsonNull());
        assertEquals(2, kits.get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals("null", kits.get(1).getAsJsonObject().get("key").getAsString());
        assertEquals(1, kits.get(1).getAsJsonObject().get("count").getAsInt());
        JsonArray carried = json(built).getAsJsonArray("carried");
        assertEquals(2, carried.size());
        assertTrue(carried.get(0).getAsJsonObject().get("key").isJsonNull());
        assertEquals("null", carried.get(1).getAsJsonObject().get("key").getAsString());
    }

    @Test
    void theBetterBreakdownWinsTiesByNameThenMixThenFullThenOp() {
        KitScore.Result a = new KitScore.Result(2, 0, 1, 4, 7, true), b = new KitScore.Result(0, 3, 0, 4, 7, true);
        assertTrue(KitReport.higher(a, b), "same total: the higher name");
        assertFalse(KitReport.higher(b, a));
        KitScore.Result c = new KitScore.Result(0, 3, 1, 3, 7, true), d = new KitScore.Result(0, 2, 1, 4, 7, true);
        assertTrue(KitReport.higher(c, d), "then mix");
        KitScore.Result e = new KitScore.Result(0, 2, 1, 4, 7, true), f = new KitScore.Result(0, 2, 0, 5, 7, true);
        assertTrue(KitReport.higher(e, f), "then full");
        KitScore.Result g = new KitScore.Result(0, 2, 1, 4, 7, true), h = new KitScore.Result(0, 2, 1, 3, 7, true);
        assertTrue(KitReport.higher(g, h), "then op");
        // whichever order the boxes are met in, the entry shows the same breakdown
        JsonObject one = json(build(List.of(box("1,64,1", 5, true, 0, kit("a kit", a), kit("a kit", b))), List.of())).getAsJsonArray("kits").get(0).getAsJsonObject();
        JsonObject two = json(build(List.of(box("1,64,1", 5, true, 0, kit("a kit", b), kit("a kit", a))), List.of())).getAsJsonArray("kits").get(0).getAsJsonObject();
        assertEquals(one.getAsJsonObject("score").toString(), two.getAsJsonObject("score").toString());
        assertEquals(2, one.getAsJsonObject("score").get("name").getAsInt());
    }

    @Test
    void aNameWithAnUnpairedSurrogateIsReportedAsUnnamed() {
        BoxInfo b = box("1,64,1", 5, true, 0, kit("kit " + (char) 0xD83D, TEN));
        JsonObject k = json(build(List.of(b), List.of(kit("kit " + (char) 0xD83D, TEN)))).getAsJsonArray("kits").get(0).getAsJsonObject();
        assertTrue(k.get("key").isJsonNull());
        assertTrue(k.get("name").isJsonNull());
        assertTrue(json(build(List.of(b), List.of())).getAsJsonArray("carried").isEmpty());
    }
}

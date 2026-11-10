package com.porter.qwerte.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.stash.KitRouting.Kind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KIT-CONTRACT v2 in the addon: the chain (rule, template chest, category chest, kits chest), kit-reserved chests, the v2 report and the heartbeat's kitsHash2. */
class Kit2RoutingTest {
    private static final String HASH = "0".repeat(40);

    private static final class FakeBox {
        final KitSettings.Pos pos;
        boolean pickable = true;
        boolean full;

        FakeBox(int x, int y, int z) {
            this.pos = new KitSettings.Pos(x, y, z);
        }
    }

    private static final class Stash implements KitRouting.Resolver<FakeBox> {
        final List<FakeBox> boxes = new ArrayList<>();

        FakeBox add(int x, int y, int z) {
            FakeBox b = new FakeBox(x, y, z);
            boxes.add(b);
            return b;
        }

        @Override
        public FakeBox boxAt(KitSettings.Pos p) {
            for (FakeBox b : boxes) if (b.pos.equals(p)) return b;
            return null;
        }

        @Override
        public boolean pickable(FakeBox box) {
            return box.pickable;
        }

        @Override
        public boolean usable(FakeBox box) {
            return box.pickable && !box.full;
        }
    }

    private static KitTemplate template(String id, String name, String chest) {
        List<KitTemplate.Slot> slots = new ArrayList<>();
        slots.add(new KitTemplate.Slot("minecraft:totem_of_undying", "minecraft:totem_of_undying", 1));
        slots.add(new KitTemplate.Slot("minecraft:end_crystal", "minecraft:end_crystal", 2));
        while (slots.size() < 27) slots.add(null);
        return new KitTemplate(id, name, "pvp", chest, null, 0, slots);
    }

    private static KitSettings settings(String kitsChest, Map<String, String> rules, Map<String, String> chests, List<KitTemplate> templates) {
        return new KitSettings(1, HASH, kitsChest, rules, KitSettings.Scoring.DEFAULT, new KitSettings.Types(HASH, List.of(), chests, Map.of(), templates));
    }

    private static KitClassifier.Result pvp() {
        return new KitClassifier.Result(true, KitClassifier.ASSORTMENT, "pvp", 22, 22, 3, 0, 0, List.of(), List.of(), null, List.of());
    }

    private static KitClassifier.Result copy(String template) {
        return new KitClassifier.Result(true, KitClassifier.TEMPLATE, "pvp", 3, 0, 0, 0, 0, List.of(), List.of(), template, List.of());
    }

    // The chain

    @Test
    void theChainIsRuleThenTemplateChestThenCategoryChestThenKitsChestWithoutRepeats() {
        KitTemplate t = template("t-0000aaaa", "Crystal Pack", "3,64,3");
        KitSettings s = settings("4,64,4", Map.of("crystal pack", "1,64,1"), Map.of("pvp", "2,64,2"), List.of(t));
        KitClassifier.Result why = copy("t-0000aaaa");
        assertEquals(List.of("rule", "template", "category", "kitsChest"), KitRouting.chain("crystal pack", why, s).stream().map(KitRouting.Level::source).toList());
        assertEquals(List.of("1,64,1", "3,64,3", "2,64,2", "4,64,4"), KitRouting.chain("crystal pack", why, s).stream().map(KitRouting.Level::container).toList());
        // A container that comes twice is listed at its first level
        KitSettings twice = settings("1,64,1", Map.of("crystal pack", "1,64,1"), Map.of(), List.of());
        assertEquals(1, KitRouting.chain("crystal pack", why, twice).size());
        // Nothing set: today's sorting
        assertTrue(KitRouting.chain("x", why, settings(null, Map.of(), Map.of(), List.of())).isEmpty());
        // An unnamed kit has no rule; a kit of another type has no chest of its own
        assertEquals(List.of("category", "kitsChest"), KitRouting.chain(null, pvp(), s).stream().map(KitRouting.Level::source).toList());
        // Settings without a v2 part (an older server): the rule, then the kits chest
        assertEquals(List.of("kitsChest"), KitRouting.chain(null, pvp(), new KitSettings(1, HASH, "4,64,4", Map.of(), KitSettings.Scoring.DEFAULT)).stream().map(KitRouting.Level::source).toList());
        // The first level is where the copies of a template are kept
        assertEquals("1,64,1", KitRouting.templateTarget(t, s));
        assertEquals("3,64,3", KitRouting.templateTarget(t, settings("4,64,4", Map.of(), Map.of("pvp", "2,64,2"), List.of(t))));
    }

    @Test
    void aKitGoesToTheFirstUsableLevelAndAStoredKitOnlyMovesUp() {
        List<KitRouting.Level> chain = List.of(new KitRouting.Level("template", "a"), new KitRouting.Level("category", "b"), new KitRouting.Level("kitsChest", "c"));
        Set<String> all = Set.of("a", "b", "c");
        assertEquals(new KitRouting.Pick(Kind.TEMPLATE, 0, false), KitRouting.pick(chain, all::contains, all::contains, -1));
        assertEquals(new KitRouting.Pick(Kind.CATEGORY, 1, false), KitRouting.pick(chain, all::contains, Set.of("b", "c")::contains, -1));
        // Stored in the category chest, the template chest has room: up
        assertEquals(new KitRouting.Pick(Kind.TEMPLATE, 0, false), KitRouting.pick(chain, all::contains, all::contains, 1));
        // Stored in the category chest, the template chest is full: home, stays
        assertEquals(new KitRouting.Pick(Kind.CATEGORY, 1, true), KitRouting.pick(chain, all::contains, Set.of("b", "c")::contains, 1));
        // Never down: stored at the top, it stays even when the top is full
        assertEquals(new KitRouting.Pick(Kind.TEMPLATE, 0, true), KitRouting.pick(chain, all::contains, Set.of("b", "c")::contains, 0));
        // Everything full, but the chests exist: FULL (no warning); none exists: MISSING
        assertEquals(Kind.FULL, KitRouting.pick(chain, all::contains, s -> false, -1).kind());
        assertEquals(Kind.MISSING, KitRouting.pick(chain, s -> false, s -> false, -1).kind());
        assertEquals(Kind.NONE, KitRouting.pick(List.of(), s -> true, s -> true, -1).kind());
    }

    @Test
    void theViewRoutesByCategoryAndTemplateAndFallsThroughAFullChest() {
        Stash stash = new Stash();
        FakeBox general = stash.add(1, 64, 1), category = stash.add(2, 64, 2), tpl = stash.add(3, 64, 3);
        KitTemplate t = template("t-0000aaaa", "Crystal Pack", "3,64,3");
        KitSettings s = settings("1,64,1", Map.of(), Map.of("pvp", "2,64,2"), List.of(t));
        KitRouting.View<FakeBox> v = new KitRouting.View<>(stash, s);
        KitRouting.Route<FakeBox> r = v.route(null, pvp(), null);
        assertEquals(Kind.CATEGORY, r.kind());
        assertEquals(List.of(category), v.destinations(r));
        // A complete copy of the template goes to the template chest
        KitRouting.Route<FakeBox> c = v.route("crystal pack", copy("t-0000aaaa"), null);
        assertEquals(Kind.TEMPLATE, c.kind());
        assertSame(tpl, c.box());
        assertFalse(c.fallback());
        // Not a kit: nothing (today's sorting)
        assertEquals(Kind.NOT_KIT, v.route(null, new KitClassifier.Result(false, KitClassifier.STOCK, "pvp", 27, 27, 1, 0, 0, List.of(), List.of(), null, List.of()), null).kind());

        // The category chest is full: the next level, and it is a fallback
        category.full = true;
        KitRouting.Route<FakeBox> f = v.route(null, pvp(), null);
        assertEquals(Kind.KITS_CHEST, f.kind());
        assertSame(general, f.box());
        assertTrue(f.fallback());
        // A stored kit in the kits chest whose category chest is full stays (it is never moved down), and when the category chest has room it moves up
        assertTrue(v.route(null, pvp(), general).fallback() == false || v.route(null, pvp(), general).box() == general);
        category.full = false;
        assertSame(category, v.route(null, pvp(), general).box());
        // Every level full: FULL, no destination, and not MISSING
        category.full = general.full = true;
        KitRouting.Route<FakeBox> all = v.route(null, pvp(), null);
        assertEquals(Kind.FULL, all.kind());
        assertTrue(v.destinations(all).isEmpty());
        // Chests that can't take kits (not read, a drop-off): MISSING
        general.pickable = category.pickable = false;
        assertEquals(Kind.MISSING, v.route(null, pvp(), null).kind());
        // Chests that are not in the stash at all: MISSING
        assertEquals(Kind.MISSING, new KitRouting.View<>(new Stash(), s).route(null, pvp(), null).kind());
    }

    @Test
    void everyChestOfTheChainIsKitReserved() {
        Stash stash = new Stash();
        FakeBox general = stash.add(1, 64, 1), category = stash.add(2, 64, 2), tpl = stash.add(3, 64, 3), other = stash.add(9, 64, 9);
        KitSettings s = settings("1,64,1", Map.of(), Map.of("pvp", "2,64,2"), List.of(template("t-0000aaaa", "Crystal Pack", "3,64,3")));
        KitRouting.View<FakeBox> v = new KitRouting.View<>(stash, s);
        assertTrue(v.reserved(general));
        assertTrue(v.reserved(category));
        assertTrue(v.reserved(tpl));
        assertFalse(v.reserved(other));
        assertEquals(List.of(category, general), v.chainBoxes(null, pvp()));
    }

    // The report

    private static KitScore.Result score() {
        return new KitScore.Result(5, 2, 3, 0, 0, true);
    }

    private static JsonObject json(KitReport.Built b) {
        return JsonParser.parseString(new String(b.json(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void theV2ReportHasTheClassifierTypesOthersTemplatesAndRestock() {
        KitTemplate t = template("t-0000aaaa", "Crystal Pack", "2,64,2");
        KitSettings s = settings(null, Map.of(), Map.of("pvp", "2,64,2"), List.of(t));
        KitClassifier.Result incompleteWhy = new KitClassifier.Result(true, KitClassifier.TEMPLATE_INCOMPLETE, "pvp", 1, 0, 0, 0, 0, List.of(), List.of(), "t-0000aaaa",
            List.of(new KitTemplates.Missing("minecraft:end_crystal", "minecraft:end_crystal", 2)));
        KitTemplates.Found found = new KitTemplates.Found(t, new KitTemplates.Match(KitTemplates.State.INCOMPLETE, incompleteWhy.missing()));
        KitClassifier.Result stockWhy = new KitClassifier.Result(false, KitClassifier.STOCK_NEUTRAL, null, 27, 0, 0, 0, 0, List.of("minecraft:cobblestone"), List.of(), null, List.of());
        List<KitReport.Shulker> shulkers = List.of(
            new KitReport.Shulker(null, score(), pvp(), null), new KitReport.Shulker(null, score(), pvp(), null),
            new KitReport.Shulker("Crystal Pack", score(), incompleteWhy, found),
            new KitReport.Shulker("Just stock", score(), stockWhy, null));
        KitReport.BoxInfo box = new KitReport.BoxInfo("2,64,2", "Kits", 27, 20, true, 1000, shulkers);
        KitReport.Built built = KitReport.buildV2("srv", "stash", "Bot", List.of(box), List.of(), KitTypes.FINGERPRINT_V1, s);
        JsonObject root = json(built);
        assertEquals(KitTypes.FINGERPRINT_V1, root.getAsJsonObject("classifier").get("table").getAsString());
        assertEquals(2, root.getAsJsonObject("classifier").get("version").getAsInt());
        assertEquals("off", root.getAsJsonObject("restock").get("state").getAsString());

        // Named non-kits are listed with the reason
        JsonArray others = root.getAsJsonArray("others");
        assertEquals(1, others.size());
        assertEquals("just stock", others.get(0).getAsJsonObject().get("key").getAsString());
        assertEquals("stock_neutral", others.get(0).getAsJsonObject().getAsJsonObject("why").get("code").getAsString());

        // The incomplete copy is counted in the template's chain target (its category chest) with what is missing
        JsonObject tr = root.getAsJsonArray("templates").get(0).getAsJsonObject();
        assertEquals("t-0000aaaa", tr.get("id").getAsString());
        assertEquals(0, tr.get("complete").getAsInt());
        assertEquals(1, tr.get("incomplete").getAsInt());
        assertEquals("minecraft:end_crystal", tr.getAsJsonArray("missing").get(0).getAsJsonObject().get("key").getAsString());

        // Every kit entry lists its types, one per (category, template), the counts adding up to the entry's
        int pvp = 0;
        for (JsonElement ke : root.getAsJsonArray("kits")) {
            JsonObject k = ke.getAsJsonObject();
            int sum = 0;
            for (JsonElement te : k.getAsJsonArray("types")) sum += te.getAsJsonObject().get("count").getAsInt();
            assertEquals(k.get("count").getAsInt(), sum);
            for (JsonElement te : k.getAsJsonArray("types")) if (te.getAsJsonObject().get("type").getAsString().equals("pvp")) pvp += te.getAsJsonObject().get("count").getAsInt();
        }
        assertEquals(3, pvp);
        assertTrue(built.hash().matches("[0-9a-f]{40}"));
    }

    @Test
    void theV2ReportHashIgnoresReadTimes() {
        KitSettings s = settings(null, Map.of(), Map.of("pvp", "2,64,2"), List.of());
        List<KitReport.Shulker> one = List.of(new KitReport.Shulker(null, score(), pvp(), null));
        String h1 = KitReport.buildV2("srv", "stash", "Bot", List.of(new KitReport.BoxInfo("2,64,2", null, 27, 26, true, 1000, one)), List.of(), KitTypes.FINGERPRINT_V1, s).hash();
        String h2 = KitReport.buildV2("srv", "stash", "Bot", List.of(new KitReport.BoxInfo("2,64,2", null, 27, 26, true, 99999, one)), List.of(), KitTypes.FINGERPRINT_V1, s).hash();
        assertEquals(h1, h2);
    }

    // The heartbeat

    private static JsonObject beat(StashIndex.KitsHashes kits) {
        StashIndex.Built b = new StashIndex.Built("2b2t.org", "stash", "Qwerttze", "ix", new byte[0]);
        byte[] body = StashIndex.heartbeat(b, null, null, -1, null, null, null, null, null, null, null, kits);
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void theHeartbeatCarriesKitsHash2OrAnExplicitNullAndTheImportCapability() {
        JsonObject none = beat(new StashIndex.KitsHashes("a".repeat(40), "b".repeat(40)));
        assertTrue(none.has("kitsHash2") && none.get("kitsHash2").isJsonNull(), "no types in the cache: null, so the server sends the settings");
        assertFalse(none.has("kitOrders"));
        JsonObject some = beat(new StashIndex.KitsHashes("a".repeat(40), "b".repeat(40), "c".repeat(40), StashIndex.KitOrdersCap.IMPORT_ONLY));
        assertEquals("c".repeat(40), some.get("kitsHash2").getAsString());
        assertEquals("a".repeat(40), some.get("kitsHash").getAsString(), "the v1 hash is untouched");
        JsonObject cap = some.getAsJsonObject("kitOrders");
        assertEquals(1, cap.get("v").getAsInt());
        assertFalse(cap.get("enabled").getAsBoolean(), "the addon cannot carry out kit orders yet");
        assertTrue(cap.get("import").getAsBoolean());
        assertNotNull(cap.get("restock"));
        // With kit sorting off nothing about kits is said
        assertFalse(beat(null).has("kitsHash2"));
    }
}

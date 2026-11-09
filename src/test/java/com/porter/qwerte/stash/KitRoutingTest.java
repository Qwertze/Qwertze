package com.porter.qwerte.stash;

import com.porter.qwerte.stash.KitRouting.Kind;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a kit shulker goes: the decision, container ids, and which containers are kit-reserved. */
class KitRoutingTest {
    private static final String HASH = "0".repeat(40);

    private static KitSettings settings(String chest, Map<String, String> rules) {
        return new KitSettings(1, HASH, chest, rules, KitSettings.Scoring.DEFAULT);
    }

    /** A fake box: parts and whether it can take kits. */
    private static final class FakeBox {
        final List<KitSettings.Pos> parts;
        boolean pickable = true;

        FakeBox(KitSettings.Pos... parts) {
            this.parts = new java.util.ArrayList<>(List.of(parts));
        }
    }

    private static KitSettings.Pos pos(int x, int y, int z) {
        return new KitSettings.Pos(x, y, z);
    }

    private static class Stash implements KitRouting.Resolver<FakeBox> {
        final List<FakeBox> boxes = new java.util.ArrayList<>();

        FakeBox add(int x, int y, int z) {
            FakeBox b = new FakeBox(pos(x, y, z));
            boxes.add(b);
            return b;
        }

        @Override
        public FakeBox boxAt(KitSettings.Pos p) {
            for (FakeBox b : boxes) if (b.parts.contains(p)) return b;
            return null;
        }

        @Override
        public boolean pickable(FakeBox box) {
            return box.pickable;
        }
    }

    // decide

    @Test
    void aRuleBeatsTheKitsChest() {
        KitSettings s = settings("1,64,1", Map.of("pvp kit", "2,64,2"));
        KitRouting.Decision d = KitRouting.decide("pvp kit", true, s, id -> true);
        assertEquals(Kind.RULE, d.kind());
        assertEquals("2,64,2", d.container());
        assertEquals("pvp kit", d.key());
        assertTrue(d.routed());
    }

    @Test
    void withoutARuleTheKitsChestTakesIt() {
        KitSettings s = settings("1,64,1", Map.of("pvp kit", "2,64,2"));
        KitRouting.Decision d = KitRouting.decide("base kit", true, s, id -> true);
        assertEquals(Kind.KITS_CHEST, d.kind());
        assertEquals("1,64,1", d.container());
    }

    @Test
    void anUnnamedKitOnlyEverGoesToTheKitsChest() {
        KitSettings s = settings("1,64,1", Map.of("pvp kit", "2,64,2"));
        KitRouting.Decision d = KitRouting.decide(null, true, s, id -> true);
        assertEquals(Kind.KITS_CHEST, d.kind());
        assertNull(d.key());
        assertEquals(Kind.NONE, KitRouting.decide(null, true, settings(null, Map.of("pvp kit", "2,64,2")), id -> true).kind(), "no kits chest: today's sorting");
    }

    @Test
    void ruleKeysNeedNoKitWord() {
        KitSettings s = settings(null, Map.of("mixed concrete", "2,64,2"));
        KitRouting.Decision d = KitRouting.decide("mixed concrete", true, s, id -> true);
        assertEquals(Kind.RULE, d.kind());
        assertEquals("2,64,2", d.container());
    }

    @Test
    void withNeitherItIsTodaysSorting() {
        KitRouting.Decision d = KitRouting.decide("base kit", true, settings(null, Map.of("pvp kit", "2,64,2")), id -> true);
        assertEquals(Kind.NONE, d.kind());
        assertFalse(d.routed());
        assertEquals(Kind.NONE, KitRouting.decide("base kit", true, KitSettings.EMPTY, id -> true).kind());
    }

    @Test
    void aChosenContainerThatCannotTakeKitsGivesNoDestinationAndDoesNotFallBack() {
        KitSettings s = settings("1,64,1", Map.of("pvp kit", "2,64,2"));
        KitRouting.Decision rule = KitRouting.decide("pvp kit", true, s, id -> id.equals("1,64,1"));
        assertEquals(Kind.MISSING, rule.kind(), "the rule's container is not pickable: the kits chest is not tried instead");
        assertTrue(rule.routed());
        assertEquals(Kind.MISSING, KitRouting.decide("base kit", true, s, id -> false).kind());
    }

    @Test
    void whatIsNotAKitIsLeftAlone() {
        KitSettings s = settings("1,64,1", Map.of());
        assertEquals(Kind.NOT_KIT, KitRouting.decide("kit", false, s, id -> true).kind());
        assertFalse(KitRouting.decide(null, false, s, id -> true).routed());
    }

    // ids

    @Test
    void anIdIsTheSmallestPart() {
        assertEquals("10,64,4", KitRouting.canonicalId(List.of(pos(10, 64, 5), pos(10, 64, 4))));
        assertEquals("9,70,99", KitRouting.canonicalId(List.of(pos(10, 64, 5), pos(9, 70, 99))), "x first");
        assertEquals("10,64,5", KitRouting.canonicalId(List.of(pos(10, 64, 5), pos(10, 65, 0))), "then y");
        assertEquals("10,64,4", KitRouting.canonicalId(List.of(pos(10, 64, 4), pos(10, 64, 5))), "whichever half was found first");
        assertEquals("-3,-60,-7", KitRouting.canonicalId(List.of(pos(-3, -60, -7))));
    }

    @Test
    void anIdStillResolvesWhenAChestBecomesADouble() {
        Stash stash = new Stash();
        FakeBox single = stash.add(10, 64, 5);
        KitRouting.View<FakeBox> before = new KitRouting.View<>(stash, settings("10,64,5", Map.of()));
        assertSame(single, before.box("10,64,5"));

        single.parts.add(pos(10, 64, 4));
        KitRouting.View<FakeBox> after = new KitRouting.View<>(stash, settings("10,64,5", Map.of()));
        assertSame(single, after.box("10,64,5"), "boxAt matches any part");
        assertSame(single, after.box("10,64,4"));
        assertEquals("10,64,4", KitRouting.canonicalId(single.parts));
    }

    // View

    @Test
    void theKitsChestAndEveryRuleTargetAreReserved() {
        Stash stash = new Stash();
        FakeBox chest = stash.add(1, 64, 1), ruleBox = stash.add(2, 64, 2), other = stash.add(3, 64, 3);
        KitRouting.View<FakeBox> v = new KitRouting.View<>(stash, settings("1,64,1", Map.of("pvp kit", "2,64,2", "gone kit", "9,64,9")));
        assertTrue(v.reserved(chest));
        assertTrue(v.reserved(ruleBox));
        assertFalse(v.reserved(other));
        assertNull(v.box("9,64,9"), "a target the stash does not have is nothing, and reserves nothing");
    }

    @Test
    void aRoutedKitHasTheTargetAsItsOnlyDestination() {
        Stash stash = new Stash();
        FakeBox chest = stash.add(1, 64, 1), ruleBox = stash.add(2, 64, 2);
        KitRouting.View<FakeBox> v = new KitRouting.View<>(stash, settings("1,64,1", Map.of("pvp kit", "2,64,2")));

        KitRouting.Route<FakeBox> rule = v.route("pvp kit", true);
        assertEquals(Kind.RULE, rule.kind());
        assertEquals(List.of(ruleBox), v.destinations(rule));

        KitRouting.Route<FakeBox> fallback = v.route("other kit", true);
        assertEquals(Kind.KITS_CHEST, fallback.kind());
        assertEquals(List.of(chest), v.destinations(fallback));

        KitRouting.Route<FakeBox> unnamed = v.route(null, true);
        assertEquals(Kind.KITS_CHEST, unnamed.kind());
        assertEquals(List.of(chest), v.destinations(unnamed));
    }

    @Test
    void aTargetThatIsNotPickableGivesNoDestinationButStaysReserved() {
        Stash stash = new Stash();
        FakeBox bad = stash.add(1, 64, 1);
        bad.pickable = false;
        for (String id : List.of("1,64,1", "5,64,5")) {
            KitRouting.View<FakeBox> v = new KitRouting.View<>(stash, settings(id, Map.of()));
            KitRouting.Route<FakeBox> route = v.route("base kit", true);
            assertEquals(Kind.MISSING, route.kind(), id);
            assertEquals(List.of(), v.destinations(route), id);
        }
        assertTrue(new KitRouting.View<>(stash, settings("1,64,1", Map.of())).reserved(bad));
    }

    @Test
    void pickableIsLookedAtEveryTime() {
        Stash stash = new Stash();
        FakeBox chest = stash.add(1, 64, 1);
        KitRouting.View<FakeBox> v = new KitRouting.View<>(stash, settings("1,64,1", Map.of()));
        assertEquals(Kind.KITS_CHEST, v.route("base kit", true).kind());
        chest.pickable = false;
        assertEquals(Kind.MISSING, v.route("base kit", true).kind());
        chest.pickable = true;
        assertEquals(Kind.KITS_CHEST, v.route("base kit", true).kind());
    }

    @Test
    void whatIsNotAKitScoreIsLeftAlone() {
        Stash stash = new Stash();
        stash.add(1, 64, 1);
        KitRouting.View<FakeBox> v = new KitRouting.View<>(stash, settings("1,64,1", Map.of()));
        assertEquals(Kind.NOT_KIT, v.route("kit", false).kind(), "the box does not score as a kit, whatever its name");
    }

    @Test
    void emptySettingsRouteNothingAndReserveNothing() {
        Stash stash = new Stash();
        FakeBox b = stash.add(1, 64, 1);
        KitRouting.View<FakeBox> v = new KitRouting.View<>(stash, KitSettings.EMPTY);
        assertTrue(v.isEmpty());
        assertEquals(Kind.NOT_KIT, v.route("kit", true).kind());
        assertFalse(v.reserved(b));
    }

    @Test
    void theResolverIsAskedOncePerId() {
        Map<String, Integer> asked = new HashMap<>();
        Stash stash = new Stash() {
            @Override
            public FakeBox boxAt(KitSettings.Pos p) {
                asked.merge(p.toString(), 1, Integer::sum);
                return super.boxAt(p);
            }
        };
        stash.add(1, 64, 1);
        KitRouting.View<FakeBox> v = new KitRouting.View<>(stash, settings("1,64,1", Map.of()));
        for (int i = 0; i < 5; i++) v.route("base kit", true);
        assertEquals(1, asked.get(pos(1, 64, 1).toString()));
    }
}

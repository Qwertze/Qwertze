package com.porter.qwerte.core.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnvilTest {
    private static final String SWORD = "diamond_sword";

    private static Stack item(int rc, Object... kv) {
        return Stack.item(map(kv), rc);
    }

    private static Stack book(int rc, Object... kv) {
        return Stack.book(map(kv), rc);
    }

    private static Map<String, Integer> map(Object... kv) {
        java.util.TreeMap<String, Integer> m = new java.util.TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    @Test
    void stepCostIsPriorWorkPlusMultiplierTimesResultingLevel() {
        for (String mc : McData.SUPPORTED) {
            McData d = McData.load(mc);
            Anvil.Result r = Anvil.combine(d, SWORD, item(0), book(0, "sharpness", 5));
            assertTrue(r.ok());
            assertEquals(5, r.cost());
            assertEquals(1, r.result().repairCost(), "2*max+1 from a fresh item");
            assertEquals(Map.of("sharpness", 5), r.result().enchants());
        }
    }

    @Test
    void priorWorkIsTheSumOfTheStoredRepairCosts() {
        McData d = McData.load("1.21.11");
        Anvil.Result base = Anvil.combine(d, SWORD, item(0), book(0, "looting", 3));
        Anvil.Result worked = Anvil.combine(d, SWORD, item(3), book(1, "looting", 3));
        assertEquals(base.cost() + 3 + 1, worked.cost());
        assertEquals(2 * 3 + 1, worked.result().repairCost());
    }

    @Test
    void multiplierDependsOnlyOnWhetherTheRightItemIsABook() {
        McData d = McData.load("1.21.11");
        // unbreaking: item multiplier 2, book multiplier 1
        assertEquals(3, Anvil.combine(d, SWORD, item(0), book(0, "unbreaking", 3)).cost());
        assertEquals(6, Anvil.combine(d, SWORD, item(0), item(0, "unbreaking", 3)).cost());
        // a book on the left does not halve anything: book + book uses the right book's multiplier
        assertEquals(3, Anvil.combine(d, null, book(0, "unbreaking", 1), book(0, "unbreaking", 3)).cost());
    }

    @Test
    void levelsMergeEqualBelowMaxAndClampAtMax() {
        McData d = McData.load("1.21.11");
        Anvil.Result r = Anvil.combine(d, null, book(0, "sharpness", 4), book(0, "sharpness", 4));
        assertEquals(Map.of("sharpness", 5), r.result().enchants());
        assertEquals(5, r.cost());
        Anvil.Result atMax = Anvil.combine(d, null, book(0, "sharpness", 5), book(0, "sharpness", 5));
        assertEquals(Map.of("sharpness", 5), atMax.result().enchants());
        assertFalse(atMax.changed(), "V + V stays V but is still charged");
        assertEquals(5, atMax.cost());
        Anvil.Result higher = Anvil.combine(d, null, book(0, "sharpness", 3), book(0, "sharpness", 4));
        assertEquals(Map.of("sharpness", 4), higher.result().enchants());
    }

    @Test
    void aConflictingEnchantCostsOneMoreAndIsDropped() {
        McData d = McData.load("1.21.11");
        Stack left = item(0, "sharpness", 5);
        Anvil.Result plain = Anvil.combine(d, SWORD, left, book(0, "looting", 2));
        Anvil.Result withSmite = Anvil.combine(d, SWORD, left, book(0, "looting", 2, "smite", 3));
        assertEquals(plain.cost() + EnchantRules.CONFLICT_PENALTY, withSmite.cost(), "+1 per conflicting enchantment on the left");
        assertEquals(List.of("smite"), withSmite.dropped());
        assertEquals(Map.of("sharpness", 5, "looting", 2), withSmite.result().enchants(), "smite must not reach the item");
        // two conflicting enchants on the left give +2 for one right enchant? only one left enchant here, so +1
        Anvil.Result bane = Anvil.combine(d, SWORD, left, book(0, "bane_of_arthropods", 5, "looting", 2));
        assertEquals(plain.cost() + 1, bane.cost());
    }

    @Test
    void whenEveryRightEnchantIsDroppedThereIsNoResult() {
        McData d = McData.load("1.21.11");
        Anvil.Result r = Anvil.combine(d, SWORD, item(0, "sharpness", 5), book(0, "smite", 5));
        assertFalse(r.ok());
        assertEquals(Anvil.REFUSAL_NOTHING, r.refusal());
        assertNull(r.result());
        assertEquals(List.of("smite"), r.dropped());
    }

    @Test
    void anEnchantTheItemCannotTakeIsSkippedWithoutCost() {
        McData d = McData.load("1.21.11");
        Anvil.Result r = Anvil.combine(d, SWORD, item(0), book(0, "power", 5, "sharpness", 3));
        assertEquals(3, r.cost());
        assertEquals(List.of("power"), r.dropped());
        // the same enchants stay on a book on the left: only the item type check is skipped
        Anvil.Result onBook = Anvil.combine(d, null, book(0), book(0, "power", 5));
        assertTrue(onBook.ok());
        assertEquals(Map.of("power", 5), onBook.result().enchants());
    }

    @Test
    void aLeftStackOfSeveralItemsForcesTooExpensive() {
        McData d = McData.load("1.21.11");
        Anvil.Result r = Anvil.combine(d, SWORD, item(0), 2, book(0, "sharpness", 1));
        assertFalse(r.ok());
        assertEquals(Anvil.REFUSAL_TOO_EXPENSIVE, r.refusal());
        assertTrue(r.cost() >= EnchantRules.TOO_EXPENSIVE_AT);
        assertTrue(Anvil.combine(d, SWORD, item(0), 1, book(0, "sharpness", 1)).ok());
    }

    @Test
    void tooExpensiveAtFortyAndAcceptedAtThirtyNine() {
        McData d = McData.load("1.21.11");
        // one right book worth 28 levels without prior work
        Stack heavy = book(0, "sharpness", 5, "looting", 3, "fire_aspect", 2, "unbreaking", 3, "mending", 1,
                "sweeping_edge", 3, "knockback", 2);
        int plain = Anvil.combine(d, SWORD, item(0), heavy).cost();
        assertEquals(28, plain);
        Anvil.Result ok = Anvil.combine(d, SWORD, item(EnchantRules.MAX_STEP_COST - plain), heavy);
        assertEquals(EnchantRules.MAX_STEP_COST, ok.cost());
        assertTrue(ok.ok());
        Anvil.Result over = Anvil.combine(d, SWORD, item(EnchantRules.TOO_EXPENSIVE_AT - plain), heavy);
        assertEquals(EnchantRules.TOO_EXPENSIVE_AT, over.cost());
        assertFalse(over.ok());
    }

    @Test
    void aBookOnTheLeftWithAnItemOnTheRightGivesNoResult() {
        McData d = McData.load("1.21.11");
        Anvil.Result r = Anvil.combine(d, SWORD, book(0, "sharpness", 1), item(0, "sharpness", 2));
        assertFalse(r.ok());
        assertEquals(Anvil.REFUSAL_OPERANDS, r.refusal());
    }
}

package com.porter.qwerte.core.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RulesTest {
    @Test
    void experiencePointsMatchTheGameCurve() {
        // known cumulative totals of the vanilla experience curve
        assertEquals(0, EnchantRules.points(0));
        assertEquals(7, EnchantRules.points(1));
        assertEquals(315, EnchantRules.points(15));
        assertEquals(352, EnchantRules.points(16));
        assertEquals(394, EnchantRules.points(17));
        assertEquals(1395, EnchantRules.points(30));
        assertEquals(1507, EnchantRules.points(31));
        assertEquals(1628, EnchantRules.points(32));
        assertEquals(2727, EnchantRules.points(39));
        assertEquals(2920, EnchantRules.points(40));
    }

    @Test
    void pointsAgreeWithTheSumOfPerLevelNeeds() {
        int sum = 0;
        for (int l = 0; l < 60; l++) {
            assertEquals(sum, EnchantRules.points(l), "level " + l);
            sum += l < 15 ? 2 * l + 7 : l < 30 ? 5 * l - 38 : 9 * l - 158;
        }
    }

    @Test
    void oneBigStepCostsMoreThanTwoSmallOnes() {
        assertEquals(true, EnchantRules.points(30) > 2 * EnchantRules.points(15));
    }

    @Test
    void repairCostFollowsTwoMaxPlusOne() {
        assertEquals(1, EnchantRules.resultRepairCost(0, 0));
        assertEquals(3, EnchantRules.resultRepairCost(1, 0));
        assertEquals(7, EnchantRules.resultRepairCost(3, 1));
        for (int n = 0; n < 8; n++) {
            assertEquals(EnchantRules.repairCostAfterSteps(n + 1), EnchantRules.resultRepairCost(EnchantRules.repairCostAfterSteps(n), 0));
        }
        assertEquals(4, EnchantRules.priorWorkCost(3, 1));
        assertEquals(Integer.MAX_VALUE, EnchantRules.resultRepairCost(Integer.MAX_VALUE, 0));
    }

    @Test
    void bottlesAreTheSmallestCountThreeSigmaUnderTheMargin() {
        assertEquals(0, EnchantRules.bottles(0));
        for (int points : new int[]{1, 7, 55, 70, 100, 785, 2727, 5060, 20000}) {
            int n = EnchantRules.bottles(points);
            double target = points * 1.10;
            long floor = (long) Math.ceil(target / 7) + 5;
            assertTrue(n >= floor, "at least ceil(points*1.1/7)+5 for " + points);
            assertTrue(7.0 * n - 6 * Math.sqrt(n) >= target, "three standard deviations under the margin for " + points);
            if (n > floor) {
                assertTrue(7.0 * (n - 1) - 6 * Math.sqrt(n - 1) < target, "the smallest such N for " + points);
            }
        }
        assertEquals(14, EnchantRules.bottles(55), "55 points: the +5 floor binds");
    }

    @Test
    void bookMultiplierHalvesButNeverBelowOne() {
        assertEquals(1, EnchantRules.bookMultiplier(1));
        assertEquals(1, EnchantRules.bookMultiplier(2));
        assertEquals(2, EnchantRules.bookMultiplier(4));
        assertEquals(4, EnchantRules.bookMultiplier(8));
    }
}

package com.porter.qwerte.core.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class MergeSearchTest {
    private static final String ITEM = "diamond_sword";

    /** Best outcome per distinct root enchantment result, by (points, levels, steps, repair cost). */
    private static Map<Map<String, Integer>, long[]> bruteBest(McData d, List<MergeSearch.Leaf> leaves) {
        Map<Map<String, Integer>, long[]> best = new HashMap<>();
        for (TreeOracle.Out o : TreeOracle.allRoots(d, ITEM, leaves)) {
            best.merge(new TreeMap<>(o.st().enchants()), o.key(), (a, b) -> TreeOracle.cmp(a, b) <= 0 ? a : b);
        }
        return best;
    }

    private static Map<Map<String, Integer>, long[]> dpBest(MergeSearch.Outcome out) {
        Map<Map<String, Integer>, long[]> best = new HashMap<>();
        for (Map.Entry<Long, List<MergeSearch.Node>> e : out.roots.entrySet()) {
            for (MergeSearch.Node n : e.getValue()) {
                long[] key = {n.points, n.levels, n.steps, n.rc};
                best.merge(new TreeMap<>(out.decode(e.getKey())), key, (a, b) -> TreeOracle.cmp(a, b) <= 0 ? a : b);
            }
        }
        return best;
    }

    @Test
    void paretoDpAgreesWithBruteForceOn500SeededCases() {
        McData d = McData.load("1.21.11");
        Random rnd = new Random(20261112L);
        int sevenLeaf = 0;
        int feasible = 0;
        for (int c = 0; c < 500; c++) {
            int count = 2 + rnd.nextInt(6);
            if (count == 7 && ++sevenLeaf > 40) {
                count = 6;
            }
            List<MergeSearch.Leaf> leaves = TreeOracle.randomLeaves(d, rnd, count);
            Map<Map<String, Integer>, long[]> brute = bruteBest(d, leaves);
            MergeSearch.Outcome out = MergeSearch.run(d, ITEM, leaves, Map.of());
            Map<Map<String, Integer>, long[]> dp = dpBest(out);
            assertEquals(brute.keySet(), dp.keySet(), "case " + c + ": reachable results differ");
            for (Map.Entry<Map<String, Integer>, long[]> e : brute.entrySet()) {
                assertEquals(0, TreeOracle.cmp(e.getValue(), dp.get(e.getKey())),
                        "case " + c + " result " + e.getKey() + ": brute " + java.util.Arrays.toString(e.getValue())
                                + " dp " + java.util.Arrays.toString(dp.get(e.getKey())));
            }
            // And through the wanted-levels path with the FULL tie-break (points, extras, levels, steps, repair cost):
            // the order is a random subset of a reachable result (so extras exist) or a random, maybe impossible, order.
            Map<String, Integer> wanted = new TreeMap<>();
            if (!brute.isEmpty() && rnd.nextInt(5) != 0) {
                List<Map<String, Integer>> keys = new java.util.ArrayList<>(brute.keySet());
                keys.sort(java.util.Comparator.comparing(Object::toString));
                Map<String, Integer> reach = keys.get(rnd.nextInt(keys.size()));
                for (Map.Entry<String, Integer> e : reach.entrySet()) {
                    if (rnd.nextInt(3) != 0) {
                        wanted.put(e.getKey(), e.getValue());
                    }
                }
            }
            if (wanted.isEmpty()) {
                String id = TreeOracle.SWORD_POOL[rnd.nextInt(TreeOracle.SWORD_POOL.length)];
                wanted.put(id, 1 + rnd.nextInt(d.get(id).maxLevel()));
            }
            long[] bruteBest = null;
            for (TreeOracle.Out o : TreeOracle.allRoots(d, ITEM, leaves)) {
                boolean match = true;
                int extras = 0;
                for (Map.Entry<String, Integer> e : o.st().enchants().entrySet()) {
                    Integer w = wanted.get(e.getKey());
                    if (w == null) {
                        extras++;
                    }
                }
                for (Map.Entry<String, Integer> w : wanted.entrySet()) {
                    match &= o.st().enchants().getOrDefault(w.getKey(), 0).equals(w.getValue());
                }
                if (match) {
                    long[] t = {o.points(), extras, o.levels(), o.steps(), o.st().repairCost()};
                    if (bruteBest == null || TreeOracle.cmp(t, bruteBest) < 0) {
                        bruteBest = t;
                    }
                }
            }
            MergeSearch.Node best = MergeSearch.run(d, ITEM, leaves, wanted).best();
            if (bruteBest == null) {
                assertEquals(null, best, "case " + c + ": infeasible order must stay infeasible");
            } else {
                feasible++;
                assertNotNull(best, "case " + c);
                MergeSearch.Outcome w = MergeSearch.run(d, ITEM, leaves, wanted);
                long[] got = {best.points, w.extras(best.ench), best.levels, best.steps, best.rc};
                assertEquals(0, TreeOracle.cmp(bruteBest, got), "case " + c + " brute " + java.util.Arrays.toString(bruteBest)
                        + " dp " + java.util.Arrays.toString(got));
            }
        }
        assertTrue(feasible > 200, "the generator should mostly give reachable results, got " + feasible);
    }

    @Test
    void aCaseWhereOneBestPerSubsetIsBeaten() {
        McData d = McData.load("1.21.11");
        Random rnd = new Random(77L);
        int witnesses = 0;
        for (int c = 0; c < 3000 && witnesses == 0; c++) {
            List<MergeSearch.Leaf> leaves = TreeOracle.randomLeaves(d, rnd, 4 + rnd.nextInt(3));
            Map<Map<String, Integer>, long[]> brute = bruteBest(d, leaves);
            TreeOracle.Out naive = TreeOracle.naiveRoot(d, ITEM, leaves);
            for (Map.Entry<Map<String, Integer>, long[]> e : brute.entrySet()) {
                boolean beaten = naive == null || !new TreeMap<>(naive.st().enchants()).equals(e.getKey())
                        || naive.points() > e.getValue()[0];
                if (!beaten) {
                    continue;
                }
                MergeSearch.Outcome out = MergeSearch.run(d, ITEM, leaves, e.getKey());
                MergeSearch.Node best = out.best();
                assertNotNull(best);
                assertEquals(e.getValue()[0], best.points);
                if (naive != null && new TreeMap<>(naive.st().enchants()).equals(e.getKey()) && naive.points() > best.points) {
                    witnesses++; // same result, strictly cheaper only with the Pareto states
                    break;
                }
            }
        }
        assertTrue(witnesses > 0, "expected a case where the single-best DP pays more points for the same result");
    }
}

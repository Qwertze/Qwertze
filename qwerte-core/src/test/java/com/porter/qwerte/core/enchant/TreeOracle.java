package com.porter.qwerte.core.enchant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/** Test helpers: a brute-force enumerator of every binary merge tree, a naive single-best DP, and a random case generator. */
final class TreeOracle {
    private TreeOracle() {
    }

    record Out(Stack st, int points, int levels, int steps) {
        long[] key() {
            return new long[]{points, levels, steps, st.repairCost()};
        }
    }

    static int cmp(long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return Long.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static Stack stackOf(MergeSearch.Leaf l) {
        return new Stack(!l.base(), l.enchants(), l.repairCost());
    }

    /** Every tree outcome at the root: each outcome of every ordered binary tree with the base never on the right. */
    static List<Out> allRoots(McData d, String item, List<MergeSearch.Leaf> leaves) {
        int m = leaves.size();
        @SuppressWarnings("unchecked")
        List<Out>[] all = new List[1 << m];
        for (int mask = 1; mask < (1 << m); mask++) {
            List<Out> list = new ArrayList<>();
            all[mask] = list;
            if (Integer.bitCount(mask) == 1) {
                list.add(new Out(stackOf(leaves.get(Integer.numberOfTrailingZeros(mask))), 0, 0, 0));
                continue;
            }
            for (int sub = (mask - 1) & mask; sub > 0; sub = (sub - 1) & mask) {
                int r = mask ^ sub;
                if ((r & 1) != 0) {
                    continue;
                }
                for (Out lo : all[sub]) {
                    for (Out ro : all[r]) {
                        Out o = step(d, item, lo, ro);
                        if (o != null) {
                            list.add(o);
                        }
                    }
                }
            }
        }
        return all[(1 << m) - 1];
    }

    static Out step(McData d, String item, Out l, Out r) {
        Anvil.Result res = Anvil.combine(d, item, l.st(), r.st());
        if (!res.ok() || !res.changed()) {
            return null;
        }
        return new Out(res.result(), l.points() + r.points() + EnchantRules.points(res.cost()),
                l.levels() + r.levels() + res.cost(), l.steps() + r.steps() + 1);
    }

    /** The naive DP: one best result per subset of leaves, whatever its enchantments. Returns the root, or null. */
    static Out naiveRoot(McData d, String item, List<MergeSearch.Leaf> leaves) {
        int m = leaves.size();
        Out[] best = new Out[1 << m];
        for (int mask = 1; mask < (1 << m); mask++) {
            if (Integer.bitCount(mask) == 1) {
                best[mask] = new Out(stackOf(leaves.get(Integer.numberOfTrailingZeros(mask))), 0, 0, 0);
                continue;
            }
            Out b = null;
            for (int sub = (mask - 1) & mask; sub > 0; sub = (sub - 1) & mask) {
                int r = mask ^ sub;
                if ((r & 1) != 0 || best[sub] == null || best[r] == null) {
                    continue;
                }
                Out o = step(d, item, best[sub], best[r]);
                if (o != null && (b == null || cmp(o.key(), b.key()) < 0)) {
                    b = o;
                }
            }
            best[mask] = b;
        }
        return best[(1 << m) - 1];
    }

    static final String[] SWORD_POOL = {"sharpness", "smite", "bane_of_arthropods", "looting", "fire_aspect", "knockback",
            "unbreaking", "mending", "sweeping_edge", "power", "protection", "silk_touch"};

    /** A random leaf set for a diamond sword: base first, then books, never with an internally conflicting book. */
    static List<MergeSearch.Leaf> randomLeaves(McData d, Random rnd, int count) {
        int[] rcs = {0, 0, 0, 1, 1, 3, 7};
        List<MergeSearch.Leaf> out = new ArrayList<>();
        out.add(new MergeSearch.Leaf(true, rnd.nextInt(4) == 0 ? one(d, rnd) : Map.of(), rcs[rnd.nextInt(rcs.length)]));
        for (int i = 1; i < count; i++) {
            Map<String, Integer> e = new TreeMap<>(one(d, rnd));
            if (rnd.nextInt(3) == 0) {
                Map<String, Integer> second = one(d, rnd);
                for (Map.Entry<String, Integer> s : second.entrySet()) {
                    boolean ok = !e.containsKey(s.getKey());
                    for (String x : e.keySet()) {
                        ok &= !d.conflicts(x, s.getKey());
                    }
                    if (ok) {
                        e.put(s.getKey(), s.getValue());
                    }
                }
            }
            out.add(new MergeSearch.Leaf(false, e, rcs[rnd.nextInt(rcs.length)]));
        }
        return out;
    }

    private static Map<String, Integer> one(McData d, Random rnd) {
        String id = SWORD_POOL[rnd.nextInt(SWORD_POOL.length)];
        McData.Enchantment e = d.get(id);
        Map<String, Integer> m = new HashMap<>();
        // low levels are more interesting for merging
        m.put(id, 1 + rnd.nextInt(Math.max(1, Math.min(e.maxLevel(), 1 + rnd.nextInt(e.maxLevel())))));
        return m;
    }
}

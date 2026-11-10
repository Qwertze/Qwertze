package com.porter.stashmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The kit classifier of KIT-CONTRACT-v2 ("Classification", rules 1, 2, 3, 5 and 6; the template rule is the bots'
 * because it needs the item components). The bots classify boxes; the server only ships this port so the presets and
 * the shared test vectors can be checked against the same rules. All arithmetic is in integers.
 */
final class KitClassifier {
    private KitClassifier() {
    }

    /** codes: kits assortment, named_assortment, override; not kits empty, stock, stock_neutral, no_purpose, few_kinds, mixed, override_none. */
    record Result(boolean kit, String type, String code, int slots, Integer covered, Integer kinds, Integer need, Integer minKinds,
                  List<String> items, List<String> words) {
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Pattern> WORDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** How many word patterns are compiled and kept (a test checks they are reused). */
    static int cachedPatterns() {
        return WORDS.size();
    }

    private static boolean wordIn(String name, String word) {
        if (name == null) return false;
        return WORDS.computeIfAbsent(word, w -> Pattern.compile("(?<![\\p{L}\\p{M}])" + Pattern.quote(w) + "(?![\\p{L}\\p{M}])")).matcher(name).find();
    }

    private static String full(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    private record Stats(int covered, int kinds, int primary) {
    }

    private static Stats stats(KitTypes t, List<String> slots, String type) {
        int covered = 0, primary = 0;
        Set<String> kinds = new LinkedHashSet<>();
        for (String s : slots) {
            List<String> ts = t.typesOf(s);
            if (ts.contains(type)) {
                covered++;
                kinds.add(full(s));
            }
            if (!ts.isEmpty() && ts.get(0).equals(type)) primary++;
        }
        return new Stats(covered, kinds.size(), primary);
    }

    /** The ids counted for the type (every id when type is null) by slot count descending then id, first three. */
    private static List<String> top(KitTypes t, List<String> slots, String type) {
        Map<String, Integer> c = new HashMap<>();
        for (String s : slots) if (type == null || t.typesOf(s).contains(type)) c.merge(full(s), 1, Integer::sum);
        List<Map.Entry<String, Integer>> l = new ArrayList<>(c.entrySet());
        l.sort((a, b) -> a.getValue().equals(b.getValue()) ? a.getKey().compareTo(b.getKey()) : Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < l.size() && i < 3; i++) out.add(l.get(i).getKey());
        return out;
    }

    /**
     * Classifies a box. slots are its occupied slots' item ids in slot order, name its normalised custom name (null when
     * unnamed), treatAs the stash's overrides (key to type id or "none").
     */
    static Result classify(KitTypes t, List<String> slots, String name, Map<String, String> treatAs) {
        int n = slots.size();
        if (n == 0) return new Result(false, null, "empty", 0, null, null, null, null, List.of(), List.of());
        Set<String> distinct = new LinkedHashSet<>();
        for (String s : slots) distinct.add(full(s));
        if (distinct.size() == 1) {
            String x = distinct.iterator().next();
            List<String> ts = t.typesOf(x);
            if (ts.isEmpty()) return new Result(false, null, "stock_neutral", n, null, null, null, null, List.of(x), List.of());
            return new Result(false, ts.get(0), "stock", n, n, 1, null, null, List.of(x), List.of());
        }
        if (name != null && treatAs != null && treatAs.containsKey(name)) {
            String v = treatAs.get(name);
            if (v.equals("none")) return new Result(false, null, "override_none", n, null, null, null, null, List.of(), List.of());
            Stats s = stats(t, slots, v);
            return new Result(true, v, "override", n, s.covered(), s.kinds(), null, null, top(t, slots, v), List.of());
        }
        Map<String, Boolean> hinted = new LinkedHashMap<>();
        Map<String, List<String>> hintWords = new HashMap<>();
        for (KitTypes.Type ty : t.types()) {
            List<String> hw = new ArrayList<>();
            for (String w : ty.words()) if (wordIn(name, w)) hw.add(w);
            hinted.put(ty.id(), !hw.isEmpty());
            hintWords.put(ty.id(), hw);
        }
        boolean kitWord = wordIn(name, "kit") || wordIn(name, "kits");
        Map<String, Stats> st = new LinkedHashMap<>();
        for (String id : KitTypes.IDS) st.put(id, stats(t, slots, id));
        List<String> passing = new ArrayList<>();
        for (KitTypes.Type ty : t.types()) {
            Stats s = st.get(ty.id());
            boolean lowered = kitWord || hinted.get(ty.id());
            boolean share = lowered ? 2 * s.covered() >= n : 4 * s.covered() >= 3 * n;
            if (s.covered() >= 1 && s.kinds() >= ty.minKinds() && share) passing.add(ty.id());
        }
        if (!passing.isEmpty()) {
            String best = null;
            for (String id : passing) {
                if (best == null || betterPassing(id, best, hinted, st)) best = id;
            }
            Stats s = st.get(best);
            boolean assortment = 4 * s.covered() >= 3 * n;
            List<String> words = new ArrayList<>();
            if (!assortment) {
                if (kitWord) words.add(wordIn(name, "kit") ? "kit" : "kits");
                for (String w : hintWords.get(best)) if (words.size() < 4 && !words.contains(w)) words.add(w);
            }
            return new Result(true, best, assortment ? "assortment" : "named_assortment", n, s.covered(), s.kinds(), null, null,
                top(t, slots, best), words);
        }
        String closest = null;
        for (String id : KitTypes.IDS) {
            Stats s = st.get(id);
            if (s.covered() < 1) continue;
            if (closest == null || betterClosest(s, st.get(closest))) closest = id;
        }
        if (closest == null) return new Result(false, null, "no_purpose", n, null, null, null, null, top(t, slots, null), List.of());
        Stats s = st.get(closest);
        boolean lowered = kitWord || hinted.get(closest);
        int need = lowered ? (n + 1) / 2 : (3 * n + 3) / 4;
        return new Result(false, closest, s.covered() >= need ? "few_kinds" : "mixed", n, s.covered(), s.kinds(), need,
            t.type(closest).minKinds(), top(t, slots, closest), List.of());
    }

    /** Ranked: hinted first, then most primary, most covered, most kinds, then table order (a is later in table order than b). */
    private static boolean betterPassing(String a, String b, Map<String, Boolean> hinted, Map<String, Stats> st) {
        boolean ha = hinted.get(a), hb = hinted.get(b);
        if (ha != hb) return ha;
        Stats x = st.get(a), y = st.get(b);
        if (x.primary() != y.primary()) return x.primary() > y.primary();
        if (x.covered() != y.covered()) return x.covered() > y.covered();
        if (x.kinds() != y.kinds()) return x.kinds() > y.kinds();
        return false; // table order: the earlier one (b, seen first) stays
    }

    private static boolean betterClosest(Stats x, Stats y) {
        if (x.covered() != y.covered()) return x.covered() > y.covered();
        if (x.primary() != y.primary()) return x.primary() > y.primary();
        return x.kinds() > y.kinds();
    }
}

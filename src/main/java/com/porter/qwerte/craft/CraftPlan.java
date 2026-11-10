package com.porter.qwerte.craft;

import java.util.List;
import java.util.Map;

/**
 * The shape of a craft plan as the craft-order runner uses it (CRAFT-CONTRACT.md "Bot execution"): the steps in the order they are made,
 * what is taken from the stash, and the grid fills. The same records as the plugin's {@code CraftPlanner.Plan}/{@code Step}; the addon's own
 * {@link CraftPlanner} (for shulker boxes) keeps its own plan type. Pure.
 */
public final class CraftPlan {
    private CraftPlan() {
    }

    public static final String TOO_DEEP = "too deep";

    /** One recipe made {@code fills} times; {@code uses} is what goes into the grid in all, by item id. */
    public record Step(Recipes.Recipe recipe, int fills, int depth, Map<String, Long> uses) {
        public long made() {
            return (long) fills * recipe.count();
        }
    }

    /**
     * The way to craft {@code count} of {@code item}, or why not ({@code refusal}).
     * @param steps     in the order they are made: what a step uses is made by the steps before it
     * @param fromStock what is taken from the stash, by item id
     * @param fills     grid fills in all
     */
    public record Plan(String item, int count, List<Step> steps, Map<String, Long> fromStock, int fills, String refusal) {
        public boolean ok() {
            return refusal == null;
        }
    }

    /** "planks" for every kind of planks, else the first item's name and "or another". */
    static String label(List<String> alts) {
        String common = null;
        for (String a : alts) {
            int at = a.lastIndexOf('_');
            String last = at < 0 ? a.substring(a.indexOf(':') + 1) : a.substring(at + 1);
            if (common == null) common = last;
            else if (!common.equals(last)) return Recipes.pretty(alts.get(0)) + " or another";
        }
        return common == null ? "items" : common;
    }
}

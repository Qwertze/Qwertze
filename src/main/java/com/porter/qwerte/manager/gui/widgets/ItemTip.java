package com.porter.qwerte.manager.gui.widgets;

import com.porter.qwerte.manager.model.IndexModel;
import com.porter.qwerte.manager.model.Text;
import com.porter.qwerte.stash.Order;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The website's item tooltip (app.js showTooltip), as lines: the name (aqua when enchanted, italic when it has a custom name),
 * the details (enchantments and so on), the durability, the count with its stacks and shulkers, how many boxes are empty or
 * filled, and the id. A filled-shulker group lists its biggest contents instead. Built when the hovered item changes, not
 * every frame. Pure apart from the model it reads.
 */
public final class ItemTip {
    /** The id line is dim (the website's tt-id), hints are blue (tt-hint), as the game's own formatting codes. */
    static final String ID = "§8";
    public static final String HINT = "§9";

    private ItemTip() {
    }

    // The website's two item hints ("Right-click to look inside", "Right-click to see the filled ones") are left out on purpose: in
    // this window a click on a stash item opens its details drawer and a right-click puts a stack in the order cart, so those
    // hints would promise something else. The delivery chest keeps its own hints, which are true here too.

    /** The website's approx(): "<0.1", one decimal, whole numbers from 10. */
    static String approx(double v) {
        if (v > 0 && v < 0.05) return "<0.1";
        if (v >= 10) return Long.toString(Math.round(v));
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    static String plural(String text, String word) {
        return text + " " + word + (text.equals("1") ? "" : "s");
    }

    public static List<String> lines(IndexModel index, IndexModel.Entry e) {
        return lines(index, e, -1);
    }

    /** {@code countOverride} (0 or more) replaces the count shown: the Orders picker shows what is left to order. */
    public static List<String> lines(IndexModel index, IndexModel.Entry e, long countOverride) {
        if (e == null) return new ArrayList<>();
        if (e.item() != null) return item(index, e.item(), countOverride >= 0 ? countOverride : e.item().count(), List.of(), true);
        return group(index, e.shulker(), countOverride >= 0 ? countOverride : e.shulker().count());
    }

    /**
     * An item's tooltip with {@code count} and extra detail lines after its own (the delivery chest's "Right-click to remove this
     * stack"). {@code boxNote} adds the empty/filled line of a shulker box item.
     */
    public static List<String> item(IndexModel index, IndexModel.Item it, long count, List<String> extraDetails, boolean boxNote) {
        List<String> out = new ArrayList<>();
        String prefix = (it.enchanted() ? "§b" : "") + (!it.name().equals(it.englishName()) ? "§o" : "");
        out.add(prefix + it.name());
        boolean hasDurability = false;
        for (String d : it.details()) {
            out.add(d);
            if (d.regionMatches(true, 0, "Durability", 0, 10)) hasDurability = true;
        }
        out.addAll(extraDetails);
        if (it.durabilityLeft() >= 0 && !hasDurability) {
            out.add("Durability: " + Text.grouped(it.maxDamage() - it.damage()) + " / " + Text.grouped(it.maxDamage()));
        }
        int maxStack = Math.max(1, it.maxStack());
        out.add("Count: " + Text.grouped(count) + " (≈ " + plural(approx((double) count / maxStack), "stack") + ", "
            + plural(approx((double) count / (maxStack * 27.0)), "shulker") + ")");
        if (boxNote && it.isShulkerBox()) {
            List<IndexModel.Shulker> groups = index.groupsOf(it);
            if (!groups.isEmpty()) {
                long filled = 0;
                for (IndexModel.Shulker g : groups) filled += g.count();
                long empty = Math.max(0, it.count() - filled);
                out.add(Text.grouped(empty) + " empty, " + Text.grouped(it.count() - empty) + " filled (listed next to it, showing what they hold)");
            }
        }
        out.add(ID + it.id());
        return out;
    }

    /** A filled-shulker group: its biggest contents, the number of stacks and how many identical shulkers there are. */
    public static List<String> group(IndexModel index, IndexModel.Shulker g, long count) {
        List<String> out = new ArrayList<>();
        out.add(g.name());
        List<Order.Lot> lots = new ArrayList<>();
        for (IndexModel.Content c : g.contents()) lots.add(new Order.Lot(c.key(), (int) Math.min(Integer.MAX_VALUE, c.count())));
        out.addAll(summary(index, lots));
        out.add("×" + Text.grouped(count) + (count == 1 ? " shulker" : " identical shulkers"));
        out.add(ID + g.id());
        return out;
    }

    /** The website's shulkerSummary: up to four "Name ×N" lines by size, "...and k more", then "N stacks". */
    public static List<String> summary(IndexModel index, List<Order.Lot> lots) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (Order.Lot c : lots) totals.merge(c.key(), (long) c.count(), Long::sum);
        List<Map.Entry<String, Long>> top = new ArrayList<>(totals.entrySet());
        top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(4, top.size()); i++) {
            IndexModel.Item c = index.item(top.get(i).getKey());
            out.add((c != null ? c.name() : Text.sanitize(top.get(i).getKey(), 40)) + " ×" + Text.grouped(top.get(i).getValue()));
        }
        if (top.size() > 4) out.add("…and " + (top.size() - 4) + " more");
        out.add(plural(String.valueOf(lots.size()), "stack"));
        return out;
    }

    /** A tooltip with no count (planned shulkers, what is already in the chest): the name, the lines, the id; one string, lines split by newline. */
    public static String plain(String name, List<String> details, String id) {
        StringBuilder sb = new StringBuilder(name);
        for (String d : details) sb.append('\n').append(d);
        return sb.append('\n').append(ID).append(id).toString();
    }
}

package com.porter.qwerte.printer.stash;

import com.porter.qwerte.manager.model.CraftModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * From the server's quote to the lines of an order. The Printer decides what is missing, the server's quote says what the stash
 * can make of it, and this turns the two into craft lines that fit the order limits, and into the words the chat and the log
 * say about them. Pure.
 *
 * <p>The server counts a craft line like an item line: {@code ceil(count / maxStack)} slots, and 27 slots per shulker.
 */
public final class CraftOrders {
    private CraftOrders() {}

    /** What one quote adds: the craft lines (item id to count), what is still missing, the stash items they use. */
    public record Applied(LinkedHashMap<String, Long> craft, LinkedHashMap<String, Long> rest, List<CraftQuote.Count> uses) {
        public boolean any() {
            return !craft.isEmpty();
        }
    }

    /**
     * The quote applied to what is still missing ({@code rest}, item id to count): for each quoted item, what can be made, at most
     * what is wanted. A quote that can't be ordered adds nothing. Items the server did not quote stay missing.
     */
    public static Applied apply(CraftQuote.Quote q, Map<String, Long> rest) {
        return apply(q, rest, id -> false);
    }

    /** As above, leaving out the items {@code blocked} says must not be ordered here (cancelled or failed there, or short lately). */
    public static Applied apply(CraftQuote.Quote q, Map<String, Long> rest, java.util.function.Predicate<String> blocked) {
        LinkedHashMap<String, Long> craft = new LinkedHashMap<>();
        LinkedHashMap<String, Long> left = new LinkedHashMap<>(rest);
        Map<String, Long> uses = new LinkedHashMap<>();
        if (q != null && q.canOrder()) {
            for (CraftQuote.Line l : q.lines()) {
                if (blocked.test(l.id())) continue;
                long wanted = left.getOrDefault(l.id(), 0L);
                long n = Math.min(l.craftable(), wanted);
                if (n <= 0) continue;
                craft.merge(l.id(), n, Long::sum);
                left.put(l.id(), wanted - n);
                for (CraftQuote.Count u : l.uses()) {
                    // A line made in part uses its share of the stash items, rounded up
                    long share = l.craftable() <= n ? u.count() : mulDivCeil(u.count(), n, l.craftable());
                    uses.merge(u.id(), share, Long::sum);
                }
            }
        }
        left.values().removeIf(v -> v <= 0);
        return new Applied(craft, left, counts(uses));
    }

    /** {@code a * b / c} rounded up, without overflow (c &gt; 0). */
    public static long mulDivCeil(long a, long b, long c) {
        if (c <= 0) return 0;
        java.math.BigInteger[] qr = java.math.BigInteger.valueOf(Math.max(0, a)).multiply(java.math.BigInteger.valueOf(Math.max(0, b)))
            .divideAndRemainder(java.math.BigInteger.valueOf(c));
        java.math.BigInteger q = qr[1].signum() == 0 ? qr[0] : qr[0].add(java.math.BigInteger.ONE);
        return q.bitLength() > 62 ? Long.MAX_VALUE / 2 : q.longValue();
    }

    /** The stash items a crafting uses, scaled to the part of it that is kept ({@code after} of {@code before}), rounded up. */
    public static List<CraftQuote.Count> scaledUses(List<CraftQuote.Count> uses, long after, long before) {
        if (after >= before) return uses;
        List<CraftQuote.Count> out = new ArrayList<>();
        if (after <= 0 || before <= 0) return out;
        for (CraftQuote.Count u : uses) out.add(new CraftQuote.Count(u.id(), mulDivCeil(u.count(), after, before)));
        return out;
    }

    private static List<CraftQuote.Count> counts(Map<String, Long> m) {
        List<CraftQuote.Count> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : m.entrySet()) if (e.getValue() > 0) out.add(new CraftQuote.Count(e.getKey(), e.getValue()));
        return out;
    }

    /** One order to build: item lines, craft lines and the stash items the crafting uses. */
    public record Draft(String stashKey, LinkedHashMap<String, Long> direct, LinkedHashMap<String, Long> craft, List<CraftQuote.Count> uses) {}

    /** The order that fits the limits; {@code shulkers} counts the item and craft lines together. */
    public record Built(String stashKey, LinkedHashMap<String, Integer> direct, LinkedHashMap<String, Integer> craft,
                        List<CraftQuote.Count> uses, int shulkers) {}

    private static int stack(ToIntFunction<String> maxStack, String id) {
        int s = maxStack.applyAsInt(id);
        return s <= 0 ? 64 : s;
    }

    private static long slots(Map<String, ? extends Number> lines, ToIntFunction<String> maxStack) {
        long slots = 0;
        for (Map.Entry<String, ? extends Number> e : lines.entrySet()) {
            long n = e.getValue().longValue();
            if (n > 0) slots += (n + stack(maxStack, e.getKey()) - 1) / stack(maxStack, e.getKey());
        }
        return slots;
    }

    /** The shulkers an order of these item and craft lines needs, the server's way. */
    public static int shulkers(Map<String, ? extends Number> direct, Map<String, ? extends Number> craft, ToIntFunction<String> maxStack) {
        long total = slots(direct, maxStack) + slots(craft, maxStack);
        return (int) ((total + OrderBatch.SLOTS - 1) / OrderBatch.SLOTS);
    }

    /**
     * The orders, in order, cut to the limits: the item lines first (as {@link OrderBatch#cap}), then the craft lines into the
     * slots that are left, whole stacks first. Each order lowers what the run may still use. An order left with no lines is dropped.
     * Without craft lines this is the plain item order of before.
     *
     * @param perOrder the most shulkers one order may need (1..54)
     * @param runLeft  what the per-run cap still allows
     */
    public static List<Built> assemble(List<Draft> drafts, int perOrder, int runLeft, ToIntFunction<String> maxStack) {
        List<Built> out = new ArrayList<>();
        int left = runLeft;
        for (Draft d : drafts) {
            OrderBatch.Limits limits = new OrderBatch.Limits(perOrder, left);
            LinkedHashMap<String, Integer> direct = OrderBatch.cap(d.direct(), maxStack, limits);
            LinkedHashMap<String, Integer> craft = new LinkedHashMap<>();
            long free = (long) limits.shulkers() * OrderBatch.SLOTS - slots(direct, maxStack);
            long asked = 0;
            long kept = 0;
            for (Map.Entry<String, Long> e : d.craft().entrySet()) {
                long n = e.getValue();
                if (n <= 0) continue;
                asked += n;
                if (free <= 0 || direct.size() + craft.size() >= limits.lines()) continue;
                int ms = stack(maxStack, e.getKey());
                long cost = (n + ms - 1) / ms;
                if (cost > free) {
                    n = free * ms;
                    cost = free;
                }
                if (n <= 0) continue;
                craft.put(e.getKey(), (int) Math.min(Integer.MAX_VALUE, n));
                free -= cost;
                kept += n;
            }
            if (direct.isEmpty() && craft.isEmpty()) continue;
            List<CraftQuote.Count> uses = d.uses();
            if (kept < asked && asked > 0) {
                // Some of the crafting was cut: the stash items it uses shrink with it, rounded up
                uses = scaledUses(uses, kept, asked);
            }
            if (craft.isEmpty()) uses = List.of();
            int shulkers = shulkers(direct, craft, maxStack);
            out.add(new Built(d.stashKey(), direct, craft, uses, shulkers));
            left -= shulkers;
        }
        return out;
    }

    // ---------- words ----------

    private static String listed(Map<String, ? extends Number> m, Function<String, String> name, int max) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, ? extends Number> e : m.entrySet()) {
            if (parts.size() >= max) break;
            parts.add(e.getValue().longValue() + " " + name.apply(e.getKey()));
        }
        String s = String.join(", ", parts);
        return m.size() > max ? s + " and " + (m.size() - max) + " more" : s;
    }

    /** "from 2 Spruce Log, 3 Stick"; empty without uses. */
    public static String usesText(List<CraftQuote.Count> uses, Function<String, String> name) {
        if (uses.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (CraftQuote.Count u : uses) {
            if (parts.size() >= 4) break;
            parts.add(u.count() + " " + name.apply(u.id()));
        }
        return "from " + String.join(", ", parts) + (uses.size() > 4 ? " and " + (uses.size() - 4) + " more" : "");
    }

    /**
     * What an order holds, for the chat and the log: "64 Oak Planks + crafting 8 Spruce Stairs (from 2 Spruce Log)". Without craft
     * lines it is only the item part; with only craft lines, only the crafting part.
     */
    public static String summary(Map<String, ? extends Number> direct, Map<String, ? extends Number> craft, List<CraftQuote.Count> uses,
                                 Function<String, String> name) {
        StringBuilder sb = new StringBuilder();
        if (!direct.isEmpty()) sb.append(listed(direct, name, 4));
        if (!craft.isEmpty()) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append("crafting ").append(listed(craft, name, 4));
            String from = usesText(uses, name);
            if (!from.isEmpty()) sb.append(" (").append(from).append(')');
        }
        return sb.toString();
    }

    /**
     * How the crafting of a finished order went, in words for the chat (no brackets): "made 8 Spruce Stairs; could not make 3 Spruce
     * Fence - the ingredients ran short". Empty without a result.
     */
    public static String resultNote(CraftModel.Result r, Function<String, String> name) {
        if (r == null) return "";
        List<String> parts = new ArrayList<>();
        List<String> made = new ArrayList<>();
        for (CraftModel.Made m : r.made()) {
            if (made.size() >= 4) break;
            made.add((m.made() < m.wanted() ? m.made() + " of " + m.wanted() : String.valueOf(m.made())) + " " + name.apply(m.key()));
        }
        if (!made.isEmpty()) parts.add("made " + String.join(", ", made) + (r.made().size() > 4 ? " and " + (r.made().size() - 4) + " more" : ""));
        List<String> miss = new ArrayList<>();
        for (CraftModel.Shortfall s : r.shorts()) {
            if (miss.size() >= 4) break;
            miss.add(s.count() + " " + name.apply(s.key()) + " - " + CraftModel.shortReason(s.reason()));
        }
        if (!miss.isEmpty()) parts.add("could not make " + String.join(", ", miss) + (r.shorts().size() > 4 ? " and " + (r.shorts().size() - 4) + " more" : ""));
        return String.join("; ", parts);
    }

    /** The crafted part of a delivered order, for the chat: "crafted 8 Spruce Stairs" with the bot's note about what was short. */
    public static String craftedWords(Map<String, Long> craft, Function<String, String> name) {
        return craft.isEmpty() ? "" : "includes crafted " + listed(craft, name, 4);
    }
}

package com.porter.qwerte.manager.model;

import com.porter.qwerte.stash.Order;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The lines of an order being put together (the website's cart): merged per type and key, never needing more than
 * {@link CartPlan#MAX_SHULKERS} shulkers, never more than what is available. Pure; the window keeps one per stash.
 */
public final class Cart {
    /** How many of a line can still be ordered, before this cart. */
    public interface Availability {
        long total(String type, String key);
    }

    /** {@code added} is how many were put in; {@code message} says why it was fewer or none ("" when fine). */
    public record Result(boolean ok, int added, String message) {
        static Result refused(String message) {
            return new Result(false, 0, message);
        }
    }

    private final List<Order.Line> lines = new ArrayList<>();
    private boolean repairAll;

    /** The cart-wide "Repair all items" switch. */
    public boolean repairAll() {
        return repairAll;
    }

    public void setRepairAll(boolean on) {
        repairAll = on;
    }

    /** A line's own "Repair" toggle. */
    public void setRepair(int line, boolean on) {
        if (line < 0 || line >= lines.size()) return;
        Order.Line l = lines.get(line);
        lines.set(line, new Order.Line(l.type(), l.key(), l.count(), on));
    }

    public List<Order.Line> lines() {
        return Collections.unmodifiableList(new ArrayList<>(lines));
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public int size() {
        return lines.size();
    }

    public void clear() {
        lines.clear();
        repairAll = false;
    }

    public void replaceAll(List<Order.Line> next) {
        lines.clear();
        lines.addAll(next);
    }

    public long count(String type, String key) {
        long n = 0;
        for (Order.Line l : lines) if (l.type().equals(type) && l.key().equals(key)) n += l.count();
        return n;
    }

    /** Available for a new addition: what the stash can still give minus what the cart already holds. */
    public long left(String type, String key, Availability available) {
        return Math.max(0, available.total(type, key) - count(type, key));
    }

    public static String capMessage(long needed, long planned) {
        return "That needs " + needed + " shulkers. One order can fill at most " + CartPlan.MAX_SHULKERS
            + " (one double chest); the cart plans " + Math.min(planned, CartPlan.MAX_SHULKERS) + ".";
    }

    /** Adds {@code n} of a line unless the cart would then need more than the cap; then nothing changes. */
    public Result add(String type, String key, long n, IndexModel index) {
        if (n < 1) return Result.refused("Nothing to add.");
        List<Order.Line> next = new ArrayList<>(lines);
        int at = indexOf(next, type, key);
        long total = (at >= 0 ? next.get(at).count() : 0) + n;
        if (total > Integer.MAX_VALUE) return Result.refused("That is more than an order can hold.");
        if (at >= 0) next.set(at, next.get(at).withCount((int) total));
        else next.add(new Order.Line(type, key, (int) total));
        long need = CartPlan.shulkersNeeded(next, index);
        if (need > CartPlan.MAX_SHULKERS) return Result.refused(capMessage(need, CartPlan.shulkersNeeded(lines, index)));
        lines.clear();
        lines.addAll(next);
        return new Result(true, (int) n, "");
    }

    /** One stack (a shulker's worth, 27 stacks, with {@code shulkerWorth}; one box for a packed shulker), clamped to what is left. */
    public Result addStacks(String type, String key, boolean shulkerWorth, IndexModel index, Availability available) {
        long left = left(type, key, available);
        int stack = Order.SHULKER.equals(type) ? 1 : CartPlan.maxStack(key, index);
        long want = shulkerWorth && !Order.SHULKER.equals(type) ? (long) stack * CartPlan.SLOTS : stack;
        long n = Math.min(want, left);
        if (n < 1) return Result.refused("None left to order.");
        return add(type, key, n, index);
    }

    /** Sets a line's count (clamped to 1..what is available); growing past the cap changes nothing. */
    public Result setCount(int line, long wanted, IndexModel index, Availability available) {
        if (line < 0 || line >= lines.size()) return Result.refused("");
        Order.Line l = lines.get(line);
        long maxNow = Math.max(1, available.total(l.type(), l.key()) - (count(l.type(), l.key()) - l.count()));
        long n = Math.max(1, Math.min(Math.min(maxNow, Integer.MAX_VALUE), wanted));
        if (n == l.count()) return new Result(true, 0, "");
        List<Order.Line> next = new ArrayList<>(lines);
        next.set(line, l.withCount((int) n));
        long need = CartPlan.shulkersNeeded(next, index);
        if (need > CartPlan.MAX_SHULKERS && n > l.count()) return Result.refused(capMessage(need, CartPlan.shulkersNeeded(lines, index)));
        lines.clear();
        lines.addAll(next);
        return new Result(true, (int) (n - l.count()), n < wanted ? "Only " + n + " are available." : "");
    }

    public void remove(int line) {
        if (line >= 0 && line < lines.size()) lines.remove(line);
    }

    /** Removes up to {@code n} of a line (the whole line when it reaches 0). */
    public void removeAmount(String type, String key, long n) {
        int at = indexOf(lines, type, key);
        if (at < 0) return;
        long left = lines.get(at).count() - n;
        if (left < 1) lines.remove(at);
        else lines.set(at, lines.get(at).withCount((int) left));
    }

    private static int indexOf(List<Order.Line> list, String type, String key) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).type().equals(type) && list.get(i).key().equals(key)) return i;
        return -1;
    }
}

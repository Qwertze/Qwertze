package com.porter.qwerte.craft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Which anvil the naming uses, without the user having to set one: the one selected by hand, then a remembered one (found
 * or placed earlier), then a placed anvil found in the stash area, then an anvil taken from the stash and placed. When none
 * of that works the rename fails with {@link #NONE} and the order goes on unnamed. Pure: the world comes in through
 * {@link World}, so the choices are tested without the game. The same order of preference as the proxy plugin.
 */
public final class AnvilFinder {
    private AnvilFinder() {}

    /** The reason a shulker goes unnamed when no anvil can be had. */
    public static final String NONE = "no anvils available to rename with";

    /** Where the anvil in use came from: set by hand, found placed, or placed from the stash. */
    public enum Source {
        NONE("none"), SET("set"), FOUND("found"), PLACED("placed");

        public final String label;

        Source(String label) {
            this.label = label;
        }

        public static Source of(String label) {
            for (Source s : values()) if (s.label.equals(label)) return s;
            return NONE;
        }
    }

    public record Pos(int x, int y, int z) {
        public double distanceSq(Pos o) {
            double dx = x - o.x, dy = y - o.y, dz = z - o.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** The anvil position the stash remembers, and how it got there. */
    public record Memory(Pos pos, Source source) {}

    public interface World {
        /** The block at the position is an anvil (any damage); an unloaded chunk counts as one, nothing is forgotten on a guess. */
        boolean isAnvil(Pos p);

        /** The nearest placed anvil in the stash area, or null. */
        Pos search();

        /** An anvil item the stash can give: not reserved for an order line. */
        boolean stockHasAnvil();
    }

    public enum Step { USE, FOUND, PLACE_FROM_STOCK, FAIL }

    /**
     * @param forget the remembered position is gone and is to be dropped
     */
    public record Decision(Step step, Pos pos, Source source, String failure, boolean forget) {}

    public static Decision decide(Memory memory, World world) {
        boolean forget = false;
        if (memory != null && memory.pos() != null) {
            if (world.isAnvil(memory.pos())) return new Decision(Step.USE, memory.pos(), memory.source() == Source.NONE ? Source.SET : memory.source(), null, false);
            // The anvil that was set or remembered is gone or broken: forgotten, then looked for again
            forget = true;
        }
        Pos found = world.search();
        if (found != null) return new Decision(Step.FOUND, found, Source.FOUND, null, forget);
        if (world.stockHasAnvil()) return new Decision(Step.PLACE_FROM_STOCK, null, Source.PLACED, null, forget);
        return new Decision(Step.FAIL, null, Source.NONE, NONE, forget);
    }

    /** The nearest of these anvil cells to {@code from} that are inside the area (null: nothing). */
    public static Pos nearest(List<Pos> anvils, Pos from, Predicate<Pos> inArea) {
        List<Pos> ok = new ArrayList<>();
        for (Pos p : anvils) if (inArea.test(p)) ok.add(p);
        ok.sort(Comparator.comparingDouble(from::distanceSq).thenComparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z));
        return ok.isEmpty() ? null : ok.get(0);
    }
}

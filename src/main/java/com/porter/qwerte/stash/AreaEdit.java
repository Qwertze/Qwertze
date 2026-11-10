package com.porter.qwerte.stash;

/**
 * The stash area editor's operations (AREA-CONTRACT.md "Editing operations"), the same rules as the website's page: set a corner, expand per side
 * or all sides, include where I stand, drag a rectangle on the map. Each works on a draft (two corners and a dimension) and gives the new draft or
 * the reason it was refused, in which case the draft is unchanged. A result over the caps (256 x 128 x 256) or outside the Y limits of the
 * dimension is refused. Pure.
 */
public final class AreaEdit {
    private AreaEdit() {
    }

    public enum Side { NEG_X, POS_X, NEG_Z, POS_Z, DOWN, UP, ALL }

    /** The most one expand may move a side by, either way. */
    public static final int MAX_N = 255;
    /** The box an editor starts from when there is no area and nothing derived: centred on the player. */
    public static final int START_W = 32, START_H = 16, START_D = 32;

    /** Two corners (x, y, z each) and the dimension; the box is the span of the two. */
    public record Draft(int[] c1, int[] c2, String dimension) {
        public int[] box() {
            return new int[] {Math.min(c1[0], c2[0]), Math.min(c1[1], c2[1]), Math.min(c1[2], c2[2]), Math.max(c1[0], c2[0]), Math.max(c1[1], c2[1]), Math.max(c1[2], c2[2])};
        }

        public static Draft ofBox(int[] box, String dimension) {
            return new Draft(new int[] {box[0], box[1], box[2]}, new int[] {box[3], box[4], box[5]}, dimension);
        }

        /** "W×H×D". */
        public String size() {
            return sizeOf(box());
        }
    }

    /** The new draft, or why it was refused (then the draft is as it was). */
    public record Result(Draft draft, String refused) {
        public boolean ok() {
            return refused == null;
        }
    }

    public static String sizeOf(int[] box) {
        return ((long) box[3] - box[0] + 1) + "×" + ((long) box[4] - box[1] + 1) + "×" + ((long) box[5] - box[2] + 1);
    }

    /** Why this box is not allowed in this dimension (the caps, the Y limits, the range), or null. */
    public static String check(int[] box, String dimension) {
        return AreaRules.validate(box, dimension, dimension);
    }

    private static Result done(Draft before, Draft after) {
        String why = check(after.box(), after.dimension());
        return why == null ? new Result(after, null) : new Result(before, why);
    }

    /** Where an editor starts: the set area, else the derived one, else a 32×16×32 box centred on the player (moved inside the Y limits). */
    public static Draft start(int[] setBox, int[] derived, String dimension, int px, int py, int pz) {
        if (setBox != null) return Draft.ofBox(setBox, dimension);
        if (derived != null) return Draft.ofBox(derived, dimension);
        int[] y = AreaRules.yLimits(dimension);
        int minY = Math.max(y[0], Math.min(py - START_H / 2, y[1] - START_H + 1));
        return Draft.ofBox(new int[] {px - START_W / 2, minY, pz - START_D / 2, px - START_W / 2 + START_W - 1, minY + START_H - 1, pz - START_D / 2 + START_D - 1}, dimension);
    }

    /** Corner 1 or 2 at a block; the other corner is kept. */
    public static Result corner(Draft d, int which, int x, int y, int z) {
        int[] p = {x, y, z};
        return done(d, which == 1 ? new Draft(p, d.c2(), d.dimension()) : new Draft(d.c1(), p, d.dimension()));
    }

    /** Grows (n above 0) or shrinks (below 0) one side or all six by n blocks; an axis never goes below 1 block. */
    public static Result expand(Draft d, Side side, int n) {
        if (n < -MAX_N || n > MAX_N) return new Result(d, "N must be " + -MAX_N + ".." + MAX_N);
        int[] b = d.box();
        boolean lowX = side == Side.NEG_X || side == Side.ALL, highX = side == Side.POS_X || side == Side.ALL;
        boolean lowZ = side == Side.NEG_Z || side == Side.ALL, highZ = side == Side.POS_Z || side == Side.ALL;
        boolean lowY = side == Side.DOWN || side == Side.ALL, highY = side == Side.UP || side == Side.ALL;
        int[] x = axis(b[0], b[3], lowX ? n : 0, highX ? n : 0);
        int[] y = axis(b[1], b[4], lowY ? n : 0, highY ? n : 0);
        int[] z = axis(b[2], b[5], lowZ ? n : 0, highZ ? n : 0);
        return done(d, Draft.ofBox(new int[] {x[0], y[0], z[0], x[1], y[1], z[1]}, d.dimension()));
    }

    /** One axis: the low side moves by -growLo, the high side by +growHi; shrinking stops at one block. */
    private static int[] axis(int lo, int hi, int growLo, int growHi) {
        long nlo = (long) lo - growLo, nhi = (long) hi + growHi;
        if (nhi < nlo) {
            if (growLo != 0 && growHi != 0) nlo = nhi = Math.floorDiv((long) lo + hi, 2);
            else if (growLo != 0) nlo = nhi = hi;
            else nlo = nhi = lo;
        }
        return new int[] {(int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, nlo)), (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, nhi))};
    }

    /** The smallest growth that holds the player's feet block and the block above it. */
    public static Result include(Draft d, int feetX, int feetY, int feetZ) {
        int[] b = d.box();
        int[] nb = {Math.min(b[0], feetX), Math.min(b[1], feetY), Math.min(b[2], feetZ), Math.max(b[3], feetX), Math.max(b[4], feetY + 1), Math.max(b[5], feetZ)};
        return done(d, Draft.ofBox(nb, d.dimension()));
    }

    /** A rectangle dragged on the map sets minX/maxX and minZ/maxZ; Y is kept. */
    public static Result mapDrag(Draft d, int x1, int z1, int x2, int z2) {
        int[] b = d.box();
        int[] nb = {Math.min(x1, x2), b[1], Math.min(z1, z2), Math.max(x1, x2), b[4], Math.max(z1, z2)};
        return done(d, Draft.ofBox(nb, d.dimension()));
    }
}

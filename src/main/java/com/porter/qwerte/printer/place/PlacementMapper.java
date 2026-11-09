package com.porter.qwerte.printer.place;

import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Turns a {@link StateSpec} into the clicks that produce it, best first. Pure. The mapper only proposes: the runtime
 * {@code PlaceSimulator} runs the real getStateForPlacement against the real level and confirms the first recipe that
 * reproduces the target (neighbour-dependent results such as door hinges, chest merging and end rod flipping are only
 * decidable there).
 *
 * <p>A recipe clicks a solid or already placed neighbour (face next to the target), or, last, air-places by clicking the
 * target cell itself. Hit offsets are the face centre, plus the 0.25 / 0.75 bands for slab, stair and trapdoor halves
 * and door hinges. How the look relates to the state is documented on {@link LookWindow}.
 */
public final class PlacementMapper {
    private PlacementMapper() {}

    private static final int MAX_RECIPES = 16;
    private static final Dir[] SITE_ORDER = { Dir.DOWN, Dir.NORTH, Dir.SOUTH, Dir.WEST, Dir.EAST, Dir.UP };

    /** What the mapper may click around one target cell. Offsets are relative to the target. */
    public interface NeighbourView {
        /** The neighbour at target+d is solid now or already placed, so its face next to the target can be clicked. */
        boolean clickable(Dir d);

        /** Clicking it would use it (chest, door, crafting table, ...), so the player has to sneak. */
        boolean interactive(Dir d);
    }

    /** The recipes for a target, best first; empty for skipped and PART specs, or when nothing can be clicked. */
    public static List<PlaceRecipe> recipes(StateSpec spec, NeighbourView view, boolean airplace) {
        List<PlaceRecipe> out = new ArrayList<>();
        if (!spec.placeable()) return out;
        new Builder(spec, view, airplace, out).run();
        out.sort(Comparator.comparingInt((PlaceRecipe r) -> r.airplace() ? 1 : 0).thenComparingInt(r -> r.sneak() ? 1 : 0));
        return out.size() > MAX_RECIPES ? new ArrayList<>(out.subList(0, MAX_RECIPES)) : out;
    }

    /**
     * Vanilla DoorBlock.getHinge when the neighbours do not decide: the click offset inside the cell and the facing
     * choose the side. Read from the bytecode.
     */
    public static String hingeByClick(Dir h, double dx, double dz) {
        boolean right = (h.dx < 0 && dz < 0.5) || (h.dx > 0 && dz > 0.5) || (h.dz < 0 && dx > 0.5) || (h.dz > 0 && dx < 0.5);
        return right ? "right" : "left";
    }

    /** True for skull and head blocks, which use the raw yaw for their rotation (signs and banners add 180). */
    public static boolean skullLike(String blockId) {
        return blockId.endsWith("_skull") || blockId.endsWith("_head");
    }

    /**
     * A point on the plane of the target cell that lies on side {@code side} (the shared face with the neighbour on
     * that side, or the cell's own face for an air-place). a and b are the two free coordinates in x, y, z order.
     */
    static double[] hit(Dir side, double a, double b) {
        return switch (side.axis()) {
            case 'y' -> new double[] { a, side == Dir.UP ? 1 : 0, b };
            case 'x' -> new double[] { side == Dir.EAST ? 1 : 0, a, b };
            default -> new double[] { a, b, side == Dir.SOUTH ? 1 : 0 };
        };
    }

    /** A point on a side plane at height y inside the cell, centred along the face. */
    private static double[] hitAtY(Dir side, double y) {
        return switch (side.axis()) {
            case 'x' -> hit(side, y, 0.5);
            case 'z' -> hit(side, 0.5, y);
            default -> hit(side, 0.5, 0.5);
        };
    }

    private static final double[] CENTER = { 0.5, 0.5 };

    private static final class Builder {
        final StateSpec s;
        final NeighbourView v;
        final boolean air;
        final List<PlaceRecipe> out;

        Builder(StateSpec s, NeighbourView v, boolean air, List<PlaceRecipe> out) {
            this.s = s;
            this.v = v;
            this.air = air;
            this.out = out;
        }

        String p(String k) {
            String x = s.props().get(k);
            if (x == null) x = s.clicked().get(k);
            return x == null ? "" : x;
        }

        int num(String k, int dflt) {
            try {
                return Integer.parseInt(p(k));
            } catch (NumberFormatException e) {
                return dflt;
            }
        }

        boolean sneakFor(Dir site) {
            return s.kind() == StateKind.CHEST || v.interactive(site);
        }

        void add(Dir site, Dir face, double[] h, LookWindow look, boolean sneak, int extraPlaces, int useClicks, boolean deferUse, String note) {
            out.add(new PlaceRecipe(site, face, h[0], h[1], h[2], look, sneak, extraPlaces, useClicks, deferUse, note));
        }

        /** The clickable neighbour directions, in preference order, that pass the filter. */
        List<Dir> sites(Predicate<Dir> filter) {
            List<Dir> r = new ArrayList<>();
            for (Dir d : SITE_ORDER) if (filter.test(d) && v.clickable(d)) r.add(d);
            return r;
        }

        /** One neighbour click per site with a fixed look, hit offset and extra clicks. */
        void clickEach(Predicate<Dir> filter, double[] centre, LookWindow look, int extraPlaces, int useClicks, boolean deferUse, String note) {
            for (Dir d : sites(filter)) {
                add(d, d.opposite(), hit(d, centre[0], centre[1]), look, sneakFor(d), extraPlaces, useClicks, deferUse, note);
            }
        }

        void airplace(Dir face, double[] centre, LookWindow look, int extraPlaces, int useClicks, boolean deferUse, String note) {
            if (air) add(null, face, hit(face, centre[0], centre[1]), look, s.kind() == StateKind.CHEST, extraPlaces, useClicks, deferUse, note + " (air)");
        }

        void run() {
            Dir f = s.facing();
            if (f == null && needsFacing(s.kind())) return;
            switch (s.kind()) {
                case FULL -> {
                    clickEach(d -> true, CENTER, LookWindow.any(), 0, 0, false, "any face");
                    airplace(Dir.UP, CENTER, LookWindow.any(), 0, 0, false, "any face");
                }
                case SUPPORTED_FLOOR -> {
                    clickEach(d -> true, CENTER, LookWindow.any(), 0, 0, false, "needs a block below");
                    airplace(Dir.UP, CENTER, LookWindow.any(), 0, 0, false, "needs a block below");
                }
                case AXIS -> {
                    char ax = p("axis").isEmpty() ? 'y' : p("axis").charAt(0);
                    clickEach(d -> d.axis() == ax, CENTER, LookWindow.any(), 0, 0, false, "face on the " + ax + " axis");
                    Dir face = ax == 'x' ? Dir.EAST : ax == 'z' ? Dir.SOUTH : Dir.UP;
                    airplace(face, CENTER, LookWindow.any(), 0, 0, false, "face on the " + ax + " axis");
                }
                case HFACING -> facingByLook(quadrant(f.opposite()), "facing = look.opposite");
                case HFACING_LOOK -> facingByLook(quadrant(f), "facing = look");
                case HFACING_CW -> facingByLook(quadrant(f.counterClockwise()), "facing = look.clockwise");
                case FACING6_LOOK -> facingByLook(LookWindow.lookCone(f.opposite()), "facing = looking direction opposite");
                case FACING6_LOOK_SAME -> facingByLook(LookWindow.lookCone(f), "facing = looking direction");
                case FACING6_FACE -> {
                    clickEach(d -> d == f.opposite(), CENTER, LookWindow.any(), 0, 0, false, "facing = clicked face");
                    airplace(f, CENTER, LookWindow.any(), 0, 0, false, "facing = clicked face");
                }
                case HOPPER -> hopper(f);
                case STAIRS -> halves(f != null ? quadrant(f) : LookWindow.any(), "top".equals(p("half")), 0, "stairs");
                case SLAB -> {
                    String t = p("type").isEmpty() ? "bottom" : p("type");
                    halves(LookWindow.any(), "top".equals(t), "double".equals(t) ? 1 : 0, "slab");
                }
                case TRAPDOOR -> trapdoor(f);
                case DOOR -> door(f);
                case BED -> {
                    LookWindow w = quadrant(f);
                    clickEach(d -> true, CENTER, w, 0, 0, false, "bed faces the look");
                    airplace(Dir.UP, CENTER, w, 0, 0, false, "bed faces the look");
                }
                case FENCE_GATE -> {
                    LookWindow w = quadrant(f);
                    int open = "true".equals(p("open")) ? 1 : 0;
                    clickEach(d -> true, CENTER, w, 0, open, false, "gate faces the look");
                    airplace(Dir.UP, CENTER, w, 0, open, false, "gate faces the look");
                }
                case ATTACH_FACE -> attachFace(f);
                case WALL_OR_STANDING -> {
                    Dir support = f.opposite();
                    clickEach(d -> d == support, CENTER, LookWindow.any(), 0, 0, false, "facing = clicked face");
                    airplace(f, CENTER, LookWindow.lookCone(support), 0, 0, false, "look at the support");
                }
                case ROTATION16 -> {
                    double off = skullLike(s.blockId()) ? 0 : 180;
                    LookWindow w = LookWindow.rotation16(num("rotation", 0), off);
                    clickEach(d -> true, CENTER, w, 0, 0, false, "rotation from the yaw");
                    airplace(Dir.UP, CENTER, w, 0, 0, false, "rotation from the yaw");
                }
                case HANGING -> {
                    boolean hanging = "true".equals(p("hanging"));
                    Dir support = hanging ? Dir.UP : Dir.DOWN;
                    clickEach(d -> d == support, CENTER, LookWindow.any(), 0, 0, false, hanging ? "hangs from above" : "stands on the block below");
                    airplace(hanging ? Dir.DOWN : Dir.UP, CENTER, LookWindow.lookCone(support), 0, 0, false, "look at the support");
                }
                case REPEATER -> {
                    LookWindow w = quadrant(f.opposite());
                    int delay = Math.max(0, num("delay", 1) - 1);
                    clickEach(d -> true, CENTER, w, 0, delay, false, "facing = look.opposite, delay by clicks");
                    airplace(Dir.UP, CENTER, w, 0, delay, false, "facing = look.opposite, delay by clicks");
                }
                case COMPARATOR -> {
                    LookWindow w = quadrant(f.opposite());
                    int mode = "subtract".equals(p("mode")) ? 1 : 0;
                    clickEach(d -> true, CENTER, w, 0, mode, false, "facing = look.opposite, mode by click");
                    airplace(Dir.UP, CENTER, w, 0, mode, false, "facing = look.opposite, mode by click");
                }
                case NOTE -> {
                    int n = Math.max(0, num("note", 0));
                    clickEach(d -> true, CENTER, LookWindow.any(), 0, n, n > 0, "note by clicks after the block below exists");
                    airplace(Dir.UP, CENTER, LookWindow.any(), 0, n, n > 0, "note by clicks");
                }
                case DAYLIGHT -> {
                    int inv = "true".equals(p("inverted")) ? 1 : 0;
                    clickEach(d -> true, CENTER, LookWindow.any(), 0, inv, false, "inverted by a click");
                    airplace(Dir.UP, CENTER, LookWindow.any(), 0, inv, false, "inverted by a click");
                }
                case COUNT -> {
                    int count = Math.max(1, Math.max(Math.max(num("candles", 1), num("pickles", 1)), Math.max(num("eggs", 1), num("layers", 1))));
                    clickEach(d -> d == Dir.DOWN, CENTER, LookWindow.any(), count - 1, 0, false, "count by repeated placement");
                    airplace(Dir.UP, CENTER, LookWindow.any(), count - 1, 0, false, "count by repeated placement");
                }
                case BELL -> bell(f);
                case CHEST -> {
                    LookWindow w = quadrant(f.opposite());
                    clickEach(d -> true, CENTER, w, 0, 0, false, "facing = look.opposite, sneak so it stays single");
                    airplace(Dir.UP, CENTER, w, 0, 0, false, "facing = look.opposite, sneak so it stays single");
                }
                default -> {
                }
            }
        }

        void facingByLook(LookWindow w, String note) {
            clickEach(d -> true, CENTER, w, 0, 0, false, note);
            airplace(Dir.UP, CENTER, w, 0, 0, false, note);
        }

        void hopper(Dir f) {
            if (f == Dir.DOWN) {
                clickEach(d -> d.vertical(), CENTER, LookWindow.any(), 0, 0, false, "vertical click gives down");
                airplace(Dir.UP, CENTER, LookWindow.any(), 0, 0, false, "vertical click gives down");
            } else {
                clickEach(d -> d == f, CENTER, LookWindow.any(), 0, 0, false, "facing the clicked block");
                airplace(f.opposite(), CENTER, LookWindow.any(), 0, 0, false, "facing = clicked face opposite");
            }
        }

        /** Stairs and slabs: half from the face (down face = top, up face = bottom) or the click height on a side face. */
        void halves(LookWindow look, boolean top, int extraPlaces, String what) {
            Dir vertical = top ? Dir.UP : Dir.DOWN; // the neighbour on the matching vertical side
            for (Dir d : sites(x -> true)) {
                if (d.vertical()) {
                    if (d != vertical) continue;
                    add(d, d.opposite(), hit(d, 0.5, 0.5), look, sneakFor(d), extraPlaces, 0, false, what + (top ? " top from the bottom face of the block above" : " bottom from the top face of the block below"));
                } else {
                    add(d, d.opposite(), hitAtY(d, top ? 0.75 : 0.25), look, sneakFor(d), extraPlaces, 0, false, what + " half from the click height");
                }
            }
            if (air) {
                Dir face = top ? Dir.DOWN : Dir.UP;
                add(null, face, hit(face, 0.5, 0.5), look, false, extraPlaces, 0, false, what + " half from the face (air)");
            }
        }

        void trapdoor(Dir f) {
            boolean top = "top".equals(p("half"));
            int open = "true".equals(p("open")) ? 1 : 0;
            // side click: facing = the face that was clicked, so the clicked block sits behind the trapdoor
            Dir behind = f.opposite();
            for (Dir d : sites(x -> x == behind)) {
                add(d, d.opposite(), hitAtY(d, top ? 0.75 : 0.25), LookWindow.any(), sneakFor(d), 0, open, false, "side click: facing = face, half = click height");
            }
            // floor/ceiling click (and any air-place): facing = H.opposite, half from the face
            LookWindow w = quadrant(f.opposite());
            Dir vertical = top ? Dir.UP : Dir.DOWN;
            for (Dir d : sites(x -> x == vertical)) {
                add(d, d.opposite(), hit(d, 0.5, 0.5), w, sneakFor(d), 0, open, false, "vertical click: facing = look.opposite");
            }
            if (air) {
                Dir face = top ? Dir.DOWN : Dir.UP;
                add(null, face, hit(face, 0.5, 0.5), w, false, 0, open, false, "facing = look.opposite (air)");
            }
        }

        void door(Dir f) {
            LookWindow w = quadrant(f);
            int open = "true".equals(p("open")) ? 1 : 0;
            String hinge = p("hinge").isEmpty() ? "left" : p("hinge");
            double[] bands = { 0.25, 0.75 };
            boolean downOk = v.clickable(Dir.DOWN);
            for (double x : bands) {
                for (double z : bands) {
                    if (!hingeByClick(f, x, z).equals(hinge)) continue;
                    if (downOk) add(Dir.DOWN, Dir.UP, hit(Dir.DOWN, x, z), w, sneakFor(Dir.DOWN), 0, open, false, "door: hinge " + hinge + " from the click offset");
                    if (air) add(null, Dir.UP, hit(Dir.UP, x, z), w, false, 0, open, false, "door: hinge " + hinge + " (air)");
                }
            }
        }

        void attachFace(Dir f) {
            String face = p("face");
            switch (face) {
                case "floor", "ceiling" -> {
                    boolean ceiling = face.equals("ceiling");
                    Dir support = ceiling ? Dir.UP : Dir.DOWN;
                    LookWindow w = quadrant(f);
                    clickEach(d -> d == support, CENTER, w, 0, 0, false, "facing = look");
                    if (air) {
                        LookWindow a = w.intersect(LookWindow.lookCone(support));
                        if (a != null) add(null, ceiling ? Dir.DOWN : Dir.UP, hit(ceiling ? Dir.DOWN : Dir.UP, 0.5, 0.5), a, false, 0, 0, false, "facing = look, looking at the support (air)");
                    }
                }
                default -> {
                    Dir support = f.opposite();
                    clickEach(d -> d == support, CENTER, LookWindow.any(), 0, 0, false, "wall: facing = clicked face");
                    airplace(f, CENTER, LookWindow.lookCone(support), 0, 0, false, "wall: look at the support");
                }
            }
        }

        void bell(Dir f) {
            switch (p("attachment")) {
                case "floor", "ceiling" -> {
                    boolean ceiling = p("attachment").equals("ceiling");
                    Dir support = ceiling ? Dir.UP : Dir.DOWN;
                    LookWindow w = quadrant(f);
                    clickEach(d -> d == support, CENTER, w, 0, 0, false, "facing = look");
                    Dir face = ceiling ? Dir.DOWN : Dir.UP;
                    if (air) add(null, face, hit(face, 0.5, 0.5), w, false, 0, 0, false, "facing = look (air)");
                }
                default -> {
                    clickEach(d -> d == f, CENTER, LookWindow.any(), 0, 0, false, "wall: facing = the clicked block's direction");
                    airplace(f.opposite(), CENTER, LookWindow.any(), 0, 0, false, "wall: facing = clicked face opposite");
                }
            }
        }
    }

    private static boolean needsFacing(StateKind k) {
        return switch (k) {
            case HFACING, HFACING_LOOK, HFACING_CW, FACING6_LOOK, FACING6_LOOK_SAME, FACING6_FACE, HOPPER, TRAPDOOR, DOOR, BED, FENCE_GATE,
                ATTACH_FACE, WALL_OR_STANDING, REPEATER, COMPARATOR, CHEST, BELL -> true;
            default -> false;
        };
    }

    private static LookWindow quadrant(Dir h) {
        return h != null && h.horizontal() ? LookWindow.quadrant(h) : LookWindow.any();
    }
}

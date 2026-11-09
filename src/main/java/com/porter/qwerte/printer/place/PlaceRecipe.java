package com.porter.qwerte.printer.place;

/**
 * One way to place a block: which neighbour to click (or an air-place), where on the face, where to look, whether to
 * sneak and which extra clicks follow. Pure data, no game objects. Coordinates of the hit are offsets inside the
 * TARGET cell, in [0,1]^3, on the plane shared with the clicked block.
 *
 * @param site         where the clicked block sits relative to the target cell, or null for an air-place (the click
 *                     goes on the target cell itself, which is air and therefore "replaced" by the placement)
 * @param face         the face of the clicked block that is hit (always {@code site.opposite()} for a neighbour
 *                     click); for an air-place, the face of the target cell that the click claims to hit
 * @param extraPlaces  more placement clicks with the same item on the freshly placed block (candles, pickles, eggs,
 *                     snow layers, the second half of a double slab); each one clicks the target cell itself
 * @param useClicks    empty-handed style use clicks on the placed block afterwards (repeater delay, comparator mode,
 *                     note, daylight sensor, wooden door/trapdoor/gate open); never sneaking
 * @param deferUse     the use clicks should wait until the block below the target exists (note blocks)
 * @param note         a short human reason, for logs
 */
public record PlaceRecipe(
    Dir site,
    Dir face,
    double hx,
    double hy,
    double hz,
    LookWindow look,
    boolean sneak,
    int extraPlaces,
    int useClicks,
    boolean deferUse,
    String note
) {
    public PlaceRecipe {
        if (face == null) throw new IllegalArgumentException("face");
        if (site != null && site.opposite() != face) throw new IllegalArgumentException("a neighbour click hits the face next to the target");
        if (look == null) look = LookWindow.any();
        if (!(hx >= 0 && hx <= 1 && hy >= 0 && hy <= 1 && hz >= 0 && hz <= 1)) throw new IllegalArgumentException("hit offset outside the cell");
        if (extraPlaces < 0 || useClicks < 0) throw new IllegalArgumentException("negative clicks");
        if (note == null) note = "";
    }

    public boolean airplace() {
        return site == null;
    }

    /** The block that gets clicked, for a target at (tx,ty,tz). */
    public int[] clickedBlock(int tx, int ty, int tz) {
        return site == null ? new int[] { tx, ty, tz } : new int[] { tx + site.dx, ty + site.dy, tz + site.dz };
    }

    /** The hit position in the world, for a target at (tx,ty,tz). */
    public double[] hitAt(int tx, int ty, int tz) {
        return new double[] { tx + hx, ty + hy, tz + hz };
    }

    public PlaceRecipe withLook(LookWindow w) {
        return new PlaceRecipe(site, face, hx, hy, hz, w, sneak, extraPlaces, useClicks, deferUse, note);
    }

    public PlaceRecipe withSneak(boolean s) {
        return new PlaceRecipe(site, face, hx, hy, hz, look, s, extraPlaces, useClicks, deferUse, note);
    }

    public PlaceRecipe withClicks(int extraPlaces, int useClicks, boolean deferUse) {
        return new PlaceRecipe(site, face, hx, hy, hz, look, sneak, extraPlaces, useClicks, deferUse, note);
    }

    /** Minecraft's six directions, ordered like Direction.get3DDataValue (down, up, north, south, west, east). */
    public enum Dir {
        DOWN(0, -1, 0),
        UP(0, 1, 0),
        NORTH(0, 0, -1),
        SOUTH(0, 0, 1),
        WEST(-1, 0, 0),
        EAST(1, 0, 0);

        public final int dx, dy, dz;

        Dir(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        public static final Dir[] HORIZONTALS = { SOUTH, WEST, NORTH, EAST }; // Direction.get2DDataValue order

        public Dir opposite() {
            return switch (this) {
                case DOWN -> UP;
                case UP -> DOWN;
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case WEST -> EAST;
                case EAST -> WEST;
            };
        }

        public boolean horizontal() {
            return dy == 0;
        }

        public boolean vertical() {
            return dy != 0;
        }

        /** 'x', 'y' or 'z'. */
        public char axis() {
            return dx != 0 ? 'x' : dy != 0 ? 'y' : 'z';
        }

        /** Direction.getClockWise: north to east to south to west, seen from above. Horizontal only. */
        public Dir clockwise() {
            return switch (this) {
                case NORTH -> EAST;
                case EAST -> SOUTH;
                case SOUTH -> WEST;
                case WEST -> NORTH;
                default -> throw new IllegalStateException("not horizontal: " + this);
            };
        }

        public Dir counterClockwise() {
            return clockwise().opposite();
        }

        /** The yaw that makes the player face this horizontal direction: south 0, west 90, north 180, east -90. */
        public double yawCenter() {
            return switch (this) {
                case SOUTH -> 0;
                case WEST -> 90;
                case NORTH -> 180;
                case EAST -> -90;
                default -> throw new IllegalStateException("not horizontal: " + this);
            };
        }

        /** Direction.fromYRot: the horizontal direction a player with this yaw faces (getHorizontalDirection). */
        public static Dir fromYaw(double yaw) {
            return HORIZONTALS[((int) Math.floor(yaw / 90.0 + 0.5)) & 3];
        }

        public static Dir byName(String name) {
            if (name == null) return null;
            for (Dir d : values()) if (d.name().equalsIgnoreCase(name)) return d;
            return null;
        }

        public String lower() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}

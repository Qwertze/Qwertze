package com.porter.qwerte.printer.schematic;

/**
 * Where a region's cells land in the world, as Litematica places them (derived from the bytecode of
 * SchematicPlacingUtils.placeBlocksWithinChunk, checked against PositionUtils at run time by LitematicaBridge). Pure
 * (no Minecraft classes); the enum constants carry the names of Minecraft's Rotation and Mirror so they convert by name.
 *
 * <pre>
 * T(p, mirror, rotation): LEFT_RIGHT negates z, FRONT_BACK negates x, then the rotation (CLOCKWISE_90: (x,y,z) -> (-z,y,x))
 * world = origin + T(posMinRel, mainMirror, mainRotation) + T(T(cell, mainMirror, mainRotation), subMirror, subRotation)
 * </pre>
 *
 * There is no pivot: cells are relative to the region's smallest corner, and the corner itself is moved by the main
 * placement's transform. Block states are mirrored by the main mirror, then by the sub-region mirror (its two axes
 * swapped when the main rotation is a quarter turn), then rotated by the main rotation plus the sub-region rotation.
 */
public final class Transform {
    public enum Rot {
        NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90;

        /** This turn followed by {@code other}. */
        public Rot plus(Rot other) {
            return values()[(ordinal() + other.ordinal()) & 3];
        }

        public Rot inverse() {
            return values()[(4 - ordinal()) & 3];
        }

        public boolean quarter() {
            return this == CLOCKWISE_90 || this == COUNTERCLOCKWISE_90;
        }
    }

    public enum Mir {
        NONE, LEFT_RIGHT, FRONT_BACK;

        public Mir swapped() {
            return this == LEFT_RIGHT ? FRONT_BACK : this == FRONT_BACK ? LEFT_RIGHT : NONE;
        }
    }

    public final int originX, originY, originZ;
    public final Rot mainRot;
    public final Mir mainMir;
    public final Rot subRot;
    public final Mir subMir;
    private final int regionX, regionY, regionZ;

    /**
     * @param minRelX the region's smallest corner relative to the origin, untransformed
     *                (see {@link SchematicData.Region#minRelX()})
     */
    public Transform(int originX, int originY, int originZ, Rot mainRot, Mir mainMir, Rot subRot, Mir subMir, int minRelX, int minRelY, int minRelZ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.mainRot = mainRot;
        this.mainMir = mainMir;
        this.subRot = subRot;
        this.subMir = subMir;
        int[] p = apply(minRelX, minRelY, minRelZ, mainMir, mainRot);
        this.regionX = p[0];
        this.regionY = p[1];
        this.regionZ = p[2];
    }

    /** A region of a file placed directly: no sub-region rotation or mirror. */
    public static Transform of(SchematicData.Region r, int originX, int originY, int originZ, Rot rot, Mir mir) {
        return new Transform(originX, originY, originZ, rot, mir, Rot.NONE, Mir.NONE, r.minRelX(), r.minRelY(), r.minRelZ());
    }

    /** Mirror, then rotate. */
    public static int[] apply(int x, int y, int z, Mir mir, Rot rot) {
        if (mir == Mir.LEFT_RIGHT) z = -z;
        else if (mir == Mir.FRONT_BACK) x = -x;
        return switch (rot) {
            case NONE -> new int[] { x, y, z };
            case CLOCKWISE_90 -> new int[] { -z, y, x };
            case CLOCKWISE_180 -> new int[] { -x, y, -z };
            case COUNTERCLOCKWISE_90 -> new int[] { z, y, -x };
        };
    }

    /** The inverse of {@link #apply}: undo the rotation, then the mirror (a mirror is its own inverse). */
    public static int[] unapply(int x, int y, int z, Mir mir, Rot rot) {
        int[] p = apply(x, y, z, Mir.NONE, rot.inverse());
        return apply(p[0], p[1], p[2], mir, Rot.NONE);
    }

    /** The world position of a cell (0 to abs size - 1 on each axis, counted from the region's smallest corner). */
    public int[] toWorld(int cx, int cy, int cz) {
        int[] a = apply(cx, cy, cz, mainMir, mainRot);
        int[] b = apply(a[0], a[1], a[2], subMir, subRot);
        return new int[] { originX + regionX + b[0], originY + regionY + b[1], originZ + regionZ + b[2] };
    }

    /** The cell a world position holds, without a bounds check (the caller compares it with the region's size). */
    public int[] toLocal(int wx, int wy, int wz) {
        int[] b = unapply(wx - originX - regionX, wy - originY - regionY, wz - originZ - regionZ, subMir, subRot);
        return unapply(b[0], b[1], b[2], mainMir, mainRot);
    }

    /** The mirrors to apply to a block state, in order: the main one, then the sub-region's (axes swapped under a quarter turn). */
    public Mir[] stateMirrors() {
        return new Mir[] { mainMir, mainRot.quarter() ? subMir.swapped() : subMir };
    }

    /** The rotation to apply to a block state, after the mirrors. */
    public Rot stateRotation() {
        return mainRot.plus(subRot);
    }
}

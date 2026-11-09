package com.porter.qwerte.printer.place;

import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Stripped logs and the like (a block with an axis that is the result of using an axe on another block). Pure: blocks are
 * ids, and the strip table (vanilla's {@code AxeItem.STRIPPABLES}, reversed) is passed in.
 *
 * <p>The unstripped block is placed with the target's axis (the axis carries over when stripping), then an axe is used
 * on it. A world block that is the unstripped block of the wanted one, with the wanted axis, is "right block, needs
 * strip", not a wrong block.
 */
public final class StripRules {
    private StripRules() {}

    public enum WorldState {
        /** The same block as wanted (its other properties are compared by the usual check). */
        SAME_BLOCK,
        /** The unstripped block with the wanted axis: only the axe is missing. */
        NEEDS_STRIP,
        /** Anything else. */
        OTHER
    }

    /**
     * @param rawOf the block that strips into the given one (null when none does)
     */
    public static WorldState classify(String wantId, String wantAxis, String haveId, String haveAxis, UnaryOperator<String> rawOf) {
        if (Objects.equals(wantId, haveId)) return WorldState.SAME_BLOCK;
        if (wantAxis == null || haveAxis == null || !wantAxis.equals(haveAxis)) return WorldState.OTHER;
        String raw = rawOf.apply(wantId);
        return raw != null && raw.equals(haveId) ? WorldState.NEEDS_STRIP : WorldState.OTHER;
    }

    public enum Choice {
        /** Place the stripped item itself (preferred). */
        STRIPPED,
        /** Place the unstripped item, then strip it with the axe. */
        RAW_THEN_STRIP,
        /** Place the unstripped item and keep it pending until an axe turns up. */
        RAW_NO_AXE,
        /** Neither item is carried. */
        NONE
    }

    public static Choice choose(boolean hasStripped, boolean hasRaw, boolean hasAxe) {
        if (hasStripped) return Choice.STRIPPED;
        if (hasRaw) return hasAxe ? Choice.RAW_THEN_STRIP : Choice.RAW_NO_AXE;
        return Choice.NONE;
    }

    /**
     * The face of the block at (bx, by, bz) to click with the axe: the side that faces the eye most. Ties go to the
     * vertical axis, then x, then z, so there is always one answer.
     */
    public static Dir faceToward(double ex, double ey, double ez, int bx, int by, int bz) {
        double dx = ex - (bx + 0.5), dy = ey - (by + 0.5), dz = ez - (bz + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy >= 0 ? Dir.UP : Dir.DOWN;
        if (ax >= az) return dx >= 0 ? Dir.EAST : Dir.WEST;
        return dz >= 0 ? Dir.SOUTH : Dir.NORTH;
    }
}

package com.porter.qwerte.printer.source;

/**
 * Litematica's render-layer range as a pure test, mirroring malilib's {@code LayerRange.isPositionWithinRange} (read from
 * its bytecode, malilib 0.23.5 and 0.27.20): ALL accepts everything; SINGLE_LAYER accepts a coordinate equal to the single
 * layer; ALL_ABOVE a coordinate at or above the "above" value; ALL_BELOW at or below the "below" value; LAYER_RANGE a
 * coordinate from min to max, both included. The coordinate is the one on the range's axis (x, y or z).
 */
public record LayerFilter(Mode mode, char axis, int single, int above, int below, int min, int max) {
    public enum Mode { ALL, SINGLE_LAYER, ALL_ABOVE, ALL_BELOW, LAYER_RANGE }

    /** No filter. */
    public static final LayerFilter ALL = new LayerFilter(Mode.ALL, 'y', 0, 0, 0, 0, 0);

    public boolean active() {
        return mode != Mode.ALL;
    }

    public boolean contains(int x, int y, int z) {
        if (mode == Mode.ALL) return true;
        int v = switch (axis) {
            case 'x' -> x;
            case 'z' -> z;
            default -> y;
        };
        return switch (mode) {
            case ALL -> true;
            case SINGLE_LAYER -> v == single;
            case ALL_ABOVE -> v >= above;
            case ALL_BELOW -> v <= below;
            case LAYER_RANGE -> v >= min && v <= max;
        };
    }

    /**
     * From the values Litematica reports (LayerMode name: ALL, SINGLE_LAYER, LAYER_RANGE, ALL_BELOW, ALL_ABOVE; axis ordinal
     * 0, 1, 2 for x, y, z). Anything unknown means no filter.
     */
    public static LayerFilter of(String modeName, int axisOrdinal, int single, int above, int below, int min, int max) {
        if (modeName == null) return ALL;
        Mode m;
        try {
            m = Mode.valueOf(modeName);
        } catch (IllegalArgumentException e) {
            return ALL;
        }
        char axis = axisOrdinal == 0 ? 'x' : axisOrdinal == 2 ? 'z' : 'y';
        return m == Mode.ALL ? ALL : new LayerFilter(m, axis, single, above, below, min, max);
    }
}

package com.porter.qwerte.printer.place;

import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import com.porter.qwerte.printer.place.PropertyRules.Action;
import com.porter.qwerte.printer.place.PropertyRules.Rule;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A block state reduced to what placing it needs: the block id, how it is placed, and which properties are matched,
 * clicked into place, listed on mismatch or ignored. Pure (strings only), so it works as a cache key and in tests.
 *
 * @param props   every property of the state, name to value name, sorted
 * @param matched properties the placement decides (the simulator compares exactly these)
 * @param clicked properties reached by clicks after the first placement (checked in the world only)
 * @param listed  derived properties whose mismatch is reported
 * @param skipReason non-null exactly when {@code kind == SKIP}
 */
public record StateSpec(
    String blockId,
    StateKind kind,
    SortedMap<String, String> props,
    SortedMap<String, String> matched,
    SortedMap<String, String> clicked,
    SortedMap<String, String> listed,
    boolean gravity,
    String skipReason
) {
    public StateSpec {
        if ((kind == StateKind.SKIP) != (skipReason != null)) throw new IllegalArgumentException("skipReason goes with SKIP");
    }

    private static SortedMap<String, String> frozen(Map<String, String> m) {
        return Collections.unmodifiableSortedMap(new TreeMap<>(m));
    }

    /** A spec that is not built, with the reason for the skipped list. */
    public static StateSpec skipped(String blockId, Map<String, String> props, String reason) {
        SortedMap<String, String> empty = frozen(Map.of());
        return new StateSpec(blockId, StateKind.SKIP, frozen(props), empty, empty, empty, false, reason);
    }

    /**
     * Applies {@link PropertyRules} to every property. One skip rule makes the whole spec a skip; a PART rule makes it
     * a PART (placed with its partner).
     */
    public static StateSpec of(String blockId, StateKind kind, Map<String, String> props, boolean gravity) {
        if (kind == StateKind.SKIP) throw new IllegalArgumentException("use skipped()");
        TreeMap<String, String> matched = new TreeMap<>(), clicked = new TreeMap<>(), listed = new TreeMap<>();
        boolean part = false;
        for (Map.Entry<String, String> e : new TreeMap<>(props).entrySet()) {
            Rule r = PropertyRules.rule(kind, blockId, e.getKey(), e.getValue());
            switch (r.action()) {
                case SKIP:
                    return skipped(blockId, props, r.reason());
                case MATCH:
                    matched.put(e.getKey(), e.getValue());
                    break;
                case CLICK:
                    clicked.put(e.getKey(), e.getValue());
                    break;
                case LISTED:
                    listed.put(e.getKey(), e.getValue());
                    break;
                case PART:
                    part = true;
                    break;
                default:
                    break;
            }
        }
        StateKind k = part ? StateKind.PART : kind;
        return new StateSpec(blockId, k, frozen(props), frozen(matched), frozen(clicked), frozen(listed), gravity && !part, null);
    }

    public boolean skipped() {
        return kind == StateKind.SKIP;
    }

    public boolean part() {
        return kind == StateKind.PART;
    }

    /** True when the printer places this state itself. */
    public boolean placeable() {
        return kind != StateKind.SKIP && kind != StateKind.PART;
    }

    public Dir facing() {
        return Dir.byName(props.get("facing"));
    }

    /** Where the block must find a solid block to stay up (as an offset from its own cell), or null. */
    public Dir supportDir() {
        Dir f = facing();
        switch (kind) {
            case SUPPORTED_FLOOR, ROTATION16, REPEATER, COMPARATOR, COUNT, DOOR:
                return Dir.DOWN;
            case WALL_OR_STANDING:
                return f != null && f.horizontal() ? f.opposite() : null;
            case ATTACH_FACE: {
                String face = props.get("face");
                if ("floor".equals(face)) return Dir.DOWN;
                if ("ceiling".equals(face)) return Dir.UP;
                return f != null && f.horizontal() ? f.opposite() : null;
            }
            case HANGING:
                return "true".equals(props.get("hanging")) ? Dir.UP : Dir.DOWN;
            case BELL: {
                String a = props.get("attachment");
                if ("floor".equals(a)) return Dir.DOWN;
                if ("ceiling".equals(a)) return Dir.UP;
                return f != null && f.horizontal() ? f : null;
            }
            default:
                return null;
        }
    }

    /** The other cell of a two-part block, as an offset from the placed cell, or null. */
    public Dir partnerDir() {
        switch (kind) {
            case DOOR:
                return Dir.UP;
            case BED:
                Dir f = facing();
                return f != null && f.horizontal() ? f : null;
            case SUPPORTED_FLOOR:
                return "lower".equals(props.get("half")) ? Dir.UP : null;
            default:
                return null;
        }
    }

    public BuildOrderer.Role role() {
        if (gravity) return BuildOrderer.Role.GRAVITY;
        return supportDir() != null ? BuildOrderer.Role.ATTACHABLE : BuildOrderer.Role.SOLID;
    }

    /** A one-line description for logs and the skipped list. */
    public String describe() {
        StringBuilder b = new StringBuilder(blockId);
        if (!props.isEmpty()) {
            b.append('[');
            boolean first = true;
            for (Map.Entry<String, String> e : props.entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append(e.getKey()).append('=').append(e.getValue());
            }
            b.append(']');
        }
        return b.toString();
    }
}

package com.porter.qwerte.printer.place;

/**
 * What to do with each block state property, per {@link StateKind}. Pure.
 *
 * <ul>
 *   <li>MATCH: the placement itself decides it; the simulator compares it and the world check after placing does too.</li>
 *   <li>CLICK: reached by clicks after the first placement (use clicks, repeated placement); the simulator ignores it
 *       and only the world check compares it.</li>
 *   <li>DERIVED: follows from the neighbours or the world (stairs shape, fence connections, power, ...); ignored.</li>
 *   <li>LISTED: derived, but a mismatch after placing is reported (rail shapes).</li>
 *   <li>PART: this state is the second cell of a two-part block; it is placed with its partner.</li>
 *   <li>SKIP: cannot be built; the reason goes on the skipped list.</li>
 * </ul>
 */
public final class PropertyRules {
    private PropertyRules() {}

    public enum Action { MATCH, CLICK, DERIVED, LISTED, PART, SKIP }

    public record Rule(Action action, String reason) {}

    private static final Rule MATCH = new Rule(Action.MATCH, "");
    private static final Rule CLICK = new Rule(Action.CLICK, "");
    private static final Rule PART = new Rule(Action.PART, "second cell of a two-part block, placed with its partner");

    private static Rule derived(String why) {
        return new Rule(Action.DERIVED, why);
    }

    private static Rule skip(String why) {
        return new Rule(Action.SKIP, why);
    }

    public static Rule rule(StateKind kind, String blockId, String prop, String value) {
        Rule k = byKind(kind, blockId, prop, value);
        if (k != null) return k;
        return universal(kind, prop, value);
    }

    private static boolean ironHand(String blockId) {
        return blockId.contains("iron_");
    }

    private static Rule openRule(String blockId, String value) {
        if (ironHand(blockId) && "true".equals(value)) return skip("iron doors and trapdoors cannot be opened by hand");
        return ironHand(blockId) ? derived("iron stays closed") : CLICK;
    }

    private static Rule horizontalFacing(String value) {
        return switch (value) {
            case "north", "south", "east", "west" -> MATCH;
            default -> skip("facing " + value + " is not a horizontal facing");
        };
    }

    private static Rule byKind(StateKind kind, String id, String prop, String value) {
        switch (kind) {
            case AXIS:
                if (prop.equals("axis")) return MATCH;
                break;
            case HFACING, HFACING_LOOK, HFACING_CW:
                if (prop.equals("facing")) return horizontalFacing(value);
                break;
            case FACING6_LOOK, FACING6_LOOK_SAME, FACING6_FACE:
                if (prop.equals("facing")) return MATCH;
                if (prop.equals("extended")) return "true".equals(value) ? skip("an extended piston needs its head and power") : derived("pistons are placed retracted");
                break;
            case HOPPER:
                if (prop.equals("facing")) return value.equals("up") ? skip("hoppers cannot face up") : MATCH;
                break;
            case STAIRS:
                if (prop.equals("facing") || prop.equals("half")) return MATCH;
                if (prop.equals("shape")) return derived("stair shape follows the neighbours");
                break;
            case SLAB:
                if (prop.equals("type")) return value.equals("double") ? CLICK : MATCH;
                break;
            case TRAPDOOR:
                if (prop.equals("facing") || prop.equals("half")) return MATCH;
                if (prop.equals("open")) return openRule(id, value);
                break;
            case DOOR:
                if (prop.equals("facing") || prop.equals("hinge")) return MATCH;
                if (prop.equals("half")) return value.equals("upper") ? PART : derived("lower half is the placed one");
                if (prop.equals("open")) return openRule(id, value);
                break;
            case BED:
                if (prop.equals("facing")) return MATCH;
                if (prop.equals("part")) return value.equals("head") ? PART : derived("foot is the placed one");
                break;
            case FENCE_GATE:
                if (prop.equals("facing")) return MATCH;
                if (prop.equals("open")) return CLICK;
                break;
            case ATTACH_FACE:
                if (prop.equals("face") || prop.equals("facing")) return MATCH;
                break;
            case WALL_OR_STANDING, WALL_HANGING, COCOA:
                if (prop.equals("facing")) return MATCH;
                break;
            case ROTATION16:
                if (prop.equals("rotation")) return MATCH;
                break;
            case HANGING:
                if (prop.equals("hanging")) return MATCH;
                break;
            case REPEATER:
                if (prop.equals("facing")) return MATCH;
                if (prop.equals("delay")) return CLICK;
                break;
            case COMPARATOR:
                if (prop.equals("facing")) return MATCH;
                if (prop.equals("mode")) return CLICK;
                break;
            case NOTE:
                if (prop.equals("note")) return CLICK;
                break;
            case DAYLIGHT:
                if (prop.equals("inverted")) return CLICK;
                break;
            case COUNT:
                if (prop.equals("candles") || prop.equals("pickles") || prop.equals("eggs") || prop.equals("layers")) return CLICK;
                break;
            case BELL:
                if (prop.equals("attachment") || prop.equals("facing")) return MATCH;
                break;
            case CHEST:
                if (prop.equals("facing")) return horizontalFacing(value);
                if (prop.equals("type")) return value.equals("single") ? MATCH : skip("double chest halves are not supported: place the chests by hand");
                break;
            case SUPPORTED_FLOOR:
                if (prop.equals("half")) return value.equals("upper") ? PART : derived("lower half is the placed one");
                if (prop.equals("shape")) return new Rule(Action.LISTED, "rail shape follows the neighbours");
                break;
            default:
                break;
        }
        return null;
    }

    private static Rule universal(StateKind kind, String prop, String value) {
        switch (prop) {
            case "waterlogged":
                // Placed dry, then given water with a water bucket when the Printer has one (see WaterlogRules)
                return value.equals("true") ? CLICK : derived("waterlogged=false follows the fluid");
            case "eye":
                return value.equals("true") ? skip("an end portal frame eye is placed by hand") : derived("empty frame");
            case "bites":
                return value.equals("0") ? derived("whole cake") : skip("cake bites cannot be built");
            case "powered", "triggered", "enabled", "lit", "power", "distance", "persistent", "snowy", "age", "attached", "in_wall",
                "instrument", "occupied", "locked", "has_book", "has_record", "honey_level", "signal_fire", "cracked", "hatch",
                "bottom", "unstable", "stage", "berries", "phase", "bloom", "can_summon", "shrieking", "open", "disarmed", "moisture":
                return derived(prop + " follows the world or redstone");
            case "north", "east", "south", "west", "up", "down":
                if (kind == StateKind.FULL || kind == StateKind.SUPPORTED_FLOOR) return derived("connections follow the neighbours");
                break;
            default:
                break;
        }
        if (prop.startsWith("slot_") || prop.startsWith("has_bottle_")) return derived(prop + " is block entity data");
        return skip("unsupported property " + prop + "=" + value);
    }
}

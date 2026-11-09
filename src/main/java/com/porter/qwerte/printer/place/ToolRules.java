package com.porter.qwerte.printer.place;

import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Blocks that a tool finishes after they are placed: stripped logs (axe), dirt paths (shovel), farmland (hoe), waxed
 * copper (honeycomb), carved pumpkins (shears), lit candles (flint and steel), doused campfires (shovel) and potted
 * plants (the plant item on an empty flower pot). Pure: blocks are ids, and the table of what turns into what is passed in
 * (the game's own tables are read by {@code ToolTables}).
 *
 * <p>The raw block is placed first (with the properties the placement carries over, {@link StateSpec#matched()}), then the
 * tool is used on it. A world block that is a raw block of the wanted one is "right block, needs a tool", not a wrong
 * block.
 */
public final class ToolRules {
    private ToolRules() {}

    /** What turns into what. */
    public interface Table {
        /** The ways to make {@code wantId} with a tool (empty when none). */
        List<ToolAction> actionsFor(String wantId);
    }

    /** A table over a map from the wanted block id to its ways. */
    public static Table table(Map<String, List<ToolAction>> byWant) {
        return id -> byWant.getOrDefault(id, List.of());
    }

    /** Collects the entries of a {@link Table}. */
    public static final class Builder {
        private final Map<String, List<ToolAction>> map = new HashMap<>();

        public Builder add(Tool tool, String rawId, String wantId) {
            List<ToolAction> list = map.computeIfAbsent(wantId, k -> new ArrayList<>());
            ToolAction a = new ToolAction(tool, rawId);
            if (!list.contains(a)) list.add(a);
            return this;
        }

        public Table build() {
            Map<String, List<ToolAction>> out = new HashMap<>();
            for (Map.Entry<String, List<ToolAction>> e : map.entrySet()) {
                List<ToolAction> l = new ArrayList<>(e.getValue());
                l.sort(Comparator.comparing(ToolAction::rawId).thenComparing(ToolAction::tool));
                out.put(e.getKey(), List.copyOf(l));
            }
            return table(out);
        }
    }

    public enum WorldState {
        /** The same block as wanted (its other properties are compared by the usual check). */
        SAME,
        /** A raw block of the wanted one (or the wanted block with a property a tool sets): only the tool is missing. */
        NEEDS_TOOL,
        /** Anything else. */
        OTHER
    }

    /** @param action set exactly when the state is NEEDS_TOOL */
    public record Verdict(WorldState state, ToolAction action) {
        public static final Verdict SAME = new Verdict(WorldState.SAME, null);
        public static final Verdict OTHER = new Verdict(WorldState.OTHER, null);

        public boolean needsTool() {
            return state == WorldState.NEEDS_TOOL;
        }
    }

    /**
     * Compares what the schematic wants with what the world shows.
     *
     * @param want      the wanted state; its {@code matched} properties must carry over from the raw block (the axis of a log)
     * @param haveProps every property of the world block, name to value name
     */
    public static Verdict classify(StateSpec want, String haveId, Map<String, String> haveProps, Table table) {
        if (want.blockId().equals(haveId)) {
            Tool t = propertyTool(want.blockId(), want.props());
            if (t == null) return Verdict.SAME;
            String haveLit = haveProps.get("lit");
            if (haveLit == null || haveLit.equals(want.props().get("lit"))) return Verdict.SAME;
            // Flint and steel on a waterlogged candle would set the block next to it on fire: it is left alone
            if (t == Tool.FLINT_AND_STEEL && "true".equals(haveProps.get("waterlogged"))) return Verdict.SAME;
            return new Verdict(WorldState.NEEDS_TOOL, new ToolAction(t, haveId));
        }
        for (ToolAction a : table.actionsFor(want.blockId())) {
            if (!a.rawId().equals(haveId) || carries(a.tool()) && !carriesOver(want, haveProps)) continue;
            return new Verdict(WorldState.NEEDS_TOOL, a);
        }
        return Verdict.OTHER;
    }

    /**
     * The tools that keep the block's properties: stripping keeps the axis, waxing keeps every property. The others change
     * the block into one whose properties are their own (a carved pumpkin's facing comes from the click, farmland's moisture
     * from the game, a pot has none), so nothing has to match.
     */
    public static boolean carries(Tool tool) {
        return tool == Tool.AXE || tool == Tool.HONEYCOMB;
    }

    private static boolean carriesOver(StateSpec want, Map<String, String> haveProps) {
        for (Map.Entry<String, String> e : want.matched().entrySet()) {
            if (!e.getValue().equals(haveProps.get(e.getKey()))) return false;
        }
        return true;
    }

    /**
     * The tool that sets a property of a block the placement leaves the other way round: a lit candle is lit with flint and
     * steel; a campfire (placed lit) is doused with a shovel. Null when the state needs none. A waterlogged block is left
     * alone (it cannot be lit, and the water puts a campfire out).
     */
    public static Tool propertyTool(String blockId, Map<String, String> props) {
        String lit = props.get("lit");
        if (lit == null || "true".equals(props.get("waterlogged"))) return null;
        String path = blockId.substring(blockId.indexOf(':') + 1);
        if ("true".equals(lit) && (path.equals("candle") || path.endsWith("_candle"))) return Tool.FLINT_AND_STEEL;
        if ("false".equals(lit) && path.endsWith("campfire")) return Tool.SHOVEL;
        return null;
    }

    public enum Choice {
        /** Place the wanted block's own item (preferred). */
        TARGET,
        /** Place the raw block's item, then use the tool on it. */
        RAW_THEN_TOOL,
        /** Place the raw block's item and keep the job pending until the tool turns up. */
        RAW_NO_TOOL,
        /** Neither item is carried. */
        NONE
    }

    public static Choice choose(boolean hasTarget, boolean hasRaw, boolean hasTool) {
        if (hasTarget) return Choice.TARGET;
        if (hasRaw) return hasTool ? Choice.RAW_THEN_TOOL : Choice.RAW_NO_TOOL;
        return Choice.NONE;
    }

    /**
     * Whether the raw block may be placed before the tool is carried. Not for honeycomb: unwaxed copper left unwaxed ages,
     * so it is better to wait for the honeycomb than to place copper that turns into another block.
     */
    public static boolean placeWithoutTool(Tool tool) {
        return tool != Tool.HONEYCOMB;
    }

    /** The raw block to place when there is a choice: dirt if it is one of them, else the first by id. */
    public static ToolAction preferred(List<ToolAction> actions) {
        ToolAction best = null;
        for (ToolAction a : actions) {
            if (a.rawId().equals("minecraft:dirt")) return a;
            if (best == null || a.rawId().compareTo(best.rawId()) < 0) best = a;
        }
        return best;
    }

    /**
     * A block above holds the cell above it back: the shovel and the hoe work only with air above, and farmland under a
     * solid block turns back to dirt. Applies only while the tool is carried (without it the cell would wait for nothing).
     */
    public static boolean holdsAbove(ToolAction action, boolean toolAvailable) {
        return action != null && toolAvailable && action.needsAirAbove();
    }

    /**
     * The face of the block at (bx, by, bz) to click: the side that faces the eye most, never the bottom for a shovel or a
     * hoe, and for shears the side the carved face is to look at (PumpkinBlock: a click on a side makes the pumpkin face
     * that side; a click on top or bottom would use the player's look). Ties go to the vertical axis, then x, then z.
     *
     * @param wantedFacing the facing the carved pumpkin must have, or null
     */
    public static Dir clickFace(ToolAction action, Dir wantedFacing, double ex, double ey, double ez, int bx, int by, int bz) {
        if (action.tool() == Tool.SHEARS && wantedFacing != null && wantedFacing.horizontal()) return wantedFacing;
        Dir d = faceToward(ex, ey, ez, bx, by, bz);
        if (d == Dir.DOWN && action.neverDown()) {
            double dx = ex - (bx + 0.5), dz = ez - (bz + 0.5);
            if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Dir.EAST : Dir.WEST;
            return dz >= 0 ? Dir.SOUTH : Dir.NORTH;
        }
        return d;
    }

    public static Dir faceToward(double ex, double ey, double ez, int bx, int by, int bz) {
        double dx = ex - (bx + 0.5), dy = ey - (by + 0.5), dz = ez - (bz + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy >= 0 ? Dir.UP : Dir.DOWN;
        if (ax >= az) return dx >= 0 ? Dir.EAST : Dir.WEST;
        return dz >= 0 ? Dir.SOUTH : Dir.NORTH;
    }
}

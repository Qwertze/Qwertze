package com.porter.qwerte.printer.place;

/**
 * One way to turn a block that is there into the block that is wanted: use {@code tool} on {@code rawId}. For a plant
 * pot the raw block is the empty flower pot and the "tool" is the plant item. For a property that a tool sets (a lit
 * candle, a doused campfire) the raw block is the wanted block itself.
 */
public record ToolAction(Tool tool, String rawId) {
    /**
     * The vanilla code needs air above the block: ShovelItem flattens and HoeItem tills only with air above (and never from
     * the bottom face). A shovel that douses a campfire needs neither.
     */
    public boolean needsAirAbove() {
        return tool == Tool.HOE || tool == Tool.SHOVEL && !rawId.endsWith("campfire");
    }

    /** The bottom face is never clicked for these (vanilla passes on a click at the bottom). */
    public boolean neverDown() {
        return needsAirAbove();
    }
}

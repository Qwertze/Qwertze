package com.porter.qwerte.printer.place;

import java.util.Map;

/**
 * The parts of the tool tables that the game does not expose as plain data, written out by hand (checked against vanilla
 * in 1.21.4 and 1.21.11 with javap): {@code HoeItem.TILLABLES} holds opaque predicates and consumers, and the pumpkin carve
 * is code in {@code PumpkinBlock}. Pure (ids only).
 */
public final class HandTables {
    private HandTables() {}

    /**
     * HoeItem.TILLABLES: grass block, dirt path and dirt become farmland. (Coarse dirt and rooted dirt also become dirt, but that
     * is left out on purpose: it would make every plain dirt cell a tool job, so a wanted dirt cell that holds coarse or
     * rooted dirt is an ordinary wrong block.)
     */
    public static final Map<String, String[]> HOE = Map.of(
        "minecraft:farmland", new String[] { "minecraft:dirt", "minecraft:grass_block", "minecraft:dirt_path" });

    /** PumpkinBlock.useItemOn: shears on a pumpkin make a carved pumpkin. */
    public static final String PUMPKIN = "minecraft:pumpkin", CARVED_PUMPKIN = "minecraft:carved_pumpkin";

    /** Blocks that only exist as the result of a tool, mapped to the item they are made from. */
    public static final Map<String, String> PLACED_FROM = Map.of(
        "minecraft:farmland", "minecraft:dirt",
        "minecraft:dirt_path", "minecraft:dirt");

    /** The empty flower pot. */
    public static final String FLOWER_POT = "minecraft:flower_pot";

    /** The wanted blocks that have no item to place them with: the tool is always needed. */
    public static boolean toolOnly(String blockId) {
        return PLACED_FROM.containsKey(blockId);
    }

    /** Adds the hand-coded entries (hoe, shears) to a builder. */
    public static ToolRules.Builder addTo(ToolRules.Builder b) {
        for (Map.Entry<String, String[]> e : HOE.entrySet()) {
            for (String raw : e.getValue()) b.add(Tool.HOE, raw, e.getKey());
        }
        return b.add(Tool.SHEARS, PUMPKIN, CARVED_PUMPKIN);
    }
}

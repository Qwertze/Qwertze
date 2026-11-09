package com.porter.qwerte.printer.place;

import com.google.common.collect.BiMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The game's tables of what a tool turns into what, as a {@link ToolRules.Table}: strip ({@link StripTable}), flatten
 * ({@link ShovelTable}), wax ({@code HoneycombItem.WAXABLES}, a public supplier of a BiMap), the hoe and the shears
 * ({@link HandTables}) and the potted plants (every {@link FlowerPotBlock} with a content). Built on first use, when the
 * registries are frozen. Client thread.
 */
public final class ToolTables implements ToolRules.Table {
    public static final ToolTables INSTANCE = new ToolTables();

    private ToolRules.Table table;

    private ToolTables() {}

    @Override
    public List<ToolAction> actionsFor(String wantId) {
        return table().actionsFor(wantId);
    }

    private synchronized ToolRules.Table table() {
        if (table == null) table = build();
        return table;
    }

    private static ToolRules.Table build() {
        ToolRules.Builder b = new ToolRules.Builder();
        for (Map.Entry<Block, Block> e : StripTable.all().entrySet()) b.add(Tool.AXE, id(e.getKey()), id(e.getValue()));
        for (Map.Entry<Block, BlockState> e : ShovelTable.all().entrySet()) b.add(Tool.SHOVEL, id(e.getKey()), id(e.getValue().getBlock()));
        BiMap<Block, Block> wax = HoneycombItem.WAXABLES.get();
        for (Map.Entry<Block, Block> e : wax.entrySet()) b.add(Tool.HONEYCOMB, id(e.getKey()), id(e.getValue()));
        HandTables.addTo(b);
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof FlowerPotBlock pot && pot.getPotted() != Blocks.AIR) b.add(Tool.PLANT, HandTables.FLOWER_POT, id(block));
        }
        return b.build();
    }

    private static String id(Block b) {
        return BuiltInRegistries.BLOCK.getKey(b).toString();
    }

    /** The item that fills this pot, or null when the block is not a filled pot or its plant has no item. */
    public static Item plantOf(Block block) {
        if (!(block instanceof FlowerPotBlock pot) || pot.getPotted() == Blocks.AIR) return null;
        Item item = pot.getPotted().asItem();
        return item == Items.AIR ? null : item;
    }
}

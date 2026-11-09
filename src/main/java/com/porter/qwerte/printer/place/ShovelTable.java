package com.porter.qwerte.printer.place;

import java.util.Map;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Vanilla's flatten table, {@code ShovelItem.FLATTENABLES} (grass block, dirt, podzol, coarse dirt, mycelium and rooted dirt
 * to dirt path). The field is {@code protected static final} in 1.21.4 and 1.21.11, so it is reached from a subclass of
 * {@link ShovelItem} the way {@link StripTable} reaches the axe's table: no mixin, no reflection, no copied table. The
 * constructor is never called (same four parameters in both versions).
 */
public final class ShovelTable extends ShovelItem {
    private ShovelTable() {
        super(null, 0f, 0f, null);
    }

    /** The block to the state a shovel turns it into. */
    public static Map<Block, BlockState> all() {
        return FLATTENABLES;
    }
}

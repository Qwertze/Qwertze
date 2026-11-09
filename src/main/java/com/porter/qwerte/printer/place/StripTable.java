package com.porter.qwerte.printer.place;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.level.block.Block;

/**
 * Vanilla's strip table, {@code AxeItem.STRIPPABLES} (unstripped block to stripped block, with the axis carried over by
 * the game). The field is {@code protected static final} in 1.21.4 and 1.21.11, so it is reached from a subclass of
 * {@link AxeItem}, which Java allows for protected statics: no mixin, no reflection and no copy of the table that could
 * go out of date. The constructor is never called (same four parameters in both versions).
 */
public final class StripTable extends AxeItem {
    private StripTable() {
        super(null, 0f, 0f, null);
    }

    private static Map<Block, Block> reverse;

    /** The block an axe turns {@code raw} into, or null. */
    public static Block stripped(Block raw) {
        return STRIPPABLES.get(raw);
    }

    /** The block that turns into {@code stripped} when an axe is used on it, or null. */
    public static synchronized Block raw(Block stripped) {
        if (reverse == null) {
            Map<Block, Block> r = new HashMap<>();
            for (Map.Entry<Block, Block> e : STRIPPABLES.entrySet()) r.putIfAbsent(e.getValue(), e.getKey());
            reverse = r;
        }
        return reverse.get(stripped);
    }
}

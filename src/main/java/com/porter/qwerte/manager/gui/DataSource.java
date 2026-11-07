package com.porter.qwerte.manager.gui;

import com.porter.qwerte.manager.local.ContainerKey;
import com.porter.qwerte.manager.local.Where;
import com.porter.qwerte.manager.model.IndexModel;
import com.porter.qwerte.manager.model.StashRef;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Where the window's data comes from. The window picks one source for a whole session and never mixes two: the Stash
 * Manager server ({@link ServerSource}) or the stash the game itself has seen ({@link LocalSource}). Only the local
 * source reads the game world.
 */
public interface DataSource {
    /** The stashes to choose from. */
    List<StashRef> stashes();

    /** The index of the selected stash; empty when none is loaded. */
    IndexModel index();

    /** True when orders can be placed (needs the server and its bots). */
    boolean ordersSupported();

    /** A real stack for an index key (item or shulker kind), or null: then the window draws one from the index. */
    default ItemStack stackFor(String key) {
        return null;
    }

    /** True when the source can say where an item is (containers with coordinates). */
    default boolean supportsWhere() {
        return false;
    }

    /** The containers that hold this key, most first. */
    default List<Where> where(String key) {
        return List.of();
    }

    /** Forgets one container; false when this source cannot or it was not known. */
    default boolean forget(ContainerKey key) {
        return false;
    }
}

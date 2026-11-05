package com.porter.qwerte.compat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;

/**
 * Items a shulker keeps in block_entity_data (ViaVersion does this) instead of the container component, for 1.21.11.
 * Read the way Meteor's Utils does.
 */
public final class LegacyItems {
    private LegacyItems() {}

    /** Whether the stack's block entity data has an item list. */
    public static boolean hasItemList(ItemStack stack) {
        TypedEntityData<?> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        return data != null && data.contains("Items");
    }
}

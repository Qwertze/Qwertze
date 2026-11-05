package com.porter.qwerte.compat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Items a shulker keeps in block_entity_data (ViaVersion does this) instead of the container component, for 1.21.4.
 * Read the way Meteor's Utils does.
 */
public final class LegacyItems {
    private LegacyItems() {}

    /** Whether the stack's block entity data has an item list. */
    public static boolean hasItemList(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY).getUnsafe();
        return tag != null && tag.contains("Items", 9);
    }
}

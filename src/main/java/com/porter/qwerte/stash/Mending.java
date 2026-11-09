package com.porter.qwerte.stash;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

/** Whether a stack has the Mending enchantment, by the enchantment's registry key (never by display text). */
public final class Mending {
    private Mending() {}

    /** The stack carries Mending as an enchantment (not as a stored enchantment of a book). */
    public static boolean has(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        var enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null) return false;
        for (var holder : enchantments.keySet()) if (holder.is(Enchantments.MENDING)) return true;
        return false;
    }
}

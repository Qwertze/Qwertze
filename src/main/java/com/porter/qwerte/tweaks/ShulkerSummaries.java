package com.porter.qwerte.tweaks;

import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.tooltip.ContainerTooltipComponent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What is in a shulker box, worked out once per distinct contents and kept: the 27 stacks, the most common item and the
 * tooltip image. Looking one up costs a hash lookup and allocates nothing; the contents component is immutable and
 * carries its own hash, so it is the key and there is no NBT to read on the render path. Main thread only.
 */
public final class ShulkerSummaries {
    private ShulkerSummaries() {}

    private static final int MAX = 256;

    public static final class Summary {
        /** The 27 stacks, {@link ItemStack#EMPTY} where there is nothing. */
        public final ItemStack[] items = new ItemStack[27];
        /** The stack standing for the contents, or {@link ItemStack#EMPTY} for an empty shulker box. */
        public ItemStack icon = ItemStack.EMPTY;

        private Item colorOf;
        private ContainerTooltipComponent component;

        /** The tooltip image, in the colour of this shulker box. Rebuilt only if the colour differs from last time. */
        public ContainerTooltipComponent component(ItemStack shulker) {
            if (component == null || colorOf != shulker.getItem()) {
                component = new ContainerTooltipComponent(items, Utils.getShulkerColor(shulker));
                colorOf = shulker.getItem();
            }
            return component;
        }
    }

    private static final Map<ItemContainerContents, Summary> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ItemContainerContents, Summary> eldest) {
            return size() > MAX;
        }
    };

    /**
     * The summary of a shulker box, or null if it is not a shulker box or has no contents component.
     */
    public static Summary of(ItemStack stack) {
        if (stack.isEmpty() || !Utils.isShulker(stack.getItem())) return null;

        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) return null;

        Summary summary = CACHE.get(contents);
        if (summary == null) {
            summary = build(stack);
            CACHE.put(contents, summary);
        }
        return summary;
    }

    private static Summary build(ItemStack stack) {
        Summary summary = new Summary();
        Utils.getItemsInContainerItem(stack, summary.items);

        Item[] keys = new Item[27];
        int[] counts = new int[27];
        for (int i = 0; i < 27; i++) {
            if (summary.items[i] == null) summary.items[i] = ItemStack.EMPTY;
            if (summary.items[i].isEmpty()) continue;
            keys[i] = summary.items[i].getItem();
            counts[i] = summary.items[i].getCount();
        }

        int best = MostCommon.pick(keys, counts);
        if (best >= 0) summary.icon = summary.items[best];
        return summary;
    }

    public static void clear() {
        CACHE.clear();
    }
}

package com.porter.qwerte.manager.gui;

import com.porter.qwerte.manager.model.IndexModel;
import com.porter.qwerte.manager.model.Text;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns what the server says about an item (an id, a name, damage, a glint) into an {@link ItemStack} to draw. Nothing
 * from the server is decoded as NBT or JSON text: the stack is built from the registry's item and a few plain
 * components, and a custom name is a literal component. Client thread only.
 */
public final class ItemStacks {
    private static final int CACHE_SIZE = 4096;
    private static final int NAME_MAX = 64;

    private static Map<String, Item> itemsById;
    private static final Map<String, ItemStack> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ItemStack> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private ItemStacks() {
    }

    private static Item lookup(String id) {
        if (itemsById == null) {
            itemsById = new HashMap<>();
            for (Item item : BuiltInRegistries.ITEM) itemsById.put(BuiltInRegistries.ITEM.getKey(item).toString(), item);
        }
        return itemsById.get(id);
    }

    /** A stack of one for an index item: damaged, glinting and named like the server says. Never null, never empty. */
    public static ItemStack display(IndexModel.Item item) {
        String cacheKey = item.key() + "|" + item.id();
        ItemStack cached = CACHE.get(cacheKey);
        if (cached != null) return cached;
        Item base = lookup(item.id());
        ItemStack stack;
        if (base == null) {
            stack = new ItemStack(Items.BARRIER);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(Text.sanitize(item.id(), NAME_MAX)));
        } else {
            stack = new ItemStack(base);
            if (item.damage() > 0 && stack.getMaxDamage() > 0) {
                stack.setDamageValue(Math.min(item.damage(), stack.getMaxDamage() - 1));
            }
            if (item.enchanted()) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
            String name = item.name();
            if (!name.isBlank() && !name.equals(item.englishName())) {
                stack.set(DataComponents.CUSTOM_NAME, Component.literal(Text.sanitize(name, NAME_MAX)));
            }
        }
        CACHE.put(cacheKey, stack);
        return stack;
    }

    /** A stack for an item key or id from a shulker's contents that the index doesn't list: the item, else a barrier. */
    public static ItemStack displayId(String keyOrId) {
        String id = keyOrId == null ? "" : keyOrId;
        int hash = id.indexOf('#');
        if (hash >= 0) id = id.substring(0, hash);
        String cacheKey = "id|" + id;
        ItemStack cached = CACHE.get(cacheKey);
        if (cached != null) return cached;
        Item base = lookup(id);
        ItemStack stack = new ItemStack(base != null ? base : Items.BARRIER);
        if (base == null) stack.set(DataComponents.CUSTOM_NAME, Component.literal(Text.sanitize(id, NAME_MAX)));
        CACHE.put(cacheKey, stack);
        return stack;
    }

    /** A stack for a shulker group: the box item, named like the group. */
    public static ItemStack display(IndexModel.Shulker group) {
        String cacheKey = "shulker|" + group.key() + "|" + group.id();
        ItemStack cached = CACHE.get(cacheKey);
        if (cached != null) return cached;
        Item base = lookup(group.id());
        ItemStack stack = new ItemStack(base != null ? base : Items.SHULKER_BOX);
        CACHE.put(cacheKey, stack);
        return stack;
    }

    public static void clear() {
        CACHE.clear();
    }
}

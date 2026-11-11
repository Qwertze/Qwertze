package com.porter.qwerte.stash;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A saved kit: what the 27 main inventory slots held, slot for slot, counts included. Items match by everything
 * except durability and anvil cost, so a worn pickaxe is the same item as a fresh one.
 */
public final class Kit {
    public static final int SLOTS = 27;

    /**
     * An item as kits see it: the item and its components, less durability and repair cost.
     */
    public record Key(Item item, DataComponentMap components, String exact) {}

    /**
     * One kind of item in a kit: how many slots it fills, how many items in total, and the count of each slot.
     */
    public static final class Need {
        public final Key key;
        public final ItemStack sample;
        public final List<Integer> counts = new ArrayList<>();
        public int total;

        Need(Key key, ItemStack sample) {
            this.key = key;
            this.sample = sample;
        }

        public int slots() {
            return counts.size();
        }
    }

    public final String name;
    /**
     * An order kit built from a template (kit orders): the colour of the empty shulker to build in (null any, "plain" or a dye colour), and the group
     * key of the incomplete copy to build on (null: an empty shulker). Set by the order runner before the kit is begun.
     */
    public String shulkerColor, baseGroup;
    /** A transient kit made for an order: items match exactly, damage and repair cost included. Not a saved kit. */
    public final boolean exact;
    private final ItemStack[] layout = new ItemStack[SLOTS];
    private final Key[] keys = new Key[SLOTS];
    private final Map<Key, Need> needs = new LinkedHashMap<>();
    private final Set<Item> items = new HashSet<>();
    private final int[] order;

    public Kit(String name, ItemStack[] stacks) {
        this(name, stacks, false);
    }

    public Kit(String name, ItemStack[] stacks, boolean exact) {
        this.name = name;
        this.exact = exact;

        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = i < stacks.length && stacks[i] != null ? stacks[i] : ItemStack.EMPTY;
            layout[i] = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
            if (layout[i].isEmpty()) continue;

            ItemStack here = layout[i];
            Key key = key(here);
            keys[i] = key;
            items.add(here.getItem());
            Need need = needs.computeIfAbsent(key, k -> new Need(k, exact ? here.copyWithCount(1) : normalized(here)));
            need.counts.add(layout[i].getCount());
            need.total += layout[i].getCount();
        }

        // One stack of each kind first, so a kit that's only part built already holds more than one kind of item
        List<Integer> first = new ArrayList<>();
        List<Integer> rest = new ArrayList<>();
        Set<Key> seen = new HashSet<>();
        for (int i = 0; i < SLOTS; i++) {
            if (keys[i] == null) continue;
            if (seen.add(keys[i])) first.add(i);
            else rest.add(i);
        }
        first.addAll(rest);
        order = first.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Reads the 27 main inventory slots, inventory indexes 9 to 35. The hotbar, offhand, armor and crafting are left out.
     */
    public static Kit fromInventory(String name, Inventory inventory) {
        ItemStack[] stacks = new ItemStack[SLOTS];
        for (int i = 0; i < SLOTS; i++) stacks[i] = inventory.getItem(9 + i);
        return new Kit(name, stacks);
    }

    // Items

    /**
     * One of the item with durability and repair cost taken off, for comparing.
     */
    public static ItemStack normalized(ItemStack stack) {
        ItemStack copy = stack.copyWithCount(1);
        copy.remove(DataComponents.DAMAGE);
        copy.remove(DataComponents.REPAIR_COST);
        return copy;
    }

    public static Key keyOf(ItemStack stack) {
        ItemStack copy = normalized(stack);
        return new Key(copy.getItem(), copy.getComponents(), null);
    }

    /** The key this kit matches a stack by: exact for an order kit, else {@link #keyOf}. */
    public Key key(ItemStack stack) {
        // An order kit counts by the index key: what the server sends for the same item can differ in components (ViaVersion's
        // marker, the way a shulker's items are stored), so raw components are never compared to decide what is wanted.
        // Which exact variant sits in a slot is decided by the stacks really put there (see KitBuilder.fillOneExact)
        if (!exact) return keyOf(stack);
        String indexKey = StashIndex.keyOf(stack);
        return indexKey != null ? new Key(stack.getItem(), DataComponentMap.EMPTY, indexKey) : new Key(stack.getItem(), stack.copyWithCount(1).getComponents(), null);
    }

    /** {@link #totals} by this kit's own keys. */
    public Map<Key, Integer> totalsOf(ItemStack[] contents) {
        Map<Key, Integer> totals = new HashMap<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty()) continue;
            totals.merge(key(stack), stack.getCount(), Integer::sum);
        }
        return totals;
    }

    /**
     * Totals by kind of item. Empty and null slots are skipped.
     */
    public static Map<Key, Integer> totals(ItemStack[] contents) {
        Map<Key, Integer> totals = new HashMap<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty()) continue;
            totals.merge(keyOf(stack), stack.getCount(), Integer::sum);
        }
        return totals;
    }

    public static int kinds(ItemStack[] contents) {
        Set<Item> kinds = new HashSet<>();
        for (ItemStack stack : contents) if (stack != null && !stack.isEmpty()) kinds.add(stack.getItem());
        return kinds.size();
    }

    // The layout

    public ItemStack stackAt(int slot) {
        return layout[slot];
    }

    public Key keyAt(int slot) {
        return keys[slot];
    }

    public int countAt(int slot) {
        return layout[slot].isEmpty() ? 0 : layout[slot].getCount();
    }

    public int slotCount() {
        int count = 0;
        for (Key key : keys) if (key != null) count++;
        return count;
    }

    public Iterable<Need> needs() {
        return needs.values();
    }

    public Need need(Key key) {
        return needs.get(key);
    }

    /**
     * Whether the item is one of the kinds this kit holds, ignoring components. Cheap, to skip everything else.
     */
    public boolean hasItem(Item item) {
        return items.contains(item);
    }

    /**
     * Slots in the order to fill them: one of each kind first, then the rest.
     */
    public int[] fillOrder() {
        return order;
    }

    public ItemStack[] layout() {
        return layout;
    }

    // Matching shulkers

    /**
     * Exactly the kit's items in total, nothing else. Where they sit and how they're split over stacks doesn't matter,
     * so kits made by hand are recognised too.
     */
    public boolean matchesFinished(ItemStack[] contents) {
        Map<Key, Integer> have = totalsOf(contents);
        if (have.size() != needs.size()) return false;
        for (Need need : needs.values()) {
            Integer count = have.get(need.key);
            if (count == null || count != need.total) return false;
        }
        return true;
    }

    /**
     * Something of the kit and nothing else, no more of anything than the kit holds, and not finished.
     */
    public boolean matchesPartial(ItemStack[] contents) {
        Map<Key, Integer> have = totalsOf(contents);
        if (have.isEmpty()) return false;

        boolean complete = have.size() == needs.size();
        for (Map.Entry<Key, Integer> entry : have.entrySet()) {
            Need need = needs.get(entry.getKey());
            if (need == null || entry.getValue() > need.total) return false;
            if (entry.getValue() != need.total) complete = false;
        }
        return !complete;
    }

    /**
     * Slot for slot, count for count.
     */
    public boolean exactLayout(ItemStack[] contents) {
        // An order kit is done when it holds the right totals, in whatever slots
        if (exact) return matchesFinished(contents);
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = i < contents.length && contents[i] != null ? contents[i] : ItemStack.EMPTY;
            if (keys[i] == null) {
                if (!stack.isEmpty()) return false;
            }
            else if (stack.isEmpty() || stack.getCount() != layout[i].getCount() || !keys[i].equals(key(stack))) return false;
        }
        return true;
    }

    // Naming

    /**
     * A sign line naming this kit's chest: the kit's name, or the name with "kit" or "kits" after it.
     */
    public boolean labelMatches(String label) {
        String line = Categories.normalize(label);
        String mine = Categories.normalize(name);
        if (line.isEmpty() || mine.isEmpty()) return false;
        return line.equals(mine) || line.equals(mine + " kit") || line.equals(mine + " kits");
    }

    public boolean sameName(String other) {
        return Categories.normalize(name).equals(Categories.normalize(other));
    }
}

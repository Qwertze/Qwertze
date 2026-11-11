package com.porter.qwerte.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** Reads values out of NBT, whose getters differ between Minecraft versions, for Minecraft 1.21.4. */
public final class NbtRead {
    private NbtRead() {}

    public static String string(CompoundTag tag, String key) {
        return tag.getString(key);
    }

    /** The list of compounds under the key, empty when missing. */
    public static ListTag compounds(CompoundTag tag, String key) {
        return tag.getList(key, 10);
    }
}

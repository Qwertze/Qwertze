package com.porter.qwerte.compat;

import net.minecraft.SharedConstants;

/** The running game's version name, whose accessor is named differently across versions. */
public final class GameVersion {
    private GameVersion() {}

    public static String name() {
        return SharedConstants.getCurrentVersion().name();
    }

    /** The world data version (what a schematic's MinecraftDataVersion is compared with). */
    public static int dataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }
}

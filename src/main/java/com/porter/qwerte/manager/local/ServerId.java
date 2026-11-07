package com.porter.qwerte.manager.local;

import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Which server (or single-player world) the local index belongs to. Single-player worlds are told apart by their
 * folder, so two worlds never share entries; while the identity is not known (joining, no world) it is null and
 * nothing is captured or swept. Local to the Stash Window: {@code Stash.currentServer()} is unchanged.
 */
public final class ServerId {
    private ServerId() {}

    /** Pure: the id for a single-player world folder or a server address; null when the needed part is blank. */
    public static String of(boolean singleplayer, String worldFolder, String address) {
        if (singleplayer) {
            return worldFolder == null || worldFolder.isBlank() ? null : "singleplayer:" + worldFolder.trim();
        }
        return address == null || address.isBlank() ? null : address.trim();
    }

    /** The id of the world the game is in now, or null when unknown. Client thread. */
    public static String current() {
        if (mc.level == null) return null;
        try {
            if (mc.isSingleplayer()) {
                var server = mc.getSingleplayerServer();
                if (server == null) return null;
                Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                Path name = root.getFileName();
                return of(true, name == null ? null : name.toString(), null);
            }
            return of(false, null, mc.getCurrentServer() == null ? null : mc.getCurrentServer().ip);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

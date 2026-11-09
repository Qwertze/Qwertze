package com.porter.qwerte.messenger;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** The names of the players on the server, whose profile accessor differs between versions (Minecraft 1.21.4). */
public final class OnlineNames {
    private OnlineNames() {
    }

    public static boolean has(String name) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return false;
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            if (info.getProfile().getName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }
}

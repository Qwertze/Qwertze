package com.porter.qwerte.messenger;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.UUIDUtil;
import net.minecraft.client.resources.PlayerSkin;

import java.util.HashMap;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Draws a player's head (face and hat layer) from the skin on the tab list. A player who is not on the tab list gets
 * the default skin that belongs to the name, so the line keeps its place. The skin of a name is looked up at most once
 * a second (a tab-list scan, and for an unknown name an MD5), not once per head per frame. The skin type lives in
 * another package in 1.21.4 and 1.21.11, so this is per version.
 */
final class PlayerHeads {
    private static final long KEEP_MS = 1000;
    private static final int MAX = 256;

    private record Entry(PlayerSkin skin, long at) {
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();

    private PlayerHeads() {
    }

    static void draw(GuiGraphics g, String name, int x, int y, int size) {
        PlayerFaceRenderer.draw(g, skin(name), x, y, size);
    }

    private static PlayerSkin skin(String name) {
        long now = System.currentTimeMillis();
        Entry e = CACHE.get(name);
        if (e != null && now - e.at() < KEEP_MS) return e.skin();
        ClientPacketListener connection = mc.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(name);
        PlayerSkin skin = info != null ? info.getSkin() : DefaultPlayerSkin.get(UUIDUtil.createOfflinePlayerUUID(name));
        if (CACHE.size() >= MAX) CACHE.clear();
        CACHE.put(name, new Entry(skin, now));
        return skin;
    }
}

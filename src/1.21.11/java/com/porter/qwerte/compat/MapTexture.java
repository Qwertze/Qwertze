package com.porter.qwerte.compat;

import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import meteordevelopment.meteorclient.renderer.Texture;

/**
 * One RGBA texture the Stash Window's map is drawn from (Minecraft 1.21.11: Meteor's Texture is a game texture).
 * Create, upload and close on the client (render) thread only.
 */
public final class MapTexture {
    private final int width;
    private final int height;
    private Texture texture;

    private MapTexture(int width, int height) {
        this.width = width;
        this.height = height;
        this.texture = new Texture(width, height, TextureFormat.RGBA8, FilterMode.NEAREST, FilterMode.NEAREST);
    }

    public static MapTexture create(int width, int height) {
        return new MapTexture(width, height);
    }

    /** Replaces the whole picture. {@code rgba} is width * height * 4 bytes, row 0 first. */
    public void upload(byte[] rgba) {
        if (texture == null || rgba.length != width * height * 4) return;
        texture.upload(rgba);
    }

    /** What the GUI renderer draws; null once closed. */
    public Texture texture() {
        return texture;
    }

    public void close() {
        Texture t = texture;
        texture = null;
        if (t != null) t.close();
    }
}

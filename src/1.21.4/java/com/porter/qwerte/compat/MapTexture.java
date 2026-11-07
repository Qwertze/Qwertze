package com.porter.qwerte.compat;

import meteordevelopment.meteorclient.renderer.Texture;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

/**
 * One RGBA texture the Stash Window's map is drawn from (Minecraft 1.21.4: Meteor's Texture is an OpenGL texture).
 * Create, upload and close on the client (render) thread only.
 */
public final class MapTexture {
    private final int width;
    private final int height;
    private Texture texture;
    /** One direct buffer for every upload (the picture is the same size each time). */
    private ByteBuffer buffer;

    private MapTexture(int width, int height) {
        this.width = width;
        this.height = height;
        this.texture = new Texture(width, height, new byte[width * height * 4], Texture.Format.RGBA, Texture.Filter.Nearest, Texture.Filter.Nearest);
    }

    public static MapTexture create(int width, int height) {
        return new MapTexture(width, height);
    }

    /** Replaces the whole picture. {@code rgba} is width * height * 4 bytes, row 0 first. */
    public void upload(byte[] rgba) {
        if (texture == null || rgba.length != width * height * 4) return;
        if (buffer == null) buffer = BufferUtils.createByteBuffer(width * height * 4);
        buffer.clear();
        buffer.put(rgba);
        texture.upload(width, height, buffer, Texture.Format.RGBA, Texture.Filter.Nearest, Texture.Filter.Nearest, true);
    }

    /** What the GUI renderer draws; null once closed. */
    public Texture texture() {
        return texture;
    }

    public void close() {
        Texture t = texture;
        texture = null;
        buffer = null;
        if (t != null) t.dispose();
    }
}

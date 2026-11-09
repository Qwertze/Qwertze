package com.porter.qwerte.messenger;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The emoji pictures as game textures, made on first use from the jar (Minecraft 1.21.4). Render thread only.
 */
final class EmojiTextures {
    private final Map<String, ResourceLocation> ids = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    /** Pictures made per frame at most, so opening the picker does not stall one frame. */
    private static final int PER_FRAME = 16;
    private int loadsLeft = PER_FRAME;

    void newFrame() {
        loadsLeft = PER_FRAME;
    }

    /** Draws the picture {@code size} GUI pixels wide and high; false if there is no usable picture (draw text instead). */
    boolean draw(GuiGraphics g, String file, int x, int y, int size) {
        if (!ids.containsKey(file)) {
            if (failed.contains(file)) return false;
            if (loadsLeft <= 0) return true; // over this frame's budget: drawn a frame or two later
        }
        ResourceLocation id = idFor(file);
        if (id == null) return false;
        int s = EmojiPixels.SIZE;
        g.blit(RenderType::guiTextured, id, x, y, 0f, 0f, size, size, s, s, s, s);
        return true;
    }

    private ResourceLocation idFor(String file) {
        ResourceLocation id = ids.get(file);
        if (id != null) return id;
        if (failed.contains(file)) return null;
        loadsLeft--;
        NativeImage image = EmojiPixels.load(file);
        if (image == null) {
            failed.add(file);
            return null;
        }
        id = ResourceLocation.fromNamespaceAndPath("qwerte", "messenger/emoji/" + file);
        DynamicTexture texture = new DynamicTexture(image);
        texture.upload();
        mc.getTextureManager().register(id, texture);
        ids.put(file, id);
        return id;
    }

    /** Releases every texture made. */
    void close() {
        for (ResourceLocation id : ids.values()) mc.getTextureManager().release(id);
        ids.clear();
        failed.clear();
    }
}

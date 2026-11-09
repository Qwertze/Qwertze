package com.porter.qwerte.messenger;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.NativeImage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The emoji pictures as game textures, made on first use from the jar (Minecraft 1.21.11). Render thread only.
 */
final class EmojiTextures {
    private final Map<String, Identifier> ids = new HashMap<>();
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
        Identifier id = idFor(file);
        if (id == null) return false;
        int s = EmojiPixels.SIZE;
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, size, size, s, s, s, s);
        return true;
    }

    private Identifier idFor(String file) {
        Identifier id = ids.get(file);
        if (id != null) return id;
        if (failed.contains(file)) return null;
        loadsLeft--;
        NativeImage image = EmojiPixels.load(file);
        if (image == null) {
            failed.add(file);
            return null;
        }
        id = Identifier.fromNamespaceAndPath("qwerte", "messenger/emoji/" + file);
        mc.getTextureManager().register(id, new DynamicTexture(() -> "qwerte emoji " + file, image));
        ids.put(file, id);
        return id;
    }

    /** Releases every texture made. */
    void close() {
        for (Identifier id : ids.values()) mc.getTextureManager().release(id);
        ids.clear();
        failed.clear();
    }
}

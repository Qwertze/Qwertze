package com.porter.qwerte.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * Drawing items on the HUD, which differs between Minecraft versions, for Minecraft 1.21.4.
 */
public final class ItemIcons {
    private ItemIcons() {
    }

    /**
     * Draws an item inside {@code NametagUtils.begin}, the same way Meteor's nametags do. {@code count} is drawn in the
     * corner like a stack size, or nothing if null.
     */
    public static void draw(GuiGraphics graphics, ItemStack stack, int x, int y, float scale, String count) {
        RenderUtils.drawItem(graphics, stack, x, y, scale, count != null, count);
    }

    /**
     * Draws a stack count the way the game does (white with a dark shadow), small, in the bottom-right corner of a
     * slot. Called right after {@link #draw} with an empty count, inside the same post task, so it lands on the icon:
     * Meteor lifts items by 401 and vanilla draws a count 200 above its item.
     *
     * @param rightX  right edge of the text in the same physical pixels as {@link #draw}
     * @param bottomY bottom edge of the text
     * @param scale   physical pixels per font pixel (a whole number except for a count too wide for its slot)
     */
    public static void drawCount(GuiGraphics graphics, String text, int rightX, int bottomY, float scale) {
        if (text == null || text.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 601);
        pose.scale(scale, scale, 1f);
        graphics.drawString(font, text, Math.round(rightX / scale) - font.width(text), Math.round(bottomY / scale) - 8, 0xFFFFFFFF, true);
        pose.popPose();
    }
}

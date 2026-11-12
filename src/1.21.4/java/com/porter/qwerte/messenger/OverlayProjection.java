package com.porter.qwerte.messenger;

import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Minecraft 1.21.4: Meteor posts its 2D render event with an unscaled (real pixel) projection, but the Messenger's
 * sizes are GUI pixels. Around the overlay this puts the game's own scaled GUI projection back (the same one Meteor
 * restores after the event) and then Meteor's unscaled one again, with the drawn parts flushed in between so each part
 * is drawn with its own projection.
 */
public final class OverlayProjection {
    private OverlayProjection() {
    }

    public static void begin(GuiGraphics g) {
        g.flush();
        Utils.scaledProjection();
    }

    public static void end(GuiGraphics g) {
        g.flush();
        Utils.unscaledProjection();
    }
}

package com.porter.qwerte.manager.gui.widgets;

import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

/** One item icon (16 GUI pixels) in a table row, drawn through {@link ItemLayer} so popups stay on top of it. */
public final class WItemIcon extends WWidget {
    private final Supplier<GuiGraphics> graphics;
    private final ItemStack stack;

    public WItemIcon(Supplier<GuiGraphics> graphics, ItemStack stack) {
        this.graphics = graphics;
        this.stack = stack;
    }

    @Override
    protected void onCalculateSize() {
        int u = SmGuiTheme.unitOf(theme);
        width = 16.0 * u;
        height = 16.0 * u;
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        ItemLayer.draw(renderer, theme, graphics.get(), stack, "", x, y);
    }
}

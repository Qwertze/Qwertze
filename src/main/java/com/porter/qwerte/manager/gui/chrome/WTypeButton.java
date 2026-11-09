package com.porter.qwerte.manager.gui.chrome;

import com.porter.qwerte.manager.gui.ItemStacks;
import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import com.porter.qwerte.manager.gui.widgets.ItemLayer;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

/** The Order maker's item-type button (the website's .type-button): an item icon and the type's name on a wide pill. */
public class WTypeButton extends WPressable {
    private static final String BOLD = "§l";
    private final Supplier<GuiGraphics> graphics;
    private final ItemStack icon;
    private final String name;
    private final String boldName;

    public WTypeButton(Supplier<GuiGraphics> graphics, String iconId, String name) {
        this.graphics = graphics;
        this.icon = ItemStacks.displayId(iconId);
        this.name = name;
        this.boldName = BOLD + name;
    }

    @Override
    protected void onCalculateSize() {
        int u = SmGuiTheme.unitOf(theme);
        double pad = theme.scale(6);
        height = 16.0 * u + 2 * pad;
        width = 2 * pad + 16.0 * u + theme.scale(8) + theme.textWidth(boldName);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        double u = t.unit();
        boolean hot = (mouseOver || pressed) && !t.hoverBlocked();
        int fill = hot ? Palette.mix(p.surface2(), p.text(), pressed ? 0.20 : 0.10) : p.surface2();
        double radius = p.radius == 0 ? 0 : Math.min(height / 2, Math.max(p.radius, 8) * u);
        Paint.panel(renderer, t, x, y, width, height, radius, p.borderWidth * u, fill, hot ? p.accent() : p.border());
        double pad = theme.scale(6);
        ItemLayer.draw(renderer, t, graphics.get(), icon, "", Math.round((x + pad + theme.scale(2)) / u) * u, Math.round((y + (height - 16 * u) / 2) / u) * u);
        renderer.text(boldName, x + pad + theme.scale(2) + 16 * u + theme.scale(8), y + (height - theme.textHeight()) / 2, t.color(p.text()), false);
    }
}

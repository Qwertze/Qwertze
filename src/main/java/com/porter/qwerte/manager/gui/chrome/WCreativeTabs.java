package com.porter.qwerte.manager.gui.chrome;

import com.porter.qwerte.manager.gui.ItemStacks;
import com.porter.qwerte.manager.gui.layout.StashLayout;
import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import com.porter.qwerte.manager.gui.widgets.ItemLayer;
import com.porter.qwerte.manager.model.IndexModel;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The website's creative tabs: a row of small tabs with an item icon each, above the inventory panel. The selected tab
 * is taller and joins the panel below it; the Orders tab sits at the right end. Hovering a tab names it.
 */
public class WCreativeTabs extends WPressable {
    private static final Map<String, String[]> INFO = Map.ofEntries(
        Map.entry("all", new String[] {"Search", "minecraft:compass"}),
        Map.entry("building_blocks", new String[] {"Building Blocks", "minecraft:bricks"}),
        Map.entry("colored_blocks", new String[] {"Colored Blocks", "minecraft:cyan_wool"}),
        Map.entry("natural_blocks", new String[] {"Natural Blocks", "minecraft:grass_block"}),
        Map.entry("functional_blocks", new String[] {"Functional Blocks", "minecraft:oak_sign"}),
        Map.entry("redstone_blocks", new String[] {"Redstone Blocks", "minecraft:redstone"}),
        Map.entry("tools_and_utilities", new String[] {"Tools & Utilities", "minecraft:diamond_pickaxe"}),
        Map.entry("combat", new String[] {"Combat", "minecraft:netherite_sword"}),
        Map.entry("food_and_drinks", new String[] {"Food & Drinks", "minecraft:golden_apple"}),
        Map.entry("ingredients", new String[] {"Ingredients", "minecraft:iron_ingot"}),
        Map.entry("spawn_eggs", new String[] {"Spawn Eggs", "minecraft:pig_spawn_egg"}),
        Map.entry("op_blocks", new String[] {"Operator Utilities", "minecraft:command_block"}),
        Map.entry("other", new String[] {"Other", "minecraft:chest"}),
        Map.entry("shulkers", new String[] {"Shulkers", "minecraft:shulker_box"}),
        Map.entry("kits", new String[] {"Kits", "minecraft:purple_shulker_box"}),
        Map.entry("orders", new String[] {"Orders", "minecraft:writable_book"}));

    private static final int GAP = 1;
    private static final int MIN_TAB = 14;
    private static final int TOP_OFFSET = 3;

    private final Supplier<GuiGraphics> graphics;
    private List<String> ids = List.of();
    private String active = IndexModel.TAB_ALL;
    private int hover = -1;
    private double[] left = new double[0];
    private double tabW;

    /** Called with the id of the tab that was clicked. */
    public Consumer<String> onTab;

    public WCreativeTabs(Supplier<GuiGraphics> graphics) {
        this.graphics = graphics;
    }

    /** The name of a tab as the website shows it. */
    /** The item id of a tab's icon. */
    public static String iconOf(String id) {
        String[] info = INFO.get(id);
        return info == null ? "minecraft:chest" : info[1];
    }

    public static String nameOf(String id) {
        String[] info = INFO.get(id);
        return info == null ? id : info[0];
    }

    public void setTabs(List<String> ids, String active) {
        this.ids = ids;
        this.active = active;
        hover = -1;
    }

    /** The row's width in physical pixels, set by the page. */
    public void setWidth(double w) {
        this.width = w;
    }

    private int u() {
        return SmGuiTheme.unitOf(theme);
    }

    @Override
    protected void onCalculateSize() {
        // The page sets the width; the height is the selected tab's
        height = StashLayout.TAB_SELECTED_H * u();
        int n = ids.size();
        int u = u();
        double avail = width;
        double pitch = StashLayout.TAB_W * u + GAP * u;
        tabW = StashLayout.TAB_W * u;
        if (n > 0 && n * pitch - GAP * u > avail) {
            tabW = Math.max(MIN_TAB * u, Math.floor((avail - (n - 1) * GAP * u) / n / u) * u);
            pitch = tabW + GAP * u;
        }
        left = new double[n];
        double xx = 0;
        int ordersIndex = ids.indexOf(IndexModel.TAB_ORDERS);
        int kitsIndex = ids.indexOf(IndexModel.TAB_KITS);
        for (int i = 0; i < n; i++) {
            // Kits sits right before Orders at the right end
            if (i == kitsIndex && ordersIndex >= 0) {
                left[i] = Math.max(xx, avail - 2 * tabW - GAP * u);
                xx = left[i] + pitch;
            }
            else if (i == ordersIndex) left[i] = Math.max(xx, avail - tabW);
            else {
                left[i] = xx;
                xx += pitch;
            }
        }
    }

    private int tabAt(double mx, double my) {
        int u = u();
        for (int i = 0; i < ids.size(); i++) {
            double top = ids.get(i).equals(active) ? 0 : TOP_OFFSET * u;
            if (mx >= x + left[i] && mx < x + left[i] + tabW && my >= y + top && my < y + height) return i;
        }
        return -1;
    }

    @Override
    protected void onPressed(int button) {
        if (hover >= 0 && hover < ids.size() && onTab != null) onTab.accept(ids.get(hover));
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (left.length != ids.size()) return; // the tabs changed since the last layout; drawn after the next one
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        int u = t.unit();
        boolean blocked = t.overlay != null && t.overlay.isOpen();
        hover = blocked || !isOver(mouseX, mouseY) ? -1 : tabAt(mouseX, mouseY);
        GuiGraphics g = graphics.get();
        double bw = p.borderWidth * u;
        double radius = Math.min(p.radius, 4) * u;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < ids.size(); i++) {
                boolean sel = ids.get(i).equals(active);
                if (sel != (pass == 1)) continue;
                double tx = x + left[i];
                double ty = y + (sel ? 0 : TOP_OFFSET * u);
                double th = height - (sel ? 0 : TOP_OFFSET * u) - (sel ? 0 : 2 * u);
                int fill = sel ? p.surface() : (i == hover ? Palette.mix(p.surface2(), p.text(), 0.10) : p.surface2());
                Paint.panel(renderer, t, tx, ty, tabW, th, radius, bw, fill, p.border());
                // The selected tab has no line under it: it is one piece with the panel
                if (sel) Paint.rect(renderer, t, tx + bw, ty + th - bw, tabW - 2 * bw, bw, fill);
                if (p.bevel && sel) Paint.bevel(renderer, t, tx + bw, ty + bw, tabW - 2 * bw, th - bw, u, 0xFFFFFFFF, 0xFFC6C6C6);
                String[] info = INFO.get(ids.get(i));
                if (info != null) {
                    double ix = tx + (tabW - 16 * u) / 2;
                    double iy = ty + (th - 16 * u) / 2 + (sel ? 0 : u);
                    ItemLayer.draw(renderer, t, g, ItemStacks.displayId(info[1]), "", Math.round(ix / u) * u, Math.round(iy / u) * u);
                }
            }
        }
        if (hover >= 0 && t.overlay != null) t.overlay.hoverCard(List.of(nameOf(ids.get(hover))), x + left[hover], y + (ids.get(hover).equals(active) ? 0 : TOP_OFFSET * u), tabW, height - (ids.get(hover).equals(active) ? 0 : TOP_OFFSET * u));
    }
}

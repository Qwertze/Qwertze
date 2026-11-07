package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.compat.ItemIcons;
import com.porter.qwerte.stash.Categories;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.stash.Stash.Box;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shows what each container in the saved stash mostly holds, as an item icon floating on the container. Uses the stash
 * stash-sorter scanned and keeps up to date, so it needs no scanning of its own.
 */
public class StashOverlay extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> size = sgGeneral.add(new DoubleSetting.Builder()
        .name("size")
        .description("How big the icons are, at 6 blocks away.")
        .defaultValue(3)
        .min(0.5)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Double> maxScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-grow")
        .description("How much bigger icons can grow as you get close.")
        .defaultValue(2.5)
        .min(1)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Double> minScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-shrink")
        .description("How small icons can shrink far away, so distant containers stay readable.")
        .defaultValue(0.35)
        .range(0.05, 1)
        .sliderRange(0.05, 1)
        .build()
    );

    private final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height")
        .description("Where on the container the icon sits: 0 is the bottom, 0.5 the middle, 1 the top.")
        .defaultValue(0.5)
        .sliderRange(0, 1.5)
        .build()
    );

    private final Setting<Boolean> shulkerContents = sgGeneral.add(new BoolSetting.Builder()
        .name("shulker-contents")
        .description("For a container mostly full of shulkers, show what the shulkers mostly hold, with a small shulker in the corner.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showCount = sgGeneral.add(new BoolSetting.Builder()
        .name("show-count")
        .description("Show how many of the item the container holds, counting inside shulkers when showing their contents.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> hideExcluded = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-excluded")
        .description("No icons on containers excluded from sorting.")
        .defaultValue(false)
        .build()
    );

    /**
     * What to draw on one container.
     */
    private record Icon(Vec3 at, ItemStack item, ItemStack shulker, String count) {}

    private static final double REFERENCE_DISTANCE = 6;
    private static final int REFRESH_TICKS = 10;

    private final List<Icon> icons = new ArrayList<>();
    private final Vector3d screenPos = new Vector3d();
    private int refresh;

    public StashOverlay() {
        super(QwerteAddon.CATEGORY, "stash-overlay", "Shows what's in each container of a saved stash as an icon on the container. Uses the stash saved by stash-sorter.");
    }

    @Override
    public void onActivate() {
        refresh = 0;
        icons.clear();

        if (stash() == null) {
            warning("No saved stash here yet. Scan one with stash-sorter first; this uses the stash named in its settings.");
        }
    }

    @Override
    public void onDeactivate() {
        icons.clear();
    }

    private Stash stash() {
        return Modules.get().get(StashSorter.class).currentStash();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        // stash-sorter draws it itself while it is on
        StashSorter sorter = Modules.get().get(StashSorter.class);
        if (!sorter.isActive()) sorter.drawArea(event);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        icons.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (refresh-- > 0) return;
        refresh = REFRESH_TICKS;

        icons.clear();
        Stash stash = mc.level == null ? null : stash();
        if (stash == null) return;

        for (Box box : stash.boxes) {
            if (!box.known() || box.foreign || (hideExcluded.get() && box.excluded)) continue;

            Icon icon = iconFor(box);
            if (icon != null) icons.add(icon);
        }
    }

    private Icon iconFor(Box box) {
        ItemStack item = Categories.mostCommon(box.contents);
        if (item == null) return null;

        ItemStack shulker = null;
        if (shulkerContents.get() && Utils.isShulker(item.getItem())) {
            ItemStack inside = mostCommonInShulkers(box.contents);
            if (inside != null) {
                shulker = item;
                item = inside;
            }
        }

        String count = null;
        if (showCount.get()) count = compact(shulker != null ? countInShulkers(box.contents, item) : count(box.contents, item));

        // The middle of the container, a double chest's two halves together
        double x = 0, y = 0, z = 0;
        for (BlockPos part : box.parts) {
            x += part.getX();
            y += part.getY();
            z += part.getZ();
        }
        int n = box.parts.size();
        Vec3 at = new Vec3(x / n + 0.5, y / n + height.get(), z / n + 0.5);

        return new Icon(at, item.copyWithCount(1), shulker == null ? null : shulker.copyWithCount(1), count);
    }

    /**
     * What the shulkers in a container mostly hold, taking all of them together.
     */
    private static ItemStack mostCommonInShulkers(ItemStack[] contents) {
        List<ItemStack> inside = new ArrayList<>();
        for (ItemStack stack : contents) {
            if (!Utils.isShulker(stack.getItem())) continue;

            ItemStack[] items = new ItemStack[27];
            Utils.getItemsInContainerItem(stack, items);
            for (ItemStack item : items) if (item != null && !item.isEmpty()) inside.add(item);
        }
        return Categories.mostCommon(inside.toArray(ItemStack[]::new));
    }

    private static int count(ItemStack[] contents, ItemStack item) {
        int total = 0;
        for (ItemStack stack : contents) if (stack != null && stack.is(item.getItem())) total += stack.getCount();
        return total;
    }

    private static int countInShulkers(ItemStack[] contents, ItemStack item) {
        int total = 0;
        for (ItemStack stack : contents) {
            if (!Utils.isShulker(stack.getItem())) continue;

            ItemStack[] items = new ItemStack[27];
            Utils.getItemsInContainerItem(stack, items);
            total += count(items, item);
        }
        return total;
    }

    private static String compact(int count) {
        if (count >= 10_000) return (count / 1000) + "k";
        if (count >= 1000) return String.format("%.1fk", count / 1000.0);
        return Integer.toString(count);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.level == null || icons.isEmpty()) return;

        Vec3 eye = mc.player.getEyePosition(event.tickDelta);
        int renderDistance = mc.options.getEffectiveRenderDistance();
        int playerChunkX = mc.player.blockPosition().getX() >> 4;
        int playerChunkZ = mc.player.blockPosition().getZ() >> 4;

        // Far ones first, so nearer icons are drawn on top
        List<Icon> visible = new ArrayList<>();
        for (Icon icon : icons) {
            BlockPos pos = BlockPos.containing(icon.at);
            int chunkDistance = Math.max(Math.abs((pos.getX() >> 4) - playerChunkX), Math.abs((pos.getZ() >> 4) - playerChunkZ));
            if (chunkDistance > renderDistance || !mc.level.isLoaded(pos)) continue;
            visible.add(icon);
        }
        visible.sort(Comparator.comparingDouble(icon -> -icon.at.distanceToSqr(eye)));

        for (Icon icon : visible) {
            double distance = Math.max(0.5, icon.at.distanceTo(eye));
            double scale = size.get() * Mth.clamp(REFERENCE_DISTANCE / distance, minScale.get(), maxScale.get());

            screenPos.set(icon.at.x, icon.at.y, icon.at.z);
            if (!NametagUtils.to2D(screenPos, scale, false)) continue;

            NametagUtils.begin(screenPos, event.drawContext);
            ItemIcons.draw(event.drawContext, icon.item, -8, -8, 1, icon.count);
            if (icon.shulker != null) ItemIcons.draw(event.drawContext, icon.shulker, -10, -10, 0.5f, null);
            NametagUtils.end(event.drawContext);
        }
    }
}

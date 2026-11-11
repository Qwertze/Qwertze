package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.compat.ItemIcons;
import com.porter.qwerte.compat.RawKeys;
import com.porter.qwerte.compat.ScreenOverlay;
import com.porter.qwerte.events.ContainerScreenDrawEvent;
import com.porter.qwerte.events.ContainerScreenRenderEvent;
import com.porter.qwerte.events.ContainerSlotClickEvent;
import com.porter.qwerte.events.ContainerSlotRenderEvent;
import com.porter.qwerte.events.ContainerTooltipEvent;
import com.porter.qwerte.events.HotbarSlotRenderEvent;
import com.porter.qwerte.events.ShulkerTooltipLinesEvent;
import com.porter.qwerte.tweaks.FreezeDecision;
import com.porter.qwerte.tweaks.FreezeScreens;
import com.porter.qwerte.tweaks.FrozenGrid;
import com.porter.qwerte.tweaks.MigrationSource;
import com.porter.qwerte.tweaks.Orphans;
import com.porter.qwerte.tweaks.ShulkerSummaries;
import com.porter.qwerte.tweaks.SmartPlaceFlow;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Kind;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Level;
import com.porter.qwerte.utils.BaritoneHelper;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.TooltipDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.tooltip.ContainerTooltipComponent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT;

/**
 * Container tweaks: a preview of what is in a shulker box when you hover it (which can be frozen to read the items), the
 * most common item drawn on every shulker box, and right-click to place a shulker box or ender chest, open it and pick
 * it up again.
 *
 * <p>The right-click flow is a state machine ({@link SmartPlaceFlow}) that gives at most one thing to do per tick, so the
 * module never sends a burst of actions. Items are never left silently: anything left in the world is remembered in
 * the saved {@code orphans} setting and listed the next time you are in that world.
 */
public class ContainerTweaks extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPreview = settings.createGroup("Shulker Preview");
    private final SettingGroup sgShulker = settings.createGroup("Smart Shulker");
    private final SettingGroup sgEchest = settings.createGroup("Smart Echest");

    public enum Corner { TopLeft, TopRight, BottomLeft, BottomRight }

    public enum NoSilkMode {
        Refuse(SmartPlaceFlow.NoSilk.REFUSE), PlaceAndLeave(SmartPlaceFlow.NoSilk.PLACE_AND_LEAVE), MineAnyway(SmartPlaceFlow.NoSilk.MINE_ANYWAY);

        final SmartPlaceFlow.NoSilk mode;

        NoSilkMode(SmartPlaceFlow.NoSilk mode) {
            this.mode = mode;
        }
    }

    // Shulker preview

    private final Setting<Boolean> preview = sgPreview.add(new BoolSetting.Builder()
        .name("preview")
        .description("Shows what is inside a shulker box as a grid when you hover it, in any container screen and your inventory. Hides Meteor's own shulker preview while on, without changing Meteor's settings.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Keybind> freezeKey = sgPreview.add(new KeybindSetting.Builder()
        .name("freeze-key")
        .description("Hold this over a shulker box to freeze its preview in place. While frozen, hovering an item in the grid shows that item's full tooltip.")
        .defaultValue(Keybind.fromKey(GLFW_KEY_LEFT_ALT))
        .visible(preview::get)
        .build()
    );

    private final Setting<Boolean> freezeInInventory = sgPreview.add(new BoolSetting.Builder()
        .name("freeze-in-inventory")
        .description("Lets the freeze key also work in your own inventory and the creative inventory. Off: it only freezes in chests, shulker boxes and other containers.")
        .defaultValue(true)
        .visible(preview::get)
        .build()
    );

    private final Setting<Boolean> hideTextList = sgPreview.add(new BoolSetting.Builder()
        .name("hide-text-list")
        .description("Leaves out the text list of contents (\"Dirt x64, and 5 more\") from a shulker box's tooltip while the preview is on. On 1.21.11 this applies to every item with contents, since the game does not say which item the list belongs to.")
        .defaultValue(true)
        .visible(preview::get)
        .build()
    );

    private final Setting<Boolean> icon = sgPreview.add(new BoolSetting.Builder()
        .name("icon")
        .description("Draws the most common item inside a shulker box on top of it, in container screens and your inventory. Empty shulker boxes get no icon.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> iconScale = sgPreview.add(new DoubleSetting.Builder()
        .name("icon-scale")
        .description("How big the icon is, as a fraction of a slot.")
        .defaultValue(0.5)
        .range(0.3, 1)
        .sliderRange(0.3, 1)
        .onChanged(v -> scale = v.floatValue())
        .visible(icon::get)
        .build()
    );

    private final Setting<Corner> iconCorner = sgPreview.add(new EnumSetting.Builder<Corner>()
        .name("icon-corner")
        .description("Which corner of the shulker box the icon sits in.")
        .defaultValue(Corner.TopLeft)
        .visible(icon::get)
        .build()
    );

    private final Setting<Boolean> hotbarIcon = sgPreview.add(new BoolSetting.Builder()
        .name("hotbar-icon")
        .description("Also draws the icon on shulker boxes in the hotbar on your screen.")
        .defaultValue(true)
        .visible(icon::get)
        .build()
    );

    private final Setting<Boolean> debugFreeze = sgPreview.add(new BoolSetting.Builder()
        .name("debug-freeze")
        .description("Temporary: writes a line to the game log each time the freeze key, the hovered shulker box or the freeze decision changes. Logs modifier key codes (Shift, Control, Alt, Super) and the freeze key; other held keys are only counted. Leave off unless asked to test the freeze.")
        .defaultValue(false)
        .visible(preview::get)
        .build()
    );

    // Smart shulker and echest

    private final Setting<Boolean> smartShulker = sgShulker.add(new BoolSetting.Builder()
        .name("smart-shulker")
        .description("Right-click a shulker box in your inventory or a container to place it at the closest spot in reach, open it, then pick it up again when you close it. Needs a spot to place it; says why if there is none.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> smartEchest = sgEchest.add(new BoolSetting.Builder()
        .name("smart-echest")
        .description("The same for an ender chest. It is picked up again with a silk touch pickaxe.")
        .defaultValue(false)
        .build()
    );

    private final Setting<NoSilkMode> noSilk = sgEchest.add(new EnumSetting.Builder<NoSilkMode>()
        .name("no-silk-touch")
        .description("What to do without a silk touch pickaxe. Refuse: do nothing and say so. PlaceAndLeave: place and open it, then leave it there. MineAnyway: place, open and mine it; WARNING: it drops 8 obsidian and the ender chest item is lost; needs a pickaxe (without one it is left there).")
        .defaultValue(NoSilkMode.Refuse)
        .visible(smartEchest::get)
        .build()
    );

    // General

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Looks at the block before placing, opening and mining it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swings your arm. Off only hides the animation on your screen: the swing is still sent to the server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Ticks to wait between steps. At most one thing is done in the world per tick.")
        .defaultValue(3)
        .range(0, 20)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Boolean> pickupWithBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("pickup-with-baritone")
        .description("Lets Baritone walk back to the block and the dropped item when they are out of reach. Needs Baritone installed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> safeWalk = sgGeneral.add(new BoolSetting.Builder()
        .name("safe-walk")
        .description("While walking: no parkour, no diagonal drops, no falls beyond one block, and no breaking or placing blocks.")
        .defaultValue(true)
        .visible(pickupWithBaritone::get)
        .build()
    );

    private final Setting<Integer> pickupTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("pickup-timeout")
        .description("Seconds to try getting back in reach, and to try picking the item up, before leaving it where it is (and telling you where).")
        .defaultValue(20)
        .range(5, 120)
        .sliderRange(5, 60)
        .build()
    );

    private final Setting<Keybind> cancelKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("cancel-key")
        .description("Stops a running place, open and pick-up. Anything already placed is left where it is and you are told where. Turning the module or the feature off does the same.")
        .defaultValue(Keybind.none())
        .build()
    );

    // Saved: items left in the world, as id|x|y|z|dimension|name
    private final Setting<List<String>> orphans = sgGeneral.add(new StringListSetting.Builder()
        .name("orphans")
        .description("Items left in the world by an interrupted flow, listed next time you are there.")
        .visible(() -> false)
        .build()
    );

    private static final int ORPHAN_CHECK = 40;

    // Preview state
    private float scale = 0.5f;
    private boolean frozen;
    private Screen frozenScreen;
    private ItemStack frozenStack = ItemStack.EMPTY;
    private ShulkerSummaries.Summary frozenSummary;
    private int panelX, panelY;
    private GuiGraphics panelGraphics;
    private Screen queuedScreen;
    private final Runnable panelDraw = this::drawPanel;

    private final SmartPlaceSession session = new SmartPlaceSession(new SessionHost());


    // The container screen under the mouse last frame, for the 1.21.11 text list
    private Screen hoverScreen;
    private boolean hoverShulker;

    // Orphans
    private Object orphanLevel;
    private int orphanTick;

    public ContainerTweaks() {
        super(QwerteAddon.CATEGORY, MigrationSource.NEW_NAME, "Shulker box preview with a freeze key, the most common item drawn on shulker boxes, and right-click to place, open and pick up a shulker box or ender chest.");
    }

    @Override
    public void onActivate() {
        scale = iconScale.get().floatValue();
    }

    @Override
    public void onDeactivate() {
        lastDebug = "";
        debugLines = 0;
        session.shutdown();
        unfreeze();
        ShulkerSummaries.clear();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        session.gameLeft();
        unfreeze();
        ShulkerSummaries.clear();
        orphanLevel = null;
    }

    // Preview

    @EventHandler(priority = EventPriority.LOWEST)
    private void onTooltipData(TooltipDataEvent event) {
        if (!preview.get()) return;
        ShulkerSummaries.Summary summary = ShulkerSummaries.of(event.itemStack);
        if (summary == null) return;
        event.tooltipData = summary.component(event.itemStack);
    }

    @EventHandler
    private void onTooltipLines(ShulkerTooltipLinesEvent event) {
        if (!preview.get() || !hideTextList.get()) return;
        // 1.21.4 says which item. 1.21.11 does not, and the text lines are built before the preview event fires, so
        // there it goes by what the mouse was over last frame: a shulker box with contents in a container screen
        if (event.stack != null) {
            if (!Utils.isShulker(event.stack.getItem()) || ShulkerSummaries.of(event.stack) == null) return;
        }
        else if (!hoverShulker || hoverScreen == null || mc.screen != hoverScreen) return;
        event.setCancelled(true);
    }

    @EventHandler
    private void onSlotRender(ContainerSlotRenderEvent event) {
        if (!icon.get()) return;
        ItemStack stack = event.slot.getItem();
        if (stack.isEmpty() || !Utils.isShulker(stack.getItem())) return;
        ShulkerSummaries.Summary summary = ShulkerSummaries.of(stack);
        if (summary == null || summary.icon.isEmpty()) return;
        drawIcon(event.graphics, summary.icon, event.slot.x, event.slot.y);
    }

    @EventHandler
    private void onHotbarRender(HotbarSlotRenderEvent event) {
        if (!icon.get() || !hotbarIcon.get()) return;
        ItemStack stack = event.stack;
        if (stack.isEmpty() || !Utils.isShulker(stack.getItem())) return;
        ShulkerSummaries.Summary summary = ShulkerSummaries.of(stack);
        if (summary == null || summary.icon.isEmpty()) return;
        drawIcon(event.graphics, summary.icon, event.x, event.y);
    }

    private void drawIcon(GuiGraphics graphics, ItemStack icon, int x, int y) {
        int offset = 16 - Math.round(16 * scale);
        Corner corner = iconCorner.get();
        int dx = corner == Corner.TopRight || corner == Corner.BottomRight ? offset : 0;
        int dy = corner == Corner.BottomLeft || corner == Corner.BottomRight ? offset : 0;
        ItemIcons.draw(graphics, icon, x + dx, y + dy, scale, null);
    }

    @EventHandler
    private void onScreenRender(ContainerScreenRenderEvent event) {
        queuedScreen = null;
        hoverScreen = event.screen;
        hoverShulker = event.hoveredSlot != null && ShulkerSummaries.of(event.hoveredSlot.getItem()) != null;
        if (!preview.get()) {
            unfreeze();
            return;
        }
        FreezeScreens.Kind kind = event.screen instanceof InventoryScreen || event.screen instanceof CreativeModeInventoryScreen
            ? FreezeScreens.Kind.INVENTORY : FreezeScreens.Kind.CONTAINER;
        if (!FreezeScreens.allowed(kind, freezeInInventory.get())) {
            unfreeze();
            return;
        }
        boolean held = freezeHeld();
        ItemStack hovered = event.hoveredSlot == null ? ItemStack.EMPTY : event.hoveredSlot.getItem();
        ShulkerSummaries.Summary hoveredSummary = event.hoveredSlot == null ? null : ShulkerSummaries.of(hovered);
        FreezeDecision.Action action = FreezeDecision.decide(held, hoveredSummary != null, frozen, event.screen == frozenScreen);
        if (debugFreeze.get()) debugFreeze(held, hoveredSummary != null, action);
        switch (action) {
            case UNFREEZE -> unfreeze();
            case FREEZE -> {
                frozen = true;
                frozenScreen = event.screen;
                frozenStack = hovered;
                frozenSummary = hoveredSummary;
                panelX = FrozenGrid.placeX(event.mouseX, event.screen.width);
                panelY = FrozenGrid.placeY(event.mouseY, event.screen.height);
            }
            case NONE -> {}
        }
        if (!frozen) return;

        // Drawn by onScreenDraw, once the screen has drawn everything of its own
        queuedScreen = event.screen;
    }

    /** Draws the frozen preview, once per frame, above the whole screen. */
    @EventHandler
    private void onScreenDraw(ContainerScreenDrawEvent event) {
        if (queuedScreen == null || event.screen != queuedScreen) return;
        queuedScreen = null;
        if (!frozen || frozenSummary == null) return;

        panelGraphics = event.graphics;
        ScreenOverlay.top(event.graphics, panelDraw);
        panelGraphics = null;

        int cell = FrozenGrid.cellAt(panelX, panelY, event.mouseX, event.mouseY);
        if (cell >= 0 && !frozenSummary.items[cell].isEmpty()) {
            ScreenOverlay.itemTooltip(event.graphics, mc.font, frozenSummary.items[cell], event.mouseX, event.mouseY);
        }
    }

    /** Read from GLFW: Meteor's key state is switched off while its text boxes have focus and misses keys other mods eat. */
    private boolean freezeHeld() {
        Keybind bind = freezeKey.get();
        return bind.hasMods() ? bind.isPressed() : RawKeys.down(bind);
    }

    private void drawPanel() {
        ContainerTooltipComponent component = frozenSummary.component(frozenStack);
        component.renderImage(mc.font, panelX, panelY, FrozenGrid.WIDTH, FrozenGrid.HEIGHT, panelGraphics);
    }

    @EventHandler
    private void onContainerTooltip(ContainerTooltipEvent event) {
        if (frozen) event.setCancelled(true);
    }

    private static final int MAX_DEBUG_LINES = 200;
    private String lastDebug = "";
    private int debugLines;

    /** One line per change of the key, the hovered shulker box, the decision or the frozen state. */
    private void debugFreeze(boolean held, boolean hovered, FreezeDecision.Action action) {
        if (debugLines >= MAX_DEBUG_LINES) return;
        Keybind bind = freezeKey.get();
        // The cheap fields first; the key scan only runs when one of them changed
        String cheap = "held=" + held + " hovered=" + hovered + " action=" + action + " frozen=" + frozen + " meteor=" + bind.isPressed();
        if (cheap.equals(lastDebug)) return;
        lastDebug = cheap;
        debugLines++;
        String down = RawKeys.pressed(bind.getValue());
        QwerteAddon.LOG.info("container-tweaks freeze: {} down={} key={} code={} isKey={} screen={}", cheap, down, bind, bind.getValue(), bind.isKey(), mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
    }

    private void unfreeze() {
        frozen = false;
        queuedScreen = null;
        frozenScreen = null;
        frozenSummary = null;
        frozenStack = ItemStack.EMPTY;
    }

    // Smart place

    @EventHandler
    private void onSlotClick(ContainerSlotClickEvent event) {
        if (frozen && FrozenGrid.inside(panelX, panelY, event.mouseX, event.mouseY)) {
            event.setCancelled(true);
            return;
        }

        if (SmartPlaceSession.busy() || event.slot == null || event.button != 1 || event.clickType != ClickType.PICKUP) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.player.hasInfiniteMaterials()) return;
        if (!event.menu.getCarried().isEmpty()) return;

        ItemStack stack = event.slot.getItem();
        if (stack.isEmpty()) return;

        Kind wanted;
        if (Utils.isShulker(stack.getItem())) wanted = smartShulker.get() ? Kind.SHULKER : null;
        else if (stack.is(Items.ENDER_CHEST)) wanted = smartEchest.get() ? Kind.ECHEST : null;
        else wanted = null;
        if (wanted == null) return;
        if (SmartPlaceSession.BARITONE && BaritoneHelper.confined()) return;

        event.setCancelled(true);
        if (SmartPlaceSession.BARITONE && pickupWithBaritone.get() && (BaritoneHelper.isActive() || BaritoneHelper.controlledByOther(null))) {
            say(Level.ERROR, "Baritone is busy with something else. Try again when it is idle, or turn off pickup-with-baritone.");
            return;
        }
        session.start(wanted, event.slot, event.slotId, stack, event.menu);
    }


    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (frozen && mc.screen != frozenScreen) unfreeze();
        if (mc.player == null || mc.level == null) return;
        orphans();
        session.tick();
    }

    // Orphans: id|x|y|z|dimension|server|name. Older entries have no server and match any.

    private void addOrphan(Item what, BlockPos at, String dim) {
        if (what == null || at == null) return;
        String id = BuiltInRegistries.ITEM.getKey(what).toString();
        String name = new ItemStack(what).getHoverName().getString();
        List<String> list = new ArrayList<>(orphans.get());
        list.add(Orphans.format(id, at.getX(), at.getY(), at.getZ(), dim, server(), name));
        orphans.set(list);
        QwerteAddon.LOG.warn("container-tweaks: left {} at {}, {}, {} in {} on {}", id, at.getX(), at.getY(), at.getZ(), dim, server());
    }

    /** The address of the server, or "singleplayer". */
    private String server() {
        if (mc.level == null) return "?";
        ServerData data = mc.getCurrentServer();
        return data != null && data.ip != null ? data.ip.replace('|', '/') : "singleplayer";
    }

    /**
     * Lists what was left behind on this server when entering a world, and forgets an entry once neither the block nor a
     * drop of it is at the place any more. Entries for other servers are kept and left alone.
     */
    private void orphans() {
        if (orphans.get().isEmpty()) {
            orphanLevel = mc.level;
            return;
        }
        String server = server();

        if (orphanLevel != mc.level) {
            orphanLevel = mc.level;
            for (String entry : orphans.get()) {
                Orphans.Entry part = Orphans.parse(entry);
                if (part != null && Orphans.here(part, server)) {
                    say(Level.WARN, "Left behind: " + part.name() + " at " + part.x() + ", " + part.y() + ", " + part.z() + " in " + part.dimension() + ".");
                }
            }
        }

        if (++orphanTick < ORPHAN_CHECK) return;
        orphanTick = 0;

        String dimensionHere = SmartPlaceSession.dimension();
        List<String> keep = new ArrayList<>();
        for (String entry : orphans.get()) {
            Orphans.Entry part = Orphans.parse(entry);
            if (part == null) continue;
            if (!Orphans.here(part, server) || !part.dimension().equals(dimensionHere) || !gone(part)) keep.add(entry);
            else say(Level.INFO, part.name() + " at " + part.x() + ", " + part.y() + ", " + part.z() + " is gone (picked up or despawned).");
        }
        if (keep.size() != orphans.get().size()) orphans.set(keep);
    }

    /** Close enough to be sure, the chunk is loaded, and neither the block nor a drop of the item is there. */
    private boolean gone(Orphans.Entry part) {
        try {
            BlockPos pos = new BlockPos(Integer.parseInt(part.x()), Integer.parseInt(part.y()), Integer.parseInt(part.z()));
            if (mc.player.distanceToSqr(Vec3.atCenterOf(pos)) > 24 * 24 || !mc.level.isLoaded(pos)) return false;

            String id = part.id();
            if (BuiltInRegistries.ITEM.getKey(mc.level.getBlockState(pos).getBlock().asItem()).toString().equals(id)) return false;
            return mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(6),
                d -> BuiltInRegistries.ITEM.getKey(d.getItem().getItem()).toString().equals(id)).isEmpty();
        }
        catch (NumberFormatException e) {
            return true;
        }
    }

    // Helpers

    private void say(Level level, String message) {
        switch (level) {
            case INFO -> info("%s", message);
            case WARN -> warning("%s", message);
            case ERROR -> error("%s", message);
        }
        QwerteAddon.LOG.info("container-tweaks: {}", message);
    }

    /** What the session needs from this module. */
    private final class SessionHost implements SmartPlaceSession.Host {
        @Override public String tag() { return "container-tweaks"; }
        @Override public boolean rotate() { return rotate.get(); }
        @Override public boolean swing() { return swing.get(); }
        @Override public int actionDelay() { return actionDelay.get(); }
        @Override public int pickupTimeout() { return pickupTimeout.get(); }
        @Override public boolean walk() { return pickupWithBaritone.get(); }
        @Override public boolean safeWalk() { return safeWalk.get(); }
        @Override public SmartPlaceFlow.NoSilk noSilk() { return noSilk.get().mode; }
        @Override public boolean cancelled(Kind kind) {
            return !(kind == Kind.ECHEST ? smartEchest : smartShulker).get()
                || cancelKey.get().isSet() && cancelKey.get().isPressed();
        }
        @Override public void say(Level level, String message) { ContainerTweaks.this.say(level, message); }
        @Override public void addOrphan(Item item, BlockPos at, String dimension) { ContainerTweaks.this.addOrphan(item, at, dimension); }
        @Override public boolean excluded(int x, int y, int z) { return false; }
        @Override public boolean hostOwnsConfinement() { return false; }
        @Override public boolean whileOwnedOpen(AbstractContainerMenu menu) { return false; }
        @Override public void onFinished(SmartPlaceSession.Result result) {}
    }
}

package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.compat.Players;
import com.porter.qwerte.printer.loop.PrintLoop;
import com.porter.qwerte.printer.loop.RecheckQueue;
import com.porter.qwerte.printer.loop.TargetBits;
import com.porter.qwerte.printer.materials.MaterialPlanner;
import com.porter.qwerte.printer.materials.EchestReturn;
import com.porter.qwerte.printer.materials.TakeRules;
import com.porter.qwerte.printer.move.HoverPoint;
import com.porter.qwerte.printer.move.MovePolicy;
import com.porter.qwerte.printer.move.SpareItems;
import com.porter.qwerte.printer.move.StandShield;
import com.porter.qwerte.printer.source.StateResolver;
import com.porter.qwerte.printer.place.AreaWatch;
import com.porter.qwerte.printer.place.BreakRules;
import com.porter.qwerte.printer.place.BuildOrderer;
import com.porter.qwerte.printer.place.JobHold;
import com.porter.qwerte.printer.place.LayerCursor;
import com.porter.qwerte.printer.place.ScaffoldPlanner;
import com.porter.qwerte.printer.place.LookWindow;
import com.porter.qwerte.printer.place.PacketBudget;
import com.porter.qwerte.printer.place.PlaceRecipe;
import com.porter.qwerte.printer.place.PlaceRecipe.Dir;
import com.porter.qwerte.printer.place.PlaceSimulator;
import com.porter.qwerte.printer.place.PlacementMapper;
import com.porter.qwerte.printer.place.StateClassifier;
import com.porter.qwerte.printer.place.StateKind;
import com.porter.qwerte.printer.place.StateSpec;
import com.porter.qwerte.printer.place.HandTables;
import com.porter.qwerte.printer.place.Tool;
import com.porter.qwerte.printer.place.ToolAction;
import com.porter.qwerte.printer.place.ToolRules;
import com.porter.qwerte.printer.place.ToolTables;
import com.porter.qwerte.printer.place.TickBatcher;
import com.porter.qwerte.printer.place.WaterlogRules;
import com.porter.qwerte.printer.stash.StashOrders;
import com.porter.qwerte.printer.schematic.LitematicReader;
import com.porter.qwerte.printer.schematic.SchematicFile;
import com.porter.qwerte.printer.schematic.Transform;
import com.porter.qwerte.printer.source.BuildQueue;
import com.porter.qwerte.printer.source.BuildSource;
import com.porter.qwerte.printer.source.LayerFilter;
import com.porter.qwerte.printer.source.LitematicaBridge;
import com.porter.qwerte.printer.source.SourcePicker;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.tweaks.Orphans;
import com.porter.qwerte.tweaks.SmartPlaceFlow;
import com.porter.qwerte.tweaks.SmartPlaceFlow.Kind;
import com.porter.qwerte.utils.AutoHotbar;
import com.porter.qwerte.utils.BaritoneHelper;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.ModuleListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SoundEventListSetting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Flight;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Builds a Litematica schematic (or a .litematic file) in the world: block by block, with the right facing, rotation and
 * state, walking with Baritone and fetching materials from the inventory, shulker boxes, chosen containers or the ender
 * chest, and ordering what runs low from the stash bots (Stash Manager server).
 *
 * <p>The decisions live in the pure parts: {@link PrintLoop} (what to do next), {@link BuildOrderer} and
 * {@link TickBatcher} (what to place), {@link PlacementMapper} and {@link PlaceSimulator} (how), {@link MaterialPlanner}
 * (what it costs). This class reads the game, runs one thing per tick and cleans up after itself on every way out.
 *
 * <p>Item safety: nothing is ever dropped; items are only moved by shift-clicking build materials out of a container
 * (which can only land in an empty slot or on a stack of the same item) and by the hotbar helper, which never touches
 * protected or foreign items.
 */
public class Printer extends Module {
    private final SettingGroup sgSource = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed");
    private final SettingGroup sgPlace = settings.createGroup("Placement");
    private final SettingGroup sgOrder = settings.createGroup("Order");
    private final SettingGroup sgWrong = settings.createGroup("Wrong Blocks");
    private final SettingGroup sgMaterials = settings.createGroup("Materials");
    private final SettingGroup sgStash = settings.createGroup("Stash Orders");
    private final SettingGroup sgBaritone = settings.createGroup("Baritone");
    private final SettingGroup sgSounds = settings.createGroup("Sounds");

    public enum Source {
        LitematicaLatest(SourcePicker.Mode.LITEMATICA_LATEST), LitematicaByName(SourcePicker.Mode.LITEMATICA_BY_NAME), File(SourcePicker.Mode.FILE);

        final SourcePicker.Mode mode;

        Source(SourcePicker.Mode mode) {
            this.mode = mode;
        }
    }

    public enum RotationMode { Smart, Snap, None }

    public enum Movement {
        StandOnBuild(MovePolicy.Mode.STAND), Fly(MovePolicy.Mode.FLY), StandThenFly(MovePolicy.Mode.STAND_THEN_FLY);

        final MovePolicy.Mode mode;

        Movement(MovePolicy.Mode mode) {
            this.mode = mode;
        }
    }

    public enum Order {
        LayerThenDistance(BuildOrderer.Mode.LAYER_THEN_DISTANCE), Distance(BuildOrderer.Mode.DISTANCE), Layer(BuildOrderer.Mode.LAYER);

        final BuildOrderer.Mode mode;

        Order(BuildOrderer.Mode mode) {
            this.mode = mode;
        }
    }

    /** Which y level is printed: one at a time, in this order; Off keeps the old behaviour (every layer at once, ranked by order and bottom-up). */
    public enum LayerOrder {
        Off(LayerCursor.Mode.OFF), BottomToTop(LayerCursor.Mode.BOTTOM_TO_TOP), TopToBottom(LayerCursor.Mode.TOP_TO_BOTTOM), MostEfficient(LayerCursor.Mode.MOST_EFFICIENT);

        final LayerCursor.Mode mode;

        LayerOrder(LayerCursor.Mode mode) {
            this.mode = mode;
        }
    }

    /** What to do when the ender chest can't be picked up again with silk touch. (Leaving it in the world is not offered: it would be lost.) */
    public enum EchestNoSilk {
        Refuse(SmartPlaceFlow.NoSilk.REFUSE), MineAnyway(SmartPlaceFlow.NoSilk.MINE_ANYWAY);

        final SmartPlaceFlow.NoSilk mode;

        EchestNoSilk(SmartPlaceFlow.NoSilk mode) {
            this.mode = mode;
        }
    }

    public enum WrongBlocks {
        SkipAndList(PrintLoop.Wrong.SKIP_AND_LIST), BreakAndReplace(PrintLoop.Wrong.BREAK_AND_REPLACE), Pause(PrintLoop.Wrong.PAUSE);

        final PrintLoop.Wrong mode;

        WrongBlocks(PrintLoop.Wrong mode) {
            this.mode = mode;
        }
    }

    // Source

    private final Setting<Source> source = sgSource.add(new EnumSetting.Builder<Source>()
        .name("source")
        .description("What to build. LitematicaLatest: the schematic placement you loaded last in Litematica, with its position, rotation and mirror. LitematicaByName: the Litematica placement with the name below. File: the .litematic file with the name below, placed at the origin below. Without Litematica the file is read instead.")
        .defaultValue(Source.LitematicaLatest)
        .build()
    );

    private final Setting<String> fileName = sgSource.add(new StringSetting.Builder()
        .name("file-name")
        .description("The .litematic file in the schematics folder of the game (the .litematic ending is added), or the name of the Litematica placement for LitematicaByName.")
        .defaultValue("")
        .build()
    );

    private final Setting<Integer> originX = sgSource.add(new IntSetting.Builder()
        .name("origin-x")
        .description("Where the schematic's corner goes, for a file that is read with this module's own position (File mode, or when Litematica can't be used).")
        .defaultValue(0)
        .noSlider()
        .build()
    );

    private final Setting<Integer> originY = sgSource.add(new IntSetting.Builder()
        .name("origin-y")
        .description("The y of the schematic's corner.")
        .defaultValue(64)
        .noSlider()
        .build()
    );

    private final Setting<Integer> originZ = sgSource.add(new IntSetting.Builder()
        .name("origin-z")
        .description("The z of the schematic's corner.")
        .defaultValue(0)
        .noSlider()
        .build()
    );

    private final Setting<Transform.Rot> rotation = sgSource.add(new EnumSetting.Builder<Transform.Rot>()
        .name("rotation")
        .description("How the file is turned, for a file placed with this module's own position.")
        .defaultValue(Transform.Rot.NONE)
        .build()
    );

    private final Setting<Transform.Mir> mirror = sgSource.add(new EnumSetting.Builder<Transform.Mir>()
        .name("mirror")
        .description("How the file is mirrored, for a file placed with this module's own position.")
        .defaultValue(Transform.Mir.NONE)
        .build()
    );

    private final Setting<Boolean> queueEnabled = sgSource.add(new BoolSetting.Builder()
        .name("queue-enabled")
        .description("Builds the schematics of the queue below one after the other instead of the single source above: when one is done the Printer goes on with the next without turning off. The source and file-name settings are not used then (the origin, rotation and mirror still are, for file entries).")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> queueList = sgSource.add(new StringListSetting.Builder()
        .name("queue")
        .description("The schematics in build order, one per entry: a Litematica placement name (exact name first, else the one placement whose name contains it), or file:<name> for a .litematic in the schematics folder (file:<name>@x,y,z gives that file its own origin). A missing, ambiguous or unreadable entry is skipped with a warning. The queue starts from the top each time the Printer is turned on.")
        .defaultValue(new ArrayList<>())
        .visible(queueEnabled::get)
        .build()
    );

    private final Setting<Keybind> reloadKey = sgSource.add(new KeybindSetting.Builder()
        .name("reload-key")
        .description("Loads the schematic again and starts over: blocks already built are noticed and skipped.")
        .defaultValue(Keybind.none())
        .action(this::reload)
        .build()
    );

    private final Setting<Keybind> cancelKey = sgSource.add(new KeybindSetting.Builder()
        .name("cancel-key")
        .description("Stops printing, puts everything back (sneak, Baritone, the selected hotbar slot) and turns the module off.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Boolean> chatMessages = sgSource.add(new BoolSetting.Builder()
        .name("chat-messages")
        .description("Lets the Printer write to the chat: progress, warnings, errors and the stash order lines. Off: it sends no chat line at all (everything is still written to the game log).")
        .defaultValue(false)
        .build()
    );

    // Speed

    private final Setting<Integer> blocksPerTick = sgSpeed.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("Most blocks placed in one tick. 1 is the safe rate for 2b2t. Higher is faster, with no upper limit: many placements in one tick, and anything above what the server allows, can get you kicked.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> tickDelay = sgSpeed.add(new IntSetting.Builder()
        .name("tick-delay")
        .description("Ticks to wait after a tick that placed blocks.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> packetBudget = sgSpeed.add(new IntSetting.Builder()
        .name("packet-budget")
        .description("Packets per second the printer may send (placements, swings, looks, slot changes), counted over 7 seconds. 55 keeps one block per tick under a 500 packets per 7 seconds limit. 0 turns the limit off.")
        .defaultValue(55)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Boolean> multiRotate = sgSpeed.add(new BoolSetting.Builder()
        .name("multi-rotate-per-tick")
        .description("Allows several different looks in one tick when blocks-per-tick is above 1 (each extra look is another packet). Off: one look per tick, and only blocks that can share it are placed together.")
        .defaultValue(false)
        .build()
    );

    // Placement

    private final Setting<Double> range = sgPlace.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far from your eyes a block is placed. Never more than the game's own reach, minus a margin.")
        .defaultValue(4.5)
        .min(1)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<RotationMode> rotationMode = sgPlace.add(new EnumSetting.Builder<RotationMode>()
        .name("rotation-mode")
        .description("Smart: look at the spot that is clicked, kept inside the look the block's facing needs. Snap: the same, rounded to 45 degrees. None: never turn; only blocks that your current look already suits are placed.")
        .defaultValue(RotationMode.Smart)
        .build()
    );

    private final Setting<Boolean> swing = sgPlace.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swings your arm. Off only hides the animation on your screen: the swing is still sent to the server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> airplace = sgPlace.add(new BoolSetting.Builder()
        .name("airplace")
        .description("Allows a block to be placed against nothing when no neighbour can be clicked. Only a fallback: a neighbour that can be clicked is always preferred.")
        .defaultValue(true)
        .build()
    );

    // Named visible-faces-only until 0.4.0-dev.15; the new name drops the old saved value (Meteor ignores a saved setting it no longer knows)
    private final Setting<Boolean> requireLineOfSight = sgPlace.add(new BoolSetting.Builder()
        .name("require-line-of-sight")
        .description("Only click a face when there is a clear line from your eyes to the spot that is clicked.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> waterlog = sgPlace.add(new BoolSetting.Builder()
        .name("waterlog")
        .description("A block that the schematic wants waterlogged is placed, then a water bucket from your hotbar or inventory is used on it. The bucket is emptied by that. Without a bucket (or with this off) the block is placed dry and you get one warning.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> refillWaterBucket = sgPlace.add(new BoolSetting.Builder()
        .name("refill-water-bucket")
        .description("When an empty bucket is carried and a water source block is within reach, picks the water up again, so the next waterlogged block has a bucket. It does not walk to water.")
        .defaultValue(false)
        .visible(waterlog::get)
        .build()
    );

    private final Setting<Integer> sneakTicks = sgPlace.add(new IntSetting.Builder()
        .name("sneak-ticks")
        .description("Ticks the sneak key is held before a click that has to sneak (a click on a chest or a door would open it otherwise). The server reads sneak from the packet of the previous tick.")
        .defaultValue(2)
        .range(1, 6)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Integer> actionDelay = sgPlace.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Ticks between steps when fetching materials (opening a container, taking a stack, closing it). At most one thing is done per tick.")
        .defaultValue(3)
        .range(0, 20)
        .sliderRange(0, 10)
        .build()
    );

    // Order

    private final Setting<LayerOrder> layerOrder = sgOrder.add(new EnumSetting.Builder<LayerOrder>()
        .name("layer-order")
        .description("Builds one y level at a time: blocks above it are left alone until it is complete, then the printer moves to the next. BottomToTop: lowest first. TopToBottom: highest first. MostEfficient: the layer with the most blocks ready now, then the nearest in height. A layer that can't be built yet (a hanging block under a later layer) is carried over and tried again. Off: no layers, blocks are ranked by order and bottom-up.")
        .defaultValue(LayerOrder.BottomToTop)
        .build()
    );

    private final Setting<Order> order = sgOrder.add(new EnumSetting.Builder<Order>()
        .name("order")
        .description("LayerThenDistance: a layer at a time, nearest block first. Distance: always the nearest block. Layer: a layer at a time, in a fixed order. With layer-order on, this ranks the blocks inside the active layer.")
        .defaultValue(Order.LayerThenDistance)
        .build()
    );

    private final Setting<Boolean> bottomUp = sgOrder.add(new BoolSetting.Builder()
        .name("bottom-up")
        .description("Build from the lowest layer up. Off builds from the top down. Only used when layer-order is Off.")
        .defaultValue(true)
        .visible(() -> layerOrder.get() == LayerOrder.Off)
        .build()
    );

    private final Setting<Boolean> scaffold = sgOrder.add(new BoolSetting.Builder()
        .name("scaffold")
        .description("When a block of the active layer has nothing to be placed against, build a short chain of spare blocks (blocks in your inventory that the schematic doesn't use) up to it, and break them again when nothing needs them. Never breaks printed blocks. Needs layer-order and air-place off.")
        .defaultValue(true)
        .visible(() -> layerOrder.get() != LayerOrder.Off)
        .build()
    );

    private final Setting<Integer> scaffoldMax = sgOrder.add(new IntSetting.Builder()
        .name("scaffold-max")
        .description("Most scaffold blocks standing at once.")
        .defaultValue(64)
        .range(1, 512)
        .sliderRange(1, 128)
        .visible(() -> layerOrder.get() != LayerOrder.Off && scaffold.get())
        .build()
    );

    // Wrong blocks

    private final Setting<WrongBlocks> wrongBlocks = sgWrong.add(new EnumSetting.Builder<WrongBlocks>()
        .name("wrong-blocks")
        .description("What to do with a block that is where the schematic wants another one. SkipAndList: leave it and list it at the end. BreakAndReplace: break it and build the right one (the drops are not collected). Pause: stop until you have dealt with it.")
        .defaultValue(WrongBlocks.SkipAndList)
        .build()
    );

    private final Setting<Boolean> neverBreakContainers = sgWrong.add(new BoolSetting.Builder()
        .name("never-break-containers")
        .description("BreakAndReplace never breaks a block that has contents (chests, shulker boxes, furnaces and the like): it is skipped and listed instead.")
        .defaultValue(true)
        .visible(() -> wrongBlocks.get() == WrongBlocks.BreakAndReplace)
        .build()
    );

    // Materials

    private final Setting<Boolean> disableWhenOut = sgMaterials.add(new BoolSetting.Builder()
        .name("disable-when-out-of-materials")
        .description("Off: running out of an item never turns the Printer off. It skips the blocks it has no item for, keeps placing the rest, and goes back to them when the item turns up (restock, stash delivery, ender chest). On: when an item is missing and no source or stash order can supply it, the Printer turns itself off.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> useShulkers = sgMaterials.add(new BoolSetting.Builder()
        .name("use-shulkers")
        .description("Takes materials out of shulker boxes in your inventory: the box is placed, emptied of what is needed, mined and picked up again. Needs Baritone to walk back for it when it is out of reach.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useContainers = sgMaterials.add(new BoolSetting.Builder()
        .name("use-containers")
        .description("Takes materials out of the containers listed below.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<String>> containers = sgMaterials.add(new StringListSetting.Builder()
        .name("containers")
        .description("Containers to fetch materials from, one per line as x,y,z|dimension (for example 10,64,-20|minecraft:overworld). Press the add-container key while looking at one to add it.")
        .visible(useContainers::get)
        .build()
    );

    private final Setting<Keybind> addContainerKey = sgMaterials.add(new KeybindSetting.Builder()
        .name("add-container-key")
        .description("Adds the container you are looking at to the list above (or removes it when it is already there).")
        .defaultValue(Keybind.none())
        .visible(useContainers::get)
        .action(this::toggleLookedAtContainer)
        .build()
    );

    private final Setting<Boolean> useEnderChest = sgMaterials.add(new BoolSetting.Builder()
        .name("use-ender-chest")
        .description("Takes materials out of your ender chest, which you carry: it is placed, shulker boxes (and plain stacks) are taken out, and after unpacking the boxes go back in and it is picked up again. Needs an ender chest in your inventory or hotbar and a silk touch pickaxe (or ender-chest-no-silk set to MineAnyway).")
        .defaultValue(false)
        .build()
    );

    private final Setting<EchestNoSilk> echestNoSilk = sgMaterials.add(new EnumSetting.Builder<EchestNoSilk>()
        .name("ender-chest-no-silk")
        .description("Without a silk touch pickaxe. Refuse: the ender chest is not used. MineAnyway: it is mined with any pickaxe and the ender chest is LOST (it drops 8 obsidian), so the boxes can't be put back.")
        .defaultValue(EchestNoSilk.Refuse)
        .visible(useEnderChest::get)
        .build()
    );

    private final Setting<Boolean> keepForeign = sgMaterials.add(new BoolSetting.Builder()
        .name("keep-foreign-items")
        .description("Items that are not on the build list stay in their slots: they are never moved out of the hotbar to make room for blocks. (Weapons, food, totems and the like are always kept.)")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> freeSlots = sgMaterials.add(new IntSetting.Builder()
        .name("free-slots")
        .description("Inventory slots left empty when taking materials, so a shulker box can come back and nothing has to be dropped.")
        .defaultValue(2)
        .range(1, 20)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Integer> pickupTimeout = sgMaterials.add(new IntSetting.Builder()
        .name("pickup-timeout")
        .description("Seconds a shulker box may take to be mined and picked up again before it is left where it is (and you are told where).")
        .defaultValue(20)
        .range(5, 120)
        .sliderRange(5, 60)
        .visible(() -> useShulkers.get() || useEnderChest.get())
        .build()
    );

    // Stash orders

    private final Setting<Boolean> orderFromStash = sgStash.add(new BoolSetting.Builder()
        .name("order-from-stash")
        .description("Orders the materials you are running low on from the Stash Manager server, from the stash bot that holds them, by itself and without asking. The bot delivers into a chest; you collect it and put it in your inventory, and printing resumes by itself. Spends stash stock. Needs the Stash Window, connected and logged in, and the order permission.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> orderWhenBelow = sgStash.add(new IntSetting.Builder()
        .name("order-when-below")
        .description("Orders an item when you carry fewer than this many of it (or fewer than the build still needs, when that is less) and what is on order doesn't cover the rest.")
        .defaultValue(64)
        .range(1, 2304)
        .sliderRange(16, 576)
        .visible(orderFromStash::get)
        .build()
    );

    private final Setting<Integer> orderMaxShulkers = sgStash.add(new IntSetting.Builder()
        .name("order-max-shulkers")
        .description("The most shulker boxes one order may need. The server takes at most 54.")
        .defaultValue(9)
        .range(1, 54)
        .sliderRange(1, 54)
        .visible(orderFromStash::get)
        .build()
    );

    private final Setting<Integer> orderMaxShulkersPerRun = sgStash.add(new IntSetting.Builder()
        .name("order-max-shulkers-per-run")
        .description("The most shulker boxes all orders of one build together may need. The count starts again when you turn the module on, when you leave the world, and when the schematic, its origin, rotation or mirror changes; a reload keeps it.")
        .defaultValue(27)
        .range(1, 540)
        .sliderRange(1, 108)
        .visible(orderFromStash::get)
        .build()
    );

    // Baritone

    private final Setting<Boolean> walk = sgBaritone.add(new BoolSetting.Builder()
        .name("walk")
        .description("Lets Baritone walk to the next blocks and to containers. Needs Baritone installed. Off: only what is in reach is built.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> safeWalk = sgBaritone.add(new BoolSetting.Builder()
        .name("safe-walk")
        .description("While walking: no parkour, no diagonal drops, no falls beyond one block, and never breaking blocks. Placing blocks is only what movement StandOnBuild allows.")
        .defaultValue(true)
        .visible(walk::get)
        .build()
    );

    private final Setting<Movement> movement = sgBaritone.add(new EnumSetting.Builder<Movement>()
        .name("movement")
        .description("How the Printer gets to blocks that are out of reach. StandOnBuild (default): Baritone walks and climbs onto the blocks already printed, placing your spare blocks (carried blocks the schematic does not use) to pillar up, and never breaking anything. Fly: flies with a flight module and never walks. StandThenFly: stands on the build, and flies when walking gets nowhere. Flying can get you kicked on servers that don't allow it (for example 2b2t's anticheat); it is off unless you pick Fly or StandThenFly.")
        .defaultValue(Movement.StandOnBuild)
        .visible(walk::get)
        .onChanged(m -> this.movePolicy.setMode(m.mode))
        .build()
    );

    private final Setting<List<Module>> flightModules = sgBaritone.add(new ModuleListSetting.Builder()
        .name("flight-modules")
        .description("The modules to fly with for Fly and StandThenFly: the first one that is already on is used, otherwise the first in the list is switched on while flying and off again afterwards (only if the Printer switched it on). It must move you with the movement keys, as Meteor's Flight does. Flight can get you kicked on servers that don't allow it.")
        .defaultValue(Flight.class)
        .visible(() -> walk.get() && movement.get() != Movement.StandOnBuild)
        .build()
    );

    private final Setting<Boolean> removeStandBlocks = sgBaritone.add(new BoolSetting.Builder()
        .name("remove-stand-blocks")
        .description("Breaks the blocks Baritone placed to climb (outside the schematic) once nothing needs them, like scaffold. Off: they are left standing.")
        .defaultValue(true)
        .visible(() -> walk.get() && movement.get() != Movement.Fly)
        .build()
    );

    private final Setting<Boolean> pauseOnInput = sgBaritone.add(new BoolSetting.Builder()
        .name("pause-on-input")
        .description("Stops walking while you press a movement key yourself.")
        .defaultValue(true)
        .visible(walk::get)
        .build()
    );

    // Sounds

    private final Setting<Boolean> finishSound = sgSounds.add(new BoolSetting.Builder()
        .name("finish-sound")
        .description("Plays a sound when the Printer finishes everything it can build.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<SoundEvent>> finishSoundEvent = sgSounds.add(new SoundEventListSetting.Builder()
        .name("finish-sound-event")
        .description("The sound for finishing (the first one in the list is played).")
        .defaultValue(SoundEvents.PLAYER_LEVELUP)
        .visible(finishSound::get)
        .build()
    );

    private final Setting<Boolean> errorSound = sgSounds.add(new BoolSetting.Builder()
        .name("error-sound")
        .description("Plays a sound when the Printer stops, or pauses because it needs you (stuck, no materials source, a failed fetch).")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<SoundEvent>> errorSoundEvent = sgSounds.add(new SoundEventListSetting.Builder()
        .name("error-sound-event")
        .description("The sound for a stop or an error pause (the first one in the list is played).")
        .defaultValue(SoundEvents.ANVIL_LAND)
        .visible(errorSound::get)
        .build()
    );

    private final Setting<Double> soundVolume = sgSounds.add(new DoubleSetting.Builder()
        .name("sound-volume")
        .description("How loud the sounds are. At most one sound every 1.5 seconds.")
        .defaultValue(1.0)
        .min(0)
        .sliderRange(0, 2)
        .visible(() -> finishSound.get() || errorSound.get())
        .build()
    );

    // Saved: items left in the world by a shulker box that could not be picked up, as id|x|y|z|dimension|server|name
    private final Setting<List<String>> orphans = sgBaritone.add(new StringListSetting.Builder()
        .name("orphans")
        .description("Items left in the world by an interrupted shulker box pick-up, listed next time you are there.")
        .visible(() -> false)
        .build()
    );

    // ---------------------------------------------------------------------------------------------------------------
    // Constants

    private static final long SCAN_BUDGET_NANOS = 2_000_000L;
    private static final long SWEEP_BUDGET_NANOS = 500_000L;
    private static final int SWEEP_BATCH = 512;
    private static final int MAX_LIST = 10;
    private static final int STORED_LIST = 400;
    private static final int DEFER_TICKS = 20, DEFER_TRIES = 3;
    private static final int WALK_GIVE_UP = 20 * 120;
    /** Ticks a hovering player may take to come down before a materials trip is skipped. */
    private static final int LAND_GIVE_UP = 20 * 20;
    private static final int RECIPE_CACHE_MAX = 4096;
    private static final int ORPHAN_CHECK = 40;
    private static final int MAX_UNPACKS = 6;
    private static final Set<StateKind> CACHEABLE = Set.of(StateKind.FULL, StateKind.AXIS, StateKind.HFACING, StateKind.HFACING_LOOK, StateKind.HFACING_CW,
        StateKind.FACING6_LOOK, StateKind.FACING6_LOOK_SAME, StateKind.FACING6_FACE, StateKind.HOPPER, StateKind.STAIRS, StateKind.SLAB,
        StateKind.COUNT);

    // Block and item ids to the game's objects, built once (the key type differs between versions, so ids are strings)
    private static Map<String, Block> blocksById;
    private static Map<String, Item> itemsById;
    private static final Map<String, String> ITEM_OF_BLOCK = new HashMap<>();

    private static void registries() {
        if (blocksById != null) return;
        Map<String, Block> blocks = new HashMap<>();
        for (Block b : BuiltInRegistries.BLOCK) blocks.put(BuiltInRegistries.BLOCK.getKey(b).toString(), b);
        Map<String, Item> items = new HashMap<>();
        for (Item i : BuiltInRegistries.ITEM) items.put(BuiltInRegistries.ITEM.getKey(i).toString(), i);
        itemsById = items;
        blocksById = blocks;
    }

    /** The item a block is placed from, by ids. */
    private static final MaterialPlanner.Items ITEMS = blockId -> {
        registries();
        if (ITEM_OF_BLOCK.containsKey(blockId)) return ITEM_OF_BLOCK.get(blockId);
        Block block = blocksById.get(blockId);
        Item item = block == null ? Items.AIR : block.asItem();
        String id = item == Items.AIR ? null : BuiltInRegistries.ITEM.getKey(item).toString();
        // Farmland and dirt paths are made from dirt; a potted plant is an empty pot (its plant is the cell's extra cost)
        if (HandTables.PLACED_FROM.containsKey(blockId)) id = HandTables.PLACED_FROM.get(blockId);
        else if (block instanceof FlowerPotBlock) id = HandTables.FLOWER_POT;
        ITEM_OF_BLOCK.put(blockId, id);
        return id;
    };

    /**
     * A block that a tool finishes. {@code action}: the tool and the raw block (for a property such as a lit candle, the
     * block itself). {@code rawItem}: the item of the raw block, null when the wanted block is placed as it is and only a
     * property is set. {@code only}: the wanted block has no item (farmland, a potted plant), so the raw one is always what
     * is placed. {@code plant}: the item a flower pot is filled with. {@code rawState}/{@code rawSpec}: how the raw block is
     * placed, when that differs from the wanted block (dirt for farmland, a pumpkin for a carved one); else null.
     */
    private record ToolSource(ToolAction action, Item rawItem, boolean only, Item plant, BlockState rawState, StateSpec rawSpec) {
        Tool tool() {
            return action.tool();
        }

        boolean property() {
            return rawItem == null;
        }
    }

    /** {@code toolSource}: set for a block a tool finishes (stripped logs, farmland, waxed copper, potted plants, ...); else null. */
    private record Info(StateSpec spec, MaterialPlanner.Cost cost, Item item, ToolSource toolSource) {}

    /** What is placed with the click: the cell's own item, or the raw one when {@code raw}. */
    private record Use(Item item, int count, boolean raw) {}

    /** {@code use}/{@code useCount}: what is placed with the click. The cell's own item, or the unstripped one when {@code raw}. */
    private record Prep(BuildOrderer.Cell cell, Info info, PlaceRecipe recipe, BlockHitResult hit, int slot, Item use, int useCount, boolean raw) {}

    private record Awaiting(BuildOrderer.Cell cell, Info info, PlaceRecipe recipe, int tickPlaced, boolean raw) {}

    /** What the world shows at a cell next to what the schematic wants. */
    private enum Seen { BUILT, NEEDS_TOOL, NO }

    /** A block that waits for its tool; {@code info} says what the schematic wants here (the tool is only used on a raw block of it). */
    private record ToolJob(BlockPos pos, Info info, Tool tool, int tries, int lastTick, int since) {}

    private record WaterJob(BlockPos pos, int since, int giveUp, int tries, int lastTick) {}

    private record Deferred(BuildOrderer.Cell cell, int dueTick, int tries, String why) {}

    private record RecipeKey(BlockState want, int mask, boolean air) {}

    private record UseJob(BlockPos pos, int clicks, int giveUpTick) {}

    /** A scaffold block: planned (in the queue), placed (click sent) and, once the world shows it, built. */
    private static final class Scaf {
        final BlockPos pos;
        final Item item;
        final BlockState state;
        final Info info;
        final int since;
        boolean placed, built;
        /** Adopted: a spare block Baritone climbed with, not planned by the Printer (it does not count toward scaffold-max). */
        boolean stand;
        int breakTicks;

        Scaf(BlockPos pos, Item item, BlockState state, Info info, int since) {
            this.pos = pos;
            this.item = item;
            this.state = state;
            this.info = info;
            this.since = since;
        }
    }

    private enum Fin { PLACED, PRESENT, DROPPED, SKIPPED, FAILED }

    /** What the tick found out about the build. */
    private static final class Plan {
        final List<Prep> preps = new ArrayList<>();
        final List<BlockPos> walkParts = new ArrayList<>();
        boolean placeable, walkTarget, lacking, waiting;
        Item lackingItem;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // State of a run

    private PrintLoop loop;
    private int runId;
    private Object runLevel;
    private int tick;

    /** The schematic queue of this activation (null when queue-enabled is off). Its position survives runs, not activations. */
    private BuildQueue queue;

    private BuildSource built;
    private Future<?> loadFuture;
    private String loadError;
    private boolean resolved, scanDone;
    private BuildSource.Cursor cursor;

    private BuildOrderer orderer;
    private final TargetBits targets = new TargetBits();
    private final List<BuildOrderer.Cell> sweep = new ArrayList<>();
    private int sweepAt;
    private BuildOrderer.Cell unloadedHint, cycleHint;
    private MaterialPlanner.Tally tally = new MaterialPlanner.Tally();
    private final Set<Item> buildItems = new HashSet<>();
    private final Map<BlockState, Info> infos = new HashMap<>();
    private final Map<RecipeKey, PlaceSimulator.Confirmed> recipes = new HashMap<>();

    private final ArrayDeque<BuildOrderer.Cell> wrongQueue = new ArrayDeque<>();
    private final Set<Long> wrongKeys = new HashSet<>();
    private final Map<Long, Awaiting> awaiting = new HashMap<>();
    private final Map<Long, Deferred> deferred = new HashMap<>();
    private final Set<Long> forceSneak = new HashSet<>();
    private final List<UseJob> useJobs = new ArrayList<>();
    private BuildOrderer.Cell breaking;
    private int breakTicks;

    private int builtCount, presentCount, scanned, brokenCount;
    private final List<String> skippedLines = new ArrayList<>(), failedLines = new ArrayList<>(), wrongLines = new ArrayList<>();
    private final Map<String, Integer> skippedReasons = new LinkedHashMap<>();
    private boolean reported;

    private PacketBudget budget = new PacketBudget(55);
    private final PrinterActions.Sneak sneak = new PrinterActions.Sneak();
    private LookWindow prevLook;
    private int placeDelay;
    private final List<BlockPos> lastClicked = new ArrayList<>();
    private int lastClickTick = -1000;
    private int openedInstead;
    private int savedSlot = -1;
    private int walkSince = -1;
    private long lastFar = -1000, lastFarInfinite = -1000;
    private List<BuildOrderer.Cell> farCells = List.of();
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private Plan plan;
    private Item currentItem;
    private int currentCost = 1;
    private TargetBits partBits = new TargetBits();
    private int lastMenuTick = -1000, noPlaceTicks, releasedAt = -1000;
    private boolean farTruncated, stuckWarned;
    private Item farLack;
    private Plan lastPlan;
    private final BlockPos.MutableBlockPos scratch2 = new BlockPos.MutableBlockPos();
    private final Map<ItemStack, Boolean> loadedCache = new IdentityHashMap<>();
    private int loadedCacheTick;
    private static final Direction[] DIRECTIONS = Direction.values();

    // Baritone
    private Object walkGoal;
    private boolean usedConfine, usedProtect, walkWarned;
    private int lastWalk;
    // Movement: stand on the build or fly. Baritone path failures are counted over the run for the policy
    private final MovePolicy movePolicy = new MovePolicy(MovePolicy.Mode.STAND);
    private final FlyController fly = new FlyController(() -> flightModules.get(), () -> sneak.holding() || this.toolSneak);
    private int pathFailures, retryWalkAt;
    private HoverPoint.Point hover;
    private int hoverAt = -1000;
    private MovePolicy.Way lastWay = MovePolicy.Way.WALK;
    private boolean standNoSpareWarned, flyNoModuleWarned;
    /** Cells seen empty near the feet while Baritone may place: one that turns into a spare block later was placed by Baritone. */
    private final Set<Long> airSeen = new HashSet<>();
    private static final int PATH_RETRY = 20, HOVER_EVERY = 10, AIR_SEEN_MAX = 4096;
    /** The spare blocks Baritone may place while it walks (empty: walking only), and how long its placing is watched for. */
    private List<Item> standItems = List.of();
    private int standTrackUntil = -1, standPlaced;

    // Tool jobs (logs placed unstripped waiting for an axe click, farmland for a hoe, ...), waterlogging, the layer range, the schematic area
    private static final int TOOL_TRIES = 4, TOOL_WAIT = 5, WATER_TRIES = 4, WATER_WAIT = 5, WATER_GIVE_UP = 20 * 60, WATER_GIVE_UP_PRESENT = 20 * 300, TOOL_GIVE_UP = 20 * 120, LAYER_EVERY = 5;
    private static final long LAYER_BUDGET_NANOS = 1_000_000L;
    private static final int OFFHAND = 40;
    /** Above the combat modules, so their rotations don't take the look this tick (Meteor sets the client look for the first one queued). */
    private static final int ROTATION_PRIORITY = 100;
    private static final ToolRules.Table TOOLS = ToolTables.INSTANCE;
    private final Map<Long, ToolJob> toolJobs = new LinkedHashMap<>();
    private final Map<Long, WaterJob> waterJobs = new LinkedHashMap<>();
    private final Set<Tool> warnedTools = EnumSet.noneOf(Tool.class);
    private final Map<Tool, Integer> toolDone = new EnumMap<>(Tool.class);
    private final int[] toolBlockedUntil = new int[Tool.values().length];
    /** The plant item the hotbar need looks for while a flower pot is being filled. */
    private Item plantWanted;
    /** Set once a shovel or hoe block is seen: the cells above it wait (see {@link #heldByTool}). */
    private boolean airAboveWork;
    /** hasToolAnywhere: asked once per tick per tool; a tool found missing stays missing for {@link #TOOL_MISSING_TICKS} ticks. */
    private final Map<Object, Boolean> toolHasCache = new HashMap<>();
    private final Map<Object, Integer> toolMissingUntil = new HashMap<>();
    private int toolHasTick = -1;
    private static final int TOOL_MISSING_TICKS = 20;
    private boolean warnedDry, toolSneak;
    private int waterBlockedUntil, waterLeft, waterDone, dryCount;
    private final List<String> dryLines = new ArrayList<>();
    private final RecheckQueue rechecks = new RecheckQueue();
    private static final int RECHECK_DELAY = 100, RECHECK_INTERVAL = 200;
    private final List<int[]> skippedWrong = new ArrayList<>();
    private WrongBlocks wrongSigMode;
    private boolean wrongSigNever;
    private long lastSoundMs;
    private double aimYaw, aimPitch;
    private boolean layerLogged;
    private LayerFilter.Mode layerModeLogged;
    // Layer building and scaffolding
    private LayerCursor layers;
    private Integer shownLayer;
    private boolean lastStalled;
    private final Map<Long, Scaf> scaffolds = new ConcurrentHashMap<>();
    private final Map<Item, Info> scaffoldInfos = new HashMap<>();
    private final Map<Long, Integer> scaffoldFail = new HashMap<>();
    private final Set<Integer> scaffoldWarned = new HashSet<>();
    private List<BlockPos> scaffoldRemovable = List.of();
    /** Stand blocks nobody needs but that the player is on or beside: kept until the player has moved off. */
    private List<BlockPos> standShielded = List.of();
    /** Cells where Baritone put a spare block that the schematic wants something else in: ours, broken whatever wrong-blocks says. */
    private final Set<Long> ownWrong = new HashSet<>();
    private final Map<Long, Integer> scaffoldBudgetHits = new HashMap<>();
    /** The walk goal is a walk to take stand blocks down (walk only, ending away from them). */
    private boolean removalWalk, walkNoPlace;
    private int removalSince, removalFails, removalRetryAt;
    private static final int REMOVAL_WALK_MAX = 20 * 20;
    private List<ScaffoldPlanner.Box> scaffoldAreas = List.of();
    private boolean scaffoldNoSpareWarned, scaffoldPlannedNow;
    private int scaffoldTry = -1000, scaffoldCheck = -1000, scaffoldPlacedTotal, scaffoldRemovedTotal;
    private static final int SCAFFOLD_EVERY = 10, SCAFFOLD_CHECK = 5, SCAFFOLD_PLANNED_TTL = 300, SCAFFOLD_FAIL_TTL = 200, SCAFFOLD_BREAK_TRIES = 200;
    /** The most one planning pass may take in all, however many cells ran out of their own time. */
    private static final long SCAFFOLD_TOTAL_NANOS = 4_000_000L;
    private BlockPos refillSource;
    private int refillScanTick = -1000;
    private LayerFilter layer = LayerFilter.ALL;
    private boolean layerDirty, layerRead;
    private int layerParkAt, layerDormantAt;
    // Dormant cells whose block is not there yet (a layer viewed through is "done" when none is left)
    private int holdScanAt = -1, holdScanTodo, holdTodo, holdTick;
    private boolean holdDone;
    private int[] dormant = new int[0];
    private int dormantN;
    private final AreaWatch areaWatch = new AreaWatch();
    private List<int[]> areaBoxes = List.of();
    private boolean awayFromArea;

    // Materials
    private final StashOrders stashOrders = new StashOrders(new StashOrders.Config() {
        @Override public int threshold() { return orderWhenBelow.get(); }
        @Override public int maxShulkersPerOrder() { return orderMaxShulkers.get(); }
        @Override public int maxShulkersPerRun() { return orderMaxShulkersPerRun.get(); }
    });
    private final OrdersView ordersView = new OrdersView();
    private String buildKey = "";
    private Map<String, Long> carriedCache = Map.of();
    private long carriedAt;
    private record EchestMemory(Set<String> ids, long at) {}
    private static final long ECHEST_MEMORY_MS = 10 * 60_000L;
    private EchestMemory echestMemory;
    private final Resupply resupply = new Resupply();
    private final SmartPlaceSession session = new SmartPlaceSession(new SessionHost());
    private final AutoHotbar.Need need = new AutoHotbar.Need("build blocks", s -> currentItem != null && s.is(currentItem) && s.getCount() >= currentCost && !loadedShulker(s), () -> 0, false, () -> currentItem != null);
    // The tools follow the same hotbar rules as the blocks: weapons, food and shulker boxes are never moved out of the way
    private final Map<Tool, AutoHotbar.Need> toolNeeds = makeToolNeeds();
    private final AutoHotbar.Need waterNeed = new AutoHotbar.Need("water bucket", s -> s.is(Items.WATER_BUCKET), () -> 0, false, () -> !waterJobs.isEmpty())
        .sharing(s -> s.is(Items.BUCKET));
    private final AutoHotbar.Need emptyBucketNeed = new AutoHotbar.Need("bucket", s -> s.is(Items.BUCKET), () -> 0, false, () -> waterlog.get() && refillWaterBucket.get() && waterWorkAhead())
        .sharing(s -> s.is(Items.WATER_BUCKET));
    private final AutoHotbar hotbar = new AutoHotbar(() -> true, s -> keepForeign.get() && !s.isEmpty() && !isBuildItem(s),
        m -> warning("%s", m), m -> info("%s", m), allNeeds());

    private Map<Tool, AutoHotbar.Need> makeToolNeeds() {
        Map<Tool, AutoHotbar.Need> m = new EnumMap<>(Tool.class);
        for (Tool t : Tool.values()) m.put(t, new AutoHotbar.Need(t.label(), s -> usableTool(t, s, plantWanted), () -> 0, false, () -> hasJobFor(t)));
        return m;
    }

    private List<AutoHotbar.Need> allNeeds() {
        List<AutoHotbar.Need> all = new ArrayList<>();
        all.add(need);
        all.addAll(toolNeeds.values());
        all.add(waterNeed);
        all.add(emptyBucketNeed);
        return all;
    }

    // Orphans
    private Object orphanLevel;
    private int orphanTick;

    private final BuildOrderer.World worldView = new BuildOrderer.World() {
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        @Override
        public boolean solid(int x, int y, int z) {
            if (!mc.level.isLoaded(pos.set(x, y, z))) return false;
            BlockState s = mc.level.getBlockState(pos);
            return !s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced() && !s.getCollisionShape(mc.level, pos).isEmpty();
        }

        @Override
        public boolean free(int x, int y, int z) {
            return mc.level.isLoaded(pos.set(x, y, z)) && PrinterActions.free(mc.level.getBlockState(pos));
        }

        @Override
        public boolean loaded(int x, int y, int z) {
            return mc.level.isLoaded(pos.set(x, y, z));
        }
    };

    public Printer() {
        super(QwerteAddon.CATEGORY, "printer", "Builds a Litematica schematic (or a .litematic file) block by block with the right facing and state, walking with Baritone and fetching materials from your inventory, shulker boxes, chosen containers and your ender chest, or ordering them from the stash bots.");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Chat: every line the Printer says goes through chatLine(). With chat-messages off nothing reaches the chat (not even
    // Meteor's "toggled" line) and the text is written to the game log instead.

    private static final String[] LEVEL_NAMES = { "info", "warning", "error" };

    private boolean chatLine(int level, String format, Object[] args) {
        if (chatMessages != null && chatMessages.get()) return true;
        String text;
        try {
            text = args == null || args.length == 0 ? format : String.format(format, args);
        } catch (RuntimeException e) {
            text = format;
        }
        QwerteAddon.LOG.info("printer (chat off, {}): {}", LEVEL_NAMES[level], text);
        return false;
    }

    @Override
    public void info(String message, Object... args) {
        if (chatLine(0, message, args)) super.info(message, args);
    }

    @Override
    public void info(Component message) {
        if (chatLine(0, message.getString(), null)) super.info(message);
    }

    @Override
    public void warning(String message, Object... args) {
        if (chatLine(1, message, args)) super.warning(message, args);
    }

    @Override
    public void error(String message, Object... args) {
        if (chatLine(2, message, args)) super.error(message, args);
    }

    @Override
    public void sendToggledMsg() {
        if (chatMessages != null && chatMessages.get()) super.sendToggledMsg();
    }

    /** A warning that is always logged, and goes to the chat only when chat-messages is on. */
    private void logWarn(String text) {
        QwerteAddon.LOG.warn("printer: {}", text);
        if (chatMessages.get()) super.warning("%s", text);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Lifecycle

    @Override
    public void onActivate() {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            error("Join a world first.");
            toggle();
            return;
        }
        // Turning the module on is a new build for the order limits (a reload is not)
        buildKey = "";
        queue = null;
        if (queueEnabled.get()) {
            BuildQueue.Parsed parsed = BuildQueue.parse(queueList.get());
            for (String problem : parsed.errors()) warning("Queue: %s", problem);
            if (parsed.entries().isEmpty()) {
                error("The queue has no usable entry: add schematics to queue, or turn queue-enabled off.");
                toggle();
                return;
            }
            queue = new BuildQueue(parsed.entries());
        }
        startRun();
    }

    @Override
    public void onDeactivate() {
        session.shutdown();
        // Switched off in the middle of a run: say how far it got
        if (loop != null && !reported && built != null) {
            reported = true;
            report(false);
        }
        stopRun();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        session.gameLeft();
        orphanLevel = null;
        echestMemory = null;
        buildKey = "";
        stashOrders.newBuild();
        if (loop != null) {
            stopRun();
            if (isActive()) toggle();
        }
    }

    private void startRun() {
        stopRun();
        runId++;
        runLevel = mc.level;
        // tick keeps counting across runs: a timer left from an earlier run can never look recent
        int ping = pingTicks();
        loop = new PrintLoop(Math.max(PrintLoop.VERIFY_TICKS, ping + 2), PrintLoop.RETRIES, PrintLoop.HYSTERESIS);
        budget = new PacketBudget(packetBudget.get());
        markWrongSig();
        registries();
        StateResolver.prepare();
        orderer = new BuildOrderer(order.get().mode, layerOrder.get() == LayerOrder.Off ? bottomUp.get() : layerOrder.get() != LayerOrder.TopToBottom, airplace.get());
        orderer.setIdleCache(20);
        movePolicy.setMode(movement.get().mode);
        movePolicy.reset();
        lastWay = MovePolicy.Way.WALK;
        pathFailures = retryWalkAt = 0;
        layers = layerOrder.get() == LayerOrder.Off ? null : new LayerCursor(layerOrder.get().mode);
        partBits = new TargetBits();
        stashOrders.reset();
        String key = source.get() + "|" + fileName.get() + "|" + originX.get() + "," + originY.get() + "," + originZ.get() + "|" + rotation.get() + "|" + mirror.get();
        boolean queueEmpty = false;
        if (queue != null) {
            // each queue entry is its own build for the order limits
            queueEmpty = !queue.seek(this::queueProblem, this::queueSkipped);
            key = queueEmpty ? "queue|end" : "queue|" + queue.index() + "|" + queue.current().raw() + "|" + rotation.get() + "|" + mirror.get() + "|"
                + originX.get() + "," + originY.get() + "," + originZ.get();
        }
        if (!key.equals(buildKey)) {
            buildKey = key;
            stashOrders.newBuild();
        }
        if (queueEmpty) loadError = "No schematic of the queue can be built.";
        reported = false;
        layer = LayerFilter.ALL;
        layerRead = false;
        warnOtherModules();
        QwerteAddon.LOG.info("printer: started ({}, blocks-per-tick {}, packet-budget {})", source.get(), blocksPerTick.get(), packetBudget.get());
    }

    private void reload() {
        if (!isActive() || mc.player == null || mc.level == null) return;
        info("Reloading the schematic.");
        stopRun();
        startRun();
    }

    /** Everything the run changed is given back. Safe to call twice and at any time. */
    private void stopRun() {
        if (loadFuture != null) {
            loadFuture.cancel(true);
            loadFuture = null;
        }
        runId++;
        if (loop != null) loop.stop();
        sneak.release();
        resupply.abort();
        stashOrders.reset();
        releaseBaritone();
        if (mc.player != null && mc.level != null && mc.gameMode != null) {
            // A container we clicked open lately is closed, whether or not the flow had noticed it open
            if ((resupply.screenOpen || tick - lastMenuTick <= 40) && mc.player.containerMenu != mc.player.inventoryMenu && !session.active()) mc.player.closeContainer();
            if (savedSlot >= 0 && savedSlot <= 8 && Players.selectedSlot() != savedSlot) InvUtils.swap(savedSlot, false);
        }
        savedSlot = -1;
        resupply.screenOpen = false;
        hotbar.reset();
        currentItem = null;

        loop = null;
        runLevel = null;
        built = null;
        loadError = null;
        resolved = scanDone = false;
        cursor = null;
        orderer = null;
        layers = null;
        shownLayer = null;
        lastStalled = false;
        scaffolds.clear();
        scaffoldFail.clear();
        scaffoldWarned.clear();
        scaffoldRemovable = List.of();
        scaffoldAreas = List.of();
        standShielded = List.of();
        ownWrong.clear();
        scaffoldBudgetHits.clear();
        removalWalk = walkNoPlace = false;
        removalFails = removalRetryAt = 0;
        scaffoldNoSpareWarned = scaffoldPlannedNow = false;
        scaffoldTry = scaffoldCheck = -1000;
        scaffoldPlacedTotal = scaffoldRemovedTotal = 0;
        scaffoldFailures = 0;
        targets.clear();
        sweep.clear();
        sweepAt = 0;
        unloadedHint = cycleHint = null;
        tally = new MaterialPlanner.Tally();
        buildItems.clear();
        infos.clear();
        airAboveWork = false;
        toolHasCache.clear();
        toolMissingUntil.clear();
        recipes.clear();
        wrongQueue.clear();
        wrongKeys.clear();
        awaiting.clear();
        deferred.clear();
        forceSneak.clear();
        useJobs.clear();
        breaking = null;
        farCells = List.of();
        farTruncated = false;
        farLack = null;
        lastPlan = null;
        noPlaceTicks = 0;
        stuckWarned = false;
        lastMenuTick = -1000;
        loadedCache.clear();
        lastClicked.clear();
        lastFar = lastFarInfinite = -1000;
        lastClickTick = -1000;
        releasedAt = -1000;
        loadedCacheTick = 0;
        openedInstead = 0;
        rechecks.clear();
        skippedWrong.clear();
        layerLogged = false;
        layerModeLogged = null;
        plan = null;
        prevLook = null;
        placeDelay = 0;
        walkSince = -1;
        toolJobs.clear();
        waterJobs.clear();
        warnedTools.clear();
        toolDone.clear();
        Arrays.fill(toolBlockedUntil, 0);
        plantWanted = null;
        warnedDry = toolSneak = false;
        waterBlockedUntil = 0;
        waterLeft = waterDone = dryCount = 0;
        refillSource = null;
        refillScanTick = -1000;
        dryLines.clear();
        layer = LayerFilter.ALL;
        layerDirty = layerRead = false;
        layerParkAt = layerDormantAt = 0;
        holdScanAt = -1;
        holdScanTodo = holdTodo = holdTick = 0;
        holdDone = false;
        dormant = new int[0];
        dormantN = 0;
        areaWatch.reset();
        areaBoxes = List.of();
        awayFromArea = false;
        builtCount = presentCount = scanned = brokenCount = 0;
        skippedLines.clear();
        failedLines.clear();
        wrongLines.clear();
        skippedReasons.clear();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The tick

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        orphanCheck();
        session.tick();
        if (loop == null) return;
        tick++;
        try {
            tickBody();
        } finally {
            // Keys nobody drove this tick go up, whatever way the tick ended
            fly.endTick(tick);
        }
    }

    private void tickBody() {
        if (orderer != null) orderer.advance();

        if (built != null && (!layerRead || tick % LAYER_EVERY == 0)) readLayer();
        PrintLoop.Obs obs = observe();
        PrintLoop.Step step = loop.tick(obs);
        if (step.msg() != null) tell(step.level(), step.msg());
        if (step.msg() != null && step.msg().startsWith(PrintLoop.STUCK_PREFIX)) reportStuck();
        if (step.msg() != null && step.act() == PrintLoop.Act.STOP_WALK && loop != null && loop.errorPause()) playSound(true);
        if (wrongBlocks.get() != wrongSigMode || neverBreakContainers.get() != wrongSigNever) requeueSkippedWrong();

        // Checks that run whatever the loop does
        recoverOpened();
        verifyDue();
        useJobs();
        resupply.tick();

        if (loop == null) return;
        if (orderFromStash.get() && scanDone) stashOrders.tick(System.currentTimeMillis(), ordersView);
        // Tool clicks and waterlogging are clicks on blocks that were just placed: done before the player can walk out of reach
        toolSneak = false;
        boolean acted = false;
        if (scanDone && step.act() != PrintLoop.Act.FINISH && step.act() != PrintLoop.Act.ABORT && step.act() != PrintLoop.Act.RESUPPLY) {
            acted = toolTick() || waterTick();
        }
        switch (step.act()) {
            case LOAD -> startLoad();
            case SCAN -> scanSlice();
            case PLAN -> planned();
            case PLACE -> {
                releaseWalk();
                if (!acted) placeTick();
            }
            case WALK -> walkTick();
            case STOP_WALK -> releaseWalk();
            case RESUPPLY -> resupply.start();
            case SKIP_WRONG -> skipWrong();
            case BREAK_WRONG -> breakTick();
            case REMOVE_SCAFFOLD -> removeScaffoldTick();
            case FINISH, ABORT -> {
                finishRun(step.act() == PrintLoop.Act.FINISH);
                return;
            }
            default -> {}
        }
        if (step.act() != PrintLoop.Act.PLACE && sneak.holding() && !toolSneak) sneak.release();
        // Baritone's placing is watched for a few ticks after it was last allowed
        if (tick <= standTrackUntil) trackStandBlocks();
        if (loop != null && loop.phase() != PrintLoop.Phase.IDLE && scanDone) {
            sweepStep();
            recheckStep();
            layerPass();
            holdScan();
        }
    }

    private PrintLoop.Obs observe() {
        boolean worldChanged = mc.level != runLevel;
        boolean cancelled = cancelKey.get().isSet() && cancelKey.get().isPressed();
        boolean loaded = built != null;
        boolean building = loaded && scanDone && loop.phase() != PrintLoop.Phase.LOADING && loop.phase() != PrintLoop.Phase.SCANNING && !worldChanged;

        plan = null;
        if (building) {
            layerTick();
            scaffoldMaintain();
        } else if (orderer != null) {
            orderer.setActiveLayer(null);
        }
        boolean otherSession = SmartPlaceSession.busy() && !session.active();
        if (building && !session.active() && !resupply.running() && !otherSession) {
            // While the placing delay runs the last plan is still good: nothing can be placed this tick anyway
            if (placeDelay > 0 && lastPlan != null && lastPlan.placeable) plan = lastPlan;
            else plan = lastPlan = planBuild();
        }

        boolean placeable = plan != null && plan.placeable;
        boolean walkTarget = plan != null && plan.walkTarget;
        boolean lacking = plan != null && plan.lacking;
        String lackingInfo = lacking && plan.lackingItem != null ? itemName(plan.lackingItem) : null;
        boolean full = lacking && plan.lackingItem != null && !hasRoomFor(plan.lackingItem);
        String lackingId = lacking && plan.lackingItem != null ? BuiltInRegistries.ITEM.getKey(plan.lackingItem).toString() : null;
        String orderWait = lackingId != null && orderFromStash.get() ? stashOrders.waitingFor(lackingId) : null;
        String orderProblem = lackingId != null && orderFromStash.get() && orderWait == null ? stashOrders.problemFor(lackingId) : null;

        awayFromArea = checkArea();
        boolean userMoving = walk.get() && pauseOnInput.get() && movementKeyDown();
        // Our own goal was only just let go of: Baritone may need a tick or two to say so
        boolean barBusy = userMoving || SmartPlaceSession.BARITONE && walk.get() && tick - releasedAt >= 2
            && BaritoneHelper.controlledByOther((baritone.api.pathing.goals.Goal) walkGoal);
        boolean canWalk = canMoveNow();

        // With layers on, a wrong block in a layer the cursor has not reached is left alone
        BuildOrderer.Cell firstWrong = firstWrong();
        boolean waitingFlag = otherSession || !useJobs.isEmpty() || plan != null && plan.waiting || jobsCanProgress() || layerDirty || holdScanning();
        lastStalled = building && plan != null && !placeable && !walkTarget && !lacking && !plan.waiting && !waitingFlag && loop.verifying() == 0
            && firstWrong == null && breaking == null && scaffoldIdle() && !deferredWaiting();
        return new PrintLoop.Obs(worldChanged, cancelled, loaded, loadError, scanDone, targets.size(), firstWrong == null ? 0 : wrongQueue.size(),
            firstWrong == null ? null : describeWrong(firstWrong), breaking != null, wrongModeFor(firstWrong), placeable, walkTarget, lacking,
            lackingInfo, !lacking || supplyPossible(lackingId), resupply.running(), resupply.takeFailure(), session.active(), full, barBusy, canWalk,
            waitingFlag, orderWait, orderProblem, disableWhenOut.get(), layerHold(), scaffoldLeft(), scaffoldRemovableNow(),
            layers != null && layers.working());
    }

    /** What the loop does with the first wrong block: the setting, except that blocks of ours are always broken. */
    private PrintLoop.Wrong wrongModeFor(BuildOrderer.Cell first) {
        return first != null && ownWrong.contains(key(first)) ? PrintLoop.Wrong.BREAK_AND_REPLACE : wrongBlocks.get().mode;
    }

    private BuildOrderer.Cell firstWrong() {
        // Spare blocks Baritone put into the build are ours: they go first
        if (!ownWrong.isEmpty()) {
            for (BuildOrderer.Cell c : wrongQueue) if (ownWrong.contains(key(c)) && (layers == null || layers.eligible(c.y()))) return c;
        }
        if (layers == null) return wrongQueue.peekFirst();
        for (BuildOrderer.Cell c : wrongQueue) if (layers.eligible(c.y())) return c;
        return null;
    }

    /** Moves the layer cursor and tells the orderer which y it may offer. */
    private void layerTick() {
        if (orderer == null) return;
        if (layers == null) {
            orderer.setActiveLayer(null);
            return;
        }
        Integer a = layers.update(targets, mc.player.getBlockY(), y -> orderer.readyCount(y, 512, box(), worldView), lastStalled);
        orderer.setActiveLayer(a);
        if (java.util.Objects.equals(a, shownLayer)) return;
        shownLayer = a;
        // The searches and the plan of the layer before are no good for this one
        lastFar = -1000;
        lastPlan = null;
        farCells = List.of();
        unloadedHint = cycleHint = null;
        walkSince = -1;
        if (a != null) {
            QwerteAddon.LOG.info("printer: layer Y={}: {} block(s), {} carried over", a, targets.open(a), layers.carriedCount());
            info("Layer Y=%d: %d blocks", a, targets.open(a));
        } else if (layers.free()) {
            QwerteAddon.LOG.info("printer: no layer can be built on its own; building whatever is possible anywhere");
            info("No layer can be built on its own right now: building whatever is possible, anywhere.");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Loading and scanning

    private void startLoad() {
        int id = runId;
        Path root;
        try {
            root = FabricLoader.getInstance().getGameDir().resolve("schematics");
        } catch (RuntimeException e) {
            loadError = "The game folder could not be found.";
            return;
        }
        SourcePicker.Config cfg;
        if (queue != null) {
            BuildQueue.Entry e = queue.current();
            if (e == null) {
                loadError = "No schematic of the queue is left.";
                return;
            }
            // a file entry is placed by its own origin when it has one, else by the origin settings; rotation and mirror are the settings'
            int[] o = e.origin();
            cfg = new SourcePicker.Config(e.kind() == BuildQueue.Kind.FILE ? SourcePicker.Mode.FILE : SourcePicker.Mode.LITEMATICA_BY_NAME, e.name(), e.name(),
                o != null ? o[0] : originX.get(), o != null ? o[1] : originY.get(), o != null ? o[2] : originZ.get(), rotation.get(), mirror.get(), root,
                SchematicFile.DEFAULT_TIMEOUT);
            info("Loading %s...", queue.label());
        } else {
            String name = fileName.get();
            if (source.get() != Source.LitematicaLatest && name.isBlank()) {
                loadError = "Set file-name first.";
                return;
            }
            cfg = new SourcePicker.Config(source.get().mode, name, name, originX.get(), originY.get(), originZ.get(), rotation.get(),
                mirror.get(), root, SchematicFile.DEFAULT_TIMEOUT);
            info("Loading the schematic...");
        }
        Future<?> f = SourcePicker.pick(cfg, picked -> {
            if (id != runId || loop == null) return;
            if (!picked.ok() && queue != null) {
                // an entry that fails to load is skipped like a missing one
                queueSkipped(queue.current(), LitematicReader.clean(picked.error(), 160));
                queue.skip();
                startRun();
                return;
            }
            if (!picked.ok()) {
                loadError = LitematicReader.clean(picked.error(), 240);
                return;
            }
            built = picked.source();
            cursor = built.start();
            loadFuture = null;
        });
        if (id == runId && built == null && loadError == null) loadFuture = f;
    }

    private void scanSlice() {
        if (built == null) return;
        if (!resolved) {
            resolved = built.resolveSome(SCAN_BUDGET_NANOS);
            return;
        }
        if (scanDone) return;
        if (!layerRead) readLayer();
        scanDone = built.scan(cursor, SCAN_BUDGET_NANOS, this::onCell);
    }

    private void onCell(int x, int y, int z, BlockState want) {
        Info in = infoOf(want);
        if (in.item() != null) buildItems.add(in.item());
        if (in.toolSource() != null) {
            if (in.toolSource().rawItem() != null) buildItems.add(in.toolSource().rawItem());
            if (in.toolSource().plant() != null) buildItems.add(in.toolSource().plant());
        }
        if (in.cost().skipped() || in.spec().part()) {
            MaterialPlanner.Cost c = tally.add(in.cost());
            if (c.skipped()) noteSkipped(c.skip(), x, y, z);
            else partBits.add(x, y, z);
            return;
        }
        // Outside Litematica's render layers: not placed, not counted, not ordered. Kept to come back when the layers change.
        if (!layer.contains(x, y, z)) {
            dormantAdd(x, y, z);
            return;
        }
        MaterialPlanner.Cost c = tally.add(in.cost());
        scratch.set(x, y, z);
        BuildOrderer.Cell cell = BuildOrderer.Cell.of(x, y, z, in.spec(), null);
        if (mc.level.isLoaded(scratch)) {
            // Looked at first: a block that is already right never enters the queue, a wrong one goes straight to its own queue
            BlockState st = mc.level.getBlockState(scratch);
            Seen seen = seen(in, st);
            if (seen != Seen.NO) {
                tally.remove(c);
                presentCount++;
                if (seen == Seen.NEEDS_TOOL) addTool(x, y, z, in);
                else queueWater(in, st, scratch.immutable(), true);
                return;
            }
            if (!PrinterActions.free(st)) {
                if (!targets.add(x, y, z)) {
                    tally.remove(c);
                    return;
                }
                if (wet(in)) waterLeft++;
                scanned++;
                wrongKeys.add(key(cell));
                wrongQueue.add(cell);
                sweep.add(cell);
                return;
            }
        }
        if (!orderer.add(cell)) {
            tally.remove(c);
            return;
        }
        targets.add(x, y, z);
        if (wet(in)) waterLeft++;
        sweep.add(cell);
        scanned++;
    }

    private static boolean wet(Info in) {
        return in != null && "true".equals(in.spec().props().get("waterlogged"));
    }

    private void planned() {
        info("Loaded %s (%s): %d block(s) to place, %d skipped.", LitematicReader.clean(built.label(), 60), built.mode(), scanned,
            skippedReasons.values().stream().mapToInt(Integer::intValue).sum());
        for (String note : built.notes()) warning("%s", LitematicReader.clean(note, 240));
        for (Map.Entry<String, Integer> e : built.problems().entrySet()) warning("%s", "Not built: " + LitematicReader.clean(e.getKey(), 80) + " x" + e.getValue());
        if (layer.active()) info("Litematica's render layers are on (%s): %d block(s) outside them are left out until the layers change.", layer.mode(), dormantN);
        materialsSummary();
        sendList("Skipped", skippedList());
    }

    private void materialsSummary() {
        Map<String, Long> have = haveById();
        List<MaterialPlanner.Line> lines = MaterialPlanner.lines(tally.need(), have);
        long total = tally.total();
        long missing = 0;
        for (MaterialPlanner.Line l : lines) missing += l.missing();
        info("Materials: %d block(s) in %d kind(s); you carry enough for all but %d.", total, lines.size(), missing);
        int shown = 0;
        StringBuilder sb = new StringBuilder();
        for (MaterialPlanner.Line l : lines) {
            if (l.missing() <= 0) continue;
            if (shown++ >= MAX_LIST) break;
            Item item = itemsById.get(l.item());
            String name = item == null ? LitematicReader.clean(l.item(), 40) : itemName(item);
            sb.append(shown > 1 ? "; " : "").append(name).append(" short ").append(l.missing()).append(" (need ").append(l.need()).append(", have ").append(l.have());
            sb.append(')');
        }
        if (sb.length() > 0) info("%s", "Short of: " + sb);
        if (orderFromStash.get()) stashOrders.summary(MaterialPlanner.missing(tally.need(), have), m -> info("%s", m));
    }

    private void noteSkipped(String reason, int x, int y, int z) {
        skippedReasons.merge(reason, 1, Integer::sum);
        if (skippedLines.size() < STORED_LIST) skippedLines.add(reason + " at " + x + " " + y + " " + z);
    }

    private List<String> skippedList() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : skippedReasons.entrySet()) out.add(e.getKey() + " x" + e.getValue());
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Cells: what the schematic wants, what the world has

    private Info infoOf(BlockState want) {
        registries();
        Info in = infos.get(want);
        if (in == null) {
            StateSpec spec = StateClassifier.classify(want);
            MaterialPlanner.Cost cost = MaterialPlanner.cost(spec, ITEMS);
            ToolSource ts = null;
            if (!spec.skipped() && !cost.skipped()) {
                if (want.getBlock() instanceof FlowerPotBlock pot && pot.getPotted() != Blocks.AIR) {
                    // A potted plant: an empty pot, then the plant on it. Both are materials.
                    Item plant = ToolTables.plantOf(want.getBlock());
                    if (plant == null) {
                        cost = new MaterialPlanner.Cost(null, 0, spec.blockId() + " has no plant item to fill the pot with");
                    } else {
                        cost = cost.withExtra(BuiltInRegistries.ITEM.getKey(plant).toString(), 1);
                        BlockState pot0 = Blocks.FLOWER_POT.defaultBlockState();
                        ts = new ToolSource(new ToolAction(Tool.PLANT, HandTables.FLOWER_POT), Items.FLOWER_POT, true, plant, pot0, StateClassifier.classify(pot0));
                    }
                } else {
                    ts = toolSourceOf(spec);
                }
                if (ts != null && ts.action().needsAirAbove()) airAboveWork = true;
            }
            in = new Info(spec, cost, cost.item() == null ? null : itemsById.get(cost.item()), ts);
            infos.put(want, in);
        }
        return in;
    }

    /** For a block a tool finishes (logs, farmland, waxed copper, carved pumpkins, lit candles, ...): the tool and the raw block. */
    private static ToolSource toolSourceOf(StateSpec spec) {
        Tool property = ToolRules.propertyTool(spec.blockId(), spec.props());
        if (property != null) return new ToolSource(new ToolAction(property, spec.blockId()), null, false, null, null, null);
        List<ToolAction> actions = TOOLS.actionsFor(spec.blockId());
        if (actions.isEmpty()) return null;
        ToolAction pref = ToolRules.preferred(actions);
        Block raw = blocksById.get(pref.rawId());
        Item rawItem = raw == null ? Items.AIR : raw.asItem();
        if (rawItem == Items.AIR) return null;
        boolean only = HandTables.toolOnly(spec.blockId());
        // Placed differently from the wanted block: dirt for farmland, a pumpkin for a carved one (same class: logs, copper, dirt)
        BlockState rawState = only || pref.tool() == Tool.SHEARS ? raw.defaultBlockState() : null;
        return new ToolSource(pref, rawItem, only, null, rawState, rawState == null ? null : StateClassifier.classify(rawState));
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static long key(BuildOrderer.Cell c) {
        return key(c.x(), c.y(), c.z());
    }

    private static BlockPos posOf(BuildOrderer.Cell c) {
        return new BlockPos(c.x(), c.y(), c.z());
    }

    /** Compares a cell with the world: built (done), wrong block in the way (queued), or still to do. */
    private void evaluate(BuildOrderer.Cell cell, Info in, BlockState st) {
        Seen seen = seen(in, st);
        if (seen != Seen.NO) {
            if (seen == Seen.NEEDS_TOOL) addTool(cell.x(), cell.y(), cell.z(), in);
            else queueWater(in, st, posOf(cell), true);
            finish(cell, in, Fin.PRESENT, null);
            return;
        }
        if (!PrinterActions.free(st) && wrongKeys.add(key(cell))) {
            orderer.remove(cell);
            wrongQueue.add(cell);
        }
    }

    /** A cell leaves the build for good, once. */
    private void finish(BuildOrderer.Cell cell, Info in, Fin how, String why) {
        if (!targets.remove(cell.x(), cell.y(), cell.z())) return;
        long k = key(cell);
        orderer.remove(cell);
        orderer.unplaced(cell.x(), cell.y(), cell.z());
        if (wrongKeys.remove(k)) wrongQueue.removeIf(c -> key(c) == k);
        ownWrong.remove(k);
        awaiting.remove(k);
        deferred.remove(k);
        forceSneak.remove(k);
        if (loop != null) loop.forget(k);
        if (breaking != null && key(breaking) == k) breaking = null;
        if (in != null) tally.remove(in.cost());
        if (wet(in) && waterLeft > 0) waterLeft--;
        String where = cell.x() + " " + cell.y() + " " + cell.z();
        String what = in == null ? "?" : in.spec().describe();
        switch (how) {
            case PLACED -> {
                builtCount++;
                rechecks.add(cell.x(), cell.y(), cell.z(), tick, RECHECK_DELAY);
            }
            case PRESENT -> {
                presentCount++;
                rechecks.add(cell.x(), cell.y(), cell.z(), tick, RECHECK_DELAY);
            }
            case DROPPED -> {}
            case SKIPPED -> {
                if (skippedWrong.size() < 20000) skippedWrong.add(new int[] { cell.x(), cell.y(), cell.z() });
                if (wrongLines.size() < STORED_LIST) wrongLines.add(why + " at " + where + " (wanted " + what + ")");
            }
            case FAILED -> {
                if (failedLines.size() < STORED_LIST) failedLines.add(what + " at " + where + ": " + why);
            }
        }
    }

    private String describeWrong(BuildOrderer.Cell c) {
        scratch.set(c.x(), c.y(), c.z());
        return StateClassifier.idOf(mc.level.getBlockState(scratch).getBlock()) + " at " + c.x() + " " + c.y() + " " + c.z();
    }

    /** Looks at a slice of the cells each tick: built ones leave, wrong ones are queued, and the nearest unloaded one is remembered. */
    private void sweepStep() {
        if (sweep.isEmpty()) return;
        long end = System.nanoTime() + SWEEP_BUDGET_NANOS;
        int n = 0;
        while (!sweep.isEmpty() && n++ < SWEEP_BATCH && System.nanoTime() < end) {
            if (sweepAt >= sweep.size()) {
                sweepAt = 0;
                unloadedHint = cycleHint;
                cycleHint = null;
            }
            BuildOrderer.Cell c = sweep.get(sweepAt);
            if (!targets.contains(c.x(), c.y(), c.z())) {
                // While the layer pass walks the list by index nothing may be moved under it
                if (layerDirty) {
                    sweepAt++;
                    continue;
                }
                sweep.set(sweepAt, sweep.get(sweep.size() - 1));
                sweep.remove(sweep.size() - 1);
                continue;
            }
            sweepAt++;
            long k = key(c);
            Deferred waiting = deferred.get(k);
            if (awaiting.containsKey(k) || waiting != null && waiting.dueTick() != Integer.MAX_VALUE || wrongKeys.contains(k)) continue;
            scratch.set(c.x(), c.y(), c.z());
            if (!mc.level.isLoaded(scratch)) {
                // With a layer active only its cells are worth walking to
                if (orderer.activeLayer() != null && c.y() != orderer.activeLayer()) continue;
                if (cycleHint == null || nearer(c, cycleHint)) cycleHint = c;
                // Known at once, not only when the sweep wraps: it is where to walk when nothing else is in reach
                if (unloadedHint == null || nearer(c, unloadedHint)) unloadedHint = c;
                continue;
            }
            BlockState want = built.stateAt(scratch);
            if (want == null || want.isAir()) {
                finish(c, null, Fin.DROPPED, null);
                continue;
            }
            evaluate(c, infoOf(want), mc.level.getBlockState(scratch));
        }
    }

    private boolean nearer(BuildOrderer.Cell a, BuildOrderer.Cell b) {
        return mc.player.distanceToSqr(a.x() + 0.5, a.y() + 0.5, a.z() + 0.5) < mc.player.distanceToSqr(b.x() + 0.5, b.y() + 0.5, b.z() + 0.5);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Planning one tick of building

    private double reach() {
        return Math.max(1.0, Math.min(range.get(), mc.player.blockInteractionRange()) - 0.25);
    }

    private BuildOrderer.Box box() {
        AABB b = mc.player.getBoundingBox();
        return new BuildOrderer.Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    private Plan planBuild() {
        Plan p = new Plan();
        if (built == null || orderer == null) return p;
        retryDeferred();
        Vec3 eye = mc.player.getEyePosition();
        double reach = reach();
        BuildOrderer.Box box = box();
        int n = Math.max(8, Math.min(65, blocksPerTick.get() * 2));
        Map<Item, Integer> have = inventoryCounts();
        Item[] lack = { null };
        Map<Object, Boolean> carried = new HashMap<>();
        // A ready cell whose materials are not carried must not take one of the places: the ones behind it may be placeable
        // (A cell outside Litematica's render layers is never offered; that is not a missing item.)
        Predicate<BuildOrderer.Cell> usable = c -> {
            Scaf sc0 = scaffolds.get(key(c));
            if (sc0 != null) return have.getOrDefault(sc0.item, 0) >= 1;
            if (!layer.contains(c.x(), c.y(), c.z())) return false;
            BlockState w = built.stateAt(scratch2.set(c.x(), c.y(), c.z()));
            if (w == null || w.isAir()) return true;
            Info in = infoOf(w);
            if (in.item() == null) return true;
            // Farmland is tilled before anything is placed above it (the hoe needs air above; a solid block turns it to dirt)
            if (airAboveWork && heldByTool(c, carried)) return false;
            if (have.getOrDefault(in.item(), 0) < in.cost().count()) {
                // The raw block does too (the tool finishes it after the placement)
                if (canUseRaw(in, have, carried)) return true;
                if (lack[0] == null) lack[0] = lackNamed(in, have);
                return false;
            }
            // A potted plant needs the plant as well as the pot: the missing plant is what the message names
            ToolSource pts = in.toolSource();
            if (pts != null && pts.plant() != null && in.cost().extra() != null
                && have.getOrDefault(pts.plant(), 0) < Math.max(1, in.cost().extraCount())) {
                if (lack[0] == null) lack[0] = pts.plant();
                return false;
            }
            return true;
        };
        orderer.setWorkCap(0);
        List<BuildOrderer.Cell> near = orderer.top(n, eye.x, eye.y, eye.z, reach, box, worldView, prevLook, usable);
        double yaw = mc.player.getYRot(), pitch = mc.player.getXRot();

        for (BuildOrderer.Cell c0 : near) {
            BuildOrderer.Cell c = c0;
            scratch.set(c.x(), c.y(), c.z());
            BlockPos pos = scratch.immutable();
            Scaf sc = scaffolds.get(key(c));
            BlockState want = sc != null ? sc.state : built.stateAt(pos);
            if (sc == null && (want == null || want.isAir())) {
                finish(c, null, Fin.DROPPED, null);
                continue;
            }
            Info in = sc != null ? sc.info : infoOf(want);
            if (in.item() == null) {
                finish(c, in, Fin.FAILED, "no item to place it with");
                continue;
            }
            // The item itself when it is carried (preferred); else the raw block, which gets its tool after it is placed
            Use use = useFor(in, have, carried);
            ToolSource ts = in.toolSource();
            BlockState simWant = use.raw() && ts.rawState() != null ? ts.rawState() : want;
            StateSpec simSpec = use.raw() && ts.rawSpec() != null ? ts.rawSpec() : in.spec();
            PlaceSimulator.Confirmed conf = recipeFor(c, simWant, simSpec, pos);
            if (conf == null) {
                if (sc != null) dropScaffold(sc);
                else defer(c, in, "no way to place it from here: " + in.spec().describe());
                continue;
            }
            PlaceRecipe r = conf.recipe();
            if (forceSneak.contains(key(c)) && !r.sneak()) r = r.withSneak(true);
            // The cell is queued with the look its recipe needs, for the look tie break of the next picks
            if (c.look() == null || !c.look().equals(r.look())) {
                orderer.remove(c);
                c = c.withLook(r.look());
                orderer.add(c);
            }
            double[] at = r.hitAt(c.x(), c.y(), c.z());
            Vec3 hitVec = new Vec3(at[0], at[1], at[2]);
            if (eye.distanceTo(hitVec) > reach + 0.25) continue;
            int[] cb = r.clickedBlock(c.x(), c.y(), c.z());
            BlockPos clicked = new BlockPos(cb[0], cb[1], cb[2]);
            if (requireLineOfSight.get() && !visible(eye, hitVec, clicked, r.airplace())) continue;
            if (rotationMode.get() == RotationMode.None && !r.look().contains(yaw, pitch)) continue;
            BlockHitResult hit = new BlockHitResult(hitVec, DIRECTIONS[r.face().ordinal()], clicked, false);
            p.preps.add(new Prep(c, in, r, hit, hotbarSlot(use.item(), use.count()), use.item(), use.count(), use.raw()));
        }
        p.placeable = !p.preps.isEmpty();
        if (p.placeable) return p;
        Item lackingNear = lack[0];

        // Nothing in reach: where to walk, or what is missing. The search covers everything, a slice at a time
        if (tick - lastFar >= 10) {
            lastFar = tick;
            lack[0] = null;
            orderer.setWorkCap(20000);
            farCells = orderer.top(16, eye.x, eye.y, eye.z, Double.POSITIVE_INFINITY, box, worldView, null, usable);
            farTruncated = orderer.truncated();
            farLack = lack[0];
            orderer.setWorkCap(0);
        }
        for (BuildOrderer.Cell c : farCells) {
            if (!targets.contains(c.x(), c.y(), c.z()) && !scaffolds.containsKey(key(c))) continue;
            p.walkParts.add(new BlockPos(c.x(), c.y(), c.z()));
        }
        if (p.walkParts.isEmpty() && unloadedHint != null && (orderer.activeLayer() == null || unloadedHint.y() == orderer.activeLayer())) {
            scratch.set(unloadedHint.x(), unloadedHint.y(), unloadedHint.z());
            if (!mc.level.isLoaded(scratch)) p.walkParts.add(scratch.immutable());
            else unloadedHint = null;
        }
        p.walkTarget = !p.walkParts.isEmpty();
        if (!p.walkTarget) {
            p.lackingItem = lackingNear != null ? lackingNear : farLack;
            p.lacking = p.lackingItem != null;
            // A search that was cut short has not said there is nothing: the run must not count that as being stuck
            p.waiting = farTruncated && !p.lacking;
        }
        if (!p.walkTarget && !p.lacking && (!toolJobs.isEmpty() || !waterJobs.isEmpty())) {
            // Blocks waiting for a tool or for water that are out of reach: go to them (only the ones that can progress)
            int toJobs = 0;
            for (ToolJob j : toolJobs.values()) {
                if (!mc.level.isLoaded(j.pos()) || tick - j.since() > TOOL_GIVE_UP || !toolCarried(j, carried) || blockedAbove(j) || eye.distanceTo(Vec3.atCenterOf(j.pos())) <= reach) continue;
                p.walkParts.add(j.pos());
                if (++toJobs >= 8) break;
            }
            if (toJobs < 8 && waterCanProgress()) {
                for (WaterJob j : waterJobs.values()) {
                    if (!mc.level.isLoaded(j.pos()) || tick > j.giveUp() || eye.distanceTo(Vec3.atCenterOf(j.pos())) <= reach) continue;
                    p.walkParts.add(j.pos());
                    if (++toJobs >= 8) break;
                }
            }
            if (toJobs > 0) {
                p.walkTarget = true;
                p.waiting = false;
            }
        }
        if (!p.walkTarget && !p.lacking) {
            // Standing in a cell that is still to be built keeps it from being placed: step out of it
            BlockPos feet = mc.player.blockPosition();
            if (targets.contains(feet.getX(), feet.getY(), feet.getZ()) || targets.contains(feet.getX(), feet.getY() + 1, feet.getZ())
                || scaffoldUnplacedAt(feet.getX(), feet.getY(), feet.getZ()) || scaffoldUnplacedAt(feet.getX(), feet.getY() + 1, feet.getZ())) {
                p.walkParts.add(feet);
                p.walkTarget = true;
                p.waiting = false;
            }
        }
        if (!p.walkTarget && !p.lacking && !p.waiting) {
            // Nothing to do in this layer: a cell with nothing to be placed against may be helped by a scaffold chain
            if (scaffoldInProgress()) p.waiting = true;
            else if (scaffoldPlan(eye, box, have)) p.waiting = true;
        }
        return p;
    }

    private boolean visible(Vec3 eye, Vec3 hit, BlockPos clicked, boolean air) {
        var result = mc.level.clip(new ClipContext(eye, hit, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (result.getType() == HitResult.Type.MISS) return air;
        return result.getBlockPos().equals(clicked);
    }

    private void defer(BuildOrderer.Cell c, Info in, String why) {
        long k = key(c);
        Deferred old = deferred.get(k);
        int tries = old == null ? 1 : old.tries() + 1;
        orderer.remove(c);
        if (tries > DEFER_TRIES) {
            finish(c, in, Fin.FAILED, why);
            return;
        }
        deferred.put(k, new Deferred(c, tick + DEFER_TICKS, tries, why));
    }

    private void retryDeferred() {
        if (deferred.isEmpty()) return;
        List<Deferred> due = new ArrayList<>();
        for (Deferred d : deferred.values()) if (d.dueTick() <= tick) due.add(d);
        for (Deferred d : due) {
            // Kept in the map for the try count; added back to the queue
            orderer.add(d.cell());
            deferred.put(key(d.cell()), new Deferred(d.cell(), Integer.MAX_VALUE, d.tries(), d.why()));
        }
    }

    /** The recipe for a cell: from the cache, or proposed by the mapper and confirmed by the game's own placement code. */
    private PlaceSimulator.Confirmed recipeFor(BuildOrderer.Cell c, BlockState want, StateSpec spec, BlockPos pos) {
        int mask = 0;
        boolean[] clickable = new boolean[6], interactive = new boolean[6];
        Dir[] dirs = Dir.values();
        for (int i = 0; i < 6; i++) {
            Dir d = dirs[i];
            int nx = c.x() + d.dx, ny = c.y() + d.dy, nz = c.z() + d.dz;
            boolean click = orderer.isPlacedSolid(nx, ny, nz) || worldView.solid(nx, ny, nz);
            clickable[i] = click;
            if (click) {
                scratch.set(nx, ny, nz);
                BlockState ns = mc.level.getBlockState(scratch);
                interactive[i] = BlockUtils.isClickable(ns.getBlock()) || mc.level.getBlockEntity(scratch) != null;
                // A neighbour of the same block (a bottom slab next to a slab): a click on it can merge instead of placing,
                // so a recipe worked out without one must not be reused with one
                if (ns.getBlock() == want.getBlock()) mask |= 1 << (12 + i);
            }
            if (clickable[i]) mask |= 1 << i;
            if (interactive[i]) mask |= 1 << (6 + i);
        }
        boolean cacheable = CACHEABLE.contains(spec.kind());
        RecipeKey rk = new RecipeKey(want, mask, airplace.get());
        if (cacheable) {
            PlaceSimulator.Confirmed hit = recipes.get(rk);
            if (hit != null) return hit;
        }
        PlacementMapper.NeighbourView view = new PlacementMapper.NeighbourView() {
            @Override
            public boolean clickable(Dir d) {
                return clickable[d.ordinal()];
            }

            @Override
            public boolean interactive(Dir d) {
                return interactive[d.ordinal()];
            }
        };
        List<PlaceRecipe> proposed = PlacementMapper.recipes(spec, view, airplace.get());
        if (proposed.isEmpty()) return null;
        PlaceSimulator.Confirmed conf = PlaceSimulator.confirm(mc.level, mc.player, want, pos, spec, proposed);
        if (conf != null && cacheable) {
            if (recipes.size() >= RECIPE_CACHE_MAX) recipes.clear();
            recipes.put(rk, conf);
        }
        return conf;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Placing

    private void placeTick() {
        if (plan == null || !plan.placeable || orderer == null || mc.screen != null) return;
        if (placeDelay > 0) {
            placeDelay--;
            return;
        }
        List<Prep> inHotbar = new ArrayList<>();
        for (Prep p : plan.preps) if (p.slot() >= 0) inHotbar.add(p);
        if (++noPlaceTicks == 100 && !stuckWarned) {
            stuckWarned = true;
            warning("Blocks are ready but nothing has been placed for 5 seconds. Check that your hotbar has a free slot (the printer never moves your weapons, food or shulker boxes), the packet budget and the sneak setting.");
        }
        if (inHotbar.isEmpty()) {
            // Everything ready is in the inventory, not the hotbar: one move this tick
            Prep first = plan.preps.get(0);
            if (savedSlot < 0) savedSlot = Players.selectedSlot();
            currentItem = first.use();
            currentCost = first.useCount();
            if (budget.canSend(System.currentTimeMillis(), 1) && hotbar.ensure(need)) budget.record(System.currentTimeMillis(), 1);
            return;
        }
        if (savedSlot < 0) savedSlot = Players.selectedSlot();

        List<Prep> remaining = new ArrayList<>(inHotbar);
        int blocksLeft = blocksPerTick.get();
        int rounds = multiRotate.get() ? 6 : 1;
        boolean sneaking = mc.player.isShiftKeyDown();
        for (int round = 0; round < rounds && !remaining.isEmpty() && blocksLeft > 0; round++) {
            List<TickBatcher.Candidate> cands = new ArrayList<>();
            for (int i = 0; i < remaining.size(); i++) {
                Prep p = remaining.get(i);
                int extra = p.recipe().extraPlaces() * (1 + (swing.get() ? 1 : 0)) + (p.recipe().deferUse() ? 0 : p.recipe().useClicks() * (1 + (swing.get() ? 1 : 0)));
                cands.add(new TickBatcher.Candidate(i, p.recipe().look(), p.recipe().sneak(), p.slot(), extra));
            }
            long nowMs = System.currentTimeMillis();
            int allowed = budget.unlimited() ? -1 : budget.remaining(nowMs);
            TickBatcher.Batch batch = TickBatcher.next(cands, blocksLeft, swing.get(), Players.selectedSlot(), sneaking, allowed);
            if (batch.empty()) return;

            // Sneak: the real key, held first. A sneaking batch waits for it; a plain batch waits for it to be let go.
            if (batch.sneak()) {
                if (!sneak.holding()) budget.record(nowMs, 1);
                sneak.press();
                if (!sneak.ready(sneakTicks.get())) return;
            }
            else if (sneak.holding()) {
                sneak.release();
                return;
            }
            if (!budget.trySpend(nowMs, batch.packets())) return;

            List<Prep> chosen = new ArrayList<>();
            for (int id : batch.ids()) chosen.add(remaining.get(id));
            Prep lead = chosen.get(0);
            double[] look = look(batch.window(), lead.hit().getLocation());
            Runnable action = () -> execute(chosen);
            if (rotationMode.get() == RotationMode.None) action.run();
            else Rotations.rotate(look[0], look[1], ROTATION_PRIORITY, true, action);

            prevLook = batch.window();
            blocksLeft -= chosen.size();
            remaining.removeAll(chosen);
            placeDelay = tickDelay.get();
        }
    }

    /** The one look of the batch: at the spot that is clicked when the window allows, else the nearest look inside it. */
    private double[] look(LookWindow window, Vec3 aim) {
        double yaw = Rotations.getYaw(aim), pitch = Rotations.getPitch(aim);
        if (rotationMode.get() == RotationMode.Snap) {
            yaw = Math.round(yaw / 45.0) * 45.0;
            pitch = Math.round(pitch / 45.0) * 45.0;
        }
        return window == null ? new double[] { yaw, pitch } : window.clamp(yaw, pitch);
    }

    /** Runs in the rotation callback, after the look went out in this tick's movement packet. */
    private void execute(List<Prep> chosen) {
        if (loop == null || mc.player == null || mc.level == null || orderer == null) return;
        for (Prep p : chosen) {
            BuildOrderer.Cell c = p.cell();
            PlaceRecipe r = p.recipe();
            PrinterActions.Click click = new PrinterActions.Click(posOf(c), p.hit(), r.airplace(), p.slot(), p.use(), p.useCount(),
                r.extraPlaces(), r.deferUse() ? 0 : r.useClicks(), r.sneak());
            PrinterActions.Outcome o = PrinterActions.clickPlace(click, swing.get());
            if (o == PrinterActions.Outcome.SUPPORT_GONE || o == PrinterActions.Outcome.CELL_TAKEN) recipes.clear();
            if (o != PrinterActions.Outcome.SENT) continue;
            if (lastClickTick != tick) lastClicked.clear();
            long k = key(c);
            Scaf scaf = scaffolds.get(k);
            if (scaf != null && !scaf.placed) {
                scaf.placed = true;
                scaffoldPlacedTotal++;
            }
            orderer.placed(c);
            awaiting.put(k, new Awaiting(c, p.info(), r, tick, p.raw()));
            loop.placed(k);
            if (p.raw()) {
                addTool(c.x(), c.y(), c.z(), p.info());
                warnNoTool(p.info().toolSource().tool(), p.info().toolSource().plant());
            }
            queueWater(p.info(), null, posOf(c), false);
            lastClicked.add(posOf(c));
            lastClickTick = tick;
            noPlaceTicks = 0;
            walkSince = -1;
        }
    }

    /** A click that opened a container instead of placing: close it and sneak for those cells from now on. */
    private void recoverOpened() {
        if (loop == null || session.active() || resupply.running() || resupply.screenOpen) return;
        if (tick - lastClickTick > 6) {
            lastClicked.clear();
            return;
        }
        if (mc.player.containerMenu == mc.player.inventoryMenu) return;
        if (!mc.player.containerMenu.getCarried().isEmpty()) return;
        mc.player.closeContainer();
        budget.record(System.currentTimeMillis(), 1);
        openedInstead++;
        for (BlockPos pos : lastClicked) {
            forceSneak.add(key(pos.getX(), pos.getY(), pos.getZ()));
            BuildOrderer.Cell c = awaitingCell(pos);
            if (c != null) {
                // The cell was not placed: back in the queue, and not a support
                long k = key(c);
                Awaiting a = awaiting.remove(k);
                loop.forget(k);
                orderer.unplaced(c.x(), c.y(), c.z());
                if (a != null) orderer.add(a.cell());
            }
        }
        lastClicked.clear();
        if (openedInstead <= 3) warning("A click opened a container instead of placing a block. Closing it and sneaking for that block.");
    }

    private BuildOrderer.Cell awaitingCell(BlockPos pos) {
        Awaiting a = awaiting.get(key(pos.getX(), pos.getY(), pos.getZ()));
        return a == null ? null : a.cell();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Verifying

    private void verifyDue() {
        if (loop == null || awaiting.isEmpty()) return;
        for (long k : loop.due()) {
            Awaiting a = awaiting.get(k);
            if (a == null) {
                loop.forget(k);
                continue;
            }
            BuildOrderer.Cell c = a.cell();
            scratch.set(c.x(), c.y(), c.z());
            BlockState st = mc.level.getBlockState(scratch);
            Scaf sc = scaffolds.get(k);
            if (sc != null) {
                scaffoldVerdict(sc, k, c, PrinterActions.built(a.info().spec(), st));
                continue;
            }
            // A block that only waits for its tool is placed
            Seen seen = seen(a.info(), st);
            boolean ok = seen != Seen.NO;
            if (seen == Seen.NEEDS_TOOL) addTool(c.x(), c.y(), c.z(), a.info());
            switch (loop.verdict(k, ok)) {
                case OK -> {
                    PlaceRecipe r = a.recipe();
                    if (r.deferUse() && r.useClicks() > 0) useJobs.add(new UseJob(scratch.immutable(), r.useClicks(), tick + 200));
                    orderer.unplaced(c.x(), c.y(), c.z()); // the world shows it now
                    finish(c, a.info(), Fin.PLACED, null);
                }
                case RETRY -> {
                    awaiting.remove(k);
                    orderer.unplaced(c.x(), c.y(), c.z());
                    orderer.add(c);
                }
                case FAILED -> {
                    orderer.unplaced(c.x(), c.y(), c.z());
                    finish(c, a.info(), Fin.FAILED, "did not appear after " + (PrintLoop.RETRIES + 1) + " tries");
                }
            }
        }
    }

    /** Use clicks that had to wait for the block below (note blocks). */
    private void useJobs() {
        if (useJobs.isEmpty() || mc.player == null) return;
        long nowMs = System.currentTimeMillis();
        for (int i = useJobs.size() - 1; i >= 0; i--) {
            UseJob j = useJobs.get(i);
            if (tick > j.giveUpTick() || !mc.level.isLoaded(j.pos())) {
                if (failedLines.size() < STORED_LIST) failedLines.add("note at " + j.pos().getX() + " " + j.pos().getY() + " " + j.pos().getZ() + " was not tuned: no block below it");
                useJobs.remove(i);
                continue;
            }
            if (!mc.level.getBlockState(j.pos()).is(Blocks.NOTE_BLOCK)) {
                useJobs.remove(i); // not a note block (any more): nothing to tune
                continue;
            }
            if (mc.level.getBlockState(j.pos().below()).isAir() || mc.player.isShiftKeyDown()) continue;
            if (!budget.trySpend(nowMs, j.clicks() * (swing.get() ? 2 : 1))) continue;
            PrinterActions.useClicks(j.pos(), j.clicks(), swing.get());
            useJobs.remove(i);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Tool jobs (stripping logs, tilling farmland, ...), waterlogging blocks, the layer range, the schematic area

    /** What the world shows at a cell next to what the schematic wants: built; "right block, needs its tool"; or neither. */
    private Seen seen(Info in, BlockState st) {
        ToolSource ts = in.toolSource();
        if (PrinterActions.built(in.spec(), st)) {
            // A candle that is there but not lit, a campfire that burns when it should be out: the block is right, the property is not
            if (ts != null && ts.property() && neededAction(in, st) != null) return Seen.NEEDS_TOOL;
            return Seen.BUILT;
        }
        if (ts == null || ts.property() || st.isAir()) return Seen.NO;
        return neededAction(in, st) != null ? Seen.NEEDS_TOOL : Seen.NO;
    }

    /** The tool action that turns the world block into what the schematic wants here, or null (built, or not a raw block of it). */
    private ToolAction neededAction(Info in, BlockState st) {
        if (in.toolSource() == null || st.isAir()) return null;
        ToolRules.Verdict v = ToolRules.classify(in.spec(), StateClassifier.idOf(st.getBlock()), StateClassifier.props(st), TOOLS);
        return v.needsTool() ? v.action() : null;
    }

    private void addTool(int x, int y, int z, Info in) {
        ToolSource ts = in.toolSource();
        if (ts == null) return;
        toolJobs.putIfAbsent(key(x, y, z), new ToolJob(new BlockPos(x, y, z), in, ts.tool(), 0, -1000, tick));
    }

    private boolean hasJobFor(Tool t) {
        for (ToolJob j : toolJobs.values()) if (j.tool() == t) return true;
        return false;
    }

    private static Item plantOf(Info in) {
        return in.toolSource() == null ? null : in.toolSource().plant();
    }

    /** The tool in this stack will do: it is the right kind and has a use left (a pot takes the one plant it is wanted with). */
    private boolean usableTool(Tool t, ItemStack s, Item plant) {
        if (s.isEmpty()) return false;
        boolean durable = !s.isDamageableItem() || s.getMaxDamage() - s.getDamageValue() > 1;
        return switch (t) {
            case AXE -> s.is(ItemTags.AXES) && durable;
            case SHOVEL -> s.is(ItemTags.SHOVELS) && durable;
            case HOE -> s.is(ItemTags.HOES) && durable;
            case SHEARS -> s.is(Items.SHEARS) && durable;
            case FLINT_AND_STEEL -> s.is(Items.FLINT_AND_STEEL) && durable;
            case HONEYCOMB -> s.is(Items.HONEYCOMB);
            case PLANT -> plant != null && s.is(plant);
        };
    }

    private boolean hasToolAnywhere(Tool t, Item plant) {
        if (mc.player == null) return false;
        if (toolHasTick != tick) {
            toolHasCache.clear();
            toolHasTick = tick;
        }
        Object k = t == Tool.PLANT ? plant : t;
        Boolean known = toolHasCache.get(k);
        if (known != null) return known;
        Integer until = toolMissingUntil.get(k);
        boolean has = false;
        if (until == null || tick >= until) {
            for (int i = 0; i < 36 && !has; i++) has = usableTool(t, mc.player.getInventory().getItem(i), plant);
            has = has || usableTool(t, mc.player.getOffhandItem(), plant);
            if (has) toolMissingUntil.remove(k);
            else toolMissingUntil.put(k, tick + TOOL_MISSING_TICKS);
        }
        toolHasCache.put(k, has);
        return has;
    }

    /** A hoe or shovel job under a solid block: the tool needs air above, so it can't progress until that is gone. */
    private boolean blockedAbove(ToolJob j) {
        ToolSource ts = j.info().toolSource();
        if (ts == null || !ts.action().needsAirAbove()) return false;
        BlockPos above = j.pos().above();
        return mc.level.isLoaded(above) && !mc.level.getBlockState(above).isAir();
    }

    /** Whether the tool for this source is carried; {@code cache} (per planning pass) keeps it to one look at the inventory per tool. */
    private boolean toolCarried(ToolSource ts, Map<Object, Boolean> cache) {
        Object k = ts.tool() == Tool.PLANT ? ts.plant() : ts.tool();
        return cache.computeIfAbsent(k, x -> hasToolAnywhere(ts.tool(), ts.plant()));
    }

    private boolean toolCarried(ToolJob j, Map<Object, Boolean> cache) {
        ToolSource ts = j.info().toolSource();
        return ts != null && toolCarried(ts, cache);
    }

    /** The item a missing-materials message names for a cell: its own, but the honeycomb when only that is missing. */
    private Item lackNamed(Info in, Map<Item, Integer> have) {
        ToolSource ts = in.toolSource();
        if (ts != null && ts.tool() == Tool.HONEYCOMB && ts.rawItem() != null && have.getOrDefault(ts.rawItem(), 0) >= 1) return Items.HONEYCOMB;
        return in.item();
    }

    /** The raw block's item is carried and may be placed now (honeycomb waits for the honeycomb: unwaxed copper ages). */
    private boolean canUseRaw(Info in, Map<Item, Integer> have, Map<Object, Boolean> cache) {
        ToolSource ts = in.toolSource();
        if (ts == null || ts.property() || ts.only() || have.getOrDefault(ts.rawItem(), 0) < 1) return false;
        return ToolRules.placeWithoutTool(ts.tool()) || toolCarried(ts, cache);
    }

    /** What to place for a cell: its own item when carried, else the raw block's (then the tool finishes it). */
    private Use useFor(Info in, Map<Item, Integer> have, Map<Object, Boolean> cache) {
        ToolSource ts = in.toolSource();
        int count = in.cost().count();
        if (ts == null || ts.property()) return new Use(in.item(), count, false);
        // No item of its own (farmland, a potted plant): the raw block is always what is placed
        if (ts.only()) return new Use(in.item(), count, true);
        ToolRules.Choice choice = ToolRules.choose(have.getOrDefault(in.item(), 0) >= count, canUseRaw(in, have, cache), toolCarried(ts, cache));
        if (choice == ToolRules.Choice.RAW_THEN_TOOL || choice == ToolRules.Choice.RAW_NO_TOOL) return new Use(ts.rawItem(), 1, true);
        return new Use(in.item(), count, false);
    }

    /**
     * A block that waits for a hoe or a shovel holds back the cell above it: the tool needs air above, and farmland under a
     * solid block turns back to dirt. Applies while the tool is carried, so a missing tool never starves the cells above.
     */
    private boolean heldByTool(BuildOrderer.Cell c, Map<Object, Boolean> cache) {
        int bx = c.x(), by = c.y() - 1, bz = c.z();
        Info below = null;
        ToolJob j = toolJobs.get(key(bx, by, bz));
        if (j != null) below = j.info();
        else if (targets.contains(bx, by, bz)) {
            BlockState w = built.stateAt(scratch2.set(bx, by, bz));
            if (w != null && !w.isAir()) below = infoOf(w);
        }
        ToolSource ts = below == null ? null : below.toolSource();
        return ts != null && ToolRules.holdsAbove(ts.action(), toolCarried(ts, cache));
    }

    /** Said once per tool: the blocks are placed anyway and finished when the tool is carried. */
    private void warnNoTool(Tool tool, Item plant) {
        if (warnedTools.contains(tool) || hasToolAnywhere(tool, plant)) return;
        warnedTools.add(tool);
        String what = tool == Tool.PLANT && plant != null ? itemName(plant) : tool.label();
        warning("No %s in your inventory: blocks that need it are placed first and finished when you carry it and are within reach of them.", what);
    }

    /** The slot of the first matching item: 0 to 8 in the hotbar, {@link #OFFHAND}, -2 in the main inventory only, -1 not carried. */
    private int slotOf(Predicate<ItemStack> test) {
        for (int i = 0; i < 9; i++) if (test.test(mc.player.getInventory().getItem(i))) return i;
        if (test.test(mc.player.getOffhandItem())) return OFFHAND;
        for (int i = 9; i < 36; i++) if (test.test(mc.player.getInventory().getItem(i))) return -2;
        return -1;
    }

    private int waterBucketSlot() {
        return slotOf(s -> s.is(Items.WATER_BUCKET));
    }

    private int emptyBucketSlot() {
        return slotOf(s -> s.is(Items.BUCKET));
    }

    /** Traces along the look the action asked for (aimYaw, aimPitch), not along whatever the player's rotation is at that moment. */
    private BlockHitResult traceLook(ClipContext.Fluid fluid) {
        Vec3 eye = mc.player.getEyePosition();
        double y = Math.toRadians(aimYaw), p = Math.toRadians(aimPitch);
        Vec3 dir = new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
        Vec3 end = eye.add(dir.scale(mc.player.blockInteractionRange()));
        return mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, mc.player));
    }

    /** Runs the click with the look it needs (right away when rotation-mode is None). */
    private void lookAndDo(Vec3 aim, Runnable action) {
        if (rotationMode.get() == RotationMode.None) {
            aimYaw = mc.player.getYRot();
            aimPitch = mc.player.getXRot();
            action.run();
            return;
        }
        aimYaw = Rotations.getYaw(aim);
        aimPitch = Rotations.getPitch(aim);
        Rotations.rotate(aimYaw, aimPitch, ROTATION_PRIORITY, true, action);
    }

    /** Moves a tool into the hotbar (one move); false when it could not be, with a pause before the next try. */
    private boolean pullIntoHotbar(AutoHotbar.Need what, Tool tool) {
        long now = System.currentTimeMillis();
        if (budget.canSend(now, 1) && hotbar.ensure(what)) {
            budget.record(now, 1);
            return true;
        }
        if (tool != null) toolBlockedUntil[tool.ordinal()] = tick + 40;
        else waterBlockedUntil = tick + 40;
        return false;
    }

    /**
     * Uses the tool of a block that was placed in its raw form (or found so): an axe on a log, a hoe on dirt, the plant on a
     * pot. Returns true when it spent this tick on it, so the placing waits one tick. Nothing here moves a weapon, food or a
     * shulker box: the tool is pulled in by the same hotbar helper as the building blocks.
     */
    private boolean toolTick() {
        if (toolJobs.isEmpty() || mc.player == null || mc.level == null || mc.screen != null || session.active() || resupply.running()) return false;
        Vec3 eye = mc.player.getEyePosition();
        double reach = reach();
        ToolJob pick = null;
        ToolAction pickAction = null;
        long pickKey = 0;
        ToolJob missing = null;
        boolean sweepAll = tick % 20 == 0;
        for (Iterator<Map.Entry<Long, ToolJob>> it = toolJobs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, ToolJob> e = it.next();
            ToolJob j = e.getValue();
            boolean inReach = eye.distanceTo(Vec3.atCenterOf(j.pos())) <= reach;
            // The tool is not carried anywhere: its jobs wait (looked up once per tick per tool, a miss is kept for a second)
            if (!hasToolAnywhere(j.tool(), plantOf(j.info()))) {
                if (inReach && missing == null) missing = j;
                continue;
            }
            // Looking at a job costs a state classification: the ones in reach are looked at every tick (until one is picked),
            // the others once a second (to drop what was finished by hand)
            if (!inReach && !sweepAll || pick != null && !sweepAll) continue;
            if (!mc.level.isLoaded(j.pos())) continue;
            BlockState st = mc.level.getBlockState(j.pos());
            ToolAction action = neededAction(j.info(), st);
            if (action == null) {
                // Done (by us or by hand), gone, or not a raw block of the wanted one: nothing to do here
                if (j.tries() > 0 && seen(j.info(), st) == Seen.BUILT) toolDone.merge(j.tool(), 1, Integer::sum);
                it.remove();
                continue;
            }
            if (j.tries() >= TOOL_TRIES && tick - j.lastTick() >= TOOL_WAIT) {
                if (failedLines.size() < STORED_LIST) failedLines.add(StateClassifier.idOf(st.getBlock()) + " at " + j.pos().getX() + " " + j.pos().getY() + " " + j.pos().getZ() + ": the " + j.tool().label() + " did not work on it");
                it.remove();
                continue;
            }
            if (tick - j.lastTick() < TOOL_WAIT || !inReach) continue;
            // A shovel and a hoe need air above (vanilla ShovelItem, HoeItem.onlyIfAirAbove)
            if (action.needsAirAbove() && !mc.level.getBlockState(j.pos().above()).isAir()) continue;
            if (!hasToolAnywhere(action.tool(), plantOf(j.info()))) {
                if (missing == null) missing = j;
                continue;
            }
            if (pick == null) {
                pick = j;
                pickAction = action;
                pickKey = e.getKey();
            }
        }
        if (pick == null) {
            if (missing != null) warnNoTool(missing.tool(), plantOf(missing.info()));
            return false;
        }
        Tool tool = pickAction.tool();
        Item plant = plantOf(pick.info());
        int slot = slotOf(s -> usableTool(tool, s, plant));
        if (slot == -1) return false;
        if (slot == -2) {
            plantWanted = plant;
            return tick >= toolBlockedUntil[tool.ordinal()] && pullIntoHotbar(toolNeeds.get(tool), tool);
        }

        long now = System.currentTimeMillis();
        if (tool.needsNoSneak()) {
            // The pot and the pumpkin do their part in the block's own right-click code, which sneaking skips
            if (sneak.holding()) {
                sneak.release();
                return true;
            }
            if (mc.player.isShiftKeyDown()) return false;
        } else if (tool == Tool.AXE && slot != OFFHAND && Players.blocksWithoutSneak(mc.player.getOffhandItem())) {
            // Axe in the main hand with a shield in the off hand: the game only strips while sneaking (AxeItem; the shovel and hoe have no such rule)
            if (!sneak.holding()) budget.record(now, 1);
            sneak.press();
            toolSneak = true;
            if (!sneak.ready(sneakTicks.get())) return true;
        }
        if (!budget.trySpend(now, 3 + (swing.get() ? 1 : 0))) return false;
        if (savedSlot < 0) savedSlot = Players.selectedSlot();

        BlockPos pos = pick.pos();
        Dir face = ToolRules.clickFace(pickAction, pick.info().spec().facing(), eye.x, eye.y, eye.z, pos.getX(), pos.getY(), pos.getZ());
        Direction dir = DIRECTIONS[face.ordinal()];
        Vec3 hitVec = new Vec3(pos.getX() + 0.5 + dir.getStepX() * 0.5, pos.getY() + 0.5 + dir.getStepY() * 0.5, pos.getZ() + 0.5 + dir.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, dir, pos, false);
        Info info = pick.info();
        toolJobs.put(pickKey, new ToolJob(pos, info, pick.tool(), pick.tries() + 1, tick, pick.since()));
        lookAndDo(hitVec, () -> {
            if (mc.player == null || mc.level == null || mc.gameMode == null) return;
            if (neededAction(info, mc.level.getBlockState(pos)) == null) return;
            int use = slotOf(s -> usableTool(tool, s, plant));
            if (use < 0) return;
            InteractionHand hand = InteractionHand.MAIN_HAND;
            if (use == OFFHAND) hand = InteractionHand.OFF_HAND;
            else if (Players.selectedSlot() != use) InvUtils.swap(use, false);
            if (mc.gameMode.useItemOn(mc.player, hand, hit).consumesAction()) PrinterActions.swing(swing.get());
        });
        return true;
    }

    private boolean waterWorkAhead() {
        return !waterJobs.isEmpty() || waterLeft > 0;
    }

    private void noteDry(String why, BlockPos pos) {
        dryCount++;
        if (pos != null && dryLines.size() < STORED_LIST) dryLines.add(pos.getX() + " " + pos.getY() + " " + pos.getZ());
        if (warnedDry) return;
        warnedDry = true;
        warning("Blocks the schematic wants waterlogged are placed dry (%s). Keep waterlog on and carry a water bucket to fill them.", why);
    }

    /** A block that is wanted waterlogged was placed (or is there, dry): queue the bucket use. */
    private void queueWater(Info in, BlockState st, BlockPos pos, boolean present) {
        if (!wet(in)) return;
        // Placed into water, the game waterlogs it by itself
        BlockState now = st != null ? st : mc.level.getBlockState(pos);
        if (now.hasProperty(BlockStateProperties.WATERLOGGED) && now.getValue(BlockStateProperties.WATERLOGGED)) return;
        if (waterlog.get() && rotationMode.get() == RotationMode.None) {
            // A bucket goes where you look; with rotation-mode None the Printer never turns
            if (!present) noteDry("waterlog needs a rotation mode other than None", pos);
            return;
        }
        boolean bucket = waterBucketSlot() != -1;
        boolean canFill = bucket || refillWaterBucket.get() && emptyBucketSlot() != -1;
        if (!waterlog.get() || !canFill) {
            if (!present) noteDry(waterlog.get() ? "no water bucket" : "waterlog is off", pos);
            return;
        }
        // A block that was already there is walked to if need be, so it is given longer
        QwerteAddon.LOG.debug("printer: water job queued at {} ({})", pos, present ? "already there" : "just placed");
        waterJobs.putIfAbsent(key(pos.getX(), pos.getY(), pos.getZ()), new WaterJob(pos, tick, tick + (present ? WATER_GIVE_UP_PRESENT : WATER_GIVE_UP), 0, -1000));
    }

    /** Uses a water bucket on a block that was placed dry; or picks water up for the next one. True when it spent the tick. */
    private boolean waterTick() {
        if (mc.player == null || mc.level == null || mc.screen != null || session.active() || resupply.running()) return false;
        if (rotationMode.get() == RotationMode.None) return false; // a bucket goes where you look
        if (waterJobs.isEmpty()) return refillTick(false);
        if (tick < waterBlockedUntil) return false;
        Vec3 eye = mc.player.getEyePosition();
        double reach = reach();
        for (Iterator<Map.Entry<Long, WaterJob>> it = waterJobs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, WaterJob> e = it.next();
            WaterJob j = e.getValue();
            BlockPos pos = j.pos();
            if (tick > j.giveUp()) {
                // Never reached or tried: dry without a misleading "too long" warning
                it.remove();
                if (j.tries() > 0) noteDry("it took too long", pos);
                else {
                    dryCount++;
                    if (dryLines.size() < STORED_LIST) dryLines.add(pos.getX() + " " + pos.getY() + " " + pos.getZ());
                }
                continue;
            }
            if (!mc.level.isLoaded(pos)) continue;
            BlockState st = mc.level.getBlockState(pos);
            if (st.isAir() && tick - j.since() <= 40) continue; // the placement has not shown yet
            if (!st.hasProperty(BlockStateProperties.WATERLOGGED)) {
                it.remove();
                continue;
            }
            if (st.getValue(BlockStateProperties.WATERLOGGED)) {
                QwerteAddon.LOG.debug("printer: water job at {} done after {} tries", pos, j.tries());
                if (j.tries() > 0) waterDone++;
                it.remove();
                continue;
            }
            if (tick - j.lastTick() < WATER_WAIT) continue;
            if (j.tries() >= WATER_TRIES) {
                if (failedLines.size() < STORED_LIST) failedLines.add(StateClassifier.idOf(st.getBlock()) + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ": could not be waterlogged");
                it.remove();
                continue;
            }
            if (eye.distanceTo(Vec3.atCenterOf(pos)) > reach) continue;
            int slot = waterBucketSlot();
            WaterlogRules.Action action = WaterlogRules.decide(waterlog.get(), true, false, slot != -1);
            if (action == WaterlogRules.Action.NONE) {
                it.remove();
                continue;
            }
            if (action == WaterlogRules.Action.NEED_BUCKET) {
                // Empty bucket and the refill setting: this block waits for water in reach. Otherwise it stays dry.
                if (refillWaterBucket.get() && emptyBucketSlot() != -1) {
                    if (refillTick(true)) return true;
                    continue;
                }
                it.remove();
                noteDry("no water bucket", pos);
                continue;
            }
            if (slot == -2) return pullIntoHotbar(waterNeed, null);
            Vec3 aim = waterAim(eye, pos, st, reach);
            if (aim == null) continue;
            long now = System.currentTimeMillis();
            if (!budget.trySpend(now, 3 + (swing.get() ? 1 : 0))) return false;
            if (savedSlot < 0) savedSlot = Players.selectedSlot();
            e.setValue(new WaterJob(pos, j.since(), j.giveUp(), j.tries() + 1, tick));
            QwerteAddon.LOG.debug("printer: water job at {} try {}", pos, j.tries() + 1);
            lookAndDo(aim, () -> useBucket(Items.WATER_BUCKET, ClipContext.Fluid.NONE, p -> p.equals(pos)));
            return true;
        }
        return refillTick(false);
    }

    /** Uses the bucket in hand when the look lands on the wanted block. Runs after the look went out. */
    private void useBucket(Item bucket, ClipContext.Fluid fluid, Predicate<BlockPos> wanted) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        BlockHitResult tr = traceLook(fluid);
        if (tr.getType() != HitResult.Type.BLOCK || !wanted.test(tr.getBlockPos())) {
            QwerteAddon.LOG.debug("printer: bucket trace missed (yaw {}, pitch {}): {} {}", aimYaw, aimPitch, tr.getType(), tr.getType() == HitResult.Type.BLOCK ? tr.getBlockPos() : "");
            return;
        }
        int use = slotOf(s -> s.is(bucket));
        if (use < 0) return;
        QwerteAddon.LOG.debug("printer: bucket used on {} (yaw {}, pitch {})", tr.getBlockPos(), aimYaw, aimPitch);
        InteractionHand hand = InteractionHand.MAIN_HAND;
        if (use == OFFHAND) hand = InteractionHand.OFF_HAND;
        else if (Players.selectedSlot() != use) InvUtils.swap(use, false);
        if (mc.gameMode.useItem(mc.player, hand).consumesAction()) PrinterActions.swing(swing.get());
    }

    /** A point on the block that a ray from the eyes hits as the block itself (a bucket is aimed with the look, not with a face). */
    private Vec3 waterAim(Vec3 eye, BlockPos pos, BlockState st, double reach) {
        var shape = st.getShape(mc.level, pos);
        AABB bb = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
        double cx = (bb.minX + bb.maxX) / 2, cy = (bb.minY + bb.maxY) / 2, cz = (bb.minZ + bb.maxZ) / 2;
        double[][] pts = { { cx, cy, cz }, { cx, bb.maxY, cz }, { cx, bb.minY, cz }, { bb.minX, cy, cz }, { bb.maxX, cy, cz }, { cx, cy, bb.minZ }, { cx, cy, bb.maxZ } };
        Vec3 best = null;
        double bestD = Double.MAX_VALUE;
        for (double[] p : pts) {
            Vec3 at = new Vec3(pos.getX() + p[0], pos.getY() + p[1], pos.getZ() + p[2]);
            Vec3 dir = at.subtract(eye);
            if (dir.lengthSqr() < 1e-6) continue;
            Vec3 end = at.add(dir.normalize().scale(0.6));
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) continue;
            double d = eye.distanceTo(hit.getLocation());
            if (d <= reach && d < bestD) {
                bestD = d;
                best = hit.getLocation();
            }
        }
        return best;
    }

    private boolean isWaterSource(BlockPos pos) {
        BlockState bs = mc.level.getBlockState(pos);
        FluidState fluid = bs.getFluidState();
        boolean wanted = false;
        if (built != null) {
            BlockState want = built.stateAt(pos);
            wanted = want != null && !want.isAir();
        }
        return WaterlogRules.refillSource(bs.getBlock() instanceof LiquidBlock && fluid.is(FluidTags.WATER), fluid.isSource(), wanted);
    }

    /** Picks water up with an empty bucket from a source block in reach. True when it spent the tick. */
    private boolean refillTick(boolean jobWaiting) {
        if (!waterlog.get() || !refillWaterBucket.get() || rotationMode.get() == RotationMode.None || tick < waterBlockedUntil) return false;
        int empty = emptyBucketSlot();
        if (empty == -1 || waterBucketSlot() != -1) return false;
        if (!jobWaiting && !waterWorkAhead()) return false;
        Vec3 eye = mc.player.getEyePosition();
        double reach = reach();
        if (tick - refillScanTick >= 10) {
            refillScanTick = tick;
            refillSource = findWaterSource(eye, reach);
        }
        BlockPos src = refillSource;
        boolean inReach = src != null && isWaterSource(src);
        if (!WaterlogRules.shouldRefill(true, true, false, true, inReach)) return false;
        if (empty == -2) return pullIntoHotbar(emptyBucketNeed, null);
        Vec3 aim = sourceAim(eye, src, reach);
        if (aim == null) {
            refillSource = null;
            return false;
        }
        if (!budget.trySpend(System.currentTimeMillis(), 3 + (swing.get() ? 1 : 0))) return false;
        if (savedSlot < 0) savedSlot = Players.selectedSlot();
        lookAndDo(aim, () -> useBucket(Items.BUCKET, ClipContext.Fluid.SOURCE_ONLY, this::isWaterSource));
        waterBlockedUntil = tick + 5;
        return true;
    }

    private BlockPos findWaterSource(Vec3 eye, double reach) {
        int r = (int) Math.ceil(reach);
        BlockPos base = BlockPos.containing(eye);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    scratch2.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    if (!mc.level.isLoaded(scratch2) || !isWaterSource(scratch2)) continue;
                    double d = eye.distanceToSqr(scratch2.getX() + 0.5, scratch2.getY() + 0.5, scratch2.getZ() + 0.5);
                    if (d <= reach * reach && d < bestD && sourceAim(eye, scratch2, reach) != null) {
                        bestD = d;
                        best = scratch2.immutable();
                    }
                }
            }
        }
        return best;
    }

    /** Where a bucket aimed from the eyes at a source block picks water up (the first source the ray meets), within reach. */
    private Vec3 sourceAim(Vec3 eye, BlockPos src, double reach) {
        Vec3 at = Vec3.atCenterOf(src);
        Vec3 dir = at.subtract(eye);
        if (dir.lengthSqr() < 1e-6) return null;
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, at.add(dir.normalize().scale(0.6)), ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK || !isWaterSource(hit.getBlockPos())) return null;
        return eye.distanceTo(hit.getLocation()) <= reach ? hit.getLocation() : null;
    }

    // Rechecking finished cells, wrong-block mode changes, sounds, other modules

    /** A slow look at finished cells in reach: one that became wrong, empty or dry goes back on the right queue. */
    private void recheckStep() {
        if (built == null || mc.level == null || rechecks.size() == 0 || session.active() || resupply.running()) return;
        Vec3 eye = mc.player.getEyePosition();
        double r = reach();
        for (RecheckQueue.Cell c : rechecks.poll(tick, 24, 2, cell -> eye.distanceToSqr(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5) <= r * r)) {
            scratch.set(c.x(), c.y(), c.z());
            if (!mc.level.isLoaded(scratch) || targets.contains(c.x(), c.y(), c.z()) || !layer.contains(c.x(), c.y(), c.z())) continue;
            BlockState want = built.stateAt(scratch);
            if (want == null || want.isAir()) continue;
            Info in = infoOf(want);
            BlockState st = mc.level.getBlockState(scratch);
            Seen seen = seen(in, st);
            if (seen == Seen.BUILT) {
                queueWater(in, st, scratch.immutable(), true);
                rechecks.again(c, tick, RECHECK_INTERVAL);
            } else if (seen == Seen.NEEDS_TOOL) {
                addTool(c.x(), c.y(), c.z(), in);
                rechecks.again(c, tick, RECHECK_INTERVAL);
            } else {
                QwerteAddon.LOG.debug("printer: finished cell at {} {} {} is no longer right, queued again", c.x(), c.y(), c.z());
                onCell(c.x(), c.y(), c.z(), want);
            }
        }
    }

    /** Remembers the wrong-block settings as they are now (compared field by field, no string is built per tick). */
    private void markWrongSig() {
        wrongSigMode = wrongBlocks.get();
        wrongSigNever = neverBreakContainers.get();
    }

    /** The wrong-blocks setting changed in the middle of a run: the cells that were skipped as wrong are looked at again. */
    private void requeueSkippedWrong() {
        markWrongSig();
        if (built == null || mc.level == null || skippedWrong.isEmpty()) return;
        List<int[]> cells = new ArrayList<>(skippedWrong);
        skippedWrong.clear();
        wrongLines.clear();
        for (int[] p : cells) {
            BlockState want = built.stateAt(scratch.set(p[0], p[1], p[2]));
            if (want == null || want.isAir() || targets.contains(p[0], p[1], p[2])) continue;
            onCell(p[0], p[1], p[2], want);
        }
        QwerteAddon.LOG.info("printer: wrong-blocks setting changed, {} skipped cell(s) looked at again", cells.size());
    }

    private void playSound(boolean error) {
        if (mc.player == null || !(error ? errorSound : finishSound).get()) return;
        List<SoundEvent> list = (error ? errorSoundEvent : finishSoundEvent).get();
        long now = System.currentTimeMillis();
        if (list.isEmpty() || now - lastSoundMs < 1500) return;
        lastSoundMs = now;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(list.get(0), 1.0f, soundVolume.get().floatValue()));
    }

    /** Another module that places blocks or aims can fight the Printer for the look and the hotbar. Said once per run. */
    private void warnOtherModules() {
        List<String> names = new ArrayList<>();
        for (Module m : Modules.get().getAll()) {
            if (m == this || !m.isActive()) continue;
            String n = (m.name + m.title).toLowerCase(Locale.ROOT).replaceAll("[-_ ]", "");
            if (n.contains("printer") || n.contains("killaura") || n.contains("crystalaura")) names.add(m.title);
        }
        if (!names.isEmpty()) logWarn("These modules are on and can fight the Printer for your look and hotbar: " + String.join(", ", names) + ".");
    }

    private boolean waterCanProgress() {
        return waterlog.get() && rotationMode.get() != RotationMode.None && (waterBucketSlot() != -1 || refillWaterBucket.get() && emptyBucketSlot() != -1);
    }

    /**
     * A tool or water job holds the end of the run only while it can progress now: the tool or bucket is carried, its block is
     * loaded and its give-up time has not passed. Jobs that cannot (no tool, unloaded chunk) never keep the Printer on; the
     * report lists them.
     */
    private boolean jobsCanProgress() {
        if (mc.player == null || mc.level == null || toolJobs.isEmpty() && waterJobs.isEmpty()) return false;
        if (!toolJobs.isEmpty()) {
            Map<Object, Boolean> carried = new HashMap<>();
            for (ToolJob j : toolJobs.values()) {
                if (!toolCarried(j, carried)) continue;
                if (blockedAbove(j)) continue;
                if (JobHold.canProgress(true, mc.level.isLoaded(j.pos()), tick - j.since() > TOOL_GIVE_UP || j.tries() >= TOOL_TRIES)) return true;
            }
        }
        if (!waterJobs.isEmpty() && waterCanProgress()) {
            for (WaterJob j : waterJobs.values()) {
                if (JobHold.canProgress(true, mc.level.isLoaded(j.pos()), tick > j.giveUp() || j.tries() >= WATER_TRIES)) return true;
            }
        }
        return false;
    }

    /** The pause message when nothing is left to build inside Litematica's render layers but blocks remain outside them; else null. */
    private String layerHold() {
        if (!scanDone || layerDirty || dormantN == 0 || !layer.active() || !holdDone || holdTodo == 0) return null;
        return "Layer done: move Litematica's render layer to continue (" + holdTodo + " block(s) outside it still to build).";
    }

    /** Lists what could not be built when the run pauses as stuck, once (the end report no longer comes from this). */
    private void reportStuck() {
        List<String> left = new ArrayList<>();
        for (BuildOrderer.Cell c : sweep) {
            if (left.size() >= MAX_LIST + 1) break;
            if (targets.contains(c.x(), c.y(), c.z())) left.add(c.x() + " " + c.y() + " " + c.z());
        }
        QwerteAddon.LOG.info("printer: stuck, first cells: {}", left);
        sendList("Can't be built right now (first)", left);
    }

    // The layer range

    /** Re-reads Litematica's render layers; a change marks the build to be sorted again (blocks outside leave, blocks inside come back). */
    private void readLayer() {
        layerRead = true;
        boolean fromLitematica = built != null && built.mode().startsWith("litematica");
        LayerFilter now = fromLitematica ? LitematicaBridge.layer() : LayerFilter.ALL;
        if (fromLitematica && LitematicaBridge.layerWorks() && (!layerLogged || now.mode() != layerModeLogged)) {
            layerLogged = true;
            layerModeLogged = now.mode();
            QwerteAddon.LOG.info("printer: Litematica render layer mode is {}{}", now.mode(), now.active() ? " (axis " + now.axis() + ", " + now + ")" : " (everything is built)");
        }
        if (now.equals(layer)) return;
        layer = now;
        layerDirty = true;
        layerParkAt = 0;
        layerDormantAt = 0;
        holdDone = false;
        holdScanAt = -1;
        QwerteAddon.LOG.info("printer: Litematica render layers are now {}", now);
    }

    private void dormantAdd(int x, int y, int z) {
        if (dormantN * 3 + 3 > dormant.length) dormant = java.util.Arrays.copyOf(dormant, Math.max(96, dormant.length * 2));
        dormant[dormantN * 3] = x;
        dormant[dormantN * 3 + 1] = y;
        dormant[dormantN * 3 + 2] = z;
        dormantN++;
    }

    private void parkCell(BuildOrderer.Cell c) {
        BlockState want = built.stateAt(scratch.set(c.x(), c.y(), c.z()));
        Info in = want == null || want.isAir() ? null : infoOf(want);
        finish(c, in, Fin.DROPPED, null);
        if (in != null) dormantAdd(c.x(), c.y(), c.z());
    }

    /**
     * After a layer change: cells now outside the range leave the build (queue, count, materials, orders), cells now
     * inside are scanned back in. A slice per tick, so a big schematic does not stall the game; placing already ignores
     * cells outside the range at once.
     */
    private void layerPass() {
        if (!layerDirty || built == null || mc.level == null) return;
        long end = System.nanoTime() + LAYER_BUDGET_NANOS;
        while (layerParkAt < sweep.size()) {
            if (System.nanoTime() > end) return;
            BuildOrderer.Cell c = sweep.get(layerParkAt++);
            if (!targets.contains(c.x(), c.y(), c.z()) || layer.contains(c.x(), c.y(), c.z())) continue;
            parkCell(c);
        }
        while (layerDormantAt < dormantN) {
            if (System.nanoTime() > end) return;
            int i = layerDormantAt * 3;
            int x = dormant[i], y = dormant[i + 1], z = dormant[i + 2];
            if (!layer.contains(x, y, z)) {
                layerDormantAt++;
                continue;
            }
            int last = (dormantN - 1) * 3;
            dormant[i] = dormant[last];
            dormant[i + 1] = dormant[last + 1];
            dormant[i + 2] = dormant[last + 2];
            dormantN--;
            BlockState want = built.stateAt(scratch.set(x, y, z));
            if (want != null && !want.isAir()) onCell(x, y, z, want);
        }
        layerDirty = false;
        holdDone = false;
        holdScanAt = -1;
    }

    /**
     * With nothing left to build inside the range: counts, a slice per tick, the dormant cells whose block is not already
     * right in the world. A finished build viewed through one layer then ends the run instead of waiting for a layer move.
     * The count is made again every 5 seconds while it matters.
     */
    private void holdScan() {
        if (!scanDone || layerDirty || dormantN == 0 || !layer.active() || targets.size() > 0 || built == null) return;
        if (holdScanAt < 0) {
            if (holdDone && tick - holdTick < 100) return;
            holdScanAt = 0;
            holdScanTodo = 0;
        }
        long end = System.nanoTime() + LAYER_BUDGET_NANOS;
        while (holdScanAt < dormantN) {
            if (System.nanoTime() > end) return;
            int i = holdScanAt++ * 3;
            scratch.set(dormant[i], dormant[i + 1], dormant[i + 2]);
            BlockState want = built.stateAt(scratch);
            if (want == null || want.isAir()) continue;
            if (!mc.level.isLoaded(scratch)) {
                holdScanTodo++;
                continue;
            }
            if (seen(infoOf(want), mc.level.getBlockState(scratch)) == Seen.NO) holdScanTodo++;
        }
        holdTodo = holdScanTodo;
        holdDone = true;
        holdTick = tick;
        holdScanAt = -1;
    }

    /** The first count is still being made. */
    private boolean holdScanning() {
        return scanDone && !layerDirty && dormantN > 0 && layer.active() && targets.size() == 0 && !holdDone;
    }

    // The schematic area

    /** True while the player is farther than the reach from every region and blocks remain. Warns once per exit. */
    private boolean checkArea() {
        if (built == null || !scanDone || mc.player == null) return false;
        if (areaBoxes.isEmpty()) {
            List<int[]> boxes = new ArrayList<>();
            for (BuildSource.Area a : built.areas()) boxes.add(new int[] { a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ() });
            areaBoxes = boxes;
        }
        Vec3 eye = mc.player.getEyePosition();
        boolean out = AreaWatch.outside(areaBoxes, eye.x, eye.y, eye.z, reach());
        if (areaWatch.update(out)) logWarn("You left the schematic area. The Printer keeps running; it places again when you are back within reach of it.");
        return out && targets.size() > 0;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Scaffolding: spare blocks that give a cell something to be placed against, taken down when nothing needs them

    private final ScaffoldPlanner.World scaffoldWorld = new ScaffoldPlanner.World() {
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        @Override
        public boolean air(int x, int y, int z) {
            return mc.level.isLoaded(pos.set(x, y, z)) && mc.level.getBlockState(pos).isAir() && !scaffolds.containsKey(key(x, y, z));
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return orderer != null && orderer.isPlacedSolid(x, y, z) || worldView.solid(x, y, z);
        }

        @Override
        public boolean wanted(int x, int y, int z) {
            if (built == null) return false;
            BlockState w = built.stateAt(pos.set(x, y, z));
            return w != null && !w.isAir();
        }

        @Override
        public BuildOrderer.Cell open(int x, int y, int z) {
            if (built == null || !targets.contains(x, y, z)) return null;
            BlockState w = built.stateAt(pos.set(x, y, z));
            return w == null || w.isAir() ? null : BuildOrderer.Cell.of(x, y, z, infoOf(w).spec(), null);
        }
    };

    private int scaffoldFailures;

    private boolean scaffoldEnabled() {
        return layers != null && layers.active() != null && scaffold.get() && !airplace.get() && scaffoldFailures < 3;
    }

    private boolean scaffoldUnplacedAt(int x, int y, int z) {
        Scaf sc = scaffolds.get(key(x, y, z));
        return sc != null && !sc.placed;
    }

    /** Cells that failed to place and are waiting to be tried again. */
    private boolean deferredWaiting() {
        for (Deferred d : deferred.values()) if (d.dueTick() != Integer.MAX_VALUE) return true;
        return false;
    }

    /** A chain is being placed: some scaffold block is not standing yet. */
    private boolean scaffoldInProgress() {
        for (Scaf sc : scaffolds.values()) if (!sc.built) return true;
        return false;
    }

    private boolean scaffoldIdle() {
        return !scaffoldInProgress();
    }

    /** Scaffold blocks that stand or may stand (a click was sent): they must be gone before the run can end. */
    private int scaffoldLeft() {
        int n = 0;
        for (Scaf sc : scaffolds.values()) if (sc.placed) n++;
        return n;
    }

    /**
     * Some scaffold is not needed any more and the loop may take it down: in reach, or nothing else is left to do. A stand
     * block (one Baritone climbed with) only counts while no walk is going and the player is not on or beside it
     * ({@link StandShield}); at the very end the player is walked away from the last ones first.
     */
    private boolean scaffoldRemovableNow() {
        if (mc.player == null || scaffoldRemovable.isEmpty() && standShielded.isEmpty()) return false;
        boolean end = targets.size() == 0;
        // Our own walk away from the stand blocks is going on: the loop keeps coming back to it
        if (removalWalk && walkGoal != null) return true;
        Vec3 eye = mc.player.getEyePosition();
        BlockPos feet = mc.player.blockPosition();
        double r = reach();
        for (BlockPos p : scaffoldRemovable) {
            Scaf sc = scaffolds.get(key(p.getX(), p.getY(), p.getZ()));
            if (sc != null && sc.stand) {
                if (StandShield.shielded(p.getX(), p.getY(), p.getZ(), feet.getX(), feet.getY(), feet.getZ())) continue;
                if (walkGoal != null && !end) continue;
            }
            if (end || eye.distanceToSqr(Vec3.atCenterOf(p)) <= r * r) return true;
        }
        return end && !standShielded.isEmpty();
    }

    /** Every few ticks: scaffold nobody needs is listed for removal (or just dropped when it was never placed); stale plans expire. */
    private void scaffoldMaintain() {
        if (scaffolds.isEmpty()) {
            if (!scaffoldRemovable.isEmpty()) scaffoldRemovable = List.of();
            if (!standShielded.isEmpty()) standShielded = List.of();
            return;
        }
        if (tick - scaffoldCheck < SCAFFOLD_CHECK) return;
        scaffoldCheck = tick;
        for (Scaf sc : new ArrayList<>(scaffolds.values())) {
            if (!sc.placed && tick - sc.since > SCAFFOLD_PLANNED_TTL) {
                dropScaffold(sc);
            } else if (sc.built && mc.level.isLoaded(sc.pos) && !mc.level.getBlockState(sc.pos).is(sc.state.getBlock())) {
                // The world showed it once and it is gone now (broken by someone, or replaced): it is no support any more
                QwerteAddon.LOG.debug("printer: scaffold block at {} is gone, dropped", sc.pos);
                dropScaffold(sc);
            }
        }
        List<int[]> all = new ArrayList<>();
        for (Scaf sc : scaffolds.values()) all.add(new int[] { sc.pos.getX(), sc.pos.getY(), sc.pos.getZ() });
        List<BlockPos> remove = new ArrayList<>(), shielded = new ArrayList<>();
        BlockPos feet = mc.player == null ? null : mc.player.blockPosition();
        for (int[] p : ScaffoldPlanner.removable(scaffoldWorld, all, airplace.get())) {
            long k = key(p[0], p[1], p[2]);
            Scaf sc = scaffolds.get(k);
            if (sc == null) continue;
            if (!sc.placed) {
                dropScaffold(sc); // never placed: it only leaves the queue
                continue;
            }
            if (awaiting.containsKey(k)) continue;
            // A stand block the player is on or beside stays until they have moved off it
            if (sc.stand && feet != null && StandShield.shielded(p[0], p[1], p[2], feet.getX(), feet.getY(), feet.getZ())) {
                shielded.add(sc.pos);
                continue;
            }
            remove.add(sc.pos);
        }
        scaffoldRemovable = remove;
        standShielded = shielded;
    }

    /** A scaffold block leaves the books (it was removed, never placed, or could not be placed). */
    private void dropScaffold(Scaf sc) {
        long k = key(sc.pos.getX(), sc.pos.getY(), sc.pos.getZ());
        scaffolds.remove(k);
        awaiting.remove(k);
        if (orderer != null) {
            orderer.releaseUngated(sc.pos.getX(), sc.pos.getY(), sc.pos.getZ());
            orderer.unplaced(sc.pos.getX(), sc.pos.getY(), sc.pos.getZ());
            orderer.wake(sc.pos.getX(), sc.pos.getY(), sc.pos.getZ());
        }
        if (loop != null) loop.forget(k);
        if (!scaffoldRemovable.isEmpty()) scaffoldRemovable = scaffoldRemovable.stream().filter(p -> !p.equals(sc.pos)).toList();
    }

    /** The check of a scaffold block that was placed. */
    private void scaffoldVerdict(Scaf sc, long k, BuildOrderer.Cell c, boolean ok) {
        switch (loop.verdict(k, ok)) {
            case OK -> {
                sc.built = true;
                awaiting.remove(k);
                orderer.unplaced(c.x(), c.y(), c.z()); // the world shows it now
                orderer.wake(c.x(), c.y(), c.z());
            }
            case RETRY -> {
                awaiting.remove(k);
                orderer.unplaced(c.x(), c.y(), c.z());
                orderer.add(c);
            }
            case FAILED -> {
                scaffoldFailures++;
                dropScaffold(sc);
                if (scaffoldFailures == 3) warning("Scaffold blocks keep not appearing; scaffolding is off for this run.");
            }
        }
    }

    private Info scaffoldInfoOf(Item item) {
        return scaffoldInfos.computeIfAbsent(item, i -> {
            StateSpec spec = StateClassifier.classify(((BlockItem) i).getBlock().defaultBlockState());
            return new Info(spec, MaterialPlanner.cost(spec, ITEMS), i, null);
        });
    }

    /** A block that makes good scaffold: a plain full cube, nothing that falls, opens, holds items or hurts. */
    private boolean spareBlock(Block b) {
        if (b instanceof FallingBlock || b instanceof EntityBlock || b instanceof LiquidBlock || b instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
            || b instanceof net.minecraft.world.level.block.ObserverBlock || b == Blocks.TNT || b == Blocks.MAGMA_BLOCK || BlockUtils.isClickable(b)) return false;
        BlockState s = b.defaultBlockState();
        if (s.getDestroySpeed(mc.level, BlockPos.ZERO) < 0 || !s.isCollisionShapeFullBlock(mc.level, BlockPos.ZERO)) return false;
        StateSpec spec = StateClassifier.classify(s);
        return !spec.skipped() && spec.kind() == StateKind.FULL;
    }

    /** The spare items: carried, not a schematic material, not one the hotbar protects, a spare block. The most plentiful first. */
    private List<Item> spareList(Map<Item, Integer> have) {
        return SpareItems.pick(have, buildItems::contains, it -> AutoHotbar.isProtected(it.getDefaultInstance()),
            it -> it instanceof BlockItem bi && spareBlock(bi.getBlock()), it -> BuiltInRegistries.ITEM.getKey(it).toString());
    }

    /** The item to scaffold with: the most plentiful spare item. */
    private Item spareItem(Map<Item, Integer> have) {
        List<Item> spare = spareList(have);
        return spare.isEmpty() ? null : spare.get(0);
    }

    private List<ScaffoldPlanner.Box> scaffoldBoxes() {
        if (scaffoldAreas.isEmpty() && !areaBoxes.isEmpty()) {
            List<ScaffoldPlanner.Box> l = new ArrayList<>();
            for (int[] b : areaBoxes) l.add(new ScaffoldPlanner.Box(b[0], b[1], b[2], b[3], b[4], b[5]));
            scaffoldAreas = l;
        }
        return scaffoldAreas;
    }

    /**
     * Nothing in the active layer can be placed: for the nearest cells with nothing next to them, looks for a chain of spare
     * blocks from an existing block to one of their faces (a nanosecond budget; a refused cell is not tried again for a
     * while) and queues it. Returns true when a chain was queued.
     */
    private boolean scaffoldPlan(Vec3 eye, BuildOrderer.Box box, Map<Item, Integer> have) {
        if (!scaffoldEnabled() || scaffoldInProgress() || tick - scaffoldTry < SCAFFOLD_EVERY) return false;
        scaffoldTry = tick;
        int layerY = layers.active();
        List<BuildOrderer.Cell> stranded = orderer.stranded(layerY, 6, 4000, eye.x, eye.y, eye.z, box, worldView);
        if (stranded.isEmpty()) return false;
        Item item = spareItem(have);
        if (item == null) {
            if (!scaffoldNoSpareWarned) {
                scaffoldNoSpareWarned = true;
                warning("no spare blocks for scaffolding: carry some blocks the schematic doesn't use");
            }
            return false;
        }
        List<ScaffoldPlanner.Box> areas = scaffoldBoxes();
        if (areas.isEmpty()) return false;
        // Only planned blocks count toward scaffold-max; the stand blocks Baritone climbed with are not the Printer's to limit
        int planned = 0;
        for (Scaf s : scaffolds.values()) if (!s.stand) planned++;
        int room = Math.min(scaffoldMax.get() - planned, have.getOrDefault(item, 0));
        long started = System.nanoTime();
        boolean timedOut = false;
        for (BuildOrderer.Cell c : stranded) {
            Integer until = scaffoldFail.get(key(c));
            if (until != null && tick < until) continue;
            // Each cell has its own time; a cell whose search ran out of it before gets more next time (up to 2 ms)
            if (System.nanoTime() - started > SCAFFOLD_TOTAL_NANOS) {
                timedOut = true;
                break;
            }
            int hits = scaffoldBudgetHits.getOrDefault(key(c), 0);
            long deadline = System.nanoTime() + ScaffoldPlanner.budgetNanos(hits);
            ScaffoldPlanner.Result r = ScaffoldPlanner.plan(scaffoldWorld, areas, c.x(), c.y(), c.z(), ScaffoldPlanner.MAX_CHAIN, eye.x, eye.y, eye.z, deadline);
            if (r.budgetHit()) {
                // Out of time: remembered for this cell only, the others are still looked at
                if (scaffoldBudgetHits.size() > 4096) scaffoldBudgetHits.clear();
                scaffoldBudgetHits.merge(key(c), 1, Integer::sum);
                timedOut = true;
                continue;
            }
            scaffoldBudgetHits.remove(key(c));
            if (r.chain() == null || r.chain().size() > room) {
                if (scaffoldFail.size() > 4096) scaffoldFail.clear();
                scaffoldFail.put(key(c), tick + SCAFFOLD_FAIL_TTL);
                continue;
            }
            Info info = scaffoldInfoOf(item);
            BlockState state = ((BlockItem) item).getBlock().defaultBlockState();
            for (int[] b : r.chain().blocks()) {
                BlockPos bp = new BlockPos(b[0], b[1], b[2]);
                BuildOrderer.Cell cell = BuildOrderer.Cell.of(b[0], b[1], b[2], info.spec(), null);
                Scaf sc = new Scaf(bp, item, state, info, tick);
                if (scaffolds.putIfAbsent(key(cell), sc) == null) orderer.addUngated(cell);
            }
            lastFar = -1000;
            lastPlan = null;
            QwerteAddon.LOG.info("printer: scaffold of {} block(s) for the cell at {} {} {}", r.chain().size(), c.x(), c.y(), c.z());
            return true;
        }
        if (!timedOut && scaffoldWarned.add(layerY) && scaffoldWarned.size() <= 5) {
            BuildOrderer.Cell c = stranded.get(0);
            warning("No scaffold can reach the cell at %d %d %d (layer Y=%d): no chain of up to %d spare blocks from a block that exists gets to it. The layer is carried over and tried again later.",
                c.x(), c.y(), c.z(), layerY, ScaffoldPlanner.MAX_CHAIN);
        }
        return false;
    }

    /**
     * Takes down scaffold nobody needs, breaking it like a wrong block. Planned scaffold goes nearest first (walking to it
     * when it is out of reach, walk only). Stand blocks (the spare blocks Baritone climbed with) only when no walk is going,
     * highest first, and never the ones the player is on or beside; at the very end the player is walked away from the last
     * ones first.
     */
    private void removeScaffoldTick() {
        if (mc.player == null || mc.level == null) return;
        if (scaffoldRemovable.isEmpty() && standShielded.isEmpty()) return;
        Vec3 eye = mc.player.getEyePosition();
        BlockPos feet = mc.player.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        List<BlockPos> free = new ArrayList<>();
        for (BlockPos p : scaffoldRemovable) {
            Scaf s = scaffolds.get(key(p.getX(), p.getY(), p.getZ()));
            if (s != null && s.stand) {
                if (!StandShield.shielded(p.getX(), p.getY(), p.getZ(), feet.getX(), feet.getY(), feet.getZ())) free.add(p);
                continue;
            }
            double d = eye.distanceToSqr(Vec3.atCenterOf(p));
            if (d < bestD) {
                best = p;
                bestD = d;
            }
        }
        if (best == null) {
            removeStandTick(eye, feet, free);
            return;
        }
        Scaf sc = scaffolds.get(key(best.getX(), best.getY(), best.getZ()));
        if (sc == null) {
            BlockPos gone = best;
            scaffoldRemovable = scaffoldRemovable.stream().filter(p -> !p.equals(gone)).toList();
            return;
        }
        breakScaffold(sc, best, eye);
    }

    /** Stand blocks: one walk at a time, highest first, the player is walked off them at the end. */
    private void removeStandTick(Vec3 eye, BlockPos feet, List<BlockPos> free) {
        boolean end = targets.size() == 0;
        if (walkGoal != null) {
            if (!removalWalk) {
                if (!end) return; // a building walk is going: the stand blocks wait for it
                releaseWalk(); // nothing is left to build: the walk to nowhere ends
            } else if (SmartPlaceSession.BARITONE && BaritoneHelper.goalIs((baritone.api.pathing.goals.Goal) walkGoal) && tick - removalSince < REMOVAL_WALK_MAX) {
                return; // our own walk away from the blocks is going on
            } else {
                // The walk ended (no path, lost goal or timeout): it failed if the feet are still next to a stand block
                int[][] left = new int[standShielded.size()][];
                for (int i = 0; i < left.length; i++) left[i] = new int[] { standShielded.get(i).getX(), standShielded.get(i).getY(), standShielded.get(i).getZ() };
                if (StandShield.walkFailed(feet.getX(), feet.getY(), feet.getZ(), left)) {
                    removalFails++;
                    removalRetryAt = tick + StandShield.RETRY_TICKS;
                }
                releaseWalk();
                scaffoldCheck = -1000; // where the player stands now decides which blocks may go: the lists are made again first
                return;
            }
        }
        if (removalFails >= StandShield.MAX_FAILS) {
            for (Scaf s : new ArrayList<>(scaffolds.values())) {
                if (!s.stand) continue;
                if (failedLines.size() < STORED_LIST) failedLines.add("stand block left standing at " + s.pos.getX() + " " + s.pos.getY() + " " + s.pos.getZ());
                giveUpScaffold(s, "the Printer could not walk away from it");
            }
            removalFails = 0;
            return;
        }
        if (!free.isEmpty()) {
            int[][] arr = new int[free.size()][];
            for (int i = 0; i < arr.length; i++) arr[i] = new int[] { free.get(i).getX(), free.get(i).getY(), free.get(i).getZ() };
            BlockPos best = free.get(StandShield.topDown(arr, eye.x, eye.y, eye.z));
            Scaf sc = scaffolds.get(key(best.getX(), best.getY(), best.getZ()));
            if (sc == null) {
                BlockPos gone = best;
                scaffoldRemovable = scaffoldRemovable.stream().filter(p -> !p.equals(gone)).toList();
                return;
            }
            breakScaffold(sc, best, eye);
            return;
        }
        if (end && !standShielded.isEmpty()) startRemovalWalk(standShielded);
    }

    /** Walks (walk only) to where the eyes reach one of the blocks but the feet are not on or beside any stand block. */
    private void startRemovalWalk(List<BlockPos> parts) {
        if (tick < removalRetryAt) return;
        if (!canWalkNow() || !confineBaritone()) {
            removalFails = StandShield.MAX_FAILS;
            return;
        }
        walkOnly();
        List<int[]> shield = new ArrayList<>();
        for (BlockPos p : parts) shield.add(new int[] { p.getX(), p.getY(), p.getZ() });
        for (BlockPos p : scaffoldRemovable) {
            Scaf s = scaffolds.get(key(p.getX(), p.getY(), p.getZ()));
            if (s != null && s.stand) shield.add(new int[] { p.getX(), p.getY(), p.getZ() });
        }
        final int[][] snapshot = shield.toArray(new int[0][]);
        walkGoal = BaritoneHelper.moveInReach(parts, Math.max(1.0, reach() - 0.25),
            p -> targets.contains(p.getX(), p.getY(), p.getZ()) || partBits.contains(p.getX(), p.getY(), p.getZ())
                || StandShield.badFeet(p.getX(), p.getY(), p.getZ(), snapshot));
        removalWalk = true;
        removalSince = lastWalk = tick;
    }

    /** Breaks one scaffold block, walking to it first if it is out of reach. */
    private void breakScaffold(Scaf sc, BlockPos best, Vec3 eye) {
        if (!mc.level.isLoaded(best)) {
            if (++sc.breakTicks > SCAFFOLD_BREAK_TRIES) giveUpScaffold(sc, "its chunk is not loaded");
            else if (canMoveNow()) walkToOnly(List.of(best));
            return;
        }
        BlockState st = mc.level.getBlockState(best);
        if (!st.is(sc.state.getBlock())) {
            // Gone (taken down, or never appeared) or replaced by something else: nothing left to break
            if (sc.built && st.isAir()) scaffoldRemovedTotal++;
            dropScaffold(sc);
            return;
        }
        if (st.getDestroySpeed(mc.level, best) < 0) {
            giveUpScaffold(sc, "it cannot be broken");
            return;
        }
        if (eye.distanceTo(Vec3.atCenterOf(best)) > reach()) {
            if (++sc.breakTicks > SCAFFOLD_BREAK_TRIES) giveUpScaffold(sc, "it is out of reach and the Printer can't walk there");
            else if (sc.stand) startRemovalWalk(List.of(best));
            else if (canMoveNow()) walkToOnly(List.of(best));
            return;
        }
        if (sc.stand) {
            // The player may have moved since the list was made: never break a block they are on or beside
            BlockPos feet = mc.player.blockPosition();
            if (walkGoal != null || StandShield.shielded(best.getX(), best.getY(), best.getZ(), feet.getX(), feet.getY(), feet.getZ())) return;
        }
        releaseWalk();
        if (++sc.breakTicks > SCAFFOLD_BREAK_TRIES) {
            giveUpScaffold(sc, "it could not be broken");
            return;
        }
        if (!budget.trySpend(System.currentTimeMillis(), 2)) return;
        if (savedSlot < 0) savedSlot = Players.selectedSlot();
        FindItemResult tool = InvUtils.findFastestTool(st);
        if (tool.found() && tool.slot() != Players.selectedSlot()) InvUtils.swap(tool.slot(), false);
        PrinterActions.breakBlock(best, rotationMode.get() != RotationMode.None, swing.get());
    }

    /** A walk that never places a block, whatever the building walk allowed. */
    private void walkToOnly(List<BlockPos> parts) {
        walkOnly();
        walkNoPlace = true;
        try {
            walkTo(parts);
        } finally {
            walkNoPlace = false;
        }
    }

    private void giveUpScaffold(Scaf sc, String why) {
        warning("Scaffold block left standing at %d %d %d: %s.", sc.pos.getX(), sc.pos.getY(), sc.pos.getZ(), why);
        dropScaffold(sc);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Wrong blocks

    private void skipWrong() {
        for (int i = 0; i < 32; i++) {
            BuildOrderer.Cell c = firstWrong();
            if (c == null) break;
            scratch.set(c.x(), c.y(), c.z());
            BlockState want = built.stateAt(scratch);
            Info in = want == null ? null : infoOf(want);
            String existing = StateClassifier.idOf(mc.level.getBlockState(scratch).getBlock());
            wrongQueue.remove(c);
            wrongKeys.remove(key(c));
            if (PrinterActions.free(mc.level.getBlockState(scratch))) {
                // It went away by itself: build here again
                orderer.add(c);
                continue;
            }
            finish(c, in, Fin.SKIPPED, "wrong block " + existing);
        }
    }

    private void breakTick() {
        BuildOrderer.Cell c = breaking != null ? breaking : firstWrong();
        if (c == null) {
            breaking = null;
            return;
        }
        scratch.set(c.x(), c.y(), c.z());
        BlockPos pos = scratch.immutable();
        BlockState st = mc.level.getBlockState(pos);
        long k = key(c);
        if (PrinterActions.free(st)) {
            // Broken (or gone): the cell is built again
            wrongQueue.removeIf(w -> key(w) == k);
            wrongKeys.remove(k);
            ownWrong.remove(k);
            if (breaking != null && key(breaking) == k) brokenCount++;
            breaking = null;
            breakTicks = 0;
            orderer.add(c);
            return;
        }
        BlockState want = built.stateAt(pos);
        Info in = want == null ? null : infoOf(want);
        String existing = StateClassifier.idOf(st.getBlock());
        if (neverBreakContainers.get() && mc.level.getBlockEntity(pos) != null) {
            finish(c, in, Fin.SKIPPED, "wrong block " + existing + " (has contents, never-break-containers)");
            return;
        }
        String risk = breakRisk(pos, st, c, want, in);
        if (risk != null) {
            finish(c, in, Fin.SKIPPED, "wrong block " + existing + " (" + risk + ")");
            return;
        }
        if (st.getDestroySpeed(mc.level, pos) < 0) {
            finish(c, in, Fin.SKIPPED, "wrong block " + existing + " (cannot be broken)");
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        if (eye.distanceTo(Vec3.atCenterOf(pos)) > reach()) {
            breaking = null;
            if (!canMoveNow()) {
                finish(c, in, Fin.SKIPPED, "wrong block " + existing + " (out of reach, walking is off)");
                return;
            }
            walkTo(List.of(pos));
            return;
        }
        releaseWalk();
        breaking = c;
        if (++breakTicks > 200) {
            breakTicks = 0;
            finish(c, in, Fin.SKIPPED, "wrong block " + existing + " (could not be broken)");
            return;
        }
        if (!budget.trySpend(System.currentTimeMillis(), 2)) return;
        if (savedSlot < 0) savedSlot = Players.selectedSlot();
        FindItemResult tool = InvUtils.findFastestTool(st);
        if (tool.found() && tool.slot() != Players.selectedSlot()) InvUtils.swap(tool.slot(), false);
        PrinterActions.breakBlock(pos, rotationMode.get() != RotationMode.None, swing.get());
    }

    /**
     * Why breaking it is risky (a short phrase), or null when it is not: it could let a fluid flow, drop a column of sand, or
     * put us next to lava or fire. A real liquid block next to the cell always counts. Waterlogged neighbours (slabs, stairs,
     * kelp) are water sources too: the block is then only broken when its replacement can be placed straight after
     * ({@link BreakRules}).
     */
    private String breakRisk(BlockPos pos, BlockState st, BuildOrderer.Cell c, BlockState want, Info in) {
        final String generic = "next to a fluid, a hazard or a falling block";
        if (st.getBlock() instanceof FallingBlock || mc.level.getBlockState(pos.above()).getBlock() instanceof FallingBlock) return generic;
        if (GroundCollector.hazardous(pos)) return generic;
        boolean liquid = false, wet = !(st.getBlock() instanceof LiquidBlock) && !st.getFluidState().isEmpty();
        for (Direction d : DIRECTIONS) {
            BlockPos n = pos.relative(d);
            if (!mc.level.isLoaded(n)) return generic;
            BlockState ns = mc.level.getBlockState(n);
            if (ns.getBlock() instanceof LiquidBlock) liquid = true;
            else if (!ns.getFluidState().isEmpty()) wet = true;
        }
        boolean wantedAir = want == null || want.isAir();
        boolean follows = wet && !liquid && !wantedAir && replacementFollows(c, want, in, pos);
        BreakRules.Verdict v = BreakRules.fluidVerdict(liquid, wet, wantedAir, follows, follows);
        return switch (v) {
            case OK -> null;
            case FLUID -> generic;
            case NEXT_TO_WATER -> "next to water";
        };
    }

    /** The wanted block (or its raw block) is carried and a placement recipe reaches the cell from where the player stands. */
    private boolean replacementFollows(BuildOrderer.Cell c, BlockState want, Info in, BlockPos pos) {
        if (in == null || in.item() == null) return false;
        Map<Item, Integer> have = inventoryCounts();
        Use use = useFor(in, have, new HashMap<>());
        if (have.getOrDefault(use.item(), 0) < use.count()) return false;
        ToolSource ts = in.toolSource();
        StateSpec spec = use.raw() && ts != null && ts.rawSpec() != null ? ts.rawSpec() : in.spec();
        boolean[] clickable = new boolean[6], interactive = new boolean[6];
        Dir[] dirs = Dir.values();
        for (int i = 0; i < 6; i++) {
            Dir d = dirs[i];
            int nx = c.x() + d.dx, ny = c.y() + d.dy, nz = c.z() + d.dz;
            clickable[i] = orderer.isPlacedSolid(nx, ny, nz) || worldView.solid(nx, ny, nz);
            if (clickable[i]) {
                scratch2.set(nx, ny, nz);
                BlockState ns = mc.level.getBlockState(scratch2);
                interactive[i] = BlockUtils.isClickable(ns.getBlock()) || mc.level.getBlockEntity(scratch2) != null;
            }
        }
        PlacementMapper.NeighbourView view = new PlacementMapper.NeighbourView() {
            @Override
            public boolean clickable(Dir d) {
                return clickable[d.ordinal()];
            }

            @Override
            public boolean interactive(Dir d) {
                return interactive[d.ordinal()];
            }
        };
        Vec3 eye = mc.player.getEyePosition();
        double reach = reach();
        for (PlaceRecipe r : PlacementMapper.recipes(spec, view, airplace.get())) {
            double[] h = r.hitAt(pos.getX(), pos.getY(), pos.getZ());
            if (eye.distanceTo(new Vec3(h[0], h[1], h[2])) <= reach) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Walking

    private boolean movementKeyDown() {
        return Input.isPressed(mc.options.keyUp) || Input.isPressed(mc.options.keyDown) || Input.isPressed(mc.options.keyLeft)
            || Input.isPressed(mc.options.keyRight);
    }

    /** Walking is on, Baritone is there, and nobody else has confined it (or we have). */
    private boolean canWalkNow() {
        return walk.get() && SmartPlaceSession.BARITONE && (usedConfine || !confinedByOther());
    }

    /** Any way of moving works: walking, or flying when that is chosen and a flight module is there. */
    private boolean canMoveNow() {
        if (!walk.get()) return false;
        if (movement.get() != Movement.StandOnBuild && fly.available()) return true;
        return canWalkNow();
    }

    private static boolean confinedByOther() {
        return SmartPlaceSession.BARITONE && BaritoneHelper.confined();
    }

    /** Takes Baritone's break/place settings away for the run (once), so it only walks. */
    private boolean confineBaritone() {
        if (!SmartPlaceSession.BARITONE) return false;
        if (usedConfine) return true;
        if (BaritoneHelper.confined()) {
            if (!walkWarned) warning("Another module has Baritone confined. Not walking.");
            walkWarned = true;
            return false;
        }
        BaritoneHelper.protect(List.of());
        usedProtect = true;
        BaritoneHelper.confine(safeWalk.get());
        usedConfine = true;
        return true;
    }

    /** Trips for containers and shulkers walk only: no placing, whatever the building walk allowed. */
    private void walkOnly() {
        if (SmartPlaceSession.BARITONE && usedConfine && BaritoneHelper.placingAllowed()) BaritoneHelper.forbidPlacing();
    }

    private void walkTick() {
        if (plan == null || plan.walkParts.isEmpty()) return;
        if (walkSince < 0) walkSince = tick;
        if (tick - walkSince > WALK_GIVE_UP) {
            // Never got in reach: those cells are given up on
            for (BlockPos p : plan.walkParts) {
                BuildOrderer.Cell c = cellAt(p);
                if (c != null) {
                    BlockState w = built.stateAt(scratch.set(c.x(), c.y(), c.z()));
                    finish(c, w == null ? null : infoOf(w), Fin.FAILED, "could not get in reach of it");
                }
            }
            walkSince = -1;
            releaseWalk();
            return;
        }
        walkTo(plan.walkParts);
    }

    private BuildOrderer.Cell cellAt(BlockPos p) {
        for (BuildOrderer.Cell c : farCells) if (c.x() == p.getX() && c.y() == p.getY() && c.z() == p.getZ()) return c;
        if (unloadedHint != null && unloadedHint.x() == p.getX() && unloadedHint.y() == p.getY() && unloadedHint.z() == p.getZ()) return unloadedHint;
        return null;
    }

    /** Moves toward cells that are out of reach: by walking with Baritone or by flying, as the movement setting and the policy say. */
    private void walkTo(List<BlockPos> parts) {
        if (parts.isEmpty() || !walk.get() || mc.player == null) return;
        if (pauseOnInput.get() && movementKeyDown()) {
            releaseWalk();
            return;
        }
        MovePolicy.Way way = pickWay(parts);
        if (way != lastWay) switchWay(way);
        if (way == MovePolicy.Way.FLY) flyTo(parts);
        else standTo(parts);
    }

    private double nearestDistance(List<BlockPos> parts) {
        Vec3 pos = mc.player.position();
        double best = Double.MAX_VALUE;
        for (BlockPos b : parts) best = Math.min(best, pos.distanceTo(Vec3.atCenterOf(b)));
        return best;
    }

    private MovePolicy.Way pickWay(List<BlockPos> parts) {
        movePolicy.setMode(movement.get().mode);
        boolean flightOk = movement.get() != Movement.StandOnBuild && fly.available();
        if (movement.get() != Movement.StandOnBuild && !flightOk && !flyNoModuleWarned) {
            flyNoModuleWarned = true;
            warning("movement %s needs a flight module: add one to flight-modules. Walking instead.", movement.get());
        }
        MovePolicy.Way way = movePolicy.update(new MovePolicy.Obs(tick, nearestDistance(parts), pathFailures, flightOk, mc.player.onGround()));
        // Walking needs Baritone and nobody else confining it: with flight there, it is flown
        if (way == MovePolicy.Way.WALK && flightOk && !canWalkNow()) return MovePolicy.Way.FLY;
        return way;
    }

    private void switchWay(MovePolicy.Way way) {
        lastWay = way;
        if (way == MovePolicy.Way.FLY) {
            if (walkGoal != null) releaseWalk();
            QwerteAddon.LOG.info("printer: flying to the next blocks ({})", fly.sourceName());
            if (movement.get() == Movement.StandThenFly) info("Walking got nowhere: flying (%s).", fly.sourceName());
        } else {
            fly.shutdown();
            flyNotice();
            hover = null;
        hoverFor = null;
            QwerteAddon.LOG.info("printer: walking to the next blocks");
            if (movement.get() == Movement.StandThenFly) info("Trying to walk again.");
        }
    }

    private void flyNotice() {
        String n = fly.takeNotice();
        if (n != null) warning("%s", n);
    }

    // Flying

    private final BlockPos.MutableBlockPos flyPos = new BlockPos.MutableBlockPos();
    private BlockPos hoverFor;

    private void flyTo(List<BlockPos> parts) {
        // Baritone and the flight module must not both steer
        if (walkGoal != null) releaseWalk();
        BlockPos first = parts.get(0);
        if (hover == null && tick - hoverAt >= HOVER_EVERY || hover != null && (!hoverOk(parts) || !first.equals(hoverFor) && tick - hoverAt >= HOVER_EVERY)) {
            hover = chooseHover(parts);
            hoverFor = first;
            hoverAt = tick;
            if (hover == null) QwerteAddon.LOG.info("printer: no free spot to hover in reach of {} {} {}", first.getX(), first.getY(), first.getZ());
        }
        if (hover == null) {
            fly.hold();
            return;
        }
        fly.drive(tick, hover.x() + 0.5, hover.y(), hover.z() + 0.5);
        flyNotice();
    }

    private double hoverReach() {
        return Math.max(1.0, reach() - 0.75);
    }

    /** The spot is still free and still in reach of one of the targets. */
    private boolean hoverOk(List<BlockPos> parts) {
        if (flyBlocked(hover.x(), hover.y(), hover.z()) || flyBlocked(hover.x(), hover.y() + 1, hover.z())) return false;
        for (BlockPos b : parts) {
            if (HoverPoint.within(hover.x(), hover.y(), hover.z(), new int[] { b.getX(), b.getY(), b.getZ() }, hoverReach())) return true;
        }
        return false;
    }

    private HoverPoint.Point chooseHover(List<BlockPos> parts) {
        List<int[]> targetCells = new ArrayList<>();
        for (BlockPos b : parts) {
            targetCells.add(new int[] { b.getX(), b.getY(), b.getZ() });
            if (targetCells.size() >= 8) break;
        }
        Vec3 pos = mc.player.position();
        return HoverPoint.choose(targetCells, pos.x, pos.y, pos.z, hoverReach(), this::flyBlocked, this::flyDiscouraged);
    }

    /** A cell the body can't be in: solid, fluid, unloaded, or a block of the layer being built that is still to be placed. */
    private boolean flyBlocked(int x, int y, int z) {
        flyPos.set(x, y, z);
        if (!mc.level.isLoaded(flyPos)) return true;
        BlockState st = mc.level.getBlockState(flyPos);
        if (!st.getCollisionShape(mc.level, flyPos).isEmpty() || !st.getFluidState().isEmpty()) return true;
        Integer active = orderer == null ? null : orderer.activeLayer();
        if (targets.contains(x, y, z) && (active == null || y <= active)) return true;
        return partBits.contains(x, y, z) || scaffolds.containsKey(key(x, y, z));
    }

    /** A cell of the schematic that is not built yet (a later layer): hovering there is allowed but not liked. */
    private boolean flyDiscouraged(int x, int y, int z) {
        if (targets.contains(x, y, z)) return true;
        if (built == null) return false;
        BlockState w = built.stateAt(flyPos.set(x, y, z));
        return w != null && !w.isAir();
    }

    // Standing on the build

    private void standTo(List<BlockPos> parts) {
        if (!SmartPlaceSession.BARITONE) return;
        if (!confineBaritone()) return;
        if (BaritoneHelper.placingAllowed()) standTrackUntil = tick + 10;
        boolean going = BaritoneHelper.goalIs((baritone.api.pathing.goals.Goal) walkGoal);
        if (walkGoal != null && !going) {
            // Baritone let go of it by itself: it got there, or found no path. Still out of reach means it found none
            walkGoal = null;
            if (nearestEyeDistance(parts) > reach()) {
                pathFailures++;
                retryWalkAt = tick + PATH_RETRY;
            }
        }
        if (going && tick - lastWalk < 60) return;
        if (tick < retryWalkAt) return;
        if (walkGoal != null) BaritoneHelper.releaseOurs((baritone.api.pathing.goals.Goal) walkGoal);
        if (walkNoPlace) walkOnly();
        else placeWhileWalking();
        walkGoal = BaritoneHelper.moveInReach(parts, Math.max(1.0, reach() - 0.25), p -> targets.contains(p.getX(), p.getY(), p.getZ()) || partBits.contains(p.getX(), p.getY(), p.getZ()) || scaffolds.containsKey(key(p.getX(), p.getY(), p.getZ())));
        lastWalk = tick;
    }

    private double nearestEyeDistance(List<BlockPos> parts) {
        Vec3 eye = mc.player.getEyePosition();
        double best = Double.MAX_VALUE;
        for (BlockPos b : parts) best = Math.min(best, eye.distanceTo(Vec3.atCenterOf(b)));
        return best;
    }

    /** Lets Baritone place the spare blocks it climbs with (never anything else, never breaking); none carried means walking only. */
    private void placeWhileWalking() {
        standItems = spareList(inventoryCounts());
        if (standItems.isEmpty()) {
            BaritoneHelper.forbidPlacing();
            if (!standNoSpareWarned) {
                standNoSpareWarned = true;
                warning("No spare blocks to climb with: carry blocks the schematic doesn't use (cobblestone, dirt) so Baritone can pillar up. Walking only.");
            }
            return;
        }
        BaritoneHelper.allowPlacing(standItems);
        standTrackUntil = tick + 10;
    }

    /**
     * Baritone is placing the spare blocks it climbs with: a spare block that appears where there was air next to the feet,
     * outside the schematic, is listed with the scaffold so it is broken again once nothing needs it. A block it placed inside
     * a cell the schematic wants is a wrong block, and the wrong-block rules deal with it.
     */
    private void trackStandBlocks() {
        if (built == null || standItems.isEmpty() || mc.player == null) return;
        BlockPos f = mc.player.blockPosition();
        for (int dy = -2; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = f.getX() + dx, y = f.getY() + dy, z = f.getZ() + dz;
                    scratch.set(x, y, z);
                    if (!mc.level.isLoaded(scratch)) continue;
                    long k = key(x, y, z);
                    BlockState st = mc.level.getBlockState(scratch);
                    if (st.isAir()) {
                        if (airSeen.size() >= AIR_SEEN_MAX) airSeen.clear();
                        airSeen.add(k);
                        continue;
                    }
                    if (!airSeen.remove(k)) continue;
                    Item item = st.getBlock().asItem();
                    if (!(item instanceof BlockItem) || !standItems.contains(item) || scaffolds.containsKey(k)) continue;
                    if (partBits.contains(x, y, z)) continue;
                    BlockState want = built.stateAt(scratch);
                    if (targets.contains(x, y, z) && want != null && !want.isAir()) {
                        // Baritone put a spare block in a cell the schematic still wants: it is ours, so it is broken and the
                        // cell built, whatever wrong-blocks says (the break still goes through the container and fluid rules)
                        Info wanted = infoOf(want);
                        ownWrong.add(k);
                        evaluate(BuildOrderer.Cell.of(x, y, z, wanted.spec(), null), wanted, st);
                        continue;
                    }
                    if (targets.contains(x, y, z) || want != null && !want.isAir()) continue;
                    if (!removeStandBlocks.get()) continue;
                    Scaf sc = new Scaf(new BlockPos(x, y, z), item, st, scaffoldInfoOf(item), tick);
                    sc.stand = true;
                    sc.placed = sc.built = true;
                    scaffolds.put(k, sc);
                    standPlaced++;
                }
            }
        }
    }

    /** Lets go of our own goal only. */
    private void releaseWalk() {
        if (SmartPlaceSession.BARITONE && walkGoal != null) {
            BaritoneHelper.releaseOurs((baritone.api.pathing.goals.Goal) walkGoal);
            releasedAt = tick;
        }
        walkGoal = null;
        removalWalk = false;
        // Standing still to place: the keys go up (the flight module stays on, the player hovers) and Baritone may not place
        fly.hold();
        walkOnly();
    }

    /** Gives Baritone back everything this run took, and lets go of the flight module and the keys. */
    private void releaseBaritone() {
        releaseWalk();
        try {
            fly.shutdown();
        } finally {
            try {
                flyNotice();
            } finally {
                releaseBaritoneRest();
            }
        }
    }

    private void releaseBaritoneRest() {
        hover = null;
        hoverFor = null;
        movePolicy.reset();
        lastWay = MovePolicy.Way.WALK;
        pathFailures = retryWalkAt = 0;
        standItems = List.of();
        standTrackUntil = -1;
        airSeen.clear();
        standNoSpareWarned = flyNoModuleWarned = false;
        if (SmartPlaceSession.BARITONE) {
            if (usedConfine) BaritoneHelper.unconfine();
            if (usedProtect) BaritoneHelper.unprotect();
        }
        usedConfine = false;
        usedProtect = false;
        walkWarned = false;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Inventory

    /** Cached by stack for twenty ticks: a shulker box's contents are decoded from its components. */
    private boolean loadedShulker(ItemStack stack) {
        if (stack.isEmpty() || !Utils.isShulker(stack.getItem())) return false;
        if (tick - loadedCacheTick >= 20 || loadedCache.size() > 64) {
            loadedCache.clear();
            loadedCacheTick = tick;
        }
        Boolean known = loadedCache.get(stack);
        if (known != null) return known;
        boolean loaded = shulkerHasContents(stack);
        loadedCache.put(stack, loaded);
        return loaded;
    }

    private static boolean shulkerHasContents(ItemStack stack) {
        ItemStack[] contents = new ItemStack[27];
        Utils.getItemsInContainerItem(stack, contents);
        for (ItemStack s : contents) if (s != null && !s.isEmpty()) return true;
        return false;
    }

    private boolean isBuildItem(ItemStack stack) {
        return buildItems.contains(stack.getItem());
    }

    private Map<Item, Integer> inventoryCounts() {
        Map<Item, Integer> out = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.isEmpty() || loadedShulker(s)) continue;
            out.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        ItemStack off = mc.player.getOffhandItem();
        if (!off.isEmpty() && !loadedShulker(off)) out.merge(off.getItem(), off.getCount(), Integer::sum);
        return out;
    }

    private Map<String, Long> haveById() {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<Item, Integer> e : inventoryCounts().entrySet()) out.put(BuiltInRegistries.ITEM.getKey(e.getKey()).toString(), (long) e.getValue());
        return out;
    }

    private int hotbarSlot(Item item, int count) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.is(item) && s.getCount() >= count && !loadedShulker(s)) return i;
        }
        // The off hand counts too (AutoHotbar sees it as present); the block is placed from it with the off hand
        ItemStack off = mc.player.getOffhandItem();
        if (off.is(item) && off.getCount() >= count && !loadedShulker(off)) return OFFHAND;
        return -1;
    }

    private int emptySlots() {
        int n = 0;
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).isEmpty()) n++;
        return n;
    }

    /** A stack of this kind could be taken: it merges into a stack of the same item, or an empty slot is left beyond the reserve. */
    private boolean hasRoomFor(Item item) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.is(item) && s.getCount() < s.getMaxStackSize() && !loadedShulker(s)) return true;
        }
        return emptySlots() > freeSlots.get();
    }

    /** The real room: what merges into stacks of the same item, the rest spilling into empty slots, the reserve left over. */
    private boolean roomFor(ItemStack stack) {
        int empty = 0, mergeable = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.isEmpty()) empty++;
            else if (ItemStack.isSameItemSameComponents(s, stack)) mergeable += Math.max(0, s.getMaxStackSize() - s.getCount());
        }
        return TakeRules.room(stack.getCount(), stack.getMaxStackSize(), mergeable, empty, freeSlots.get());
    }

    private boolean supplyPossible(String lackingId) {
        if ((useShulkers.get() || resupply.echestOut > 0) && shulkerSlotFor(null, Set.of()) >= 0) return true;
        if (useContainers.get() && !containers.get().isEmpty()) return true;
        return echestUsable(lackingId == null ? List.of() : List.of(lackingId));
    }

    /** The slot (0 to 35) of an ender chest in the inventory, or -1. */
    private int echestSlot() {
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).is(Items.ENDER_CHEST)) return i;
        return -1;
    }

    /**
     * The ender chest can be used as a source: the setting is on, one is carried, it can be picked up again (silk touch, or
     * MineAnyway with any pickaxe), and it is not known to hold none of the wanted items.
     */
    private boolean echestUsable(java.util.Collection<String> wantedIds) {
        if (!useEnderChest.get() || mc.player == null || echestSlot() < 0) return false;
        boolean silk = InvUtils.find(Restocker::isSilkPickaxe).found();
        if (!silk && !(echestNoSilk.get() == EchestNoSilk.MineAnyway && InvUtils.find(st -> st.is(ItemTags.PICKAXES)).found())) return false;
        EchestMemory m = echestMemory;
        if (m != null && System.currentTimeMillis() - m.at() < ECHEST_MEMORY_MS && !wantedIds.isEmpty()) {
            for (String id : wantedIds) if (m.ids().contains(id)) return true;
            return false;
        }
        return true;
    }

    /** What the Printer lets the stash orders see. */
    private final class OrdersView implements StashOrders.View {
        @Override public Map<String, Long> need() { return tally.need(); }

        @Override public Map<String, Long> carried() {
            long now = System.currentTimeMillis();
            if (now - carriedAt < 1000) return carriedCache;
            carriedAt = now;
            Map<String, Long> out = new HashMap<>();
            for (int i = 0; i < 36; i++) {
                ItemStack st = mc.player.getInventory().getItem(i);
                if (st.isEmpty()) continue;
                if (Utils.isShulker(st.getItem())) {
                    ItemStack[] contents = new ItemStack[27];
                    Utils.getItemsInContainerItem(st, contents);
                    for (ItemStack in : contents) {
                        if (in != null && !in.isEmpty()) out.merge(BuiltInRegistries.ITEM.getKey(in.getItem()).toString(), (long) in.getCount(), Long::sum);
                    }
                } else {
                    out.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), (long) st.getCount(), Long::sum);
                }
            }
            carriedCache = out;
            return out;
        }

        @Override public int maxStack(String itemId) {
            Item item = itemsById == null ? null : itemsById.get(itemId);
            return item == null ? 64 : new ItemStack(item).getMaxStackSize();
        }

        @Override public boolean localFetch() { return resupply.running() || session.active(); }

        @Override public String itemName(String itemId) {
            Item item = itemsById == null ? null : itemsById.get(itemId);
            return item == null ? LitematicReader.clean(itemId, 40) : Printer.itemName(item);
        }

        @Override public String server() { return Stash.currentServer(); }

        @Override public void say(boolean warn, String text) {
            // Text from the server was cleaned already; once more here so nothing can reach the chat as styling or a format
            String safe = LitematicReader.clean(text, 700);
            if (warn) warning("%s", safe);
            else info("%s", safe);
            QwerteAddon.LOG.info("printer: {}", safe);
        }
    }

    private static String itemName(Item item) {
        return LitematicReader.clean(new ItemStack(item).getHoverName().getString(), 60);
    }

    /** The first inventory slot (0 to 35) holding a shulker box with one of the wanted items in it (any item when null), not in {@code skip}. */
    private int shulkerSlotFor(Map<String, Long> want, Set<Integer> skip) {
        for (int i = 0; i < 36; i++) {
            if (skip.contains(i)) continue;
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.isEmpty() || !Utils.isShulker(s.getItem())) continue;
            ItemStack[] contents = new ItemStack[27];
            Utils.getItemsInContainerItem(s, contents);
            for (ItemStack in : contents) {
                if (in == null || in.isEmpty()) continue;
                if (want == null ? buildItems.contains(in.getItem()) : want.containsKey(BuiltInRegistries.ITEM.getKey(in.getItem()).toString())) return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fetching materials

    /** The resupply flow: pick a source, walk to it, open it, take what is needed, close it, or unpack a shulker box. */
    private final class Resupply {
        private enum Stage { LAND, PICK, WALK, OPEN, TAKE, CLOSE, UNPACK, ECHEST_TAKE, ECHEST_RETURN }

        private boolean running, screenOpen;
        private Stage stage = Stage.PICK;
        private String failure, failureOut;
        private long haveAtStart, haveAtUnpack;
        private final Set<Long> tried = new HashSet<>();
        private final Set<Integer> triedShulkers = new HashSet<>();
        private int[] target;
        private Object goal;
        private int ticks, openTries, wait, unpacks, unpackSlot = -1;
        private int landTicks;
        private SmartPlaceSession.Result unpackResult;
        private final Map<Integer, Integer> slotTries = new HashMap<>();
        private boolean roomBlocked, fullSeen;
        // The ender chest: boxes taken out of it (and not yet back), what the inventory held before, one trip each way
        private int echestOut;
        private List<EchestReturn.ShulkerSlot> echestBefore = List.of();
        private boolean echestTried, returnTried;
        private SmartPlaceSession.Result echestResult;
        private String endMessage;
        private final Map<Integer, Integer> returnTries = new HashMap<>();

        boolean running() {
            return running;
        }

        String takeFailure() {
            String f = failureOut;
            failureOut = null;
            return f;
        }

        void start() {
            if (running) return;
            releaseWalk();
            running = true;
            failure = null;
            tried.clear();
            triedShulkers.clear();
            target = null;
            unpacks = 0;
            stage = Stage.PICK;
            wait = 0;
            fullSeen = false;
            slotTries.clear();
            echestOut = 0;
            echestBefore = List.of();
            echestTried = false;
            returnTried = false;
            echestResult = null;
            endMessage = null;
            // A trip on foot starts on the ground: a hovering player comes down first (a module we switched on is switched off)
            landTicks = 0;
            stage = fly.airborne() ? Stage.LAND : Stage.PICK;
            haveAtStart = wantedHave();
            QwerteAddon.LOG.info("printer: fetching materials");
        }

        /** Stops now (disable, world change). */
        void abort() {
            // Boxes taken out of the ender chest are never left unmentioned
            warnLeftover();
            running = false;
            failureOut = null;
            echestResult = null;
            endMessage = null;
            if (SmartPlaceSession.BARITONE && goal != null) BaritoneHelper.releaseOurs((baritone.api.pathing.goals.Goal) goal);
            goal = null;
            target = null;
            unpackSlot = -1;
        }

        private void end(String why) {
            warnLeftover();
            running = false;
            screenOpen = false;
            if (SmartPlaceSession.BARITONE && goal != null) BaritoneHelper.releaseOurs((baritone.api.pathing.goals.Goal) goal);
            goal = null;
            target = null;
            if (why != null) {
                failure = why;
                failureOut = why;
            }
        }

        /** What is still to be fetched: item id to how many, from the build's remaining need and what is carried. */
        private Map<String, Long> wanted() {
            return MaterialPlanner.missing(tally.need(), haveById());
        }

        private long wantedHave() {
            long n = 0;
            Map<String, Long> have = haveById();
            for (String id : tally.need().keySet()) n += have.getOrDefault(id, 0L);
            return n;
        }

        void tick() {
            if (!running) return;
            if (mc.player == null || mc.level == null || mc.level != runLevel) {
                abort();
                return;
            }
            if (session.active()) return;
            ticks++;
            if (wait > 0) {
                wait--;
                return;
            }
            switch (stage) {
                case LAND -> landStep();
                case PICK -> pick();
                case WALK -> walkStep();
                case OPEN -> openStep();
                case TAKE -> {
                    if (mc.player.containerMenu == mc.player.inventoryMenu) {
                        // It closed under us
                        screenOpen = false;
                        mark();
                        stage = Stage.PICK;
                    }
                    else if (takeStep(mc.player.containerMenu, false)) stage = Stage.CLOSE;
                }
                case CLOSE -> {
                    if (mc.player.containerMenu != mc.player.inventoryMenu) {
                        mc.player.closeContainer();
                        budget.record(System.currentTimeMillis(), 1);
                    }
                    screenOpen = false;
                    mark();
                    stage = Stage.PICK;
                    wait = actionDelay.get();
                }
                case UNPACK -> unpacked();
                case ECHEST_TAKE -> echestTaken();
                case ECHEST_RETURN -> echestReturned();
            }
        }

        /**
         * Flying: the trip is not made from the air. The player descends (holding the descend key only); if there is no safe
         * ground to come down to, or it takes too long, the trip is skipped with a message (a hovering player is never sent
         * walking, and a flight module is never switched off in mid-air).
         */
        private void landStep() {
            FlyController.Landing l = fly.land(tick);
            flyNotice();
            if (l == FlyController.Landing.LANDED) {
                hover = null;
                hoverFor = null;
                landTicks = 0;
                stage = Stage.PICK;
                return;
            }
            if (l == FlyController.Landing.STUCK || ++landTicks > LAND_GIVE_UP) {
                fly.hold();
                end("you are flying and could not land safely (no ground or water below, a screen open, or it took too long).");
            }
        }

        private void mark() {
            if (target != null) tried.add(key(target[0], target[1], target[2]));
            target = null;
        }

        private void pick() {
            Map<String, Long> want = wanted();
            if (want.isEmpty()) {
                finish(null);
                return;
            }
            if (mc.screen != null || mc.player.containerMenu != mc.player.inventoryMenu) {
                finish("a screen is open; close it so materials can be fetched.");
                return;
            }
            if (!mc.player.containerMenu.getCarried().isEmpty()) {
                finish("something is on your cursor; put it down so materials can be fetched.");
                return;
            }

            // 1. A shulker box in the inventory (also one taken out of the ender chest)
            if ((useShulkers.get() || echestOut > 0) && unpacks < MAX_UNPACKS) {
                int slot = shulkerSlotFor(want, triedShulkers);
                if (slot >= 0 && !(unpacks > 0 && wantedHave() <= haveAtUnpack)) {
                    if (startUnpack(slot)) return;
                    triedShulkers.add(slot);
                }
            }

            // 2. The ender chest: one trip to take, one to put the boxes back
            if (!echestTried && unpacks < MAX_UNPACKS && echestUsable(want.keySet())) {
                if (startEchestTake()) return;
            }

            // 3. A container
            int[] spot = nextContainer(want);
            if (spot != null) {
                target = spot;
                stage = Stage.WALK;
                ticks = 0;
                openTries = 0;
                slotTries.clear();
                return;
            }
            if (wantedHave() > haveAtStart) finish(null);
            else finish(noSourceMessage(want));
        }

        private String noSourceMessage(Map<String, Long> want) {
            if (fullSeen) return "your inventory is full, so nothing more can be taken. Free a slot.";
            String first = want.keySet().iterator().next();
            Item item = itemsById.get(first);
            String name = item == null ? LitematicReader.clean(first, 40) : itemName(item);
            return "no shulker box, listed container or ender chest holds " + name + ", or none could be reached.";
        }

        /** Ends the flow; first puts the boxes taken out of the ender chest back when there are some and the chest is carried. */
        private void finish(String why) {
            if (echestOut > 0 && !returnTried && startEchestReturn(why)) return;
            end(why);
        }

        // The ender chest

        private List<EchestReturn.ShulkerSlot> shulkerSnapshot() {
            List<EchestReturn.ShulkerSlot> out = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                ItemStack st = mc.player.getInventory().getItem(i);
                if (st.isEmpty() || !Utils.isShulker(st.getItem())) continue;
                out.add(new EchestReturn.ShulkerSlot(i, BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), String.valueOf(st.getComponents().hashCode())));
            }
            return out;
        }

        private boolean startEchestTake() {
            int slot = echestSlot();
            if (slot < 0) return false;
            if (walk.get() && SmartPlaceSession.BARITONE) confineBaritone();
            releaseWalk();
            if (savedSlot < 0) savedSlot = Players.selectedSlot();
            echestTried = true;
            echestBefore = shulkerSnapshot();
            echestResult = null;
            slotTries.clear();
            if (!session.startFromInventory(Kind.ECHEST, slot)) return false;
            stage = Stage.ECHEST_TAKE;
            return true;
        }

        private boolean startEchestReturn(String why) {
            if (mc.player == null || mc.screen != null || mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()) return false;
            int slot = echestSlot();
            if (slot < 0) return false;
            if (EchestReturn.slotsToReturn(shulkerSnapshot(), echestBefore, echestOut).isEmpty()) {
                echestOut = 0;
                return false;
            }
            if (walk.get() && SmartPlaceSession.BARITONE) confineBaritone();
            releaseWalk();
            if (savedSlot < 0) savedSlot = Players.selectedSlot();
            returnTried = true;
            returnTries.clear();
            echestResult = null;
            wait = 0;
            if (!session.startFromInventory(Kind.ECHEST, slot)) return false;
            endMessage = why;
            stage = Stage.ECHEST_RETURN;
            return true;
        }

        private void echestTaken() {
            SmartPlaceSession.Result r = echestResult;
            echestResult = null;
            echestOut = EchestReturn.slotsToReturn(shulkerSnapshot(), echestBefore, 54).size();
            if (r == SmartPlaceSession.Result.ORPHANED) {
                end("the ender chest was left in the world (see the message above); it is listed under Left behind.");
                return;
            }
            if (r != SmartPlaceSession.Result.DONE) {
                finish("the ender chest could not be used (see the message above).");
                return;
            }
            stage = Stage.PICK;
            wait = actionDelay.get();
        }

        private void echestReturned() {
            SmartPlaceSession.Result r = echestResult;
            echestResult = null;
            String why = endMessage;
            endMessage = null;
            if (r == SmartPlaceSession.Result.ORPHANED) {
                end("the ender chest was left in the world (see the message above); it is listed under Left behind.");
                return;
            }
            // Whatever is still in the inventory is told by end()
            end(why);
        }

        /** Says how many boxes from the ender chest are still in the inventory, then forgets them. */
        private void warnLeftover() {
            // While the chest is still being opened echestOut is not known yet: the boxes are counted from the inventory
            int cap = echestOut > 0 ? echestOut : running && stage == Stage.ECHEST_TAKE ? 54 : 0;
            if (cap <= 0) return;
            boolean here = mc.player != null && mc.level != null;
            int n = here ? EchestReturn.slotsToReturn(shulkerSnapshot(), echestBefore, cap).size() : cap;
            echestOut = 0;
            if (n > 0 && !here) {
                QwerteAddon.LOG.warn("printer: up to {} shulker box(es) from the ender chest may be in the inventory (left the world)", n);
            }
            if (n > 0 && here) {
                warning("%s", n + (n == 1 ? " shulker box" : " shulker boxes") + " from your ender chest " + (n == 1 ? "is" : "are")
                    + " in your inventory. Put " + (n == 1 ? "it" : "them") + " back when you can.");
            }
        }

        /** The session's container is open (the host is asked every tick): what to do there depends on the stage. */
        boolean ownedOpen(AbstractContainerMenu menu) {
            return switch (stage) {
                case ECHEST_TAKE -> takeStep(menu, true);
                case ECHEST_RETURN -> returnStep(menu);
                default -> takeStep(menu, false);
            };
        }

        /** Shift-clicks one box that came out of the ender chest back in per call (one per action delay). True when none is left. */
        private boolean returnStep(AbstractContainerMenu menu) {
            if (wait > 0) {
                wait--;
                return false;
            }
            for (EchestReturn.ShulkerSlot box : EchestReturn.slotsToReturn(shulkerSnapshot(), echestBefore, echestOut)) {
                if (returnTries.getOrDefault(box.invIndex(), 0) >= TakeRules.MAX_TRIES) continue;
                Slot found = null;
                for (Slot slot : menu.slots) {
                    if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == box.invIndex()) {
                        found = slot;
                        break;
                    }
                }
                if (found == null) continue;
                if (!budget.trySpend(System.currentTimeMillis(), 1)) return false;
                returnTries.merge(box.invIndex(), 1, Integer::sum);
                PrinterActions.quickMove(menu, found.index);
                lastMenuTick = tick;
                wait = actionDelay.get();
                return false;
            }
            // Boxes went back with whatever was left in them: what the chest holds is known again
            rememberEchest(menu);
            return true;
        }

        private void rememberEchest(AbstractContainerMenu menu) {
            Set<String> ids = new HashSet<>();
            for (Slot slot : menu.slots) {
                if (slot.container == mc.player.getInventory()) continue;
                ItemStack st = slot.getItem();
                if (st.isEmpty()) continue;
                ids.add(BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
                if (Utils.isShulker(st.getItem())) {
                    ItemStack[] contents = new ItemStack[27];
                    Utils.getItemsInContainerItem(st, contents);
                    for (ItemStack in : contents) if (in != null && !in.isEmpty()) ids.add(BuiltInRegistries.ITEM.getKey(in.getItem()).toString());
                }
            }
            echestMemory = new EchestMemory(ids, System.currentTimeMillis());
        }

        private boolean startUnpack(int slot) {
            if (walk.get() && SmartPlaceSession.BARITONE) confineBaritone();
            releaseWalk();
            if (savedSlot < 0) savedSlot = Players.selectedSlot();
            unpackResult = null;
            slotTries.clear();
            haveAtUnpack = wantedHave();
            if (!session.startFromInventory(Kind.SHULKER, slot)) return false;
            unpackSlot = slot;
            unpacks++;
            stage = Stage.UNPACK;
            return true;
        }

        private void unpacked() {
            // The session has ended (tick() returns early while it runs)
            SmartPlaceSession.Result r = unpackResult;
            unpackResult = null;
            unpackSlot = -1;
            if (r == SmartPlaceSession.Result.ORPHANED) {
                end("a shulker box was left in the world (see the message above); it is listed under Left behind.");
                return;
            }
            if (r == SmartPlaceSession.Result.ABORTED) {
                finish("the shulker box could not be used (see the message above).");
                return;
            }
            stage = Stage.PICK;
            wait = actionDelay.get();
        }

        /** Containers to try: the listed ones, nearest first; one that was visited is not tried twice. */
        private int[] nextContainer(Map<String, Long> want) {
            List<int[]> spots = new ArrayList<>();
            String here = SmartPlaceSession.dimension();
            if (useContainers.get()) {
                for (String entry : containers.get()) {
                    int[] p = parseContainer(entry, here);
                    if (p != null) spots.add(p);
                }
            }
            int[] best = null;
            double bestD = Double.MAX_VALUE;
            for (int[] p : spots) {
                if (tried.contains(key(p[0], p[1], p[2]))) continue;
                // A bogus far entry is never walked to
                if (mc.player.distanceToSqr(p[0] + 0.5, p[1] + 0.5, p[2] + 0.5) > 128.0 * 128.0) continue;
                double d = mc.player.distanceToSqr(p[0] + 0.5, p[1] + 0.5, p[2] + 0.5);
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            return best;
        }

        private void walkStep() {
            BlockPos pos = new BlockPos(target[0], target[1], target[2]);
            Vec3 eye = mc.player.getEyePosition();
            if (eye.distanceTo(Vec3.atCenterOf(pos)) <= reach()) {
                if (SmartPlaceSession.BARITONE && goal != null) BaritoneHelper.releaseOurs((baritone.api.pathing.goals.Goal) goal);
                goal = null;
                stage = Stage.OPEN;
                ticks = 0;
                return;
            }
            if (!canWalkNow() || ticks > 20 * 90) {
                mark();
                stage = Stage.PICK;
                return;
            }
            if (pauseOnInput.get() && movementKeyDown()) return;
            if (BaritoneHelper.controlledByOther((baritone.api.pathing.goals.Goal) goal)) {
                // Not ours: wait, but not for ever
                if (ticks > 20 * 30) {
                    mark();
                    stage = Stage.PICK;
                }
                return;
            }
            if (!confineBaritone()) {
                mark();
                stage = Stage.PICK;
                return;
            }
            boolean going = goal != null && BaritoneHelper.goalIs((baritone.api.pathing.goals.Goal) goal);
            if (!going || ticks % 100 == 0) {
                if (goal != null) BaritoneHelper.releaseOurs((baritone.api.pathing.goals.Goal) goal);
                goal = BaritoneHelper.moveInReach(List.of(pos), Math.max(1.0, reach() - 0.25), p -> targets.contains(p.getX(), p.getY(), p.getZ()));
            }
        }

        private void openStep() {
            if (mc.player.containerMenu != mc.player.inventoryMenu) {
                screenOpen = true;
                lastMenuTick = tick;
                slotTries.clear();
                stage = Stage.TAKE;
                roomBlocked = false;
                wait = actionDelay.get();
                return;
            }
            if (openTries >= 4) {
                mark();
                stage = Stage.PICK;
                return;
            }
            BlockPos pos = new BlockPos(target[0], target[1], target[2]);
            String refused = containerRefusal(pos);
            if (refused != null) {
                warning("Not opening the container at %d %d %d: %s.", pos.getX(), pos.getY(), pos.getZ(), refused);
                mark();
                stage = Stage.PICK;
                return;
            }
            if (!budget.trySpend(System.currentTimeMillis(), 3)) return;
            openTries++;
            lastMenuTick = tick;
            Restocker.clickOpen(pos, rotationMode.get() != RotationMode.None, swing.get());
            wait = Math.max(actionDelay.get(), 10);
        }

        /**
         * Shift-clicks one wanted stack out of the open menu per call (one per action delay). Returns true when there is
         * nothing more to take, or no room to take it.
         */
        boolean takeStep(AbstractContainerMenu menu, boolean echest) {
            if (wait > 0) {
                wait--;
                return false;
            }
            Map<String, Long> want = wanted();
            int boxBudget = Integer.MAX_VALUE;
            if (echest) {
                // Boxes already taken will be unpacked: what is in them is not taken again
                List<EchestReturn.ShulkerSlot> got = EchestReturn.slotsToReturn(shulkerSnapshot(), echestBefore, 54);
                for (EchestReturn.ShulkerSlot box : got) {
                    ItemStack[] contents = new ItemStack[27];
                    Utils.getItemsInContainerItem(mc.player.getInventory().getItem(box.invIndex()), contents);
                    for (ItemStack in : contents) {
                        if (in == null || in.isEmpty()) continue;
                        want.computeIfPresent(BuiltInRegistries.ITEM.getKey(in.getItem()).toString(), (k, v) -> v - in.getCount());
                    }
                }
                want.values().removeIf(v -> v <= 0);
                boxBudget = Math.min(MAX_UNPACKS - unpacks - got.size(), Math.max(0, emptySlots() - freeSlots.get()));
            }
            if (want.isEmpty()) {
                if (echest) rememberEchest(menu);
                return true;
            }
            List<TakeRules.Slot> view = new ArrayList<>();
            for (Slot slot : menu.slots) {
                if (slot.container == mc.player.getInventory()) continue;
                ItemStack st = slot.getItem();
                if (st.isEmpty()) continue;
                String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                boolean plain = ItemStack.isSameItemSameComponents(st, new ItemStack(st.getItem()));
                boolean box = (useShulkers.get() || echest) && Utils.isShulker(st.getItem()) && boxHolds(st, want);
                view.add(new TakeRules.Slot(slot.index, id, plain, box, roomFor(st)));
            }
            TakeRules.Decision d = TakeRules.next(view, want, slotTries, boxBudget);
            if (d.slot() < 0) {
                if (d.blocked()) fullSeen = true;
                if (echest) rememberEchest(menu);
                return true;
            }
            if (!budget.trySpend(System.currentTimeMillis(), 1)) {
                slotTries.merge(d.slot(), -1, Integer::sum); // not clicked: not counted
                return false;
            }
            PrinterActions.quickMove(menu, d.slot());
            lastMenuTick = tick;
            wait = actionDelay.get();
            return false;
        }

        private boolean boxHolds(ItemStack box, Map<String, Long> want) {
            ItemStack[] contents = new ItemStack[27];
            Utils.getItemsInContainerItem(box, contents);
            for (ItemStack s : contents) {
                if (s != null && !s.isEmpty() && want.containsKey(BuiltInRegistries.ITEM.getKey(s.getItem()).toString())) return true;
            }
            return false;
        }

        void sessionFinished(SmartPlaceSession.Result result) {
            unpackResult = result;
            echestResult = result;
        }
    }

    /** x,y,z|dimension, in the dimension the player is in (an entry without one counts as this one); null otherwise. */
    private static int[] parseContainer(String entry, String here) {
        try {
            String[] halves = entry.trim().split("\\|", 2);
            if (halves.length == 2 && !halves[1].trim().isEmpty() && !halves[1].trim().equals(here)) return null;
            String[] c = halves[0].split(",");
            if (c.length != 3) return null;
            return new int[] { Integer.parseInt(c[0].trim()), Integer.parseInt(c[1].trim()), Integer.parseInt(c[2].trim()) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Why a position must not be clicked as a container, or null. The list setting and the stash index are data: before one
     * is right-clicked its chunk must be loaded, it must be near, and it must be a block with an inventory (never a bed, a
     * respawn anchor or anything else a click does something to the player with).
     */
    private String containerRefusal(BlockPos pos) {
        if (!mc.level.isLoaded(pos)) return "its chunk is not loaded";
        if (mc.player.distanceToSqr(Vec3.atCenterOf(pos)) > 128.0 * 128.0) return "it is more than 128 blocks away";
        BlockState st = mc.level.getBlockState(pos);
        BlockEntity be = mc.level.getBlockEntity(pos);
        if (be == null) return "there is no container there";
        // An allow-list: chests (trapped too), barrels, shulker boxes, ender chests, hoppers, dispensers and droppers
        boolean ok = be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity
            || be instanceof EnderChestBlockEntity || be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity;
        if (!ok) return "it is not a chest, barrel, shulker box, ender chest, hopper or dispenser";
        return null;
    }

    private void toggleLookedAtContainer() {
        if (mc.player == null || mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = hit.getBlockPos();
        if (mc.level.getBlockEntity(pos) == null) {
            warning("That is not a container.");
            return;
        }
        String entry = pos.getX() + "," + pos.getY() + "," + pos.getZ() + "|" + SmartPlaceSession.dimension();
        List<String> list = new ArrayList<>(containers.get());
        if (list.remove(entry)) info("Removed the container at %d %d %d.", pos.getX(), pos.getY(), pos.getZ());
        else {
            list.add(entry);
            info("Added the container at %d %d %d.", pos.getX(), pos.getY(), pos.getZ());
        }
        containers.set(list);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ending

    private void finishRun(boolean finished) {
        playSound(!finished);
        if (!reported) {
            reported = true;
            report(finished);
        }
        if (queue != null && queue.hasCurrent()) {
            if (finished) {
                info("%s done.", queue.label());
                // FINISH needs the scaffold gone, and startRun gives back everything else, so the next entry starts clean
                queue.complete();
                if (queue.seek(this::queueProblem, this::queueSkipped)) {
                    info("Next: %s", queue.label());
                    startRun();
                    return;
                }
                info("Queue done: %d schematic(s) built%s.", queue.completed(), queue.skipped() > 0 ? ", " + queue.skipped() + " skipped" : "");
            } else {
                info("Queue stopped at %s (%d done, %d skipped).", queue.label(), queue.completed(), queue.skipped());
            }
        }
        stopRun();
        if (isActive()) toggle();
    }

    /** Whether a queue entry can be built: null when it can, else why not. Client thread. */
    private String queueProblem(BuildQueue.Entry e) {
        Path root;
        try {
            root = FabricLoader.getInstance().getGameDir().resolve("schematics");
        } catch (RuntimeException ex) {
            return "the game folder could not be found";
        }
        if (e.kind() == BuildQueue.Kind.PLACEMENT && LitematicaBridge.available()) return LitematicaBridge.byNameProblem(e.name());
        SchematicFile.Resolved r = SchematicFile.resolve(root, e.name());
        if (r.ok()) return null;
        return e.kind() == BuildQueue.Kind.PLACEMENT ? "Litematica is not available and no file has that name (" + r.error() + ")" : r.error();
    }

    private void queueSkipped(BuildQueue.Entry e, String why) {
        warning("Queue: skipped %s: %s", LitematicReader.clean(e == null ? "?" : e.raw(), 60), why);
        QwerteAddon.LOG.warn("printer: queue entry skipped: {} ({})", e == null ? "?" : e.raw(), why);
    }

    private void report(boolean finished) {
        info("%s", (finished ? "Printer done: " : "Printer stopped: ") + builtCount + " placed, " + presentCount + " already there, " + failedLines.size() + " failed, " + wrongLines.size()
            + " wrong block(s) skipped, " + skippedReasons.values().stream().mapToInt(Integer::intValue).sum() + " not buildable, "
            + targets.size() + " left.");
        sendList("Not buildable", skippedList());
        sendList("Failed", failedLines);
        sendList("Wrong blocks skipped", wrongLines);
        if (brokenCount > 0) info("Broke %d wrong block(s); their drops were not collected.", brokenCount);
        for (Tool t : Tool.values()) {
            int done = toolDone.getOrDefault(t, 0);
            if (done > 0) info("%s %d block(s) with the %s.", t.doneLabel(), done, t.label());
            List<String> left = new ArrayList<>();
            for (ToolJob j : toolJobs.values()) {
                if (j.tool() == t) left.add(j.pos().getX() + " " + j.pos().getY() + " " + j.pos().getZ());
            }
            if (left.isEmpty()) continue;
            info("%d block(s) were left without the %s (not carried, out of reach or not loaded).", left.size(), t.label());
            sendList(t.leftTitle(), left);
        }
        if (waterDone > 0) info("Waterlogged %d block(s).", waterDone);
        if (dryCount > 0) {
            info("%d block(s) wanted waterlogged were left dry.", dryCount);
            sendList("Left dry", dryLines);
        }
        if (!waterJobs.isEmpty()) {
            List<String> wet = new ArrayList<>();
            for (WaterJob j : waterJobs.values()) wet.add(j.pos().getX() + " " + j.pos().getY() + " " + j.pos().getZ());
            sendList("Waiting for water", wet);
        }
        if (dormantN > 0) info("%d block(s) outside Litematica's render layers were left out.", dormantN);
        if (scaffoldPlacedTotal > 0) info("Scaffold: %d block(s) placed, %d removed.", scaffoldPlacedTotal, scaffoldRemovedTotal);
        List<String> scaffoldStanding = new ArrayList<>();
        for (Scaf sc : scaffolds.values()) if (sc.placed) scaffoldStanding.add(sc.pos.getX() + " " + sc.pos.getY() + " " + sc.pos.getZ());
        if (!scaffoldStanding.isEmpty()) sendList("Scaffold blocks still standing", scaffoldStanding);
        if (targets.size() > 0 && built != null) {
            List<String> left = new ArrayList<>();
            for (BuildOrderer.Cell c : sweep) {
                if (left.size() >= MAX_LIST + 1) break;
                if (targets.contains(c.x(), c.y(), c.z())) left.add(c.x() + " " + c.y() + " " + c.z());
            }
            sendList("Left unbuilt (first)", left);
        }
    }

    private void sendList(String title, List<String> entries) {
        if (entries.isEmpty()) return;
        int shown = Math.min(entries.size(), MAX_LIST);
        StringBuilder line = new StringBuilder(title).append(" (").append(entries.size()).append("): ");
        for (int i = 0; i < shown; i++) line.append(i > 0 ? "; " : "").append(LitematicReader.clean(entries.get(i), 100));
        if (entries.size() > shown) line.append("; and ").append(entries.size() - shown).append(" more");
        info("%s", line.length() > 700 ? line.substring(0, 700) : line.toString());
    }

    private void tell(PrintLoop.Level level, String message) {
        switch (level) {
            case INFO -> info("%s", message);
            case WARN -> warning("%s", message);
            case ERROR -> error("%s", message);
        }
    }

    private void say(SmartPlaceFlow.Level level, String message) {
        switch (level) {
            case INFO -> info("%s", message);
            case WARN -> warning("%s", message);
            case ERROR -> error("%s", message);
        }
        QwerteAddon.LOG.info("printer: {}", message);
    }

    private int pingTicks() {
        var connection = mc.getConnection();
        var info = connection == null ? null : connection.getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : (info.getLatency() + 49) / 50;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Orphans: id|x|y|z|dimension|server|name, like Container Tweaks' own, but this module's list

    private void addOrphan(Item what, BlockPos at, String dim) {
        if (what == null || at == null) return;
        String id = BuiltInRegistries.ITEM.getKey(what).toString();
        String name = new ItemStack(what).getHoverName().getString();
        List<String> list = new ArrayList<>(orphans.get());
        list.add(Orphans.format(id, at.getX(), at.getY(), at.getZ(), dim, server(), name));
        orphans.set(list);
        QwerteAddon.LOG.warn("printer: left {} at {}, {}, {} in {}", id, at.getX(), at.getY(), at.getZ(), dim);
    }

    private String server() {
        if (mc.level == null) return "?";
        ServerData data = mc.getCurrentServer();
        return data != null && data.ip != null ? data.ip.replace('|', '/') : "singleplayer";
    }

    private void orphanCheck() {
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
                    warning("Left behind: %s at %s, %s, %s in %s.", LitematicReader.clean(part.name(), 60), part.x(), part.y(), part.z(), part.dimension());
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
            if (!Orphans.here(part, server) || !part.dimension().equals(dimensionHere) || !orphanGone(part)) keep.add(entry);
            else info("%s at %s, %s, %s is gone (picked up or despawned).", LitematicReader.clean(part.name(), 60), part.x(), part.y(), part.z());
        }
        if (keep.size() != orphans.get().size()) orphans.set(keep);
    }

    private boolean orphanGone(Orphans.Entry part) {
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

    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public String getInfoString() {
        if (loop == null) return null;
        return (queue != null && queue.hasCurrent() ? queue.label() + ", " : "") + targets.size() + " left" + (toolJobs.isEmpty() ? "" : ", " + toolJobs.size() + " to finish with a tool");
    }

    /** What the shulker box session needs from this module. */
    private final class SessionHost implements SmartPlaceSession.Host {
        @Override public String tag() { return "printer"; }
        @Override public boolean rotate() { return rotationMode.get() != RotationMode.None; }
        @Override public boolean swing() { return swing.get(); }
        @Override public int actionDelay() { return actionDelay.get(); }
        @Override public int pickupTimeout() { return pickupTimeout.get(); }
        @Override public boolean walk() { return walk.get() && canWalkNow(); }
        @Override public boolean safeWalk() { return safeWalk.get(); }
        @Override public SmartPlaceFlow.NoSilk noSilk() { return echestNoSilk.get().mode; }
        @Override public boolean cancelled(Kind kind) {
            return !isActive() || loop == null || mc.level != runLevel || cancelKey.get().isSet() && cancelKey.get().isPressed();
        }
        @Override public void say(SmartPlaceFlow.Level level, String message) { Printer.this.say(level, message); }
        @Override public void addOrphan(Item item, BlockPos at, String dimension) { Printer.this.addOrphan(item, at, dimension); }
        @Override public boolean excluded(int x, int y, int z) {
            return targets.contains(x, y, z) || targets.contains(x, y + 1, z) || partBits.contains(x, y, z) || partBits.contains(x, y + 1, z);
        }
        @Override public boolean hostOwnsConfinement() { return usedConfine; }
        @Override public boolean whileOwnedOpen(AbstractContainerMenu menu) { return !resupply.running() || resupply.ownedOpen(menu); }
        @Override public void onFinished(SmartPlaceSession.Result result) { resupply.sessionFinished(result); }
    }
}

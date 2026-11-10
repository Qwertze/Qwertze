package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.compat.RightClick;
import com.porter.qwerte.stash.Categories;
import com.porter.qwerte.stash.ItemPreviewScreen;
import com.porter.qwerte.stash.Kit;
import com.porter.qwerte.stash.KitPreviewScreen;
import com.porter.qwerte.stash.KitClassifier;
import com.porter.qwerte.stash.KitNames;
import com.porter.qwerte.stash.KitTemplate;
import com.porter.qwerte.stash.KitRouting;
import com.porter.qwerte.stash.KitScore;
import com.porter.qwerte.stash.KitSettings;
import com.porter.qwerte.stash.KitStore;
import com.porter.qwerte.stash.Kits;
import com.porter.qwerte.stash.StackInfo;
import com.porter.qwerte.stash.StackInfo.BulkKey;
import com.porter.qwerte.stash.DeliveryBook;
import com.porter.qwerte.stash.DeliveryRoom;
import com.porter.qwerte.stash.StashIndex;
import com.porter.qwerte.stash.StashWatch;
import com.porter.qwerte.stash.Containers;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.stash.StashSafety;
import com.porter.qwerte.stash.Stash.Box;
import com.porter.qwerte.utils.BaritoneHelper;
import com.porter.qwerte.craft.AnvilFinder;
import com.porter.qwerte.craft.TableFinder;
import com.porter.qwerte.utils.ChestStack;
import com.porter.qwerte.utils.PlaceSearch;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sorts a stash of chests and barrels, either into tiers (shulkers first, then stackables, then singles) or into
 * whichever container's signs best describe each item. The stash is scanned once and saved, then kept up to date
 * whenever a container in it is opened, even with the module off. It can stay on as a sorting bot, and the saved
 * stash can be searched from the module's settings or by whitelisted players over whispers.
 */
public class StashSorter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBot = settings.createGroup("Bot");
    private final SettingGroup sgGround = settings.createGroup("Ground");
    private final SettingGroup sgWatch = settings.createGroup("Watch");
    private final SettingGroup sgLabels = settings.createGroup("Labels");
    private final SettingGroup sgShulkers = settings.createGroup("Shulkers");
    private final SettingGroup sgKits = settings.createGroup("Kits");
    private final SettingGroup sgSlots = settings.createGroup("Slots");
    private final SettingGroup sgSearch = settings.createGroup("Search");
    private final SettingGroup sgRender = settings.createGroup("Render");

    public enum Mode {
        Tiers("Tiers"),
        Signs("Signs");

        private final String title;

        Mode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public enum LogLevel {
        Quiet("Quiet"),
        Normal("Normal"),
        Debug("Debug");

        private final String title;

        LogLevel(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public enum Pick {
        Off("Off"),
        Exclude("Exclude"),
        DropOff("Drop-off"),
        CompactSkip("Compact skip"),
        Delivery("Delivery"),
        Corner1("Area corner 1"),
        Corner2("Area corner 2");

        private final String title;

        Pick(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    // General

    private final Setting<String> stashName = sgGeneral.add(new StringSetting.Builder()
        .name("stash-name")
        .description("The name the stash is saved under. Each name on each server is its own stash, scanned once and then kept up to date.")
        .defaultValue("stash")
        .onChanged(name -> stash = null)
        .build()
    );

    private final Setting<Boolean> sortItems = sgGeneral.add(new BoolSetting.Builder()
        .name("sort-items")
        .description("Off: scan only. The module still reads containers it doesn't know or that went stale, watches containers and signs, and keeps the saved stash current for stash-overlay and search, but never sorts: no taking, depositing, compacting, labelling, saved kits or picking up from the ground. Containers are only opened to read them. Orders from stash-manager still run, they are the only thing that moves items, and what an order leaves over is put back where it came from.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> safeWalk = sgGeneral.add(new BoolSetting.Builder()
        .name("safe-walk")
        .description("While the sorter walks, Baritone never parkours, never jumps off or falls more than one block, and the sorter sneaks at ledges with a drop of 2 or more blocks. A container with no such way to it is skipped.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Tiers: filled shulkers, empty shulkers, 64-stackables, 16-stackables, then singles, filling the nearest containers first. Signs: each item goes to the container whose signs describe it best.")
        .defaultValue(Mode.Tiers)
        .build()
    );

    private final Setting<Pick> pick = sgGeneral.add(new EnumSetting.Builder<Pick>()
        .name("pick")
        .description("Right-click containers to mark them. Exclude: never sorted into or out of. Drop-off: emptied into the stash, for others to drop items in. Compact skip: never used to gather stacks or shulkers for compacting. Delivery: where finished stash-manager orders are put (one per stash, never sorted into or out of). Right-click again to unmark. Area corner 1 and 2: the block you right-click becomes that corner of the stash perimeter. Works while the module is off.")
        .defaultValue(Pick.Off)
        .build()
    );

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("How far around you to look for new containers to add to the stash.")
        .defaultValue(8)
        .range(2, 32)
        .sliderRange(2, 24)
        .build()
    );

    private final Setting<Integer> height = sgGeneral.add(new IntSetting.Builder()
        .name("height")
        .description("How far above and below you to look for new containers.")
        .defaultValue(3)
        .range(0, 16)
        .sliderRange(0, 8)
        .build()
    );

    private final Setting<Boolean> barrels = sgGeneral.add(new BoolSetting.Builder()
        .name("barrels")
        .description("Includes barrels as well as chests.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("How close a container has to be to open it.")
        .defaultValue(4.5)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait between inventory clicks. Raise it if the server rejects clicks.")
        .defaultValue(3)
        .range(0, 20)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> containerDelay = sgGeneral.add(new IntSetting.Builder()
        .name("container-delay")
        .description("Extra ticks of pause around opening containers: a container is not opened until this many ticks after the last one was closed, and the first click in a freshly opened one waits this long too. 0 is as fast as possible. A higher value is slower but gentler on the server; 2b2t can kick or rubber-band when containers are opened very quickly.")
        .defaultValue(0)
        .range(0, 100)
        .sliderRange(0, 40)
        .build()
    );

    private final Setting<Integer> quiet = sgGeneral.add(new IntSetting.Builder()
        .name("quiet")
        .description("Seconds to wait after you or another module stops using the client (a screen, movement keys, Baritone, auto eat, auto-till, auto-dirt) before carrying on. Doubles for each repeated interruption within 5 minutes, up to 60 seconds.")
        .defaultValue(3)
        .range(0, 60)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> walk = sgGeneral.add(new BoolSetting.Builder()
        .name("walk")
        .description("Uses Baritone to walk to containers that are out of reach.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Faces containers when opening them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> unsignedOverflow = sgGeneral.add(new BoolSetting.Builder()
        .name("unsigned-overflow")
        .description("Signs mode: when nowhere an item belongs has room, and no container is signed misc, other or overflow, puts it in a container with no sign at all (one already holding items first). Never in a container signed for something else.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Signs)
        .build()
    );

    private final Setting<Boolean> kitSorting = sgGeneral.add(new BoolSetting.Builder()
        .name("kit-sorting")
        .description("Send shulker boxes that score as kits to the chest picked for them on the Stash Manager website or in the Stash Window's Kits tab: a chest per kit name (named kits only), or one kits chest for all of them (unnamed kits always go there). A box scores points for a name with the word kit, a mix of useful items, all 27 slots filled and OP items; it is a kit when the total reaches the threshold you set there (a part-filled box of one item never is). The chests picked hold nothing but kits. Off: kits are sorted like any shulker, and nothing about kits is reported. The settings come from the Stash Manager server (the last ones it sent are kept while it is offline); without any, nothing changes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<LogLevel> logLevel = sgGeneral.add(new EnumSetting.Builder<LogLevel>()
        .name("log-level")
        .description("Quiet: what it is doing now and next, errors and alerts (alerts are also whispered to the whisper-search whitelist). Normal: the usual messages. Debug: also why it decides what it does. Everything is always written to the log.")
        .defaultValue(LogLevel.Normal)
        .build()
    );

    // Bot

    private final Setting<Boolean> stayOn = sgBot.add(new BoolSetting.Builder()
        .name("stay-on")
        .description("Stays on after sorting and keeps the stash sorted: sorts again whenever a container changes, and empties drop-off containers.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> recheck = sgBot.add(new IntSetting.Builder()
        .name("recheck")
        .description("Seconds between checks of drop-off containers for new items. With smart-recheck it is the shortest wait.")
        .defaultValue(30)
        .range(5, 600)
        .sliderRange(5, 120)
        .visible(stayOn::get)
        .build()
    );

    private final Setting<Boolean> smartRecheck = sgBot.add(new BoolSetting.Builder()
        .name("smart-recheck")
        .description("Only drop-offs that can change without anyone opening them (a hopper, dropper or dispenser next to or above them, or out of loaded range) are checked, and the wait grows from the recheck time up to 5 minutes while nothing is found. Opened drop-offs are noticed from their lids. Off: every drop-off is checked every recheck seconds.")
        .defaultValue(true)
        .visible(stayOn::get)
        .build()
    );

    // Ground

    private final Setting<Boolean> collectGround = sgGround.add(new BoolSetting.Builder()
        .name("collect-ground")
        .description("Between jobs, walks onto items other players left lying in the stash area (the containers plus the range below, 4 blocks up and down) and sorts them with the rest. Needs Baritone. Never items you dropped or threw, or that came from blocks you broke, only items that have lain still for 3 seconds, and only while no other player is loaded. Not in scan-only mode.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> collectRange = sgGround.add(new IntSetting.Builder()
        .name("collect-range")
        .description("How many blocks around the outermost containers to pick items up from.")
        .defaultValue(8)
        .range(0, 64)
        .sliderRange(0, 32)
        .visible(collectGround::get)
        .build()
    );

    // Labels

    private final Setting<Boolean> autoLabel = sgLabels.add(new BoolSetting.Builder()
        .name("auto-label")
        .description("Signs mode: when items have no container signed for them, puts up a sign for them on an empty, unsigned chest next to the signed ones, using signs from your inventory or the stash. Never places or breaks a chest, and never breaks a sign.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> labelGap = sgLabels.add(new IntSetting.Builder()
        .name("label-gap")
        .description("Seconds to wait between putting up signs.")
        .defaultValue(10)
        .range(0, 300)
        .sliderRange(0, 60)
        .visible(autoLabel::get)
        .build()
    );

    // Watch

    private final Setting<Boolean> watch = sgWatch.add(new BoolSetting.Builder()
        .name("watch")
        .description("Notices when another player opens a container of the stash and closes it again, and reads it again after the delay below. Works while the module is off.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> watchDelay = sgWatch.add(new IntSetting.Builder()
        .name("watch-delay")
        .description("Seconds a container has to stay closed after someone else used it before it's read again.")
        .defaultValue(5)
        .range(0, 60)
        .sliderRange(0, 60)
        .visible(watch::get)
        .build()
    );

    private final Setting<Boolean> watchSigns = sgWatch.add(new BoolSetting.Builder()
        .name("watch-signs")
        .description("Signs mode, while sorting: notices when the text of a container's sign changes, and sorts by the new text after the delay below. A sign that's gone for a minute counts as removed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> signDelay = sgWatch.add(new IntSetting.Builder()
        .name("sign-delay")
        .description("Seconds a sign has to be left alone before the stash is sorted by what it says now. The container isn't used meanwhile.")
        .defaultValue(10)
        .range(0, 60)
        .sliderRange(0, 60)
        .visible(watchSigns::get)
        .build()
    );

    // Shulkers

    private final Setting<Boolean> compact = sgShulkers.add(new BoolSetting.Builder()
        .name("compact")
        .description("Signs mode, once everything is sorted: packs a full shulker's worth of one item (27 full stacks, or two of the same gear piece and up) from the category chests into an empty unnamed shulker and files it in the item's chest, so 27 slots become 1. Never mixed shulkers, never a named one, never kit chests. Shulkers are put down beside you for it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> compactTopUpAt = sgShulkers.add(new IntSetting.Builder()
        .name("compact-top-up-at")
        .description("Tops up a part-full shulker of one item once this many full stacks of it are waiting.")
        .defaultValue(9)
        .range(1, 26)
        .sliderRange(1, 26)
        .visible(compact::get)
        .build()
    );

    private final Setting<List<Item>> compactExclude = sgShulkers.add(new ItemListSetting.Builder()
        .name("compact-exclude")
        .description("Items that are never compacted, like ender pearls.")
        .defaultValue(List.of())
        .visible(compact::get)
        .build()
    );

    private final Setting<Boolean> repack = sgShulkers.add(new BoolSetting.Builder()
        .name("repack")
        .description("Signs mode: takes items that belong elsewhere out of mixed shulkers, and packs loose items into shulkers that already hold only that item. Places the shulker beside you to do it, then breaks it and picks it back up. Named shulkers, and shulkers when a sign asks for them whole (like \"kits\"), are left as they are.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> newBulkAt = sgShulkers.add(new IntSetting.Builder()
        .name("new-bulk-at")
        .description("Starts a new bulk shulker from an empty one once carrying this many stacks of one item. 0 only tops up shulkers that already exist. Only used while compact is off, which makes new bulk shulkers itself.")
        .defaultValue(9)
        .range(0, 27)
        .sliderRange(0, 27)
        .visible(repack::get)
        .build()
    );

    // Kits

    private final Setting<Boolean> buildKits = sgKits.add(new BoolSetting.Builder()
        .name("build-kits")
        .description("Signs mode: builds the kits saved below out of the stash, as many as it can. Takes an empty shulker, gathers the items, and fills it slot for slot with the saved counts, then files it in the chest signed with the kit's name (or \"<name> kit\"). A kit with no such chest isn't built. Uses every empty shulker in the stash.")
        .defaultValue(false)
        .build()
    );

    // Slots

    private final Setting<String> carrySlots = sgSlots.add(new StringSetting.Builder()
        .name("carry-slots")
        .description("Slots to carry stash items in, like \"28-36\" or \"1-3, 30\". 1-9 is the hotbar, 10-36 the inventory from top left. Blank uses every slot that's empty when sorting starts.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> keepSlots = sgSlots.add(new StringSetting.Builder()
        .name("keep-slots")
        .description("Slots never to touch, even when they're empty, in the same format as carry-slots.")
        .defaultValue("")
        .build()
    );

    // Search

    private final Setting<Boolean> whisperSearch = sgSearch.add(new BoolSetting.Builder()
        .name("whisper-search")
        .description("Lets whitelisted players whisper you to search the stash. You whisper back where it is. Works while the module is off.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> whitelist = sgSearch.add(new StringListSetting.Builder()
        .name("whitelist")
        .description("Players allowed to search the stash by whisper.")
        .visible(whisperSearch::get)
        .build()
    );

    private final Setting<String> prefix = sgSearch.add(new StringSetting.Builder()
        .name("prefix")
        .description("Whispers starting with this are searches, like \"find diamonds\". Blank treats every whisper from the whitelist as a search.")
        .defaultValue("find")
        .visible(whisperSearch::get)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlights the container being worked on, and excluded and drop-off containers while picking or sorting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> areaColor = sgRender.add(new ColorSetting.Builder()
        .name("area-color")
        .description("The outline of the stash perimeter, drawn while stash-sorter or stash-overlay is on.")
        .defaultValue(new SettingColor(80, 255, 120, 255))
        .build()
    );

    private final Setting<SettingColor> workColor = sgRender.add(new ColorSetting.Builder()
        .name("work-color")
        .description("The container being worked on.")
        .defaultValue(new SettingColor(255, 170, 0, 60))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> excludedColor = sgRender.add(new ColorSetting.Builder()
        .name("excluded-color")
        .description("Containers excluded from sorting.")
        .defaultValue(new SettingColor(255, 50, 50, 60))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> compactSkipColor = sgRender.add(new ColorSetting.Builder()
        .name("compact-skip-color")
        .description("Containers never used for compacting.")
        .defaultValue(new SettingColor(180, 100, 255, 60))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> dropOffColor = sgRender.add(new ColorSetting.Builder()
        .name("drop-off-color")
        .description("Drop-off containers.")
        .defaultValue(new SettingColor(50, 150, 255, 60))
        .visible(render::get)
        .build()
    );

    // Generous limits, 2b2t can be laggy
    private static final int OPEN_TIMEOUT = 20 * 4;
    private static final int WALK_TIMEOUT = 20 * 60;
    private static final int PATH_GRACE_TICKS = 10;
    // Whispers back are spaced out so the server doesn't kick us for spam
    private static final int REPLY_GAP_TICKS = 20 * 3;

    private static final Direction[] HORIZONTALS = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };

    // Baritone is optional, BaritoneHelper must only be touched when it's installed
    private static final boolean BARITONE = classExists("baritone.api.BaritoneAPI");

    private static final Pattern[] WHISPER_FORMATS = {
        Pattern.compile("^(\\w{3,16}) whispers(?: to you)?: (.+)$"),
        Pattern.compile("^From (\\w{3,16}): (.+)$"),
        Pattern.compile("^\\[(\\w{3,16}) -> (?:me|you)] (.+)$"),
        Pattern.compile("^(\\w{3,16}) -> (?:me|you): (.+)$")
    };

    private enum Job { Survey, Take, Deposit, Dump, Fetch, Clean, Fill, Compact, Deliver, Return, KitTake, KitUnpack, KitFill, FetchSigns, Label, Gather, Ground, Explore, Unbundle, Name, CraftTake, CraftUnpack, Craft, AnvilPlace, ChestPlace, Repair }

    private enum Step { Walking, Opening, Working }

    // Repacking a shulker in the world: put it down, open it, move items, break it, pick it back up
    private enum Handle { Place, Wander, Open, Work, Break, Collect, Return, Leave, Resume }

    // Giving way: running, letting go of what is ours, then waiting for the others to finish and be quiet
    private enum Gate { Running, Yielding, Waiting }

    private static final int MAX_JOB_INTERRUPTIONS = 5;
    private static final int CURSOR_TRIES = 40;

    private static final int HANDLE_TIMEOUT = 20 * 10;
    private static final int COLLECT_TIMEOUT = 20 * 120;
    private static final int PLACE_GAP_TICKS = 20 * 5;
    private static final int PAUSE_TICKS = 20 * 60 * 3;

    private Stash stash;
    private String triedKey;
    private final Set<Integer> carry = new LinkedHashSet<>();

    // Tiers mode: which boxes each tier fills
    private final Map<Integer, List<Box>> tierBoxes = new HashMap<>();

    private Job job;
    private Box target;
    private Step step;
    private int stepTicks;
    // How long the container job in hand has been running, and the most it may
    private int jobTicks;
    private static final int JOB_WATCHDOG_TICKS = 20 * 60 * 4;
    // A read has three short stages (walk at most WALK_TIMEOUT, open at most OPEN_TIMEOUT, read): this is the last resort
    // for one that still hangs, well under the general watchdog
    private static final int SURVEY_WATCHDOG_TICKS = 20 * 90;
    // A walk to a container to read that gets no closer than a block in this long (30 s) is given up on; not a setting
    private static final int SURVEY_WALK_STALL_TICKS = 20 * 30;
    private int wait;
    private int swapStep;
    private int swapCarry, swapSlot;
    private int recoveries;
    private ItemStack swapStack = ItemStack.EMPTY, swapDisplaced = ItemStack.EMPTY;
    private boolean usedBaritone;
    // How often a walk to a container ended with our goal gone from Baritone
    private final Map<Box, Integer> cancelRetries = new HashMap<>();

    // Giving way to the player and other modules
    private Gate gate = Gate.Running;
    private int ticks;
    private int lastOwnAct = -100;
    // The tick the last container of ours was closed, for container-delay
    private int lastCloseTick = -100_000;
    private int cleanupTries;
    private int jobInterruptions;
    private Handle returnTo;
    // The container window the sorter opened, and the Baritone goal it set (an Object so Baritone's classes are only
    // touched when it's installed), with when it was set and let go of
    private AbstractContainerMenu ownedMenu;
    private Object ourGoal;
    private int goalTick = -100, releaseTick = -100;
    // Set while the sorter presses keys itself
    private boolean foreignNow;

    // Putting up signs for items nothing is signed for
    private final StashLabeller labeller = new StashLabeller(new StashLabeller.Owner() {
        @Override
        public Stash stash() {
            return stash;
        }

        @Override
        public Set<Integer> carry() {
            return carry;
        }

        @Override
        public Set<Integer> keep() {
            return parseSlots(keepSlots.get());
        }

        @Override
        public List<Integer> activeCarried() {
            return StashSorter.this.activeCarried();
        }

        @Override
        public List<Integer> carriedSlots() {
            return StashSorter.this.carriedSlots();
        }

        @Override
        public List<Box> destinationsFor(ItemStack stack) {
            return StashSorter.this.destinationsFor(stack);
        }

        @Override
        public boolean isMisc(Box box) {
            return StashSorter.this.isMisc(box);
        }

        @Override
        public boolean mixedShulker(ItemStack stack) {
            return StashSorter.mixedShulker(stack);
        }

        @Override
        public boolean keepWholeExists() {
            return !keepWholeBoxes().isEmpty();
        }

        @Override
        public boolean kitReserved(Box box) {
            return StashSorter.this.kitReserved(box);
        }

        @Override
        public boolean kitRouted(ItemStack stack) {
            return StashSorter.this.kitRouted(stack);
        }

        @Override
        public int misplacedCount(Box box) {
            return StashSorter.this.misplacedCount(box);
        }

        @Override
        public boolean signPending(Box box) {
            return StashSorter.this.signPending(box);
        }

        @Override
        public boolean rereadPending(Box box) {
            return staleBoxes.contains(box) || deferredAccess.containsKey(box);
        }

        @Override
        public boolean fetchBlocked(Box box) {
            return blocked(box, Job.FetchSigns);
        }

        @Override
        public Box nearest(List<Box> boxes) {
            return StashSorter.this.nearest(boxes);
        }

        @Override
        public int freeCarrySlot() {
            return StashSorter.this.freeCarrySlot();
        }

        @Override
        public boolean autoLabel() {
            return autoLabel.get();
        }

        @Override
        public boolean signsMode() {
            return mode.get() == Mode.Signs;
        }

        @Override
        public int gapTicks() {
            return labelGap.get() * 20;
        }

        @Override
        public boolean walk() {
            return walkOn();
        }

        @Override
        public boolean rotate() {
            return rotate.get();
        }

        @Override
        public int delay() {
            return delay.get();
        }

        @Override
        public int clock() {
            return clock;
        }

        @Override
        public int version() {
            return version;
        }

        @Override
        public boolean running() {
            return gate == Gate.Running;
        }

        @Override
        public boolean foreign() {
            return foreignNow;
        }

        @Override
        public void beginLabel(Box target) {
            job = Job.Label;
            StashSorter.this.target = target;
            step = Step.Walking;
            handle = null;
            actions = 0;
            jobCounted = false;
            stepTicks = 0;
            jobInterruptions = 0;
            startSigSet = endSigSet = false;
            settled = false;
            openPos = null;
            openMenuBefore = null;
        }

        @Override
        public void endLabel() {
            job = null;
            StashSorter.this.target = null;
            wait = delay.get();
        }

        @Override
        public boolean fetchSigns(Box from) {
            return start(Job.FetchSigns, from);
        }

        @Override
        public void walkTo(BlockPos pos) {
            if (!BARITONE) return;
            walkNear(pos, 0);
        }

        @Override
        public void releaseGoal() {
            StashSorter.this.releaseGoal();
        }

        @Override
        public boolean moving() {
            return BARITONE && BaritoneHelper.isMoving();
        }

        @Override
        public boolean leftArea() {
            return StashSorter.this.leftArea();
        }

        @Override
        public String blockName(Box box) {
            return StashSorter.this.blockName(box);
        }

        @Override
        public void pause(int ticks) {
            wait = ticks;
        }

        @Override
        public void closeOurWindow() {
            if (mc.player.containerMenu != mc.player.inventoryMenu) StashSorter.this.closeOurWindow(mc.player.containerMenu);
        }

        @Override
        public boolean containerOpen() {
            return mc.player.containerMenu != mc.player.inventoryMenu && ourMenu(mc.player.containerMenu);
        }

        @Override
        public void held(int slot, ItemStack stack) {
            stashHeld.put(slot, stack.copy());
        }

        @Override
        public void labelled(Box box) {
            stash.markDirty();
            stash.save();
            version++;
            seenVersion = version;
            clearStalled();
            stuckCarried.clear();
            stuckWarned = false;
            stashWatch.settleNow(box);
            idle = false;
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }

        @Override
        public void warn(String text) {
            warning("%s", text);
        }

        @Override
        public void alert(String text) {
            StashSorter.this.alert("%s", text);
        }

        @Override
        public void step(String text) {
            StashSorter.this.step(text);
        }

        @Override
        public void debug(String text) {
            StashSorter.this.debug("%s", text);
        }
    });

    private int openClickTick = -100;
    private static final int OPEN_ANSWER_TICKS = 20 * 5;
    private final StashYield yielder = new StashYield(new StashYield.Owner() {
        @Override
        public boolean ourMenu(AbstractContainerMenu menu) {
            return StashSorter.this.ourMenu(menu);
        }

        @Override
        public boolean baritoneForeign() {
            if (!BARITONE || ticks - goalTick <= 3 || ticks - releaseTick <= 3) return false;
            return BaritoneHelper.controlledByOther((baritone.api.pathing.goals.Goal) ourGoal);
        }

        @Override
        public boolean baritoneActive() {
            return BARITONE && BaritoneHelper.isActive();
        }

        @Override
        public boolean drivingInput() {
            return labeller.drivingInput() || placer.drivingInput() || ledgeSneaking;
        }

        @Override
        public boolean justReleased() {
            return ticks - releaseTick <= 2;
        }

        @Override
        public boolean walkingOurs() {
            return ourGoal != null;
        }

        @Override
        public boolean ownInventoryActivity() {
            return handle != null || ticks - lastOwnAct < 20;
        }

        @Override
        public Set<Integer> carry() {
            return carry;
        }

        @Override
        public int quietSeconds() {
            return quiet.get();
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }

        @Override
        public void step(String text) {
            StashSorter.this.step(text);
        }
    });
    private int moved;
    // Containers read by surveys since the last summary, for scan-only mode
    private int scanned;

    // The stash perimeter: chunk columns seen loaded since it was set, the ones that couldn't be reached, and the trip in
    // hand. Walking inside it is allowed, and the sorter explores it so its chunks load
    private final LongOpenHashSet exploredChunks = new LongOpenHashSet();
    private final LongOpenHashSet exploreSkipped = new LongOpenHashSet();
    private boolean exploring, exploreRan, areaDirty;
    private int exploreRunStart;
    private static final int EXPLORE_RUN_LIMIT = 20 * 60 * 30;
    private int exploreStanding;
    private long exploreTarget;
    private int exploreX, exploreZ;
    private int exploreTicks, exploreIssue, exploreBestTick;
    private double exploreBest;
    private boolean walkInside, baritoneWarned;
    // Containers already reported as impossible to reach without breaking or placing blocks
    private final Set<BlockPos> unreachableReported = new HashSet<>();
    private static final int EXPLORE_STANDING = 40;
    private static final int EXPLORE_TIMEOUT = 20 * 40;
    private static final int EXPLORE_STUCK = 20 * 15;
    private static final int AREA_MARGIN = 2;

    // Picking up what other players left on the ground
    private final GroundCollector ground = new GroundCollector(new GroundCollector.Owner() {
        @Override
        public Stash stash() {
            return stash;
        }

        @Override
        public boolean enabled() {
            return groundOn();
        }

        @Override
        public int range() {
            return collectRange.get();
        }

        @Override
        public int freeCarrySlot() {
            return StashSorter.this.freeCarrySlot();
        }

        @Override
        public boolean carries(int slot) {
            return carry.contains(slot);
        }

        @Override
        public int moveToCarry(int slot) {
            int free = StashSorter.this.freeCarrySlot();
            if (free == -1 || mc.screen != null || !mc.player.containerMenu.getCarried().isEmpty()) return -1;
            InvUtils.move().from(slot).to(free);
            lastOwnAct = ticks;
            yielder.inventoryChangedByUs();
            return free;
        }

        @Override
        public void held(int slot, ItemStack stack) {
            stashHeld.put(slot, stack.copy());
        }

        @Override
        public void walkTo(BlockPos pos) {
            if (!BARITONE) return;
            walkNear(pos, 0);
        }

        @Override
        public void releaseGoal() {
            StashSorter.this.releaseGoal();
        }

        @Override
        public void confine() {
            // Every walk of the sorter's confines Baritone itself, see walkNear
        }

        @Override
        public void unconfine() {
            if (BARITONE && ourGoal == null) BaritoneHelper.unconfine();
        }

        @Override
        public void finished(boolean pickedUp) {
            if (job == Job.Ground) job = null;
            wait = delay.get();
            lastOwnAct = ticks;
            if (pickedUp) {
                // What came in is put away like anything else carried
                idle = false;
                clearStalled();
                stuckCarried.clear();
                stuckWarned = false;

                // In batches: while there is room for two more stacks and more is lying there, the next one is fetched
                // before anything is put away
                if (freeCarryCount() >= 2 && groundBatch < GROUND_BATCH_MAX) {
                    groundBatch++;
                    if (startGround()) return;
                }
            }
            groundBatch = 0;
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }

        @Override
        public void debug(String text) {
            StashSorter.this.debug("%s", text);
        }

        @Override
        public void step(String text) {
            StashSorter.this.step(text);
        }
    });

    // Containers where the last job got nothing done, skipped until something moves somewhere else
    private final Set<Box> stalled = new HashSet<>();
    private final Set<Box> compacted = new HashSet<>();
    private int actions;

    // Loop protection. What the carry slots and the target container held when the job's window opened and just before
    // it closed, to tell whether the clicks changed anything
    private long startSig, endSig;
    private boolean startSigSet, endSigSet;
    // Consecutive jobs per container that clicked but ended before the result could be read, and containers that closed
    // on us this pass (said once)
    private final Map<Box, Integer> unconfirmedRuns = new HashMap<>();
    private final Set<Box> closedEarlyReported = new HashSet<>();
    // Jobs at each container since the backlog last reached a new low, the ones given up on for going back and forth, and
    // the ones already reported this pass
    private final Map<Box, Integer> boxVisits = new HashMap<>();
    private final Set<Box> circular = new HashSet<>();
    private final Set<Box> loopReported = new HashSet<>();
    private boolean jobCounted;

    // Watching containers and signs: other players' lids and sign text, read from the client's own world
    private final StashWatch stashWatch = new StashWatch();
    private static final int SIGN_POLL_TICKS = 10;
    private static final int LID_POLL_TICKS = 4;
    private static final int SIGN_SWEEP_TICKS = 20 * 30;
    private static final int OWN_TOUCH_TICKS = 20 * 10;
    private static final int REREAD_COOLDOWN_TICKS = 20 * 60;
    // When the sorter or the player last had each container open, and when the sorter last read it
    private final Map<Box, Integer> ownTouch = new HashMap<>();
    private final Map<Box, Integer> readAt = new HashMap<>();
    // Containers someone else used, to be read again between jobs, and the ones held back by the cooldown
    private final Set<Box> staleBoxes = new LinkedHashSet<>();
    private final Map<Box, Integer> deferredAccess = new HashMap<>();
    // Jobs ended because the container's sign is unsettled, which is no fault of the container
    private boolean signAbort;
    private final Set<Box> signAborted = new HashSet<>();
    private final Set<Box> signHeld = new HashSet<>();
    private final Map<Box, Integer> signAbortRuns = new HashMap<>();
    private final Set<Box> signAbortReported = new HashSet<>();
    private int nextSignSweep;
    // Jobs that opened a container and clicked nothing, with what the carry slots and the container held then: not
    // repeated until one of them changes, however often stalled is cleared meanwhile
    private final Map<String, Long> futile = new HashMap<>();
    private final Set<String> noOpReported = new HashSet<>();
    private String lastSummary;
    private int passJobs;
    private int passMin = Integer.MAX_VALUE;
    // Containers that could not be read when visited: not tried again until something changes
    private final Set<Box> failedSurvey = new HashSet<>();
    // When each of them is tried again: 1 min, 2 min, then every 5 min
    private final com.porter.qwerte.utils.SurveyRetry<Box> surveyRetry = new com.porter.qwerte.utils.SurveyRetry<>();
    private final com.porter.qwerte.utils.WalkProgress walkProgress = new com.porter.qwerte.utils.WalkProgress();

    // Carried stacks nothing has room for right now: slot to item id, left out of the plans until something changes
    private final Map<Integer, String> stuckCarried = new HashMap<>();
    private boolean stuckWarned;

    // Carry slots the sorter filled itself, slot to the stack it put there. The player's own gear in a carry slot is
    // never touched unless it's in here
    private final Map<Integer, ItemStack> stashHeld = new HashMap<>();
    private net.minecraft.client.player.LocalPlayer lastPlayer;
    // When stuck gear was last warned about, and when the stuck stacks were last looked at again
    private static final int STUCK_REWARN = 20 * 60 * 3;
    private static final int STUCK_RECHECK = 20 * 5;
    private int lastStuckWarn = -STUCK_REWARN, lastStuckCheck, stuckCheckVersion;
    // Ticks to let the server answer the last click before the result is judged
    private static final int SETTLE_TICKS = 2;
    private boolean settled;

    // Repacking
    private int wantSlot = -1;
    private int handledSlot;
    // Where the shulker handled came from, before it was moved to the hotbar to be placed
    private int handledOrigin;
    private BulkKey fillKey;
    private Item mainItem;

    // Offline safety net: a shulker of ours standing in the world is written to the stash file with what it is for, so a
    // restart (disconnect, crash, stopping the module) finishes the step instead of losing it
    private static final int RESUME_MAX_TICKS = 20 * 60 * 10;
    private Stash.InFlight pendingResume;
    private boolean resuming, resumeVerify, resumeFinishOnly, resumeDimensionSaid;
    private int resumeStarted;
    private String placedItemId = "";
    private BlockPos lastGoodStand;
    private ItemStack fillSample = ItemStack.EMPTY;
    private Handle syncedHandle;
    private BlockPos syncedPlaced;
    private boolean syncedConfirmed;
    // Leaving the world: nothing is whispered
    private boolean offline;
    // Ticks in the world since joining: alert whispers wait until the server has settled
    private int worldTicks;
    private static final int ALERT_JOIN_TICKS = 20 * 15;
    // The dimension we were last in, for a record written while the world is being left
    private String cachedDimension = "";

    // Safe shulker handling: spots where a place failed, how many in a row, when the last was, a pause after problems,
    // and a shulker that could not be picked up (no more are put down)
    private final Set<BlockPos> failedSpots = new HashSet<>();
    private int placeFailures;
    private int lastPlaceTick = -1000;
    private int repackPausedUntil;
    private boolean dropLost;
    private boolean placedConfirmed;
    private int placedStacks;
    private int breakTicks;
    private Predicate<ItemStack> fetchWants = s -> Utils.isShulker(s.getItem());
    private Handle handle;
    private int handleTicks;
    private BlockPos placed;
    // A hotbar slot a shulker was moved into, and the player's item that was in it, now in swapBackFrom
    private int swapBackFrom = -1, swapBackTo = -1;
    private ItemStack hotbarDisplaced = ItemStack.EMPTY;
    private boolean pickaxeSwapped;
    private int shulkersBefore;
    private final Set<Integer> shulkerSlotsBefore = new HashSet<>();
    // Shulkers held the last tick the shulker in the world was still standing, -1 if it never was seen standing
    private int shulkersAtBreak = -1;
    private final Set<BulkKey> packTried = new HashSet<>();

    // Packing a full shulker's worth of one item
    private final Compactor compactor = new Compactor(new Compactor.Owner() {
        @Override
        public Stash stash() {
            return stash;
        }

        @Override
        public boolean on() {
            return compactOn();
        }

        @Override
        public int topUpAt() {
            return compactTopUpAt.get();
        }

        @Override
        public boolean excluded(ItemStack stack) {
            return compactExclude.get().contains(stack.getItem());
        }

        @Override
        public List<Box> destinationsFor(ItemStack stack) {
            return StashSorter.this.destinationsFor(stack);
        }

        @Override
        public boolean isMisc(Box box) {
            return StashSorter.this.isMisc(box);
        }

        @Override
        public boolean isKitBox(Box box) {
            return StashSorter.this.isKitBox(box);
        }

        @Override
        public boolean isKitShulker(ItemStack stack) {
            return StashSorter.this.isKitShulker(stack);
        }

        @Override
        public boolean signPending(Box box) {
            return StashSorter.this.signPending(box);
        }

        @Override
        public boolean gatherBlocked(Box box) {
            return blocked(box, Job.Gather);
        }

        @Override
        public boolean fetchBlocked(Box box) {
            return blocked(box, Job.Fetch);
        }

        @Override
        public Box nearest(List<Box> boxes) {
            return StashSorter.this.nearest(boxes);
        }

        @Override
        public boolean closer(Box a, Box b) {
            return StashSorter.this.closer(a, b);
        }

        @Override
        public List<Integer> carriedSlots() {
            return StashSorter.this.carriedSlots();
        }

        @Override
        public List<Integer> activeCarried() {
            return StashSorter.this.activeCarried();
        }

        @Override
        public Set<Integer> carry() {
            return carry;
        }

        @Override
        public int freeCarrySlot() {
            return StashSorter.this.freeCarrySlot();
        }

        @Override
        public int freeCarryCount() {
            return StashSorter.this.freeCarryCount();
        }

        @Override
        public boolean kitHolds(int slot) {
            return kitBuilder.holds(slot);
        }

        @Override
        public boolean fetch(Box box, int slot, Predicate<ItemStack> wants) {
            wantSlot = slot;
            fetchWants = wants;
            return start(Job.Fetch, box);
        }

        @Override
        public boolean gather(Box box) {
            return start(Job.Gather, box);
        }

        @Override
        public boolean pack(Compactor.Plan plan) {
            if (plan.placedPos != null) {
                startHandling(Job.Fill, -1, plan.key);
                placed = plan.placedPos;
                placedConfirmed = true;
                shulkersBefore = countShulkers() + 1;
                handle = Handle.Return;
                returnTo = Handle.Open;
                return true;
            }
            int slot = compactor.findPlanShulker();
            if (slot < 0) {
                compactor.abort("the shulker is missing");
                return compactor.next();
            }
            return startHandling(Job.Fill, slot, plan.key);
        }

        @Override
        public boolean recover(Compactor.Plan plan, BlockPos at) {
            startHandling(Job.Fill, -1, plan.key);
            placed = at;
            placedConfirmed = true;
            handle = Handle.Collect;
            shulkersBefore = countShulkers() + 1;
            shulkerSlotsBefore.clear();
            for (int i = 0; i < 36; i++) if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) shulkerSlotsBefore.add(i);
            return true;
        }

        @Override
        public void held(int slot, ItemStack stack) {
            stashHeld.put(slot, stack.copy());
        }

        @Override
        public void moved() {
            moved++;
        }

        @Override
        public void headway() {
            // Real headway: the circle watchdog starts over
            passMin = Integer.MAX_VALUE;
            passJobs = 0;
            boxVisits.clear();
            stalled.removeAll(circular);
            circular.clear();
            clearStalled();
        }

        @Override
        public void clearOrphan(BlockPos at) {
            StashSorter.this.clearOrphan(at);
        }

        @Override
        public void progress(BlockPos at, int stacks) {
            Stash.Orphan old = stash.orphans.stream().filter(o -> o.pos.equals(at)).findFirst().orElse(null);
            recordOrphan(at, old != null ? old.item : placedName(), stacks);
        }

        @Override
        public int version() {
            return version;
        }

        @Override
        public boolean dropOffsBusy() {
            return stash.boxes.stream().anyMatch(b -> b.dropOff && b.known() && !b.unreachable && b.free() < b.contents.length);
        }

        @Override
        public int ticks() {
            return ticks;
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }

        @Override
        public void warn(String text) {
            warning("%s", text);
        }

        @Override
        public void alert(String text) {
            StashSorter.this.alert("%s", text);
        }

        @Override
        public void debug(String text) {
            StashSorter.this.debug("%s", text);
        }

        @Override
        public void step(String text) {
            StashSorter.this.step(text);
        }

        @Override
        public void changed() {
            syncInFlight();
        }

        @Override
        public String craftEmpty(String purpose) {
            return craftEmptyForCompaction(purpose);
        }
    });

    // Building kits
    private final KitBuilder kitBuilder = new KitBuilder(new KitBuilder.Owner() {
        @Override
        public boolean dashboardKit(String name) {
            return StashSorter.this.dashboardKit(name);
        }

        @Override
        public Stash stash() {
            return stash;
        }

        @Override
        public Set<Integer> carry() {
            return ownedCarry();
        }

        @Override
        public int freeCarrySlot() {
            return StashSorter.this.freeCarrySlot();
        }

        @Override
        public int freeCarryCount() {
            return StashSorter.this.freeCarryCount();
        }

        @Override
        public boolean usable(Box box) {
            return !blocked(box, Job.Take) && !signPending(box) && !box.unreachable;
        }

        @Override
        public void held(int slot, ItemStack stack) {
            stashHeld.put(slot, stack.copy());
            noteOrderHeld(slot, stack);
        }

        @Override
        public boolean keepWhole(Box box) {
            return box.labels.stream().anyMatch(Categories::isKeepWhole);
        }

        @Override
        public double distance(Box box) {
            return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(nearestPart(box)));
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }

        @Override
        public void warn(String text) {
            warning("%s", text);
        }

        @Override
        public boolean claims(ItemStack stack) {
            return orderRunner.claims(stack);
        }

        @Override
        public boolean namedShulkers() {
            return namedForOrders();
        }
    });

    // Orders from the Stash Manager server: kits built from exact items, delivered to the delivery container
    private final OrderRunner orderRunner = new OrderRunner(kitBuilder, new OrderRunner.Owner() {
        @Override
        public Stash stash() {
            return stash;
        }

        @Override
        public boolean gate() {
            return ordersOn();
        }

        @Override
        public boolean usable(Box box) {
            return !blocked(box, Job.Take) && !signPending(box) && !box.unreachable;
        }

        @Override
        public boolean keepWhole(Box box) {
            return box.labels.stream().anyMatch(Categories::isKeepWhole);
        }

        @Override
        public boolean namedShulkers() {
            return namedForOrders();
        }

        @Override
        public boolean rereading() {
            return !staleBoxes().isEmpty();
        }

        @Override
        public int staleCount() {
            return staleBoxes().size();
        }

        @Override
        public double distance(Box box) {
            return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(nearestPart(box)));
        }

        @Override
        public Set<Integer> carry() {
            return ownedCarry();
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }

        @Override
        public String anvilProblem() {
            return StashSorter.this.anvilProblem();
        }

        @Override
        public BlockPos anvilPos() {
            return stash == null ? null : stash.anvilPos;
        }

        @Override
        public Crafter crafter() {
            return crafter;
        }

        @Override
        public boolean craftingOn() {
            return StashSorter.this.craftingOn();
        }

        @Override
        public String craftBlocker() {
            return StashSorter.this.craftBlocker();
        }

        @Override
        public boolean anvilMissing() {
            return StashSorter.this.anvilMissing();
        }

        @Override
        public DeliveryRoom deliveryRoom() {
            return deliveryRoomNow();
        }

        @Override
        public int deliveryChests() {
            return Math.max(1, book().chests());
        }

        @Override
        public Box deliveryChest(boolean verify) {
            return deliveryChestFor(verify);
        }

        @Override
        public void stopRepair() {
            if (repairer.active()) repairer.abortNow();
            if (job == Job.Repair) job = null;
        }

        @Override
        public int freeCarryCount() {
            return StashSorter.this.freeCarryCount();
        }

        @Override
        public boolean craftBusy() {
            return tableSession || placingTable;
        }

        @Override
        public int craftMaxFills() {
            StashManager manager = meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
            return manager == null ? 2048 : manager.craftOrderMaxFills();
        }

        @Override
        public int craftMaxPerHour() {
            StashManager manager = meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
            return manager == null ? 4096 : manager.craftOrderMaxPerHour();
        }

        @Override
        public String craftTableProblem() {
            return StashSorter.this.craftTableProblem();
        }

        @Override
        public void retryTable() {
            craftHealth.forget();
        }
    });
    private int collectedSlot = -1;
    private int seenKits;
    private int seenKitSettings;
    private boolean seenKitSorting;

    // Bot mode
    private boolean idle;
    private int idleTicks;
    private int seenVersion;
    private int version;

    // Containers the player opens themselves
    private AbstractContainerMenu trackedMenu;
    private Box trackedBox;

    // The last block interact sent, so a menu is only filed under the box it was really opened from
    private static final int INTERACT_WINDOW = 20 * 5;
    private int clock;
    private BlockPos interactPos;
    private AbstractContainerMenu interactMenu;
    private int interactClock;

    // The sorter's own open click, same idea
    private BlockPos openPos;
    private AbstractContainerMenu openMenuBefore;

    private final Queue<String> replies = new ArrayDeque<>();
    private int replyCooldown;

    public StashSorter() {
        super(QwerteAddon.CATEGORY, "stash-sorter", "Sorts a saved stash by tier or by sign, keeps it sorted, and lets you search it.");

        // Always listening: keeping the stash up to date, picking containers and whisper searches all work with the
        // module off
        MeteorClient.EVENT_BUS.subscribe(new Tracker());
        RightClick.listen(this::onRightClick);
    }

    // Turning on and off

    @Override
    public void onActivate() {
        // Taking over from stash-manager's driving: that is torn down first, then this starts as usual
        if (driven) {
            driven = false;
            teardown();
        }
        setup();
    }

    /**
     * Everything a start needs. {@code user} (the module was turned on) adds what is for a person: new containers near
     * you, signs, everything marked stale, the checks and the talk. Driven by stash-manager only the state is reset.
     */
    private void setup() {
        setUp = true;
        boolean user = isActive();
        job = null;
        target = null;
        breakRetries = 0;
        brokenSince = -1;
        moved = 0;
        wait = 0;
        idle = false;
        stalled.clear();
        compacted.clear();
        resetLoopState();
        yielder.reset();
        gate = Gate.Running;
        ownedMenu = null;
        ourGoal = null;
        if (BARITONE) BaritoneHelper.unconfine();
        jobInterruptions = 0;
        returnTo = null;
        tierBoxes.clear();
        tiesReported.clear();
        handle = null;
        packTried.clear();
        compactor.reset();
        failedSpots.clear();
        resetWander();
        placeFailures = 0;
        lastPlaceTick = -1000;
        repackPausedUntil = 0;
        dropLost = false;
        placedConfirmed = false;
        kitBuilder.reset();
        crafter.reset();
        craftForOrder = false;
        craftCooldownUntil = 0;
        craftHealth.note(null, null, null);
        collectedSlot = -1;
        scanned = 0;
        unbundleBlocked.clear();
        ground.abort();
        offline = false;
        resuming = false;
        resumeVerify = false;
        resumeFinishOnly = false;
        resumeDimensionSaid = false;
        syncedHandle = null;
        syncedPlaced = null;
        syncedConfirmed = false;

        // What was carried from the stash before the last stop is carried again, before the slots are chosen
        ensureStash();
        restoreHeld();

        // Scan-only never carries anything, so there are no slots to find
        if (sorting()) {
            if (!pickCarrySlots()) {
                turnOff();
                return;
            }
        }
        else if (ordersWanted()) {
            // Not sorting moves nothing by itself, but orders need slots to carry in
            pickCarrySlots(false);
        }
        else {
            carry.clear();
            pruneHeld();
        }
        orderHeld.clear();
        heldFrom.clear();
        returnGaveUp.clear();
        returnWarned.clear();
        // What an order took before a stop is put back now
        if (!sorting() && ordersWanted()) {
            for (int slot : stashHeld.keySet()) {
                ItemStack there = mc.player.getInventory().getItem(slot);
                if (carry.contains(slot) && !there.isEmpty()) orderHeld.put(slot, there.copy());
            }
        }

        boolean existed = ensureStash() != null;
        if (stash == null) stash = new Stash(stashName.get(), Stash.currentServer());

        // A shulker step that was under way is finished first
        pendingResume = stash.inFlight;
        if (pendingResume != null && pendingResume.placed != null) {
            info("A shulker step was under way at %s when I stopped, finishing it first.", pendingResume.placed.toShortString());
        }
        else pendingResume = null;

        // Driven: reads, orders and leftovers only. New containers, signs, marking everything stale, the checks and the talk
        // are for a person turning the module on
        if (!user) return;

        int added = addNearbyBoxes();
        labeller.reset();
        for (Box box : stash.boxes) {
            box.unreachable = false;
            box.labelFailed = false;
            readSigns(box);

            // A sign somebody else has changed since is theirs now
            Containers.Signs signs = Containers.read(stash, box);
            if (box.autoLabel != null && signs != null && !signs.labels().contains(box.autoLabel)) {
                box.autoLabel = null;
                box.autoSignPos = null;
                stash.markDirty();
            }
        }
        // Everything given up on or unexplored before gets another try
        forgetExploration();
        orderBoxes();
        checkOrphans();
        // Players may have changed things while it was off or the connection was down: every container is read again,
        // what is known stays in use until each one is
        // (not the ones read within the fresh-minutes window: those are only read again if something marks them stale)
        for (Box box : stash.boxes) box.stale = !box.excluded && !fresh(box);

        // With a perimeter the containers come from exploring it, and the checks wait until that is done
        if (!stash.hasArea() && !checkStash()) return;

        int unknown = (int) stash.boxes.stream().filter(b -> !b.excluded && !b.foreign && (!b.known() || b.stale)).count();
        if (!sorting()) info("Stash \"%s\": %d containers, scan only: reading %d unknown or stale ones, then watching for changes.", stash.name, stash.boxes.size(), unknown);
        else if (!existed) info("New stash \"%s\": scanning %d containers, then sorting.", stash.name, stash.boxes.size());
        else if (unknown > 0) info("Stash \"%s\": %d containers, scanning %d new ones first.", stash.name, stash.boxes.size(), unknown);
        else info("Stash \"%s\": %d containers, sorting by %s.", stash.name, stash.boxes.size(), mode.get() == Mode.Tiers ? "tier" : "sign");

        // What is going to happen, and in what order
        List<String> plan = new ArrayList<>();
        if (stash.hasArea()) plan.add("explore the area");
        if (unknown > 0) plan.add("read " + unknown + " containers");
        plan.add(!sorting() ? "watch for changes" : mode.get() == Mode.Tiers ? "sort by tier" : "sort by sign");
        StringBuilder next = new StringBuilder();
        for (int i = 0; i < plan.size(); i++) next.append(i == 0 ? "" : ", then ").append(plan.get(i));
        step(String.format("starting \"%s\", %d containers; next: %s.", stash.name, stash.boxes.stream().filter(b -> !b.excluded).count(), next));

        if (added > 0) stash.save();
    }

    @Override
    public void onDeactivate() {
        teardown();
    }

    private void teardown() {
        setUp = false;
        // Nothing waiting for an area, nothing awaiting an acknowledgement: they belong to this run
        areaPending.reset();
        releaseWebsiteTable();
        // An order stops where it is: its saved plan carries on at the next claim
        // A repair in progress puts the bot's gear back first
        if (repairer.active()) {
            repairer.abortNow();
            if (job == Job.Repair) job = null;
        }
        orderRunner.reset();

        // A crafting table being made in the inventory's 2x2 grid: what is in the grid is taken back
        if (mc.player != null && crafter.inventoryGrid()) {
            var invMenu = mc.player.inventoryMenu;
            for (int i = 0; i < 30; i++) {
                Crafter.Act back = crafter.drain(invMenu);
                if (back == null || back.kind() != Crafter.Act.CLICK) break;
                mc.gameMode.handleInventoryMouseClick(invMenu.containerId, back.slot(), back.button(), ClickType.PICKUP, mc.player);
            }
            String left = gridLeftovers();
            if (!left.isEmpty()) alert("Stopped with items left in the inventory's crafting grid, no room to take them back: %s.", left);
        }
        // Only a window of ours, never one the player opened
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu && ourMenu(mc.player.containerMenu)) {
            // A crafting table is never closed with items in the grid: they are taken back first
            if (mc.player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu cm) {
                for (int i = 0; i < 30; i++) {
                    Crafter.Act back = crafter.drain(cm);
                    if (back == null || back.kind() != Crafter.Act.CLICK) break;
                    mc.gameMode.handleInventoryMouseClick(cm.containerId, back.slot(), back.button(), ClickType.PICKUP, mc.player);
                }
                if (!crafter.clean(cm)) alert("Stopped with items left in the crafting table, no room to take them back. The server returns them to your inventory.");
            }
            if (!mc.player.containerMenu.getCarried().isEmpty()) recoverCursor();
            closeOurWindow(mc.player.containerMenu);
        }
        ownedMenu = null;
        gate = Gate.Running;
        labeller.deactivate();
        ledgeGuard(false);
        ground.abort();
        // Emptying a bundle: whatever is on the cursor goes into a carry slot
        if (job == Job.Unbundle && mc.player != null) finishUnbundle();

        if (usedBaritone) {
            releaseGoal();
            BaritoneHelper.unprotect();
            usedBaritone = false;
        }

        if (pickaxeSwapped) {
            InvUtils.swapBack();
            pickaxeSwapped = false;
        }
        reportPlacedShulker();
        restoreHotbar();
        handle = null;
        resuming = false;
        pendingResume = null;
        syncedHandle = null;
        syncedPlaced = null;
        kitBuilder.deactivate();
        // What the craft gathered stays tracked as the stash's, so it goes back later
        if (crafter.active()) crafter.finish();
        crafter.reset();
        craftForOrder = false;
        anvilSession = false;
        tableSession = false;
        placingTable = false;
        anvilSlot = -1;
        placer.abort("stopped");
        placer.restoreHand();
        chestSession = false;
        chestHolding = false;
        recheckOnly = false;

        syncHeld();
        if (stash != null && stash.isDirty()) stash.save();

        if (mc.player != null) {
            int held = sorting() ? carriedSlots().size() : (int) orderHeld.keySet().stream().filter(slot -> !mc.player.getInventory().getItem(slot).isEmpty()).count();
            if (held > 0) alert("Stopped while carrying %d stacks from the stash, they're in your inventory.", held);
        }

        job = null;
        target = null;
        if (mc.player != null) pruneHeld();
    }

    /**
     * Drops the current job and anything left to resume, then starts over as if the module had just been turned on. A
     * shulker of ours still standing is left where it is and listed with the orphaned ones, not finished. With the module
     * off, only a saved shulker step is forgotten.
     */
    private void unstuck() {
        if (mc.player == null || mc.level == null) return;
        // Driven by stash-manager counts as running: it is stopped and started again like the module
        boolean wasOn = isActive();
        if (!isActive() && !driven) {
            if (ensureStash() == null || stash.inFlight == null) {
                info("Nothing to unstick: the sorter is off and no unfinished shulker step is saved.");
                return;
            }
            BlockPos at = stash.inFlight.placed;
            stash.inFlight = null;
            stash.markDirty();
            stash.save();
            info("Forgot the unfinished shulker step%s, it won't be resumed.", at == null ? "" : " at " + at.toShortString());
            return;
        }

        BlockPos left = standingShulker();
        if (left == null && pendingResume != null) left = pendingResume.placed;
        String what = placedName();
        Compactor.Plan plan = compactor.plan;
        int stacks = plan != null ? plan.placedSize : placedStacks;
        BlockPos wasCollecting = handle == Handle.Collect ? placed : null;

        // Turning off saves a standing shulker to be finished on the next start: that record is dropped before turning on
        turnOff();
        if (stash != null && stash.inFlight != null) {
            stash.inFlight = null;
            stash.markDirty();
            stash.save();
        }
        if (left != null && shulkerAt(left)) {
            recordOrphan(left, what, stacks);
            alert("Unstuck: left the shulker at %s where it stands, it is listed with the orphaned shulkers.", left.toShortString());
        }
        else if (wasCollecting != null) alert("Unstuck: the shulker from %s may still be on the ground there, check for it.", wasCollecting.toShortString());
        alert("Unstuck: dropped what I was doing, starting over.");
        // The module goes back on if it was on; driven by stash-manager it is started again by its next tick
        if (wasOn && !isActive()) toggle();
    }

    /**
     * Works out which slots to carry stash items in, from the settings or, by default, every empty slot.
     */
    private boolean pickCarrySlots() {
        return pickCarrySlots(true);
    }

    /**
     * @param required false in scan-only mode, where the slots are only for orders: having none is not an error
     */
    private boolean pickCarrySlots(boolean required) {
        carry.clear();
        pruneHeld();
        Set<Integer> keep = parseSlots(keepSlots.get());
        Set<Integer> chosen = parseSlots(carrySlots.get());
        boolean designated = !carrySlots.get().isBlank();

        for (int i = 0; i < 36; i++) {
            if (keep.contains(i)) continue;
            if (designated && !chosen.contains(i)) continue;
            // Stacks from the stash still in the inventory from before (sorting turned off and on) are carried again, so
            // they are put away and not left behind as if they were the player's own
            ItemStack there = mc.player.getInventory().getItem(i);
            ItemStack held = stashHeld.get(i);
            if (there.isEmpty() || (held != null && ItemStack.matches(there, held))) carry.add(i);
        }

        if (designated && carry.size() < chosen.size()) {
            warning("%d of your carry-slots have items in them, they'll be left alone.", chosen.size() - carry.size());
        }

        if (carry.isEmpty()) {
            if (!required) return true;
            alertError(designated ? "Your carry-slots are all full. Empty at least one of them." : "Empty at least one inventory slot to carry items in.");
            return false;
        }
        return true;
    }

    /**
     * "1-9, 28-36" into inventory indexes. 1-9 is the hotbar and 10-36 the main inventory, matching index + 1.
     */
    private static Set<Integer> parseSlots(String text) {
        Set<Integer> slots = new HashSet<>();
        for (String part : text.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                String[] range = part.split("-");
                int from = Integer.parseInt(range[0].trim());
                int to = range.length > 1 ? Integer.parseInt(range[1].trim()) : from;
                for (int n = Math.max(1, Math.min(from, to)); n <= Math.min(36, Math.max(from, to)); n++) slots.add(n - 1);
            } catch (NumberFormatException ignored) {
                // Skip anything that isn't a number or range
            }
        }
        return slots;
    }

    // The sorting loop

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // The website's table stands in for ours only while a craft session runs: cleared every tick whatever else happened
        // The order table (when the user's own is out of the limits) stays in until a table being gathered, crafted or placed for it is done
        if (swappedStash != null && !crafter.active() && (!swappedSide || (!tableSession && !placingTable && !orderRunner.craftChunkPending()))) releaseWebsiteTable();
        if (stash == null) {
            turnOff();
            return;
        }
        ticks++;
        // Gear stowed for a repair that was cut short (death, disconnect, a crash) is put back as soon as it can be
        if (!stash.repairGear.isEmpty() && mc.player != null && mc.player.tickCount > 40 && !repairer.active() && mc.screen == null
            && mc.player.containerMenu == mc.player.inventoryMenu && mc.player.containerMenu.getCarried().isEmpty()) {
            Repairer.undoGear(stash.repairGear);
            stash.repairGear.clear();
            stash.markDirty();
            stash.save();
        }
        // Whatever the sorter is doing, a skipped container whose time has come is made eligible again
        releaseDueSurveys();
        if (handle != null) lastOwnAct = ticks;
        // Time spent giving way does not count against the time allowed to finish a saved shulker step
        if (resuming && gate != Gate.Running) resumeStarted++;

        // What else is using the client, every tick: nothing of ours goes on while it's anything
        StashYield.Reason foreign = yielder.check();
        foreignNow = foreign != null;
        // The sorter has the controls while it walks or works; a ledge with a drop gets a sneak, which is let go of otherwise
        ledgeGuard(gate == Gate.Running && foreign == null && standingWork());
        switch (gate) {
            case Yielding -> {
                if (cleanupStep()) gate = Gate.Waiting;
                return;
            }
            case Waiting -> {
                if (!tickWaiting(foreign)) return;
            }
            case Running -> {
                // Stay-on mode with nothing to do holds nothing of anyone's, so there's nothing to give way
                if (foreign != null && !restingIdle() && !(job == Job.Label && labeller.critical())) {
                    beginYield(foreign);
                    return;
                }
            }
        }

        if (wait > 0) {
            wait--;
            return;
        }

        if (job == null) {
            // Never start while a container is open, it might be one the player is using
            if (mc.player.containerMenu != mc.player.inventoryMenu) return;
            pruneHeld();
            if (relocateNamed()) return;
            remindStuck();
            releaseDeferred();
            applyStale();
            applySignChanges();
            // The perimeter changed while a job was under way: containers outside it go, the ones inside come in
            if (areaDirty) applyArea();
            // Chunks of the perimeter that loaded since, with containers in them
            if (ticks % 100 == 0 && stash.hasArea()) {
                pruneForeign("periodic check");
                int added = scanArea();
                // Containers that are gone, where the chunk is loaded to tell
                boolean gone = stash.boxes.removeIf(this::blockGone);
                if (gone) stash.markDirty();
                if (added > 0 || gone) {
                    version++;
                    idle = false;
                }
            }
            // Scan-only with no carry slot yet: looked for again now and then, for orders
            if (!sorting() && ordersWanted() && carry.isEmpty() && ticks % 100 == 0 && job == null && mc.player.containerMenu == mc.player.inventoryMenu) pickCarrySlots(false);
            // Not taking orders and nothing of an order held: no carry slots
            else if (!sorting() && !ordersWanted() && orderHeld.isEmpty() && !carry.isEmpty()) carry.clear();
            // Scan-only was switched off: there are carry slots to find now
            if (sorting() && carry.isEmpty()) {
                if (!pickCarrySlots()) {
                    turnOff();
                    return;
                }
                idle = false;
            }
            if (idle && !wakeUp()) {
                // Resting: only something on the ground can still start a job. Looked at every half second, and only
                // when nobody else is busy
                if (ticks % 10 != 0 || foreign != null || !yielder.quietElapsed() || !startGround()) return;
            }
            else {
                // Nothing is started while someone else is busy, nor until they have been quiet for a while
                if (foreign != null || !yielder.quietElapsed()) return;
                if (!nextJob()) return;
            }
        }

        if (handle != null) {
            tickHandling();
            return;
        }
        if (job == Job.Ground) {
            ground.tick();
            return;
        }
        if (job == Job.Explore) {
            exploreTick();
            return;
        }
        if (job == Job.Unbundle) {
            unbundleTick();
            return;
        }
        if (job == Job.Label) {
            labeller.tick();
            return;
        }
        if (job == Job.Name) {
            nameTick();
            return;
        }
        if (job == Job.Repair) {
            repairTick();
            return;
        }
        if (job == Job.Craft) {
            craftTick();
            return;
        }
        if (job == Job.AnvilPlace) {
            anvilPlaceTick();
            return;
        }
        if (job == Job.ChestPlace) {
            chestPlaceTick();
            return;
        }
        stepTicks++;

        // A container job that has run 4 minutes of its own time (giving way is not counted, nothing runs then) is over
        if (target != null && ++jobTicks > (job == Job.Survey ? SURVEY_WATCHDOG_TICKS : JOB_WATCHDOG_TICKS)) {
            Box stuck = target;
            debug("Watchdog: %s at %s ran %d s (step %s, %d actions), ending it.", job, xyz(stuck.pos), jobTicks / 20, step, actions);
            if (job == Job.Survey) {
                stuck.stale = true;
                surveyFailed(stuck, "reading it took " + jobTicks / 20 + "s (stuck in step " + step + ")");
            }
            else stalled.add(stuck);
            // The walk is let go of too
            releaseGoal();
            if (BARITONE) BaritoneHelper.unconfine();
            finishJob();
            return;
        }

        switch (step) {
            case Walking -> tickWalking();
            case Opening -> tickOpening();
            case Working -> tickWorking();
        }
    }

    // Giving way

    /**
     * Whether the sorter is in the middle of a job, so other modules can keep out of its way. False when it's off, resting
     * in stay-on mode, between jobs, or giving way to someone else.
     */
    public boolean busyWithJob() {
        // Between jobs, and while waiting for others, it holds nothing: whoever is working finishes its step first
        return running() && gate == Gate.Running && (job != null || handle != null);
    }

    private boolean restingIdle() {
        return idle && job == null && handle == null;
    }

    // Picking up from the ground

    /**
     * Collecting from the ground is on and possible: the setting, not scan-only, Baritone there, the module running.
     */
    private boolean groundOn() {
        return isActive() && sorting() && collectGround.get() && BARITONE;
    }

    /**
     * Between jobs, with nothing else to do: starts a trip to something lying on the ground. Never while a shulker is
     * standing in the world, a kit or a compaction is in hand, or a container is open.
     * @return true when a trip has begun
     */
    // Trips to the ground in a row without putting anything away, at the most
    private static final int GROUND_BATCH_MAX = 30;
    private int groundBatch;

    private boolean startGround() {
        if (job != null || handle != null || placed != null || gate != Gate.Running) return false;
        if (compactor.active() || kitBuilder.active() || mc.player.containerMenu != mc.player.inventoryMenu) return false;
        if (!mc.player.containerMenu.getCarried().isEmpty() || mc.screen != null) return false;
        if (!ground.next()) return false;

        job = Job.Ground;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        return true;
    }

    /**
     * The menu is a container window the sorter opened, or is in the middle of opening.
     */
    private boolean ourMenu(AbstractContainerMenu menu) {
        if (menu == null || mc.player == null || menu == mc.player.inventoryMenu) return false;
        if (menu == ownedMenu) return true;
        if (job == null || job == Job.Ground || job == Job.Explore || job == Job.Unbundle) return false;
        // A chest that opens while a sign is going up is the click's doing, and ours to close
        if (job == Job.Label) return labeller.ownsChest(menu);
        if (handle != null) return handle == Handle.Open && menu instanceof ShulkerBoxMenu;
        // Whatever opened soon after our own click on the box is ours, whatever it turns out to be (the wrong size, an
        // ender chest, a plugin menu): it is closed and the click made again, never waited on as somebody else's
        return step == Step.Opening && openPos != null && ticks - openClickTick <= OPEN_ANSWER_TICKS && menu != openMenuBefore;
    }

    /**
     * Closes one of our windows without taking a screen of the player's down with it: with another screen on top, the
     * server is told and the menu let go of, and the screen stays.
     */
    private void closeOurWindow(AbstractContainerMenu menu) {
        boolean ourScreen = mc.screen == null || (mc.screen instanceof AbstractContainerScreen<?> screen && screen.getMenu() == menu);
        if (ourScreen) mc.player.closeContainer();
        else {
            mc.player.connection.send(new ServerboundContainerClosePacket(menu.containerId));
            mc.player.containerMenu = mc.player.inventoryMenu;
        }
        ownedMenu = null;
        lastOwnAct = ticks;
        lastCloseTick = ticks;
        yielder.inventoryChangedByUs();
    }

    /** True while container-delay still holds back the next open click. */
    private boolean openHeld() {
        return com.porter.qwerte.utils.ContainerPacing.openHeld(ticks, lastCloseTick, containerDelay.get());
    }

    /** Ticks to wait before the first click in a window that has just opened. */
    private int firstClickWait() {
        return com.porter.qwerte.utils.ContainerPacing.firstClickWait(delay.get(), containerDelay.get());
    }

    /**
     * Lets go of the Baritone goal we set, and only that one: never cancels the player's own paths.
     */
    private void releaseGoal() {
        if (BARITONE && ourGoal != null) BaritoneHelper.releaseOurs((baritone.api.pathing.goals.Goal) ourGoal);
        ourGoal = null;
        releaseTick = ticks;
        // Baritone gets its break and place settings back the moment our walk is over
        if (BARITONE) BaritoneHelper.unconfine();
    }

    private void setGoal(Object goal) {
        ourGoal = goal;
        goalTick = ticks;
    }

    /**
     * Walks to within {@code range} blocks of a position with our own Baritone goal. Every walk the sorter makes goes
     * through here, with Baritone not allowed to break or place blocks for as long as the goal is ours: it never
     * digs or bridges its way to a destination. {@link #releaseGoal()} gives the settings back.
     */
    private void walkNear(BlockPos pos, int range) {
        if (!BARITONE) return;
        BaritoneHelper.protect(List.of());
        usedBaritone = true;
        BaritoneHelper.confine(safeWalk.get());
        if (ourGoal == null) walkInside = stash != null && stash.hasArea() && stash.inArea(mc.player.getX(), mc.player.getY(), mc.player.getZ(), AREA_MARGIN);
        debug(() -> String.format("Walking to within %d of %s.", range, xyz(pos)));
        setGoal(BaritoneHelper.moveNear(pos, range));
    }

    /**
     * The same walk, but only until the container is within reach: one up high is opened from below, the way a player
     * would, instead of looking for a spot next to it that may not be there.
     */
    private void walkReach(Box box) {
        if (!BARITONE) return;
        BaritoneHelper.protect(List.of());
        usedBaritone = true;
        BaritoneHelper.confine(safeWalk.get());
        if (ourGoal == null) walkInside = stash != null && stash.hasArea() && stash.inArea(mc.player.getX(), mc.player.getY(), mc.player.getZ(), AREA_MARGIN);
        debug(() -> String.format("Walking to within reach (%.1f) of %s.", reach.get(), xyz(box.pos)));
        setGoal(BaritoneHelper.moveInReach(box.parts, reach.get()));
    }

    /**
     * The same walk to a column, at any height: for places whose chunk isn't loaded yet.
     */
    private void walkXZ(int x, int z) {
        if (!BARITONE) return;
        BaritoneHelper.protect(List.of());
        usedBaritone = true;
        BaritoneHelper.confine(safeWalk.get());
        if (ourGoal == null) walkInside = stash != null && stash.hasArea() && stash.inArea(mc.player.getX(), mc.player.getY(), mc.player.getZ(), AREA_MARGIN);
        debug(() -> String.format("Walking to column %d, %d.", x, z));
        setGoal(BaritoneHelper.moveXZ(x, z));
    }

    /**
     * Our walk has left the stash area it started in (plus the margin), by a way that is not to be taken.
     */
    private boolean leftArea() {
        return ourGoal != null && walkInside && stash != null && stash.hasArea()
            && !stash.inArea(mc.player.getX(), mc.player.getY(), mc.player.getZ(), AREA_MARGIN);
    }

    // Ledge guard

    private boolean ledgeSneaking;

    /**
     * The sorter is standing still at its work with a window open or a click in the inventory: not while Baritone walks,
     * and never at the clicks that open or use something in the world (the container, the shulker, the sign), which
     * are made with the key up.
     */
    private boolean standingWork() {
        if (ourGoal != null) return false;
        if (handle != null) return handle == Handle.Work;
        if (job == null) return false;
        return switch (job) {
            case Unbundle -> true;
            case Label, Ground, Explore -> false;
            default -> step == Step.Working;
        };
    }

    /**
     * While the sorter has the controls (walking, or standing working), sneaks at the edge of a block when the drop on
     * that side is two blocks or more, so a push or a slip can't take it off. Let go as soon as the feet are 0.3 blocks
     * or more from every such edge, or the sorter doesn't have the controls: the key goes back to how it is physically.
     */
    private void ledgeGuard(boolean controlling) {
        boolean want = controlling && safeWalk.get() && mc.player != null && mc.level != null && nearDrop();
        KeyMapping key = mc.options.keyShift;
        if (want && !ledgeSneaking) {
            ledgeSneaking = true;
            key.setDown(true);
        }
        else if (!want && ledgeSneaking) {
            ledgeSneaking = false;
            key.setDown(Input.isPressed(key));
        }
    }

    /**
     * The feet are within 0.3 blocks of an edge of the block stood on, with a drop of 2 or more blocks beyond it: no solid
     * block in the two cells below the neighbouring cell (the cell beside the feet). Corners are checked diagonally.
     */
    private boolean nearDrop() {
        if (!mc.player.onGround() || mc.player.isInWater() || mc.player.getAbilities().flying) return false;

        BlockPos feet = mc.player.blockPosition();
        double fx = mc.player.getX() - feet.getX(), fz = mc.player.getZ() - feet.getZ();
        int dx = fx < LEDGE ? -1 : fx > 1 - LEDGE ? 1 : 0;
        int dz = fz < LEDGE ? -1 : fz > 1 - LEDGE ? 1 : 0;
        if (dx == 0 && dz == 0) return false;

        if (dx != 0 && drops(feet.offset(dx, 0, 0))) return true;
        if (dz != 0 && drops(feet.offset(0, 0, dz))) return true;
        return dx != 0 && dz != 0 && drops(feet.offset(dx, 0, dz));
    }

    private static final double LEDGE = 0.3;

    private boolean drops(BlockPos beside) {
        return !solid(beside.below()) && !solid(beside.below(2));
    }

    private boolean solid(BlockPos pos) {
        // What isn't loaded is no drop to guard against
        return !mc.level.isLoaded(pos) || !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    /**
     * The sorter may walk: Baritone is there, and the walk setting is on or a perimeter is set (which implies it).
     */
    private boolean walkOn() {
        return BARITONE && (walk.get() || (stash != null && stash.hasArea()));
    }

    /**
     * Someone else started something. Everything of ours is let go of first: the path, the window, the cursor. The job
     * stays, and is picked up again once they have finished and been quiet for a while.
     */
    private void beginYield(StashYield.Reason reason) {
        gate = Gate.Yielding;
        debug("Giving way to %s%s.", reason == null ? "a stack on the cursor" : reason.label, job == null ? "" : " in the middle of " + job);
        cleanupTries = 0;
        wait = 0;
        releaseGoal();
        ledgeGuard(false);
        if (job == Job.Label) labeller.yielded();
        if (job == Job.ChestPlace) placer.release();
        if (job != null) {
            yielder.interrupted();
            jobInterruptions++;
        }
        // A trip to the ground is simply dropped, the item is looked at again later
        if (job == Job.Ground) {
            ground.abort();
            job = null;
        }
        // Exploring carries on from the chunks still unseen
        if (job == Job.Explore || job == Job.Unbundle) job = null;
        if (stash.isDirty()) stash.save();
        yielder.startWaiting(reason);
    }

    /**
     * One step of letting go: the stack on the cursor is put away, then our own window is closed.
     * @return true when nothing of ours is left to let go of
     */
    private boolean cleanupStep() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        // The inventory's 2x2 grid of a crafting table being made is emptied too
        if (crafter.inventoryGrid() && menu == mc.player.inventoryMenu && cleanupTries < CURSOR_TRIES * 2) {
            Crafter.Act back = crafter.drain(menu);
            if (back != null && back.kind() == Crafter.Act.CLICK) {
                if (cleanupTries++ % 4 == 0) craftClick(menu, back.slot(), back.button());
                return false;
            }
        }
        if (menu != mc.player.inventoryMenu && ourMenu(menu)) {
            // A crafting table is never closed with items in the grid: they are taken back first
            if (menu instanceof net.minecraft.world.inventory.CraftingMenu && crafter.active() && cleanupTries < CURSOR_TRIES * 2) {
                Crafter.Act back = crafter.drain(menu);
                if (back != null && back.kind() == Crafter.Act.CLICK) {
                    if (cleanupTries++ % 4 == 0) craftClick(menu, back.slot(), back.button());
                    return false;
                }
            }
            // Never closed with something on the cursor, it would be merged into the inventory or dropped. After a few
            // tries it is closed anyway, so the others are not kept waiting for a click that never works
            if (!menu.getCarried().isEmpty() && cleanupTries < CURSOR_TRIES) {
                if (cleanupTries++ % 4 == 0) recoverCursor();
                return false;
            }
            closeOurWindow(menu);
            return false;
        }
        ownedMenu = null;
        return true;
    }

    /**
     * Letting go is done: waiting for them to finish, and then to be quiet for a while.
     * @return true when waiting is over and sorting carries on this tick
     */
    private boolean tickWaiting(StashYield.Reason foreign) {
        // A late answer to our own click: a window of ours opened while we were waiting
        if (mc.player.containerMenu != mc.player.inventoryMenu && ourMenu(mc.player.containerMenu)) {
            cleanupStep();
            return false;
        }
        if (foreign != null || !yielder.quietElapsed()) return false;

        // A stack left on the cursor with no screen open (the server doesn't always hand it back) goes into a free slot
        if (!mc.player.containerMenu.getCarried().isEmpty()) {
            if (mc.screen == null && mc.player.containerMenu == mc.player.inventoryMenu && cleanupTries++ % 4 == 0) returnCursor();
            return false;
        }

        yielder.endWaiting();
        gate = Gate.Running;
        yielder.inventoryChangedByUs();
        resumeJob();
        return true;
    }

    /**
     * Picks up where we left off. A job on a container is dropped and planned again: the world has changed under it,
     * and the containers are read as they are now. It isn't counted as getting nothing done. Repacking has a shulker in
     * the world, so it carries on from the same step.
     */
    private void resumeJob() {
        wait = delay.get();
        if (job == null) return;
        if (job == Job.Label) {
            labeller.resume();
            return;
        }
        if (handle != null) {
            resumeHandle();
            return;
        }

        if (job == Job.Compact && target != null) compacted.remove(target);
        if (jobCounted && target != null) {
            passJobs = Math.max(0, passJobs - 1);
            boxVisits.merge(target, -1, Integer::sum);
        }
        job = null;
        target = null;
        openPos = null;
        openMenuBefore = null;
        swapStep = 0;
        recoveries = 0;
        startSigSet = endSigSet = false;
        settled = false;
        jobInterruptions = 0;
    }

    private void resumeHandle() {
        boolean inWorld = placed != null && !mc.level.getBlockState(placed).isAir();

        // Interrupted over and over before anything was put down: plan something else
        if (jobInterruptions > MAX_JOB_INTERRUPTIONS && !inWorld) {
            endHandling();
            jobInterruptions = 0;
            return;
        }

        // The window was closed on the way: open it again
        Handle next = handle == Handle.Work ? Handle.Open : handle;
        if (!placedConfirmed && next != Handle.Place && next != Handle.Open) placedConfirmed = placed != null;
        if (inWorld && (next == Handle.Open || next == Handle.Break) && !inReachOf(placed)) {
            returnTo = next;
            next = Handle.Return;
        }
        handle = next;
        handleTicks = 0;
    }

    /**
     * Back to the shulker we put down, if we were moved away from it.
     */
    private void handleReturn() {
        if (placed == null || mc.level.getBlockState(placed).isAir() || inReachOf(placed)) {
            releaseGoal();
            handle = returnTo != null ? returnTo : Handle.Open;
            returnTo = null;
            handleTicks = 0;
            return;
        }

        if (leftArea()) {
            abandonShulker("Skipping the shulker box at " + at(placed) + ": the way there leaves the stash area.");
            return;
        }

        if (!walkOn() || handleTicks > WALK_TIMEOUT) {
            abandonShulker("Couldn't get back to the shulker I put down at " + placed.toShortString() + ".");
            return;
        }

        boolean idleNow = handleTicks > PATH_GRACE_TICKS && !BaritoneHelper.isMoving();
        if (handleTicks == 1 || (idleNow && handleTicks % 40 == 0)) {
            walkNear(placed, 2);
        }
    }

    /**
     * Puts a stack left on the cursor, with no window open, into a free slot of the inventory.
     */
    private void returnCursor() {
        ItemStack cursor = mc.player.containerMenu.getCarried();
        Set<Integer> keep = parseSlots(keepSlots.get());

        // The carry slots first, so the stack is put away with the rest; any other free slot only if there is none
        for (int pass = 0; pass < 2; pass++) {
            for (int slot = 0; slot < 36; slot++) {
                if (keep.contains(slot) || !mc.player.getInventory().getItem(slot).isEmpty()) continue;
                if ((pass == 0) != carry.contains(slot)) continue;

                stashHeld.put(slot, cursor.copy());
                InvUtils.click().slotId(slot < 9 ? 36 + slot : slot);
                lastOwnAct = ticks;
                return;
            }
        }
    }

    private boolean inReachOf(BlockPos pos) {
        return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reach.get() * reach.get();
    }

    /**
     * Gear, food and the like that came out of the stash and has nowhere to go looks just like the player's own, so it's
     * mentioned again every few minutes for as long as it's stuck.
     */
    private void remindStuck() {
        if (stuckCarried.isEmpty() || clock - lastStuckWarn < STUCK_REWARN) return;

        int own = 0;
        for (int slot : stuckCarried.keySet()) if (ownKind(mc.player.getInventory().getItem(slot))) own++;
        if (own == 0) return;

        lastStuckWarn = clock;
        alert("Still carrying %d stacks of gear or food from the stash that have nowhere to go, they're in your inventory.", own);
    }

    // Drop-offs: per container, when it is due for a check and how long to wait after that (it grows while checks find
    // nothing new). The ones being checked now are in dropRechecked
    private static final int DROP_RECHECK_MAX = 20 * 60 * 5;
    private final Map<Box, int[]> dropState = new HashMap<>();
    private final Set<Box> dropRechecked = new HashSet<>();

    /**
     * Whether items can arrive in or leave this container without anyone opening it, so no lid shows it: a hopper,
     * dropper or dispenser next to it or above it (or below, which empties it), or the place not being loaded.
     */
    private boolean changesSilently(Box box) {
        for (BlockPos part : box.parts) {
            if (!mc.level.isLoaded(part)) return true;
            for (Direction d : Direction.values()) {
                var block = mc.level.getBlockState(part.relative(d)).getBlock();
                if (block == Blocks.HOPPER || block == Blocks.DROPPER || block == Blocks.DISPENSER) return true;
            }
        }
        return false;
    }

    /**
     * A check of a drop-off is over: its waiting time starts again when it held something new, else it is doubled.
     */
    private void dropChecked(Box box, boolean differed) {
        if (!dropRechecked.remove(box)) return;
        dropDone(box, differed);
    }

    private void dropDone(Box box, boolean differed) {
        int base = recheck.get() * 20;
        int[] state = dropState.computeIfAbsent(box, b -> new int[] {0, base});
        state[1] = differed ? base : Math.min(DROP_RECHECK_MAX, Math.max(base, state[1]) * 2);
        state[0] = ticks + state[1];
        debug("Drop-off at %s %s, next check in %d s.", xyz(box.pos), differed ? "held something new" : "held nothing new", state[1] / 20);
    }

    /**
     * A container the scan could not read: skipped, said so with the reason and when it is tried again, and the scan
     * carries on with the others.
     */
    private void surveyFailed(Box box, String why) {
        failedSurvey.add(box);
        // One failure per job: the skip paths and finishJob can both get here for the same read
        int delay = surveyRetry.failOnce(box, ticks);
        if (delay < 0) return;
        step(String.format("skipped %s at %s: %s; will retry in %s.", blockName(box), xyz(box.pos), why, com.porter.qwerte.utils.SurveyRetry.describe(delay)));
    }

    /**
     * Containers whose retry time has come are read again. Runs every tick between jobs, so it does not depend on the
     * sorter being idle or on a particular tick.
     */
    private void releaseDueSurveys() {
        if (failedSurvey.isEmpty()) return;
        for (Box box : surveyRetry.due(ticks)) {
            if (!failedSurvey.remove(box)) continue;
            idle = false;
            QwerteAddon.LOG.info("[stash-sorter] retrying the read of {} at {} (failed {} times).", blockName(box), xyz(box.pos), surveyRetry.failures(box));
        }
    }

    /**
     * Bot mode, waiting for something to sort: a container that changed, or a drop-off due for a check.
     */
    private boolean wakeUp() {
        idleTicks++;

        // Never rest while there is work: a chest a player used, a changed area, or a container not read yet or due to be
        String why = null;
        if (!staleBoxes.isEmpty()) why = "a used chest is queued for a re-read";
        else if (areaDirty) why = "the stash area changed";
        else {
            // Containers that couldn't be read are tried again on their schedule (releaseDueSurveys, every tick)
            for (Box box : stash.boxes) {
                if (box.excluded || box.unreachable || failedSurvey.contains(box) || signPending(box)) continue;
                if (!box.known() || box.stale) {
                    Box found = box;
                    why = "the container at " + xyz(found.pos) + " is " + (box.known() ? "stale" : "unread");
                    break;
                }
            }
        }
        if (why != null) {
            debug("Waking from rest: %s.", why);
            idle = false;
            return true;
        }

        // Drop-offs are only emptied when sorting; scan-only learns of their changes from the lids
        int base = recheck.get() * 20;
        // A drop-off that can't be reached or read any more is no longer being waited for: it counts as a check that found
        // nothing
        for (Box box : new ArrayList<>(dropRechecked)) {
            if (box.unreachable || failedSurvey.contains(box) || !box.stale || !stash.boxes.contains(box)) {
                dropRechecked.remove(box);
                dropDone(box, false);
            }
        }
        if (sorting() && ticks % 20 == 0) {
            boolean any = false;
            for (Box box : stash.boxes) {
                if (!box.dropOff || box.unreachable || box.excluded || box.stale) continue;
                int[] state = dropState.computeIfAbsent(box, b -> new int[] {ticks + base, base});
                if (ticks < state[0]) continue;
                // Without smart-recheck every drop-off, as before; with it only those that can change unseen
                if (!smartRecheck.get()) {
                    state[0] = ticks + base;
                    box.stale = true;
                    any = true;
                }
                else if (changesSilently(box)) {
                    box.stale = true;
                    dropRechecked.add(box);
                    any = true;
                }
                else state[0] = ticks + DROP_RECHECK_MAX;
            }
            if (any) {
                idle = false;
                return true;
            }
        }

        if (Kits.version() != seenKits) {
            seenKits = Kits.version();
            version++;
        }

        // Turning kit-sorting on or off plans everything again
        if (kitSorting.get() != seenKitSorting) {
            seenKitSorting = kitSorting.get();
            version++;
        }

        // New kit settings from the Stash Manager server: one full pass, which moves kits stored elsewhere once their chest has room
        if (KitStore.shared().version() != seenKitSettings) {
            seenKitSettings = KitStore.shared().version();
            version++;
        }

        if (version != seenVersion) {
            seenVersion = version;
            clearStalled();
            compacted.clear();
            packTried.clear();
            futile.clear();
            // Boxes still waiting for their retry time stay skipped: a version bump does not cut the schedule short
            failedSurvey.removeIf(b -> !surveyRetry.waiting(b, ticks));
            unconfirmedRuns.clear();
            stuckCarried.clear();
            stuckWarned = false;
            kitBuilder.wake();
            idle = false;
            return true;
        }

        return false;
    }

    /**
     * Picks the next job. Read every container we don't know yet, then put away what we're carrying, then pick up
     * what's in the wrong place.
     * @return false when there's nothing left to do
     */
    private boolean nextJob() {
        try {
            return pickJob();
        } finally {
            destCache = null;
            misplacedCache = null;
            signedCache = null;
            roomBase = null;
            kitRoutedCache = null;
        }
    }

    /**
     * Per-planning-pass memos, only set while nextJob is choosing. Nothing that destinationsFor or misplacedCount read
     * (box contents, labels, flags, mode, tier boxes) changes during a pass, so within it equal inputs give equal
     * answers. Dropped when the pass ends, so later changes can never be served stale.
     */
    private Map<DestKey, List<Box>> destCache;
    private Map<Box, Integer> misplacedCache;
    // bestSigned by item and components, and the room each container has for deposits, both for the pass only
    private Map<DestKey, List<Box>> signedCache;
    private Map<Box, Integer> roomBase;

    /**
     * What destinationsFor and Categories.signScore depend on in a stack: its item and components (name, enchants,
     * potion, food, damage, shulker contents...), not its count.
     */
    private record DestKey(Item item, DataComponentMap components) {
        static DestKey of(ItemStack stack) {
            return new DestKey(stack.getItem(), stack.getComponents());
        }
    }

    /**
     * The nearest container something can be taken from right now, or null. With every carry slot full there is nothing
     * to pick up into: a trip would only open the container and stall it.
     */
    private Box nearestTakeSource() {
        if (freeCarrySlot() == -1) return null;
        return nearest(stash.boxes.stream().filter(b -> b.sortable() && !b.stale && !blocked(b, Job.Take) && !signPending(b) && takeableSlot(b) != -1).toList());
    }

    private boolean pickJob() {
        // A shulker step that was saved comes before anything else
        if (startResume()) return true;

        // An order in hand comes before the sorter's own work: reading stale containers, exploring, sorting, compacting,
        // labelling, saved kits, the ground. They wait until it is finished or parked
        if (orderRunner.planned() && orderPriorityStep()) return true;

        // Exploring: whatever unknown or old is within reach is read before moving on to the next waypoint
        if (exploring && stash.hasArea()) {
            Box close = nearest(stash.boxes.stream().filter(b -> (!b.known() || b.stale) && !b.excluded && !b.foreign && !b.unreachable && !failedSurvey.contains(b)
                && !signPending(b) && inReach(b)).toList());
            if (close != null) return start(Job.Survey, close);
        }

        // With a perimeter, its chunks are loaded first, so its containers are known before anything is sorted
        if (explorePending && exploreRan && !exploring) explorePending = false;
        if ((isActive() || explorePending) && startExplore()) return true;
        if (!running()) return false;

        Box unread = nearest(stash.boxes.stream().filter(b -> !b.known() && !b.unreachable && !failedSurvey.contains(b) && !signPending(b)).toList());
        if (unread != null) return start(Job.Survey, unread);

        // Scan-only: the containers last seen before a stop are read again, then everything is read, nothing is moved.
        if (!sorting()) {
            Box old = nearest(staleBoxes());
            if (old != null) return start(Job.Survey, old);
        }

        // Scan-only: crafting whose order is over is driven to its end (the grid emptied, what was gathered put back)
        if (!sorting() && crafter.active() && (!craftForOrder || !orderRunner.running()) && runCraft(crafter.next())) return true;

        // Scan-only: an order is the only thing that moves items, and what it leaves over goes back where it came from
        if (!sorting() && scanOnlyOrderStep()) return true;

        // Scan-only: everything is read, nothing is moved. A kit in hand is let go of, and a shulker standing in the
        // world is fetched back by finishSorting
        if (!sorting()) {
            if (!orderKit()) kitBuilder.suspend("scan only.");
            finishSorting();
            return false;
        }

        plan();
        destCache = new HashMap<>();
        misplacedCache = new HashMap<>();
        signedCache = new HashMap<>();
        roomBase = null;

        // Jobs started since the backlog (stacks out of place, and carried) last reached a new low: a pass that keeps
        // starting jobs without getting anywhere is going in circles, so say so and stop rather than reopen forever
        int carriedNow = carriedSlots().size();
        int backlog = carriedNow;
        for (Box box : stash.boxes) if (box.sortable()) backlog += misplacedCount(box);
        if (backlog < passMin) {
            passMin = backlog;
            passJobs = 0;
            // Real headway: containers given up on for going back and forth get another chance
            stalled.removeAll(circular);
            circular.clear();
            boxVisits.clear();
        }
        if (passJobs > 6 * stash.boxes.size() + 60) {
            alert("Sorting went in circles (%d jobs without getting closer to done), stopping this pass. %d stacks out of place, carrying %d.",
                passJobs, backlog - carriedNow, carriedNow);
            finishSorting();
            return false;
        }

        // A bundle with things in it is emptied first, its contents are then carried like anything else
        if (startUnbundle()) return true;

        // Containers last seen before a stop are read again before anything is taken from them or built from them; what
        // is carried is put away first
        List<Box> toReread = staleBoxes();
        boolean rereading = !toReread.isEmpty();

        // An order kit left over from an order that is over is let go of, so it can't be taken for a saved kit
        if (orderKit() && !orderRunner.running()) kitBuilder.dropOrderKit();

        // Sorting: a shulker an order unpacked goes back to the container it came from, named or not
        if (sorting() && !rereading && !kitBuilder.active() && !orderKit()) {
            List<Integer> back = returnCandidates();
            returning = !back.isEmpty();
            if (returning && returnLeftovers(back)) return true;
        }

        // An order carries on until it is delivered, failed or paused. New work on it waits until what is carried is put away
        if (orderRunner.running() && !rereading && (kitBuilder.active() || crafter.active() || activeCarried().isEmpty()) && runOrder(orderRunner.next())) return true;

        // Crafting for the compactor carries on until it is done or given up on
        if (crafter.active() && (!craftForOrder || !orderRunner.running()) && !rereading && runCraft(crafter.next())) return true;

        // A kit being built carries on until it's finished or given up on
        if (kitBuilder.active() && !rereading && !orderKit()) {
            if (!kitsOn()) {
                kitBuilder.suspend(!shulkersOk() ? "placing shulkers failed lately, so no shulker work for a few minutes."
                    : mode.get() != Mode.Signs ? "kits are only built in Signs mode." : "build-kits was switched off.");
            }
            else if (runKit(kitBuilder.next())) return true;
        }

        // Compacting carries on until it is done or given up on: nothing else is started meanwhile
        if (compactor.active() && (!rereading || compactor.plan.finishOnly) && compactor.next()) return true;

        // Something nothing is signed for gets a new category on an empty chest, before it's put away
        if (!rereading && labeller.next()) return true;

        if (!rereading && repackOn() && nextRepackJob()) return true;

        // Stacks given up on are tried again every pass: a container read, emptied or signed since can give them a place
        stuckCarried.entrySet().removeIf(held -> {
            ItemStack stack = mc.player.getInventory().getItem(held.getKey());
            return stack.isEmpty() || !idOf(stack).equals(held.getValue());
        });
        // Looked at again when something changed or every few seconds, not on every pick
        if (!stuckCarried.isEmpty() && (stuckCheckVersion != version || clock - lastStuckCheck >= STUCK_RECHECK)) {
            stuckCheckVersion = version;
            lastStuckCheck = clock;
            stuckCarried.entrySet().removeIf(held -> canPlace(mc.player.getInventory().getItem(held.getKey())));
        }
        if (stuckCarried.isEmpty()) stuckWarned = false;

        List<Integer> carried = activeCarried();
        if (!carried.isEmpty()) {
            List<Box> places = new ArrayList<>();
            for (int slot : carried) {
                for (Box box : destinationsFor(mc.player.getInventory().getItem(slot))) {
                    if (!blocked(box, Job.Deposit) && !signPending(box) && (box.free() > 0 || misplacedCount(box) > 0)) places.add(box);
                }
            }

            Box place = nearest(places);

            // A box given up on for going back and forth that is the only home for something carried is still used:
            // the pass watchdog stops a real loop
            if (place == null) {
                for (int slot : carried) {
                    for (Box box : destinationsFor(mc.player.getInventory().getItem(slot))) {
                        if (circular.contains(box) && !signPending(box) && !signHeld.contains(box) && !blockedByFutility(box, Job.Deposit) && (box.free() > 0 || misplacedCount(box) > 0)) places.add(box);
                    }
                }
                place = nearest(places);
            }
            if (place != null) {
                // Room for more and something to take nearer than where this goes: take on the way, then put away
                // together. What is taken is only what has room to go (takeableSlot reserves it)
                if (freeCarryCount() >= Math.max(2, carry.size() / 3)) {
                    Box onTheWay = nearestTakeSource();
                    if (onTheWay != null && closer(onTheWay, place)) {
                        debug("Taking from %s at %s on the way, it is nearer than %s at %s.", home(onTheWay), xyz(onTheWay.pos), home(place), xyz(place.pos));
                        return start(Job.Take, onTheWay);
                    }
                }
                return start(Job.Deposit, place);
            }

            // Nowhere it belongs has room: a container signed misc, other or overflow takes it, failing that (in Signs
            // mode) a container with no sign at all. Never one signed for something else
            Box spare = spareBox();
            if (spare != null) return start(Job.Dump, spare);

            // Nothing takes it for now. It stays in the carry slots, out of the plans, while the rest carries on, and is
            // looked at again when something in the stash changes
            for (int slot : carried) stuckCarried.put(slot, idOf(mc.player.getInventory().getItem(slot)));
            if (!stuckWarned) {
                stuckWarned = true;
                lastStuckWarn = clock;
                alert("Found nowhere with room for %d stacks I'm carrying (no container they belong in has space, and none signed misc/other/overflow does%s), keeping them in your inventory and carrying on with the rest.",
                    carried.size(), unsignedOverflow.get() && mode.get() == Mode.Signs ? ", and no unsigned container does" : "");
            }
        }

        // Now the containers that were last seen before the stop are read again
        if (rereading) {
            Box old = nearest(toReread);
            if (old != null) return start(Job.Survey, old);
        }

        // Mixed shulkers sitting in the stash: fetch one to clean out
        if (repackOn() && freeCarryCount() >= 2) {
            Box best = null;
            int bestSlot = -1;
            for (Box box : stash.boxes) {
                if (!box.sortable() || box.stale || blocked(box, Job.Fetch) || signPending(box) || isKitBox(box)) continue;
                for (int i = 0; i < box.contents.length; i++) {
                    if (!strays(box.contents[i]).isEmpty() && (best == null || closer(box, best))) {
                        best = box;
                        bestSlot = i;
                        break;
                    }
                }
            }
            if (best != null) return fetch(best, bestSlot);
        }

        // With every carry slot full there's nothing to pick up into: a trip would only open the container and stall it
        Box source = nearestTakeSource();
        if (source != null) return start(Job.Take, source);

        // Downstack: merge partial stacks of the same item, once per container per run
        Box messy = nearest(stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && !blocked(b, Job.Compact) && !signPending(b) && !compacted.contains(b) && mergeable(b) != null).toList());
        if (messy != null) {
            compacted.add(messy);
            return start(Job.Compact, messy);
        }

        // A sign that is being changed decides what is left to do: wait for it before calling it done
        if (watchSigns.get() && mode.get() == Mode.Signs && stashWatch.anyPending()) return false;

        // Everything is put away: whatever others left on the ground comes before kits and compacting
        if (startGround()) return true;

        // Nothing left to sort: build kits
        if (kitsOn() && !orderKit() && activeCarried().isEmpty() && !compactor.active() && kitBuilder.begin() && runKit(kitBuilder.next())) return true;

        // Everything is where it belongs: a full shulker's worth of anything is packed, which frees chest slots
        if (!kitBuilder.active() && compactor.start() && compactor.next()) return true;
        if (crafter.active() && (!craftForOrder || !orderRunner.running()) && runCraft(crafter.next())) return true;

        finishSorting();
        return false;
    }

    // Bundles

    private int unbundleSlot = -1, unbundleTaken, unbundleStalls, unbundleSize;
    private boolean unbundleClicked;
    private final Map<Integer, Integer> unbundleBlocked = new HashMap<>();
    private int unbundleStartSize;
    private static final int UNBUNDLE_BLOCK_TICKS = 20 * 30;

    private static boolean isBundle(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.BUNDLE_CONTENTS);
    }

    /**
     * A bundle with something in it.
     */
    private static boolean fullBundle(ItemStack stack) {
        if (!isBundle(stack)) return false;
        var contents = stack.get(DataComponents.BUNDLE_CONTENTS);
        return contents != null && !contents.isEmpty();
    }

    private static int bundleSize(ItemStack stack) {
        var contents = stack.get(DataComponents.BUNDLE_CONTENTS);
        return contents == null ? 0 : contents.size();
    }

    /**
     * Where an empty bundle goes: a container signed for bundles, else one signed for shulkers or containers. Empty when
     * there is none, and the usual rules apply.
     */
    private List<Box> bundleHomes(ItemStack bundle) {
        List<Box> signed = new ArrayList<>();
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.dropOff || isKitBox(box)) continue;
            if (box.labels.stream().anyMatch(label -> Categories.normalize(label).contains("bundle"))) signed.add(box);
        }
        if (!signed.isEmpty()) return signed;
        return bestSigned(Items.SHULKER_BOX.getDefaultInstance());
    }

    /**
     * A bundle with things in it, in a carry slot, is emptied into the other carry slots before anything else is done with
     * it. Needs the plain inventory (no screen, nothing on the cursor) and a free carry slot for what comes out.
     * @return true when emptying has begun
     */
    private boolean startUnbundle() {
        if (!sorting()) return false;
        if (mc.screen != null || mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()) return false;
        if (freeCarrySlot() == -1) return false;

        Set<Integer> setAside = compactor.reserved();
        for (int slot : carriedSlots()) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!fullBundle(stack) || labeller.reservedSlot(slot) || setAside.contains(slot) || ticks < unbundleBlocked.getOrDefault(slot, -1)) continue;

            unbundleSlot = slot;
            unbundleTaken = 0;
            unbundleStalls = 0;
            unbundleClicked = false;
            unbundleSize = unbundleStartSize = bundleSize(stack);
            stashHeld.put(slot, stack.copy());

            step(String.format("emptying a bundle; next: put away %d stacks.", unbundleSize));
            job = Job.Unbundle;
            target = null;
            handle = null;
            actions = 0;
            jobCounted = false;
            stepTicks = 0;
            jobInterruptions = 0;
            startSigSet = endSigSet = false;
            settled = false;
            openPos = null;
            openMenuBefore = null;
            return true;
        }
        return false;
    }

    /**
     * Takes the contents of the bundle out one stack at a time. Right-clicking a bundle in the inventory with an empty
     * cursor puts the next stack in it on the cursor (the same in 1.21.4 and 1.21.11), and a left click on a free carry slot
     * puts that stack down. The bundle never leaves its slot, so there's nothing to put back.
     */
    private void unbundleTick() {
        stepTicks++;
        lastOwnAct = ticks;
        AbstractContainerMenu menu = mc.player.inventoryMenu;
        if (mc.screen != null || mc.player.containerMenu != menu) {
            finishUnbundle();
            return;
        }

        ItemStack bundle = mc.player.getInventory().getItem(unbundleSlot);
        ItemStack cursor = menu.getCarried();
        if (!isBundle(bundle) || stepTicks > 20 * 30) {
            finishUnbundle();
            return;
        }

        // A stack is out: down into a free carry slot
        if (!cursor.isEmpty()) {
            int free = freeCarrySlot();
            if (free == -1) {
                // Nowhere to put it: back into the bundle (a left click on a bundle with a stack on the cursor)
                bundleClick(unbundleSlot, 0);
                finishUnbundle();
                return;
            }
            stashHeld.put(free, cursor.copy());
            bundleClick(free, 0);
            unbundleTaken++;
            unbundleClicked = false;
            wait = delay.get();
            return;
        }

        if (!fullBundle(bundle) || freeCarrySlot() == -1) {
            finishUnbundle();
            return;
        }

        // The last take got nothing out of the bundle: the server isn't letting it
        int size = bundleSize(bundle);
        boolean noProgress = (unbundleClicked && size >= unbundleSize) || (unbundleTaken > 0 && size >= unbundleStartSize);
        if (noProgress && ++unbundleStalls > 2) {
            finishUnbundle();
            return;
        }
        unbundleSize = size;
        unbundleClicked = true;
        bundleClick(unbundleSlot, 1);
        wait = delay.get();
    }

    /**
     * A click on a carry slot in the player inventory menu: button 0 is a left click, 1 a right click.
     */
    private void bundleClick(int slot, int button) {
        AbstractContainerMenu menu = mc.player.inventoryMenu;
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot < 9 ? 36 + slot : slot, button, ClickType.PICKUP, mc.player);
        yielder.inventoryChangedByUs();
    }

    private void finishUnbundle() {
        ItemStack bundle = unbundleSlot >= 0 ? mc.player.getInventory().getItem(unbundleSlot) : ItemStack.EMPTY;
        if (isBundle(bundle)) stashHeld.put(unbundleSlot, bundle.copy());
        if (unbundleTaken > 0 && !fullBundle(bundle)) info("Emptied a bundle: %d stacks taken out.", unbundleTaken);
        if (fullBundle(bundle) && unbundleSlot >= 0) unbundleBlocked.put(unbundleSlot, ticks + UNBUNDLE_BLOCK_TICKS);
        // A stack still on the cursor, with no screen to take it: into a carry slot
        if (mc.screen == null && mc.player.containerMenu == mc.player.inventoryMenu && !mc.player.containerMenu.getCarried().isEmpty()) returnCursor();
        unbundleSlot = -1;
        if (job == Job.Unbundle) job = null;
        wait = delay.get();
        moved++;
    }

    // Building kits

    private boolean kitsOn() {
        return buildKits.get() && mode.get() == Mode.Signs && shulkersOk();
    }

    /**
     * Starts the job for the next step of the kit being built.
     * @return true when a job was started, false when the kit is finished or given up on
     */
    private boolean runKit(KitBuilder.Intent intent) {
        kitBuilder.jobStarted();

        switch (intent) {
            case KitBuilder.Take take -> {
                if (start(Job.KitTake, take.box())) return true;
                kitBuilder.suspend("couldn't reach " + take.box().pos.toShortString() + ".");
            }
            case KitBuilder.Fetch fetch -> {
                if (fetch(fetch.box(), fetch.slot())) return true;
                kitBuilder.suspend("couldn't reach " + fetch.box().pos.toShortString() + ".");
            }
            case KitBuilder.Unpack unpack -> {
                return startHandling(Job.KitUnpack, unpack.slot(), null);
            }
            case KitBuilder.Fill fill -> {
                return startHandling(Job.KitFill, fill.slot(), null);
            }
            case KitBuilder.Done done -> kitBuilder.finish();
            case KitBuilder.Abort abort -> kitBuilder.suspend(abort.reason());
        }
        return false;
    }

    // Order work: the carry slots holding what an order took out of the stash (with a copy of the stack, to tell it from
    // something the player put there since), and the container each came from
    private final Map<Integer, ItemStack> orderHeld = new java.util.LinkedHashMap<>();
    private final Map<Integer, Box> heldFrom = new HashMap<>();
    // Slots nowhere could be found for, so it does not try over and over; told once
    private final Set<Integer> returnGaveUp = new HashSet<>();
    private final Set<Integer> returnWarned = new HashSet<>();
    private int returnGaveUpVersion;
    private boolean returning;
    // When the bot last worked on an order, and last did any job (in ticks of the clock)
    private int lastOrderWork = -100000, lastOwnJob = -100000;

    /** For the info string: leftovers of an order are being put back. */
    boolean returningLeftovers() {
        return returning && !sorting();
    }

    private boolean orderWork() {
        return orderKit() || orderRunner.running() || crafter.active();
    }

    private void noteOrderHeld(int slot, ItemStack baseline) {
        if (slot < 0 || !orderWork()) return;
        orderHeld.put(slot, baseline.copy());
        if (target != null && (job == Job.KitTake || job == Job.Fetch || job == Job.CraftTake)) heldFrom.put(slot, target);
    }

    /** A shulker of an order picked up again after it was placed: it keeps where it came from. */
    private void noteCollected(int from, int slot) {
        if (slot < 0 || !orderWork()) return;
        ItemStack there = mc.player.getInventory().getItem(slot);
        if (!there.isEmpty()) orderHeld.put(slot, there.copy());
        Box source = heldFrom.get(from);
        if (source != null) heldFrom.put(slot, source);
        // Kept with what is held from the stash, so it is still known after a restart
        if (!there.isEmpty()) stashHeld.put(slot, there.copy());
    }

    /** Whether the slot still holds what the order put there (shulkers change inside, loose stacks shrink). */
    private boolean stillOurs(int slot) {
        ItemStack now = mc.player.getInventory().getItem(slot);
        if (now.isEmpty()) return false;

        ItemStack was = orderHeld.get(slot);
        if (was == null) {
            orderHeld.put(slot, now.copy());
            return true;
        }
        boolean same = Utils.isShulker(now.getItem()) ? now.getItem() == was.getItem() : ItemStack.isSameItemSameComponents(now, was);
        if (!same) return false;
        orderHeld.put(slot, now.copy());
        return true;
    }

    /**
     * Carry slots with things the order took that nothing is going to use any more: not the shulker being built or the
     * finished one waiting for delivery.
     */
    private List<Integer> leftoverSlots() {
        if (mc.player == null) return List.of();
        orderHeld.keySet().removeIf(slot -> !stillOurs(slot));
        heldFrom.keySet().retainAll(orderHeld.keySet());
        returnGaveUp.retainAll(orderHeld.keySet());
        returnWarned.retainAll(orderHeld.keySet());
        if (returnGaveUpVersion != version) {
            returnGaveUp.clear();
            returnGaveUpVersion = version;
        }

        List<Integer> out = new ArrayList<>();
        for (int slot : orderHeld.keySet()) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (orderRunner.claims(stack) || kitBuilder.holds(slot) || crafter.holds(slot) || crafter.reserved(slot) || slot == anvilSlot || returnGaveUp.contains(slot)) continue;
            out.add(slot);
        }
        return out;
    }

    /**
     * What is put back by itself: everything an order left over when not sorting; when sorting only the shulkers taken
     * from a known container (those go back where they came from, the rest is sorted like anything else).
     */
    private List<Integer> returnCandidates() {
        List<Integer> left = leftoverSlots();
        if (!sorting()) return left;
        left.removeIf(slot -> !Utils.isShulker(mc.player.getInventory().getItem(slot).getItem()) || !heldFrom.containsKey(slot));
        return left;
    }

    /** An order that is planned: put leftover shulkers back, then the next step of the order. @return true when a job was started */
    private boolean orderPriorityStep() {
        if (!sorting()) return scanOnlyOrderStep();

        List<Integer> back = returnCandidates();
        List<Integer> spent = spentSources(back);
        if (!spent.isEmpty() && returnLeftovers(spent)) {
            returning = true;
            return true;
        }
        returning = !back.isEmpty() && !kitBuilder.active() && !orderKit() && !crafter.active();
        if (returning && returnLeftovers(back)) return true;
        return (kitBuilder.active() || crafter.active() || activeCarried().isEmpty()) && runOrder(orderRunner.next());
    }

    /** Of these held slots, the shulkers the order kit being built is done with. */
    private List<Integer> spentSources(List<Integer> held) {
        if (!kitBuilder.active() || !orderKit()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (int slot : held) if (kitBuilder.sourceSpent(slot)) out.add(slot);
        return out;
    }

    /** What may be put back now: while the kit is built only the shulkers it is done with, else every leftover. */
    private List<Integer> returnPool() {
        List<Integer> candidates = returnCandidates();
        return kitBuilder.active() ? spentSources(candidates) : candidates;
    }

    /** Not sorting: carries on with the order, or puts leftovers back. @return true when a job was started */
    private boolean scanOnlyOrderStep() {
        if (orderKit() && !orderRunner.running()) kitBuilder.dropOrderKit();

        List<Integer> left = returnCandidates();

        // A source shulker with nothing more the kit needs goes back at once, while the kit is still being built
        List<Integer> spent = spentSources(left);
        if (!spent.isEmpty() && returnLeftovers(spent)) {
            returning = true;
            return true;
        }
        returning = !left.isEmpty() && !kitBuilder.active() && !crafter.active();

        if (orderRunner.running() && (kitBuilder.active() || crafter.active() || left.isEmpty()) && runOrder(orderRunner.next())) return true;
        return returning && returnLeftovers(left);
    }

    /** Whether a container could take this stack right now. */
    private boolean roomFor(Box box, ItemStack stack) {
        if (box.contents == null) return false;
        for (ItemStack here : box.contents) {
            if (here.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(here, stack) && here.getCount() < here.getMaxStackSize()) return true;
        }
        return false;
    }

    private boolean returnable(Box box, ItemStack stack) {
        return returnable(box, stack, false);
    }

    /**
     * @param home the container the stack was taken from: a kit shulker may go back into a kit-reserved chest it came from, but no
     *             reserved chest is ever a fallback for anything
     */
    private boolean returnable(Box box, ItemStack stack, boolean home) {
        return box != null && stash.boxes.contains(box) && box.known() && !box.stale && !box.excluded && !box.delivery && !box.dropOff && !box.unreachable
            && !Kits.isKitBox(box) && (!kitReserved(box) || (home && kitRoutedTo(box, stack))) && box.labels.stream().noneMatch(Categories::isKeepWhole)
            && !blocked(box, Job.Return) && !signPending(box) && roomFor(box, stack);
    }

    /**
     * Starts putting the first leftover back: in the container it was taken from if that has room, else the nearest
     * container that has.
     */
    private boolean returnLeftovers(List<Integer> left) {
        int slot = left.get(0);
        ItemStack stack = mc.player.getInventory().getItem(slot);

        Box home = heldFrom.get(slot);
        // A shulker emptied by taking its items does not go back where it came from if it is misplaced there: it goes where
        // empty shulkers belong, else the nearest container that takes it
        boolean emptied = Utils.isShulker(stack.getItem()) && !stack.has(DataComponents.CUSTOM_NAME) && emptyShulker(stack);
        if (emptied && home != null && !destinationsFor(stack).contains(home)) home = null;
        if (!returnable(home, stack, true)) {
            if (emptied) {
                home = nearest(destinationsFor(stack).stream().filter(b -> returnable(b, stack)).toList());
                if (home == null) home = nearest(stash.boxes.stream().filter(b -> returnable(b, stack)).toList());
            }
            else {
                // Shulkers are only ever put back where they came from when sorting; the rest is sorted
                home = sorting() ? null : nearest(stash.boxes.stream().filter(b -> returnable(b, stack)).toList());
            }
        }
        if (home == null) {
            returnGaveUp.add(slot);
            if (returnWarned.add(slot)) alert("Nowhere in the stash has room for %s I'm carrying from an order, keeping it in your inventory.", StackInfo.label(stack));
            return false;
        }
        return start(Job.Return, home);
    }

    /** Puts one leftover into the open container. */
    private boolean returnOne() {
        if (!mc.player.containerMenu.getCarried().isEmpty()) return recoverBounded();

        for (int slot : returnPool()) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!canFit(stack)) continue;
            // A kit-reserved chest takes back only a kit routed to it, whatever else is in the pool
            if (kitReserved(target) && !kitRoutedTo(target, stack)) continue;
            InvUtils.shiftClick().slotId(inventoryToMenu(slot, target.contents.length));
            return true;
        }
        return false;
    }

    // Driven by stash-manager: the loop runs without the module being turned on, to read containers, take orders and
    // put leftovers back. Never to sort.

    private boolean driven;
    private boolean explorePending;

    /** The loop is running: the module is on, or stash-manager is driving it. */
    private boolean running() {
        return isActive() || driven;
    }

    /** Sorting, compacting, labelling, saved kits and ground pickup: only when the module itself is on and sort-items is. */
    private boolean sorting() {
        return isActive() && sortItems.get();
    }

    // An order run by this client from the Stash Window (LocalOrders), no server: orders are wanted like stash-manager's accept-orders
    private boolean localOrders;

    void localOrders(boolean on) {
        localOrders = on;
    }

    private boolean ordersWanted() {
        if (localOrders) return true;
        StashManager manager = meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
        return manager != null && manager.takingOrders();
    }

    private void turnOff() {
        if (isActive()) toggle();
        else if (driven) {
            driven = false;
            teardown();
        }
    }

    /** Containers waiting to be read: not read yet, or stale. */
    boolean needsReading() {
        if (stash == null) return false;
        if (explorePending && stash.hasArea() && BARITONE) return true;
        return stash.boxes.stream().anyMatch(b -> !b.excluded && !b.foreign && !b.unreachable && !failedSurvey.contains(b) && (!b.known() || b.stale));
    }

    /** stash-manager is driving the loop. */
    boolean driving() {
        return driven;
    }

    int staleCount() {
        return staleBoxes().size();
    }

    /**
     * stash-manager's call, every tick: whether it needs the loop. When the module is on it runs by itself and this does
     * nothing. Starting is the same set-up as turning the module on (minus what is only for a person doing it), and
     * stopping the same tear-down, without the module's switch ever moving.
     */
    void drive(boolean want) {
        if (isActive()) {
            driven = false;
            return;
        }
        if (mc.player == null || mc.level == null) {
            driven = false;
            setUp = false;
            return;
        }

        driveBlocked = null;
        if (want) driveBlocked = notInStashWorld();
        if (driveBlocked != null) want = false;

        if (want && !driven) {
            driven = true;
            setup();
        }
        else if (!want && (driven || setUp)) {
            driven = false;
            teardown();
        }
    }

    // Set up and not yet torn down, driven or not
    private boolean setUp;
    // Why the loop can't be driven now, for the blocker
    private String driveBlocked;

    /**
     * Why the player is not where the stash is, or null: no stash, another dimension, or (when the stash does not know its
     * dimension yet) too far from every container it knows.
     */
    private String notInStashWorld() {
        if (stash == null) return "no stash";
        String dim = !stash.dimension.isEmpty() ? stash.dimension : stash.areaDimension;
        if (!dim.isEmpty()) return dim.equals(Stash.currentDimension()) ? null : "wrong dimension";

        Vec3 at = mc.player.position();
        for (Box box : stash.boxes) {
            if (box.known() && at.distanceToSqr(Vec3.atCenterOf(box.pos)) <= 128 * 128) return null;
        }
        return "too far from the stash";
    }

    /** stash-manager's setting: orders may unpack shulkers with a custom name. */
    private static boolean namedForOrders() {
        StashManager manager = meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
        return manager == null || manager.useNamedShulkers();
    }

    /** The kit being built is a transient one made for an order. */
    private boolean orderKit() {
        return kitBuilder.kit() != null && kitBuilder.kit().exact;
    }

    private boolean ordersOn() {
        return running() && shulkersOk();
    }

    private boolean runOrder(OrderRunner.Intent intent) {
        return switch (intent) {
            case OrderRunner.Idle idleStep -> false;
            case OrderRunner.Build build -> runKit(build.intent());
            case OrderRunner.Fetch fetch -> fetch(fetch.box(), fetch.slot());
            case OrderRunner.Deliver deliver -> {
                recheckOnly = false;
                lastDeliveryBox = deliver.box();
                yield start(Job.Deliver, deliver.box());
            }
            case OrderRunner.Expand expand -> startExpand();
            case OrderRunner.Recheck recheck -> startRecheck(recheck.box());
            case OrderRunner.Rename rename -> startName(rename);
            case OrderRunner.Craft craft -> startOrderCraft(craft);
            case OrderRunner.FindAnvil find -> startFindAnvil();
            case OrderRunner.Repair repair -> startRepair(repair);
            case OrderRunner.CraftChunk chunk -> startOrderChunk(chunk);
        };
    }

    // Crafting (Job.Craft). The table is no Box: it is never read, sorted or indexed
    private int chunkJobLimit;
    private static final int CRAFT_JOB_TICKS = 20 * 180, CRAFT_OPEN_TICKS = 20 * 15, CRAFT_OPEN_TRIES = 4, CRAFT_OPEN_RETRY = 40;
    private static final int CRAFT_COOLDOWN_TICKS = 20 * 60 * 5;
    // The reasons crafting is refused or given up on, worded as the proxy plugin has them
    private static final String TABLE_GONE = "the crafting table is gone",
        TABLE_NOT_REACHED = "couldn't reach the crafting table", TABLE_NOT_OPENED = "couldn't open the crafting table",
        TABLE_CLOSED = "the crafting table closed before it was done", TABLE_OTHER_DIMENSION = "the crafting table is in another dimension";
    private static final String SHULKER_BOX_ID = "minecraft:shulker_box";
    private BlockPos craftPos;
    private String craftFailure;
    private boolean craftDone, craftForOrder;
    private int craftJobTicks, craftOpenTries, craftOpenClickAt, craftDrainTries;
    // A table that failed, for which order: later crafts of that order don't try it again
    private final com.porter.qwerte.craft.CraftHealth craftHealth = new com.porter.qwerte.craft.CraftHealth();
    // How long the window has been kept open for lack of room to empty the grid, and whether the order was told
    private int craftStuckTicks;
    private boolean craftStuckReported;
    // Compaction asked for a craft that failed: not asked again for a while
    private int craftCooldownUntil;
    private String craftCooldownWhy = "";

    private boolean craftingOn() {
        StashManager manager = meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
        return manager != null && manager.craftingEnabled();
    }

    private boolean isTable(BlockPos pos) {
        return mc.level.getBlockState(pos).is(Blocks.CRAFTING_TABLE);
    }

    /** Why nothing can be crafted now (no table, it is gone, it failed for this order), or null. */
    private String craftBlocker() {
        if (stash == null) return TableFinder.NONE;
        if (mc.level == null || mc.player == null) return "no world";
        // Nothing is shipped: without the fetched (or game-built) recipe table nothing is crafted
        if (Crafter.recipes() == null) return Crafter.recipesPending() ? Crafter.WAITING_FOR_RECIPES : Crafter.NO_RECIPES;
        if (!stash.dimension.isEmpty() && !stash.dimension.equals(Stash.currentDimension())) return TABLE_OTHER_DIMENSION;
        // A table that is set or remembered and still there: only trouble it caused for this order stops the craft
        if (!tableMissing()) return craftHealth.problem(orderRunner.orderId(), stash.craftingTablePos);
        // None, or it is gone: crafting goes on when one can be found, taken from the stash or crafted (nothing is done here)
        TableFinder.Decision d = tablePreCheck.get(System.currentTimeMillis(), TABLE_PRECHECK_MS, this::decideTable);
        return d.step() == TableFinder.Step.FAIL ? d.failure() : null;
    }

    /** No crafting table is set or remembered, or the one that was is gone. */
    private boolean tableMissing() {
        if (stash == null || stash.craftingTablePos == null) return true;
        return mc.level != null && mc.level.isLoaded(stash.craftingTablePos) && !isTable(stash.craftingTablePos);
    }

    /** For the heartbeat: a crafting table is set and, where its chunk is loaded, it is one. */
    boolean craftingTableReady() {
        Stash s = ensureStash();
        if (s == null || s.craftingTablePos == null || mc.level == null) return false;
        return !mc.level.isLoaded(s.craftingTablePos) || isTable(s.craftingTablePos);
    }

    /** For the UI (the outline, the window's setup): the user's own table, never the website's that stands in for it during a craft chunk. */
    BlockPos craftingTablePos() {
        Stash s = ensureStash();
        return s == null ? null : s.tableSwap.persistedPos(s.craftingTablePos);
    }

    /** The website's table is standing in for the user's own right now (a craft chunk): changes to the user's table wait. */
    boolean websiteTableInUse() {
        return stash != null && stash.tableSwap.engaged();
    }

    /** For the heartbeat: where the table in use came from: "set", "found", "placed", "crafted" or "none". */
    String craftingTableSource() {
        Stash s = ensureStash();
        BlockPos pos = s == null ? null : s.tableSwap.persistedPos(s.craftingTablePos);
        if (pos == null) return "none";
        String source = s.tableSwap.persistedSource(s.craftingTableSource);
        return source.isEmpty() ? "set" : source;
    }

    /**
     * For stash-manager: sets the crafting table, or clears it if it is this one.
     * @return 1 when set, 0 when cleared, -1 when the block is no crafting table, -2 when no stash is loaded
     */
    int toggleCraftingTable(BlockPos pos) {
        if (mc.level == null || ensureStash() == null) return -2;
        // The website's table is standing in for the user's own: a change now would be lost when it is swapped back
        if (stash.tableSwap.engaged()) return -3;
        if (stash.craftingTablePos != null && stash.craftingTablePos.equals(pos)) {
            stash.craftingTablePos = null;
            stash.craftingTableSource = "";
            stash.save();
            return 0;
        }
        if (!isTable(pos)) return -1;
        stash.craftingTablePos = pos.immutable();
        stash.craftingTableSource = "set";
        tableChanged();
        stash.save();
        return 1;
    }

    /** For stash-manager: no crafting table any more. @return false when there was none or no stash */
    boolean clearCraftingTable() {
        if (ensureStash() == null || stash.tableSwap.engaged() || stash.craftingTablePos == null) return false;
        stash.craftingTablePos = null;
        stash.craftingTableSource = "";
        stash.save();
        return true;
    }

    // Craft orders (0.4.0-dev.18, CRAFT-CONTRACT.md): the website's crafting table for this stash, as the server last sent it, and what the
    // heartbeat says about it. Nothing here moves the bot or clicks: it only reads the world.
    private BlockPos websiteTableSeen;
    private long seenScanAt = Long.MIN_VALUE / 2;
    private List<String> seenCache = List.of();
    private static final long SEEN_SCAN_MS = 10 * 60_000L, SEEN_RESCAN_EMPTY_MS = 30_000L;

    /** The craft settings cached for the loaded stash, or null when nothing was heard from the server. */
    com.porter.qwerte.stash.CraftSettings craftSettings() {
        Stash s = ensureStash();
        return s == null ? null : com.porter.qwerte.stash.CraftStore.shared().get(StashIndex.clamp(s.server, 256), StashIndex.clamp(s.name, 256));
    }

    /**
     * This build can carry out craft orders now: its runner exists ({@link com.porter.qwerte.craft.CraftOrder#EXECUTES}), crafting is on and
     * a recipe table is held. The heartbeat and the claim say so; otherwise the server never gives this bot a craft order.
     */
    boolean craftOrdersCapable() {
        StashManager manager = meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
        if (!craftingOn() || Crafter.recipes() == null) return false;
        return com.porter.qwerte.craft.CraftCapability.capable(true, manager != null && manager.craftOrdersOn(), true, com.porter.qwerte.craft.CraftAuto.tableOk(autoState()));
    }

    // Craft chunks (0.4.0-dev.18): the website's table stands in for the stash's own while a chunk is crafted, and is always put back

    private Stash swappedStash, swappedCapStash;
    private boolean swappedSide;
    private final com.porter.qwerte.craft.CraftTableCap tableCap = new com.porter.qwerte.craft.CraftTableCap();

    /**
     * Why the website's crafting table can't be used for a craft chunk now (a CraftOrder reason): none set, outside the stash's dimension or area
     * margin (checked before the bot walks anywhere), not a crafting table where it is loaded, or trouble reaching it earlier in this order.
     */
    private String craftTableProblem() {
        com.porter.qwerte.stash.CraftSettings cs = craftSettings();
        com.porter.qwerte.stash.CraftSettings.Table t = cs == null ? null : cs.table();
        if (t == null) return autoTableProblem();
        if (ensureStash() == null || mc.level == null || mc.player == null) return com.porter.qwerte.craft.CraftOrder.TABLE_BLOCKED;
        BlockPos pos = new BlockPos(t.x(), t.y(), t.z());
        boolean loaded = mc.level.isLoaded(pos);
        String problem = com.porter.qwerte.craft.CraftTable.problem(t, stash.dimension, craftBounds(), Stash.currentDimension(), loaded, loaded && isTable(pos));
        if (problem == null && craftHealth.problem(orderRunner.orderId(), pos) != null) problem = com.porter.qwerte.craft.CraftOrder.TABLE_BLOCKED;
        return problem;
    }

    /** The stash cannot hold a bot table to limits (a CraftAuto code): no area, no recorded dimension, or the bot is in another dimension. Null when it can. */
    private String autoConfigCode() {
        if (ensureStash() == null || mc.level == null || mc.player == null) return com.porter.qwerte.craft.CraftAuto.NO_AREA;
        if (craftBounds() == null) return com.porter.qwerte.craft.CraftAuto.NO_AREA;
        if (stash.dimension.isEmpty()) return com.porter.qwerte.craft.CraftAuto.NO_DIMENSION;
        if (!com.porter.qwerte.stash.CraftBounds.sameDimension(Stash.currentDimension(), stash.dimension)) return com.porter.qwerte.craft.CraftAuto.WRONG_DIMENSION;
        return null;
    }

    /** The CraftOrder reason for a failure code of the auto table: a stash that can't hold one is no_table, the rest table_blocked or no_table by what failed. */
    private static String autoReason(String code) {
        return switch (code) {
            case com.porter.qwerte.craft.CraftAuto.NO_AREA, com.porter.qwerte.craft.CraftAuto.NO_DIMENSION, com.porter.qwerte.craft.CraftAuto.NO_MATERIAL -> com.porter.qwerte.craft.CraftOrder.NO_TABLE;
            default -> com.porter.qwerte.craft.CraftOrder.TABLE_BLOCKED;
        };
    }

    /**
     * Why no table can be had with none set on the website (a CraftOrder reason), or null when one may be found, placed or crafted. A try that
     * failed a short while ago is not made again at once (the heartbeat says why).
     */
    private String autoTableProblem() {
        if (ensureStash() == null || mc.level == null || mc.player == null) return com.porter.qwerte.craft.CraftOrder.TABLE_BLOCKED;
        String config = autoConfigCode();
        if (config != null) return autoReason(config);
        String recent = currentAutoWhy();
        return recent == null ? null : autoReason(recent);
    }

    /** What {@link #autoTable} says to do next. */
    private static final String AUTO_GATHER = "<gather>", AUTO_PLACING = "<placing>";

    /**
     * With no website table: a table the bot can use. The user's own table (hand-set) is used for orders when it is within the limits; when it
     * is out of them, orders use a table of their own (a slot of the stash, persisted) that never overwrites it. A table the bot picked that has
     * got out of the limits is forgotten, a missing one is found nearby, taken out of the stash and placed, or crafted from planks or logs and
     * placed ({@link #prepareTable}, the shulker crafting's own), at most one table per order and three an hour.
     * @return null when a table is ready, {@link #AUTO_GATHER} or {@link #AUTO_PLACING} when a session or a job began, else the failure text
     */
    private String autoTable() {
        String config = autoConfigCode();
        if (config != null) return com.porter.qwerte.craft.CraftTable.failureText(autoReason(config));
        tableForChunk = true;
        if (stash != swappedCapStash) {
            swappedCapStash = stash;
            tableCap.load(stash.craftTables);
        }
        // A hand-set table out of the limits stays as it is (shulker crafting still uses it); the order table stands in for it for the chunk
        if (sideMode() && !stash.orderSlotInUse()) {
            stash.useOrderSlot();
            swappedStash = stash;
            swappedSide = true;
            tableChanged();
        }
        if (knownTableOutOfLimits()) {
            // A table the bot picked earlier that is out of bounds is let go, and the steps are run again
            stash.craftingTablePos = null;
            stash.craftingTableSource = "";
            stash.save();
            tableChanged();
        }
        if (!tableMissing()) {
            autoWhy.cleared();
            return null;
        }
        // A table is put down only so often (it is a block in the world): once per order, three an hour
        TableFinder.Decision d = decideTable(true);
        boolean puts = d.step() == TableFinder.Step.PLACE_FROM_STOCK || d.step() == TableFinder.Step.CRAFT;
        if (puts && tableItemSlot(false) < 0 && !tableCap.allows(orderRunner.orderId(), System.currentTimeMillis())) {
            // The cap is its own reason: not a failure of the auto table, so nothing is remembered and the heartbeat's tableWhy stays null
            com.porter.qwerte.QwerteAddon.LOG.info("orders: table cap reached ({} per order, {} an hour): no table is put down for this order", com.porter.qwerte.craft.CraftTableCap.PER_ORDER, com.porter.qwerte.craft.CraftTableCap.PER_HOUR);
            return com.porter.qwerte.craft.CraftTable.failureText(com.porter.qwerte.craft.CraftOrder.TABLE_BLOCKED);
        }
        return switch (prepareTable(TableResume.Order, true)) {
            case READY -> {
                autoWhy.cleared();
                yield null;
            }
            case GATHERING -> AUTO_GATHER;
            case PLACING -> AUTO_PLACING;
            default -> {
                autoFailed(tableCode);
                yield tableWhy;
            }
        };
    }

    /** @return null when the website's table now stands in for ours, else the crafter's words for why not */
    private String engageWebsiteTable() {
        // The order table's slot (or an earlier swap) goes back first: the website's table must never be copied into the order table's slot
        if (swappedStash != null) releaseWebsiteTable();
        String problem = craftTableProblem();
        if (problem != null) return com.porter.qwerte.craft.CraftTable.failureText(problem);
        com.porter.qwerte.stash.CraftSettings.Table t = craftSettings().table();
        stash.overrideTable(new BlockPos(t.x(), t.y(), t.z()));
        swappedStash = stash;
        tableChanged();
        return null;
    }

    /** The user's own table is put back (a no-op when the website's is not in use). Safe to call any time. */
    private void releaseWebsiteTable() {
        if (swappedStash == null) return;
        swappedStash.restoreTable();
        swappedStash = null;
        swappedSide = false;
        tableChanged();
    }

    /** The order wants a chunk made at the website's table. The table is swapped in for the session and always swapped back out. */
    private boolean startOrderChunk(OrderRunner.CraftChunk chunk) {
        try {
            if (!crafter.active()) {
                com.porter.qwerte.stash.CraftSettings cs = craftSettings();
                boolean website = cs != null && cs.table() != null;
                // A website table has priority; without one the bot's own (found, placed or crafted) is used and kept for next time
                String bad = website ? engageWebsiteTable() : autoTable();
                if (AUTO_GATHER.equals(bad)) return runCraft(crafter.next());
                if (AUTO_PLACING.equals(bad)) return true;
                if (bad != null) {
                    orderRunner.craftEnded(new Crafter.Outcome(chunk.key(), 0, bad));
                    return false;
                }
                int chunkFills = 0;
                for (var step : chunk.plan().steps()) chunkFills += step.crafts();
                chunkJobLimit = 20 * (120 + 20 * chunkFills);
                String why = crafter.beginPlan(new Crafter.Request(chunk.key(), chunk.count(), "order " + orderRunner.orderId(), false, 0, java.util.Map::of, java.util.Set::of, false, true),
                    chunk.plan(), chunk.carried());
                if (why != null) {
                    orderRunner.craftEnded(new Crafter.Outcome(chunk.key(), 0, why));
                    return false;
                }
                craftForOrder = true;
            }
            return runCraft(crafter.next());
        } finally {
            if (!crafter.active() && !swappedSide) releaseWebsiteTable();
        }
    }

    /** The stash area as minX, minY, minZ, maxX, maxY, maxZ, or null when it has none (or it was set in another dimension than the stash). */
    private int[] craftBounds() {
        if (stash == null || !stash.hasCorners()) return null;
        if (!stash.areaDimension.isEmpty() && !com.porter.qwerte.stash.CraftBounds.sameDimension(stash.areaDimension, stash.dimension)) return null;
        BlockPos lo = stash.areaMin(), hi = stash.areaMax();
        return new int[] { lo.getX(), lo.getY(), lo.getZ(), hi.getX(), hi.getY(), hi.getZ() };
    }

    /** The website's table is not in the stash's dimension, or is more than 64 blocks outside the stash area: it is never walked to. */
    boolean craftTableOutOfBounds(com.porter.qwerte.stash.CraftSettings.Table t) {
        return stash == null || com.porter.qwerte.stash.CraftBounds.outOfBounds(stash.dimension, craftBounds(), t);
    }

    /**
     * The table the heartbeat reports (CRAFT-CONTRACT.md + the auto-table addition): the website's table when one is set (unset, ok, unloaded,
     * ...), else "auto" when the bot can find, place or craft one itself, else "unset". Never a position.
     */
    String craftTableStatus() {
        return com.porter.qwerte.craft.CraftAuto.status(autoState());
    }

    /** Why the table is not usable (a CraftAuto code), or null when it is. */
    String craftTableWhy() {
        return com.porter.qwerte.craft.CraftAuto.why(autoState());
    }

    /** The bot's own table in use, as "x,y,z" and where it came from (found, placed, crafted), or null. A position: the server hides it from streamers. */
    String[] botTable() {
        Stash st = ensureStash();
        if (st == null || st.tableSwap.engaged() || st.craftingTablePos == null) return null;
        String source = com.porter.qwerte.craft.CraftAuto.botSource(st.craftingTableSource);
        return source == null ? null : new String[] { st.craftingTablePos.getX() + "," + st.craftingTablePos.getY() + "," + st.craftingTablePos.getZ(), source };
    }

    // The auto table: with no website table the bot finds one nearby, takes one out of the stash and places it, or crafts one (TableFinder,
    // prepareTable), within the same limits as the website's: the stash's dimension, 64 blocks of its area, a height of -64 to 320
    private final com.porter.qwerte.craft.AutoWhy autoWhy = new com.porter.qwerte.craft.AutoWhy();

    /** The last try at a table on the order path failed for this reason (a CraftAuto code); said in the heartbeat for a while, and not tried again at once. */
    private void autoFailed(String code) {
        // Only on the order path: a shulker craft or the chest expansion failing to get a table is not a reason to stop taking craft orders
        autoWhy.failed(tableForChunk, code, System.currentTimeMillis());
    }

    private String currentAutoWhy() {
        return autoWhy.current(System.currentTimeMillis());
    }

    private boolean outOfLimits(BlockPos p) {
        return com.porter.qwerte.stash.CraftBounds.outOfBounds(stash.dimension, craftBounds(), new com.porter.qwerte.stash.CraftSettings.Table(p.getX(), p.getY(), p.getZ(), stash.dimension));
    }

    /** The table in use now (the live fields) is outside the limits of a bot table. */
    private boolean knownTableOutOfLimits() {
        return stash != null && stash.craftingTablePos != null && outOfLimits(stash.craftingTablePos);
    }

    /**
     * The user's own table was set by hand and is outside the limits: craft orders use a table of their own then (CraftingTableSpot's side mode in the
     * plugin), and the hand-set one is kept for shulker crafting. A table found, placed or crafted is not the user's: it is simply forgotten.
     */
    private boolean sideMode() {
        if (stash == null) return false;
        BlockPos pos = stash.tableSwap.persistedPos(stash.craftingTablePos);
        String source = stash.tableSwap.persistedSource(stash.craftingTableSource);
        return com.porter.qwerte.craft.CraftSide.decide(pos != null, source, pos != null && outOfLimits(pos)) == com.porter.qwerte.craft.CraftSide.Use.SLOT;
    }

    /** A table can be had without the website: a known one within the limits, a table found nearby, one in the stash, or planks or logs to craft one. */
    private boolean autoObtainable() {
        if (stash == null || mc.level == null) return false;
        TableFinder.Decision d = orderPreCheck.get(System.currentTimeMillis(), TABLE_PRECHECK_MS, () -> decideTable(true));
        return d.step() != TableFinder.Step.FAIL;
    }

    private com.porter.qwerte.craft.CraftAuto.State autoState() {
        com.porter.qwerte.stash.CraftSettings cs = craftSettings();
        boolean websiteSet = cs != null && cs.table() != null;
        if (ensureStash() == null) return new com.porter.qwerte.craft.CraftAuto.State(websiteSet, websiteSet ? websiteTableStatus() : "unset", false, false, false, null);
        boolean area = craftBounds() != null, dimension = !stash.dimension.isEmpty();
        return new com.porter.qwerte.craft.CraftAuto.State(websiteSet, websiteSet ? websiteTableStatus() : "unset", area, dimension,
            area && dimension && autoObtainable(), currentAutoWhy(), sideMode());
    }

    /** The state of the website's crafting table for the heartbeat (unset, ok, unloaded, ...). Never a position. */
    String websiteTableStatus() {
        com.porter.qwerte.stash.CraftSettings cs = craftSettings();
        com.porter.qwerte.stash.CraftSettings.Table t = cs == null ? null : cs.table();
        if (t == null) return StashIndex.TABLE_UNSET;
        if (ensureStash() == null || mc.level == null) return StashIndex.TABLE_UNLOADED;
        // A table outside the stash's dimension or far from the stash area is never "ok": the bot does not go there
        if (!stash.dimension.isEmpty() && !com.porter.qwerte.stash.CraftBounds.sameDimension(stash.dimension, t.dimension())) return StashIndex.TABLE_OTHER_DIMENSION;
        if (craftTableOutOfBounds(t)) return StashIndex.TABLE_UNREACHABLE;
        if (!com.porter.qwerte.stash.CraftBounds.sameDimension(Stash.currentDimension(), t.dimension())) return StashIndex.TABLE_OTHER_DIMENSION;
        BlockPos pos = new BlockPos(t.x(), t.y(), t.z());
        // Once the table was seen standing, leaving its chunk unloaded does not make it unknown again ("unloaded" is only for one never seen)
        if (!mc.level.isLoaded(pos)) return com.porter.qwerte.stash.CraftBounds.unloadedStatus(pos.equals(websiteTableSeen));
        if (!isTable(pos)) {
            websiteTableSeen = null;
            return StashIndex.TABLE_NOT_TABLE;
        }
        websiteTableSeen = pos;
        return StashIndex.TABLE_OK;
    }

    /** Crafting tables standing in the stash area, as the last search found them ("x,y,z", at most 8): the website's quick pick. Searched at most every 10 minutes. */
    List<String> seenTables() {
        long now = System.currentTimeMillis();
        // A search that found nothing is repeated sooner (a chunk may not have been loaded yet)
        if (now - seenScanAt < (seenCache.isEmpty() ? SEEN_RESCAN_EMPTY_MS : SEEN_SCAN_MS) || mc.level == null || mc.player == null || ensureStash() == null) return seenCache;
        seenScanAt = now;
        List<String> out = new ArrayList<>();
        try {
            for (BlockPos p : scanArea(st -> st.is(Blocks.CRAFTING_TABLE), true)) out.add(p.getX() + "," + p.getY() + "," + p.getZ());
        } catch (RuntimeException e) {
            return seenCache;
        }
        java.util.Collections.sort(out);
        seenCache = out.size() > StashIndex.MAX_SEEN ? new ArrayList<>(out.subList(0, StashIndex.MAX_SEEN)) : out;
        return seenCache;
    }

    // Finding a crafting table to craft at (before a craft that needs one). In order: the one picked by hand, a remembered one, a
    // placed table found in the stash area, one taken from the stash and placed, one crafted in the inventory's 2x2 grid and placed.
    // Nothing works: the craft fails with TableFinder.NONE. The placing is the anvil's (Job.AnvilPlace) with a table in hand.
    private enum TableResume { Order, Compaction, Expand }

    private enum TablePrep { READY, GATHERING, PLACING, FAILED }

    private final TableFinder.Throttle tableThrottle = new TableFinder.Throttle();
    private boolean tableSession, placingTable, tableCrafted, tableForChunk;
    private TableResume tableResume = TableResume.Order;
    // Why the table could not be had, as the failure text ("no crafting table available: <cause>"), and why placing failed
    private String tableWhy = TableFinder.NONE, placeWhy = "";
    // Why the table could not be had, as a CraftAuto code that travels with the failure text
    private String tableCode = com.porter.qwerte.craft.CraftAuto.NO_MATERIAL;
    // Slots that held a crafting table before the table session began: never the one placed
    private final Set<Integer> tablePre = new HashSet<>();
    // The pre-check of a craft (can a table be had?) is kept for a few seconds, the world and the stash are not combed on every ask
    private static final long TABLE_PRECHECK_MS = 3000;
    private final TableFinder.Memo<TableFinder.Decision> tablePreCheck = new TableFinder.Memo<>();
    // The same for craft orders: their table may come only from plain stacks, and from the order slot when the user's own is out of the limits
    private final TableFinder.Memo<TableFinder.Decision> orderPreCheck = new TableFinder.Memo<>();
    private int tableWaitTicks;

    /** The table position, or what it can be had from, changed: the kept answers are dropped. */
    private void tableChanged() {
        tableThrottle.reset();
        tablePreCheck.reset();
        orderPreCheck.reset();
    }

    private TableFinder.World tableWorld(boolean plain) {
        return new TableFinder.World() {
            @Override
            public boolean isTable(TableFinder.Pos p) {
                BlockPos at = new BlockPos(p.x(), p.y(), p.z());
                return !mc.level.isLoaded(at) || StashSorter.this.isTable(at);
            }

            @Override
            public TableFinder.Pos search() {
                long now = System.currentTimeMillis();
                TableFinder.Pos from = new TableFinder.Pos(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
                java.util.function.Supplier<TableFinder.Pos> look = () -> {
                    PlaceSearch.View view = placeView();
                    List<TableFinder.Pos> tables = new ArrayList<>();
                    for (BlockPos t : scanArea(st -> st.is(Blocks.CRAFTING_TABLE), true)) tables.add(new TableFinder.Pos(t.getX(), t.getY(), t.getZ()));
                    return TableFinder.nearest(tables, from, p -> {
                        BlockPos t = new BlockPos(p.x(), p.y(), p.z());
                        if (stash.hasArea() && !stash.inArea(t)) return false;
                        // One the bot can stand next to
                        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) {
                            if ((dx != 0 || dz != 0) && view.standable(t.getX() + dx, t.getY() + dy, t.getZ() + dz)) return true;
                        }
                        return false;
                    });
                };
                TableFinder.Pos hit = tableThrottle.get(now, look);
                // An answer from the last 10 seconds that is not a table any more is looked for again
                if (hit != null && !isTable(hit)) {
                    tableThrottle.reset();
                    hit = tableThrottle.get(now, look);
                }
                return hit;
            }

            @Override
            public boolean stockHasTable() {
                return orderRunner.craftPool(plain).getOrDefault(TableFinder.TABLE_ID, 0L) > 0;
            }

            @Override
            public TableFinder.Material craftMaterial() {
                StashManager manager = meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
                return TableFinder.material(Crafter.recipes(), orderRunner.craftPool(plain), manager == null ? Set.of() : manager.craftNeverUse());
            }
        };
    }

    private TableFinder.Decision decideTable() {
        return decideTable(false);
    }

    /**
     * @param orders the decision is for a craft order's table: plain stacks only, and the order slot when the user's own table is out of the
     *               limits. The shulker, compaction and chest flows keep the user's table and every stack.
     */
    private TableFinder.Decision decideTable(boolean orders) {
        BlockPos known = stash.craftingTablePos;
        String knownSource = stash.craftingTableSource;
        // With the user's hand-set table out of the limits the table of orders is the stash's order slot (when it is not standing in already)
        if (orders && !stash.tableSwap.engaged() && sideMode()) {
            known = stash.orderTablePos;
            knownSource = stash.orderTableSource;
        }
        TableFinder.Memory memory = known == null ? null
            : new TableFinder.Memory(new TableFinder.Pos(known.getX(), known.getY(), known.getZ()),
                TableFinder.Source.of(knownSource.isEmpty() ? "set" : knownSource));
        return TableFinder.decide(memory, tableWorld(orders));
    }

    /** What is in the inventory window's crafting grid and on its cursor, as text ("" when nothing). */
    private String gridLeftovers() {
        if (mc.player == null) return "";
        var menu = mc.player.inventoryMenu;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (!s.isEmpty()) sb.append(sb.length() > 0 ? ", " : "").append(s.getCount()).append("x ").append(s.getHoverName().getString());
        }
        ItemStack c = menu.getCarried();
        if (!c.isEmpty()) sb.append(sb.length() > 0 ? ", " : "").append(c.getCount()).append("x ").append(c.getHoverName().getString()).append(" (cursor)");
        return sb.toString();
    }

    private static boolean tableItem(ItemStack s) {
        return !s.isEmpty() && s.is(Items.CRAFTING_TABLE);
    }

    /** The inventory slot of a crafting table item gathered or crafted for placing, or -1. */
    private int tableItemSlot(boolean fromSession) {
        var inv = mc.player.getInventory();
        if (placingTable && anvilSlot >= 0 && tableItem(inv.getItem(anvilSlot))) return anvilSlot;
        if (!fromSession) return -1;
        // Only what the table session itself held, and never a slot that held a table before it began (an order line may need those)
        for (int slot : crafter.lastHeld()) {
            if (tableItem(inv.getItem(slot)) && !tablePre.contains(slot) && (stashHeld.containsKey(slot) || orderHeld.containsKey(slot))) return slot;
        }
        return -1;
    }

    /**
     * A craft needs a table and none is set or remembered: finds one placed, or starts getting one to place. The caller drives what
     * was started: GATHERING is a crafter session to be run, PLACING a job already started. When it is over {@link #tableAcquired}
     * tells whoever asked.
     */
    private TablePrep prepareTable(TableResume resume) {
        return prepareTable(resume, false);
    }

    /** @param chunk the table is for a craft-order chunk (plain stacks only, counted against the table cap, remembered when it fails) */
    private TablePrep prepareTable(TableResume resume, boolean chunk) {
        if (stash == null || mc.level == null || mc.player == null) return TablePrep.FAILED;
        tableResume = resume;
        tableForChunk = chunk;
        // A table taken out or crafted earlier and not yet placed (the job was interrupted)
        int held = tableItemSlot(false);
        if (held >= 0) tableCrafted = false;
        if (held >= 0 && startTablePlace(held)) return TablePrep.PLACING;

        TableFinder.Decision decision = decideTable(tableForChunk);
        if (decision.forget()) {
            info("The crafting table I knew is gone, looking for another.");
            stash.craftingTablePos = null;
            stash.craftingTableSource = "";
            stash.save();
            tableChanged();
        }
        tablePre.clear();
        var invNow = mc.player.getInventory();
        for (int i = 0; i < invNow.getContainerSize(); i++) if (tableItem(invNow.getItem(i))) tablePre.add(i);
        switch (decision.step()) {
            case USE -> {
                return TablePrep.READY;
            }
            case FOUND -> {
                stash.craftingTablePos = new BlockPos(decision.pos().x(), decision.pos().y(), decision.pos().z());
                stash.craftingTableSource = "found";
                stash.save();
                info("Found a crafting table in the stash area, using it for crafting from now on.");
                return TablePrep.READY;
            }
            case PLACE_FROM_STOCK -> {
                String why = crafter.begin(new Crafter.Request(TableFinder.TABLE_ID, 1, "placing", false, 0, orderRunner::craftPool, orderRunner::lotKeys, true, chunk));
                if (why != null) {
                    warning("No crafting table to place: %s.", why);
                    tableWhy = TableFinder.failure("couldn't take one from the stash: " + why);
                    tableCode = com.porter.qwerte.craft.CraftAuto.NO_MATERIAL;
                    return TablePrep.FAILED;
                }
                tableCrafted = false;
                info("No crafting table in the area, taking one out of the stash to place.");
            }
            case CRAFT -> {
                String why = crafter.begin(new Crafter.Request(TableFinder.TABLE_ID, 1, "placing", false, 0, orderRunner::craftPool, orderRunner::lotKeys, false, chunk));
                if (why != null) {
                    warning("No crafting table to craft: %s.", why);
                    tableWhy = TableFinder.failure("couldn't craft one: " + why);
                    tableCode = com.porter.qwerte.craft.CraftAuto.NO_MATERIAL;
                    return TablePrep.FAILED;
                }
                crafter.useInventoryGrid();
                tableCrafted = true;
                info("No crafting table in the area or the stash, crafting one from %s in the inventory grid.", decision.material() == TableFinder.Material.LOGS ? "logs" : "planks");
            }
            default -> {
                warning("%s.", decision.failure());
                tableWhy = decision.failure();
                tableCode = com.porter.qwerte.craft.CraftAuto.NO_MATERIAL;
                return TablePrep.FAILED;
            }
        }
        craftForOrder = resume == TableResume.Order;
        tableSession = true;
        return TablePrep.GATHERING;
    }

    private boolean startTablePlace(int slot) {
        return startPlaceItem(slot, true);
    }

    /** The table is there (or not): whoever asked is told, and carries on. */
    private void tableAcquired(boolean ok) {
        // Only a table put down for an order is remembered as a failure of the auto table (a shulker craft or the chest expansion are not)
        if (tableForChunk) {
            if (ok) autoWhy.cleared();
            else autoFailed(tableCode);
        }
        switch (tableResume) {
            case Order -> {
                if (ok) orderRunner.tableReady();
                else orderRunner.craftEnded(new Crafter.Outcome(SHULKER_BOX_ID, 0, tableWhy));
            }
            case Compaction -> {
                if (!ok) {
                    craftCooldownUntil = ticks + CRAFT_COOLDOWN_TICKS;
                    craftCooldownWhy = tableWhy;
                }
            }
            case Expand -> {
                if (ok) startExpand(true);
                else expandFailed("no chests left in the stash, and " + tableWhy);
            }
        }
    }

    private final Crafter crafter = new Crafter(new Crafter.Owner() {
        @Override
        public Stash stash() {
            return stash;
        }

        @Override
        public Set<Integer> carry() {
            return ownedCarry();
        }

        @Override
        public int freeCarrySlot() {
            return StashSorter.this.freeCarrySlot();
        }

        @Override
        public int freeCarryCount() {
            return StashSorter.this.freeCarryCount();
        }

        @Override
        public boolean usable(Box box) {
            return !blocked(box, Job.Take) && !blocked(box, Job.CraftTake) && !signPending(box) && !box.unreachable;
        }

        @Override
        public boolean keepWhole(Box box) {
            return box.labels.stream().anyMatch(Categories::isKeepWhole);
        }

        @Override
        public boolean namedShulkers() {
            return namedForOrders();
        }

        @Override
        public boolean claimed(ItemStack shulker) {
            return Kits.claims(shulker) || orderRunner.claims(shulker) || kitBuilder.heldKit(shulker) != null;
        }

        @Override
        public void held(int slot, ItemStack stack) {
            stashHeld.put(slot, stack.copy());
            noteOrderHeld(slot, stack);
        }

        @Override
        public double distance(Box box) {
            return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(nearestPart(box)));
        }

        @Override
        public Set<String> neverUse() {
            StashManager manager = meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
            return manager == null ? Set.of() : manager.craftNeverUse();
        }

        @Override
        public com.porter.qwerte.craft.CraftPlanner.Caps caps() {
            StashManager manager = meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
            return manager == null ? com.porter.qwerte.craft.CraftPlanner.Caps.NONE : manager.craftCaps();
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }
    });

    /** Starts the step the crafter asks for. @return true when a job was started */
    private boolean runCraft(Crafter.Intent intent) {
        switch (intent) {
            case Crafter.Take take -> {
                if (start(Job.CraftTake, take.box())) return true;
                crafter.abort("couldn't reach " + take.box().pos.toShortString());
            }
            case Crafter.Fetch fetch -> {
                if (fetch(fetch.box(), fetch.slot())) return true;
                crafter.abort("couldn't reach " + fetch.box().pos.toShortString());
            }
            case Crafter.Unpack unpack -> {
                if (shulkersOk()) return startHandling(Job.CraftUnpack, unpack.slot(), null);
                crafter.abort("placing shulkers failed lately, so no shulker work for a few minutes");
            }
            case Crafter.Table table -> {
                return startCraftTable();
            }
            case Crafter.Done done -> { }
            case Crafter.Abort abort -> crafter.abort(abort.reason());
        }
        endCraft();
        return false;
    }

    /** The crafting session is over: the order (or the compactor) is told how it went. */
    private void endCraft() {
        Crafter.Outcome outcome = crafter.finish();
        // The session is over: the user's own crafting table is ours again (the order table stays in while its table is being put down)
        if (!swappedSide) releaseWebsiteTable();
        chunkJobLimit = 0;
        if (tableSession) {
            // The crafting table was only gathered or crafted: it is placed now
            tableSession = false;
            craftForOrder = false;
            int slot = outcome.failure() == null ? tableItemSlot(true) : -1;
            if (slot < 0 || !startTablePlace(slot)) {
                String cause = outcome.failure() == null ? "the crafting table couldn't be taken" : outcome.failure();
                warning("No crafting table to place: %s.", cause);
                tableWhy = TableFinder.failure(cause);
                tableCode = cause.contains("reach") || cause.contains("open") ? com.porter.qwerte.craft.CraftAuto.PATH_FAILED : com.porter.qwerte.craft.CraftAuto.NO_MATERIAL;
                tableAcquired(false);
            }
            // What the session held is not looked at again
            crafter.clearLastHeld();
            version++;
            idle = false;
            return;
        }
        if (chestSession) {
            // The chests for the delivery storage were only gathered or crafted: they are put up now
            chestSession = false;
            craftForOrder = false;
            ChestStack.Result res = outcome.failure() == null ? stackPlan() : null;
            if (outcome.failure() != null) {
                crafter.clearLastHeld();
                expandFailed("no chests: " + outcome.failure());
            }
            else if (res.plan() == null) expandFailed(res.blocked());
            else if (!startChestPlace(res.plan())) expandFailed("it couldn't be put up");
            version++;
            idle = false;
            return;
        }
        if (anvilSession) {
            // The anvil was only gathered: it is placed now
            anvilSession = false;
            craftForOrder = false;
            int slot = outcome.failure() == null ? anvilItemSlot() : -1;
            if (slot < 0 || !startAnvilPlace(slot)) {
                crafter.clearLastHeld();
                warning("No anvil to place: %s.", outcome.failure() == null ? "the anvil couldn't be taken" : outcome.failure());
                orderRunner.anvilFound(false);
            }
            version++;
            idle = false;
            return;
        }
        if (craftForOrder) orderRunner.craftEnded(outcome);
        else {
            if (outcome.failure() != null) {
                craftCooldownUntil = ticks + CRAFT_COOLDOWN_TICKS;
                craftCooldownWhy = outcome.failure();
                warning("Couldn't craft a shulker box: %s.", outcome.failure());
            }
            else if (outcome.crafted() > 0) info("Crafted %d Shulker Box.", outcome.crafted());
        }
        craftForOrder = false;
        crafter.clearLastHeld();
        version++;
        idle = false;
    }

    /** The order wants this many plain shulker boxes crafted. */
    private boolean startOrderCraft(OrderRunner.Craft craft) {
        if (!crafter.active()) {
            // No table set or remembered: one is found, placed from the stash or crafted first (the order carries on after)
            if (stash != null && tableMissing()) {
                // The craft itself must be possible before a table is put in the world for it
                var preview = crafter.preview(SHULKER_BOX_ID, craft.count(), orderRunner.crafted(), orderRunner.craftPool(), false);
                if (!preview.ok()) {
                    orderRunner.craftEnded(new Crafter.Outcome(SHULKER_BOX_ID, 0, preview.refusal()));
                    return false;
                }
                switch (prepareTable(TableResume.Order)) {
                    case GATHERING -> {
                        return runCraft(crafter.next());
                    }
                    case PLACING -> {
                        return true;
                    }
                    case FAILED -> {
                        orderRunner.craftEnded(new Crafter.Outcome(SHULKER_BOX_ID, 0, tableWhy));
                        return false;
                    }
                    default -> { }
                }
            }
            String why = crafter.begin(new Crafter.Request(SHULKER_BOX_ID, craft.count(), "order " + orderRunner.orderId(), true, orderRunner.crafted(), orderRunner::craftPool, orderRunner::lotKeys, false));
            if (why != null) {
                orderRunner.craftEnded(new Crafter.Outcome(SHULKER_BOX_ID, 0, why));
                return false;
            }
            craftForOrder = true;
        }
        return runCraft(crafter.next());
    }

    /** Compaction has no empty shulker: crafts one. @return null when started, "" when crafting is off, else why not */
    private String craftEmptyForCompaction(String purpose) {
        if (!craftingOn()) return "";
        if (ticks < craftCooldownUntil) return craftCooldownWhy;
        if (crafter.active()) return null;
        String why = craftBlocker();
        if (why == null && tableMissing()) {
            // The craft itself must be possible before a table is put in the world for it
            var preview = crafter.preview(SHULKER_BOX_ID, 1, 0, crafter.survey(), false);
            if (!preview.ok()) why = preview.refusal();
            else {
                switch (prepareTable(TableResume.Compaction)) {
                    case GATHERING, PLACING -> {
                        return null;
                    }
                    case FAILED -> why = tableWhy;
                    default -> { }
                }
            }
        }
        if (why == null) why = crafter.begin(new Crafter.Request(SHULKER_BOX_ID, 1, purpose, false, 0, crafter::survey));
        if (why != null) {
            craftCooldownUntil = ticks + CRAFT_COOLDOWN_TICKS;
            craftCooldownWhy = why;
            return why;
        }
        craftForOrder = false;
        return null;
    }

    private boolean startCraftTable() {
        if (crafter.inventoryGrid()) return startInventoryCraft();
        String bad = craftBlocker();
        BlockPos table = stash == null ? null : stash.craftingTablePos;
        // The table was cleared (from the website) or forgotten while the session ran
        if (table == null) {
            crafter.tableEnded(TableFinder.failure("it was cleared while crafting"));
            return false;
        }
        if (bad == null && tableMissing()) bad = TABLE_GONE;
        if (bad == null && !walkOn() && !inReachOf(table)) bad = TABLE_NOT_REACHED;
        if (bad != null) {
            crafter.tableEnded(bad);
            return false;
        }

        craftPos = table;
        craftFailure = null;
        craftDone = false;
        craftJobTicks = craftOpenTries = craftOpenClickAt = craftDrainTries = 0;
        craftStuckTicks = 0;
        craftStuckReported = false;

        job = Job.Craft;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;
        swapStep = 0;
        step(craftForOrder ? "order: crafting at the crafting table; next: carry on with the order." : "crafting: at the crafting table; next: carry on.");

        if (inReachOf(craftPos)) step = Step.Opening;
        else {
            walkNear(craftPos, 2);
            // The table may be outside the stash area: that is not a reason to refuse the way
            walkInside = false;
            step = Step.Walking;
        }
        return true;
    }

    /** Crafts in the 2x2 grid of the inventory window: no table, no walking, no window to open. */
    private boolean startInventoryCraft() {
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            if (!ourMenu(mc.player.containerMenu)) {
                crafter.tableEnded("another window is open");
                return false;
            }
            if (!mc.player.containerMenu.getCarried().isEmpty()) recoverCursor();
            closeOurWindow(mc.player.containerMenu);
        }
        craftPos = null;
        craftFailure = null;
        craftDone = false;
        craftJobTicks = craftOpenTries = craftOpenClickAt = craftDrainTries = 0;
        craftStuckTicks = 0;
        tableWaitTicks = 0;
        craftStuckReported = false;

        job = Job.Craft;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;
        swapStep = 0;
        step("crafting: a crafting table in the inventory grid; next: place it.");
        step = Step.Working;
        wait = delay.get();
        return true;
    }

    private void craftTick() {
        stepTicks++;
        lastOwnAct = ticks;
        if (step == Step.Working) craftJobTicks++;
        // A craft-order chunk gets what its plan needs: 2 minutes and 20 seconds a fill (CRAFT-CONTRACT.md), at least the usual
        int jobLimit = Math.max(CRAFT_JOB_TICKS, chunkJobLimit);
        if (craftJobTicks > jobLimit + 400) {
            if (craftFailure == null) craftFailure = "crafting didn't finish";
            craftEnd();
            return;
        }
        if (craftJobTicks > jobLimit && craftFailure == null) craftFailure = "crafting didn't finish";
        switch (step) {
            case Walking -> craftWalk();
            case Opening -> craftOpen();
            case Working -> craftWork();
        }
    }

    private void craftWalk() {
        if (mc.level.isLoaded(craftPos) && !isTable(craftPos)) {
            craftFailure = TABLE_GONE;
            craftEnd();
            return;
        }
        if (inReachOf(craftPos)) {
            releaseGoal();
            step = Step.Opening;
            stepTicks = 0;
            craftOpenTries = 0;
            return;
        }
        boolean stopped = stepTicks > PATH_GRACE_TICKS && !BaritoneHelper.isMoving();
        if (stopped || stepTicks > WALK_TIMEOUT) {
            craftFailure = TABLE_NOT_REACHED;
            craftEnd();
        }
    }

    private void craftOpen() {
        if (!isTable(craftPos)) {
            craftFailure = TABLE_GONE;
            craftEnd();
            return;
        }
        AbstractContainerMenu open = mc.player.containerMenu;
        if (open != mc.player.inventoryMenu) {
            if (openPos != null && open != openMenuBefore && open instanceof net.minecraft.world.inventory.CraftingMenu) {
                ownedMenu = open;
                step = Step.Working;
                stepTicks = 0;
                wait = firstClickWait();
                return;
            }
            // Something else opened (one left over, a plugin window): closed, and the click made again
            if (!open.getCarried().isEmpty()) recoverCursor();
            mc.player.closeContainer();
            lastCloseTick = ticks;
        }

        if (openHeld()) {
            stepTicks--;
            return;
        }

        if (stepTicks > CRAFT_OPEN_TICKS || (craftOpenTries >= CRAFT_OPEN_TRIES && stepTicks - craftOpenClickAt > CRAFT_OPEN_RETRY)) {
            craftFailure = TABLE_NOT_OPENED;
            craftEnd();
            return;
        }

        if (craftOpenTries < CRAFT_OPEN_TRIES && (craftOpenTries == 0 || stepTicks - craftOpenClickAt >= CRAFT_OPEN_RETRY)) {
            craftOpenTries++;
            craftOpenClickAt = stepTicks;
            BlockPos pos = craftPos;
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            Runnable click = () -> {
                if (gate != Gate.Running || job != Job.Craft) return;
                openPos = pos;
                openClickTick = ticks;
                openMenuBefore = mc.player.containerMenu;
                BlockUtils.interact(hit, InteractionHand.MAIN_HAND, true);
            };

            if (rotate.get()) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), click);
            else click.run();
        }
    }

    /** One click in the open crafting window: always PICKUP, never a shift-click. */
    private void craftClick(AbstractContainerMenu menu, int slot, int button) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, ClickType.PICKUP, mc.player);
        yielder.inventoryChangedByUs();
        wait = Math.max(delay.get(), 4);
    }

    private void craftWork() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        boolean grid = crafter.inventoryGrid();
        if (grid ? menu != mc.player.inventoryMenu : (!(menu instanceof net.minecraft.world.inventory.CraftingMenu) || menu != ownedMenu)) {
            craftEnd();
            return;
        }
        if (craftFailure == null && crafter.failure() != null) craftFailure = crafter.failure();
        if (craftFailure != null || craftDone) {
            craftEnd();
            return;
        }

        Crafter.Act act = crafter.window(menu);
        switch (act.kind()) {
            case Crafter.Act.CLICK -> craftClick(menu, act.slot(), act.button());
            case Crafter.Act.DONE -> {
                craftDone = true;
                craftEnd();
            }
            case Crafter.Act.FAIL -> {
                craftFailure = act.why();
                craftEnd();
            }
            default -> { }
        }
    }

    /**
     * Ends the job at the table. The window is never closed with something in the grid or on the cursor: it is taken back
     * first, and when there is no room for it the window stays open and the player is told.
     */
    private void craftEnd() {
        if (ourGoal != null) releaseGoal();
        AbstractContainerMenu menu = mc.player.containerMenu;
        boolean grid = crafter.inventoryGrid();
        // The inventory window can't be drained while another window is open: it is waited for (our own is closed), up to 10 seconds
        if (grid && menu != mc.player.inventoryMenu) {
            if (ourMenu(menu)) {
                if (!menu.getCarried().isEmpty()) recoverCursor();
                closeOurWindow(menu);
                return;
            }
            if (++tableWaitTicks <= 20 * 10) return;
            if (craftFailure == null) craftFailure = "another window is open";
            String left = gridLeftovers();
            if (!left.isEmpty()) alert("Another window is open, so I left items in the inventory's crafting grid: %s. They come back to your inventory when you close it.", left);
        }
        boolean mine = grid ? menu == mc.player.inventoryMenu : menu instanceof net.minecraft.world.inventory.CraftingMenu && menu == ownedMenu;
        if (mine && !crafter.clean(menu)) {
            Crafter.Act back = crafter.drain(menu);
            if (back != null && back.kind() == Crafter.Act.CLICK) {
                craftDrainTries++;
                craftClick(menu, back.slot(), back.button());
                return;
            }
            if (craftDrainTries++ % 200 == 0) alert(grid ? "No room for what is in the inventory's crafting grid. Please make room in your inventory."
                : "No room for what is in the crafting table, keeping the window open. Please make room in your inventory.");
            // After about two minutes the order step ends with a clear reason; the window stays open and the alert goes on
            if (++craftStuckTicks > 20 * 120 && !craftStuckReported) {
                craftStuckReported = true;
                crafter.abort(com.porter.qwerte.craft.CraftHealth.NO_ROOM);
                if (craftForOrder) orderRunner.abortOrder(com.porter.qwerte.craft.CraftHealth.NO_ROOM + " (the crafting window is still open)");
            }
            return;
        }
        if (!grid && menu != mc.player.inventoryMenu && ourMenu(menu)) closeOurWindow(menu);
        ownedMenu = null;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;

        String why = craftFailure;
        if (why == null && !craftDone) why = TABLE_CLOSED;
        if (why != null) {
            QwerteAddon.LOG.info("[stash-sorter] crafting given up: {}", why);
        }
        // Table trouble is remembered for the order the way the plugin does: no result, not taken, couldn't reach or open, or
        // two closes in a row. Not after a single close
        if (!grid) craftHealth.note(orderRunner.orderId(), craftPos, why);
        // A found, placed or crafted table that can't be reached or opened is forgotten, so the next craft searches again; a hand-set one never
        if (!grid && (TABLE_NOT_REACHED.equals(why) || TABLE_NOT_OPENED.equals(why)) && stash.craftingTablePos != null && stash.craftingTablePos.equals(craftPos)
            && !stash.craftingTableSource.isEmpty() && !stash.craftingTableSource.equals("set")) {
            stash.craftingTablePos = null;
            stash.craftingTableSource = "";
            stash.save();
            tableChanged();
        }
        crafter.tableEnded(why);
        craftFailure = null;
        craftDone = false;
        job = null;
        target = null;
        wait = delay.get();
    }

    // Naming a shulker at the anvil (Job.Name). Not a container job: the anvil is no Box, it is never read, sorted or indexed
    private static final int NAME_RESULT_TICKS = 20 * 30, NAME_JOB_TICKS = 20 * 40, NAME_SETTLE_TICKS = 6, NAME_COST_WAIT_TICKS = 20 * 3;
    private static final int NAME_CHECK_TICKS = 20 * 5, NAME_MAX_TAKES = 3, NAME_TOO_DEAR = 40;
    private static final int NAME_OPEN_TICKS = 20 * 15, NAME_OPEN_TRIES = 4, NAME_OPEN_RETRY = 40;
    // The reasons a shulker goes unnamed, worded as the proxy plugin has them
    private static final String NO_ANVIL = "no anvil set", ANVIL_GONE = "the anvil is gone (the block there isn't an anvil)",
        OTHER_DIMENSION = "the anvil is in another dimension", TOO_FEW_LEVELS = "too few XP levels",
        NOT_REACHED = "couldn't reach the anvil", NOT_OPENED = "couldn't open the anvil",
        CLOSED = "the anvil closed before it was done", TOO_DEAR = "the anvil said it is too expensive",
        NO_RESULT = "no result from the anvil in time", NOT_TAKEN = "the anvil didn't give the shulker back named",
        NAME_UNFINISHED = "naming didn't finish";
    private int nameTakes, nameCheckTicks, nameOpenTries, nameOpenClickAt, nameDrainTries, nameClosedStreak;
    private boolean nameSucceeded;
    private String nameStreakOrder;
    // Why the anvil could not be used (reached, opened), for which order and anvil: later shulkers of that order go unnamed
    // at once instead of walking there again
    private String anvilFailure, anvilFailedOrder;
    private BlockPos anvilFailedFor;
    private BlockPos namePos;
    private String nameText;
    private String nameFailure;
    private int nameSlot = -1, nameSentAt, nameJobTicks, nameResultSeen;
    private boolean nameSent, namePlaced;
    // The carry slot the shulker came from: it is put back there, and found again if it landed elsewhere
    private int relocateSlot = -1;

    private boolean isAnvil(BlockPos pos) {
        return mc.level.getBlockState(pos).is(net.minecraft.tags.BlockTags.ANVIL);
    }

    /** Why shulkers can't be named now, or null. */
    private String anvilProblem() {
        if (stash == null || stash.anvilPos == null) return NO_ANVIL;
        if (mc.level == null || mc.player == null) return "no world";
        if (!stash.dimension.isEmpty() && !stash.dimension.equals(Stash.currentDimension())) return OTHER_DIMENSION;
        if (mc.level.isLoaded(stash.anvilPos) && !isAnvil(stash.anvilPos)) return ANVIL_GONE;
        if (anvilFailure != null && stash.anvilPos.equals(anvilFailedFor) && java.util.Objects.equals(anvilFailedOrder, orderRunner.orderId())) return anvilFailure;
        if (!mc.player.hasInfiniteMaterials() && mc.player.experienceLevel < 1) return TOO_FEW_LEVELS;
        return null;
    }

    /** For the heartbeat: an anvil is set and, where its chunk is loaded, it is one. */
    boolean anvilReady() {
        Stash s = ensureStash();
        if (s == null || s.anvilPos == null || mc.level == null) return false;
        return !mc.level.isLoaded(s.anvilPos) || isAnvil(s.anvilPos);
    }

    /** For the heartbeat: the bot's XP level, or -1. */
    int xpLevels() {
        return mc.player == null ? -1 : mc.player.experienceLevel;
    }

    BlockPos anvilPos() {
        Stash s = ensureStash();
        return s == null ? null : s.anvilPos;
    }

    /**
     * For stash-manager: sets the anvil, or clears it if it is this one.
     * @return 1 when set, 0 when cleared, -1 when the block is no anvil, -2 when no stash is loaded
     */
    int toggleAnvil(BlockPos pos) {
        if (mc.level == null || ensureStash() == null) return -2;
        if (stash.anvilPos != null && stash.anvilPos.equals(pos)) {
            stash.anvilPos = null;
            stash.anvilSource = "";
            stash.save();
            return 0;
        }
        if (!isAnvil(pos)) return -1;
        stash.anvilPos = pos.immutable();
        stash.anvilSource = "set";
        stash.save();
        return 1;
    }

    /** For stash-manager: no anvil any more. @return false when there was none or no stash */
    boolean clearAnvil() {
        if (ensureStash() == null || stash.anvilPos == null) return false;
        stash.anvilPos = null;
        stash.anvilSource = "";
        stash.save();
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Delivery storage (0.4.0-dev.5; the proxy plugin 1.9.0 has the same): the delivery container and the double chests put up above
    // it (DeliveryBook keeps which are full). With auto-expand-delivery on, when every chest is full another double chest is taken
    // from the stash (or crafted, with crafting on) and put up one block above the top row by ChestPlacer: the first half by a
    // sneaking click on the top of the chest below, the second by a sneaking click on the side of the first (ChestStack has the
    // rules). A chest a player emptied is found by its lid (StashWatch, the lid animation) and looked at once; where the lid was
    // out of sight, by looking again now and then while the order waits for space. The chests are boxes flagged delivery: never
    // sorted into, read, taken from, indexed or searched, saved with the stash.

    private static final String CHEST_ID = "minecraft:chest";
    private static final long EXPAND_RETRY_MS = 60_000L, EXPAND_EVAL_MS = 5_000L;
    // Chests are being gathered or crafted for the storage, and are carried until they are put up
    private boolean chestSession, chestHolding;
    // The Deliver job is a look at a full chest (Recheck), not a delivery; the chest the last shulker went into
    private boolean recheckOnly;
    private Box lastDeliveryBox;
    private ChestStack.Plan chestPlanNow;
    // Why another chest could not be put up the last time (no chests to be had, placing failed), and when; the last evaluation
    private String expandFailure, expandEval;
    private long expandFailureAt, expandEvalAt;
    private final StashWatch deliveryWatch = new StashWatch(true);

    private final ChestPlacer placer = new ChestPlacer(new ChestPlacer.Owner() {
        @Override
        public Set<Integer> keep() {
            return parseSlots(keepSlots.get());
        }

        @Override
        public boolean rotate() {
            return rotate.get();
        }

        @Override
        public int delay() {
            return delay.get();
        }

        @Override
        public boolean walk() {
            return walkOn();
        }

        @Override
        public void walkTo(BlockPos stand) {
            if (!BARITONE) return;
            walkNear(stand, 0);
            // The stand may be outside the stash area: that is not a reason to refuse the way
            walkInside = false;
        }

        @Override
        public void releaseGoal() {
            StashSorter.this.releaseGoal();
        }

        @Override
        public boolean moving() {
            return BARITONE && BaritoneHelper.isMoving();
        }

        @Override
        public boolean leftArea() {
            return StashSorter.this.leftArea();
        }

        @Override
        public boolean running() {
            return gate == Gate.Running && orderRunner.running() && !orderRunner.calledOff();
        }

        @Override
        public void moved(int from, int to, ItemStack was) {
            // The tracking of the stack goes with it, or what is left of it would count as the player's own
            stashHeld.remove(from);
            orderHeld.remove(from);
            Box source = heldFrom.remove(from);
            stashHeld.put(to, was);
            if (orderWork()) orderHeld.put(to, was.copy());
            if (source != null) heldFrom.put(to, source);
        }

        @Override
        public void pause(int ticks) {
            wait = ticks;
        }

        @Override
        public void debug(String text) {
            StashSorter.this.debug("%s", text);
        }
    });

    private static BlockPos bp(DeliveryBook.Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    private static DeliveryBook.Pos dp(BlockPos p) {
        return new DeliveryBook.Pos(p.getX(), p.getY(), p.getZ());
    }

    private DeliveryBook book() {
        return new DeliveryBook(stash == null ? new ArrayList<>() : stash.deliveryStorage);
    }

    private static boolean autoExpand() {
        StashManager manager = meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
        return manager != null && manager.autoExpandDelivery();
    }

    private boolean plainChest(ItemStack s) {
        return s.is(net.minecraft.world.item.Items.CHEST) && !s.has(DataComponents.CONTAINER);
    }

    /** The chests carried to be put up. */
    private int carriedChests() {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (plainChest(s) && (stashHeld.containsKey(i) || orderHeld.containsKey(i))) n += s.getCount();
        }
        return n;
    }

    /** A chest put up for the delivery storage is turned into something else by hand: it leaves the storage. */
    private void forgetExtra(Box box) {
        if (!box.deliveryExtra || stash == null) return;
        DeliveryBook book = book();
        DeliveryBook.Entry e = book.find(dp(box.pos));
        if (e != null && !e.primary) book.remove(e);
        box.deliveryExtra = false;
        stash.markDirty();
    }

    /**
     * A box stops being a delivery box: every flag delivery set goes back (including noCompact), and a box that was never
     * read is removed instead of being kept as an ordinary container nobody has seen.
     */
    private void unflagDelivery(Box b) {
        b.delivery = false;
        b.deliveryExtra = false;
        b.excluded = b.wasExcluded;
        b.dropOff = b.wasDropOff;
        b.wasExcluded = false;
        b.wasDropOff = false;
        b.noCompact = false;
        if (b.neverRead()) {
            stash.boxes.remove(b);
            surveyRetry.succeed(b);
            failedSurvey.remove(b);
            QwerteAddon.LOG.info("[stash-sorter] removed the never-read container at {} that stopped being a delivery container.", xyz(b.pos));
        }
        stash.markDirty();
    }

    /**
     * Keeps the stash's boxes for the delivery storage in step with the book: the delivery container (the book follows it; a
     * different one forgets the chests put up above the old one) and the chests put up above it, flagged delivery and excluded.
     */
    private void syncDelivery() {
        Stash s = stash;
        if (s == null || mc.level == null) return;
        s.ensureDelivery();
        Box primary = null;
        for (Box b : s.boxes) if (b.delivery && !b.deliveryExtra) primary = b;
        DeliveryBook book = book();
        if (primary == null) {
            for (Box b : new ArrayList<>(s.boxes)) if (b.deliveryExtra) unflagDelivery(b);
            if (!s.deliveryStorage.isEmpty()) {
                book.syncPrimary(null);
                s.markDirty();
            }
            return;
        }
        int before = s.deliveryStorage.size();
        List<DeliveryBook.Pos> parts = new ArrayList<>();
        for (BlockPos p : primary.parts) parts.add(dp(p));
        book.syncPrimary(parts);
        if (book.dedupe() || s.deliveryStorage.size() != before) s.markDirty();

        Set<Box> mine = new HashSet<>();
        mine.add(primary);
        for (DeliveryBook.Entry e : book.sorted()) {
            if (e.primary) continue;
            BlockPos at = bp(e.first());
            Box eb = s.boxAt(at);
            // A chest that is gone (broken by a player) leaves the storage
            if (mc.level.isLoaded(at) && !(mc.level.getBlockEntity(at) instanceof ChestBlockEntity)) {
                book.remove(e);
                if (eb != null) s.boxes.remove(eb);
                s.markDirty();
                continue;
            }
            if (eb == null) {
                eb = mc.level.isLoaded(at) ? makeBox(at) : new Box(at, new ArrayList<>(List.of(at)));
                s.boxes.add(eb);
                s.markDirty();
            }
            // A single chest that became a double (or half of one broken): the halves are what the world says
            if (mc.level.isLoaded(at)) {
                Box fresh = makeBox(at);
                if (fresh.parts.size() != eb.parts.size() || !fresh.parts.containsAll(eb.parts)) {
                    eb.parts.clear();
                    eb.parts.addAll(fresh.parts);
                    eb.contents = null;
                    List<DeliveryBook.Pos> p2 = new ArrayList<>();
                    for (BlockPos p : fresh.parts) p2.add(dp(p));
                    e.at = new DeliveryBook(new ArrayList<>()).add(p2).at;
                    s.markDirty();
                }
            }
            if (!eb.delivery || !eb.deliveryExtra || !eb.excluded || eb.dropOff) {
                // What the user had set on it comes back when it leaves the storage
                if (!eb.delivery) {
                    eb.wasExcluded = eb.excluded;
                    eb.wasDropOff = eb.dropOff;
                }
                eb.delivery = true;
                eb.deliveryExtra = true;
                eb.excluded = true;
                eb.dropOff = false;
                eb.noCompact = true;
                s.markDirty();
            }
            mine.add(eb);
        }
        int dropped = 0;
        for (Box b : new ArrayList<>(s.boxes)) {
            if (!b.deliveryExtra || mine.contains(b)) continue;
            // The same chest as one of the storage's (a duplicate box): gone; any other is an ordinary container again
            boolean twin = false;
            for (Box m : mine) for (BlockPos q : b.parts) if (m.parts.contains(q)) twin = true;
            if (twin) {
                s.boxes.remove(b);
                s.markDirty();
                continue;
            }
            dropped++;
            unflagDelivery(b);
        }
        if (dropped > 0) alert("%d extra delivery chest%s now ordinary stash containers; their contents will be sorted.", dropped, dropped == 1 ? " is" : "s are");
    }

    /** A different delivery container, or none: the chests put up above the old one are an ordinary stash again. */
    private void dropDeliveryExtras() {
        if (stash == null) return;
        int n = 0;
        for (Box b : new ArrayList<>(stash.boxes)) if (b.deliveryExtra) {
            n++;
            unflagDelivery(b);
        }
        if (n > 0) alert("%d extra delivery chest%s now ordinary stash containers; their contents will be sorted.", n, n == 1 ? " is" : "s are");
        stash.deliveryStorage.clear();
        stash.markDirty();
    }

    private final ChestStack.View stackView = new ChestStack.View() {
        @Override
        public boolean loaded(int x, int z) {
            return mc.level.isLoaded(new BlockPos(x, 0, z));
        }

        @Override
        public boolean air(int x, int y, int z) {
            BlockPos p = new BlockPos(x, y, z);
            BlockPos feet = mc.player.blockPosition();
            boolean body = feet.getX() == x && feet.getZ() == z && (feet.getY() == y || feet.getY() + 1 == y);
            return mc.level.getBlockState(p).isAir() && !body;
        }

        @Override
        public boolean conductor(int x, int y, int z) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState st = mc.level.getBlockState(p);
            return !st.isAir() && st.isRedstoneConductor(mc.level, p);
        }

        @Override
        public String name(int x, int y, int z) {
            return BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(new BlockPos(x, y, z)).getBlock()).getPath();
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return placeView().standable(x, y, z);
        }

        @Override
        public int minY() {
            return mc.level.getMinSectionY() << 4;
        }

        @Override
        public int maxY() {
            return (mc.level.getMaxSectionY() + 1) << 4;
        }
    };

    /** Where the next row goes, or why it can't. */
    private ChestStack.Result stackPlan() {
        List<DeliveryBook.Entry> row = book().topRow();
        if (row.isEmpty()) return new ChestStack.Result(null, "no delivery container is set");
        DeliveryBook.Entry top = row.get(0);
        List<DeliveryBook.Pos> parts = top.parts();
        BlockPos p0 = bp(parts.get(0));
        if (!mc.level.isLoaded(p0)) return new ChestStack.Result(null, "the delivery chest isn't loaded");
        BlockState st = mc.level.getBlockState(p0);
        int fx = 0, fz = 0;
        if (st.hasProperty(ChestBlock.FACING)) {
            Direction d = st.getValue(ChestBlock.FACING);
            fx = d.getStepX();
            fz = d.getStepZ();
        }
        return ChestStack.next(parts, fx, fz, !(st.getBlock() instanceof BarrelBlock), book().sorted().get(0).y(), dp(mc.player.blockPosition()), stackView);
    }

    private String expandBlockerNow() {
        long now = System.currentTimeMillis();
        String failed = expandFailure;
        if (failed != null && now - expandFailureAt < EXPAND_RETRY_MS) return failed;
        if (now - expandEvalAt < EXPAND_EVAL_MS) return expandEval;
        expandEval = computeExpandBlocker();
        expandEvalAt = now;
        return expandEval;
    }

    private String computeExpandBlocker() {
        ChestStack.Result res = stackPlan();
        if (res.plan() == null) return res.blocked();
        int need = res.plan().second() != null ? 2 : 1;
        int have = carriedChests();
        if (have >= need) return null;
        Map<String, Long> pool = orderRunner.craftPool();
        if (pool.getOrDefault(CHEST_ID, 0L) >= need - have) return null;
        if (!craftingOn()) return "no chests left in the stash (crafting is off)";
        String blocker = craftBlocker();
        if (blocker != null) return "no chests left in the stash, and " + blocker;
        com.porter.qwerte.craft.CraftPlanner.Result plan = crafter.preview(CHEST_ID, need - have, 0, pool, false);
        return plan.ok() ? null : "no chests left in the stash, and " + plan.refusal();
    }

    /** What the delivery storage can take now, as the order runner asks. */
    private DeliveryRoom deliveryRoomNow() {
        if (stash == null || mc.level == null) return DeliveryRoom.ROOM;
        syncDelivery();
        DeliveryBook book = book();
        if (book.chests() == 0) return DeliveryRoom.ROOM;
        // Only worked out when every chest is full (the supply check reads the whole stash)
        String blocker = autoExpand() && book.firstUsable() == null ? expandBlockerNow() : null;
        return book.room(autoExpand(), blocker, System.currentTimeMillis(), DeliveryBook.RECHECK_MS);
    }

    /** The chest the next shulker goes into: the lowest with room, or (verify) the one the last went into. */
    private Box deliveryChestFor(boolean verify) {
        if (stash == null) return null;
        if (verify && lastDeliveryBox != null && lastDeliveryBox.delivery && stash.boxes.contains(lastDeliveryBox)) return lastDeliveryBox;
        DeliveryBook book = book();
        for (DeliveryBook.Entry e : book.sorted()) {
            if (e.full) continue;
            Box b = stash.boxAt(bp(e.first()));
            if (b == null || !b.delivery) continue;
            // A chest put up above the container that can't be reached is passed over like a full one
            if (b.deliveryExtra && b.unreachable) {
                book.markFull(e, System.currentTimeMillis());
                continue;
            }
            return b;
        }
        return null;
    }

    private void deliveryChestFull(Box box) {
        if (box == null || stash == null) return;
        DeliveryBook book = book();
        DeliveryBook.Entry e = book.find(dp(box.pos));
        if (e == null) return;
        book.markFull(e, System.currentTimeMillis());
        stash.markDirty();
    }

    /** The heartbeat's deliveryStorage (null when no delivery container is set): chests, full ones, whether another can go up. */
    StashIndex.DeliveryStorage deliveryStorage() {
        try {
            Stash s = stash;
            if (s == null || mc.level == null || mc.player == null || s.deliveryPos == null) return null;
            DeliveryBook book = new DeliveryBook(s.deliveryStorage);
            int chests = book.chests(), full = book.fullCount();
            boolean auto = autoExpand();
            String blocked = null;
            if (chests > 0) {
                if (!auto) blocked = full >= chests ? "auto expand is off" : null;
                else if (expandFailure != null && System.currentTimeMillis() - expandFailureAt < EXPAND_RETRY_MS) blocked = expandFailure;
                else if (book.firstUsable() == null) blocked = storageBlocker();
            }
            return new StashIndex.DeliveryStorage(auto, chests, full, auto && blocked == null && chests > 0, blocked);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String storageCached;
    private long storageAt;

    /** The geometry blocker, only worked out when every chest is full and cached for a few seconds (asked for every tick). */
    private String storageBlocker() {
        long now = System.currentTimeMillis();
        if (now - storageAt < EXPAND_EVAL_MS) return storageCached;
        storageAt = now;
        try {
            ChestStack.Result res = stackPlan();
            storageCached = res.plan() == null ? res.blocked() : expandEval;
        } catch (RuntimeException e) {
            storageCached = null;
        }
        return storageCached;
    }

    /** "3 chests, 1 full, can expand" or "... blocked: why". */
    String deliveryStorageLine() {
        StashIndex.DeliveryStorage d = deliveryStorage();
        if (d == null) return "no delivery container set";
        String tail = d.blocked() != null ? "blocked: " + d.blocked() : d.autoExpand() ? "can expand" : "auto expand is off";
        return d.chests() + (d.chests() == 1 ? " chest, " : " chests, ") + d.full() + " full, " + tail;
    }

    /** Another player opened a delivery chest and closed it again (StashWatch): a full one is to be looked at. */
    private void deliveryTouched(Box box) {
        if (stash == null) return;
        if (book().lidClosedByOther(dp(box.pos))) {
            debug("Another player used the delivery chest at %s.", box.pos.toShortString());
            stash.markDirty();
        }
    }

    // Looking at a full chest again

    private boolean startRecheck(Box box) {
        if (box == null || !box.delivery) return false;
        recheckOnly = true;
        step("order: looking at a delivery chest that may have been emptied.");
        boolean started = start(Job.Deliver, box);
        if (!started) {
            recheckOnly = false;
            // Not tried again every tick: it counts as looked at now, and the next look is due later
            deliveryChestFull(box);
        }
        return started;
    }

    /** The open delivery chest was read: it has room again, or it is still full. */
    private void recheckRead() {
        recheckOnly = false;
        if (target == null) return;
        DeliveryBook book = book();
        DeliveryBook.Entry e = book.find(dp(target.pos));
        if (e == null) return;
        boolean room = false;
        for (ItemStack s : target.contents) if (s.isEmpty()) room = true;
        if (room) book.markRoom(e, System.currentTimeMillis());
        else book.markFull(e, System.currentTimeMillis());
        stash.markDirty();
        info("Order: a delivery chest was looked at again: %s.", room ? "it has room" : "still full");
    }

    // Putting a double chest up

    /** Chests could not be had or put up: said, and remembered for a minute before it is tried again. */
    private void expandFailed(String why) {
        expandFailure = why;
        expandFailureAt = System.currentTimeMillis();
        expandEvalAt = 0;
        chestHolding = false;
        warning("Delivery storage: couldn't put another chest up (%s). The order waits for space.", why);
        step("delivery storage: couldn't put another chest up: " + why + "; the order waits for space.");
    }

    /** Starts putting another row of chests up (OrderRunner.Expand). @return true when a job was started */
    private boolean startExpand() {
        return startExpand(false);
    }

    /** @param again the crafting table it waited for is ready: not counted as another try */
    private boolean startExpand(boolean again) {
        if (stash == null || mc.level == null || crafter.active() || kitBuilder.active()) return false;
        syncDelivery();
        ChestStack.Result res = stackPlan();
        if (res.plan() == null) {
            expandFailed(res.blocked());
            return false;
        }
        ChestStack.Plan plan = res.plan();
        if (!again) orderRunner.expandStarted();
        // Chests already standing in the new row (a try cut short before) are taken as they are
        boolean firstUp = mc.level.getBlockState(bp(plan.first())).getBlock() instanceof ChestBlock;
        boolean secondUp = plan.second() != null && mc.level.getBlockState(bp(plan.second())).getBlock() instanceof ChestBlock;
        int toPlace = (firstUp ? 0 : 1) + (plan.second() == null || secondUp ? 0 : 1);
        if (toPlace == 0) {
            registerChests(plan);
            return false;
        }
        int have = carriedChests();
        chestHolding = true;
        if (have >= toPlace) return startChestPlace(plan);

        int need = toPlace - have;
        Map<String, Long> pool = orderRunner.craftPool();
        boolean inStock = pool.getOrDefault(CHEST_ID, 0L) >= need;
        if (!inStock && !craftingOn()) {
            expandFailed("no chests left in the stash (crafting is off)");
            return false;
        }
        String why = inStock ? null : craftBlocker();
        if (why == null && !inStock && tableMissing()) {
            // The craft itself must be possible before a table is put in the world for it
            var preview = crafter.preview(CHEST_ID, need, 0, pool, false);
            if (!preview.ok()) why = preview.refusal();
            else {
                switch (prepareTable(TableResume.Expand)) {
                    case GATHERING -> {
                        return runCraft(crafter.next());
                    }
                    case PLACING -> {
                        return true;
                    }
                    case FAILED -> why = tableWhy;
                    default -> { }
                }
            }
        }
        if (why == null) why = crafter.begin(new Crafter.Request(CHEST_ID, need, "delivery storage", false, 0, orderRunner::craftPool, orderRunner::lotKeys, inStock));
        if (why != null) {
            expandFailed(inStock ? why : "no chests left in the stash, and " + why);
            return false;
        }
        chestSession = true;
        craftForOrder = false;
        info("Delivery storage: taking %d chest%s out of the stash to put up.", need, need == 1 ? "" : "s");
        boolean started = runCraft(crafter.next());
        if (!started && chestSession && job == null && !crafter.active()) {
            chestSession = false;
            chestHolding = false;
            expandFailed("taking the chests out of the stash didn't start");
        }
        return started;
    }

    private boolean startChestPlace(ChestStack.Plan plan) {
        chestPlanNow = plan;
        chestJobTicks = 0;
        boolean firstUp = mc.level.getBlockState(bp(plan.first())).getBlock() instanceof ChestBlock;
        placer.begin(plan, firstUp);
        job = Job.ChestPlace;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;
        swapStep = 0;
        step("order: putting another chest up for the delivery storage" + (plan.second() != null ? " (a double chest)" : "") + "; next: carry on with the order.");
        return true;
    }

    private int chestJobTicks;
    private static final int CHEST_JOB_TICKS = 20 * 90;

    private void chestPlaceTick() {
        stepTicks++;
        lastOwnAct = ticks;
        // The whole job is bounded: whatever the placer is stuck on, it ends
        if (++chestJobTicks > CHEST_JOB_TICKS) placer.abort("it took too long");
        ChestPlacer.Outcome out = placer.tick();
        if (out != null) chestPlaceDone(out);
    }

    private void chestPlaceDone(ChestPlacer.Outcome out) {
        ChestStack.Plan plan = chestPlanNow;
        releaseGoal();
        job = null;
        target = null;
        wait = delay.get();
        chestHolding = false;
        chestPlanNow = null;
        if (out.placed() && plan != null) {
            if (out.partial()) warning("Delivery storage: the second half of the chest couldn't be put up (%s), the first stands alone.", out.failure());
            registerChests(plan);
        }
        else expandFailed(out.failure() == null ? "it couldn't be put up" : out.failure());
        version++;
        idle = false;
    }

    /** The chests of the row stand: the book and the stash know them, the delivery storage has more. */
    private void registerChests(ChestStack.Plan plan) {
        DeliveryBook book = book();
        addPlacedChest(book, plan.first());
        if (plan.second() != null) addPlacedChest(book, plan.second());
        expandFailure = null;
        expandEvalAt = 0;
        syncDelivery();
        stash.markDirty();
        stash.save();
        version++;
        info("Delivery storage: %d chests now.", book.chests());
    }

    private void addPlacedChest(DeliveryBook book, DeliveryBook.Pos at) {
        BlockPos p = bp(at);
        if (!(mc.level.getBlockState(p).getBlock() instanceof ChestBlock)) return;
        Box one = makeBox(p);
        List<DeliveryBook.Pos> parts = new ArrayList<>();
        for (BlockPos q : one.parts) parts.add(dp(q));
        // A double chest put up half by half is one chest: the second half merges into the entry of the first
        book.addOrMerge(parts);
    }

    // Finding an anvil to name at (Job.AnvilPlace). In order: the one picked by hand, a remembered one, a placed anvil found in
    // the stash area, one taken from the stash and placed. Nothing works: the rename fails with AnvilFinder.NONE
    private static final String[] ANVIL_IDS = { "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil" };
    private static final int ANVIL_PLACE_TICKS = 20 * 90, ANVIL_TRIES = 3;
    private boolean anvilSession, anvilPlaced, anvilWalking;
    private int anvilSlot = -1, anvilPlaceTries, anvilPlacedAt, anvilWalkStart, anvilRestarts;
    private BlockPos anvilSpot, anvilStand;
    private final Set<PlaceSearch.Pos> anvilSkip = new HashSet<>();

    /** No anvil is set or remembered, or the one that was is gone. */
    boolean anvilMissing() {
        if (stash == null || stash.anvilPos == null) return true;
        return mc.level != null && mc.level.isLoaded(stash.anvilPos) && !isAnvil(stash.anvilPos);
    }

    /** For the heartbeat: where the anvil in use came from: "set", "found", "placed" or "none". */
    String anvilSource() {
        Stash s = ensureStash();
        if (s == null || s.anvilPos == null) return "none";
        return s.anvilSource.isEmpty() ? "set" : s.anvilSource;
    }

    private static boolean anvilItem(ItemStack s) {
        return !s.isEmpty() && (s.is(Items.ANVIL) || s.is(Items.CHIPPED_ANVIL) || s.is(Items.DAMAGED_ANVIL));
    }

    /** The inventory slot of an anvil item gathered for placing, or -1. */
    private int anvilItemSlot() {
        var inv = mc.player.getInventory();
        if (anvilSlot >= 0 && anvilItem(inv.getItem(anvilSlot))) return anvilSlot;
        for (int slot : crafter.lastHeld()) if (anvilItem(inv.getItem(slot)) && (stashHeld.containsKey(slot) || orderHeld.containsKey(slot))) return slot;
        return -1;
    }

    /** Placed anvils in the stash area: the perimeter if one is set, else the radius and height around us. Loaded chunks only. */
    private List<AnvilFinder.Pos> placedAnvils() {
        List<AnvilFinder.Pos> out = new ArrayList<>();
        for (BlockPos p : scanArea(st -> st.is(net.minecraft.tags.BlockTags.ANVIL), false)) out.add(new AnvilFinder.Pos(p.getX(), p.getY(), p.getZ()));
        return out;
    }

    /**
     * Blocks in the stash area that pass the test: the perimeter if one is set, else the radius and height around us. Loaded chunks
     * only. {@code bounded}: at most TableFinder.SEARCH_SPAN blocks across (x and z), around us.
     */
    private List<BlockPos> scanArea(java.util.function.Predicate<BlockState> test, boolean bounded) {
        List<BlockPos> out = new ArrayList<>();
        if (mc.level == null) return out;
        int minX, maxX, minY, maxY, minZ, maxZ;
        if (stash.hasArea()) {
            BlockPos lo = stash.areaMin(), hi = stash.areaMax();
            minX = lo.getX(); maxX = hi.getX(); minY = lo.getY(); maxY = hi.getY(); minZ = lo.getZ(); maxZ = hi.getZ();
        }
        else {
            BlockPos at = mc.player.blockPosition();
            int r = radius.get(), h = height.get();
            minX = at.getX() - r; maxX = at.getX() + r; minY = at.getY() - h; maxY = at.getY() + h; minZ = at.getZ() - r; maxZ = at.getZ() + r;
        }
        if (bounded) {
            int[] xs = TableFinder.span(minX, maxX, mc.player.getBlockX()), zs = TableFinder.span(minZ, maxZ, mc.player.getBlockZ());
            minX = xs[0]; maxX = xs[1]; minZ = zs[0]; maxZ = zs[1];
        }
        int minSection = mc.level.getMinSectionY();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                var sections = mc.level.getChunk(cx, cz).getSections();
                for (int i = 0; i < sections.length; i++) {
                    int baseY = (minSection + i) * 16;
                    if (baseY + 15 < minY || baseY > maxY) continue;
                    var section = sections[i];
                    // Whole sections without such a block in their palette are skipped
                    if (section.hasOnlyAir() || !section.maybeHas(test)) continue;
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                if (!test.test(section.getBlockState(x, y, z))) continue;
                                int wx = cx * 16 + x, wy = baseY + y, wz = cz * 16 + z;
                                if (wx < minX || wx > maxX || wy < minY || wy > maxY || wz < minZ || wz > maxZ) continue;
                                out.add(new BlockPos(wx, wy, wz));
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /** An anvil is needed: finds one placed, or gathers one from the stash to place. The outcome goes to the order runner. */
    private boolean startFindAnvil() {
        if (stash == null || mc.level == null) {
            orderRunner.anvilFound(false);
            return false;
        }
        // An anvil taken out earlier and not yet placed (the job was interrupted)
        if (anvilSlot >= 0 && !anvilItem(mc.player.getInventory().getItem(anvilSlot))) anvilSlot = -1;
        int held = anvilItemSlot();
        if (held >= 0 && startAnvilPlace(held)) return true;

        AnvilFinder.Memory memory = stash.anvilPos == null ? null
            : new AnvilFinder.Memory(new AnvilFinder.Pos(stash.anvilPos.getX(), stash.anvilPos.getY(), stash.anvilPos.getZ()), AnvilFinder.Source.of(stash.anvilSource.isEmpty() ? "set" : stash.anvilSource));
        AnvilFinder.Decision decision = AnvilFinder.decide(memory, new AnvilFinder.World() {
            @Override
            public boolean isAnvil(AnvilFinder.Pos p) {
                BlockPos at = new BlockPos(p.x(), p.y(), p.z());
                return !mc.level.isLoaded(at) || StashSorter.this.isAnvil(at);
            }

            @Override
            public AnvilFinder.Pos search() {
                AnvilFinder.Pos from = new AnvilFinder.Pos(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
                return AnvilFinder.nearest(placedAnvils(), from, p -> !stash.hasArea() || stash.inArea(new BlockPos(p.x(), p.y(), p.z())));
            }

            @Override
            public boolean stockHasAnvil() {
                Map<String, Long> pool = orderRunner.craftPool();
                for (String id : ANVIL_IDS) if (pool.getOrDefault(id, 0L) > 0) return true;
                return false;
            }
        });
        if (decision.forget()) {
            info("The anvil I knew is gone, looking for another.");
            stash.anvilPos = null;
            stash.anvilSource = "";
            stash.save();
        }
        switch (decision.step()) {
            case USE -> orderRunner.anvilFound(true);
            case FOUND -> {
                stash.anvilPos = new BlockPos(decision.pos().x(), decision.pos().y(), decision.pos().z());
                stash.anvilSource = "found";
                stash.save();
                info("Found an anvil in the stash area, using it for naming from now on.");
                orderRunner.anvilFound(true);
            }
            case PLACE_FROM_STOCK -> {
                Map<String, Long> pool = orderRunner.craftPool();
                String id = null;
                for (String candidate : ANVIL_IDS) if (id == null && pool.getOrDefault(candidate, 0L) > 0) id = candidate;
                String why = id == null ? "none in the stash"
                    : crafter.begin(new Crafter.Request(id, 1, "naming", false, 0, orderRunner::craftPool, orderRunner::lotKeys, true));
                if (why != null) {
                    warning("No anvil to place: %s.", why);
                    orderRunner.anvilFound(false);
                    return false;
                }
                craftForOrder = true;
                anvilSession = true;
                info("No anvil in the area, taking one out of the stash to place.");
                return runCraft(crafter.next());
            }
            case FAIL -> {
                warning("%s.", decision.failure());
                orderRunner.anvilFound(false);
            }
        }
        return false;
    }

    private boolean startAnvilPlace(int slot) {
        return startPlaceItem(slot, false);
    }

    /** Places the anvil, or the crafting table (the same job and spot search), from this inventory slot. */
    private boolean startPlaceItem(int slot, boolean table) {
        placingTable = table;
        placeWhy = "";
        anvilSlot = slot;
        anvilSpot = anvilStand = null;
        anvilPlaced = anvilWalking = false;
        anvilPlaceTries = anvilRestarts = 0;
        anvilSkip.clear();
        job = Job.AnvilPlace;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;
        swapStep = 0;
        step(table ? "placing a crafting table to craft at; next: carry on." : "order: placing an anvil to name the shulkers at; next: carry on with the order.");
        return true;
    }

    private void anvilPlaceTick() {
        stepTicks++;
        lastOwnAct = ticks;
        var inv = mc.player.getInventory();

        if (anvilPlaced) {
            if (placingTable ? isTable(anvilSpot) : isAnvil(anvilSpot)) anvilPlaceDone(true);
            else if (stepTicks - anvilPlacedAt > 40) anvilPlaceDone(false);
            return;
        }
        if (stepTicks > ANVIL_PLACE_TICKS || anvilSlot < 0 || !(placingTable ? tableItem(inv.getItem(anvilSlot)) : anvilItem(inv.getItem(anvilSlot)))) {
            anvilPlaceDone(false);
            return;
        }

        if (anvilWalking) {
            Vec3 at = mc.player.position();
            if (at.distanceToSqr(Vec3.atBottomCenterOf(anvilStand)) <= 1.8 * 1.8) {
                releaseGoal();
                anvilWalking = false;
                wait = delay.get();
                return;
            }
            if (ourGoal == null) {
                if (++anvilRestarts > 3) anvilSpotFailed();
                else {
                    walkNear(anvilStand, 1);
                    anvilWalkStart = stepTicks;
                }
                return;
            }
            boolean stopped = stepTicks - anvilWalkStart > PATH_GRACE_TICKS && !BaritoneHelper.isMoving();
            if (stopped || stepTicks - anvilWalkStart > WANDER_TICKS) anvilSpotFailed();
            return;
        }

        if (anvilSpot == null) {
            PlaceSearch.Result found = PlaceSearch.search(pos(mc.player.blockPosition()), placeView(), anvilSkip);
            if (found.found() == null) {
                QwerteAddon.LOG.info("{}: no spot within {} blocks: {}", placingTable ? "crafting table" : "anvil", PlaceSearch.RADIUS, found.why());
                placeWhy = "no room to place it (" + found.why() + ")";
                anvilPlaceDone(false);
                return;
            }
            anvilSpot = bp(found.found().spot());
            anvilStand = bp(found.found().stand());
            if (found.found().steps() > 0) {
                if (!walkOn()) {
                    anvilPlaceDone(false);
                    return;
                }
                info("No spot for the %s beside me, walking %d steps to the nearest one.", placingTable ? "crafting table" : "anvil", found.found().steps());
                walkNear(anvilStand, 1);
                anvilWalking = true;
                anvilWalkStart = stepTicks;
                anvilRestarts = 0;
                return;
            }
        }

        // It has to be in the hotbar to place it
        if (anvilSlot >= 9) {
            Set<Integer> keep = parseSlots(keepSlots.get());
            int hotbar = -1;
            for (int i = 0; i < 9 && hotbar == -1; i++) if (!keep.contains(i) && inv.getItem(i).isEmpty()) hotbar = i;
            if (hotbar == -1) {
                warning("No free hotbar slot to place the %s from.", placingTable ? "crafting table" : "anvil");
                placeWhy = "no free hotbar slot to place it from";
                anvilPlaceDone(false);
                return;
            }
            // The tracking of the stack goes with it, or what is left of it would count as the player's own
            int old = anvilSlot;
            ItemStack was = inv.getItem(old).copy();
            Box source = heldFrom.get(old);
            InvUtils.move().from(old).to(hotbar);
            anvilSlot = hotbar;
            stashHeld.remove(old);
            orderHeld.remove(old);
            heldFrom.remove(old);
            stashHeld.put(hotbar, was);
            if (orderWork()) orderHeld.put(hotbar, was.copy());
            if (source != null) heldFrom.put(hotbar, source);
            wait = delay.get();
            return;
        }
        if (!BlockUtils.canPlace(anvilSpot, true)) {
            anvilSpotFailed();
            return;
        }
        if (BlockUtils.place(anvilSpot, new FindItemResult(anvilSlot, inv.getItem(anvilSlot).getCount()), rotate.get(), 50, true, true, true)) {
            anvilPlaced = true;
            anvilPlacedAt = stepTicks;
            wait = delay.get();
        }
        else anvilSpotFailed();
    }

    /** That spot did not work: not tried again, and another is looked for (a few times). */
    private void anvilSpotFailed() {
        releaseGoal();
        if (anvilSpot != null) anvilSkip.add(pos(anvilSpot));
        anvilSpot = anvilStand = null;
        anvilWalking = false;
        if (++anvilPlaceTries >= ANVIL_TRIES) anvilPlaceDone(false);
    }

    private void anvilPlaceDone(boolean ok) {
        releaseGoal();
        boolean table = placingTable;
        if (ok && table) {
            stash.craftingTablePos = anvilSpot.immutable();
            stash.craftingTableSource = tableCrafted ? "crafted" : "placed";
            // A table actually placed for a craft order counts against its cap (one per order, three an hour); kept with the stash
            if (tableForChunk) {
                long stamp = System.currentTimeMillis();
                tableCap.placed(orderRunner.orderId(), stamp);
                stash.craftTables.clear();
                stash.craftTables.addAll(tableCap.export(stamp));
            }
            stash.save();
            tableChanged();
            info(tableCrafted ? "Crafted a crafting table and placed it, remembered for next time." : "Placed a crafting table to craft at, remembered for next time.");
        }
        else if (ok) {
            stash.anvilPos = anvilSpot.immutable();
            stash.anvilSource = "placed";
            stash.save();
            info("Placed an anvil to name shulkers at, remembered for next time.");
        }
        else warning(table ? "Couldn't place a crafting table." : "Couldn't place an anvil.");
        if (table && !ok) {
            tableWhy = TableFinder.failure(placeWhy.isEmpty() ? "it couldn't be placed" : placeWhy);
            tableCode = com.porter.qwerte.craft.CraftAuto.NO_STAND;
        }
        placingTable = false;
        // An anvil item that was not placed is carried like anything else and put back
        anvilSlot = -1;
        crafter.clearLastHeld();
        anvilSpot = anvilStand = null;
        anvilPlaced = anvilWalking = false;
        job = null;
        target = null;
        wait = delay.get();
        if (table) tableAcquired(ok);
        else orderRunner.anvilFound(ok);
        version++;
        idle = false;
    }


    // Repairing the order's items with XP bottles (Job.Repair). Not a container job: the items and bottles are already carried
    private final Repairer repairer = new Repairer(new Repairer.Owner() {
        @Override
        public Set<Integer> carry() {
            return ownedCarry();
        }

        @Override
        public boolean calledOff() {
            return orderRunner.calledOff() || !running();
        }

        @Override
        public int delay() {
            return StashSorter.this.delay.get();
        }

        @Override
        public void saveGear(List<int[]> pairs) {
            if (stash == null || (pairs.isEmpty() && stash.repairGear.isEmpty())) return;
            stash.repairGear.clear();
            stash.repairGear.addAll(pairs);
            stash.markDirty();
            stash.save();
        }
    });

    private boolean startRepair(OrderRunner.Repair repair) {
        repairer.start(repair.keys(), orderRunner.repairTicks(), orderRunner.repairRetries());
        job = Job.Repair;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;
        swapStep = 0;
        step("order: repairing items with XP bottles; next: build the shulker.");
        return true;
    }

    private void repairTick() {
        lastOwnAct = ticks;
        if (!repairer.tick()) return;
        repairEnd();
    }

    private void repairEnd() {
        var outcome = repairer.outcome();
        // What the repair changed in the carry slots (durability) is still the order's
        if (mc.player != null) {
            for (int slot : new ArrayList<>(orderHeld.keySet())) {
                ItemStack now = mc.player.getInventory().getItem(slot);
                ItemStack was = orderHeld.get(slot);
                if (!now.isEmpty() && was != null && ItemStack.isSameItem(now, was)) {
                    orderHeld.put(slot, now.copy());
                    stashHeld.put(slot, now.copy());
                }
            }
        }
        job = null;
        target = null;
        wait = delay.get();
        orderRunner.repairEnded(outcome);
        version++;
        idle = false;
    }

    private boolean startName(OrderRunner.Rename rename) {
        String bad = anvilProblem();
        if (bad == null && !walkOn() && !inReachOf(rename.anvil())) bad = NOT_REACHED;
        if (bad != null) {
            orderRunner.nameFailed(bad);
            return runOrder(orderRunner.next());
        }

        namePos = rename.anvil();
        nameText = rename.name();
        nameSlot = relocateSlot = rename.slot();
        nameSent = namePlaced = false;
        nameSentAt = nameJobTicks = nameResultSeen = 0;
        nameTakes = nameCheckTicks = nameOpenTries = nameOpenClickAt = nameDrainTries = 0;
        nameSucceeded = false;
        nameFailure = null;
        if (!java.util.Objects.equals(nameStreakOrder, orderRunner.orderId())) {
            nameStreakOrder = orderRunner.orderId();
            nameClosedStreak = 0;
        }

        job = Job.Name;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;
        swapStep = 0;
        step("order: naming a shulker at the anvil; next: deliver it.");

        if (inReachOf(namePos)) step = Step.Opening;
        else {
            walkNear(namePos, 2);
            // The anvil may be outside the stash area: that is not a reason to refuse the way
            walkInside = false;
            step = Step.Walking;
        }
        return true;
    }

    private void nameTick() {
        stepTicks++;
        lastOwnAct = ticks;
        // The whole job at the anvil (window open) is capped; the walk and the open have their own limits
        if (step == Step.Working) nameJobTicks++;
        if (nameJobTicks > NAME_JOB_TICKS + 400) {
            if (nameFailure == null) nameFailure = NAME_UNFINISHED;
            nameEnd();
            return;
        }
        if (nameJobTicks > NAME_JOB_TICKS && nameFailure == null) nameFailure = NAME_UNFINISHED;
        switch (step) {
            case Walking -> nameWalk();
            case Opening -> nameOpen();
            case Working -> nameWork();
        }
    }

    private void nameWalk() {
        if (mc.level.isLoaded(namePos) && !isAnvil(namePos)) {
            nameFailure = ANVIL_GONE;
            nameEnd();
            return;
        }
        if (inReachOf(namePos)) {
            releaseGoal();
            step = Step.Opening;
            stepTicks = 0;
            nameOpenTries = 0;
            return;
        }
        boolean stopped = stepTicks > PATH_GRACE_TICKS && !BaritoneHelper.isMoving();
        if (stopped || stepTicks > WALK_TIMEOUT) {
            nameFailure = NOT_REACHED;
            nameEnd();
        }
    }

    private void nameOpen() {
        if (!isAnvil(namePos)) {
            nameFailure = ANVIL_GONE;
            nameEnd();
            return;
        }
        AbstractContainerMenu open = mc.player.containerMenu;
        if (open != mc.player.inventoryMenu) {
            if (openPos != null && open != openMenuBefore && open instanceof net.minecraft.world.inventory.AnvilMenu) {
                ownedMenu = open;
                step = Step.Working;
                stepTicks = 0;
                wait = firstClickWait();
                return;
            }
            // Something else opened (one left over, a plugin window): closed, and the click made again
            if (!open.getCarried().isEmpty()) recoverCursor();
            mc.player.closeContainer();
            lastCloseTick = ticks;
        }

        if (openHeld()) {
            stepTicks--;
            return;
        }

        if (stepTicks > NAME_OPEN_TICKS || (nameOpenTries >= NAME_OPEN_TRIES && stepTicks - nameOpenClickAt > NAME_OPEN_RETRY)) {
            nameFailure = NOT_OPENED;
            nameEnd();
            return;
        }

        if (nameOpenTries < NAME_OPEN_TRIES && (nameOpenTries == 0 || stepTicks - nameOpenClickAt >= NAME_OPEN_RETRY)) {
            nameOpenTries++;
            nameOpenClickAt = stepTicks;
            BlockPos pos = namePos;
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            Runnable click = () -> {
                if (gate != Gate.Running || job != Job.Name) return;
                openPos = pos;
                openClickTick = ticks;
                openMenuBefore = mc.player.containerMenu;
                BlockUtils.interact(hit, InteractionHand.MAIN_HAND, true);
            };

            if (rotate.get()) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), click);
            else click.run();
        }
    }

    /** One click in the open anvil window. */
    private void nameClick(int slotId) {
        InvUtils.click().slotId(slotId);
        yielder.inventoryChangedByUs();
        wait = Math.max(delay.get(), 4);
    }

    /**
     * Works out what to do from what the window shows, so it can be picked up at any point: the shulker goes in, the
     * rename is sent, the result is checked and taken into the same slot the shulker came from, and the slot is checked
     * for the name. When it can't be named the shulker is taken back out first.
     */
    private void nameWork() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!(menu instanceof net.minecraft.world.inventory.AnvilMenu anvil) || menu != ownedMenu) {
            nameEnd();
            return;
        }

        ItemStack cursor = menu.getCarried();
        ItemStack input = anvil.getSlot(net.minecraft.world.inventory.AnvilMenu.INPUT_SLOT).getItem();
        ItemStack result = anvil.getSlot(net.minecraft.world.inventory.AnvilMenu.RESULT_SLOT).getItem();
        int home = inventoryToMenu(nameSlot, 3);

        // Something was picked up: it goes back into the slot the shulker came from
        if (!cursor.isEmpty()) {
            int free = freeCarrySlot();
            if (menu.getSlot(home).getItem().isEmpty()) nameClick(home);
            else if (free != -1) nameClick(inventoryToMenu(free, 3));
            else if (!recoverBounded()) nameEnd();
            return;
        }

        if (nameFailure != null) {
            if (!input.isEmpty()) nameClick(net.minecraft.world.inventory.AnvilMenu.INPUT_SLOT);
            else nameEnd();
            return;
        }

        if (input.isEmpty()) {
            if (namePlaced) {
                // Taken out of the anvil: the slot it came from must now carry the name
                if (nameTakes > 0 && !result.isEmpty()) return;
                net.minecraft.network.chat.Component shown = mc.player.getInventory().getItem(nameSlot).get(DataComponents.CUSTOM_NAME);
                if (shown != null && shown.getString().equals(nameText)) {
                    nameSucceeded = true;
                    nameEnd();
                    return;
                }
                if (++nameCheckTicks > NAME_CHECK_TICKS) nameFailure = NOT_TAKEN;
                return;
            }
            if (!orderRunner.claims(mc.player.getInventory().getItem(nameSlot))) {
                nameFailure = CLOSED;
                return;
            }
            InvUtils.move().fromId(home).toId(net.minecraft.world.inventory.AnvilMenu.INPUT_SLOT);
            yielder.inventoryChangedByUs();
            namePlaced = true;
            wait = delay.get();
            return;
        }

        if (!nameSent) {
            // What the anvil screen does when the name box changes
            if (anvil.setItemName(nameText)) mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundRenameItemPacket(nameText));
            nameSent = true;
            nameSentAt = stepTicks;
            wait = delay.get();
            return;
        }

        net.minecraft.network.chat.Component custom = result.get(DataComponents.CUSTOM_NAME);
        boolean named = !result.isEmpty() && Utils.isShulker(result.getItem()) && custom != null && custom.getString().equals(nameText);
        if (!named) {
            nameResultSeen = 0;
            // After a take that did not go through, the result is gone with it: that is the refused take
            if (nameTakes > 0) nameFailure = NOT_TAKEN;
            else if (stepTicks - nameSentAt > NAME_RESULT_TICKS) nameFailure = NO_RESULT;
            return;
        }
        // The server's answer, not only our own guess, is what is taken: the result stays a moment first
        nameResultSeen++;
        int cost = anvil.getCost();
        boolean free = mc.player.hasInfiniteMaterials();
        if (nameResultSeen < NAME_SETTLE_TICKS || (cost <= 0 && !free && nameResultSeen <= NAME_COST_WAIT_TICKS)) return;

        // A rename costs at least one level
        int need = Math.max(1, cost);
        if (!free && need >= NAME_TOO_DEAR) {
            nameFailure = TOO_DEAR;
            return;
        }
        if (!free && mc.player.experienceLevel < need) {
            nameFailure = TOO_FEW_LEVELS;
            return;
        }
        // A take the server keeps refusing is given up on after a few clicks, not repeated
        if (nameTakes >= NAME_MAX_TAKES) {
            nameFailure = NOT_TAKEN;
            return;
        }
        nameTakes++;
        nameClick(net.minecraft.world.inventory.AnvilMenu.RESULT_SLOT);
        wait = Math.max(wait, NAME_SETTLE_TICKS * 2);
    }

    private void rememberAnvil(String why) {
        anvilFailure = why;
        anvilFailedOrder = orderRunner.orderId();
        anvilFailedFor = namePos;
    }

    /**
     * The window is never closed on the shulker: one still in the anvil or on the cursor goes back into the slot it came
     * from (kept free for it) or another free carry slot first, for a few tries.
     * @return true when a click was made and the window stays open
     */
    private boolean nameDrain(AbstractContainerMenu menu) {
        if (!(menu instanceof net.minecraft.world.inventory.AnvilMenu anvil) || menu != ownedMenu || ++nameDrainTries > 20) return false;
        int home = inventoryToMenu(nameSlot, 3);
        if (!menu.getCarried().isEmpty()) {
            int free = freeCarrySlot();
            if (menu.getSlot(home).getItem().isEmpty()) nameClick(home);
            else if (free != -1) nameClick(inventoryToMenu(free, 3));
            else return false;
            return true;
        }
        if (!anvil.getSlot(net.minecraft.world.inventory.AnvilMenu.INPUT_SLOT).getItem().isEmpty()) {
            nameClick(net.minecraft.world.inventory.AnvilMenu.INPUT_SLOT);
            return true;
        }
        return false;
    }

    private void nameEnd() {
        if (ourGoal != null) releaseGoal();
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (nameDrain(menu)) return;
        if (menu != mc.player.inventoryMenu && ourMenu(menu)) {
            if (!menu.getCarried().isEmpty()) recoverCursor();
            closeOurWindow(menu);
        }
        ownedMenu = null;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;
        if (nameFailure != null) {
            QwerteAddon.LOG.info("[stash-sorter] naming given up: {}", nameFailure);
            // An anvil that can't be reached or opened, gives no result or doesn't give the shulker back is not tried again
            // for the rest of this order
            if (nameFailure.equals(NOT_REACHED) || nameFailure.equals(NOT_OPENED) || nameFailure.equals(NO_RESULT) || nameFailure.equals(NOT_TAKEN)) rememberAnvil(nameFailure);
            // A found or placed anvil that can't be reached or opened is forgotten, so the next naming searches again; a hand-set one never
            if ((nameFailure.equals(NOT_REACHED) || nameFailure.equals(NOT_OPENED)) && stash.anvilPos != null && namePos != null && namePos.equals(stash.anvilPos)
                && ("found".equals(stash.anvilSource) || "placed".equals(stash.anvilSource))) {
                stash.anvilPos = null;
                stash.anvilSource = "";
                stash.save();
            }
            orderRunner.nameFailed(nameFailure);
        }
        // Closes that nobody asked for, twice in a row, count as a bad anvil too
        if (nameSucceeded) nameClosedStreak = 0;
        else if (nameFailure == null && ++nameClosedStreak >= 2) rememberAnvil(CLOSED);
        nameSucceeded = false;
        nameFailure = null;
        job = null;
        target = null;
        wait = delay.get();
    }

    /**
     * Between jobs: the shulker that was at the anvil is back in the inventory but not in a carry slot (the window was
     * closed on it), so the order can find it again: it goes to the slot it came from, or any free carry slot.
     * @return true when a click was made
     */
    private boolean relocateNamed() {
        if (relocateSlot < 0) return false;
        var inv = mc.player.getInventory();
        if (!orderRunner.running() || !mc.player.containerMenu.getCarried().isEmpty() || mc.screen != null) {
            if (!orderRunner.running()) relocateSlot = -1;
            return false;
        }
        for (int s : carry) {
            if (orderRunner.claims(inv.getItem(s))) {
                relocateSlot = -1;
                return false;
            }
        }
        int dest = inv.getItem(relocateSlot).isEmpty() ? relocateSlot : freeCarrySlot();
        for (int s = 0; s < 36; s++) {
            if (carry.contains(s) || !orderRunner.claims(inv.getItem(s))) continue;
            // Held but nowhere to put it: tried again later
            if (dest == -1) return false;
            bundleClick(s, 0);
            bundleClick(dest, 0);
            relocateSlot = -1;
            wait = delay.get();
            return true;
        }
        relocateSlot = -1;
        return false;
    }

    /** Puts the order's finished shulker into the delivery container, stopping when it is full. */
    private boolean deliverOne() {
        if (!mc.player.containerMenu.getCarried().isEmpty()) return recoverBounded();
        // A look at a full chest that may have been emptied, not a delivery
        if (recheckOnly) {
            recheckRead();
            return false;
        }

        // What the container held before, to see afterwards that it gained the shulker
        if (actions == 0) orderRunner.deliveryOpened(target.contents);

        for (int slot : carriedSlots()) {
            if (!orderRunner.claims(mc.player.getInventory().getItem(slot))) continue;
            if (!canFit(mc.player.getInventory().getItem(slot))) {
                deliveryChestFull(target);
                orderRunner.deliveryFull();
                return false;
            }
            InvUtils.shiftClick().slotId(inventoryToMenu(slot, target.contents.length));
            return true;
        }
        // Only a read after the settle wait, or of a freshly opened window, is the server's word and not our own prediction
        if (settled || actions == 0) orderRunner.deliveryClosed(target.contents);
        return false;
    }

    // For the Stash Manager module

    OrderRunner orders() {
        return orderRunner;
    }

    /** Why an order can't be taken now, or null if one can. */
    String orderBlocker() {
        if (!running()) return driveBlocked != null ? driveBlocked : "starting";
        if (!shulkersOk()) return "sorter can't use shulkers";
        if (carry.isEmpty()) return "no free inventory slot";
        if (ensureStash() == null) return "no stash";
        if (orderRunner.deliveryBox() == null) return "no delivery box";
        if (kitBuilder.active() && !orderKit()) return "busy: building a kit";
        return orderRunner.holdsOrder() ? "busy" : null;
    }

    /**
     * For stash-manager (the website's rescan button): every known container is read again, nearest first, and the area
     * is explored again for new ones. What is known stays in place, so the index keeps showing it until each is read.
     * Works with sort-items off, and with the sorter off: the reading starts when it is turned on.
     * @return how many containers will be read, or -1 when no stash is loaded
     */
    int rescan() {
        if (ensureStash() == null) return -1;
        int count = 0;
        for (Box box : stash.boxes) {
            if (box.excluded) continue;
            box.stale = true;
            count++;
        }
        forgetExploration();
        explorePending = true;
        stash.save();
        version++;
        idle = false;
        return count;
    }

    /** The server cancelled an order: the sorter wakes up to stop it at the next safe point. */
    boolean orderCancel(String id) {
        boolean held = orderRunner.cancel(id);
        if (held) {
            version++;
            idle = false;
        }
        return held;
    }

    /** A new order was taken: the sorter wakes up for it. */
    void orderTaken() {
        version++;
        idle = false;
    }

    private boolean isKitJob() {
        return job == Job.KitTake || job == Job.KitUnpack || job == Job.KitFill;
    }

    /** A container only kits go in: signed for a KitBuilder kit, or picked as the kits chest or a kit's chest (kit sorting). */
    private boolean isKitBox(Box box) {
        return Kits.isKitBox(box) || kitReserved(box);
    }

    // Kit sorting (0.4.0-dev.14, KIT-CONTRACT.md "What the bot does"). The settings come from the cache the Stash Manager reply fills
    // (KitStore, also while offline). The view is the settings against this stash: which box an id is and which boxes are kit-reserved.
    private KitRouting.View<Box> kitViewCache;
    private Stash kitViewStash;
    private int kitViewStore = -1, kitViewVersion = -1, kitViewBoxes = -1;
    private boolean kitViewOn;
    private KitSettings kitStashSettings;
    // The kit-settings store version the warnings below belong to: they start over only when it changes
    private int kitWarnedVersion = -1;
    // A kit whose chest is missing or cannot take kits is said once per key and container until the settings change
    private final Set<String> kitWarned = new HashSet<>();

    /** Whether kit sorting is on (the kit-sorting setting): stash-manager sends and reports kits only then. */
    public boolean kitSorting() {
        return kitSorting.get();
    }

    /** The kit settings against the stash, or null when kit sorting is off or nothing is set. */
    private KitRouting.View<Box> kitView() {
        Stash st = stash;
        if (!kitSorting.get() || st == null) {
            kitViewCache = null;
            kitViewStash = null;
            kitViewOn = false;
            return null;
        }
        int store = KitStore.shared().version(), boxes = st.boxes.changes();
        // Not rebuilt on every version change: what the view holds (the settings, the boxes ids resolve to) only changes with the store, the stash or its
        // boxes; whether a box can take kits, and its room, are asked live
        if (kitViewOn && st == kitViewStash && store == kitViewStore && boxes == kitViewBoxes) return kitViewCache;
        KitSettings settings = KitStore.shared().get(st.server, st.name);
        kitViewCache = settings == null || settings.isEmpty() ? null : new KitRouting.View<>(new KitRouting.Resolver<Box>() {
            @Override
            public Box boxAt(KitSettings.Pos pos) {
                return st.boxAt(new BlockPos(pos.x(), pos.y(), pos.z()));
            }

            @Override
            public boolean pickable(Box box) {
                return StashIndex.kitPickable(box);
            }

            @Override
            public boolean usable(Box box) {
                return StashIndex.kitPickable(box) && box.free() > 0;
            }
        }, settings);
        kitStashSettings = settings;
        kitViewStash = st;
        kitViewStore = store;
        kitViewVersion = version;
        kitViewBoxes = boxes;
        kitViewOn = true;
        return kitViewCache;
    }

    /** The kits chest or the chest of a kit rule: nothing but kit shulkers goes in, and it is out of overflow, sign scoring, the tiers, compacting, repacking and labelling. */
    private boolean kitReserved(Box box) {
        KitRouting.View<Box> v = kitView();
        return v != null && v.reserved(box);
    }

    /**
     * Whether kit routing may apply to what is in hand: never while a repair holds the carried stacks (an order's own stacks are
     * already out of destinationsFor through orderRunner.claims). Planning passes run with neither going.
     */
    private boolean kitRoutingOn() {
        return kitRoutingFlag;
    }

    // Whether kit routing applies, looked at once per planning pass (plan()); and what kitRouted answered in that pass
    private boolean kitRoutingFlag = true;
    private Map<DestKey, Boolean> kitRoutedCache;

    /** The shulker is one KitBuilder is building or holds: it is not a carried kit for the kit report. */
    public boolean buildingKit(ItemStack stack) {
        return stack != null && !stack.isEmpty() && kitBuilder.heldKit(stack) != null;
    }

    /**
     * What kit settings say about a stack: NOT_KIT for anything that is not a shulker box scoring as a kit. A shulker KitBuilder
     * already routes (a finished or part built kit) keeps KitBuilder's routing, so it is looked at first. The score is worked out once
     * per item, components and scoring (StashIndex.kitScore keeps it); the key is the normalised custom name, null for an unnamed kit.
     */
    private KitRouting.Route<Box> kitRoute(KitRouting.View<Box> v, ItemStack stack) {
        if (stack.isEmpty() || !Utils.isShulker(stack.getItem())) return v.route(null, false);
        if (StashIndex.builderKit(stack) || kitBuilder.heldKit(stack) != null) return v.route(null, false);
        // KIT-CONTRACT v2: the classifier decides (a single kind of item is never a kit); the chain is rule, template chest, type chest, kits chest
        KitClassifier.Result why = StashIndex.classify(stack, v.settings());
        if (why == null || !why.kit()) return v.route(null, false);
        String key = KitNames.keyOf(StashIndex.customName(stack));
        return v.route(key, why, kitBoxOf(v.chainBoxes(key, why), stack));
    }

    /** The chain box that holds this very stack (by identity), or null: carried, or stored elsewhere. At most four boxes are looked at. */
    private static Box kitBoxOf(List<Box> chain, ItemStack stack) {
        for (Box b : chain) {
            if (b.contents == null) continue;
            for (ItemStack i : b.contents) if (i == stack) return b;
        }
        return null;
    }

    /** Whether the kit's chain (rule, template, type, kits chest) contains this container: where a kit may be put back. */
    private boolean kitChainHas(KitRouting.View<Box> v, Box box, ItemStack stack) {
        KitClassifier.Result why = StashIndex.classify(stack, v.settings());
        if (why == null || !why.kit()) return false;
        return v.chainBoxes(KitNames.keyOf(StashIndex.customName(stack)), why).contains(box);
    }

    /** A dashboard kit template has this name (normalised): the local build-kits leaves such a kit to the dashboard. */
    private boolean dashboardKit(String name) {
        Stash st = stash;
        if (st == null || !kitSorting.get()) return false;
        KitSettings settings = KitStore.shared().get(st.server, st.name);
        if (settings == null || settings.types() == null) return false;
        String norm = KitNames.normalize(name);
        for (KitTemplate t : settings.types().templates()) if (KitNames.normalize(t.name()).equals(norm)) return true;
        return false;
    }

    /** The stack is a kit that kit settings send to this very container (the only thing a reserved chest takes back). */
    private boolean kitRoutedTo(Box box, ItemStack stack) {
        KitRouting.View<Box> v = kitView();
        if (v == null || !kitRoutingOn()) return false;
        // A kit goes back into a reserved chest it came from when that chest is anywhere in its chain
        return kitChainHas(v, box, stack);
    }

    /** A kit shulker that has a say in where it goes (a chest, or none to take it). */
    private boolean kitRouted(ItemStack stack) {
        // Only shulker boxes can be kits: nothing else costs a lookup
        if (stack.isEmpty() || !Utils.isShulker(stack.getItem())) return false;
        KitRouting.View<Box> v = kitView();
        if (v == null || !kitRoutingOn()) return false;
        Map<DestKey, Boolean> cache = kitRoutedCache;
        if (cache == null) return kitRoute(v, stack).decision().routed();
        return cache.computeIfAbsent(DestKey.of(stack), k -> kitRoute(v, stack).decision().routed());
    }

    /** The category the classifier gave a kit shulker ("pvp", "c:shop"...), or "" . */
    private String kitType(ItemStack stack) {
        KitClassifier.Result why = StashIndex.classify(stack, kitStashSettings);
        return why == null || why.type() == null ? "" : why.type();
    }

    // A fallback level in use is logged once per (key, type) until the settings change
    private final Set<String> kitFellBack = new HashSet<>();

    private void kitFallback(KitRouting.Decision d, String type) {
        int store = KitStore.shared().version();
        if (store != kitWarnedVersion) {
            kitWarned.clear();
            kitFellBack.clear();
            kitWarnedVersion = store;
        }
        if (kitFellBack.add((d.key() == null ? "\u0000" : d.key()) + "|" + type)) {
            QwerteAddon.LOG.info("[kit-sorting] a kit of type {} goes to its next chest ({}): the first one is full or missing.", type.isEmpty() ? "-" : type, d.kind());
        }
    }

    private void kitMissing(KitRouting.Decision d, String type) {
        // The warnings start over only when the kit-settings store changes, not on every planning pass
        int store = KitStore.shared().version();
        if (store != kitWarnedVersion) {
            kitWarned.clear();
            kitWarnedVersion = store;
        }
        // An unnamed kit has no key: a sentinel, so it never meets a kit really named "null"
        if (kitWarned.add((d.key() == null ? "\u0000" : d.key()) + "|" + type + "|" + d.container())) {
            warning("The chest picked for %s is missing or can't take kits (not read yet, excluded, a drop-off or unreachable). Such a kit stays where it is, and one from a drop-off goes to overflow.",
                d.key() == null ? "an unnamed kit" : "the kit \"" + d.key() + "\"");
            // The position is for the log only: chat may be on stream
            QwerteAddon.LOG.info("[kit-sorting] chest {} (type {}) is missing or can't take kits.", d.container(), type.isEmpty() ? "-" : type);
        }
    }

    // Repacking shulkers

    /**
     * Shulkers may be put down: not paused after a problem, and none left standing that couldn't be picked up.
     */
    private boolean shulkersOk() {
        return ticks >= repackPausedUntil && !dropLost && pendingResume == null;
    }

    private boolean compactOn() {
        return compact.get() && mode.get() == Mode.Signs && shulkersOk();
    }

    /**
     * No more shulkers for a few minutes, sorting goes on.
     */
    private void pauseShulkers(String why) {
        warning("%s, no more shulker work for a few minutes.", why);
        repackPausedUntil = ticks + PAUSE_TICKS;
    }

    private boolean repackOn() {
        return repack.get() && mode.get() == Mode.Signs && shulkersOk();
    }

    /**
     * Cleans a carried mixed shulker, or packs carried items into a bulk shulker, fetching one if needed.
     * @return true when a job was started
     */
    private boolean nextRepackJob() {
        // Compacting has the shulkers and the stacks set aside for it
        if (compactor.active()) return false;

        // A carried shulker with items in it that belong elsewhere: clean it out right here
        if (freeCarrySlot() != -1) {
            for (int slot : carriedSlots()) {
                ItemStack stack = mc.player.getInventory().getItem(slot);
                if (strays(stack).isEmpty()) continue;
                mainItem = Categories.dominantContent(stack).getItem();
                return startHandling(Job.Clean, slot, null);
            }
        }

        // Loose items with a bulk shulker to go in
        for (int slot : carriedSlots()) {
            ItemStack loose = mc.player.getInventory().getItem(slot);
            BulkKey key = keyOf(loose);
            if (key == null || packTried.contains(key)) continue;
            int stacks = carriedStacksOf(key);
            // With compacting on, new bulk shulkers are made by compacting: a full shulker's worth, never a part-filled one
            boolean startNew = !compact.get() && newBulkAt.get() > 0 && stacks >= newBulkAt.get();

            for (int s : carriedSlots()) {
                ItemStack shulker = mc.player.getInventory().getItem(s);
                if (bulkWithRoom(shulker, key) || (startNew && emptyShulker(shulker))) {
                    return startHandling(Job.Fill, s, key);
                }
            }

            if (freeCarrySlot() != -1) {
                Box from = null;
                int fromSlot = -1;
                for (Box box : stash.boxes) {
                    if (!box.sortable() || box.stale || box.dropOff || blocked(box, Job.Fetch) || signPending(box) || isKitBox(box)) continue;
                    for (int i = 0; i < box.contents.length; i++) {
                        ItemStack s = box.contents[i];
                        boolean usable = bulkWithRoom(s, key) || (startNew && emptyShulker(s));
                        if (usable && (from == null || closer(box, from))) {
                            from = box;
                            fromSlot = i;
                            break;
                        }
                    }
                }
                if (from != null) return fetch(from, fromSlot);
            }

            // Nowhere to pack it: it's put away loose
            packTried.add(key);
        }

        return false;
    }

    private static BulkKey keyOf(ItemStack stack) {
        StackInfo info = StackInfo.of(stack);
        return info == null ? null : info.bulkKey();
    }

    private boolean fetch(Box box, int slot) {
        fetchWants = it -> Utils.isShulker(it.getItem());
        wantSlot = slot;
        return start(Job.Fetch, box);
    }

    /**
     * Slots inside a shulker holding items that belong in another signed container than what the shulker is mostly
     * full of. Empty when the shulker isn't one to clean: not a shulker, named, all one item, or wanted whole by a
     * sign like "kits".
     */
    private List<Integer> strays(ItemStack shulker) {
        if (shulker == null || shulker.isEmpty() || !Utils.isShulker(shulker.getItem())) return List.of();
        if (shulker.has(DataComponents.CUSTOM_NAME)) return List.of();

        ItemStack main = Categories.dominantContent(shulker);
        if (main == null) return List.of();

        // A saved kit, finished or part built, stays whole
        if (isKitShulker(shulker)) return List.of();

        // A chest signed for kits takes mixed shulkers whole instead
        if (!keepWholeBoxes().isEmpty()) return List.of();

        List<Integer> out = new ArrayList<>();
        ItemStack[] inside = inside(shulker);
        for (int i = 0; i < inside.length; i++) {
            ItemStack s = inside[i];
            if (s == null || s.isEmpty()) continue;
            if (fullBundle(s)) {
                out.add(i);
                continue;
            }
            if (s.is(main.getItem())) continue;
            if (!bestSigned(s).isEmpty()) out.add(i);
        }
        return out;
    }

    /**
     * A finished or mixed part-built kit, or what is left of a kit just given up on. A one-kind shulker that merely
     * fits inside a kit is an ordinary bulk shulker.
     */
    private boolean isKitShulker(ItemStack shulker) {
        return Kits.claims(shulker) || kitBuilder.heldKit(shulker) != null || orderRunner.claims(shulker) || keptWholeKit(shulker);
    }

    /**
     * A shulker that scores as a kit while kit sorting is on and that has a target (a rule or the kits chest applies, pickable or missing;
     * for an unnamed kit a kits chest is set): kept whole, never unpacked, repacked or compacted. With no settings or no target the
     * shulker is repacked exactly as before. The score uses the cached scoring of the stash (the defaults without a cache).
     */
    private boolean keptWholeKit(ItemStack shulker) {
        if (shulker == null || shulker.isEmpty() || !Utils.isShulker(shulker.getItem())) return false;
        KitRouting.View<Box> v = kitView();
        return v != null && kitRoute(v, shulker).decision().routed();
    }

    /**
     * A shulker holding only stacks of this bulk key, with room for more.
     */
    private boolean bulkWithRoom(ItemStack shulker, BulkKey key) {
        if (shulker == null || shulker.isEmpty() || !Utils.isShulker(shulker.getItem()) || shulker.has(DataComponents.CUSTOM_NAME) || isKitShulker(shulker)) return false;

        List<StackInfo> contents = StackInfo.of(shulker).contents();
        if (contents.isEmpty()) return false;
        boolean room = contents.size() < 27;
        for (StackInfo s : contents) {
            if (!key.equals(s.bulkKey())) return false;
            if (s.count < s.maxStack) room = true;
        }
        return room;
    }

    private boolean emptyShulker(ItemStack shulker) {
        if (shulker == null || shulker.isEmpty() || !Utils.isShulker(shulker.getItem()) || shulker.has(DataComponents.CUSTOM_NAME) || Kits.claims(shulker) || orderRunner.claims(shulker)) return false;
        for (ItemStack s : inside(shulker)) if (s != null && !s.isEmpty()) return false;
        return true;
    }

    private static ItemStack[] inside(ItemStack shulker) {
        return Kits.inside(shulker);
    }

    private int carriedStacksOf(BulkKey key) {
        int count = 0;
        for (int slot : carriedSlots()) if (key.equals(keyOf(mc.player.getInventory().getItem(slot)))) count++;
        return count;
    }

    private int freeCarryCount() {
        int count = 0;
        for (int slot : carry) if (mc.player.getInventory().getItem(slot).isEmpty()) count++;
        return count;
    }

    private boolean closer(Box a, Box b) {
        Vec3 eyes = mc.player.getEyePosition();
        return eyes.distanceToSqr(Vec3.atCenterOf(nearestPart(a))) < eyes.distanceToSqr(Vec3.atCenterOf(nearestPart(b)));
    }

    private boolean startHandling(Job next, int slot, BulkKey fill) {
        restoreHotbar();
        resetWander();
        job = next;
        target = null;
        handledSlot = handledOrigin = slot;
        fillKey = fill;
        handle = Handle.Place;
        handleTicks = 0;
        breakRetries = 0;
        brokenSince = -1;
        placed = null;
        placedConfirmed = false;
        shulkersAtBreak = -1;
        actions = 0;
        collectedSlot = -1;
        fillSample = ItemStack.EMPTY;
        if (fill != null) {
            Compactor.Plan active = compactor.plan;
            if (active != null && fill.equals(active.key)) fillSample = active.sample.copy();
            else {
                for (int s : carriedSlots()) {
                    ItemStack stack = mc.player.getInventory().getItem(s);
                    if (fill.equals(keyOf(stack))) {
                        fillSample = stack.copyWithCount(1);
                        break;
                    }
                }
            }
        }
        BlockPos here = mc.player.blockPosition();
        if (resuming) return true;
        switch (next) {
            case Clean -> {
                ItemStack shulker = mc.player.getInventory().getItem(slot);
                int n = shulker.isEmpty() ? 0 : strays(shulker).size();
                step(String.format("repacking: cleaning a mixed shulker beside %s; next: break and pick it up, then put away %d stacks.", xyz(here), n));
            }
            case Fill -> step(String.format("repacking: filling a shulker beside %s; next: break and pick it up, then put it away.", xyz(here)));
            case KitFill -> step(String.format(orderKit() ? "order: filling the shulker for the order beside %s; next: break and pick it up." : "kits: filling a kit shulker beside %s; next: break and pick it up.", xyz(here)));
            case CraftUnpack -> step(String.format("crafting: taking what the craft needs out of a shulker beside %s; next: break and pick it up.", xyz(here)));
            case KitUnpack -> step(String.format(orderKit() ? "order: taking what the order needs out of a shulker beside %s; next: break and pick it up." : "kits: unpacking a kit shulker beside %s; next: break and pick it up.", xyz(here)));
            default -> { }
        }
        return true;
    }

    /**
     * One step of repacking a shulker beside us: place, open, move items, break, pick back up.
     */
    private void tickHandling() {
        handleTicks++;

        // Written down whenever the shulker's state changes, so a restart can finish the step
        if (handle != syncedHandle || !java.util.Objects.equals(placed, syncedPlaced) || placedConfirmed != syncedConfirmed) {
            syncedHandle = handle;
            syncedPlaced = placed;
            syncedConfirmed = placedConfirmed;
            syncInFlight();
        }
        if (resuming && ticks - resumeStarted > RESUME_MAX_TICKS && handle != null) {
            abandonShulker("Couldn't finish the shulker step at " + (placed == null ? "the saved place" : placed.toShortString()) + " within 10 minutes.");
            return;
        }

        switch (handle) {
            case Place -> handlePlace();
            case Wander -> handleWander();
            case Open -> handleOpen();
            case Work -> handleWork();
            case Break -> handleBreak();
            case Collect -> handleCollect();
            case Return -> handleReturn();
            case Leave -> handleLeave();
            case Resume -> handleResume();
        }
    }

    private void handlePlace() {
        ItemStack shulker = mc.player.getInventory().getItem(handledSlot);
        if (!Utils.isShulker(shulker.getItem())) {
            endHandling();
            return;
        }

        // Shulkers are not put down in quick succession: that is what a build bot does
        if (ticks - lastPlaceTick < PLACE_GAP_TICKS) {
            wait = 10;
            return;
        }

        // Where first: nothing is moved for a spot that isn't there
        // The spot a walk was made for is placed at directly; else one beside me
        BlockPos spot = wanderSpot != null && BlockUtils.canPlace(wanderSpot, true) && spotProblem(wanderSpot) == null ? wanderSpot : null;
        if (spot == null) {
            wanderSpot = null;
            spot = safeSpot();
        }
        if (spot == null) {
            // Nothing beside me: the nearest spot within 5 blocks that can be walked to, and only when there is none the work pauses
            String why = startWander();
            if (why == null) return;
            pauseShulkers("no spot within " + PlaceSearch.RADIUS + " blocks: " + why);
            endHandling();
            return;
        }
        wanderSpot = null;

        // It has to be in the hotbar to place it
        int hotbar = handledSlot;
        if (hotbar >= 9) {
            hotbar = -1;
            Set<Integer> keep = parseSlots(keepSlots.get());
            // An empty slot first, so nothing of the player's moves
            for (int i = 0; i < 9 && hotbar == -1; i++) if (!keep.contains(i) && mc.player.getInventory().getItem(i).isEmpty()) hotbar = i;
            if (hotbar == -1) {
                for (int i = 0; i < 9 && hotbar == -1; i++) if (!keep.contains(i)) hotbar = i;
                if (hotbar != -1) {
                    // The player's item ends up in the carry slot: it's theirs until it's put back
                    swapBackFrom = handledSlot;
                    swapBackTo = hotbar;
                    hotbarDisplaced = mc.player.getInventory().getItem(hotbar).copy();
                }
            }
            if (hotbar == -1) {
                pauseShulkers("No hotbar slot I'm allowed to use to place shulkers");
                endHandling();
                return;
            }
            InvUtils.move().from(handledSlot).to(hotbar);
            handledSlot = hotbar;
            wait = delay.get();
            return;
        }

        shulkersBefore = countShulkers();
        placedStacks = StackInfo.of(shulker).contents().size();
        if (!BlockUtils.place(spot, new FindItemResult(hotbar, shulker.getCount()), rotate.get(), 50, true, true, true)) {
            placeFailed(spot);
            return;
        }

        lastPlaceTick = ticks;
        placed = spot;
        placedConfirmed = false;
        placedItemId = BuiltInRegistries.ITEM.getKey(shulker.getItem()).getPath();
        // Written down now, in case the block never shows: it is a shulker standing somewhere until it's picked up
        recordOrphan(spot, placedName(), placedStacks);
        shulkerSlotsBefore.clear();
        for (int i = 0; i < 36; i++) {
            if (i != handledSlot && Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) shulkerSlotsBefore.add(i);
        }
        handle = Handle.Open;
        handleTicks = 0;
        wait = delay.get();
    }

    /**
     * A spot where the shulker would not go: never tried again. After three in a row shulkers are left alone for a while.
     */
    private void placeFailed(BlockPos spot) {
        failedSpots.add(spot);
        debug("Couldn't put a shulker at %s.", spot.toShortString());
        if (++placeFailures >= 3) {
            placeFailures = 0;
            pauseShulkers("Couldn't put the shulker down (3 spots failed)");
            endHandling();
            return;
        }
        wait = 5;
    }

    private boolean shulkerAt(BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock;
    }

    private void handleOpen() {
        // Put back whatever we swapped out of the hotbar to place it, as soon as it's safe to
        if (swapBackFrom != -1) {
            restoreHotbar();
            if (swapBackFrom == -1) {
                wait = delay.get();
                return;
            }
        }

        // The place may still be on its way: longer while the shulker is out of the inventory
        if (!shulkerAt(placed)) {
            if (placedConfirmed) {
                // Was there, now it isn't: broken, or picked up by someone
                handle = Handle.Collect;
                handleTicks = 0;
                return;
            }
            boolean gone = countShulkers() < shulkersBefore;
            if (handleTicks <= (gone ? 200 : 30)) return;

            if (gone) {
                // Out of the inventory but no block: a late update, or it's on the ground. Looked for, and on record
                warning("The shulker I put down at %s hasn't shown up as a block, looking for it.", placed.toShortString());
                placedConfirmed = true;
                handle = Handle.Collect;
                handleTicks = 0;
                return;
            }

            // Never got placed after all, it's still in the inventory
            BlockPos failed = placed;
            clearOrphan(failed);
            placed = null;
            handle = Handle.Place;
            handleTicks = 0;
            placeFailed(failed);
            return;
        }
        if (!placedConfirmed) {
            placedConfirmed = true;
            placeFailures = 0;
            recordOrphan(placed, placedName(), placedStacks);
        }
        lastGoodStand = mc.player.blockPosition();

        if (mc.player.containerMenu instanceof ShulkerBoxMenu) {
            // A shulker picked up again from a saved step is only worked with if it holds what the record says
            if (resumeVerify) {
                resumeVerify = false;
                String problem = resumeContentsProblem(mc.player.containerMenu);
                if (problem != null) {
                    int actual = 0;
                    for (int i = 0; i < 27; i++) if (!mc.player.containerMenu.getSlot(i).getItem().isEmpty()) actual++;
                    closeOurWindow(mc.player.containerMenu);
                    alert("The shulker at %s doesn't hold what I expected (%s), leaving it alone.", placed.toShortString(), problem);
                    // Standing there, with what it really holds
                    recordOrphan(placed, placedName(), actual);
                    compactor.drop();
                    dropLost = true;
                    endHandling();
                    return;
                }
            }
            ownedMenu = mc.player.containerMenu;
            shulkerSlotsBefore.clear();
            for (int i = 0; i < 36; i++) if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) shulkerSlotsBefore.add(i);
            handle = Handle.Work;
            handleTicks = 0;
            wait = firstClickWait();
            return;
        }

        // container-delay: the open click waits, and the wait does not count toward the open timeout
        if (openHeld()) {
            handleTicks--;
            return;
        }

        if (handleTicks > HANDLE_TIMEOUT) {
            handle = Handle.Break;
            handleTicks = 0;
            return;
        }

        if (handleTicks == 1 || handleTicks % 20 == 0) {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(placed), Direction.UP, placed, false);
            BlockUtils.interact(hit, InteractionHand.MAIN_HAND, true);
        }
    }

    /**
     * What the shulker in the world is holding, for the records.
     */
    private String placedName() {
        Compactor.Plan plan = compactor.plan;
        if (plan != null) return plan.name;
        return fillKey != null ? new ItemStack(fillKey.item()).getItemName().getString() : "items";
    }

    private void handleWork() {
        if (!(mc.player.containerMenu instanceof ShulkerBoxMenu menu)) {
            handle = Handle.Break;
            handleTicks = 0;
            return;
        }

        // A saved step with nothing to rebuild the work from: the shulker is just picked up
        if (resumeFinishOnly) {
            if (!menu.getCarried().isEmpty()) recoverCursor();
            closeOurWindow(mc.player.containerMenu);
            handle = Handle.Break;
            handleTicks = 0;
            wait = delay.get();
            return;
        }

        boolean packing = job == Job.Fill && compactor.active();
        boolean busy = switch (job) {
            case Clean -> takeStray(menu);
            case KitUnpack -> kitBuilder.unpackOne(menu);
            case CraftUnpack -> crafter.unpackOne(menu);
            case KitFill -> kitBuilder.fillOne(menu);
            default -> packing ? compactor.packOne(menu) : packOne(menu);
        };
        if (busy) {
            actions++;
            wait = delay.get();
            return;
        }

        // Never close with something on the cursor, it would be merged into the inventory or dropped
        if (!menu.getCarried().isEmpty()) recoverCursor();

        if (packing) {
            boolean stay = compactor.afterPacking(menu, actions, placed);
            closeOurWindow(mc.player.containerMenu);
            handle = stay ? Handle.Leave : Handle.Break;
            handleTicks = 0;
            wait = delay.get();
            return;
        }

        if (job == Job.Fill && actions == 0 && fillKey != null) packTried.add(fillKey);
        // Nothing to take out after all: stop trying, or we'd keep reopening the same shulker
        if (job == Job.Clean && actions == 0) pauseShulkers("There was nothing to take out of the shulker");
        if (isKitJob() && actions == 0) kitBuilder.suspend("there was nothing I could move in the shulker.");
        if (job == Job.CraftUnpack && actions == 0) crafter.unpackFailed();
        closeOurWindow(mc.player.containerMenu);
        handle = Handle.Break;
        handleTicks = 0;
        wait = delay.get();
    }

    /**
     * Takes one stack that belongs elsewhere out of the open shulker, into a free carry slot. One slot stays free, for
     * picking the shulker up.
     */
    private boolean takeStray(AbstractContainerMenu menu) {
        int into = freeCarrySlot();
        if (into == -1 || freeCarryCount() <= 1) return false;

        for (int i = 0; i < 27; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty() || (!fullBundle(s) && (s.is(mainItem) || bestSigned(s).isEmpty()))) continue;
            stashHeld.put(into, s.copy());
            InvUtils.move().fromId(i).toId(inventoryToMenu(into, 27));
            moved++;
            return true;
        }
        return false;
    }

    /**
     * Puts one carried stack of the item being packed into the open shulker.
     */
    private boolean packOne(AbstractContainerMenu menu) {
        for (int slot : carriedSlots()) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (fillKey == null || !fillKey.equals(keyOf(stack))) continue;

            for (int i = 0; i < 27; i++) {
                ItemStack here = menu.getSlot(i).getItem();
                if (here.isEmpty() || (ItemStack.isSameItemSameComponents(here, stack) && here.getCount() < here.getMaxStackSize())) {
                    InvUtils.shiftClick().slotId(inventoryToMenu(slot, 27));
                    moved++;
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /**
     * The round is packed and more is wanted: the window is closed and the shulker stays where it is while the next round
     * is gathered.
     */
    private void handleLeave() {
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            closeOurWindow(mc.player.containerMenu);
            return;
        }
        // Left standing: the plan knows where, and the next round comes back to it
        Compactor.Plan plan = compactor.plan;
        if (plan != null) {
            plan.placedPos = placed;
            plan.dimension = mc.level.dimension().toString();
        }
        endHandling();
    }

    private void handleBreak() {
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            closeOurWindow(mc.player.containerMenu);
            return;
        }

        // The break is reported as soon as the block looks gone here, but the server can still refuse it and put the
        // shulker back: it only counts as broken once the spot has stayed clear for a moment
        if (!shulkerAt(placed)) {
            if (brokenSince < 0) brokenSince = ticks;
            if (ticks - brokenSince < BREAK_SETTLE_TICKS) return;
            brokenSince = -1;
            if (pickaxeSwapped) {
                InvUtils.swapBack();
                pickaxeSwapped = false;
            }
            handle = Handle.Collect;
            handleTicks = 0;
            return;
        }
        brokenSince = -1;
        // It can't be picked up while it stands: what is held now is what picking it up adds to
        shulkersAtBreak = countShulkers();

        // Breaking it needs a free slot to pick it up into: otherwise it would end up on the ground
        if (!freeInventorySlot()) {
            if (handleTicks % 200 == 1) alert("No free inventory slot to pick the shulker at %s up into, leaving it where it is until there is one.", placed.toShortString());
            if (handleTicks > 20 * 60 * 5) abandonShulker("No free inventory slot to pick the shulker at " + placed.toShortString() + " up into.");
            return;
        }

        if (++breakTicks > HANDLE_TIMEOUT * 2) {
            abandonShulker("Couldn't break the shulker at " + placed.toShortString() + ", it's still there.");
            return;
        }

        if (!pickaxeSwapped) {
            FindItemResult pickaxe = InvUtils.findInHotbar(s -> s.is(ItemTags.PICKAXES));
            if (pickaxe.found()) {
                InvUtils.swap(pickaxe.slot(), true);
                pickaxeSwapped = true;
            }
        }
        BlockUtils.breakBlock(placed, true);
    }

    private static final int BREAK_SETTLE_TICKS = 10;
    private static final int MAX_BREAK_RETRIES = 3;
    private int breakRetries;
    private int brokenSince = -1;

    private static String fmtPos(BlockPos pos) {
        return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }

    private boolean shulkerItemNearby() {
        return shulkerItemNearby(4);
    }

    private boolean shulkerItemNearby(int radius) {
        for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(placed).inflate(radius))) {
            if (Utils.isShulker(e.getItem().getItem())) return true;
        }
        return false;
    }

    private void handleCollect() {
        breakTicks = 0;
        // The server put the shulker back: it is broken again rather than looked for on the ground, where it never was
        if (shulkerAt(placed) && !shulkerItemNearby()) {
            if (++breakRetries > MAX_BREAK_RETRIES) {
                abandonShulker("Couldn't break the shulker at " + fmtPos(placed) + ", the server keeps putting it back. It's still standing there.");
                return;
            }
            alert("The shulker at %s is still standing, the server didn't let it break. Breaking it again (%d/%d).", fmtPos(placed), breakRetries, MAX_BREAK_RETRIES);
            brokenSince = -1;
            handle = Handle.Break;
            handleTicks = 0;
            return;
        }
        // A block that turns up after all (a late update) is worked with again
        if (shulkerAt(placed)) {
            handle = Handle.Open;
            handleTicks = 0;
            return;
        }

        if (pickedUp()) {
            // Picked up: the new shulker, or failing that the one that holds what the plan was packing, in any slot
            int found = -1;
            for (int i = 0; i <= 40 && found == -1; i++) {
                if ((i >= 36 && i < 40) || shulkerSlotsBefore.contains(i)) continue;
                if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) found = i;
            }
            Compactor.Plan plan = compactor.plan;
            if (found == -1 && plan != null) {
                for (int i = 0; i <= 40 && found == -1; i++) {
                    if (i >= 36 && i < 40) continue;
                    ItemStack there = mc.player.getInventory().getItem(i);
                    if (Utils.isShulker(there.getItem()) && StackInfo.of(there).contents().stream().anyMatch(c -> plan.key.equals(c.bulkKey()))) found = i;
                }
            }
            if (found == -1) found = heldLikePlaced(false);

            if (found == -1) {
                alert("I picked up the shulker from %s but couldn't tell which slot it is in.", placed.toShortString());
                compactor.collected(-1, placed, ItemStack.EMPTY);
            }
            else {
                ItemStack got = mc.player.getInventory().getItem(found).copy();
                collectedSlot = found;
                if (found < 36 && !carry.contains(found)) {
                    int free = freeCarrySlot();
                    if (free != -1) {
                        InvUtils.move().from(found).to(free);
                        collectedSlot = free;
                    }
                    else {
                        // No carry slot to move it to: that slot is carried from now on, so it gets put away
                        warning("The repacked shulker ended up outside the carry slots, in slot %d, treating it as carried.", found + 1);
                        carry.add(found);
                        stashHeld.put(found, got.copy());
                    }
                }
                else if (found >= 36) {
                    warning("The repacked shulker ended up in your offhand, please put it in your inventory.");
                }
                compactor.collected(collectedSlot, placed, got);
            }
            clearOrphan(placed);
            endHandling();
            return;
        }

        if (handleTicks == 20 * 30 || handleTicks == 20 * 60) {
            alert("Still looking for the shulker I broke at %s, it isn't in my inventory yet (%d s).", placed.toShortString(), handleTicks / 20);
        }
        if (handleTicks > COLLECT_TIMEOUT) {
            abandonShulker("Couldn't pick the shulker back up, it's on the ground near " + placed.toShortString() + ".");
            return;
        }

        if (leftArea()) {
            abandonShulker("Skipping the shulker box at " + at(placed) + ": the way there leaves the stash area.");
            return;
        }

        // Dropped out of reach: walk over it
        if (handleTicks >= 40 && handleTicks % 200 == 40 && walkOn()) {
            ItemEntity drop = null;
            for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(placed).inflate(4))) {
                if (Utils.isShulker(e.getItem().getItem())) {
                    drop = e;
                    break;
                }
            }
            if (drop != null) {
                walkNear(drop.blockPosition(), 0);
            }
        }
    }

    private static final int PICKUP_GRACE_TICKS = 20 * 3;

    /**
     * Whether the shulker we broke is in the inventory now.
     * <p>
     * The shulker count alone was not enough: a shulker resumed after a reconnect may already be held, and a count taken
     * at the wrong moment never comes back up, so the bot kept looking on the ground for a shulker it was holding. So
     * once nothing is lying near the spot for a few seconds, a held shulker that matches it counts as picked up.
     */
    private boolean pickedUp() {
        int now = countShulkers();
        if (now >= shulkersBefore || (shulkersAtBreak >= 0 && now > shulkersAtBreak)) return true;
        // Only standing close by with nothing lying in sight, and only when exactly one held shulker could be it
        // (handleCollect has already gone back to breaking or opening if the block is there again)
        if (handleTicks <= PICKUP_GRACE_TICKS || mc.player.blockPosition().distSqr(placed) > 6 * 6 || shulkerItemNearby(8)) return false;
        int held = heldLikePlaced(true);
        if (held != -1) info("Nothing dropped near %s and I hold a shulker like it in slot %d, taking it as picked up.", placed.toShortString(), held < 36 ? held + 1 : 41);
        return held != -1;
    }

    /**
     * The inventory slot (40 for the offhand) of a held shulker that could be the one we put down: the same colour,
     * holding what the job put in or left in it. -1 if there is none, or with {@code unique} if more than one could be.
     */
    private int heldLikePlaced(boolean unique) {
        Compactor.Plan plan = compactor.plan;
        int match = -1;
        for (int i = 0; i <= 40; i++) {
            if (i >= 36 && i < 40) continue;
            ItemStack there = mc.player.getInventory().getItem(i);
            if (!Utils.isShulker(there.getItem())) continue;
            if (!placedItemId.isEmpty() && !BuiltInRegistries.ITEM.getKey(there.getItem()).getPath().equals(placedItemId)) continue;
            List<StackInfo> inside = StackInfo.of(there).contents();
            if (inside.isEmpty()) continue;
            boolean fits;
            if (plan != null) fits = inside.size() == plan.placedSize && inside.stream().allMatch(c -> plan.key.equals(c.bulkKey()));
            else if (job == Job.Fill && fillKey != null) fits = inside.stream().allMatch(c -> fillKey.equals(c.bulkKey()));
            else if (job == Job.Clean && mainItem != null) {
                String main = BuiltInRegistries.ITEM.getKey(mainItem).getPath();
                fits = inside.stream().allMatch(c -> main.equals(c.id));
            }
            else fits = false;
            if (!fits) continue;
            if (!unique) return i;
            if (match != -1) return -1;
            match = i;
        }
        return match;
    }

    private void endHandling() {
        resetWander();
        // A shulker cleaned or filled is real headway: the counters start again
        if (job == Job.Clean || job == Job.Fill) {
            passJobs = 0;
            boxVisits.clear();
            stalled.removeAll(circular);
            circular.clear();
        }
        if (job == Job.CraftUnpack) {
            int now = collectedSlot >= 0 ? collectedSlot : handledSlot;
            crafter.relocated(handledOrigin, now);
            noteCollected(handledOrigin, now);
        }
        if (job == Job.KitFill || job == Job.KitUnpack) {
            kitBuilder.handled(job == Job.KitFill, collectedSlot >= 0 ? collectedSlot : handledSlot);
            // The shulker picked up again: an unpacked one goes back to the stash in scan-only mode
            noteCollected(handledOrigin, collectedSlot >= 0 ? collectedSlot : handledSlot);
        }
        handle = null;
        placed = null;
        placedConfirmed = false;
        shulkersAtBreak = -1;
        breakRetries = 0;
        brokenSince = -1;
        lastOwnAct = ticks;
        restoreHotbar();
        if (pickaxeSwapped) {
            InvUtils.swapBack();
            pickaxeSwapped = false;
        }
        syncedHandle = null;
        syncedPlaced = null;
        syncedConfirmed = false;
        if (resuming) finishResume();
        else syncInFlight();
        syncHeld();
        if (stash.isDirty()) stash.save();
        job = null;
        wait = delay.get();
    }

    /**
     * A shulker that could not be broken or picked up, or walked back to: said loudly, kept in the stash file and the
     * status with what it holds and where, and no more shulkers are put down until sorting is restarted. Sorting carries on.
     */
    private void abandonShulker(String why) {
        Compactor.Plan plan = compactor.plan;
        BlockPos at = placed != null ? placed : plan != null ? plan.placedPos : null;
        String what = placedName();
        int stacks = plan != null ? plan.placedSize : placedStacks;
        alertError("%s It holds %s of %s. No more shulkers are put down until sorting is restarted.", why, stacks > 0 ? stacks + " stacks" : "some stacks", what);
        if (at != null) recordOrphan(at, what, stacks);
        dropLost = true;
        compactor.drop();
        if (isKitJob()) kitBuilder.suspend(why);
        if (job == Job.CraftUnpack) crafter.abort(why);
        endHandling();
    }

    /**
     * Puts the player's hotbar item back where it was, once no screen is open and nothing is on the cursor, if it's still
     * where the move left it. Nothing is moved otherwise: a stale record is dropped.
     */
    private void restoreHotbar() {
        if (swapBackFrom == -1) return;
        if (mc.player == null || mc.screen != null || mc.player.containerMenu != mc.player.inventoryMenu
            || !mc.player.containerMenu.getCarried().isEmpty()) return;

        int from = swapBackFrom, to = swapBackTo;
        ItemStack displaced = hotbarDisplaced;
        swapBackFrom = swapBackTo = -1;
        hotbarDisplaced = ItemStack.EMPTY;

        if (displaced.isEmpty() || !ItemStack.matches(mc.player.getInventory().getItem(from), displaced)) return;
        ItemStack hot = mc.player.getInventory().getItem(to);
        // Into the free slot, or in place of the shulker still there (which goes back to the carry slot)
        if (hot.isEmpty() || Utils.isShulker(hot.getItem())) InvUtils.move().from(from).to(to);
    }

    // The offline safety net

    /**
     * The stacks carried from the stash go into the stash file with it, so they are still known as the stash's after a
     * restart.
     */
    private void syncHeld() {
        if (stash == null) return;
        boolean same = stash.held.size() == stashHeld.size();
        if (same) {
            for (Map.Entry<Integer, ItemStack> entry : stashHeld.entrySet()) {
                ItemStack saved = stash.held.get(entry.getKey());
                if (saved == null || !ItemStack.matches(saved, entry.getValue())) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return;
        stash.held.clear();
        for (Map.Entry<Integer, ItemStack> entry : stashHeld.entrySet()) stash.held.put(entry.getKey(), entry.getValue().copy());
        stash.markDirty();
    }

    /**
     * What the stash file said we were carrying is carried again, where the inventory still holds exactly that.
     */
    private void restoreHeld() {
        if (stash == null || mc.player == null) return;
        for (Map.Entry<Integer, ItemStack> entry : stash.held.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= 36) continue;
            ItemStack there = mc.player.getInventory().getItem(slot);
            if (!there.isEmpty() && ItemStack.isSameItemSameComponents(there, entry.getValue())) stashHeld.putIfAbsent(slot, there.copy());
        }
    }

    /**
     * Where a shulker of ours stands in the world right now, or null.
     */
    private BlockPos standingShulker() {
        Compactor.Plan plan = compactor.plan;
        if (handle != null && placed != null && job != null) return placed;
        if (plan != null && plan.placedPos != null) return plan.placedPos;
        return null;
    }

    private static String blockId(net.minecraft.world.level.Level level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
    }

    private Stash.InFlight buildInFlight() {
        BlockPos at = standingShulker();
        if (at == null) return null;

        Compactor.Plan plan = compactor.plan;
        boolean live = handle != null && placed != null && job != null;
        Stash.InFlight f = new Stash.InFlight();
        f.savedAt = System.currentTimeMillis();
        f.dimension = mc.level != null ? mc.level.dimension().toString() : cachedDimension;
        f.job = live ? job.name() : Job.Fill.name();
        f.handle = live ? handle.name() : Handle.Leave.name();
        f.returnTo = live && returnTo != null ? returnTo.name() : "";
        f.placed = at;
        f.confirmed = live ? placedConfirmed : true;
        f.placedItemId = mc.level != null && shulkerAt(at) ? blockId(mc.level, at) : placedItemId;
        f.placedStacks = plan != null ? plan.placedSize : placedStacks;
        f.fillSample = plan != null ? plan.sample.copy() : live && fillKey != null ? fillSample.copy() : ItemStack.EMPTY;
        f.cleanMainId = live && job == Job.Clean && mainItem != null ? BuiltInRegistries.ITEM.getKey(mainItem).getPath() : "";
        f.lastGoodStand = lastGoodStand;
        f.target = target == null ? null : target.pos;
        f.compact = compactor.snapshot();
        return f;
    }

    /**
     * Writes the state of the shulker step in hand into the stash file (or removes it when none is standing), together
     * with what is carried. Called at every change that matters, so the file is never behind by more than a tick.
     */
    private void syncInFlight() {
        syncInFlight(false);
    }

    /**
     * {@code leaving}: the world may already be gone, so it is written from the sorter's own fields and the dimension
     * and registries remembered earlier.
     */
    private void syncInFlight(boolean leaving) {
        if (stash == null || (!leaving && (mc.level == null || mc.player == null))) return;
        // A record waiting to be finished is not ours to rewrite until we are at it
        if (pendingResume != null && !resuming) return;

        syncHeld();
        Stash.InFlight record = buildInFlight();
        if (record == null && stash.inFlight == null) return;
        stash.inFlight = record;
        stash.markDirty();
        stash.save();
    }

    private static Item itemByPath(String path) {
        if (path == null || path.isEmpty()) return null;
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.getKey(item).getPath().equals(path)) return item;
        }
        return null;
    }

    private List<Box> staleBoxes() {
        if (stash == null) return List.of();
        return stash.boxes.stream().filter(b -> b.stale && !b.excluded && !b.foreign && !b.unreachable && !failedSurvey.contains(b) && !signPending(b)).toList();
    }

    /**
     * A saved shulker step is picked up again: the shulker is walked to, checked to be the one we put down, and worked
     * with where it stands. Before anything else is done.
     * @return true when the step has begun
     */
    private boolean startResume() {
        Stash.InFlight r = pendingResume;
        if (r == null || stash == null || resuming) return false;

        String here = mc.level.dimension().toString();
        if (!r.dimension.isEmpty() && !r.dimension.equals(here)) {
            if (!resumeDimensionSaid) {
                resumeDimensionSaid = true;
                alert("A shulker I left at %s is in another dimension, it will be finished when I'm back there. No shulkers are put down until then.", r.placed.toShortString());
            }
            return false;
        }
        if (mc.player.containerMenu != mc.player.inventoryMenu) return false;

        // What the shulker is for, rebuilt: a compaction, a packing of one item, a cleaning, or only to be picked up
        Compactor.Plan plan = r.compact != null ? compactor.restore(r.compact, r.dimension) : null;
        Job rebuilt = Job.Fill;
        BulkKey key = null;
        boolean finishOnly = false;
        if (plan != null) key = plan.key;
        else if (r.job.equals(Job.Clean.name()) && itemByPath(r.cleanMainId) != null) {
            rebuilt = Job.Clean;
            mainItem = itemByPath(r.cleanMainId);
        }
        else if (r.job.equals(Job.Fill.name()) && !r.fillSample.isEmpty() && keyOf(r.fillSample) != null) key = keyOf(r.fillSample);
        else finishOnly = true;
        if (plan == null && rebuilt == Job.Fill && key == null) finishOnly = true;

        resuming = true;
        resumeStarted = ticks;
        resumeVerify = true;
        resumeFinishOnly = finishOnly;
        startHandling(rebuilt, -1, key);
        fillSample = r.fillSample.copy();
        placed = r.placed;
        placedConfirmed = true;
        placedItemId = r.placedItemId;
        placedStacks = r.placedStacks;
        lastGoodStand = r.lastGoodStand;
        returnTo = r.handle.equals(Handle.Collect.name()) ? Handle.Collect : Handle.Open;
        handle = Handle.Resume;
        handleTicks = 0;

        String what = plan != null ? plan.name : rebuilt == Job.Clean && mainItem != null ? new ItemStack(mainItem).getItemName().getString() : fillSample.isEmpty() ? "items" : StackInfo.label(fillSample);
        int unread = (int) stash.boxes.stream().filter(b -> !b.excluded && !b.foreign && (b.stale || !b.known())).count();
        step(String.format("resuming: shulker of %s (%d stacks) at %s; next: %s, then re-read %d containers.", what, r.placed == null ? 0 : (plan != null ? plan.placedSize : r.placedStacks), xyz(r.placed),
            returnTo == Handle.Collect ? "pick it up, store it" : finishOnly ? "break and pick it up, store it" : "open it, finish packing, break and pick it up, store it", unread));
        return true;
    }

    /**
     * The first step of a resumed shulker: get to where it stands, make sure it is the one we put down, then on to
     * opening it (or picking it up, if it was already broken).
     */
    private void handleResume() {
        Stash.InFlight r = pendingResume;
        if (r == null || placed == null) {
            endHandling();
            return;
        }

        // The chunk is not there yet: walk to where we last stood
        if (!mc.level.isLoaded(placed)) {
            if (handleTicks == 1 || handleTicks % 100 == 0) {
                if (walkOn()) walkNear(lastGoodStand != null ? lastGoodStand : placed, 1);
            }
            return;
        }

        if (!shulkerAt(placed)) {
            // Broken since: on the ground nearby, or already in the inventory, or gone
            if (shulkerItemNearby(8)) {
                shulkersBefore = countShulkers() + 1;
                shulkerSlotsBefore.clear();
                for (int i = 0; i < 36; i++) if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) shulkerSlotsBefore.add(i);
                handle = Handle.Collect;
                handleTicks = 0;
                return;
            }
            for (int i = 0; i < 36; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (Utils.isShulker(stack.getItem()) && StackInfo.of(stack).contents().size() == r.placedStacks && r.placedStacks > 0) {
                    info("The shulker I left at %s is back in my inventory.", placed.toShortString());
                    compactor.drop();
                    clearOrphan(placed);
                    endHandling();
                    return;
                }
            }
            alert("The shulker I left at %s is gone and I can't find it. Forgetting it.", placed.toShortString());
            compactor.drop();
            clearOrphan(placed);
            endHandling();
            return;
        }

        // Never break a shulker that is not the one we put down
        String id = blockId(mc.level, placed);
        if (!r.placedItemId.isEmpty() && !id.equals(r.placedItemId)) {
            alert("The shulker box at %s is a %s, not the %s I put down, leaving it alone.", placed.toShortString(), id, r.placedItemId);
            compactor.drop();
            clearOrphan(placed);
            dropLost = true;
            endHandling();
            return;
        }

        shulkersBefore = countShulkers() + 1;
        shulkerSlotsBefore.clear();
        handleTicks = 0;
        if (returnTo == Handle.Collect) {
            // It was being broken: the block is still here, so it is opened and checked, then broken again
            resumeFinishOnly = true;
        }
        handle = Handle.Return;
        returnTo = Handle.Open;
    }

    /**
     * The shulker that was opened does not hold what the record says it should: nothing is done to it.
     */
    private String resumeContentsProblem(AbstractContainerMenu menu) {
        Compactor.Plan plan = compactor.plan;
        BulkKey key = plan != null ? plan.key : fillKey;
        boolean any = false, main = false;
        for (int i = 0; i < 27; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty()) continue;
            any = true;
            if (job == Job.Clean && mainItem != null && s.is(mainItem)) main = true;
            if (job != Job.Clean && key != null && !key.equals(keyOf(s))) return StackInfo.label(s) + " is in it";
        }
        if (job == Job.Clean && mainItem != null && any && !main) return "none of it is " + new ItemStack(mainItem).getItemName().getString();
        return null;
    }

    private void finishResume() {
        resuming = false;
        resumeVerify = false;
        resumeFinishOnly = false;
        pendingResume = null;
        if (stash != null) {
            stash.inFlight = null;
            stash.markDirty();
            if (mc.level != null) stash.save();
        }
        int unread = stash == null ? 0 : (int) stash.boxes.stream().filter(b -> !b.excluded && !b.foreign && (b.stale || !b.known())).count();
        step(String.format("resume finished; next: re-read %d containers.", unread));
    }

    // Shulkers standing in the world are kept in the stash file until they are picked up

    private void recordOrphan(BlockPos at, String item, int stacks) {
        if (stash == null) return;
        stash.orphans.removeIf(o -> o.pos.equals(at));
        stash.orphans.add(new Stash.Orphan(at, item, stacks, java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
            mc.level.dimension().toString()));
        stash.markDirty();
        stash.save();
    }

    private void clearOrphan(BlockPos at) {
        if (stash == null || at == null) return;
        if (stash.orphans.removeIf(o -> o.pos.equals(at))) {
            stash.markDirty();
            stash.save();
        }
    }

    /**
     * When sorting starts: shulkers left standing last time that are gone now are forgotten, and any still there mean no
     * more are put down.
     */
    private void checkOrphans() {
        String here = mc.level.dimension().toString();
        for (Stash.Orphan orphan : new ArrayList<>(stash.orphans)) {
            // One in another dimension is neither forgotten nor looked for from here
            if (!orphan.dimension.isEmpty() && !orphan.dimension.equals(here)) continue;
            // The one a saved step is about to finish
            if (pendingResume != null && orphan.pos.equals(pendingResume.placed)) continue;
            if (mc.level.isLoaded(orphan.pos) && !shulkerAt(orphan.pos)) {
                stash.orphans.remove(orphan);
                stash.markDirty();
                info("The shulker I left at %s is gone.", orphan.pos.toShortString());
            }
            else {
                alert("A shulker with %d stacks of %s is still standing at %s (since %s), pick it up by hand. No shulkers are put down until it's gone.",
                    orphan.stacks, orphan.item, orphan.pos.toShortString(), orphan.when);
                dropLost = true;
            }
        }
    }

    /**
     * A shulker of ours standing in the world when sorting stops: said loudly with what is in it and where.
     */
    private void reportPlacedShulker() {
        Compactor.Plan plan = compactor.plan;
        BlockPos at = null;
        if (handle != null && placed != null && mc.level != null && shulkerAt(placed)) at = placed;
        else if (plan != null && plan.placedPos != null) at = plan.placedPos;
        if (at == null) return;

        String what = placedName();
        int stacks = plan != null ? plan.placedSize : placedStacks;
        // Saved, and finished the next time the sorter starts
        syncInFlight();
        if (stash != null && stash.inFlight != null) {
            alert("Stopped mid-job: shulker at %s kept, it will be finished on the next start.", at.toShortString());
            return;
        }
        alertError("Stopped with a shulker holding %s of %s standing at %s. Pick it up by hand.", stacks > 0 ? stacks + " stacks" : "some stacks", what, at.toShortString());
        recordOrphan(at, what, stacks);
    }

    private boolean freeInventorySlot() {
        Set<Integer> keep = parseSlots(keepSlots.get());
        for (int i = 0; i < 36; i++) if (!keep.contains(i) && mc.player.getInventory().getItem(i).isEmpty()) return true;
        return false;
    }

    private int countShulkers() {
        int count = 0;
        for (int i = 0; i < 36; i++) if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) count++;
        if (Utils.isShulker(mc.player.getInventory().getItem(40).getItem())) count++;
        return count;
    }

    /**
     * A spot beside us that is safe for a shulker: open air on solid ground with room for the lid, not where a place
     * failed before, not a stash container, not on top of one (it would not open), not next to a hopper (it would be
     * emptied), not where a player or a mob stands.
     */
    private BlockPos safeSpot() {
        BlockPos feet = mc.player.blockPosition();
        Map<String, Integer> rejected = new HashMap<>();
        for (int dy = 0; dy >= -1; dy--) {
            for (Direction dir : HORIZONTALS) {
                BlockPos p = feet.relative(dir).above(dy);
                if (!BlockUtils.canPlace(p, true)) continue;
                if (!mc.level.getBlockState(p.above()).isAir()) continue;
                if (!mc.level.getBlockState(p.below()).isFaceSturdy(mc.level, p.below(), Direction.UP)) continue;

                String why = spotProblem(p);
                if (why == null) return p;
                debug("Not putting a shulker at %s: %s.", p.toShortString(), why);
                rejected.merge(why, 1, Integer::sum);
            }
        }
        if (!rejected.isEmpty()) {
            StringBuilder text = new StringBuilder();
            for (var entry : rejected.entrySet()) text.append(text.isEmpty() ? "" : ", ").append(entry.getValue()).append("x ").append(entry.getKey());
            info("No safe spot for a shulker beside me: %s.", text);
        }
        return null;
    }

    /**
     * Why a shulker mustn't be put at this spot, or null if it may.
     */
    private String unsafeBecause(BlockPos spot) {
        if (failedSpots.contains(spot)) return "placing there failed before";
        if (stash.boxAt(spot) != null) return "a stash container is there";
        // What is dropped there when the shulker is broken has to be walkable to: floor under it, and open on two sides
        // at least (our own side and one more), not a gap between containers
        BlockPos floor = spot.below();
        if (mc.level.getBlockState(floor).getCollisionShape(mc.level, floor).isEmpty()) return "there is no floor under it";
        int open = 0;
        for (Direction d : HORIZONTALS) {
            BlockPos side = spot.relative(d);
            if (mc.level.getBlockState(side).getCollisionShape(mc.level, side).isEmpty() && stash.boxAt(side) == null) open++;
        }
        if (open < 2) return "it is boxed in, an item dropped there could not be reached";
        // Another player or a mob whose box, a little wider than it is, overlaps the spot's cell blocks the place. Not the
        // player themselves, and not items, frames or armor stands
        if (!mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(spot).inflate(0.3), e -> e != mc.player && !(e instanceof ArmorStand)).isEmpty()) {
            return "a player or mob is standing in the way";
        }
        BlockPos below = spot.below();
        for (Box box : stash.boxes) if (box.has(below)) return "it is on top of the container at " + box.pos.toShortString();
        for (Direction d : Direction.values()) {
            if (mc.level.getBlockState(spot.relative(d)).is(Blocks.HOPPER)) return "there is a hopper next to it";
        }
        return null;
    }

    /** All the rules for a shulker (or an anvil) at this spot: the safe ones, away from the special blocks, inside the perimeter. */
    private String spotProblem(BlockPos spot) {
        String why = unsafeBecause(spot);
        if (why != null) return why;
        String special = nearSpecial(spot);
        if (special != null) return special;
        if (stash.hasArea() && !stash.inArea(spot)) return "it is outside the stash area";
        return null;
    }

    /** On or next to the delivery container, the anvil or the crafting table: those are walked up to and used. */
    private String nearSpecial(BlockPos spot) {
        for (Box box : stash.boxes) {
            if (!box.delivery) continue;
            for (BlockPos part : box.parts) if (touches(spot, part)) return "it is next to the delivery container";
        }
        if (stash.anvilPos != null && touches(spot, stash.anvilPos)) return "it is next to the anvil";
        if (stash.craftingTablePos != null && touches(spot, stash.craftingTablePos)) return "it is next to the crafting table";
        return null;
    }

    private static boolean touches(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) <= 1 && Math.abs(a.getY() - b.getY()) <= 1 && Math.abs(a.getZ() - b.getZ()) <= 1;
    }

    // Walking to a spot for a block (a shulker, an anvil) when there is none beside us: PlaceSearch, as the plugin's
    private static final int WANDER_TRIES = 3, WANDER_TICKS = 20 * 20;
    private BlockPos wanderStand, wanderSpot;
    private int wanderTries, wanderStart, wanderRestarts;
    private final Set<PlaceSearch.Pos> wanderFailed = new HashSet<>();

    private static PlaceSearch.Pos pos(BlockPos p) {
        return new PlaceSearch.Pos(p.getX(), p.getY(), p.getZ());
    }

    private static BlockPos bp(PlaceSearch.Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    private void resetWander() {
        wanderTries = wanderRestarts = 0;
        wanderFailed.clear();
        wanderStand = wanderSpot = null;
    }

    /** The world as the placement search sees it. */
    private PlaceSearch.View placeView() {
        return new PlaceSearch.View() {
            private boolean empty(int x, int y, int z) {
                BlockPos p = new BlockPos(x, y, z);
                BlockState st = mc.level.getBlockState(p);
                return st.getCollisionShape(mc.level, p).isEmpty() && st.getFluidState().isEmpty();
            }

            @Override
            public boolean open(int x, int y, int z) {
                return empty(x, y, z);
            }

            @Override
            public boolean floor(int x, int y, int z) {
                BlockPos p = new BlockPos(x, y, z);
                return mc.level.getBlockState(p).isFaceSturdy(mc.level, p, Direction.UP);
            }

            @Override
            public boolean standable(int x, int y, int z) {
                BlockPos p = new BlockPos(x, y, z);
                return empty(x, y, z) && empty(x, y + 1, z) && !mc.level.getBlockState(p.below()).getCollisionShape(mc.level, p.below()).isEmpty()
                    && stash.boxAt(p) == null;
            }

            @Override
            public String unsafe(PlaceSearch.Pos spot) {
                return unsafeBecause(bp(spot));
            }

            @Override
            public boolean inArea(PlaceSearch.Pos p) {
                return !stash.hasArea() || stash.inArea(bp(p));
            }

            @Override
            public boolean guarded(PlaceSearch.Pos spot) {
                return nearSpecial(bp(spot)) != null;
            }
        };
    }

    /**
     * No spot beside us: looks for the nearest spot within 5 blocks that can be walked to, and starts walking to the cell
     * beside it. When the cell to stand in is where we stand, the spot is just used.
     * @return null when the walk started (or the spot is at hand), else why there is nothing to walk to
     */
    private String startWander() {
        if (!walkOn()) return "walking is off";
        if (wanderTries >= WANDER_TRIES) return "couldn't walk to one";
        PlaceSearch.Result found = PlaceSearch.search(pos(mc.player.blockPosition()), placeView(), wanderFailed);
        if (found.found() == null) return found.why();

        wanderTries++;
        wanderSpot = bp(found.found().spot());
        if (found.found().steps() == 0) return null;
        wanderStand = bp(found.found().stand());
        wanderRestarts = 0;
        info("No spot for a shulker beside me, walking %d steps to the nearest one.", found.found().steps());
        walkNear(wanderStand, 1);
        wanderStart = handleTicks;
        handle = Handle.Wander;
        return null;
    }

    /** Walks to the cell beside the chosen spot; there, the block is put down at that spot. A shulker carried is never dropped. */
    private void handleWander() {
        if (wanderStand == null) {
            handle = Handle.Place;
            handleTicks = 0;
            return;
        }
        Vec3 at = mc.player.position();
        boolean arrived = at.distanceToSqr(Vec3.atBottomCenterOf(wanderStand)) <= 1.8 * 1.8;
        if (arrived) {
            releaseGoal();
            wanderStand = null;
            handle = Handle.Place;
            handleTicks = 0;
            wait = delay.get();
            return;
        }
        // A goal let go of while giving way is set again, a few times
        if (ourGoal == null) {
            if (++wanderRestarts > 3) {
                wanderGiveUp();
                return;
            }
            walkNear(wanderStand, 1);
            wanderStart = handleTicks;
            return;
        }
        boolean stopped = handleTicks - wanderStart > PATH_GRACE_TICKS && !BaritoneHelper.isMoving();
        if (stopped || handleTicks - wanderStart > WANDER_TICKS) wanderGiveUp();
    }

    /** The walk did not get there: that spot is not tried again, and the next best is looked for. */
    private void wanderGiveUp() {
        releaseGoal();
        if (wanderSpot != null) wanderFailed.add(pos(wanderSpot));
        wanderStand = wanderSpot = null;
        handle = Handle.Place;
        handleTicks = 0;
    }

    /** Stay-on, or stash-manager is driving it: the sorter rests when it has nothing to do instead of turning itself off. */
    private boolean keepOn() {
        return stayOn.get() || driven;
    }

    private void finishSorting() {
        // An order in hand keeps the sorter awake, even when it has to wait (paused, or for a saved kit)
        if (orderRunner.running()) {
            // The pass is over all the same: its counters start again, or the loop watchdog would fire on every tick
            boxVisits.clear();
            circular.clear();
            loopReported.clear();
            closedEarlyReported.clear();
            passJobs = 0;
            passMin = Integer.MAX_VALUE;
            wait = Math.max(wait, 20);
            return;
        }

        // A plan in hand: the shulker standing in the world is fetched back first, then what was gathered for it is put away
        if (compactor.active()) {
            compactor.abort("sorting is finishing");
            if (compactor.next()) return;
            if (!activeCarried().isEmpty()) return;
        }

        boxVisits.clear();
        circular.clear();
        loopReported.clear();
        closedEarlyReported.clear();
        passJobs = 0;
        passMin = Integer.MAX_VALUE;
        if (stash.isDirty()) stash.save();

        long unreachable = stash.boxes.stream().filter(b -> b.unreachable).count();
        String extra = unreachable > 0 ? String.format(" Couldn't open %d containers, they were left alone.", unreachable) : "";
        int packedRun = compactor.runPacked();
        extra += compactor.takeRunSummary();
        if (!stuckCarried.isEmpty()) extra += String.format(" %d stacks I am carrying have nowhere to go.", stuckCarried.size());
        long leftInDropOffs = stash.boxes.stream().filter(b -> b.dropOff && b.known() && !b.unreachable).mapToLong(b -> b.contents.length - b.free()).sum();
        if (sorting() && leftInDropOffs > 0) extra += String.format(" %d stacks are still in drop-offs, there's no room left in the stash.", leftInDropOffs);

        // Scan-only: one scan, then off, unless stay-on keeps it watching
        if (!sorting()) {
            if (!keepOn()) {
                info("Scan only: read %d containers, the saved stash is up to date.%s", scanned, extra);
                step(String.format("done: read %d containers; stopping.", scanned));
                scanned = 0;
                turnOff();
                return;
            }
            if (!driven && (scanned > 0 || !extra.equals(lastSummary))) {
                info("Scan only: read %d containers, the saved stash is up to date.%s Watching for changes.", scanned, extra);
                step(String.format("idle: read %d containers; next: re-read a container when it changes.", scanned));
            }
            lastSummary = extra;
            scanned = 0;
            moved = 0;
            idle = true;
            idleTicks = 0;
            seenVersion = version;
            return;
        }

        if (keepOn()) {
            // Waking up for a drop-off check that finds nothing says nothing; a changed summary does
            if (moved > 0 || !extra.equals(lastSummary)) {
                info("Sorted, moved %d stacks.%s Waiting for more to sort.", moved, extra);
                step(String.format("idle: sorted, moved %d stacks; next: sort again when a container, sign or the ground changes.", moved));
            }
            lastSummary = extra;
            moved = 0;
            idle = true;
            idleTicks = 0;
            seenVersion = version;
            return;
        }

        info("Done, moved %d stacks.%s", moved, extra);
        step(String.format("done: moved %d stacks, packed %d shulkers; stopping.", moved, packedRun));
        turnOff();
    }

    // When the job in hand began, for the debug log
    private long jobStartedAt;

    private boolean start(Job next, Box box) {
        // A kit being built has its own limits: its trips are not what the circle watchdog counts
        // Repacking trips (fetching a shulker), compacting, surveys and kit builds don't shrink the backlog, so they aren't
        // what the circle watchdog counts
        jobCounted = !kitBuilder.active() && next != Job.Fetch && next != Job.Compact && next != Job.Survey && next != Job.FetchSigns && next != Job.Gather;
        if (jobCounted) {
            passJobs++;
            boxVisits.merge(box, 1, Integer::sum);
        }
        startSigSet = endSigSet = false;
        settled = false;
        chained = 0;
        jobTicks = 0;
        jobStartedAt = System.nanoTime();
        debug(() -> String.format("pickJob: %s on %s at %s, %.1f blocks away; backlog min %d, %d jobs this pass, %d containers stalled, %d carried.",
            next, home(box), xyz(box.pos), Math.sqrt(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(box.pos))), passMin == Integer.MAX_VALUE ? -1 : passMin,
            passJobs, stalled.size(), carriedSlots().size()));
        job = next;
        target = box;
        swapStep = 0;
        actions = 0;
        recoveries = 0;
        openPos = null;
        openMenuBefore = null;

        // Plainly not a container (air, no block entity in a loaded chunk): dropped at once, nothing is walked to. It comes
        // back if the scan finds a container there
        if (next == Job.Survey && !box.delivery && mc.level.isLoaded(box.pos) && inStashDimension()) {
            if (!containerBlockHere(box.pos)) {
                QwerteAddon.LOG.info("[stash-sorter] dropped the container at {}: there is no chest or barrel there any more.", xyz(box.pos));
                step(String.format("skipped %s: there is no chest or barrel there; forgotten.", xyz(box.pos)));
                stash.boxes.remove(box);
                stash.markDirty();
                failedSurvey.remove(box);
                surveyRetry.succeed(box);
                job = null;
                target = null;
                return false;
            }
            if (!isContainer(box.pos)) {
                // A barrel with the barrels setting off: kept in the stash, only not read
                job = null;
                target = null;
                surveyFailed(box, "barrels are switched off in the settings");
                return false;
            }
        }

        if (inReach(box)) {
            step = Step.Opening;
        }
        else if (walkOn()) {
            walkReach(box);
            step = Step.Walking;
        }
        else {
            if (stash.hasArea() && !BARITONE) warnNoBaritone();
            box.unreachable = true;
            job = null;
            target = null;
            return false;
        }

        stepTicks = 0;
        walkProgress.reset(0, walkDistance(box));
        announce(next, box);
        return true;
    }

    private static String xyz(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /**
     * The container as a sign or its block: "Diamonds", or chest.
     */
    private String home(Box box) {
        return box.labels.isEmpty() || box.labels.getFirst().isBlank() ? blockName(box) : "\"" + box.labels.getFirst() + "\"";
    }

    /**
     * Says what the job just started does and what comes after, from queries that change nothing.
     */
    private void announce(Job next, Box box) {
        try {
            switch (next) {
                case Survey -> {
                    int total = (int) stash.boxes.stream().filter(b -> !b.excluded).count();
                    int done = (int) stash.boxes.stream().filter(b -> !b.excluded && b.known() && !b.stale).count();
                    Supplier<String> line = () -> String.format("reading containers: %d/%d; next: %s at %s.", done, total, blockName(box), xyz(box.pos));
                    // The last ones are always said, the bucketed progress could skip them
                    if (total - done <= 1) step(line.get());
                    else stepProgress("read", done, total, line);
                }
                case Take -> {
                    List<Box> homes = List.of();
                    int n = 0;
                    for (ItemStack stack : box.contents) {
                        if (stack.isEmpty() || !misplaced(stack, box)) continue;
                        n++;
                        if (homes.isEmpty()) homes = destinationsFor(stack).stream().filter(b -> b != box).toList();
                    }
                    n = Math.min(n, Math.max(1, freeCarryCount()));
                    Box nearest = homes.isEmpty() ? null : nearest(homes);
                    step(String.format("sorting: taking %d stacks from %s at %s; next: %s.", n, home(box), xyz(box.pos),
                        nearest == null ? "carry on" : String.format("put them away (%d containers, nearest %s)", homes.size(), xyz(nearest.pos))));
                }
                case Deposit, Dump -> {
                    List<Integer> carried = activeCarried();
                    int n = 0;
                    Set<Box> others = new LinkedHashSet<>();
                    for (int slot : carried) {
                        List<Box> homes = destinationsFor(mc.player.getInventory().getItem(slot));
                        if (homes.contains(box) || next == Job.Dump) n++;
                        for (Box other : homes) if (other != box && !blocked(other, Job.Deposit) && !signPending(other)) others.add(other);
                    }
                    Box source = next == Job.Dump ? null : nearest(stash.boxes.stream().filter(b -> b.sortable() && !blocked(b, Job.Take) && !signPending(b) && b != box && takeableSlot(b) != -1).toList());
                    String after = others.isEmpty() ? "" : others.size() + " more stops, then ";
                    step(String.format("sorting: putting %d stacks into %s at %s; next: %s.", Math.max(n, 1), home(box), xyz(box.pos),
                        source != null ? after + "take from " + xyz(source.pos) : others.isEmpty() ? "carry on" : others.size() + " more stops"));
                }
                case Compact -> step(String.format("sorting: merging partial stacks in %s at %s; next: carry on.", home(box), xyz(box.pos)));
                case Fetch -> {
                    if (orderWork()) {
                        step(String.format("order: fetching %s from %s at %s.", kitBuilder.active() ? kitBuilder.fetchPurpose() : "the shulker the order asks for", home(box), xyz(box.pos)));
                    }
                    else step(String.format("repacking: fetching a mixed shulker from %s at %s; next: clean it beside the chest.", home(box), xyz(box.pos)));
                }
                case FetchSigns -> step(String.format("labelling: fetching signs from %s at %s; next: put up the signs.", home(box), xyz(box.pos)));
                case Gather -> { /* said by the compactor */ }
                case CraftTake -> step(String.format("crafting: taking ingredients from %s at %s; next: the crafting table.", home(box), xyz(box.pos)));
                case KitTake -> step(String.format(orderKit() ? "order: taking items for the order from %s at %s; next: fill the shulker." : "kits: taking items from %s at %s; next: fill the kit shulker.", home(box), xyz(box.pos)));
                default -> { }
            }
        } catch (RuntimeException e) {
            QwerteAddon.LOG.debug("[stash-sorter] couldn't describe the job", e);
        }
    }

    private void finishJob() {
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            // Never close with something on the cursor, it would be merged into the inventory or dropped
            if (!mc.player.containerMenu.getCarried().isEmpty() && !recoverCursor()) {
                alertError("Couldn't put away the stack on the cursor, no free slot. Stopping, and the stack may be dropped when the container closes.");
                turnOff();
                return;
            }
            closeOurWindow(mc.player.containerMenu);
        }
        ownedMenu = null;
        lastOwnAct = ticks;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;

        // Clicks that changed neither the carry slots nor the container count for nothing: they never went through.
        // Clicks were made but the window went before the result could be read: an unconfirmed attempt, which is neither
        // progress nor proof of none. A box is stalled after two of them in a row
        boolean confirmed = endSigSet && startSigSet;
        boolean unconfirmed = actions > 0 && !confirmed;
        boolean progressed = actions > 0 && confirmed && startSig != endSig;
        if (debugOn() && job != null && target != null) {
            debug("%s at %s took %d ms: %d actions, %s.", job, xyz(target.pos), (System.nanoTime() - jobStartedAt) / 1_000_000, actions,
                progressed ? "progressed" : unconfirmed ? "unconfirmed" : "no progress");
        }

        // Opened it and got nothing done: not repeated until the carry slots or the container change
        if (actions == 0 && !signAbort && job != null && job != Job.Survey && target != null && !target.unreachable) {
            futile.put(futileKey(target, job), futileSig(target));
        }
        if (actions > 0 && target != null) signAbortRuns.remove(target);
        if (job == Job.Survey && target != null && target.known()) {
            scanned++;
            if (!target.stale) surveyRetry.succeed(target);
        }

        int visits = target == null ? 0 : boxVisits.getOrDefault(target, 0);
        if (jobCounted && visits >= 3 && job != Job.Survey && target != null && loopReported.add(target)) {
            debug("Container at %s has been the target of %d jobs without the backlog shrinking: last job %s, %d actions, %s, %s, carrying %d stacks.",
                target.pos.toShortString(), visits, job, actions, confirmed ? "confirmed" : "unconfirmed",
                progressed ? "changed something" : "changed nothing", carriedSlots().size());
        }

        // Going back and forth between containers while the backlog doesn't shrink: given up on, however each job looks
        boolean circle = jobCounted && visits >= 4 && job != Job.Survey && target != null && !target.unreachable && !signAbort;
        if (circle) {
            circular.add(target);
            stalled.add(target);
        }
        else if (job == Job.Survey && target != null && (!target.known() || target.stale) && !signAbort) {
            surveyFailed(target, "the window closed before it could be read");
        }
        else if (job != null && job != Job.Survey && target != null && !target.unreachable) {
            if (unconfirmed && !signAbort) {
                if (unconfirmedRuns.merge(target, 1, Integer::sum) >= 2) stalled.add(target);
            }
            else if (!progressed && !signAbort) stalled.add(target);
            else if (progressed) {
                unconfirmedRuns.remove(target);
                clearStalled();
                stalled.addAll(circular);
                stuckCarried.clear();
                stuckWarned = false;
            }
        }

        startSigSet = endSigSet = false;

        if (stash.isDirty()) stash.save();
        signAbort = false;
        job = null;
        target = null;
        wait = delay.get();
    }

    // Steps

    /**
     * Distance from the eyes to the nearest part of the container, for telling whether a walk is getting closer.
     */
    private double walkDistance(Box box) {
        return Math.sqrt(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(nearestPart(box))));
    }

    private void tickWalking() {
        if (inReach(target)) {
            releaseGoal();
            step = Step.Opening;
            stepTicks = 0;
            return;
        }

        // A read does not spend long on a walk that is going nowhere (looping, blocked, no way in): the box is skipped
        // and tried again later, instead of the whole WALK_TIMEOUT or the watchdog
        if (job == Job.Survey) {
            walkProgress.update(stepTicks, walkDistance(target));
            // A chunk that isn't loaded can't be walked into by a path, so it gets half the time
            if (walkProgress.stalled(stepTicks, mc.level.isLoaded(target.pos) ? SURVEY_WALK_STALL_TICKS : SURVEY_WALK_STALL_TICKS / 2)) {
                releaseGoal();
                surveyFailed(target, String.format("could not reach it (walk made no progress for %ds)", walkProgress.idleTicks(stepTicks) / 20));
                finishJob();
                return;
            }
        }

        // Started inside the perimeter and now outside it: a way that leads out of the stash area isn't taken
        if (walkInside && stash.hasArea() && !stash.inArea(mc.player.getX(), mc.player.getY(), mc.player.getZ(), AREA_MARGIN)) {
            releaseGoal();
            reportUnreachable(target, Cause.Area);
            target.unreachable = true;
            finishJob();
            return;
        }

        boolean stopped = stepTicks > PATH_GRACE_TICKS && !BaritoneHelper.isMoving();
        boolean ourGoalWasTaken = stopped && BARITONE && ourGoal != null && !BaritoneHelper.goalIs((baritone.api.pathing.goals.Goal) ourGoal);
        if (stopped || stepTicks > WALK_TIMEOUT) {
            releaseGoal();
            // Our path went away because somebody else cancelled or replaced it (nothing signalled it, but our goal is no
            // longer the one Baritone has): not the container's fault, plan it again. A few times only, since a path
            // that couldn't be found clears the goal too
            boolean cancelled = stopped && BARITONE && ourGoalWasTaken && cancelRetries.merge(target, 1, Integer::sum) <= 2;
            if (stopped && (cancelled || yielder.foreignRecently(stepTicks + 5))) {
                if (jobCounted) {
                    passJobs = Math.max(0, passJobs - 1);
                    boxVisits.merge(target, -1, Integer::sum);
                }
                job = null;
                target = null;
                wait = delay.get();
                return;
            }
            if (job == Job.Survey) {
                // Skipped for now, not for good: the way may open up (a door, a chunk, a player in the way)
                surveyFailed(target, stopped ? "could not reach it (no path)" : "could not reach it (walk timed out after " + WALK_TIMEOUT / 20 + "s)");
                finishJob();
                return;
            }
            reportUnreachable(target, Cause.Path);
            target.unreachable = true;
            finishJob();
        }
    }

    private void tickOpening() {
        AbstractContainerMenu open = mc.player.containerMenu;
        if (open != mc.player.inventoryMenu) {
            // Only the menu our own click opened, of the size this box has. Anything else (an ender chest, a plugin
            // GUI, one left over from an earlier click) is closed and the click retried
            if (openPos != null && open != openMenuBefore && open instanceof ChestMenu && sizedForTarget(open)) {
                ownedMenu = open;
                debug(() -> String.format("Opened %s at %s after %d ticks.", home(target), xyz(target.pos), stepTicks));
                step = Step.Working;
                stepTicks = 0;
                read(target);
                wait = firstClickWait();
                return;
            }

            debug(() -> String.format("The window that opened at %s doesn't fit (%s, %d slots, expected %d chest slots), closing it and clicking again.",
                xyz(target.pos), open.getClass().getSimpleName(), open.slots.size() - 36, target.parts.size() * 27));
            if (!open.getCarried().isEmpty()) recoverCursor();
            mc.player.closeContainer();
            lastCloseTick = ticks;
        }

        // container-delay: the open click waits, and the wait does not count toward the open timeout
        if (openHeld()) {
            stepTicks--;
            return;
        }

        if (stepTicks > OPEN_TIMEOUT) {
            // A read that never gets a window (someone has it open, or it closes at once) is tried again later, not given
            // up on for good
            if (job == Job.Survey) surveyFailed(target, "could not open it (no window after " + OPEN_TIMEOUT / 20 + "s)");
            else {
                reportUnreachable(target, Cause.Open);
                target.unreachable = true;
            }
            finishJob();
            return;
        }

        if (stepTicks == 1 || stepTicks % 20 == 0) {
            BlockPos pos = nearestPart(target);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            Runnable click = () -> {
                // The rotation can finish after we have started to give way
                if (gate != Gate.Running || job == null) return;
                openPos = pos;
                openClickTick = ticks;
                openMenuBefore = mc.player.containerMenu;
                BlockUtils.interact(hit, InteractionHand.MAIN_HAND, true);
            };

            if (rotate.get()) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), click);
            else click.run();
        }
    }

    private void tickWorking() {
        // A sign on this container changed: what it takes depends on it now, so don't carry on with the old idea
        if (signPending(target)) {
            // Not the container's fault: it is looked at again once its sign has settled, so it is not stalled
            signAbort = true;
            signAborted.add(target);
            if (signAbortReported.add(target)) debug("Sign at %s reported changing, waiting for it to settle.", target.pos.toShortString());
            // The same container aborted again and again without a click: held until its sign text really changes
            if (actions == 0 && signAbortRuns.merge(target, 1, Integer::sum) >= 2) {
                signHeld.add(target);
                stalled.add(target);
            }
            finishJob();
            return;
        }

        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!(menu instanceof ChestMenu) || menu.slots.size() - 36 != target.parts.size() * 27) {
            // Closed on us, or swapped for something else, by the server or the player
            if (closedEarlyReported.add(target)) {
                debug("Container at %s closed before I finished with it (%s), the server may have closed it.", target.pos.toShortString(),
                    menu == mc.player.inventoryMenu ? "window gone" : "another window opened");
            }
            finishJob();
            return;
        }

        read(target);

        // Both ends of the comparison are read through the open window, before anything is clicked and once the last
        // click is through, so they come from the same source
        if (actions == 0 && !startSigSet) {
            startSig = stateSig();
            startSigSet = true;
        }

        boolean busy = switch (job) {
            case Survey, Clean, Fill, KitUnpack, KitFill, Label, Ground, Explore, Unbundle, Name, CraftUnpack, Craft, AnvilPlace, ChestPlace, Repair -> false;
            case FetchSigns -> labeller.fetchSignsOne(target, actions);
            case Gather -> compactor.gatherOne(target);
            case KitTake -> kitBuilder.takeOne(target);
            case CraftTake -> crafter.takeOne(target);
            case Take -> takeOne();
            case Fetch -> fetchOne();
            case Compact -> compactOne();
            case Deliver -> deliverOne();
            case Return -> returnOne();
            case Deposit -> depositOne();
            case Dump -> dumpOne();
        };

        if (busy) {
            actions++;
            settled = false;
            wait = delay.get();
        }
        else if (actions > 0 && !settled) {
            // Give the server a moment to answer the last click, then look again before judging what the clicks did
            settled = true;
            wait = SETTLE_TICKS;
        }
        else {
            endSig = stateSig();
            endSigSet = true;
            if (actions == 0 && noOpReported.add(futileKey(target, job))) {
                debug("%s at %s did nothing: %d free carry slots, %d carried.", job, target.pos.toShortString(), freeCarryCount(), carriedSlots().size());
            }
            if (chainJob()) return;
            finishJob();
        }
    }

    /**
     * The job in hand is done and its window is still open: if the same container has something else to do, the job is
     * switched in place instead of closing it and walking back to open it again. A take (something here that belongs
     * elsewhere and has room to go), or a merge of part stacks that has not been done this run.
     * @return true when the job was switched and carries on
     */
    private static final int CHAIN_MAX = 4;
    private int chained;

    private boolean chainJob() {
        if (!sorting() || signAbort || target == null || job == null || actions == 0) return false;
        if (job != Job.Deposit && job != Job.Take && job != Job.Dump && job != Job.Compact) return false;
        // Only after a part that got somewhere, and never on a container given up on, with a sign changing, in the
        // middle of a swap, and not more than 4 times in a row
        if (!(endSigSet && startSigSet && startSig != endSig) || chained >= CHAIN_MAX || swapStep != 0) return false;
        if (stalled.contains(target) || circular.contains(target) || signHeld.contains(target) || signPending(target)) return false;
        if (kitBuilder.active() || compactor.active() || target.unreachable || target.stale || foreignNow) return false;
        if (!(mc.player.containerMenu instanceof ChestMenu) || !mc.player.containerMenu.getCarried().isEmpty()) return false;

        Job next = null;
        if (freeCarrySlot() != -1 && !blocked(target, Job.Take) && takeableSlot(target) != -1) next = Job.Take;
        else if (!target.dropOff && !blocked(target, Job.Compact) && !compacted.contains(target) && mergeable(target) != null) {
            compacted.add(target);
            next = Job.Compact;
        }
        if (next == null) return false;

        // What the finished part achieved counts, as when a job ends
        unconfirmedRuns.remove(target);
        clearStalled();
        stalled.addAll(circular);
        stuckCarried.clear();
        stuckWarned = false;
        chained++;
        debug("Staying at %s at %s: %s done, %s next.", home(target), xyz(target.pos), job, next);

        jobCounted = !kitBuilder.active() && next != Job.Compact;
        if (jobCounted) {
            passJobs++;
            boxVisits.merge(target, 1, Integer::sum);
        }
        job = next;
        actions = 0;
        recoveries = 0;
        startSigSet = endSigSet = false;
        settled = false;
        swapStep = 0;
        jobStartedAt = System.nanoTime();
        announce(next, target);
        wait = delay.get();
        return true;
    }

    // Loop protection

    private void resetLoopState() {
        stalled.clear();
        cancelRetries.clear();
        stashWatch.clear();
        deliveryWatch.clear();
        signHeld.clear();
        signAborted.clear();
        signAbortRuns.clear();
        signAbortReported.clear();
        staleBoxes.clear();
        deferredAccess.clear();
        signAbort = false;
        nextSignSweep = clock + SIGN_SWEEP_TICKS;
        futile.clear();
        noOpReported.clear();
        failedSurvey.clear();
        surveyRetry.clear();
        unconfirmedRuns.clear();
        closedEarlyReported.clear();
        boxVisits.clear();
        circular.clear();
        loopReported.clear();
        stuckCarried.clear();
        stuckWarned = false;
        lastSummary = null;
        passJobs = 0;
        passMin = Integer.MAX_VALUE;
        startSigSet = endSigSet = false;
    }

    private String futileKey(Box box, Job kind) {
        return box.pos.asLong() + ":" + kind;
    }

    private static long stackSig(ItemStack stack) {
        return stack.isEmpty() ? 0 : ItemStack.hashItemAndComponents(stack) * 131L + stack.getCount();
    }

    private long futileSig(Box box) {
        long h = 17;
        for (int slot : carry) h = h * 31 + stackSig(mc.player.getInventory().getItem(slot));
        if (box.known()) for (ItemStack stack : box.contents) h = h * 31 + stackSig(stack);
        return h;
    }

    /**
     * What the carry slots and the target container hold now.
     */
    private long stateSig() {
        long h = 17;
        for (int slot : carry) h = h * 31 + stackSig(mc.player.getInventory().getItem(slot));
        if (target != null && target.known()) for (ItemStack stack : target.contents) h = h * 31 + stackSig(stack);
        return h;
    }

    /**
     * Not worth a trip for this kind of job: the box is stalled, or the same trip came back empty-handed and neither
     * the carry slots nor the container have changed since.
     */
    private void clearStalled() {
        stalled.clear();
        stalled.addAll(signHeld);
    }

    /**
     * A sign on this container is being changed, so what it takes is not settled.
     */
    private boolean signPending(Box box) {
        return watchSigns.get() && mode.get() == Mode.Signs && stashWatch.pending(box);
    }

    private boolean blocked(Box box, Job kind) {
        return stalled.contains(box) || signHeld.contains(box) || blockedByFutility(box, kind);
    }

    private boolean blockedByFutility(Box box, Job kind) {
        Long was = futile.get(futileKey(box, kind));
        return was != null && was == futileSig(box);
    }

    // Logging. Classes of line: ERROR and ALERT (a person is needed) and STEP (now / next) always show; INFO and WARN
    // show unless Quiet; DEBUG only on Debug. Whatever is hidden is still written to the log at debug level

    private static String format(String message, Object... args) {
        if (args == null || args.length == 0) return message;
        try {
            return String.format(message, args);
        } catch (RuntimeException e) {
            return message;
        }
    }

    private boolean quiet() {
        return logLevel.get() == LogLevel.Quiet;
    }

    @Override
    public void info(String message, Object... args) {
        if (quiet()) QwerteAddon.LOG.debug("[stash-sorter] {}", format(message, args));
        else super.info(message, args);
    }

    @Override
    public void warning(String message, Object... args) {
        if (quiet()) QwerteAddon.LOG.warn("[stash-sorter] {}", format(message, args));
        else super.warning(message, args);
    }

    /**
     * Feedback to something the player just did (marking a container, setting the area): shown at every level.
     */
    private void notice(String message, Object... args) {
        super.info(message, args);
    }

    /**
     * A line that needs a person: shown at every level, and whispered to the whitelist.
     */
    private void alert(String message, Object... args) {
        String text = format(message, args);
        super.warning("%s", text);
        QwerteAddon.LOG.warn("[stash-sorter] ALERT {}", text);
        sendAlert(text);
    }

    /**
     * An error that needs a person: like alert, but shown as an error.
     */
    private void alertError(String message, Object... args) {
        String text = format(message, args);
        super.error("%s", text);
        QwerteAddon.LOG.error("[stash-sorter] ALERT {}", text);
        sendAlert(text);
    }

    private static final int ALERT_REPEAT_MS = 10 * 60 * 1000, ALERT_GAP_MS = 5000, ALERT_QUEUE = 5;
    // Alert text with its digits taken out, and when it was last queued
    private final Map<String, Long> alertsSent = new HashMap<>();
    private final java.util.ArrayDeque<String> alertQueue = new java.util.ArrayDeque<>();
    private long lastAlertDm;

    /**
     * Queues an alert as a whisper to every whitelisted player (the whitelist, not the search setting, decides): the same
     * text, digits aside, at most once in 10 minutes, and 5 whispers waiting at the most, the oldest dropped. Sent by
     * {@link #pumpAlerts()}, one every 5 seconds, 256 characters at the most, plain ASCII.
     */
    private void sendAlert(String text) {
        if (offline) return;
        List<String> to = whitelist.get().stream().map(String::trim).filter(n -> !n.isEmpty()).toList();
        if (to.isEmpty()) return;

        long now = System.currentTimeMillis();
        alertsSent.values().removeIf(at -> now - at > ALERT_REPEAT_MS);
        String key = text.replaceAll("[0-9]", "");
        if (alertsSent.containsKey(key)) return;
        alertsSent.put(key, now);

        StringBuilder plain = new StringBuilder("Stash Sorter: ");
        for (char c : text.toCharArray()) plain.append(c >= 32 && c < 127 ? c : c == '\u00d7' ? 'x' : '?');
        for (String name : to) {
            String prefix = "/msg " + name + " ";
            String body = plain.length() + prefix.length() > 256 ? plain.substring(0, Math.max(0, 256 - prefix.length())) : plain.toString();
            alertQueue.addLast(prefix + body);
            while (alertQueue.size() > ALERT_QUEUE) alertQueue.pollFirst();
        }
    }

    /**
     * Sends one waiting alert whisper, at most one in 5 seconds, and only while we are in a world.
     */
    private void pumpAlerts() {
        if (alertQueue.isEmpty() || offline || worldTicks < ALERT_JOIN_TICKS || mc.player == null || mc.level == null || mc.getConnection() == null) return;
        long now = System.currentTimeMillis();
        if (now - lastAlertDm < ALERT_GAP_MS) return;
        lastAlertDm = now;
        ChatUtils.sendPlayerMsg(alertQueue.pollFirst());
    }

    // What it is doing now and next, shown at every level. The same line is never said twice in a row
    private String lastStep = "";
    private final Map<String, long[]> stepProgress = new HashMap<>();

    private void step(String text) {
        text = "Stash: " + text;
        if (text.equals(lastStep)) return;
        lastStep = text;
        super.info("%s", text);
        QwerteAddon.LOG.info("[stash-sorter] {}", text);
    }

    /**
     * A STEP for something that goes on for a long time: at most every 10 percent or 60 seconds of {@code done} out of
     * {@code total}.
     */
    private void stepProgress(String phase, int done, int total, Supplier<String> text) {
        long now = System.currentTimeMillis();
        long[] last = stepProgress.get(phase);
        long bucket = total <= 0 ? 0 : done * 10L / total;
        if (last != null && last[0] == bucket && now - last[1] < 60_000) return;
        stepProgress.put(phase, new long[] {bucket, now});
        step(text.get());
    }

    private boolean debugOn() {
        return logLevel.get() == LogLevel.Debug;
    }

    /**
     * Shown as info with [debug] on the Debug level, otherwise only written to the debug log.
     */
    private void debug(String format, Object... args) {
        if (debugOn()) debug(() -> format(format, args));
        else if (QwerteAddon.LOG.isDebugEnabled()) QwerteAddon.LOG.debug("[stash-sorter] {}", format(format, args));
    }

    /**
     * Like debug(format, args), for text that costs something to build: only built when it will be shown or logged.
     */
    private void debug(Supplier<String> text) {
        if (debugOn()) {
            String line = text.get();
            QwerteAddon.LOG.debug("[stash-sorter] {}", line);
            super.info("[debug] %s", line);
        }
        else if (QwerteAddon.LOG.isDebugEnabled()) QwerteAddon.LOG.debug("[stash-sorter] {}", text.get());
    }

    /**
     * Whether the open menu is a chest-sized one for the target. A box that has become a double chest (or been halved)
     * since it was saved is brought up to date when the menu's size shows it.
     */
    private boolean sizedForTarget(AbstractContainerMenu menu) {
        int slots = menu.slots.size() - 36;
        if (slots == target.parts.size() * 27) return true;
        return refreshTargetParts() && slots == target.parts.size() * 27;
    }

    /**
     * Re-reads which blocks make up the target container, when a chest next to it was placed or broken since it was
     * saved. Its contents are read again, and a saved box that is now just its other half is dropped, keeping its marks.
     * @return true if the parts changed
     */
    private boolean refreshTargetParts() {
        if (!mc.level.isLoaded(target.pos)) return false;

        List<BlockPos> now = makeBox(target.pos).parts;
        if (now.equals(target.parts)) return false;

        if (now.size() != target.parts.size()) target.contents = null;
        target.parts.clear();
        target.parts.addAll(now);
        readSigns(target);
        for (int i = stash.boxes.size() - 1; i >= 0; i--) {
            Box other = stash.boxes.get(i);
            if (other == target || !target.has(other.pos)) continue;
            carryMarks(other, target);
            stash.boxes.remove(i);
        }
        stash.markDirty();
        return true;
    }

    /**
     * What a box that is being dropped as a duplicate was marked as carries over to the box that stays.
     */
    private static void carryMarks(Box from, Box to) {
        to.excluded |= from.excluded;
        to.dropOff |= from.dropOff;
        to.delivery |= from.delivery;
        to.deliveryExtra |= from.deliveryExtra;
        to.wasExcluded |= from.wasExcluded;
        to.wasDropOff |= from.wasDropOff;
    }

    // Moving items

    /**
     * Moves one stack that belongs somewhere else into a free carry slot.
     * @return false when there's nothing more to take, or nowhere to carry it
     */
    private boolean takeOne() {
        int into = freeCarrySlot();
        if (into == -1) return false;

        int slot = takeableSlot(target);
        if (slot == -1) return false;

        // Straight into a carry slot, so it can never land in or top up one of the player's own stacks
        stashHeld.put(into, target.contents[slot].copy());
        debug(() -> String.format("Click: move slot %d (%s) to carry slot %d.", slot, StackInfo.label(target.contents[slot]), into));
        InvUtils.move().fromId(slot).toId(inventoryToMenu(into, target.contents.length));
        return true;
    }

    /**
     * Takes the one shulker this trip came for into a free carry slot, to repack it.
     * @return false once it's taken, or if it's gone
     */
    private boolean fetchOne() {
        if (wantSlot < 0 || actions > 0) return false;

        int into = freeCarrySlot();
        if (into == -1 || wantSlot >= target.contents.length || !fetchWants.test(target.contents[wantSlot])) return false;

        stashHeld.put(into, target.contents[wantSlot].copy());
        noteOrderHeld(into, target.contents[wantSlot]);
        InvUtils.move().fromId(wantSlot).toId(inventoryToMenu(into, target.contents.length));
        wantSlot = -1;
        return true;
    }

    /**
     * A slot in this box holding something that belongs elsewhere, where that somewhere still has room once what
     * we're already carrying is put away. -1 if there's none.
     */
    private int takeableSlot(Box source) {
        // What each destination can still take, so we don't pick up more than we can put away. The room of every
        // container is worked out once per planning pass; what the carried stacks use up is taken off per source
        Map<Box, Integer> base = roomBase;
        if (base == null) {
            base = new HashMap<>();
            for (Box box : stash.boxes) {
                // Only where a deposit could go: not a container being skipped
                if (box.sortable() && !box.dropOff && !blocked(box, Job.Deposit) && !signPending(box)) base.put(box, box.free() + misplacedCount(box));
            }
            if (destCache != null) roomBase = base;
        }
        Map<Box, Integer> used = new HashMap<>();
        for (int carried : carriedSlots()) {
            for (Box box : destinationsFor(mc.player.getInventory().getItem(carried))) {
                if (box != source && base.containsKey(box)) {
                    used.merge(box, 1, Integer::sum);
                    break;
                }
            }
        }
        final Map<Box, Integer> baseRoom = base;
        java.util.function.ToIntFunction<Box> room = b -> b == source || !baseRoom.containsKey(b) ? 0 : baseRoom.get(b) - used.getOrDefault(b, 0);

        // Drop-offs are emptied even when nowhere particular has room: whatever doesn't fit goes to a misc chest,
        // or any container with space
        int spare = source.dropOff ? spareRoom(source) : 0;

        for (int i = 0; i < source.contents.length; i++) {
            ItemStack stack = source.contents[i];
            if (stack.isEmpty() || !misplaced(stack, source)) continue;
            // A bundle with things in it needs a slot for itself and one for what comes out
            if (fullBundle(stack)) {
                if (freeCarryCount() >= 2) return i;
                continue;
            }
            if (destinationsFor(stack).stream().anyMatch(b -> room.applyAsInt(b) > 0)) return i;
            if (spare > 0) return i;
        }

        return -1;
    }

    /**
     * Puts one carried stack that belongs here into this container. When it's full, swaps it with something in here
     * that belongs elsewhere instead, so full containers can still be sorted.
     * @return false when nothing more can be put here
     */
    private boolean depositOne() {
        int size = target.contents.length;

        // Mid-swap: carried stack on the cursor, then into the slot, then what was there back into the carry slot
        ItemStack carried = mc.player.containerMenu.getCarried();
        if (swapStep > 0) {
            // The cursor must hold what the last click should have left on it, otherwise a click was rejected
            ItemStack expected = swapStep == 1 ? swapStack : swapDisplaced;
            if (!ItemStack.matches(carried, expected)) {
                swapStep = 0;
                return recoverBounded();
            }

            if (swapStep == 1) InvUtils.click().slotId(swapSlot);
            else InvUtils.click().slotId(inventoryToMenu(swapCarry, size));

            swapStep = swapStep == 2 ? 0 : swapStep + 1;
            if (swapStep == 0) moved++;
            return true;
        }

        // A stack left on the cursor by an earlier hiccup goes away before anything else is clicked
        if (!carried.isEmpty()) return recoverBounded();

        for (int slot : carriedSlots()) {
            if (kitBuilder.holds(slot)) continue;
            ItemStack stack = mc.player.getInventory().getItem(slot);
            // A bundle with things in it is emptied, never put away as it is
            if (fullBundle(stack)) continue;
            if (!destinationsFor(stack).contains(target)) continue;

            if (canFit(stack)) {
                debug(() -> String.format("Click: shift-click carry slot %d into %s.", slot, xyz(target.pos)));
                InvUtils.shiftClick().slotId(inventoryToMenu(slot, size));
                moved++;
                return true;
            }

            for (int i = 0; i < size; i++) {
                ItemStack here = target.contents[i];
                // A click of one stack on a bundle puts it in the bundle, and a bundle on a stack takes it in: never swapped
                if (here.isEmpty() || isBundle(here) || isBundle(stack) || !misplaced(here, target) || ItemStack.isSameItemSameComponents(here, stack)) continue;

                swapCarry = slot;
                swapSlot = i;
                swapStack = stack.copy();
                swapDisplaced = here.copy();
                stashHeld.put(slot, here.copy());
                swapStep = 1;
                InvUtils.click().slotId(inventoryToMenu(slot, size));
                return true;
            }
        }

        return false;
    }

    /**
     * recoverCursor, but gives up after a few tries in one job, so a click the server keeps rejecting can't loop forever.
     * Giving up counts as getting nothing done, so the box is skipped until something else moves.
     */
    private boolean recoverBounded() {
        if (++recoveries > 3) {
            actions = 0;
            return false;
        }
        return recoverCursor();
    }

    /**
     * Puts whatever is on the cursor into an empty slot, the player's own slots first.
     * @return false if there's nothing to put it in
     */
    private boolean recoverCursor() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu.getCarried().isEmpty()) return true;
        lastOwnAct = ticks;

        int size = menu.slots.size() - 36;
        // The carry slots first, so the stack is put away with the rest, and noted as the stash's own
        for (int pass = 0; pass < 2; pass++) {
            for (int i = size; i < menu.slots.size(); i++) {
                if (!menu.getSlot(i).getItem().isEmpty()) continue;
                int slot = i - size < 27 ? 9 + i - size : i - size - 27;
                if ((pass == 0) != carry.contains(slot)) continue;

                stashHeld.put(slot, menu.getCarried().copy());
                InvUtils.click().slotId(i);
                return true;
            }
        }
        // Only into the container we're working on, never some other menu
        if (target != null && menu instanceof ChestMenu && size == target.parts.size() * 27) {
            for (int i = 0; i < size; i++) {
                if (menu.getSlot(i).getItem().isEmpty()) {
                    InvUtils.click().slotId(i);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Puts carried stacks down in a container with space, when nowhere they belong has room for them.
     */
    private boolean dumpOne() {
        for (int slot : carriedSlots()) {
            if (kitBuilder.holds(slot) || fullBundle(mc.player.getInventory().getItem(slot)) || !canFit(mc.player.getInventory().getItem(slot))) continue;

            InvUtils.shiftClick().slotId(inventoryToMenu(slot, target.contents.length));
            return true;
        }
        return false;
    }

    private boolean canFit(ItemStack stack) {
        for (ItemStack here : target.contents) {
            if (here.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(here, stack) && here.getCount() < here.getMaxStackSize()) return true;
        }
        return false;
    }

    // Planning

    /**
     * Works out where everything belongs. Signs mode needs no planning, each item is matched on its own.
     */
    private void plan() {
        tierBoxes.clear();
        // The kit view is looked at again once per pass, whatever the cheap checks say; so is whether kit routing applies
        kitViewOn = false;
        kitRoutingFlag = !repairer.active();
        kitRoutedCache = new HashMap<>();
        if (mode.get() != Mode.Tiers) return;

        // Kit-reserved containers hold kits only, and kits that have a chest of their own are not part of the tiers
        List<Box> ordered = stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && !kitReserved(b)).sorted(Comparator.comparingInt(b -> b.order)).toList();

        // How many stacks of each tier there are, including drop-offs and what we're carrying
        int[] counts = new int[5];
        for (Box box : stash.boxes) {
            if (!box.sortable()) continue;
            for (ItemStack stack : box.contents) if (!stack.isEmpty() && !kitRouted(stack)) counts[tier(stack)]++;
        }
        for (int slot : carriedSlots()) {
            ItemStack carriedStack = mc.player.getInventory().getItem(slot);
            if (!kitRouted(carriedStack)) counts[tier(carriedStack)]++;
        }

        // Fill the containers in order, a tier at a time. A container can end up shared by two tiers.
        int index = 0, used = 0;
        for (int t = 0; t < counts.length; t++) {
            List<Box> list = new ArrayList<>();
            int need = counts[t];

            while (need > 0 && index < ordered.size()) {
                Box box = ordered.get(index);
                if (!list.contains(box)) list.add(box);

                int take = Math.min(need, box.contents.length - used);
                need -= take;
                used += take;

                if (used >= box.contents.length) {
                    index++;
                    used = 0;
                }
            }

            // More of this tier than room: the overflow can sit in the last container
            if (list.isEmpty() && !ordered.isEmpty()) list.add(ordered.get(Math.min(index, ordered.size() - 1)));
            tierBoxes.put(t, list);
        }
    }

    /**
     * Where a stack should go: the boxes it belongs in, all equally good. Empty if it doesn't belong anywhere in
     * particular, in which case it's left where it is. Never an excluded or drop-off container.
     */
    private List<Box> destinationsFor(ItemStack stack) {
        if (stack.isEmpty() || orderRunner.claims(stack)) return List.of();
        if (destCache == null) return computeDestinations(stack);
        // A routed kit's destination depends on where this very stack is stored (a stored kit only moves up its chain): never shared by content
        if (kitRouted(stack)) return computeDestinations(stack);
        return destCache.computeIfAbsent(DestKey.of(stack), k -> computeDestinations(stack));
    }

    private List<Box> computeDestinations(ItemStack stack) {
        // A kit shulker (a custom name with the word kit) goes where the website says, before Tiers, signs and contents are looked at.
        // An order's own stacks never get here (destinationsFor leaves them out through orderRunner.claims)
        KitRouting.View<Box> kitRules = kitView();
        if (kitRules != null && kitRoutingOn()) {
            KitRouting.Route<Box> route = kitRoute(kitRules, stack);
            // A resolved chest that is not one of the stash's boxes any more: the view is stale, made again once
            if (route.box() != null && !stash.boxes.contains(route.box())) {
                kitViewOn = false;
                kitRules = kitView();
                route = kitRules == null ? route : kitRoute(kitRules, stack);
            }
            if (kitRules != null && route.decision().routed()) {
                if (route.kind() == KitRouting.Kind.MISSING) kitMissing(route.decision(), kitType(stack));
                else if (route.fallback()) kitFallback(route.decision(), kitType(stack));
                return kitRules.destinations(route);
            }
        }

        if (mode.get() == Mode.Tiers) return tierBoxes.getOrDefault(tier(stack), List.of());

        // An empty bundle goes to a chest signed for bundles, else one signed for shulkers or containers
        if (isBundle(stack)) {
            List<Box> bundles = bundleHomes(stack);
            if (!bundles.isEmpty()) return bundles;
        }

        // A finished kit, or one part built with more than one kind of item, goes to the chest signed with its name.
        // With no such chest it's treated like any other shulker.
        Kits.Match kit = Kits.match(stack);
        Kit owner = kit != null && kit.claims() ? kit.kit() : kitBuilder.heldKit(stack);
        if (owner != null) {
            List<Box> homes = Kits.homes(owner, stash);
            if (!homes.isEmpty()) return homes;
        }

        // A shulker goes where what it's mostly full of goes: a shulker of totems belongs with the totems. Only when
        // no sign describes its contents does it fall back to signs about shulkers themselves, like "shulkers" or "kits".
        // Mixed shulkers go whole to a chest signed for kits, when there is one
        if (mixedShulker(stack)) {
            List<Box> kits = keepWholeBoxes();
            if (!kits.isEmpty()) return kits;
        }

        ItemStack inside = Categories.dominantContent(stack);
        if (inside != null) {
            List<Box> byContents = bestSigned(inside);
            if (!byContents.isEmpty()) return byContents;
        }

        return bestSigned(stack);
    }

    /**
     * The containers whose signs describe this item best, all tied, or empty if no sign describes it.
     */
    private List<Box> bestSigned(ItemStack stack) {
        Map<DestKey, List<Box>> cache = signedCache;
        if (cache == null || stack.isEmpty()) return computeBestSigned(stack);
        return cache.computeIfAbsent(DestKey.of(stack), k -> computeBestSigned(stack));
    }

    private List<Box> computeBestSigned(ItemStack stack) {
        int best = 0;
        List<Box> result = new ArrayList<>();
        Categories.Described item = Categories.describe(stack);
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.dropOff) continue;

            // Kit chests only take their kits, not loose items that happen to match the name
            if (isKitBox(box)) continue;

            int score = 0;
            for (String label : box.labels) score = Math.max(score, Categories.signScore(label, item));

            if (score > best) {
                best = score;
                result.clear();
            }
            if (score == best && score > 0) result.add(box);
        }
        if (result.size() > 1) reportTie(stack, result);
        return result;
    }

    // Item id plus the tied containers' positions already reported as fitting two differently signed containers equally.
    // Only cleared when the module is switched on again, so each tie is said once per run (or again when the tied set changes)
    private final Set<String> tiesReported = new HashSet<>();

    /**
     * Two containers with different signs describe an item equally well: it goes to the nearest. Several containers with
     * the same sign are no tie, that is just a category with more than one chest.
     */
    private void reportTie(ItemStack stack, List<Box> tied) {
        Set<String> first = new HashSet<>(tied.getFirst().labels);
        boolean different = false;
        for (Box other : tied) if (!new HashSet<>(other.labels).equals(first)) different = true;
        if (!different) return;
        String key = idOf(stack) + "|" + tied.stream().map(b -> b.pos.toString()).sorted().collect(java.util.stream.Collectors.joining(";"));
        if (tiesReported.add(key)) {
            warning("%s fits %d containers with different signs equally well (%s), it goes to the nearest.", StackInfo.label(stack), tied.size(),
                String.join(" / ", tied.stream().map(b -> b.labels.isEmpty() ? "?" : b.labels.getFirst()).distinct().toList()));
        }
    }

    /**
     * In the wrong place: everything in a drop-off that has somewhere to go, or anything in a container it doesn't
     * belong in.
     */
    private boolean misplaced(ItemStack stack, Box box) {
        // Nothing stays in a drop-off
        if (box.dropOff) return true;

        // A bundle with things in it is taken out to be emptied, wherever it is
        if (fullBundle(stack)) return true;

        // A kit, or part of one, in its own chest is where it belongs
        if (mode.get() == Mode.Signs) {
            Kits.Match kit = Kits.match(stack);
            if (kit != null && Kits.isHome(box, kit.kit())) return false;
        }

        List<Box> places = destinationsFor(stack);
        return !places.isEmpty() && !places.contains(box);
    }

    /**
     * A container signed for anything: misc, other, overflow and the like.
     */
    private boolean isMisc(Box box) {
        return box.labels.stream().anyMatch(Categories::isCatchAll);
    }

    /**
     * Free slots in the containers that take anything (signed misc, other, overflow, or unsigned), outside drop-offs and
     * {@code except}, less what we're already carrying.
     */
    private int spareRoom(Box except) {
        int free = 0;
        for (Box box : stash.boxes) if (box != except && (takesAnything(box, true) || takesAnything(box, false))) free += box.free();
        return free - carriedSlots().size();
    }

    /**
     * Whether a carried stack has somewhere to go right now: a container it belongs in that has room, or one that
     * takes anything.
     */
    private boolean canPlace(ItemStack stack) {
        for (Box box : destinationsFor(stack)) {
            if (!blocked(box, Job.Deposit) && !signPending(box) && (box.free() > 0 || misplacedCount(box) > 0)) return true;
        }
        return spareBox() != null;
    }

    /**
     * Where a stack with no room in the containers it belongs in can be put down: a container signed misc, other or
     * overflow first; failing that an unsigned one with space, which is a free container waiting for a sign.
     */
    private Box spareBox() {
        Box misc = nearest(stash.boxes.stream().filter(b -> takesAnything(b, true)).toList());
        if (misc != null) return misc;

        // Unsigned: one already in use before an empty one, which a sign could still be put on
        List<Box> unsigned = stash.boxes.stream().filter(b -> takesAnything(b, false)).toList();
        Box used = nearest(unsigned.stream().filter(b -> b.free() < b.contents.length).toList());
        return used != null ? used : nearest(unsigned);
    }

    private boolean takesAnything(Box b, boolean signedMisc) {
        if (!b.sortable() || b.dropOff || b.free() <= 0 || blocked(b, Job.Dump) || signPending(b) || isKitBox(b)) return false;
        if (signedMisc) return isMisc(b);

        // Tiers has no signs to respect: any container with space will do
        if (mode.get() == Mode.Tiers) return true;

        // Only in Signs mode, and only a container whose signs can be read now: no sign, not even a blank one
        if (!unsignedOverflow.get() || mode.get() != Mode.Signs || !b.labels.isEmpty()) return false;
        return noSignNow(b);
    }

    /**
     * Whether there is no sign block at all on any side of the container (or on top of it) right now. A blank sign
     * counts: it's a sign that's about to be written. False if any of the places isn't loaded.
     */
    private boolean noSignNow(Box box) {
        for (BlockPos part : box.parts) {
            for (Direction dir : HORIZONTALS) {
                BlockPos signPos = part.relative(dir);
                if (!mc.level.isLoaded(signPos)) return false;
                // Any sign beside it counts, whichever way it faces, hanging ones too
                if (mc.level.getBlockEntity(signPos) instanceof SignBlockEntity) return false;
            }

            BlockPos above = part.above();
            if (!mc.level.isLoaded(above)) return false;
            if (mc.level.getBlockEntity(above) instanceof SignBlockEntity) return false;
        }
        return true;
    }

    /**
     * Two partial stacks of the same item in this container, {first, second}, or null if there are none to merge.
     */
    private static int[] mergeable(Box box) {
        ItemStack[] c = box.contents;
        for (int i = 0; i < c.length; i++) {
            ItemStack a = c[i];
            if (a.isEmpty() || isBundle(a) || a.getMaxStackSize() <= 1 || a.getCount() >= a.getMaxStackSize()) continue;
            for (int j = c.length - 1; j > i; j--) {
                ItemStack b = c[j];
                if (!b.isEmpty() && ItemStack.isSameItemSameComponents(a, b) && b.getCount() < b.getMaxStackSize()) return new int[] {i, j};
            }
        }
        return null;
    }

    /**
     * Merges one later partial stack into an earlier one: pick it up, drop it on the earlier stack, put back what's left.
     */
    private boolean compactOne() {
        int[] pair = mergeable(target);
        if (pair == null) return false;

        InvUtils.click().slotId(pair[1]);
        InvUtils.click().slotId(pair[0]);
        if (!mc.player.containerMenu.getCarried().isEmpty()) InvUtils.click().slotId(pair[1]);
        return true;
    }

    /**
     * Chests signed for kits, which take mixed shulkers whole.
     */
    private List<Box> keepWholeBoxes() {
        return stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && !kitReserved(b) && b.labels.stream().anyMatch(Categories::isKeepWhole)).toList();
    }

    /**
     * A shulker holding more than one kind of item.
     */
    private static boolean mixedShulker(ItemStack stack) {
        if (stack.isEmpty() || !Utils.isShulker(stack.getItem())) return false;
        Item first = null;
        for (ItemStack s : inside(stack)) {
            if (s == null || s.isEmpty()) continue;
            if (first == null) first = s.getItem();
            else if (s.getItem() != first) return true;
        }
        return false;
    }

    private int misplacedCount(Box box) {
        if (misplacedCache == null) return countMisplaced(box);
        Integer cached = misplacedCache.get(box);
        if (cached != null) return cached;
        int count = countMisplaced(box);
        misplacedCache.put(box, count);
        return count;
    }

    private int countMisplaced(Box box) {
        int count = 0;
        for (ItemStack stack : box.contents) if (!stack.isEmpty() && misplaced(stack, box)) count++;
        return count;
    }

    /**
     * 0 filled shulkers, 1 empty shulkers, 2 stacks to 64, 3 stacks to 16, 4 everything else.
     */
    private static int tier(ItemStack stack) {
        if (Utils.isShulker(stack.getItem())) {
            ItemStack[] contents = new ItemStack[27];
            Utils.getItemsInContainerItem(stack, contents);
            for (ItemStack s : contents) if (s != null && !s.isEmpty()) return 0;
            return 1;
        }
        if (stack.getMaxStackSize() == 64) return 2;
        if (stack.getMaxStackSize() == 16) return 3;
        return 4;
    }

    // Containers and signs

    /**
     * Adds containers around us that aren't in the stash yet, and drops ones that have been broken.
     * @return how many were added
     */
    private int addNearbyBoxes() {
        BlockPos center = mc.player.blockPosition();
        int r = radius.get(), h = height.get();
        int added = 0;

        // With a perimeter, nothing outside it belongs to the stash
        // (the delivery container is wherever the user put it: outside the perimeter is fine)
        pruneForeign("scan");
        pruneStrays("scan");
        if (stash.boxes.removeIf(this::blockGone)) stash.markDirty();

        // A chest next to a saved one may have made it a double (or a double been halved): bring the parts up to date
        // and forget one that is now just the other half of an earlier box. Changed boxes get read again.
        for (Box box : stash.boxes) {
            if (!mc.level.isLoaded(box.pos)) continue;
            List<BlockPos> now = makeBox(box.pos).parts;
            if (now.equals(box.parts)) continue;

            boolean resized = now.size() != box.parts.size();
            box.parts.clear();
            box.parts.addAll(now);
            if (resized) box.contents = null;
            stash.markDirty();
        }
        for (int i = stash.boxes.size() - 1; i > 0; i--) {
            for (int j = 0; j < i; j++) {
                if (stash.boxes.get(j).has(stash.boxes.get(i).pos)) {
                    carryMarks(stash.boxes.get(i), stash.boxes.get(j));
                    surveyRetry.succeed(stash.boxes.get(i));
                    failedSurvey.remove(stash.boxes.get(i));
                    stash.boxes.remove(i);
                    stash.markDirty();
                    break;
                }
            }
        }

        // Nothing above takes the delivery container; if it was lost some other way it is made a box again from its position
        stash.ensureDelivery();

        // The perimeter replaces radius and height: every container inside it, in the chunks that are loaded
        if (stash.hasArea()) return added + scanArea();

        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -h, -r), center.offset(r, h, r))) {
            if (!isContainer(p) || stash.boxAt(p) != null) continue;
            stash.boxes.add(makeBox(p.immutable()));
            added++;
        }

        if (added > 0) stash.markDirty();
        return added;
    }

    /**
     * Containers outside the stash perimeter (a damaged or foreign save, a pick outside it): one never read is dropped
     * (nothing is known of it, and walking to one far away wastes the scan); one that was read is kept in the file but
     * flagged foreign, so scanning and sorting skip it. The delivery container may be anywhere.
     */
    private void pruneForeign(String where) {
        if (stash == null) return;
        if (!stash.hasArea() || !inStashDimension()) {
            for (Box box : stash.boxes) box.foreign = false;
            return;
        }
        boolean any = stash.boxes.removeIf(box -> {
            StashSafety.Outside what = StashSafety.outside(box.delivery, box.parts.stream().noneMatch(stash::inArea), box.neverRead());
            if (what == StashSafety.Outside.INSIDE) {
                box.foreign = false;
                return false;
            }
            if (what == StashSafety.Outside.SKIP) {
                if (!box.foreign) QwerteAddon.LOG.info("[stash-sorter] kept the read container at {} ({}): it is outside the stash area {}; it is skipped, not deleted.", xyz(box.pos), where, stash.areaText());
                box.foreign = true;
                return false;
            }
            surveyRetry.succeed(box);
            QwerteAddon.LOG.info("[stash-sorter] dropped the never-read container at {} ({}): it is outside the stash area {}.", xyz(box.pos), where, stash.areaText());
            return true;
        });
        if (any) {
            stash.markDirty();
            failedSurvey.removeIf(b -> !stash.boxes.contains(b));
        }
    }

    /**
     * A stash with no area: never-read containers more than 20000 blocks from every read, non-delivery one are strays
     * (a bad save) and are removed, after the stash file is backed up. Needs at least 5 such reference containers.
     */
    private void pruneStrays(String where) {
        if (stash == null || stash.hasArea() || !inStashDimension()) return;
        List<StashSafety.Spot> spots = new ArrayList<>();
        for (Box b : stash.boxes) spots.add(new StashSafety.Spot(new StashSafety.Pt(b.pos.getX(), b.pos.getZ()), !b.neverRead(), b.delivery));
        List<Integer> idx = StashSafety.strays(spots);
        if (idx.isEmpty()) return;
        if (!stash.backUpFile("Removing stray containers from the stash file")) {
            QwerteAddon.LOG.warn("[stash-sorter] {} stray never-read container(s) found ({}) but the stash file could not be backed up, so none were removed.", idx.size(), where);
            return;
        }
        List<Box> gone = new ArrayList<>();
        for (int i : idx) gone.add(stash.boxes.get(i));
        for (Box b : gone) {
            QwerteAddon.LOG.info("[stash-sorter] removed the stray never-read container at {} ({}): over {} blocks from every read container. Box: parts={}, excluded={}, dropOff={}, noCompact={}, lastRead={}.",
                xyz(b.pos), where, StashSafety.STRAY_DISTANCE, b.parts.stream().map(StashSorter::xyz).toList(), b.excluded, b.dropOff, b.noCompact, b.lastRead);
            stash.boxes.remove(b);
            surveyRetry.succeed(b);
            failedSurvey.remove(b);
        }
        stash.markDirty();
    }

    /**
     * Something a stash can hold is still at this block, whatever the barrels setting says: that setting only decides what
     * is added and read, never what is forgotten.
     */
    private boolean containerBlockHere(BlockPos pos) {
        BlockEntity entity = mc.level.getBlockEntity(pos);
        return entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity;
    }

    /**
     * The stash is known to belong to the dimension we are in (the perimeter's, else the stash's own). Nothing is
     * forgotten for lying outside the perimeter or for a missing block unless this holds.
     */
    private boolean inStashDimension() {
        if (stash == null || mc.level == null) return false;
        String dim = !stash.areaDimension.isEmpty() ? stash.areaDimension : stash.dimension;
        return !dim.isEmpty() && dim.equals(Stash.currentDimension());
    }

    private boolean isContainer(BlockPos pos) {
        BlockEntity entity = mc.level.getBlockEntity(pos);
        return entity instanceof ChestBlockEntity || (barrels.get() && entity instanceof BarrelBlockEntity);
    }

    private Box makeBox(BlockPos pos) {
        List<BlockPos> parts = new ArrayList<>(List.of(pos));
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            parts.add(pos.relative(ChestBlock.getConnectedDirection(state)));
        }
        return new Box(pos, parts);
    }

    private void orderBoxes() {
        // Tiers fill the nearest containers first
        Vec3 start = mc.player.position();
        List<Box> sorted = new ArrayList<>(stash.boxes);
        sorted.sort(Comparator.<Box>comparingDouble(b -> start.distanceToSqr(Vec3.atCenterOf(b.pos)))
            .thenComparingInt(b -> b.pos.getY()).thenComparingInt(b -> b.pos.getX()).thenComparingInt(b -> b.pos.getZ()));
        for (int i = 0; i < sorted.size(); i++) sorted.get(i).order = i;
    }

    /**
     * Signs on the side of a container facing away from it, or standing on top of it. Each line is a label, and so
     * are all the lines of a sign read together, for labels split across lines.
     */
    private void readSigns(Box box) {
        Containers.Signs signs = Containers.read(stash, box);
        if (signs == null) return;
        box.labels.clear();
        box.labels.addAll(signs.labels());
    }

    // Picking containers

    /**
     * @return true when picking, so the click doesn't open the container
     */
    private boolean onRightClick() {
        if (pick.get() == Pick.Off) return false;
        if (mc.player == null || mc.level == null || !mc.mouseHandler.isMouseGrabbed()) return false;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return false;

        BlockPos pos = hit.getBlockPos();
        if (pick.get() == Pick.Corner1 || pick.get() == Pick.Corner2) {
            setCorner(pick.get() == Pick.Corner1 ? 1 : 2, pos.immutable());
            return true;
        }
        if (!isContainer(pos)) {
            notice("That isn't a chest or barrel.");
            return true;
        }

        // A refused delivery spot leaves no box behind
        if (pick.get() == Pick.Delivery && !deliverySpotSettable(pos)) return true;
        if (ensureStash() == null) stash = new Stash(stashName.get(), Stash.currentServer());
        Box box = stash.boxAt(pos);
        if (box == null) {
            if (stash.hasArea() && pick.get() != Pick.Delivery && !stash.inArea(pos)) {
                notice("%s is outside the stash area, it isn't added.", pos.toShortString());
                return true;
            }
            box = makeBox(pos.immutable());
            stash.boxes.add(box);
        }

        if (pick.get() == Pick.CompactSkip) {
            box.noCompact = !box.noCompact;
            notice(box.noCompact ? "%s is never used to gather for compacting." : "%s can be used for compacting again.", box.pos.toShortString());
        }
        else if (pick.get() == Pick.Delivery) {
            if (box.deliveryExtra) notice("%s is already one of the delivery chests (put up above the delivery container). Change the delivery container to forget them.", box.pos.toShortString());
            else {
                boolean set = markDelivery(box);
                notice(set ? "%s is where orders are delivered. It is never sorted." : "%s is a normal container again.", box.pos.toShortString());
            }
        }
        else if (pick.get() == Pick.Exclude) {
            box.excluded = !box.excluded;
            box.dropOff = false;
            if (box.delivery && box.pos.equals(stash.deliveryPos)) stash.deliveryPos = null;
            forgetExtra(box);
            box.delivery = false;
            notice(box.excluded ? "%s won't be sorted." : "%s will be sorted again.", box.pos.toShortString());
        }
        else {
            box.dropOff = !box.dropOff;
            box.excluded = false;
            if (box.delivery && box.pos.equals(stash.deliveryPos)) stash.deliveryPos = null;
            forgetExtra(box);
            box.delivery = false;
            notice(box.dropOff ? "%s is a drop-off, it'll be emptied into the stash." : "%s is a normal container again.", box.pos.toShortString());
        }

        stash.save();
        version++;
        return true;
    }

    /**
     * Makes this box the delivery container, or a normal one again if it was. There is only one: the old one is restored
     * to what it was before. The same for the pick and for stash-manager.
     * @return true when it is now the delivery container
     */
    /** Whether the container's block is really gone (its chunk is loaded and it is no container). */
    private boolean blockGone(Box box) {
        if (!mc.level.isLoaded(box.pos)) return false;
        if (!box.delivery) {
            if (containerBlockHere(box.pos)) return false;
            surveyRetry.succeed(box);
            failedSurvey.remove(box);
            return true;
        }

        // A chest put up for the delivery storage: gone when it is no chest (syncDelivery takes it out of the storage)
        if (box.deliveryExtra) return !(mc.level.getBlockEntity(box.pos) instanceof ChestBlockEntity);

        // The delivery container counts as long as it is a chest or barrel, whatever the barrels setting says
        var entity = mc.level.getBlockEntity(box.pos);
        if (entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity) return false;
        stash.deliveryPos = null;
        alert("Delivery container at %s is gone (block removed), cleared.", box.pos.toShortString());
        return true;
    }

    private boolean markDelivery(Box box) {
        // A chest put up for the delivery storage is not the container: it is left as it is
        if (box.deliveryExtra) return true;
        boolean set = !box.delivery;
        // A different container (or none) forgets the chests put up above the old one
        dropDeliveryExtras();
        List<Box> unread = new ArrayList<>();
        for (Box other : stash.boxes) {
            if (other.delivery) {
                other.delivery = false;
                other.excluded = other.wasExcluded;
                other.dropOff = other.wasDropOff;
                other.wasExcluded = other.wasDropOff = false;
                // Not a delivery box any more: a never-read one is removed rather than kept as an ordinary container
                if (other.neverRead()) unread.add(other);
            }
        }
        for (Box gone : unread) {
            stash.boxes.remove(gone);
            surveyRetry.succeed(gone);
            failedSurvey.remove(gone);
            QwerteAddon.LOG.info("[stash-sorter] removed the never-read container at {} that stopped being a delivery container.", xyz(gone.pos));
        }
        if (set) {
            box.wasExcluded = box.excluded;
            box.wasDropOff = box.dropOff;
            box.delivery = true;
            box.excluded = true;
            box.dropOff = false;
        }
        stash.deliveryPos = set ? box.pos : null;
        return set;
    }

    /**
     * For stash-manager: marks or unmarks the container at this block as the delivery container.
     * @return 1 when it is now the delivery container, 0 when it was unmarked, -1 when it isn't a container, -2 when no
     *         stash is loaded, -3 when the position is not loaded and more than 128 blocks from the player
     */
    int toggleDelivery(BlockPos pos) {
        if (mc.level == null || ensureStash() == null) return -2;
        if (!deliverySpotSettable(pos)) return -3;
        if (!isContainer(pos)) return -1;

        Box box = stash.boxAt(pos);
        // A chest put up for the delivery storage is already a delivery chest
        if (box != null && box.deliveryExtra) return 1;
        if (box == null) {
            box = makeBox(pos.immutable());
            stash.boxes.add(box);
        }
        boolean set = markDelivery(box);
        stash.save();
        version++;
        return set ? 1 : 0;
    }

    /** A delivery spot is refused when it is not loaded and far from the player (a mistyped or stale position). */
    private boolean deliverySpotSettable(BlockPos pos) {
        if (mc.player == null) return false;
        boolean ok = StashSafety.spotSettable(mc.level.isLoaded(pos), new StashSafety.Pt(pos.getX(), pos.getZ()),
            new StashSafety.Pt(mc.player.getBlockX(), mc.player.getBlockZ()));
        if (!ok) notice("%s is not loaded and more than %d blocks from you, so it is not made the delivery container. Go there first.", pos.toShortString(), StashSafety.FAR_LIMIT);
        return ok;
    }

    /** For stash-manager: no delivery container any more. @return false when there was none or no stash */
    boolean clearDelivery() {
        if (ensureStash() == null) return false;
        Box was = null;
        for (Box b : stash.boxes) if (b.delivery) was = b;
        if (was == null) return false;
        markDelivery(was);
        stash.save();
        version++;
        return true;
    }


    // The stash area editor (0.4.0-dev.20, AREA-CONTRACT.md): an area from the website or a local edit waits in areaPending and is applied here, on the
    // tick, only between tasks; a website area further than 128 blocks away or for another dimension waits; a local edit is reported as "edited" until the
    // server has it

    private final com.porter.qwerte.stash.AreaPending areaPending = new com.porter.qwerte.stash.AreaPending();

    public com.porter.qwerte.stash.AreaPending areaPending() {
        return areaPending;
    }

    /** The set area as a box (min and max corners), or null. */
    private static int[] boxOf(Stash s) {
        if (!s.hasCorners()) return null;
        BlockPos lo = s.areaMin(), hi = s.areaMax();
        return new int[] { lo.getX(), lo.getY(), lo.getZ(), hi.getX(), hi.getY(), hi.getZ() };
    }

    /** The set area's dimension as the contract names it ("minecraft:overworld"), or null when it isn't known. */
    private static String areaDimensionOf(Stash s) {
        String d = com.porter.qwerte.stash.CraftBounds.fullId(!s.areaDimension.isEmpty() ? s.areaDimension : s.dimension);
        return d.isEmpty() ? null : d;
    }

    /** The stash's dimension as the contract names it, or null. */
    private static String stashDimensionOf(Stash s) {
        String d = com.porter.qwerte.stash.CraftBounds.fullId(s.dimension);
        return d.isEmpty() ? null : d;
    }

    /** A dimension id in the form this addon keeps ({@code ResourceKey[minecraft:dimension / minecraft:overworld]}): the current one's own text when it is that one. */
    private static String rawDimension(String full) {
        String current = Stash.currentDimension();
        if (!current.isEmpty() && com.porter.qwerte.stash.CraftBounds.sameDimension(current, full)) return current;
        return "ResourceKey[minecraft:dimension / " + full + "]";
    }

    /** The area fields of the heartbeat, or null with no stash or an area whose dimension is not known. */
    public StashIndex.AreaReport areaReport() {
        Stash s = ensureStash();
        if (s == null) return null;
        int[] box = boxOf(s);
        String dimension = null;
        if (box != null) {
            dimension = areaDimensionOf(s);
            if (dimension == null) return null;
        }
        List<int[]> blocks = new ArrayList<>();
        int outside = 0;
        for (Box b : s.boxes) {
            if (b.delivery) continue;
            if (box != null && b.foreign) outside++;
            if (b.known() && !b.foreign) for (BlockPos p : b.parts) blocks.add(new int[] { p.getX(), p.getY(), p.getZ() });
        }
        String key = areaKey();
        com.porter.qwerte.stash.AreaPending.Request pending = areaPending.get(key);
        return new StashIndex.AreaReport(com.porter.qwerte.stash.AreaRules.hashOf(box, dimension), box, dimension,
            box == null ? com.porter.qwerte.stash.AreaRules.derived(blocks) : null, areaPending.edited(key),
            pending == null ? null : pending.hash(), pending == null ? null : areaPending.why(), outside);
    }

    /** A cheap signature of what the heartbeat says about the area, so a change sends a heartbeat at once. */
    public String areaSig() {
        Stash s = stash;
        String key = areaKey();
        com.porter.qwerte.stash.AreaPending.Request p = areaPending.get(key);
        return (s == null ? "-" : s.corner1 + "/" + s.corner2 + "/" + s.areaDimension) + "|" + (p == null ? "-" : p.hash() + areaPending.why()) + "|" + areaPending.edited(key);
    }

    /** Queues a local area edit (the window's area card): applied between tasks, then reported as edited. {@code box} null clears. */
    public String queueLocalArea(int[] box, String dimension) {
        int[] copy = box == null ? null : box.clone();
        String dim = dimension != null ? dimension : StashIndex.dimension();
        if (copy != null) {
            if (dim.isEmpty()) return "no dimension";
            Stash s = stash;
            String own = s == null ? null : stashDimensionOf(s);
            String refused = com.porter.qwerte.stash.AreaRules.validate(copy, dim, own != null ? own : dim);
            if (refused != null) return refused;
        }
        areaPending.queue(new com.porter.qwerte.stash.AreaPending.Request(copy, copy == null ? null : dim, false), areaKey());
        return null;
    }

    /** The key of the stash this module works on (server and name): a queued area or an unacknowledged edit belongs to one stash. */
    public String areaKey() {
        return Stash.currentServer() + "/" + stashName.get();
    }

    /** The area waiting for the current stash, or null. */
    public com.porter.qwerte.stash.AreaPending.Request pendingArea() {
        return areaPending.get(areaKey());
    }

    /** The bot is in the middle of something an area change must not cut short. */
    private boolean areaBusy(boolean website) {
        boolean hard = orderRunner.holdsOrder() || orderRunner.resumeId() != null || job != null || handle != null || placed != null || pendingResume != null
            || crafter.active() || kitBuilder.active() || repairer.active() || compactor.active() || (stash != null && stash.inFlight != null) || !orderHeld.isEmpty()
            || chestHolding || chestSession || tableSession || placingTable || anvilSession || returning || !activeCarried().isEmpty();
        // Leftovers with no destination must not hold the area forever: after 10 minutes of nothing else, a change made in game goes through
        // (the website one still waits, and is reported as busy)
        return areaPending.held().busy(hard, !stashHeld.isEmpty(), website, System.currentTimeMillis());
    }

    private String areaWaitingWhy;
    private long areaWaitingAt;

    /** A waiting area is said in chat when the reason changes and every 10 minutes after, so it never waits without a word. */
    private void announceAreaWaiting(String why) {
        long now = System.currentTimeMillis();
        if (why.equals(areaWaitingWhy) && now - areaWaitingAt < 10 * 60_000L) return;
        areaWaitingWhy = why;
        areaWaitingAt = now;
        String words = switch (why) {
            case com.porter.qwerte.stash.AreaRules.FAR -> "the bot is more than 128 blocks away";
            case com.porter.qwerte.stash.AreaRules.DIMENSION -> "the bot is in another dimension than the area";
            default -> "the bot is busy with a task";
        };
        notice("A stash area change is waiting: %s. The stash keeps working with its current area until then.", words);
    }

    /** Applies the queued area when the bot is between tasks, near enough and in the right dimension. Every tick, client thread. */
    private void applyPendingArea() {
        String key = areaKey();
        // A request or an edit made for another stash (or server) is nothing here
        areaPending.dropOthers(key);
        com.porter.qwerte.stash.AreaPending.Request r = areaPending.get(key);
        if (r == null || mc.player == null || mc.level == null) return;
        Stash s = ensureStash();
        if (s == null) return;
        String current = StashIndex.dimension();
        // The dimension of a local edit is resolved now: the one it was queued with, else the bot's
        String dimension = com.porter.qwerte.stash.AreaSync.resolveDimension(r.dimension(), current.isEmpty() ? null : current);
        if (r.box() != null && dimension == null) {
            areaPending.setWhy(com.porter.qwerte.stash.AreaRules.BUSY);
            return;
        }
        String why = com.porter.qwerte.stash.AreaRules.pendingWhy(r.box(), dimension, current.isEmpty() ? null : current, r.website(), mc.player.getX(), mc.player.getZ(),
            areaBusy(r.website()) ? com.porter.qwerte.stash.AreaRules.BUSY : null);
        areaPending.setWhy(why == null ? com.porter.qwerte.stash.AreaRules.BUSY : why);
        if (why != null) {
            announceAreaWaiting(why);
            return;
        }
        areaWaitingWhy = null;
        if (r.box() != null) {
            // Checked again against this stash's own dimension, whoever queued it
            String own = stashDimensionOf(s);
            // An area from the website is never checked against itself: a stash with no known dimension takes none. A local edit is made
            // in the dimension the player is in, so it may be the stash's first
            String refused = r.website() && own == null ? "the stash's dimension isn't known"
                : com.porter.qwerte.stash.AreaRules.validate(r.box(), dimension, own != null ? own : dimension);
            if (refused != null) {
                areaPending.clear(r);
                warning("The stash area was not applied: %s.", refused);
                return;
            }
        }
        if (r.box() == null) {
            s.corner1 = null;
            s.corner2 = null;
            s.areaDimension = "";
        } else {
            s.corner1 = new BlockPos(r.box()[0], r.box()[1], r.box()[2]);
            s.corner2 = new BlockPos(r.box()[3], r.box()[4], r.box()[5]);
            s.areaDimension = rawDimension(dimension);
        }
        // Only the request that was applied is removed: one queued meanwhile stays. A change made in game is reported until the server has this area
        areaPending.clear(r);
        if (!r.website()) areaPending.markEdited(com.porter.qwerte.stash.AreaRules.hashOf(r.box(), r.box() == null ? null : dimension), key);
        s.markDirty();
        s.save();
        // Only the size in chat: the positions are for the user's own stash area command
        notice("Stash area %s.", r.box() == null ? "cleared" : "changed (" + com.porter.qwerte.stash.AreaEdit.sizeOf(r.box()) + ")");
        areaChanged();
    }

    // The stash perimeter

    /**
     * Makes the block the corner of the stash perimeter. The perimeter only applies once both corners are set.
     */
    private void setCorner(int which, BlockPos pos) {
        if (ensureStash() == null) stash = new Stash(stashName.get(), Stash.currentServer());
        // Corners set in another dimension don't go with this one
        String dimension = Stash.currentDimension();
        if (stash.hasCorners() || stash.corner1 != null || stash.corner2 != null) {
            if (!stash.areaDimension.isEmpty() && !com.porter.qwerte.stash.CraftBounds.sameDimension(stash.areaDimension, dimension)) {
                stash.corner1 = null;
                stash.corner2 = null;
            }
        }
        BlockPos other = which == 1 ? stash.corner2 : stash.corner1;
        // A change of ours that is still waiting is the area as it will be: the other corner comes from it, not from the corners applied now
        com.porter.qwerte.stash.AreaPending.Request waiting = pendingArea();
        if (waiting != null && !waiting.website()) {
            int[] wb = waiting.box();
            if (wb == null) {
                error("Clearing the stash area is still waiting for the bot to be between tasks: set the corner after it is done.");
                return;
            }
            other = which == 1 ? new BlockPos(wb[3], wb[4], wb[5]) : new BlockPos(wb[0], wb[1], wb[2]);
        }
        if (other != null && Stash.tooBig(pos, other)) {
            error("That area is %d\u00d7%d\u00d7%d, the largest allowed is %d\u00d7%d\u00d7%d (width \u00d7 height \u00d7 depth).",
                Math.abs(pos.getX() - other.getX()) + 1, Math.abs(pos.getY() - other.getY()) + 1, Math.abs(pos.getZ() - other.getZ()) + 1,
                Stash.MAX_X, Stash.MAX_Y, Stash.MAX_Z);
            return;
        }

        // The corner that completes an area is a local edit: it waits for the bot to be between tasks (a lone corner changes nothing yet)
        if (other != null) {
            int[] box = { Math.min(pos.getX(), other.getX()), Math.min(pos.getY(), other.getY()), Math.min(pos.getZ(), other.getZ()),
                Math.max(pos.getX(), other.getX()), Math.max(pos.getY(), other.getY()), Math.max(pos.getZ(), other.getZ()) };
            String full = StashIndex.dimension();
            String refused = queueLocalArea(box, full);
            if (refused != null) {
                error("That area can't be used: %s.", refused);
                return;
            }
            notice("Stash area change queued (%s): it is applied when the bot is between tasks.", com.porter.qwerte.stash.AreaEdit.sizeOf(box));
            return;
        }
        if (which == 1) stash.corner1 = pos;
        else stash.corner2 = pos;
        stash.areaDimension = dimension;
        stash.markDirty();
        stash.save();

        if (stash.hasArea()) {
            notice("Stash area set: %s.", stash.areaText());
            areaChanged();
        }
        else notice("Corner %d set at [%d, %d, %d]. Set corner %d to finish the stash area.", which, pos.getX(), pos.getY(), pos.getZ(), which == 1 ? 2 : 1);
    }

    private void clearArea() {
        if (ensureStash() == null || (stash.corner1 == null && stash.corner2 == null)) {
            notice("There is no stash area set.");
            return;
        }
        if (stash.hasCorners()) {
            // A set area is cleared between tasks, like any local edit
            queueLocalArea(null, null);
            notice("Clearing the stash area is queued: it is done when the bot is between tasks.");
            return;
        }
        stash.corner1 = null;
        stash.corner2 = null;
        stash.areaDimension = "";
        stash.markDirty();
        stash.save();
        notice("Stash area cleared, finding containers around you again.");
    }

    /**
     * The perimeter was set or cleared: nothing counts as explored or unreachable any more, and an exploration under
     * way ends at once. With the module on, dropping containers outside it, adding the ones inside and the new order wait
     * until between jobs, so nothing being worked on changes under it.
     */
    private void areaChanged() {
        forgetExploration();
        if (job == Job.Explore) finishExplore();
        if (running()) {
            areaDirty = true;
            version++;
            idle = false;
        }
        else applyArea();
    }

    private void applyArea() {
        areaDirty = false;
        if (mc.player == null || mc.level == null || stash == null) return;
        // An area change drops only never-read containers outside it; read ones are kept and skipped
        pruneForeign("area change");
        addNearbyBoxes();
        orderBoxes();
        stash.save();
        version++;
        idle = false;
    }

    /**
     * Starts the exploration over, and forgets what went wrong: containers that could not be reached or opened, surveys
     * and jobs given up on, so everything gets another try.
     */
    private void forgetExploration() {
        exploredChunks.clear();
        exploreSkipped.clear();
        exploring = false;
        exploreRan = false;
        unreachableReported.clear();
        failedSurvey.clear();
        surveyRetry.clear();
        futile.clear();
        noOpReported.clear();
        cancelRetries.clear();
        clearStalled();
        if (stash != null) {
            for (Box box : stash.boxes) {
                box.unreachable = false;
                box.labelFailed = false;
            }
        }
    }

    /**
     * Adds the containers inside the perimeter in every chunk that is loaded, and notes those chunks as seen. Goes through
     * the chunks' block entities directly.
     * @return how many containers were added
     */
    private int scanArea() {
        if (stash == null || !stash.hasArea() || mc.level == null) return 0;
        BlockPos min = stash.areaMin(), max = stash.areaMax();
        boolean withBarrels = barrels.get();
        int added = 0;

        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                exploredChunks.add(ChunkPos.asLong(cx, cz));

                for (BlockEntity entity : mc.level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (!(entity instanceof ChestBlockEntity || (withBarrels && entity instanceof BarrelBlockEntity))) continue;
                    BlockPos pos = entity.getBlockPos();
                    if (!stash.inArea(pos) || stash.boxAt(pos) != null) continue;
                    stash.boxes.add(makeBox(pos.immutable()));
                    added++;
                }
            }
        }

        if (added > 0) {
            stash.markDirty();
            orderBoxes();
        }
        return added;
    }

    private int columnsTouched() {
        BlockPos min = stash.areaMin(), max = stash.areaMax();
        return ((max.getX() >> 4) - (min.getX() >> 4) + 1) * ((max.getZ() >> 4) - (min.getZ() >> 4) + 1);
    }

    private List<Long> pendingChunks() {
        List<Long> list = new ArrayList<>();
        BlockPos min = stash.areaMin(), max = stash.areaMax();
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                if (!exploredChunks.contains(key) && !exploreSkipped.contains(key)) list.add(key);
            }
        }
        return list;
    }

    /** The middle of the part of the chunk column inside the perimeter, in X. */
    private int columnX(long key) {
        int cx = (int) (key & 0xFFFFFFFFL);
        return (Math.max(stash.areaMin().getX(), cx * 16) + Math.min(stash.areaMax().getX(), cx * 16 + 15)) / 2;
    }

    /** The middle of the part of the chunk column inside the perimeter, in Z. */
    private int columnZ(long key) {
        int cz = (int) (key >>> 32);
        return (Math.max(stash.areaMin().getZ(), cz * 16) + Math.min(stash.areaMax().getZ(), cz * 16 + 15)) / 2;
    }

    private void warnNoBaritone() {
        if (baritoneWarned) return;
        baritoneWarned = true;
        warning("Baritone isn't installed, so I can't walk the stash area or explore it. Containers in loaded chunks are still used.");
    }

    /**
     * The checks made when turning on: enough containers, and signs on some in Signs mode. With a perimeter they wait for
     * the exploration, which is what finds the containers.
     * @return false if the module was turned off for failing one
     */
    private boolean checkStash() {
        long sortable = stash.boxes.stream().filter(b -> !b.excluded && !b.dropOff).count();
        if (!sorting()) {
            if (stash.boxes.isEmpty()) {
                error("Found no containers within %d blocks.", radius.get());
                turnOff();
                return false;
            }
        }
        else if (sortable < 2) {
            error("Found %d container%s to sort within %d blocks, need at least 2.", sortable, sortable == 1 ? "" : "s", radius.get());
            turnOff();
            return false;
        }

        if (sorting() && mode.get() == Mode.Signs && stash.boxes.stream().allMatch(b -> b.labels.isEmpty())) {
            alertError("None of the containers have signs on them. Put signs on them, or use Tiers mode.");
            turnOff();
            return false;
        }
        return true;
    }

    /**
     * With a perimeter set: walks to the nearest chunk column of it not seen loaded yet, so it loads and its containers
     * can be added. The goal is the middle of the part of the column inside the perimeter, in X and Z only. The activation
     * checks follow the exploration.
     * @return true when a trip has begun
     */
    private boolean startExplore() {
        if (stash == null || !stash.hasArea()) return false;
        scanArea();
        if (!BARITONE) {
            warnNoBaritone();
            return false;
        }

        if (!exploreRan) {
            exploreRan = true;
            exploreRunStart = ticks;
            exploring = true;
            info("Exploring the stash area: %d chunks to look at.", columnsTouched());
        }
        if (!exploring) return false;
        // One run takes half an hour at the most: whatever is still unseen is given up on, and the run is reported
        if (ticks - exploreRunStart > EXPLORE_RUN_LIMIT) {
            for (long key : pendingChunks()) exploreSkipped.add(key);
            info("Exploring the stash area took more than 30 minutes, carrying on with what was found.");
        }

        List<Long> pending = pendingChunks();
        if (pending.isEmpty()) {
            exploring = false;
            idle = false;
            orderBoxes();
            stash.save();
            long inside = stash.boxes.stream().filter(b -> b.parts.stream().anyMatch(stash::inArea)).count();
            info("Explored the stash area: found %d containers (%d chunks couldn't be reached).", inside, exploreSkipped.size());
            checkStash();
            return false;
        }

        Vec3 here = mc.player.position();
        long best = pending.getFirst();
        double bestDistance = Double.MAX_VALUE;
        for (long key : pending) {
            double dx = columnX(key) - here.x, dz = columnZ(key) - here.z;
            if (dx * dx + dz * dz < bestDistance) {
                bestDistance = dx * dx + dz * dz;
                best = key;
            }
        }

        exploreTarget = best;
        exploreX = columnX(best);
        exploreZ = columnZ(best);
        int chunksTotal = columnsTouched();
        int chunksDone = Math.max(0, chunksTotal - pending.size());
        stepProgress("explore", chunksDone, chunksTotal, () -> String.format("exploring the area: %d/%d chunks; next: chunk at %d %d.", chunksDone, chunksTotal, exploreX, exploreZ));
        exploreTicks = 0;
        exploreIssue = 0;
        exploreStanding = 0;
        exploreBest = Double.MAX_VALUE;
        exploreBestTick = 0;

        job = Job.Explore;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        walkXZ(exploreX, exploreZ);
        return true;
    }

    private void exploreTick() {
        exploreTicks++;
        if (stash == null || !stash.hasArea() || !BARITONE) {
            finishExplore();
            return;
        }
        if (exploreTicks % 5 == 0) scanArea();
        // Done as soon as the chunk is loaded
        if (exploredChunks.contains(exploreTarget)) {
            finishExplore();
            return;
        }

        Vec3 here = mc.player.position();
        double distance = Math.sqrt((here.x - exploreX) * (here.x - exploreX) + (here.z - exploreZ) * (here.z - exploreZ));
        if (distance < exploreBest - 1) {
            exploreBest = distance;
            exploreBestTick = exploreTicks;
        }
        exploreStanding = distance <= 2 ? exploreStanding + 1 : 0;

        String why = null;
        if (leftArea()) why = "the way there leaves the stash area";
        else if (exploreTicks > EXPLORE_TIMEOUT) why = "it took longer than 40 seconds";
        else if (exploreTicks - exploreBestTick > EXPLORE_STUCK) why = "no way closer for 15 seconds";
        else if (exploreStanding >= EXPLORE_STANDING) why = "it is still not loaded at the spot";
        if (why != null) {
            exploreSkipped.add(exploreTarget);
            int cx = (int) (exploreTarget & 0xFFFFFFFFL), cz = (int) (exploreTarget >>> 32);
            if (safeWalk.get() && !why.startsWith("the way")) debug("Can't reach the chunk at %d, %d without breaking or placing blocks, jumping or falling, skipping it.", cx, cz);
            else debug("Skipping the chunk at %d, %d: %s.", cx, cz, why);
            finishExplore();
            return;
        }

        // Baritone stopped short, told again
        if (exploreTicks >= 10 && exploreTicks - exploreIssue >= 60 && !BaritoneHelper.isMoving()) {
            exploreIssue = exploreTicks;
            walkXZ(exploreX, exploreZ);
        }
    }

    private void finishExplore() {
        releaseGoal();
        if (job == Job.Explore) job = null;
        idle = false;
        wait = delay.get();
    }

    // Containers that can't be got to

    private static String at(BlockPos pos) {
        return String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * What the block is called in messages: chest, trapped chest or barrel.
     */
    private String blockName(Box box) {
        if (mc.level == null) return "chest";
        var block = mc.level.getBlockState(box.pos).getBlock();
        if (block instanceof TrappedChestBlock) return "trapped chest";
        if (block instanceof BarrelBlock) return "barrel";
        return "chest";
    }

    private enum Cause { Path, Open, Area }

    /**
     * Says once per container why it is skipped.
     */
    private void reportUnreachable(Box box, Cause cause) {
        if (!unreachableReported.add(box.pos)) return;
        String name = blockName(box);
        switch (cause) {
            case Path -> {
                if (safeWalk.get()) alert("Can't reach the %s at %s without breaking or placing blocks, jumping or falling, skipping it.", name, at(box.pos));
                else alert("Can't reach the %s at %s without breaking or placing blocks, skipping it.", name, at(box.pos));
            }
            case Open -> alert("Couldn't open the %s at %s, skipping it.", name, at(box.pos));
            case Area -> alert("Skipping the %s at %s: the way there leaves the stash area.", name, at(box.pos));
        }
    }

    private String areaText() {
        if (ensureStash() == null || (stash.corner1 == null && stash.corner2 == null)) return "Stash area: none, containers are found around you (radius and height).";
        if (stash.hasCorners() && !stash.hasArea()) return "Stash area: set in another dimension, not used here.";
        if (!stash.hasArea()) return "Stash area: corner " + (stash.corner1 != null ? "1 is set at " + stash.corner1.toShortString() : "2 is set at " + stash.corner2.toShortString()) + ", the other is missing.";
        com.porter.qwerte.stash.AreaPending.Request waiting = pendingArea();
        return "Stash area: " + stash.areaText() + "." + (waiting == null ? "" : " A change waits to be applied (" + areaPending.why() + ").");
    }

    /**
     * Outlines the stash perimeter. Also called by stash-overlay when this module is off.
     */
    public void drawArea(Render3DEvent event) {
        // The tracker keeps the stash loaded every tick, even with the module off: nothing is looked up per frame
        Stash current = stash;
        if (mc.level == null || current == null || !current.hasArea()) return;
        if (current.corner1 != boxCorner1 || current.corner2 != boxCorner2) {
            boxCorner1 = current.corner1;
            boxCorner2 = current.corner2;
            BlockPos min = current.areaMin(), max = current.areaMax();
            boxMin = new double[] { min.getX(), min.getY(), min.getZ() };
            boxMax = new double[] { max.getX() + 1, max.getY() + 1, max.getZ() + 1 };
        }
        event.renderer.box(boxMin[0], boxMin[1], boxMin[2], boxMax[0], boxMax[1], boxMax[2], areaColor.get(), areaColor.get(), ShapeMode.Lines, 0);
    }

    // The outline as last worked out, for the corners it was worked out for
    private BlockPos boxCorner1, boxCorner2;
    private double[] boxMin = new double[3], boxMax = new double[3];

    // The stash, kept up to date

    /**
     * The stash named in the settings, loaded from disk the first time it's needed on this server.
     */
    private Stash ensureStash() {
        if (mc.level == null) return null;

        String server = Stash.currentServer();
        if (stash != null && stash.name.equals(stashName.get()) && stash.server.equals(server)) return stash;

        // Only try the disk once per name and server, a missing stash isn't looked for again every tick
        String key = server + "/" + stashName.get();
        if (!key.equals(triedKey)) {
            triedKey = key;
            stash = Stash.load(stashName.get());
            pruneForeign("load");
            pruneStrays("load");
            // Another stash, name or server: its exploration and what failed with the last one don't carry over
            forgetExploration();
            areaDirty = false;
        }
        else stash = null;

        return stash;
    }

    /**
     * The saved stash named in the settings, for other modules like stash-overlay. Null if there isn't one here.
     */
    public Stash currentStash() {
        return ensureStash();
    }

    private class Tracker {
        // Driven by stash-manager the module's own handlers are not subscribed: the loop is ticked from here
        @EventHandler
        private void onEngineTick(TickEvent.Pre event) {
            if (driven && !isActive() && mc.player != null && mc.level != null) StashSorter.this.onTick(event);
        }

        @EventHandler
        private void onGameLeft(GameLeftEvent event) {
            driven = false;
            setUp = false;
            areaPending.reset();
            // Before anything is forgotten: what is carried and the shulker standing in the world are written down
            if (stash != null) {
                BlockPos standing = standingShulker();
                offline = true;
                syncInFlight(true);
                if (standing != null && stash.inFlight != null) alert("Disconnected mid-job: shulker at %s kept, it will be finished on return.", standing.toShortString());
                syncHeld();
            }
            if (stash != null && stash.isDirty()) stash.save();
            job = null;
            target = null;
            handle = null;
            placed = null;
            placedConfirmed = false;
            resuming = false;
            resumeVerify = false;
            pendingResume = null;
            syncedHandle = null;
            syncedPlaced = null;
            stash = null;
            triedKey = null;
            trackedMenu = null;
            trackedBox = null;
            interactPos = null;
            interactMenu = null;
            replies.clear();
            alertQueue.clear();
            worldTicks = 0;
            kitBuilder.reset();
            crafter.reset();
            craftForOrder = false;
            orderRunner.reset();
            orderHeld.clear();
            heldFrom.clear();
            returnGaveUp.clear();
            returnWarned.clear();
            stashHeld.clear();
            labeller.reset();
            compactor.reset();
            swapBackFrom = swapBackTo = -1;
            hotbarDisplaced = ItemStack.EMPTY;
            stashWatch.clear();
        deliveryWatch.clear();
            ownTouch.clear();
            readAt.clear();
            staleBoxes.clear();
            deferredAccess.clear();
            lastPlayer = null;
            ground.reset();
            forgetExploration();
            // Whatever walk was ours is over with the world, and Baritone gets its settings back
            ourGoal = null;
            if (BARITONE) BaritoneHelper.unconfine();
            ledgeGuard(false);
            Kits.clear();
        }

        @EventHandler
        private void onPacketSend(PacketEvent.Send event) {
            // Whatever the player drops or breaks is theirs, from any source: the keys, clicks, other modules
            ground.onPacket(event.packet);
        }

        @EventHandler
        private void onOpenScreen(OpenScreenEvent event) {
            if (isActive() && job == Job.Label) labeller.onScreen(event);
        }

        @EventHandler
        private void onInteract(InteractBlockEvent event) {
            if (mc.player == null) return;
            interactPos = event.result.getBlockPos();
            interactMenu = mc.player.containerMenu;
            interactClock = clock;
            // Whoever's hand it is, the lid that opens next is not another player's
            if (stash != null && stash.boxAt(interactPos) != null) ownTouch.put(stash.boxAt(interactPos), clock);
        }

        @EventHandler
        private void onTick(TickEvent.Post event) {
            if (mc.player == null || mc.level == null) return;
            offline = false;
            clock++;
            worldTicks++;
            Stash.remember();
            if (mc.level != null) cachedDimension = mc.level.dimension().toString();
            applyPendingArea();
            pumpAlerts();
            ground.update();
            // Not walking for us: Baritone's settings are the user's own
            if (BARITONE) {
                if (ourGoal == null) BaritoneHelper.unconfine();
                // The user's own Baritone run took over our goal: their settings are theirs at once, not after the yield
                else if (BaritoneHelper.isActive() && !BaritoneHelper.goalIs((baritone.api.pathing.goals.Goal) ourGoal)) BaritoneHelper.unconfine();
            }
            // Safeguard for the sneak: it never outlives the module
            if (ledgeSneaking && !running()) ledgeGuard(false);
            labeller.restoreHand();
            placer.restoreHand();
            restoreHotbar();

            // A new player entity means joining, dying or changing dimension: whatever was held is not ours to claim now
            if (mc.player != lastPlayer) {
                if (lastPlayer != null) {
                    stashHeld.clear();
                    swapBackFrom = swapBackTo = -1;
                    hotbarDisplaced = ItemStack.EMPTY;
                    // Dead, or in another dimension: a sign being put up is lost with the old player
                    labeller.abandon();
                    placer.abandon();
                    chestSession = false;
                    chestHolding = false;
                    if (job == Job.Label) {
                        job = null;
                        target = null;
                    }
                    // A crafting table being gathered, crafted or placed is lost with the old player: whoever waited for it is told
                    boolean tableWork = tableSession || (placingTable && job == Job.AnvilPlace);
                    if (job == Job.Ground || job == Job.Explore || job == Job.Unbundle || job == Job.Name || job == Job.Craft || job == Job.AnvilPlace || job == Job.ChestPlace || job == Job.Repair) {
                        if (job == Job.Repair) repairer.abortNow();
                        job = null;
                    }
                    anvilSlot = -1;
                    anvilSession = false;
                    tableSession = false;
                    placingTable = false;
                    if (tableWork) {
                        if (crafter.active()) crafter.finish();
                        crafter.clearLastHeld();
                        craftForOrder = false;
                        tableWhy = TableFinder.failure("the player changed (respawn or dimension)");
                        tableCode = com.porter.qwerte.craft.CraftAuto.PATH_FAILED;
                        tableAcquired(false);
                    }
                    // Another dimension or a new life: nothing was explored there, and the area may not be used at all
                    forgetExploration();
                    // Another world or dimension: the ground is another ground
                    ground.reset();
                }
                lastPlayer = mc.player;
            }

            if (replyCooldown > 0) replyCooldown--;
            else if (!replies.isEmpty()) {
                ChatUtils.sendPlayerMsg(replies.poll());
                replyCooldown = REPLY_GAP_TICKS;
            }

            if (ensureStash() == null) return;
            watchStash();

            // The sorter reads the containers it opens itself
            if (running() && job != null) return;

            AbstractContainerMenu menu = mc.player.containerMenu;
            if (menu instanceof ChestMenu) {
                if (menu != trackedMenu) {
                    trackedMenu = menu;

                    // Only a menu that opened after our last interact, from that block, and of that box's size
                    trackedBox = null;
                    if (interactPos != null && interactMenu != menu && clock - interactClock <= INTERACT_WINDOW) {
                        Box box = stash.boxAt(interactPos);
                        if (box != null && menu.slots.size() - 36 == box.parts.size() * 27) trackedBox = box;
                    }
                    interactPos = null;
                    interactMenu = null;
                }

                if (trackedBox != null && changed(trackedBox, menu)) {
                    read(trackedBox);
                    version++;
                }
            }
            else if (trackedMenu != null) {
                trackedMenu = null;
                trackedBox = null;
                if (stash.isDirty()) stash.save();
            }
        }

        @EventHandler
        private void onMessage(ReceiveMessageEvent event) {
            if (!whisperSearch.get() || mc.player == null) return;

            String text = event.getMessage().getString().replaceAll("§.", "").trim();
            for (Pattern format : WHISPER_FORMATS) {
                Matcher m = format.matcher(text);
                if (m.matches()) {
                    onWhisper(m.group(1), m.group(2).trim());
                    return;
                }
            }
        }

        @EventHandler
        private void onRender(Render3DEvent event) {
            if (isActive()) drawArea(event);
            if (!render.get() || stash == null) return;

            if (isActive() || pick.get() != Pick.Off) {
                for (Box box : stash.boxes) {
                    SettingColor color = box.excluded ? excludedColor.get() : box.dropOff ? dropOffColor.get() : box.noCompact ? compactSkipColor.get() : null;
                    if (color == null) continue;
                    for (BlockPos part : box.parts) event.renderer.box(part, color, color, shapeMode.get(), 0);
                }
            }

            Box working = target;
            if (isActive() && working != null) {
                for (BlockPos part : working.parts) event.renderer.box(part, workColor.get(), workColor.get(), shapeMode.get(), 0);
            }
        }
    }

    private static boolean changed(Box box, AbstractContainerMenu menu) {
        int size = menu.slots.size() - 36;
        if (!box.known() || box.contents.length != size) return true;
        for (int i = 0; i < size; i++) {
            if (!ItemStack.matches(box.contents[i], menu.getSlot(i).getItem())) return true;
        }
        return false;
    }

    /** Whether the box was read recently enough, by stash-manager's fresh-minutes, to be trusted without reading it again. */
    private boolean fresh(Box box) {
        StashManager manager = meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
        long window = (manager == null ? 5 : manager.freshMinutes()) * 60_000L;
        return window > 0 && box.known() && box.lastRead > 0 && System.currentTimeMillis() - box.lastRead < window;
    }

    private void read(Box box) {
        box.lastRead = System.currentTimeMillis();
        if (stash.dimension.isEmpty() && mc.level != null) {
            stash.dimension = Stash.currentDimension();
            stash.markDirty();
        }
        readAt.put(box, clock);
        AbstractContainerMenu menu = mc.player.containerMenu;
        boolean differs = changed(box, menu);
        if (differs) stash.markDirty();
        dropChecked(box, differs);

        int size = menu.slots.size() - 36;
        ItemStack[] contents = new ItemStack[size];
        for (int i = 0; i < size; i++) contents[i] = menu.getSlot(i).getItem().copy();
        box.contents = contents;
        box.stale = false;
    }

    // Watching the stash

    /**
     * Every tick, even with the module off: lids other players use, and while sorting in Signs mode the text of signs.
     */
    private void watchStash() {
        // Our own and the player's opens are not other players'
        if (orderWork() || returning) lastOrderWork = clock;
        if (running() && (job != null || handle != null)) lastOwnJob = clock;
        if (running() && target != null && job != null) ownTouch.put(target, clock);
        if (trackedBox != null) ownTouch.put(trackedBox, clock);

        if (watch.get() && clock % LID_POLL_TICKS == 0) {
            stashWatch.tickLids(stash, clock, watchDelay.get() * 20L, this::openedByUs, this::accessed);
        }
        // The delivery chests are watched whatever the setting says: a player who emptied one makes it usable again
        if (clock % LID_POLL_TICKS == 0 && !stash.deliveryStorage.isEmpty()) {
            deliveryWatch.tickLids(stash, clock, 20L * 5, this::openedByUs, this::deliveryTouched);
        }

        if (isActive() && signsWatched() && clock % SIGN_POLL_TICKS == 0) {
            stashWatch.pollSigns(stash, clock, b -> job == Job.Label && b == target);
        }
    }

    /**
     * Whether the sorter or the player opened this container just now, as opposed to somebody else.
     */
    private boolean openedByUs(Box box) {
        Integer at = ownTouch.get(box);
        if (at != null && clock - at <= OWN_TOUCH_TICKS) return true;
        // Working on an order, a lid near the bot soon after one of its jobs is its own: a shulker put down or taken up beside
        // a chest, a close that arrives late
        return clock - lastOrderWork <= OWN_TOUCH_TICKS * 2 && clock - lastOwnJob <= OWN_TOUCH_TICKS * 2 && mc.player != null
            && mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(nearestPart(box))) <= 12 * 12;
    }

    /**
     * Another player opened this container of the stash and has closed it again. It's read again through the normal
     * jobs while sorting, between jobs. With the module off it's only forgotten, to be read the next time it runs.
     */
    private void accessed(Box box) {
        debug("Another player used the container at %s.", box.pos.toShortString());
        if (running()) {
            // One we've just read ourselves isn't read again for a minute: a late lid event from our own opening on a
            // laggy server would otherwise start a loop. The visit is kept, once per container, for after that
            Integer last = readAt.get(box);
            if (last != null && clock - last < REREAD_COOLDOWN_TICKS) deferredAccess.putIfAbsent(box, clock);
            else staleBoxes.add(box);
            return;
        }

        // Read again when it runs next; what is known stays in use for searching until then
        box.stale = true;
    }

    /**
     * Visits held back by the cooldown are read once it has passed, or dropped if we have read the container again
     * since the visit, which has shown what it holds now.
     */
    private void releaseDeferred() {
        if (deferredAccess.isEmpty()) return;
        for (Map.Entry<Box, Integer> entry : new ArrayList<>(deferredAccess.entrySet())) {
            Box box = entry.getKey();
            Integer last = readAt.get(box);
            if (last != null && clock - last < REREAD_COOLDOWN_TICKS) continue;

            deferredAccess.remove(box);
            if (last == null || last <= entry.getValue()) staleBoxes.add(box);
        }
    }

    /**
     * Forgets what is known of the containers that were used, so they're read again. Between jobs, so nothing being
     * worked on changes under it.
     */
    private void applyStale() {
        if (staleBoxes.isEmpty()) return;
        for (Box box : new ArrayList<>(staleBoxes)) {
            staleBoxes.remove(box);
            if (!stash.boxes.contains(box) || box.excluded) continue;

            box.stale = true;
            clearStalled();
            compacted.remove(box);
            failedSurvey.remove(box);
            surveyRetry.succeed(box);
            idle = false;
            info("A player used the chest at %s, re-checking it.", box.pos.toShortString());
        }
    }

    /**
     * Signs are watched for changes: while sorting by signs, and in scan-only mode, which keeps the saved labels current.
     */
    private boolean signsWatched() {
        return watchSigns.get() && (mode.get() == Mode.Signs || !sorting());
    }

    /**
     * Between jobs: signs that have been left alone for a while are read again and what they changed is sorted, and now
     * and then all of them, which catches what was missed (a chunk loading in).
     */
    private void applySignChanges() {
        if (!signsWatched()) return;

        if (clock >= nextSignSweep) {
            nextSignSweep = clock + SIGN_SWEEP_TICKS;
            for (Box box : new ArrayList<>(stash.boxes)) {
                if (!stashWatch.pending(box)) checkSigns(box);
            }
        }

        for (Box box : stashWatch.settled(clock, signDelay.get() * 20L)) {
            signAbortRuns.remove(box);
            if (signHeld.remove(box)) stalled.remove(box);
            // A job on it was ended because its sign was unsettled, and it was left out of the plans meanwhile: it gets
            // another look even if the text turns out the same
            if (signAborted.remove(box)) {
                clearStalled();
                idle = false;
            }
            else if (!stuckCarried.isEmpty()) idle = false;
            checkSigns(box);
        }

        // A sign gone or blank for the whole grace period is really gone
        for (Box box : stashWatch.goneLongEnough(clock)) checkSigns(box);
    }

    /**
     * Compares what the signs of a container say now with what we have, and applies the difference.
     */
    private void checkSigns(Box box) {
        List<String> now = stashWatch.labelChange(box, clock);
        if (now == null) return;

        List<String> before = new ArrayList<>(box.labels);
        box.labels.clear();
        box.labels.addAll(now);

        // A player's text on a sign the sorter wrote is theirs from then on
        if (box.autoLabel != null && !now.contains(box.autoLabel)) {
            box.autoLabel = null;
            box.autoSignPos = null;
        }

        // What was sorted by the old sign is sorted again by the new one: the planner already moves whatever is in the
        // wrong container, so it only has to be told to look
        stash.markDirty();
        version++;
        seenVersion = version;
        clearStalled();
        stuckCarried.clear();
        stuckWarned = false;
        compacted.remove(box);
        signHeld.remove(box);
        signAbortRuns.remove(box);
        futile.clear();
        idle = false;

        String was = before.isEmpty() ? "no sign" : before.getFirst(), is = now.isEmpty() ? "no sign" : now.getFirst();
        if (sorting()) info("Sign at %s changed: \"%s\" -> \"%s\", re-sorting.", box.pos.toShortString(), was, is);
        else info("Sign at [%d, %d, %d] changed: \"%s\" -> \"%s\" (scan only).", box.pos.getX(), box.pos.getY(), box.pos.getZ(), was, is);
    }

    // Searching

    private void onWhisper(String sender, String message) {
        if (sender.equalsIgnoreCase(mc.player.getName().getString())) return;
        if (whitelist.get().stream().noneMatch(name -> name.equalsIgnoreCase(sender))) return;

        String query = message;
        String p = prefix.get().trim();
        if (!p.isEmpty()) {
            if (!message.toLowerCase(Locale.ROOT).startsWith(p.toLowerCase(Locale.ROOT) + " ")) return;
            query = message.substring(p.length()).trim();
        }
        if (query.isEmpty() || ensureStash() == null) return;

        List<Stash.Result> results = stash.search(query);
        if (results.isEmpty()) {
            replies.add(String.format("/whisper %s Couldn't find %s @ %s", sender, query, stash.name));
            return;
        }

        Stash.Result result = results.getFirst();
        Stash.Spot spot = result.best();
        BlockPos pos = spot.box().pos;
        String where = spot.inShulker() ? " (in a shulker)" : "";
        replies.add(String.format("/whisper %s %s Can be Found At %d, %d, %d @ %s%s",
            sender, StackInfo.label(result.sample), pos.getX(), pos.getY(), pos.getZ(), stash.name, where));
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WLabel status = list.add(theme.label(statusText())).expandX().widget();
        list.add(theme.label("Compacting: " + (sortItems.get() ? compactor.status() : "off, scan only") + ".")).expandX();
        list.add(theme.label("Ground: " + groundStatus() + ".")).expandX();

        WLabel area = list.add(theme.label(areaText())).expandX().widget();
        WHorizontalList areaButtons = list.add(theme.horizontalList()).widget();
        WButton corner1 = areaButtons.add(theme.button("Set corner 1 here")).widget();
        corner1.tooltip = "Makes the block you are standing in corner 1 of the stash perimeter.";
        corner1.action = () -> {
            if (mc.player != null) setCorner(1, mc.player.blockPosition());
            area.set(areaText());
        };
        WButton corner2 = areaButtons.add(theme.button("Set corner 2 here")).widget();
        corner2.tooltip = "Makes the block you are standing in corner 2 of the stash perimeter.";
        corner2.action = () -> {
            if (mc.player != null) setCorner(2, mc.player.blockPosition());
            area.set(areaText());
        };
        WButton clearArea = areaButtons.add(theme.button("Clear area")).widget();
        clearArea.tooltip = "Removes the stash perimeter, so containers are found around you again.";
        clearArea.action = () -> {
            clearArea();
            area.set(areaText());
        };

        if (ensureStash() != null) {
            for (Stash.Orphan orphan : stash.orphans) {
                list.add(theme.label(String.format("Orphaned shulker: %d stacks of %s at %s (since %s). Pick it up by hand.",
                    orphan.stacks, orphan.item, orphan.pos.toShortString(), orphan.when))).expandX();
            }
        }

        WHorizontalList buttons = list.add(theme.horizontalList()).widget();
        WButton rescan = buttons.add(theme.button("Forget contents")).widget();
        rescan.tooltip = "Forgets what's in every container, so the next run reads them all again, nearest first. Keeps the containers, signs, excluded and drop-off marks and kits.";
        rescan.action = () -> {
            if (ensureStash() == null) return;
            for (Box box : stash.boxes) box.contents = null;
            // The perimeter is explored again too, and containers that couldn't be reached get another try
            forgetExploration();
            stash.save();
            version++;
            idle = false;
            status.set(statusText());
        };

        WButton unstuck = buttons.add(theme.button("Unstuck")).widget();
        unstuck.tooltip = "Drops whatever the sorter is doing and starts over. A shulker step that was under way is not resumed: a shulker of ours still standing is left where it is and listed with the orphaned shulkers.";
        unstuck.action = () -> {
            unstuck();
            status.set(statusText());
        };

        WButton forget = buttons.add(theme.button("Forget stash")).widget();
        forget.tooltip = "Deletes the saved stash, including excluded and drop-off marks.";
        forget.action = () -> {
            Stash.delete(stashName.get());
            stash = null;
            triedKey = null;
            status.set(statusText());
        };

        list.add(theme.horizontalSeparator("Search"));
        WTextBox search = list.add(theme.textBox("")).expandX().widget();
        WTable results = list.add(theme.table()).expandX().widget();
        search.action = () -> fillResults(theme, results, search.get());

        list.add(theme.horizontalSeparator("Labels"));
        WVerticalList report = list.add(theme.verticalList()).expandX().widget();
        WButton refreshReport = list.add(theme.button("Labels report")).widget();
        refreshReport.tooltip = "What's homeless, what the sorter has labelled itself, and the chests it gave up on.";
        refreshReport.action = () -> fillReport(theme, report);
        fillReport(theme, report);

        list.add(theme.horizontalSeparator("Kits"));
        WHorizontalList saveRow = list.add(theme.horizontalList()).expandX().widget();
        WTextBox kitName = saveRow.add(theme.textBox("", "Kit name")).expandX().widget();
        WButton saveKit = saveRow.add(theme.button("Save kit")).widget();
        saveKit.tooltip = "Saves what's in your 27 main inventory slots (not the hotbar, offhand or armor) as a kit, with the same items in the same slots and the same counts.";
        WLabel kitStatus = list.add(theme.label("Put a kit in your main inventory, name it and save it.")).expandX().widget();
        WTable kits = list.add(theme.table()).expandX().widget();

        saveKit.action = () -> {
            String name = kitName.get().trim();
            String error = Kits.saveFromInventory(name);
            if (error != null) {
                kitStatus.set(error);
                return;
            }
            kitName.set("");
            kitStatus.set("Saved kit \"" + name + "\".");
            fillKits(theme, kits, kitStatus);
        };
        fillKits(theme, kits, kitStatus);

        return list;
    }

    private void fillReport(GuiTheme theme, WVerticalList report) {
        report.clear();
        for (String line : labeller.report()) report.add(theme.label(line)).expandX();
    }

    // The kit being asked to delete, until it's clicked again
    private Kit armedDelete;

    private void fillKits(GuiTheme theme, WTable table, WLabel status) {
        table.clear();
        armedDelete = null;
        if (mc.level == null || mc.player == null) {
            table.add(theme.label("Join a server to see its kits."));
            return;
        }

        List<Kit> saved = new ArrayList<>(Kits.all());
        if (saved.isEmpty()) {
            table.add(theme.label("No kits saved on this server."));
            return;
        }

        Stash current = ensureStash();
        for (Kit kit : saved) {
            ItemStack icon = ItemStack.EMPTY;
            for (int i = 0; i < Kit.SLOTS && icon.isEmpty(); i++) icon = kit.stackAt(i);
            table.add(theme.item(icon.copyWithCount(1)));
            table.add(theme.label(kit.name)).expandX();

            int possible = current == null ? 0 : kitBuilder.possible(kit);
            String more = possible < 0 ? String.format("no chest signed \"%s\"", kit.name) : String.format("%d more possible", possible);
            table.add(theme.label(String.format("%d slots, %d built, %s", kit.slotCount(), Kits.built(kit, current), more)));

            WButton view = table.add(theme.button("View")).widget();
            view.action = () -> KitPreviewScreen.open(kit);

            WMinus delete = table.add(theme.minus()).widget();
            delete.tooltip = "Click twice to delete this kit.";
            delete.action = () -> {
                if (armedDelete != kit) {
                    armedDelete = kit;
                    status.set("Click the minus again to delete \"" + kit.name + "\".");
                    return;
                }
                Kits.delete(kit);
                fillKits(theme, table, status);
                status.set("Deleted kit \"" + kit.name + "\".");
            };
            table.row();
        }
    }

    private String statusText() {
        if (mc.level == null) return "Join a server to see its stashes.";
        if (ensureStash() == null) return String.format("No stash called \"%s\" here yet. Turn the module on to scan one.", stashName.get());

        long excluded = stash.boxes.stream().filter(b -> b.excluded).count();
        long dropOffs = stash.boxes.stream().filter(b -> b.dropOff).count();
        return String.format("\"%s\": %d containers (%d read, %d excluded, %d drop-off), %,d items.%s",
            stash.name, stash.boxes.size(), stash.knownCount(), excluded, dropOffs, stash.itemCount(), sortItems.get() ? "" : " Scan only.");
    }

    private String groundStatus() {
        if (!sortItems.get()) return "off (scan only)";
        if (!collectGround.get() || !isActive()) return "off";
        if (!BARITONE) return "off, Baritone isn't installed";
        return ground.status();
    }

    private void fillResults(GuiTheme theme, WTable table, String query) {
        table.clear();
        if (ensureStash() == null) {
            table.add(theme.label("No stash loaded."));
            return;
        }

        List<Stash.Result> results = stash.search(query);
        if (results.isEmpty()) {
            if (!Categories.normalize(query).isEmpty()) table.add(theme.label("Nothing found."));
            return;
        }

        for (Stash.Result result : results.subList(0, Math.min(25, results.size()))) {
            Stash.Spot best = result.best();
            BlockPos pos = best.box().pos;
            int more = result.spots.size() - 1;

            table.add(theme.item(result.sample));
            table.add(theme.label(String.format("%s x%,d", StackInfo.label(result.sample), result.total))).expandX();
            table.add(theme.label(String.format("%d, %d, %d%s%s", pos.getX(), pos.getY(), pos.getZ(),
                best.inShulker() ? " (shulker)" : "", more > 0 ? String.format(" +%d more", more) : "")));

            WButton view = table.add(theme.button("View")).widget();
            view.action = () -> ItemPreviewScreen.open(result.sample, result.total, stash.name);
            table.row();
        }
    }

    // Helpers

    /**
     * Inventory slots holding stash items we're carrying: not the player's own gear, food, totems and pearls, unless the
     * sorter put them there itself.
     */
    private List<Integer> carriedSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot : carry) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty() || isOwn(slot, stack)) continue;
            slots.add(slot);
        }
        return slots;
    }

    /**
     * Carried stacks that something could take right now, not the ones already found to have nowhere to go.
     */
    private List<Integer> activeCarried() {
        List<Integer> slots = new ArrayList<>();
        Set<Integer> setAside = compactor.reserved();
        for (int slot : carriedSlots()) {
            // Signs kept for labelling, and what compacting is gathering and packing, are not put away
            if (labeller.reservedSlot(slot) || setAside.contains(slot)) continue;
            // A bundle with things in it is emptied, not put away
            if (fullBundle(mc.player.getInventory().getItem(slot)) || orderRunner.claims(mc.player.getInventory().getItem(slot))) continue;
            // The shulker an order is being built in
            if (orderKit() && kitBuilder.holds(slot)) continue;
            // What a craft gathered, and the boxes it made for an order
            if (crafter.holds(slot) || crafter.reserved(slot) || slot == anvilSlot) continue;
            // The chests gathered for the delivery storage, until they are put up
            if (chestHolding && plainChest(mc.player.getInventory().getItem(slot))) continue;
            if (!idOf(mc.player.getInventory().getItem(slot)).equals(stuckCarried.get(slot))) slots.add(slot);
        }
        return slots;
    }

    /**
     * The carry slots, less the ones holding the player's own gear.
     */
    private Set<Integer> ownedCarry() {
        Set<Integer> slots = new LinkedHashSet<>();
        for (int slot : carry) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty() || !isOwn(slot, stack)) slots.add(slot);
        }
        // Not sorting, only what an order itself took counts as carried: the rest of the slots are the player's
        if (!sorting()) slots.removeIf(slot -> !mc.player.getInventory().getItem(slot).isEmpty() && !orderHeld.containsKey(slot));
        return slots;
    }

    private boolean isOwn(int slot, ItemStack stack) {
        // Moved out of the way of a sign or a shulker
        if (labeller.displaced(slot) || (slot == swapBackFrom && swapBackFrom != -1)) return true;
        if (!ownKind(stack)) return false;
        ItemStack held = stashHeld.get(slot);
        return held == null || !ItemStack.isSameItemSameComponents(stack, held);
    }

    /**
     * Between jobs, with no screen open: a slot we filled that is now empty or holds something else is no longer ours.
     */
    private void pruneHeld() {
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()) return;
        stashHeld.entrySet().removeIf(entry -> {
            ItemStack stack = mc.player.getInventory().getItem(entry.getKey());
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, entry.getValue())) return true;
            // Keep the count as it is now, so a later start only takes back exactly what was left here
            entry.setValue(stack.copy());
            return false;
        });
    }

    /**
     * What the player keeps for themselves: tools, weapons, armor, food, totems and ender pearls.
     */
    private static boolean ownKind(ItemStack stack) {
        String id = idOf(stack);
        if (stack.has(DataComponents.FOOD) || id.equals("totem_of_undying") || id.equals("ender_pearl")) return true;
        for (String suffix : OWN_SUFFIXES) {
            if (id.endsWith(suffix)) return true;
        }
        return switch (id) {
            case "bow", "crossbow", "trident", "shield", "fishing_rod", "shears", "elytra", "mace", "flint_and_steel" -> true;
            default -> false;
        };
    }

    private static final List<String> OWN_SUFFIXES = List.of("_sword", "_axe", "_pickaxe", "_shovel", "_hoe", "_helmet", "_chestplate", "_leggings", "_boots");

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private int freeCarrySlot() {
        for (int slot : carry) if (mc.player.getInventory().getItem(slot).isEmpty()) return slot;
        return -1;
    }

    /**
     * Where inventory slot {@code index} sits in a container menu: the main inventory follows the container's slots,
     * then the hotbar.
     */
    private static int inventoryToMenu(int index, int containerSize) {
        return index < 9 ? containerSize + 27 + index : containerSize + index - 9;
    }

    private boolean inReach(Box box) {
        Vec3 eyes = mc.player.getEyePosition();
        double reachSq = reach.get() * reach.get();
        for (BlockPos part : box.parts) {
            if (eyes.distanceToSqr(Vec3.atCenterOf(part)) <= reachSq) return true;
        }
        return false;
    }

    private BlockPos nearestPart(Box box) {
        Vec3 eyes = mc.player.getEyePosition();
        return box.parts.stream().min(Comparator.comparingDouble(p -> eyes.distanceToSqr(Vec3.atCenterOf(p)))).orElse(box.pos);
    }

    private Box nearest(List<Box> list) {
        Vec3 eyes = mc.player.getEyePosition();
        return list.stream().min(Comparator.comparingDouble(b -> eyes.distanceToSqr(Vec3.atCenterOf(nearestPart(b))))).orElse(null);
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, StashSorter.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}

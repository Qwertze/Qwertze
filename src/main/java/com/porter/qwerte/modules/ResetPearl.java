package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.compat.Players;
import com.porter.qwerte.pearl.Chamber;
import com.porter.qwerte.pearl.ClimbDiagnosis;
import com.porter.qwerte.pearl.ElytraGuard;
import com.porter.qwerte.pearl.ElytraMode;
import com.porter.qwerte.pearl.ElytraSwap;
import com.porter.qwerte.pearl.DebugThrottle;
import com.porter.qwerte.pearl.EdgeApproach;
import com.porter.qwerte.pearl.OwnThrow;
import com.porter.qwerte.pearl.Hang;
import com.porter.qwerte.pearl.InvQueue;
import com.porter.qwerte.pearl.Pending;
import com.porter.qwerte.pearl.PearlRest;
import com.porter.qwerte.pearl.PhaseTimer;
import com.porter.qwerte.pearl.PullDetector;
import com.porter.qwerte.pearl.SlotWatch;
import com.porter.qwerte.pearl.SpeedMode;
import com.porter.qwerte.pearl.Sequence;
import com.porter.qwerte.pearl.ShaftScan;
import com.porter.qwerte.pearl.ShaftScan.Kind;
import com.porter.qwerte.pearl.TrapClicks;
import com.porter.qwerte.pearl.TrapRoles;
import com.porter.qwerte.pearl.ThrowGate;
import com.porter.qwerte.utils.BaritoneHelper;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
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
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Re-arms a stasis chamber after the player was pulled through it: climbs out (opening the trapdoors in the shaft),
 * hangs sneaking over the rim, throws a fresh pearl straight down into the water and closes the trapdoors it opened.
 *
 * Limit: the throw is checked against the pearl's random spread, so a fall of more than about 10 ticks (roughly 15
 * blocks) from the rim to the water is refused, as is a hang so far off-centre that the pearl would brush the wall.
 *
 * Movement is plain key input, a one or two block move doesn't need Baritone. Everything the sequence presses or
 * changes (keys, the selected slot, a pearl moved into the hotbar) is put back when it ends, however it ends.
 */
public class ResetPearl extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("detection-radius")
        .description("How close to a saved chamber a pearl teleport has to land to count as a pull through it.")
        .defaultValue(3)
        .range(1, 12)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Integer> ownThrowWindow = sgGeneral.add(new IntSetting.Builder()
        .name("own-throw-window")
        .description("Seconds after you throw a pearl yourself in which a teleport is yours and doesn't count as a pull.")
        .defaultValue(5)
        .range(1, 60)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Double> minTeleport = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-teleport-distance")
        .description("How far you have to be moved in one tick to count as a teleport. Smaller moves are lag-backs.")
        .defaultValue(8)
        .range(3, 64)
        .sliderRange(3, 32)
        .build()
    );

    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Seconds after a pull in which the next one is ignored.")
        .defaultValue(10)
        .range(1, 120)
        .sliderRange(1, 60)
        .build()
    );

    private final Setting<Boolean> cover = sgGeneral.add(new BoolSetting.Builder()
        .name("cover")
        .description("Closes the trapdoors that were opened to get out, once the pearl is below them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SpeedMode> speed = sgGeneral.add(new EnumSetting.Builder<SpeedMode>()
        .name("speed")
        .description("Safe keeps the waits of the older builds. Fast (default) swims up sprinting, opens a trapdoor every tick, throws the pearl from inside the shaft on the way up and closes the cover without waiting. Max does two trapdoor clicks a tick and the shortest pearl check.")
        .defaultValue(SpeedMode.Fast)
        .build()
    );

    private final Setting<Boolean> climbThrow = sgGeneral.add(new BoolSetting.Builder()
        .name("experimental-climb-throw")
        .description("EXPERIMENTAL, off by default: throw the pearl from inside the shaft on the way up instead of from the edge of the rim. Faster, but less precise; the pearl can land on a wall and teleport you.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> fastSwim = sgGeneral.add(new BoolSetting.Builder()
        .name("experimental-sprint-swim")
        .description("EXPERIMENTAL, off by default: look straight up and sprint-swim up the shaft, which is roughly 1.6 times faster than holding jump, but less precise.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> silentRotations = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-rotations")
        .description("Aims the pearl throw with rotations that are sent to the server only: your camera does not move. Some anti-cheats may flag silent rotations. Off turns the camera to the aim. Walking out of the chamber still turns you, as walking follows where you look.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ElytraMode> elytraHandling = sgGeneral.add(new EnumSetting.Builder<ElytraMode>()
        .name("elytra-handling")
        .description("An elytra in the chest slot makes 2b2t glide you in water instead of letting you swim. Auto takes it off for the climb and puts it back, and also blocks the start-gliding packet and the jump key in the air. Swap Off only takes it off. Guard Only only blocks. Off does nothing.")
        .defaultValue(ElytraMode.Auto)
        .build()
    );

    private final Setting<Integer> timeout = sgSafety.add(new IntSetting.Builder()
        .name("timeout")
        .description("Seconds after which the whole sequence is given up.")
        .defaultValue(10)
        .range(3, 60)
        .sliderRange(3, 30)
        .build()
    );

    private final Setting<Double> hostileDistance = sgSafety.add(new DoubleSetting.Builder()
        .name("hostile-distance")
        .description("Gives up if a hostile mob is this close, before and during the sequence. 0 turns it off.")
        .defaultValue(4)
        .range(0, 32)
        .sliderRange(0, 16)
        .build()
    );

    private final Setting<Double> playerDistance = sgSafety.add(new DoubleSetting.Builder()
        .name("player-distance")
        .description("Gives up if another player is this close, before and during the sequence. 0 turns it off.")
        .defaultValue(6)
        .range(0, 32)
        .sliderRange(0, 16)
        .build()
    );

    private final Setting<Integer> pearlWait = sgSafety.add(new IntSetting.Builder()
        .name("pearl-wait")
        .description("Seconds to wait for an ender pearl when you are pulled without one (someone throws or gives you one). 0 gives up at once.")
        .defaultValue(20)
        .range(0, 120)
        .sliderRange(0, 60)
        .build()
    );

    private final Setting<Boolean> abortOnDamage = sgSafety.add(new BoolSetting.Builder()
        .name("abort-on-damage")
        .description("Gives up if you take damage during the sequence (the pearl's own landing damage is ignored).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debug = sgSafety.add(new BoolSetting.Builder()
        .name("debug")
        .description("Logs every decision (detection, scan, each phase, aborts) to the client log and to chat. Per-tick reasons are limited to one a second.")
        .defaultValue(false)
        .build()
    );

    // The chambers are listed in the module's GUI, this only holds them for saving
    private final Setting<List<String>> chamberData = sgGeneral.add(new StringListSetting.Builder()
        .name("chambers")
        .description("The saved chambers, managed with the list below the settings.")
        .visible(() -> false)
        .build()
    );

    private static final boolean BARITONE = classExists("baritone.api.BaritoneAPI");
    /** Ticks after the start in which the landing damage of the pearl is let through. */
    private static final int DAMAGE_GRACE = 10;
    private static final int PEARL_PREP_TRIES = 6;

    private final PullDetector detector = new PullDetector(() -> new PullDetector.Config(radius.get(),
        ownThrowWindow.get() * 20, minTeleport.get(), cooldown.get() * 20));

    private volatile long tick;
    private Player lastPlayer;
    /** Debug lines for per-tick reasons: one per second per reason. */
    private final DebugThrottle throttle = new DebugThrottle(20);
    private static final double DEBUG_MIN_JUMP = 3;
    // Pearl entities this client has already seen, to spot a fresh throw of ours
    private final Set<Integer> seenPearls = new HashSet<>();
    private boolean pearlBaseline;
    private Pending.Block lastBlock;
    // Climb progress
    private double exitStartY;
    private boolean downColumn;
    // Climb diagnosis
    private boolean glidedInWater;
    private double climbBestY;
    private int climbProgressTick;
    private final DebugThrottle climbThrottle = new DebugThrottle(5);
    // The elytra taken off for the climb, and where it is until it goes back
    private ElytraSwap.Swapped swapped;
    private int elytraStage; // 0 not decided, 1 settling after the click, 3 done
    private int elytraSettle, elytraTries, restoreTries;
    private boolean restoreIssued, elytraWarned;
    private List<String> rawChambers = new ArrayList<>();
    private List<Chamber> parsedChambers = new ArrayList<>();

    // The sequence that is running
    private boolean running;
    private Chamber chamber;
    private ShaftScan.Shaft shaft;
    private ShaftScan.Rim rim;
    private Sequence seq;
    private int seqTicks;
    private int stillTicks;
    private Vec3 lastPos;
    private int actionCooldown;
    private float healthAtGrace = -1;
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    // Which trapdoor is which, from the scan: the stasis one at the water stays open, only covers are ever closed
    private TrapRoles roles;
    // The last trapdoor click, looked at again two ticks later
    private BlockPos clickedPos;
    private boolean clickedWant;
    private long clickedTick;
    // The check that the new pearl stays in stasis after the cover
    private boolean verifying;
    private int verifyTicks;
    private Vec3 verifyPos;
    /** Every inventory click goes through one queue: one in flight, the next only after its result shows. */
    private enum InvKind { ELYTRA_OFF, PEARL_TO_HAND, PEARL_BACK, ELYTRA_BACK }
    private final InvQueue<InvKind> invQueue = new InvQueue<>(3);
    private int invStateId;

    // Why a pearl did not stay in the hand: what we set, and who changed it
    private int pearlExpectSelected = -1, pearlDiagTicks, pearlDiagLines, pearlWatchState;
    private long pearlAttemptTick = -100;
    private boolean pearlDiagSaidMod;
    private String pearlHijack = "";

    // Trapdoor clicks that wait for the server, and the block updates seen at the shaft (written by the network thread)
    private final TrapClicks trapClicks = new TrapClicks();
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> blockUpdates = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_TRAP_FAILURES = 3;

    private SpeedMode.Params sp() {
        return speed.get().params();
    }

    // Timing of the run, and what the speed modes changed in the way the player is driven
    private PhaseTimer timer;
    private boolean timerPrinted, elytraMarked;
    private boolean sprintOurs, pitchTouched;
    private float origPitch;
    private int coverShiftWaits;
    private final TreeSet<BlockPos> opened = new TreeSet<>(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
    private int coverWait;
    private final PearlRest pearlRest = new PearlRest();
    /** The covers left open on purpose were already reported this run. */
    private boolean leftOpenReported;
    private int throwUnfit;
    private long throwAt;
    private int rethrows, waitTicks;
    private static final int RETHROW_AFTER = 12, MAX_RETHROWS = 2;
    private Item displacedKind;

    // A pull that is waiting for the chunks to load, or for a screen or mob to go away
    private Pending pending;
    private Chamber pendingChamber;
    // Where the pull landed: the column to scan, and how far the player may drift before the start is dropped
    private BlockPos pendingFeet;
    private Vec3 pendingPos;
    private static final double PENDING_DRIFT = 1.5;
    private boolean waitingForPearl;
    private String pendingDimension = "";
    private int pendingAge;
    private float pendingHealth = -1;
    /** How far below the player the water may be when saving a chamber. */
    private static final int SAVE_DEPTH = 24;

    // Keys we drive, and what they were before
    private static final class Held {
        boolean ours;
        boolean orig;
    }
    private final Held forwardKey = new Held(), jumpKey = new Held(), sneakKey = new Held();

    // The pearl in hand, and what to put back
    private InteractionHand hand = InteractionHand.MAIN_HAND;
    private boolean pearlReady;
    private int prepTries;
    private int originalSlot = -1;
    private int swappedFrom = -1;
    private int swappedTo = -1;
    private boolean displacedItem;
    private boolean pendingRestore;
    private final ThrowGate throwGate = new ThrowGate();
    private boolean thrown;

    public ResetPearl() {
        super(QwerteAddon.CATEGORY, "reset-pearl", "After you are pulled through a saved stasis chamber, climbs out, throws a fresh pearl into its water and closes the cover again.");
    }

    // The chamber list

    private List<Chamber> chambers() {
        List<String> strings = chamberData.get();
        if (!strings.equals(rawChambers)) {
            rawChambers = new ArrayList<>(strings);
            parsedChambers = new ArrayList<>();
            for (String s : strings) {
                Chamber c = Chamber.decode(s);
                if (c != null) parsedChambers.add(c);
            }
        }
        return parsedChambers;
    }

    private void storeChambers(List<Chamber> list) {
        List<String> strings = new ArrayList<>();
        for (Chamber c : list) strings.add(c.encode());
        chamberData.set(strings);
    }

    private String serverKey() {
        return Chamber.normalizeServer(mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer");
    }

    private String dimensionKey() {
        return mc.level.dimension().toString();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        list.add(theme.label("Stand on the cover trapdoor above the shaft (or on the rim beside it, or in the water) to save it.")).expandX();

        WHorizontalList add = list.add(theme.horizontalList()).expandX().widget();
        WTextBox name = add.add(theme.textBox("", "Chamber name")).expandX().widget();
        WButton set = add.add(theme.button("Set location here")).widget();
        set.tooltip = "Saves the block you are standing in as a chamber on this server and dimension.";
        WLabel status = list.add(theme.label("")).expandX().widget();
        WTable table = list.add(theme.table()).expandX().widget();

        set.action = () -> {
            if (mc.player == null || mc.level == null) {
                status.set("Join a world first.");
                return;
            }
            List<Chamber> all = new ArrayList<>(chambers());
            BlockPos pos = mc.player.blockPosition();
            ShaftScan.SaveResult saved = ShaftScan.saveScan(this::kindAt, pos.getX(), pos.getY(), pos.getZ(), SAVE_DEPTH);
            dbg("save scan from %s (feet block): %s", pos, saved.log());
            if (saved.found() == null) {
                status.set("Not saved: " + saved.error() + ".");
                dbg("save refused: %s", saved.error());
                return;
            }
            ShaftScan.Found shaftFound = saved.found();
            TrapRoles savedRoles = TrapRoles.classify(shaftFound.waterTop(), shaftFound.trapYs());
            if (savedRoles.error() != null) {
                status.set("Not saved: " + savedRoles.error() + ".");
                dbg("save refused: %s", savedRoles.error());
                return;
            }
            // The rim: the block the player stands on if beside the shaft, else the first one the scan finds
            int rdx = Chamber.UNKNOWN, rdz = Chamber.UNKNOWN, rfy = Chamber.UNKNOWN;
            if (shaftFound.x() != pos.getX() || shaftFound.z() != pos.getZ()) {
                int ddx = pos.getX() - shaftFound.x(), ddz = pos.getZ() - shaftFound.z();
                if (Math.abs(ddx) + Math.abs(ddz) == 1) {
                    rdx = ddx;
                    rdz = ddz;
                    rfy = pos.getY();
                }
            }
            else {
                ShaftScan.Result rimScan = ShaftScan.scan(this::kindAt, shaftFound.x(), shaftFound.waterTop(), shaftFound.z(), 12, 6);
                if (rimScan.error() == null && !rimScan.shaft().rims().isEmpty()) {
                    ShaftScan.Rim first = rimScan.shaft().rims().get(0);
                    rdx = first.dx();
                    rdz = first.dz();
                    rfy = first.floorY();
                }
            }
            dbg("save: rim offset %d,%d, floor y %d", rdx, rdz, rfy);
            Chamber made = new Chamber(serverKey(), dimensionKey(), shaftFound.x(), shaftFound.waterTop(), shaftFound.z(),
                nameFor(name.get(), all), shaftFound.waterTop(), shaftFound.trapYs(), rdx, rdz, rfy);
            for (Chamber c : all) {
                if (c.samePlace(made)) {
                    status.set("Already saved as " + c.name() + ".");
                    return;
                }
            }
            all.add(made);
            storeChambers(all);
            name.set("");
            status.set("Saved " + made.name() + ": shaft found, water at depth " + shaftFound.depth() + ", " + shaftFound.trapYs().size()
                + " trapdoor(s).");
            dbg("saved %s: column %d,%d, water y %d, trapdoors %s", made.name(), made.x(), made.z(), made.waterTop(), made.trapYs());
            fill(theme, table);
        };
        fill(theme, table);
        return list;
    }

    private static String nameFor(String typed, List<Chamber> all) {
        String name = typed == null ? "" : typed.trim().replace('|', '/');
        if (!name.isEmpty()) return name;
        for (int n = all.size() + 1; ; n++) {
            String candidate = "Chamber " + n;
            boolean taken = false;
            for (Chamber c : all) taken |= c.name().equals(candidate);
            if (!taken) return candidate;
        }
    }

    private void fill(GuiTheme theme, WTable table) {
        table.clear();
        List<Chamber> all = chambers();
        if (all.isEmpty()) {
            table.add(theme.label("No chambers saved.")).expandX();
            return;
        }
        for (Chamber c : new ArrayList<>(all)) {
            table.add(theme.label(c.name())).expandX();
            table.add(theme.label(c.shortDimension() + " " + c.x() + ", " + c.y() + ", " + c.z()));
            WMinus minus = table.add(theme.minus()).widget();
            minus.tooltip = "Remove this chamber.";
            minus.action = () -> {
                List<Chamber> rest = new ArrayList<>(chambers());
                rest.removeIf(other -> other.samePlace(c));
                storeChambers(rest);
                fill(theme, table);
            };
            table.row();
        }
    }

    // Lifecycle

    @Override
    public void onActivate() {
        detector.reset();
        lastPlayer = null;
        pending = null;
        seenPearls.clear();
        pearlBaseline = false;
        invQueue.clear();
        throttle.clear();
        dbg("module activated; %d chamber(s) saved, server key '%s'", chambers().size(), mc.level != null ? serverKey() : "(no world)");
        // Whatever an earlier run left undone is not ours to undo now: the inventory has moved on
        swappedFrom = -1;
        pendingRestore = false;
        originalSlot = -1;
    }

    @Override
    public void onDeactivate() {
        if (pending != null) warn("Not re-arming %s: the module was turned off.", pendingChamber.name());
        pending = null;
        if (running) abort("the module was turned off");
        flushInventory();
        if (swapped != null && !restoreIssued) warn("Your elytra is in %s.", slotName(swapped.slot()));
        if (swappedFrom >= 0) {
            warn("A pearl moved into your hotbar could not be put back, check your hotbar.");
            swappedFrom = -1;
            pendingRestore = false;
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (running) abort(null);
        if (pending != null) QwerteAddon.LOG.info("reset-pearl: dropped the pending start of '{}': left the world", pendingChamber.name());
        pending = null;
        pendingRestore = false;
        swappedFrom = -1;
        detector.reset();
        lastPlayer = null;
        seenPearls.clear();
        pearlBaseline = false;
    }

    // Detection

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        // The start-gliding request is what the server answers with a glide in the water
        if (running && elytraHandling.get().guards() && event.packet instanceof ServerboundPlayerCommandPacket cmd
            && cmd.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING) {
            event.cancel();
            dbg("cancelled a START_FALL_FLYING packet (in water %b, on ground %b, y %.2f)", mc.player.isInWater(), mc.player.onGround(), mc.player.getY());
            return;
        }
        // Any pearl the player throws, from any source: right click, other modules
        if (running || mc.player == null) return;
        if (event.packet instanceof ServerboundUseItemPacket use && mc.player.getItemInHand(use.getHand()).is(Items.ENDER_PEARL)) {
            detector.ownThrow(tick);
            dbg("own throw: pearl use packet at tick %d (a teleport in the next %d s is treated as yours)", tick, ownThrowWindow.get());
            // The player is throwing a pearl by hand: this teleport was theirs, or they are busy with it
            if (pending != null) {
                String name = pendingChamber.name();
                pending = null;
                warn("Not re-arming %s: you threw a pearl yourself.", name);
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        tickBody();
        // After everything above had its say, so an operation asked for this tick goes out this tick
        invTick();
    }

    private void tickBody() {
        if (mc.player == null || mc.level == null) {
            if (running) abort(null);
            if (pending != null) QwerteAddon.LOG.info("reset-pearl: dropped the pending start of '{}': no player or world", pendingChamber.name());
            pending = null;
            return;
        }
        if (mc.player != lastPlayer) {
            // A new player entity (respawn, new world): nothing of the old one carries over
            if (running) abort(null);
            if (pending != null) warn("Not re-arming %s: the world or dimension changed.", pendingChamber.name());
            pending = null;
            pendingRestore = false;
            swappedFrom = -1;
            lastPlayer = mc.player;
            detector.reset();
            seenPearls.clear();
            pearlBaseline = false;
            invQueue.clear();
            dbg("new player entity: detection baseline reset");
        }
        tick++;

        Vec3 at = mc.player.position();
        // A pearl of ours in the air (a silent swap throw, a long flight) makes a teleport ours too
        if (!running) watchOwnPearls();
        PullDetector.Result seen = detector.inspect(tick, at.x, at.y, at.z, serverKey(), dimensionKey(), chambers());
        Chamber hit = seen.pull() && !running ? seen.chamber() : null;
        if (seen.pull() && running) dbg("pull into %s detected while a sequence is running: ignored", seen.chamber().name());
        else reportDetection(seen);

        if (running) {
            try {
                runTick();
            }
            catch (RuntimeException e) {
                QwerteAddon.LOG.error("reset-pearl: the sequence failed", e);
                failSafe("an internal error stopped it");
            }
            return;
        }
        if (pendingRestore) tryRestoreHand();
        if (swapped != null && pending == null) tryRestoreElytra();

        if (hit != null && pending != null) {
            dbg("pull into %s detected while another start is pending: ignored", hit.name());
        }
        if (pending == null && hit != null) {
            pending = new Pending();
            pendingChamber = hit;
            pendingFeet = mc.player.blockPosition();
            pendingPos = mc.player.position();
            pendingDimension = dimensionKey();
            lastBlock = null;
            waitingForPearl = false;
            pendingAge = 0;
            pendingHealth = -1;
            // Once per pull, when it is accepted: the same pull can't come again while it waits or runs
            detector.markTriggered(tick);
            timer = new PhaseTimer(tick, System.nanoTime());
            timerPrinted = false;
            info("Pulled into %s: re-arming...", hit.name());
            QwerteAddon.LOG.info("reset-pearl: pull detected into '{}' ({}, {}, {}), landed at {}", hit.name(), hit.x(), hit.y(), hit.z(), pendingFeet);
        }
        if (pending != null) {
            try {
                tickPending();
            }
            catch (RuntimeException e) {
                QwerteAddon.LOG.error("reset-pearl: starting failed", e);
                pending = null;
                failSafe("an internal error stopped it");
            }
        }
    }

    /** The sequence threw: let go of everything, even if the normal way out fails too. */
    private void failSafe(String why) {
        try {
            abort(why);
        }
        catch (RuntimeException e) {
            QwerteAddon.LOG.error("reset-pearl: cleaning up failed", e);
        }
        // Whatever the flag says, and whatever failed above: each of these on its own
        running = false;
        try {
            releaseKeys();
        }
        catch (RuntimeException e) {
            QwerteAddon.LOG.error("reset-pearl: releasing the keys failed", e);
        }
        try {
            if (mc.player != null) restoreHand();
        }
        catch (RuntimeException e) {
            QwerteAddon.LOG.error("reset-pearl: restoring the hand failed", e);
        }
        opened.clear();
        attempts.clear();
        throwGate.cancel();
    }

    /**
     * Spots a pearl of ours that has just left us: first seen by this client, young, fast and next to the eyes. A
     * stasis pearl that falls or is loaded in later is not one (see {@link OwnThrow}).
     */
    private void watchOwnPearls() {
        Set<Integer> now = new HashSet<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Projectile p) || p.getType() != EntityType.ENDER_PEARL) continue;
            now.add(p.getId());
            boolean first = pearlBaseline && !seenPearls.contains(p.getId());
            if (first && OwnThrow.isFreshThrow(p.getOwner() == mc.player, true, p.position().distanceTo(mc.player.getEyePosition()),
                p.getDeltaMovement().lengthSqr(), p.tickCount)) {
                detector.ownThrow(tick);
                dbg("own throw: pearl entity %d first seen %.1f blocks from your eyes, speed^2 %.2f, age %d", p.getId(),
                    p.position().distanceTo(mc.player.getEyePosition()), p.getDeltaMovement().lengthSqr(), p.tickCount);
            }
        }
        // Everything there on the first look (or after a world change) was there before us
        seenPearls.clear();
        seenPearls.addAll(now);
        pearlBaseline = true;
    }

    private void reportDetection(PullDetector.Result r) {
        PullDetector.Reason why = r.reason();
        if (why == PullDetector.Reason.NO_BASELINE || why == PullDetector.Reason.SMALL_MOVE) return;
        String near = r.nearest() == null ? "no chamber saved" : String.format("nearest '%s' at %d,%d,%d: %.1f sideways, %.1f vertical, server %s, dimension %s",
            r.nearest().name(), r.nearest().x(), r.nearest().y(), r.nearest().z(), r.nearestHorizontal(), r.nearestVertical(),
            r.nearestServerOk() ? "ok" : "MISMATCH", r.nearestDimensionOk() ? "ok" : "MISMATCH");
        String ago = r.ownThrowAgo() == Long.MAX_VALUE ? "never" : (r.ownThrowAgo() / 20.0) + " s ago";
        String facts = String.format("jump %.1f blocks to %.1f, %.1f, %.1f; server '%s', dimension '%s'; %s; last own throw %s; cooldown left %.1f s",
            r.jump(), mc.player.getX(), mc.player.getY(), mc.player.getZ(), serverKey(), dimensionKey(), near, ago, r.cooldownLeft() / 20.0);
        switch (why) {
            case PULL -> dbg("pull accepted: %s", facts);
            case COOLDOWN -> {
                if (throttle.allow(tick, "cooldown")) {
                    warn("Pull into %s ignored: %.0f s of cooldown left.", r.chamber().name(), r.cooldownLeft() / 20.0);
                    QwerteAddon.LOG.info("reset-pearl: cooldown ignored a pull: {}", facts);
                }
            }
            case OWN_THROW -> {
                if (throttle.allow(tick, "own")) {
                    warn("Pull into %s ignored: you threw a pearl %.1f s ago.", r.chamber().name(), r.ownThrowAgo() / 20.0);
                    QwerteAddon.LOG.info("reset-pearl: own throw ignored a pull: {}", facts);
                }
            }
            case NO_CHAMBER -> {
                if (r.jump() > DEBUG_MIN_JUMP) dbgThrottled("nochamber", "teleport not into a saved chamber: %s", facts);
            }
            default -> {}
        }
    }

    // Start

    private boolean hasPearl() {
        return mc.player.getMainHandItem().is(Items.ENDER_PEARL) || mc.player.getOffhandItem().is(Items.ENDER_PEARL)
            || InvUtils.find(st -> st.is(Items.ENDER_PEARL), 0, 35).found();
    }

    /** Where the pearl in the inventory is, for the debug log. */
    private String describePearl() {
        if (mc.player.getMainHandItem().is(Items.ENDER_PEARL)) return "main hand, " + mc.player.getMainHandItem().getCount();
        if (mc.player.getOffhandItem().is(Items.ENDER_PEARL)) return "offhand, " + mc.player.getOffhandItem().getCount();
        FindItemResult found = InvUtils.find(st -> st.is(Items.ENDER_PEARL), 0, 35);
        if (!found.found()) return "none";
        return (found.isHotbar() ? "hotbar" : "main inventory") + " slot " + found.slot() + ", " + found.count();
    }

    /**
     * While waiting, once a second: how long, and any pearl lying near. The player does not walk to it: they sit at the
     * bottom of the shaft, and vanilla pickup reaches items thrown onto or next to them.
     */
    private void logPearlItems() {
        if (!debug.get() || !throttle.allow(tick, "pwait")) return;
        dbg("still waiting for a pearl: %d of %d s", pending.pearlTicks() / 20, pearlWait.get());
        AABB pickup = mc.player.getBoundingBox().inflate(1.0, 0.5, 1.0);
        for (Entity e : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(8))) {
            if (e instanceof ItemEntity item && item.getItem().is(Items.ENDER_PEARL) && !pickup.intersects(item.getBoundingBox())) {
                dbg("pearl item at %.1f blocks, not walking to it", item.distanceTo(mc.player));
            }
        }
    }

    /** Whether the chunks around the column are there, so the scan can be believed. */
    private boolean columnLoaded(BlockPos feet) {
        for (int dx = -2; dx <= 2; dx += 4) {
            for (int dz = -2; dz <= 2; dz += 4) {
                if (!mc.level.getChunkSource().hasChunk((feet.getX() + dx) >> 4, (feet.getZ() + dz) >> 4)) return false;
            }
        }
        return true;
    }

    /** Tries to begin the re-arm of the pull that is waiting. */
    private void tickPending() {
        if (!mc.player.isAlive()) {
            String name = pendingChamber.name();
            pending = null;
            warn("Not re-arming %s: you died.", name);
            return;
        }
        // While waiting for a pearl a bubble column may lift the player: only sideways drift counts then
        Vec3 now = mc.player.position();
        double drift = waitingForPearl ? Math.hypot(now.x - pendingPos.x, now.z - pendingPos.z) : now.distanceTo(pendingPos);
        if (drift > PENDING_DRIFT) {
            String name = pendingChamber.name();
            pending = null;
            warn("Not re-arming %s: you moved away from where you landed.", name);
            return;
        }
        pendingAge++;
        if (pendingAge == DAMAGE_GRACE) pendingHealth = mc.player.getHealth();
        boolean pearl = hasPearl();
        int waitTicks = pearlWait.get() * 20;
        String stop = null;
        if (!pearl && waitTicks > 0) {
            // Waiting for a pearl: the keys are not touched, and these end it
            if (mc.screen != null) stop = "a screen opened";
            else if ((stop = danger()) != null) { /* the reason */ }
            else if (abortOnDamage.get() && pendingHealth >= 0 && mc.player.getHealth() < pendingHealth - 0.01f) stop = "you took damage";
            else if (!dimensionKey().equals(pendingDimension)) stop = "the dimension changed";
        }
        if (stop != null) {
            String name = pendingChamber.name();
            pending = null;
            warn("Not re-arming %s: %s while waiting for a pearl.", name, stop);
            return;
        }
        if (pearl && waitingForPearl) {
            waitingForPearl = false;
            info("Pearl received, re-arming...");
            dbg("pearl arrived after %d ticks of waiting: %s", pending.pearlTicks(), describePearl());
        }
        if (!pearl && waitTicks > 0 && !waitingForPearl) {
            waitingForPearl = true;
            info("Waiting for a pearl (up to %ds)...", pearlWait.get());
            dbg("waiting for a pearl: none in slots 0-35 or the offhand; limit %d s; keys untouched, health %.1f", pearlWait.get(), mc.player.getHealth());
        }
        if (!pearl && waitTicks > 0) logPearlItems();
        Pending.Block block;
        String detail = null;
        ShaftScan.Result scanned = null;
        if (!pearl) block = Pending.Block.NO_PEARL;
        else if (mc.screen != null) block = Pending.Block.SCREEN;
        else if ((detail = danger()) != null) block = Pending.Block.DANGER;
        else if (!columnLoaded(pendingFeet)) block = Pending.Block.UNLOADED;
        else {
            // The column the player landed in, not wherever they have been pushed to since
            scanned = ShaftScan.scan(this::kindAt, pendingFeet.getX(), pendingFeet.getY(), pendingFeet.getZ(), 12, 6);
            if (scanned.error() != null && pendingChamber.hasShaft()
                && pendingChamber.horizontalTo(pendingPos.x, pendingPos.z) <= radius.get()) {
                // The landing column has no water: try the shaft that was saved with the chamber
                ShaftScan.Result stored = ShaftScan.scan(this::kindAt, pendingChamber.x(), pendingChamber.waterTop(), pendingChamber.z(), 12, 6);
                dbg("landing column scan failed (%s); stored shaft column %d,%d: %s", scanned.error(), pendingChamber.x(), pendingChamber.z(),
                    stored.error() == null ? "ok" : stored.error());
                if (stored.error() == null) scanned = stored;
            }
            if (scanned.error() == null) {
                TrapRoles scannedRoles = TrapRoles.classify(scanned.shaft().waterTop(), scanned.shaft().trapYs());
                if (scannedRoles.error() != null) scanned = new ShaftScan.Result(null, scannedRoles.error());
            }
            if (scanned.error() == null) dbg("scan ok: %s", describe(scanned.shaft()));
            if (scanned.error() != null) {
                block = Pending.Block.BAD_SHAFT;
                detail = scanned.error();
            }
            else block = Pending.Block.NONE;
        }

        Chamber found = pendingChamber;
        if (block != lastBlock) {
            lastBlock = block;
            dbg("start check (tick %d of pending): %s%s", pending.ticks() + 1, block, detail != null ? " (" + detail + ")" : "");
        }
        switch (pending.step(block, detail, waitTicks)) {
            case WAIT -> {}
            case GO -> {
                pending = null;
                begin(found, scanned.shaft());
            }
            case GIVE_UP -> {
                String why = pending.reason();
                boolean pearlTimeout = pending.pearlTimedOut();
                pending = null;
                if (pearlTimeout) warn("No pearl received in %ds, not re-arming %s.", pearlWait.get(), found.name());
                else warn("Not re-arming %s: %s.", found.name(), why);
                QwerteAddon.LOG.info("reset-pearl: gave up starting: {} ({})", why, block);
            }
        }
    }

    private void begin(Chamber found, ShaftScan.Shaft scanned) {
        String name = found.name();
        // Baritone would fight the keys, whoever started it
        if (BARITONE && BaritoneHelper.isActive()) BaritoneHelper.stop();

        chamber = found;
        shaft = scanned;
        roles = TrapRoles.classify(scanned.waterTop(), scanned.trapYs());
        verifying = false;
        clickedPos = null;
        rim = null;
        seq = new Sequence(sp().minHang());
        seqTicks = 0;
        stillTicks = 0;
        lastPos = null;
        actionCooldown = 0;
        healthAtGrace = -1;
        attempts.clear();
        opened.clear();
        coverWait = 0;
        pearlRest.reset();
        leftOpenReported = false;
        throwUnfit = 0;
        rethrows = 0;
        waitTicks = 0;
        displacedItem = false;
        displacedKind = null;
        pearlReady = false;
        prepTries = 0;
        throwGate.cancel();
        thrown = false;
        swappedFrom = -1;
        pendingRestore = false;
        originalSlot = Players.selectedSlot();
        hand = InteractionHand.MAIN_HAND;
        running = true;
        pearlExpectSelected = -1;
        pearlDiagTicks = 0;
        pearlDiagLines = 0;
        pearlAttemptTick = -100;
        pearlDiagSaidMod = false;
        pearlHijack = "";
        trapClicks.clear();
        blockUpdates.clear();
        mark("pending (chunks, pearl, scan)");
        elytraMarked = false;
        pitchTouched = false;
        origPitch = mc.player.getXRot();
        coverShiftWaits = 0;
        exitStartY = mc.player.getY();
        downColumn = false;
        glidedInWater = false;
        climbBestY = exitStartY;
        climbProgressTick = 0;
        elytraStage = 0;
        elytraSettle = 0;
        elytraTries = 0;
        throttle.clear();
        dbg("sequence begins for %s at %s; scan: %s", name, mc.player.position(), describe(scanned));
        for (int ty : scanned.trapYs()) {
            dbg("trapdoor y %d (%+d to the water top %d): %s, %s", ty, ty - scanned.waterTop(), scanned.waterTop(),
                kindAt(scanned.x(), ty, scanned.z()) == Kind.TRAPDOOR_OPEN ? "open" : "closed", roles.roleOf(ty));
        }
        dbg("player: sneak-toggle %s, selected slot %d, baritone %s", mc.options.toggleCrouch().get(), originalSlot,
            BARITONE ? "present" : "absent");
    }

    // The sequence

    private double cx() {
        return shaft.x() + 0.5;
    }

    private double cz() {
        return shaft.z() + 0.5;
    }

    private void runTick() {
        if (throwGate.expire(tick)) dbg("throw: the rotation callback did not run within %d ticks, request dropped", ThrowGate.EXPIRY_TICKS);
        checkClick();
        if (verifying) {
            if (sp().parallelRestore()) tryRestoreElytra();
            verifyTick();
            return;
        }
        String reason = danger();
        if (reason == null && mc.screen != null) reason = "a screen opened (the pause menu opens when the game loses focus)";
        if (reason == null && !mc.player.isAlive()) reason = "you died";
        if (reason == null && abortOnDamage.get()) {
            if (seqTicks == DAMAGE_GRACE) healthAtGrace = mc.player.getHealth();
            else if (healthAtGrace >= 0 && mc.player.getHealth() < healthAtGrace - 0.01f) reason = "you took damage";
        }
        if (reason == null && ++seqTicks > timeout.get() * 20) {
            reason = seq.phase() == Sequence.Phase.EXIT ? "timed out climbing out of the chamber: " + climbReason() : "timed out";
        }
        if (reason != null) {
            abort(reason);
            return;
        }
        if (BARITONE && seqTicks % 10 == 0 && BaritoneHelper.isActive()) {
            dbg("Baritone was active during the sequence: stopped it");
            BaritoneHelper.stop();
        }

        Vec3 pos = mc.player.position();
        if (thrown && lastPos != null && pos.distanceToSqr(lastPos) > 9) {
            abort("the thrown pearl landed and teleported you (it hit a block instead of staying in the water)");
            return;
        }
        if (lastPos != null && pos.distanceToSqr(lastPos) < sp().stillEps() * sp().stillEps()) stillTicks++;
        else stillTicks = 0;
        lastPos = pos;
        if (actionCooldown > 0) actionCooldown--;

        // An elytra on the chest is taken off first (one click a tick, then a check)
        if (seq.phase() == Sequence.Phase.EXIT) {
            boolean ready = elytraReady();
            if (elytraStage == 3 && !elytraMarked) {
                elytraMarked = true;
                mark("elytra handled");
            }
            // The faster modes climb on while the swap settles; the guard keeps the jump key off the air meanwhile
            if (!ready && sp().elytraBlocksClimb()) return;
        }

        Sequence.Phase before = seq.phase();
        switch (before) {
            case EXIT -> exitTick(pos);
            case HANG -> hangTick();
            case THROW -> throwTick();
            case WAIT_PEARL -> waitTick();
            case COVER -> {}
            default -> {}
        }
        if (!running) return;

        boolean onRim = rim != null && mc.player.onGround() && Math.abs(pos.y - rim.floorY()) < 0.25
            && Hang.offShaft(pos.x, pos.z, cx(), cz(), rim.dx(), rim.dz());
        boolean stationary = stillTicks >= sp().stillTicks() && pearlReady;
        // The line is worked out from where the player really stands, and only matters once they stand still
        boolean fits = before == Sequence.Phase.HANG && stationary && aimNow() != null;
        boolean settled = before == Sequence.Phase.WAIT_PEARL && pearlSettled();
        boolean coverNeeded = cover.get() && !opened.isEmpty();
        boolean coverDone = before == Sequence.Phase.COVER && coverTick();
        if (!running) return;

        Sequence.Phase after = seq.advance(new Sequence.Obs(onRim, stationary, fits, thrown, settled, coverNeeded, coverDone));
        if (after != before) {
            dbg("phase %s -> %s after %d total ticks at %.2f, %.2f, %.2f (onRim %b, still %d ticks, fits %b, thrown %b)", before, after, seqTicks,
                pos.x, pos.y, pos.z, onRim, stillTicks, fits, thrown);
            mark(before + " done");
            onEnter(after);
        }

        if (after == Sequence.Phase.DONE) beginVerify();
        else if (after == Sequence.Phase.FAILED) {
            if (before == Sequence.Phase.COVER) partialFinish(seq.failure());
            else if (before == Sequence.Phase.EXIT) abort(seq.failure() + ": " + climbReason());
            else abort(seq.failure());
        }
    }

    private void onEnter(Sequence.Phase phase) {
        // The sprint-swim is for the way up only
        if (phase != Sequence.Phase.EXIT) setSprint(false);
        switch (phase) {
            case THROW -> {
                // Standing still on the edge, nothing pushes the pearl sideways
                setForward(false);
                setJump(false);
            }
            case WAIT_PEARL -> {
                // Nothing pushes the player on after a throw made on the way out (the keys of the climb are let go)
                setForward(false);
                setJump(false);
            }
            case COVER -> {
                // A sneaking player's click on a block is skipped while they hold an item, so stand up first
                setSneak(false);
                setForward(false);
                setJump(false);
                coverWait = 0;
            }
            default -> {}
        }
    }

    private void exitTick(Vec3 pos) {
        setSneak(false);
        // Climb progress, and a column that drags down (magma) that swimming cannot beat
        if (inDownColumn() && !downColumn) {
            downColumn = true;
            dbg("standing in a downward bubble column (magma): trying to swim out anyway");
        }
        if (pos.y > climbBestY + 0.05) {
            climbBestY = pos.y;
            climbProgressTick = seqTicks;
        }
        if (mc.player.isFallFlying() && mc.player.isInWater() && !glidedInWater) {
            glidedInWater = true;
            dbg("the player is gliding (fall flying) in the water: the server's elytra quirk");
        }
        if (debug.get() && climbThrottle.allow(tick, "climb")) {
            Vec3 v = mc.player.getDeltaMovement();
            dbg("climb: pose %s, fallFlying %b, inWater %b, swimming %b, onGround %b, y %.2f (best %.2f), vy %.3f, jump key %b, downColumn %b",
                mc.player.getPose(), mc.player.isFallFlying(), mc.player.isInWater(), mc.player.isSwimming(), mc.player.onGround(),
                pos.y, climbBestY, v.y, mc.options.keyJump.isDown(), downColumn);
        }
        if (glidedInWater && seqTicks - climbProgressTick >= ClimbDiagnosis.STUCK_TICKS) {
            abort("could not climb out of the chamber: " + climbReason());
            return;
        }
        if (downColumn && seq.phaseTicks() >= 40 && pos.y < exitStartY + 0.5) {
            abort("a downward bubble column (magma) holds you in the chamber, swimming up does not beat it");
            return;
        }
        SpeedMode.Params sp = sp();
        // The pearl in hand early, for the throw on the way up
        if (!pearlReady && elytraStage != 0) {
            preparePearl();
            if (!running) return;
        }
        ShaftScan.Rim target = rim != null ? rim : shaft.rims().get(0);
        // Swimming up the shaft: look straight up and sprint-swim, which is faster than holding jump alone
        boolean swim = sp.sprintSwim() || fastSwim.get();
        boolean low = swim && mc.player.isInWater() && pos.y < target.floorY() - 0.9;

        if (sp.awaitServer() && judgeTrapClicks()) {
            abort("a trapdoor will not open");
            return;
        }
        BlockPos lowest = lowestClosedTrapAbove(sp.awaitServer());
        if (lowest == null && sp.awaitServer() && trapClicks.anyPending()) {
            // Clicked, not answered yet: keep swimming up, and don't leave before the server has confirmed
            setJump(jumpWanted());
            swimUp(low);
            return;
        }
        if (lowest != null) {
            setJump(jumpWanted());
            swimUp(low);
            int sent = 0;
            while (sent < sp.trapClicksPerTick() && actionCooldown == 0 && lowest != null && inReach(lowest)) {
                if (!sp.awaitServer() && attempts.merge(lowest, 1, Integer::sum) > 4) {
                    abort("a trapdoor will not open");
                    return;
                }
                TrapRoles.Role role = roles.roleOf(lowest.getY());
                if (role == TrapRoles.Role.NONE) {
                    abort("the trapdoors could not be told apart");
                    return;
                }
                // The stasis trapdoor is opened and stays open; only covers are remembered, to be closed again
                if (clickTrapdoor(lowest, true, role) && role == TrapRoles.Role.COVER) opened.add(lowest.immutable());
                sent++;
                lowest = lowestClosedTrapAbove(sp.awaitServer());
            }
            if (sent > 0) actionCooldown = sp.trapCooldown();
            tryEarlyThrow(pos);
            return;
        }

        if (rim == null) {
            rim = chooseRim();
            if (rim == null) {
                abort("there is no clear line to throw the pearl down the shaft");
                return;
            }
            dbg("rim chosen: neighbour offset %d,%d, floor y %d (of %d candidate(s))", rim.dx(), rim.dz(), rim.floorY(), shaft.rims().size());
            target = rim;
            low = swim && mc.player.isInWater() && pos.y < target.floorY() - 0.9;
        }
        if (low) {
            swimUp(true);
            setJump(jumpWanted());
        }
        else {
            setSprint(false);
            if (pitchTouched) mc.player.setXRot(0);
            face(pos, cx() + rim.dx(), cz() + rim.dz());
            setForward(true);
            setJump(jumpWanted());
        }
        tryEarlyThrow(pos);
    }

    private void swimUp(boolean on) {
        if (on) {
            if (!pitchTouched) {
                pitchTouched = true;
                origPitch = mc.player.getXRot();
            }
            mc.player.setXRot(-90);
            setForward(true);
            setSprint(true);
        }
        else {
            setForward(false);
            setSprint(false);
        }
    }

    private void setSprint(boolean on) {
        if (on) {
            sprintOurs = true;
            if (!mc.player.isSprinting()) mc.player.setSprinting(true);
        }
        else if (sprintOurs) {
            sprintOurs = false;
            if (!Input.isPressed(mc.options.keySprint)) mc.player.setSprinting(false);
        }
    }

    /**
     * Throws the pearl straight down the shaft from inside it, as soon as the eyes are above the water: the position and
     * speed one tick ahead (where the throw packet will be sent) go into the aim, and the throw only goes out when the
     * whole path fits the shaft and is clear. Then there is nothing left to do on the rim but close the cover.
     */
    private void tryEarlyThrow(Vec3 pos) {
        if (!climbThrow.get() || thrown || throwGate.pending() || !pearlReady || !holdsPearl()) return;
        double surface = shaft.waterTop() + 0.89;
        Vec3 v = mc.player.getDeltaMovement();
        double px = pos.x + v.x, pz = pos.z + v.z, eye = mc.player.getEyeY() + v.y;
        // The pearl should leave well above the water, not at its surface
        if (eye - 0.1 < surface + 0.35) return;
        Hang.Aim aim = Hang.aimMoving(px, pz, v.x, v.z, v.y, mc.player.onGround(), cx(), cz(), eye, surface);
        if (aim == null) {
            dbgThrottled("earlyaim", "no throw on the way up yet: offset %.2f, %.2f, speed %.3f, %.3f, %.3f, eye %.2f", px - cx(), pz - cz(), v.x, v.y, v.z, eye);
            return;
        }
        if (!Hang.pathFits(aim, cx(), cz()) || !pathClear(aim)) {
            dbgThrottled("earlyaim", "no throw on the way up yet: the path does not fit or is not clear (offset %.2f, %.2f)", px - cx(), pz - cz());
            return;
        }
        dbg("throwing on the way up from %.2f, %.2f (eye %.2f), speed %.3f, %.3f, %.3f", px, pz, eye, v.x, v.y, v.z);
        queueThrow(aim);
    }

    /** Jump is held to swim up, but not in the air out of the water, where it would ask to start gliding. */
    private boolean jumpWanted() {
        if (!elytraHandling.get().guards()) return true;
        return ElytraGuard.mayHoldJump(mc.player.onGround(), mc.player.isInWater(), mc.player.isFallFlying());
    }

    private String climbReason() {
        return ClimbDiagnosis.reason(glidedInWater, downColumn, lowestClosedTrapAbove(false) != null, seqTicks - climbProgressTick);
    }

    // The elytra

    private ElytraSwap.Kind kindOf(ItemStack st) {
        if (st.isEmpty()) return ElytraSwap.Kind.EMPTY;
        if (st.is(Items.ELYTRA)) return ElytraSwap.Kind.ELYTRA;
        var equippable = st.get(DataComponents.EQUIPPABLE);
        if (equippable != null && equippable.slot() == EquipmentSlot.CHEST) return ElytraSwap.Kind.CHESTPLATE;
        return ElytraSwap.Kind.OTHER;
    }

    private ItemStack menuStack(int id) {
        return mc.player.inventoryMenu.getSlot(id).getItem();
    }

    private ElytraSwap.Kind slotKind(int id) {
        return kindOf(menuStack(id));
    }

    private String itemId(ItemStack st) {
        return BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
    }

    private static String slotName(int id) {
        return id >= 36 ? "hotbar slot " + (id - 35) : "inventory slot " + (id - 8);
    }

    private boolean inventoryFree() {
        return mc.screen == null && mc.player.containerMenu == mc.player.inventoryMenu && mc.player.containerMenu.getCarried().isEmpty();
    }

    /** Takes a worn elytra off before the climb. True when the climb may go on this tick. */
    private boolean elytraReady() {
        if (elytraStage == 3) return true;
        if (elytraStage == 0) {
            if (!elytraHandling.get().swaps()) {
                elytraStage = 3;
                return true;
            }
            if (swapped != null && slotKind(ElytraSwap.CHEST) == ElytraSwap.Kind.ELYTRA) {
                // A restore from before went through: it is on again
                swapped = null;
                restoreIssued = false;
            }
            if (swapped != null) {
                dbg("the elytra is already off (in %s)", slotName(swapped.slot()));
                elytraStage = 3;
                return true;
            }
            ElytraSwap.Kind chest = slotKind(ElytraSwap.CHEST);
            if (chest != ElytraSwap.Kind.ELYTRA) {
                dbg("no elytra on the chest (%s): nothing to take off", chest);
                elytraStage = 3;
                return true;
            }
            ElytraSwap.Plan plan = ElytraSwap.plan(chest, this::slotKind);
            if (plan.action() == ElytraSwap.Action.NO_ROOM || plan.action() == ElytraSwap.Action.NONE) {
                elytraStage = 3;
                warn("No free slot to take the elytra off; trying without.");
                return true;
            }
            elytraStage = 1;
            invQueue.enqueue(InvKind.ELYTRA_OFF);
            dbg("queued: take the elytra off (%s)", plan.action() == ElytraSwap.Action.SWAP_CHESTPLATE ? "swap with a chestplate" : "move to a free slot");
            return false;
        }
        // Stage 1: the operation is queued or in flight
        if (invQueue.contains(InvKind.ELYTRA_OFF)) return false;
        elytraStage = 3;
        if (invQueue.takeFailed(InvKind.ELYTRA_OFF)) {
            swapped = null;
            warn("Could not take the elytra off, trying without.");
            return true;
        }
        info("Took your elytra off for the climb, it goes back afterwards.");
        dbg("the elytra is off the chest%s", swapped != null ? "; it is in " + slotName(swapped.slot()) : "");
        return true;
    }

    /** The click of the ELYTRA_OFF operation, planned afresh. False when there is nothing to click. */
    private boolean doElytraOff() {
        ElytraSwap.Kind chest = slotKind(ElytraSwap.CHEST);
        if (chest != ElytraSwap.Kind.ELYTRA) {
            dbg("elytra off: the chest slot holds no elytra now, nothing to do");
            return false;
        }
        ElytraSwap.Plan plan = ElytraSwap.plan(chest, this::slotKind);
        if (plan.action() == ElytraSwap.Action.NO_ROOM || plan.action() == ElytraSwap.Action.NONE) {
            dbg("elytra off: no room now");
            return false;
        }
        boolean withPlate = plan.action() == ElytraSwap.Action.SWAP_CHESTPLATE;
        String plateId = withPlate ? itemId(menuStack(plan.slot())) : "";
        dbg("elytra off: %s, before: %s", withPlate ? "swap" : "move", invSlots(ElytraSwap.CHEST, plan.slot()));
        InvUtils.move().fromId(ElytraSwap.CHEST).toId(plan.slot());
        swapped = new ElytraSwap.Swapped(plan.slot(), withPlate, plateId);
        restoreIssued = false;
        elytraWarned = false;
        dbg("elytra off: after (client side): %s", invSlots(ElytraSwap.CHEST, plan.slot()));
        return true;
    }

    /** Puts the elytra back, after checking both slots still hold what we left. Retried from the tick. */
    private void tryRestoreElytra() {
        if (swapped == null || (running && !verifying) || mc.player == null || mc.level == null) return;
        if (invQueue.contains(InvKind.ELYTRA_BACK)) return;
        if (invQueue.takeFailed(InvKind.ELYTRA_BACK)) {
            warn("Could not put your elytra back, it is in %s.", slotName(swapped.slot()));
            swapped = null;
            return;
        }
        if (!inventoryFree()) {
            if (!elytraWarned) {
                elytraWarned = true;
                warn("Your elytra is in %s, it goes back when your inventory is free.", slotName(swapped.slot()));
            }
            return;
        }
        ItemStack chestStack = menuStack(ElytraSwap.CHEST);
        ElytraSwap.Restore step = ElytraSwap.restore(swapped, kindOf(chestStack), itemId(chestStack), slotKind(swapped.slot()));
        switch (step) {
            case DONE -> {
                dbg("the elytra is back on the chest");
                mark("elytra back");
                if (timerPrinted && timer != null) dbg("timing: %s", timer.summary().get(timer.summary().size() - 1));
                swapped = null;
                restoreIssued = false;
                elytraWarned = false;
            }
            case LOST -> {
                warn("Could not put your elytra back: it is no longer in %s. Check your inventory.", slotName(swapped.slot()));
                swapped = null;
            }
            case CHANGED -> {
                warn("Your elytra stays in %s: the chest slot changed since.", slotName(swapped.slot()));
                swapped = null;
            }
            case RESTORE -> {
                invQueue.enqueue(InvKind.ELYTRA_BACK);
                dbg("queued: put the elytra back from %s", slotName(swapped.slot()));
            }
        }
    }

    /** The click of the ELYTRA_BACK operation, checked afresh. False when there is nothing to click. */
    private boolean doElytraBack() {
        if (swapped == null) return false;
        ItemStack chestStack = menuStack(ElytraSwap.CHEST);
        if (ElytraSwap.restore(swapped, kindOf(chestStack), itemId(chestStack), slotKind(swapped.slot())) != ElytraSwap.Restore.RESTORE) return false;
        dbg("elytra back, before: %s", invSlots(ElytraSwap.CHEST, swapped.slot()));
        InvUtils.move().fromId(swapped.slot()).toId(ElytraSwap.CHEST);
        restoreIssued = true;
        dbg("elytra back, after (client side): %s", invSlots(ElytraSwap.CHEST, swapped.slot()));
        return true;
    }

    // The inventory queue

    private static int menuId(int inventoryIndex) {
        return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
    }

    /** The items in these menu slots, for the debug log. */
    private String invSlots(int... ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            ItemStack st = menuStack(id);
            sb.append(sb.length() == 0 ? "" : ", ").append("slot ").append(id).append("=").append(st.isEmpty() ? "empty" : itemId(st) + " x" + st.getCount());
        }
        return sb.toString();
    }

    private int pingTicks() {
        var connection = mc.getConnection();
        var info = connection == null ? null : connection.getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : info.getLatency() / 50;
    }

    private boolean invVisible(InvKind kind) {
        return switch (kind) {
            case ELYTRA_OFF -> slotKind(ElytraSwap.CHEST) != ElytraSwap.Kind.ELYTRA;
            case PEARL_TO_HAND -> holdsPearl();
            case PEARL_BACK -> swappedFrom < 0;
            case ELYTRA_BACK -> slotKind(ElytraSwap.CHEST) == ElytraSwap.Kind.ELYTRA;
        };
    }

    /** Once a tick: issues the next operation when none is in flight, and sees how the one in flight is doing. */
    private void invTick() {
        if (mc.player == null || mc.level == null) {
            invQueue.clear();
            return;
        }
        pearlDiagTick();
        if (!invQueue.hasWork()) return;
        // A screen or a stack on the cursor: wait with the next one
        if (!invQueue.busy() && !inventoryFree()) return;
        InvKind cur = invQueue.current();
        boolean visible = cur != null && invVisible(cur);
        boolean changed = cur != null && mc.player.containerMenu.getStateId() != invStateId;
        int waited = invQueue.waited() + 1;
        InvQueue.Step step = invQueue.tick(visible, changed, pingTicks());
        switch (step) {
            case ISSUE, RETRY -> issueInv(invQueue.current(), step == InvQueue.Step.RETRY);
            case DONE -> dbg("inventory op %s done after %d tick(s): result seen, state id %d -> %d", cur, waited, invStateId, mc.player.containerMenu.getStateId());
            case FAILED -> {
                QwerteAddon.LOG.info("reset-pearl: inventory op {} failed after retries", cur);
                dbg("inventory op %s failed: the result never showed (state id %d -> %d)", cur, invStateId, mc.player.containerMenu.getStateId());
            }
            default -> {}
        }
    }

    private void issueInv(InvKind kind, boolean retry) {
        invStateId = mc.player.containerMenu.getStateId();
        dbg("inventory op %s: %s (state id %d, ping %d tick(s), failed tries %d)", kind, retry ? "retry with a fresh plan" : "issue", invStateId,
            pingTicks(), invQueue.failures());
        boolean clicked = switch (kind) {
            case ELYTRA_OFF -> doElytraOff();
            case PEARL_TO_HAND -> doPearlToHand();
            case PEARL_BACK -> doPearlBack();
            case ELYTRA_BACK -> doElytraBack();
        };
        if (!clicked && invQueue.current() == kind) {
            dbg("inventory op %s: nothing to click", kind);
            invQueue.skip();
        }
    }

    /** Settles the inventory when the module is turned off, where the tick no longer runs: at most one click, if idle. */
    private void flushInventory() {
        boolean busy = invQueue.busy();
        invQueue.clear();
        if (busy || !inventoryFree()) return;
        if (doPearlBack()) return;
        if (swapped != null && doElytraBack()) restoreIssued = true;
    }

    /**
     * Back from the rim block to the edge, on the rim axis only: face the hang spot every tick (which corrects any
     * sideways drift), press only forward, and let go at the spot. Never onto a covered shaft: a closed trapdoor (or
     * anything solid) over the opening stops the run, since the sneak edge stop does not hold on it.
     */
    private void hangTick() {
        Vec3 pos = mc.player.position();
        setJump(false);
        EdgeApproach.Surface surface = EdgeApproach.checkAhead(kindAt(shaft.x(), rim.floorY() - 1, shaft.z()), kindAt(shaft.x(), rim.floorY(), shaft.z()));
        if (surface != EdgeApproach.Surface.OK) {
            abort(surface == EdgeApproach.Surface.CLOSED_COVER
                ? "a closed trapdoor covers the shaft, not walking onto it"
                : "something solid is over the shaft, not walking onto it");
            return;
        }
        double[] spot = EdgeApproach.target(cx(), cz(), rim.dx(), rim.dz());
        double along = EdgeApproach.along(pos.x, pos.z, cx(), cz(), rim.dx(), rim.dz());
        Vec3 v = mc.player.getDeltaMovement();
        double vAlong = v.x * rim.dx() + v.z * rim.dz();
        if (Math.hypot(spot[0] - pos.x, spot[1] - pos.z) > 0.02) face(pos, spot[0], spot[1]);
        boolean walk = EdgeApproach.shouldWalk(along, vAlong, EdgeApproach.TARGET_ALONG);
        // The first part of the way, fully on the rim block, at walking speed; the edge itself always sneaking
        setSneak(!(sp().briskApproach() && along > EdgeApproach.BRISK_ALONG));
        setForward(walk);
        if (seq.phaseTicks() % 10 == 0) {
            dbg("hang: at %.2f, %.2f, %.2f; %.2f along the rim axis (target %.2f), walking %b; still %d ticks; pearl ready %b", pos.x, pos.y, pos.z,
                along, EdgeApproach.TARGET_ALONG, walk, stillTicks, pearlReady);
        }
        if (!pearlReady) preparePearl();
    }

    private void throwTick() {
        if (thrown || throwGate.pending()) return;
        if (!holdsPearl()) {
            pearlReady = false;
            preparePearl();
            return;
        }
        if (!issueThrow() && ++throwUnfit > Sequence.FIT_RETRIES) abort("could not line up over the shaft");
    }

    /** Aims from where the player really is and queues the throw. False if there is no clear line. */
    private boolean issueThrow() {
        Hang.Aim aim = aimNow();
        if (aim == null) return false;
        queueThrow(aim);
        return true;
    }

    /**
     * Queues the throw with this aim. The use-item is sent from the rotation's callback, which Meteor runs after the
     * rotation packet (the movement packet of the tick, or the extra packet of a later queued rotation), so the
     * server sees the aim before the throw. Silent: the packet only, the camera stays. Off: the camera turns to the
     * aim first, and the packet carries the same angles.
     */
    private void queueThrow(Hang.Aim aim) {
        long token = throwGate.request(tick);
        if (token < 0) return;
        InteractionHand h = hand;
        dbg("throw: aim yaw %.1f pitch %.1f from %.2f, %.2f (eye y %.2f), %d path point(s), hand %s, %s rotation", aim.yaw(), aim.pitch(),
            mc.player.getX(), mc.player.getZ(), mc.player.getEyeY(), aim.path().length, h, silentRotations.get() ? "silent" : "camera");
        if (!silentRotations.get()) {
            if (!pitchTouched) {
                pitchTouched = true;
                origPitch = mc.player.getXRot();
            }
            mc.player.setYRot((float) aim.yaw());
            mc.player.setXRot((float) aim.pitch());
        }
        // Down the shaft, leaning towards its middle by what the real offset needs. The pearl leaves from our x and z
        // with our velocity, which is zero by now.
        Rotations.rotate(aim.yaw(), aim.pitch(), ThrowGate.ROTATION_PRIORITY, false, () -> {
            if (!running || !throwGate.rotationSent(token)) return;
            if (mc.player == null || mc.gameMode == null || !mc.player.getItemInHand(h).is(Items.ENDER_PEARL)) {
                // The pearl is gone from the hand: nothing to throw, the rethrow logic decides what next
                throwGate.finished();
                dbg("throw skipped: no pearl in the %s", h);
                return;
            }
            // Meteor only puts a rotation first if its priority is strictly higher. If another module queued an equal or
            // higher one this tick, the player still has its camera angles here, and the use-item packet (1.21.2+: hand,
            // sequence, yaw, pitch of the player) would carry those instead of the aim. So the angles are set to the aim
            // for the call and put back right after; both 1.21.4 and 1.21.11 read them from the player in useItem.
            float oldYaw = mc.player.getYRot(), oldPitch = mc.player.getXRot();
            float aimYaw = (float) aim.yaw(), aimPitch = (float) aim.pitch();
            boolean swapped = oldYaw != aimYaw || oldPitch != aimPitch;
            InteractionResult result = null;
            try {
                if (swapped) {
                    mc.player.setYRot(aimYaw);
                    mc.player.setXRot(aimPitch);
                }
                result = mc.gameMode.useItem(mc.player, h);
            }
            finally {
                if (swapped) {
                    mc.player.setYRot(oldYaw);
                    mc.player.setXRot(oldPitch);
                }
                throwGate.finished();
            }
            // A pearl on cooldown or a refused use is tried again next tick, within the throw phase's limit
            dbg("throw result: %s", result);
            if (result.consumesAction()) {
                thrown = true;
                throwAt = tick;
                mark("pearl thrown");
            }
        });
    }

    /** The server may have refused the throw without telling: if no pearl shows up, throw again (a couple of times). */
    private void waitTick() {
        waitTicks++;
        Entity seenPearl = findPearl();
        if (seenPearl != null && waitTicks % 10 == 1) dbg("pearl seen at y %.2f (water top %d, lowest opened trapdoor %s)", seenPearl.getY(), shaft.waterTop(), opened.isEmpty() ? "none" : opened.first());
        if (throwGate.pending() || seenPearl != null) return;
        dbgThrottled("nopearl", "no pearl of yours seen in the shaft %d ticks after the throw (rethrows %d)", tick - throwAt, rethrows);
        if (rethrows >= MAX_RETHROWS || tick - throwAt < RETHROW_AFTER * (rethrows + 1L)) return;
        if (!holdsPearl()) {
            preparePearl();
            return;
        }
        if (issueThrow()) {
            rethrows++;
            dbg("throwing again (%d)", rethrows);
        }
    }

    /**
     * The throw from where the player really is, if its whole path stays inside the shaft with room for the pearl and
     * its spread, and clear of the blocks in the way (open trapdoor plates included). Null if not.
     */
    private Hang.Aim aimNow() {
        Vec3 p = mc.player.position();
        double surface = shaft.waterTop() + 0.89;
        Hang.Aim aim = Hang.aim(p.x, p.z, cx(), cz(), mc.player.getEyeY(), surface);
        if (aim == null) {
            dbgThrottled("aim", "no throw line: the fall from the rim to the water or the offset is outside what the pearl's spread allows (at %.2f, %.2f)", p.x, p.z);
            return null;
        }
        if (!Hang.pathFits(aim, cx(), cz())) {
            dbgThrottled("aim", "no throw line: the path does not fit inside the shaft (at %.2f, %.2f, offset %.2f, %.2f)", p.x, p.z, p.x - cx(), p.z - cz());
            return null;
        }
        if (!pathClear(aim)) return null;
        return aim;
    }

    /** No block is in the way of the pearl's path, with room for its size and the spread (open trapdoor plates included). */
    private boolean pathClear(Hang.Aim aim) {
        double h = Hang.PEARL_HALF;
        for (int i = 0; i + 1 < aim.path().length; i++) {
            double[] a = aim.path()[i], b = aim.path()[i + 1];
            double m = h + Hang.drift(i + 1);
            AABB box = new AABB(Math.min(a[0], b[0]) - m, Math.min(a[1], b[1]) - h, Math.min(a[2], b[2]) - m,
                Math.max(a[0], b[0]) + m, Math.max(a[1], b[1]) + h, Math.max(a[2], b[2]) + m);
            if (!mc.level.noCollision(box)) {
                dbgThrottled("aim", "no throw line: a block is in the way of path segment %d", i);
                return false;
            }
        }
        return true;
    }

    /** Our own pearl, thrown after the throw began, in the shaft. */
    private Entity findPearl() {
        Entity pearl = null;
        double best = Double.MAX_VALUE;
        long since = tick - throwAt + 3;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Projectile pr) || pr.getType() != EntityType.ENDER_PEARL || pr.getOwner() != mc.player) continue;
            // Newer than the throw: one that was only just loaded, or is old, is not it
            if (e.tickCount > since) continue;
            double dx = e.getX() - cx(), dz = e.getZ() - cz();
            double d = dx * dx + dz * dz;
            if (d <= 0.7 * 0.7 && d < best) {
                pearl = e;
                best = d;
            }
        }
        return pearl;
    }

    private boolean pearlSettled() {
        Entity pearl = findPearl();
        if (pearl == null) {
            pearlRest.reset();
            return false;
        }
        boolean restingByHeight = pearlRest.update(mc.level.getGameTime(), pearl.getY());
        if (opened.isEmpty()) return pearl.getY() < shaft.waterTop() + 1.5;
        // The lowest trapdoor we opened is the first to close, and only once the pearl is below it
        // ... and only once it is resting, not while it still falls past the trapdoor
        // In an upward bubble column the speed never gets small, so a pearl that holds its height counts as resting too
        boolean resting = pearl.getDeltaMovement().lengthSqr() < 0.04 || restingByHeight;
        return resting && Hang.pearlBelow(pearl.getY(), opened.first().getY());
    }

    /** The block y of the pearl in the shaft, or null when there is none. */
    private Integer pearlBlockY() {
        Entity pearl = findPearl();
        return pearl == null ? null : (int) Math.floor(pearl.getY());
    }

    /** Closes the lowest open trapdoor of ours, one per few ticks. Returns true when all of ours are closed. */
    private boolean coverTick() {
        // Covers that we opened and that are open, lowest first. The stasis trapdoor is never in this list.
        List<Integer> openedYs = new ArrayList<>();
        for (BlockPos t : opened) openedYs.add(t.getY());
        List<BlockPos> targets = new ArrayList<>();
        boolean await = sp().awaitServer();
        if (await && judgeTrapClicks()) {
            partialFinish("a trapdoor will not close");
            return false;
        }
        for (int y : roles.coverPlan(openedYs, pearlBlockY())) {
            BlockPos at = new BlockPos(shaft.x(), y, shaft.z());
            if (await && trapClicks.isPending(at.asLong())) continue;
            if (kindAt(shaft.x(), y, shaft.z()) == Kind.TRAPDOOR_OPEN) targets.add(at);
        }
        // Closed on our side but not confirmed yet: wait for the server before calling the cover done
        if (targets.isEmpty()) {
            boolean done = !(await && trapClicks.anyPending());
            if (done) reportLeftOpen(openedYs);
            return done;
        }
        if (coverWait < sp().coverWait()) {
            coverWait++;
            return false;
        }
        // A click on a block is skipped while sneaking with an item: wait for the stand-up to show (a few ticks at most)
        if (mc.player.isShiftKeyDown() && coverShiftWaits++ < 6) return false;
        if (actionCooldown > 0) return false;
        int sent = 0;
        for (BlockPos target : targets) {
            if (sent >= sp().coverClicksPerTick()) break;
            if (!inReach(target)) {
                partialFinish("a trapdoor is out of reach");
                return false;
            }
            if (!await && attempts.merge(target, 1, Integer::sum) > 9) {
                partialFinish("a trapdoor will not close");
                return false;
            }
            if (clickTrapdoor(target, false, TrapRoles.Role.COVER)) sent++;
        }
        actionCooldown = sp().coverCooldown();
        return false;
    }

    /** Says once, in the log, in debug and in chat, which covers were left open on purpose, so the run does not look like a full success. */
    private void reportLeftOpen(List<Integer> openedYs) {
        if (leftOpenReported) return;
        List<Integer> left = roles.leftOpen(openedYs, pearlBlockY());
        if (left.isEmpty()) return;
        leftOpenReported = true;
        String ys = left.toString();
        QwerteAddon.LOG.info("reset-pearl: trapdoors left open on purpose (the pearl rests at or below them): y {}", ys);
        dbg("trapdoors left open on purpose at y %s: the pearl rests at or below them", ys);
        warn("Left open on purpose: the trapdoor(s) at y %s (the pearl rests at or below them, closing would pull it).", ys);
    }

    // Helpers for the sequence

    /** The lowest closed trapdoor of the shaft that is not below the player. */
    private BlockPos lowestClosedTrapAbove(boolean skipPending) {
        int feetY = mc.player.blockPosition().getY();
        for (int y : shaft.trapYs()) {
            BlockPos at = new BlockPos(shaft.x(), y, shaft.z());
            if (skipPending && trapClicks.isPending(at.asLong())) continue;
            // A closed stasis trapdoor below the feet is opened too, when it is within reach: it has to be open
            if (y < feetY && !(roles.roleOf(y) == TrapRoles.Role.STASIS && inReach(at))) continue;
            if (kindAt(shaft.x(), y, shaft.z()) == Kind.TRAPDOOR_CLOSED) return at;
        }
        return null;
    }

    private boolean inReach(BlockPos pos) {
        return mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= mc.player.blockInteractionRange() - 0.4;
    }

    /**
     * Clicks one trapdoor of the shaft to open or close it. The target is checked first: it has to be in the shaft
     * column, be the trapdoor of the role meant, be a trapdoor in the world right now and not already be as wanted.
     * The click names that exact block (no aiming, so no other block can be hit), and the state is looked at again
     * two ticks later. A stasis trapdoor is never closed, whoever asks.
     * @return whether a click was sent
     */
    private boolean clickTrapdoor(BlockPos pos, boolean wantOpen, TrapRoles.Role role) {
        TrapRoles.Role actual = roles.roleOf(pos.getY());
        if (pos.getX() != shaft.x() || pos.getZ() != shaft.z() || actual != role) {
            dbg("refused to click %s: not the %s trapdoor of the shaft (it is %s)", pos, role, actual);
            return false;
        }
        if (!wantOpen && actual != TrapRoles.Role.COVER) {
            QwerteAddon.LOG.warn("reset-pearl: refused to close the {} trapdoor at {}", actual, pos);
            return false;
        }
        BlockState state = mc.level.getBlockState(pos);
        if (!state.is(BlockTags.TRAPDOORS)) {
            dbg("refused to click %s: there is no trapdoor there now (%s)", pos, state);
            return false;
        }
        boolean before = state.getValue(BlockStateProperties.OPEN);
        if (before == wantOpen) {
            dbg("trapdoor %s is already %s", pos, before ? "open" : "closed");
            return false;
        }
        Direction face = wantOpen ? Direction.DOWN : Direction.UP;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) mc.player.swing(InteractionHand.MAIN_HAND);
        dbg("click %s trapdoor %s (%s, %+d to the water top, try %s): face %s, open %b -> wanted %b, result %s",
            wantOpen ? "open" : "close", pos, actual, pos.getY() - shaft.waterTop(), attempts.get(pos), face, before, wantOpen, result);
        clickedPos = pos.immutable();
        clickedWant = wantOpen;
        clickedTick = tick;
        if (sp().awaitServer()) {
            // Pending until the server's block update (or the timeout) says what it did: not clicked again before
            blockUpdates.remove(pos.asLong());
            trapClicks.clicked(pos.asLong(), wantOpen, tick);
        }
        return true;
    }

    /**
     * Looks at the clicked trapdoors that are waiting for the server. A click that did not give the wanted state is
     * judged a failure (and may be clicked again).
     * @return some trapdoor has failed too many times
     */
    private boolean judgeTrapClicks() {
        int timeout = TrapClicks.timeoutTicks(pingTicks());
        boolean tooMany = false;
        for (TrapClicks.Click c : trapClicks.pendingClicks()) {
            BlockPos pos = BlockPos.of(c.key());
            Kind now = kindAt(pos.getX(), pos.getY(), pos.getZ());
            boolean wanted = now == (c.wantOpen() ? Kind.TRAPDOOR_OPEN : Kind.TRAPDOOR_CLOSED);
            Long seen = blockUpdates.get(c.key());
            TrapClicks.Verdict v = trapClicks.judge(c.key(), tick, wanted, seen == null ? -1 : seen, timeout);
            if (v == TrapClicks.Verdict.OK) {
                dbg("trapdoor %s: %s confirmed after %d tick(s)", pos, c.wantOpen() ? "open" : "closed", tick - c.tick());
            }
            else if (v == TrapClicks.Verdict.FAILED) {
                dbg("trapdoor %s: not %s after %d tick(s) (server update %s), failure %d", pos, c.wantOpen() ? "open" : "closed", tick - c.tick(),
                    seen == null ? "none" : "seen", trapClicks.failures(c.key()));
                if (trapClicks.failures(c.key()) >= MAX_TRAP_FAILURES) tooMany = true;
            }
        }
        return tooMany;
    }

    /** The server's block updates at the shaft, noted for the trapdoor clicks that wait for them (network thread). */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!running || shaft == null) return;
        int sx = shaft.x(), sz = shaft.z();
        if (event.packet instanceof ClientboundBlockUpdatePacket update) {
            BlockPos p = update.getPos();
            if (p.getX() == sx && p.getZ() == sz) blockUpdates.put(p.asLong(), tick);
        }
        else if (event.packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            section.runUpdates((p, state) -> {
                if (p.getX() == sx && p.getZ() == sz) blockUpdates.put(p.asLong(), tick);
            });
        }
    }

    /** Two ticks after a click: what the trapdoor did. A wrong state is simply tried again, within the attempt limits. */
    private void checkClick() {
        if (clickedPos == null || tick - clickedTick < 2) return;
        BlockState state = mc.level.getBlockState(clickedPos);
        boolean now = state.is(BlockTags.TRAPDOORS) && state.getValue(BlockStateProperties.OPEN);
        dbg("trapdoor %s after the click: open %b (wanted %b)%s", clickedPos, now, clickedWant, now == clickedWant ? "" : ", will retry");
        clickedPos = null;
    }

    /** The first standing spot beside the shaft from which a pearl thrown straight down reaches the water unobstructed. */
    private ShaftScan.Rim chooseRim() {
        List<ShaftScan.Rim> order = new ArrayList<>(shaft.rims());
        if (chamber != null && chamber.hasRim()) {
            // The rim saved with the chamber first, when it is still there
            for (ShaftScan.Rim r : shaft.rims()) {
                if (r.dx() == chamber.rimDx() && r.dz() == chamber.rimDz() && r.floorY() == chamber.rimFloorY()) {
                    order.remove(r);
                    order.add(0, r);
                    break;
                }
            }
        }
        for (ShaftScan.Rim candidate : order) {
            double[] spot = EdgeApproach.target(cx(), cz(), candidate.dx(), candidate.dz());
            double h = Hang.PEARL_HALF;
            AABB line = new AABB(spot[0] - h, shaft.waterTop() + 1.0, spot[1] - h, spot[0] + h, candidate.floorY() + 1.5, spot[1] + h);
            if (mc.level.noCollision(line)) return candidate;
        }
        return null;
    }

    private void face(Vec3 from, double tx, double tz) {
        mc.player.setYRot((float) Hang.yawToward(from.x, from.z, tx, tz));
    }

    private Kind kindAt(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!mc.level.getChunkSource().hasChunk(x >> 4, z >> 4)) return Kind.OTHER;
        BlockState state = mc.level.getBlockState(pos);
        if (state.is(BlockTags.TRAPDOORS)) return state.getValue(BlockStateProperties.OPEN) ? Kind.TRAPDOOR_OPEN : Kind.TRAPDOOR_CLOSED;
        if (state.getFluidState().is(FluidTags.WATER)) return Kind.WATER;
        if (!state.getCollisionShape(mc.level, pos).isEmpty()) return Kind.SOLID;
        return Kind.AIR;
    }

    private String danger() {
        double hostile = hostileDistance.get(), players = playerDistance.get();
        if (hostile <= 0 && players <= 0) return null;
        AABB around = mc.player.getBoundingBox().inflate(Math.max(hostile, players));
        for (Entity e : mc.level.getEntities(mc.player, around, x -> x.isAlive() && (x instanceof Enemy || x instanceof Player))) {
            if (hostile > 0 && e instanceof Enemy && e.distanceTo(mc.player) <= hostile) return "a hostile mob is nearby";
            if (players > 0 && e instanceof Player && e.distanceTo(mc.player) <= players) return "another player is nearby";
        }
        return null;
    }

    // Pearl in hand

    private boolean holdsPearl() {
        if (mc.player.getMainHandItem().is(Items.ENDER_PEARL)) {
            hand = InteractionHand.MAIN_HAND;
            return true;
        }
        if (mc.player.getOffhandItem().is(Items.ENDER_PEARL)) {
            hand = InteractionHand.OFF_HAND;
            return true;
        }
        return false;
    }

    private String hijackNote() {
        return pearlHijack.isEmpty() ? "" : " (" + pearlHijack + ")";
    }

    /** INFO line in the client log whatever the debug setting is, for why a pearl did not get into the hand. */
    private void pearlLog(String format, Object... args) {
        if (pearlDiagLines++ < 60) QwerteAddon.LOG.info("reset-pearl: pearl-to-hand: {}", String.format(format, args));
    }

    /** Starts watching what happens to the selected slot and the hand after our attempt. */
    private void startPearlWatch(String how, String where, int selectedBefore) {
        pearlExpectSelected = Players.selectedSlot();
        pearlDiagTicks = 4;
        pearlWatchState = mc.player.containerMenu.getStateId();
        ItemStack hand = mc.player.getMainHandItem();
        pearlLog("%s: pearl was in %s, selected slot before %d after %d, main hand %s, state id %d", how, where, selectedBefore + 1,
            pearlExpectSelected + 1, hand.isEmpty() ? "empty" : itemId(hand), pearlWatchState);
    }

    /** The ticks after an attempt: is the selected slot still ours, does the hand hold the pearl, did the server answer. */
    private void pearlDiagTick() {
        if (pearlDiagTicks <= 0 || pearlExpectSelected < 0) return;
        pearlDiagTicks--;
        int now = Players.selectedSlot();
        boolean answered = mc.player.containerMenu.getStateId() != pearlWatchState;
        ItemStack hand = mc.player.getMainHandItem();
        SlotWatch.Verdict verdict = SlotWatch.judge(pearlExpectSelected, now, hand.is(Items.ENDER_PEARL), answered);
        pearlLog("follow-up: selected %d (expected %d), main hand %s, state id %d (was %d) -> %s", now + 1, pearlExpectSelected + 1,
            hand.isEmpty() ? "empty" : itemId(hand), mc.player.containerMenu.getStateId(), pearlWatchState, verdict);
        if (verdict != SlotWatch.Verdict.OK) {
            pearlHijack = SlotWatch.describe(verdict, pearlExpectSelected, now);
            if (!pearlDiagSaidMod && verdict == SlotWatch.Verdict.HELD_SLOT_CHANGED) {
                pearlDiagSaidMod = true;
                warn("Pearl: %s.", pearlHijack);
            }
            pearlDiagTicks = 0;
        }
    }

    /**
     * One step towards a pearl in hand. A pearl in the hotbar is selected (a held-item change, not a container click)
     * and given 2 ticks to show before the next try; one in the main inventory is swapped into the selected slot by one
     * SWAP click, through the inventory queue, behind whatever is in flight.
     */
    private void preparePearl() {
        if (holdsPearl()) {
            pearlReady = true;
            return;
        }
        if (pearlReady && pearlExpectSelected >= 0 && Players.selectedSlot() != pearlExpectSelected) {
            // It was in the hand and the held slot moved without us
            pearlHijack = SlotWatch.describe(SlotWatch.Verdict.HELD_SLOT_CHANGED, pearlExpectSelected, Players.selectedSlot());
            pearlLog("the pearl left the hand: %s", pearlHijack);
            if (!pearlDiagSaidMod) {
                pearlDiagSaidMod = true;
                warn("Pearl: %s.", pearlHijack);
            }
            pearlReady = false;
        }
        if (invQueue.contains(InvKind.PEARL_TO_HAND)) return;
        if (invQueue.takeFailed(InvKind.PEARL_TO_HAND)) {
            abort("could not get a pearl into your hand" + hijackNote());
            return;
        }
        if (mc.screen != null || !mc.player.containerMenu.getCarried().isEmpty()) return;
        // Slots 0 to 35 only: not armour, and the offhand was looked at already
        FindItemResult found = InvUtils.find(st -> st.is(Items.ENDER_PEARL), 0, 35);
        if (!found.found()) {
            abort("no ender pearls");
            return;
        }
        if (found.isHotbar()) {
            // Let the last selection show before the next try; only real tries count
            if (tick - pearlAttemptTick < 2) return;
            if (++prepTries > PEARL_PREP_TRIES) {
                abort("could not get a pearl into your hand" + hijackNote());
                return;
            }
            int before = Players.selectedSlot();
            pearlAttemptTick = tick;
            InvUtils.swap(found.slot(), false);
            startPearlWatch("select hotbar slot " + (found.slot() + 1) + " (try " + prepTries + ")", "hotbar slot " + (found.slot() + 1), before);
        }
        else if (found.isMain()) {
            invQueue.enqueue(InvKind.PEARL_TO_HAND);
            dbg("queued: pearl from inventory slot %d into the hand", found.slot() - 8);
        }
    }

    /** The SWAP click of the PEARL_TO_HAND operation, planned afresh. False when there is nothing to click. */
    private boolean doPearlToHand() {
        if (holdsPearl()) return false;
        FindItemResult found = InvUtils.find(st -> st.is(Items.ENDER_PEARL), 0, 35);
        if (!found.found()) {
            abort("no ender pearls");
            return false;
        }
        int selected = Players.selectedSlot();
        if (found.isHotbar()) {
            pearlAttemptTick = tick;
            InvUtils.swap(found.slot(), false);
            startPearlWatch("select hotbar slot " + (found.slot() + 1), "hotbar slot " + (found.slot() + 1), selected);
            return true;
        }
        if (!found.isMain()) return false;
        displacedItem = !mc.player.getInventory().getItem(selected).isEmpty();
        displacedKind = displacedItem ? mc.player.getInventory().getItem(selected).getItem() : null;
        swappedFrom = found.slot();
        swappedTo = selected;
        int fromId = menuId(found.slot());
        dbg("pearl to hand: swap click, before: %s", invSlots(fromId, menuId(selected)));
        pearlAttemptTick = tick;
        // One SWAP click: the slot with the pearl, button = the selected hotbar index
        mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, fromId, selected, ClickType.SWAP, mc.player);
        dbg("pearl to hand: after (client side): %s", invSlots(fromId, menuId(selected)));
        startPearlWatch("swap click", "inventory slot " + (found.slot() - 8), selected);
        return true;
    }

    /** Puts the selected slot back at once, and the moved pearl back through the queue. */
    private void restoreHand() {
        if (originalSlot >= 0 && mc.player != null && Players.selectedSlot() != originalSlot) InvUtils.swap(originalSlot, false);
        originalSlot = -1;
        pendingRestore = swappedFrom >= 0;
        tryRestoreHand();
    }

    private void tryRestoreHand() {
        if (swappedFrom < 0) {
            pendingRestore = false;
            return;
        }
        if (mc.player == null) return;
        invQueue.enqueue(InvKind.PEARL_BACK);
    }

    /** The click of the PEARL_BACK operation: only if both slots still hold what we left there. False when none. */
    private boolean doPearlBack() {
        if (swappedFrom < 0 || mc.player == null) {
            pendingRestore = false;
            return false;
        }
        var from = mc.player.getInventory().getItem(swappedFrom);
        var to = mc.player.getInventory().getItem(swappedTo);
        boolean fromOk = displacedItem ? from.getItem() == displacedKind : from.isEmpty();
        boolean toOk = to.isEmpty() || to.is(Items.ENDER_PEARL);
        boolean clicked = false;
        if (fromOk && toOk) {
            dbg("pearl back, before: %s", invSlots(menuId(swappedFrom), menuId(swappedTo)));
            if (to.is(Items.ENDER_PEARL)) {
                // The same single SWAP click the other way
                mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, menuId(swappedFrom), swappedTo, ClickType.SWAP, mc.player);
                clicked = true;
            }
            dbg("pearl back, after (client side): %s", invSlots(menuId(swappedFrom), menuId(swappedTo)));
        }
        else warn("Your inventory changed, so the pearl moved into your hotbar was left where it is.");
        swappedFrom = -1;
        pendingRestore = false;
        return clicked;
    }

    // Keys

    private void setForward(boolean on) {
        drive(mc.options.keyUp, forwardKey, on);
    }

    private void setJump(boolean on) {
        drive(mc.options.keyJump, jumpKey, on);
    }

    private void setSneak(boolean on) {
        drive(mc.options.keyShift, sneakKey, on);
    }

    /**
     * With "Sneak: Toggle" on, the sneak key is a ToggleKeyMapping whose setDown(true) flips the state and setDown(false)
     * does nothing (checked in 1.21.4 and 1.21.11), so the state is only ever changed when it differs from what is wanted.
     */
    private boolean toggles(KeyMapping key) {
        return key == mc.options.keyShift && mc.options.toggleCrouch().get();
    }

    private void change(KeyMapping key, boolean down) {
        if (toggles(key)) key.setDown(true);
        else key.setDown(down);
    }

    /** Makes the key down or up, remembering what it was the first time. */
    private void drive(KeyMapping key, Held held, boolean down) {
        if (!held.ours) {
            held.ours = true;
            held.orig = key.isDown();
        }
        if (key.isDown() != down) change(key, down);
    }

    /** Gives the key back: to what it was before (toggle-sneak), or to what the keyboard says. */
    private void releaseKey(KeyMapping key, Held held) {
        if (!held.ours) return;
        held.ours = false;
        boolean target = toggles(key) ? held.orig : Input.isPressed(key);
        if (key.isDown() != target) change(key, target);
    }

    private void releaseKeys() {
        if (mc.options == null) return;
        releaseKey(mc.options.keyUp, forwardKey);
        releaseKey(mc.options.keyJump, jumpKey);
        releaseKey(mc.options.keyShift, sneakKey);
        if (mc.player != null) setSprint(false);
    }

    // Debug

    /** A warning in chat that is also kept in the client log. */
    private void warn(String format, Object... args) {
        QwerteAddon.LOG.info("reset-pearl: warning: {}", String.format(format, args));
        warning(format, args);
    }

    private void dbg(String format, Object... args) {
        if (!debug.get()) return;
        String message = String.format(format, args);
        QwerteAddon.LOG.info("reset-pearl: {}", message);
        info("%s", "[debug] " + message);
    }

    /** A debug line that comes at most once a second per key. */
    private void dbgThrottled(String key, String format, Object... args) {
        if (debug.get() && throttle.allow(tick, key)) dbg(format, args);
    }

    private static String describe(ShaftScan.Shaft sh) {
        if (sh == null) return "none";
        return String.format("column %d,%d; water top y %d; trapdoors at y %s; rims %s", sh.x(), sh.z(), sh.waterTop(), sh.trapYs(), sh.rims());
    }

    /** Whether the player stands in or just above a bubble column that drags down (above magma). */
    private boolean inDownColumn() {
        BlockPos feet = mc.player.blockPosition();
        for (int dy = -1; dy <= 1; dy++) {
            BlockState state = mc.level.getBlockState(feet.offset(0, dy, 0));
            if (state.is(Blocks.BUBBLE_COLUMN) && state.getValue(BubbleColumnBlock.DRAG_DOWN)) return true;
        }
        return false;
    }

    // Ending

    /** The cover is done: the new pearl has to stay in the water, and the player where they are, for a moment. */
    private void beginVerify() {
        releaseKeys();
        verifying = true;
        verifyTicks = 0;
        verifyPos = mc.player.position();
        mark("cover closed");
        // The controls are back; the hotbar and the elytra go back now too in the faster modes
        if (sp().parallelRestore()) restoreHand();
        dbg("verifying that the pearl stays in stasis for %d ticks", sp().verifyTicks());
    }

    private void verifyTick() {
        verifyTicks++;
        Vec3 p = mc.player.position();
        boolean teleported = p.distanceTo(verifyPos) > 3;
        verifyPos = p;
        Entity pearl = findPearl();
        boolean present = pearl != null;
        boolean slow = present && pearl.getDeltaMovement().lengthSqr() < 0.25;
        boolean atWater = present && Math.abs(pearl.getY() - (shaft.waterTop() + 0.5)) < 2.5;
        TrapRoles.Armed verdict = TrapRoles.decide(verifyTicks, sp().verifyTicks(), present, slow, atWater, teleported);
        dbgThrottled("verify", "verify tick %d: pearl %s, slow %b, at water %b, player moved %b -> %s", verifyTicks,
            present ? String.format("y %.2f", pearl.getY()) : "gone", slow, atWater, teleported, verdict);
        String name = chamber.name();
        switch (verdict) {
            case WAIT -> {}
            case ARMED -> {
                verifying = false;
                finish();
            }
            case TRIGGERED -> {
                verifying = false;
                end();
                QwerteAddon.LOG.info("reset-pearl: the pearl was triggered during cover ({}), player moved {}", name, teleported);
                warn("The pearl was triggered during cover: %s is not armed.", name);
            }
            case NOT_SETTLED -> {
                verifying = false;
                end();
                warn("The new pearl is not resting in the water after the cover: %s may not be armed.", name);
            }
        }
    }

    private void finish() {
        String name = chamber.name();
        mark("verified");
        end();
        QwerteAddon.LOG.info("reset-pearl: finished '{}'", name);
        info("Re-armed %s.", name);
    }

    /** The pearl is thrown but the cover could not be closed. */
    private void partialFinish(String why) {
        if (!running) return;
        String name = chamber.name();
        end();
        warn("Re-armed %s, but %s: the cover may be open.", name, why);
    }

    private void abort(String why) {
        if (!running) return;
        String name = chamber.name();
        boolean left;
        try {
            left = mc.level != null && shaft != null && opened.stream().anyMatch(t -> kindAt(t.getX(), t.getY(), t.getZ()) == Kind.TRAPDOOR_OPEN);
        }
        catch (RuntimeException e) {
            // Could not look: say it may be open
            left = !opened.isEmpty();
        }
        boolean closedAgain = left && closeOpenedCoversIfOut();
        if (closedAgain) left = false;
        boolean placed = thrown;
        Sequence.Phase at = seq == null ? null : seq.phase();
        end();
        QwerteAddon.LOG.info("reset-pearl: aborted '{}' ({}), phase {}, thrown {}, cover possibly open {}", name, why, at, placed, left);
        if (why != null) {
            warn("Stopped re-arming %s: %s.%s%s", name, why, placed ? " A pearl was already thrown." : "", left ? " The cover was left open." : closedAgain ? " The cover was closed again." : "");
        }
    }

    /**
     * After an abort with covers still open: if the player is already out of the shaft, close the covers that were
     * opened (never the stasis trapdoor, which the cover plan does not contain). Inside, they are left, so as not to
     * shut the player in.
     * @return all of them were clicked shut
     */
    private boolean closeOpenedCoversIfOut() {
        try {
            Vec3 p = mc.player.position();
            if (Math.max(Math.abs(p.x - cx()), Math.abs(p.z - cz())) < 0.7) {
                dbg("abort: still in the shaft, the cover is left as it is");
                return false;
            }
            List<Integer> ys = new ArrayList<>();
            for (BlockPos t : opened) ys.add(t.getY());
            boolean all = true;
            for (int y : roles.coverPlan(ys, pearlBlockY())) {
                BlockPos at = new BlockPos(shaft.x(), y, shaft.z());
                if (kindAt(at.getX(), at.getY(), at.getZ()) != Kind.TRAPDOOR_OPEN) continue;
                if (!inReach(at) || !clickTrapdoor(at, false, TrapRoles.Role.COVER)) all = false;
            }
            return all;
        }
        catch (RuntimeException e) {
            QwerteAddon.LOG.error("reset-pearl: closing the cover after the abort failed", e);
            return false;
        }
    }

    /** Lets go of everything, however the sequence ended. */
    private void mark(String label) {
        if (timer != null) timer.mark(label, tick, System.nanoTime());
    }

    private void printTiming() {
        if (timer == null || timerPrinted) return;
        timerPrinted = true;
        List<String> lines = timer.summary();
        QwerteAddon.LOG.info("reset-pearl: timing (speed {}): {}", speed.get(), String.join("; ", lines));
        for (String line : lines) dbg("timing: %s", line);
        if (timer.has("cover closed")) dbg("timing: cover closed %d ticks after the pull%s", timer.ticksTo("cover closed"),
            timer.has("verified") ? ", verified " + timer.ticksTo("verified") + " ticks after the pull" : "");
    }

    private void end() {
        printTiming();
        // Not issued yet: no longer wanted. One in flight stays; its effects are put back by the restores behind it.
        invQueue.cancelQueued(InvKind.PEARL_TO_HAND);
        invQueue.cancelQueued(InvKind.ELYTRA_OFF);
        if (pitchTouched && mc.player != null) {
            mc.player.setXRot(origPitch);
            pitchTouched = false;
        }
        running = false;
        verifying = false;
        clickedPos = null;
        releaseKeys();
        if (mc.player != null) restoreHand();
        else {
            originalSlot = -1;
            swappedFrom = -1;
            pendingRestore = false;
        }
        opened.clear();
        attempts.clear();
        throwGate.cancel();
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, ResetPearl.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}

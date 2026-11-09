package com.porter.qwerte.modules;

import com.porter.qwerte.manager.local.LocalOrderStore;
import com.porter.qwerte.manager.local.LocalSafety;
import com.porter.qwerte.manager.model.OrderMode;
import com.porter.qwerte.manager.model.OrderModel;
import com.porter.qwerte.manager.model.OrderModel.Entry;
import com.porter.qwerte.stash.Order;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.stash.StashIndex;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Carries out an order with this client as the bot, with no server: the stash window hands it an {@link Order}, and the
 * stash-sorter's own {@link OrderRunner} (the engine stash-manager feeds from the server) does the work, driven the way
 * stash-manager drives it (see {@code StashManager.driveSorter}, {@code takeOrder}, {@code onDeactivate}): the sorter loop is
 * driven with {@code drive(true)} every tick, the order is offered when {@code orderBlocker()} is clear, its report is read,
 * a finished order is acknowledged, and stopping parks the order and stops driving. Nothing is sent over the network.
 *
 * <p>Safety for unattended use: the run stops when another player or a hostile mob comes close, or when it takes too long
 * ({@link LocalSafety}); the sorter's own teardown lets go of everything it holds. It lives in this package because the
 * runner is package-private. Client thread only. The run subscribes to the event bus only while an order is live.
 */
public final class LocalOrders {
    /** Where the run says things (the module's chat messages). */
    public interface Chat {
        void info(String text);

        void warn(String text);
    }

    /** The module's settings for a run, read when it starts. */
    public record Settings(LocalSafety.Limits limits, boolean chat, int abortHealth) {
        /** Without the health check. */
        public Settings(LocalSafety.Limits limits, boolean chat) {
            this(limits, chat, 0);
        }
    }

    private static final String LEFTOVERS_NOTE = " Items taken for the order may still be in your inventory.";

    /** The delivery container, anvil and crafting table of the sorter's stash (null: not set), for display. */
    public record Setup(String stash, int[] delivery, int[] anvil, int[] table, String anvilSource, String tableSource,
                        boolean craftingOn, boolean namedShulkers, boolean autoExpand, int xpLevels) {
    }

    private enum Phase { IDLE, STARTING, RUNNING, ENDING }

    private static final long START_WAIT_MS = 20_000;
    private static final long ENDING_MIN_MS = 3_000, ENDING_MAX_MS = 60_000;
    private static final int SCAN_EVERY = 10;
    private static final long SAVE_EVERY_MS = 3_000;

    private final LocalOrderStore store;
    private final Supplier<Settings> settings;
    private final Chat chat;
    private final SecureRandom random = new SecureRandom();

    private Phase phase = Phase.IDLE;
    private Order order;
    private Entry entry;
    private String stashName = "";
    private Settings run;
    private long startedAt, endingSince, lastSaved;
    private int ticks;
    private String lastSig = "";
    private Player runPlayer;
    private boolean endingCancelled;
    private boolean loggedFailure;

    public LocalOrders(LocalOrderStore store, Supplier<Settings> settings, Chat chat) {
        this.store = store;
        this.settings = settings;
        this.chat = chat;
    }

    // ---------- what the window asks ----------

    public boolean active() {
        return phase != Phase.IDLE;
    }

    /** The id of the order being carried out, or null. */
    public String activeId() {
        return phase == Phase.IDLE ? null : order.id;
    }

    private static StashSorter sorter() {
        return Modules.get().get(StashSorter.class);
    }

    private static StashManager manager() {
        return Modules.get().get(StashManager.class);
    }

    /** The sorter's stash, or null with no world or no stash. */
    private static Stash stash() {
        StashSorter s = sorter();
        return s == null || mc.level == null || mc.player == null ? null : s.currentStash();
    }

    /** Why an order cannot start now, or null. */
    public String blocker() {
        StashSorter s = sorter();
        if (s == null) return "stash-sorter is not available.";
        boolean inWorld = mc.level != null && mc.player != null;
        StashManager m = manager();
        Stash st = inWorld ? s.currentStash() : null;
        String saved = st == null || st.activeOrder == null ? null : st.activeOrder.id;
        String why = OrderMode.localRefusal(inWorld, m != null && m.isActive(), active(), inWorld && s.orders().holdsOrder(),
            st != null, st != null && s.orders().deliveryBox() != null, saved);
        if (why != null || st == null) return why;
        // What the sorter's loop would refuse a moment after starting, said up front
        String away = notInStashWorld(st);
        return away == null ? null : OrderMode.driveBlockerText(away);
    }

    /** The sorter's own check before it can be driven (StashSorter.notInStashWorld, which is private): another dimension, or too far. */
    private static String notInStashWorld(Stash st) {
        String dim = !st.dimension.isEmpty() ? st.dimension : st.areaDimension;
        if (!dim.isEmpty()) return dim.equals(Stash.currentDimension()) ? null : "wrong dimension";
        net.minecraft.world.phys.Vec3 at = mc.player.position();
        for (Stash.Box box : st.boxes) {
            if (box.known() && !box.foreign && at.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(box.pos)) <= 128 * 128) return null;
        }
        return "too far from the stash";
    }

    /** Whether saved state of this order id may be dropped: only orders of this client, never a server order parked by stash-manager. */
    private static void dropLocalSaved(OrderRunner runner, String keep) {
        String saved = runner.resumeId();
        if (OrderMode.isLocalId(saved)) runner.dropSaved(keep);
    }

    private void safely(String step, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException | LinkageError e) {
            if (!loggedFailure) {
                loggedFailure = true;
                com.porter.qwerte.QwerteAddon.LOG.warn("stash-window: local order, step '{}' failed: {}", step, e.toString());
            }
        }
    }

    /** Said while leftovers are being put back after the order ended and the user asked to cancel; "" otherwise. */
    public String note() {
        return phase == Phase.ENDING && endingCancelled ? "Returning leftovers... (Cancel again to stop now)" : phase == Phase.ENDING ? "Returning leftovers..." : "";
    }

    public static boolean isLocalId(String id) {
        return OrderMode.isLocalId(id);
    }

    /** The saved history of the sorter's stash, newest first (empty with no stash). */
    public List<Entry> history() {
        Stash st = stash();
        if (st == null) return List.of();
        store.load(st.server);
        return store.list(st.name);
    }

    public String historyWarning() {
        return store.warning();
    }

    /** Removes the finished orders of the sorter's stash from the history. */
    public int clearFinished() {
        Stash st = stash();
        if (st == null) return 0;
        store.load(st.server);
        int n = store.removeFinished(st.name);
        store.save();
        return n;
    }

    public String stashName() {
        Stash st = stash();
        return st == null ? "" : st.name;
    }

    public Setup setup() {
        StashSorter s = sorter();
        Stash st = stash();
        if (s == null || st == null) return null;
        StashManager m = manager();
        return new Setup(st.name, xyz(st.deliveryPos), xyz(st.anvilPos), xyz(st.craftingTablePos), s.anvilSource(), s.craftingTableSource(),
            m != null && m.craftingEnabled(), m == null || m.useNamedShulkers(), m != null && m.autoExpandDelivery(), s.xpLevels());
    }

    private static int[] xyz(BlockPos p) {
        return p == null ? null : new int[] { p.getX(), p.getY(), p.getZ() };
    }

    /** How the sorter's stash would be reported to a server: for the window's naming and delivery warnings. */
    public StashIndex.DeliveryStorage storage() {
        StashSorter s = sorter();
        return s == null ? null : s.deliveryStorage();
    }

    /** A number that changes when what the stash index or delivery contents show changes. */
    public long fingerprint() {
        return indexFingerprint() * 31 + deliveryFingerprint();
    }

    /** Changes when the stash index would. */
    public long indexFingerprint() {
        Stash st = stash();
        return st == null ? 0 : StashIndex.fingerprint(st, false);
    }

    /** Changes when a delivery chest was read or its fullness changed. */
    public long deliveryFingerprint() {
        Stash st = stash();
        return st == null ? 0 : StashIndex.deliveryStamp(st);
    }

    /** The stash index as the server would get it (JSON), or null. */
    public byte[] indexJson() {
        Stash st = stash();
        if (st == null) return null;
        StashIndex.Built b = StashIndex.build(st, false);
        return b == null ? null : b.json();
    }

    /** The delivery contents as the server would get them (JSON), or null when no delivery container is set. */
    public byte[] deliveryJson() {
        Stash st = stash();
        if (st == null) return null;
        var report = StashIndex.deliveryReport(st, mc.player.getName().getString());
        return report == null ? null : report.json();
    }

    /**
     * Turns on one of stash-manager's right-click picks ("delivery", "anvil" or "crafting-table"): the next right-click on
     * a matching block sets it, or clears it when it is the current one.
     * @return null when the pick is on, else why not
     */
    public String select(String what) {
        if (active()) return "An order is running on this client. Stop it first.";
        StashManager m = manager();
        if (m == null) return "stash-manager is not available.";
        if (stash() == null) return "No stash is loaded. Set stash-name in stash-sorter and scan the stash first.";
        Setting<?> pick = m.settings.get("select-" + what);
        if (!(pick instanceof BoolSetting bool)) return "That setting does not exist.";
        bool.set(true);
        return bool.get() ? null : "No stash is loaded.";
    }

    /** Clears the delivery container, anvil or crafting table ("delivery", "anvil", "crafting-table"). */
    public boolean clear(String what) {
        StashSorter s = sorter();
        if (s == null || stash() == null || active()) return false;
        return switch (what) {
            case "delivery" -> s.clearDelivery();
            case "anvil" -> s.clearAnvil();
            case "crafting-table" -> s.clearCraftingTable();
            default -> false;
        };
    }

    // ---------- running an order ----------

    /** A fresh id for an order of this client. */
    public String newId() {
        byte[] b = new byte[6];
        random.nextBytes(b);
        return "L-" + HexFormat.of().formatHex(b);
    }

    /** @return null when the order was taken, else why not */
    public String start(Order o) {
        String why = blocker();
        if (why != null) return why;
        StashSorter s = sorter();
        Stash st = stash();
        store.load(st.server);
        run = settings.get();
        order = o;
        stashName = st.name;
        startedAt = System.currentTimeMillis();
        endingSince = 0;
        ticks = 0;
        lastSig = "";
        lastSaved = 0;
        runPlayer = mc.player;
        endingCancelled = false;
        loggedFailure = false;
        entry = newEntry(o, startedAt);
        s.localOrders(true);
        phase = Phase.STARTING;
        MeteorClient.EVENT_BUS.subscribe(this);
        record(true);
        chat.info("Order " + o.id + ": started on this client.");
        return null;
    }

    /** Starts the same order again as a new one. @return null when it started, else why not */
    public String retry(Entry old) {
        Order o = new Order(newId(), old.lines(), Order.Names.of(old.namesMode(), old.namesAll(), old.namesEach()), old.repairAll(), old.repairEstimate());
        String why = start(o);
        if (why == null) {
            store.put(stashName, LocalOrderStore.withRetried(old, o.id));
            store.save();
        }
        return why;
    }

    /**
     * Stops the order the way turning stash-manager off does (parked, the sorter stops being driven). The history says cancelled.
     * While the leftovers of an order that already ended are being put back, the first call only says so and lets that finish
     * (up to a minute); the second stops at once. Never throws.
     */
    public void cancel(String reason) {
        if (phase == Phase.IDLE) return;
        if (phase == Phase.ENDING && !endingCancelled && !"stopped: stash-window was turned off".equals(reason)) {
            endingCancelled = true;
            chat.info("Returning leftovers... (Cancel again to stop now)");
            return;
        }
        end(OrderModel.CANCELLED, reason == null || reason.isEmpty() ? "stopped from the window" : reason, false);
    }

    private void abort(String reason) {
        end(OrderModel.FAILED, "stopped: " + reason, true);
    }

    /**
     * Parks the order, lets go of the sorter and ends the run. Every step is tried on its own and a failure of one is logged
     * (once) and does not stop the next; {@link #finish()} always runs, so the handler is never left subscribed, the phase is
     * never left running and the sorter's order flag is never left on. Never throws.
     */
    private void end(String status, String message, boolean warn) {
        try {
            boolean wasRunning = phase == Phase.STARTING || phase == Phase.RUNNING;
            StashSorter s = sorter();
            OrderRunner runner = s == null ? null : s.orders();
            boolean alreadyDone = entry != null && entry.finished();
            if (runner != null && phase != Phase.IDLE) {
                // Like StashManager.onDeactivate: nobody is left to carry the order, so it is parked and the loop let go
                safely("park", runner::park);
                safely("stop driving", () -> s.drive(false));
                safely("drop saved plan", () -> dropLocalSaved(runner, null));
            }
            if (!alreadyDone && entry != null) {
                entry = LocalOrderStore.withEnd(entry, status, message, System.currentTimeMillis());
                // An order stopped before its end may have left things taken out for it in the inventory
                String text = "Order " + order.id + " " + message + "." + (wasRunning ? LEFTOVERS_NOTE : "");
                if (warn) safely("chat", () -> chat.warn(text));
                else safely("chat", () -> chat.info(text));
            } else if (warn) {
                safely("chat", () -> chat.warn("Order " + order.id + " " + message + "." + LEFTOVERS_NOTE));
            }
        } catch (RuntimeException | LinkageError e) {
            if (!loggedFailure) {
                loggedFailure = true;
                com.porter.qwerte.QwerteAddon.LOG.warn("stash-window: local order, could not end cleanly: {}", e.toString());
            }
        } finally {
            finish();
        }
    }

    private void finish() {
        try {
            StashSorter s = sorter();
            if (s != null) safely("order flag", () -> s.localOrders(false));
            safely("save history", () -> {
                if (entry != null) record(true);
                store.flush();
            });
        } finally {
            phase = Phase.IDLE;
            endingCancelled = false;
            runPlayer = null;
            safely("unsubscribe", () -> MeteorClient.EVENT_BUS.unsubscribe(this));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (phase == Phase.IDLE) return;
        if (entry != null && !entry.finished()) entry = LocalOrderStore.withEnd(entry, OrderModel.FAILED, "stopped: you left the game", System.currentTimeMillis());
        finish();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (phase == Phase.IDLE) return;
        try {
            step();
        } catch (RuntimeException | LinkageError e) {
            com.porter.qwerte.QwerteAddon.LOG.warn("stash-window: local order stopped on an error", e);
            abort("an error: " + e.getClass().getSimpleName());
        }
    }

    private void step() {
        long now = System.currentTimeMillis();
        StashSorter s = sorter();
        if (mc.level == null || mc.player == null || s == null) {
            if (entry != null && !entry.finished()) entry = LocalOrderStore.withEnd(entry, OrderModel.FAILED, "stopped: no world", now);
            finish();
            return;
        }
        StashManager m = manager();
        if (m != null && m.isActive()) {
            abort("stash-manager was turned on");
            return;
        }
        // Dead, or a new player entity after a respawn: the sorter's state is not this player's any more
        if (!mc.player.isAlive() || (runPlayer != null && mc.player != runPlayer)) {
            abort("you died, respawned or changed dimension");
            return;
        }
        if (run.abortHealth() > 0 && mc.player.getHealth() <= run.abortHealth()) {
            abort("your health dropped to " + run.abortHealth());
            return;
        }
        if (ticks % SCAN_EVERY == 0) {
            String why = LocalSafety.check(run.limits(), nearest(false, run.limits().playerRadius()), nearest(true, run.limits().hostileRadius()), now - startedAt);
            if (why != null) {
                abort(why);
                return;
            }
        }
        ticks++;
        s.drive(true);
        OrderRunner runner = s.orders();

        switch (phase) {
            case STARTING -> {
                String blocked = s.orderBlocker();
                if (blocked == null) {
                    runner.chat(run.chat());
                    dropLocalSaved(runner, order.id);
                    String why = runner.offer(order);
                    if (why == null) {
                        s.orderTaken();
                        phase = Phase.RUNNING;
                    } else {
                        abort(why);
                    }
                } else if (now - startedAt > START_WAIT_MS) {
                    abort("the sorter could not start it (" + blocked + ")");
                }
            }
            case RUNNING -> {
                OrderRunner.Report r = runner.report();
                if (r == null) {
                    abort("the order was dropped by the sorter");
                    return;
                }
                update(r);
                if (r.terminal()) {
                    runner.ack(r.id());
                    phase = Phase.ENDING;
                    endingSince = now;
                    String text = "Order " + order.id + " " + r.status().replace('_', ' ') + (r.message().isEmpty() ? "." : ": " + r.message());
                    if (r.status().equals("delivered")) chat.info(text);
                    else chat.warn(text);
                }
            }
            case ENDING -> {
                long waited = now - endingSince;
                boolean settled = !s.returningLeftovers() && !runner.holdsOrder();
                if ((settled && waited >= ENDING_MIN_MS) || waited >= ENDING_MAX_MS) {
                    safely("stop driving", () -> s.drive(false));
                    finish();
                    return;
                }
            }
            default -> {
            }
        }
        if (phase != Phase.IDLE) record(false);
    }

    private void update(OrderRunner.Report r) {
        String sig = r.status() + "|" + r.phase() + "|" + r.done() + "|" + r.total() + "|" + r.message();
        if (sig.equals(lastSig)) return;
        lastSig = sig;
        boolean terminal = r.terminal();
        entry = new Entry(entry.id(), r.status(), clean(r.phase(), 120), clean(r.message(), 320), r.done(), r.total(), entry.cancelRequested(), 0,
            System.currentTimeMillis(), entry.createdAt(), entry.hasPlacedBy(), entry.placedById(), entry.placedByName(), entry.retriedAs(),
            entry.lines(), entry.namesMode(), entry.namesAll(), entry.namesEach(), terminal ? r.shulkers() : 0, terminal && !r.status().equals("cancelled"),
            entry.repairAll(), entry.repairEstimate(), terminal && !r.status().equals("cancelled") ? r.repair() : null);
    }

    private static String clean(String s, int max) {
        return com.porter.qwerte.manager.model.Text.sanitize(s, max);
    }

    private void record(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastSaved < SAVE_EVERY_MS) return;
        lastSaved = now;
        store.put(stashName, entry);
        store.save();
    }

    private double nearest(boolean hostile, int radius) {
        if (radius <= 0) return -1;
        AABB box = mc.player.getBoundingBox().inflate(radius);
        double best = -1;
        for (Entity e : mc.level.getEntities(mc.player, box, x -> x.isAlive() && (hostile ? x instanceof Enemy : x instanceof Player p && !p.isSpectator()))) {
            double d = e.distanceTo(mc.player);
            if (best < 0 || d < best) best = d;
        }
        return best;
    }

    private Entry newEntry(Order o, long now) {
        Order.Names n = o.names;
        List<String> each = new ArrayList<>(n == null ? List.of() : n.each());
        return new Entry(o.id, OrderModel.IN_PROGRESS, "starting", "", 0, 0, false, 0, now, now, false, "", "", "", List.copyOf(o.lines),
            n == null ? "" : n.mode(), n == null ? "" : n.all(), each, 0, false, o.repairAll, o.repairEstimate, null);
    }
}

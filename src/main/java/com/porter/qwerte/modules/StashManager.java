package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.stash.StashIndex;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.stash.Order;
import com.porter.qwerte.compat.RightClick;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pushes what is in the saved stash (the one stash-sorter uses) to the Stash Manager web server on the local network,
 * which shows it. The index is built on the client thread; only bytes go to the network thread, and the reply is handed
 * back to the client thread, so all state here is only touched on the client thread.
 */
public class StashManager extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> serverUrl = sgGeneral.add(new StringSetting.Builder()
        .name("server-url")
        .description("Address of the Stash Manager server, http or https.")
        .defaultValue("http://127.0.0.1:8765")
        .build()
    );

    private final Setting<String> apiKey = sgGeneral.add(new StringSetting.Builder()
        .name("api-key")
        .description("The server's API key. The server prints it on its console and keeps it in stash-manager-data/config.properties.")
        .defaultValue("")
        .build()
    );

    private final Setting<Integer> scanInterval = sgGeneral.add(new IntSetting.Builder()
        .name("scan-interval")
        .description("How often, in seconds, the stash is checked for changes. Only changes are sent.")
        .defaultValue(10)
        .min(2)
        .sliderRange(2, 60)
        .build()
    );

    private final Setting<Boolean> includeExcluded = sgGeneral.add(new BoolSetting.Builder()
        .name("include-excluded")
        .description("Also list containers excluded from sorting.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> acceptOrders = sgGeneral.add(new BoolSetting.Builder()
        .name("accept-orders")
        .description("Let the bot take orders from the web page: it builds shulkers from the stash and delivers them to the delivery container (stash-sorter pick: Delivery). stash-sorter does not need to be on: while this module is on it reads containers, takes orders and puts leftovers back by itself, using stash-sorter's settings (stash-name, area, safe-walk...), and never sorts. Needs a free inventory slot and enough empty shulkers. Loose items an order took out and did not deliver go back to the container they came from, or the nearest one with room when that is not known; shulkers it unpacked go back where they came from. To stop the bot, turn off accept-orders or stash-manager. Turning stash-sorter off hands control back to stash-manager while accept-orders is on.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> useNamedShulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("use-named-shulkers")
        .description("Orders may take items out of shulkers that have a custom name. Off: named shulkers are left alone, as the sorter does.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> freshMinutes = sgGeneral.add(new IntSetting.Builder()
        .name("fresh-minutes")
        .description("A container read within this many minutes is not read again when stash-sorter or an order starts, unless something marked it changed (another player opened it, a sign changed, items were not where they were saved). 0: read everything again every time. A website rescan always reads everything. stash-sorter uses this too.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 30)
        .build()
    );

    private final Setting<Boolean> orderChat = sgGeneral.add(new BoolSetting.Builder()
        .name("order-messages")
        .description("Tell you in chat when an order is taken, delivered or fails.")
        .defaultValue(true)
        .build()
    );

    private final SettingGroup sgDelivery = settings.createGroup("Delivery");

    private final Setting<Boolean> selectDelivery = sgDelivery.add(new BoolSetting.Builder()
        .name("select-delivery")
        .description("Turn on, then right-click a container in the stash to make it the delivery container, where finished orders are put. Right-click the current one to clear it. Turns itself off after one click.")
        .defaultValue(false)
        .onChanged(on -> {
            if (on && Modules.get().get(StashSorter.class).currentStash() == null) {
                warning("No stash is loaded. Scan one with stash-sorter first; this uses the stash named in its settings.");
                this.selectDelivery.set(false);
            }
            // Only one of the picks at a time
            else if (on) {
                if (this.selectAnvil != null && this.selectAnvil.get()) this.selectAnvil.set(false);
                if (this.selectCraftingTable != null && this.selectCraftingTable.get()) this.selectCraftingTable.set(false);
            }
        })
        .build()
    );

    private final Setting<Boolean> clearDelivery = sgDelivery.add(new BoolSetting.Builder()
        .name("clear-delivery")
        .description("Turn on to clear the delivery container. Turns itself off again.")
        .defaultValue(false)
        .onChanged(on -> {
            if (!on) return;
            this.clearDelivery.set(false);
            if (Modules.get().get(StashSorter.class).clearDelivery()) info("Delivery container cleared.");
            else info("There is no delivery container.");
        })
        .build()
    );

    private final Setting<Boolean> showDelivery = sgDelivery.add(new BoolSetting.Builder()
        .name("show-delivery")
        .description("Outline the delivery container while this module is on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> deliveryColor = sgDelivery.add(new ColorSetting.Builder()
        .name("delivery-color")
        .description("The outline of the delivery container.")
        .defaultValue(new SettingColor(60, 255, 90, 90))
        .visible(showDelivery::get)
        .build()
    );

    private final SettingGroup sgAnvil = settings.createGroup("Anvil");

    private final Setting<Boolean> selectAnvil = sgAnvil.add(new BoolSetting.Builder()
        .name("select-anvil")
        .description("Turn on, then right-click an anvil to make it the anvil orders are named at (when an order gives its shulkers names). Right-click the current one to clear it. It may be outside the stash area, and is never sorted or listed. Turns itself off after one click.")
        .defaultValue(false)
        .onChanged(on -> {
            if (on && Modules.get().get(StashSorter.class).currentStash() == null) {
                warning("No stash is loaded. Scan one with stash-sorter first; this uses the stash named in its settings.");
                this.selectAnvil.set(false);
            }
            else if (on) {
                if (this.selectDelivery.get()) this.selectDelivery.set(false);
                if (this.selectCraftingTable != null && this.selectCraftingTable.get()) this.selectCraftingTable.set(false);
            }
        })
        .build()
    );

    private final Setting<Boolean> clearAnvil = sgAnvil.add(new BoolSetting.Builder()
        .name("clear-anvil")
        .description("Turn on to clear the anvil. Turns itself off again.")
        .defaultValue(false)
        .onChanged(on -> {
            if (!on) return;
            this.clearAnvil.set(false);
            if (Modules.get().get(StashSorter.class).clearAnvil()) info("Anvil cleared.");
            else info("There is no anvil.");
        })
        .build()
    );

    private final Setting<Boolean> showAnvil = sgAnvil.add(new BoolSetting.Builder()
        .name("show-anvil")
        .description("Outline the anvil while this module is on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> anvilColor = sgAnvil.add(new ColorSetting.Builder()
        .name("anvil-color")
        .description("The outline of the anvil.")
        .defaultValue(new SettingColor(255, 190, 40, 90))
        .visible(showAnvil::get)
        .build()
    );

    private final SettingGroup sgCrafting = settings.createGroup("Crafting");

    private final Setting<Boolean> enableCrafting = sgCrafting.add(new BoolSetting.Builder()
        .name("enable-crafting")
        .description("Orders and compaction may craft a plain Shulker Box when no empty one is left: shulker shells and a chest (made of planks, made of logs) are taken from the stash and crafted at the crafting table below. Shulker shells can't be crafted, so they have to be in the stash. Needs a crafting table set. Kits never craft.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> craftMaxPerOrder = sgCrafting.add(new IntSetting.Builder()
        .name("craft-max-per-order")
        .description("The most Shulker Boxes crafted for one order.")
        .defaultValue(27)
        .min(1)
        .sliderRange(1, 54)
        .build()
    );

    private final Setting<Integer> craftMaxOpsPerTask = sgCrafting.add(new IntSetting.Builder()
        .name("craft-max-ops-per-task")
        .description("The most grid fills (ingredients put into the crafting grid) in one trip to the table. A bigger craft is done in several trips.")
        .defaultValue(128)
        .min(8)
        .sliderRange(8, 512)
        .build()
    );

    private final Setting<Integer> craftMaxPerHour = sgCrafting.add(new IntSetting.Builder()
        .name("craft-max-per-hour")
        .description("The most Shulker Boxes crafted in an hour.")
        .defaultValue(54)
        .min(1)
        .sliderRange(1, 216)
        .build()
    );

    private final Setting<List<Item>> craftNeverUse = sgCrafting.add(new ItemListSetting.Builder()
        .name("craft-never-use")
        .description("Items crafting never uses as ingredients, whatever the stash holds.")
        .defaultValue(List.of())
        .build()
    );

    private final SettingGroup sgCraftingTable = settings.createGroup("Crafting table");

    private final Setting<Boolean> selectCraftingTable = sgCraftingTable.add(new BoolSetting.Builder()
        .name("select-crafting-table")
        .description("Turn on, then right-click a crafting table to make it the table crafting is done at. Right-click the current one to clear it. It may be outside the stash area, and is never sorted or listed. Turns itself off after one click.")
        .defaultValue(false)
        .onChanged(on -> {
            if (on && Modules.get().get(StashSorter.class).currentStash() == null) {
                warning("No stash is loaded. Scan one with stash-sorter first; this uses the stash named in its settings.");
                this.selectCraftingTable.set(false);
            }
            else if (on) {
                if (this.selectDelivery.get()) this.selectDelivery.set(false);
                if (this.selectAnvil.get()) this.selectAnvil.set(false);
            }
        })
        .build()
    );

    private final Setting<Boolean> clearCraftingTable = sgCraftingTable.add(new BoolSetting.Builder()
        .name("clear-crafting-table")
        .description("Turn on to clear the crafting table. Turns itself off again.")
        .defaultValue(false)
        .onChanged(on -> {
            if (!on) return;
            this.clearCraftingTable.set(false);
            if (Modules.get().get(StashSorter.class).clearCraftingTable()) info("Crafting table cleared.");
            else info("There is no crafting table.");
        })
        .build()
    );

    private final Setting<Boolean> showCraftingTable = sgCraftingTable.add(new BoolSetting.Builder()
        .name("show-crafting-table")
        .description("Outline the crafting table while this module is on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> craftingTableColor = sgCraftingTable.add(new ColorSetting.Builder()
        .name("crafting-table-color")
        .description("The outline of the crafting table.")
        .defaultValue(new SettingColor(120, 170, 255, 90))
        .visible(showCraftingTable::get)
        .build()
    );

    private static final long HEARTBEAT_MS = 15_000;
    private static final long CLAIM_MS = 5_000, STATUS_MS = 2_000;
    private static final long REBUILD_MS = 60_000;
    private static final long BACKOFF_MIN_MS = 5_000, BACKOFF_MAX_MS = 60_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qwerte-stash-manager");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    // Everything below is client thread only
    private StashIndex.Built current;
    private long fingerprint, lastBuild;
    private int scanTicks;
    private int seenInterval = -1;
    private boolean seenInclude;
    private long lastHeartbeat, nextAttemptAt, backoff;
    private boolean warnedKey, warnedUrl;
    private boolean inFlight;
    private int generation;
    private String lastPushed;
    private boolean needIndex;
    private String status = "idle";
    private String lastLogged = "";

    // Orders
    private int pendingOrders;
    private long lastClaim, lastStatusAt;
    private String lastStatusSig = "";
    private String rejectedId, rejectedWhy, rejectedStatus = "failed";
    // The order whose full status report was refused, so its short form is sent
    private String minimalFor;
    // The owner of the stash name last warned about
    private String warnedOwner;
    private String ordersNote = "";

    // Client thread only: the delivery container's blocks, for drawing, and what the last heartbeat said about it
    private List<BlockPos> deliveryParts = List.of();
    private BlockPos anvilPart, craftingTablePart;
    private String sentDelivery = "";
    // The rescan id handled and not yet acknowledged to the server, or -1
    private long rescanDone = -1;
    // A website rescan is being carried out: the bot reads until nothing is stale or unread
    private boolean rescanning;
    // The last rescan acted on, kept over a reset so one that was not acknowledged yet is not done twice
    private long lastRescanId = -1, lastRescanAt;

    public StashManager() {
        super(QwerteAddon.CATEGORY, "stash-manager", "Sends what's in your saved stash to the Stash Manager web server on your network, which shows it. Uses the stash saved by stash-sorter. With accept-orders on, or during a website rescan, it moves the bot by itself; to stop that, turn off accept-orders or this module. Turning stash-sorter off hands control back to this module while accept-orders is on.");
        RightClick.listen(this::onRightClick);
    }

    /**
     * While selecting, the next right-click on a container sets or clears the delivery container.
     * @return true when the click is used, so the container doesn't open
     */
    private boolean onRightClick() {
        if (!selectDelivery.get() && !selectAnvil.get() && !selectCraftingTable.get()) return false;
        if (mc.player == null || mc.level == null || !mc.mouseHandler.isMouseGrabbed()) return false;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return false;

        BlockPos pos = hit.getBlockPos();
        if (selectCraftingTable.get()) {
            switch (Modules.get().get(StashSorter.class).toggleCraftingTable(pos)) {
                case -2 -> {
                    warning("No stash is loaded. Scan one with stash-sorter first.");
                    this.selectCraftingTable.set(false);
                }
                case -1 -> info("That isn't a crafting table.");
                case 0 -> {
                    info("Crafting table cleared.");
                    this.selectCraftingTable.set(false);
                }
                default -> {
                    info("Crafting table set to %d, %d, %d.", pos.getX(), pos.getY(), pos.getZ());
                    this.selectCraftingTable.set(false);
                }
            }
            return true;
        }
        if (!selectDelivery.get()) {
            switch (Modules.get().get(StashSorter.class).toggleAnvil(pos)) {
                case -2 -> {
                    warning("No stash is loaded. Scan one with stash-sorter first.");
                    this.selectAnvil.set(false);
                }
                case -1 -> info("That isn't an anvil.");
                case 0 -> {
                    info("Anvil cleared.");
                    this.selectAnvil.set(false);
                }
                default -> {
                    info("Anvil set to %d, %d, %d.", pos.getX(), pos.getY(), pos.getZ());
                    this.selectAnvil.set(false);
                }
            }
            return true;
        }
        int result = Modules.get().get(StashSorter.class).toggleDelivery(pos);
        switch (result) {
            case -2 -> {
                warning("No stash is loaded. Scan one with stash-sorter first.");
                this.selectDelivery.set(false);
            }
            case -1 -> info("That isn't a chest or barrel.");
            case 0 -> {
                info("Delivery container cleared.");
                this.selectDelivery.set(false);
            }
            default -> {
                info("Delivery container set to %d, %d, %d.", pos.getX(), pos.getY(), pos.getZ());
                this.selectDelivery.set(false);
            }
        }
        return true;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (showAnvil.get() && anvilPart != null) {
            SettingColor anvil = anvilColor.get();
            event.renderer.box(anvilPart, anvil, anvil, ShapeMode.Both, 0);
        }
        if (showCraftingTable.get() && craftingTablePart != null) {
            SettingColor table = craftingTableColor.get();
            event.renderer.box(craftingTablePart, table, table, ShapeMode.Both, 0);
        }
        if (!showDelivery.get()) return;
        SettingColor color = deliveryColor.get();
        for (BlockPos part : deliveryParts) event.renderer.box(part, color, color, ShapeMode.Both, 0);
    }

    @Override
    public void onActivate() {
        selfCheck();
        reset();
    }

    @Override
    public void onDeactivate() {
        selectDelivery.set(false);
        selectAnvil.set(false);
        selectCraftingTable.set(false);
        deliveryParts = List.of();
        anvilPart = null;
        craftingTablePart = null;
        // Nobody is left to tell the server how an order is going: park it so a later claim carries on
        StashSorter sorter = Modules.get().get(StashSorter.class);
        if (sorter != null) {
            sorter.orders().park();
            sorter.drive(false);
        }
        reset();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        reset();
    }

    private void reset() {
        generation++;
        current = null;
        fingerprint = 0;
        lastBuild = 0;
        scanTicks = 0;
        seenInterval = -1;
        lastHeartbeat = 0;
        nextAttemptAt = 0;
        backoff = 0;
        warnedKey = warnedUrl = false;
        lastPushed = null;
        needIndex = false;
        inFlight = false;
        status = "idle";
        lastLogged = "";
        pendingOrders = 0;
        lastClaim = lastStatusAt = 0;
        lastStatusSig = "";
        rejectedId = rejectedWhy = null;
        rejectedStatus = "failed";
        ordersNote = "";
        sentDelivery = "";
        rescanDone = -1;
        rescanning = false;
        warnedOwner = null;
        StashIndex.clearCache();
    }

    @Override
    public String getInfoString() {
        return ordersNote.isEmpty() ? status : status + " " + ordersNote;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null || mc.player == null) return;

        driveSorter();
        updateOrdersNote();

        String key = apiKey.get().trim();
        if (key.isEmpty()) {
            setStatus("no key");
            if (!warnedKey) {
                warnedKey = true;
                warning("No API key set. Copy it from the Stash Manager server's console into this module's api-key setting.");
            }
            return;
        }

        URI base = parse(serverUrl.get());
        if (base == null) {
            setStatus("bad url");
            if (!warnedUrl) {
                warnedUrl = true;
                warning("The server-url must be a http or https address without a query, like http://127.0.0.1:8765.");
            }
            return;
        }
        warnedUrl = false;

        // A changed setting is looked at right away
        if (seenInterval != scanInterval.get() || seenInclude != includeExcluded.get()) {
            seenInterval = scanInterval.get();
            seenInclude = includeExcluded.get();
            scanTicks = 0;
        }

        if (scanTicks-- <= 0) {
            scanTicks = seenInterval * 20;
            scan();
        }
        if (inFlight) return;
        long now = System.currentTimeMillis();
        if (now < nextAttemptAt) return;

        // Orders come first: the server should hear how the bot is doing, and a claim is cheap
        if (orderStep(base, key, now)) return;

        if (current == null) return;
        StashIndex.Built built = current;
        String id = built.server() + "|" + built.stash() + "|" + built.hash();

        if (needIndex || !id.equals(lastPushed)) {
            lastHeartbeat = now;
            send(base, "/api/v1/index", built.json(), key, (code, body) -> {
                if (code != 200) return false;
                lastPushed = id;
                needIndex = false;
                return true;
            });
        } else if (now - lastHeartbeat >= HEARTBEAT_MS || !deliverySig().equals(sentDelivery)) {
            lastHeartbeat = now;
            String sig = deliverySig();
            long done = rescanDone;
            send(base, "/api/v1/heartbeat", StashIndex.heartbeat(built, deliveryPos(), orderBlocker(), done, rescanBlocked(), namingInfo(), craftingInfo(), anvilSource()), key, (code, body) -> {
                if (code != 200) return false;
                sentDelivery = sig;
                if (done >= 0 && rescanDone == done) rescanDone = -1;
                readRescan(body);
                needIndex = StashIndex.needsIndex(body);
                readOrders(body);
                return true;
            });
        }
    }

    /**
     * Reports the order to the server, or claims a new one. Cancels asked for by the server are passed on to the sorter.
     * @return true when a request was sent
     */
    private boolean orderStep(URI base, String key, long now) {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        if (sorter == null) return false;
        OrderRunner runner = sorter.orders();
        runner.chat(orderChat.get());

        Stash stash = sorter.currentStash();
        String server = stash == null ? "" : stash.server;
        String name = stash == null ? "" : stash.name;

        // An order the sorter could not take
        if (rejectedId != null) {
            String id = rejectedId, why = rejectedWhy, end = rejectedStatus;
            JsonObject o = new JsonObject();
            o.addProperty("status", end);
            o.addProperty("message", StashIndex.clamp(why, 300));
            send(base, "/api/v1/orders/" + id + "/status", bytes(o), key, (code, body) -> {
                // 409: the server already has it finished; another refusal is not tried again
                if (code >= 400 && code < 500 && code != 401 && code != 409) QwerteAddon.LOG.warn("stash-manager: the server refused the status of order {} ({}): {}", id, code, body);
                else if (code != 200 && code != 409) return false;
                if (rejectedId != null && rejectedId.equals(id)) rejectedId = rejectedWhy = null;
                if (end.equals("cancelled")) runner.dropSaved(null);
                return true;
            });
            return true;
        }

        // Taking orders was switched off with one in hand: it is parked, and resumed when it is switched on again
        if (!acceptOrders.get() && runner.running()) runner.park();

        OrderRunner.Report report = runner.report();
        String blocker = acceptOrders.get() ? sorter.orderBlocker() : "off";

        // What the bot is doing, when it changed
        if (report != null) {
            String sig = report.id() + "|" + report.status() + "|" + report.phase() + "|" + report.done() + "|" + report.message();
            if (!sig.equals(lastStatusSig) && (report.terminal() || now - lastStatusAt >= STATUS_MS)) {
                lastStatusAt = now;
                boolean minimal = report.id().equals(minimalFor);
                send(base, "/api/v1/orders/" + report.id() + "/status", bytes(minimal ? minimalJson(report) : statusJson(report, runner)), key, (code, body) -> {
                    // A report the server refuses (4xx other than a wrong key or "already finished"): said again in short once,
                    // then given up on, so the bot is never stuck on one it can't send
                    if (code >= 400 && code < 500 && code != 401 && code != 409) {
                        QwerteAddon.LOG.warn("stash-manager: the server refused the status of order {} ({}): {}", report.id(), code, body);
                        if (!minimal) {
                            minimalFor = report.id();
                            return false;
                        }
                        minimalFor = null;
                        if (report.terminal()) {
                            runner.ack(report.id());
                            lastStatusSig = "";
                        } else lastStatusSig = sig;
                        return true;
                    }
                    // 409: the order is already finished or moved on, nothing more to say about it
                    if (code != 200 && code != 409) return false;
                    minimalFor = null;
                    lastStatusSig = sig;
                    if (report.terminal()) {
                        runner.ack(report.id());
                        lastStatusSig = "";
                    }
                    return true;
                });
                return true;
            }
            return false;
        }

        // Nothing in hand: take the oldest pending order, or the one still held from before a restart
        if (acceptOrders.get() && blocker == null && stash != null && now - lastClaim >= CLAIM_MS && (pendingOrders > 0 || runner.resumeId() != null)) {
            lastClaim = now;
            JsonObject o = new JsonObject();
            o.addProperty("server", server);
            o.addProperty("stash", name);
            send(base, "/api/v1/orders/claim", bytes(o), key, (code, body) -> {
                if (code == 204) {
                    pendingOrders = 0;
                    runner.dropSaved(null);
                    return true;
                }
                if (code != 200) return false;
                takeOrder(sorter, runner, body);
                return true;
            });
            return true;
        }
        return false;
    }

    private int[] deliveryPos() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        Stash.Box d = sorter == null ? null : sorter.orders().deliveryBox();
        return d == null ? null : new int[] { d.pos.getX(), d.pos.getY(), d.pos.getZ() };
    }

    /** What the heartbeat says about naming: whether an anvil is ready, and the bot's XP level (-1: unknown). */
    private int[] namingInfo() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        if (sorter == null) return new int[] { 0, -1 };
        return new int[] { sorter.anvilReady() ? 1 : 0, sorter.xpLevels() };
    }

    /** Where the anvil in use came from, for the heartbeat: "set", "found", "placed" or "none". */
    private String anvilSource() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        return sorter == null ? "none" : sorter.anvilSource();
    }

    /** What the heartbeat says about crafting: whether it is on, and whether a crafting table is ready. */
    private int[] craftingInfo() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        return new int[] { enableCrafting.get() ? 1 : 0, sorter != null && sorter.craftingTableReady() ? 1 : 0 };
    }

    /** Why a rescan can't start yet, or null. */
    private String rescanBlocked() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        return sorter == null || sorter.currentStash() == null ? "no stash" : null;
    }

    /** The website asked for a rescan: every container is marked to be read again, and the server is told at once. */
    private void readRescan(String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            if (!e.isJsonObject() || !e.getAsJsonObject().has("rescan") || e.getAsJsonObject().get("rescan").isJsonNull()) return;
            long id = e.getAsJsonObject().get("rescan").getAsLong();
            // The server sends an id until it has our rescanDone, so one handled and waiting to be acknowledged is not done twice
            if (id == rescanDone) return;
            boolean duplicate = id == lastRescanId && System.currentTimeMillis() - lastRescanAt < 120_000;
            rescanDone = id;
            if (duplicate) return;
            lastRescanId = id;
            lastRescanAt = System.currentTimeMillis();

            StashSorter sorter = Modules.get().get(StashSorter.class);
            int count = sorter == null ? -1 : sorter.rescan();
            rescanning = count >= 0;
            if (count < 0) warning("Rescan requested from the website, but no stash is loaded.");
            else info("Rescan requested from the website: re-reading %d containers.", count);
        } catch (RuntimeException ex) {
            QwerteAddon.LOG.warn("stash-manager: couldn't read the rescan request", ex);
        }
    }

    /** Why the bot can't take orders right now, or null. */
    private String orderBlocker() {
        if (!acceptOrders.get()) return "accept-orders off";
        StashSorter sorter = Modules.get().get(StashSorter.class);
        return sorter == null ? "sorter off" : sorter.orderBlocker();
    }

    /** What the heartbeat says about delivery; when it changes, another heartbeat goes out at once. */
    private String deliverySig() {
        int[] d = deliveryPos();
        return (d == null ? "none" : d[0] + "," + d[1] + "," + d[2]) + "|" + (namingInfo()[0]) + anvilSource() + "|" + craftingInfo()[0] + craftingInfo()[1] + "|" + orderBlocker() + "|" + rescanDone + "|" + rescanBlocked();
    }

    /** The module is on and taking orders. */
    boolean takingOrders() {
        return isActive() && acceptOrders.get();
    }

    int freshMinutes() {
        return freshMinutes.get();
    }

    boolean useNamedShulkers() {
        return useNamedShulkers.get();
    }

    /** enable-crafting. */
    boolean craftingEnabled() {
        return enableCrafting.get();
    }

    /** The items crafting never uses, as ids like minecraft:beacon. */
    Set<String> craftNeverUse() {
        Set<String> out = new HashSet<>();
        for (Item item : craftNeverUse.get()) out.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString());
        return out;
    }

    com.porter.qwerte.craft.CraftPlanner.Caps craftCaps() {
        return new com.porter.qwerte.craft.CraftPlanner.Caps(craftMaxPerOrder.get(), craftMaxOpsPerTask.get(), craftMaxPerHour.get());
    }

    /** Starts or stops the sorter's loop for this module: when it takes orders, or containers wait to be read. */
    private void driveSorter() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        if (sorter == null) return;
        // The bot walks only when asked: orders are accepted, or a website rescan is under way. Containers a player opened
        // are not read by the bot on their own
        if (rescanning && !sorter.needsReading()) rescanning = false;
        boolean want = isActive() && sorter.currentStash() != null && (acceptOrders.get() || rescanning);
        sorter.drive(want);
    }

    /** The order progress, or why no order is taken, for the info string. Every tick. */
    private void updateOrdersNote() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        if (sorter == null) {
            ordersNote = "";
            return;
        }
        OrderRunner runner = sorter.orders();
        Stash.Box delivery = runner.deliveryBox();
        deliveryParts = delivery == null ? List.of() : List.copyOf(delivery.parts);
        anvilPart = sorter.anvilPos();
        craftingTablePart = sorter.craftingTablePos();

        String info = runner.info();
        if (sorter.returningLeftovers()) {
            ordersNote = "(returning leftovers)";
            return;
        }
        if (acceptOrders.get() && info != null && runner.running()) {
            ordersNote = "(" + info + ")";
            return;
        }
        int stale = sorter.staleCount();
        if (stale > 0 && (sorter.driving() || sorter.isActive())) {
            ordersNote = "(re-reading " + stale + ")";
            return;
        }
        String blocker = acceptOrders.get() ? sorter.orderBlocker() : null;
        if (blocker != null && !blocker.equals("busy") && !blocker.equals("no delivery box")) ordersNote = "(" + blocker + ")";
        else if (acceptOrders.get() && info != null) ordersNote = "(" + info + ")";
        else if (sorter.driving() && delivery != null) ordersNote = "(idle, delivery " + delivery.pos.getX() + "," + delivery.pos.getY() + "," + delivery.pos.getZ() + ")";
        else ordersNote = delivery == null ? "(no delivery box)" : "(delivery " + delivery.pos.getX() + "," + delivery.pos.getY() + "," + delivery.pos.getZ() + ")";
    }

    private void takeOrder(StashSorter sorter, OrderRunner runner, String body) {
        Order order = null;
        try {
            JsonElement e = JsonParser.parseString(body);
            if (e.isJsonObject() && e.getAsJsonObject().has("order")) order = Order.parse(e.getAsJsonObject().getAsJsonObject("order"));
        } catch (RuntimeException e) {
            // Treated as no usable order below
        }
        if (order == null) {
            // Left alone it would be handed to us again forever: say it failed, if there is an id to say it with
            String id = null;
            try {
                JsonElement e = JsonParser.parseString(body);
                if (e.isJsonObject() && e.getAsJsonObject().has("order")) {
                    JsonObject o = e.getAsJsonObject().getAsJsonObject("order");
                    if (o.has("id")) id = o.get("id").getAsString();
                }
            } catch (RuntimeException e) {
                // No id to report
            }
            QwerteAddon.LOG.warn("stash-manager: the server sent an order that could not be read ({})", id);
            if (id != null && !id.isEmpty()) {
                rejectedId = id;
                rejectedWhy = "couldn't read order";
                rejectedStatus = "failed";
            }
            return;
        }

        runner.dropSaved(order.id);
        String why = runner.offer(order);
        if (why == null) {
            sorter.orderTaken();
            pendingOrders = Math.max(0, pendingOrders - 1);
        } else {
            rejectedId = order.id;
            rejectedWhy = why;
            rejectedStatus = "failed";
            QwerteAddon.LOG.warn("stash-manager: rejected order {}: {}", order.id, why);
            if (orderChat.get()) warning("Order %s can't be done: %s.", order.id, why);
        }
    }

    /** The orders part of a heartbeat reply: how many wait, and which of ours were cancelled. */
    private void readOrders(String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            if (!e.isJsonObject() || !e.getAsJsonObject().has("orders")) {
                pendingOrders = 0;
                return;
            }
            JsonObject orders = e.getAsJsonObject().getAsJsonObject("orders");
            pendingOrders = orders.has("pending") ? orders.get("pending").getAsInt() : 0;
            StashSorter sorter = Modules.get().get(StashSorter.class);
            if (sorter != null && orders.has("cancel") && orders.get("cancel").isJsonArray()) {
                for (JsonElement id : orders.getAsJsonArray("cancel")) {
                    String cancelled = id.getAsString();
                    // An order finished here whose report never got through: it is sent again now
                    if (!sorter.orders().running() && sorter.orders().holdsOrder()) {
                        lastStatusSig = "";
                        nextAttemptAt = 0;
                    }
                    // Not held here, but saved from before a restart: say it is cancelled and forget it
                    if (!sorter.orderCancel(cancelled) && cancelled.equals(sorter.orders().resumeId()) && rejectedId == null) {
                        rejectedId = cancelled;
                        rejectedWhy = "cancelled";
                        rejectedStatus = "cancelled";
                    }
                }
            }
        } catch (RuntimeException ex) {
            pendingOrders = 0;
        }
    }

    private static JsonObject statusJson(OrderRunner.Report r, OrderRunner runner) {
        JsonObject o = new JsonObject();
        o.addProperty("status", r.status());
        o.addProperty("phase", StashIndex.clamp(r.phase(), 100));
        JsonObject progress = new JsonObject();
        progress.addProperty("done", r.done());
        progress.addProperty("total", r.total());
        o.add("progress", progress);
        o.addProperty("message", StashIndex.clamp(r.message(), 300));
        if (r.terminal() && !r.status().equals("cancelled")) {
            JsonObject result = new JsonObject();
            result.addProperty("shulkers", r.shulkers());
            JsonArray lines = new JsonArray();
            for (String key : runner.requested().keySet()) {
                // The server takes at most 400 lines with keys up to 256 characters
                if (lines.size() >= 400 || key.length() > 256) continue;
                JsonObject line = new JsonObject();
                line.addProperty("key", key);
                line.addProperty("delivered", r.delivered().getOrDefault(key, 0));
                lines.add(line);
            }
            result.add("lines", lines);
            o.add("result", result);
        }
        return o;
    }

    /** Only the status and a short message, for a report the server would not take in full. */
    private static JsonObject minimalJson(OrderRunner.Report r) {
        JsonObject o = new JsonObject();
        o.addProperty("status", r.status());
        o.addProperty("message", StashIndex.clamp(r.message(), 100));
        return o;
    }

    private byte[] bytes(JsonObject o) {
        // Every order request says which bot it comes from: the server only takes them from the stash's owner
        if (!o.has("player") && mc.player != null) o.addProperty("player", mc.player.getName().getString());
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Looks at the stash and rebuilds the index only if something changed, or it's been a while. */
    private void scan() {
        Stash stash = null;
        StashSorter sorter = Modules.get().get(StashSorter.class);
        if (sorter != null) stash = sorter.currentStash();
        if (stash == null) {
            current = null;
            setStatus("no stash");
            return;
        }

        try {
            long fp = StashIndex.fingerprint(stash, seenInclude);
            long now = System.currentTimeMillis();
            if (current != null && fp == fingerprint && now - lastBuild < REBUILD_MS) return;

            StashIndex.Built built = StashIndex.build(stash, seenInclude);
            if (built == null) return;
            current = built;
            fingerprint = fp;
            lastBuild = now;
            if (status.equals("no stash") || status.equals("index error")) status = "idle";
        } catch (Throwable t) {
            current = null;
            if (!status.equals("index error")) QwerteAddon.LOG.error("stash-manager: couldn't build the index", t);
            setStatus("index error");
        }
    }

    private static URI parse(String url) {
        try {
            String s = url.trim();
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            URI uri = URI.create(s);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** What to do with the reply, on the client thread. {@code code} is -1 when the server could not be reached. */
    private interface Reply {
        /** @return false when it failed and should be tried again later */
        boolean done(int code, String body);
    }

    private void send(URI base, String path, byte[] body, String key, Reply reply) {
        URI uri;
        try {
            uri = URI.create(base + path);
        } catch (IllegalArgumentException e) {
            setStatus("bad url");
            return;
        }

        // The key itself never goes on the wire: the request is signed with a key derived from it (see PROTOCOL.md)
        byte[] signingKey = signingKey(key);
        String nonce = randomHex(16);
        long ts = System.currentTimeMillis();
        String pathQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String sig = requestSignature(signingKey, "POST", pathQuery, ts, nonce, body);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("X-SM-Auth", "v1 - " + ts + " " + nonce + " " + sig)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        } catch (IllegalArgumentException e) {
            setStatus("bad url");
            return;
        }

        inFlight = true;
        int gen = generation;

        executor.execute(() -> {
            int code;
            String text = "";
            boolean badSignature = false;
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                code = response.statusCode();
                byte[] bytes = response.body() == null ? new byte[0] : response.body();
                // The signature covers the body as it is without compression
                if (response.headers().firstValue("Content-Encoding").map(v -> v.toLowerCase().contains("gzip")).orElse(false) && bytes.length > 0) {
                    try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
                        bytes = in.readAllBytes();
                    } catch (java.io.IOException e) {
                        badSignature = true;
                    }
                }
                text = new String(bytes, StandardCharsets.UTF_8);
                // Everything but a refusal before the key was checked (401, 403) or a server error must be signed by the server
                // for this request. Anything else is not acted on
                boolean unsignedOk = code == 401 || code == 403 || code >= 500;
                if (!unsignedOk && !badSignature) {
                    badSignature = !responseSignatureValid(signingKey, nonce, code, response.headers().firstValue("X-SM-Sig").orElse(""), bytes);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                code = -1;
            }

            int finalCode = code;
            String finalText = text;
            boolean finalBad = badSignature;
            mc.execute(() -> {
                if (gen != generation) return;
                inFlight = false;

                // A reply that is not signed by the server for this request is thrown away: no orders, rescans or cancels in it
                if (finalBad) {
                    backoff = backoff == 0 ? BACKOFF_MIN_MS : Math.min(BACKOFF_MAX_MS, backoff * 2);
                    nextAttemptAt = System.currentTimeMillis() + backoff;
                    setStatus("server signature invalid");
                    return;
                }

                // Another bot owns this stash name (not the 409 of an order that is already finished): stop for a minute, then
                // try again, so a release on the website takes effect
                String owner = stashOwner(finalCode, finalText);
                if (owner != null) {
                    backoff = 0;
                    nextAttemptAt = System.currentTimeMillis() + 60_000;
                    if (!owner.equals(warnedOwner)) {
                        warnedOwner = owner;
                        StashSorter sorter = Modules.get().get(StashSorter.class);
                        Stash stash = sorter == null ? null : sorter.currentStash();
                        warning("Stash name '%s' is already used by %s on this server. Pick another stash name in stash-sorter's stash-name setting (each bot needs its own).", stash == null ? "?" : stash.name, owner);
                    }
                    setStatus("stash name taken by " + owner);
                    return;
                }

                boolean ok;
                try {
                    ok = reply.done(finalCode, finalText);
                } catch (RuntimeException e) {
                    QwerteAddon.LOG.warn("stash-manager: couldn't handle the reply", e);
                    ok = false;
                }
                if (ok) {
                    warnedOwner = null;
                    backoff = 0;
                    nextAttemptAt = 0;
                } else {
                    backoff = backoff == 0 ? BACKOFF_MIN_MS : Math.min(BACKOFF_MAX_MS, backoff * 2);
                    nextAttemptAt = System.currentTimeMillis() + backoff;
                }
                setStatus(ok ? "online" : finalCode < 0 ? "offline" : "error " + finalCode);
            });
        });
    }

    // Signed requests

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        new java.security.SecureRandom().nextBytes(random);
        return hex(random);
    }

    /** K = HMAC-SHA256(key = the API key, message = "stash-manager v1"). */
    private static byte[] signingKey(String apiKey) {
        return hmac(apiKey.getBytes(StandardCharsets.UTF_8), "stash-manager v1".getBytes(StandardCharsets.UTF_8));
    }

    private static String requestSignature(byte[] signingKey, String method, String pathQuery, long ts, String nonce, byte[] body) {
        String message = method + "\n" + pathQuery + "\n" + ts + "\n" + nonce + "\n" + sha256Hex(body);
        return hex(hmac(signingKey, message.getBytes(StandardCharsets.UTF_8)));
    }

    /** The server's signature on a reply: bound to our nonce, within 60 seconds, compared in constant time. */
    private static boolean responseSignatureValid(byte[] signingKey, String nonce, int status, String header, byte[] body) {
        String[] parts = header.trim().split(" ");
        if (parts.length != 3 || !parts[0].equals("v1")) return false;
        try {
            long ts = Long.parseLong(parts[1]);
            if (Math.abs(System.currentTimeMillis() - ts) > 60_000) return false;
            String message = "resp\n" + nonce + "\n" + status + "\n" + ts + "\n" + sha256Hex(body);
            byte[] expected = hex(hmac(signingKey, message.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
            return java.security.MessageDigest.isEqual(expected, parts[2].toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The fixed vector of PROTOCOL.md (computed here until the file has it), checked when the module turns on. */
    private static void selfCheck() {
        String got = requestSignature(signingKey("test-key"), "POST", "/api/v1/heartbeat", 1700000000000L, "00112233445566778899aabbccddeeff",
            "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        String expected = "7d2f41009d9f0c6c61216ee00811bc643c646652c3d96b36fb89df09bc9356f1";
        if (got.equals(expected)) QwerteAddon.LOG.info("stash-manager: request signing self-check passed");
        else QwerteAddon.LOG.error("stash-manager: request signing self-check FAILED: got {}, expected {}", got, expected);
    }

    /** The owner named in a "stash name in use" refusal, or null if the reply is not one. */
    private static String stashOwner(int code, String body) {
        if (code != 409) return null;
        try {
            JsonElement e = JsonParser.parseString(body);
            if (!e.isJsonObject()) return null;
            JsonObject o = e.getAsJsonObject();
            if (!o.has("error") || !"stash name in use".equals(o.get("error").getAsString())) return null;
            return o.has("owner") && !o.get("owner").isJsonNull() ? o.get("owner").getAsString() : "another player";
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Sets the status and, only when it changes, logs it and tells the user about a rejected key. */
    private void setStatus(String next) {
        status = next;
        if (next.equals(lastLogged)) return;
        lastLogged = next;

        if (next.equals("error 401")) warning("API key rejected by the server. Check the api-key setting.");
        if (next.equals("server signature invalid")) error("The server's reply was not signed with your API key, so it was ignored. Check the api-key and server-url settings.");
        if (next.equals("online") || next.equals("idle")) QwerteAddon.LOG.info("stash-manager: {}", next);
        else QwerteAddon.LOG.warn("stash-manager: {}", next);
    }
}

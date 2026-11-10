package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.compat.GameVersion;
import com.porter.qwerte.craft.ClaimWait;
import com.porter.qwerte.craft.RecipeBoot;
import com.porter.qwerte.craft.RecipeHttp;
import com.porter.qwerte.craft.RecipeStore;
import com.porter.qwerte.craft.RecipeSync;
import com.porter.qwerte.craft.Recipes;
import com.porter.qwerte.stash.CraftSettings;
import com.porter.qwerte.stash.CraftStore;
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
        .description("Address of the Stash Manager server, http or https, e.g. http://127.0.0.1:8765. Empty: no server, nothing is sent.")
        .defaultValue("")
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

    private final Setting<Boolean> autoExpandDelivery = sgDelivery.add(new BoolSetting.Builder()
        .name("auto-expand-delivery")
        .description("When every chest of the delivery storage is full, put another double chest up one block above the highest one (taken from the stash, or crafted when enable-crafting is on) and keep filling the lowest chest with room. A chest a player emptied is used again. Off: an order waits for space (paused for up to 2 hours, then it ends). It goes up for as long as the new chests could be opened and the bot can reach them: from the floor the first row above the delivery container, higher ones need something to stand on beside the stack. The chests are never sorted into or listed.")
        .defaultValue(false)
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
        .description("Orders and compaction may craft a plain Shulker Box when no empty one is left: shulker shells and a chest (made of planks, made of logs) are taken from the stash and crafted at the crafting table below. Shulker shells can't be crafted, so they have to be in the stash. Needs a crafting table: the one set below, else one is found in the stash area, taken from the stash and placed, or crafted in the inventory grid from 4 planks (or logs) and placed. Kits never craft. Needs the recipe table: nothing is shipped, it is fetched from the Stash Manager server (server-url and api-key) or, without one, built from the game's own data; until it is there nothing is crafted.")
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

    private final Setting<Boolean> craftOrders = sgCrafting.add(new BoolSetting.Builder()
        .name("craft-orders")
        .description("Take orders from the website that ask for items the stash can make from what it holds (crafting from the stash). The bot takes the ingredients from the stash, crafts them at the crafting table the website set for this stash, and delivers the results with the order. Needs enable-crafting and a crafting table set on the website (stash settings). Off: such orders are never given to this bot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> craftOrderMaxFills = sgCrafting.add(new IntSetting.Builder()
        .name("craft-order-max-fills")
        .description("The most grid fills (one craft of one recipe) the bot makes for one craft order. An order that needs more is refused when it is placed.")
        .defaultValue(2048)
        .min(1)
        .max(100000)
        .sliderRange(1, 8192)
        .build()
    );

    private final Setting<Integer> craftOrderMaxPerHour = sgCrafting.add(new IntSetting.Builder()
        .name("craft-order-max-per-hour")
        .description("The most grid fills the bot makes for craft orders in an hour. Past it, the rest of a craft order's items are left uncrafted (reported as short, reason cap) and the order carries on.")
        .defaultValue(4096)
        .min(1)
        .sliderRange(1, 16384)
        .build()
    );

    private final SettingGroup sgCraftingTable = settings.createGroup("Crafting table");

    private final Setting<Boolean> selectCraftingTable = sgCraftingTable.add(new BoolSetting.Builder()
        .name("select-crafting-table")
        .description("Turn on, then right-click a crafting table to make it the table crafting is done at (without one, the bot finds, places or crafts one itself). Right-click the current one to clear it. It may be outside the stash area, and is never sorted or listed. Turns itself off after one click.")
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
            StashSorter sorter = Modules.get().get(StashSorter.class);
            if (sorter.websiteTableInUse()) info("A craft order is using a crafting table right now. Try again when it is done.");
            else if (sorter.clearCraftingTable()) info("Crafting table cleared.");
            else info("There is no crafting table.");
        })
        .build()
    );

    private final Setting<Boolean> showCraftingTable = sgCraftingTable.add(new BoolSetting.Builder()
        .name("show-crafting-table")
        .description("Outline the crafting table while this module is on. The chat says whether it was set, found, placed or crafted.")
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

    private static final int MAX_REPLY_BYTES = 4 * 1024 * 1024;
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
    // The delivery contents report (0.4.0-dev.7): when to build and send it, the last stamp of the delivery chests, the hash of the
    // last one built (the heartbeat's deliveryHash) and the one built and not yet taken
    private final com.porter.qwerte.stash.DeliveryContents.Sync deliverySync = new com.porter.qwerte.stash.DeliveryContents.Sync();
    private final com.porter.qwerte.stash.DeliveryContents.SentLog deliveryLog = new com.porter.qwerte.stash.DeliveryContents.SentLog();
    private long deliveryStamp;
    private String deliveryFor = "", deliveryHash;
    private com.porter.qwerte.stash.DeliveryContents.Built deliveryPending;
    // Kit sorting (0.4.0-dev.14, KIT-CONTRACT.md): the report as last built (every few seconds), what the server last said about it, and what was sent
    private com.porter.qwerte.stash.KitReport.Built kitReport;
    private long kitCheckedAt, kitAttemptAt;
    private boolean kitReportKnown, kitAsked;
    // What the report was last built from: it is built again only when this changes (no rescoring of every shulker every few seconds)
    private long kitBuiltSig, kitBuiltAt;
    private boolean kitBuiltValid;
    private String kitSentHash, kitRejectedHash, kitInvalidSaid;
    // The recipe table (0.4.0-dev.18, CRAFT-CONTRACT.md "Recipe tables for the bots"): nothing is shipped. It is cached in meteor-client/qwerte, fetched
    // from the server (signed GET /api/v1/bot/recipes) when it has none or the heartbeat reply's recipesHash differs, and built from the game's own
    // jar when there is no server or it has none
    private final RecipeSync recipeSync = new RecipeSync();
    private final ExecutorService recipeIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qwerte-stash-manager-recipes");
        t.setDaemon(true);
        return t;
    });
    private boolean recipeSaid, fetchFailed, craftWarned;
    private String craftInvalidSaid, gameVersion;
    // What a 204 to a claim means for an order held with craft lines
    private final ClaimWait claimWait = new ClaimWait();
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
                case -3 -> {
                    info("A craft order is using a crafting table right now. Try again when it is done.");
                    this.selectCraftingTable.set(false);
                }
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
            case -3 -> info("That spot is not loaded and more than 128 blocks away, so it is not made the delivery container.");
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
    public net.minecraft.nbt.CompoundTag toTag() {
        net.minecraft.nbt.CompoundTag tag = super.toTag();
        if (tag != null) tag.putBoolean(URL_DEFAULT_MARKER, true);
        return tag;
    }

    @Override
    public Module fromTag(net.minecraft.nbt.CompoundTag tag) {
        Module result = super.fromTag(tag);
        // Before 0.4.0-dev.8 server-url defaulted to OLD_DEFAULT_URL, and Meteor saves only settings that differ from their default.
        // A config saved by an older version, set up (api-key set) but on the old default, has no server-url saved: keep it working.
        if (OldDefaultUrl.shouldRestore(tag.contains(URL_DEFAULT_MARKER), savedSetting(tag, "server-url"), apiKey.get())) {
            serverUrl.set(OLD_DEFAULT_URL);
        }
        return result;
    }

    static final String OLD_DEFAULT_URL = "http://127.0.0.1:8765";
    private static final String URL_DEFAULT_MARKER = "qwerteUrlDefaultBlank";

    /** Whether the module's saved settings hold this setting (Meteor's layout: settings.groups[].settings[].name). */
    private static boolean savedSetting(net.minecraft.nbt.CompoundTag tag, String name) {
        if (!(tag.get("settings") instanceof net.minecraft.nbt.CompoundTag settings)) return false;
        if (!(settings.get("groups") instanceof net.minecraft.nbt.ListTag groups)) return false;
        for (net.minecraft.nbt.Tag g : groups) {
            if (!(g instanceof net.minecraft.nbt.CompoundTag group)) continue;
            if (!(group.get("settings") instanceof net.minecraft.nbt.ListTag list)) continue;
            for (net.minecraft.nbt.Tag t : list) {
                if (!(t instanceof net.minecraft.nbt.CompoundTag setting)) continue;
                String n = net.minecraft.nbt.NbtOps.INSTANCE.getStringValue(setting.get("name") == null ? net.minecraft.nbt.StringTag.valueOf("") : setting.get("name")).result().orElse("");
                if (name.equals(n)) return true;
            }
        }
        return false;
    }

    /** The pure rule of the one-off migration of the old server-url default. */
    static final class OldDefaultUrl {
        private OldDefaultUrl() {}

        static boolean shouldRestore(boolean savedByNewVersion, boolean urlSaved, String apiKey) {
            return !savedByNewVersion && !urlSaved && apiKey != null && !apiKey.isBlank();
        }
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
        // Crafting without this module may still need the game's own data for the recipe table
        RecipeBoot.allowLocal(true);
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
        resetDelivery();
        resetKits();
        rescanDone = -1;
        rescanning = false;
        warnedOwner = null;
        recipeSync.reset();
        fetchFailed = false;
        craftWarned = false;
        claimWait.other();
        craftInvalidSaid = null;
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
        // The recipe table is kept whether or not a server is set: the game's own data stands in when there is none
        recipeTick();

        // No server set (the default since 0.4.0-dev.8): the addon works without one, so this is a status, not a warning
        if (serverUrl.get().isBlank()) {
            setStatus("server URL not set");
            return;
        }

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
        updateDelivery(now, built.player());
        refreshKits(now, built);

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
            // The reply is for the stash this heartbeat was sent for
            String sentArea = areaKeyNow();
            send(base, "/api/v1/heartbeat", StashIndex.heartbeat(built, deliveryPos(), orderBlocker(), done, rescanBlocked(), namingInfo(), craftingInfo(), anvilSource(), deliveryStorage(), tableSource(), deliveryHash, kitsBody(built), craftBody(), areaBody()), key, (code, body) -> {
                if (code != 200) return false;
                sentDelivery = sig;
                if (done >= 0 && rescanDone == done) rescanDone = -1;
                readRescan(body);
                needIndex = StashIndex.needsIndex(body);
                deliverySync.onHeartbeatReply(body);
                kitsReplied(built, body);
                craftReplied(built, body);
                areaReplied(body, sentArea);
                readOrders(body);
                return true;
            });
        } else if (deliveryPending != null && deliverySync.canSend(now)) {
            sendDelivery(base, key, now);
        } else if (kitReportDue(built, now)) {
            // Last in line: the index, the heartbeat, the orders and the delivery report all go first
            sendKits(base, key, kitReport, now);
        }
    }

    // Kit sorting (0.4.0-dev.14, KIT-CONTRACT.md)

    private void resetKits() {
        kitReport = null;
        kitCheckedAt = kitAttemptAt = 0;
        kitReportKnown = kitAsked = false;
        kitBuiltValid = false;
        kitSentHash = kitRejectedHash = kitInvalidSaid = null;
    }

    private boolean kitSorting() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        return sorter != null && sorter.kitSorting();
    }

    /** The kit fields of the heartbeat, or null with kit sorting off (nothing about kits is said then). */
    private StashIndex.KitsHashes kitsBody(StashIndex.Built built) {
        if (!kitSorting()) return null;
        com.porter.qwerte.stash.KitSettings cached = com.porter.qwerte.stash.KitStore.shared().get(built.server(), built.stash());
        com.porter.qwerte.stash.KitReport.Built report = kitReport;
        // v2: the cached v2 hash (null with no cache or no types: the server then sends the settings), and what this bot can do with kits
        return new StashIndex.KitsHashes(cached == null ? null : cached.hash(), report == null ? null : report.hash(),
            cached == null || cached.types() == null ? null : cached.types().hash(), StashIndex.KitOrdersCap.IMPORT_ONLY);
    }

    /** What a heartbeat reply says about kits: new settings are checked and cached (the sorter notices and plans again); the report is asked for or not. */
    private void kitsReplied(StashIndex.Built built, String body) {
        if (!kitSorting()) return;
        StashIndex.KitsReply reply = StashIndex.kitsReply(body);
        // Only a server that answers with a needKitsReport key (true or false) knows the report route
        kitReportKnown = reply.reportKnown();
        kitAsked = reply.reportKnown() && reply.needReport();
        if (reply.invalid() != null) {
            if (!reply.invalid().equals(kitInvalidSaid)) QwerteAddon.LOG.warn("stash-manager: ignoring kit settings from the server: {}.", reply.invalid());
            kitInvalidSaid = reply.invalid();
        } else if (reply.settings() != null) {
            kitInvalidSaid = null;
            if (com.porter.qwerte.stash.KitStore.shared().put(built.server(), built.stash(), reply.settings())) {
                QwerteAddon.LOG.info("stash-manager: kit settings updated (revision {}, {} rules{}).", reply.settings().rev(), reply.settings().rules().size(),
                    reply.settings().kitsChest() == null ? "" : ", a kits chest");
            }
        }
    }

    /** Keeps the kit report current: rebuilt every few seconds from the stash as it is and what the bot carries. Cheap (containers and shulker names only). */
    private void refreshKits(long now, StashIndex.Built built) {
        if (!kitSorting()) {
            kitReport = null;
            kitBuiltValid = false;
            return;
        }
        if (now - kitCheckedAt < 5000) return;
        kitCheckedAt = now;
        StashSorter sorter = Modules.get().get(StashSorter.class);
        Stash stash = sorter == null ? null : sorter.currentStash();
        // The server needs a player name in the report
        if (stash == null || mc.player == null || mc.level == null || built.player().isEmpty()) {
            kitReport = null;
            kitBuiltValid = false;
            return;
        }
        try {
            List<net.minecraft.world.item.ItemStack> inv = new java.util.ArrayList<>(36);
            for (int slot = 0; slot < 36; slot++) inv.add(mc.player.getInventory().getItem(slot));
            long sig = kitSignature(stash, built, inv);
            // Built again when something it shows changed, and at least once a minute
            if (kitBuiltValid && kitReport != null && sig == kitBuiltSig && now - kitBuiltAt < 60_000) return;
            kitBuiltSig = sig;
            kitBuiltAt = now;
            kitBuiltValid = true;
            com.porter.qwerte.stash.KitSettings cached = com.porter.qwerte.stash.KitStore.shared().get(built.server(), built.stash());
            kitReport = StashIndex.kitReportV2(stash, includeExcluded.get(), built.player(), inv, cached, sorter == null ? null : sorter::buildingKit);
        } catch (RuntimeException e) {
            QwerteAddon.LOG.debug("stash-manager: couldn't build the kit report: {}", e.toString());
            kitReport = null;
        }
    }

    private static long mixKit(long h, long v) {
        return (h ^ v) * 0x100000001b3L + 0x9e3779b97f4a7c15L;
    }

    /**
     * A cheap number that changes when the kit report would: the stash contents (the manager's own fingerprint), the containers' labels,
     * flags and read times, the kit settings (their version), the shulkers carried and whether excluded containers are listed. Not sent.
     */
    private long kitSignature(Stash stash, StashIndex.Built built, List<net.minecraft.world.item.ItemStack> inventory) {
        long h = mixKit(fingerprint, includeExcluded.get() ? 1 : 0);
        h = mixKit(h, com.porter.qwerte.stash.KitStore.shared().version());
        h = mixKit(h, built.server().hashCode());
        h = mixKit(h, built.stash().hashCode());
        h = mixKit(h, built.player().hashCode());
        for (Stash.Box box : stash.boxes) {
            h = mixKit(h, box.lastRead);
            h = mixKit(h, box.labels.hashCode());
            h = mixKit(h, (box.known() ? 1 : 0) | (box.excluded ? 2 : 0) | (box.dropOff ? 4 : 0) | (box.delivery ? 8 : 0) | (box.unreachable ? 16 : 0) | (box.foreign ? 32 : 0));
            h = mixKit(h, box.parts.size() * 100L + (box.known() ? box.contents.length : 0));
        }
        for (net.minecraft.world.item.ItemStack stack : inventory) {
            if (stack.isEmpty() || !meteordevelopment.meteorclient.utils.Utils.isShulker(stack.getItem())) continue;
            h = mixKit(h, stack.getItem().hashCode());
            h = mixKit(h, stack.getComponentsPatch().hashCode());
        }
        return h;
    }

    /**
     * Whether to send the report now: only after a heartbeat reply carried needKitsReport (an older server never sees the route), at
     * most every 10 s, and only when its hash changed or the server asked. A report the server refused is not sent again until it changes.
     */
    private boolean kitReportDue(StashIndex.Built built, long now) {
        com.porter.qwerte.stash.KitReport.Built r = kitReport;
        if (r == null || !kitReportKnown || !kitSorting()) return false;
        if (!r.server().equals(built.server()) || !r.stash().equals(built.stash())) return false;
        if (now - kitAttemptAt < 10_000) return false;
        if (r.hash().equals(kitRejectedHash)) return false;
        return kitAsked || !r.hash().equals(kitSentHash);
    }

    /** POST /api/v1/kits/report, signed like the index. Isolated: the answer never feeds the manager's backoff or status. */
    private void sendKits(URI base, String key, com.porter.qwerte.stash.KitReport.Built report, long now) {
        kitAttemptAt = now;
        send(base, "/api/v1/kits/report", report.json(), key, (code, body) -> {
            if (code == 200) {
                kitSentHash = report.hash();
                kitRejectedHash = null;
                kitAsked = false;
            } else if (code == 400 || code == 413) {
                QwerteAddon.LOG.debug("stash-manager: the server refused the kit report ({}).", code);
                kitRejectedHash = report.hash();
            }
            return true;
        }, true);
    }

    private void resetDelivery() {
        deliverySync.reset();
        deliveryStamp = 0;
        deliveryFor = "";
        deliveryHash = null;
        deliveryPending = null;
    }

    /**
     * The delivery contents report: built when a delivery chest was read (at most every 5 seconds) or the server asked, and kept to be
     * sent when its hash differs from the last one the server took, or the server asked. Client thread. Never touches the index.
     */
    private void updateDelivery(long now, String player) {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        Stash stash = sorter == null ? null : sorter.currentStash();
        // No world or no stash yet: nothing is known, so nothing changes
        if (stash == null || mc.level == null) return;
        String owner = stash.server + "|" + stash.name;
        if (!owner.equals(deliveryFor)) {
            resetDelivery();
            deliveryFor = owner;
        }
        long stamp = StashIndex.deliveryStamp(stash);
        if (stamp != deliveryStamp) {
            deliveryStamp = stamp;
            deliverySync.changed();
        }
        if (!deliverySync.due(now)) return;
        com.porter.qwerte.stash.DeliveryContents.Built report;
        try {
            report = StashIndex.deliveryReport(stash, player);
        } catch (RuntimeException e) {
            QwerteAddon.LOG.warn("stash-manager: couldn't build the delivery contents: {}", e.toString());
            report = null;
        }
        if (report == null) {
            // Only a stash with no delivery container at all has nothing to report; one not resolved yet is looked at again
            if (stash.deliveryPos != null) return;
            deliverySync.none(now);
            deliveryHash = null;
            deliveryPending = null;
            return;
        }
        deliveryHash = report.hash();
        deliveryPending = deliverySync.built(report.hash(), now) ? report : null;
    }

    /**
     * Sends the delivery report. It is isolated: whatever goes wrong (offline, a bad or missing signature, 404, 5xx) is the report's own
     * business, tried again on its own timer, and never touches the shared backoff or the module's status.
     */
    private void sendDelivery(URI base, String key, long now) {
        com.porter.qwerte.stash.DeliveryContents.Built report = deliveryPending;
        send(base, "/api/v1/delivery", report.json(), key, (code, body) -> {
            if (code == 200) {
                deliverySync.sent(report.hash());
                if (deliveryLog.sent(System.currentTimeMillis())) QwerteAddon.LOG.info("stash-manager: {}", com.porter.qwerte.stash.DeliveryContents.SentLog.line(report.chests()));
                if (deliveryPending == report) deliveryPending = null;
                return true;
            }
            // Refused for good: that hash is not sent again, even when the server asks, until the contents change
            if (code == 400 || code == 413) {
                QwerteAddon.LOG.warn("stash-manager: the server refused the delivery contents ({}): {}", code, body);
                deliverySync.rejected(report.hash());
                deliveryLog.failed();
                if (deliveryPending == report) deliveryPending = null;
                return true;
            }
            deliverySync.retryLater(System.currentTimeMillis());
            deliveryLog.failed();
            return false;
        }, true);
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
        String blocker = acceptOrders.get() ? orderBlocker() : "off";

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
            // Said when the claim is made, never earlier: without the flag the server skips orders with craft lines and fails a held one
            com.porter.qwerte.craft.CraftCapability.claimFlag(o, sorter.craftOrdersCapable(), runner.resumeHasCraft());
            send(base, "/api/v1/orders/claim", bytes(o), key, (code, body) -> {
                if (code == 204) {
                    pendingOrders = 0;
                    // A held craft order claimed without the flag also gets 204 (the server waits for a capable heartbeat before it fails it): its
                    // saved plan, and the ledger of what it made, is kept through two of them
                    if (claimWait.keepSaved(runner.resumeHasCraft())) return true;
                    runner.dropSaved(null);
                    return true;
                }
                claimWait.other();
                if (code != 200) return false;
                takeOrder(sorter, runner, body);
                return true;
            });
            return true;
        }
        return false;
    }

    // The stash area (0.4.0-dev.20, AREA-CONTRACT.md)

    private String areaIgnored;

    private StashIndex.AreaReport areaBody() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        return sorter == null ? null : sorter.areaReport();
    }

    private String areaKeyNow() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        return sorter == null ? "" : sorter.areaKey();
    }

    private String areaSig() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        return sorter == null ? "-" : sorter.areaSig();
    }

    /**
     * What a heartbeat reply says about the area (AreaSync decides): an acknowledgement ends our edit only when it is for the hash we edited to;
     * an area for us is queued unless a change of ours is waiting or was not acknowledged yet.
     */
    private void areaReplied(String body, String sentFor) {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        if (sorter == null) return;
        // The player may have moved to another stash or server since the heartbeat went out: its area is not this one's
        if (!sentFor.equals(sorter.areaKey())) return;
        StashIndex.AreaReply r = StashIndex.areaReply(body);
        com.porter.qwerte.stash.AreaPending pending = sorter.areaPending();
        String areaKey = sorter.areaKey();
        pending.acknowledge(areaKey, r.hash());
        Stash s = sorter.currentStash();
        StashIndex.AreaReport now = sorter.areaReport();
        if (s == null || now == null) return;
        String dimension = com.porter.qwerte.stash.CraftBounds.fullId(s.dimension);
        com.porter.qwerte.stash.AreaSync.Action action = com.porter.qwerte.stash.AreaSync.react(r, now.hash(), pending.get(areaKey), pending.edited(areaKey), dimension.isEmpty() ? null : dimension);
        if (action.warn() != null) {
            if (!action.warn().equals(areaIgnored)) QwerteAddon.LOG.warn("stash-manager: ignoring the stash area from the server: {}.", action.warn());
            areaIgnored = action.warn();
        } else {
            areaIgnored = null;
        }
        if (action.queue() != null) {
            pending.queue(action.queue(), areaKey);
            QwerteAddon.LOG.info("stash-manager: a stash area from the server waits to be applied between tasks.");
        }
    }

    // The recipe table (0.4.0-dev.18)

    /** The server's table is being waited for: a server is set, this module is on, there is no table and the fetch has not failed. */
    boolean waitingForServerRecipes() {
        if (!isActive() || Recipes.current() != null || fetchFailed) return false;
        String key = apiKey.get().trim();
        return !serverUrl.get().isBlank() && !key.isEmpty() && parse(serverUrl.get()) != null;
    }

    /** The game version the recipe table is for ("1.21.4"), or null when it can't be told. */
    private String gameVersion() {
        if (gameVersion == null) {
            try {
                String v = GameVersion.name();
                if (RecipeStore.validMc(v)) gameVersion = v;
            } catch (RuntimeException | LinkageError e) {
                // not known yet
            }
        }
        return gameVersion;
    }

    /**
     * Keeps the recipe table: from the cache; fetched from the server when it has none and whenever the heartbeat reply's recipesHash is not
     * the held table's; built from the game's own data (once, by {@link RecipeBoot}) when there is no server or it could not give one.
     * Every tick, client thread.
     */
    private void recipeTick() {
        String version = gameVersion();
        if (version == null) return;
        long now = System.currentTimeMillis();
        String key = apiKey.get().trim();
        URI base = serverUrl.get().isBlank() || key.isEmpty() ? null : parse(serverUrl.get());
        // With a server set, its table is waited for; the game's own data stands in when there is no server or it has none
        RecipeBoot.allowLocal(base == null || fetchFailed);
        RecipeBoot.bootstrap(version);
        Recipes held = Recipes.current();
        if (base != null && recipeSync.due(now, held == null ? null : held.hash())) fetchRecipes(base, key, version, now, held == null ? null : held.hash());
        if (held == null && RecipeBoot.localDone() && enableCrafting.get() && !craftWarned) {
            craftWarned = true;
            warning("enable-crafting is on but there is no recipe table: it comes from the Stash Manager server (server-url, api-key) or the game's own data, and neither gave one. Nothing is crafted.");
        }
    }

    /** A signed GET of the version's table ({@link RecipeHttp}). The answer is handled by {@link #recipesFetched}. */
    private void fetchRecipes(URI base, String key, String version, long now, String heldHash) {
        recipeSync.started();
        URI uri;
        try {
            uri = URI.create(base + "/api/v1/bot/recipes?mc=" + version);
        } catch (IllegalArgumentException e) {
            recipeSync.failed(now, -1);
            return;
        }
        int gen = generation;
        recipeIo.execute(() -> {
            RecipeHttp.Response r = RecipeHttp.get(http, uri, key, heldHash == null ? null : RecipeStore.quote(heldHash), Duration.ofSeconds(30));
            mc.execute(() -> {
                // A new session since: reset() already cleared the sync state, so this answer is just dropped
                if (gen != generation) return;
                recipesFetched(version, r.code(), r.body(), r.etag());
            });
        });
    }

    /** The answer to the table request: a table (200, checked and cached), "not changed" (304), or a failure that is backed off from. */
    private void recipesFetched(String version, int code, String body, String etag) {
        long now = System.currentTimeMillis();
        if (code == 304) {
            recipeSync.succeeded(now);
            return;
        }
        if (code == 200) {
            try {
                RecipeStore.Checked checked = RecipeStore.shared().validate(version, body, etag);
                Recipes table = checked.table();
                // The table is used as soon as it validates (always replacing a locally built one); failing to cache it is only logged
                Recipes.setCurrent(table);
                RecipeStore.shared().persist(version, checked);
                recipeSync.succeeded(now);
                recipeSaid = false;
                fetchFailed = false;
                QwerteAddon.LOG.info("stash-manager: recipe table for {} fetched ({} recipes, hash {}). Crafting uses it.", version, table.size(), table.hash().substring(0, 8));
                return;
            } catch (RecipeStore.Invalid e) {
                QwerteAddon.LOG.warn("stash-manager: the recipe table from the server was refused: {}.", e.getMessage());
                recipeSync.failed(now, 400);
                fetchFailed = true;
                return;
            }
        }
        recipeSync.failed(now, code);
        fetchFailed = true;
        // Said once per run of failures (not every retry): without a table nothing is crafted
        if (!recipeSaid && Recipes.current() == null) {
            recipeSaid = true;
            QwerteAddon.LOG.warn("stash-manager: no recipe table from the server: it answered {} for the recipes of {}. Crafting stays off until there is one.", code < 0 ? "nothing" : code, version);
        }
    }

    /** What the heartbeat says about craft orders changes: a heartbeat of its own is sent. */
    private String craftSig() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        if (sorter == null || !com.porter.qwerte.craft.CraftCapability.sendOrders(enableCrafting.get(), Recipes.current() != null)) return "-";
        Recipes table = Recipes.current();
        String[] bot = sorter.botTable();
        return table == null ? "-" : table.hash() + "/" + sorter.craftTableStatus() + "/" + sorter.craftTableWhy() + "/" + (bot == null ? "-" : bot[0] + bot[1]);
    }

    /**
     * The craft fields of the heartbeat. A bot that can't carry out craft orders (no recipe table, crafting off, or no runner in this build)
     * sends craftOrders as an explicit null, so it is never given one; one that can says which table it holds, the state of the website's
     * crafting table and the tables it has seen in the stash area.
     */
    private StashIndex.Craft craftBody() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        Recipes table = Recipes.current();
        // Sent whenever crafting and craft-orders are on and a recipe table is held, with the table state and why it is not usable, so the website can
        // say so; the table route being unavailable is not a reason to send null
        if (sorter == null || table == null || !com.porter.qwerte.craft.CraftCapability.sendOrders(enableCrafting.get(), true))
            return com.porter.qwerte.craft.CraftCapability.heartbeat(false, null, null, null, 0, null, null);
        CraftSettings cached = sorter.craftSettings();
        String[] bot = sorter.botTable();
        return com.porter.qwerte.craft.CraftCapability.heartbeat(true, craftOrders.get(), table.hash(), gameVersion() == null ? table.version() : gameVersion(), sorter.craftTableStatus(),
            craftOrderMaxFills.get(), sorter.seenTables(), cached == null ? null : cached.hash(), sorter.craftTableWhy(), bot == null ? null : new StashIndex.BotTable(bot[0], bot[1]));
    }

    /** What a heartbeat reply says about crafting: the server's recipe hash, and new craft settings (the website's table), checked and cached. */
    private void craftReplied(StashIndex.Built built, String body) {
        StashIndex.CraftReply reply = StashIndex.craftReply(body);
        recipeSync.serverHash(reply.recipesHash());
        if (reply.invalid() != null) {
            if (!reply.invalid().equals(craftInvalidSaid)) QwerteAddon.LOG.warn("stash-manager: ignoring craft settings from the server: {}.", reply.invalid());
            craftInvalidSaid = reply.invalid();
        } else if (reply.settings() != null) {
            craftInvalidSaid = null;
            if (CraftStore.shared().put(StashIndex.clamp(built.server(), 256), StashIndex.clamp(built.stash(), 256), reply.settings())) {
                // No position is logged
                QwerteAddon.LOG.info("stash-manager: craft settings updated (revision {}, a crafting table is {}).", reply.settings().rev(), reply.settings().table() == null ? "not set" : "set");
            }
        }
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

    /** Where the crafting table in use came from, for the heartbeat: "set", "found", "placed", "crafted" or "none". */
    private String tableSource() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        return sorter == null ? "none" : sorter.craftingTableSource();
    }

    /** What the heartbeat says about crafting: whether it is on, and whether a crafting table is ready. */
    private int[] craftingInfo() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        // Crafting is only on with a recipe table: nothing is crafted without one
        return new int[] { enableCrafting.get() && Recipes.current() != null ? 1 : 0, sorter != null && sorter.craftingTableReady() ? 1 : 0 };
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
        if (sorter == null) return "sorter off";
        String blocker = sorter.orderBlocker();
        // Crafting is on but its recipe table is still on its way: nothing is claimed meanwhile (an order that needs crafting would be refused for good)
        if (blocker == null && enableCrafting.get() && Crafter.recipesPending()) return Crafter.WAITING_FOR_RECIPES;
        return blocker;
    }

    /** What the heartbeat says about delivery; when it changes, another heartbeat goes out at once. */
    private String storageSig() {
        StashIndex.DeliveryStorage d = deliveryStorage();
        return d == null ? "-" : d.autoExpand() + "/" + d.chests() + "/" + d.full() + "/" + d.canExpand() + "/" + d.blocked();
    }

    private String deliverySig() {
        int[] d = deliveryPos();
        return (d == null ? "none" : d[0] + "," + d[1] + "," + d[2]) + "|" + (namingInfo()[0]) + anvilSource() + "|" + craftingInfo()[0] + craftingInfo()[1] + tableSource() + "|" + orderBlocker() + "|" + rescanDone + "|" + rescanBlocked() + "|" + storageSig() + "|" + craftSig() + "|" + areaSig();
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

    /** auto-expand-delivery. */
    boolean autoExpandDelivery() {
        return autoExpandDelivery.get();
    }

    /** What the heartbeat says about the delivery storage, or null when no delivery container is set. */
    private StashIndex.DeliveryStorage deliveryStorage() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        return sorter == null ? null : sorter.deliveryStorage();
    }

    /** enable-crafting. */
    boolean craftingEnabled() {
        return enableCrafting.get();
    }

    /** craft-orders: craft orders may be taken (it also needs crafting on and a recipe table; see StashSorter.craftOrdersCapable). */
    boolean craftOrdersOn() {
        return craftOrders.get();
    }

    int craftOrderMaxFills() {
        return craftOrderMaxFills.get();
    }

    int craftOrderMaxPerHour() {
        return craftOrderMaxPerHour.get();
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
        // A craft order is only given to a bot that said it can carry them out (the claim flag); one that arrives anyway is failed, not half done
        String why = (order.hasCraft() || order.craft != null) && !sorter.craftOrdersCapable() ? "this bot can't carry out craft orders" : runner.offer(order);
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
        // A cancelled craft order still reports what it made and gave back (result.craft); other cancelled orders send no result
        if (r.terminal() && (!r.status().equals("cancelled") || r.craft() != null)) {
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
            if (r.repair() != null) result.add("repair", r.repair().toJson());
            // Only when the order had craft lines (CRAFT-CONTRACT.md): numbers, item ids and reason codes only
            if (r.craft() != null) result.add("craft", r.craft());
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
        send(base, path, body, key, reply, false);
    }

    /** @param isolated the request is not part of the module's link to the server: its failures never set the shared backoff or the status */
    private void send(URI base, String path, byte[] body, String key, Reply reply, boolean isolated) {
        URI uri;
        try {
            uri = URI.create(base + path);
        } catch (IllegalArgumentException e) {
            if (!isolated) setStatus("bad url");
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
            if (!isolated) setStatus("bad url");
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
                        // A reply that unpacks to more than 4 MiB is not one the server sends (a compression bomb): treated as unsigned
                        bytes = in.readNBytes(MAX_REPLY_BYTES + 1);
                        if (bytes.length > MAX_REPLY_BYTES) badSignature = true;
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

                if (isolated) {
                    // No signature check failure, 404 or conflict here may reach the shared backoff or the status
                    String conflict = stashOwner(finalCode, finalText);
                    if (finalBad) {
                        reply.done(-2, "");
                        return;
                    }
                    if (conflict != null) {
                        deliverySync.forget();
                        deliverySync.retryLater(System.currentTimeMillis());
                        // The kit report waits at least 5 minutes after an owner conflict
                        kitAttemptAt = System.currentTimeMillis() + 290_000;
                        return;
                    }
                    try {
                        reply.done(finalCode, finalText);
                    } catch (RuntimeException e) {
                        QwerteAddon.LOG.warn("stash-manager: couldn't handle the reply", e);
                        deliverySync.retryLater(System.currentTimeMillis());
                    }
                    return;
                }

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
                    deliverySync.forget();
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

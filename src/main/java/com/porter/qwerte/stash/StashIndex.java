package com.porter.qwerte.stash;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.stash.IndexJson.Agg;
import com.porter.qwerte.stash.IndexJson.Info;
import com.porter.qwerte.stash.IndexJson.Shulker;
import com.porter.qwerte.stash.Stash.Box;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Builds the stash index the stash-manager module pushes to the Stash Manager server (see stash-manager-server/PROTOCOL.md).
 * Everything here touches game objects, so it must run on the client thread. The result holds only bytes and strings.
 */
public final class StashIndex {
    /** A finished index: the JSON body and the hash of its items, plus what identifies the stash. */
    public record Built(String server, String stash, String player, String hash, byte[] json) {}

    private record CacheKey(Item item, DataComponentPatch patch) {}

    private static final Gson GSON = new Gson();
    private static final int MAX_DEPTH = 4;
    private static final int MAX_CACHE = 20000;
    // Shulker and bundle prototypes carry an empty container component, so the patch may hold a set or a removal of it
    // Left out of keys: what a container holds, and custom_data, which ViaVersion adds to most items on some servers
    private static final Predicate<DataComponentType<?>> CONTENTS = t -> t == DataComponents.CONTAINER || t == DataComponents.BUNDLE_CONTENTS || t == DataComponents.CUSTOM_DATA;
    // Shulkers coming through ViaVersion hold their items in block_entity_data
    private static final Predicate<DataComponentType<?>> SHULKER_CONTENTS = t -> CONTENTS.test(t) || t == DataComponents.BLOCK_ENTITY_DATA;

    // The match key (KIT-CONTRACT v2, revision 4) is the index key over the patch with this strip set also removed: always the custom name, damage and
    // repair cost (custom data, container and bundle contents, and a shulker's block entity data, are already left out of the key). Everything else counts.
    private static final Predicate<DataComponentType<?>> MATCH_STRIP = t -> t == DataComponents.CUSTOM_NAME || t == DataComponents.DAMAGE || t == DataComponents.REPAIR_COST;

    private static final Map<CacheKey, Info> CACHE = new HashMap<>();
    private static final List<String> TABS = List.of("building_blocks", "colored_blocks", "natural_blocks", "functional_blocks", "redstone_blocks",
        "tools_and_utilities", "combat", "food_and_drinks", "ingredients", "spawn_eggs", "op_blocks");
    private static final int OTHER_ORDER = 1_000_000;

    private record TabSlot(String tab, int order) {}

    // Item -> creative tab, built once per world
    private static Map<Item, TabSlot> tabs;
    private static boolean tabsWarned;
    private static Map<String, String> lang;
    private static boolean langWarned, encodeWarned;

    private StashIndex() {}

    /** Forgets the cached names and keys. */
    public static void clearCache() {
        CACHE.clear();
        tabs = null;
    }

    /**
     * A cheap number that changes when anything the index shows changes. Only for noticing changes within a session:
     * it uses hashCode, so it is never stored or sent.
     */
    public static long fingerprint(Stash stash, boolean includeExcluded) {
        long h = includeExcluded ? 1 : 0;
        h = mix(h, stash.server.hashCode());
        h = mix(h, stash.name.hashCode());
        if (mc.level != null) h = mix(h, mc.level.dimension().hashCode());
        for (Box box : stash.boxes) {
            if (box.delivery || box.foreign || box.excluded && !includeExcluded) continue;
            h = mix(h, (box.known() ? 1 : 0) | (box.stale ? 2 : 0));
            if (!box.known()) continue;
            for (ItemStack stack : box.contents) h = mix(h, stackHash(stack));
        }
        for (var e : stash.held.entrySet()) {
            h = mix(h, e.getKey());
            h = mix(h, stackHash(e.getValue()));
        }
        return h;
    }

    private static long mix(long h, long v) {
        return (h ^ v) * 0x100000001b3L + 0x9e3779b97f4a7c15L;
    }

    private static long stackHash(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        long h = mix(BuiltInRegistries.ITEM.getId(stack.getItem()) + 1, stack.getCount());
        return mix(h, stack.getComponentsPatch().hashCode());
    }

    /**
     * Builds the index of the stash, or null if there is no world.
     */
    public static Built build(Stash stash, boolean includeExcluded) {
        if (mc.level == null || mc.player == null) return null;
        if (CACHE.size() > MAX_CACHE) CACHE.clear();
        RegistryOps<Tag> ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);

        Map<String, Agg> groups = new LinkedHashMap<>();
        Map<String, Shulker> shulkers = new LinkedHashMap<>();
        int known = 0, total = 0, stale = 0;
        for (Box box : stash.boxes) {
            // The delivery containers are never indexed, whatever include-excluded says
            if (box.delivery || box.foreign || box.excluded && !includeExcluded) continue;
            total++;
            if (box.stale) stale++;
            if (!box.known()) continue;
            known++;
            for (ItemStack stack : box.contents) {
                add(groups, ops, stack, 0);
                addShulker(shulkers, ops, stack);
            }
        }
        for (ItemStack stack : stash.held.values()) {
            add(groups, ops, stack, 0);
            addShulker(shulkers, ops, stack);
        }

        JsonArray items = IndexJson.items(groups);
        JsonArray shulkerArray = IndexJson.shulkers(shulkers);
        String hash = sha1(GSON.toJson(items) + GSON.toJson(shulkerArray));

        String dimension = dimension();

        String player = mc.player.getName().getString();
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("server", stash.server);
        root.addProperty("stash", stash.name);
        root.addProperty("player", player);
        root.addProperty("mcVersion", mcVersion());
        root.addProperty("dimension", dimension);
        root.addProperty("generatedAt", System.currentTimeMillis());
        root.addProperty("indexHash", hash);
        JsonObject containers = new JsonObject();
        containers.addProperty("known", known);
        containers.addProperty("total", total);
        containers.addProperty("stale", stale);
        root.add("containers", containers);
        root.add("items", items);
        root.add("shulkers", shulkerArray);

        return new Built(stash.server, stash.name, player, hash, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The {@code items} and {@code shulkers} arrays for these stacks, in the exact shape (and order, and numbers) of
     * {@link #build}: shulker and bundle contents are counted, filled shulkers are grouped. Used by the in-game Stash
     * Window to index what the player has opened. Null with no world. Client thread only.
     */
    public static JsonObject aggregate(Iterable<ItemStack> stacks) {
        if (mc.level == null) return null;
        if (CACHE.size() > MAX_CACHE) CACHE.clear();
        RegistryOps<Tag> ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Map<String, Agg> groups = new LinkedHashMap<>();
        Map<String, Shulker> shulkers = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            add(groups, ops, stack, 0);
            addShulker(shulkers, ops, stack);
        }
        JsonObject out = new JsonObject();
        out.add("items", IndexJson.items(groups));
        out.add("shulkers", IndexJson.shulkers(shulkers));
        return out;
    }

    /** At most {@code max} characters, cut at a word boundary and ending in an ellipsis when it was longer. */
    public static String clamp(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int cut = s.lastIndexOf(' ', max - 1);
        if (cut < max / 2) cut = max - 1;
        return s.substring(0, cut).stripTrailing() + "\u2026";
    }

    /** The dimension we are in, like "minecraft:overworld". */
    public static String dimension() {
        if (mc.level == null) return "";
        String dimension = mc.level.dimension().toString();
        // "ResourceKey[minecraft:dimension / minecraft:overworld]" -> "minecraft:overworld"
        int slash = dimension.lastIndexOf(" / ");
        if (slash >= 0) dimension = dimension.substring(slash + 3).replace("]", "");
        return dimension;
    }

    /**
     * The JSON body of a heartbeat for an index. {@code delivery} is the delivery container's position (x, y, z) or null;
     * {@code orderBlocker} is why orders can't be taken now, or null. Both are sent even when null. {@code naming} is
     * {anvil ready (0/1), XP levels or -1}, or null to leave it out. {@code crafting} is {enabled (0/1), crafting table ready (0/1)},
     * or null to leave it out.
     */
    public static byte[] heartbeat(Built b, int[] delivery, String orderBlocker, long rescanDone, String rescanBlocked, int[] naming, int[] crafting, String anvilSource) {
        return heartbeat(b, delivery, orderBlocker, rescanDone, rescanBlocked, naming, crafting, anvilSource, null);
    }

    /**
     * The delivery storage (0.4.0-dev.5, "deliveryStorage" in the heartbeat): how many chests there are (a double chest counts once),
     * how many are known to be full, whether another can be put up now, and why not. No positions.
     * @param blocked why no chest can be put up, or null
     */
    public record DeliveryStorage(boolean autoExpand, int chests, int full, boolean canExpand, String blocked) {}

    /** As above, with {@code storage} sent as {@code deliveryStorage}, or left out when null. */
    public static byte[] heartbeat(Built b, int[] delivery, String orderBlocker, long rescanDone, String rescanBlocked, int[] naming, int[] crafting, String anvilSource,
                                   DeliveryStorage storage) {
        return heartbeat(b, delivery, orderBlocker, rescanDone, rescanBlocked, naming, crafting, anvilSource, storage, null);
    }

    /**
     * As above, with {@code tableSource} sent as {@code crafting.tableSource} ("set", "found", "placed", "crafted" or "none"; 0.4.0-dev.6),
     * or left out when null.
     */
    public static byte[] heartbeat(Built b, int[] delivery, String orderBlocker, long rescanDone, String rescanBlocked, int[] naming, int[] crafting, String anvilSource,
                                   DeliveryStorage storage, String tableSource) {
        return heartbeat(b, delivery, orderBlocker, rescanDone, rescanBlocked, naming, crafting, anvilSource, storage, tableSource, null);
    }

    /** As above, with {@code deliveryHash} (0.4.0-dev.7, the hash of the delivery contents report) sent as {@code deliveryHash}, or left out when null. */
    public static byte[] heartbeat(Built b, int[] delivery, String orderBlocker, long rescanDone, String rescanBlocked, int[] naming, int[] crafting, String anvilSource,
                                   DeliveryStorage storage, String tableSource, String deliveryHash) {
        return heartbeat(b, delivery, orderBlocker, rescanDone, rescanBlocked, naming, crafting, anvilSource, storage, tableSource, deliveryHash, null);
    }

    /**
     * What the heartbeat says about kit sorting (0.4.0-dev.14, KIT-CONTRACT.md "Heartbeat"). Both keys are written, as an explicit null
     * when the addon has nothing: {@code settingsHash} is the hash of the cached settings for this stash, {@code reportHash} the hash of
     * the current report.
     */
    public record KitsHashes(String settingsHash, String reportHash, String settingsHash2, KitOrdersCap kitOrders) {
        public KitsHashes(String settingsHash, String reportHash) {
            this(settingsHash, reportHash, null, null);
        }

        public KitsHashes(String settingsHash, String reportHash, String settingsHash2) {
            this(settingsHash, reportHash, settingsHash2, null);
        }
    }

    /** The heartbeat's {@code kitOrders}: what this bot can do with kits. The addon's first release can only import its local kits. */
    public record KitOrdersCap(boolean enabled, boolean restock, boolean craft, boolean importer) {
        /** Release 1: sorts by type and template, can't carry out kit orders or restock, can import its local kits. */
        public static final KitOrdersCap IMPORT_ONLY = new KitOrdersCap(false, false, false, true);
    }

    /** As above, with {@code kits} sent as {@code kitsHash} and {@code kitsReportHash}; null leaves both out (kit sorting is off). */
    public static byte[] heartbeat(Built b, int[] delivery, String orderBlocker, long rescanDone, String rescanBlocked, int[] naming, int[] crafting, String anvilSource,
                                   DeliveryStorage storage, String tableSource, String deliveryHash, KitsHashes kits) {
        return heartbeat(b, delivery, orderBlocker, rescanDone, rescanBlocked, naming, crafting, anvilSource, storage, tableSource, deliveryHash, kits, null);
    }

    /** The most crafting tables the heartbeat lists as seen in the stash area. */
    public static final int MAX_SEEN = 8;

    /** The state of the website's crafting table, as the heartbeat words it. */
    public static final String TABLE_UNSET = "unset", TABLE_OK = "ok", TABLE_UNLOADED = "unloaded", TABLE_NOT_TABLE = "not_table",
        TABLE_UNREACHABLE = "unreachable", TABLE_OTHER_DIMENSION = "other_dimension";

    /**
     * What the heartbeat says about craft orders (0.4.0-dev.18, CRAFT-CONTRACT.md "Heartbeat"): {@code v} 1, whether this bot takes them,
     * the tableHash of the recipe table it holds, its Minecraft version, the state of the website's crafting table, the most grid
     * fills it makes for one order, and up to 8 tables seen in the stash area ("x,y,z").
     */
    public record CraftOrders(boolean enabled, String recipes, String mc, String table, int maxFills, List<String> seen, String tableWhy, BotTable botTable) {
        public CraftOrders(boolean enabled, String recipes, String mc, String table, int maxFills, List<String> seen) {
            this(enabled, recipes, mc, table, maxFills, seen, null, null);
        }
    }

    /**
     * The bot's own crafting table in use (found, placed or crafted by it; {@code craftOrders.botTable}): "x,y,z" and where it came from. A
     * position: the server hides it from streamers.
     */
    public record BotTable(String pos, String source) {}

    /**
     * @param orders       the craftOrders object, or null when the bot can't carry out craft orders (sent as an explicit null: it is
     *                     never given one)
     * @param settingsHash the hash of the cached craft settings for this stash, or null when it has none (sent as an explicit null, so
     *                     the server answers with them); only written with {@code orders}
     */
    public record Craft(CraftOrders orders, String settingsHash) {}

    /** As above, with {@code craft} sent as {@code craftOrders} and {@code craftHash}; null leaves both out. */
    public static byte[] heartbeat(Built b, int[] delivery, String orderBlocker, long rescanDone, String rescanBlocked, int[] naming, int[] crafting, String anvilSource,
                                   DeliveryStorage storage, String tableSource, String deliveryHash, KitsHashes kits, Craft craft) {
        return heartbeat(b, delivery, orderBlocker, rescanDone, rescanBlocked, naming, crafting, anvilSource, storage, tableSource, deliveryHash, kits, craft, null);
    }

    /**
     * What the heartbeat says about the stash area (0.4.0-dev.20, AREA-CONTRACT.md "Heartbeat"). {@code hash} is the hash of the area the bot uses
     * now; {@code box}/{@code dimension} that area (null: none set); {@code derived} its working box when none is set; {@code edited} the area was
     * changed in game and the server has not taken it yet; {@code pendingHash}/{@code pendingWhy} an area received and not applied yet, and why
     * (busy, far, dimension); {@code containersOutside} how many containers lie outside the set area.
     */
    public record AreaReport(String hash, int[] box, String dimension, int[] derived, boolean edited, String pendingHash, String pendingWhy, int containersOutside) {}

    /** As above, with {@code area} sent as {@code area} (it needs a player); null leaves it out. */
    public static byte[] heartbeat(Built b, int[] delivery, String orderBlocker, long rescanDone, String rescanBlocked, int[] naming, int[] crafting, String anvilSource,
                                   DeliveryStorage storage, String tableSource, String deliveryHash, KitsHashes kits, Craft craft, AreaReport area) {
        JsonObject o = new JsonObject();
        if (area != null && b.player() != null && !b.player().isBlank()) {
            JsonObject a = new JsonObject();
            a.addProperty("v", 1);
            a.addProperty("hash", area.hash());
            if (area.box() == null || area.dimension() == null) a.add("area", com.google.gson.JsonNull.INSTANCE);
            else a.add("area", areaObject(area.box(), area.dimension()));
            if (area.derived() == null) a.add("derived", com.google.gson.JsonNull.INSTANCE);
            else a.add("derived", boxArray(area.derived()));
            a.addProperty("edited", area.edited());
            if (area.pendingHash() == null) a.add("pending", com.google.gson.JsonNull.INSTANCE);
            else {
                JsonObject pend = new JsonObject();
                pend.addProperty("hash", area.pendingHash());
                pend.addProperty("why", area.pendingWhy() == null ? AreaRules.BUSY : area.pendingWhy());
                a.add("pending", pend);
            }
            a.addProperty("containersOutside", Math.max(0, area.containersOutside()));
            o.add("area", a);
        }
        // The server answers 400 to craftOrders or craftHash without a player, and only gives the craft settings after checking the owner: with
        // no player known the craft fields are left out altogether
        if (craft != null && b.player() != null && !b.player().isBlank()) {
            CraftOrders c = craft.orders();
            if (c == null) o.add("craftOrders", com.google.gson.JsonNull.INSTANCE);
            else {
                JsonObject co = new JsonObject();
                co.addProperty("v", 1);
                co.addProperty("enabled", c.enabled());
                co.addProperty("recipes", clamp(c.recipes(), 40));
                co.addProperty("mc", clamp(c.mc(), 32));
                co.addProperty("table", c.table());
                co.addProperty("maxFills", Math.max(1, Math.min(100_000, c.maxFills())));
                // The auto table (additive): why the table is not usable (null when it is), and the bot's own table in use
                if (c.tableWhy() == null) co.add("tableWhy", com.google.gson.JsonNull.INSTANCE);
                else co.addProperty("tableWhy", clamp(c.tableWhy(), 32));
                if (c.botTable() == null) co.add("botTable", com.google.gson.JsonNull.INSTANCE);
                else {
                    JsonObject bt = new JsonObject();
                    bt.addProperty("pos", clamp(c.botTable().pos(), 48));
                    bt.addProperty("source", clamp(c.botTable().source(), 16));
                    co.add("botTable", bt);
                }
                if (c.seen() != null && !c.seen().isEmpty()) {
                    JsonArray seen = new JsonArray();
                    for (String s : c.seen()) if (seen.size() < MAX_SEEN) seen.add(s);
                    co.add("seen", seen);
                }
                o.add("craftOrders", co);
                if (craft.settingsHash() == null) o.add("craftHash", com.google.gson.JsonNull.INSTANCE);
                else o.addProperty("craftHash", clamp(craft.settingsHash(), 64));
            }
        }
        if (kits != null) {
            if (kits.settingsHash() == null) o.add("kitsHash", com.google.gson.JsonNull.INSTANCE);
            else o.addProperty("kitsHash", clamp(kits.settingsHash(), 64));
            if (kits.reportHash() == null) o.add("kitsReportHash", com.google.gson.JsonNull.INSTANCE);
            else o.addProperty("kitsReportHash", clamp(kits.reportHash(), 64));
            // v2: the cached v2 hash, an explicit null when there is no cache or it has no types (so the server sends the settings)
            if (kits.settingsHash2() == null) o.add("kitsHash2", com.google.gson.JsonNull.INSTANCE);
            else o.addProperty("kitsHash2", clamp(kits.settingsHash2(), 64));
            // What the bot can do with kits (needs a player, like the other capability objects)
            if (kits.kitOrders() != null && b.player() != null && !b.player().isBlank()) {
                JsonObject ko = new JsonObject();
                ko.addProperty("v", 1);
                ko.addProperty("enabled", kits.kitOrders().enabled());
                ko.addProperty("restock", kits.kitOrders().restock());
                ko.addProperty("craft", kits.kitOrders().craft());
                ko.addProperty("import", kits.kitOrders().importer());
                o.add("kitOrders", ko);
            }
        }
        if (deliveryHash != null) o.addProperty("deliveryHash", deliveryHash);
        if (storage != null) {
            JsonObject d = new JsonObject();
            d.addProperty("autoExpand", storage.autoExpand());
            d.addProperty("chests", Math.max(0, storage.chests()));
            d.addProperty("full", Math.max(0, Math.min(storage.full(), storage.chests())));
            d.addProperty("canExpand", storage.canExpand());
            if (storage.blocked() == null || storage.blocked().isEmpty()) d.add("blocked", com.google.gson.JsonNull.INSTANCE);
            else d.addProperty("blocked", clamp(storage.blocked(), 160));
            o.add("deliveryStorage", d);
        }
        if (naming != null) {
            JsonObject n = new JsonObject();
            n.addProperty("anvil", naming[0] != 0);
            n.addProperty("xpLevels", naming[1]);
            if (anvilSource != null) n.addProperty("anvilSource", anvilSource);
            o.add("naming", n);
        }
        if (crafting != null) {
            JsonObject c = new JsonObject();
            c.addProperty("enabled", crafting[0] != 0);
            c.addProperty("table", crafting[1] != 0);
            if (tableSource != null) c.addProperty("tableSource", tableSource);
            o.add("crafting", c);
        }
        // This bot can repair items with XP bottles before delivery (REPAIR-CONTRACT.md)
        o.addProperty("repair", true);
        if (rescanDone >= 0) o.addProperty("rescanDone", rescanDone);
        if (rescanBlocked == null) o.add("rescanBlocked", com.google.gson.JsonNull.INSTANCE);
        else o.addProperty("rescanBlocked", clamp(rescanBlocked, 100));
        if (delivery == null) o.add("delivery", com.google.gson.JsonNull.INSTANCE);
        else {
            JsonObject d = new JsonObject();
            d.addProperty("x", delivery[0]);
            d.addProperty("y", delivery[1]);
            d.addProperty("z", delivery[2]);
            d.addProperty("dimension", dimension());
            o.add("delivery", d);
        }
        if (orderBlocker == null) o.add("orderBlocker", com.google.gson.JsonNull.INSTANCE);
        else o.addProperty("orderBlocker", clamp(orderBlocker, 100));
        o.addProperty("server", b.server());
        o.addProperty("stash", b.stash());
        o.addProperty("player", b.player());
        o.addProperty("indexHash", b.hash());
        return new com.google.gson.GsonBuilder().serializeNulls().create().toJson(o).getBytes(StandardCharsets.UTF_8);
    }

    /** Whether a heartbeat reply says the server wants the index. Missing or broken means no. */
    public static boolean needsIndex(String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            return e.isJsonObject() && e.getAsJsonObject().has("needIndex") && e.getAsJsonObject().get("needIndex").getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** The delivery chests of the stash in fill order, as far as the book and the boxes tell; empty when no delivery container is set. */
    private record DChest(Box box, boolean full) {}

    private static List<DChest> deliveryChests(Stash stash) {
        List<DChest> out = new ArrayList<>();
        DeliveryBook book = new DeliveryBook(stash.deliveryStorage);
        for (DeliveryBook.Entry e : book.fillOrder()) {
            Box box = stash.boxAt(new net.minecraft.core.BlockPos(e.at[0], e.at[1], e.at[2]));
            if (box != null && box.delivery) out.add(new DChest(box, e.full));
        }
        if (out.isEmpty()) {
            // The book is filled in when the sorter first looks at the storage: until then the delivery container alone
            for (Box box : stash.boxes) if (box.delivery && !box.deliveryExtra) out.add(new DChest(box, false));
        }
        return out;
    }

    /**
     * A cheap number that changes when a delivery chest is read or its fullness changes (any read moves {@code lastRead}), to notice
     * that the delivery report is to be built again. Not sent. Never indexes anything.
     */
    public static long deliveryStamp(Stash stash) {
        long h = 0;
        for (DChest c : deliveryChests(stash)) {
            Box box = c.box();
            h = mix(h, box.lastRead);
            h = mix(h, (box.known() ? box.contents.length + 1 : 0) | (c.full() ? 1000 : 0));
        }
        return h;
    }

    /**
     * The delivery storage's contents (see {@link DeliveryContents}), or null with no world or no delivery container. Reads the
     * contents the sorter last saw in each chest ({@code Box.contents}, kept by the read of every open window and saved with the stash);
     * nothing here opens a container. Delivery containers stay out of the index and out of searches.
     */
    public static DeliveryContents.Built deliveryReport(Stash stash, String player) {
        if (mc.level == null || stash == null) return null;
        List<DChest> chests = deliveryChests(stash);
        if (chests.isEmpty()) return null;
        if (CACHE.size() > MAX_CACHE) CACHE.clear();
        RegistryOps<Tag> ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        List<DeliveryContents.Chest> out = new ArrayList<>();
        for (DChest c : chests) {
            Box box = c.box();
            if (!box.known() || box.lastRead <= 0) {
                out.add(new DeliveryContents.Chest(box.parts.size() > 1 ? 54 : 27, c.full(), 0, null));
                continue;
            }
            List<DeliveryContents.Item> items = new ArrayList<>();
            for (int slot = 0; slot < box.contents.length; slot++) {
                ItemStack stack = box.contents[slot];
                if (stack == null || stack.isEmpty()) continue;
                Info info = infoOf(ops, stack);
                List<DeliveryContents.Inner> inside = null;
                if (Utils.isShulker(stack.getItem())) {
                    inside = new ArrayList<>();
                    for (ItemStack inner : Kits.inside(stack)) {
                        if (inner == null || inner.isEmpty()) continue;
                        inside.add(new DeliveryContents.Inner(BuiltInRegistries.ITEM.getKey(inner.getItem()).toString(), inner.getCount()));
                    }
                }
                items.add(new DeliveryContents.Item(slot, info.id(), info.name(), stack.getCount(), info.maxStack(), inside, info.damage(), info.maxDamage()));
            }
            out.add(new DeliveryContents.Chest(box.parts.size() > 1 || box.contents.length > 27 ? 54 : 27, c.full(), box.lastRead, items));
        }
        return DeliveryContents.build(stash.server, stash.name, player, out, System.currentTimeMillis());
    }

    /**
     * The container id of a box for kit sorting: its smallest part (KIT-CONTRACT.md "Container ids"), the same on both bots.
     */
    public static String kitId(Box box) {
        List<KitSettings.Pos> parts = new ArrayList<>(box.parts.size());
        for (net.minecraft.core.BlockPos p : box.parts) parts.add(new KitSettings.Pos(p.getX(), p.getY(), p.getZ()));
        if (parts.isEmpty()) parts.add(new KitSettings.Pos(box.pos.getX(), box.pos.getY(), box.pos.getZ()));
        return KitRouting.canonicalId(parts);
    }

    /** Read, sortable, not excluded, not a drop-off and not a delivery container: a container a kit may be sorted into. */
    public static boolean kitPickable(Box box) {
        return box != null && box.sortable() && !box.dropOff && !box.delivery;
    }

    /** The item ids and stack counts of a shulker box's 27 slots (an empty id for an empty slot). */
    private static List<KitScore.Slot> shulkerSlots(ItemStack shulker) {
        ItemStack[] inside = Kits.inside(shulker);
        List<KitScore.Slot> slots = new ArrayList<>(KitScore.SLOTS);
        for (ItemStack s : inside) slots.add(s == null || s.isEmpty() ? new KitScore.Slot("", 0) : new KitScore.Slot(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount()));
        return slots;
    }

    private record ScoreKey(Item item, DataComponentPatch patch, KitSettings.Scoring scoring) {}

    private static final Map<ScoreKey, KitScore.Result> SCORES = new HashMap<>();

    /**
     * The kit score of a stack (KIT-CONTRACT.md "Kit score"): null for anything but a shulker box. Worked out once per item and
     * components (name, contents) and scoring, then remembered, so a planning pass or a report never scores the same box twice.
     * Whether KitBuilder already routes the shulker is not looked at here. Client thread only.
     */
    public static KitScore.Result kitScore(ItemStack stack, KitSettings.Scoring scoring) {
        if (stack == null || stack.isEmpty() || !Utils.isShulker(stack.getItem())) return null;
        if (SCORES.size() > MAX_CACHE) SCORES.clear();
        return SCORES.computeIfAbsent(new ScoreKey(stack.getItem(), stack.getComponentsPatch(), scoring), k -> {
            var name = stack.get(DataComponents.CUSTOM_NAME);
            return KitScore.score(shulkerSlots(stack), name == null ? null : name.getString(), scoring, KitItems.table());
        });
    }

    /** The plain custom name of a stack, or null when it has none. */
    public static String customName(ItemStack stack) {
        var name = stack == null || stack.isEmpty() ? null : stack.get(DataComponents.CUSTOM_NAME);
        return name == null ? null : name.getString();
    }

    /** True when KitBuilder routes this shulker already (a finished or part built kit): it keeps KitBuilder's routing, whatever it scores. */
    public static boolean builderKit(ItemStack stack) {
        Kits.Match m = Kits.match(stack);
        return m != null && m.claims();
    }

    private static KitReport.Shulker reportShulker(ItemStack stack, KitSettings.Scoring scoring) {
        KitScore.Result r = kitScore(stack, scoring);
        if (r == null || !r.kit() || builderKit(stack)) return null;
        return new KitReport.Shulker(customName(stack), r);
    }

    /**
     * The kit report of the stash (see {@link KitReport}): the containers the index covers (delivery and foreign ones and, unless
     * {@code includeExcluded}, excluded ones are left out), the shulkers that score as kits in them and in {@code inventory}.
     * Reads what the sorter last saw; nothing here opens a container. Client thread. Null with no stash.
     */
    public static KitReport.Built kitReport(Stash stash, boolean includeExcluded, String player, Iterable<ItemStack> inventory, KitSettings.Scoring scoring,
                                            java.util.function.Predicate<ItemStack> notCarried) {
        if (stash == null) return null;
        List<KitReport.BoxInfo> boxes = new ArrayList<>(stash.boxes.size());
        for (Box box : stash.boxes) {
            if (box.delivery || box.foreign || box.excluded && !includeExcluded) continue;
            ItemStack[] contents = box.contents;
            int size = contents != null ? (contents.length >= 54 ? 54 : 27) : (box.parts.size() > 1 ? 54 : 27);
            int free = 0;
            List<KitReport.Shulker> shulkers = List.of();
            if (contents != null) {
                for (ItemStack stack : contents) {
                    if (stack == null || stack.isEmpty()) {
                        free++;
                        continue;
                    }
                    KitReport.Shulker sh = reportShulker(stack, scoring);
                    if (sh == null) continue;
                    if (shulkers.isEmpty()) shulkers = new ArrayList<>();
                    shulkers.add(sh);
                }
            }
            boxes.add(new KitReport.BoxInfo(kitId(box), box.labels.isEmpty() ? null : box.labels.getFirst(), size, free, kitPickable(box), Math.max(0, box.lastRead), shulkers));
        }
        List<KitReport.Shulker> carried = new ArrayList<>();
        for (ItemStack stack : inventory) {
            // A kit the sorter is building or holds for KitBuilder is not a carried kit
            if (notCarried != null && notCarried.test(stack)) continue;
            KitReport.Shulker sh = reportShulker(stack, scoring);
            if (sh != null) carried.add(sh);
        }
        return KitReport.build(stash.server, stash.name, player, boxes, carried);
    }

    private static KitReport.Shulker reportShulkerV2(ItemStack stack, KitSettings.Scoring scoring, KitSettings settings) {
        if (stack == null || stack.isEmpty() || !Utils.isShulker(stack.getItem()) || builderKit(stack)) return null;
        KitScore.Result score = kitScore(stack, scoring);
        if (score == null) return null;
        return new KitReport.Shulker(customName(stack), score, classify(stack, settings), templateOf(stack, settings));
    }

    /**
     * The v2 kit report (KIT-CONTRACT v2): every shulker box in the covered containers and in {@code inventory} with the classifier's verdict and
     * the template it is a copy of; the report lists the kits among them, and the named boxes that are not kits. Null with no stash or no table.
     */
    public static KitReport.Built kitReportV2(Stash stash, boolean includeExcluded, String player, Iterable<ItemStack> inventory, KitSettings settings,
                                              java.util.function.Predicate<ItemStack> notCarried) {
        if (stash == null) return null;
        KitTypes table = KitTypes.shared();
        if (table == null) return null;
        KitSettings.Scoring scoring = settings == null ? KitSettings.Scoring.DEFAULT : settings.scoring();
        List<KitReport.BoxInfo> boxes = new ArrayList<>(stash.boxes.size());
        for (Box box : stash.boxes) {
            if (box.delivery || box.foreign || box.excluded && !includeExcluded) continue;
            ItemStack[] contents = box.contents;
            int size = contents != null ? (contents.length >= 54 ? 54 : 27) : (box.parts.size() > 1 ? 54 : 27);
            int free = 0;
            List<KitReport.Shulker> shulkers = List.of();
            if (contents != null) {
                for (ItemStack stack : contents) {
                    if (stack == null || stack.isEmpty()) {
                        free++;
                        continue;
                    }
                    KitReport.Shulker sh = reportShulkerV2(stack, scoring, settings);
                    if (sh == null) continue;
                    if (shulkers.isEmpty()) shulkers = new ArrayList<>();
                    shulkers.add(sh);
                }
            }
            boxes.add(new KitReport.BoxInfo(kitId(box), box.labels.isEmpty() ? null : box.labels.getFirst(), size, free, kitPickable(box), Math.max(0, box.lastRead), shulkers));
        }
        List<KitReport.Shulker> carried = new ArrayList<>();
        for (ItemStack stack : inventory) {
            if (notCarried != null && notCarried.test(stack)) continue;
            KitReport.Shulker sh = reportShulkerV2(stack, scoring, settings);
            if (sh != null) carried.add(sh);
        }
        return KitReport.buildV2(stash.server, stash.name, player, boxes, carried, table.fingerprint(), settings);
    }

    /**
     * What a heartbeat reply says about kits. {@code reportKnown}: the reply has a {@code needKitsReport} key (true or false), so the
     * server knows the report route. {@code needReport}: it wants the report. {@code settings}: a valid {@code kits} object, else null.
     * {@code invalid}: why a {@code kits} object was refused (null when there was none or it was fine).
     */
    public record KitsReply(boolean reportKnown, boolean needReport, KitSettings settings, String invalid) {}

    private static final KitsReply NO_KITS = new KitsReply(false, false, null, null);

    /** Reads the kit fields of a heartbeat reply. Missing or broken fields mean nothing was said; a bad {@code kits} object is refused whole. */
    public static KitsReply kitsReply(String body) {
        try {
            JsonElement e = com.porter.qwerte.craft.SafeJson.parse(body);
            if (!e.isJsonObject()) return NO_KITS;
            JsonObject o = e.getAsJsonObject();
            boolean known = false, need = false;
            JsonElement n = o.get("needKitsReport");
            if (n != null && n.isJsonPrimitive() && n.getAsJsonPrimitive().isBoolean()) {
                known = true;
                need = n.getAsBoolean();
            }
            KitSettings settings = null;
            String invalid = null;
            JsonElement k = o.get("kits");
            if (k != null && !k.isJsonNull()) {
                try {
                    settings = KitSettings.parse(k);
                } catch (KitSettings.Invalid ex) {
                    invalid = ex.getMessage();
                }
            }
            return new KitsReply(known, need, settings, invalid);
        } catch (RuntimeException ex) {
            return NO_KITS;
        }
    }

    /**
     * What a heartbeat reply says about craft orders. {@code hash}: the server's hash of the stash's craft settings (null when the
     * reply has none: an older server, or its settings file couldn't be read). {@code settings}: a valid {@code craft} object, else
     * null. {@code invalid}: why a {@code craft} object was refused (null when there was none or it was fine). {@code recipesHash}:
     * the server's tableHash for the stash's version (null when not said).
     */
    public record CraftReply(String hash, CraftSettings settings, String invalid, String recipesHash) {}

    private static final CraftReply NO_CRAFT = new CraftReply(null, null, null, null);

    /** Reads the craft fields of a heartbeat reply. Missing or broken fields mean nothing was said; a bad {@code craft} object is refused whole. */
    public static CraftReply craftReply(String body) {
        try {
            JsonElement e = com.porter.qwerte.craft.SafeJson.parse(body);
            if (!e.isJsonObject()) return NO_CRAFT;
            JsonObject o = e.getAsJsonObject();
            String hash = hex40(o.get("craftHash")), recipes = hex40(o.get("recipesHash"));
            CraftSettings settings = null;
            String invalid = null;
            JsonElement c = o.get("craft");
            if (c != null && !c.isJsonNull()) {
                try {
                    settings = CraftSettings.parse(c);
                } catch (CraftSettings.Invalid ex) {
                    invalid = ex.getMessage();
                }
            }
            return new CraftReply(hash, settings, invalid, recipes);
        } catch (RuntimeException ex) {
            return NO_CRAFT;
        }
    }

    private static JsonObject areaObject(int[] box, String dimension) {
        JsonObject a = new JsonObject();
        a.add("box", boxArray(box));
        a.addProperty("dimension", dimension);
        return a;
    }

    private static JsonArray boxArray(int[] box) {
        JsonArray a = new JsonArray();
        for (int v : box) a.add(v);
        return a;
    }

    /**
     * What a heartbeat reply says about the stash area. {@code hash}: the server's {@code areaHash} (null when absent: an older server).
     * {@code areaPresent}: the reply has an {@code area} key (an object, or null for "cleared"). {@code invalid}: why that area was refused.
     */
    public record AreaReply(String hash, boolean areaPresent, AreaRules.Parsed area, String invalid) {}

    private static final AreaReply NO_AREA = new AreaReply(null, false, null, null);

    /** Reads the area fields of a heartbeat reply. Missing or broken fields mean nothing was said; a bad {@code area} object is refused. */
    public static AreaReply areaReply(String body) {
        try {
            JsonElement e = com.porter.qwerte.craft.SafeJson.parse(body);
            if (!e.isJsonObject()) return NO_AREA;
            JsonObject o = e.getAsJsonObject();
            String hash = hex40(o.get("areaHash"));
            if (!o.has("area")) return new AreaReply(hash, false, null, null);
            JsonElement a = o.get("area");
            if (a.isJsonNull()) return new AreaReply(hash, true, null, null);
            try {
                return new AreaReply(hash, true, AreaRules.parse(a), null);
            } catch (AreaRules.Invalid ex) {
                return new AreaReply(hash, true, null, ex.getMessage());
            }
        } catch (RuntimeException ex) {
            return NO_AREA;
        }
    }

    private static String hex40(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return null;
        String s = e.getAsString();
        return s.matches("^[0-9a-f]{40}$") ? s : null;
    }

    private static String mcVersion() {
        try {
            return com.porter.qwerte.compat.GameVersion.name();
        } catch (RuntimeException | LinkageError e) {
            return "unknown";
        }
    }

    private static void add(Map<String, Agg> groups, RegistryOps<Tag> ops, ItemStack stack, int depth) {
        if (stack == null || stack.isEmpty()) return;

        final Info found = infoOf(ops, stack);
        groups.computeIfAbsent(found.key(), k -> new Agg(found)).count += stack.getCount();

        if (depth >= MAX_DEPTH) return;
        if (Utils.isShulker(stack.getItem())) {
            for (ItemStack inner : Kits.inside(stack)) add(groups, ops, inner, depth + 1);
        }
        var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            for (ItemStack inner : bundle.items()) add(groups, ops, inner, depth + 1);
        }
    }

    private static Info infoOf(RegistryOps<Tag> ops, ItemStack stack) {
        CacheKey ck = new CacheKey(stack.getItem(), stack.getComponentsPatch().forget(Utils.isShulker(stack.getItem()) ? SHULKER_CONTENTS : CONTENTS));
        Info info = CACHE.get(ck);
        if (info == null) {
            info = describe(ops, stack, ck.patch());
            CACHE.put(ck, info);
        }
        return info;
    }

    /** The key of a stack as the index lists it, or null with no world. Only on the client thread. */
    public static String keyOf(ItemStack stack) {
        if (mc.level == null || stack == null || stack.isEmpty()) return null;
        return infoOf(mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack).key();
    }

    /**
     * The match key of a stack: the index key of its component patch with the contents, custom data (and a shulker's block entity data) and also
     * the custom name, damage and repair cost left out. Package-private so the tests can apply the real strip set to real stacks.
     */
    static String matchKey(RegistryOps<Tag> ops, ItemStack stack, boolean shulker) {
        DataComponentPatch stripped = stack.getComponentsPatch().forget(shulker ? SHULKER_CONTENTS : CONTENTS);
        return key(ops, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stripped.forget(MATCH_STRIP));
    }

    /** The match key of a stack (KIT-CONTRACT v2): its index key with the custom name, damage and repair cost left out too, or null with no world. */
    public static String matchKeyOf(ItemStack stack) {
        if (mc.level == null || stack == null || stack.isEmpty()) return null;
        return infoOf(mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack).match();
    }

    /** The occupied slots of a shulker box in slot order, as the classifier and the template match take them (id, match key, count). */
    public static List<KitTemplates.BoxStack> boxStacks(ItemStack shulker) {
        List<KitTemplates.BoxStack> out = new ArrayList<>(27);
        for (ItemStack s : Kits.inside(shulker)) {
            if (s == null || s.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            String match = matchKeyOf(s);
            out.add(new KitTemplates.BoxStack(id, match == null ? id : match, s.getCount()));
        }
        return out;
    }

    private record ClassKey(Item item, DataComponentPatch patch, String typesHash) {}

    private static final Map<ClassKey, KitClassifier.Result> CLASSES = new HashMap<>();

    /** What the classifier makes of a table that can't be read: never a kit. */
    private static final KitClassifier.Result NO_TABLE = new KitClassifier.Result(false, KitClassifier.NO_PURPOSE, null, 0, 0, 0, 0, 0, List.of(), List.of(), null, List.of());

    /** The normalised custom name of a stack (KIT-CONTRACT v1.1 normalize), or null when it has none. */
    public static String normalizedName(ItemStack stack) {
        String name = customName(stack);
        return name == null ? null : KitNames.normalize(name);
    }

    /**
     * The kit classifier's verdict for a shulker box (KIT-CONTRACT v2): null for anything but a shulker box. Worked out once per item, components
     * (name, contents) and v2 settings, then remembered. A table that can't be read means no kit. Client thread only.
     */
    public static KitClassifier.Result classify(ItemStack stack, KitSettings settings) {
        if (stack == null || stack.isEmpty() || !Utils.isShulker(stack.getItem())) return null;
        KitTypes table = KitTypes.shared();
        if (table == null) return NO_TABLE;
        KitSettings.Types types = settings == null ? null : settings.types();
        if (CLASSES.size() > MAX_CACHE) CLASSES.clear();
        return CLASSES.computeIfAbsent(new ClassKey(stack.getItem(), stack.getComponentsPatch(), types == null ? "" : types.hash()),
            k -> KitClassifier.classify(table, boxStacks(stack), normalizedName(stack), types));
    }

    /** The kit template a shulker is a copy of (complete or part built), or null. */
    public static KitTemplates.Found templateOf(ItemStack stack, KitSettings settings) {
        if (settings == null || settings.types() == null || settings.types().templates().isEmpty() || stack == null || stack.isEmpty() || !Utils.isShulker(stack.getItem())) return null;
        // Worked out once per item, components and v2 settings, like the classification (a report of a big stash asks for every shulker)
        if (FOUNDS.size() > MAX_CACHE) FOUNDS.clear();
        return FOUNDS.computeIfAbsent(new ClassKey(stack.getItem(), stack.getComponentsPatch(), settings.types().hash()),
            k -> java.util.Optional.ofNullable(KitTemplates.find(settings.types().templates(), boxStacks(stack), normalizedName(stack)))).orElse(null);
    }

    private static final Map<ClassKey, java.util.Optional<KitTemplates.Found>> FOUNDS = new HashMap<>();

    /** The group key ("s:...") the index gives a shulker with this content, or null if it is not a shulker or is empty. */
    public static String groupKey(ItemStack stack) {
        if (mc.level == null || stack == null || stack.isEmpty() || !Utils.isShulker(stack.getItem())) return null;
        Shulker sh = describeShulker(mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack);
        return sh == null ? null : sh.key;
    }

    /** Records a top-level shulker with something in it. */
    private static void addShulker(Map<String, Shulker> shulkers, RegistryOps<Tag> ops, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !Utils.isShulker(stack.getItem())) return;
        Shulker found = describeShulker(ops, stack);
        if (found == null) return;
        Shulker sh = shulkers.get(found.key);
        if (sh == null) {
            sh = found;
            shulkers.put(sh.key, sh);
        }
        sh.count++;
    }

    private static Shulker describeShulker(RegistryOps<Tag> ops, ItemStack stack) {

        ItemStack[] inside = Kits.inside(stack);
        List<int[]> slots = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<String> matches = new ArrayList<>();
        StringBuilder canon = new StringBuilder();
        for (int slot = 0; slot < inside.length; slot++) {
            ItemStack inner = inside[slot];
            if (inner == null || inner.isEmpty()) continue;
            Info innerInfo = infoOf(ops, inner);
            String k = innerInfo.key();
            slots.add(new int[] { slot, inner.getCount() });
            keys.add(k);
            matches.add(innerInfo.match());
            if (canon.length() > 0) canon.append(',');
            canon.append(slot).append(':').append(k).append(':').append(inner.getCount());
        }
        if (slots.isEmpty()) return null;

        Info info = infoOf(ops, stack);
        Shulker sh = new Shulker(info, "s:" + sha1(info.key() + "|" + canon).substring(0, 10));
        sh.slots.addAll(slots);
        sh.keys.addAll(keys);
        sh.matches.addAll(matches);
        return sh;
    }

    /** The creative tab of an item and its place in it, built from the game's own tabs the first time it is needed. */
    private static TabSlot tabOf(Item item) {
        if (tabs == null) tabs = buildTabs();
        TabSlot slot = tabs.get(item);
        return slot != null ? slot : new TabSlot("other", OTHER_ORDER);
    }

    private static Map<Item, TabSlot> buildTabs() {
        Map<Item, TabSlot> map = new HashMap<>();
        try {
            Map<String, net.minecraft.world.item.CreativeModeTab> byPath = new HashMap<>();
            for (var tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
                if (tab.getType() != net.minecraft.world.item.CreativeModeTab.Type.CATEGORY) continue;
                var id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
                if (id != null) byPath.put(id.getPath(), tab);
            }

            // Only built if the game has not (the creative screen does it when opened). Building it again could change what
            // an open creative screen shows, so never while one is open
            boolean built = false;
            for (var tab : byPath.values()) if (!tab.getDisplayItems().isEmpty()) built = true;
            if (!built && !(mc.screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen) && mc.level != null) {
                net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(mc.level.enabledFeatures(), false, mc.level.registryAccess());
            }

            for (String path : TABS) {
                var tab = byPath.get(path);
                if (tab == null) continue;
                int n = 0;
                for (ItemStack shown : tab.getDisplayItems()) {
                    map.putIfAbsent(shown.getItem(), new TabSlot(path, n));
                    n++;
                }
            }
        } catch (Throwable t) {
            map.clear();
            QwerteAddon.LOG.warn("stash-manager: could not read the creative tabs: {}", t.toString());
        }
        if (map.isEmpty() && !tabsWarned) {
            tabsWarned = true;
            QwerteAddon.LOG.warn("stash-manager: no creative tab contents, every item goes in \"other\"");
        }
        return map;
    }

    private static Info describe(RegistryOps<Tag> ops, ItemStack stack, DataComponentPatch stripped) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        StackInfo info = StackInfo.of(stack);
        TabSlot tab = tabOf(stack.getItem());
        return new Info(key(ops, id, stripped), id, stack.getHoverName().getString(), englishName(stack), stack.getMaxStackSize(),
            details(info), info != null && !info.enchants.isEmpty(), tab.tab(), tab.order(),
            durability(stack.getDamageValue(), stack.getMaxDamage())[0], durability(stack.getDamageValue(), stack.getMaxDamage())[1], Mending.has(stack),
            key(ops, id, stripped.forget(MATCH_STRIP)));
    }

    /** {damage, maxDamage} to send for an item, or {0, 0} to send neither: only damageable (maxDamage > 0) and damaged (damage > 0). */
    public static int[] durability(int damage, int maxDamage) {
        return maxDamage > 0 && damage > 0 ? new int[] {damage, maxDamage} : new int[] {0, 0};
    }

    private static String key(RegistryOps<Tag> ops, String id, DataComponentPatch stripped) {
        if (stripped.isEmpty()) return id;
        var encoded = DataComponentPatch.CODEC.encodeStart(ops, stripped).result();
        if (encoded.isEmpty()) {
            if (!encodeWarned) {
                encodeWarned = true;
                QwerteAddon.LOG.warn("stash-manager: couldn't encode the components of {}, using the bare id as its key", id);
            }
            return id;
        }
        return id + "#" + sha1(encoded.get().toString()).substring(0, 8);
    }

    private static List<String> details(StackInfo info) {
        List<String> out = new ArrayList<>();
        if (info == null) return out;
        info.levels.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> out.add(title(e.getKey()) + level(e.getValue())));
        info.storedLevels.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> out.add(title(e.getKey()) + level(e.getValue())));
        if (info.potion != null) out.add(title(info.potion));
        return out;
    }

    private static String level(int n) {
        String[] roman = { "", " I", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X" };
        return n >= 1 && n < roman.length ? roman[n] : " " + n;
    }

    private static Map<String, net.minecraft.world.item.Item> itemsById;

    /** The English name for an index key, like "Block of Gold", for messages. */
    public static String nameOf(String key) {
        if (key == null) return "?";
        int hash = key.indexOf('#');
        String id = hash < 0 ? key : key.substring(0, hash);
        if (itemsById == null) {
            itemsById = new HashMap<>();
            for (Item item : BuiltInRegistries.ITEM) itemsById.put(BuiltInRegistries.ITEM.getKey(item).toString(), item);
        }
        Item item = itemsById.get(id);
        if (lang == null) loadLang();
        String name = item == null ? null : lang.get(item.getDescriptionId());
        if (name == null) name = title(id.substring(id.indexOf(':') + 1));
        return hash < 0 ? name : name + " (variant)";
    }

    private static String englishName(ItemStack stack) {
        if (lang == null) loadLang();
        String name = lang.get(stack.getItem().getDescriptionId());
        if (name != null) return name;
        return title(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
    }

    private static synchronized void loadLang() {
        if (lang != null) return;
        Map<String, String> map = new HashMap<>();
        try (var in = net.minecraft.client.Minecraft.class.getResourceAsStream("/assets/minecraft/lang/en_us.json")) {
            if (in == null) throw new java.io.IOException("not found on the classpath");
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : o.entrySet()) {
                if (e.getValue().isJsonPrimitive()) map.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (Exception e) {
            if (!langWarned) {
                langWarned = true;
                QwerteAddon.LOG.warn("stash-manager: couldn't read en_us.json, using item ids as names: {}", e.toString());
            }
        }
        lang = map;
    }

    private static String title(String path) {
        StringBuilder sb = new StringBuilder();
        for (String word : path.replace('/', '_').split("_")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return sb.toString();
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

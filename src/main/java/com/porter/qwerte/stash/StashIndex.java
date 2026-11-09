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
        JsonObject o = new JsonObject();
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
        StringBuilder canon = new StringBuilder();
        for (int slot = 0; slot < inside.length; slot++) {
            ItemStack inner = inside[slot];
            if (inner == null || inner.isEmpty()) continue;
            String k = infoOf(ops, inner).key();
            slots.add(new int[] { slot, inner.getCount() });
            keys.add(k);
            if (canon.length() > 0) canon.append(',');
            canon.append(slot).append(':').append(k).append(':').append(inner.getCount());
        }
        if (slots.isEmpty()) return null;

        Info info = infoOf(ops, stack);
        Shulker sh = new Shulker(info, "s:" + sha1(info.key() + "|" + canon).substring(0, 10));
        sh.slots.addAll(slots);
        sh.keys.addAll(keys);
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
            durability(stack.getDamageValue(), stack.getMaxDamage())[0], durability(stack.getDamageValue(), stack.getMaxDamage())[1], Mending.has(stack));
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

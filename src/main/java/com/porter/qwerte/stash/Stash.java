package com.porter.qwerte.stash;

import com.porter.qwerte.QwerteAddon;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * A named stash: its containers, what was in them when last seen, and which ones are excluded from sorting or used as
 * drop-off points. Saved per server under {@code meteor-client/qwerte/stashes}, so a stash is only scanned once and
 * then kept up to date as containers are opened.
 */
public class Stash {
    public static class Box {
        public final BlockPos pos;
        public final List<BlockPos> parts;
        public final List<String> labels = new ArrayList<>();
        public ItemStack[] contents;
        public boolean excluded;
        public boolean dropOff;
        // Where finished orders are delivered. Also excluded, so nothing is ever sorted into or out of it
        public boolean delivery;
        // What excluded was before this became the delivery container, restored when it stops being one
        public boolean wasExcluded, wasDropOff;
        // When its contents were last read (milliseconds); 0 for stashes saved before this was kept, which counts as old
        public long lastRead;
        public boolean unreachable;
        public int order;
        // The text of the sign the sorter put up on this container itself, and where, saved with the stash
        public String autoLabel;
        public BlockPos autoSignPos;
        // Putting a sign on it failed this run
        public boolean labelFailed;
        // Never used to gather stacks or shulkers for compacting
        public boolean noCompact;
        // Its contents are from an earlier visit and are due to be read again. Not saved. Searching and planning go on
        // with what is known, but nothing is taken from it until it has been read
        public boolean stale;

        public Box(BlockPos pos, List<BlockPos> parts) {
            this.pos = pos;
            this.parts = parts;
        }

        public boolean known() {
            return contents != null;
        }

        public int free() {
            int count = 0;
            for (ItemStack stack : contents) if (stack.isEmpty()) count++;
            return count;
        }

        /**
         * Joins in sorting: not excluded, read at least once and possible to open.
         */
        public boolean sortable() {
            return known() && !unreachable && !excluded;
        }

        public boolean has(BlockPos pos) {
            return parts.contains(pos);
        }
    }

    /**
     * A shulker with stacks in it the sorter put down and could not pick up again.
     */
    public static class Orphan {
        public final BlockPos pos;
        public final String item;
        public final int stacks;
        public final String when;
        // The dimension it stands in, empty if unknown
        public final String dimension;

        public Orphan(BlockPos pos, String item, int stacks, String when, String dimension) {
            this.pos = pos;
            this.item = item;
            this.stacks = stacks;
            this.when = when;
            this.dimension = dimension == null ? "" : dimension;
        }
    }

    /**
     * The compaction a standing shulker belongs to: what is being packed and where it is.
     */
    public static class Compact {
        public ItemStack sample = ItemStack.EMPTY;
        public String name = "";
        public boolean topUp;
        public int startSize, target;
        public BlockPos sourceBox;
        public int sourceSlot = -1;
        public BlockPos placedPos;
        public int placedSize, jobs;
        public boolean aborting;
    }

    /**
     * A shulker step that was under way when the sorter stopped or the connection dropped, saved so the next start can
     * finish it: the shulker standing in the world, what it is for and what state the work was in.
     */
    public static class InFlight {
        public static final int VERSION = 1;
        public int version = VERSION;
        public long savedAt;
        public String dimension = "";
        public String job = "", handle = "", returnTo = "";
        public BlockPos placed;
        public boolean confirmed;
        public String placedItemId = "";
        public int placedStacks;
        // A stack of what is being packed, and the item a mixed shulker is mostly full of
        public ItemStack fillSample = ItemStack.EMPTY;
        public String cleanMainId = "";
        public BlockPos lastGoodStand;
        public BlockPos target;
        public Compact compact;
    }

    /**
     * The order the bot is working on, saved so a restart carries on instead of starting again: its id, how many of its
     * units (shulkers to deliver) are already delivered, and what was delivered per line key.
     */
    public static class ActiveOrder {
        /** One shulker of the plan: kind 0 = packed shulker (key = group key), 1 = empty shulker (key = item key), 2 = built from lots. */
        public static class UnitPlan {
            public int kind;
            public String key = "";
            public final List<String> lotKeys = new ArrayList<>();
            public final List<Integer> lotCounts = new ArrayList<>();
            /** How naming went: done (named or given up on), named, why not, and the key it has with its name. */
            public boolean nameDone, named;
            public String nameFailure = "", namedKey = "";
        }

        public String id = "";
        public int done;
        public int shulkersDelivered;
        public final java.util.Map<String, Integer> delivered = new java.util.LinkedHashMap<>();
        /** The order's lines as {type, key, count}, to report against when it is resumed. */
        public final List<String[]> lines = new ArrayList<>();
        /** The planned shulkers, so a resume carries on the same plan whatever the stash holds by then. */
        public final List<UnitPlan> units = new ArrayList<>();
        /** The order's shulker names (see {@link Order.Names}): mode "" for none. */
        public String namesMode = "";
        public String namesAll = "";
        public final List<String> namesEach = new ArrayList<>();
        /** Shulkers delivered without the name they were meant to get, and why (distinct reasons), for the order's note. */
        public int unnamed;
        public final List<String> unnamedReasons = new ArrayList<>();
        /** Shulker boxes crafted for this order so far (the per-order cap and the end message count them). */
        public int crafted;
    }

    public ActiveOrder activeOrder;
    public final List<Orphan> orphans = new ArrayList<>();
    // The stacks the sorter took out of the stash and carries, by inventory slot, so they are still known as the stash's
    // after a restart
    public final java.util.Map<Integer, ItemStack> held = new java.util.LinkedHashMap<>();
    public InFlight inFlight;
    public final String name;
    public final String server;
    public final List<Box> boxes = new ArrayList<>();
    private boolean dirty;

    // The perimeter: two corners (inclusive), either may be unset. It only applies once both are
    public BlockPos corner1, corner2;

    public static final int MAX_X = 256, MAX_Y = 128, MAX_Z = 256;

    public String areaDimension = "";
    // The delivery container, kept on the stash itself so that no pruning of the box list (a perimeter that leaves it out,
    // say) can lose it. It stays until the user clears it
    public BlockPos deliveryPos;
    // The anvil orders are named at. Not a container: never a box, never indexed. Stays until the user clears it
    public BlockPos anvilPos;
    // How the anvil came to be remembered: "set" (picked by hand), "found" (placed, found in the area) or "placed" (taken from
    // the stash and placed). "" counts as set
    public String anvilSource = "";
    // The crafting table orders craft at. Not a container: never a box, never indexed. Stays until the user clears it
    public BlockPos craftingTablePos;

    /**
     * Makes sure the delivery container is among the boxes: the box flagged as one gives the position, else the saved
     * position is made a box again.
     */
    public void ensureDelivery() {
        for (Box b : boxes) {
            if (b.delivery) {
                deliveryPos = b.pos;
                return;
            }
        }
        if (deliveryPos == null) return;

        Box box = new Box(deliveryPos, new ArrayList<>(List.of(deliveryPos)));
        box.delivery = true;
        box.excluded = true;
        boxes.add(box);
        dirty = true;
    }
    // The dimension the stash is in, noted when a container of it is first read; empty for stashes saved before
    public String dimension = "";
    private static Object cachedLevel;
    private static String cachedDimension = "";

    /** The dimension of the level we are in, cached per level. */
    public static String currentDimension() {
        if (mc.level == null) return "";
        if (mc.level != cachedLevel) {
            cachedLevel = mc.level;
            cachedDimension = mc.level.dimension().toString();
        }
        return cachedDimension;
    }

    /** Both corners are set, whatever dimension they are in. */
    public boolean hasCorners() {
        return corner1 != null && corner2 != null;
    }

    /** The perimeter applies: both corners are set, and we are in the dimension they were set in. */
    public boolean hasArea() {
        return corner1 != null && corner2 != null && (areaDimension.isEmpty() || areaDimension.equals(currentDimension()));
    }

    public BlockPos areaMin() {
        return new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
    }

    public BlockPos areaMax() {
        return new BlockPos(Math.max(corner1.getX(), corner2.getX()), Math.max(corner1.getY(), corner2.getY()), Math.max(corner1.getZ(), corner2.getZ()));
    }

    /**
     * Whether the position is inside the perimeter, grown by {@code margin} blocks on every side. False without one.
     */
    public boolean inArea(double x, double y, double z, int margin) {
        if (!hasArea()) return false;
        BlockPos min = areaMin(), max = areaMax();
        return x >= min.getX() - margin && x < max.getX() + 1 + margin && y >= min.getY() - margin && y < max.getY() + 1 + margin
            && z >= min.getZ() - margin && z < max.getZ() + 1 + margin;
    }

    public boolean inArea(BlockPos pos) {
        return inArea(pos.getX(), pos.getY(), pos.getZ(), 0);
    }

    /** Width, height and depth of the perimeter in blocks. */
    public int[] areaSize() {
        BlockPos min = areaMin(), max = areaMax();
        return new int[] { max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1 };
    }

    /** Whether the two corners would span more than the most allowed. */
    public static boolean tooBig(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + 1 > MAX_X || Math.abs(a.getY() - b.getY()) + 1 > MAX_Y || Math.abs(a.getZ() - b.getZ()) + 1 > MAX_Z;
    }

    /** "[x1, y1, z1] to [x2, y2, z2] (W x H x D)" for the perimeter. */
    public String areaText() {
        BlockPos min = areaMin(), max = areaMax();
        int[] size = areaSize();
        return String.format("[%d, %d, %d] to [%d, %d, %d] (%d\u00d7%d\u00d7%d)", min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ(), size[0], size[1], size[2]);
    }

    public Stash(String name, String server) {
        this.name = name;
        this.server = server;
    }

    public Box boxAt(BlockPos pos) {
        for (Box box : boxes) if (box.has(pos)) return box;
        return null;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public int knownCount() {
        return (int) boxes.stream().filter(Box::known).count();
    }

    public int itemCount() {
        int count = 0;
        for (Box box : boxes) {
            if (!box.known()) continue;
            for (ItemStack stack : box.contents) count += stack.getCount();
        }
        return count;
    }

    // Searching

    /**
     * One kind of item found in the stash: how many in total, and where.
     */
    public static class Result {
        public final ItemStack sample;
        public final List<Spot> spots = new ArrayList<>();
        public int total;
        int score;

        Result(ItemStack sample) {
            this.sample = sample.copyWithCount(1);
        }

        public Spot best() {
            return spots.stream().max(Comparator.comparingInt(s -> s.count)).orElse(null);
        }
    }

    public record Spot(Box box, int count, boolean inShulker) {}

    /**
     * Everything matching {@code query}, best match first, counting items inside shulkers too.
     */
    public List<Result> search(String query) {
        List<Result> results = new ArrayList<>();
        if (Categories.normalize(query).isEmpty()) return results;

        for (Box box : boxes) {
            if (!box.known()) continue;

            for (ItemStack stack : box.contents) {
                if (stack.isEmpty()) continue;
                add(results, query, stack, box, false);

                if (Utils.isShulker(stack.getItem())) {
                    ItemStack[] inside = new ItemStack[27];
                    Utils.getItemsInContainerItem(stack, inside);
                    for (ItemStack inner : inside) {
                        if (inner != null && !inner.isEmpty()) add(results, query, inner, box, true);
                    }
                }
            }
        }

        results.sort(Comparator.<Result>comparingInt(r -> -r.score).thenComparingInt(r -> -r.total));
        return results;
    }

    private static void add(List<Result> results, String query, ItemStack stack, Box box, boolean inShulker) {
        int score = Categories.searchScore(query, stack);
        if (score <= 0) return;

        Result result = null;
        for (Result r : results) {
            if (ItemStack.isSameItemSameComponents(r.sample, stack)) {
                result = r;
                break;
            }
        }
        if (result == null) {
            result = new Result(stack);
            result.score = score;
            results.add(result);
        }

        result.total += stack.getCount();
        Spot spot = null;
        for (Spot s : result.spots) {
            if (s.box() == box && s.inShulker() == inShulker) {
                spot = s;
                break;
            }
        }
        if (spot != null) result.spots.set(result.spots.indexOf(spot), new Spot(box, spot.count() + stack.getCount(), inShulker));
        else result.spots.add(new Spot(box, stack.getCount(), inShulker));
    }

    // Saving and loading

    private static Path folder() {
        return MeteorClient.FOLDER.toPath().resolve("qwerte").resolve("stashes");
    }

    private static Path file(String name, String server) {
        String safe = (server + "_" + name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return folder().resolve(safe + ".nbt");
    }

    public static String currentServer() {
        if (mc.isSingleplayer()) return "singleplayer";
        return mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "unknown";
    }

    public static boolean exists(String name) {
        return Files.exists(file(name, currentServer()));
    }

    public static void delete(String name) {
        try {
            Files.deleteIfExists(file(name, currentServer()));
        } catch (IOException e) {
            QwerteAddon.LOG.error("Couldn't delete stash {}", name, e);
        }
    }

    // The registries of the last world we saw, so the stash can still be written while the world is being left
    private static net.minecraft.core.RegistryAccess lastRegistries;

    /** Remembers the registries of the world we are in, for a save made after it is gone. */
    public static void remember() {
        if (mc.level != null) lastRegistries = mc.level.registryAccess();
    }

    public void save() {
        remember();
        net.minecraft.core.RegistryAccess registries = mc.level != null ? mc.level.registryAccess() : lastRegistries;
        if (registries == null) return;
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);

        CompoundTag root = new CompoundTag();
        root.putString("name", name);
        root.putString("server", server);

        ListTag list = new ListTag();
        for (Box box : boxes) {
            CompoundTag tag = new CompoundTag();
            tag.put("parts", positions(box.parts));
            tag.putBoolean("excluded", box.excluded);
            tag.putBoolean("dropOff", box.dropOff);
            if (box.lastRead > 0) tag.putLong("readAt", box.lastRead);
            if (box.delivery) tag.putBoolean("delivery", true);
            if (box.wasExcluded) tag.putBoolean("wasExcluded", true);
            if (box.wasDropOff) tag.putBoolean("wasDropOff", true);
            if (box.noCompact) tag.putBoolean("noCompact", true);
            if (box.autoLabel != null) tag.putString("autoLabel", box.autoLabel);
            if (box.autoSignPos != null) tag.put("autoSignPos", positions(List.of(box.autoSignPos)));

            if (box.known()) {
                tag.putInt("size", box.contents.length);
                ListTag items = new ListTag();
                for (int i = 0; i < box.contents.length; i++) {
                    if (box.contents[i].isEmpty()) continue;
                    int slot = i;
                    ItemStack.CODEC.encodeStart(ops, box.contents[i]).result().ifPresent(item -> {
                        CompoundTag entry = new CompoundTag();
                        entry.putInt("slot", slot);
                        entry.put("item", item);
                        items.add(entry);
                    });
                }
                tag.put("items", items);
            }

            list.add(tag);
        }
        root.put("boxes", list);

        ListTag lost = new ListTag();
        for (Orphan orphan : orphans) {
            CompoundTag tag = new CompoundTag();
            tag.put("pos", positions(List.of(orphan.pos)));
            tag.putString("item", orphan.item);
            tag.putInt("stacks", orphan.stacks);
            tag.putString("when", orphan.when);
            tag.putString("dimension", orphan.dimension);
            lost.add(tag);
        }
        root.put("orphans", lost);

        ListTag heldList = new ListTag();
        for (var entry : held.entrySet()) {
            int slot = entry.getKey();
            ItemStack.CODEC.encodeStart(ops, entry.getValue()).result().ifPresent(item -> {
                CompoundTag tag = new CompoundTag();
                tag.putInt("slot", slot);
                tag.put("item", item);
                heldList.add(tag);
            });
        }
        root.put("held", heldList);

        if (inFlight != null) root.put("inFlight", writeInFlight(inFlight, ops));

        if (activeOrder != null) {
            CompoundTag order = new CompoundTag();
            order.putString("id", activeOrder.id);
            order.putInt("done", activeOrder.done);
            order.putInt("shulkers", activeOrder.shulkersDelivered);
            ListTag orderLines = new ListTag();
            for (String[] l : activeOrder.lines) {
                CompoundTag line = new CompoundTag();
                line.putString("type", l[0]);
                line.putString("key", l[1]);
                line.putInt("count", Integer.parseInt(l[2]));
                orderLines.add(line);
            }
            order.put("lines", orderLines);
            ListTag planned = new ListTag();
            for (ActiveOrder.UnitPlan u : activeOrder.units) {
                CompoundTag unit = new CompoundTag();
                unit.putInt("kind", u.kind);
                unit.putString("key", u.key);
                ListTag lots = new ListTag();
                for (int i = 0; i < u.lotKeys.size(); i++) {
                    CompoundTag lot = new CompoundTag();
                    lot.putString("key", u.lotKeys.get(i));
                    lot.putInt("count", u.lotCounts.get(i));
                    lots.add(lot);
                }
                unit.put("lots", lots);
                if (u.nameDone) unit.putBoolean("nameDone", true);
                if (u.named) unit.putBoolean("named", true);
                if (!u.nameFailure.isEmpty()) unit.putString("nameFailure", u.nameFailure);
                if (!u.namedKey.isEmpty()) unit.putString("namedKey", u.namedKey);
                planned.add(unit);
            }
            order.put("units", planned);
            if (!activeOrder.namesMode.isEmpty()) {
                order.putString("namesMode", activeOrder.namesMode);
                order.putString("namesAll", activeOrder.namesAll);
                ListTag each = new ListTag();
                for (String n : activeOrder.namesEach) each.add(StringTag.valueOf(n));
                order.put("namesEach", each);
            }
            if (activeOrder.crafted > 0) order.putInt("crafted", activeOrder.crafted);
            if (activeOrder.unnamed > 0) {
                order.putInt("unnamed", activeOrder.unnamed);
                ListTag why = new ListTag();
                for (String r : activeOrder.unnamedReasons) why.add(StringTag.valueOf(r));
                order.put("unnamedWhy", why);
            }
            ListTag lines = new ListTag();
            for (var entry : activeOrder.delivered.entrySet()) {
                CompoundTag line = new CompoundTag();
                line.putString("key", entry.getKey());
                line.putInt("count", entry.getValue());
                lines.add(line);
            }
            order.put("delivered", lines);
            root.put("activeOrder", order);
        }

        if (corner1 != null) root.put("corner1", positions(List.of(corner1)));
        if (corner2 != null) root.put("corner2", positions(List.of(corner2)));
        if (!areaDimension.isEmpty()) root.putString("areaDimension", areaDimension);
        if (deliveryPos != null) root.put("deliveryPos", positions(List.of(deliveryPos)));
        if (anvilPos != null) root.put("anvilPos", positions(List.of(anvilPos)));
        if (anvilPos != null && !anvilSource.isEmpty()) root.putString("anvilSource", anvilSource);
        if (craftingTablePos != null) root.put("craftingTablePos", positions(List.of(craftingTablePos)));
        if (!dimension.isEmpty()) root.putString("dimension", dimension);

        try {
            Files.createDirectories(folder());
            // Written beside the file and moved over it, so a crash in the middle never leaves half a stash
            Path target = file(name, server);
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, temp);
            try {
                Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            QwerteAddon.LOG.error("Couldn't save stash {}", name, e);
        }
    }

    private static CompoundTag writeInFlight(InFlight f, RegistryOps<Tag> ops) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", f.version);
        tag.putLong("savedAt", f.savedAt);
        tag.putString("dimension", f.dimension);
        tag.putString("job", f.job);
        tag.putString("handle", f.handle);
        tag.putString("returnTo", f.returnTo);
        if (f.placed != null) tag.put("placed", positions(List.of(f.placed)));
        tag.putBoolean("confirmed", f.confirmed);
        tag.putString("placedItemId", f.placedItemId);
        tag.putInt("placedStacks", f.placedStacks);
        if (!f.fillSample.isEmpty()) ItemStack.CODEC.encodeStart(ops, f.fillSample).result().ifPresent(item -> tag.put("fillSample", item));
        tag.putString("cleanMainId", f.cleanMainId);
        if (f.lastGoodStand != null) tag.put("lastGoodStand", positions(List.of(f.lastGoodStand)));
        if (f.target != null) tag.put("target", positions(List.of(f.target)));
        if (f.compact != null) {
            Compact c = f.compact;
            CompoundTag ct = new CompoundTag();
            if (!c.sample.isEmpty()) ItemStack.CODEC.encodeStart(ops, c.sample).result().ifPresent(item -> ct.put("sample", item));
            ct.putString("name", c.name);
            ct.putBoolean("topUp", c.topUp);
            ct.putInt("startSize", c.startSize);
            ct.putInt("target", c.target);
            if (c.sourceBox != null) ct.put("sourceBox", positions(List.of(c.sourceBox)));
            ct.putInt("sourceSlot", c.sourceSlot);
            if (c.placedPos != null) ct.put("placedPos", positions(List.of(c.placedPos)));
            ct.putInt("placedSize", c.placedSize);
            ct.putInt("jobs", c.jobs);
            ct.putBoolean("aborting", c.aborting);
            tag.put("compact", ct);
        }
        return tag;
    }

    private static BlockPos readPos(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof ListTag list)) return null;
        List<BlockPos> at = readPositions(list);
        return at.isEmpty() ? null : at.getFirst();
    }

    private static String text(CompoundTag tag, String key) {
        String s = string(tag.get(key));
        return s == null ? "" : s;
    }

    private static ItemStack readStack(RegistryOps<Tag> ops, Tag tag) {
        if (tag == null) return ItemStack.EMPTY;
        return ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
    }

    private static InFlight readInFlight(CompoundTag tag, RegistryOps<Tag> ops) {
        InFlight f = new InFlight();
        f.version = number(tag.get("version"), 0);
        // From a version this one doesn't understand: ignored
        if (f.version != InFlight.VERSION) return null;
        f.savedAt = longNumber(tag.get("savedAt"));
        f.dimension = text(tag, "dimension");
        f.job = text(tag, "job");
        f.handle = text(tag, "handle");
        f.returnTo = text(tag, "returnTo");
        f.placed = readPos(tag, "placed");
        f.confirmed = flag(tag, "confirmed");
        f.placedItemId = text(tag, "placedItemId");
        f.placedStacks = number(tag.get("placedStacks"), 0);
        f.fillSample = readStack(ops, tag.get("fillSample"));
        f.cleanMainId = text(tag, "cleanMainId");
        f.lastGoodStand = readPos(tag, "lastGoodStand");
        f.target = readPos(tag, "target");
        if (f.placed == null) return null;
        if (tag.get("compact") instanceof CompoundTag ct) {
            Compact c = new Compact();
            c.sample = readStack(ops, ct.get("sample"));
            c.name = text(ct, "name");
            c.topUp = flag(ct, "topUp");
            c.startSize = number(ct.get("startSize"), 0);
            c.target = number(ct.get("target"), 0);
            c.sourceBox = readPos(ct, "sourceBox");
            c.sourceSlot = number(ct.get("sourceSlot"), -1);
            c.placedPos = readPos(ct, "placedPos");
            c.placedSize = number(ct.get("placedSize"), 0);
            c.jobs = number(ct.get("jobs"), 0);
            c.aborting = flag(ct, "aborting");
            // Without the item there is nothing to rebuild the plan from
            if (!c.sample.isEmpty()) f.compact = c;
        }
        return f;
    }

    /**
     * The stash called {@code name} on this server, or null if there isn't one saved.
     */
    public static Stash load(String name) {
        String server = currentServer();
        Path path = file(name, server);
        if (!Files.exists(path) || mc.level == null) return null;

        RegistryOps<Tag> ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Stash stash = new Stash(name, server);
        boolean[] errors = { false };

        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            // Saved before the perimeter existed: there are no corners, which is no perimeter
            if (root.get("corner1") instanceof ListTag c1) {
                List<BlockPos> at = readPositions(c1);
                if (!at.isEmpty()) stash.corner1 = at.getFirst();
            }
            if (root.get("corner2") instanceof ListTag c2) {
                List<BlockPos> at = readPositions(c2);
                if (!at.isEmpty()) stash.corner2 = at.getFirst();
            }
            String areaDim = string(root.get("areaDimension"));
            if (areaDim != null) stash.areaDimension = areaDim;
            if (root.get("deliveryPos") instanceof ListTag dp) {
                List<BlockPos> at = readPositions(dp);
                if (!at.isEmpty()) stash.deliveryPos = at.getFirst();
            }
            if (root.get("anvilPos") instanceof ListTag ap) {
                List<BlockPos> at = readPositions(ap);
                if (!at.isEmpty()) stash.anvilPos = at.getFirst();
            }
            String anvilSource = string(root.get("anvilSource"));
            if (anvilSource != null && stash.anvilPos != null) stash.anvilSource = anvilSource;
            if (root.get("craftingTablePos") instanceof ListTag ct) {
                List<BlockPos> at = readPositions(ct);
                if (!at.isEmpty()) stash.craftingTablePos = at.getFirst();
            }
            String dim = string(root.get("dimension"));
            if (dim != null) stash.dimension = dim;
            if (root.get("held") instanceof ListTag heldList) {
                for (Tag t : heldList) {
                    if (!(t instanceof CompoundTag tag)) continue;
                    int slot = number(tag.get("slot"), -1);
                    ItemStack stack = readStack(ops, tag.get("item"));
                    if (slot >= 0 && !stack.isEmpty()) stash.held.put(slot, stack);
                }
            }
            if (root.get("inFlight") instanceof CompoundTag flight) stash.inFlight = readInFlight(flight, ops);
            if (root.get("activeOrder") instanceof CompoundTag order) {
                ActiveOrder active = new ActiveOrder();
                active.id = text(order, "id");
                active.done = number(order.get("done"), 0);
                active.shulkersDelivered = number(order.get("shulkers"), active.done);
                if (order.get("lines") instanceof ListTag orderLines) {
                    for (Tag t : orderLines) {
                        if (t instanceof CompoundTag line) active.lines.add(new String[] { text(line, "type"), text(line, "key"), String.valueOf(number(line.get("count"), 0)) });
                    }
                }
                if (order.get("units") instanceof ListTag planned) {
                    for (Tag t : planned) {
                        if (!(t instanceof CompoundTag unit)) continue;
                        ActiveOrder.UnitPlan u = new ActiveOrder.UnitPlan();
                        u.kind = number(unit.get("kind"), 0);
                        u.key = text(unit, "key");
                        if (unit.get("lots") instanceof ListTag lots) {
                            for (Tag lt : lots) {
                                if (lt instanceof CompoundTag lot) {
                                    u.lotKeys.add(text(lot, "key"));
                                    u.lotCounts.add(number(lot.get("count"), 0));
                                }
                            }
                        }
                        u.nameDone = flag(unit, "nameDone");
                        u.named = flag(unit, "named");
                        u.nameFailure = text(unit, "nameFailure");
                        u.namedKey = text(unit, "namedKey");
                        active.units.add(u);
                    }
                }
                String namesMode = string(order.get("namesMode"));
                if (namesMode != null && !namesMode.isEmpty()) {
                    active.namesMode = namesMode;
                    String all = string(order.get("namesAll"));
                    active.namesAll = all == null ? "" : all;
                    if (order.get("namesEach") instanceof ListTag each) {
                        for (Tag t : each) active.namesEach.add(string(t) == null ? "" : string(t));
                    }
                }
                active.unnamed = number(order.get("unnamed"), 0);
                active.crafted = number(order.get("crafted"), 0);
                if (order.get("unnamedWhy") instanceof ListTag why) {
                    for (Tag t : why) if (string(t) != null) active.unnamedReasons.add(string(t));
                }
                active.done = Math.max(0, Math.min(active.done, active.units.size()));
                if (order.get("delivered") instanceof ListTag lines) {
                    for (Tag t : lines) {
                        if (t instanceof CompoundTag line) active.delivered.put(text(line, "key"), number(line.get("count"), 0));
                    }
                }
                if (!active.id.isEmpty()) stash.activeOrder = active;
            }
            ListTag list = (ListTag) root.get("boxes");
            if (list == null) {
                stash.ensureDelivery();
                return stash;
            }

            for (Tag t : list) {
                // One damaged entry must not take the whole stash (and the world join) with it
                try {
                CompoundTag tag = (CompoundTag) t;
                List<BlockPos> parts = readPositions((ListTag) tag.get("parts"));
                if (parts.isEmpty()) continue;

                Box box = new Box(parts.getFirst(), parts);
                box.excluded = flag(tag, "excluded");
                box.dropOff = flag(tag, "dropOff");
                // Saved before read times existed: no tag, which counts as read long ago
                box.lastRead = longNumber(tag.get("readAt"));
                box.delivery = flag(tag, "delivery");
                box.wasExcluded = flag(tag, "wasExcluded");
                box.wasDropOff = flag(tag, "wasDropOff");
                box.noCompact = flag(tag, "noCompact");
                box.autoLabel = string(tag.get("autoLabel"));
                if (tag.get("autoSignPos") instanceof ListTag sign) {
                    List<BlockPos> at = readPositions(sign);
                    if (!at.isEmpty()) box.autoSignPos = at.getFirst();
                }

                int size = number(tag.get("size"), -1);
                if (size >= 0) {
                    box.contents = new ItemStack[size];
                    java.util.Arrays.fill(box.contents, ItemStack.EMPTY);

                    if (tag.get("items") instanceof ListTag items) {
                        for (Tag e : items) {
                            CompoundTag entry = (CompoundTag) e;
                            int slot = number(entry.get("slot"), -1);
                            if (slot < 0 || slot >= box.contents.length || entry.get("item") == null) continue;
                            ItemStack.CODEC.parse(ops, entry.get("item")).result().ifPresent(stack -> box.contents[slot] = stack);
                        }
                    }
                }

                stash.boxes.add(box);
                } catch (RuntimeException ex) {
                    QwerteAddon.LOG.warn("Skipped a container of stash {} that couldn't be read: {}", name, ex.toString());
                    errors[0] = true;
                }
            }
            if (root.get("orphans") instanceof ListTag lost) {
                for (Tag t : lost) {
                    if (!(t instanceof CompoundTag tag) || !(tag.get("pos") instanceof ListTag at)) continue;
                    List<BlockPos> pos = readPositions(at);
                    if (pos.isEmpty()) continue;
                    String item = string(tag.get("item"));
                    String when = string(tag.get("when"));
                    stash.orphans.add(new Orphan(pos.getFirst(), item == null ? "items" : item, number(tag.get("stacks"), 0), when == null ? "" : when,
                        string(tag.get("dimension"))));
                }
            }
        } catch (IOException | RuntimeException e) {
            QwerteAddon.LOG.error("Couldn't load stash {}", name, e);
            errors[0] = true;
        }

        // A stash that did not load fully could be saved over the original later: the original is kept first
        if (errors[0]) backUp(path);

        stash.ensureDelivery();
        return stash;
    }

    /** A long from a number tag; 0 when there is none (a stash saved before the field existed) or it is not a number. */
    private static long longNumber(Tag tag) {
        if (tag == null) return 0L;
        return NbtOps.INSTANCE.getNumberValue(tag).result().map(Number::longValue).orElse(0L);
    }

    /** Copies a stash file that had errors to name.bak-yyyyMMdd-HHmmss, keeping the last three, and says so once. */
    private static void backUp(Path path) {
        try {
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path copy = path.resolveSibling(path.getFileName() + ".bak-" + stamp);
            Files.copy(path, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            String prefix = path.getFileName() + ".bak-";
            try (var files = Files.list(path.getParent())) {
                var old = files.filter(f -> f.getFileName().toString().startsWith(prefix)).sorted().toList();
                for (int i = 0; i < old.size() - 3; i++) Files.deleteIfExists(old.get(i));
            }
            QwerteAddon.LOG.warn("Stash file had errors; a backup was saved as {}", copy.getFileName());
            meteordevelopment.meteorclient.utils.player.ChatUtils.warning("Stash file had errors; a backup was saved as %s", copy.getFileName());
        } catch (IOException | RuntimeException e) {
            QwerteAddon.LOG.warn("Couldn't back up the stash file {}: {}", path.getFileName(), e.toString());
        }
    }

    private static boolean flag(CompoundTag tag, String key) {
        return number(tag.get(key), 0) != 0;
    }

    /**
     * Reads a number tag. Numeric tags changed between Minecraft versions, but reading through NbtOps works on all.
     */
    private static int number(Tag tag, int fallback) {
        if (tag == null) return fallback;
        return NbtOps.INSTANCE.getNumberValue(tag).result().map(Number::intValue).orElse(fallback);
    }

    /**
     * Reads a string tag. String access on compound tags changed between Minecraft versions, but reading through NbtOps
     * works on all.
     */
    private static String string(Tag tag) {
        if (tag == null) return null;
        return NbtOps.INSTANCE.getStringValue(tag).result().orElse(null);
    }

    private static ListTag positions(List<BlockPos> list) {
        ListTag tags = new ListTag();
        for (BlockPos pos : list) {
            ListTag xyz = new ListTag();
            xyz.add(IntTag.valueOf(pos.getX()));
            xyz.add(IntTag.valueOf(pos.getY()));
            xyz.add(IntTag.valueOf(pos.getZ()));
            tags.add(xyz);
        }
        return tags;
    }

    private static List<BlockPos> readPositions(ListTag tags) {
        List<BlockPos> list = new ArrayList<>();
        if (tags == null) return list;

        for (Tag t : tags) {
            if (!(t instanceof ListTag xyz) || xyz.size() != 3) continue;
            list.add(new BlockPos(number(xyz.get(0), 0), number(xyz.get(1), 0), number(xyz.get(2), 0)));
        }
        return list;
    }
}

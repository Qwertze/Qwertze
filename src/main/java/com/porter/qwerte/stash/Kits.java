package com.porter.qwerte.stash;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.stash.Stash.Box;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The saved kits of the server we're on, kept in memory while in a world and saved under
 * {@code meteor-client/qwerte/kits}. Also recognises kit shulkers by their contents.
 */
public final class Kits {
    private Kits() {
    }

    /**
     * A shulker that is a kit, or part of one. {@code mixed} is two or more kinds of item inside.
     */
    public record Match(Kit kit, boolean finished, boolean mixed) {
        /**
         * Safe to treat as a kit anywhere, not only in its own chest: finished, or clearly part built.
         */
        public boolean claims() {
            return finished || mixed;
        }
    }

    private record StackKey(Item item, DataComponentMap components) {}

    private static final List<Kit> kits = new ArrayList<>();
    private static final Map<StackKey, Match> matches = new HashMap<>();
    private static final Match NONE = new Match(null, false, false);
    private static final Map<String, Boolean> labels = new HashMap<>();
    private static String loadedFor;
    private static int version;

    // The list

    /**
     * Bumps whenever the list changes or is loaded, so callers can tell something may have changed.
     */
    public static int version() {
        return version;
    }

    public static List<Kit> all() {
        ensureLoaded();
        return Collections.unmodifiableList(kits);
    }

    public static Kit byName(String name) {
        ensureLoaded();
        for (Kit kit : kits) if (kit.sameName(name)) return kit;
        return null;
    }

    /**
     * Forgets the loaded list, on leaving the world.
     */
    public static void clear() {
        kits.clear();
        unreadable.clear();
        saveBlocked = false;
        loadedFor = null;
        changed();
    }

    private static void changed() {
        version++;
        matches.clear();
        labels.clear();
    }

    /**
     * Saves the 27 main inventory slots as a new kit.
     * @return why it couldn't be saved, or null if it was
     */
    public static String saveFromInventory(String name) {
        if (mc.level == null || mc.player == null) return "Join a world first.";
        ensureLoaded();

        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) return "Give the kit a name.";
        if (Categories.normalize(trimmed).isEmpty()) return "The name needs some letters or numbers.";
        if (byName(trimmed) != null) return "There's already a kit called \"" + trimmed + "\".";

        Kit kit = Kit.fromInventory(trimmed, mc.player.getInventory());
        if (kit.slotCount() == 0) return "Your 27 main inventory slots are empty, put the kit there first.";
        for (ItemStack stack : kit.layout()) {
            if (!stack.isEmpty() && Utils.isShulker(stack.getItem())) return "Kits can't contain shulker boxes.";
            if (!stack.isEmpty() && stack.has(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS)) return "Kits can't contain bundles.";
        }

        kits.add(kit);
        changed();
        if (!save()) {
            kits.remove(kit);
            changed();
            return "Couldn't save the kit, see the chat and log.";
        }
        return null;
    }

    public static void delete(Kit kit) {
        int at = kits.indexOf(kit);
        if (at < 0) return;
        kits.remove(at);
        changed();
        if (!save()) {
            // Not written, so it would come back next time: keep it
            kits.add(at, kit);
            changed();
        }
    }

    // Finding kits

    /**
     * The kit a shulker is, or part of, going by what's inside. Null if it isn't one, or is named (named shulkers are
     * left as they are).
     */
    public static Match match(ItemStack shulker) {
        // Retired (kit sorting v2, addon 0.7.0): saved local kits no longer route shulkers; the dashboard's kit templates do
        if (RETIRED) return null;
        if (shulker == null || shulker.isEmpty() || !Utils.isShulker(shulker.getItem()) || shulker.has(DataComponents.CUSTOM_NAME)) return null;
        ensureLoaded();
        if (kits.isEmpty()) return null;

        StackKey lookup = new StackKey(shulker.getItem(), shulker.getComponents());
        Match cached = matches.get(lookup);
        if (cached != null) return cached == NONE ? null : cached;

        Match found = compute(shulker);
        matches.put(new StackKey(shulker.getItem(), shulker.copy().getComponents()), found == null ? NONE : found);
        return found;
    }

    private static Match compute(ItemStack shulker) {
        ItemStack[] inside = inside(shulker);
        boolean any = false;
        for (ItemStack s : inside) if (s != null && !s.isEmpty()) any = true;
        if (!any) return null;

        boolean mixed = Kit.kinds(inside) >= 2;
        for (Kit kit : kits) if (kit.matchesFinished(inside)) return new Match(kit, true, mixed);
        for (Kit kit : kits) if (kit.matchesPartial(inside)) return new Match(kit, false, mixed);
        return null;
    }

    /**
     * A finished kit, or one clearly part built, of any saved kit.
     */
    public static boolean claims(ItemStack shulker) {
        Match match = match(shulker);
        return match != null && match.claims();
    }

    public static ItemStack[] inside(ItemStack shulker) {
        ItemStack[] contents = new ItemStack[Kit.SLOTS];
        Utils.getItemsInContainerItem(shulker, contents);

        // Shulkers that came through ViaVersion keep their items in block_entity_data. A shulker always has a (default,
        // empty) container component, so the reader never looks there: it is asked again with the container taken off
        if (shulker.has(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA) && allEmpty(contents)) {
            ItemStack[] other = legacyContents(shulker);
            if (other != null) return other;
        }
        return contents;
    }

    // The legacy read is worked out once per stack and tick
    private static final java.util.IdentityHashMap<ItemStack, ItemStack[]> LEGACY = new java.util.IdentityHashMap<>();
    private static int legacyTick = -1;

    private static ItemStack[] legacyContents(ItemStack shulker) {
        int tick = net.minecraft.client.Minecraft.getInstance().player == null ? -1 : net.minecraft.client.Minecraft.getInstance().player.tickCount;
        if (tick != legacyTick) {
            LEGACY.clear();
            legacyTick = tick;
        }
        if (LEGACY.containsKey(shulker)) return LEGACY.get(shulker);

        ItemStack[] result = null;
        // Only data that has an item list is worth the read
        if (com.porter.qwerte.compat.LegacyItems.hasItemList(shulker)) {
            try {
                ItemStack copy = shulker.copy();
                copy.remove(net.minecraft.core.component.DataComponents.CONTAINER);
                ItemStack[] other = new ItemStack[Kit.SLOTS];
                Utils.getItemsInContainerItem(copy, other);
                if (!allEmpty(other)) result = other;
            } catch (RuntimeException e) {
                // Not readable: treated as the container says
            }
        }
        LEGACY.put(shulker, result);
        return result;
    }

    private static boolean allEmpty(ItemStack[] contents) {
        for (ItemStack s : contents) if (s != null && !s.isEmpty()) return false;
        return true;
    }

    // Chests

    /**
     * Whether a sign line names the chest of some saved kit.
     */
    /** The local saved kits no longer build, route or claim shulkers; the list is only read (for the import into the Kit Builder). */
    private static final boolean RETIRED = true;

    public static boolean isKitLabel(String label) {
        if (RETIRED) return false;
        ensureLoaded();
        if (kits.isEmpty()) return false;
        return labels.computeIfAbsent(label, l -> {
            for (Kit kit : kits) if (kit.labelMatches(l)) return true;
            return false;
        });
    }

    public static boolean isKitBox(Box box) {
        for (String label : box.labels) if (isKitLabel(label)) return true;
        return false;
    }

    public static boolean isHome(Box box, Kit kit) {
        if (RETIRED) return false;
        for (String label : box.labels) if (kit.labelMatches(label)) return true;
        return false;
    }

    /**
     * The chests a finished kit goes to: signed with its name.
     */
    public static List<Box> homes(Kit kit, Stash stash) {
        List<Box> homes = new ArrayList<>();
        if (stash == null || RETIRED) return homes;
        for (Box box : stash.boxes) if (box.sortable() && !box.dropOff && isHome(box, kit)) homes.add(box);
        return homes;
    }

    /**
     * Finished copies of a kit sitting in the stash, named or not.
     */
    public static int built(Kit kit, Stash stash) {
        if (stash == null) return 0;
        int count = 0;
        for (Box box : stash.boxes) {
            if (!box.known() || box.foreign) continue;
            for (ItemStack stack : box.contents) {
                if (!stack.isEmpty() && Utils.isShulker(stack.getItem()) && kit.matchesFinished(inside(stack))) count++;
            }
        }
        return count;
    }

    // Saving and loading

    private static Path folder() {
        return MeteorClient.FOLDER.toPath().resolve("qwerte").resolve("kits");
    }

    private static Path file(String server) {
        return folder().resolve(server.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_") + ".nbt");
    }

    private static void ensureLoaded() {
        if (mc.level == null) return;
        String server = Stash.currentServer();
        if (!server.equals(loadedFor)) load(server);
    }

    // Kits in the file that this version couldn't read, kept as they were so saving never loses them
    private static final List<CompoundTag> unreadable = new ArrayList<>();
    private static boolean saveBlocked;

    private static int currentDataVersion() {
        return NbtUtils.getDataVersion(NbtUtils.addCurrentDataVersion(new CompoundTag()), 0);
    }

    private static void load(String server) {
        kits.clear();
        unreadable.clear();
        saveBlocked = false;
        loadedFor = server;
        changed();

        Path path = file(server);
        if (!Files.exists(path)) return;

        RegistryOps<Tag> ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        CompoundTag root;
        ListTag list;
        try {
            root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            if (!(root.get("kits") instanceof ListTag found)) throw new IOException("no kit list");
            list = found;
        } catch (IOException | RuntimeException e) {
            // Never overwrite a file we couldn't read: move it aside, then start with none
            QwerteAddon.LOG.error("Couldn't read kits for {}", server, e);
            String base = path.getFileName().toString();
            if (base.endsWith(".nbt")) base = base.substring(0, base.length() - 4);
            Path aside = path.resolveSibling(base + ".corrupt-" + System.currentTimeMillis() + ".nbt");
            try {
                Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING);
                ChatUtils.warning("Your saved kits couldn't be read. The file was moved to %s.", aside.getFileName());
            } catch (IOException moveFailed) {
                QwerteAddon.LOG.error("Couldn't move the unreadable kits file aside", moveFailed);
                ChatUtils.warning("Your saved kits couldn't be read, and the file couldn't be moved aside. Kits won't be saved this session.");
                saveBlocked = true;
            }
            changed();
            return;
        }

        int fileVersion = NbtUtils.getDataVersion(root, 0);
        boolean otherVersion = fileVersion != 0 && fileVersion != currentDataVersion();
        if (otherVersion) QwerteAddon.LOG.info("Kits were saved with data version {}, this is {}", fileVersion, currentDataVersion());

        for (Tag t : list) {
            Kit kit = null;
            try {
                kit = read(t, ops);
            } catch (RuntimeException e) {
                QwerteAddon.LOG.warn("A kit threw while being read", e);
            }

            if (kit == null) {
                if (!(t instanceof CompoundTag raw)) continue;
                unreadable.add(raw.copy());
                String name = raw.get("name") == null ? "?" : NbtOps.INSTANCE.getStringValue(raw.get("name")).result().orElse("?");
                QwerteAddon.LOG.warn("Kit {} couldn't be read, keeping it untouched in the file", name);
                ChatUtils.warning("Kit \"%s\" couldn't be read%s, so it's unavailable here. It stays in the file untouched.", name,
                    otherVersion ? " (saved by another Minecraft version?)" : "");
                continue;
            }

            if (byName(kit.name) == null) kits.add(kit);
        }
        changed();
    }

    /**
     * @return the kit, or null if any slot is out of range or its item can't be read
     */
    private static Kit read(Tag t, RegistryOps<Tag> ops) {
        if (!(t instanceof CompoundTag tag)) return null;

        String name = tag.get("name") == null ? null : NbtOps.INSTANCE.getStringValue(tag.get("name")).result().orElse(null);
        if (name == null || Categories.normalize(name).isEmpty() || !(tag.get("slots") instanceof ListTag slots)) return null;

        ItemStack[] stacks = new ItemStack[Kit.SLOTS];
        java.util.Arrays.fill(stacks, ItemStack.EMPTY);
        for (Tag e : slots) {
            if (!(e instanceof CompoundTag entry) || entry.get("slot") == null || entry.get("item") == null) return null;

            int slot = entry.get("slot") == null ? -1 : NbtOps.INSTANCE.getNumberValue(entry.get("slot")).result().map(Number::intValue).orElse(-1);
            if (slot < 0 || slot >= Kit.SLOTS) {
                QwerteAddon.LOG.warn("Kit {} has a slot out of range: {}", name, slot);
                return null;
            }

            var parsed = ItemStack.CODEC.parse(ops, entry.get("item")).result();
            if (parsed.isEmpty() || parsed.get().isEmpty()) {
                QwerteAddon.LOG.warn("Kit {} slot {} couldn't be read", name, slot);
                return null;
            }
            stacks[slot] = parsed.get();
        }

        Kit kit = new Kit(name, stacks);
        return kit.slotCount() == 0 ? null : kit;
    }

    /**
     * @return false if nothing was written, because a stack couldn't be encoded or the file couldn't be written
     */
    private static boolean save() {
        if (mc.level == null) return false;
        if (saveBlocked) {
            ChatUtils.warning("Kits can't be saved this session, the old kits file is still in the way.");
            return false;
        }
        RegistryOps<Tag> ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);

        CompoundTag root = new CompoundTag();
        NbtUtils.addCurrentDataVersion(root);
        root.putInt("v", 1);
        ListTag list = new ListTag();
        for (Kit kit : kits) {
            CompoundTag tag = new CompoundTag();
            tag.putString("name", kit.name);
            ListTag slots = new ListTag();
            for (int i = 0; i < Kit.SLOTS; i++) {
                if (kit.stackAt(i).isEmpty()) continue;

                var encoded = ItemStack.CODEC.encodeStart(ops, kit.stackAt(i)).result();
                if (encoded.isEmpty()) {
                    QwerteAddon.LOG.error("Kit {} slot {} couldn't be encoded, not saving", kit.name, i);
                    ChatUtils.warning("Kit \"%s\" has an item that can't be saved, so nothing was saved.", kit.name);
                    return false;
                }

                CompoundTag entry = new CompoundTag();
                entry.putInt("slot", i);
                entry.put("item", encoded.get());
                slots.add(entry);
            }
            tag.put("slots", slots);
            list.add(tag);
        }
        for (CompoundTag raw : unreadable) list.add(raw.copy());
        root.put("kits", list);

        Path target = file(Stash.currentServer());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(folder());
            NbtIo.writeCompressed(root, temp);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            QwerteAddon.LOG.error("Couldn't save kits", e);
            ChatUtils.warning("Couldn't save kits: %s", e.getMessage());
            return false;
        }
    }
}

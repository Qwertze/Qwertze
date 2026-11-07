package com.porter.qwerte.manager.local;

import com.porter.qwerte.manager.local.LocalStore.Entry;
import com.porter.qwerte.manager.local.LocalStore.Slot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The local index file of one server: compressed NBT, written next to itself and moved over the old one, so a crash
 * never leaves half a file. Reading survives anything: a broken file is moved aside and the index starts empty.
 * No game registries are used; safe on any thread.
 *
 * <pre>{version:1, server, containers:[{dim, pos:[x,y,z], parts:[[x,y,z],..], block, size, readAt, items:[{slot, item}]}]}</pre>
 */
public final class LocalFile {
    public static final int VERSION = 1;
    /** A file bigger than this (compressed) is neither written nor read. */
    public static final long MAX_FILE_BYTES = 48L * 1024 * 1024;
    /** The NBT of a file may be this big once uncompressed, at write time and (plus {@link #READ_MARGIN}) at read time. */
    public static final long MAX_UNCOMPRESSED = 96L * 1024 * 1024;
    public static final long READ_MARGIN = 8L * 1024 * 1024;
    /** The most server files kept; the oldest go when a new one is made. */
    public static final int MAX_FILES = 64;
    private static final int BACKUPS = 3;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Qwerte/stash-window");

    private LocalFile() {}

    /**
     * OK and MISSING are usable. CORRUPT: unparsable, moved aside. UNREADABLE: could not be read or moved, left alone.
     * TOO_BIG: over the size limits, left alone. NEWER: written by a newer version, loaded as far as understood and left
     * alone. For the last three the caller must not write the file.
     */
    public enum Status { OK, MISSING, CORRUPT, UNREADABLE, TOO_BIG, NEWER }

    /** What a read found. {@code skipped} counts containers in the file that were malformed and left out. */
    public record Loaded(Status status, List<Entry> entries, int skipped) {}

    public enum WriteStatus { OK, TOO_BIG, ERROR }

    // ---------- the NBT shape ----------

    public static CompoundTag toTag(String server, Collection<Entry> entries) {
        CompoundTag root = new CompoundTag();
        root.putInt("version", VERSION);
        root.putString("server", server == null ? "" : server);
        ListTag list = new ListTag();
        for (Entry e : entries) {
            CompoundTag c = new CompoundTag();
            c.putString("dim", e.key().dimension());
            c.put("pos", xyz(e.key().x(), e.key().y(), e.key().z()));
            ListTag parts = new ListTag();
            for (int[] p : e.parts()) parts.add(xyz(p[0], p[1], p[2]));
            c.put("parts", parts);
            c.putString("block", e.block());
            c.putInt("size", e.size());
            c.putLong("readAt", e.readAt());
            ListTag items = new ListTag();
            for (Slot s : e.slots()) {
                CompoundTag t = new CompoundTag();
                t.putInt("slot", s.slot());
                t.put("item", s.item());
                items.add(t);
            }
            c.put("items", items);
            list.add(c);
        }
        root.put("containers", list);
        return root;
    }

    /** Reads the containers of a file's root tag; malformed ones are skipped, at most {@code cap} are taken. */
    public static Loaded fromTag(CompoundTag root, String server, int cap) {
        List<Entry> out = new ArrayList<>();
        int skipped = 0;
        if (root == null || !(root.get("containers") instanceof ListTag list)) return new Loaded(Status.CORRUPT, out, 0);
        for (Tag t : list) {
            if (out.size() >= Math.max(1, cap)) {
                skipped++;
                continue;
            }
            Entry e = entry(t, server);
            if (e == null) skipped++;
            else out.add(e);
        }
        return new Loaded(Status.OK, out, skipped);
    }

    private static Entry entry(Tag t, String server) {
        try {
            if (!(t instanceof CompoundTag c)) return null;
            String dim = string(c.get("dim"));
            String block = string(c.get("block"));
            if (dim == null || dim.isEmpty() || dim.length() > 100 || block == null || block.length() > 100) return null;
            int[] pos = readXyz(c.get("pos"));
            if (pos == null) return null;
            List<int[]> parts = new ArrayList<>();
            if (c.get("parts") instanceof ListTag ps) {
                for (Tag p : ps) {
                    int[] xyz = readXyz(p);
                    if (xyz != null && parts.size() < 2) parts.add(xyz);
                }
            }
            if (parts.isEmpty()) parts.add(pos);
            int size = number(c.get("size"), 0);
            if (size < 1 || size > CaptureRules.MAX_SLOTS) return null;
            long readAt = numberLong(c.get("readAt"));
            List<Slot> slots = new ArrayList<>();
            if (c.get("items") instanceof ListTag items) {
                for (Tag it : items) {
                    if (!(it instanceof CompoundTag ic)) continue;
                    int slot = number(ic.get("slot"), -1);
                    Tag item = ic.get("item");
                    if (slot < 0 || slot >= size || !(item instanceof CompoundTag) || slots.size() >= CaptureRules.MAX_SLOTS) continue;
                    slots.add(new Slot(slot, item));
                }
            }
            Entry e = new Entry(new ContainerKey(server, dim, pos[0], pos[1], pos[2]), List.copyOf(parts), block, size, readAt, List.copyOf(slots));
            return LocalStore.valid(e) ? e : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static ListTag xyz(int x, int y, int z) {
        ListTag l = new ListTag();
        l.add(IntTag.valueOf(x));
        l.add(IntTag.valueOf(y));
        l.add(IntTag.valueOf(z));
        return l;
    }

    private static int[] readXyz(Tag t) {
        if (!(t instanceof ListTag l) || l.size() != 3) return null;
        return new int[] { number(l.get(0), 0), number(l.get(1), 0), number(l.get(2), 0) };
    }

    // Numbers and strings are read through NbtOps: compound tag accessors differ between Minecraft versions
    private static int number(Tag tag, int fallback) {
        if (tag == null) return fallback;
        return NbtOps.INSTANCE.getNumberValue(tag).result().map(Number::intValue).orElse(fallback);
    }

    private static long numberLong(Tag tag) {
        if (tag == null) return 0;
        return NbtOps.INSTANCE.getNumberValue(tag).result().map(Number::longValue).orElse(0L);
    }

    private static String string(Tag tag) {
        if (tag == null) return null;
        return NbtOps.INSTANCE.getStringValue(tag).result().orElse(null);
    }

    // ---------- the file ----------

    /**
     * Reads a server's file. A missing file gives an empty MISSING. One that cannot be parsed is moved aside as
     * name.bad-time (keeping the last three) and gives CORRUPT. A file over the size limits, or written by a newer
     * version, is kept where it is (TOO_BIG, NEWER); so is one that can be neither read nor moved (UNREADABLE).
     */
    public static Loaded read(Path file, String server, int cap) {
        if (!Files.exists(file)) return new Loaded(Status.MISSING, List.of(), 0);
        byte[] bytes;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) return new Loaded(Status.TOO_BIG, List.of(), 0);
            bytes = Files.readAllBytes(file);
        } catch (IOException | RuntimeException e) {
            return new Loaded(Status.UNREADABLE, List.of(), 0);
        }
        try {
            // Measured first, so a size problem is told apart from a broken file and a bomb is never inflated
            if (uncompressedSize(bytes, MAX_UNCOMPRESSED + READ_MARGIN) > MAX_UNCOMPRESSED + READ_MARGIN) return new Loaded(Status.TOO_BIG, List.of(), 0);
        } catch (IOException | RuntimeException e) {
            return moveAside(file);
        }
        try {
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            Loaded loaded = fromTag(root, server, cap);
            if (loaded.status() != Status.OK) return moveAside(file);
            int version = number(root.get("version"), VERSION);
            return version > VERSION ? new Loaded(Status.NEWER, loaded.entries(), loaded.skipped()) : loaded;
        } catch (OutOfMemoryError e) {
            return new Loaded(Status.UNREADABLE, List.of(), 0);
        } catch (IOException | RuntimeException e) {
            return moveAside(file);
        }
    }

    /** The size of the gzip data once uncompressed, counting stops just past {@code limit}. */
    static long uncompressedSize(byte[] gzip, long limit) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            byte[] buffer = new byte[65536];
            long n = 0;
            int r;
            while ((r = in.read(buffer)) >= 0) {
                n += r;
                if (n > limit) return n;
            }
            return n;
        }
    }

    /** The size the root tag has once serialized (the NBT before compression). */
    static long nbtSize(CompoundTag root) throws IOException {
        long[] count = new long[1];
        OutputStream counter = new OutputStream() {
            @Override
            public void write(int b) {
                count[0]++;
            }

            @Override
            public void write(byte[] b, int off, int len) {
                count[0] += len;
            }
        };
        DataOutputStream out = new DataOutputStream(counter);
        NbtIo.write(root, out);
        out.flush();
        return count[0];
    }

    private static Loaded moveAside(Path file) {
        try {
            Path bad = file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis());
            Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
            String prefix = file.getFileName() + ".bad-";
            try (var files = Files.list(file.getParent())) {
                List<Path> old = files.filter(f -> f.getFileName().toString().startsWith(prefix)).sorted().toList();
                for (int i = 0; i < old.size() - BACKUPS; i++) Files.deleteIfExists(old.get(i));
            }
            return new Loaded(Status.CORRUPT, List.of(), 0);
        } catch (IOException | RuntimeException e) {
            return new Loaded(Status.UNREADABLE, List.of(), 0);
        }
    }

    /**
     * Writes the tag to {@code target} atomically. Over {@link #MAX_FILE_BYTES} the old file is kept and TOO_BIG comes
     * back. When this makes a new file, the oldest ones beyond {@link #MAX_FILES} are deleted.
     */
    public static WriteStatus write(Path target, CompoundTag root) {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            boolean isNew = !Files.exists(target);
            // The limit that matters for reading it back is the uncompressed one
            if (nbtSize(root) > MAX_UNCOMPRESSED) return WriteStatus.TOO_BIG;
            NbtIo.writeCompressed(root, temp);
            if (Files.size(temp) > MAX_FILE_BYTES) {
                Files.deleteIfExists(temp);
                return WriteStatus.TOO_BIG;
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (isNew) prune(target.getParent(), target);
            return WriteStatus.OK;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // nothing more to do
            }
            return WriteStatus.ERROR;
        }
    }

    /** Deletes the oldest .nbt files of the folder beyond {@link #MAX_FILES}, never {@code keep}. */
    static void prune(Path folder, Path keep) {
        try (var files = Files.list(folder)) {
            List<Path> nbt = new ArrayList<>(files.filter(f -> f.getFileName().toString().endsWith(".nbt") && !f.equals(keep)).toList());
            // The new file counts toward the cap
            for (Path drop : Evict.oldest(nbt, MAX_FILES - 1, LocalFile::modified)) {
                if (Files.deleteIfExists(drop)) LOG.info("stash-window: deleted the oldest local index file {} (more than {} servers)", drop.getFileName(), MAX_FILES);
            }
        } catch (IOException | RuntimeException e) {
            // Pruning is a courtesy
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    public static Path fileFor(Path folder, String server) {
        return folder.resolve(ContainerKey.fileName(server) + ".nbt");
    }

    /** The name files had before the hash was added to it. */
    static Path legacyFileFor(Path folder, String server) {
        return folder.resolve(ContainerKey.safeFileName(server) + ".nbt");
    }

    /** Renames a file with the old name to the new one, if only the old one exists. */
    public static void migrate(Path folder, String server) {
        Path now = fileFor(folder, server);
        Path old = legacyFileFor(folder, server);
        try {
            if (!Files.exists(now) && Files.exists(old)) Files.move(old, now);
        } catch (IOException | RuntimeException e) {
            // the old file is simply not used
        }
    }

    /** Deletes a server's file (and a leftover temp file). */
    public static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
        } catch (IOException e) {
            // reported by the caller through the next read
        }
    }
}

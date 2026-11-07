package com.porter.qwerte.manager.local;

import com.porter.qwerte.manager.local.LocalStore.Entry;
import com.porter.qwerte.manager.local.LocalStore.Slot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The local store and its file: round trip, caps, and files that are damaged. */
class LocalStoreTest {
    @TempDir
    Path dir;

    private static Tag item(String id, int count) {
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putInt("count", count);
        return t;
    }

    private static Entry entry(String server, int x, long readAt, int size, Slot... slots) {
        ContainerKey key = new ContainerKey(server, "minecraft:overworld", x, 64, 0);
        return new Entry(key, List.of(new int[] { x, 64, 0 }), "minecraft:chest", size, readAt, List.of(slots));
    }

    @Test
    void roundTripKeepsEverything() {
        Entry a = new Entry(new ContainerKey("srv", "minecraft:the_nether", 10, 70, -5), List.of(new int[] { 10, 70, -5 }, new int[] { 11, 70, -5 }),
            "minecraft:trapped_chest", 54, 12345L, List.of(new Slot(0, item("minecraft:stone", 64)), new Slot(53, item("minecraft:apple", 3))));
        Entry b = entry("srv", 1, 99, 27);
        CompoundTag tag = LocalFile.toTag("srv", List.of(a, b));

        LocalFile.Loaded loaded = LocalFile.fromTag(tag, "srv", 4096);
        assertEquals(LocalFile.Status.OK, loaded.status());
        assertEquals(0, loaded.skipped());
        assertEquals(2, loaded.entries().size());
        Entry ra = loaded.entries().get(0);
        assertEquals(a.key(), ra.key());
        assertEquals("minecraft:trapped_chest", ra.block());
        assertEquals(54, ra.size());
        assertEquals(12345L, ra.readAt());
        assertEquals(2, ra.parts().size());
        assertEquals(11, ra.parts().get(1)[0]);
        assertEquals(2, ra.slots().size());
        assertEquals(53, ra.slots().get(1).slot());
        assertEquals(a.slots().get(0).item(), ra.slots().get(0).item());
        assertEquals(0, loaded.entries().get(1).slots().size(), "an empty container is kept");
    }

    @Test
    void writeThenReadFromDisk() throws IOException {
        Path file = LocalFile.fileFor(dir.resolve("local-index"), "My.Server:25565");
        assertTrue(file.getFileName().toString().matches("my\\.server_25565-[0-9a-f]{8}\\.nbt"), file.getFileName().toString());
        Entry a = entry("My.Server:25565", 3, 7, 27, new Slot(2, item("minecraft:diamond", 9)));
        assertEquals(LocalFile.WriteStatus.OK, LocalFile.write(file, LocalFile.toTag("My.Server:25565", List.of(a))));
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")), "the temp file is moved over the target");

        LocalFile.Loaded loaded = LocalFile.read(file, "My.Server:25565", 4096);
        assertEquals(LocalFile.Status.OK, loaded.status());
        assertEquals(a.key(), loaded.entries().get(0).key());
        assertEquals(a.slots().get(0).item(), loaded.entries().get(0).slots().get(0).item());
    }

    @Test
    void aMissingFileIsEmptyNotAnError() {
        LocalFile.Loaded loaded = LocalFile.read(dir.resolve("nope.nbt"), "s", 10);
        assertEquals(LocalFile.Status.MISSING, loaded.status());
        assertTrue(loaded.entries().isEmpty());
    }

    @Test
    void garbageIsMovedAsideAndReadsAsEmpty() throws IOException {
        Path file = dir.resolve("bad.nbt");
        Files.writeString(file, "this is not gzip nbt at all");
        LocalFile.Loaded loaded = LocalFile.read(file, "s", 10);
        assertEquals(LocalFile.Status.CORRUPT, loaded.status());
        assertTrue(loaded.entries().isEmpty());
        assertFalse(Files.exists(file), "moved away so the next save does not read it again");
        try (var files = Files.list(dir)) {
            assertEquals(1, files.filter(f -> f.getFileName().toString().startsWith("bad.nbt.bad-")).count());
        }
    }

    @Test
    void validNbtOfTheWrongShapeIsCorruptToo() throws IOException {
        Path file = dir.resolve("shape.nbt");
        CompoundTag wrong = new CompoundTag();
        wrong.putString("hello", "world");
        NbtIo.writeCompressed(wrong, file);
        assertEquals(LocalFile.Status.CORRUPT, LocalFile.read(file, "s", 10).status());
    }

    @Test
    void anEmptyFileIsCorrupt() throws IOException {
        Path file = dir.resolve("empty.nbt");
        Files.write(file, new byte[0]);
        assertEquals(LocalFile.Status.CORRUPT, LocalFile.read(file, "s", 10).status());
    }

    @Test
    void malformedContainersAreSkippedTheRestLoad() {
        CompoundTag tag = LocalFile.toTag("s", List.of(entry("s", 1, 1, 27), entry("s", 2, 2, 27)));
        ListTag list = (ListTag) tag.get("containers");
        list.add(new CompoundTag());              // no fields
        CompoundTag oversize = (CompoundTag) list.get(0).copy();
        oversize.putInt("size", 500);             // too many slots
        list.add(oversize);
        LocalFile.Loaded loaded = LocalFile.fromTag(tag, "s", 4096);
        assertEquals(2, loaded.entries().size());
        assertEquals(2, loaded.skipped());
    }

    @Test
    void slotsOutsideTheContainerAreDroppedOnLoad() {
        Entry e = entry("s", 1, 1, 27, new Slot(4, item("a:b", 1)));
        CompoundTag tag = LocalFile.toTag("s", List.of(e));
        CompoundTag c = (CompoundTag) ((ListTag) tag.get("containers")).get(0);
        ListTag items = (ListTag) c.get("items");
        CompoundTag stray = new CompoundTag();
        stray.putInt("slot", 40);
        stray.put("item", item("a:c", 1));
        items.add(stray);
        LocalFile.Loaded loaded = LocalFile.fromTag(tag, "s", 10);
        assertEquals(1, loaded.entries().get(0).slots().size());
    }

    @Test
    void theLoadHonoursTheContainerCap() {
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) many.add(entry("s", i, i, 27));
        LocalFile.Loaded loaded = LocalFile.fromTag(LocalFile.toTag("s", many), "s", 5);
        assertEquals(5, loaded.entries().size());
        assertEquals(15, loaded.skipped());
    }

    @Test
    void theStoreEvictsTheOldestOverTheCapButKeepsTheNewest() {
        LocalStore store = new LocalStore("s");
        store.markLoadedEmpty();
        assertTrue(store.put(entry("s", 1, 100, 27), 3));
        assertTrue(store.put(entry("s", 2, 300, 27), 3));
        assertTrue(store.put(entry("s", 3, 200, 27), 3));
        assertEquals(3, store.size());
        // the new one is the oldest by time but is the one just put: it stays, the oldest of the others goes
        assertTrue(store.put(entry("s", 4, 50, 27), 3));
        assertEquals(3, store.size());
        assertNull(store.get(new ContainerKey("s", "minecraft:overworld", 1, 64, 0)));
        assertNotNull(store.get(new ContainerKey("s", "minecraft:overworld", 4, 64, 0)));
        assertNotNull(store.get(new ContainerKey("s", "minecraft:overworld", 2, 64, 0)));
    }

    @Test
    void replacingAContainerDoesNotGrowTheStore() {
        LocalStore store = new LocalStore("s");
        store.markLoadedEmpty();
        store.put(entry("s", 1, 1, 27), 10);
        store.put(entry("s", 1, 2, 27, new Slot(0, item("a:b", 1))), 10);
        assertEquals(1, store.size());
        assertEquals(1, store.get(new ContainerKey("s", "minecraft:overworld", 1, 64, 0)).slots().size());
    }

    @Test
    void malformedEntriesAreRefused() {
        LocalStore store = new LocalStore("s");
        assertFalse(store.put(entry("s", 1, 1, 0), 10), "no slots");
        assertFalse(store.put(entry("s", 1, 1, 55), 10), "more than 54");
        assertFalse(store.put(entry("s", 1, 1, 27, new Slot(27, item("a:b", 1))), 10), "slot outside");
        assertFalse(store.put(null, 10));
        assertEquals(0, store.size());
    }

    @Test
    void nothingIsSavedUntilTheFileWasRead() {
        LocalStore store = new LocalStore("s");
        store.put(entry("s", 1, 1, 27), 10);
        assertFalse(store.needsSave(), "not loaded yet: a save would overwrite what was not read");
        store.mergeLoaded(List.of(entry("s", 2, 2, 27)), 10);
        assertTrue(store.needsSave(), "the capture made while loading must be written");
        assertEquals(2, store.size());
        store.markSaved(store.version());
        assertFalse(store.needsSave());
    }

    @Test
    void aBlockedStoreNeverSaves() {
        LocalStore store = new LocalStore("s");
        store.blockSaving();
        store.mergeLoaded(List.of(), 10);
        store.put(entry("s", 1, 1, 27), 10);
        assertFalse(store.needsSave());
    }

    @Test
    void capturesMadeWhileLoadingWinOverTheFile() {
        LocalStore store = new LocalStore("s");
        store.put(entry("s", 1, 500, 27, new Slot(0, item("new:one", 1))), 10);
        store.mergeLoaded(List.of(entry("s", 1, 100, 27, new Slot(0, item("old:one", 1)))), 10);
        assertEquals(1, store.size());
        assertEquals(item("new:one", 1), store.get(new ContainerKey("s", "minecraft:overworld", 1, 64, 0)).slots().get(0).item());
    }

    @Test
    void clearingWhileLoadingKeepsTheFileContentsOut() {
        LocalStore store = new LocalStore("s");
        store.clear();
        store.mergeLoaded(List.of(entry("s", 9, 1, 27)), 10);
        assertEquals(0, store.size());
    }

    @Test
    void removeAndClear() {
        LocalStore store = new LocalStore("s");
        store.markLoadedEmpty();
        store.put(entry("s", 1, 1, 27), 10);
        store.put(entry("s", 2, 1, 27), 10);
        assertTrue(store.remove(new ContainerKey("s", "minecraft:overworld", 1, 64, 0)));
        assertFalse(store.remove(new ContainerKey("s", "minecraft:overworld", 1, 64, 0)));
        store.clear();
        assertEquals(0, store.size());
    }

    @Test
    void theFileSizeCapIsFortyEightMiB() throws IOException {
        Path file = dir.resolve("big.nbt");
        assertEquals(LocalFile.WriteStatus.OK, LocalFile.write(file, LocalFile.toTag("s", List.of(entry("s", 1, 1, 27)))));
        long before = Files.size(file);
        // incompressible payload larger than the cap is too heavy for a unit test, so the cap is checked on its constant
        assertEquals(48L * 1024 * 1024, LocalFile.MAX_FILE_BYTES);
        assertTrue(before < LocalFile.MAX_FILE_BYTES);
    }

    @Test
    void oldServerFilesBeyondTheCapArePrunedWhenANewOneIsMade() throws IOException {
        Path folder = dir.resolve("idx");
        Files.createDirectories(folder);
        for (int i = 0; i < LocalFile.MAX_FILES + 5; i++) {
            Path f = folder.resolve("srv" + i + ".nbt");
            Files.writeString(f, "x");
            Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(1_000_000L + i));
        }
        Path fresh = folder.resolve("fresh.nbt");
        assertEquals(LocalFile.WriteStatus.OK, LocalFile.write(fresh, LocalFile.toTag("fresh", List.of())));
        try (var files = Files.list(folder)) {
            List<String> names = files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".nbt")).toList();
            assertEquals(LocalFile.MAX_FILES, names.size());
            assertTrue(names.contains("fresh.nbt"));
            assertFalse(names.contains("srv0.nbt"), "the oldest went first");
            assertTrue(names.contains("srv" + (LocalFile.MAX_FILES + 4) + ".nbt"));
        }
    }
}

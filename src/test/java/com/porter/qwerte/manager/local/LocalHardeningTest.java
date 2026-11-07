package com.porter.qwerte.manager.local;

import com.porter.qwerte.manager.local.LocalStore.Entry;
import com.porter.qwerte.manager.local.LocalStore.Slot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Size limits, newer files, save backoff, world identity, click matching and file names. */
class LocalHardeningTest {
    @TempDir
    Path dir;

    private static Entry entry(int x) {
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:stone");
        return new Entry(new ContainerKey("srv", "minecraft:overworld", x, 64, 0), List.of(new int[] { x, 64, 0 }), "minecraft:chest", 27, 1L,
            List.of(new Slot(0, (Tag) item)));
    }

    // ---- file size limits ----

    @Test
    void aFileOverTheUncompressedLimitIsKeptNotMovedAside() throws IOException {
        Path file = dir.resolve("big.nbt");
        // a few KB on disk, but far more than the limit once inflated
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            byte[] zeros = new byte[1 << 20];
            long total = LocalFile.MAX_UNCOMPRESSED + LocalFile.READ_MARGIN + (1 << 20);
            for (long written = 0; written < total; written += zeros.length) out.write(zeros);
        }
        assertTrue(Files.size(file) < LocalFile.MAX_FILE_BYTES);
        LocalFile.Loaded loaded = LocalFile.read(file, "srv", 100);
        assertEquals(LocalFile.Status.TOO_BIG, loaded.status());
        assertTrue(Files.exists(file), "kept where it is");
        try (var files = Files.list(dir)) {
            assertEquals(0, files.filter(f -> f.getFileName().toString().contains(".bad-")).count());
        }
    }

    @Test
    void aFileThatWasWrittenCanAlwaysBeReadBack() {
        // the write limit is on the uncompressed size and the read limit is that plus a margin
        assertTrue(LocalFile.MAX_UNCOMPRESSED + LocalFile.READ_MARGIN > LocalFile.MAX_UNCOMPRESSED);
        Path file = dir.resolve("ok.nbt");
        assertEquals(LocalFile.WriteStatus.OK, LocalFile.write(file, LocalFile.toTag("srv", List.of(entry(1), entry(2)))));
        LocalFile.Loaded loaded = LocalFile.read(file, "srv", 100);
        assertEquals(LocalFile.Status.OK, loaded.status());
        assertEquals(2, loaded.entries().size());
    }

    @Test
    void theSerializedSizeIsMeasured() throws IOException {
        long small = LocalFile.nbtSize(LocalFile.toTag("srv", List.of(entry(1))));
        long bigger = LocalFile.nbtSize(LocalFile.toTag("srv", List.of(entry(1), entry(2), entry(3))));
        assertTrue(small > 0);
        assertTrue(bigger > small);
    }

    @Test
    void aBrokenGzipIsStillCorrupt() throws IOException {
        Path file = dir.resolve("junk.nbt");
        Files.writeString(file, "not gzip");
        assertEquals(LocalFile.Status.CORRUPT, LocalFile.read(file, "srv", 10).status());
        assertFalse(Files.exists(file));
    }

    @Test
    void aNewerFileIsLoadedButKeptAndFlaggedReadOnly() throws IOException {
        Path file = dir.resolve("newer.nbt");
        CompoundTag tag = LocalFile.toTag("srv", List.of(entry(1)));
        tag.putInt("version", LocalFile.VERSION + 1);
        net.minecraft.nbt.NbtIo.writeCompressed(tag, file);
        LocalFile.Loaded loaded = LocalFile.read(file, "srv", 10);
        assertEquals(LocalFile.Status.NEWER, loaded.status());
        assertEquals(1, loaded.entries().size());
        assertTrue(Files.exists(file));
    }

    // ---- save backoff ----

    @Test
    void afterAFailureTheNextTryWaitsAndTheWaitDoubles() {
        SaveBackoff b = new SaveBackoff();
        assertTrue(b.allowed(0, 10));
        assertTrue(b.failed(1000, 10, false), "the first failure of a streak is the one to log");
        assertFalse(b.allowed(1000, 10));
        assertFalse(b.allowed(1000 + SaveBackoff.FIRST_DELAY - 1, 10));
        assertTrue(b.allowed(1000 + SaveBackoff.FIRST_DELAY, 10));
        assertFalse(b.failed(2000, 10, false), "later ones in the streak are not logged again");
        assertFalse(b.allowed(2000 + SaveBackoff.FIRST_DELAY, 10), "now twice as long");
        assertTrue(b.allowed(2000 + 2 * SaveBackoff.FIRST_DELAY, 10));
    }

    @Test
    void theWaitStopsGrowingAtTenMinutes() {
        SaveBackoff b = new SaveBackoff();
        long t = 0;
        for (int i = 0; i < 20; i++) {
            b.failed(t, 5, false);
            t += SaveBackoff.MAX_DELAY;
        }
        assertTrue(b.allowed(t, 5));
        b.failed(t, 5, false);
        assertFalse(b.allowed(t + SaveBackoff.MAX_DELAY - 1, 5));
        assertTrue(b.allowed(t + SaveBackoff.MAX_DELAY, 5));
    }

    @Test
    void aTooBigStoreIsNotSavedAgainUntilItShrinks() {
        SaveBackoff b = new SaveBackoff();
        b.failed(0, 4000, true);
        assertTrue(b.blockedAsTooBig());
        assertFalse(b.allowed(1_000_000, 4000));
        assertFalse(b.allowed(1_000_000, 4500));
        assertTrue(b.allowed(1_000_000, 3999));
    }

    @Test
    void successForgetsTheStreak() {
        SaveBackoff b = new SaveBackoff();
        b.failed(0, 10, false);
        b.succeeded();
        assertTrue(b.allowed(0, 10));
        assertTrue(b.failed(5, 10, false), "a new streak is logged again");
    }

    // ---- world identity ----

    @Test
    void singleplayerWorldsAreToldApartByFolder() {
        String a = ServerId.of(true, "World A", null);
        String b = ServerId.of(true, "World B", null);
        assertNotEquals(a, b);
        assertEquals("singleplayer:World A", a);
        assertNull(ServerId.of(true, "", "ignored.example"), "unknown while the folder is unknown");
        assertNull(ServerId.of(true, null, null));
    }

    @Test
    void remoteServersUseTheirAddressAndUnknownIsNull() {
        assertEquals("play.example.org", ServerId.of(false, null, " play.example.org "));
        assertNull(ServerId.of(false, null, null));
        assertNull(ServerId.of(false, "folder", ""));
        assertNotEquals(ServerId.of(true, "x", null), ServerId.of(false, null, "x"));
    }

    // ---- click matching ----

    @Test
    void oneClickBeforeTheMenuIsMatched() {
        ClickLog log = new ClickLog();
        Object menuAtClick = new Object();
        log.add(1, 64, 2, 100, menuAtClick);
        ClickLog.Click c = log.take(103);
        assertNotNull(c);
        assertEquals(1, c.x());
        assertEquals(100, c.tick());
        assertTrue(log.isEmpty());
    }

    @Test
    void twoDifferentContainersInTheWindowAreAmbiguous() {
        ClickLog log = new ClickLog();
        log.add(1, 64, 2, 100, null);
        log.add(5, 64, 2, 102, null);
        assertNull(log.take(104), "which one opened is not known: nothing is captured");
        assertTrue(log.isEmpty());
    }

    @Test
    void repeatedClicksOnTheSameContainerAreFine() {
        ClickLog log = new ClickLog();
        log.add(1, 64, 2, 100, null);
        log.add(1, 64, 2, 101, null);
        ClickLog.Click c = log.take(103);
        assertNotNull(c);
        assertEquals(101, c.tick());
    }

    @Test
    void anOldClickExpiresAndDoesNotMakeALaterOneAmbiguous() {
        ClickLog log = new ClickLog();
        log.add(1, 64, 2, 100, null);
        log.add(5, 64, 2, 100 + CaptureRules.INTERACT_WINDOW + 5, null);
        ClickLog.Click c = log.take(100 + CaptureRules.INTERACT_WINDOW + 6);
        assertNotNull(c);
        assertEquals(5, c.x());

        log.add(9, 64, 9, 500, null);
        assertNull(log.take(500 + CaptureRules.INTERACT_WINDOW + 1), "expired");
    }

    @Test
    void aMenuWithNoClickIsNotMatched() {
        assertNull(new ClickLog().take(10));
    }

    // ---- file names ----

    @Test
    void windowsDeviceNamesGetASuffix() {
        for (String n : List.of("con", "nul", "aux", "prn", "com1", "com9", "lpt1", "lpt9", "CON", "Nul")) {
            assertEquals(n.toLowerCase() + "_", ContainerKey.safeFileName(n), n);
        }
        assertEquals("con_.txt", ContainerKey.safeFileName("con.txt"));
        assertEquals("com10", ContainerKey.safeFileName("com10"));
        assertEquals("console", ContainerKey.safeFileName("console"));
        assertEquals("com0", ContainerKey.safeFileName("com0"));
    }

    @Test
    void differentAddressesNeverShareAFile() {
        // all of these have the same readable part
        List<String> addresses = List.of("a:b", "a/b", "a b", "A:B", "a_b");
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String a : addresses) names.add(ContainerKey.fileName(a));
        assertEquals(addresses.size(), names.size());
        assertEquals(ContainerKey.fileName("a:b"), ContainerKey.fileName("a:b"), "stable");
        assertTrue(ContainerKey.fileName("con").startsWith("con_-"));
        assertTrue(ContainerKey.fileName("play.example.org:25565").matches("play\\.example\\.org_25565-[0-9a-f]{8}"));
        assertTrue(ContainerKey.fileName(null).matches("unknown-[0-9a-f]{8}"));
    }

    @Test
    void anOldFileNameIsRenamedOnFirstLoad() throws IOException {
        String server = "Play.Example.org:25565";
        Path old = LocalFile.legacyFileFor(dir, server);
        assertEquals(LocalFile.WriteStatus.OK, LocalFile.write(old, LocalFile.toTag(server, List.of(entry(1)))));
        LocalFile.migrate(dir, server);
        assertFalse(Files.exists(old));
        Path now = LocalFile.fileFor(dir, server);
        assertTrue(Files.exists(now));
        assertEquals(1, LocalFile.read(now, server, 10).entries().size());

        // a file with the new name is never replaced by an old one
        assertEquals(LocalFile.WriteStatus.OK, LocalFile.write(old, LocalFile.toTag(server, List.of())));
        LocalFile.migrate(dir, server);
        assertTrue(Files.exists(old));
        assertEquals(1, LocalFile.read(now, server, 10).entries().size());
    }

    @Test
    void writeAndReadRoundTripOnDisk() {
        Path file = LocalFile.fileFor(dir.resolve("idx"), "srv");
        assertEquals(LocalFile.WriteStatus.OK, LocalFile.write(file, LocalFile.toTag("srv", List.of(entry(7)))));
        LocalFile.Loaded loaded = LocalFile.read(file, "srv", 10);
        assertEquals(LocalFile.Status.OK, loaded.status());
        assertEquals(7, loaded.entries().get(0).key().x());
    }
}

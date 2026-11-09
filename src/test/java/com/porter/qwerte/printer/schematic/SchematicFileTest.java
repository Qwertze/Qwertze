package com.porter.qwerte.printer.schematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchematicFileTest {
    @TempDir
    Path root;

    private Path write(String rel, byte[] bytes) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
        return p;
    }

    private static void refused(String name) {
        assertNull(SchematicFile.normalize(name)[0], "should be refused: " + name);
        assertNotNull(SchematicFile.normalize(name)[1]);
    }

    private static String ok(String name) {
        String[] n = SchematicFile.normalize(name);
        assertNull(n[1], name + ": " + n[1]);
        return n[0];
    }

    @Test
    void goodNamesGetTheExtensionAppendedWhenMissing() {
        assertEquals("castle.litematic", ok("castle"));
        assertEquals("castle.litematic", ok("castle.litematic"));
        assertEquals("Castle.LITEMATIC", ok("Castle.LITEMATIC"));
        assertEquals("a/b/c.litematic", ok("a/b/c"));
        assertEquals("my build v2.litematic", ok("my build v2"));
    }

    @Test
    void parentDotAndAbsoluteNamesAreRefused() {
        for (String bad : new String[] { "..", ".", "...", "../x", "a/../b", "a/./b", "/etc/passwd", "/x", "a//b", "a/", "x/..", "../../x.litematic" }) refused(bad);
    }

    @Test
    void driveLettersBackslashesAndWindowsCharactersAreRefused() {
        for (String bad : new String[] { "C:\\x.litematic", "C:x", "c:/x", "a\\b", "\\\\server\\share\\x", "a:b", "a<b", "a>b", "a\"b", "a|b", "a?b", "a*b" }) refused(bad);
    }

    @Test
    void emptyLongAndControlCharacterNamesAreRefused() {
        refused(null);
        refused("");
        refused("   ");
        refused("a".repeat(SchematicFile.MAX_NAME + 1));
        assertEquals(("a".repeat(SchematicFile.MAX_NAME - 10)) + ".litematic", ok("a".repeat(SchematicFile.MAX_NAME - 10)));
        refused("a\u0000b");
        refused("a\nb");
        refused("a\u007fb");
        refused(".litematic");
        refused("sub/.litematic");
    }

    @Test
    void moreThanTwoSubfoldersAreRefused() {
        ok("a/b/c");
        refused("a/b/c/d");
    }

    @Test
    void anyOtherExtensionIsRefused() {
        for (String bad : new String[] { "a.schem", "a.nbt", "a.txt", "a.exe", "a.litematic.txt", "a.zip", ".hidden", "a.b" }) refused(bad);
    }

    @Test
    void windowsDeviceNamesAndTrailingDotsOrSpacesAreRefused() {
        for (String bad : new String[] { "con", "NUL.litematic", "a/aux", "com1", "lpt9.litematic", "name.", "name ", " name", "a /b" }) refused(bad);
    }

    @Test
    void aPlainFileResolvesInsideTheRoot() throws IOException {
        Path f = write("sub/castle.litematic", new byte[] { 1 });
        SchematicFile.Resolved r = SchematicFile.resolve(root, "sub/castle");
        assertTrue(r.ok(), r.error());
        assertEquals(f.toRealPath(), r.path().toRealPath());
    }

    @Test
    void aMissingFileAndADirectoryAreNotPlainFiles() throws IOException {
        assertFalse(SchematicFile.resolve(root, "nothing").ok());
        Files.createDirectories(root.resolve("dir.litematic"));
        assertFalse(SchematicFile.resolve(root, "dir").ok());
        assertFalse(SchematicFile.resolve(null, "x").ok());
    }

    @Test
    void aSymbolicLinkToAFileIsNeverFollowed(@TempDir Path other) throws IOException {
        Path real = write("real.litematic", new byte[] { 1 });
        Path outside = other.resolve("outside.litematic");
        Files.write(outside, new byte[] { 1 });
        try {
            Files.createSymbolicLink(root.resolve("link.litematic"), outside);
            Files.createSymbolicLink(root.resolve("inner.litematic"), real);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("symbolic links are not available here");
        }
        assertFalse(SchematicFile.resolve(root, "link").ok());
        assertFalse(SchematicFile.resolve(root, "inner").ok(), "even a link that stays inside is refused");
        assertTrue(SchematicFile.resolve(root, "real").ok());
    }

    @Test
    void aFolderLinkOrJunctionThatLeavesTheRootIsRefused(@TempDir Path other) throws IOException, InterruptedException {
        Files.write(other.resolve("secret.litematic"), new byte[] { 1 });
        Path link = root.resolve("escape");
        boolean made = false;
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            // a junction needs no privilege on Windows, which is the case a symbolic-link-only check misses
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), other.toString()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            made = p.waitFor() == 0 && Files.exists(link);
        } else {
            try {
                Files.createSymbolicLink(link, other);
                made = true;
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                made = false;
            }
        }
        Assumptions.assumeTrue(made, "no way to make a link here");
        try {
            assertTrue(Files.exists(other.resolve("secret.litematic")));
            SchematicFile.Resolved r = SchematicFile.resolve(root, "escape/secret");
            assertFalse(r.ok(), "a junction out of the root must not resolve");
        } finally {
            Files.deleteIfExists(link); // removes the link itself, so the temp folder clean-up never walks through it
        }
    }

    @Test
    void anAllowedPathMustEndInLitematicAndLieInsideARoot(@TempDir Path second, @TempDir Path outside) throws IOException {
        Path a = write("a.litematic", new byte[] { 1 });
        Path b = second.resolve("b.litematic");
        Files.write(b, new byte[] { 1 });
        Path c = outside.resolve("c.litematic");
        Files.write(c, new byte[] { 1 });
        Path d = write("d.schem", new byte[] { 1 });
        List<Path> roots = List.of(root, second);
        assertTrue(SchematicFile.resolveAllowed(roots, a).ok());
        assertTrue(SchematicFile.resolveAllowed(roots, b).ok());
        assertFalse(SchematicFile.resolveAllowed(roots, c).ok());
        assertFalse(SchematicFile.resolveAllowed(roots, d).ok());
        assertFalse(SchematicFile.resolveAllowed(roots, null).ok());
        assertFalse(SchematicFile.resolveAllowed(roots, root.resolve("..").resolve(outside.getFileName()).resolve("c.litematic")).ok());
        assertFalse(SchematicFile.resolveAllowed(java.util.Arrays.asList((Path) null), a).ok());
    }

    @Test
    void readingRefusesEmptyAndOversizeFilesBeforeReading() throws IOException {
        Path empty = write("empty.litematic", new byte[0]);
        assertFalse(SchematicFile.readBytes(empty).ok());
        Path big = root.resolve("big.litematic");
        try (RandomAccessFile f = new RandomAccessFile(big.toFile(), "rw")) {
            f.setLength(LitematicReader.MAX_COMPRESSED + 1);
        }
        SchematicFile.Bytes r = SchematicFile.readBytes(big);
        assertFalse(r.ok());
        assertTrue(r.error().contains("larger"));
        assertFalse(SchematicFile.readBytes(root).ok(), "a directory");
        assertFalse(SchematicFile.readBytes(root.resolve("absent.litematic")).ok());
    }

    @Test
    void aValidFileLoadsAndABrokenOneGivesAnError() throws IOException {
        Path good = write("good.litematic", LitematicFixtures.gzip(LitematicFixtures.file(LitematicFixtures.small())));
        SchematicFile.Result r = SchematicFile.load(good, null);
        assertTrue(r.ok(), r.error());
        assertEquals(12, r.data().totalVolume);
        Path bad = write("bad.litematic", "not gzip at all".getBytes());
        SchematicFile.Result b = SchematicFile.load(bad, Duration.ofSeconds(5));
        assertFalse(b.ok());
        assertNotNull(b.error());
    }

    @Test
    void asyncLoadingDeliversThroughThePostedRunnable() throws Exception {
        Path good = write("g.litematic", LitematicFixtures.gzip(LitematicFixtures.file(LitematicFixtures.small())));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<SchematicFile.Result> got = new AtomicReference<>();
        AtomicReference<String> thread = new AtomicReference<>();
        SchematicFile.loadAsync(good, Duration.ofSeconds(10), r -> {
            thread.set(Thread.currentThread().getName());
            r.run();
        }, res -> {
            got.set(res);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertTrue(got.get().ok());
        assertEquals("Qwerte-Schematic", thread.get());
    }

    @Test
    void deviceNamesAreRefusedEvenWithTrailingSpacesSuperscriptsOrConsoleNames() {
        for (String bad : new String[] { "con .litematic", "CON  .litematic", "nul  .litematic", "com¹", "LPT².litematic", "conin$", "CONOUT$.litematic", "a/com³ .litematic" }) refused(bad);
    }

    @Test
    void aLitematicaSuppliedNameWithAColonIsRefused(@TempDir Path other) throws IOException {
        Path stream;
        try {
            stream = root.resolve("ab:y.litematic");
        } catch (java.nio.file.InvalidPathException e) {
            Assumptions.abort("this file system does not take a colon in a path");
            return;
        }
        SchematicFile.Resolved r = SchematicFile.resolveAllowed(List.of(root), stream);
        assertFalse(r.ok());
        assertTrue(r.error().contains("not usable"), r.error());
    }

    @Test
    void aResolvedPathIsTheRealPathOfTheFile() throws IOException {
        write("sub/x.litematic", new byte[] { 1 });
        SchematicFile.Resolved r = SchematicFile.resolve(root, "sub/x");
        assertEquals(root.resolve("sub/x.litematic").toRealPath(), r.path());
    }

    @Test
    void aCancelledLoadNeverDelivers() throws Exception {
        Path good = write("c.litematic", LitematicFixtures.gzip(LitematicFixtures.file(LitematicFixtures.small())));
        CountDownLatch posted = new CountDownLatch(1);
        AtomicReference<Runnable> held = new AtomicReference<>();
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.Future<?> f = SchematicFile.loadAsync(good, Duration.ofSeconds(10), r -> {
            held.set(r);
            posted.countDown();
            try {
                release.await(10, TimeUnit.SECONDS); // the task is still running while the test cancels it
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, res -> {
            throw new AssertionError("delivered after cancel");
        });
        assertTrue(posted.await(10, TimeUnit.SECONDS));
        assertTrue(f.cancel(false));
        release.countDown();
        held.get().run();
    }
}

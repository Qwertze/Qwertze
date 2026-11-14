package com.porter.qwerte.core.farm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FarmKeyTest {
    private static FileSystem posixFs() {
        return Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("basic", "owner", "posix", "unix").build());
    }

    private static String b64(int n) {
        byte[] k = new byte[n];
        for (int i = 0; i < n; i++) {
            k[i] = (byte) (i + 1);
        }
        return Base64.getEncoder().encodeToString(k);
    }

    // ---------- environment override ----------

    @Test
    void envKeyMustBeExactly32BytesAfterTrimAndBase64() {
        FarmKey.Loaded ok = FarmKey.load(Path.of("does-not-exist"), b64(32));
        assertTrue(ok.ok());
        assertEquals("env", ok.source());
        assertEquals(32, ok.key().length);
        assertEquals(HexFormat.of().formatHex(Seal.keyId(ok.key())), ok.keyIdHex());
        // surrounding whitespace that trims to 32 bytes is accepted
        FarmKey.Loaded padded = FarmKey.load(Path.of("does-not-exist"), "  \n" + b64(32) + " \r\n");
        assertTrue(padded.ok());
        assertArrayEquals(ok.key(), padded.key());
        // wrong lengths and non-base64 are key_invalid
        assertEquals("key_invalid", FarmKey.load(Path.of("x"), b64(31)).code());
        assertEquals("key_invalid", FarmKey.load(Path.of("x"), b64(33)).code());
        assertEquals("key_invalid", FarmKey.load(Path.of("x"), "this is not base64!!").code());
        assertEquals("key_invalid", FarmKey.load(Path.of("x"), "   ").code());
        assertEquals("key_invalid", FarmKey.load(Path.of("x"), b64(32).substring(0, 20)).code());
    }

    @Test
    void envNeverLeaksIntoResultsAndNeverFallsBackToTheFile() throws IOException {
        try (FileSystem fs = posixFs()) {
            Path dir = fs.getPath("/farm");
            FarmKey.Loaded made = FarmKey.load(dir, null);
            assertTrue(made.ok());
            // a bad env value fails closed even though a valid key file exists
            String bad = b64(31);
            FarmKey.Loaded r = FarmKey.load(dir, bad);
            assertEquals("key_invalid", r.code());
            assertNull(r.key());
            assertFalse(r.toString().contains(bad));
            String good = b64(32);
            FarmKey.Loaded g = FarmKey.load(dir, good);
            assertFalse(g.toString().contains(good));
            assertFalse(g.toString().contains(HexFormat.of().formatHex(g.key())));
            assertEquals("Loaded[env, id " + g.keyIdHex() + "]", g.toString());
            // an empty variable counts as not set
            assertEquals("file", FarmKey.load(dir, "").source());
        }
    }

    // ---------- the key file (in-memory POSIX file system) ----------

    @Test
    void createsTheKeyAtomicallyWith0600AndTheDirectoryWith0700() throws IOException {
        try (FileSystem fs = posixFs()) {
            Path dir = fs.getPath("/plugins/farm");
            FarmKey.Loaded a = FarmKey.load(dir, null);
            assertTrue(a.ok(), a.toString());
            assertEquals("file", a.source());
            Path file = dir.resolve("farm.key");
            assertEquals(32, Files.size(file));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(dir));
            assertArrayEquals(a.key(), Files.readAllBytes(file));
            // a second load reads the same key
            FarmKey.Loaded b = FarmKey.load(dir, null);
            assertArrayEquals(a.key(), b.key());
            // a different install gets a different random key
            Path dir2 = fs.getPath("/other/farm");
            assertFalse(java.util.Arrays.equals(a.key(), FarmKey.load(dir2, null).key()));
        }
    }

    @Test
    void neverCreatesAKeyOverExistingSealedFiles() throws IOException {
        try (FileSystem fs = posixFs()) {
            Path dir = fs.getPath("/farm");
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Files.write(dir.resolve("f-1a2b3c4d.qse"), new byte[] {1, 2, 3});
            FarmKey.Loaded r = FarmKey.load(dir, null);
            assertEquals("key_missing", r.code());
            assertFalse(Files.exists(dir.resolve("farm.key")), "no key was invented");
            assertTrue(Files.exists(dir.resolve("f-1a2b3c4d.qse")), "nothing deleted");
            // the env override still works with sealed files present (and creates no file)
            assertTrue(FarmKey.load(dir, b64(32)).ok());
            assertFalse(Files.exists(dir.resolve("farm.key")));
            // a stale temporary file is not a sealed file
            Files.delete(dir.resolve("f-1a2b3c4d.qse"));
            Files.write(dir.resolve("f-1a2b3c4d.qse.tmp"), new byte[] {1});
            assertTrue(FarmKey.load(dir, null).ok());
        }
    }

    @Test
    void failsClosedWhenTheModesAreNotPrivate() throws IOException {
        try (FileSystem fs = posixFs()) {
            Path dir = fs.getPath("/farm");
            assertTrue(FarmKey.load(dir, null).ok());
            Path file = dir.resolve("farm.key");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
            assertEquals("key_perms", FarmKey.load(dir, null).code());
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            assertTrue(FarmKey.load(dir, null).ok());
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
            assertEquals("key_perms", FarmKey.load(dir, null).code());
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
            assertTrue(FarmKey.load(dir, null).ok(), "0400 is private too");
            assertTrue(Files.exists(file), "a refused key file is never deleted");
        }
    }

    @Test
    void aKeyFileOfTheWrongSizeIsKeyInvalidAndLeftAlone() throws IOException {
        try (FileSystem fs = posixFs()) {
            Path dir = fs.getPath("/farm");
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path file = dir.resolve("farm.key");
            Files.write(file, new byte[31]);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            assertEquals("key_invalid", FarmKey.load(dir, null).code());
            assertEquals(31, Files.size(file));
        }
    }

    @Test
    void aSymlinkedKeyFileIsRefused() throws IOException {
        try (FileSystem fs = posixFs()) {
            Path dir = fs.getPath("/farm");
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path real = fs.getPath("/elsewhere.key");
            Files.write(real, new byte[32]);
            Files.setPosixFilePermissions(real, PosixFilePermissions.fromString("rw-------"));
            Files.createSymbolicLink(dir.resolve("farm.key"), real);
            assertEquals("key_perms", FarmKey.load(dir, null).code());
        }
    }

    @Test
    void anExistingDirectoryWithLooseModesIsTightenedBeforeTheKeyIsCreated() throws IOException {
        try (FileSystem fs = posixFs()) {
            Path dir = fs.getPath("/farm");
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
            assertTrue(FarmKey.load(dir, null).ok());
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(dir));
        }
    }

    // ---------- non-POSIX file systems (this Windows box): fail closed ----------

    @Test
    void aFileSystemWithoutPosixModesFailsClosedAndCreatesNothing(@TempDir Path tmp) throws IOException {
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        Path dir = tmp.resolve("farm");
        FarmKey.Loaded r = FarmKey.load(dir, null);
        if (posix) {
            assertTrue(r.ok(), "on a POSIX host the real file system works too");
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(dir.resolve("farm.key")));
        } else {
            assertEquals("key_perms", r.code());
            assertNull(r.key());
            assertFalse(Files.exists(dir.resolve("farm.key")), "no key file is left behind");
            // the override is the only way on such a host
            assertTrue(FarmKey.load(dir, b64(32)).ok());
        }
        assertNotNull(r.toString());
    }
}

package com.porter.qwerte.manager.model;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicFilesTest {
    @TempDir
    Path root;

    private Path file(String rel, int size, long modified) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[size]);
        Files.setLastModifiedTime(p, FileTime.fromMillis(modified));
        return p;
    }

    @Test
    void listsOnlySchematicExtensionsTwoFoldersDeepNewestFirst() throws IOException {
        file("a.litematic", 10, 1000);
        file("B.SCHEM", 10, 3000);
        file("sub/c.schematic", 10, 2000);
        file("sub/deep/d.nbt", 10, 500);
        file("sub/deep/deeper/e.litematic", 10, 500);
        file("readme.txt", 10, 5000);
        file(".litematic", 10, 5000);
        List<SchematicFiles.Entry> list = SchematicFiles.scan(root);
        assertEquals(List.of("B.SCHEM", "sub/c.schematic", "a.litematic", "sub/deep/d.nbt"), list.stream().map(SchematicFiles.Entry::relative).toList());
        assertEquals("c.schematic", list.get(1).name());
        assertEquals(10, list.get(0).size());
    }

    @Test
    void anAbsentFolderIsEmpty() throws IOException {
        assertTrue(SchematicFiles.scan(root.resolve("nope")).isEmpty());
        assertTrue(SchematicFiles.scan(null).isEmpty());
    }

    @Test
    void filtersByPathCaseBlind() throws IOException {
        file("House.litematic", 1, 1);
        file("towers/Castle.schem", 1, 2);
        List<SchematicFiles.Entry> list = SchematicFiles.scan(root);
        assertEquals(1, SchematicFiles.filter(list, "castle").size());
        assertEquals(1, SchematicFiles.filter(list, "TOWERS/").size());
        assertEquals(2, SchematicFiles.filter(list, "  ").size());
    }

    @Test
    void symbolicLinksAreNeverListedOrRead() throws IOException {
        Path real = file("real.litematic", 10, 1000);
        Path link = root.resolve("link.litematic");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("symbolic links are not available here");
        }
        assertEquals(List.of("real.litematic"), SchematicFiles.scan(root).stream().map(SchematicFiles.Entry::relative).toList());
        assertFalse(SchematicFiles.read(link).ok());
    }

    @Test
    void aLinkedRootIsFollowedOnceButAFolderLinkBelowItIsNot(@TempDir Path other) throws IOException {
        Path outside = other.resolve("secret.litematic");
        Files.write(outside, new byte[10]);
        Path real = file("real/ok.litematic", 10, 1000);
        Path link = other.resolve("linked-root");
        try {
            Files.createSymbolicLink(link, root.resolve("real"));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("symbolic links are not available here");
        }
        // the schematics folder itself is a link: it is used
        List<SchematicFiles.Entry> viaLink = SchematicFiles.scan(link);
        assertEquals(List.of("ok.litematic"), viaLink.stream().map(SchematicFiles.Entry::relative).toList());
        assertTrue(SchematicFiles.read(link, viaLink.get(0).path()).ok());

        // a folder link below the root leading outside is not entered, and a file read through it is refused
        Path escape = root.resolve("escape");
        Files.createSymbolicLink(escape, other);
        assertEquals(List.of("real/ok.litematic"), SchematicFiles.scan(root).stream().map(SchematicFiles.Entry::relative).toList());
        Path through = escape.resolve("secret.litematic");
        assertFalse(SchematicFiles.inside(root, through));
        assertEquals("That file is outside the schematics folder.", SchematicFiles.read(root, through).error());
        assertTrue(SchematicFiles.inside(root, real));
        assertFalse(SchematicFiles.inside(root, outside));
        assertFalse(SchematicFiles.inside(null, real));
        assertFalse(SchematicFiles.inside(root, root.resolve("missing.litematic")));
    }

    @Test
    void theSizeIsCheckedBeforeReading() throws IOException {
        Path ok = file("ok.litematic", 100, 1);
        assertTrue(SchematicFiles.read(ok).ok());
        assertEquals(100, SchematicFiles.read(ok).bytes().length);
        assertEquals("That file is empty.", SchematicFiles.read(file("empty.litematic", 0, 1)).error());

        // a sparse file of 8 MiB + 1 is refused without being read
        Path big = root.resolve("big.litematic");
        try (RandomAccessFile f = new RandomAccessFile(big.toFile(), "rw")) {
            f.setLength(SchematicFiles.MAX_BYTES + 1);
        }
        SchematicFiles.Entry entry = SchematicFiles.scan(root).stream().filter(e -> e.name().equals("big.litematic")).findFirst().orElseThrow();
        assertTrue(entry.tooBig());
        assertEquals("That file is larger than 8 MiB.", SchematicFiles.read(big).error());

        Path exact = root.resolve("exact.litematic");
        try (RandomAccessFile f = new RandomAccessFile(exact.toFile(), "rw")) {
            f.setLength(SchematicFiles.MAX_BYTES);
        }
        assertTrue(SchematicFiles.read(exact).ok());
        assertFalse(SchematicFiles.read(root.resolve("missing.litematic")).ok());
        assertFalse(SchematicFiles.read(root).ok());
    }
}

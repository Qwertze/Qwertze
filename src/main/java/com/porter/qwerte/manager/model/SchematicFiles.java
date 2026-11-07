package com.porter.qwerte.manager.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * The schematics the picker offers: the files of one folder (and two folders below it) with a schematic extension. The folder
 * itself may be a link (it is resolved once); below it, symbolic links, Windows junctions and other special files or folders
 * are never listed, entered or read, and a file is only read when it really lies inside the folder. The size is checked before
 * any byte is read. Blocking: call it
 * off the client thread. Pure (no Minecraft classes).
 */
public final class SchematicFiles {
    private SchematicFiles() {
    }

    /** The server refuses a body over 8 MiB. */
    public static final long MAX_BYTES = 8L * 1024 * 1024;
    /** Folders below the root that are looked into. */
    public static final int MAX_DEPTH = 2;
    public static final int MAX_FILES = 500;
    public static final List<String> EXTENSIONS = List.of(".litematic", ".schem", ".schematic", ".nbt");

    /** @param name the bare file name (what the server is told); relative the path below the root, for the list */
    public record Entry(Path path, String name, String relative, long size, long modified) {
        public boolean tooBig() {
            return size > MAX_BYTES;
        }

        public boolean empty() {
            return size <= 0;
        }
    }

    /** What reading a file gave: the bytes, or why not. */
    public record Loaded(byte[] bytes, String error) {
        public boolean ok() {
            return bytes != null;
        }
    }

    public static boolean wanted(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) if (lower.endsWith(ext) && lower.length() > ext.length()) return true;
        return false;
    }

    /** The files below root, newest first. An absent folder gives an empty list; a root that is a link is followed once. */
    public static List<Entry> scan(Path root) throws IOException {
        List<Entry> out = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) return out;
        Path real = root.toRealPath();
        Files.walkFileTree(real, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH + 1, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                // a junction or link below the root may lead anywhere
                if (!dir.equals(real) && (a.isSymbolicLink() || a.isOther())) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path p, BasicFileAttributes a) {
                if (out.size() >= MAX_FILES) return FileVisitResult.TERMINATE;
                Path fileName = p.getFileName();
                if (fileName == null || !wanted(fileName.toString())) return FileVisitResult.CONTINUE;
                if (!a.isRegularFile() || a.isSymbolicLink() || a.isOther()) return FileVisitResult.CONTINUE;
                out.add(new Entry(p, fileName.toString(), real.relativize(p).toString().replace("\\", "/"), a.size(), a.lastModifiedTime().toMillis()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path p, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(Comparator.comparingLong(Entry::modified).reversed().thenComparing(Entry::relative));
        return out;
    }

    /** The entries whose path or name contains the query (case blind). */
    public static List<Entry> filter(List<Entry> entries, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return entries;
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.relative().toLowerCase(Locale.ROOT).contains(q)) out.add(e);
        return out;
    }

    /** True when {@code path} really lies inside {@code root} (links and junctions resolved). */
    public static boolean inside(Path root, Path path) {
        try {
            return root != null && path != null && path.toRealPath().startsWith(root.toRealPath());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** As {@link #read(Path)}, and refused when the file does not lie inside {@code root}. */
    public static Loaded read(Path root, Path path) {
        if (!inside(root, path)) return new Loaded(null, "That file is outside the schematics folder.");
        return read(path);
    }

    /** Reads a file for upload: refused when it is not a plain file, empty or over 8 MiB, judged before reading. */
    public static Loaded read(Path path) {
        try {
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile() || a.isSymbolicLink() || a.isOther()) return new Loaded(null, "That is not a plain file.");
            if (a.size() <= 0) return new Loaded(null, "That file is empty.");
            if (a.size() > MAX_BYTES) return new Loaded(null, "That file is larger than 8 MiB.");
            // never more than the limit plus one byte, even when the file grew since the check
            try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = in.readNBytes((int) MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) return new Loaded(null, "That file is larger than 8 MiB.");
                if (bytes.length == 0) return new Loaded(null, "That file is empty.");
                return new Loaded(bytes, "");
            }
        } catch (IOException | RuntimeException e) {
            return new Loaded(null, "Could not read the file.");
        }
    }
}

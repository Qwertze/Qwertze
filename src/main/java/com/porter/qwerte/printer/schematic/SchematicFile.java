package com.porter.qwerte.printer.schematic;

import com.porter.qwerte.manager.model.SchematicFiles;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Finding and reading a schematic file safely: a typed name is validated and resolved below the schematics folder, a
 * path handed over by Litematica must lie inside an allowed folder, links are never followed, and the read is bounded.
 * Blocking; use {@link #loadAsync} off the client thread. Pure (no Minecraft game objects).
 */
public final class SchematicFile {
    private SchematicFile() {
    }

    public static final String EXTENSION = ".litematic";
    public static final int MAX_NAME = 128;
    /** Folders allowed below the schematics folder. */
    public static final int MAX_SUBFOLDERS = 2;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
        "com¹", "com²", "com³", "lpt¹", "lpt²", "lpt³", "conin$", "conout$");

    /** A path, or why not. */
    public record Resolved(Path path, String error) {
        public boolean ok() {
            return path != null;
        }
    }

    /** What loading gave: the schematic, or why not. */
    public record Result(SchematicData data, String error) {
        public boolean ok() {
            return data != null;
        }
    }

    /** The bytes of a file, or why not. */
    public record Bytes(byte[] bytes, String error) {
        public boolean ok() {
            return bytes != null;
        }
    }

    // ---------- names ----------

    /**
     * Checks a name typed by the user and gives it back as a relative path with the extension: an error string in
     * the second slot when refused. Refused: empty, over 128 characters, control characters, any of {@code : \ < > " | ? *},
     * a leading '/', empty or dot-only or space/dot-ended segments, Windows device names, more than two subfolders, an
     * extension other than .litematic.
     */
    public static String[] normalize(String raw) {
        if (raw == null || raw.isBlank()) return err("Give the schematic's file name.");
        if (raw.length() > MAX_NAME) return err("That file name is too long.");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f) return err("That file name has control characters.");
            if (":\\<>\"|?*".indexOf(c) >= 0) return err("A file name can't contain " + c + ".");
        }
        if (raw.startsWith("/")) return err("Use a name relative to the schematics folder.");
        String[] segments = raw.split("/", -1);
        if (segments.length > MAX_SUBFOLDERS + 1) return err("At most " + MAX_SUBFOLDERS + " subfolders are allowed.");
        for (String s : segments) {
            if (s.isEmpty()) return err("That file name has an empty folder name.");
            if (s.chars().allMatch(ch -> ch == '.')) return err("A file name can't contain . or .. segments.");
            if (s.endsWith(".") || s.endsWith(" ") || s.startsWith(" ")) return err("A name can't start with a space or end with a space or dot.");
        }
        String last = segments[segments.length - 1];
        String name = raw;
        if (last.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
            if (last.length() == EXTENSION.length()) return err("Give the schematic's file name.");
        } else if (last.indexOf('.') >= 0) {
            return err("Only " + EXTENSION + " files can be used (leave the extension out).");
        } else {
            name = raw + EXTENSION;
        }
        for (String s : name.split("/")) {
            String stem = s.contains(".") ? s.substring(0, s.indexOf('.')) : s;
            if (RESERVED.contains(stem.stripTrailing().toLowerCase(Locale.ROOT))) return err("That file name is reserved by Windows.");
        }
        return new String[] { name, null };
    }

    private static String[] err(String message) {
        return new String[] { null, message };
    }

    /** The file a typed name means below {@code root} (the schematics folder), checked to be a plain file really inside it. */
    public static Resolved resolve(Path root, String raw) {
        String[] n = normalize(raw);
        if (n[0] == null) return new Resolved(null, n[1]);
        if (root == null) return new Resolved(null, "There is no schematics folder.");
        try {
            Path candidate = root.resolve(n[0]).normalize();
            if (!candidate.startsWith(root.normalize())) return new Resolved(null, "That file is outside the schematics folder.");
            return checked(root, candidate);
        } catch (RuntimeException e) {
            return new Resolved(null, "That is not a usable file name.");
        }
    }

    /**
     * A file path from elsewhere (Litematica says where it loaded a schematic from): it must end in .litematic and lie
     * inside one of {@code roots}.
     */
    public static Resolved resolveAllowed(List<Path> roots, Path file) {
        if (file == null) return new Resolved(null, "There is no file.");
        Path name = file.getFileName();
        if (name == null || !name.toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION)) return new Resolved(null, "That is not a .litematic file.");
        if (name.toString().indexOf(':') >= 0) return new Resolved(null, "That file name is not usable."); // an NTFS alternate data stream
        for (Path root : roots) {
            if (root == null) continue;
            Resolved r = checked(root, file);
            if (r.ok()) return r;
        }
        return new Resolved(null, "That file is outside the allowed schematics folders.");
    }

    private static Resolved checked(Path root, Path candidate) {
        try {
            BasicFileAttributes a = Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile() || a.isSymbolicLink() || a.isOther()) return new Resolved(null, "That is not a plain file.");
            if (!SchematicFiles.inside(root, candidate)) return new Resolved(null, "That file is outside the schematics folder.");
            // the real path is what gets read, so a folder swapped for a link after this check is not followed
            return new Resolved(candidate.toRealPath(), null);
        } catch (java.nio.file.NoSuchFileException e) {
            return new Resolved(null, "No such schematic file.");
        } catch (IOException | RuntimeException e) {
            return new Resolved(null, "Could not look at that file.");
        }
    }

    // ---------- reading ----------

    /** Reads a resolved file: a plain file of at most {@link LitematicReader#MAX_COMPRESSED}, judged before reading and bounded while reading. */
    public static Bytes readBytes(Path path) {
        long max = LitematicReader.MAX_COMPRESSED;
        try {
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile() || a.isSymbolicLink() || a.isOther()) return new Bytes(null, "That is not a plain file.");
            if (a.size() <= 0) return new Bytes(null, "That file is empty.");
            if (a.size() > max) return new Bytes(null, "That file is larger than " + (max >> 20) + " MiB.");
            try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = in.readNBytes((int) max + 1);
                if (bytes.length > max) return new Bytes(null, "That file is larger than " + (max >> 20) + " MiB.");
                if (bytes.length == 0) return new Bytes(null, "That file is empty.");
                return new Bytes(bytes, null);
            }
        } catch (IOException | RuntimeException e) {
            return new Bytes(null, "Could not read the file.");
        }
    }

    /** Reads and parses a resolved file; blocking. */
    public static Result load(Path path, Duration timeout) {
        try {
            Bytes b = readBytes(path);
            if (!b.ok()) return new Result(null, b.error());
            long deadline = System.nanoTime() + (timeout == null ? DEFAULT_TIMEOUT : timeout).toNanos();
            return new Result(LitematicReader.read(b.bytes(), deadline), null);
        } catch (LitematicReader.Failure e) {
            return new Result(null, e.getMessage());
        } catch (OutOfMemoryError e) {
            return new Result(null, "Not enough memory to read that schematic.");
        } catch (RuntimeException e) {
            return new Result(null, "The schematic could not be read.");
        }
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Qwerte-Schematic");
        t.setDaemon(true);
        return t;
    });

    /**
     * Reads and parses {@code path} on a worker thread. {@code post} hands a Runnable to the thread that may touch the
     * game (the client thread: {@code mc::execute}) and {@code done} runs there with the result, unless the returned
     * future was cancelled first.
     */
    public static Future<?> loadAsync(Path path, Duration timeout, Consumer<Runnable> post, Consumer<Result> done) {
        AtomicReference<Future<?>> self = new AtomicReference<>();
        Future<?> future = WORKER.submit(() -> {
            Result r = load(path, timeout);
            if (Thread.currentThread().isInterrupted()) return;
            post.accept(() -> {
                Future<?> me = self.get();
                if (me == null || !me.isCancelled()) done.accept(r); // a cancel after the post still drops the result
            });
        });
        self.set(future);
        return future;
    }
}

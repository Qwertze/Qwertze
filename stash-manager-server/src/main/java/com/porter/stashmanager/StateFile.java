package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small helpers for the versioned JSON state files of the task slice (bots.json, ops.json, tasks.json, farms.json). */
final class StateFile {
    /** The Gson of the state files: nulls are left out (a missing field is read back as null), so no field needs a null adapter. */
    static final com.google.gson.Gson STORE = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();

    /** Tests only: makes a write to a matching file throw (a full disk, a locked file). */
    static volatile java.util.function.Predicate<Path> writeFault;

    private StateFile() {
    }

    /** The root object of a state file ({"version":1,...}), or null when it is missing or unreadable. */
    static JsonObject read(Path file) {
        if (!Files.exists(file)) return null;
        try {
            JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) return null;
            JsonObject root = e.getAsJsonObject();
            if (!(root.get("version") instanceof JsonPrimitive v) || !v.isNumber() || v.getAsInt() != 1) return null;
            return root;
        } catch (IOException | RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    /** Writes owner-only and atomically; retries a few times when Windows holds the target open for a moment. */
    static void write(Path file, byte[] bytes) throws IOException {
        java.util.function.Predicate<Path> fault = writeFault;
        if (fault != null && fault.test(file)) throw new IOException("injected write failure");
        for (int attempt = 1; ; attempt++) {
            try {
                AtomicFiles.write(file, bytes, true);
                return;
            } catch (java.nio.file.AccessDeniedException e) {
                if (attempt >= 5) throw e;
                try {
                    Thread.sleep(25L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Writes root; the last readable file is copied to the backup first (a damaged file never replaces a good backup). */
    static void writeWithBackup(Path file, Path backup, JsonObject root) throws IOException {
        if (Files.exists(file) && read(file) != null) writeBackup(backup, Files.readAllBytes(file));
        write(file, root);
    }

    /**
     * Writes a backup copy. A failing backup never blocks the primary write: it is logged with a closed code and the file name
     * (no content) and the caller carries on; the old backup is untouched because every write is atomic.
     */
    static void writeBackup(Path backup, byte[] good) {
        try {
            write(backup, good);
        } catch (IOException | RuntimeException e) {
            System.err.println("state_backup_failed " + backup.getFileName());
        }
    }

    static void write(Path file, JsonObject root) throws IOException {
        write(file, Json.GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    /** Moves an unreadable file aside to name.bad-&lt;ts&gt; (never deleted). */
    static void moveAside(Path file, long ts) {
        try {
            AtomicFiles.move(file, file.resolveSibling(file.getFileName() + ".bad-" + ts));
        } catch (IOException | RuntimeException ignored) {
            // The next write replaces it anyway.
        }
    }
}

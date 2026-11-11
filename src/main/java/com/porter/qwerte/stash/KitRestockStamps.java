package com.porter.qwerte.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * When kit restock started its jobs (epoch milliseconds), for the per-hour cap (KIT-CONTRACT v2, revision 4, "Restock"). Kept in
 * {@code meteor-client/qwerte/kit-restock.json} and written atomically (owner only where the file system has permissions), so a restart does not reset
 * the cap. Stamps older than an hour are dropped. Synchronised; the file holds numbers only. A port of the plugin's {@code KitRestockStamps}.
 */
public final class KitRestockStamps {
    public static final long HOUR_MS = 60 * 60 * 1000L;
    private static final int MAX = 600;
    private static KitRestockStamps shared;

    public static synchronized KitRestockStamps shared() {
        if (shared == null) shared = new KitRestockStamps(MeteorClient.FOLDER.toPath().resolve("qwerte").resolve("kit-restock.json"));
        return shared;
    }

    private final Path file;
    private final ArrayDeque<Long> stamps = new ArrayDeque<>();

    public KitRestockStamps(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 64 * 1024) return;
            JsonElement e = com.porter.qwerte.craft.SafeJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!e.isJsonArray()) return;
            List<Long> read = new ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) {
                if (read.size() >= MAX) break;
                if (x.isJsonPrimitive() && x.getAsJsonPrimitive().isNumber()) read.add(x.getAsLong());
            }
            // Oldest first; a stamp more than an hour ahead of the clock is a damaged record and is dropped, one a little ahead counts as now
            long now = System.currentTimeMillis();
            Collections.sort(read);
            stamps.clear();
            for (long s : read) if (s <= now + HOUR_MS) stamps.add(Math.min(s, now));
        } catch (IOException | RuntimeException ex) {
            // A broken file is an empty record: the cap starts over
            stamps.clear();
        }
    }

    /** How many jobs started in the hour before {@code now}. */
    public synchronized int lastHour(long now) {
        prune(now);
        return stamps.size();
    }

    /** Records a job start and saves. */
    public synchronized void add(long now) {
        prune(now);
        stamps.addLast(now);
        while (stamps.size() > MAX) stamps.pollFirst();
        save();
    }

    private void prune(long now) {
        while (!stamps.isEmpty() && now - stamps.peekFirst() > HOUR_MS) stamps.pollFirst();
    }

    private void save() {
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            JsonArray a = new JsonArray();
            for (long s : stamps) a.add(s);
            Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
            try {
                if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                    Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                }
                Files.writeString(tmp, a.toString(), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException ex) {
            LogUtils.getLogger().warn("[kit-restock] could not save the restock record: {}", ex.toString());
        }
    }
}

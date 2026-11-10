package com.porter.qwerte.stash;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The craft settings the bot has cached, per (server, stash): the crafting table the website set. Kept in
 * {@code meteor-client/qwerte/craft-settings.json} and written atomically (a temp file moved over it). Every entry read back is
 * checked like one that comes from the server; one that fails is dropped. {@link #version()} goes up on every change. Thread-safe.
 * Modelled on {@link KitStore}. The file holds a position: it is never printed to the log.
 */
public final class CraftStore {
    /** The most stashes kept (the oldest change goes first). */
    public static final int MAX_STASHES = 64;
    private static final int FILE_VERSION = 1;
    private static final long MAX_FILE_BYTES = 1024L * 1024;
    private static final Gson GSON = new Gson();
    private static final Logger LOG = LogUtils.getLogger();

    private record Key(String server, String stash) {
    }

    private static volatile CraftStore shared;

    /** The store of the running addon. */
    public static CraftStore shared() {
        CraftStore s = shared;
        if (s == null) {
            synchronized (CraftStore.class) {
                s = shared;
                if (s == null) shared = s = new CraftStore(MeteorClient.FOLDER.toPath().resolve("qwerte").resolve("craft-settings.json"));
            }
        }
        return s;
    }

    private final Path file;
    private final Map<Key, CraftSettings> entries = new LinkedHashMap<>();
    private volatile int version;

    public CraftStore(Path file) {
        this.file = file;
        load();
    }

    public synchronized int size() {
        return entries.size();
    }

    /** Goes up whenever the settings of any stash change. */
    public int version() {
        return version;
    }

    /** What is cached for this stash, or null when nothing is. */
    public synchronized CraftSettings get(String server, String stash) {
        return entries.get(new Key(server, stash));
    }

    /** Replaces what is cached for this stash and saves. @return true when the settings changed */
    public synchronized boolean put(String server, String stash, CraftSettings settings) {
        Key key = new Key(server, stash);
        CraftSettings old = entries.get(key);
        if (settings.equals(old)) return false;
        entries.remove(key);
        entries.put(key, settings);
        while (entries.size() > MAX_STASHES) entries.remove(entries.keySet().iterator().next());
        version++;
        save();
        return true;
    }

    private static void warn(String text, Object arg) {
        LOG.warn(text, arg);
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("file too big");
            JsonElement root = com.google.gson.JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("stashes") || !root.getAsJsonObject().get("stashes").isJsonArray()) throw new IOException("not a craft settings file");
            int dropped = 0;
            for (JsonElement e : root.getAsJsonObject().getAsJsonArray("stashes")) {
                try {
                    if (!e.isJsonObject()) throw new CraftSettings.Invalid("not an object");
                    JsonObject o = e.getAsJsonObject();
                    String server = text(o, "server"), stash = text(o, "stash");
                    if (entries.size() >= MAX_STASHES) throw new CraftSettings.Invalid("too many stashes");
                    entries.put(new Key(server, stash), CraftSettings.parse(o));
                } catch (CraftSettings.Invalid | RuntimeException ex) {
                    dropped++;
                }
            }
            if (dropped > 0) warn("[craft] dropped {} unreadable entries of the craft settings cache", dropped);
        } catch (IOException | RuntimeException ex) {
            warn("[craft] couldn't read the craft settings cache ({}), starting with none", ex.getClass().getSimpleName());
            entries.clear();
        }
    }

    private static String text(JsonObject o, String name) throws CraftSettings.Invalid {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().length() > 256) throw new CraftSettings.Invalid(name);
        return e.getAsString();
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", FILE_VERSION);
        JsonArray array = new JsonArray();
        for (Map.Entry<Key, CraftSettings> e : entries.entrySet()) {
            JsonObject o = e.getValue().toJson();
            o.addProperty("server", e.getKey().server());
            o.addProperty("stash", e.getKey().stash());
            array.add(o);
        }
        root.add("stashes", array);
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            // A fresh temp file beside it (owner only where the file system has permissions) moved over it, so a crash or a stop in the middle
            // never leaves half a file; the file holds a position
            Path temp = Files.createTempFile(dir != null ? dir : Path.of("."), file.getFileName().toString(), ".tmp");
            try {
                if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                    Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                }
                Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            LOG.error("[craft] couldn't save the craft settings cache", e);
        }
    }
}

package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Which player owns each (server, stash) pair (PROTOCOL.md, "Additions: multiple bots, stash ownership"), so two bots
 * that share the API key can't overwrite each other's stash. Persisted to data/owners.json. The last rejected player
 * per pair is kept in memory for the website's conflict warning.
 */
final class Owners {
    static final long TAKEOVER_MS = 7L * 24 * 60 * 60 * 1000;
    /** How stale the saved "last seen" of an owner may get before it is written again. */
    private static final long SEEN_WRITE_MS = 60L * 60 * 1000;

    record Conflict(String player, long at) {
    }

    private static final class Entry {
        String owner;
        long seen;
        long writtenSeen;

        Entry(String owner, long seen) {
            this.owner = owner;
            this.seen = seen;
        }
    }

    private final Path file;
    private final Map<IndexStore.StashKey, Entry> owners = new HashMap<>();
    private final Map<IndexStore.StashKey, Conflict> conflicts = new HashMap<>();

    Owners(Path dataDir) {
        this.file = dataDir.resolve("owners.json");
    }

    synchronized void load() throws IOException {
        if (!Files.exists(file)) return;
        try {
            JsonArray list = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement e : list) {
                JsonObject o = e.getAsJsonObject();
                Entry entry = new Entry(o.get("owner").getAsString(), o.has("seen") ? o.get("seen").getAsLong() : 0);
                entry.writtenSeen = entry.seen;
                owners.put(new IndexStore.StashKey(o.get("server").getAsString(), o.get("stash").getAsString()), entry);
            }
        } catch (RuntimeException e) {
            System.err.println("Skipping " + file.getFileName() + ": " + e);
        }
    }

    /** Stashes from before ownership existed: their stored player becomes the owner. */
    synchronized void adoptAll(Map<IndexStore.StashKey, IndexStore.PlayerSeen> players) {
        boolean changed = false;
        for (Map.Entry<IndexStore.StashKey, IndexStore.PlayerSeen> p : players.entrySet()) {
            String player = p.getValue().player();
            if (player == null || player.isEmpty() || owners.containsKey(p.getKey())) continue;
            owners.put(p.getKey(), new Entry(player, p.getValue().seen()));
            System.out.println("  Stash '" + p.getKey().stash() + "' @ " + p.getKey().server() + " belongs to " + player + ".");
            changed = true;
        }
        if (changed) save();
    }

    /**
     * Checks a bot request. Returns null when the player may use the pair, otherwise the owner's name (and records the
     * conflict). With {@code take} (index and heartbeat), a pair without an owner, or whose owner hasn't been seen for
     * 7 days, becomes this player's. A request without a player can't be checked and is let through.
     */
    synchronized String check(String server, String stash, String player, boolean take) {
        if (player == null || player.isEmpty()) return null;
        IndexStore.StashKey key = new IndexStore.StashKey(server, stash);
        long now = System.currentTimeMillis();
        Entry e = owners.get(key);
        if (e == null) {
            if (take) {
                owners.put(key, new Entry(player, now));
                save();
            }
            return null;
        }
        if (e.owner.equalsIgnoreCase(player)) {
            e.seen = now;
            if (now - e.writtenSeen >= SEEN_WRITE_MS) save();
            return null;
        }
        if (take && now - e.seen >= TAKEOVER_MS) {
            System.out.println(Instant.now() + " Stash '" + stash + "' @ " + server + ": " + player + " took it over from "
                + e.owner + " (not seen since " + Instant.ofEpochMilli(e.seen) + ").");
            e.owner = player;
            e.seen = now;
            conflicts.remove(key);
            save();
            return null;
        }
        conflicts.put(key, new Conflict(player, now));
        return e.owner;
    }

    /** Clears the owner (the next bot to use the pair takes it). Returns whether there was one. */
    synchronized boolean release(String server, String stash) {
        IndexStore.StashKey key = new IndexStore.StashKey(server, stash);
        conflicts.remove(key);
        if (owners.remove(key) == null) return false;
        System.out.println(Instant.now() + " Stash '" + stash + "' @ " + server + " released from the website.");
        save();
        return true;
    }

    synchronized String owner(String server, String stash) {
        Entry e = owners.get(new IndexStore.StashKey(server, stash));
        return e == null ? null : e.owner;
    }

    synchronized Conflict conflict(String server, String stash) {
        return conflicts.get(new IndexStore.StashKey(server, stash));
    }

    private void save() {
        JsonArray list = new JsonArray();
        for (Map.Entry<IndexStore.StashKey, Entry> e : owners.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("server", e.getKey().server());
            o.addProperty("stash", e.getKey().stash());
            o.addProperty("owner", e.getValue().owner);
            o.addProperty("seen", e.getValue().seen);
            list.add(o);
        }
        try {
            AtomicFiles.write(file, Json.GSON.toJson(list).getBytes(StandardCharsets.UTF_8));
            for (Entry e : owners.values()) e.writtenSeen = e.seen;
        } catch (IOException ex) {
            System.err.println("Could not save " + file.getFileName() + ": " + ex.getMessage());
        }
    }
}

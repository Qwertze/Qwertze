package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** craft.json: hash, revisions, limits, the backup, and damaged files and entries (modelled on KitStoreTest). */
class CraftStoreTest {
    @TempDir
    Path dir;

    static final CraftStore.Table T = new CraftStore.Table(118, 64, -30, "minecraft:overworld");

    CraftStore store() {
        CraftStore s = new CraftStore(dir);
        s.load();
        return s;
    }

    @Test
    void hashFollowsTheContract() {
        assertEquals(RecipeNormalizerTest.sha1("craft-v1\n\n"), CraftStore.hash(null));
        assertEquals(RecipeNormalizerTest.sha1("craft-v1\n118,64,-30,minecraft:overworld\n"), CraftStore.hash(T));
        assertEquals(CraftStore.hash(null), store().get("s", "a").hash());
        assertEquals(0, store().get("s", "a").rev());
        assertNull(store().get("s", "a").table());
    }

    @Test
    void tableChangesBumpTheRevisionAndNoChangeWritesNothing() throws Exception {
        CraftStore s = store();
        CraftStore.Settings a = s.setTable("s", "a", T);
        assertEquals(1, a.rev());
        assertEquals(CraftStore.hash(T), a.hash());
        long stamp = Files.getLastModifiedTime(dir.resolve("craft.json")).toMillis();
        Thread.sleep(20);
        assertEquals(1, s.setTable("s", "a", T).rev(), "the same table changes nothing");
        assertEquals(stamp, Files.getLastModifiedTime(dir.resolve("craft.json")).toMillis());
        assertEquals(2, s.setTable("s", "a", null).rev());
        assertEquals(CraftStore.hash(null), s.get("s", "a").hash());
        // The never-use list is not part of the hash, but it is a change.
        CraftStore.Settings n = s.setNeverUse("s", "a", List.of("minecraft:stone", "minecraft:dirt", "minecraft:stone"));
        assertEquals(3, n.rev());
        assertEquals(CraftStore.hash(null), n.hash());
        assertEquals(List.of("minecraft:dirt", "minecraft:stone"), new ArrayList<>(n.neverUse()));
        // The bot never gets the never-use list.
        JsonObject bot = n.toBotJson();
        assertEquals(java.util.Set.of("rev", "hash", "table"), bot.keySet());
        assertTrue(bot.get("table").isJsonNull());
        // It all survives a restart.
        CraftStore again = store();
        assertEquals(3, again.get("s", "a").rev());
        assertEquals(2, again.get("s", "a").neverUse().size());
        assertEquals(T, store().setTable("s", "a", T).table());
        assertEquals(T, store().get("s", "a").table());
    }

    @Test
    void limits() throws Exception {
        CraftStore s = store();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < CraftStore.MAX_NEVER_USE; i++) ids.add("minecraft:item_" + i);
        assertEquals(200, s.setNeverUse("s", "a", ids).neverUse().size());
        ids.add("minecraft:one_too_many");
        assertThrows(CraftStore.LimitException.class, () -> s.setNeverUse("s", "a", ids));
        assertThrows(CraftStore.LimitException.class, () -> s.setNeverUse("s", "a", List.of("Minecraft:Stone")));
        assertThrows(CraftStore.LimitException.class, () -> s.setNeverUse("s", "a", List.of("stone")));
        assertThrows(CraftStore.LimitException.class, () -> s.setNeverUse("s", "a", List.of("minecraft:stone#abc")));
        for (int i = 1; i < CraftStore.MAX_STASHES; i++) s.setTable("s", "stash" + i, T);
        assertThrows(CraftStore.LimitException.class, () -> s.setTable("s", "stash99", T), "a 65th stash");
        assertEquals(T, s.setTable("s", "a", T).table(), "an existing stash still changes");
    }

    @Test
    void tableRanges() {
        assertTrue(CraftStore.Table.valid(30_000_000, 2048, -30_000_000, "minecraft:overworld"));
        assertFalse(CraftStore.Table.valid(30_000_001, 0, 0, "minecraft:overworld"));
        assertFalse(CraftStore.Table.valid(0, 0, -30_000_001, "minecraft:overworld"));
        assertFalse(CraftStore.Table.valid(0, 2049, 0, "minecraft:overworld"));
        assertFalse(CraftStore.Table.valid(0, -2049, 0, "minecraft:overworld"));
        assertTrue(CraftStore.Table.valid(0, -2048, 0, "my_mod:the-end/x.y"));
        assertFalse(CraftStore.Table.valid(0, 0, 0, "overworld"));
        assertFalse(CraftStore.Table.valid(0, 0, 0, "Minecraft:overworld"));
        assertFalse(CraftStore.Table.valid(0, 0, 0, null));
        assertFalse(CraftStore.Table.valid(0, 0, 0, "minecraft:" + "x".repeat(101)));
    }

    @Test
    void theBackupIsTheStateBeforeTheLastWrite() throws Exception {
        CraftStore s = store();
        s.setTable("s", "a", T);
        assertFalse(Files.exists(dir.resolve("craft.json.bak")), "the first write has nothing to back up");
        s.setNeverUse("s", "a", List.of("minecraft:stone"));
        JsonObject bak = JsonParser.parseString(Files.readString(dir.resolve("craft.json.bak"))).getAsJsonObject();
        assertEquals(1, bak.getAsJsonArray("stashes").get(0).getAsJsonObject().get("rev").getAsInt());
        // A missing file comes back from the backup.
        Files.delete(dir.resolve("craft.json"));
        assertEquals(1, store().get("s", "a").rev());
    }

    @Test
    void anUnreadableFileFallsBackToTheBackupAndBothBrokenDisablesWithoutWriting() throws Exception {
        CraftStore s = store();
        s.setTable("s", "a", T);
        s.setNeverUse("s", "a", List.of("minecraft:stone"));
        Files.writeString(dir.resolve("craft.json"), "{ not json");
        CraftStore fromBak = store();
        assertFalse(fromBak.disabled());
        assertEquals(1, fromBak.get("s", "a").rev());
        // The restored backup is not replaced by the unreadable file on the next write.
        fromBak.setNeverUse("s", "a", List.of("minecraft:dirt"));
        assertEquals("[\"minecraft:dirt\"]", store().get("s", "a").neverUse().stream().map(x -> "\"" + x + "\"").toList().toString());
        // Both broken: disabled, never overwritten.
        Files.writeString(dir.resolve("craft.json"), "{ not json");
        Files.writeString(dir.resolve("craft.json.bak"), "[]");
        CraftStore dead = store();
        assertTrue(dead.disabled());
        assertThrows(CraftStore.DisabledException.class, () -> dead.get("s", "a"));
        assertThrows(CraftStore.DisabledException.class, () -> dead.setTable("s", "a", T));
        assertEquals("{ not json", Files.readString(dir.resolve("craft.json")));
        assertEquals("[]", Files.readString(dir.resolve("craft.json.bak")));
    }

    @Test
    void aDamagedEntryIsKeptAsItIsAndOnlyThatStashIsOff() throws Exception {
        CraftStore s = store();
        s.setTable("s", "good", T);
        JsonObject root = JsonParser.parseString(Files.readString(dir.resolve("craft.json"))).getAsJsonObject();
        JsonObject bad = new JsonObject();
        bad.addProperty("server", "s");
        bad.addProperty("stash", "bad");
        bad.addProperty("rev", 3);
        JsonObject t = new JsonObject();
        t.addProperty("x", 99_999_999); // out of range
        t.addProperty("y", 0);
        t.addProperty("z", 0);
        t.addProperty("dimension", "minecraft:overworld");
        bad.add("table", t);
        bad.add("neverUse", new JsonArray());
        root.getAsJsonArray("stashes").add(bad);
        Files.writeString(dir.resolve("craft.json"), root.toString(), StandardCharsets.UTF_8);
        CraftStore again = store();
        assertFalse(again.disabled());
        assertEquals(T, again.get("s", "good").table());
        assertThrows(CraftStore.DisabledException.class, () -> again.get("s", "bad"));
        assertThrows(CraftStore.DisabledException.class, () -> again.setTable("s", "bad", T));
        assertTrue(again.knows("s", "bad"));
        again.setNeverUse("s", "good", List.of("minecraft:stone")); // writes, keeping the damaged entry
        JsonArray list = JsonParser.parseString(Files.readString(dir.resolve("craft.json"))).getAsJsonObject().getAsJsonArray("stashes");
        assertEquals(2, list.size());
        assertEquals(bad, list.get(1).getAsJsonObject());
    }

    @Test
    void duplicateStashesOrAnUnknownVersionMakeTheWholeFileSuspect() throws Exception {
        Files.writeString(dir.resolve("craft.json"), "{\"version\":2,\"stashes\":[]}");
        assertTrue(store().disabled());
        Files.writeString(dir.resolve("craft.json"), "{\"version\":1,\"stashes\":[{\"server\":\"s\",\"stash\":\"a\",\"rev\":1,\"table\":null,\"neverUse\":[]},"
            + "{\"server\":\"s\",\"stash\":\"a\",\"rev\":2,\"table\":null,\"neverUse\":[]}]}");
        assertTrue(store().disabled());
    }

    @Test
    void forgetDropsOnlyThatStashAndFreesTheSlot() throws Exception {
        CraftStore s = store();
        for (int i = 0; i < CraftStore.MAX_STASHES; i++) s.setTable("s", "stash" + i, T);
        assertThrows(CraftStore.LimitException.class, () -> s.setTable("s", "extra", T));
        s.forget("s", "stash3");
        s.forget("s", "never-had-one"); // no-op
        assertNull(store().get("s", "stash3").table());
        assertEquals(0, store().get("s", "stash3").rev());
        assertEquals(T, store().get("s", "stash4").table());
        assertEquals(T, s.setTable("s", "extra", T).table(), "the freed slot is usable");
        assertTrue(Files.exists(dir.resolve("craft.json.bak")));
    }

}

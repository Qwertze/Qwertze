package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The heartbeat's naming and crafting fields, and the creative-tab fallback for items without a tab. */
class NamingAndTabsTest {
    @TempDir
    Path data;

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static IndexStore.BotStatus status(String s) {
        return AddonRoutes.botStatus(json(s));
    }

    private static JsonObject entry(IndexStore store) {
        return store.listJson().get(0).getAsJsonObject();
    }

    @Test
    void namingTypes() {
        IndexStore.BotStatus none = status("{}");
        assertFalse(none.hasNaming());
        assertNull(none.naming());

        IndexStore.BotStatus cleared = status("{\"naming\":null}");
        assertTrue(cleared.hasNaming());
        assertNull(cleared.naming());

        assertEquals(new IndexStore.Naming(true, 30), status("{\"naming\":{\"anvil\":true,\"xpLevels\":30}}").naming());
        assertEquals(new IndexStore.Naming(false, -1), status("{\"naming\":{\"anvil\":false,\"xpLevels\":-1}}").naming());
        assertEquals(new IndexStore.Naming(true, 7), status("{\"naming\":{\"anvil\":true,\"xpLevels\":7.0}}").naming());
        assertEquals(new IndexStore.Naming(true, -1), status("{\"naming\":{\"anvil\":true}}").naming());

        for (String bad : new String[]{
            "{\"naming\":true}", "{\"naming\":[]}", "{\"naming\":\"x\"}", "{\"naming\":{}}",
            "{\"naming\":{\"anvil\":\"yes\",\"xpLevels\":1}}", "{\"naming\":{\"anvil\":1,\"xpLevels\":1}}",
            "{\"naming\":{\"anvil\":true,\"xpLevels\":\"5\"}}", "{\"naming\":{\"anvil\":true,\"xpLevels\":1.5}}",
            "{\"naming\":{\"anvil\":true,\"xpLevels\":-2}}", "{\"naming\":{\"anvil\":true,\"xpLevels\":3000000000}}"}) {
            assertThrows(IllegalArgumentException.class, () -> status(bad), bad);
        }
    }

    @Test
    void namingIsKeptAndCleared() throws Exception {
        IndexStore store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        store.heartbeat("s", "a", "Bob", null, status("{}"));
        assertTrue(entry(store).get("naming").isJsonNull());

        store.heartbeat("s", "a", "Bob", null, status("{\"naming\":{\"anvil\":true,\"xpLevels\":12}}"));
        assertEquals(json("{\"anvil\":true,\"xpLevels\":12,\"anvilSource\":null}"), entry(store).getAsJsonObject("naming"));

        // Absent keeps it, and other fields don't touch it.
        store.heartbeat("s", "a", "Bob", null, status("{\"orderBlocker\":\"busy\"}"));
        assertEquals(json("{\"anvil\":true,\"xpLevels\":12,\"anvilSource\":null}"), entry(store).getAsJsonObject("naming"));

        store.heartbeat("s", "a", "Bob", null, status("{\"naming\":{\"anvil\":false,\"xpLevels\":0}}"));
        assertEquals(json("{\"anvil\":false,\"xpLevels\":0,\"anvilSource\":null}"), entry(store).getAsJsonObject("naming"));

        store.heartbeat("s", "a", "Bob", null, status("{\"naming\":null}"));
        assertTrue(entry(store).get("naming").isJsonNull());
    }

    @Test
    void anvilSourceTypes() {
        for (String src : new String[]{"set", "found", "placed", "none"}) {
            assertEquals(new IndexStore.Naming(false, 5, src),
                status("{\"naming\":{\"anvil\":false,\"xpLevels\":5,\"anvilSource\":\"" + src + "\"}}").naming(), src);
        }
        // Absent or null (an older bot): null.
        assertNull(status("{\"naming\":{\"anvil\":true,\"xpLevels\":5}}").naming().anvilSource());
        assertNull(status("{\"naming\":{\"anvil\":true,\"xpLevels\":5,\"anvilSource\":null}}").naming().anvilSource());

        for (String bad : new String[]{"\"SET\"", "\"\"", "\"anvil\"", "1", "true", "[]", "{}", "[\"set\"]"}) {
            String body = "{\"naming\":{\"anvil\":false,\"xpLevels\":5,\"anvilSource\":" + bad + "}}";
            assertThrows(IllegalArgumentException.class, () -> status(body), body);
        }
    }

    @Test
    void anvilSourceIsListed() throws Exception {
        IndexStore store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        store.heartbeat("s", "a", "Bob", null, status("{\"naming\":{\"anvil\":false,\"xpLevels\":4,\"anvilSource\":\"none\"}}"));
        assertEquals(json("{\"anvil\":false,\"xpLevels\":4,\"anvilSource\":\"none\"}"), entry(store).getAsJsonObject("naming"));

        store.heartbeat("s", "a", "Bob", null, status("{\"naming\":{\"anvil\":true,\"xpLevels\":4,\"anvilSource\":\"placed\"}}"));
        assertEquals("placed", entry(store).getAsJsonObject("naming").get("anvilSource").getAsString());

        // An older bot's heartbeat replaces it with null.
        store.heartbeat("s", "a", "Bob", null, status("{\"naming\":{\"anvil\":true,\"xpLevels\":4}}"));
        assertTrue(entry(store).getAsJsonObject("naming").get("anvilSource").isJsonNull());
    }

    @Test
    void craftingTypes() {
        IndexStore.BotStatus none = status("{}");
        assertFalse(none.hasCrafting());
        assertNull(none.crafting());

        IndexStore.BotStatus cleared = status("{\"crafting\":null}");
        assertTrue(cleared.hasCrafting());
        assertNull(cleared.crafting());

        assertEquals(new IndexStore.Crafting(true, true), status("{\"crafting\":{\"enabled\":true,\"table\":true}}").crafting());
        assertEquals(new IndexStore.Crafting(true, false), status("{\"crafting\":{\"enabled\":true,\"table\":false}}").crafting());
        assertEquals(new IndexStore.Crafting(false, true), status("{\"crafting\":{\"enabled\":false,\"table\":true}}").crafting());

        for (String bad : new String[]{
            "{\"crafting\":true}", "{\"crafting\":[]}", "{\"crafting\":\"x\"}", "{\"crafting\":{}}",
            "{\"crafting\":{\"enabled\":true}}", "{\"crafting\":{\"table\":true}}",
            "{\"crafting\":{\"enabled\":\"yes\",\"table\":true}}", "{\"crafting\":{\"enabled\":true,\"table\":1}}",
            "{\"crafting\":{\"enabled\":null,\"table\":true}}", "{\"crafting\":{\"enabled\":true,\"table\":null}}"}) {
            assertThrows(IllegalArgumentException.class, () -> status(bad), bad);
        }
    }

    @Test
    void craftingIsKeptAndCleared() throws Exception {
        IndexStore store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        store.heartbeat("s", "a", "Bob", null, status("{}"));
        assertTrue(entry(store).get("crafting").isJsonNull());

        store.heartbeat("s", "a", "Bob", null, status("{\"crafting\":{\"enabled\":true,\"table\":false}}"));
        assertEquals(json("{\"enabled\":true,\"table\":false}"), entry(store).getAsJsonObject("crafting"));

        // Absent keeps it, and other fields (naming included) don't touch it.
        store.heartbeat("s", "a", "Bob", null, status("{\"naming\":{\"anvil\":true,\"xpLevels\":3}}"));
        assertEquals(json("{\"enabled\":true,\"table\":false}"), entry(store).getAsJsonObject("crafting"));
        assertEquals(json("{\"anvil\":true,\"xpLevels\":3,\"anvilSource\":null}"), entry(store).getAsJsonObject("naming"));

        store.heartbeat("s", "a", "Bob", null, status("{\"crafting\":{\"enabled\":true,\"table\":true}}"));
        assertEquals(json("{\"enabled\":true,\"table\":true}"), entry(store).getAsJsonObject("crafting"));
        assertEquals(json("{\"anvil\":true,\"xpLevels\":3,\"anvilSource\":null}"), entry(store).getAsJsonObject("naming"));

        store.heartbeat("s", "a", "Bob", null, status("{\"crafting\":null}"));
        assertTrue(entry(store).get("crafting").isJsonNull());
        assertEquals(json("{\"anvil\":true,\"xpLevels\":3,\"anvilSource\":null}"), entry(store).getAsJsonObject("naming"));
    }

    @Test
    void craftingTableSource() throws Exception {
        for (String src : new String[]{"set", "found", "placed", "crafted", "none"}) {
            assertEquals(new IndexStore.Crafting(true, false, src),
                status("{\"crafting\":{\"enabled\":true,\"table\":false,\"tableSource\":\"" + src + "\"}}").crafting(), src);
        }
        // Absent or null (an older bot): null.
        assertNull(status("{\"crafting\":{\"enabled\":true,\"table\":true}}").crafting().tableSource());
        assertNull(status("{\"crafting\":{\"enabled\":true,\"table\":true,\"tableSource\":null}}").crafting().tableSource());
        for (String bad : new String[]{"\"SET\"", "\"\"", "\"table\"", "1", "true", "[]", "{}", "[\"set\"]"}) {
            String body = "{\"crafting\":{\"enabled\":true,\"table\":false,\"tableSource\":" + bad + "}}";
            assertThrows(IllegalArgumentException.class, () -> status(body), body);
        }

        // Listed when sent; an older bot's heartbeat goes back to {enabled, table}.
        IndexStore store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        store.heartbeat("s", "a", "Bob", null, status("{\"crafting\":{\"enabled\":true,\"table\":true,\"tableSource\":\"crafted\"}}"));
        assertEquals(json("{\"enabled\":true,\"table\":true,\"tableSource\":\"crafted\"}"), entry(store).getAsJsonObject("crafting"));
        store.heartbeat("s", "a", "Bob", null, status("{\"crafting\":{\"enabled\":true,\"table\":false}}"));
        assertEquals(json("{\"enabled\":true,\"table\":false}"), entry(store).getAsJsonObject("crafting"));
    }

    // ---------- creative tabs ----------

    private static CreativeTabs fixture() throws Exception {
        return CreativeTabs.load(fixtureDir());
    }

    private static Path fixtureDir() throws URISyntaxException {
        return Path.of(NamingAndTabsTest.class.getClassLoader().getResource("creative-tabs-fixture").toURI());
    }

    private static int stoneOrder(CreativeTabs tabs, String mc) {
        return tabs.pick(mc).items().get("minecraft:stone").order();
    }

    @Test
    void versionChoice() throws Exception {
        CreativeTabs tabs = fixture(); // 1.20.6 (206), 1.21.4 (12), 1.21.11 (112)
        assertEquals(3, tabs.size());
        assertEquals(12, stoneOrder(tabs, "1.21.4"));
        assertEquals(112, stoneOrder(tabs, "1.21.11"));
        assertEquals(206, stoneOrder(tabs, "1.20.6"));
        // Nearest lower with the same major.minor.
        assertEquals(12, stoneOrder(tabs, "1.21.8"));
        assertEquals(112, stoneOrder(tabs, "1.21.15"));
        assertEquals(206, stoneOrder(tabs, "1.20.9"));
        // Nothing lower in that major.minor, another minor, or no version: the newest table.
        assertEquals(112, stoneOrder(tabs, "1.21.2"));
        assertEquals(112, stoneOrder(tabs, "1.21"));
        assertEquals(112, stoneOrder(tabs, "1.20.1"));
        assertEquals(112, stoneOrder(tabs, "1.22.1"));
        assertEquals(112, stoneOrder(tabs, null));
        assertEquals(112, stoneOrder(tabs, "garbage"));
    }

    private static JsonObject index(String mc) {
        return json("{\"version\":1,\"server\":\"s\",\"stash\":\"a\"" + (mc == null ? "" : ",\"mcVersion\":\"" + mc + "\"") + ",\"items\":["
            + "{\"key\":\"minecraft:stone\",\"id\":\"minecraft:stone\",\"count\":1,\"tab\":\"other\",\"tabOrder\":1000000},"
            + "{\"key\":\"minecraft:dirt\",\"id\":\"minecraft:dirt\",\"count\":1},"
            + "{\"key\":\"minecraft:dirt#named\",\"id\":\"minecraft:dirt\",\"count\":1,\"tab\":null},"
            + "{\"key\":\"minecraft:diamond_sword\",\"id\":\"minecraft:diamond_sword\",\"count\":1,\"tab\":\"tools_and_utilities\",\"tabOrder\":9},"
            + "{\"key\":\"minecraft:mystery\",\"id\":\"minecraft:mystery\",\"count\":1,\"tab\":\"other\",\"tabOrder\":1000000}]}");
    }

    private static JsonObject item(JsonArray items, String key) {
        for (var e : items) if (e.getAsJsonObject().get("key").getAsString().equals(key)) return e.getAsJsonObject();
        throw new AssertionError(key);
    }

    @Test
    void fillsMissingAndOtherTabs() throws Exception {
        CreativeTabs tabs = fixture();
        JsonObject idx = index("1.21.4");
        assertEquals(3, tabs.fill(idx));
        JsonArray items = idx.getAsJsonArray("items");
        assertEquals("building_blocks", item(items, "minecraft:stone").get("tab").getAsString());
        assertEquals(12, item(items, "minecraft:stone").get("tabOrder").getAsInt());
        assertEquals("natural_blocks", item(items, "minecraft:dirt").get("tab").getAsString());
        assertEquals(3, item(items, "minecraft:dirt").get("tabOrder").getAsInt());
        assertEquals("natural_blocks", item(items, "minecraft:dirt#named").get("tab").getAsString()); // by base id
        // A real tab from the bot wins.
        assertEquals("tools_and_utilities", item(items, "minecraft:diamond_sword").get("tab").getAsString());
        assertEquals(9, item(items, "minecraft:diamond_sword").get("tabOrder").getAsInt());
        // Not in the table: stays other.
        assertEquals("other", item(items, "minecraft:mystery").get("tab").getAsString());
        assertEquals(1000000, item(items, "minecraft:mystery").get("tabOrder").getAsInt());

        JsonObject newest = index(null);
        tabs.fill(newest);
        assertEquals(112, item(newest.getAsJsonArray("items"), "minecraft:stone").get("tabOrder").getAsInt());
    }

    @Test
    void noTableLeavesItemsAlone() throws Exception {
        JsonObject idx = index("1.21.4");
        JsonObject before = idx.deepCopy();
        assertEquals(0, CreativeTabs.NONE.fill(idx));
        assertEquals(0, CreativeTabs.load(data.resolve("no-such-folder")).fill(idx));
        assertEquals(before, idx);

        IndexStore store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        store.putIndex(index("1.21.4"));
        String served = new String(store.index("s", "a").bytes(), StandardCharsets.UTF_8);
        assertEquals("other", item(json(served).getAsJsonArray("items"), "minecraft:stone").get("tab").getAsString());
    }

    @Test
    void servedIndexIsFilledButDiskKeepsWhatTheBotSent() throws Exception {
        IndexStore store = new IndexStore(data, fixture());
        store.load();
        store.putIndex(index("1.21.11"));
        JsonObject served = json(new String(store.index("s", "a").bytes(), StandardCharsets.UTF_8));
        assertEquals(112, item(served.getAsJsonArray("items"), "minecraft:stone").get("tabOrder").getAsInt());
        assertEquals("tools_and_utilities", item(served.getAsJsonArray("items"), "minecraft:diamond_sword").get("tab").getAsString());

        Path file = data.resolve("indexes").resolve(IndexStore.fileName("s", "a"));
        JsonObject disk = json(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("other", item(disk.getAsJsonArray("items"), "minecraft:stone").get("tab").getAsString());

        // An index stored before (or by a bot that was never updated) is filled when it is loaded.
        IndexStore reloaded = new IndexStore(data, fixture());
        reloaded.load();
        JsonObject again = json(new String(reloaded.index("s", "a").bytes(), StandardCharsets.UTF_8));
        assertEquals("building_blocks", item(again.getAsJsonArray("items"), "minecraft:stone").get("tab").getAsString());
        // ...and an old server without tables serves it unchanged.
        IndexStore plain = new IndexStore(data, CreativeTabs.NONE);
        plain.load();
        JsonObject raw = json(new String(plain.index("s", "a").bytes(), StandardCharsets.UTF_8));
        assertEquals("other", item(raw.getAsJsonArray("items"), "minecraft:stone").get("tab").getAsString());
    }

    @Test
    void badTablesAreSkipped() {
        assertNull(CreativeTabs.parse("{\"version\":\"x.y\",\"items\":{}}"));
        assertNull(CreativeTabs.parse("{\"items\":{}}"));
        CreativeTabs.Table t = CreativeTabs.parse("{\"version\":\"1.21.4\",\"items\":{\"a\":[\"combat\",1],\"b\":\"combat\",\"c\":[1,2],\"d\":[\"other\",3]}}");
        assertEquals(1, t.items().size());
        assertTrue(t.items().containsKey("a"));
    }

    @Test
    void deliveryStorageFromTheHeartbeatIsStoredAndListedWithoutPositions() throws Exception {
        IndexStore store = new IndexStore(java.nio.file.Files.createTempDirectory("ds"), CreativeTabs.NONE);
        store.load();
        com.google.gson.JsonObject hb = com.google.gson.JsonParser.parseString(
            "{\"deliveryStorage\":{\"autoExpand\":true,\"chests\":3,\"full\":3,\"canExpand\":false,\"blocked\":\"stone is in the way above the top chest\"}}").getAsJsonObject();
        store.heartbeat("s", "a", "P", null, AddonRoutes.botStatus(hb));
        com.google.gson.JsonObject ds = store.listJson().get(0).getAsJsonObject().getAsJsonObject("deliveryStorage");
        org.junit.jupiter.api.Assertions.assertEquals(3, ds.get("chests").getAsInt());
        org.junit.jupiter.api.Assertions.assertEquals(3, ds.get("full").getAsInt());
        org.junit.jupiter.api.Assertions.assertFalse(ds.get("canExpand").getAsBoolean());
        org.junit.jupiter.api.Assertions.assertEquals("stone is in the way above the top chest", ds.get("blocked").getAsString());
        // Absent keeps it; null clears it; nonsense is refused.
        store.heartbeat("s", "a", "P", null, AddonRoutes.botStatus(new com.google.gson.JsonObject()));
        org.junit.jupiter.api.Assertions.assertTrue(store.listJson().get(0).getAsJsonObject().get("deliveryStorage").isJsonObject());
        store.heartbeat("s", "a", "P", null, AddonRoutes.botStatus(com.google.gson.JsonParser.parseString("{\"deliveryStorage\":null}").getAsJsonObject()));
        org.junit.jupiter.api.Assertions.assertTrue(store.listJson().get(0).getAsJsonObject().get("deliveryStorage").isJsonNull());
        for (String bad : new String[]{"{\"deliveryStorage\":5}", "{\"deliveryStorage\":{\"autoExpand\":true,\"chests\":1,\"full\":2,\"canExpand\":true}}",
            "{\"deliveryStorage\":{\"autoExpand\":\"yes\",\"chests\":1,\"full\":0,\"canExpand\":true}}"}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> AddonRoutes.botStatus(com.google.gson.JsonParser.parseString(bad).getAsJsonObject()), bad);
        }
    }
}

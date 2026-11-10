package com.porter.qwerte.manager.model;

import com.google.gson.JsonParser;
import com.porter.qwerte.manager.model.IndexModel.Entry;
import com.porter.qwerte.manager.model.IndexModel.Sort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexModelTest {
    /** The PROTOCOL sample, a few more items and two filled shulker groups. */
    private static final String INDEX = """
        {"version":1,"server":"2b2t.org","stash":"stash","mcVersion":"1.21.11","containers":{"known":120,"total":130,"stale":3},
         "receivedAt":1767225600000,
         "items":[
          {"key":"minecraft:diamond","id":"minecraft:diamond","name":"Diamond","englishName":"Diamond","count":1728,"maxStack":64,"details":[],"enchanted":false,"tab":"ingredients","tabOrder":10},
          {"key":"minecraft:diamond_sword#3f2a9c1d","id":"minecraft:diamond_sword","name":"Diamond Sword","englishName":"Diamond Sword","count":12,"maxStack":1,
           "details":["Sharpness V","Unbreaking III"],"enchanted":true,"tab":"combat","tabOrder":3},
          {"key":"minecraft:diamond_sword#aaaaaaaa","id":"minecraft:diamond_sword","name":"Diamond Sword","englishName":"Diamond Sword","count":1,"maxStack":1,
           "details":[],"enchanted":false,"tab":"combat","tabOrder":3,"damage":1000,"maxDamage":1561},
          {"key":"minecraft:diamond_sword","id":"minecraft:diamond_sword","name":"Diamond Sword","englishName":"Diamond Sword","count":2,"maxStack":1,
           "details":[],"enchanted":false,"tab":"combat","tabOrder":3},
          {"key":"minecraft:cobblestone","id":"minecraft:cobblestone","name":"Cobblestone","englishName":"Cobblestone","count":99999,"maxStack":64,"details":[],"enchanted":false,"tab":"building_blocks","tabOrder":5},
          {"key":"create:andesite_alloy","id":"create:andesite_alloy","name":"Andesite Alloy","englishName":"Andesite Alloy","count":40,"maxStack":64,"details":[],"enchanted":false,"tab":"nonsense"},
          {"key":"minecraft:red_shulker_box","id":"minecraft:red_shulker_box","name":"Red Shulker Box","englishName":"Red Shulker Box","count":8,"maxStack":1,"details":[],"enchanted":false,"tab":"colored_blocks","tabOrder":2},
          {"key":"minecraft:shulker_box","id":"minecraft:shulker_box","name":"Shulker Box","englishName":"Shulker Box","count":3,"maxStack":1,"details":[],"enchanted":false,"tab":"colored_blocks","tabOrder":1}
         ],
         "shulkers":[
          {"key":"s:3f2a9c1d0b","itemKey":"minecraft:red_shulker_box","id":"minecraft:red_shulker_box","name":"Red Shulker Box","count":4,
           "contents":[{"slot":0,"key":"minecraft:diamond","count":64},{"slot":5,"key":"minecraft:diamond_sword#3f2a9c1d","count":1},{"slot":99,"key":"minecraft:diamond","count":1}]},
          {"key":"s:0000000001","id":"minecraft:red_shulker_box","name":"Loot","count":2,"contents":[{"slot":1,"key":"minecraft:cobblestone","count":64}]}
         ]}
        """;

    private static IndexModel model() {
        return IndexModel.parse(JsonParser.parseString(INDEX));
    }

    private static List<String> keys(List<Entry> entries) {
        return entries.stream().map(Entry::key).collect(Collectors.toList());
    }

    @Test
    void parsesTheProtocolSample() {
        IndexModel m = model();
        assertEquals("1.21.11", m.mcVersion());
        assertEquals(8, m.items().size());
        assertEquals(2, m.shulkers().size());
        assertEquals(130, m.containersTotal());
        assertEquals(3, m.containersStale());
        IndexModel.Item sword = m.item("minecraft:diamond_sword#3f2a9c1d");
        assertNotNull(sword);
        assertTrue(sword.enchanted());
        assertEquals(List.of("Sharpness V", "Unbreaking III"), sword.details());
        assertEquals(1, sword.maxStack());
        IndexModel.Item damaged = m.item("minecraft:diamond_sword#aaaaaaaa");
        assertEquals(1000, damaged.damage());
        assertEquals(1561, damaged.maxDamage());
        assertEquals(561.0 / 1561.0, damaged.durabilityLeft(), 1e-9);
        assertEquals(-1, sword.durabilityLeft());
        // an unknown tab counts as "other", a missing tabOrder sorts last
        assertEquals("other", m.item("create:andesite_alloy").tab());
        assertEquals(1_000_000, m.item("create:andesite_alloy").tabOrder());
    }

    @Test
    void shulkerContentsKeepOnlyValidSlots() {
        IndexModel m = model();
        IndexModel.Shulker red = m.shulkers().get(0);
        assertEquals(2, red.contents().size());
        assertEquals(0, m.contentsOf(red).get(0).slot());
        assertEquals(5, m.contentsOf(red).get(1).slot());
    }

    @Test
    void badBodiesGiveAnEmptyIndex() {
        assertTrue(IndexModel.parse(JsonParser.parseString("[]")).items().isEmpty());
        assertTrue(IndexModel.parse(JsonParser.parseString("{\"items\":\"x\",\"shulkers\":5}")).items().isEmpty());
        assertTrue(IndexModel.parse(com.google.gson.JsonNull.INSTANCE).shulkers().isEmpty());
        IndexModel m = IndexModel.parse(JsonParser.parseString("{\"items\":[1,null,{},{\"key\":\"a\",\"count\":\"x\"},{\"key\":\"a\"}]}"));
        assertEquals(1, m.items().size());
        assertEquals(0, m.items().get(0).count());
    }

    @Test
    void searchMatchesNameEnglishNameIdAndDetails() {
        IndexModel m = model();
        List<String> diamond = keys(m.view("diamond", Sort.COUNT, false));
        assertTrue(diamond.contains("minecraft:diamond"));
        assertTrue(diamond.contains("minecraft:diamond_sword#3f2a9c1d"));
        assertFalse(diamond.contains("minecraft:cobblestone"));
        // two terms must both match
        assertEquals(List.of("minecraft:diamond_sword#3f2a9c1d"), keys(m.view("diamond sharpness", Sort.COUNT, false)));
        assertEquals(List.of("minecraft:diamond_sword#3f2a9c1d"), keys(m.view("UNBREAKING", Sort.COUNT, false)));
        assertTrue(m.view("zzz", Sort.COUNT, false).isEmpty());
        assertEquals(m.view("", Sort.COUNT, false).size(), m.view("   ", Sort.COUNT, false).size());
    }

    @Test
    void atSignFiltersByNamespacePrefix() {
        IndexModel m = model();
        assertEquals(List.of("create:andesite_alloy"), keys(m.view("@create", Sort.COUNT, false)));
        assertEquals(List.of("create:andesite_alloy"), keys(m.view("@cre andesite", Sort.COUNT, false)));
        assertTrue(m.view("@create diamond", Sort.COUNT, false).isEmpty());
        // a lone @ is ignored
        assertEquals(m.view("", Sort.COUNT, false).size(), m.view("@", Sort.COUNT, false).size());
    }

    @Test
    void sortsByCountNameAndId() {
        IndexModel m = model();
        List<String> byCount = keys(m.view("", Sort.COUNT, false));
        assertEquals("minecraft:cobblestone", byCount.get(0));
        assertEquals("minecraft:diamond", byCount.get(1));
        List<String> byName = keys(m.view("sword", Sort.NAME, false)).stream().filter(k -> k.startsWith("minecraft:diamond_sword")).collect(Collectors.toList());
        // same name: most items first
        assertEquals(List.of("minecraft:diamond_sword#3f2a9c1d", "minecraft:diamond_sword", "minecraft:diamond_sword#aaaaaaaa"), byName);
        List<String> byId = keys(m.view("", Sort.ID, false));
        assertEquals("create:andesite_alloy", byId.get(0));
    }

    @Test
    void creativeSortFollowsTabsThenTabOrderAndPutsWornCopiesFirst() {
        IndexModel m = model();
        List<String> creative = keys(m.view("", Sort.CREATIVE, false));
        assertEquals("minecraft:cobblestone", creative.get(0)); // building_blocks
        List<String> swords = creative.stream().filter(k -> k.startsWith("minecraft:diamond_sword")).collect(Collectors.toList());
        assertEquals("minecraft:diamond_sword#aaaaaaaa", swords.get(0)); // the damaged one first
        assertEquals("create:andesite_alloy", creative.get(creative.size() - 1)); // other, tabOrder 1000000
        // ingredients come after combat and food
        assertTrue(creative.indexOf("minecraft:diamond") > creative.indexOf("minecraft:diamond_sword"));
    }

    @Test
    void emptyShulkerAvailabilityIsTheTotalMinusTheFilledGroups() {
        IndexModel m = model();
        assertEquals(2, m.emptyShulkerAvailable("minecraft:red_shulker_box")); // 8 - 4 - 2 (one group by key, one by id)
        assertEquals(3, m.emptyShulkerAvailable("minecraft:shulker_box"));
        assertEquals(0, m.emptyShulkerAvailable("minecraft:diamond"));
        assertEquals(0, m.emptyShulkerAvailable("nope"));
    }

    @Test
    void groupsOfOlderIndexesAreMatchedById() {
        IndexModel m = IndexModel.parse(JsonParser.parseString("""
            {"items":[{"key":"minecraft:blue_shulker_box","id":"minecraft:blue_shulker_box","name":"Blue Shulker Box","count":5}],
             "shulkers":[{"key":"s:1","id":"minecraft:blue_shulker_box","name":"Blue Shulker Box","count":2,"contents":[]}]}"""));
        assertEquals(3, m.emptyShulkerAvailable("minecraft:blue_shulker_box"));
    }

    @Test
    void aShulkerBoxItemShowsItsEmptyBoxesThenTheMatchingFilledGroups() {
        IndexModel m = model();
        List<Entry> all = m.view("shulker", Sort.COUNT, false);
        List<Entry> red = all.stream().filter(e -> e.id().equals("minecraft:red_shulker_box")).collect(Collectors.toList());
        assertEquals(3, red.size());
        assertEquals("minecraft:red_shulker_box", red.get(0).key());
        assertEquals(2, red.get(0).count()); // the empty ones
        assertNull(red.get(0).shulker());
        assertEquals("s:3f2a9c1d0b", red.get(1).key()); // most first
        assertEquals(4, red.get(1).count());
        assertEquals("s:0000000001", red.get(2).key());
    }

    @Test
    void searchingForContentsFindsTheShulkerGroupsButNotTheEmptyBoxes() {
        IndexModel m = model();
        List<Entry> found = m.view("cobblestone", Sort.COUNT, false);
        assertEquals(List.of("minecraft:cobblestone", "s:0000000001"), keys(found));
        // the red box item stays out because it is not a match itself
        assertFalse(keys(found).contains("minecraft:red_shulker_box"));
    }

    @Test
    void shulkersOnlyListsGroups() {
        IndexModel m = model();
        List<Entry> groups = m.view("", Sort.CREATIVE, true);
        assertEquals(List.of("s:3f2a9c1d0b", "s:0000000001"), keys(groups));
        assertEquals(List.of("s:0000000001"), keys(m.view("loot", Sort.COUNT, true)));
        assertEquals(List.of("s:3f2a9c1d0b"), keys(m.view("diamond", Sort.COUNT, true)));
    }

    @Test
    void untrustedTextIsCleaned() {
        IndexModel m = IndexModel.parse(JsonParser.parseString("""
            {"items":[{"key":"k1","id":"minecraft:stick","name":"\\u00a7cRed\\u0007 Stick\\n2","englishName":"Stick","count":1,"details":["\\u00a74evil"]}]}"""));
        IndexModel.Item item = m.items().get(0);
        assertEquals("cRed Stick 2", item.name());
        assertEquals(List.of("4evil"), item.details());
    }

    @Test
    void visibleTabsFollowTheWebsite() {
        // Search first, the creative tabs that have items in the website's order, Shulkers (filled ones exist), Orders last
        assertEquals(List.of("all", "building_blocks", "colored_blocks", "combat", "ingredients", "other", "shulkers", "orders"), model().visibleTabs());
        IndexModel empty = IndexModel.empty();
        assertEquals(List.of("all", "orders"), empty.visibleTabs());
        IndexModel noShulkers = IndexModel.parse(JsonParser.parseString(
            "{\"items\":[{\"key\":\"minecraft:stone\",\"id\":\"minecraft:stone\",\"name\":\"Stone\",\"count\":5,\"tab\":\"natural_blocks\"}]}"));
        assertEquals(List.of("all", "natural_blocks", "orders"), noShulkers.visibleTabs());
    }

    @Test
    void theAreaAndKitsTabsSitBeforeOrders() {
        IndexModel empty = IndexModel.empty();
        assertEquals(List.of("all", "area", "orders"), empty.visibleTabs(false, true));
        assertEquals(List.of("all", "area", "kits", "orders"), empty.visibleTabs(true, true));
        assertEquals(List.of("all", "kits", "orders"), empty.visibleTabs(true), "the old overload has no area");
    }

    @Test
    void aCreativeTabShowsOnlyItsItems() {
        IndexModel m = model();
        assertEquals(List.of("minecraft:diamond"), keys(m.view("", Sort.COUNT, false, "ingredients")));
        List<String> combat = keys(m.view("", Sort.CREATIVE, false, "combat"));
        assertEquals(3, combat.size());
        assertTrue(combat.stream().allMatch(k -> k.startsWith("minecraft:diamond_sword")));
        // an unknown tab on an item counts as "other"
        assertEquals(List.of("create:andesite_alloy"), keys(m.view("", Sort.COUNT, false, "other")));
        // the search still applies inside the tab
        assertTrue(m.view("zzz", Sort.COUNT, false, "combat").isEmpty());
        assertEquals(List.of("minecraft:diamond_sword#3f2a9c1d"), keys(m.view("sharpness", Sort.COUNT, false, "combat")));
        // a tab nobody has heard of is empty
        assertTrue(m.view("", Sort.COUNT, false, "no_such_tab").isEmpty());
    }

    @Test
    void searchShulkersAndOrdersTabsKeepTheirMeaning() {
        IndexModel m = model();
        assertEquals(keys(m.view("", Sort.COUNT, false)), keys(m.view("", Sort.COUNT, false, "all")));
        assertEquals(keys(m.view("", Sort.COUNT, false)), keys(m.view("", Sort.COUNT, false, null)));
        assertEquals(keys(m.view("", Sort.COUNT, false)), keys(m.view("", Sort.COUNT, false, "orders")));
        assertEquals(keys(m.view("", Sort.COUNT, true)), keys(m.view("", Sort.COUNT, false, "shulkers")));
        assertEquals(2, m.view("", Sort.COUNT, false, "shulkers").size());
    }
}

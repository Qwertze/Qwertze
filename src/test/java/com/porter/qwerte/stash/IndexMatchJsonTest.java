package com.porter.qwerte.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The match keys in the index JSON (KIT-CONTRACT v2, revision 4): every item and every shulker content entry carries one; an index without them is as before. */
class IndexMatchJsonTest {
    private static IndexJson.Info info(String key, String match) {
        return new IndexJson.Info(key, "minecraft:netherite_sword", "Sword", "Netherite Sword", 1, List.of(), true, "combat", 1, 0, 0, false, match);
    }

    @Test
    void anItemEntryCarriesItsMatchKeyAndAnOldInfoCarriesNone() {
        Map<String, IndexJson.Agg> groups = new LinkedHashMap<>();
        IndexJson.Agg a = new IndexJson.Agg(info("minecraft:netherite_sword#aaaaaaaa", "minecraft:netherite_sword#bbbbbbbb"));
        a.count = 2;
        groups.put(a.info.key(), a);
        JsonArray items = IndexJson.items(groups);
        assertEquals("minecraft:netherite_sword#bbbbbbbb", items.get(0).getAsJsonObject().get("match").getAsString());
        // The old shape: no match key at all, so an index hash from before does not change
        Map<String, IndexJson.Agg> old = new LinkedHashMap<>();
        IndexJson.Agg o = new IndexJson.Agg(new IndexJson.Info("minecraft:stick", "minecraft:stick", "Stick", "Stick", 64, List.of(), false, "ingredients", 1, 0, 0));
        o.count = 1;
        old.put("minecraft:stick", o);
        assertFalse(IndexJson.items(old).get(0).getAsJsonObject().has("match"));
    }

    @Test
    void aShulkerGroupsContentsCarryMatchKeysOnlyWhenAllAreKnown() {
        Map<String, IndexJson.Shulker> shulkers = new LinkedHashMap<>();
        IndexJson.Shulker sh = new IndexJson.Shulker(info("minecraft:red_shulker_box", null), "s:abc");
        sh.slots.add(new int[] {0, 1});
        sh.keys.add("minecraft:netherite_sword#aaaaaaaa");
        sh.matches.add("minecraft:netherite_sword#bbbbbbbb");
        sh.count = 1;
        shulkers.put(sh.key, sh);
        JsonObject c = IndexJson.shulkers(shulkers).get(0).getAsJsonObject().getAsJsonArray("contents").get(0).getAsJsonObject();
        assertEquals("minecraft:netherite_sword#bbbbbbbb", c.get("match").getAsString());
        sh.matches.clear();
        assertFalse(IndexJson.shulkers(shulkers).get(0).getAsJsonObject().getAsJsonArray("contents").get(0).getAsJsonObject().has("match"));
        assertTrue(c.has("key"));
    }
}

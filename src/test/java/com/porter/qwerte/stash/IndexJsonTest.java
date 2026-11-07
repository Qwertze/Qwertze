package com.porter.qwerte.stash;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.porter.qwerte.stash.IndexJson.Agg;
import com.porter.qwerte.stash.IndexJson.Info;
import com.porter.qwerte.stash.IndexJson.Shulker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bot's index JSON must stay byte-identical after the loops were moved out of StashIndex.build. LEGACY below is the
 * code of those loops exactly as they were (copied before the move); both are run on the same fixture and the serialized
 * text, which is what the index hash is made of, must be equal.
 */
class IndexJsonTest {
    private static final Gson GSON = new Gson();

    private static Info info(String key, String id, int tabOrder, int damage, int maxDamage, boolean enchanted, String... details) {
        return new Info(key, id, "Name of " + key, "English " + key, 64, List.of(details), enchanted, "combat", tabOrder, damage, maxDamage);
    }

    private static Map<String, Agg> groups() {
        Map<String, Agg> groups = new LinkedHashMap<>();
        // inserted out of key order on purpose: the output must be sorted
        for (Info i : List.of(
            info("minecraft:stone", "minecraft:stone", 3, 0, 0, false),
            info("minecraft:diamond_sword#ab12", "minecraft:diamond_sword", 7, 12, 1561, true, "Sharpness V", "Unbreaking III"),
            info("minecraft:apple", "minecraft:apple", 1, 0, 0, false),
            info("minecraft:shulker_box", "minecraft:shulker_box", 9, 0, 0, false))) {
            Agg a = new Agg(i);
            a.count = i.key().length() * 17;
            groups.put(i.key(), a);
        }
        return groups;
    }

    private static Map<String, Shulker> shulkers() {
        Map<String, Shulker> map = new LinkedHashMap<>();
        for (String k : List.of("s:zzz", "s:aaa")) {
            Shulker sh = new Shulker(info("minecraft:shulker_box", "minecraft:shulker_box", 9, 0, 0, false), k);
            sh.count = k.equals("s:zzz") ? 3 : 1;
            sh.slots.add(new int[] { 0, 64 });
            sh.keys.add("minecraft:stone");
            sh.slots.add(new int[] { 5, 12 });
            sh.keys.add("minecraft:apple");
            map.put(k, sh);
        }
        return map;
    }

    // ---- the loops of StashIndex.build as they were before the move ----

    private static JsonArray legacyItems(Map<String, Agg> groups) {
        List<Agg> sorted = new ArrayList<>(groups.values());
        sorted.sort(Comparator.comparing((Agg a) -> a.info.key()));

        JsonArray items = new JsonArray();
        for (Agg a : sorted) {
            Info i = a.info;
            JsonObject o = new JsonObject();
            o.addProperty("key", i.key());
            o.addProperty("id", i.id());
            o.addProperty("name", i.name());
            o.addProperty("englishName", i.englishName());
            o.addProperty("count", a.count);
            o.addProperty("maxStack", i.maxStack());
            JsonArray details = new JsonArray();
            for (String d : i.details()) details.add(d);
            o.add("details", details);
            o.addProperty("enchanted", i.enchanted());
            o.addProperty("tab", i.tab());
            o.addProperty("tabOrder", i.tabOrder());
            if (i.damage() > 0) {
                o.addProperty("damage", i.damage());
                o.addProperty("maxDamage", i.maxDamage());
            }
            items.add(o);
        }
        return items;
    }

    private static JsonArray legacyShulkers(Map<String, Shulker> shulkers) {
        List<Shulker> sortedShulkers = new ArrayList<>(shulkers.values());
        sortedShulkers.sort(Comparator.comparing((Shulker x) -> x.key));
        JsonArray shulkerArray = new JsonArray();
        for (Shulker sh : sortedShulkers) {
            JsonObject o = new JsonObject();
            o.addProperty("key", sh.key);
            o.addProperty("itemKey", sh.info.key());
            o.addProperty("id", sh.info.id());
            o.addProperty("name", sh.info.name());
            o.addProperty("count", sh.count);
            JsonArray contents = new JsonArray();
            for (int n = 0; n < sh.keys.size(); n++) {
                JsonObject c = new JsonObject();
                c.addProperty("slot", sh.slots.get(n)[0]);
                c.addProperty("key", sh.keys.get(n));
                c.addProperty("count", sh.slots.get(n)[1]);
                contents.add(c);
            }
            o.add("contents", contents);
            shulkerArray.add(o);
        }
        return shulkerArray;
    }

    @Test
    void itemsAreByteIdenticalToTheOldLoop() {
        String before = GSON.toJson(legacyItems(groups()));
        String after = GSON.toJson(IndexJson.items(groups()));
        assertEquals(before, after);
        assertTrue(before.indexOf("minecraft:apple") < before.indexOf("minecraft:stone"), "sorted by key");
        assertTrue(before.contains("\"damage\":12,\"maxDamage\":1561"));
    }

    @Test
    void shulkersAreByteIdenticalToTheOldLoop() {
        String before = GSON.toJson(legacyShulkers(shulkers()));
        String after = GSON.toJson(IndexJson.shulkers(shulkers()));
        assertEquals(before, after);
        assertTrue(before.indexOf("s:aaa") < before.indexOf("s:zzz"));
    }

    @Test
    void theIndexHashInputIsTheSameConcatenation() {
        // StashIndex.build hashes toJson(items) + toJson(shulkers); the text before and after the move is equal
        String before = GSON.toJson(legacyItems(groups())) + GSON.toJson(legacyShulkers(shulkers()));
        String after = GSON.toJson(IndexJson.items(groups())) + GSON.toJson(IndexJson.shulkers(shulkers()));
        assertEquals(before, after);
    }

    @Test
    void aGoldenItemKeepsItsExactShape() {
        Map<String, Agg> one = new LinkedHashMap<>();
        Agg a = new Agg(info("minecraft:apple", "minecraft:apple", 1, 0, 0, false));
        a.count = 5;
        one.put("minecraft:apple", a);
        assertEquals("[{\"key\":\"minecraft:apple\",\"id\":\"minecraft:apple\",\"name\":\"Name of minecraft:apple\","
            + "\"englishName\":\"English minecraft:apple\",\"count\":5,\"maxStack\":64,\"details\":[],\"enchanted\":false,"
            + "\"tab\":\"combat\",\"tabOrder\":1}]", GSON.toJson(IndexJson.items(one)));
    }
}

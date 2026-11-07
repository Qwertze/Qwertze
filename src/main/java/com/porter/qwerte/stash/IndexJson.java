package com.porter.qwerte.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The JSON shape of the {@code items} and {@code shulkers} arrays of a stash index (stash-manager-server/PROTOCOL.md),
 * written from what {@link StashIndex} worked out about the stacks. It holds no game objects, so it is tested on its own:
 * the bot's index hash is made of exactly these bytes, and the in-game Stash Window's local index uses the same code.
 */
final class IndexJson {
    private IndexJson() {}

    /** What is worked out once per kind of stack (item plus components, container contents ignored). */
    record Info(String key, String id, String name, String englishName, int maxStack, List<String> details, boolean enchanted, String tab, int tabOrder, int damage, int maxDamage) {}

    /** One kind of stack and how many of it there are. */
    static final class Agg {
        final Info info;
        int count;

        Agg(Info info) {
            this.info = info;
        }
    }

    /** One kind of filled shulker box: what is in it, slot by slot, and how many such boxes there are. */
    static final class Shulker {
        final Info info;
        final List<int[]> slots = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        final String key;
        int count;

        Shulker(Info info, String key) {
            this.info = info;
            this.key = key;
        }
    }

    /** The {@code items} array, sorted by key. */
    static JsonArray items(Map<String, Agg> groups) {
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
            // Only a damaged, damageable item has them (a damaged stack has a key of its own, so one entry has one damage value)
            if (i.damage() > 0) {
                o.addProperty("damage", i.damage());
                o.addProperty("maxDamage", i.maxDamage());
            }
            items.add(o);
        }
        return items;
    }

    /** The {@code shulkers} array, sorted by key. */
    static JsonArray shulkers(Map<String, Shulker> shulkers) {
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
}

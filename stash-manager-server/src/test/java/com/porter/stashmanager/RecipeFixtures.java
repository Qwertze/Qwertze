package com.porter.stashmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Synthetic recipe and tag JSON in the format of the game's data files (written for these tests; nothing from Mojang's
 * jars is used), covering what the contract's test vectors and the normaliser rules need.
 */
final class RecipeFixtures {
    private RecipeFixtures() {
    }

    static JsonObject j(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    static String shaped(String key, String pattern, String result, int count) {
        return "{\"type\":\"minecraft:crafting_shaped\",\"key\":" + key + ",\"pattern\":" + pattern + ",\"result\":{\"id\":\"" + result + "\",\"count\":" + count + "}}";
    }

    static String shapeless(String ingredients, String result, int count) {
        return "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":" + ingredients + ",\"result\":{\"id\":\"" + result + "\",\"count\":" + count + "}}";
    }

    static Map<String, String> recipeText() {
        Map<String, String> r = new TreeMap<>();
        for (String wood : new String[]{"spruce", "oak", "birch", "acacia"}) {
            r.put(wood + "_planks", shapeless("[\"#minecraft:" + wood + "_logs\"]", "minecraft:" + wood + "_planks", 4));
        }
        r.put("spruce_stairs", shaped("{\"#\":\"minecraft:spruce_planks\"}", "[\"#  \",\"## \",\"###\"]", "minecraft:spruce_stairs", 4));
        r.put("spruce_slab", shaped("{\"#\":\"minecraft:spruce_planks\"}", "[\"###\"]", "minecraft:spruce_slab", 6));
        r.put("spruce_fence", shaped("{\"#\":\"minecraft:spruce_planks\",\"W\":\"minecraft:stick\"}", "[\"#W#\",\"#W#\"]", "minecraft:spruce_fence", 3));
        r.put("stick", shaped("{\"#\":\"#minecraft:planks\"}", "[\"#\",\"#\"]", "minecraft:stick", 4));
        r.put("stick_from_bamboo_item", shaped("{\"#\":\"minecraft:bamboo\"}", "[\"#\",\"#\"]", "minecraft:stick", 1));
        r.put("ladder", shaped("{\"S\":\"minecraft:stick\"}", "[\"S S\",\"SSS\",\"S S\"]", "minecraft:ladder", 3));
        r.put("chest", shaped("{\"#\":\"#minecraft:planks\"}", "[\"###\",\"# #\",\"###\"]", "minecraft:chest", 1));
        r.put("iron_bars", shaped("{\"#\":\"minecraft:iron_ingot\"}", "[\"###\",\"###\"]", "minecraft:iron_bars", 16));
        r.put("iron_ingot_from_iron_block", shapeless("[\"minecraft:iron_block\"]", "minecraft:iron_ingot", 9));
        r.put("iron_ingot_from_nuggets", shaped("{\"#\":\"minecraft:iron_nugget\"}", "[\"###\",\"###\",\"###\"]", "minecraft:iron_ingot", 1));
        r.put("iron_nugget", shapeless("[\"minecraft:iron_ingot\"]", "minecraft:iron_nugget", 9));
        r.put("iron_block", shaped("{\"#\":\"minecraft:iron_ingot\"}", "[\"###\",\"###\",\"###\"]", "minecraft:iron_block", 1));
        r.put("white_bed", shaped("{\"#\":\"minecraft:white_wool\",\"X\":\"#minecraft:planks\"}", "[\"###\",\"XXX\"]", "minecraft:white_bed", 1));
        r.put("dye_white_bed", shapeless("[\"minecraft:red_bed\",\"minecraft:white_dye\"]", "minecraft:white_bed", 1));
        r.put("white_wool", shaped("{\"S\":\"minecraft:string\"}", "[\"SS\",\"SS\"]", "minecraft:white_wool", 1));
        // Left out by the normaliser's rules:
        r.put("cake", shaped("{\"A\":\"minecraft:milk_bucket\",\"B\":\"minecraft:sugar\"}", "[\"AAA\",\"BBB\"]", "minecraft:cake", 1));
        r.put("mystery_stew", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:bowl\",\"minecraft:red_mushroom\"],"
            + "\"result\":{\"id\":\"minecraft:suspicious_stew\",\"count\":1,\"components\":{\"minecraft:suspicious_stew_effects\":[]}}}");
        r.put("lost_tag", shapeless("[\"#minecraft:no_such_tag\"]", "minecraft:lost_item", 1));
        r.put("loop_tag", shapeless("[\"#minecraft:loop_a\"]", "minecraft:loop_item", 1));
        // Other stations, and a special recipe named after an item:
        r.put("stone", "{\"type\":\"minecraft:smelting\",\"ingredient\":\"minecraft:cobblestone\",\"result\":{\"id\":\"minecraft:stone\"}}");
        r.put("iron_ingot_from_smelting", "{\"type\":\"minecraft:smelting\",\"ingredient\":\"minecraft:raw_iron\",\"result\":{\"id\":\"minecraft:iron_ingot\"}}");
        r.put("stone_bricks_from_stone_stonecutting", "{\"type\":\"minecraft:stonecutting\",\"ingredient\":\"minecraft:stone\",\"result\":{\"id\":\"minecraft:stone_bricks\",\"count\":1}}");
        r.put("red_dyed_shulker", "{\"type\":\"minecraft:crafting_transmute\",\"input\":\"minecraft:shulker_box\",\"material\":\"minecraft:red_dye\",\"result\":\"minecraft:red_shulker_box\"}");
        r.put("firework_star", "{\"type\":\"minecraft:crafting_special_firework_star\",\"category\":\"misc\"}");
        r.put("armor_dye", "{\"type\":\"minecraft:crafting_special_armordye\",\"category\":\"misc\"}");
        r.put("netherite_upgrade", "{\"type\":\"minecraft:smithing_transform\",\"result\":{\"id\":\"minecraft:netherite_sword\"}}");
        return r;
    }

    static Map<String, String> tagText() {
        Map<String, String> t = new TreeMap<>();
        for (String wood : new String[]{"spruce", "oak", "birch", "acacia"}) {
            t.put(wood + "_logs", "{\"values\":[\"minecraft:" + wood + "_log\",\"minecraft:" + wood + "_wood\",\"minecraft:stripped_" + wood
                + "_log\",\"minecraft:stripped_" + wood + "_wood\"]}");
        }
        t.put("planks", "{\"values\":[\"minecraft:oak_planks\",\"minecraft:spruce_planks\",\"minecraft:birch_planks\",\"minecraft:acacia_planks\"]}");
        t.put("loop_a", "{\"values\":[\"#minecraft:loop_b\"]}");
        t.put("loop_b", "{\"values\":[\"#minecraft:loop_a\"]}");
        t.put("logs_that_burn", "{\"values\":[\"#minecraft:oak_logs\",\"#minecraft:spruce_logs\"]}");
        return t;
    }

    static Map<String, JsonObject> recipes() {
        Map<String, JsonObject> out = new TreeMap<>();
        recipeText().forEach((k, v) -> out.put(k, j(v)));
        return out;
    }

    static Map<String, JsonObject> tags() {
        Map<String, JsonObject> out = new TreeMap<>();
        tagText().forEach((k, v) -> out.put(k, j(v)));
        return out;
    }

    static Set<String> items() {
        return Set.of("firework_star", "stone", "stick");
    }

    static RecipeNormalizer.Result normalized(String version) {
        return RecipeNormalizer.normalize(version, recipes(), tags(), items());
    }

    static RecipeTable table() {
        return normalized("1.99.9").table();
    }

    static String name(String id) {
        return Redact.prettyId(id);
    }

    // ---------- a jar ----------

    static void entry(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    /** A client jar with the recipes and tags above (and item definitions, so IconPacks could use it too). */
    static byte[] jar(boolean withItemDefinitions, byte[] png) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : recipeText().entrySet()) {
                entry(z, "data/minecraft/recipe/" + e.getKey() + ".json", e.getValue().getBytes(StandardCharsets.UTF_8));
            }
            for (Map.Entry<String, String> e : tagText().entrySet()) {
                entry(z, "data/minecraft/tags/item/" + e.getKey() + ".json", e.getValue().getBytes(StandardCharsets.UTF_8));
            }
            // Entries that never match the name patterns:
            entry(z, "data/minecraft/recipe/../evil.json", "{}".getBytes(StandardCharsets.UTF_8));
            entry(z, "data/minecraft/recipe/Upper.json", "{}".getBytes(StandardCharsets.UTF_8));
            entry(z, "data/minecraft/recipe//abs.json", "{}".getBytes(StandardCharsets.UTF_8));
            entry(z, "data/other/recipe/foreign.json", "{}".getBytes(StandardCharsets.UTF_8));
            entry(z, "data/minecraft/tags/block/not_items.json", "{}".getBytes(StandardCharsets.UTF_8));
            if (withItemDefinitions) {
                String item = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/test\"}}";
                entry(z, "assets/minecraft/items/stone.json", item.getBytes(StandardCharsets.UTF_8));
                entry(z, "assets/minecraft/items/firework_star.json", item.getBytes(StandardCharsets.UTF_8));
                entry(z, "assets/minecraft/models/item/test.json",
                    "{\"parent\":\"minecraft:builtin/generated\",\"textures\":{\"layer0\":\"minecraft:item/test\"}}".getBytes(StandardCharsets.UTF_8));
                entry(z, "assets/minecraft/textures/item/test.png", png);
            }
        }
        return out.toByteArray();
    }
}

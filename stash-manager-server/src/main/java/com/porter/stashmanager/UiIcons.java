package com.porter.stashmanager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Item ids the website uses as tab icons, with their English names (for the wiki fallback). They are allowed
 * through the icon allowlist even when no index contains them.
 */
final class UiIcons {
    static final Map<String, String> NAMES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("minecraft:compass", "Compass");
        m.put("minecraft:bricks", "Bricks");
        m.put("minecraft:cyan_wool", "Cyan Wool");
        m.put("minecraft:grass_block", "Grass Block");
        m.put("minecraft:oak_sign", "Oak Sign");
        m.put("minecraft:redstone", "Redstone Dust");
        m.put("minecraft:diamond_pickaxe", "Diamond Pickaxe");
        m.put("minecraft:netherite_sword", "Netherite Sword");
        m.put("minecraft:golden_apple", "Golden Apple");
        m.put("minecraft:iron_ingot", "Iron Ingot");
        m.put("minecraft:pig_spawn_egg", "Pig Spawn Egg");
        m.put("minecraft:command_block", "Command Block");
        m.put("minecraft:chest", "Chest");
        m.put("minecraft:writable_book", "Book and Quill");
        m.put("minecraft:shulker_box", "Shulker Box");
        for (String c : new String[]{"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"}) {
            StringBuilder name = new StringBuilder();
            for (String part : c.split("_")) name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
            m.put("minecraft:" + c + "_shulker_box", name + "Shulker Box");
        }
        NAMES = Map.copyOf(m);
    }

    private UiIcons() {
    }
}

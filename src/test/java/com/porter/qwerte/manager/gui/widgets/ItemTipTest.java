package com.porter.qwerte.manager.gui.widgets;

import com.google.gson.JsonParser;
import com.porter.qwerte.manager.model.IndexModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemTipTest {
    private static final String INDEX = """
        {"items":[
          {"key":"minecraft:diamond","id":"minecraft:diamond","name":"Diamond","englishName":"Diamond","count":1728,"maxStack":64,"details":[],"enchanted":false,"tab":"ingredients"},
          {"key":"minecraft:diamond_sword#1","id":"minecraft:diamond_sword","name":"Blade","englishName":"Diamond Sword","count":1,"maxStack":1,
           "details":["Sharpness V"],"enchanted":true,"tab":"combat","damage":1000,"maxDamage":1561},
          {"key":"minecraft:red_shulker_box","id":"minecraft:red_shulker_box","name":"Red Shulker Box","englishName":"Red Shulker Box","count":8,"maxStack":1,"details":[],"enchanted":false,"tab":"colored_blocks"}
         ],
         "shulkers":[
          {"key":"s:1","itemKey":"minecraft:red_shulker_box","id":"minecraft:red_shulker_box","name":"Red Shulker Box","count":4,
           "contents":[{"slot":0,"key":"minecraft:diamond","count":64},{"slot":1,"key":"minecraft:diamond","count":10}]}
         ]}
        """;

    private static IndexModel model() {
        return IndexModel.parse(JsonParser.parseString(INDEX));
    }

    private static IndexModel.Entry item(IndexModel m, String key) {
        IndexModel.Item it = m.item(key);
        return new IndexModel.Entry(it, null, it.count());
    }

    @Test
    void approxLikeTheWebsite() {
        assertEquals("<0.1", ItemTip.approx(0.01));
        assertEquals("0", ItemTip.approx(0));
        assertEquals("1.5", ItemTip.approx(1.5));
        assertEquals("2", ItemTip.approx(2.0));
        assertEquals("27", ItemTip.approx(27.4));
    }

    @Test
    void plainItemHasCountStacksAndShulkers() {
        IndexModel m = model();
        List<String> lines = ItemTip.lines(m, item(m, "minecraft:diamond"));
        assertEquals("Diamond", lines.get(0));
        assertEquals("Count: 1,728 (≈ 27 stacks, 1 shulker)", lines.get(1));
        assertEquals("§8minecraft:diamond", lines.get(lines.size() - 1));
    }

    @Test
    void enchantedDamagedCustomItem() {
        IndexModel m = model();
        List<String> lines = ItemTip.lines(m, item(m, "minecraft:diamond_sword#1"));
        assertTrue(lines.get(0).endsWith("Blade") && lines.get(0).contains("§b") && lines.get(0).contains("§o"));
        assertEquals("Sharpness V", lines.get(1));
        assertEquals("Durability: 561 / 1,561", lines.get(2));
        assertTrue(lines.get(3).startsWith("Count: 1 (≈ 1 stack, "));
    }

    @Test
    void shulkerBoxSaysHowManyAreEmpty() {
        IndexModel m = model();
        List<String> lines = ItemTip.lines(m, item(m, "minecraft:red_shulker_box"));
        assertTrue(lines.contains("4 empty, 4 filled (listed next to it, showing what they hold)"), lines.toString());
    }

    @Test
    void groupListsItsContents() {
        IndexModel m = model();
        List<String> lines = ItemTip.lines(m, new IndexModel.Entry(null, m.shulkers().get(0), 4));
        assertEquals("Red Shulker Box", lines.get(0));
        assertEquals("Diamond ×74", lines.get(1));
        assertEquals("2 stacks", lines.get(2));
        assertEquals("×4 identical shulkers", lines.get(3));
    }

    @Test
    void pickerShowsWhatIsLeftToOrder() {
        IndexModel m = model();
        List<String> lines = ItemTip.lines(m, item(m, "minecraft:diamond"), 64);
        assertEquals("Count: 64 (≈ 1 stack, <0.1 shulkers)", lines.get(1));
    }

    @Test
    void plainTipHasNoCount() {
        String tip = ItemTip.plain("Shulker Box (packed for you)", List.of("Shulker 1 of 2", "Click to look inside"), "minecraft:shulker_box");
        assertEquals("Shulker Box (packed for you)\nShulker 1 of 2\nClick to look inside\n§8minecraft:shulker_box", tip);
    }
}

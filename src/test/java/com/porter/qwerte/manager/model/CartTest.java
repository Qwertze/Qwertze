package com.porter.qwerte.manager.model;

import com.google.gson.JsonParser;
import com.porter.qwerte.stash.Order;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartTest {
    static final String INDEX = """
        {"version":1,"items":[
          {"key":"minecraft:diamond","id":"minecraft:diamond","name":"Diamond","count":100000,"maxStack":64},
          {"key":"minecraft:cobblestone","id":"minecraft:cobblestone","name":"Cobblestone","count":100000,"maxStack":64},
          {"key":"minecraft:ender_pearl","id":"minecraft:ender_pearl","name":"Ender Pearl","count":100,"maxStack":16},
          {"key":"minecraft:diamond_sword","id":"minecraft:diamond_sword","name":"Diamond Sword","count":30,"maxStack":1},
          {"key":"minecraft:red_shulker_box","id":"minecraft:red_shulker_box","name":"Red Shulker Box","count":10,"maxStack":1},
          {"key":"minecraft:apple","id":"minecraft:apple","name":"Apple","count":5,"maxStack":64}
         ],
         "shulkers":[{"key":"s:aaaaaaaaaa","itemKey":"minecraft:red_shulker_box","id":"minecraft:red_shulker_box","name":"Loot","count":4,
           "contents":[{"slot":0,"key":"minecraft:diamond","count":64}]}]}
        """;

    static IndexModel index() {
        return IndexModel.parse(JsonParser.parseString(INDEX));
    }

    private static final Cart.Availability STOCK = (type, key) -> {
        IndexModel idx = index();
        if (Order.SHULKER.equals(type)) return idx.shulkers().stream().filter(g -> g.key().equals(key)).mapToLong(IndexModel.Shulker::count).sum();
        IndexModel.Item it = idx.item(key);
        if (it == null) return 0;
        return it.isShulkerBox() ? idx.emptyShulkerAvailable(key) : it.count();
    };

    @Test
    void linesWithTheSameTypeAndKeyMerge() {
        Cart cart = new Cart();
        assertTrue(cart.add("item", "minecraft:diamond", 64, index()).ok());
        assertTrue(cart.add("item", "minecraft:diamond", 36, index()).ok());
        assertEquals(1, cart.size());
        assertEquals(100, cart.count("item", "minecraft:diamond"));
        assertEquals(0, cart.count("shulker", "minecraft:diamond"));
    }

    @Test
    void addStacksTakesOneStackOrAShulkersWorthClampedToWhatIsLeft() {
        Cart cart = new Cart();
        assertEquals(16, cart.addStacks("item", "minecraft:ender_pearl", false, index(), STOCK).added());
        // 27 stacks of 16 = 432, only 100 exist, 16 are in the cart
        assertEquals(84, cart.addStacks("item", "minecraft:ender_pearl", true, index(), STOCK).added());
        assertEquals(100, cart.count("item", "minecraft:ender_pearl"));
        Cart.Result none = cart.addStacks("item", "minecraft:ender_pearl", false, index(), STOCK);
        assertFalse(none.ok());
        assertEquals("None left to order.", none.message());
    }

    @Test
    void apackedShulkerIsOneBoxPerAddition() {
        Cart cart = new Cart();
        assertEquals(1, cart.addStacks("shulker", "s:aaaaaaaaaa", true, index(), STOCK).added());
        assertEquals(1, cart.count("shulker", "s:aaaaaaaaaa"));
    }

    @Test
    void theCartNeverNeedsMoreThanFiftyFourShulkers() {
        Cart cart = new Cart();
        // 54 shulkers of single swords: 27 * 54 = 1458 slots, but only 30 swords exist; use diamonds: 64 * 27 * 54 = 93312
        assertTrue(cart.add("item", "minecraft:diamond", 64L * 27 * 54, index()).ok());
        assertEquals(54, CartPlan.shulkersNeeded(cart.lines(), index()));
        Cart.Result over = cart.add("item", "minecraft:diamond", 1, index());
        assertFalse(over.ok());
        assertTrue(over.message().contains("at most 54"), over.message());
        // Nothing changed
        assertEquals(64L * 27 * 54, cart.count("item", "minecraft:diamond"));
    }

    @Test
    void settingACountIsClampedToWhatIsAvailableAndTheCap() {
        Cart cart = new Cart();
        cart.add("item", "minecraft:apple", 2, index());
        Cart.Result r = cart.setCount(0, 50, index(), STOCK);
        assertTrue(r.ok());
        assertEquals(5, cart.count("item", "minecraft:apple"));
        assertEquals("Only 5 are available.", r.message());
        cart.setCount(0, 0, index(), STOCK);
        assertEquals(1, cart.count("item", "minecraft:apple"));
    }

    @Test
    void growingAPastTheCapIsRefusedButShrinkingIsAlwaysAllowed() {
        Cart cart = new Cart();
        cart.add("item", "minecraft:diamond", 64L * 27 * 54, index());
        cart.add("item", "minecraft:red_shulker_box", 1, index());
        // 54 + 1 empty box is over the cap only if it got in: it must not have
        assertEquals(0, cart.count("item", "minecraft:red_shulker_box"));
        cart.setCount(0, 64L * 27 * 10, index(), STOCK);
        assertEquals(1, cart.size());
        assertEquals(64L * 27 * 10, cart.count("item", "minecraft:diamond"));
    }

    @Test
    void removeAmountDropsTheLineAtZero() {
        Cart cart = new Cart();
        cart.add("item", "minecraft:red_shulker_box", 3, index());
        cart.removeAmount("item", "minecraft:red_shulker_box", 1);
        assertEquals(2, cart.count("item", "minecraft:red_shulker_box"));
        cart.removeAmount("item", "minecraft:red_shulker_box", 5);
        assertTrue(cart.isEmpty());
    }

    @Test
    void availabilityMinusTheCart() {
        Cart cart = new Cart();
        cart.add("item", "minecraft:apple", 3, index());
        assertEquals(2, cart.left("item", "minecraft:apple", STOCK));
        // empty boxes: 10 boxes minus the 4 filled ones
        assertEquals(6, STOCK.total("item", "minecraft:red_shulker_box"));
    }
}

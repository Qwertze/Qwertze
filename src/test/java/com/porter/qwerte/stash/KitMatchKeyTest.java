package com.porter.qwerte.stash;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The match key over the game's own component patch (KIT-CONTRACT v2, revision 4): the strip set is applied to real stacks, so a renamed, damaged or
 * repaired item keeps its match key while a changed component (the rest) changes it. The enchantment vectors need the game's data registries, which a
 * unit test does not have; fireworks stand in for "a component that counts".
 */
class KitMatchKeyTest {
    private static RegistryOps<Tag> ops;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ops = RegistryAccess.EMPTY.createSerializationContext(NbtOps.INSTANCE);
    }

    private static String match(ItemStack s) {
        return StashIndex.matchKey(ops, s, false);
    }

    private static ItemStack rocket(int flight) {
        ItemStack s = new ItemStack(Items.FIREWORK_ROCKET);
        s.set(DataComponents.FIREWORKS, new Fireworks(flight, List.of()));
        return s;
    }

    @Test
    void theNameDamageAndRepairCostDoNotChangeTheMatchKey() {
        ItemStack plain = new ItemStack(Items.DIAMOND_SWORD);
        assertEquals("minecraft:diamond_sword", match(plain), "nothing left is the plain id");
        ItemStack dressed = new ItemStack(Items.DIAMOND_SWORD);
        dressed.set(DataComponents.CUSTOM_NAME, Component.literal("potato"));
        dressed.set(DataComponents.DAMAGE, 300);
        dressed.set(DataComponents.REPAIR_COST, 7);
        assertEquals(match(plain), match(dressed));
    }

    @Test
    void aComponentThatCountsChangesIt() {
        assertTrue(match(rocket(3)).startsWith("minecraft:firework_rocket#"));
        assertEquals(match(rocket(3)), match(rocket(3)));
        assertNotEquals(match(rocket(3)), match(rocket(1)), "flight 1 is not flight 3");
        // And the name on top of a counting component is still ignored
        ItemStack named = rocket(3);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("fast"));
        assertEquals(match(rocket(3)), match(named));
    }

    @Test
    void aShulkerBoxIgnoresItsContents() {
        ItemStack box = new ItemStack(Items.RED_SHULKER_BOX);
        ItemStack named = new ItemStack(Items.RED_SHULKER_BOX);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Kit"));
        assertEquals(StashIndex.matchKey(ops, box, true), StashIndex.matchKey(ops, named, true));
    }
}

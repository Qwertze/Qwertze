package com.porter.qwerte.stash;

import com.porter.qwerte.stash.KitScore.Slot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The score vectors of KIT-CONTRACT.md (default points, threshold and opMin), and the rules around them. */
class KitScoreTest {
    private static final KitSettings.Scoring D = KitSettings.Scoring.DEFAULT;
    private static final KitItems ITEMS = KitItems.table();

    /** {@code count} slots filled by cycling through the ids (one item per stack), the rest empty (27 slots in all). */
    private static List<Slot> fill(int count, String... ids) {
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < KitScore.SLOTS; i++) slots.add(i < count ? new Slot("minecraft:" + ids[i % ids.length], 1) : new Slot("", 0));
        return slots;
    }

    /** Slots from id and stack count pairs, the rest empty. */
    private static List<Slot> stacks(int slots, Object... idAndCount) {
        List<Slot> out = new ArrayList<>();
        for (int i = 0; i < idAndCount.length; i += 2) out.add(new Slot("minecraft:" + idAndCount[i], (Integer) idAndCount[i + 1]));
        while (out.size() < slots) out.add(new Slot("", 0));
        return out;
    }

    private static void check(KitScore.Result r, int name, int mix, int full, int op, int total, boolean kit) {
        assertEquals(new KitScore.Result(name, mix, full, op, total, kit), r);
    }

    private static final String[] DYES = {"white_dye", "orange_dye", "magenta_dye", "light_blue_dye", "yellow_dye", "lime_dye", "pink_dye", "gray_dye",
        "light_gray_dye", "cyan_dye", "purple_dye", "blue_dye", "brown_dye", "green_dye", "red_dye", "black_dye"};
    private static final String[] CONCRETE = {"white_concrete", "orange_concrete", "magenta_concrete", "light_blue_concrete", "yellow_concrete", "lime_concrete",
        "pink_concrete", "gray_concrete", "light_gray_concrete", "cyan_concrete", "purple_concrete", "blue_concrete", "brown_concrete", "green_concrete",
        "red_concrete", "black_concrete"};

    // The twelve vectors of the contract, in its order

    @Test
    void crystalKitScoresTen() {
        List<Slot> slots = fill(27, "netherite_helmet", "netherite_chestplate", "netherite_leggings", "netherite_boots", "netherite_sword", "totem_of_undying",
            "end_crystal", "obsidian", "respawn_anchor", "glowstone", "ender_pearl", "golden_apple");
        check(KitScore.score(slots, "Crystal Kit", D, ITEMS), 2, 3, 1, 4, 10, true);
        assertEquals(10, D.max());
    }

    @Test
    void anUnnamedArmourAndPotionBoxIsAKit() {
        List<Slot> slots = fill(27, "netherite_helmet", "netherite_chestplate", "netherite_leggings", "netherite_boots", "potion", "potion", "splash_potion");
        check(KitScore.score(slots, null, D, ITEMS), 0, 3, 1, 4, 8, true);
    }

    @Test
    void dyeKit() {
        check(KitScore.score(fill(27, DYES), "Dye Kit", D, ITEMS), 2, 3, 1, 0, 6, true);
    }

    @Test
    void concreteBox() {
        check(KitScore.score(fill(27, CONCRETE), null, D, ITEMS), 0, 3, 1, 0, 4, true);
    }

    @Test
    void tenTotemsInTenSlotsAreNotAKit() {
        check(KitScore.score(fill(10, "totem_of_undying"), null, D, ITEMS), 0, 0, 0, 4, 4, false);
    }

    @Test
    void aFullBoxOfTotemsIsAKit() {
        check(KitScore.score(fill(27, "totem_of_undying"), null, D, ITEMS), 0, 0, 1, 4, 5, true);
    }

    @Test
    void aFullBoxOfDioriteIsNot() {
        check(KitScore.score(fill(27, "diorite"), null, D, ITEMS), 0, 0, 1, 0, 1, false);
    }

    @Test
    void anEmptyBoxIsNeverAKitEvenWithTheName() {
        check(KitScore.score(fill(0, "x"), "kit", D, ITEMS), 2, 0, 0, 0, 2, false);
        assertFalse(KitScore.score(fill(0, "x"), "kit", new KitSettings.Scoring(1, 2, 3, 1, 4, 3), ITEMS).kit(), "even when the threshold is low enough for the name alone");
    }

    @Test
    void threeElytrasAndRocketsFillingTheBoxAreAKit() {
        List<Slot> slots = new ArrayList<>(List.of(new Slot("minecraft:elytra", 1), new Slot("minecraft:elytra", 1), new Slot("minecraft:elytra", 1)));
        for (int i = 3; i < 27; i++) slots.add(new Slot("minecraft:firework_rocket", 64));
        check(KitScore.score(slots, null, D, ITEMS), 0, 0, 1, 4, 5, true);
    }

    @Test
    void mixedStorageWithOneDiamondSwordIsNotAKit() {
        List<Slot> slots = stacks(27, "diamond_sword", 1, "cobblestone", 64, "cobblestone", 64, "dirt", 64, "oak_log", 64, "oak_log", 64, "oak_log", 64,
            "dirt", 64, "cobblestone", 64, "dirt", 64);
        check(KitScore.score(slots, null, D, ITEMS), 0, 0, 0, 0, 0, false);
    }

    @Test
    void threeTotemsWithCobblestoneAndDirtAreAKit() {
        List<Slot> slots = stacks(27, "totem_of_undying", 1, "totem_of_undying", 1, "totem_of_undying", 1, "cobblestone", 64, "cobblestone", 64, "dirt", 64,
            "dirt", 64, "cobblestone", 64);
        check(KitScore.score(slots, null, D, ITEMS), 0, 0, 0, 4, 4, true);
    }

    @Test
    void fourTotemsAloneAreNotAKitBecauseTheBoxIsOneItemAndNotFull() {
        check(KitScore.score(stacks(27, "totem_of_undying", 1, "totem_of_undying", 1, "totem_of_undying", 1, "totem_of_undying", 1), null, D, ITEMS), 0, 0, 0, 4, 4, false);
    }

    // The rules around them

    @Test
    void opItemsAreCountedByStackCountsNotBySlots() {
        // one slot holding three totems (a stack of three) reaches the default opMin of 3 ...
        assertEquals(4, KitScore.score(stacks(27, "totem_of_undying", 3, "cobblestone", 64), null, D, ITEMS).op());
        // ... two items do not, whatever the slots
        assertEquals(0, KitScore.score(stacks(27, "totem_of_undying", 2, "cobblestone", 64), null, D, ITEMS).op());
        // different listed ids add up: a sword and two god apples
        assertEquals(4, KitScore.score(stacks(27, "diamond_sword", 1, "enchanted_golden_apple", 2, "cobblestone", 64), null, D, ITEMS).op());
        // and opMin is yours to set
        KitSettings.Scoring one = new KitSettings.Scoring(4, 2, 3, 1, 4, 1);
        assertEquals(4, KitScore.score(stacks(27, "totem_of_undying", 1, "cobblestone", 64), null, one, ITEMS).op());
        KitSettings.Scoring many = new KitSettings.Scoring(4, 2, 3, 1, 4, 100);
        assertEquals(0, KitScore.score(stacks(27, "totem_of_undying", 64, "cobblestone", 64), null, many, ITEMS).op());
        assertEquals(4, KitScore.score(stacks(27, "totem_of_undying", 64, "end_crystal", 64, "cobblestone", 64), null, many, ITEMS).op());
    }

    @Test
    void theNamePointsFollowIsKitOnlyAndTheWordHasToBeAWord() {
        assertEquals(2, KitScore.score(fill(5, "diorite", "stone"), "PvP Kits", D, ITEMS).name());
        assertEquals(0, KitScore.score(fill(5, "diorite", "stone"), "Toolkit", D, ITEMS).name());
        assertEquals(0, KitScore.score(fill(5, "diorite", "stone"), null, D, ITEMS).name());
        assertTrue(KitScore.score(fill(5, "diorite", "stone"), "kit", new KitSettings.Scoring(2, 2, 3, 1, 4, 3), ITEMS).kit());
        assertFalse(KitScore.score(fill(5, "diorite", "stone"), "kit", D, ITEMS).kit(), "2 points under the default threshold of 4");
    }

    @Test
    void yourOwnPointsAndThresholdApply() {
        KitSettings.Scoring none = new KitSettings.Scoring(1, 0, 0, 0, 0, 3);
        assertFalse(KitScore.score(fill(27, "totem_of_undying", "stone"), null, none, ITEMS).kit(), "nothing scores, so no kit at threshold 1");
        KitSettings.Scoring hard = new KitSettings.Scoring(11, 2, 3, 1, 4, 3);
        assertFalse(KitScore.score(fill(27, "netherite_helmet", "netherite_chestplate", "netherite_leggings", "totem_of_undying"), "Kit", hard, ITEMS).kit(), "10 under 11");
    }

    @Test
    void mixNeedsTheGroupsMinimumOfDistinctIds() {
        assertEquals(0, KitScore.score(fill(27, "elytra", "firework_rocket"), null, D, ITEMS).mix());
        assertEquals(3, KitScore.score(fill(27, "elytra", "firework_rocket", "ender_pearl"), null, D, ITEMS).mix());
    }

    @Test
    void unknownIdsNeverMatchAndShortListsCountAsPartFilled() {
        assertEquals(0, KitScore.score(List.of(new Slot("minecraft:not_an_item_in_this_version", 64)), null, D, ITEMS).total());
        List<Slot> three = new ArrayList<>(List.of(new Slot("minecraft:totem_of_undying", 1), new Slot("minecraft:totem_of_undying", 1), new Slot("minecraft:totem_of_undying", 1)));
        assertFalse(KitScore.score(three, null, D, ITEMS).kit());
    }

    @Test
    void scoringIsChecked() {
        assertTrue(D.valid());
        assertFalse(new KitSettings.Scoring(0, 2, 3, 1, 4, 3).valid());
        assertFalse(new KitSettings.Scoring(41, 2, 3, 1, 4, 3).valid());
        assertFalse(new KitSettings.Scoring(4, 11, 3, 1, 4, 3).valid());
        assertFalse(new KitSettings.Scoring(4, 2, -1, 1, 4, 3).valid());
        assertTrue(new KitSettings.Scoring(40, 10, 10, 10, 10, 1728).valid());
        assertEquals(40, new KitSettings.Scoring(40, 10, 10, 10, 10, 1728).max());
    }
}

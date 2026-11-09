package com.porter.qwerte.stash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared kit-name vectors (KIT-CONTRACT.md): the server, the addon and this plugin must all agree on them. */
class KitNamesTest {
    private static final char SECTION = (char) 0xA7;
    private static final char HIGH = (char) 0xD83D, LOW = (char) 0xDE00;

    private static void vector(String name, String key, boolean kit) {
        String normal = KitNames.normalize(name);
        assertEquals(key, normal, "key of " + name);
        assertEquals(kit, KitNames.isKit(normal), "kit: " + name);
        if (key != null) assertTrue(KitNames.validKey(key), "any non-null key is valid, kit or not");
    }

    @Test
    void contractVectors() {
        vector("PvP Kit", "pvp kit", true);
        vector("kit 3", "kit 3", true);
        vector("KIT-base", "kit-base", true);
        vector("kit_3", "kit_3", true);
        vector("Kit3", "kit3", true);
        vector("my-kit!", "my-kit!", true);
        vector("PvP Kits", "pvp kits", true);
        vector(SECTION + "6PvP " + SECTION + "lKit", "pvp kit", true);
        vector("K" + SECTION + "ci" + SECTION + "at", "kit", true);
        vector("  Kit\t\tOne  ", "kit one", true);
        vector("kit" + (char) 0x200B, "kit", true);
        vector("Mixed Concrete", "mixed concrete", false);
        vector("Kitchen", "kitchen", false);
        vector("Skittles", "skittles", false);
        vector("Toolkit", "toolkit", false);
        vector("Kitsune", "kitsune", false);
        vector("" + (char) 0xFF2B + (char) 0xFF29 + (char) 0xFF34, "" + (char) 0xFF4B + (char) 0xFF49 + (char) 0xFF54, false);
        vector("" + SECTION + SECTION, null, false);
        vector("   ", null, false);
        vector("", null, false);
        vector(null, null, false);
    }

    @Test
    void aTrailingSectionSignIsDroppedToo() {
        assertEquals("kit", KitNames.normalize("Kit" + SECTION));
        assertNull(KitNames.normalize("" + SECTION));
    }

    @Test
    void noBreakSpaceAndOtherSeparatorsAreSpaces() {
        assertEquals("pvp kit", KitNames.normalize("PvP" + (char) 0xA0 + "Kit"));
        assertEquals("pvp kit", KitNames.normalize("PvP" + (char) 0x2003 + (char) 0x2003 + "Kit"));
    }

    @Test
    void controlAndFormatCharactersAreDropped() {
        assertEquals("kit", KitNames.normalize("k" + (char) 0 + "i" + (char) 0x202E + "t"));
        assertEquals("kitone", KitNames.normalize("kit" + (char) 0x200D + "One"));
    }

    @Test
    void lowercaseUsesTheRootLocale() {
        assertEquals("kit", KitNames.normalize("KIT"));
        // Capital I with a dot lowercases to i plus a combining dot, in every locale
        assertEquals("i" + (char) 0x307 + " kit", KitNames.normalize("" + (char) 0x130 + " KIT"));
    }

    @Test
    void cutAtSixtyFourUnitsWithoutHalfAPair() {
        String key = KitNames.normalize("kit " + "a".repeat(100));
        assertEquals(64, key.length());

        // Units 63 and 64 are one surrogate pair: the cut would split it, so the high half goes
        String pair = "kit " + "a".repeat(59) + HIGH + LOW + "tail";
        assertEquals(63, pair.indexOf(HIGH), "test layout: the pair starts at unit 63");
        String cut = KitNames.normalize(pair);
        assertEquals(63, cut.length());
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));

        // A space at the cut is trimmed again
        assertEquals("kit " + "a".repeat(59), KitNames.normalize("kit " + "a".repeat(59) + " bbb"));
    }

    @Test
    void boundariesAreAnythingButLettersAndMarks() {
        assertTrue(KitNames.isKit("kit"));
        assertTrue(KitNames.isKit("kits"));
        assertTrue(KitNames.isKit("a-kit-b"));
        assertTrue(KitNames.isKit("9kit9"));
        assertTrue(KitNames.isKit("my_kits_"));
        assertFalse(KitNames.isKit("kitt"));
        assertFalse(KitNames.isKit("akit"));
        assertFalse(KitNames.isKit("kit" + (char) 0x301), "a combining mark after it makes it part of a word");
        assertFalse(KitNames.isKit("" + (char) 0xE9 + "kit"));
        assertFalse(KitNames.isKit(null));
    }

    @Test
    void validKeyNeedsAnAlreadyNormalNameButNotTheWordKit() {
        assertTrue(KitNames.validKey("pvp kit"));
        assertTrue(KitNames.validKey("toolkit"), "v1.1: the word kit is no longer needed");
        assertTrue(KitNames.validKey("mixed concrete"));
        assertFalse(KitNames.validKey("a" + HIGH), "an unpaired high surrogate");
        assertFalse(KitNames.validKey("" + LOW + "a"), "an unpaired low surrogate");
        assertTrue(KitNames.validKey("a" + HIGH + LOW));
        assertFalse(KitNames.validKey("PvP Kit"));
        assertFalse(KitNames.validKey(" kit"));
        assertFalse(KitNames.validKey("kit  one"));
        assertFalse(KitNames.validKey(""));
        assertFalse(KitNames.validKey(null));
        assertFalse(KitNames.validKey("kit " + "a".repeat(80)));
    }

    @Test
    void aNameWithAnUnpairedSurrogateIsUnnamed() {
        assertEquals("pvp kit", KitNames.keyOf("PvP Kit"));
        assertNull(KitNames.keyOf(null));
        assertNull(KitNames.keyOf("   "));
        assertNull(KitNames.keyOf("kit " + HIGH), "a lone high surrogate");
        assertNull(KitNames.keyOf("" + LOW + " kit"), "a lone low surrogate");
        assertEquals("kit " + HIGH + LOW, KitNames.keyOf("kit " + HIGH + LOW), "a pair is fine");
        assertTrue(KitNames.usable("kit"));
        assertFalse(KitNames.usable(null));
        assertFalse(KitNames.usable("a" + HIGH));
    }
}

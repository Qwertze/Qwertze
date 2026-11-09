package com.porter.stashmanager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared test vectors of KIT-CONTRACT.md (the plugin and the addon run the same table). */
class KitNamesTest {
    private static void vector(String name, String key, boolean kit) {
        assertEquals(key, KitNames.normalize(name), "key of [" + name + "]");
        assertEquals(kit, KitNames.isKit(KitNames.normalize(name)), "kit? [" + name + "]");
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
        vector("\u00A76PvP \u00A7lKit", "pvp kit", true);
        vector("K\u00A7ci\u00A7at", "kit", true);
        vector("  Kit\t\tOne  ", "kit one", true);
        vector("kit\u200B", "kit", true);
        vector("Kitchen", "kitchen", false);
        vector("Skittles", "skittles", false);
        vector("Toolkit", "toolkit", false);
        vector("Kitsune", "kitsune", false);
        vector("\uFF2B\uFF29\uFF34", "\uFF4B\uFF49\uFF54", false);
        vector("\u00A7\u00A7", null, false);
        vector("   ", null, false);
        vector("", null, false);
        assertNull(KitNames.normalize(null));
        assertFalse(KitNames.isKit(null));
    }

    @Test
    void edgeCases() {
        assertEquals("kit", KitNames.normalize("kit\u00A7"), "a trailing lone section sign");
        assertEquals("kit a", KitNames.normalize("Kit\u00A0a"), "no-break space is a space");
        assertEquals("kit a", KitNames.normalize("Kit\u2003\u3000a"), "other space separators collapse");
        assertEquals("kita", KitNames.normalize("Kit\u202Ea"), "format characters are dropped");
        assertEquals("kit", KitNames.normalize("\u00A7\uD83D\uDE00kit"), "the code point after the section sign may be a pair");
        assertEquals("kit a", KitNames.normalize("KIT\n\r\ta"));
        assertTrue(KitNames.isKit("a kit."));
        assertTrue(KitNames.isKit("kit"));
        assertTrue(KitNames.isKit("kits2"));
        assertFalse(KitNames.isKit("kite"));
        assertFalse(KitNames.isKit("\u00E9kit"), "a letter next to it is not a boundary");
    }

    @Test
    void longNamesAreCutAtSixtyFourUnits() {
        String base = "kit " + "a".repeat(59); // 63 units
        String withPair = base + "\uD83D\uDE00"; // 65 units: the cut falls between the surrogates
        String key = KitNames.normalize(withPair);
        assertEquals(base, key);
        assertEquals(63, key.length());
        assertEquals(64, KitNames.normalize("kit " + "b".repeat(100)).length());
        // The cut can leave a space at the end: trimmed again.
        assertEquals("kit " + "c".repeat(59), KitNames.normalize("kit " + "c".repeat(59) + " " + "d".repeat(10)));
    }

    @Test
    void keysAndIds() {
        assertTrue(KitNames.validKey("pvp kit"));
        assertFalse(KitNames.validKey("PvP Kit"), "not normalised");
        assertFalse(KitNames.validKey("pvp  kit"));
        assertTrue(KitNames.validKey("chest"), "v1.1: a key needs no kit word");
        assertFalse(KitNames.validKey(null));
        assertFalse(KitNames.validKey("kit " + (char) 0xD800), "a lone high surrogate");
        assertFalse(KitNames.validKey((char) 0xDC00 + " kit"), "a lone low surrogate");
        assertTrue(KitNames.validKey("kit " + new String(Character.toChars(0x1F600))), "a pair is fine");
        assertFalse(KitNames.validKey(""));
        assertFalse(KitNames.validKey("kit " + "x".repeat(70)), "too long to be its own key");

        assertTrue(KitNames.validId("120,64,-33"));
        assertTrue(KitNames.validId("-30000000,-2048,30000000"));
        assertTrue(KitNames.validId("0,2048,0"));
        assertFalse(KitNames.validId("30000001,64,0"));
        assertFalse(KitNames.validId("0,2049,0"));
        assertFalse(KitNames.validId("0,-2049,0"));
        assertFalse(KitNames.validId("1, 2, 3"));
        assertFalse(KitNames.validId("1,2"));
        assertFalse(KitNames.validId("1,2,3,4"));
        assertFalse(KitNames.validId("123456789,64,0"));
        assertFalse(KitNames.validId("1,64,x"));
        assertFalse(KitNames.validId(null));
        assertTrue(KitNames.compareIds("1,2,3", "1,2,4") < 0);
        assertTrue(KitNames.compareIds("-5,200,3", "1,0,0") < 0);
        assertTrue(KitNames.compareIds("10,0,0", "9,0,0") > 0);
    }
}

package com.porter.qwerte.tweaks;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DownKeysTest {
    private static final int ALT = 342;

    @Test
    void nothingDown() {
        assertEquals("[] others=0", DownKeys.describe(k -> false, ALT));
    }

    @Test
    void modifiersAndTheFreezeKeyAreNamed() {
        Set<Integer> down = Set.of(340, 342, 347);
        assertEquals("[340 342 347] others=0", DownKeys.describe(down::contains, ALT));
        assertEquals("[90 340 342 347] others=0", DownKeys.describe(Set.of(340, 342, 347, 90)::contains, 90));
    }

    @Test
    void aLetterKeyNeverAppears() {
        // A, W, 1, space, Enter, Backspace
        for (int key : new int[]{65, 87, 49, 32, 257, 259}) {
            String line = DownKeys.describe(k -> k == key || k == ALT, ALT);
            assertEquals("[342] others=1", line, "key " + key);
            assertFalse(line.contains(Integer.toString(key)) && key != 342);
        }
    }

    @Test
    void typingIsOnlyCounted() {
        Set<Integer> typed = Set.of(72, 69, 76, 79, 342);
        assertEquals("[342] others=4", DownKeys.describe(typed::contains, ALT));
    }
}

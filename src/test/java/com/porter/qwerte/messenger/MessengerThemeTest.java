package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessengerThemeTest {
    @Test
    void defaultsAreTheOldLook() {
        assertEquals(0x0E1218, ThemeColor.WINDOW.defaultRgb());
        assertEquals(0x1B2430, ThemeColor.HEADER.defaultRgb());
        assertEquals(0x131A22, ThemeColor.LEFT_PANE.defaultRgb());
        assertEquals(0x24456B, ThemeColor.SELECTED.defaultRgb());
        assertEquals(0x1E2C3B, ThemeColor.HOVER.defaultRgb());
        assertEquals(0xFFE6EAF0, ThemeColor.TEXT.defaultArgb());
        assertEquals(0xFF8A94A0, ThemeColor.DIM.defaultArgb());
        assertEquals(0xFFFFD866, ThemeColor.FRIEND.defaultArgb());
        assertEquals(0xFF4CD964, ThemeColor.ONLINE_DOT.defaultArgb());
        assertEquals(0xFFE5484D, ThemeColor.OFFLINE_DOT.defaultArgb());
        assertEquals(0xFF2A3644, ThemeColor.BUTTON.defaultArgb());
        assertEquals(0xFF3A5A80, ThemeColor.BUTTON_HOVER.defaultArgb());
        assertEquals(0xFF2D4C70, ThemeColor.BUTTON_ACTIVE.defaultArgb());
        assertEquals(0xFFD2F5D8, ThemeColor.OWN.defaultArgb());
        assertEquals(0xFFDCEBFF, ThemeColor.INCOMING.defaultArgb());
        assertEquals(0xFF4FA3FF, ThemeColor.ACCENT.defaultArgb());
    }

    @Test
    void settingNamesAreUnique() {
        var names = new HashSet<String>();
        for (ThemeColor c : ThemeColor.values()) assertTrue(names.add(c.settingName()), c.settingName());
    }

    @Test
    void defaultBadgeKeepsTheDarkText() {
        assertEquals(0xFF0B1118, MessengerTheme.textOn(ThemeColor.BADGE.defaultArgb()));
        assertEquals(0xFFE6EAF0, MessengerTheme.textOn(0xFF101010));
    }

    @Test
    void meteorMappingCoversEveryColourAndIsOpaque() {
        Map<ThemeColor, Integer> m = MessengerTheme.fromMeteor(MessengerTheme.Palette.meteorDefault());
        for (ThemeColor c : ThemeColor.values()) {
            assertTrue(m.containsKey(c), c.name());
            assertEquals(0xFF, m.get(c) >>> 24, c.name());
        }
    }

    @Test
    void meteorMappingUsesTheRightSources() {
        var p = new MessengerTheme.Palette(0xFF112233, 0xFFAABBCC, 0xFF445566, 0xFFFAD700, 0xFF00FF00, 0xFF00EE00, 0xFFEE0000,
            0xC8010203, 0xC8040506, 0xC8070809, 0xFF0A0B0C);
        Map<ThemeColor, Integer> m = MessengerTheme.fromMeteor(p);
        assertEquals(0xFF010203, m.get(ThemeColor.WINDOW));
        assertEquals(0xFF0A0B0C, m.get(ThemeColor.HEADER));
        assertEquals(0xFF040506, m.get(ThemeColor.LEFT_PANE));
        assertEquals(0xFF070809, m.get(ThemeColor.HOVER));
        assertEquals(0xFFAABBCC, m.get(ThemeColor.TEXT));
        assertEquals(0xFF445566, m.get(ThemeColor.DIM));
        assertEquals(0xFFFAD700, m.get(ThemeColor.FRIEND));
        assertEquals(0xFF112233, m.get(ThemeColor.ACCENT));
        assertEquals(0xFF112233, m.get(ThemeColor.BADGE));
        assertEquals(0xFF00EE00, m.get(ThemeColor.ONLINE_DOT));
        assertEquals(0xFFEE0000, m.get(ThemeColor.OFFLINE_DOT));
    }

    @Test
    void mix() {
        assertEquals(0xFF808080, MessengerTheme.mix(0xFF000000, 0xFFFFFFFF, 0.5f));
        assertEquals(0xFF000000, MessengerTheme.mix(0xFF000000, 0xFFFFFFFF, 0f));
    }

    @Test
    void scaleAlphaKeepsColour() {
        int c = MessengerTheme.scaleAlpha(0xFFABCDEF, 0.55f);
        assertEquals(0xABCDEF, c & 0xFFFFFF);
        assertEquals(Math.round(255 * 0.55f), c >>> 24);
        assertEquals(0xFFABCDEF, MessengerTheme.scaleAlpha(0xFFABCDEF, 2f));
        assertEquals(0x00ABCDEF, MessengerTheme.scaleAlpha(0xFFABCDEF, -1f));
    }
}

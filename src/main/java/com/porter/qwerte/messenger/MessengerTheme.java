package com.porter.qwerte.messenger;

import java.util.EnumMap;
import java.util.Map;

/**
 * Colour helpers and the mapping from Meteor's GUI theme to the Messenger's colours. Pure: no Minecraft or Meteor
 * classes; the module reads the colours out of Meteor's theme into a {@link Palette}.
 */
public final class MessengerTheme {
    private MessengerTheme() {
    }

    /** The colours of Meteor's theme that are used, each 0xAARRGGBB. */
    public record Palette(int accent, int text, int textSecondary, int favorite, int loggedIn, int plus, int minus,
                          int background, int backgroundHover, int backgroundPressed, int moduleBackground) {
        /** Meteor's own default theme (used for what a theme does not have). */
        public static Palette meteorDefault() {
            return new Palette(0xFF913DE2, 0xFFFFFFFF, 0xFF969696, 0xFFFAD700, 0xFF2DE12D, 0xFF32FF32, 0xFFFF3232,
                0xC8141414, 0xC81E1E1E, 0xC8282828, 0xFF323232);
        }
    }

    /** Meteor's colours mapped to the Messenger's. The opacity of the backgrounds is the window-opacity setting's, so these are opaque. */
    public static Map<ThemeColor, Integer> fromMeteor(Palette p) {
        Map<ThemeColor, Integer> m = new EnumMap<>(ThemeColor.class);
        int window = opaque(p.background());
        int header = opaque(p.moduleBackground());
        int left = opaque(p.backgroundHover());
        m.put(ThemeColor.WINDOW, window);
        m.put(ThemeColor.HEADER, header);
        m.put(ThemeColor.LEFT_PANE, left);
        m.put(ThemeColor.SELECTED, mix(window, p.accent(), 0.45f));
        m.put(ThemeColor.HOVER, opaque(p.backgroundPressed()));
        m.put(ThemeColor.TEXT, opaque(p.text()));
        m.put(ThemeColor.DIM, opaque(p.textSecondary()));
        m.put(ThemeColor.FRIEND, opaque(p.favorite()));
        m.put(ThemeColor.ONLINE_NAME, opaque(p.loggedIn()));
        m.put(ThemeColor.ONLINE_DOT, opaque(p.plus()));
        m.put(ThemeColor.OFFLINE_DOT, opaque(p.minus()));
        m.put(ThemeColor.BADGE, opaque(p.accent()));
        m.put(ThemeColor.INPUT, header);
        int button = opaque(p.backgroundPressed());
        m.put(ThemeColor.BUTTON, button);
        m.put(ThemeColor.BUTTON_HOVER, mix(button, p.accent(), 0.6f));
        m.put(ThemeColor.BUTTON_ACTIVE, mix(button, p.accent(), 0.35f));
        m.put(ThemeColor.OWN, mix(p.text(), p.plus(), 0.2f));
        m.put(ThemeColor.INCOMING, mix(p.text(), p.accent(), 0.2f));
        m.put(ThemeColor.OWN_BAR, opaque(p.plus()));
        m.put(ThemeColor.INCOMING_BAR, opaque(p.accent()));
        m.put(ThemeColor.ACCENT, opaque(p.accent()));
        m.put(ThemeColor.WARNING, opaque(p.favorite()));
        m.put(ThemeColor.ERROR, opaque(p.minus()));
        return m;
    }

    public static int opaque(int argb) {
        return 0xFF000000 | (argb & 0xFFFFFF);
    }

    /** {@code a} towards {@code b} by {@code t} (0 to 1), opaque. */
    public static int mix(int a, int b, float t) {
        int r = ch(a, b, 16, t);
        int g = ch(a, b, 8, t);
        int bl = ch(a, b, 0, t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int ch(int a, int b, int shift, float t) {
        int x = (a >> shift) & 0xFF;
        int y = (b >> shift) & 0xFF;
        return Math.max(0, Math.min(255, Math.round(x + (y - x) * t)));
    }

    /** The colour with its alpha multiplied by {@code factor} (0 to 1). */
    public static int scaleAlpha(int argb, float factor) {
        float f = Math.max(0f, Math.min(1f, factor));
        int a = Math.round(((argb >>> 24) & 0xFF) * f);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Text on top of {@code bg}: dark on a light colour, light on a dark one. The default badge (0x4FA3FF) gets the old dark text. */
    public static int textOn(int bg) {
        double lum = (0.2126 * ((bg >> 16) & 0xFF) + 0.7152 * ((bg >> 8) & 0xFF) + 0.0722 * (bg & 0xFF)) / 255.0;
        return lum > 0.4 ? 0xFF0B1118 : 0xFFE6EAF0;
    }
}

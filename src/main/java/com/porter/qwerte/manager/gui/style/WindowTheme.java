package com.porter.qwerte.manager.gui.style;

import java.util.Locale;

/**
 * The window's themes: every website palette (themes.css, dev/themes-palette.json) plus Mac. Classic is the default,
 * as on the website. Pure: no Minecraft or Meteor classes. The colours here are checked against the JSON by a test.
 */
public enum WindowTheme {
    CLASSIC("classic", "Classic", Palette.of(0, 2, true,
        "#3b2a1e", "#c6c6c6", "#b5b5b5", "#111111", "#3a3a3a", "#3355cc", "#ffffff", "#1a1a1a", "#1f1f1f", "#f2f2f2", "#1f1f1f", "#d6d6d6", "#ffffff", "#4a4a4a", "#6b6b6b", "#d9d9d9", "#2e7d32", "#ffffff", "#c9a227", "#1a1a1a", "#b71c1c", "#ffffff", "#d32f2f", "#b8732e")),
    MODERN("modern", "Modern", Palette.of(16, 1, false,
        "#eef1f6", "#ffffff", "#f5f7fb", "#0f172a", "#475569", "#4f46e5", "#ffffff", "#e2e8f0", "#ffffff", "#0f172a", "#ffffff", "#475569", "#4338ca", "#eef2ff", "#cbd5e1", "#f1f5f9", "#dcfce7", "#14532d", "#fef3c7", "#78350f", "#fee2e2", "#7f1d1d", "#ef4444", "#f59e0b")),
    LIGHT("light", "Light", Palette.of(8, 1, false,
        "#fafaf9", "#ffffff", "#f5f5f4", "#1c1917", "#57534e", "#0f766e", "#ffffff", "#d6d3d1", "#fafaf9", "#1c1917", "#ffffff", "#57534e", "#0f766e", "#ccfbf1", "#d6d3d1", "#f5f5f4", "#dcfce7", "#14532d", "#fef3c7", "#78350f", "#fee2e2", "#7f1d1d", "#dc2626", "#d97706")),
    DARK("dark", "Dark", Palette.of(12, 1, false,
        "#0e1116", "#171b22", "#1f2530", "#e6e9ef", "#a3adbb", "#60a5fa", "#0b1220", "#2a3140", "#0e1116", "#e6e9ef", "#12161c", "#a3adbb", "#93c5fd", "#1e2a3d", "#3a4252", "#1f2530", "#14301f", "#86efac", "#3a2e10", "#fcd34d", "#3b1416", "#fca5a5", "#f87171", "#fbbf24")),
    MIDNIGHT("midnight", "Midnight", Palette.of(10, 1, false,
        "#000000", "#0a0a0a", "#141414", "#f5f5f5", "#a3a3a3", "#f59e0b", "#111111", "#262626", "#000000", "#f5f5f5", "#000000", "#a3a3a3", "#fbbf24", "#1c1405", "#333333", "#141414", "#0b2414", "#86efac", "#2a1d05", "#fcd34d", "#2a0b0b", "#fca5a5", "#ef4444", "#f59e0b")),
    CONTRAST("contrast", "High contrast", Palette.of(4, 2, false,
        "#ffffff", "#ffffff", "#f0f0f0", "#000000", "#1f1f1f", "#0033cc", "#ffffff", "#000000", "#000000", "#ffffff", "#000000", "#ffffff", "#ffdd00", "#222222", "#767676", "#ffffff", "#006b1f", "#ffffff", "#ffdd00", "#000000", "#a00000", "#ffffff", "#a00000", "#7a4a00")),
    MAC("mac", "Mac", Palette.of(8, 1, false,
        "#0e0e10", "#1c1c1e", "#26262a", "#ebe9e6", "#a09fa4", "#f2a48c", "#1f1310", "#38383d", "#161618", "#ebe9e6", "transparent", "#a09fa4", "#1f1310", "#f2a48c", "#48484d", "#2a2a2e", "#163a22", "#7ee2a0", "#3a2f12", "#f7cd6e", "#3d1719", "#ffa39c", "#ff6961", "#f5b45f").mac("rgba(30,30,33,.80)", "#ff5f57", "#febc2e", "#28c840"));

    public static final WindowTheme DEFAULT = CLASSIC;

    private final String id;
    private final String label;
    private final Palette palette;

    WindowTheme(String id, String label, Palette palette) {
        this.id = id;
        this.label = label;
        this.palette = palette;
    }

    public String id() {
        return id;
    }

    public Palette palette() {
        return palette;
    }

    /** The next theme in the list (the header button cycles through them). */
    public WindowTheme next() {
        WindowTheme[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** By website id ("classic", "contrast", ...); the default for anything unknown. */
    public static WindowTheme byId(String id) {
        if (id != null) {
            String s = id.trim().toLowerCase(Locale.ROOT);
            for (WindowTheme t : values()) if (t.id.equals(s)) return t;
        }
        return DEFAULT;
    }

    @Override
    public String toString() {
        return label;
    }
}

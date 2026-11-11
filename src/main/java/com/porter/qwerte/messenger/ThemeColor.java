package com.porter.qwerte.messenger;

/** Every colour of the Messenger window, with its setting name and its default (the look before theming existed). */
public enum ThemeColor {
    WINDOW("window-background", "The window's background.", 0x0E1218),
    HEADER("header", "The title bar.", 0x1B2430),
    LEFT_PANE("left-pane", "The conversation list on the left.", 0x131A22),
    SELECTED("selected-row", "The open conversation in the list, and the picked row in the New list.", 0x24456B),
    HOVER("hover-row", "A row under the mouse.", 0x1E2C3B),
    TEXT("text", "Main text.", 0xE6EAF0),
    DIM("dim-text", "Secondary text: hints, tags, notes.", 0x8A94A0),
    FRIEND("friend-name", "The name of a Meteor friend in the list.", 0xFFD866),
    ONLINE_NAME("online-name", "The name of a player who is in the tab list (not a friend).", 0xE6EAF0),
    ONLINE_DOT("online-dot", "The dot of a player in the tab list.", 0x4CD964),
    OFFLINE_DOT("offline-dot", "The ring of a player who is not in the tab list.", 0xE5484D),
    BADGE("unread-badge", "The unread count.", 0x4FA3FF),
    INPUT("input-box", "The hint box shown instead of the input line while the window is not focused.", 0x1B2430),
    BUTTON("button", "Buttons.", 0x2A3644),
    BUTTON_HOVER("button-hover", "A button under the mouse.", 0x3A5A80),
    BUTTON_ACTIVE("button-active", "A button that is switched on (New, Emoji).", 0x2D4C70),
    OWN("own-message", "Text of the messages you sent.", 0xD2F5D8),
    INCOMING("incoming-message", "Text of the whispers you got.", 0xDCEBFF),
    OWN_BAR("own-message-bar", "The bar beside your messages.", 0x5BD16E),
    INCOMING_BAR("incoming-message-bar", "The bar beside whispers you got.", 0x4FA3FF),
    ACCENT("accent", "Global in the list, dialog and tooltip borders.", 0x4FA3FF),
    CONSOLE_TEXT("console-text", "Console lines: server and system messages, deaths, joins and leaves, notices (drawn at system-message-opacity). Transparent (alpha 0, the default) uses the text colour.", 0xE6EAF0, TEXT),
    CONSOLE_NOTE("console-note", "The Messenger's own notes in the console and in Global (\"Ran Meteor command\"). Transparent (alpha 0, the default) uses the dim-text colour.", 0x8A94A0, DIM),
    CONSOLE_NAME("console-name", "The Console entry in the list. Transparent (alpha 0, the default) uses the dim-text colour.", 0x8A94A0, DIM),
    WARNING("warning", "The \"sending...\" tag.", 0xE0C060),
    ERROR("error", "Errors and \"not sent\".", 0xE06868);

    private final String settingName;
    private final String description;
    private final int rgb;

    private final ThemeColor fallback;

    ThemeColor(String settingName, String description, int rgb) {
        this(settingName, description, rgb, null);
    }

    ThemeColor(String settingName, String description, int rgb, ThemeColor fallback) {
        this.settingName = settingName;
        this.description = description;
        this.rgb = rgb;
        this.fallback = fallback;
    }

    /**
     * The colour used while this one is "unset" (transparent, alpha 0, which is its default), or null if it always has
     * a value of its own. A user who changed the text colours so sees the same colours on the new parts of the window.
     */
    public ThemeColor fallback() {
        return fallback;
    }

    /** The alpha of the default: 0 (unset) for a colour with a fallback, else 255. */
    public int defaultAlpha() {
        return fallback == null ? 255 : 0;
    }

    public String settingName() {
        return settingName;
    }

    public String description() {
        return description;
    }

    /** The default colour, 0xRRGGBB. */
    public int defaultRgb() {
        return rgb;
    }

    /** The default as 0xAARRGGBB: opaque, or (a colour with a fallback) transparent, meaning unset. */
    public int defaultArgb() {
        return (defaultAlpha() << 24) | rgb;
    }
}

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
    WARNING("warning", "The \"sending...\" tag.", 0xE0C060),
    ERROR("error", "Errors and \"not sent\".", 0xE06868);

    private final String settingName;
    private final String description;
    private final int rgb;

    ThemeColor(String settingName, String description, int rgb) {
        this.settingName = settingName;
        this.description = description;
        this.rgb = rgb;
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

    /** The default as 0xAARRGGBB (opaque). */
    public int defaultArgb() {
        return 0xFF000000 | rgb;
    }
}

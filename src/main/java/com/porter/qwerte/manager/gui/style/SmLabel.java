package com.porter.qwerte.manager.gui.style;

import meteordevelopment.meteorclient.gui.themes.meteor.widgets.WMeteorLabel;

/**
 * A label whose title form is bold text of the normal size (the game's {@code section-sign l}), not Meteor's 1.25 times bigger
 * text, which the pixel font draws unevenly. {@link #get()} returns the text without the bold code. A small label is
 * drawn in the website's smaller text size (see {@link SmGuiTheme#fontPixel}).
 */
public class SmLabel extends WMeteorLabel {
    private static final String BOLD = "§l";
    private final boolean bold;

    public SmLabel(String text, boolean title) {
        this(text, title, false);
    }

    /**
     * @param bold  bold text
     * @param small the website's smaller text (Meteor's "title" flag, which this window uses for small text)
     */
    public SmLabel(String text, boolean bold, boolean small) {
        super(bold ? BOLD + text : text, small);
        this.bold = bold;
    }

    @Override
    public void set(String text) {
        super.set(bold ? BOLD + text : text);
    }

    @Override
    public String get() {
        String t = super.get();
        return bold && t.startsWith(BOLD) ? t.substring(2) : t;
    }
}

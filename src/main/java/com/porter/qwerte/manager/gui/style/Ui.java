package com.porter.qwerte.manager.gui.style;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** Small builders the pages share: muted and coloured labels, the page title line and the page-wide card. */
public final class Ui {
    private Ui() {
    }

    private static SmGuiTheme sm(GuiTheme theme) {
        return (SmGuiTheme) theme;
    }

    private static double lum(int argb) {
        return (0.299 * Palette.red(argb) + 0.587 * Palette.green(argb) + 0.114 * Palette.blue(argb)) / 255.0;
    }

    /** Of a badge's text and background colour, the one that reads better as plain text on the page's surface. */
    private static int ink(SmGuiTheme t, int fg, int bg) {
        double s = lum(t.palette().surface());
        return Math.abs(lum(fg) - s) >= Math.abs(lum(bg) - s) ? fg : bg;
    }

    /** Text colours for status words on a card, from the palette's ok, warn and error pairs. */
    public static Color ok(GuiTheme theme) {
        Palette p = sm(theme).palette();
        return sm(theme).color(ink(sm(theme), p.okFg(), p.okBg()));
    }

    public static Color warn(GuiTheme theme) {
        Palette p = sm(theme).palette();
        return sm(theme).color(ink(sm(theme), p.warnFg(), p.warnBg()));
    }

    public static Color err(GuiTheme theme) {
        Palette p = sm(theme).palette();
        return sm(theme).color(ink(sm(theme), p.errFg(), p.errBg()));
    }

    public static Color dim(GuiTheme theme) {
        return sm(theme).color(sm(theme).palette().muted());
    }

    /** A width a caller worked out by subtracting paddings can come out tiny or negative: wrapped text always gets some room. */
    public static double floor(GuiTheme theme, double width) {
        return Double.isNaN(width) ? sm(theme).scale(40) : Math.max(width, sm(theme).scale(40));
    }

    public static WLabel tint(GuiTheme theme, WLabel label, int argb) {
        label.color(sm(theme).color(argb));
        return label;
    }

    /** The website's smaller text in the muted colour (notes, hints, "last seen"). */
    public static WLabel muted(GuiTheme theme, String text) {
        return tint(theme, sm(theme).smallLabel(text), sm(theme).palette().muted());
    }

    public static WLabel muted(GuiTheme theme, String text, double maxWidth) {
        return tint(theme, sm(theme).smallLabel(text, floor(theme, maxWidth)), sm(theme).palette().muted());
    }

    /** Normal text that wraps at {@code maxWidth} physical pixels. */
    public static WLabel wrap(GuiTheme theme, String text, double maxWidth) {
        return sm(theme).wrapLabel(text, floor(theme, maxWidth));
    }

    public static WLabel bold(GuiTheme theme, String text) {
        return theme.label(text, true);
    }

    /** The page title line under the header ("orders", "activity - qwertttze"): small, muted, lower case. */
    public static WLabel pageTitle(WContainer parent, GuiTheme theme, String text) {
        WLabel l = muted(theme, text);
        parent.add(l);
        return l;
    }

    /** A card that fills the page width. */
    public static SmCard card(WContainer parent, String title) {
        SmCard c = new SmCard(title);
        parent.add(c).expandX();
        c.fill = PageRoom.body(c);
        return c;
    }

    public static SmCard card(WContainer parent, String title, SmCard.Style style) {
        SmCard c = card(parent, title);
        c.style = style;
        return c;
    }
}

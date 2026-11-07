package com.porter.qwerte.manager.gui.style;

/**
 * How big the window's text is, independent of the size of its slots. The game's font is 9 pixels tall at one pixel per
 * font pixel, which next to an 18 pixel slot is about twice the website's text (14 px beside a 36 px slot). Pure.
 */
public enum TextScale {
    /** Website-like: text about 0.39 of a slot tall. */
    AUTO("Auto", 0.75),
    SMALL("Small", 0.6),
    NORMAL("Normal", 1.0),
    LARGE("Large", 1.25);

    /** The website's small text (summary, hint, info row) is 12 px beside its 14 px body text. */
    public static final double SMALL_TEXT = 12.0 / 14.0;

    private final String label;
    private final double factor;

    TextScale(String label, double factor) {
        this.label = label;
        this.factor = factor;
    }

    public double factor() {
        return factor;
    }

    @Override
    public String toString() {
        return label;
    }

    /**
     * Physical pixels per font pixel for a GUI unit of {@code unit} pixels: {@code unit * factor}. Where that is 2 or more
     * it is rounded to a whole number, which keeps the pixel font crisp; below that a fraction is used, because the next
     * whole number down would be too small to read and the next up too big.
     */
    public static double physical(int unit, double factor) {
        double raw = Math.max(1, unit) * factor;
        return raw >= 2 ? Math.round(raw) : Math.max(1, raw);
    }

    /**
     * The scale Meteor's renderer should start a text batch with ({@code requested} is what it asked for: the theme's
     * scale for 1 for normal text, 1.25 times that for "title" text, which this window uses for small text) so that the
     * font is drawn at {@link #physical} pixels per font pixel. Meteor's batch scale is half of that.
     *
     * @param ref the theme's scale of 1 (half the unit)
     */
    public static double batchScale(double requested, double ref, int unit, double factor) {
        boolean small = requested > ref * 1.1;
        return physical(unit, small ? factor * SMALL_TEXT : factor) / 2.0;
    }
}

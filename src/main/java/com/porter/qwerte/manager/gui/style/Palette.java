package com.porter.qwerte.manager.gui.style;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The colours of one website theme (themes.css), as ARGB ints, plus the radius and border width in GUI pixels (the
 * website's CSS pixels halved: one in-game GUI pixel is two CSS pixels). Pure: no Minecraft or Meteor classes.
 */
public final class Palette {
    /** Token names in the order of {@link #of}. They are also the keys of dev/themes-palette.json. */
    public static final String[] TOKENS = {"bg", "surface", "surface2", "text", "muted", "accent", "onAccent", "border", "bar", "barText",
        "nav", "navText", "navActive", "navActiveBg", "trackOff", "chipBg", "okBg", "okFg", "warnBg", "warnFg", "errBg", "errFg", "hp", "food"};

    private final int[] c;
    /** The website's radius and border width in CSS pixels. */
    public final int radiusCss;
    public final int borderCss;
    /** Corner radius and border width in GUI pixels. */
    public final int radius;
    public final int borderWidth;
    /** Classic's vanilla bevel (light top-left, dark bottom-right) inside panels. */
    public final boolean bevel;
    /** Mac: the smoky panel glass and the three window dots (0 when the theme has none). */
    public final int glass;
    public final int dotRed;
    public final int dotYellow;
    public final int dotGreen;

    private Palette(int[] c, int radiusCss, int borderCss, boolean bevel, int glass, int dotRed, int dotYellow, int dotGreen) {
        this.c = c;
        this.radiusCss = radiusCss;
        this.borderCss = borderCss;
        this.radius = radiusCss / 2;
        this.borderWidth = Math.max(1, borderCss / 2);
        this.bevel = bevel;
        this.glass = glass;
        this.dotRed = dotRed;
        this.dotYellow = dotYellow;
        this.dotGreen = dotGreen;
    }

    /** @param radiusCss the website's --radius in CSS pixels; {@code values} follow {@link #TOKENS} */
    public static Palette of(int radiusCss, int borderCss, boolean bevel, String... values) {
        if (values.length != TOKENS.length) throw new IllegalArgumentException("expected " + TOKENS.length + " colours");
        int[] c = new int[values.length];
        for (int i = 0; i < c.length; i++) c[i] = parse(values[i]);
        return new Palette(c, radiusCss, borderCss, bevel, 0, 0, 0, 0);
    }

    /** The same palette with Mac's glass and window dots. */
    public Palette mac(String glass, String red, String yellow, String green) {
        return new Palette(c, radiusCss, borderCss, bevel, parse(glass), parse(red), parse(yellow), parse(green));
    }

    public boolean isMac() {
        return glass != 0;
    }

    public int bg() { return c[0]; }
    public int surface() { return c[1]; }
    public int surface2() { return c[2]; }
    public int text() { return c[3]; }
    public int muted() { return c[4]; }
    public int accent() { return c[5]; }
    public int onAccent() { return c[6]; }
    public int border() { return c[7]; }
    public int bar() { return c[8]; }
    public int barText() { return c[9]; }
    public int nav() { return c[10]; }
    public int navText() { return c[11]; }
    public int navActive() { return c[12]; }
    public int navActiveBg() { return c[13]; }
    public int trackOff() { return c[14]; }
    public int chipBg() { return c[15]; }
    public int okBg() { return c[16]; }
    public int okFg() { return c[17]; }
    public int warnBg() { return c[18]; }
    public int warnFg() { return c[19]; }
    public int errBg() { return c[20]; }
    public int errFg() { return c[21]; }
    public int hp() { return c[22]; }
    public int food() { return c[23]; }

    /** Every colour token by name, for tests. */
    public Map<String, Integer> tokens() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < TOKENS.length; i++) m.put(TOKENS[i], c[i]);
        return m;
    }

    // ---------- colour maths ----------

    /**
     * A CSS colour: {@code #rrggbb}, {@code #rgb}, {@code rgba(r,g,b,a)}, {@code rgb(r,g,b)} or {@code transparent}.
     * Anything else is an IllegalArgumentException.
     */
    public static int parse(String css) {
        if (css == null) throw new IllegalArgumentException("null colour");
        String s = css.trim().toLowerCase(Locale.ROOT);
        if (s.equals("transparent")) return 0;
        try {
            if (s.startsWith("#")) {
                String h = s.substring(1);
                if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
                if (h.length() != 6) throw new IllegalArgumentException("bad colour " + css);
                return 0xFF000000 | Integer.parseInt(h, 16);
            }
            if (s.startsWith("rgba(") || s.startsWith("rgb(")) {
                int open = s.indexOf('(');
                int close = s.lastIndexOf(')');
                if (close < open) throw new IllegalArgumentException("bad colour " + css);
                String[] p = s.substring(open + 1, close).split(",");
                if (p.length < 3 || p.length > 4) throw new IllegalArgumentException("bad colour " + css);
                int r = channel(p[0]);
                int g = channel(p[1]);
                int b = channel(p[2]);
                int a = p.length == 4 ? (int) Math.round(Math.max(0, Math.min(1, Double.parseDouble(p[3].trim()))) * 255) : 255;
                return a << 24 | r << 16 | g << 8 | b;
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad colour " + css, e);
        }
        throw new IllegalArgumentException("bad colour " + css);
    }

    private static int channel(String v) {
        return Math.max(0, Math.min(255, (int) Math.round(Double.parseDouble(v.trim()))));
    }

    /** {@code a} towards {@code b} by {@code t} (0 to 1), channel by channel including alpha. */
    public static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int out = 0;
        for (int shift = 24; shift >= 0; shift -= 8) {
            int x = a >>> shift & 0xFF;
            int y = b >>> shift & 0xFF;
            out |= (int) Math.round(x + (y - x) * t) << shift;
        }
        return out;
    }

    /** The colour with its alpha replaced by {@code alpha} (0 to 255). */
    public static int withAlpha(int argb, int alpha) {
        return Math.max(0, Math.min(255, alpha)) << 24 | (argb & 0xFFFFFF);
    }

    public static int alpha(int argb) {
        return argb >>> 24;
    }

    public static int red(int argb) {
        return argb >> 16 & 0xFF;
    }

    public static int green(int argb) {
        return argb >> 8 & 0xFF;
    }

    public static int blue(int argb) {
        return argb & 0xFF;
    }
}

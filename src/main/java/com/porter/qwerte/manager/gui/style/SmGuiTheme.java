package com.porter.qwerte.manager.gui.style;

import com.porter.qwerte.compat.ScaledText;
import com.porter.qwerte.manager.gui.frame.WOverlayLayer;
import com.porter.qwerte.manager.gui.widgets.TextWrap;
import meteordevelopment.meteorclient.gui.renderer.packer.GuiTexture;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.gui.utils.CharFilter;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WTooltip;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.renderer.text.VanillaTextRenderer;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The look of the Stash Window: Meteor's own widgets, drawn with the game's font, sized by the Minecraft GUI scale
 * (not Meteor's GUI scale) and coloured from a website palette. It overrides only methods whose signatures are the
 * same in every Meteor build this addon supports. It is never registered with Meteor's theme list, so it does not
 * change how any other Meteor screen looks.
 *
 * <p>One "unit" is the number of physical pixels in one GUI pixel (a whole number). {@link #scale} turns the sizes
 * existing tab code writes in Meteor pixels into units, so at GUI scale 2 everything is the size it always was.
 */
public class SmGuiTheme extends MeteorGuiTheme {
    private final IntSupplier unitSetting;
    private final Supplier<WindowTheme> themeSetting;
    private final Supplier<TextScale> textSetting;
    private final TextRenderer text;
    private final Map<Integer, Color> colors = new HashMap<>();
    private WindowTheme applied;
    private Palette palette = WindowTheme.DEFAULT.palette();

    /** The frame's overlay (open lists, hover card), set by the window. Null outside it. */
    public WOverlayLayer overlay;

    /**
     * @param unitSetting 0 for the Minecraft GUI scale, else a fixed number of physical pixels per GUI pixel
     * @param textSetting how big the text is next to the slots
     */
    public SmGuiTheme(IntSupplier unitSetting, Supplier<WindowTheme> themeSetting, Supplier<TextScale> textSetting) {
        super();
        this.unitSetting = unitSetting;
        this.themeSetting = themeSetting;
        this.textSetting = textSetting;
        this.text = new ScaledText(() -> scale(1), this::unit, textSetting);
        apply(themeSetting.get());
    }

    // ---------- units ----------

    /** Physical pixels per GUI pixel; always at least 1. */
    public int unit() {
        int fixed = unitSetting.getAsInt();
        if (fixed > 0) return fixed;
        double s = mc.getWindow().getGuiScale();
        return Math.max(1, (int) Math.round(s));
    }

    /** {@code guiPixels} in physical pixels. */
    public double g(double guiPixels) {
        return guiPixels * unit();
    }

    /** The unit of whatever theme a widget has: this one's, or 2 (the size Meteor's own scale 1 has). */
    public static int unitOf(meteordevelopment.meteorclient.gui.GuiTheme theme) {
        return theme instanceof SmGuiTheme t ? t.unit() : 2;
    }

    /** The screen's size in GUI pixels. */
    public int screenW() {
        return Math.max(1, Utils.getWindowWidth() / unit());
    }

    public int screenH() {
        return Math.max(1, Utils.getWindowHeight() / unit());
    }

    @Override
    public double scale(double value) {
        return value * unit() / 2.0;
    }

    /**
     * Physical pixels per font pixel: the text setting at this unit. The "title" flag means the website's smaller text
     * here (summary, hint, info row), not Meteor's bigger one: titles are bold normal text, drawn by {@link SmLabel}.
     */
    public double fontPixel(boolean small) {
        double f = textSetting.get().factor();
        return TextScale.physical(unit(), small ? f * TextScale.SMALL_TEXT : f);
    }

    @Override
    public double textWidth(String text, int length, boolean title) {
        if (text.isEmpty()) return 0;
        String s = length < text.length() ? text.substring(0, length) : text;
        return mc.font.width(s) * fontPixel(title);
    }

    @Override
    public double textHeight(boolean title) {
        return mc.font.lineHeight * fontPixel(title);
    }

    /** Width of the website's smaller text. */
    public double smallWidth(String text) {
        return textWidth(text, text.length(), true);
    }

    public double smallHeight() {
        return textHeight(true);
    }

    @Override
    public TextRenderer textRenderer() {
        return text;
    }

    // ---------- palette ----------

    public Palette palette() {
        return palette;
    }

    public WindowTheme windowTheme() {
        return applied;
    }

    /** A cached Meteor colour for an ARGB int. Never change the returned object. */
    public Color color(int argb) {
        Color c = colors.get(argb);
        if (c == null) {
            c = new Color(Palette.red(argb), Palette.green(argb), Palette.blue(argb), Palette.alpha(argb));
            colors.put(argb, c);
        }
        return c;
    }

    /** True while an open list covers the pointer: widgets under it must not look hovered. */
    public boolean hoverBlocked() {
        return disableHoverColor;
    }

    /** Takes over the module's theme setting now, if it changed (a screen calls this before it builds widgets). */
    public void sync() {
        WindowTheme wanted = themeSetting.get();
        if (wanted != applied) apply(wanted);
    }

    @Override
    public void beforeRender() {
        super.beforeRender();
        sync();
        if (overlay != null && overlay.isOpen()) disableHoverColor = true;
    }

    /** Copies a palette into the colour settings Meteor's own widgets read (the ones this theme doesn't draw itself). */
    public void apply(WindowTheme theme) {
        applied = theme;
        palette = theme.palette();
        Palette p = palette;
        int text = p.text();
        int bg = p.surface2();
        put("accent-color", p.accent());
        put("checkbox-color", p.accent());
        put("text-color", text);
        put("text-secondary-text-color", p.muted());
        put("text-highlight-color", Palette.withAlpha(p.accent(), 110));
        put("title-text-color", text);
        put("logged-in-text-color", p.okFg());
        put("placeholder-color", Palette.withAlpha(p.muted(), 170));
        put("background-color", bg);
        put("hovered-background-color", Palette.mix(bg, text, 0.10));
        put("pressed-background-color", Palette.mix(bg, text, 0.20));
        put("module-background-color", bg);
        put("outline-color", p.border());
        put("hovered-outline-color", Palette.mix(p.border(), p.accent(), 0.5));
        put("pressed-outline-color", p.accent());
        put("separator-text-color", text);
        put("separator-center-color", p.muted());
        put("separator-edges-color", Palette.withAlpha(p.muted(), 150));
        put("scrollbar-color", p.trackOff());
        put("hovered-scrollbar-color", Palette.mix(p.trackOff(), text, 0.25));
        put("pressed-scrollbar-color", p.accent());
        put("slider-handle-color", p.accent());
        put("hovered-slider-handle-color", Palette.mix(p.accent(), text, 0.15));
        put("pressed-slider-handle-color", Palette.mix(p.accent(), text, 0.30));
        put("slider-left-color", p.accent());
        put("slider-right-color", p.trackOff());
    }

    @SuppressWarnings("unchecked")
    private void put(String name, int argb) {
        Setting<?> s = settings.get(name);
        if (s == null || !(s.get() instanceof SettingColor)) return; // a setting a Meteor build doesn't have: skipped
        ((Setting<SettingColor>) s).set(new SettingColor(Palette.red(argb), Palette.green(argb), Palette.blue(argb), Palette.alpha(argb)));
    }

    // ---------- widgets ----------

    @Override
    public WLabel label(String text, boolean title, double maxWidth) {
        if (maxWidth == 0 && !text.contains("\n")) return w(new SmLabel(text, title));
        if (title && maxWidth > 0) {
            // Wrap first, then make each line bold, so the bold code is not lost on the later lines
            String bold = "\u00a7l";
            double limit = scale(maxWidth);
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (String part : text.split("\n", -1)) {
                for (String row : TextWrap.wrap(part, limit, s -> textWidth(bold + s))) lines.add(bold + row);
            }
            return super.label(String.join("\n", lines), false, maxWidth);
        }
        return super.label(text, false, maxWidth);
    }

    /** A label in the website's smaller text (the index line, hints). */
    public WLabel smallLabel(String text) {
        return w(new SmLabel(text, false, true));
    }

    /** Normal-size text wrapped to {@code maxWidth} physical pixels, long words broken. */
    public WLabel wrapLabel(String text, double maxWidth) {
        return w(new WWrapLabel(text, false, maxWidth));
    }

    /** Wrapped text in the website's smaller size; {@code maxWidth} in physical pixels. */
    public WLabel smallLabel(String text, double maxWidth) {
        if (maxWidth <= 0) return smallLabel(text);
        return w(new WWrapLabel(text, true, maxWidth));
    }

    @Override
    protected WButton button(String text, GuiTexture texture) {
        return w(new SmButton(text, texture));
    }

    /** A button in the accent colour (the main action). */
    public SmButton primaryButton(String text) {
        SmButton b = w(new SmButton(text, null));
        b.primary = true;
        return b;
    }

    @Override
    public WCheckbox checkbox(boolean checked) {
        return w(new SmCheckbox(checked));
    }

    @Override
    public WTextBox textBox(String text, String placeholder, CharFilter filter, Class<? extends WTextBox.Renderer> renderer) {
        return w(new SmTextBox(text, placeholder, filter, renderer));
    }

    @Override
    public <T> WDropdown<T> dropdown(T[] values, T value) {
        return w(new SmSelect<>(values, value));
    }

    @Override
    public WTooltip tooltip(String text) {
        return w(new SmTooltip(text));
    }
}

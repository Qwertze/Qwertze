package com.porter.qwerte.manager.gui.style;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;

/**
 * The website toggle (.toggle): a small track with a knob and the word ON or OFF. The track is the accent colour
 * when on. action runs after the click; read the field on for the new state (it has already flipped).
 */
public class SmToggle extends WPressable {
    public boolean on;
    /** Greyed, and ignores clicks. */
    public boolean disabled;
    private final String onText;
    private final String offText;

    public SmToggle(boolean on) {
        this(on, "ON", "OFF");
    }

    public SmToggle(boolean on, String onText, String offText) {
        this.on = on;
        this.onText = onText;
        this.offText = offText;
    }

    @Override
    protected void onPressed(int button) {
        if (!disabled) on = !on;
    }

    private double trackW() {
        return theme.scale(28);
    }

    private double trackH() {
        return theme.scale(16);
    }

    @Override
    protected void onCalculateSize() {
        double textW = Math.max(theme.textWidth(onText), theme.textWidth(offText));
        width = theme.scale(8) + trackW() + theme.scale(6) + textW + theme.scale(10);
        height = Math.max(trackH(), theme.textHeight()) + 2 * theme.scale(4);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        double u = t.unit();
        boolean hot = mouseOver && !disabled && !t.hoverBlocked();
        int fill = hot ? Palette.mix(p.surface2(), p.text(), 0.10) : p.surface2();
        double bw = p.borderWidth * u;
        double radius = p.radius == 0 ? 0 : height / 2;
        Paint.panel(renderer, t, x, y, width, height, radius, bw, fill, p.border());
        double tx = x + theme.scale(8);
        double th = trackH();
        double ty = y + (height - th) / 2;
        double tr = p.radius == 0 ? 0 : th / 2;
        int track = on ? p.accent() : p.trackOff();
        if (disabled) track = Palette.withAlpha(track, 140);
        Paint.panel(renderer, t, tx, ty, trackW(), th, tr, u, track, p.border());
        double knob = th - 4 * u;
        double kx = on ? tx + trackW() - knob - 2 * u : tx + 2 * u;
        Paint.roundRect(renderer, t, kx, ty + 2 * u, knob, knob, p.radius == 0 ? 0 : knob / 2, on ? p.onAccent() : p.surface());
        int ink = disabled ? p.muted() : p.text();
        renderer.text(on ? onText : offText, tx + trackW() + theme.scale(6), y + (height - theme.textHeight()) / 2, t.color(ink), false);
    }
}

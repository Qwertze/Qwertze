package com.porter.qwerte.manager.gui.style;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;

/**
 * The website's pill (.chip-label, .badge, "Online", "not set"): small text on a rounded tag with a border. Kind picks the
 * colours. set(text) changes the text; setKind the colours (neither allocates). Nothing is drawn, and no room taken, while
 * the text is empty.
 */
public class SmPill extends SmLabel {
    public enum Kind {
        /** chip-bg with the text colour (the default badge). */
        NEUTRAL,
        OK,
        WARN,
        ERROR,
        /** The accent colour (the active nav pill, "Add user"). */
        ACCENT
    }

    private Kind kind;

    public SmPill(String text, Kind kind) {
        super(text, false, true);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    private double padX() {
        return theme.scale(8);
    }

    private double padY() {
        return theme.scale(1);
    }

    @Override
    protected void onCalculateSize() {
        super.onCalculateSize();
        if (!text.isEmpty()) {
            width += 2 * padX();
            height += 2 * padY();
        }
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (text.isEmpty()) return;
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        double u = t.unit();
        int fill;
        int ink;
        int border;
        switch (kind) {
            case OK -> {
                fill = p.okBg();
                ink = p.okFg();
                border = p.okBg();
            }
            case WARN -> {
                fill = p.warnBg();
                ink = p.warnFg();
                border = p.warnBg();
            }
            case ERROR -> {
                fill = p.errBg();
                ink = p.errFg();
                border = p.errBg();
            }
            case ACCENT -> {
                fill = p.accent();
                ink = p.onAccent();
                border = p.accent();
            }
            default -> {
                fill = p.chipBg();
                ink = p.text();
                border = p.border();
            }
        }
        double radius = p.radius == 0 ? 0 : height / 2;
        Paint.panel(renderer, t, x, y, width, height, radius, p.borderWidth * u, fill, border);
        renderer.text(text, x + padX(), y + padY(), t.color(ink), true);
    }
}

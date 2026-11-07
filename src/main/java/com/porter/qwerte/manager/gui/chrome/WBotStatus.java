package com.porter.qwerte.manager.gui.chrome;

import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;

/**
 * The website's bot status: a round dot (green while the bot is online, red when not) and a text such as
 * "Bot online, last seen 5 s ago". The text can change every second; the widget asks for a new layout only when its
 * width changes.
 */
public class WBotStatus extends WWidget {
    private String text;
    private boolean online;

    public WBotStatus(String text, boolean online) {
        this.text = text;
        this.online = online;
    }

    public void set(String text, boolean online) {
        this.online = online;
        if (!text.equals(this.text)) {
            int before = (int) Math.round(textW(this.text));
            this.text = text;
            if ((int) Math.round(textW(text)) != before) invalidate();
        }
    }

    private double textW(String s) {
        return theme.textWidth(s, s.length(), false);
    }

    private double dot() {
        return 5 * SmGuiTheme.unitOf(theme);
    }

    @Override
    protected void onCalculateSize() {
        double gap = 3 * SmGuiTheme.unitOf(theme);
        width = dot() + gap + textW(text);
        height = Math.max(dot(), theme.textHeight());
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        SmGuiTheme t = (SmGuiTheme) theme;
        Palette p = t.palette();
        double u = t.unit();
        double d = dot();
        int fill = online ? p.okBg() : p.errBg();
        int edge = online ? p.okFg() : p.errFg();
        double cy = y + height / 2;
        Paint.dot(renderer, t, x + d / 2, cy, d / 2, edge);
        Paint.dot(renderer, t, x + d / 2, cy, d / 2 - u, fill);
        renderer.text(text, x + d + 3 * u, y + (height - t.textHeight()) / 2, t.color(p.barText()), false);
    }
}

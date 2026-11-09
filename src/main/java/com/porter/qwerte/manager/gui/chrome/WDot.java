package com.porter.qwerte.manager.gui.chrome;

import com.porter.qwerte.manager.gui.style.Paint;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;

/** A small round status dot (the website's online/offline dot) that sits in a text row; {@link #set} changes its colour. */
public class WDot extends WWidget {
    private int argb;

    public WDot(int argb) {
        this.argb = argb;
    }

    public void set(int argb) {
        this.argb = argb;
    }

    @Override
    protected void onCalculateSize() {
        width = theme.scale(8);
        height = theme.textHeight();
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        SmGuiTheme t = (SmGuiTheme) theme;
        Paint.dot(renderer, t, x + width / 2, y + height / 2, theme.scale(3), argb);
    }
}

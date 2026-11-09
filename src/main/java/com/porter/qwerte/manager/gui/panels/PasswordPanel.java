package com.porter.qwerte.manager.gui.panels;

import com.porter.qwerte.manager.gui.widgets.MaskedRenderer;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.ArrayList;
import java.util.List;

/** Shared bits of the password panels: masked boxes, a notice line, and wiping what was typed. */
abstract class PasswordPanel {
    private static final Color ERROR = new Color(255, 110, 110);

    protected GuiTheme theme;
    private WLabel notice;
    private final List<WTextBox> secrets = new ArrayList<>();

    /** A labelled password box. It has no placeholder: that would be drawn masked too. */
    protected WTextBox secret(WContainer parent, String label) {
        parent.add(theme.label(label));
        WTextBox box = parent.add(theme.textBox("", null, MaskedRenderer.ANY_CHAR, MaskedRenderer.class)).minWidth(theme.scale(300)).expandX().widget();
        secrets.add(box);
        return box;
    }

    protected WTextBox plain(WContainer parent, String label, String text) {
        parent.add(theme.label(label));
        return parent.add(theme.textBox(text)).minWidth(theme.scale(300)).expandX().widget();
    }

    protected void addNotice(WContainer parent, String message) {
        // wraps to the room the panel has, so a long server message never runs past its edge
        notice = parent.add(com.porter.qwerte.manager.gui.style.Ui.wrap(theme, "", Math.min(theme.scale(420), com.porter.qwerte.manager.gui.style.PageRoom.width(parent, 0)))).expandX().widget();
        notice.color(ERROR);
        setNotice(message);
    }

    public void setNotice(String message) {
        if (notice != null) notice.set(message == null ? "" : message);
    }

    /** Empties every password box. */
    public void clearSecrets() {
        for (WTextBox box : secrets) box.set("");
    }

    /** What Enter does. */
    public abstract void submit();
}

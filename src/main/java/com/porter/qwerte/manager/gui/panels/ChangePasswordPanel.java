package com.porter.qwerte.manager.gui.panels;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

/** The must-change-password form: the password you were given, then a new one twice. */
public final class ChangePasswordPanel extends PasswordPanel {
    /** Gets the current and the new password. */
    public interface Handler {
        void change(String current, String next);
    }

    private WTextBox current;
    private WTextBox next;
    private WTextBox again;
    private int minLength;
    private Handler handler;

    public void build(WContainer parent, GuiTheme theme, int minLength, String message, Handler handler, Runnable onLogout) {
        this.theme = theme;
        this.minLength = minLength;
        this.handler = handler;
        parent.add(theme.label("Choose a new password", true));
        parent.add(com.porter.qwerte.manager.gui.style.Ui.wrap(theme, "Your account must change its password before anything else works.", Math.min(theme.scale(420), com.porter.qwerte.manager.gui.style.PageRoom.width(parent, 0)))).expandX();
        current = secret(parent, "The password you were given");
        next = secret(parent, "New password (at least " + minLength + " characters)");
        again = secret(parent, "New password again");
        WHorizontalList buttons = parent.add(theme.horizontalList()).widget();
        WButton save = buttons.add(theme.button("Change password")).widget();
        save.action = this::submit;
        WButton logout = buttons.add(theme.button("Log out")).widget();
        logout.action = onLogout;
        addNotice(parent, message);
    }

    @Override
    public void submit() {
        if (current == null) return;
        String c = current.get();
        String n = next.get();
        String a = again.get();
        if (c.isEmpty()) {
            setNotice("Enter the password you were given.");
            return;
        }
        if (n.length() < minLength) {
            setNotice("The new password needs at least " + minLength + " characters.");
            return;
        }
        if (!n.equals(a)) {
            setNotice("The two new passwords are different.");
            return;
        }
        if (n.equals(c)) {
            setNotice("Choose a new password, not the one you were given.");
            return;
        }
        clearSecrets();
        handler.change(c, n);
    }
}

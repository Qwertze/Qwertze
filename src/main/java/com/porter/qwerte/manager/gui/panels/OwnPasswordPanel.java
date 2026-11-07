package com.porter.qwerte.manager.gui.panels;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

/**
 * "Change your own password" inside the Settings tab: the current password, then a new one twice (the website's password
 * window). Unlike {@link ChangePasswordPanel} nothing forces it, so it sits inline and has no log out button.
 */
public final class OwnPasswordPanel extends PasswordPanel {
    /** Gets the current and the new password. */
    public interface Handler {
        void change(String current, String next);
    }

    private WTextBox current;
    private WTextBox next;
    private WTextBox again;
    private int minLength;
    private Handler handler;

    public void build(WContainer parent, GuiTheme theme, int minLength, String message, Handler handler) {
        this.theme = theme;
        this.minLength = minLength;
        this.handler = handler;
        parent.add(theme.label("Change your password", true));
        current = secret(parent, "Current password");
        next = secret(parent, "New password (at least " + minLength + " characters)");
        again = secret(parent, "New password again");
        WHorizontalList buttons = parent.add(theme.horizontalList()).widget();
        WButton save = buttons.add(theme.button("Change password")).widget();
        save.action = this::submit;
        addNotice(parent, message);
    }

    @Override
    public void submit() {
        if (current == null) return;
        String c = current.get();
        String n = next.get();
        String a = again.get();
        if (c.isEmpty()) {
            setNotice("Enter your current password.");
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
            setNotice("Choose a new password, not the current one.");
            return;
        }
        clearSecrets();
        handler.change(c, n);
    }
}

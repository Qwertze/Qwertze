package com.porter.qwerte.manager.gui.panels;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

import java.util.function.Consumer;

/**
 * Asks for the password again before a sensitive action (the server answered "confirm password"). After a right
 * password the action is tried once more.
 */
public final class ConfirmPanel extends PasswordPanel {
    private WTextBox password;
    private Consumer<String> onConfirm;

    public void build(WContainer parent, GuiTheme theme, String why, String message, Consumer<String> onConfirm, Runnable onCancel) {
        this.theme = theme;
        this.onConfirm = onConfirm;
        parent.add(theme.label("Confirm your password", true));
        parent.add(com.porter.qwerte.manager.gui.style.Ui.wrap(theme, why, Math.min(theme.scale(420), com.porter.qwerte.manager.gui.style.PageRoom.width(parent, 0)))).expandX();
        password = secret(parent, "Password");
        WHorizontalList buttons = parent.add(theme.horizontalList()).widget();
        WButton ok = buttons.add(theme.button("Confirm")).widget();
        ok.action = this::submit;
        WButton cancel = buttons.add(theme.button("Cancel")).widget();
        cancel.action = onCancel;
        addNotice(parent, message);
    }

    @Override
    public void submit() {
        if (password == null) return;
        String pass = password.get();
        password.set("");
        if (pass.isEmpty()) {
            setNotice("Enter your password.");
            return;
        }
        onConfirm.accept(pass);
    }
}

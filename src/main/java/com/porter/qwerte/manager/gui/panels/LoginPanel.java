package com.porter.qwerte.manager.gui.panels;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

import java.util.function.BiConsumer;

/**
 * The login form. The password box is emptied the moment the form is submitted, so the password is only ever in the
 * request on its way out.
 */
public final class LoginPanel extends PasswordPanel {
    private WTextBox user;
    private WTextBox password;
    private BiConsumer<String, String> onLogin;

    /**
     * @param serverText what to show as the server (hidden when streaming)
     * @param onLogin    gets the user name and the password
     */
    public void build(WContainer parent, GuiTheme theme, String serverText, String lastUser, String message, BiConsumer<String, String> onLogin) {
        this.theme = theme;
        this.onLogin = onLogin;
        parent.add(theme.label("Log in to the Stash Manager", true));
        parent.add(com.porter.qwerte.manager.gui.style.Ui.wrap(theme, "Server: " + serverText, Math.min(theme.scale(420), com.porter.qwerte.manager.gui.style.PageRoom.width(parent, 0))));
        user = plain(parent, "User name (empty for the owner)", lastUser == null ? "" : lastUser);
        password = secret(parent, "Password");
        WButton go = parent.add(theme.button("Log in")).widget();
        go.action = this::submit;
        addNotice(parent, message);
    }

    @Override
    public void submit() {
        if (password == null) return;
        String name = user.get();
        String pass = password.get();
        password.set("");
        onLogin.accept(name, pass);
    }
}

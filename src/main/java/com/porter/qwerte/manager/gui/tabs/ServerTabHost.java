package com.porter.qwerte.manager.gui.tabs;

import com.google.gson.JsonObject;
import com.porter.qwerte.manager.gui.BotDirectory;
import com.porter.qwerte.manager.model.AuthState;
import com.porter.qwerte.manager.model.DisplayPolicy;
import com.porter.qwerte.manager.model.NavPage;
import com.porter.qwerte.manager.model.StashRef;
import com.porter.qwerte.manager.net.ApiResult;
import com.porter.qwerte.manager.net.SmClient;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What the server-only tabs (Map, Settings, Proxy, Bots) need from the window. They never exist in local mode, so everything
 * here is the server's: a client, the account, and the shared bot list.
 */
public interface ServerTabHost {
    SmClient client();

    /** Never null while a tab is shown. */
    AuthState auth();

    DisplayPolicy policy();

    GuiGraphics graphics();

    /** Runs after the current event (safe for rebuilding the widget that sent it). */
    void later(Runnable r);

    /** A message that stays up for a few seconds. */
    void notice(String text);

    /** The window was closed (answers still on their way are ignored). */
    boolean closed();

    BotDirectory bots();

    /** The stashes the account may see (for the permission scope lists). */
    List<StashRef> stashes();

    /** POST that asks for the password when the server wants it ("confirm password"), then tries once more. */
    void write(String path, JsonObject body, Consumer<ApiResult> done);

    /** GET that asks for the password when the server wants it, then tries once more. */
    void read(String path, Map<String, String> query, Consumer<ApiResult> done);

    /** Shows the password prompt in place of the tab; {@code retry} runs once the password was accepted. */
    void confirm(String why, Runnable retry);

    /** The answer was not a success the tab can handle itself: login lost, must-change, no permission... */
    void failed(ApiResult r);

    /** Asks for the login state now (permissions may have changed). */
    void refreshAuth();

    /**
     * Goes to a page of the window. {@code botId} (may be null) is picked as the bot first. A page without an in-game tab yet
     * shows the website note; a page the account may not see is ignored.
     */
    void openPage(NavPage page, String botId);

    /** The stash picked in the window, or null. */
    StashRef selectedStash();

    /** Picks the stash and goes to the Stash page. */
    void showStash(StashRef stash);

    /** Asks for the stash list now (a stash was changed, for example released). */
    void refreshStashes();

    /** Rebuilds the window's widgets from the tabs' state. */
    void rebuild();

    /** Ends this session on the server and shows the login. */
    void logout();

    /** Ends every session of this account (the owner: of every account). */
    void logoutEverywhere();

    /** The window's theme (kept in the module, not on the server). */
    com.porter.qwerte.manager.gui.style.WindowTheme windowTheme();

    void setWindowTheme(com.porter.qwerte.manager.gui.style.WindowTheme theme);

    /** Text for a failed answer, like the window's own ("Too many attempts. Try again in 5 s."). */
    String describe(ApiResult r, String fallback);
}

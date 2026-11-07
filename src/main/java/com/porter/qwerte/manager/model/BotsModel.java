package com.porter.qwerte.manager.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The text of the Bots page, as the website's {@code botDoing}, {@code conflictText} and {@code renderDashBots} say it. Pure.
 */
public final class BotsModel {
    private BotsModel() {
    }

    /** The website shows a stash-name conflict for a day. */
    public static final long CONFLICT_SHOW_MS = 24L * 60 * 60 * 1000;

    /** What a stash's bot is doing; {@code warn} marks the lines the website colours as warnings. */
    public record Doing(String text, boolean warn) {
    }

    public static Doing doing(StashRef s) {
        BotInfo b = s.bot();
        if (!b.activeOrder().isEmpty()) return new Doing(b.activeOrder(), false);
        if (!b.orderBlocker().isEmpty()) return new Doing("Can't take orders: " + b.orderBlocker(), true);
        if (s.rescanPending() >= 0) return new Doing("Rescan requested", false);
        if (!s.rescanBlocked().isEmpty()) return new Doing("Can't rescan: " + Text.sanitize(s.rescanBlocked(), 80), true);
        if (s.containersStale() > 0) {
            return new Doing("Re-reading containers: " + Text.grouped(Math.max(0, s.containersTotal() - s.containersStale())) + " / "
                + Text.grouped(Math.max(0, s.containersTotal())), false);
        }
        return new Doing("Idle", false);
    }

    /** The owner shown in the table: the owner, else the player, else "(no owner)". */
    public static String who(StashRef s) {
        String w = !s.owner().isEmpty() ? s.owner() : s.player();
        return w.isEmpty() ? "(no owner)" : Text.sanitize(w, 32);
    }

    /** The conflict line for a stash, or "" when there is none or it is older than a day. */
    public static String conflict(StashRef s, long now) {
        return conflict(s, now, false);
    }

    /** As {@link #conflict(StashRef, long)}; {@code hide} (streamer mode) leaves out the other player's name and the stash name. */
    public static String conflict(StashRef s, long now, boolean hide) {
        BotInfo b = s.bot();
        if (b.conflictAt() <= 0 || now - b.conflictAt() > CONFLICT_SHOW_MS) return "";
        if (hide) return "Another bot tried to use this stash. It was refused: give that bot its own stash name.";
        return "Another bot (" + Text.sanitize(b.conflictPlayer(), 32) + ") tried to use stash '" + Text.sanitize(s.stash(), 48)
            + "'. It was refused: give that bot its own stash name.";
    }

    /** The stash name for the table; "(hidden)" in streamer mode. */
    public static String stashName(StashRef s, boolean hide) {
        return hide ? "(hidden)" : Text.sanitize(s.stash(), 48);
    }

    /** The notice after a successful release; streamer mode leaves the stash name out. */
    public static String releasedNotice(StashRef s, boolean hide) {
        return hide ? "Released the stash." : "Released '" + Text.sanitize(s.stash(), 48) + "'.";
    }

    /** Where the bot's delivery container is ("x, y, z"), or "not set". */
    public static String delivery(StashRef s) {
        return s.bot().deliveryAt().isEmpty() ? "not set" : s.bot().deliveryAt();
    }

    /** True when the table offers Release for the stash: it has an owner and the account may link. */
    public static boolean canRelease(StashRef s, AuthState auth) {
        return !s.owner().isEmpty() && auth != null && auth.can("link");
    }

    /** The warning shown with the second click of Release. */
    public static String releaseWarning(StashRef s) {
        return releaseWarning(s, false);
    }

    /** As {@link #releaseWarning(StashRef)}; {@code hide} (streamer mode) says "this bot" and leaves out the stash name. */
    public static String releaseWarning(StashRef s, boolean hide) {
        if (hide) {
            return "Release this stash from its bot? Another bot can then take it over by using this stash name. "
                + "If this bot still runs with this stash name, it takes it back on its next heartbeat.";
        }
        String owner = Text.sanitize(s.owner(), 32);
        return "Release '" + Text.sanitize(s.stash(), 48) + "' from " + owner + "? Another bot can then take it over by using this stash name. "
            + "If " + owner + " still runs with this stash name, it takes it back on its next heartbeat.";
    }

    /** One of the pages a bot card jumps to. */
    public record Jump(NavPage page, String cap, String label) {
    }

    /** The website's card buttons, in its order: shown when the bot has the capability. */
    public static final List<Jump> JUMPS = List.of(
        new Jump(NavPage.ACTIVITY, "activity", "Activity"),
        new Jump(NavPage.SETTINGS, "settings", "Settings"),
        new Jump(NavPage.PROXY, "proxy", "Proxy"),
        new Jump(NavPage.CONSOLE, "console", "Console"));

    /** The jumps a card offers for {@code bot}: it has the capability and the account may see the page. */
    public static List<Jump> jumps(BotList.Bot bot, AuthState auth) {
        List<NavPage> allowed = NavPage.visible(auth, true, true);
        List<Jump> out = new ArrayList<>();
        for (Jump j : JUMPS) if (bot.hasCap(j.cap()) && allowed.contains(j.page())) out.add(j);
        return out;
    }

    /** "Plugin qs 1.2 · proxy 1.0 (MC 1.21.4) · id ct1" as the card's meta line says it. */
    public static String meta(BotList.Bot b) {
        return "Plugin " + (b.pluginId().isEmpty() ? "?" : b.pluginId()) + (b.pluginVersion().isEmpty() ? "" : " " + b.pluginVersion())
            + " · proxy " + (b.proxyVersion().isEmpty() ? "?" : b.proxyVersion()) + (b.proxyMc().isEmpty() ? "" : " (MC " + b.proxyMc() + ")")
            + " · id " + Text.sanitize(b.botId(), 40);
    }
}

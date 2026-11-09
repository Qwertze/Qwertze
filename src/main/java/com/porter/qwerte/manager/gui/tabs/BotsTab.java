package com.porter.qwerte.manager.gui.tabs;

import com.google.gson.JsonObject;
import com.porter.qwerte.manager.gui.chrome.WDot;
import com.porter.qwerte.manager.gui.chrome.WFlow;
import com.porter.qwerte.manager.gui.style.PageRoom;
import com.porter.qwerte.manager.gui.style.SmButton;
import com.porter.qwerte.manager.gui.style.SmCard;
import com.porter.qwerte.manager.gui.style.SmGrid;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import com.porter.qwerte.manager.gui.style.SmPill;
import com.porter.qwerte.manager.gui.style.SmTable;
import com.porter.qwerte.manager.gui.style.Ui;
import com.porter.qwerte.manager.model.BotList;
import com.porter.qwerte.manager.model.BotsModel;
import com.porter.qwerte.manager.model.DisplayPolicy;
import com.porter.qwerte.manager.model.StashRef;
import com.porter.qwerte.manager.model.Text;
import com.porter.qwerte.manager.net.ApiError;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * The Bots tab: the website's Bots page. One card per dashboard bot (state, what it is doing, versions, its stashes, and
 * buttons that open its Activity, Settings, Proxy and Console pages), then a table of every stash with its owner, state,
 * delivery container, open orders and a Release button (two clicks).
 *
 * <p>The bot list comes from the window's {@link ServerTabHost#bots()} (polled while this tab asks for it); the stash table
 * from {@link ServerTabHost#stashes()}, which the window polls. The widgets are rebuilt when the structure changes (a bot or stash
 * appears, goes online or offline, ...); the texts that change all the time (what a bot is doing, when it was last seen) are
 * written into their labels in place. No rebuild happens while a Release is armed.
 *
 * <p>Streamer display hides the owner, the stash name, the server and the other player's name, in the table, the warnings and the
 * notices.
 */
public final class BotsTab {
    private static final long ARM_MS = 4_000;
    private static final long SIGNATURE_MS = 1_000;
    private static final int MAX_STASH_ROWS = 200;

    private final ServerTabHost host;
    private GuiTheme theme;

    private String signature = "";
    private long nextSignature;
    private String armedKey = "";
    private long armedUntil;
    private boolean releasing;
    private int token;
    /** Writes the current values into the labels the last build made. */
    private final List<LongConsumer> live = new ArrayList<>();

    public BotsTab(ServerTabHost host) {
        this.host = host;
    }

    /** The window closed or the session ended. */
    public void reset() {
        token++;
        releasing = false;
        armedKey = "";
        armedUntil = 0;
        signature = "";
        nextSignature = 0;
        live.clear();
    }

    public void tick(long now) {
        if (host.closed()) return;
        host.bots().tick(now, true);
        if (!armedKey.isEmpty() && now > armedUntil) {
            armedKey = "";
            host.later(host::rebuild);
            return;
        }
        if (now < nextSignature) return;
        nextSignature = now + SIGNATURE_MS;
        if (!computeSignature().equals(signature)) {
            // not under a click: the second click of Release must find its button
            if (armedKey.isEmpty()) host.later(host::rebuild);
        } else {
            for (LongConsumer u : live) u.accept(now);
        }
    }

    /** Everything the page shows, as one string, so the tab is rebuilt only when something changed. */
    private String computeSignature() {
        StringBuilder sb = new StringBuilder();
        DisplayPolicy policy = host.policy();
        sb.append(policy.streamer()).append(host.bots().selected()).append(host.bots().loaded()).append(releasing);
        for (BotList.Bot b : host.bots().list().bots()) {
            // the summary text and the last-seen age change all the time: they are updated in place
            sb.append('|').append(b.botId()).append(b.online()).append(b.player()).append(b.summary().isEmpty()).append(b.caps()).append(b.stashes())
                .append(b.proxyVersion()).append(b.pluginVersion());
        }
        for (StashRef s : host.stashes()) {
            sb.append('|').append(s.key()).append(s.online()).append(s.owner()).append(s.player()).append(s.stash()).append(s.server())
                .append(BotsModel.delivery(s)).append(s.bot().openOrders()).append(s.bot().conflictAt());
        }
        return sb.toString();
    }

    public void build(WContainer parent, GuiTheme theme) {
        this.theme = theme;
        live.clear();
        signature = computeSignature();
        nextSignature = System.currentTimeMillis() + SIGNATURE_MS;
        Ui.pageTitle(parent, theme, "bots");
        if (!host.auth().can("activity")) {
            parent.add(Ui.wrap(theme, "Your account can't see the bots; ask the owner.", PageRoom.width(parent, 0))).expandX().widget().color(Ui.warn(theme));
            return;
        }
        DisplayPolicy policy = host.policy();
        long now = System.currentTimeMillis();
        List<BotList.Bot> bots = host.bots().list().bots();
        SmCard dash = Ui.card(parent, "Dashboard bots");
        double inner = PageRoom.width(dash, 16);
        dash.add(Ui.muted(theme, "Proxy bots running the Qwerte Stash plugin with the dashboard on. Pick one to change its settings or use its console.", inner)).expandX();
        if (bots.isEmpty()) {
            dash.add(Ui.wrap(theme, host.bots().loaded()
                ? "No dashboard bots yet. In the proxy, turn the Qwerte Stash plugin's dashboard on (stash manager dashboard on)."
                : "Loading the bots...", inner)).expandX().widget().color(host.bots().loaded() ? Ui.dim(theme) : Ui.warn(theme));
        }
        SmGrid cards = dash.add(new SmGrid()).expandX().widget();
        cards.minCell = 250;
        cards.gapX = 8;
        cards.gapY = 8;
        cards.maxWidth = inner;
        // the room a card's text has: the grid's own column width, less the card's padding
        double cardWrap = Math.max(theme.scale(80), cards.columnWidth() - theme.scale(16));
        for (BotList.Bot b : bots) card(cards, b, policy, now, cardWrap);

        SmCard stashes = Ui.card(parent, "Stashes");
        stashes.add(Ui.muted(theme, "Every stash, with the bot that owns it.", inner)).expandX();
        stashTable(stashes, policy, now, inner);
    }

    // ---------- cards ----------

    private void card(WContainer parent, BotList.Bot b, DisplayPolicy policy, long now, double wrap) {
        boolean selected = b.botId().equals(host.bots().selected());
        SmCard card = parent.add(new SmCard("", selected ? SmCard.Style.SELECTED : SmCard.Style.TILE)).widget();
        card.padding = 6;
        card.spacing = 4;
        SmGuiTheme st = (SmGuiTheme) theme;
        WFlow head = card.add(new WFlow()).expandX().widget();
        head.maxWidth = wrap;
        head.gapX = 6;
        head.gapY = 2;
        head.add(new WDot(b.online() ? st.palette().okFg() : st.palette().errFg()));
        // each item of the row is at most a card wide: a long name or age text wraps inside its own box
        head.add(Ui.wrap(theme, "§l" + host.bots().list().name(b, policy.hideNames()), wrap - theme.scale(30)));
        head.add(new SmPill(b.online() ? "Online" : "Offline", b.online() ? SmPill.Kind.OK : SmPill.Kind.ERROR));
        WLabel seen = head.add(Ui.muted(theme, "last seen " + ago(b.lastSeen(), now), wrap)).widget();
        String id = b.botId();
        live.add(t -> {
            BotList.Bot cur = host.bots().list().find(id);
            if (cur != null) seen.set("last seen " + ago(cur.lastSeen(), t));
        });
        if (!b.summary().isEmpty()) {
            WLabel sum = card.add(Ui.wrap(theme, policy.hideCoords() ? "Now: (hidden)" : "Now: " + b.summary(), wrap)).expandX().widget();
            live.add(t -> {
                BotList.Bot cur = host.bots().list().find(id);
                if (cur != null && !cur.summary().isEmpty()) sum.set(host.policy().hideCoords() ? "Now: (hidden)" : "Now: " + cur.summary());
            });
        }
        card.add(Ui.muted(theme, BotsModel.meta(b), wrap)).expandX();

        WFlow stashes = card.add(new WFlow()).expandX().widget();
        stashes.maxWidth = wrap;
        stashes.gapX = 4;
        stashes.gapY = 4;
        stashes.add(Ui.muted(theme, b.stashes().isEmpty() ? "No stash linked to this player." : "Stashes:"));
        for (BotList.Link link : b.stashes()) {
            StashRef ref = find(link);
            String where = policy.hideServer() ? "(hidden)" : Text.sanitize(link.server(), 48);
            String what = policy.hideNames() ? "(hidden)" : Text.sanitize(link.stash(), 48);
            String full = what + " @ " + where;
            String shown = ellipsize(full, wrap - 2 * theme.scale(10));
            SmButton jump = (SmButton) stashes.add(theme.button(shown)).widget();
            jump.pill = true;
            jump.tooltip = (ref == null ? "Not in your stash list." : "Show this stash") + (full.equals(shown) ? "" : ": " + full);
            if (ref != null) jump.action = () -> host.later(() -> host.showStash(ref));
        }

        WFlow actions = card.add(new WFlow()).expandX().widget();
        actions.maxWidth = wrap;
        actions.gapX = 4;
        actions.gapY = 4;
        for (BotsModel.Jump j : BotsModel.jumps(b, host.auth())) {
            SmButton btn = (SmButton) actions.add(theme.button(j.label())).widget();
            btn.pill = true;
            btn.tooltip = "Open " + j.label() + " for this bot";
            btn.action = () -> host.later(() -> host.openPage(j.page(), b.botId()));
        }
    }

    /** Text cut with ".." to fit {@code limit} physical pixels (a pill must never be wider than its card). */
    private String ellipsize(String text, double limit) {
        if (theme.textWidth(text) <= limit) return text;
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (theme.textWidth(text.substring(0, mid) + "..") <= limit) lo = mid;
            else hi = mid - 1;
        }
        return text.substring(0, lo) + "..";
    }

    private StashRef find(BotList.Link link) {
        return find(link.key());
    }

    private StashRef find(String key) {
        for (StashRef s : host.stashes()) if (s.key().equals(key)) return s;
        return null;
    }

    // ---------- the stash table ----------

    private void stashTable(WContainer parent, DisplayPolicy policy, long now, double inner) {
        List<StashRef> stashes = host.stashes();
        if (stashes.isEmpty()) {
            parent.add(Ui.muted(theme, "No bots yet. Use Link addon (on the website) to connect one.", inner)).expandX();
            return;
        }
        SmGuiTheme st = (SmGuiTheme) theme;
        SmTable table = parent.add(new SmTable("Bot", "Stash", "Status", "Doing", "Delivery container", "Open orders", "")).expandX().widget();
        table.weights(2, 3, 2, 3, 2, 1, 1);
        table.rightAlign(6);
        table.maxWidth = inner;
        int shown = 0;
        List<String> conflicts = new ArrayList<>();
        for (StashRef s : stashes) {
            if (shown++ >= MAX_STASH_ROWS) break;
            WLabel who = theme.label(policy.hideNames() ? "Bot" : BotsModel.who(s), true);
            WLabel stash = theme.label(BotsModel.stashName(s, policy.hideNames()) + " @ " + (policy.hideServer() ? "(hidden)" : Text.sanitize(s.server(), 48)));
            WLabel state = theme.label((s.online() ? "online" : "offline") + " \u00b7 " + ago(s.lastSeen(), now));
            state.color(s.online() ? Ui.ok(theme) : Ui.dim(theme));
            BotsModel.Doing doing = BotsModel.doing(s);
            WLabel doingLabel = theme.label(policy.hideCoords() ? "(hidden)" : doing.text());
            doingLabel.color(doing.warn() ? Ui.warn(theme) : st.color(st.palette().text()));
            String stashKey = s.key();
            live.add(t -> {
                StashRef cur = find(stashKey);
                if (cur == null) return;
                state.set((cur.online() ? "online" : "offline") + " \u00b7 " + ago(cur.lastSeen(), t));
                BotsModel.Doing d = BotsModel.doing(cur);
                doingLabel.set(host.policy().hideCoords() ? "(hidden)" : d.text());
                doingLabel.color(d.warn() ? Ui.warn(theme) : st.color(st.palette().text()));
            });
            boolean noDelivery = s.bot().deliveryAt().isEmpty();
            WWidget delivery = noDelivery
                ? new SmPill(BotsModel.delivery(s), SmPill.Kind.ERROR)
                : theme.label(policy.hideCoords() ? "(hidden)" : BotsModel.delivery(s));
            WLabel orders = theme.label(String.valueOf(s.bot().openOrders()));
            WWidget release = null;
            if (BotsModel.canRelease(s, host.auth())) {
                String key = s.key();
                boolean armed = armedKey.equals(key) && now <= armedUntil;
                SmButton rel = (SmButton) theme.button(armed ? "Confirm release?" : "Release");
                rel.pill = true;
                rel.tooltip = armed ? BotsModel.releaseWarning(s, policy.streamer()) : "Lets another bot take this stash name. Click twice.";
                rel.action = () -> clickedRelease(s);
                release = rel;
            }
            table.addRow(who, stash, state, doingLabel, delivery, orders, release);
            String conflict = BotsModel.conflict(s, now, policy.streamer());
            if (!conflict.isEmpty()) conflicts.add(conflict);
        }
        for (String c : conflicts) parent.add(Ui.wrap(theme, c, inner)).expandX().widget().color(Ui.warn(theme));
        if (stashes.size() > MAX_STASH_ROWS) {
            parent.add(Ui.muted(theme, "Showing the first " + MAX_STASH_ROWS + " of " + stashes.size() + " stashes."));
        }
    }

    // ---------- release ----------

    private void clickedRelease(StashRef s) {
        if (releasing) return;
        long now = System.currentTimeMillis();
        String key = s.key();
        if (!armedKey.equals(key) || now > armedUntil) {
            armedKey = key;
            armedUntil = now + ARM_MS;
            host.notice(BotsModel.releaseWarning(s, host.policy().streamer()));
            host.later(host::rebuild);
            return;
        }
        armedKey = "";
        releasing = true;
        int t = token;
        JsonObject body = new JsonObject();
        body.addProperty("server", s.server());
        body.addProperty("stash", s.stash());
        host.write("/api/v1/stashes/release", body, r -> {
            if (t != token || host.closed()) return;
            releasing = false;
            if (r.ok()) {
                host.notice(BotsModel.releasedNotice(s, host.policy().streamer()));
                host.refreshStashes();
            } else if (r.kind() == ApiError.Kind.CANCELLED) {
                host.notice("Not released: the password was not confirmed.");
            } else if (r.kind() == ApiError.Kind.NO_PERMISSION || r.kind() == ApiError.Kind.LOGIN || r.kind() == ApiError.Kind.MUST_CHANGE) {
                host.failed(r);
            } else {
                host.notice("Could not release: " + host.describe(r, "HTTP " + r.status()));
            }
            host.later(host::rebuild);
        });
        host.later(host::rebuild);
    }

    private static String ago(long at, long now) {
        return at <= 0 ? "never" : Text.ago(Math.max(0, now - at));
    }
}

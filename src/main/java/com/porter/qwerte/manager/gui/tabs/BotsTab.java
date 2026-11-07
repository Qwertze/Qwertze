package com.porter.qwerte.manager.gui.tabs;

import com.google.gson.JsonObject;
import com.porter.qwerte.manager.model.BotList;
import com.porter.qwerte.manager.model.BotsModel;
import com.porter.qwerte.manager.model.DisplayPolicy;
import com.porter.qwerte.manager.model.StashRef;
import com.porter.qwerte.manager.model.Text;
import com.porter.qwerte.manager.net.ApiError;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;

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
    private static final Color WARN = new Color(255, 190, 80);
    private static final Color GOOD = new Color(120, 220, 140);
    private static final Color BAD = new Color(255, 110, 110);
    private static final Color DIM = new Color(170, 170, 180);
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
        if (!host.auth().can("activity")) {
            parent.add(theme.label("Your account can't see the bots; ask the owner.", theme.scale(520))).expandX().widget().color(WARN);
            return;
        }
        DisplayPolicy policy = host.policy();
        long now = System.currentTimeMillis();
        List<BotList.Bot> bots = host.bots().list().bots();
        if (bots.isEmpty()) {
            parent.add(theme.label(host.bots().loaded()
                ? "No dashboard bots yet. In the proxy, turn the Qwerte Stash plugin's dashboard on (stash manager dashboard on)."
                : "Loading the bots...", theme.scale(520))).expandX().widget().color(host.bots().loaded() ? DIM : WARN);
        }
        WVerticalList cards = parent.add(theme.verticalList()).expandX().widget();
        for (BotList.Bot b : bots) card(cards, b, policy, now);

        parent.add(theme.label("Stashes", true));
        stashTable(parent, policy, now);
    }

    // ---------- cards ----------

    private void card(WVerticalList parent, BotList.Bot b, DisplayPolicy policy, long now) {
        boolean selected = b.botId().equals(host.bots().selected());
        WVerticalList card = parent.add(theme.verticalList()).expandX().widget();
        WHorizontalList head = card.add(theme.horizontalList()).expandX().widget();
        head.add(theme.label((selected ? "> " : "") + host.bots().list().name(b, policy.hideNames()), true));
        head.add(theme.label(b.online() ? "Online" : "Offline")).widget().color(b.online() ? GOOD : BAD);
        WLabel seen = head.add(theme.label("last seen " + ago(b.lastSeen(), now))).widget();
        seen.color(DIM);
        String id = b.botId();
        live.add(t -> {
            BotList.Bot cur = host.bots().list().find(id);
            if (cur != null) seen.set("last seen " + ago(cur.lastSeen(), t));
        });
        if (!b.summary().isEmpty()) {
            WLabel sum = card.add(theme.label(policy.hideCoords() ? "Now: (hidden)" : "Now: " + b.summary(), theme.scale(520))).expandX().widget();
            live.add(t -> {
                BotList.Bot cur = host.bots().list().find(id);
                if (cur != null && !cur.summary().isEmpty()) sum.set(host.policy().hideCoords() ? "Now: (hidden)" : "Now: " + cur.summary());
            });
        }
        card.add(theme.label(BotsModel.meta(b), theme.scale(520))).expandX().widget().color(DIM);

        WHorizontalList stashes = card.add(theme.horizontalList()).expandX().widget();
        stashes.add(theme.label(b.stashes().isEmpty() ? "No stash linked to this player." : "Stashes:")).widget().color(DIM);
        for (BotList.Link link : b.stashes()) {
            StashRef ref = find(link);
            String where = policy.hideServer() ? "(hidden)" : Text.sanitize(link.server(), 48);
            String what = policy.hideNames() ? "(hidden)" : Text.sanitize(link.stash(), 48);
            WButton jump = stashes.add(theme.button(what + " @ " + where)).widget();
            jump.tooltip = ref == null ? "Not in your stash list." : "Show this stash";
            if (ref != null) jump.action = () -> host.later(() -> host.showStash(ref));
        }

        WHorizontalList actions = card.add(theme.horizontalList()).expandX().widget();
        for (BotsModel.Jump j : BotsModel.jumps(b, host.auth())) {
            WButton btn = actions.add(theme.button(j.label())).widget();
            btn.tooltip = "Open " + j.label() + " for this bot";
            btn.action = () -> host.later(() -> host.openPage(j.page(), b.botId()));
        }
    }

    private StashRef find(BotList.Link link) {
        return find(link.key());
    }

    private StashRef find(String key) {
        for (StashRef s : host.stashes()) if (s.key().equals(key)) return s;
        return null;
    }

    // ---------- the stash table ----------

    private void stashTable(WContainer parent, DisplayPolicy policy, long now) {
        List<StashRef> stashes = host.stashes();
        if (stashes.isEmpty()) {
            parent.add(theme.label("No bots yet. Use Link addon (on the website) to connect one.", theme.scale(520))).expandX().widget().color(DIM);
            return;
        }
        var view = parent.add(theme.view()).expandX().widget();
        view.maxHeight = Math.max(theme.scale(160), Utils.getWindowHeight() * 0.35);
        WTable table = view.add(theme.table()).expandX().widget();
        for (String h : new String[]{"Bot", "Stash", "Status", "Doing", "Delivery container", "Open orders", ""}) {
            table.add(theme.label(h, true));
        }
        table.row();
        int shown = 0;
        for (StashRef s : stashes) {
            if (shown++ >= MAX_STASH_ROWS) break;
            table.add(theme.label(policy.hideNames() ? "Bot" : BotsModel.who(s)));
            table.add(theme.label(BotsModel.stashName(s, policy.hideNames()) + " @ " + (policy.hideServer() ? "(hidden)" : Text.sanitize(s.server(), 48))));
            WLabel state = table.add(theme.label((s.online() ? "online" : "offline") + " · " + ago(s.lastSeen(), now))).widget();
            state.color(s.online() ? GOOD : DIM);
            BotsModel.Doing doing = BotsModel.doing(s);
            WLabel doingLabel = table.add(theme.label(policy.hideCoords() ? "(hidden)" : doing.text())).widget();
            doingLabel.color(doing.warn() ? WARN : theme.textColor());
            String stashKey = s.key();
            live.add(t -> {
                StashRef cur = find(stashKey);
                if (cur == null) return;
                state.set((cur.online() ? "online" : "offline") + " · " + ago(cur.lastSeen(), t));
                BotsModel.Doing d = BotsModel.doing(cur);
                doingLabel.set(host.policy().hideCoords() ? "(hidden)" : d.text());
                doingLabel.color(d.warn() ? WARN : theme.textColor());
            });
            boolean noDelivery = s.bot().deliveryAt().isEmpty();
            table.add(theme.label(noDelivery ? BotsModel.delivery(s) : policy.hideCoords() ? "(hidden)" : BotsModel.delivery(s)))
                .widget().color(noDelivery ? WARN : theme.textColor());
            table.add(theme.label(String.valueOf(s.bot().openOrders())));
            if (BotsModel.canRelease(s, host.auth())) {
                String key = s.key();
                boolean armed = armedKey.equals(key) && now <= armedUntil;
                WButton rel = table.add(theme.button(armed ? "Confirm release?" : "Release")).widget();
                rel.tooltip = armed ? BotsModel.releaseWarning(s, policy.streamer()) : "Lets another bot take this stash name. Click twice.";
                rel.action = () -> clickedRelease(s);
            } else {
                table.add(theme.label(""));
            }
            table.row();
            String conflict = BotsModel.conflict(s, now, policy.streamer());
            if (!conflict.isEmpty()) {
                table.add(theme.label(conflict, theme.scale(520))).widget().color(WARN);
                table.row();
            }
        }
        if (stashes.size() > MAX_STASH_ROWS) {
            parent.add(theme.label("Showing the first " + MAX_STASH_ROWS + " of " + stashes.size() + " stashes.")).widget().color(DIM);
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

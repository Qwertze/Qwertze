package com.porter.qwerte.manager.gui.tabs;

import com.porter.qwerte.manager.gui.widgets.WBar;
import com.porter.qwerte.manager.gui.widgets.WTextLog;
import com.porter.qwerte.manager.model.ActivityModel;
import com.porter.qwerte.manager.model.ActivityModel.Connection;
import com.porter.qwerte.manager.model.ActivityModel.Data;
import com.porter.qwerte.manager.model.BotList;
import com.porter.qwerte.manager.model.DisplayPolicy;
import com.porter.qwerte.manager.net.ApiError;
import com.porter.qwerte.manager.net.ServerUrl;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Activity tab: the Activity page of the website for the bot picked in the header. Connection and stats (health and food as
 * bars), what the bot is doing now (with progress bars), the events log with source and level filters, the modules (filter and
 * "enabled only") and the plugins. Polled every 2 s while this tab is shown.
 *
 * <p>The widgets are rebuilt only when what they show changes ({@link ActivityModel#structureSignature}); the events log and the
 * module list are refilled in place, so a filter box keeps its cursor. In streamer display the server, position, task details,
 * module status and event text are hidden, like on the website.
 */
public final class ActivityTab {
    private static final Color WARN = new Color(255, 190, 80);
    private static final Color GOOD = new Color(120, 220, 140);
    private static final Color BAD = new Color(255, 110, 110);
    private static final Color DIM = new Color(170, 170, 180);
    private static final Color TEXT = new Color(225, 225, 230);
    private static final long POLL_MS = 2_000;
    private static final long RETRY_MS = 5_000;
    private static final long CHECK_MS = 1_000;
    private static final long AGE_BUCKET_MS = 30_000;
    private static final long FILTER_DEBOUNCE_MS = 250;
    private static final int MODULES_SHOWN = 300;
    private static final String[] LEVEL_LABELS = {"All levels", "Info", "Warnings", "Errors"};
    private static final String[] LEVEL_VALUES = {"", "info", "warn", "error"};

    private final ServerTabHost host;
    private GuiTheme theme;

    private String bot;
    private Data data;
    private String message = "";
    private int token;
    private boolean busy;
    private long nextGet;
    private long skew;
    private long nextCheck;

    private String sourceFilter = "";
    private String levelFilter = "";
    private String moduleQuery = "";
    private boolean enabledOnly;
    private long modDirtyAt;
    private boolean modPending;

    // what the current widgets were built from
    private String builtSignature = "";
    private String shownEvents = "";
    private WLabel updatedLabel;
    private WLabel posLabel;
    private WLabel pingLabel;
    private WLabel uptimeLabel;
    private WLabel specLabel;
    private WBar healthBar;
    private WBar foodBar;
    private WContainer root;
    private WTextLog eventsLog;
    private WVerticalList modBox;
    private WLabel modCount;

    public ActivityTab(ServerTabHost host) {
        this.host = host;
    }

    /** The window closed or the session ended: forget the bot and its data. */
    public void reset() {
        token++;
        busy = false;
        bot = null;
        data = null;
        message = "";
        sourceFilter = "";
        levelFilter = "";
        moduleQuery = "";
        enabledOnly = false;
        builtSignature = "";
        shownEvents = "";
        nextGet = 0;
        updatedLabel = null;
        posLabel = pingLabel = uptimeLabel = specLabel = null;
        healthBar = foodBar = null;
        root = null;
        eventsLog = null;
        modBox = null;
        modCount = null;
    }

    private long serverNow() {
        return System.currentTimeMillis() + skew;
    }

    private BotList.Bot capableBot() {
        BotList.Bot b = host.bots().selectedBot();
        return b != null && b.hasCap("activity") ? b : null;
    }

    // ---------- per frame ----------

    public void tick(long now) {
        if (host.closed() || !host.auth().can("activity")) return;
        host.bots().tick(now, true);
        String id = host.bots().selected();
        if (!Objects.equals(id, bot)) {
            // another bot: start over (the filters belong to the bot)
            token++;
            busy = false;
            bot = id;
            data = null;
            message = "";
            sourceFilter = "";
            levelFilter = "";
            nextGet = 0;
            host.later(host::rebuild);
            return;
        }
        if (capableBot() == null) {
            if (!builtSignature.equals(signature())) rebuildIfIdle();
            return;
        }
        if (!busy && now >= nextGet) fetch(now);
        if (modPending && now - modDirtyAt > FILTER_DEBOUNCE_MS && modBox != null) {
            modPending = false;
            fillModules();
        }
        if (now < nextCheck) return;
        nextCheck = now + CHECK_MS;
        if (updatedLabel != null && data != null) {
            long sn = serverNow();
            updatedLabel.set(ActivityModel.updated(data.receivedAt(), sn));
            updatedLabel.color(ActivityModel.stale(data.receivedAt(), sn) ? WARN : DIM);
        }
        liveValues();
        if (!builtSignature.equals(signature())) rebuildIfIdle();
        else if (data != null && eventsLog != null && !shownEvents.equals(eventsKey())) fillEvents();
    }

    /** A rebuild clears the text boxes: it waits while one has the keyboard (the signature differs until then, so it comes later). */
    private void rebuildIfIdle() {
        if (root != null && TabFocus.anyFocused(root)) return;
        host.later(host::rebuild);
    }

    /** The numbers that move all the time are written into their widgets, not rebuilt. */
    private void liveValues() {
        if (data == null) return;
        Connection c = data.connection();
        if (posLabel != null) posLabel.set(positionText(host.policy(), c));
        if (pingLabel != null) pingLabel.set(Math.round(c.ping()) + " ms");
        if (uptimeLabel != null) uptimeLabel.set(ActivityModel.duration((long) (c.uptimeSeconds() * 1000)));
        if (specLabel != null) specLabel.set(String.valueOf(Math.round(c.spectators())));
        setBar(healthBar, c.health());
        setBar(foodBar, c.food());
    }

    private static void setBar(WBar bar, double value) {
        if (bar == null || Double.isNaN(value)) return;
        int v = (int) Math.max(0, Math.min(20, Math.round(value)));
        bar.set(v, v + " / 20");
    }

    private static String positionText(DisplayPolicy policy, Connection c) {
        return policy.hideCoords() ? "Hidden (streamer)" : Math.round(c.x()) + ", " + Math.round(c.y()) + ", " + Math.round(c.z());
    }

    /** Everything the widgets are built from, so a change rebuilds them. */
    private String signature() {
        DisplayPolicy p = host.policy();
        StringBuilder sb = new StringBuilder();
        sb.append(p.streamer()).append(bot).append(capableBot() != null).append(host.bots().loaded()).append(message);
        if (data != null) {
            sb.append(ActivityModel.structureSignature(data, serverNow(), AGE_BUCKET_MS)).append("#").append(ActivityModel.sources(data.events()));
        }
        return sb.toString();
    }

    private String eventsKey() {
        return data == null ? "" : ActivityModel.eventsSignature(data) + "|" + sourceFilter + "|" + levelFilter + "|" + host.policy().streamer();
    }

    private void fetch(long now) {
        String id = bot;
        if (id == null) return;
        busy = true;
        nextGet = now + POLL_MS;
        int t = token;
        host.client().get("/api/v1/bots/" + ServerUrl.seg(id) + "/activity", null, r -> {
            if (t != token) return;
            busy = false;
            if (host.closed()) return;
            if (r.ok()) {
                data = ActivityModel.parse(r.body());
                if (r.serverTimeMillis() > 0) skew = r.serverTimeMillis() - System.currentTimeMillis();
                message = "";
            } else if (r.kind() == ApiError.Kind.NETWORK || r.kind() == ApiError.Kind.SERVER || r.kind() == ApiError.Kind.RATE_LIMIT
                || r.kind() == ApiError.Kind.NOT_FOUND) {
                nextGet = System.currentTimeMillis() + RETRY_MS;
                message = host.describe(r, "Could not load the activity.");
            } else {
                nextGet = System.currentTimeMillis() + RETRY_MS;
                host.failed(r);
            }
        });
    }

    // ---------- widgets ----------

    public void build(WContainer parent, GuiTheme theme) {
        this.theme = theme;
        root = parent;
        updatedLabel = null;
        posLabel = pingLabel = uptimeLabel = specLabel = null;
        healthBar = foodBar = null;
        eventsLog = null;
        modBox = null;
        modCount = null;
        modPending = false;
        if (!host.auth().can("activity")) {
            parent.add(theme.label("Your account can not see the bot activity; ask the owner.", theme.scale(520))).expandX().widget().color(WARN);
            builtSignature = signature();
            return;
        }
        // the first build after the tab was picked: tick has not seen the bot yet
        if (bot == null) bot = host.bots().selected();
        builtSignature = signature();
        if (bot == null) {
            parent.add(theme.label(host.bots().loaded() ? "No dashboard bot yet." : "Loading the bots...", theme.scale(520))).expandX().widget().color(DIM);
            return;
        }
        if (capableBot() == null) {
            parent.add(theme.label("This bot does not send its activity (update the Qwerte Stash plugin).", theme.scale(520))).expandX().widget().color(WARN);
            return;
        }
        if (data == null || !data.hasActivity()) {
            String m = !message.isEmpty() ? message : data == null ? "Loading the activity..." : "Waiting for the first activity report of the bot...";
            parent.add(theme.label(m, theme.scale(520))).expandX().widget().color(message.isEmpty() ? DIM : BAD);
            return;
        }
        DisplayPolicy policy = host.policy();
        long sn = serverNow();
        status(parent, policy, sn);
        parent.add(theme.label("Now doing", true));
        tasks(parent, policy, sn);
        parent.add(theme.label("Events", true));
        events(parent);
        modules(parent);
        plugins(parent);
    }

    private void status(WContainer parent, DisplayPolicy policy, long sn) {
        Connection c = data.connection();
        WHorizontalList head = parent.add(theme.horizontalList()).expandX().widget();
        Color stateColor = c.state().equals("online") ? GOOD : c.state().equals("offline") ? BAD : WARN;
        head.add(theme.label(ActivityModel.badge(c), true)).widget().color(stateColor);
        if (!c.server().isEmpty()) head.add(theme.label(policy.hideServer() ? "Hidden (streamer)" : c.server())).widget().color(DIM);
        updatedLabel = head.add(theme.label(ActivityModel.updated(data.receivedAt(), sn))).widget();
        updatedLabel.color(ActivityModel.stale(data.receivedAt(), sn) ? WARN : DIM);

        WTable t = parent.add(theme.table()).expandX().widget();
        if (!c.dimension().isEmpty()) row(t, "Dimension", ActivityModel.dimension(c.dimension()));
        if (c.hasPosition()) {
            posLabel = row(t, "Position", positionText(policy, c));
        }
        if (!Double.isNaN(c.health())) healthBar = bar(t, "Health", c.health(), new Color(220, 60, 60));
        if (!Double.isNaN(c.food())) foodBar = bar(t, "Food", c.food(), new Color(210, 140, 50));
        if (!Double.isNaN(c.ping())) pingLabel = row(t, "Ping", Math.round(c.ping()) + " ms");
        if (!Double.isNaN(c.uptimeSeconds())) uptimeLabel = row(t, "Uptime", ActivityModel.duration((long) (c.uptimeSeconds() * 1000)));
        if (!Double.isNaN(c.spectators())) specLabel = row(t, "Spectators", String.valueOf(Math.round(c.spectators())));
        row(t, "Controlled by", c.controllingPlayer().isEmpty() ? "nobody" : policy.hideNames() ? "Hidden (streamer)" : c.controllingPlayer());
    }

    private WLabel row(WTable t, String name, String value) {
        t.add(theme.label(name)).widget().color(DIM);
        WLabel l = t.add(theme.label(value)).widget();
        t.row();
        return l;
    }

    private WBar bar(WTable t, String name, double value, Color fill) {
        t.add(theme.label(name)).widget().color(DIM);
        WBar b = t.add(new WBar(fill, 20)).widget();
        setBar(b, value);
        t.row();
        return b;
    }

    private void tasks(WContainer parent, DisplayPolicy policy, long sn) {
        if (data.current().isEmpty()) {
            parent.add(theme.label("Nothing running right now.")).widget().color(DIM);
            return;
        }
        for (ActivityModel.Task k : data.current()) {
            WVerticalList card = parent.add(theme.verticalList()).expandX().widget();
            WHorizontalList top = card.add(theme.horizontalList()).expandX().widget();
            top.add(theme.label(k.source().isEmpty() ? "?" : k.source(), true)).widget().color(levelColor(k.level()));
            if (k.since() > 0) top.add(theme.label("since " + ActivityModel.duration(sn - k.since()))).widget().color(DIM);
            if (!k.title().isEmpty()) card.add(theme.label(k.title(), theme.scale(520))).expandX();
            if (!k.detail().isEmpty()) {
                card.add(theme.label(policy.streamer() ? "(hidden)" : k.detail(), theme.scale(520))).expandX().widget().color(DIM);
            }
            if (k.hasProgress()) {
                WHorizontalList pr = card.add(theme.horizontalList()).widget();
                long total = Math.max(1, k.total());
                long done = Math.max(0, Math.min(total, k.done()));
                pr.add(new WBar(new Color(90, 150, 230), total)).widget().set(done, Math.round(100.0 * done / total) + "%");
                pr.add(theme.label(k.done() + " / " + k.total())).widget().color(DIM);
            }
        }
    }

    private static Color levelColor(String level) {
        return level.equals("error") ? BAD : level.equals("warn") ? WARN : TEXT;
    }

    private void events(WContainer parent) {
        WHorizontalList filters = parent.add(theme.horizontalList()).expandX().widget();
        List<String> sources = ActivityModel.sources(data.events());
        String[] sourceLabels = new String[sources.size() + 1];
        sourceLabels[0] = "All sources";
        for (int i = 0; i < sources.size(); i++) sourceLabels[i + 1] = sources.get(i);
        if (!sourceFilter.isEmpty() && !sources.contains(sourceFilter)) sourceFilter = "";
        WDropdown<String> src = filters.add(theme.dropdown(sourceLabels, sourceFilter.isEmpty() ? sourceLabels[0] : sourceFilter)).widget();
        src.action = () -> {
            String v = src.get();
            sourceFilter = v.equals(sourceLabels[0]) ? "" : v;
            fillEvents();
        };
        int li = 0;
        for (int i = 0; i < LEVEL_VALUES.length; i++) if (LEVEL_VALUES[i].equals(levelFilter)) li = i;
        WDropdown<String> lvl = filters.add(theme.dropdown(LEVEL_LABELS, LEVEL_LABELS[li])).widget();
        lvl.action = () -> {
            String v = lvl.get();
            levelFilter = "";
            for (int i = 0; i < LEVEL_LABELS.length; i++) if (LEVEL_LABELS[i].equals(v)) levelFilter = LEVEL_VALUES[i];
            fillEvents();
        };
        eventsLog = parent.add(new WTextLog(520)).expandX().widget();
        fillEvents();
    }

    /** Puts the events matching the filters into the log (no rebuild). */
    private void fillEvents() {
        if (eventsLog == null || data == null) return;
        boolean hide = host.policy().streamer();
        List<WTextLog.Line> lines = new ArrayList<>();
        List<ActivityModel.Event> shown = ActivityModel.filterEvents(data.events(), sourceFilter, levelFilter);
        if (shown.isEmpty()) {
            lines.add(new WTextLog.Line(data.events().isEmpty() ? "No events yet." : "No events match.", DIM));
        }
        ZoneId zone = ZoneId.systemDefault();
        for (ActivityModel.Event e : shown) {
            String text = ActivityModel.clock(e.at(), zone) + " [" + e.source() + "] " + (hide ? "(hidden)" : e.text());
            lines.add(new WTextLog.Line(text, levelColor(e.level())));
        }
        eventsLog.setLines(lines);
        shownEvents = eventsKey();
    }

    private void modules(WContainer parent) {
        WHorizontalList head = parent.add(theme.horizontalList()).expandX().widget();
        head.add(theme.label("Modules", true));
        modCount = head.add(theme.label(data.modulesOn() + " of " + data.modules().size() + " on")).widget();
        modCount.color(DIM);
        WHorizontalList filter = parent.add(theme.horizontalList()).expandX().widget();
        WTextBox box = filter.add(theme.textBox(moduleQuery, "Filter modules")).minWidth(theme.scale(220)).expandX().widget();
        box.action = () -> {
            String q = box.get();
            if (q.equals(moduleQuery)) return;
            moduleQuery = q;
            modDirtyAt = System.currentTimeMillis();
            modPending = true;
        };
        WCheckbox only = filter.add(theme.checkbox(enabledOnly)).widget();
        only.action = () -> {
            enabledOnly = only.checked;
            fillModules();
        };
        filter.add(theme.label("Enabled only"));
        modBox = parent.add(theme.verticalList()).expandX().widget();
        fillModules();
    }

    private void fillModules() {
        if (modBox == null || data == null) return;
        modBox.clear();
        boolean hide = host.policy().streamer();
        Map<String, List<ActivityModel.Module>> groups = ActivityModel.filterModules(data.modules(), moduleQuery, enabledOnly);
        if (groups.isEmpty()) {
            modBox.add(theme.label(data.modules().isEmpty() ? "The bot did not report any modules." : "No modules match.")).widget().color(DIM);
            return;
        }
        var view = modBox.add(theme.view()).expandX().widget();
        view.maxHeight = Math.max(theme.scale(160), Utils.getWindowHeight() * 0.3);
        int shown = 0;
        for (Map.Entry<String, List<ActivityModel.Module>> g : groups.entrySet()) {
            if (shown >= MODULES_SHOWN) break;
            view.add(theme.label(ActivityModel.groupTitle(g.getKey()), true));
            WTable table = view.add(theme.table()).expandX().widget();
            int col = 0;
            for (ActivityModel.Module m : g.getValue()) {
                if (shown++ >= MODULES_SHOWN) break;
                WLabel l = table.add(theme.label((m.enabled() ? "[on] " : "[off] ") + m.name())).widget();
                l.color(m.enabled() ? GOOD : DIM);
                if (!m.status().isEmpty() && !hide) l.tooltip = m.status();
                if (++col == 3) {
                    col = 0;
                    table.row();
                }
            }
        }
        if (shown >= MODULES_SHOWN) modBox.add(theme.label("Showing the first " + MODULES_SHOWN + " modules. Use the filter for the rest.")).widget().color(DIM);
    }

    private void plugins(WContainer parent) {
        parent.add(theme.label("Plugins", true));
        if (data.plugins().isEmpty()) {
            parent.add(theme.label("The bot did not report any plugins.")).widget().color(DIM);
            return;
        }
        WTable t = parent.add(theme.table()).expandX().widget();
        for (ActivityModel.Plugin p : data.plugins()) {
            t.add(theme.label(p.name().isEmpty() ? p.id() : p.name()));
            String meta = (!p.id().isEmpty() && !p.name().isEmpty() ? p.id() + " " : "") + (p.version().isEmpty() ? "" : "v" + p.version());
            t.add(theme.label(meta)).widget().color(DIM);
            t.row();
        }
    }

    /** The window is closing: nothing secret is held here. */
    public void clearSecrets() {
        token++;
        busy = false;
        data = null;
        message = "";
        builtSignature = "";
        shownEvents = "";
        nextGet = 0;
        root = null;
    }
}

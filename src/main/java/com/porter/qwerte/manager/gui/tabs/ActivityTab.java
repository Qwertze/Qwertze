package com.porter.qwerte.manager.gui.tabs;

import com.porter.qwerte.manager.gui.chrome.WModuleChip;
import com.porter.qwerte.manager.gui.style.PageRoom;
import com.porter.qwerte.manager.gui.style.SmCard;
import com.porter.qwerte.manager.gui.style.SmColumns;
import com.porter.qwerte.manager.gui.style.SmGrid;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import com.porter.qwerte.manager.gui.style.SmPill;
import com.porter.qwerte.manager.gui.style.Ui;
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
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
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
    /** Writes the current task values into the labels and bars of the last build. */
    private final List<java.util.function.LongConsumer> taskLive = new ArrayList<>();
    private final List<WModuleChip> chips = new ArrayList<>();

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
            updatedLabel.color(ActivityModel.stale(data.receivedAt(), sn) ? Ui.warn(theme) : Ui.dim(theme));
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
        long sn = serverNow();
        for (java.util.function.LongConsumer u : taskLive) u.accept(sn);
        if (!chips.isEmpty()) updateChips();
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
            // Only what changes the widgets: the connection layout, the task set, the module names and switches, the plugins.
            // Progress, texts, module status and the "since" ages are written into their widgets (liveValues).
            sb.append(data.online()).append(data.hasActivity()).append('|').append(ActivityModel.connectionSignature(data.connection()));
            for (ActivityModel.Task t : data.current()) {
                sb.append('|').append(t.source()).append(t.level()).append(t.title().isEmpty()).append(t.detail().isEmpty()).append(t.hasProgress()).append(t.since() > 0);
            }
            for (ActivityModel.Module m : data.modules()) sb.append('|').append(m.name()).append(m.plugin()).append(m.enabled()).append(m.status().isEmpty());
            for (ActivityModel.Plugin pl : data.plugins()) sb.append('|').append(pl.id()).append(pl.name()).append(pl.version());
            sb.append("#").append(ActivityModel.sources(data.events()));
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
        taskLive.clear();
        chips.clear();
        double wide = PageRoom.width(parent, 0);
        if (!host.auth().can("activity")) {
            parent.add(Ui.wrap(theme, "Your account can not see the bot activity; ask the owner.", wide)).expandX().widget().color(Ui.warn(theme));
            builtSignature = signature();
            return;
        }
        // the first build after the tab was picked: tick has not seen the bot yet
        if (bot == null) bot = host.bots().selected();
        builtSignature = signature();
        BotList.Bot picked = host.bots().selectedBot();
        String botName = picked == null ? "" : host.bots().list().name(picked, host.policy().hideNames());
        Ui.pageTitle(parent, theme, "activity" + (botName.isEmpty() ? "" : " \u00b7 " + botName));
        if (bot == null) {
            parent.add(Ui.wrap(theme, host.bots().loaded() ? "No dashboard bot yet." : "Loading the bots...", wide)).expandX().widget().color(Ui.dim(theme));
            return;
        }
        if (capableBot() == null) {
            parent.add(Ui.wrap(theme, "This bot does not send its activity (update the Qwerte Stash plugin).", wide)).expandX().widget().color(Ui.warn(theme));
            return;
        }
        if (data == null || !data.hasActivity()) {
            String m = !message.isEmpty() ? message : data == null ? "Loading the activity..." : "Waiting for the first activity report of the bot...";
            parent.add(Ui.wrap(theme, m, wide)).expandX().widget().color(message.isEmpty() ? Ui.dim(theme) : Ui.err(theme));
            return;
        }
        DisplayPolicy policy = host.policy();
        long sn = serverNow();
        status(parent, policy, sn);
        double gap = theme.scale(10);
        double half = (wide - gap) / 2;
        SmColumns cols = parent.add(new SmColumns()).expandX().widget();
        cols.maxWidth = wide;
        cols.gap = 10;
        cols.stretch = true;
        cols.weights(1, 1);
        SmCard doing = cols.add(new SmCard("Now doing")).widget();
        tasks(doing, policy, sn, half - theme.scale(16));
        SmCard ev = cols.add(new SmCard("Events")).widget();
        events(ev, half - theme.scale(16));
        modules(parent, wide);
        plugins(parent, wide);
    }

    private void status(WContainer parent, DisplayPolicy policy, long sn) {
        SmGuiTheme st = (SmGuiTheme) theme;
        Connection c = data.connection();
        SmCard strip = Ui.card(parent, "");
        strip.padding = 6;
        WHorizontalList head = strip.add(theme.horizontalList()).expandX().widget();
        SmPill.Kind kind = c.state().equals("online") ? SmPill.Kind.OK : c.state().equals("offline") ? SmPill.Kind.ERROR : SmPill.Kind.WARN;
        head.add(new SmPill(ActivityModel.badge(c), kind));
        head.add(theme.label(c.server().isEmpty() ? "" : policy.hideServer() ? "Hidden (streamer)" : c.server(), true)).expandX();
        updatedLabel = head.add(Ui.muted(theme, ActivityModel.updated(data.receivedAt(), sn))).widget();
        updatedLabel.color(ActivityModel.stale(data.receivedAt(), sn) ? Ui.warn(theme) : Ui.dim(theme));

        com.porter.qwerte.manager.gui.chrome.WFlow flow = strip.add(new com.porter.qwerte.manager.gui.chrome.WFlow()).expandX().widget();
        flow.gapX = 18;
        flow.gapY = 6;
        flow.maxWidth = PageRoom.width(strip, 12);
        if (!c.dimension().isEmpty()) stat(flow, "Dimension", ActivityModel.dimension(c.dimension()));
        if (c.hasPosition()) posLabel = stat(flow, "Position", positionText(policy, c));
        if (!Double.isNaN(c.health())) healthBar = bar(flow, "Health", c.health(), st.color(st.palette().hp()));
        if (!Double.isNaN(c.food())) foodBar = bar(flow, "Food", c.food(), st.color(st.palette().food()));
        if (!Double.isNaN(c.ping())) pingLabel = stat(flow, "Ping", Math.round(c.ping()) + " ms");
        if (!Double.isNaN(c.uptimeSeconds())) uptimeLabel = stat(flow, "Uptime", ActivityModel.duration((long) (c.uptimeSeconds() * 1000)));
        if (!Double.isNaN(c.spectators())) specLabel = stat(flow, "Spectators", String.valueOf(Math.round(c.spectators())));
        stat(flow, "Controlled by", c.controllingPlayer().isEmpty() ? "nobody" : policy.hideNames() ? "Hidden (streamer)" : c.controllingPlayer());
    }

    private WLabel stat(WContainer row, String name, String value) {
        WVerticalList col = row.add(theme.verticalList()).widget();
        col.spacing = 1;
        col.add(Ui.muted(theme, name));
        return col.add(theme.label(value)).widget();
    }

    private WBar bar(WContainer row, String name, double value, Color fill) {
        WVerticalList col = row.add(theme.verticalList()).widget();
        col.spacing = 1;
        col.add(Ui.muted(theme, name));
        WBar b = col.add(new WBar(fill, 20)).widget();
        setBar(b, value);
        return b;
    }

    private void tasks(SmCard card, DisplayPolicy policy, long sn, double wrap) {
        if (data.current().isEmpty()) {
            card.add(Ui.muted(theme, "Nothing running right now.")).expandX();
            return;
        }
        taskLive.clear();
        List<ActivityModel.Task> list = data.current();
        for (int i = 0; i < list.size(); i++) {
            ActivityModel.Task k = list.get(i);
            int index = i;
            SmCard t = card.add(new SmCard("", SmCard.Style.TILE)).expandX().widget();
            t.padding = 6;
            t.spacing = 3;
            WHorizontalList top = t.add(theme.horizontalList()).expandX().widget();
            top.add(theme.label(k.source().isEmpty() ? "?" : k.source(), true)).expandX().widget().color(levelColor(k.level()));
            WLabel since = k.since() > 0 ? top.add(Ui.muted(theme, "since " + ActivityModel.duration(sn - k.since()))).widget() : null;
            WLabel title = k.title().isEmpty() ? null : t.add(Ui.wrap(theme, k.title(), wrap - theme.scale(12))).expandX().widget();
            WLabel detail = k.detail().isEmpty() ? null : t.add(Ui.muted(theme, policy.streamer() ? "(hidden)" : k.detail(), wrap - theme.scale(12))).expandX().widget();
            WBar bar = null;
            WLabel counts = null;
            if (k.hasProgress()) {
                WHorizontalList pr = t.add(theme.horizontalList()).widget();
                SmGuiTheme st = (SmGuiTheme) theme;
                long total = Math.max(1, k.total());
                bar = pr.add(new WBar(st.color(st.palette().accent()), total)).widget();
                counts = pr.add(Ui.muted(theme, "")).widget();
            }
            WBar fbar = bar;
            WLabel fcounts = counts;
            taskLive.add(now -> {
                if (data == null || index >= data.current().size()) return;
                ActivityModel.Task cur = data.current().get(index);
                if (since != null && cur.since() > 0) since.set("since " + ActivityModel.duration(now - cur.since()));
                if (title != null) title.set(cur.title());
                if (detail != null) detail.set(host.policy().streamer() ? "(hidden)" : cur.detail());
                if (fbar != null && cur.hasProgress()) {
                    long total = Math.max(1, cur.total());
                    long done = Math.max(0, Math.min(total, cur.done()));
                    fbar.setMax(total);
                    fbar.set(done, Math.round(100.0 * done / total) + "%");
                    fcounts.set(cur.done() + " / " + cur.total());
                }
            });
        }
        liveValues();
    }

    private void updateChips() {
        if (data == null || host.policy().streamer()) return;
        int i = 0;
        for (ActivityModel.Module m : currentModules) {
            if (i >= chips.size()) break;
            chips.get(i++).setStatus(m.status());
        }
    }

    /** The modules in the order the chips were made (the filtered, grouped list). */
    private final List<ActivityModel.Module> currentModules = new ArrayList<>();

    private Color levelColor(String level) {
        SmGuiTheme st = (SmGuiTheme) theme;
        return level.equals("error") ? Ui.err(theme) : level.equals("warn") ? Ui.warn(theme) : st.color(st.palette().text());
    }

    private void events(SmCard card, double wrap) {
        WHorizontalList filters = card.addRight(theme.horizontalList()).widget();
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
        eventsLog = card.add(new WTextLog(0).size(wrap, 16)).expandX().widget();
        fillEvents();
    }

    /** Puts the events matching the filters into the log (no rebuild). */
    private void fillEvents() {
        if (eventsLog == null || data == null) return;
        boolean hide = host.policy().streamer();
        List<WTextLog.Line> lines = new ArrayList<>();
        List<ActivityModel.Event> shown = ActivityModel.filterEvents(data.events(), sourceFilter, levelFilter);
        if (shown.isEmpty()) {
            lines.add(new WTextLog.Line(data.events().isEmpty() ? "No events yet." : "No events match.", Ui.dim(theme)));
        }
        ZoneId zone = ZoneId.systemDefault();
        for (ActivityModel.Event e : shown) {
            String text = ActivityModel.clock(e.at(), zone) + " [" + e.source() + "] " + (hide ? "(hidden)" : e.text());
            lines.add(new WTextLog.Line(text, levelColor(e.level())));
        }
        eventsLog.setLines(lines);
        shownEvents = eventsKey();
    }

    private void modules(WContainer parent, double wide) {
        SmCard card = Ui.card(parent, "Modules");
        WHorizontalList right = card.addRight(theme.horizontalList()).widget();
        WTextBox box = right.add(theme.textBox(moduleQuery, "Filter modules")).minWidth(theme.scale(180)).widget();
        box.action = () -> {
            String q = box.get();
            if (q.equals(moduleQuery)) return;
            moduleQuery = q;
            modDirtyAt = System.currentTimeMillis();
            modPending = true;
        };
        WCheckbox only = right.add(theme.checkbox(enabledOnly)).widget();
        only.action = () -> {
            enabledOnly = only.checked;
            fillModules();
        };
        right.add(theme.label("enabled only"));
        modCount = card.add(Ui.muted(theme, data.modulesOn() + " of " + data.modules().size() + " on")).widget();
        modBox = card.add(theme.verticalList()).expandX().widget();
        modBox.spacing = 4;
        modWidth = PageRoom.width(card, 16);
        fillModules();
    }

    private double modWidth;

    private void fillModules() {
        if (modBox == null || data == null) return;
        modBox.clear();
        chips.clear();
        currentModules.clear();
        boolean hide = host.policy().streamer();
        Map<String, List<ActivityModel.Module>> groups = ActivityModel.filterModules(data.modules(), moduleQuery, enabledOnly);
        if (groups.isEmpty()) {
            modBox.add(Ui.muted(theme, data.modules().isEmpty() ? "The bot did not report any modules." : "No modules match.")).expandX();
            return;
        }
        int shown = 0;
        for (Map.Entry<String, List<ActivityModel.Module>> g : groups.entrySet()) {
            if (shown >= MODULES_SHOWN) break;
            modBox.add(theme.label(ActivityModel.groupTitle(g.getKey()), true)).expandX();
            SmGrid grid = modBox.add(new SmGrid()).expandX().widget();
            grid.minCell = 140;
            grid.gapX = 6;
            grid.gapY = 4;
            grid.maxWidth = modWidth;
            for (ActivityModel.Module m : g.getValue()) {
                if (shown++ >= MODULES_SHOWN) break;
                chips.add(grid.add(new WModuleChip(m.name(), hide ? "" : m.status(), m.enabled())).widget());
                currentModules.add(m);
            }
        }
        if (shown >= MODULES_SHOWN) modBox.add(Ui.muted(theme, "Showing the first " + MODULES_SHOWN + " modules. Use the filter for the rest.")).expandX();
    }

    private void plugins(WContainer parent, double wide) {
        SmCard card = Ui.card(parent, "Plugins");
        if (data.plugins().isEmpty()) {
            card.add(Ui.muted(theme, "The bot did not report any plugins.")).expandX();
            return;
        }
        pluginCards(card, wide);
    }

    private void pluginCards(SmCard card, double wide) {
        com.porter.qwerte.manager.gui.chrome.WFlow flow = card.add(new com.porter.qwerte.manager.gui.chrome.WFlow()).expandX().widget();
        flow.gapX = 8;
        flow.gapY = 8;
        flow.maxWidth = PageRoom.width(card, 16);
        for (ActivityModel.Plugin p : data.plugins()) {
            SmCard c = flow.add(new SmCard("", SmCard.Style.TILE)).widget();
            c.padding = 6;
            c.spacing = 1;
            c.add(theme.label(p.name().isEmpty() ? p.id() : p.name(), true));
            String meta = (!p.id().isEmpty() && !p.name().isEmpty() ? p.id() + " " : "") + (p.version().isEmpty() ? "" : "v" + p.version());
            if (!meta.isEmpty()) c.add(Ui.muted(theme, meta));
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

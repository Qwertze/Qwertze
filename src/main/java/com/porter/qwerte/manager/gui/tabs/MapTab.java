package com.porter.qwerte.manager.gui.tabs;

import com.porter.qwerte.compat.MapTexture;
import com.porter.qwerte.manager.gui.ItemStacks;
import com.porter.qwerte.manager.gui.chrome.WFlow;
import com.porter.qwerte.manager.gui.style.PageRoom;
import com.porter.qwerte.manager.gui.style.SmButton;
import com.porter.qwerte.manager.gui.style.SmCard;
import com.porter.qwerte.manager.gui.style.SmColumns;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import com.porter.qwerte.manager.gui.style.SmPill;
import com.porter.qwerte.manager.gui.style.Ui;
import com.porter.qwerte.manager.gui.widgets.WBar;
import com.porter.qwerte.manager.gui.widgets.WItemGrid;
import com.porter.qwerte.manager.gui.widgets.WMapView;
import com.porter.qwerte.manager.gui.widgets.WTextLog;
import com.porter.qwerte.manager.map.MapAtlas;
import com.porter.qwerte.manager.map.MapPalette;
import com.porter.qwerte.manager.map.MapView;
import com.porter.qwerte.manager.map.ViewerEvent;
import com.porter.qwerte.manager.map.ViewerState;
import com.porter.qwerte.manager.model.BotList;
import com.porter.qwerte.manager.model.Json;
import com.porter.qwerte.manager.net.ApiResult;
import com.porter.qwerte.manager.net.ServerUrl;
import com.porter.qwerte.manager.net.StreamRestart;
import com.porter.qwerte.manager.net.ViewerStream;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The Map tab: the live viewer of a dashboard bot (what the website's Live page shows): the map around it, its heading,
 * nearby players, mobs and items, its health, food and hotbar, and its chat. The stream runs on its own thread
 * ({@link ViewerStream}); events are applied here, in {@link #tick}, a few per frame. The server does the privacy: for an
 * account forced into streamer mode it sends rebased coordinates and no names or chat, and nothing local is ever drawn here.
 *
 * <p>The stream runs only while this tab is shown (see {@link #setShown}); the picture, the bot's state and the texture are
 * kept while the window is up and dropped when it closes.
 */
public final class MapTab {
    /** What the tab needs beyond {@link ServerTabHost}. */
    public interface Host extends ServerTabHost {
        /** The viewer id for this bot, kept for the game session (the server allows only ~20 new ones an hour). */
        String viewerId(String botId);
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final long UPLOAD_MS = 200;
    private static final int PER_FRAME = 64;

    private final Host host;
    private final MapView view = new MapView();
    private MapAtlas atlas;
    private ViewerState state;
    private MapTexture texture;
    private ViewerStream stream;
    private String streamBot;
    private boolean shown;
    private long lastUpload;
    private boolean explaining;
    private String stateBot;
    private String lastChange = "";
    private boolean endHandled;
    private int restartAttempt;
    private long restartAt;
    private boolean revokedNote;
    private String explanation = "";

    private GuiTheme theme;
    private WLabel statusLabel, noteLabel, coordsLabel, xpLabel;
    private WBar hpBar, foodBar;
    private WItemGrid hotbar, gear, inventory;
    private SmPill statePill;
    private ViewerEvent.Hud lastHud;
    private WTextLog chatLog;
    private WLabel chatHidden;
    private String botSignature = "";

    public MapTab(Host host) {
        this.host = host;
    }

    private void ensureState() {
        if (atlas == null) {
            atlas = new MapAtlas(MapPalette.argb());
            state = new ViewerState(atlas);
        }
    }

    // ---------- lifecycle ----------

    /** The tab is on screen (true) or not (false). Idempotent: starts or stops the stream, and frees the texture when hidden. */
    public void setShown(boolean on) {
        if (on == shown) {
            if (on) ensureStream();
            return;
        }
        shown = on;
        if (on) {
            ensureStream();
        } else {
            stopStream();
            releaseTexture();
        }
    }

    /** The window closed: nothing may keep running. */
    public void close() {
        shown = false;
        stopStream();
        releaseTexture();
    }

    private void releaseTexture() {
        if (texture != null) {
            texture.close();
            texture = null;
            if (atlas != null) atlas.markDirty();
        }
    }

    private void stopStream() {
        if (stream != null) stream.stop();
        stream = null;
        streamBot = null;
    }

    private boolean allowed() {
        return host.auth().loggedIn() && host.auth().can("viewer") && !host.closed();
    }

    private void ensureStream() {
        if (!shown || !allowed()) {
            if (stream != null) stopStream();
            return;
        }
        String bot = host.bots().selected();
        if (bot == null) {
            if (stream != null) stopStream();
            return;
        }
        long now = System.currentTimeMillis();
        String sig = changeSignature();
        boolean changed = !sig.equals(lastChange);
        lastChange = sig;
        if (changed) {
            // The bot list or the account changed: a stream that ended for good may try again at once
            restartAttempt = 0;
            restartAt = 0;
        }
        if (stream != null && !bot.equals(streamBot)) stopStream();
        if (stream != null) {
            if (stream.running()) return;
            ViewerStream.End end = stream.end();
            if (end == ViewerStream.End.NONE) return; // still starting
            if (!endHandled) {
                endHandled = true;
                long delay = StreamRestart.delayMs(end, restartAttempt++);
                restartAt = delay < 0 ? Long.MAX_VALUE : now + delay;
                if (end == ViewerStream.End.ERROR) explanation = explanation.isEmpty() ? "The live viewer stopped. Press Reconnect, or it tries again shortly." : explanation;
                refreshStatus();
            }
            if (changed) restartAt = 0;
            if (now < restartAt) return;
            // Starting again: keep the picture unless the server redacted or refused (revoked)
            if (end == ViewerStream.End.REVOKED) state.reset();
            stream = null;
        }
        ensureState();
        if (!bot.equals(stateBot)) {
            // Only a different bot starts from nothing
            state.reset();
            view.follow();
            explanation = "";
            stateBot = bot;
            restartAttempt = 0;
        }
        streamBot = bot;
        endHandled = false;
        stream = new ViewerStream(host.client(), bot, host.viewerId(bot));
        stream.start();
        refreshAll();
    }

    /** Presses "Reconnect": try again now. */
    private void reconnect() {
        restartAttempt = 0;
        restartAt = 0;
        explanation = "";
        if (stream != null && !stream.running()) stream = null;
        ensureStream();
    }

    private String changeSignature() {
        com.porter.qwerte.manager.model.AuthState a = host.auth();
        return a.can("viewer") + "|" + a.owner() + "|" + a.forcedStreamer() + "|" + a.userId() + "|" + botsSignature();
    }

    // ---------- per frame ----------

    /** Called every frame while the tab is shown (before the widgets are drawn, on the client thread). */
    public void tick(long now) {
        if (!shown || host.closed()) return;
        host.bots().tick(now, true);
        if (host.bots().takeChanged()) {
            String sig = botsSignature();
            if (!sig.equals(botSignature)) host.later(host::rebuild);
        }
        ensureStream();
        if (stream == null || state == null) return;
        int[] changed = {0};
        stream.drain(e -> {
            changed[0] |= state.apply(e);
            onEvent(e);
        }, PER_FRAME);
        int c = changed[0];
        if (c != 0) {
            ViewerEvent.Self self = state.self();
            if ((c & (ViewerState.TILES | ViewerState.SELF)) != 0 && self != null) {
                atlas.recentre(Math.floorDiv((int) Math.floor(self.x()), 16), Math.floorDiv((int) Math.floor(self.z()), 16));
            }
            if ((c & ViewerState.TILES) != 0) atlas.trim();
            if ((c & ViewerState.HUD) != 0) refreshHud();
            if ((c & (ViewerState.SELF | ViewerState.META)) != 0) refreshCoords();
            if ((c & ViewerState.CHAT) != 0) refreshChat();
            if ((c & (ViewerState.STATUS | ViewerState.META)) != 0) refreshStatus();
        }
        upload(now);
    }

    private void onEvent(ViewerEvent e) {
        if (e instanceof ViewerEvent.Bye b && b.reason().equals("revoked")) {
            // Nothing of the old view stays: the next stream is redacted if the account was forced into streamer mode
            state.reset();
            revokedNote = true;
            explanation = "";
            atlas.markDirty();
            host.refreshAuth();
            refreshAll();
        } else if (e instanceof ViewerEvent.Failed f) {
            onFailed(f);
        } else if (e instanceof ViewerEvent.Snapshot) {
            restartAttempt = 0; // a stream that delivered a snapshot was a good one: the next restart starts fresh
            explanation = "";
            revokedNote = false;
        }
    }

    private void onFailed(ViewerEvent.Failed f) {
        switch (f.status()) {
            case 401 -> explanation = "";
            case 403 -> explanation = "Your account can't use the live viewer; ask the owner.";
            case 404 -> explanation = "This bot isn't known to the server (any more).";
            default -> explain(f.retryMs());
        }
    }

    /** Asks the server why a stream failed (the website does the same), then says so under the map. */
    private void explain(long retryMs) {
        String bot = streamBot;
        String secs = retryMs > 0 ? " Trying again in " + Math.max(1, (retryMs + 999) / 1000) + " s." : "";
        explanation = "The connection to the server dropped." + secs;
        if (explaining || bot == null) return;
        explaining = true;
        host.client().get("/api/v1/bots/" + ServerUrl.seg(bot) + "/viewer", null, r -> {
            explaining = false;
            if (!shown || host.closed() || !bot.equals(streamBot)) return;
            if (r.ok()) {
                explanation = explainText(r) + secs;
            } else if (r.kind() == com.porter.qwerte.manager.net.ApiError.Kind.NOT_FOUND) {
                explanation = "This bot isn't known to the server (any more).";
            } else if (r.kind() == com.porter.qwerte.manager.net.ApiError.Kind.NO_PERMISSION) {
                explanation = "Your account can't use the live viewer; ask the owner.";
            } else if (r.kind() == com.porter.qwerte.manager.net.ApiError.Kind.LOGIN) {
                host.failed(r);
            } else {
                explanation = "Can't reach the server." + secs;
            }
            refreshStatus();
        });
    }

    static String explainText(ApiResult r) {
        com.google.gson.JsonObject o = r.obj();
        double yours = Json.num(o, "yours", 0), perUser = Json.num(o, "maxPerUser", Double.MAX_VALUE);
        double streams = Json.num(o, "streams", 0), maxStreams = Json.num(o, "maxStreams", Double.MAX_VALUE);
        double total = Json.num(o, "total", 0), maxTotal = Json.num(o, "maxTotal", Double.MAX_VALUE);
        if (yours >= perUser) return "You already have " + (int) yours + " viewers open (at most " + (int) perUser + "); close one (the website counts too).";
        if (streams >= maxStreams) return "This bot already has " + (int) streams + " viewers open (at most " + (int) maxStreams + ").";
        if (total >= maxTotal) return "The server already has " + (int) total + " viewer streams open (at most " + (int) maxTotal + ").";
        return "The connection to the server dropped.";
    }

    private void upload(long now) {
        if (atlas == null || !atlas.isDirty() || now - lastUpload < UPLOAD_MS) return;
        try {
            if (texture == null) texture = MapTexture.create(MapAtlas.SIZE, MapAtlas.SIZE);
            texture.upload(atlas.rgba());
            atlas.takeDirty();
            lastUpload = now;
        } catch (RuntimeException | LinkageError e) {
            // A texture that can't be made must not break the window: the markers still show
            atlas.takeDirty();
            lastUpload = now + 5_000;
        }
    }

    // ---------- widgets ----------

    private String botsSignature() {
        StringBuilder sb = new StringBuilder();
        for (BotList.Bot b : host.bots().list().bots()) sb.append(b.botId()).append(b.online()).append(';');
        return sb.toString();
    }

    public void build(WContainer parent, GuiTheme theme) {
        this.theme = theme;
        statusLabel = noteLabel = coordsLabel = xpLabel = null;
        statePill = null;
        hpBar = foodBar = null;
        hotbar = gear = inventory = null;
        lastHud = null;
        chatLog = null;
        chatHidden = null;
        botSignature = botsSignature();
        double wide = PageRoom.width(parent, 0);
        if (!host.auth().can("viewer")) {
            parent.add(Ui.wrap(theme, "Your account can't use the live viewer. Ask the owner to turn on Live viewer for it.", wide)).expandX();
            return;
        }
        ensureState();
        SmGuiTheme st = (SmGuiTheme) theme;
        boolean hide = host.policy().hideNames();
        BotList.Bot picked = host.bots().selectedBot();
        String botName = picked == null ? "" : host.bots().list().name(picked, hide);

        // "live - bot" with the state at its right end
        WHorizontalList title = parent.add(theme.horizontalList()).expandX().widget();
        title.add(Ui.muted(theme, "live" + (botName.isEmpty() ? "" : " \u00b7 " + botName))).expandX();
        statePill = title.add(new SmPill("", SmPill.Kind.NEUTRAL)).widget();
        statusLabel = statePill;
        if (host.bots().list().bots().isEmpty()) {
            parent.add(Ui.wrap(theme, host.bots().loaded() ? "No dashboard bots yet. Turn the Qwerte Stash plugin's dashboard on in the proxy." : "Loading the bots...", wide)).expandX();
        }

        double gap = theme.scale(10);
        // A narrow window stacks the map above the chat, each as wide as the page; a wide one puts the chat in a column at the right
        boolean stack = wide < theme.scale(560);
        double chatW = stack ? wide : Math.max(theme.scale(200), (wide - gap) * 0.25);
        double mainW = stack ? wide : wide - gap - chatW;
        SmColumns cols = null;
        if (!stack) {
            cols = parent.add(new SmColumns()).expandX().widget();
            cols.maxWidth = wide;
            cols.gap = 10;
            cols.stretch = true;
            cols.weights(mainW, chatW);
        }

        SmCard left = new SmCard("");
        if (stack) {
            parent.add(left).expandX();
            left.fill = wide;
        } else {
            cols.add(left);
        }
        left.padding = 6;
        // the stats row, and Centre / + / - at its right end
        double leftInner = mainW - theme.scale(12 + 4);
        WFlow top = left.add(new WFlow()).expandX().widget();
        top.maxWidth = leftInner;
        top.gapX = 8;
        top.gapY = 4;
        hpBar = top.add(new WBar(st.color(st.palette().hp()), 20)).widget();
        foodBar = top.add(new WBar(st.color(st.palette().food()), 20)).widget();
        xpLabel = top.add(Ui.wrap(theme, "", leftInner)).widget();
        coordsLabel = top.add(Ui.wrap(theme, "", leftInner)).widget();
        // the buttons have their own row, so the position text never runs under them
        WFlow buttons = left.add(new WFlow()).expandX().widget();
        buttons.maxWidth = leftInner;
        buttons.gapX = 6;
        buttons.gapY = 4;
        SmButton follow = (SmButton) buttons.add(theme.button("Centre")).widget();
        follow.pill = true;
        follow.primary = true;
        follow.tooltip = "Centre on the bot and keep following it. Drag the map to look around; right-click also follows.";
        follow.action = view::follow;
        SmButton zoomIn = (SmButton) buttons.add(theme.button("+")).widget();
        zoomIn.pill = true;
        zoomIn.tooltip = "Zoom in (the mouse wheel zooms around the pointer)";
        zoomIn.action = () -> view.zoomBy(MapView.STEP);
        SmButton zoomOut = (SmButton) buttons.add(theme.button("-")).widget();
        zoomOut.pill = true;
        zoomOut.tooltip = "Zoom out";
        zoomOut.action = () -> view.zoomBy(1 / MapView.STEP);
        SmButton again = (SmButton) buttons.add(theme.button("Reconnect")).widget();
        again.pill = true;
        again.tooltip = "Open the live stream again now.";
        again.action = this::reconnect;

        noteLabel = left.add(Ui.muted(theme, "", mainW - theme.scale(16))).expandX().widget();
        noteLabel.color(Ui.warn(theme));
        WMapView map = left.add(new WMapView(view, atlas, state, () -> texture, () -> host.policy().hideNames(), this::mapHint)).centerX().widget();
        map.maxWidth = Math.max(theme.scale(120), mainW - theme.scale(16));

        // The bot's inventory and hotbar, centred under the map
        WVerticalList inv = new WVerticalList();
        inv.spacing = 2;
        left.add(inv).centerX();
        inventory = inv.add(new WItemGrid(host::graphics).compact(9, 3)).widget();
        hotbar = inv.add(new WItemGrid(host::graphics).compact(9, 1)).widget();
        WHorizontalList gearRow = inv.add(theme.horizontalList()).widget();
        gearRow.add(Ui.muted(theme, "Gear"));
        gear = gearRow.add(new WItemGrid(host::graphics).compact(5, 1)).widget();

        SmCard chat = new SmCard("Chat");
        if (stack) {
            parent.add(chat).expandX();
            chat.fill = wide;
        } else {
            cols.add(chat);
        }
        if (host.policy().hideChat()) {
            chatHidden = chat.add(Ui.muted(theme, "Chat is hidden in streamer display.", chatW - theme.scale(16))).widget();
        } else {
            chatLog = chat.add(new WTextLog(0).size(chatW - theme.scale(16), 30)).expandX().widget();
        }
        refreshAll();
    }

    private String mapHint() {
        if (state == null) return "";
        if (!atlas.placed() || atlas.tileCount() == 0) {
            return switch (state.state()) {
                case "connecting" -> "Connecting...";
                case "closed" -> "Not connected";
                case "offline" -> "The bot is offline";
                default -> "Waiting for the bot's map...";
            };
        }
        return "";
    }

    private void selectBot(String id) {
        if (id.equals(host.bots().selected())) return;
        host.bots().select(id);
        ensureStream();
        refreshAll();
    }

    // ---------- refreshing the labels ----------

    private void refreshAll() {
        refreshStatus();
        refreshHud();
        refreshCoords();
        refreshChat();
    }

    private void refreshStatus() {
        if (state == null) return;
        String st = state.state();
        if (statusLabel != null) {
            if (statePill != null) statePill.setKind(st.equals("live") ? SmPill.Kind.OK : st.equals("closed") || st.equals("offline") ? SmPill.Kind.ERROR : SmPill.Kind.WARN);
            statusLabel.set(switch (st) {
                case "connecting" -> "Connecting";
                case "waiting" -> "Waiting for the bot";
                case "live" -> "Live";
                case "stale" -> "Stale";
                case "offline" -> "Bot offline";
                default -> "Closed";
            });
        }
        if (noteLabel != null) {
            String note = explanation;
            if (note.isEmpty()) {
                if (st.equals("waiting")) note = "Waiting for the bot's first frame. It needs the Qwerte Stash plugin with the live viewer.";
                else if (st.equals("offline")) note = "The bot is offline.";
                else if (revokedNote && !st.equals("live")) note = "The viewer was closed: your login or permissions changed.";
            }
            noteLabel.set(note);
        }
    }

    private void refreshCoords() {
        if (coordsLabel == null || state == null) return;
        if (host.policy().hideCoords()) {
            coordsLabel.set("Coordinates hidden (streamer display)");
            return;
        }
        ViewerEvent.Self s = state.self();
        if (s == null) {
            coordsLabel.set("-");
            return;
        }
        String dim = state.world() == null ? "" : state.world().dim().replaceFirst("^minecraft:", "");
        if (dim.equals("hidden")) dim = "";
        coordsLabel.set((state.relative() ? "relative " : "") + Math.round(s.x()) + ", " + Math.round(s.y()) + ", " + Math.round(s.z()) + (dim.isEmpty() ? "" : " - " + dim));
    }

    private void refreshHud() {
        if (hpBar == null || state == null) return;
        ViewerEvent.Hud h = state.hud();
        double hp = h == null ? Double.NaN : h.hp();
        double food = h == null ? Double.NaN : h.food();
        hpBar.set(hp, num(hp));
        foodBar.set(food, num(food));
        if (xpLabel != null) {
            String xp = h == null || Double.isNaN(h.xp()) ? "XP -" : "XP " + Math.round(h.xp());
            if (h != null && !Double.isNaN(h.armor())) xp += "   Armour " + Math.round(h.armor());
            xpLabel.set(xp);
        }
        if (h != null && h.equals(lastHud)) return;
        lastHud = h;
        List<WItemGrid.Cell> invCells = new ArrayList<>(27);
        for (int i = 0; i < 27; i++) invCells.add(cell(h == null ? null : h.inv().get(i), 200 + i));
        inventory.setCells(invCells);
        List<WItemGrid.Cell> cells = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) cells.add(cell(h == null ? null : h.hotbar().get(i), i));
        hotbar.setCells(cells);
        hotbar.select(h != null && h.held() >= 0 ? Integer.valueOf(h.held()) : null);
        List<WItemGrid.Cell> g = new ArrayList<>(5);
        String[] names = {"Helmet", "Chestplate", "Leggings", "Boots", "Offhand"};
        for (int i = 0; i < 5; i++) {
            ViewerEvent.Slot slot = h == null ? null : i < 4 ? h.armorSlots().get(i) : h.offhand();
            g.add(cell(slot, 100 + i, names[i]));
        }
        gear.setCells(g);
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "-" : Double.toString(Math.round(v * 2) / 2.0).replaceFirst("\\.0$", "");
    }

    private static WItemGrid.Cell cell(ViewerEvent.Slot slot, int ref) {
        return cell(slot, ref, "");
    }

    private static WItemGrid.Cell cell(ViewerEvent.Slot slot, int ref, String empty) {
        if (slot == null) return new WItemGrid.Cell(ItemStack.EMPTY, "", empty, ref);
        return new WItemGrid.Cell(ItemStacks.displayId(slot.id()), slot.count() > 1 ? String.valueOf(slot.count()) : "", slot.id(), ref);
    }

    private void refreshChat() {
        if (chatLog == null || state == null) return;
        List<WTextLog.Line> lines = new ArrayList<>();
        boolean any = false;
        for (ViewerEvent.ChatLine l : state.chat()) {
            any = true;
            String who = l.sender();
            String head = l.kind().equals("whisper") ? (who.isEmpty() ? "?" : who) + " -> " + (l.to().isEmpty() ? "?" : l.to()) + ": " : who.isEmpty() ? "" : who + ": ";
            String time = l.time() > 0 ? Instant.ofEpochMilli(l.time()).atZone(ZoneId.systemDefault()).format(CLOCK) + " " : "";
            lines.add(new WTextLog.Line(time + head + l.text(), chatColor(l.kind())));
        }
        if (!any) lines.add(new WTextLog.Line("No chat yet.", Ui.dim(theme)));
        chatLog.setLines(lines);

    }

    private meteordevelopment.meteorclient.utils.render.color.Color chatColor(String kind) {
        SmGuiTheme st = (SmGuiTheme) theme;
        return switch (kind) {
            case "whisper" -> st.color(st.palette().accent());
            case "system" -> st.color(st.palette().muted());
            case "death" -> Ui.err(theme);
            default -> st.color(st.palette().text());
        };
    }
}

package com.porter.qwerte.manager.gui;

import com.google.gson.JsonObject;
import com.porter.qwerte.manager.gui.chrome.WBotStatus;
import com.porter.qwerte.manager.gui.chrome.WChip;
import com.porter.qwerte.manager.gui.chrome.WFlow;
import com.porter.qwerte.manager.gui.chrome.WMacDots;
import com.porter.qwerte.manager.gui.chrome.WNavPill;
import com.porter.qwerte.manager.gui.frame.WFrame;
import com.porter.qwerte.manager.gui.frame.WindowCursor;
import com.porter.qwerte.manager.gui.frame.WindowGeometry;
import com.porter.qwerte.manager.gui.panels.ChangePasswordPanel;
import com.porter.qwerte.manager.gui.panels.ConfirmPanel;
import com.porter.qwerte.manager.gui.panels.LoginPanel;
import com.porter.qwerte.manager.gui.tabs.ActivityTab;
import com.porter.qwerte.manager.gui.tabs.AppearanceCard;
import com.porter.qwerte.manager.gui.tabs.BotsTab;
import com.porter.qwerte.manager.gui.tabs.AreaTab;
import com.porter.qwerte.manager.gui.tabs.KitsTab;
import com.porter.qwerte.manager.gui.tabs.LocalAreaSource;
import com.porter.qwerte.manager.gui.tabs.ServerAreaSource;
import com.porter.qwerte.manager.gui.tabs.ConsoleTab;
import com.porter.qwerte.manager.gui.tabs.PlannerTab;
import com.porter.qwerte.manager.gui.tabs.BrowseTab;
import com.porter.qwerte.manager.gui.tabs.MapTab;
import com.porter.qwerte.manager.gui.tabs.ServerTabHost;
import com.porter.qwerte.manager.gui.tabs.SettingsTab;
import com.porter.qwerte.manager.gui.tabs.UsersTab;
import com.porter.qwerte.manager.gui.chrome.WPaletteButton;
import com.porter.qwerte.manager.gui.style.Palette;
import com.porter.qwerte.manager.gui.style.SmButton;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import com.porter.qwerte.manager.gui.style.WindowTheme;
import com.porter.qwerte.manager.gui.tabs.OrdersTab;
import com.porter.qwerte.manager.model.AuthState;
import com.porter.qwerte.manager.model.BotList;
import com.porter.qwerte.manager.model.HeaderInfo;
import com.porter.qwerte.manager.model.NavPage;
import com.porter.qwerte.manager.model.DisplayPolicy;
import com.porter.qwerte.manager.model.IndexModel;
import com.porter.qwerte.manager.model.OrderMode;
import com.porter.qwerte.manager.model.StashList;
import com.porter.qwerte.manager.model.StashRef;
import com.porter.qwerte.manager.model.Text;
import com.porter.qwerte.manager.model.WindowMode;
import com.porter.qwerte.manager.net.ApiError;
import com.porter.qwerte.manager.net.ApiResult;
import com.porter.qwerte.manager.net.ServerUrl;
import com.porter.qwerte.manager.net.Session;
import com.porter.qwerte.manager.net.SmClient;
import com.porter.qwerte.modules.StashWindow;
import com.porter.qwerte.stash.ItemPreviewScreen;
import com.porter.qwerte.stash.Stash;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The Stash Window: the Stash Manager website's stash view inside the game.
 *
 * <p>Everything here runs on the client thread. Answers from the server arrive through {@link SmClient}, which drops
 * the ones that were overtaken by a logout, a lost session or the window closing. Prompts (login, change password,
 * confirm password) replace the content in place rather than opening another screen, because opening a screen closes
 * this one. Nothing is polled while the window is closed.
 */
public class StashManagerScreen extends WidgetScreen implements BrowseTab.Host {
    private enum View { NO_URL, BAD_URL, INSECURE, CHECKING, ERROR, LOGIN, CHANGE, CONFIRM, MAIN }

    /**
     * The window's pages, named as the website's navigation names them. Browse and Orders work without a server; the
     * others need a Stash Manager server and are shown in local mode so the window looks the same, with a hint
     * instead of content.
     */
    private enum Tab {
        BROWSE(NavPage.STASH), ORDERS(NavPage.ORDERS), MAP(NavPage.LIVE), SETTINGS(NavPage.SETTINGS),
        // Server pages with an in-game version (NavPage.inGame), for a window that has a server
        PROXY(NavPage.PROXY), BOTS(NavPage.BOTS), ACTIVITY(NavPage.ACTIVITY), CONSOLE(NavPage.CONSOLE), PLANNER(NavPage.PLANNER),
        // Reached from the stash page's creative tabs, not the page buttons: it belongs to the Stash page (never in local mode)
        KITS(NavPage.STASH),
        // The stash's area editor, also from the creative tabs: works for a server stash and for the local one
        AREA(NavPage.STASH);
        // A page without an in-game version would show a note that points to the website. To add one: flip its
        // NavPage.inGame, add its tab object (null in local mode), and handle it in tickServerTabs, onSessionLost and buildMain.

        private final NavPage page;
        private final String title;

        Tab(NavPage page) {
            this.page = page;
            this.title = page.title();
        }

        /** True for a page that only exists on the website. */
        private boolean webOnly() {
            return !page.inGame();
        }
    }

    /** The pages local mode lists: the ones that work without a server, plus the server-only ones that show a hint. */
    private static final List<Tab> LOCAL_TABS = List.of(Tab.BROWSE, Tab.ORDERS, Tab.MAP, Tab.SETTINGS);

    private static final long AUTH_POLL_MS = 30_000;
    private static final long STASH_POLL_MS = 10_000;
    private static final long ENTER_GRACE_MS = 400;
    private static final long RESCAN_ARM_MS = 5_000;
    private static final String NEEDS_SERVER = "Connect a Stash Manager server to use this: turn on connect-to-server in the stash-window module settings.";

    private final StashWindow module;
    private final SmClient client;
    private final ServerSource source = new ServerSource();
    /** True when the window runs without a server (the default): it browses what the game has seen. */
    private final boolean local;
    private final LocalSource localSource;
    private final BrowseTab browse;
    private final OrdersTab orders;
    private final LocalOrderBackend localOrders;
    /** Null in local mode. */
    private final MapTab mapTab;
    private final SettingsTab settingsTab;
    /** The Proxy page: the settings form in proxy mode. Null in local mode. */
    private final SettingsTab proxyTab;
    private final BotsTab botsTab;
    /** The Kits page. Null in local mode. */
    private final KitsTab kitsTab;
    /** The Area page: the stash's area and its editor. In both modes. */
    private final AreaTab areaTab;
    private final ActivityTab activityTab;
    private final ConsoleTab consoleTab;
    private final PlannerTab plannerTab;
    private final UsersTab usersTab;
    private final BotDirectory bots;
    /** Null in local mode. */
    private final ServerOrderBackend serverOrders;
    private long statusHoldUntil;
    private final Deque<Runnable> later = new ConcurrentLinkedDeque<>();

    private WFrame frame;
    private Cell<WVerticalList> bodyCell;
    private WVerticalList body;
    private WindowTheme lastTheme;
    /** True while the item preview is up on top of this window: the window is not really closed then. */
    private boolean previewing;
    private WLabel infoLabel;
    private GuiGraphics graphics;
    private boolean started;
    private boolean closed;
    private long shownAt;
    /** The frame's width when the page was built (0: not laid out yet), and since when it differs: a resized window rebuilds its page. */
    private double builtWidth;
    private long widthChangedAt;
    private double lastSeenW;

    private ServerUrl url;
    private AuthState auth;
    private View view = View.CHECKING;
    private Tab tab = Tab.BROWSE;
    private String errorText = "";
    private String loginNotice = "";
    private boolean quietLogout;
    private boolean busy;

    private LoginPanel loginPanel;
    private ChangePasswordPanel changePanel;
    private ConfirmPanel confirmPanel;
    private String confirmWhy = "";
    private Runnable confirmRetry;
    private Runnable confirmCancel;

    private long nextAuthPoll;
    private long nextStashPoll;
    private boolean authBusy;
    private boolean stashBusy;

    private String selectedKey;
    private String headerSignature = "";
    private List<String> dropdownKeys = List.of();
    private int loadToken;
    private String loadedWant;
    private String pendingWant;
    private String indexEtag;
    private String indexEtagKey;

    private WChip statusLabel;
    private WBotStatus botStatus;
    private long nextHeaderRefresh;
    private String statusText = "";
    private WLabel rescanLabel;
    private WButton rescanButton;
    private long rescanArmedUntil;
    private WButton clearButton;
    private long clearArmedUntil;

    public StashManagerScreen(GuiTheme theme, StashWindow module) {
        super(theme, "Stash Window");
        this.module = module;
        // In local mode the client is never touched, so nothing can be sent
        this.local = !WindowMode.of(module.connectToServer()).network();
        this.client = local ? null : module.client();
        this.localSource = local ? new LocalSource(module.hub()) : null;
        this.browse = new BrowseTab(this, local ? localSource : source);
        if (local) {
            this.bots = null;
            this.mapTab = null;
            this.settingsTab = null;
            this.proxyTab = null;
            this.botsTab = null;
            this.kitsTab = null;
            this.activityTab = null;
            this.consoleTab = null;
            this.plannerTab = null;
            this.usersTab = null;
        } else {
            TabHost th = new TabHost();
            this.bots = new BotDirectory(client, () -> auth, this::fail, module::rememberBot, module.lastBot());
            this.mapTab = new MapTab(th);
            this.settingsTab = new SettingsTab(th);
            this.proxyTab = new SettingsTab(th, true);
            this.botsTab = new BotsTab(th);
            this.kitsTab = new KitsTab(th);
            this.activityTab = new ActivityTab(th);
            this.consoleTab = new ConsoleTab(th);
            this.plannerTab = new PlannerTab(th);
            this.usersTab = new UsersTab(th);
            this.settingsTab.attachUsers(usersTab);
        }
        this.areaTab = new AreaTab(local ? new LocalAreaSource(() -> policy().hideCoords()) : new ServerAreaSource(new TabHost()), new AreaHost());
        if (mapTab != null) mapTab.attachArea(areaTab);
        this.localOrders = new LocalOrderBackend(module.localOrders());
        this.serverOrders = local ? null : new ServerOrderBackend(client, new ServerOrderBackend.Host() {
            @Override
            public StashRef stash() {
                return selected();
            }

            @Override
            public IndexModel index() {
                return source.index;
            }

            @Override
            public AuthState auth() {
                return auth;
            }

            @Override
            public void write(String path, JsonObject body, java.util.function.Consumer<ApiResult> done) {
                sendWrite(path, body, done);
            }

            @Override
            public void failed(ApiResult r) {
                fail(r);
            }

            @Override
            public boolean closed() {
                return closed;
            }
        });
        this.orders = new OrdersTab(new OrdersTab.Host() {
            @Override
            public GuiGraphics graphics() {
                return graphics;
            }

            @Override
            public void later(Runnable r) {
                StashManagerScreen.this.later(r);
            }

            @Override
            public void status(String text) {
                notice(text);
            }

            @Override
            public boolean hideNames() {
                return policy().hideNames();
            }

            @Override
            public void closeWindow(String hint) {
                module.tell(hint);
                mc.setScreen(null);
            }

            @Override
            public com.porter.qwerte.manager.model.Cart cart(String key) {
                return module.cart(key);
            }

            @Override
            public com.porter.qwerte.manager.model.NamesDraft names(String key) {
                return module.names(key);
            }

            @Override
            public OrderMode mode() {
                return currentBackend() == localOrders ? OrderMode.THIS_CLIENT : OrderMode.SERVER_BOTS;
            }

            @Override
            public void setMode(OrderMode mode) {
                module.setOrderMode(mode);
                headerSignature = "";
                StashManagerScreen.this.later(StashManagerScreen.this::rebuild);
            }

            @Override
            public boolean offersModeChoice() {
                return !local && !policy().forced();
            }
        });
        this.browse.setCartAdder(new BrowseTab.CartAdder() {
            @Override
            public boolean enabled() {
                OrderBackend b = currentBackend();
                return b != null && b.canPlace();
            }

            @Override
            public String add(String type, String key, boolean shulkerWorth) {
                orders.setBackend(currentBackend());
                return orders.addToCart(type, key, shulkerWorth);
            }
        });
    }

    /** Who carries out orders now: this client in local mode (or when chosen), else the server's bots. */
    private OrderBackend currentBackend() {
        if (local) return localOrders;
        if (module.orderMode() == OrderMode.THIS_CLIENT && !policy().forced()) return localOrders;
        return serverOrders;
    }

    /** A message that stays up for a few seconds, over the background notices. */
    @Override
    public void notice(String text) {
        setStatus(text);
        if (text != null && !text.isEmpty()) statusHoldUntil = System.currentTimeMillis() + 6_000;
    }

    // ---------- lifecycle ----------

    private SmGuiTheme sm() {
        return (SmGuiTheme) theme;
    }

    @Override
    public void initWidgets() {
        SmGuiTheme t = sm();
        WindowGeometry saved = module.windowGeometry();
        WindowGeometry start = saved != null ? saved : WindowGeometry.initial(t.screenW(), t.screenH());
        frame = add(new WFrame(start, module::setWindowGeometry, () -> later(() -> mc.setScreen(null)))).widget();
        bodyCell = frame.page.add(theme.verticalList());
        bodyCell.expandX();
        body = bodyCell.widget();
        body.spacing = 8;
        rebuild();
    }

    /** Esc closes an open list first, and the window with the next press. */
    @Override
    public void onClose() {
        if (frame != null && frame.overlay != null && frame.overlay.isOpen()) {
            frame.overlay.close();
            return;
        }
        super.onClose();
    }

    @Override
    protected void init() {
        closed = false;
        previewing = false;
        super.init();
        if (frame != null) {
            // Back from a preview, or the game window was resized: the theme must point at the overlay again, and a list
            // opened before is no longer where its select is
            sm().overlay = frame.overlay;
            frame.overlay.close();
        }
        shownAt = System.currentTimeMillis();
        if (!local) module.setSessionListener(this::onSessionLost);
        if (!started) {
            started = true;
            begin();
        } else {
            // Back from a preview (or resized): ask again right away
            pendingWant = null;
            nextAuthPoll = 0;
            nextStashPoll = 0;
        }
    }

    @Override
    protected void onClosed() {
        closed = true;
        WindowCursor.reset();
        com.porter.qwerte.manager.gui.widgets.SlotArt.clearCache();
        if (frame != null) frame.overlay.close();
        // The place is remembered when a move, resize or maximize ends; it is written to disk on a real close only
        if (!previewing) module.flushWindowGeometry();
        module.setSessionListener(null);
        // The item preview opens on top of this window and gives it back (init() reuses the tabs): nothing is dropped for
        // it, or the tabs would come back empty with their in-flight flags stuck and their answers discarded
        if (loginPanel != null) loginPanel.clearSecrets();
        if (changePanel != null) changePanel.clearSecrets();
        if (confirmPanel != null) confirmPanel.clearSecrets();
        if (previewing) return;
        if (client != null) client.bumpGeneration();
        if (mapTab != null) mapTab.close();
        if (settingsTab != null) settingsTab.clearSecrets();
        if (proxyTab != null) proxyTab.clearSecrets();
        if (usersTab != null) usersTab.clearSecrets();
        if (consoleTab != null) consoleTab.clearSecrets();
        if (activityTab != null) activityTab.clearSecrets();
        if (plannerTab != null) plannerTab.clearSecrets();
        if (botsTab != null) botsTab.reset();
        if (kitsTab != null) kitsTab.reset();
        areaTab.reset();
        areaDraftHint();
        dropConfirm();
        resetBusy();
    }

    @Override
    protected void onRenderBefore(GuiGraphics context, float delta) {
        graphics = context;
        // Only what is queued now runs: a task that queues itself again waits for the next frame instead of looping forever
        for (int n = later.size(); n > 0; n--) {
            Runnable r = later.poll();
            if (r == null) break;
            r.run();
        }
        // The palette must be the new one before the header is built from it
        sm().sync();
        if (!closed && module.windowTheme() != lastTheme) {
            // The header differs between themes (Mac has window dots), so it is built again
            headerSignature = "";
            rebuild();
        }
        long now = System.currentTimeMillis();
        if (local) tickLocal(now);
        else tickTimers(now);
        if (view == View.MAIN && tab == Tab.BROWSE) browse.tick(now);
        if (view == View.MAIN) tickOrders(now);
        if (view == View.MAIN && local && tab == Tab.AREA) areaTab.tick(now);
        if (view == View.MAIN && !local) tickServerTabs(now);
        checkResized(now);
    }

    /**
     * Pages are laid out for the width the window had when they were built (cards, columns, wrapped text), so a window that
     * was resized rebuilds its page once the size has been still for a moment, and never while a text box has the keyboard.
     */
    private void checkResized(long now) {
        if (frame == null || closed || view != View.MAIN) return;
        double w = frame.width;
        if (builtWidth == 0) {
            if (w > 1) {
                builtWidth = w;
                lastSeenW = w;
            }
            return;
        }
        // Still moving: the timer starts again at every change, so a drag never rebuilds
        if (Math.abs(w - lastSeenW) >= 1) {
            lastSeenW = w;
            widthChangedAt = now;
            return;
        }
        if (Math.abs(w - builtWidth) < 2) {
            widthChangedAt = 0;
            return;
        }
        if (widthChangedAt == 0) widthChangedAt = now;
        if (now - widthChangedAt > 250 && !textBoxFocused(frame)) {
            widthChangedAt = 0;
            builtWidth = 0;
            rebuild();
        }
    }

    private void tickServerTabs(long now) {
        if (closed || auth == null || !auth.loggedIn()) return;
        switch (tab) {
            case MAP -> mapTab.tick(now);
            case SETTINGS -> settingsTab.tick(now);
            case PROXY -> proxyTab.tick(now);
            case BOTS -> botsTab.tick(now);
            case KITS -> kitsTab.tick(now);
            case AREA -> areaTab.tick(now);
            case ACTIVITY -> activityTab.tick(now);
            case CONSOLE -> consoleTab.tick(now);
            case PLANNER -> plannerTab.tick(now);
            default -> {
            }
        }
    }

    private void tickOrders(long now) {
        if (closed) return;
        boolean visible = tab == Tab.ORDERS;
        OrderBackend b = currentBackend();
        if (b == null) return;
        // The server's orders are polled even off the Orders tab (slowly), so right-click in Browse knows what is available;
        // this client's data is only looked at while the tab is up
        if (b == localOrders && !visible) return;
        if (b == serverOrders && (auth == null || !auth.loggedIn())) return;
        boolean changed = b.tick(now, visible);
        if (visible) orders.tick(now, changed);
    }

    private void tickLocal(long now) {
        if (closed || view != View.MAIN) return;
        boolean changed = localSource.tick(now);
        String signature = localSource.dimensionSignature() + "|" + localSource.selected() + "|" + policy().streamer();
        if (!signature.equals(headerSignature)) {
            headerSignature = signature;
            rebuild();
            return;
        }
        if (changed) browse.dataChanged(false);
        String notice = localSource.notice();
        if (now > statusHoldUntil && !notice.equals(statusText)) setStatus(notice);
        if (clearArmedUntil != 0 && now > clearArmedUntil) {
            clearArmedUntil = 0;
            if (clearButton != null) clearButton.set("Clear local index");
        }
    }

    /** Whether any text box in the window has the keyboard. */
    private static boolean textBoxFocused(meteordevelopment.meteorclient.gui.widgets.WWidget w) {
        if (w instanceof meteordevelopment.meteorclient.gui.widgets.input.WTextBox tb) return tb.isFocused();
        if (w instanceof meteordevelopment.meteorclient.gui.widgets.containers.WContainer c) {
            for (meteordevelopment.meteorclient.gui.utils.Cell<?> cell : c.cells) {
                if (textBoxFocused(cell.widget())) return true;
            }
        }
        return false;
    }

    private void tickTimers(long now) {
        if (closed || !signedIn()) return;
        if (now >= nextAuthPoll && !authBusy) checkAuth();
        if (now >= nextStashPoll && !stashBusy) pollStashes();
        if (view == View.MAIN && bots != null && auth.can("activity")) bots.tick(now, true);
        if (view == View.MAIN && now >= nextHeaderRefresh) {
            nextHeaderRefresh = now + 1000;
            String signature = headerSignature();
            if (!signature.equals(headerSignature) && !textBoxFocused(frame)) {
                // A rebuild clears the page: wait while the user is typing in it, so nothing typed is lost
                headerSignature = signature;
                rebuild();
            } else {
                updateRescanLabel();
            }
        }
        if (rescanArmedUntil != 0 && now > rescanArmedUntil) {
            rescanArmedUntil = 0;
            if (rescanButton != null) rescanButton.set(RESCAN_LABEL);
        }
    }

    /** Logged in and showing the stash (or asking to confirm a password on top of it). */
    private boolean signedIn() {
        return auth != null && auth.loggedIn() && (view == View.MAIN || view == View.CONFIRM);
    }

    /** Runs on the next frame, outside the event that asked: safe for rebuilding the widgets that sent the event. */
    private void later(Runnable r) {
        later.add(r);
    }

    private void resetBusy() {
        busy = false;
        authBusy = false;
        stashBusy = false;
        pendingWant = null;
    }

    // ---------- BrowseTab.Host ----------

    @Override
    public GuiGraphics graphics() {
        return graphics;
    }

    @Override
    public boolean canInspect() {
        return mc.player != null && mc.level != null;
    }

    @Override
    public boolean hideCoords() {
        return policy().hideCoords();
    }

    /** The Kits tab is offered: a window with a server and an account that may view stashes. */
    private boolean kitsOffered() {
        // a user the owner forced into streamer mode does not get the tab, as on the website (the server would answer 403 anyway)
        return !local && auth != null && auth.loggedIn() && auth.can("view") && !auth.forcedStreamer();
    }

    @Override
    public boolean kitsTab() {
        return kitsOffered();
    }

    @Override
    public void openKits() {
        if (kitsOffered() && tab != Tab.KITS) {
            tab = Tab.KITS;
            later(this::rebuild);
        }
    }

    /** The Area tab is offered: the local stash in local mode; with a server an account that may view stashes (a forced streamer has none, as on the website). */
    private boolean areaOffered() {
        if (local) return true;
        return auth != null && auth.loggedIn() && auth.can("view") && !auth.forcedStreamer();
    }

    @Override
    public boolean areaTab() {
        return areaOffered();
    }

    @Override
    public void openArea() {
        if (areaOffered() && tab != Tab.AREA) {
            tab = Tab.AREA;
            later(this::rebuild);
        }
    }

    /** One chat line when the window closes with an area draft still open (it stays drawn in the world). */
    private void areaDraftHint() {
        com.porter.qwerte.stash.AreaDraft.State st = com.porter.qwerte.stash.AreaDraft.shared().get(System.currentTimeMillis());
        if (st == null) return;
        String text = st.clear() ? "Area draft (remove the area): open the Stash window to Save or Cancel." : com.porter.qwerte.stash.AreaDraft.hint(st.draft());
        meteordevelopment.meteorclient.utils.player.ChatUtils.info("%s", text);
    }

    @Override
    public void openOrders() {
        if (tab != Tab.ORDERS) {
            tab = Tab.ORDERS;
            later(this::rebuild);
        }
    }

    @Override
    public boolean cartEnabled() {
        OrderBackend b = currentBackend();
        return b != null && b.canPlace();
    }

    @Override
    public void inspect(ItemStack stack, long total, String title) {
        previewing = true;
        ItemPreviewScreen.open(stack, (int) Math.min(total, Integer.MAX_VALUE), title, this);
    }

    // ---------- policy ----------

    private DisplayPolicy policy() {
        return new DisplayPolicy(auth != null && auth.forcedStreamer(), module.streamerDisplay(), !local);
    }

    // ---------- start and sign in ----------

    private void begin() {
        if (local) {
            view = View.MAIN;
            rebuild();
            return;
        }
        String raw = module.rawUrl();
        if (raw == null || raw.isBlank()) {
            view = View.NO_URL;
            rebuild();
            return;
        }
        url = module.url();
        if (url == null) {
            view = View.BAD_URL;
            rebuild();
            return;
        }
        if (module.insecureRefused(url)) {
            view = View.INSECURE;
            rebuild();
            return;
        }
        client.setBase(url);
        Session saved = module.session();
        client.setToken(saved == null ? null : saved.token(), saved == null ? null : saved.origin());
        view = View.CHECKING;
        rebuild();
        checkAuth();
    }

    private void checkAuth() {
        authBusy = true;
        nextAuthPoll = System.currentTimeMillis() + AUTH_POLL_MS;
        client.get("/api/v1/auth/state", null, r -> {
            authBusy = false;
            if (closed) return;
            if (r.ok() && r.serverTimeMillis() > 0 && serverOrders != null) serverOrders.setClockSkew(r.serverTimeMillis() - System.currentTimeMillis());
            if (r.ok()) applyAuth(AuthState.parse(r.body()));
            else authFailed(r);
        });
    }

    private void authFailed(ApiResult r) {
        if (view == View.CHECKING) {
            errorText = describe(r, "Could not reach the server.");
            view = View.ERROR;
            rebuild();
        } else {
            fail(r);
        }
    }

    private void applyAuth(AuthState state) {
        String before = authSignature();
        if (!state.loggedIn()) {
            if (module.session() != null) {
                // The server ended the session (expired, password changed, disabled): forget it and say so
                module.dropSession("expired");
                return;
            }
            auth = state;
            String note = "";
            if (state.setupNeeded()) {
                note = state.setupAllowed()
                    ? "The server has no password yet. Open its address on the server PC to set one."
                    : Text.sanitize(state.message(), 200);
                if (note.isEmpty()) note = "The server has no password yet. Set one on the server PC first.";
            }
            showLogin(note);
            return;
        }
        auth = state;
        if (state.mustChange()) {
            view = View.CHANGE;
            rebuild();
            return;
        }
        if (view != View.MAIN && view != View.CONFIRM) {
            view = View.MAIN;
            nextStashPoll = 0;
            rebuild();
            return;
        }
        if (view == View.MAIN && !authSignature().equals(before)) rebuild();
    }

    private String authSignature() {
        if (auth == null) return "";
        return auth.forcedStreamer() + "|" + auth.can("view") + "|" + auth.can("rescan") + "|" + auth.can("viewer") + "|" + auth.owner() + "|" + auth.can("activity") + "|" + auth.userName();
    }

    private void showLogin(String note) {
        loginNotice = note;
        view = View.LOGIN;
        rebuild();
    }

    /** The session is gone (logout, 401, another address): forget everything the server told us. */
    private void onSessionLost() {
        if (local) return;
        boolean wasIn = auth != null && auth.loggedIn();
        dropConfirm();
        source.clear();
        if (serverOrders != null) serverOrders.clear();
        mapTab.close();
        settingsTab.reset();
        proxyTab.reset();
        botsTab.reset();
        kitsTab.reset();
        areaTab.reset();
        activityTab.reset();
        consoleTab.reset();
        plannerTab.reset();
        usersTab.reset();
        bots.clear();
        ItemStacks.clear();
        auth = null;
        resetBusy();
        selectedKey = null;
        loadToken++;
        loadedWant = null;
        indexEtag = null;
        indexEtagKey = null;
        headerSignature = "";
        browse.dataChanged(true);
        if (closed || view == View.NO_URL || view == View.BAD_URL) return;
        String note = wasIn && !quietLogout ? "Your session ended. Please log in again." : "";
        quietLogout = false;
        showLogin(note);
    }

    private void submitLogin(String user, String password) {
        if (busy || url == null) return;
        if (password.isEmpty()) {
            loginPanel.setNotice("Enter the password.");
            return;
        }
        busy = true;
        loginPanel.setNotice("Checking...");
        client.login(user, password, (r, token) -> {
            busy = false;
            if (closed) return;
            if (r.ok()) {
                if (token == null) {
                    loginPanel.setNotice("The server did not send a session. Is something between you and the server changing its answers?");
                    return;
                }
                module.setSession(new Session(token, url.origin(), user.trim()));
                resetBusy();
                view = View.CHECKING;
                rebuild();
                checkAuth();
                return;
            }
            String text = switch (r.kind()) {
                case WRONG_PASSWORD -> describe(r, "Wrong username or password.");
                case NO_PERMISSION -> describe(r, "This account is disabled.");
                case CONFLICT -> "The server has no password yet. Set one on the server PC first.";
                default -> describe(r, "Could not log in (HTTP " + r.status() + ").");
            };
            loginPanel.setNotice(text);
        });
    }

    private void submitChange(String current, String next) {
        if (busy) return;
        busy = true;
        changePanel.setNotice("Saving...");
        JsonObject b = new JsonObject();
        b.addProperty("current", current);
        b.addProperty("next", next);
        client.post("/api/v1/auth/password", b, r -> {
            busy = false;
            if (closed) return;
            if (r.ok()) {
                view = View.CHECKING;
                rebuild();
                checkAuth();
            } else if (r.kind() == ApiError.Kind.WRONG_PASSWORD) {
                changePanel.setNotice("The current password is wrong.");
            } else {
                changePanel.setNotice(describe(r, "Could not change the password (HTTP " + r.status() + ")."));
            }
        });
    }

    /** Forgets a password retry that is waiting (the tab, the bot or the session changed): the cancel hook runs so its caller is not left waiting. */
    private void dropConfirm() {
        confirmRetry = null;
        Runnable cancelled = confirmCancel;
        confirmCancel = null;
        if (cancelled != null) cancelled.run();
    }

    /** Asks for the password, then runs {@code retry} once. */
    private void askConfirm(String why, Runnable retry) {
        askConfirm(why, retry, null);
    }

    /** @param onCancel runs when the user cancels the prompt (null: nothing) */
    private void askConfirm(String why, Runnable retry, Runnable onCancel) {
        // A prompt that is replaced counts as cancelled, so its caller is never left waiting
        Runnable replaced = confirmCancel;
        confirmCancel = null;
        if (replaced != null && replaced != onCancel) replaced.run();
        confirmWhy = why;
        confirmRetry = retry;
        confirmCancel = onCancel;
        view = View.CONFIRM;
        rebuild();
    }

    private void submitConfirm(String password) {
        if (busy) return;
        busy = true;
        confirmPanel.setNotice("Checking...");
        JsonObject b = new JsonObject();
        b.addProperty("password", password);
        client.post("/api/v1/auth/confirm", b, r -> {
            busy = false;
            if (closed) return;
            if (r.ok()) {
                Runnable retry = confirmRetry;
                confirmRetry = null;
                confirmCancel = null;
                view = View.MAIN;
                rebuild();
                nextAuthPoll = 0;
                if (retry != null) retry.run();
            } else if (r.kind() == ApiError.Kind.WRONG_PASSWORD) {
                confirmPanel.setNotice("Wrong password.");
            } else {
                confirmPanel.setNotice(describe(r, "Could not confirm (HTTP " + r.status() + ")."));
            }
        });
    }

    /** POST that asks for the password when the server wants it (sudo mode), then tries once more. */
    void sendWrite(String path, JsonObject body, java.util.function.Consumer<ApiResult> done) {
        sendWrite(path, body, done, false);
    }

    /** @param tellCancel the caller also hears {@link ApiResult#cancelled()} when the user cancels the password prompt */
    void sendWrite(String path, JsonObject body, java.util.function.Consumer<ApiResult> done, boolean tellCancel) {
        client.post(path, body, r -> {
            if (r.kind() == ApiError.Kind.CONFIRM) {
                askConfirm(confirmWhy(path, body), () -> client.post(path, body, done),
                    tellCancel ? () -> done.accept(ApiResult.cancelled()) : null);
                return;
            }
            done.accept(r);
        });
    }

    /** The prompt text for a write that wants the password; a console command is named, so the user sees what the password lets through. */
    private String confirmWhy(String path, JsonObject body) {
        String base = "This needs your password again. It is not asked for again for 15 minutes.";
        if (path.endsWith("/console") && body != null && body.has("command") && body.get("command").isJsonPrimitive()) {
            String c = body.get("command").getAsString();
            String shown = policy().hideChat() ? "(hidden)" : Text.sanitize(c.length() > 80 ? c.substring(0, 80) : c, 80);
            return "Send the console command '" + shown + "'? It needs your password again. It is not asked for again for 15 minutes.";
        }
        return base;
    }

    private void logout() {
        quietLogout = true;
        // The request is built with the cookie right now; the session is forgotten at once
        client.post("/api/v1/auth/logout", new JsonObject(), r -> { });
        module.dropSession("logout");
    }

    // ---------- polling ----------

    private void pollStashes() {
        if (auth == null || !auth.can("view")) {
            nextStashPoll = System.currentTimeMillis() + STASH_POLL_MS;
            return;
        }
        stashBusy = true;
        nextStashPoll = System.currentTimeMillis() + STASH_POLL_MS;
        client.get("/api/v1/stashes", null, null, false, SmClient.DEFAULT_TIMEOUT, r -> StashList.parse(r.body()), (r, list) -> {
            stashBusy = false;
            if (closed) return;
            if (!r.ok()) {
                fail(r);
                return;
            }
            setStatus("");
            source.stashes = list;
            chooseSelection();
            String signature = headerSignature();
            if (!signature.equals(headerSignature)) {
                headerSignature = signature;
                if (view == View.MAIN) rebuild();
            } else {
                updateRescanLabel();
            }
            maybeLoadIndex();
        });
    }

    private StashRef find(String key) {
        if (key == null) return null;
        for (StashRef s : source.stashes) if (s.key().equals(key)) return s;
        return null;
    }

    private StashRef selected() {
        return find(selectedKey);
    }

    private void chooseSelection() {
        String want = selectedKey;
        if (find(want) == null) want = find(module.lastStash()) != null ? module.lastStash() : null;
        if (want == null && !policy().streamer() && mc.level != null) {
            // Only from the local game address, and never while streamer display is on (forced or chosen)
            String local = Stash.currentServer();
            for (StashRef s : source.stashes) {
                if (s.server().equalsIgnoreCase(local)) {
                    want = s.key();
                    break;
                }
            }
        }
        if (want == null) {
            for (StashRef s : source.stashes) {
                if (s.hasIndex()) {
                    want = s.key();
                    break;
                }
            }
        }
        if (want == null && !source.stashes.isEmpty()) want = source.stashes.get(0).key();
        if (!java.util.Objects.equals(want, selectedKey)) {
            selectedKey = want;
            module.rememberStash(want);
            onStashChanged();
        }
    }

    private void onStashChanged() {
        loadToken++;
        loadedWant = null;
        pendingWant = null;
        indexEtag = null;
        indexEtagKey = null;
        source.index = IndexModel.empty();
        rescanArmedUntil = 0;
        browse.dataChanged(true);
    }

    private void maybeLoadIndex() {
        StashRef s = selected();
        if (s == null || !s.hasIndex()) {
            if (loadedWant != null) {
                loadedWant = null;
                source.index = IndexModel.empty();
                browse.dataChanged(false);
            }
            return;
        }
        String want = s.key() + "|" + s.indexedAt();
        if (want.equals(loadedWant) || want.equals(pendingWant)) return;
        pendingWant = want;
        int token = ++loadToken;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("server", s.server());
        query.put("stash", s.stash());
        String etag = s.key().equals(indexEtagKey) ? indexEtag : null;
        client.get("/api/v1/index", query, etag, true, SmClient.INDEX_TIMEOUT, r -> IndexModel.parse(r.body()), (r, model) -> {
            if (closed || token != loadToken) return;
            pendingWant = null;
            switch (r.kind()) {
                case OK -> {
                    source.index = model;
                    indexEtag = r.etag();
                    indexEtagKey = s.key();
                    loadedWant = want;
                    setStatus("");
                    browse.dataChanged(false);
                }
                case NOT_MODIFIED -> loadedWant = want;
                case NOT_FOUND -> {
                    source.index = IndexModel.empty();
                    loadedWant = want;
                    browse.dataChanged(false);
                }
                default -> fail(r);
            }
        });
    }

    // ---------- rescan ----------

    private void rescanClicked() {
        StashRef s = selected();
        if (s == null || rescanButton == null) return;
        long now = System.currentTimeMillis();
        if (now > rescanArmedUntil) {
            rescanArmedUntil = now + RESCAN_ARM_MS;
            rescanButton.set("Confirm rescan?");
            setStatus("Click again to ask the bot to re-read every container of this stash.");
            return;
        }
        rescanArmedUntil = 0;
        rescanButton.set(RESCAN_LABEL);
        JsonObject b = new JsonObject();
        b.addProperty("server", s.server());
        b.addProperty("stash", s.stash());
        sendWrite("/api/v1/rescan", b, r -> {
            if (closed) return;
            if (r.ok()) {
                setStatus("Rescan requested.");
                nextStashPoll = 0;
            } else if (r.kind() == ApiError.Kind.NOT_FOUND) {
                setStatus("The server doesn't know this stash yet.");
            } else {
                fail(r);
            }
        });
    }

    private void updateRescanLabel() {
        if (infoLabel != null) infoLabel.set(infoText());
        refreshBotStatus();
        if (rescanLabel == null) return;
        StashRef s = selected();
        rescanLabel.set(s == null ? "" : s.rescanStatus());
    }

    // ---------- messages ----------

    private void setStatus(String text) {
        statusText = text == null ? "" : text;
        if (statusLabel != null) statusLabel.set(statusText);
    }

    /** What to tell the user about an answer that wasn't a success. */
    private void fail(ApiResult r) {
        switch (r.kind()) {
            case LOGIN -> onSessionLost();
            case MUST_CHANGE -> checkAuth();
            case NO_PERMISSION -> {
                nextAuthPoll = System.currentTimeMillis() + 500;
                setStatus("Your account can't do that.");
            }
            default -> setStatus(describe(r, "Unexpected answer (HTTP " + r.status() + ")."));
        }
    }

    private static String describe(ApiResult r, String fallback) {
        return switch (r.kind()) {
            case RATE_LIMIT -> "Too many attempts. Try again in " + r.wait429() + " s.";
            case REDIRECT -> "The server redirected the request. Check http:// or https:// in the server-url.";
            case NETWORK -> "Server unreachable.";
            case CANCELLED -> "Cancelled.";
            case SERVER -> "The server had an error (HTTP " + r.status() + ").";
            default -> {
                String text = Text.sentence(Text.sanitize(r.error(), 120));
                yield text.isEmpty() ? fallback : text;
            }
        };
    }

    // ---------- building the widgets ----------

    private void rebuild() {
        if (body == null || frame == null) return;
        frame.overlay.close();
        enterAction = null;
        loginPanel = null;
        changePanel = null;
        confirmPanel = null;
        statusLabel = null;
        botStatus = null;
        infoLabel = null;
        rescanLabel = null;
        rescanButton = null;
        rescanArmedUntil = 0;
        clearButton = null;
        clearArmedUntil = 0;
        body.clear();
        frame.header.clear();
        lastTheme = module.windowTheme();
        if (view == View.MAIN && !local && auth != null && !tabVisible(tab)) tab = Tab.BROWSE;
        boolean browse = browsePage();
        frame.surfacePage = !browse;
        bodyCell.pad(browse ? WFrame.PAGE_PAD : WFrame.SURFACE_PAD);
        buildHeader();
        // Wrapped text is wrapped once, to the room the window has now
        double room = sm().g(frame.effective().w() - 2 * (browse ? 5 : 10) - 8);
        double wrap = Math.max(theme.scale(200), Math.min(theme.scale(520), room));
        switch (view) {
            case NO_URL -> {
                body.add(theme.label("No server set", true));
                body.add(theme.label("Connect to server is on, but the server-url of the stash-window module is empty. Set it to your Stash Manager address, "
                    + "like http://192.168.1.20:8765, or turn connect-to-server off to use the local index.", wrap)).expandX();
            }
            case INSECURE -> {
                body.add(theme.label("Plain http is refused", true));
                body.add(theme.label("This address is not on your own network and is not https, so your password and session would cross the network unencrypted. "
                    + "Use an https:// address, or turn on allow-insecure-http in the stash-window module if you accept that.", wrap)).expandX().widget().color(bad());
            }
            case BAD_URL -> {
                body.add(theme.label("The server-url is not valid", true));
                body.add(theme.label("It has to look like http://192.168.1.20:8765 or https://stash.example.com, with nothing after the address.", wrap)).expandX();
            }
            case CHECKING -> body.add(theme.label("Connecting..."));
            case ERROR -> {
                body.add(theme.label("Can't use the server", true));
                body.add(theme.label(errorText, wrap)).expandX().widget().color(bad());
                WButton retry = body.add(theme.button("Try again")).widget();
                retry.action = () -> later(this::begin);
            }
            case LOGIN -> {
                if (url != null && !url.secure() && !url.localHost()) {
                    body.add(theme.label("Warning: this address is plain http and not on a local network. Your password and session cross the internet unencrypted.", wrap))
                        .expandX().widget().color(warn());
                }
                loginPanel = new LoginPanel();
                loginPanel.build(body, theme, policy().hideServer() ? "(hidden)" : url.origin(), module.lastUser(), loginNotice, this::submitLogin);
                LoginPanel p = loginPanel;
                enterAction = () -> enter(p::submit);
            }
            case CHANGE -> {
                changePanel = new ChangePasswordPanel();
                changePanel.build(body, theme, auth == null ? 12 : auth.minLength(), "", this::submitChange, this::logout);
                ChangePasswordPanel p = changePanel;
                enterAction = () -> enter(p::submit);
            }
            case CONFIRM -> {
                confirmPanel = new ConfirmPanel();
                confirmPanel.build(body, theme, confirmWhy, "", this::submitConfirm, () -> later(() -> {
                    confirmRetry = null;
                    Runnable cancelled = confirmCancel;
                    confirmCancel = null;
                    view = View.MAIN;
                    rebuild();
                    if (cancelled != null) cancelled.run();
                }));
                ConfirmPanel p = confirmPanel;
                enterAction = () -> enter(p::submit);
            }
            case MAIN -> {
                if (local) buildLocal();
                else buildMain();
            }
        }
        if (mapTab != null) mapTab.setShown(view == View.MAIN && tab == Tab.MAP && !closed);
        builtWidth = 0;
        widthChangedAt = 0;
        invalidate();
    }

    /** True when the stash page itself is up: it draws its own panel and tabs, unlike the other pages. */
    private boolean browsePage() {
        return view == View.MAIN && tab == Tab.BROWSE && (local || (auth != null && auth.can("view")));
    }

    private meteordevelopment.meteorclient.utils.render.color.Color bad() {
        return sm().color(errorInk());
    }

    private meteordevelopment.meteorclient.utils.render.color.Color warn() {
        return sm().color(warnInk());
    }

    /** Error and warning text on the page's panel: the theme's badge background, which reads on its surface. */
    private int errorInk() {
        Palette p = sm().palette();
        return luminance(p.surface()) > 0.5 ? Palette.mix(p.errBg(), 0xFF000000, 0.25) : p.errFg();
    }

    private int warnInk() {
        Palette p = sm().palette();
        return luminance(p.surface()) > 0.5 ? Palette.mix(p.warnBg(), 0xFF000000, 0.65) : p.warnBg();
    }

    private static double luminance(int argb) {
        return (0.299 * Palette.red(argb) + 0.587 * Palette.green(argb) + 0.114 * Palette.blue(argb)) / 255.0;
    }

    // ---------- the header ----------
    // Laid out like the website's: a title row (title, Stash select, bot status, Bot select, Link addon), a second row
    // (streamer mode, theme, account, log out), the index line with the rescan pill at its right end, and the page buttons.

    /** A row of the header with the website's gaps (10 px between rows, 18 px between items), in Meteor pixels. */
    private WFlow flow() {
        WFlow f = frame.header.add(new WFlow()).widget();
        f.gapX = 18;
        f.gapY = 10;
        return f;
    }

    /** Text on the header bar. */
    private WLabel ink(WLabel label) {
        return (WLabel) label.color(sm().color(sm().palette().barText()));
    }

    private WLabel barLabel(WFlow row, String text, boolean title) {
        return ink(row.add(theme.label(text, title)).widget());
    }

    /** The website's small text on the header bar (the index line). */
    private WLabel barSmall(WFlow row, String text) {
        return ink(row.add(sm().smallLabel(text)).widget());
    }

    /** The theme select every page's header has. */
    private void themeSelect(WFlow row) {
        WDropdown<WindowTheme> select = row.add(theme.dropdown(WindowTheme.values(), module.windowTheme())).widget();
        select.tooltip = "Change the window's theme";
        select.action = () -> module.setWindowTheme(select.get());
    }

    private void buildHeader() {
        Palette p = sm().palette();
        WFlow row = flow();
        if (p.isMac()) row.add(new WMacDots(() -> later(() -> mc.setScreen(null)), frame::toggleMaximized));
        barLabel(row, "Stash Manager", true);
        if (view != View.MAIN) {
            themeSelect(row);
            return;
        }
        DisplayPolicy policy = policy();
        if (local) {
            buildLocalPicker(row, policy);
        } else {
            buildStashRow(row, policy);
            if (auth.can("view")) {
                botStatus = row.add(new WBotStatus("", false)).widget();
                refreshBotStatus();
            }
            if (auth.can("activity")) buildBotPicker(row, policy);
            if (auth.can("link")) {
                WButton link = row.add(theme.button("Link addon")).widget();
                link.tooltip = "Linking an addon is done on the website.";
                link.action = () -> notice("Linking an addon is done on the website" + websiteWhere() + ".");
            }
        }

        WFlow second = flow();
        WButton streamer = second.add(theme.button(policy.forced() ? "Streamer mode: on (locked)" : "Streamer mode: " + (policy.streamer() ? "on" : "off"))).widget();
        streamer.tooltip = policy.forced() ? "The owner turned streamer mode on for your account." : "Hides coordinates, server addresses and player names in this window.";
        if (streamer instanceof SmButton b) b.danger = policy.streamer();
        streamer.action = () -> {
            if (policy().forced()) return;
            module.setStreamerDisplay(!policy().streamer());
            headerSignature = "";
            later(this::rebuild);
        };
        WPaletteButton palette = second.add(new WPaletteButton()).widget();
        palette.tooltip = "Themes: Settings > Appearance";
        palette.action = () -> {
            if (tab != Tab.SETTINGS) {
                dropConfirm();
                tab = Tab.SETTINGS;
                later(this::rebuild);
            }
        };
        if (local) {
            clearButton = second.add(theme.button("Clear local index")).widget();
            clearButton.tooltip = "Forgets every container stored for this server and deletes its file. Click twice.";
            clearButton.action = this::clearClicked;
        } else {
            String who = auth.userName().isBlank() ? "owner" : Text.sanitize(auth.userName(), 40);
            barSmall(second, policy.hideNames() ? "Logged in" : who + (auth.owner() && !who.equals("owner") ? " (owner)" : ""));
            WButton logout = second.add(theme.button("Log out")).widget();
            logout.action = () -> later(this::logout);
        }

        WFlow info = flow();
        info.gapX = 12;
        info.gapY = 4;
        if (local) {
            infoLabel = barSmall(info, "What this game has seen. No server.");
        } else {
            infoLabel = barSmall(info, infoText());
            rescanLabel = barSmall(info, "");
            updateRescanLabel();
        }
        statusLabel = info.add(new WChip("")).widget();
        statusLabel.set(statusText);
        if (!local && auth.can("rescan") && !source.stashes.isEmpty()) {
            rescanButton = info.add(theme.button(RESCAN_LABEL)).widget();
            if (rescanButton instanceof SmButton b) b.pill = true;
            rescanButton.tooltip = "Asks the bot to re-read every container of this stash. Click twice.";
            rescanButton.action = this::rescanClicked;
            info.lastRight = true;
        }

        WFlow nav = flow();
        nav.gapX = 4;
        nav.gapY = 4;
        for (Tab t : navTabs()) {
            String title = local && t != Tab.BROWSE && t != Tab.ORDERS && t != Tab.SETTINGS ? t.title + " (server)" : t.title;
            WNavPill pill = nav.add(new WNavPill(title, t == tab || (t == Tab.BROWSE && (tab == Tab.KITS || tab == Tab.AREA)))).widget();
            pill.action = () -> {
                if (tab != t) {
                    dropConfirm();
                    tab = t;
                    later(this::rebuild);
                }
            };
        }
    }

    private static final String RESCAN_LABEL = "Rescan stash";

    /** The page buttons: the in-game pages in local mode (the server-only ones with a hint), the website's list otherwise. */
    private List<Tab> navTabs() {
        List<Tab> out = new ArrayList<>();
        if (local) {
            out.addAll(LOCAL_TABS);
            return out;
        }
        List<NavPage> pages = NavPage.visible(auth, botHasActivity(), bots.selected() != null);
        for (Tab t : Tab.values()) if (t != Tab.KITS && t != Tab.AREA && pages.contains(t.page)) out.add(t);
        // the website's order
        out.sort(java.util.Comparator.comparingInt(t -> t.page.ordinal()));
        return out;
    }

    /** True when the picked dashboard bot reports activity, or the bot list isn't known yet. */
    private boolean botHasActivity() {
        if (bots == null || !bots.loaded()) return true;
        BotList.Bot b = bots.selectedBot();
        return b != null && b.hasCap("activity");
    }

    /** " (open <address> in a browser)" for a page the window doesn't have; the address is hidden in streamer mode. */
    private String websiteWhere() {
        if (url == null || policy().hideServer()) return "";
        return " (open " + url.origin() + " in a browser)";
    }

    /** The website's Bot select: the dashboard bots this account may see, polled only while the window is up. */
    private void buildBotPicker(WFlow row, DisplayPolicy policy) {
        List<BotList.Bot> list = bots.list().bots();
        if (list.isEmpty()) return;
        String[] labels = new String[list.size()];
        String current = null;
        String picked = bots.selected();
        for (int i = 0; i < labels.length; i++) {
            BotList.Bot b = list.get(i);
            labels[i] = (b.online() ? "[on] " : "[off] ") + bots.list().name(b, policy.hideNames());
            if (b.botId().equals(picked)) current = labels[i];
        }
        WDropdown<String> select = row.add(theme.dropdown(labels, current == null ? labels[0] : current)).widget();
        select.action = () -> {
            String chosen = select.get();
            for (int i = 0; i < labels.length; i++) {
                if (labels[i].equals(chosen)) {
                    String id = list.get(i).botId();
                    later(() -> {
                        if (!id.equals(bots.selected())) {
                            dropConfirm();
                            bots.select(id);
                            headerSignature = "";
                            rebuild();
                        }
                    });
                }
            }
        };
    }

    /** "Indexed 5 min ago" and the containers, dimension and Minecraft version of the stash picked, as the website's info row says it. */
    private String infoText() {
        StashRef s = selected();
        if (s == null) return source.stashes.isEmpty() ? "" : "Pick a stash.";
        return HeaderInfo.indexLine(s, loadedWant != null ? source.index : null, policy().forced(), System.currentTimeMillis());
    }

    private void refreshBotStatus() {
        if (botStatus == null) return;
        StashRef s = selected();
        botStatus.set(HeaderInfo.botStatus(s, policy().hideNames(), System.currentTimeMillis()), s != null && s.online());
    }

    /** Enter pressed in the chat to run the command must not submit an empty form on the new screen. */
    private void enter(Runnable submit) {
        if (System.currentTimeMillis() - shownAt >= ENTER_GRACE_MS) submit.run();
    }

    private void buildMain() {
        if (url != null && !url.secure() && !url.localHost()) {
            body.add(com.porter.qwerte.manager.gui.style.Ui.wrap(theme, "Warning: plain http outside your network (allow-insecure-http is on). Your session is sent unencrypted.", com.porter.qwerte.manager.gui.style.PageRoom.width(body, 0)))
                .expandX().widget().color(warn());
        }

        if (tab.webOnly()) {
            // No in-game version: say so, and where the page is (the address is hidden while streamer mode is on)
            body.add(com.porter.qwerte.manager.gui.style.Ui.wrap(theme, tab.page.websiteNote(url == null || policy().hideServer() ? null : url.origin()), com.porter.qwerte.manager.gui.style.PageRoom.width(body, 0))).expandX();
            return;
        }
        if (tab == Tab.ORDERS) {
            if (!auth.can("view")) {
                body.add(theme.label("Your account has no access to stashes. Ask the owner.")).expandX();
            } else if (currentBackend() == serverOrders && selected() == null) {
                body.add(theme.label("Pick a stash first.")).expandX();
            } else {
                orders.setBackend(currentBackend());
                orders.build(body, theme);
            }
            return;
        }
        if (tab == Tab.KITS) {
            if (!auth.can("view")) {
                body.add(theme.label("Your account has no access to stashes. Ask the owner.")).expandX();
            } else {
                kitsTab.build(body, theme);
            }
            return;
        }
        if (tab == Tab.AREA) {
            if (!auth.can("view")) {
                body.add(theme.label("Your account has no access to stashes. Ask the owner.")).expandX();
            } else {
                areaTab.build(body, theme);
            }
            return;
        }
        if (tab == Tab.MAP) {
            mapTab.build(body, theme);
            return;
        }
        if (tab == Tab.SETTINGS) {
            settingsTab.build(body, theme);
            return;
        }
        if (tab == Tab.PROXY) {
            proxyTab.build(body, theme);
            return;
        }
        if (tab == Tab.BOTS) {
            botsTab.build(body, theme);
            return;
        }
        if (tab == Tab.ACTIVITY) {
            activityTab.build(body, theme);
            return;
        }
        if (tab == Tab.CONSOLE) {
            consoleTab.build(body, theme);
            return;
        }
        if (tab == Tab.PLANNER) {
            plannerTab.build(body, theme);
            return;
        }
        if (!auth.can("view")) {
            body.add(theme.label("Your account has no access to stashes. Ask the owner.")).expandX();
            return;
        }
        browse.build(body, theme);
    }

    /** What the Map, Settings and Users tabs get from the window. */
    /** What the Area page needs from the window (it has no server tab host in local mode). */
    private final class AreaHost implements AreaTab.Host {
        @Override
        public void later(Runnable r) {
            StashManagerScreen.this.later(r);
        }

        @Override
        public void rebuild() {
            StashManagerScreen.this.rebuild();
        }

        @Override
        public void notice(String text) {
            StashManagerScreen.this.notice(text);
        }

        @Override
        public boolean closed() {
            return closed;
        }
    }

    private final class TabHost implements MapTab.Host {
        @Override
        public com.porter.qwerte.manager.net.SmClient client() {
            return client;
        }

        @Override
        public AuthState auth() {
            return auth;
        }

        @Override
        public DisplayPolicy policy() {
            return StashManagerScreen.this.policy();
        }

        @Override
        public GuiGraphics graphics() {
            return graphics;
        }

        @Override
        public void later(Runnable r) {
            StashManagerScreen.this.later(r);
        }

        @Override
        public void notice(String text) {
            StashManagerScreen.this.notice(text);
        }

        @Override
        public boolean closed() {
            return closed;
        }

        @Override
        public BotDirectory bots() {
            return bots;
        }

        @Override
        public List<StashRef> stashes() {
            return source.stashes;
        }

        @Override
        public void write(String path, JsonObject body, java.util.function.Consumer<ApiResult> done) {
            sendWrite(path, body, done, true);
        }

        @Override
        public void read(String path, Map<String, String> query, java.util.function.Consumer<ApiResult> done) {
            client.get(path, query, r -> {
                if (r.kind() == ApiError.Kind.CONFIRM) {
                    askConfirm("This needs your password again. It is not asked for again for 15 minutes.", () -> client.get(path, query, done), () -> done.accept(ApiResult.cancelled()));
                    return;
                }
                done.accept(r);
            });
        }

        @Override
        public void confirm(String why, Runnable retry) {
            askConfirm(why, retry);
        }

        @Override
        public void failed(ApiResult r) {
            fail(r);
        }

        @Override
        public void refreshAuth() {
            nextAuthPoll = 0;
        }

        @Override
        public void openPage(NavPage page, String botId) {
            StashManagerScreen.this.openPage(page, botId);
        }

        @Override
        public StashRef selectedStash() {
            return selected();
        }

        @Override
        public void showStash(StashRef stash) {
            StashManagerScreen.this.showStash(stash);
        }

        @Override
        public void refreshStashes() {
            nextStashPoll = 0;
        }

        @Override
        public void rebuild() {
            StashManagerScreen.this.rebuild();
        }

        @Override
        public void logout() {
            StashManagerScreen.this.logout();
        }

        @Override
        public void logoutEverywhere() {
            client.post("/api/v1/auth/logout-all", new JsonObject(), r -> {
                if (closed) return;
                if (r.ok()) {
                    quietLogout = true;
                    module.dropSession("logout-all");
                } else {
                    fail(r);
                }
            });
        }

        @Override
        public String describe(ApiResult r, String fallback) {
            return StashManagerScreen.describe(r, fallback);
        }

        @Override
        public WindowTheme windowTheme() {
            return module.windowTheme();
        }

        @Override
        public void setWindowTheme(WindowTheme theme) {
            module.setWindowTheme(theme);
        }

        @Override
        public String viewerId(String botId) {
            return module.viewerId(botId);
        }
    }

    /** Goes to the window's page for {@code page} with {@code botId} picked (a bot card's buttons). Ignored when the account can't see it. */
    private void openPage(NavPage page, String botId) {
        if (local || closed || auth == null || bots == null) return;
        if (botId != null) {
            if (bots.list().find(botId) == null) {
                notice("That bot is no longer in the list.");
                return;
            }
            if (!botId.equals(bots.selected())) dropConfirm();
            bots.select(botId);
            headerSignature = "";
        }
        for (Tab t : Tab.values()) {
            if (t.page == page && tabVisible(t)) {
                dropConfirm();
                tab = t;
                rebuild();
                return;
            }
        }
        notice("Your account can't open " + page.title() + " for this bot.");
    }

    /** Picks the stash and shows the Stash page (a bot card's stash link). */
    private void showStash(StashRef stash) {
        if (local || closed || stash == null || find(stash.key()) == null) return;
        if (!stash.key().equals(selectedKey)) {
            selectedKey = stash.key();
            module.rememberStash(selectedKey);
            onStashChanged();
            maybeLoadIndex();
        }
        dropConfirm();
        tab = Tab.BROWSE;
        headerSignature = headerSignature();
        rebuild();
    }

    private boolean tabVisible(Tab t) {
        if (t == Tab.KITS) return kitsOffered();
        if (t == Tab.AREA) return areaOffered();
        return navTabs().contains(t);
    }

    // ---------- local mode ----------

    private void buildLocal() {
        if (tab == Tab.ORDERS) {
            // Works without a server: this client carries the order out
            orders.setBackend(localOrders);
            orders.build(body, theme);
            return;
        }
        if (tab == Tab.SETTINGS) {
            // Works without a server: the themes
            AppearanceCard.build(body, theme, module.windowTheme(), module::setWindowTheme);
            return;
        }
        if (tab == Tab.AREA) {
            areaTab.build(body, theme);
            return;
        }
        if (tab != Tab.BROWSE) {
            body.add(com.porter.qwerte.manager.gui.style.Ui.wrap(theme, NEEDS_SERVER, com.porter.qwerte.manager.gui.style.PageRoom.width(body, 0))).expandX();
            return;
        }
        browse.build(body, theme);
    }

    /** Local mode: which page of the local index (a dimension, or all of them) is shown. */
    private void buildLocalPicker(WFlow row, DisplayPolicy policy) {
        List<StashRef> list = localSource.stashes();
        barLabel(row, "Local index", false);
        if (list.isEmpty()) return;
        String[] labels = new String[list.size()];
        String current = null;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = LocalSource.label(list.get(i), policy.hideServer());
            if (list.get(i).stash().equals(localSource.selected())) current = labels[i];
        }
        WDropdown<String> dropdown = row.add(theme.dropdown(labels, current == null ? labels[0] : current)).widget();
        dropdown.action = () -> {
            String picked = dropdown.get();
            for (int i = 0; i < labels.length; i++) {
                if (labels[i].equals(picked) && !list.get(i).stash().equals(localSource.selected())) {
                    String stash = list.get(i).stash();
                    later(() -> {
                        localSource.select(stash);
                        browse.dataChanged(true);
                    });
                }
            }
        };
    }

    private void clearClicked() {
        long now = System.currentTimeMillis();
        if (now > clearArmedUntil) {
            clearArmedUntil = now + RESCAN_ARM_MS;
            if (clearButton != null) clearButton.set("Confirm clear?");
            setStatus("Click again to forget everything stored for this server.");
            return;
        }
        clearArmedUntil = 0;
        if (clearButton != null) clearButton.set("Clear local index");
        localSource.clearAll();
        browse.dataChanged(true);
        setStatus("");
    }

    private void buildStashRow(WFlow row, DisplayPolicy policy) {
        List<StashRef> list = source.stashes;
        barLabel(row, "Stash", false);
        if (list.isEmpty()) {
            barLabel(row, auth.can("view") ? "No stashes yet. Turn on stash-manager in the addon." : "", false);
            dropdownKeys = List.of();
            return;
        }
        String[] labels = new String[list.size()];
        List<String> keys = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        String current = null;
        for (int i = 0; i < list.size(); i++) {
            StashRef s = list.get(i);
            String label = s.label(policy.streamer());
            int n = seen.merge(label, 1, Integer::sum);
            labels[i] = n > 1 ? label + " (" + n + ")" : label;
            keys.add(s.key());
            if (s.key().equals(selectedKey)) current = labels[i];
        }
        dropdownKeys = keys;
        WDropdown<String> dropdown = row.add(theme.dropdown(labels, current == null ? labels[0] : current)).widget();
        dropdown.action = () -> {
            String picked = dropdown.get();
            for (int i = 0; i < labels.length; i++) {
                if (labels[i].equals(picked) && !keys.get(i).equals(selectedKey)) {
                    selectedKey = keys.get(i);
                    module.rememberStash(selectedKey);
                    later(() -> {
                        onStashChanged();
                        headerSignature = headerSignature();
                        maybeLoadIndex();
                        updateRescanLabel();
                    });
                }
            }
        };
    }

    /** Changes when the stash list, its labels or the selection change, so the header is only rebuilt then. */
    private String headerSignature() {
        StringBuilder sb = new StringBuilder(String.valueOf(selectedKey)).append('|').append(policy().streamer());
        for (StashRef s : source.stashes) sb.append('|').append(s.key()).append(s.label(policy().streamer()));
        if (bots != null) {
            // The Bot select and the page buttons depend on the dashboard bots and what the picked one reports
            sb.append("|bots:").append(bots.selected());
            for (BotList.Bot b : bots.list().bots()) sb.append(b.botId()).append(b.online()).append(b.hasCap("activity")).append(b.player());
            if (auth != null && auth.loggedIn()) sb.append(navTabs());
        }
        return sb.toString();
    }
}

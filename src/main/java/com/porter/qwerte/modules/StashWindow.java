package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.manager.gui.ItemStacks;
import com.porter.qwerte.manager.gui.StashManagerScreen;
import com.porter.qwerte.manager.gui.frame.WindowGeometry;
import com.porter.qwerte.manager.gui.style.SmGuiTheme;
import com.porter.qwerte.manager.gui.style.TextScale;
import com.porter.qwerte.manager.gui.style.WindowTheme;
import com.porter.qwerte.manager.local.LocalCapture;
import com.porter.qwerte.manager.local.LocalHub;
import com.porter.qwerte.manager.local.LocalOrderStore;
import com.porter.qwerte.manager.local.LocalSafety;
import com.porter.qwerte.manager.local.LocalStore;
import com.porter.qwerte.manager.model.Cart;
import com.porter.qwerte.manager.model.NamesDraft;
import com.porter.qwerte.manager.model.OrderMode;
import com.porter.qwerte.manager.net.ServerUrl;
import com.porter.qwerte.manager.net.Session;
import com.porter.qwerte.manager.net.SmClient;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * An in-game window for your stash. By default it works on its own: it remembers the containers you open and lets you
 * search them (local mode, no network). Optionally it can connect to a Stash Manager server, log in as a website user
 * and show what that user may see ({@link StashManagerScreen}).
 *
 * <p>The session token is a secret. It is kept in memory and in this module's saved data only ({@link #toTag}), never
 * as a setting, never drawn, logged or sent to chat, and is only sent to the origin it was issued for. While
 * connect-to-server is off the client makes no request at all and holds no token; a saved session is kept (bound to its
 * address) and used again when the setting is turned on.
 */
public class StashWindow extends Module {
    private static final String SESSION_KEY = "qwerteSession";
    private static final String GEOMETRY_KEY = "qwerteWindow";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLocal = settings.createGroup("Local index");
    private final SettingGroup sgWindow = settings.createGroup("Window");

    /** How big the window's pixels are: the Minecraft GUI scale, or a fixed number of screen pixels per GUI pixel. */
    public enum WindowScale {
        AUTO("Auto", 0), X1("1x", 1), X2("2x", 2), X3("3x", 3), X4("4x", 4);

        private final String label;
        private final int unit;

        WindowScale(String label, int unit) {
            this.label = label;
            this.unit = unit;
        }

        /** Physical pixels per GUI pixel; 0 follows Minecraft's GUI scale. */
        public int unit() {
            return unit;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final Setting<Boolean> connect = sgGeneral.add(new BoolSetting.Builder()
        .name("connect-to-server")
        .description("Connect to a Stash Manager server (optional). Off: the window only uses the containers this game has seen, and nothing goes over the network.")
        .defaultValue(false)
        .onChanged(on -> syncClient())
        .build()
    );

    private final Setting<String> serverUrl = sgGeneral.add(new StringSetting.Builder()
        .name("server-url")
        .description("Address of your Stash Manager server, like http://192.168.1.20:8765.")
        .defaultValue("")
        .visible(() -> connect.get())
        .build()
    );

    private final Setting<Boolean> allowInsecure = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-insecure-http")
        .description("Allows plain http:// to an address that is not on your own network. Your password and session are then sent unencrypted. Leave off unless you know why you need it.")
        .defaultValue(false)
        .visible(() -> connect.get())
        .build()
    );

    private final Setting<Keybind> openKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("open-window")
        .description("Opens the Stash Window while this module is on. The .stash-window command and the button below work any time.")
        .defaultValue(Keybind.none())
        .action(this::openFromKey)
        .build()
    );

    private final Setting<Boolean> streamerDisplay = sgGeneral.add(new BoolSetting.Builder()
        .name("streamer-display")
        .description("Hides coordinates, server addresses and player names in the window. Always on if the owner forced streamer mode for your account.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> localCapture = sgLocal.add(new BoolSetting.Builder()
        .name("local-capture")
        .description("Remember the contents of chests, barrels and shulker boxes you open (local mode only).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> localMax = sgLocal.add(new IntSetting.Builder()
        .name("local-max-containers")
        .description("The most containers remembered per server; the ones read longest ago are forgotten first.")
        .defaultValue(LocalStore.DEFAULT_CAP)
        .range(LocalStore.MIN_CAP, LocalStore.DEFAULT_CAP)
        .sliderRange(LocalStore.MIN_CAP, LocalStore.DEFAULT_CAP)
        .build()
    );

    private final SettingGroup sgOrders = settings.createGroup("Local orders");

    private final Setting<Integer> orderPlayerRadius = sgOrders.add(new IntSetting.Builder()
        .name("order-player-radius")
        .description("An order this client carries out itself stops when another player comes within this many blocks. 0 turns the check off.")
        .defaultValue(6)
        .range(0, 64)
        .sliderRange(0, 32)
        .build()
    );

    private final Setting<Integer> orderHostileRadius = sgOrders.add(new IntSetting.Builder()
        .name("order-hostile-radius")
        .description("An order this client carries out itself stops when a hostile mob comes within this many blocks. 0 turns the check off.")
        .defaultValue(4)
        .range(0, 64)
        .sliderRange(0, 32)
        .build()
    );

    private final Setting<Integer> orderTimeout = sgOrders.add(new IntSetting.Builder()
        .name("order-timeout-minutes")
        .description("An order this client carries out itself stops when it has taken longer than this.")
        .defaultValue(30)
        .range(1, 480)
        .sliderRange(5, 120)
        .build()
    );

    private final Setting<Boolean> orderChat = sgOrders.add(new BoolSetting.Builder()
        .name("order-messages")
        .description("Tell you in chat about orders this client carries out itself (the sorter's own order messages, and when one ends or is stopped).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> orderAbortHealth = sgOrders.add(new IntSetting.Builder()
        .name("order-abort-health")
        .description("Stop a local order when your health drops to this or below. 0: off.")
        .defaultValue(0)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<WindowTheme> windowTheme = sgWindow.add(new EnumSetting.Builder<WindowTheme>()
        .name("window-theme")
        .description("The colours of the Stash Window: the website's themes (Classic is the website's default), plus Mac. Also changed by the Theme select in the window's header.")
        .defaultValue(WindowTheme.DEFAULT)
        .build()
    );

    private final Setting<WindowScale> windowScale = sgWindow.add(new EnumSetting.Builder<WindowScale>()
        .name("window-scale")
        .description("Size of the Stash Window: Auto follows Minecraft's GUI scale (Options > Video Settings), the others fix it. Meteor's own GUI scale does not apply to this window.")
        .defaultValue(WindowScale.AUTO)
        .build()
    );

    private final Setting<TextScale> windowText = sgWindow.add(new EnumSetting.Builder<TextScale>()
        .name("window-text-scale")
        .description("Size of the text in the Stash Window next to its slots. Auto is about the size of the website's text; Normal is the game's own font size.")
        .defaultValue(TextScale.AUTO)
        .build()
    );

    private final LocalOrderStore orderStore = new LocalOrderStore(MeteorClient.FOLDER.toPath().resolve("qwerte").resolve("local-orders"));
    private final LocalOrders localOrders = new LocalOrders(orderStore,
        () -> new LocalOrders.Settings(new LocalSafety.Limits(orderPlayerRadius.get(), orderHostileRadius.get(), orderTimeout.get()), orderChat.get(), orderAbortHealth.get()),
        new LocalOrders.Chat() {
            @Override
            public void info(String text) {
                if (orderChat.get()) StashWindow.this.info("%s", text);
            }

            @Override
            public void warn(String text) {
                // Stops and failures are always said
                StashWindow.this.warning("%s", text);
            }
        });
    private final Map<String, Cart> carts = new HashMap<>();
    private final Map<String, NamesDraft> namesDrafts = new HashMap<>();
    private OrderMode orderMode;
    /** Where the window was left, in GUI pixels; null until it was moved or resized. Saved with the module's data. */
    private WindowGeometry windowGeometry;
    private boolean geometryDirty;

    // Read from the HTTP threads' callbacks only through the client thread, but cheap to make safe
    private volatile Session session;
    private volatile SmClient client;
    private volatile SmClient backgroundClient;
    private volatile Runnable sessionListener;
    private String lastUser = "";
    private String lastStash;
    private String lastBot;
    private final Map<String, String> viewerIds = new HashMap<>();

    private final LocalHub hub = new LocalHub(() -> localMax.get());
    private final LocalCapture capture = new LocalCapture(hub, () -> localCapture.get() && !connect.get());

    public StashWindow() {
        super(QwerteAddon.CATEGORY, "stash-window", "A window to browse your stash from in game. Works on its own; a Stash Manager server is optional.");
    }

    /** The module, found the same way for the command and the window. */
    public static StashWindow get() {
        return Modules.get().get(StashWindow.class);
    }

    @Override
    public void onActivate() {
        MeteorClient.EVENT_BUS.subscribe(capture);
    }

    @Override
    public void onDeactivate() {
        MeteorClient.EVENT_BUS.unsubscribe(capture);
        hub.save();
        // Turning the module off stops an order this client is carrying out (it is parked, like stash-manager's deactivate)
        localOrders.cancel("stopped: stash-window was turned off");
        orderStore.flush();
    }

    public LocalHub hub() {
        return hub;
    }

    /** Says something in chat. */
    public void tell(String text) {
        info("%s", text);
    }

    /** Orders carried out by this client (no server). */
    public LocalOrders localOrders() {
        return localOrders;
    }

    /** The cart being put together for a stash (any key the window uses); kept while the game runs. */
    public Cart cart(String key) {
        return carts.computeIfAbsent(key, k -> new Cart());
    }

    public NamesDraft names(String key) {
        return namesDrafts.computeIfAbsent(key, k -> new NamesDraft());
    }

    /** Who carries out new orders: the server's bots by default when connected, else this client. */
    public OrderMode orderMode() {
        return OrderMode.choose(connect.get(), orderMode);
    }

    public void setOrderMode(OrderMode mode) {
        this.orderMode = mode;
    }

    // ---------- opening ----------

    private void openFromKey() {
        if (mc.screen == null) openLater();
    }

    /** Opens the window as soon as no other screen is up (from the keybind or a chat command). */
    public void openLater() {
        Utils.screenToOpen = new StashManagerScreen(newTheme(), this);
    }

    /** Opens the window right now, on top of the current screen (the module's own settings screen). */
    public void openNow() {
        mc.setScreen(new StashManagerScreen(newTheme(), this));
    }

    /** The look of one window: Meteor's widgets with this module's theme and scale settings. Not registered with Meteor. */
    private SmGuiTheme newTheme() {
        return new SmGuiTheme(() -> windowScale.get().unit(), windowTheme::get, windowText::get);
    }

    // ---------- window look and place ----------

    public WindowTheme windowTheme() {
        return windowTheme.get();
    }

    public void setWindowTheme(WindowTheme theme) {
        windowTheme.set(theme);
    }

    /** The saved place of the window, or null when it was never moved. */
    public WindowGeometry windowGeometry() {
        return windowGeometry;
    }

    /** Remembers the window's place (kept in memory while it moves; saved by {@link #flushWindowGeometry}). */
    public void setWindowGeometry(WindowGeometry g) {
        if (g == null || g.equals(windowGeometry)) return;
        windowGeometry = g;
        geometryDirty = true;
    }

    /** Saves the module's data if the window's place changed since the last save. */
    public void flushWindowGeometry() {
        if (!geometryDirty) return;
        geometryDirty = false;
        Modules.get().save();
    }

    // ---------- settings ----------

    /** True when the user chose to connect to a Stash Manager server. Everything server-related is off otherwise. */
    public boolean connectToServer() {
        return connect.get();
    }

    public boolean allowInsecureHttp() {
        return allowInsecure.get();
    }

    public String rawUrl() {
        return serverUrl.get();
    }

    /** Null when no address is set or it isn't a valid one. */
    public ServerUrl url() {
        return ServerUrl.parse(serverUrl.get());
    }

    /** True for plain http to an address outside the user's own network: refused unless allowed. */
    public boolean insecureRefused(ServerUrl url) {
        return url != null && !url.secure() && !url.localHost() && !allowInsecure.get();
    }

    public boolean streamerDisplay() {
        return streamerDisplay.get();
    }

    public void setStreamerDisplay(boolean on) {
        streamerDisplay.set(on);
    }

    // ---------- connection and session ----------

    /**
     * The client for the server. It makes no request, and holds no token, while connect-to-server is off. Client
     * thread only (like the screen and the settings).
     */
    public SmClient client() {
        if (client == null) {
            SmClient created = new SmClient(mc::execute);
            created.onUnauthorized(() -> dropSession("401"));
            created.setAllowed(u -> !insecureRefused(u));
            client = created;
            syncClient();
        }
        return client;
    }

    /**
     * A second client for work the window does not own (the Printer's stash orders): it shares the address, the connect setting
     * and the session of {@link #client()}, but closing the window does not drop its answers. It never asks for a password.
     * Client thread only. It is kept in step by {@link #syncClient()}, {@link #dropSession} and every call of this method.
     */
    public SmClient backgroundClient() {
        if (backgroundClient == null) {
            SmClient created = new SmClient(mc::execute);
            created.onUnauthorized(() -> dropSession("401"));
            created.setAllowed(u -> !insecureRefused(u));
            backgroundClient = created;
        }
        syncBackground();
        return backgroundClient;
    }

    /**
     * Brings the background client in line with the connect setting, the address and the session. Each change that bumps its
     * generation (and so drops answers on the way) is made only when the value really differs.
     */
    private void syncBackground() {
        SmClient c = backgroundClient;
        if (c == null) return;
        boolean on = connect.get();
        if (c.enabled() != on) c.setEnabled(on);
        ServerUrl url = on ? url() : null;
        ServerUrl current = c.base();
        boolean same = url == null ? current == null : current != null && current.origin().equals(url.origin());
        if (!same) c.setBase(url);
        Session s = on ? session() : null;
        if (s != null) c.setToken(s.token(), s.origin());
        else c.setToken(null, null);
    }

    /** Brings the client in line with the connect setting and the session. */
    private void syncClient() {
        SmClient c = client;
        if (c != null) {
            boolean on = connect.get();
            c.setEnabled(on);
            Session s = session;
            if (on && s != null) c.setToken(s.token(), s.origin());
            else c.setToken(null, null);
        }
        if (backgroundClient != null) syncBackground();
    }

    /**
     * The saved session if it was issued for the address now set; otherwise it is dropped (a token is never kept for
     * another origin). Null, and untouched, while connect-to-server is off.
     */
    public Session session() {
        if (!connect.get() || session == null) return null;
        ServerUrl url = url();
        if (url == null || !url.origin().equals(session.origin())) {
            dropSession("address changed");
            return null;
        }
        return session;
    }

    /** Remembers a new session and saves it now, so a crash can't bring back an older one. */
    public void setSession(Session next) {
        this.session = next;
        if (next != null) lastUser = next.user() == null ? "" : next.user();
        syncClient();
        client().bumpGeneration();
        Modules.get().save();
    }

    /** Forgets the session (logout, 401, another address). {@code why} is for the log only and never holds a secret. */
    public void dropSession(String why) {
        boolean had = session != null;
        session = null;
        SmClient c = client;
        if (c != null) {
            c.setToken(null, null);
            c.bumpGeneration();
        }
        SmClient bg = backgroundClient;
        if (bg != null) {
            bg.setToken(null, null);
            bg.bumpGeneration();
        }
        viewerIds.clear();
        // Whatever the account saw must not linger in the icon cache
        ItemStacks.clear();
        if (had) {
            QwerteAddon.LOG.debug("stash-window: session dropped ({})", why);
            Modules.get().save();
        }
        Runnable listener = sessionListener;
        if (listener != null) listener.run();
    }

    /** The open window hears when the session was dropped (for example by a 401). */
    public void setSessionListener(Runnable listener) {
        this.sessionListener = listener;
    }

    public String lastUser() {
        return lastUser;
    }

    public String lastStash() {
        return lastStash;
    }

    public void rememberStash(String key) {
        this.lastStash = key;
    }

    public String lastBot() {
        return lastBot;
    }

    public void rememberBot(String id) {
        this.lastBot = id;
    }

    /** The live viewer's page id for a bot: 16 random hex digits, the same for the whole login (the server allows only about 20 new ones an hour). */
    public String viewerId(String botId) {
        return viewerIds.computeIfAbsent(botId, k -> {
            byte[] b = new byte[8];
            new java.security.SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        });
    }

    // ---------- saved data ----------

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = super.toTag();
        // Only a session that belongs to the address set now is saved
        Session current = session;
        ServerUrl url = url();
        if (tag != null && current != null && url != null && url.origin().equals(current.origin())) {
            CompoundTag s = new CompoundTag();
            s.putString("token", current.token());
            s.putString("origin", current.origin());
            s.putString("user", current.user() == null ? "" : current.user());
            tag.put(SESSION_KEY, s);
        }
        if (tag != null && windowGeometry != null) tag.putString(GEOMETRY_KEY, windowGeometry.encode());
        return tag;
    }

    @Override
    public Module fromTag(CompoundTag tag) {
        // Read it first: super.fromTag may toggle the module on
        Session loaded = null;
        Tag saved = tag.get(SESSION_KEY);
        if (saved instanceof CompoundTag s) {
            String token = string(s.get("token"));
            String origin = string(s.get("origin"));
            String user = string(s.get("user"));
            if (token != null && !token.isEmpty() && origin != null && !origin.isEmpty()) {
                loaded = new Session(token, origin, user == null ? "" : user);
            }
        }
        // A malformed value is ignored: the window then opens in its default place
        windowGeometry = WindowGeometry.decode(string(tag.get(GEOMETRY_KEY)));
        geometryDirty = false;
        Module result = super.fromTag(tag);
        ServerUrl url = url();
        // Not for plain http outside the local network, unless that was allowed
        if (loaded != null && url != null && url.origin().equals(loaded.origin()) && !insecureRefused(url)) {
            session = loaded;
            lastUser = loaded.user();
        } else {
            session = null;
        }
        syncClient();
        return result;
    }

    /** String access on tags changed between Minecraft versions; reading through NbtOps works on all. */
    private static String string(Tag tag) {
        if (tag == null) return null;
        return NbtOps.INSTANCE.getStringValue(tag).result().orElse(null);
    }

    // ---------- module settings widget ----------

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WLabel status = list.add(theme.label(statusText())).expandX().widget();

        WHorizontalList buttons = list.add(theme.horizontalList()).widget();
        WButton open = buttons.add(theme.button("Open window")).widget();
        open.action = this::openNow;

        if (connect.get()) {
            WButton copy = buttons.add(theme.button("Use stash-manager's URL")).widget();
            copy.tooltip = "Copies the server-url of the stash-manager module into this module.";
            copy.action = () -> {
                Module manager = Modules.get().get(StashManager.class);
                Setting<?> other = manager == null ? null : manager.settings.get("server-url");
                Object value = other == null ? null : other.get();
                if (value instanceof String url && !url.isBlank()) {
                    serverUrl.set(url.trim());
                    status.set(statusText());
                } else {
                    status.set("The stash-manager module has no server-url set.");
                }
            };
        }

        WButton refresh = buttons.add(theme.button("Refresh")).widget();
        refresh.action = () -> status.set(statusText());
        return list;
    }

    /** Never includes the token. */
    private String statusText() {
        if (!connect.get()) return "Local mode: the window uses the containers this game has seen. No server connection.";
        String raw = serverUrl.get();
        if (raw == null || raw.isBlank()) return "Connect to server is on, but no server-url is set.";
        ServerUrl url = url();
        if (url == null) return "The server-url is not a valid http:// or https:// address.";
        if (insecureRefused(url)) return "Plain http to an address outside your network is refused. Use https://, or turn on allow-insecure-http.";
        Session s = session();
        return s == null ? "Not logged in. Open the window to log in." : "Logged in as " + (s.user().isBlank() ? "owner" : s.user()) + ".";
    }
}

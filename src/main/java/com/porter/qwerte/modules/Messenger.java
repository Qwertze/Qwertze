package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.mixin.ChatScreenAccessor;
import com.porter.qwerte.messenger.ChatClicks;
import com.porter.qwerte.messenger.ChatKeyRoute;
import com.porter.qwerte.messenger.ChatLines;
import com.porter.qwerte.messenger.ConversationStore;
import com.porter.qwerte.messenger.DmFormat;
import com.porter.qwerte.messenger.DmParser;
import com.porter.qwerte.messenger.DmPattern;
import com.porter.qwerte.messenger.Geometry;
import com.porter.qwerte.messenger.HistoryJson;
import com.porter.qwerte.messenger.MessengerCore;
import com.porter.qwerte.messenger.MessengerScreen;
import com.porter.qwerte.messenger.MessengerUi;
import com.porter.qwerte.messenger.OnlineNames;
import com.porter.qwerte.messenger.Outbox;
import com.porter.qwerte.messenger.ServerStores;
import com.porter.qwerte.messenger.SendText;
import com.porter.qwerte.messenger.UserPatterns;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.sounds.SoundEvents;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Reads the whispers you get (and send) from chat into a small messenger window: a conversation list, a history per
 * player, an input line and an emoji picker. The window is drawn over the game like a HUD element and takes the
 * keyboard and mouse only when you focus it (the keybind, or a click on it while the chat is open); Esc or the keybind
 * lets go. Nothing is ever sent unless you press Enter or Send in the window, and then only what you typed: as a
 * whisper command to the open player, or in Global as public chat (a text starting with / as a command), one message
 * per interval.
 *
 * <p>History is kept in memory. With save-history on it is also written to one JSON file in the addon's folder
 * ({@code meteor-client/qwerte/messenger-history.json}); it is never sent anywhere.
 */
public class Messenger extends Module {
    private static final String GEOMETRY_KEY = "qwerteMessengerWindow";
    /** Changed whispers are written after this long; the public chat alone only after the longer one (or on leaving). */
    private static final long SAVE_EVERY_MS = 20_000;
    private static final long SAVE_GLOBAL_EVERY_MS = 5 * 60_000;

    private final SettingGroup sgPatterns = settings.createGroup("Patterns");
    private final SettingGroup sgSending = settings.createGroup("Sending");
    private final SettingGroup sgHistory = settings.createGroup("History");
    private final SettingGroup sgWindow = settings.createGroup("Window");

    // ---------- patterns ----------

    private final Setting<Boolean> fmtVanilla = sgPatterns.add(new BoolSetting.Builder()
        .name("pattern-vanilla")
        .description("Vanilla whispers: \"X whispers to you: ...\" and \"You whisper to X: ...\".")
        .defaultValue(true)
        .onChanged(v -> rebuildParser())
        .build()
    );

    private final Setting<Boolean> fmt2b2t = sgPatterns.add(new BoolSetting.Builder()
        .name("pattern-2b2t")
        .description("2b2t whispers: \"X whispers: ...\" and \"to X: ...\".")
        .defaultValue(true)
        .onChanged(v -> rebuildParser())
        .build()
    );

    private final Setting<Boolean> fmtEssentials = sgPatterns.add(new BoolSetting.Builder()
        .name("pattern-essentials")
        .description("Essentials whispers: \"[X -> me] ...\" and \"[me -> X] ...\".")
        .defaultValue(true)
        .onChanged(v -> rebuildParser())
        .build()
    );

    private final Setting<Boolean> fmtFromTo = sgPatterns.add(new BoolSetting.Builder()
        .name("pattern-from-to")
        .description("\"From X: ...\" and \"To X: ...\".")
        .defaultValue(true)
        .onChanged(v -> rebuildParser())
        .build()
    );

    private final Setting<List<String>> extraPatterns = sgPatterns.add(new StringListSetting.Builder()
        .name("extra-patterns")
        .description("Your own regular expressions, one per line, for a server with another format. Start with in: (a message to you, the default) or out: (a message you sent). "
            + "The whole chat line must match, with the groups (?<name>...) for the other player and (?<msg>...) for the text. "
            + "Example: in:\\[(?<name>\\w{3,16}) whispers\\] (?<msg>.*). A pattern that is invalid or slow is refused and said in chat.")
        .defaultValue(new ArrayList<>())
        .onChanged(v -> rebuildParser())
        .build()
    );

    // ---------- sending ----------

    private final Setting<String> template = sgSending.add(new StringSetting.Builder()
        .name("command-template")
        .description("The command that sends a whisper. {name} is the player, {text} what you typed. It must be exactly /command {name} {text}, with nothing else in it. Vanilla: /msg {name} {text}. On 2b2t use /w {name} {text}.")
        .defaultValue(SendText.DEFAULT_TEMPLATE)
        .onChanged(v -> {
            this.core.template(v);
            if (isActive()) checkTemplate(v);
        })
        .build()
    );

    private final Setting<Integer> interval = sgSending.add(new IntSetting.Builder()
        .name("send-interval-ms")
        .description("At most one message is sent per this many milliseconds (up to " + Outbox.MAX_QUEUE + " wait; more are refused).")
        .defaultValue(1500)
        .range(Outbox.MIN_INTERVAL, Outbox.MAX_INTERVAL)
        .sliderRange(Outbox.MIN_INTERVAL, 5000)
        .onChanged(v -> this.core.intervalMs(v))
        .build()
    );

    // ---------- history ----------

    private final Setting<Boolean> saveHistory = sgHistory.add(new BoolSetting.Builder()
        .name("save-history")
        .description("Keeps the conversations in a file in the addon's folder (meteor-client/qwerte/messenger-history.json) so they are there next time. Stays on this computer; never sent anywhere.")
        .defaultValue(false)
        .onChanged(on -> {
            if (!isActive()) return;
            if (on) {
                loadHistory();
                this.core.stores().touch();
            }
        })
        .build()
    );

    private final Setting<Integer> maxHistory = sgHistory.add(new IntSetting.Builder()
        .name("max-history")
        .description("The most messages kept per player; the oldest are dropped.")
        .defaultValue(200)
        .range(ConversationStore.MIN_CAP, ConversationStore.MAX_CAP)
        .sliderRange(ConversationStore.MIN_CAP, ConversationStore.MAX_CAP)
        .onChanged(v -> this.core.stores().cap(v))
        .build()
    );

    private final Setting<Integer> globalScrollback = sgHistory.add(new IntSetting.Builder()
        .name("global-scrollback")
        .description("The most public chat lines kept in the Global view per server; the oldest are dropped. Saved with save-history too.")
        .defaultValue(ConversationStore.DEFAULT_GLOBAL_CAP)
        .range(ConversationStore.MIN_GLOBAL_CAP, ConversationStore.MAX_GLOBAL_CAP)
        .sliderRange(ConversationStore.MIN_GLOBAL_CAP, 1000)
        .onChanged(v -> this.core.stores().globalCap(v))
        .build()
    );

    // ---------- window ----------

    private final Setting<Keybind> focusKey = sgWindow.add(new KeybindSetting.Builder()
        .name("focus-key")
        .description("Takes the keyboard and mouse for the Messenger window, and lets go again (a key that is not a letter or digit; Esc always lets go).")
        .defaultValue(Keybind.none())
        .action(this::keybindAction)
        .build()
    );

    private final Setting<Boolean> takeChatKey = sgWindow.add(new BoolSetting.Builder()
        .name("take-chat-key")
        .description("Take priority over the chat key: it opens the Messenger on Global instead of the normal chat. The command key (/) still opens the normal chat with a slash. Only a chat opened with empty text is taken, so on 1.21.11 a saved vanilla chat draft still opens vanilla chat, and another mod that opens an empty chat screen is taken too. Off: the normal chat is untouched.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> overlayGlobalChat = sgWindow.add(new BoolSetting.Builder()
        .name("overlay-global")
        .description("Shows the public chat (Global) in the always-on overlay. Off: the overlay shows your whispers, and Global only appears while the window is focused.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> overlay = sgWindow.add(new BoolSetting.Builder()
        .name("show-overlay")
        .description("Draws the window over the game while you play. Off: it only appears when you focus it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> opacity = sgWindow.add(new IntSetting.Builder()
        .name("window-opacity")
        .description("How opaque the window's panels are, in percent.")
        .defaultValue(75)
        .range(20, 100)
        .sliderRange(20, 100)
        .build()
    );

    private final Setting<Boolean> sound = sgWindow.add(new BoolSetting.Builder()
        .name("notification-sound")
        .description("A quiet sound when a whisper arrives (not when you are reading that conversation).")
        .defaultValue(true)
        .build()
    );

    private final MessengerCore core = new MessengerCore(new ServerStores(200));
    private final MessengerUi ui = new MessengerUi(new MessengerUi.Host() {
        @Override
        public MessengerCore core() {
            return core;
        }

        @Override
        public Geometry geometry() {
            return geometry;
        }

        @Override
        public void geometryChanged(Geometry g) {
            geometry = g;
        }

        @Override
        public float opacity() {
            return opacity.get() / 100f;
        }

        @Override
        public boolean isFriend(String name) {
            return Friends.get().get(name) != null;
        }

        @Override
        public int friendsStamp() {
            int h = Friends.get().count();
            for (var f : Friends.get()) h = h * 31 + f.name.toLowerCase().hashCode();
            return h;
        }

        @Override
        public List<String> friendNames() {
            List<String> names = new ArrayList<>();
            for (var f : Friends.get()) names.add(f.name);
            return names;
        }

        @Override
        public List<String> onlineNames() {
            return OnlineNames.all();
        }

        @Override
        public String selfName() {
            return mc.getUser() != null ? mc.getUser().getName() : null;
        }

        @Override
        public boolean overlayGlobal() {
            return overlayGlobalChat.get();
        }

        @Override
        public String meteorPrefix() {
            return Config.get().prefix.get();
        }

        @Override
        public String hint() {
            Keybind key = focusKey.get();
            return key.isSet() ? "Press " + key + " to type" : "Set the focus-key, or click here with the chat open";
        }
    });
    private final ChatClicks clicks = new ChatClicks(this::onChatClick);

    private volatile DmParser parser = new DmParser(EnumSet.allOf(DmFormat.class), List.of());
    private String lastPatternErrors = "";
    /** Where the window was left, in GUI pixels; null until it was moved or resized. Saved with the module's data. */
    private Geometry geometry;
    private long lastSave;
    /** Set by the whisper reader for the line being handled, read by the Global reader that runs after it. */
    private boolean lastWasWhisper;
    /** Writes the history one at a time, off the client thread; made on first use. */
    private volatile ExecutorService writer;
    /** Set by the writer thread, shown to the user on the next tick. */
    private final AtomicReference<String> pendingWarning = new AtomicReference<>();
    private final AtomicBoolean saveFailed = new AtomicBoolean();
    /** The history file could not be read, so it is not written this session (it would be lost). */
    private boolean saveBlocked;
    private String warnedTemplate;

    public Messenger() {
        super(QwerteAddon.CATEGORY, "messenger", "Reads your whispers into a small chat window with a history per player, plus a Global view of the public chat. Type back from the window (a key or a click on it), with emoji. The New list leaves out names the whisper command can't address (like Bedrock .Name).");
        // On exit, wait a moment for a write that is already running. This thread never reads the live conversations (they
        // belong to the client thread); what changed after the last save is saved on leaving a world or turning the module off.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                ExecutorService w = writer;
                if (w != null) {
                    w.shutdown();
                    w.awaitTermination(3, TimeUnit.SECONDS);
                }
            } catch (Throwable ignored) {
                // shutting down anyway
            }
        }, "qwerte-messenger-flush"));
    }

    /** The key of the server the player is on: its address, or "local" for singleplayer and LAN. */
    private String serverKey() {
        var server = mc.getCurrentServer();
        if (server == null || mc.isLocalServer()) return ServerStores.LOCAL;
        return ServerStores.normalize(server.ip);
    }

    public static Messenger get() {
        return Modules.get().get(Messenger.class);
    }

    // ---------- lifecycle ----------

    @Override
    public void onActivate() {
        core.template(template.get());
        core.intervalMs(interval.get());
        core.stores().cap(maxHistory.get());
        core.stores().globalCap(globalScrollback.get());
        core.switchServer(serverKey());
        ui.reset();
        saveBlocked = false;
        rebuildParser();
        checkTemplate(template.get());
        if (saveHistory.get()) loadHistory();
        lastSave = System.currentTimeMillis();
        MeteorClient.EVENT_BUS.subscribe(clicks);
    }

    @Override
    public void onDeactivate() {
        MeteorClient.EVENT_BUS.unsubscribe(clicks);
        core.cancelQueued();
        if (mc.screen instanceof MessengerScreen) mc.setScreen(null);
        if (Utils.screenToOpen instanceof MessengerScreen) Utils.screenToOpen = null; // asked for but not opened yet
        saveNow();
        ui.release();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        core.switchServer(serverKey());
        ui.reset();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        core.leave();
        saveNow();
    }

    /** Warns once per template if its command is not a known whisper command (it is still used). */
    private void checkTemplate(String value) {
        if (value.equals(warnedTemplate)) return;
        warnedTemplate = value;
        if (!SendText.validTemplate(value)) warning("The command template is not valid (it must be exactly /command {name} {text}); nothing can be sent.");
        else if (!SendText.isWhisperCommand(value)) warning("The command template does not start with a whisper command (msg, w, tell, ...). Make sure it only whispers.");
    }

    // ---------- reading ----------

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        long now = System.currentTimeMillis();
        DmParser.Parsed p = ChatLines.parse(event.getMessage(), parser, fmtVanilla.get(), OnlineNames::has);
        lastWasWhisper = p != null; // read by onChatLine, which runs after this for the same message
        if (p == null) return;
        String self = mc.getUser() != null ? mc.getUser().getName() : null;
        boolean reading = ui.isReading(p.name());
        MessengerCore.Received r = core.receive(p, self, now, reading);
        if (r == null) return;
        if (r.fromOther() && !reading && sound.get() && mc.player != null) {
            // quiet: a short high blip at a quarter of the volume
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.6f, 0.25f));
        }
    }

    /**
     * The public chat for Global: every line that reaches the chat feed except whispers (those go only to their
     * conversation, so deleting it removes them everywhere). It runs last, so it sees the line as the chat shows it:
     * after other handlers changed it, and not at all if one cancelled it. The whisper reading above stays at the normal
     * priority, because the patterns are written for the server's own wording.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    private void onChatLine(ReceiveMessageEvent event) {
        boolean whisper = lastWasWhisper;
        lastWasWhisper = false;
        if (whisper || event.isCancelled() || event.getMessage() == null) return;
        core.receiveGlobal(event.getMessage().getString(), System.currentTimeMillis());
    }

    // ---------- sending and saving ----------

    @EventHandler
    private void onTick(TickEvent.Post event) {
        long now = System.currentTimeMillis();
        core.tick(now, new MessengerCore.Sender() {
            @Override
            public boolean command(String command) {
                ClientPacketListener connection = mc.getConnection();
                if (connection == null || mc.player == null) return false;
                connection.sendCommand(command); // a whisper, or a command the user typed in Global (no slash)
                return true;
            }

            @Override
            public boolean chat(String text) {
                ClientPacketListener connection = mc.getConnection();
                if (connection == null || mc.player == null) return false;
                connection.sendChat(text); // public chat the user typed in Global
                return true;
            }
        });
        if (saveHistory.get()) {
            boolean whispers = core.stores().dirty() && now - lastSave > SAVE_EVERY_MS;
            boolean pub = core.stores().globalDirty() && now - lastSave > SAVE_GLOBAL_EVERY_MS;
            if (whispers || pub) saveNow();
        }
        String warn = pendingWarning.getAndSet(null);
        if (warn != null) warning(warn);
        if (saveFailed.getAndSet(false)) core.stores().touch(); // try again at the next save
    }

    // ---------- drawing and focus ----------

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!overlay.get() || mc.player == null || mc.options.hideGui) return;
        if (mc.screen != null && !(mc.screen instanceof ChatScreen)) return; // also over the chat, where the mouse is free
        ui.render(event.drawContext, event.screenWidth, event.screenHeight, -1, -1, false);
    }

    private void keybindAction() {
        if (mc.screen instanceof MessengerScreen) {
            // A key that types a character can not also let go, or the letter would close the window while you type
            if (!typesCharacters(focusKey.get())) ui.close();
        } else if (mc.screen == null && mc.player != null) {
            open();
        }
    }

    private static boolean typesCharacters(Keybind key) {
        if (!key.isKey() || key.hasMods()) return false;
        int v = key.getValue();
        return (v >= 32 && v <= 96) || (v >= 320 && v <= 336);
    }

    /** A click while the chat is open: on the window it focuses it. */
    private boolean onChatClick() {
        // The same conditions as the drawing: only a window that is shown can be clicked
        if (!isActive() || !overlay.get() || mc.player == null || mc.options.hideGui || !(mc.screen instanceof ChatScreen)) return false;
        double sw = mc.getWindow().getScreenWidth();
        double sh = mc.getWindow().getScreenHeight();
        if (sw <= 0 || sh <= 0) return false;
        int guiW = mc.getWindow().getGuiScaledWidth();
        int guiH = mc.getWindow().getGuiScaledHeight();
        double x = mc.mouseHandler.xpos() * guiW / sw;
        double y = mc.mouseHandler.ypos() * guiH / sh;
        if (!ui.isOver(x, y, guiW, guiH)) return false;
        mc.setScreen(new MessengerScreen(ui));
        return true;
    }

    /**
     * With take-chat-key on, the game's chat key opens the Messenger on Global. The game opens its chat by
     * {@code Minecraft.setScreen(new ChatScreen(initialText))}, with an empty text from the chat key and "/" from the
     * command key; this event comes before that and is cancelled only for an exact {@link ChatScreen} with an empty text.
     * Any other chat screen (a link, a command suggestion, another mod, a restored draft) is left alone.
     */
    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!takeChatKey.get() || event.screen == null || event.screen.getClass() != ChatScreen.class) return;
        boolean available = isActive() && mc.player != null && mc.level != null && mc.screen == null;
        String initial = ((ChatScreenAccessor) event.screen).qwerte$getInitial();
        if (ChatKeyRoute.route(true, initial, available) != ChatKeyRoute.Route.MESSENGER) return;
        event.cancel();
        ui.selectGlobal();
        open();
    }

    /** Focuses the window (as soon as no other screen is up). */
    public void open() {
        Utils.screenToOpen = new MessengerScreen(ui);
    }

    // ---------- patterns ----------

    private void rebuildParser() {
        Set<DmFormat> on = EnumSet.noneOf(DmFormat.class);
        if (fmtVanilla.get()) on.add(DmFormat.VANILLA);
        if (fmt2b2t.get()) on.add(DmFormat.TWO_B2T);
        if (fmtEssentials.get()) on.add(DmFormat.ESSENTIALS);
        if (fmtFromTo.get()) on.add(DmFormat.FROM_TO);
        UserPatterns.Result extra = UserPatterns.parse(extraPatterns.get());
        List<DmPattern> patterns = extra.patterns();
        // A new parser starts every pattern with no strikes
        parser = new DmParser(on, patterns, p -> warning("Extra pattern switched off because it is too slow: %s", p.source()));
        String errors = String.join("\n", extra.errors());
        if (!errors.equals(lastPatternErrors)) {
            lastPatternErrors = errors;
            if (isActive()) {
                for (String e : extra.errors()) warning("Extra pattern ignored: %s", e);
            }
        }
    }

    // ---------- history file ----------

    private static Path historyFile() {
        return MeteorClient.FOLDER.toPath().resolve("qwerte").resolve("messenger-history.json");
    }

    private void loadHistory() {
        // An old (version 1) file has no servers: its conversations go to the server the player is on now
        HistoryJson.Loaded loaded = HistoryJson.load(historyFile(), core.stores(), core.stores().currentKey());
        switch (loaded.status()) {
            case CORRUPT -> warning("The saved history was unreadable and was moved aside (messenger-history.json.corrupt).");
            case TOO_BIG, UNREADABLE -> {
                // Left as it is, and not overwritten: saving is off until the module is turned on again
                saveBlocked = true;
                warning("The saved history could not be read (too big or unreadable); it is left alone and nothing is saved this session.");
            }
            default -> {
            }
        }
    }

    /** The one background thread that writes the file, so two writes never overlap and the client thread never waits for the disk. */
    private ExecutorService writer() {
        ExecutorService w = writer;
        if (w == null) {
            w = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qwerte-messenger-save");
                t.setDaemon(true);
                return t;
            });
            writer = w;
        }
        return w;
    }

    /**
     * Writes the history if it changed (client thread). A copy of the conversations is taken here, so the thread that
     * writes never looks at the live lists; it only turns the copy into JSON and writes it. A failed write marks the
     * history as changed again, so the next save tries once more.
     */
    private void saveNow() {
        lastSave = System.currentTimeMillis();
        if (!saveHistory.get() || saveBlocked || !core.stores().anyDirty()) return;
        HistoryJson.Snapshot snapshot = HistoryJson.snapshot(core.stores());
        core.stores().clean();
        Path file = historyFile();
        AtomicReference<String> warn = pendingWarning;
        AtomicBoolean failed = saveFailed;
        writer().execute(() -> {
            if (!HistoryJson.write(file, snapshot)) {
                warn.set("Could not save the history.");
                failed.set(true);
            }
        });
    }

    /** Forgets every conversation (every server), in memory and on disk. */
    public void clearHistory() {
        core.cancelQueued();
        core.stores().clear();
        core.stores().clean();
        ui.reset();
        saveBlocked = false;
        Path file = historyFile();
        AtomicReference<String> warn = pendingWarning;
        // On the writer thread, after any save already waiting, so an old save can not bring the file back
        writer().execute(() -> {
            if (!HistoryJson.delete(file)) warn.set("Could not delete the history file.");
        });
    }

    // ---------- saved data and settings screen ----------

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = super.toTag();
        if (tag != null && geometry != null) tag.putString(GEOMETRY_KEY, geometry.encode());
        return tag;
    }

    @Override
    public Module fromTag(CompoundTag tag) {
        // A malformed value is ignored: the window then opens in its default place
        geometry = Geometry.decode(string(tag.get(GEOMETRY_KEY)));
        ui.resetGeometry(); // the window may already have read the old place
        return super.fromTag(tag);
    }

    private static String string(Tag tag) {
        if (tag == null) return null;
        return NbtOps.INSTANCE.getStringValue(tag).result().orElse(null);
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WHorizontalList list = theme.horizontalList();
        WButton openButton = list.add(theme.button("Open window")).widget();
        openButton.action = () -> {
            if (isActive()) mc.setScreen(new MessengerScreen(ui));
            else warning("Turn the module on first.");
        };
        WButton clear = list.add(theme.button("Clear history")).widget();
        clear.tooltip = "Forgets every conversation and deletes the history file.";
        clear.action = this::clearHistory;
        return list;
    }
}

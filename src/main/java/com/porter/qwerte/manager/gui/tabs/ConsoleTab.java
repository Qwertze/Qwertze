package com.porter.qwerte.manager.gui.tabs;

import com.google.gson.JsonObject;
import com.porter.qwerte.manager.gui.widgets.WTextLog;
import com.porter.qwerte.manager.model.BotList;
import com.porter.qwerte.manager.model.ConsoleLog;
import com.porter.qwerte.manager.net.ApiError;
import com.porter.qwerte.manager.net.ServerUrl;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.render.color.Color;
import org.lwjgl.glfw.GLFW;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Console tab: the Console page of the website for the bot picked in the header. The console needs the password typed in
 * the last 15 minutes: the poll never opens the password prompt by itself, it shows a Confirm password button. Sending goes
 * through the window confirm-and-retry flow.
 *
 * <p>Polls GET console?after=rev: every second while an entry waits for the bot, else every 5 s. The log is a text box filled in
 * place, and so is the status line, so typing is never interrupted by a poll. Enter, Up and Down in the command box are read
 * from the keyboard state each frame (the box itself only knows its own keys). Commands in {@link ConsoleLog#CONFIRM_WORDS}
 * need two clicks. Everything typed or received is dropped by {@link #clearSecrets()}.
 */
public final class ConsoleTab {
    private static final Color WARN = new Color(255, 190, 80);
    private static final Color BAD = new Color(255, 110, 110);
    private static final Color DIM = new Color(170, 170, 180);
    private static final long FAST_MS = 1_000;
    private static final long SLOW_MS = 5_000;
    private static final long ARM_MS = 4_000;
    private static final long CHECK_MS = 1_000;

    private final ServerTabHost host;
    private GuiTheme theme;

    private final ConsoleLog log = new ConsoleLog();
    private final ConsoleLog.History history = new ConsoleLog.History();
    private String bot;
    private boolean locked;
    private boolean polling;
    private boolean sending;
    private long nextGet;
    private long nextCheck;
    private int token;
    private String input = "";
    private String note = "";
    private String armedCommand = "";
    private long armedUntil;
    private String builtSignature = "";
    private String shownLog = "";

    private WTextLog logBox;
    private WTextBox inputBox;
    private WButton sendButton;
    private WLabel noteLabel;
    private WLabel stateLabel;
    private boolean prevEnter, prevUp, prevDown, wasFocused;

    public ConsoleTab(ServerTabHost host) {
        this.host = host;
    }

    /** The window closed or the session ended. */
    public void reset() {
        token++;
        polling = false;
        sending = false;
        bot = null;
        locked = false;
        nextGet = 0;
        note = "";
        armedCommand = "";
        builtSignature = "";
        shownLog = "";
        clearSecrets();
        logBox = null;
        inputBox = null;
        sendButton = null;
        noteLabel = null;
        stateLabel = null;
    }

    /** The window is closing: empty the command box and the log. */
    public void clearSecrets() {
        token++;
        polling = false;
        sending = false;
        armedCommand = "";
        builtSignature = "";
        nextGet = 0;
        input = "";
        if (inputBox != null) inputBox.set("");
        log.clear();
        history.clear();
        if (logBox != null) logBox.setLines(List.of());
        shownLog = "";
    }

    private BotList.Bot capableBot() {
        BotList.Bot b = host.bots().selectedBot();
        return b != null && b.hasCap("console") ? b : null;
    }

    private boolean allowed() {
        return !host.auth().permMode("console").equals("none") && !host.auth().forcedStreamer();
    }

    private String signature() {
        return bot + "|" + (capableBot() != null) + "|" + locked + "|" + host.bots().loaded() + "|" + allowed();
    }

    // ---------- per frame ----------

    public void tick(long now) {
        if (host.closed() || !allowed()) return;
        host.bots().tick(now, true);
        String id = host.bots().selected();
        if (!Objects.equals(id, bot)) {
            // another bot: its own log and history (the typed text stays)
            token++;
            polling = false;
            sending = false;
            bot = id;
            locked = false;
            log.clear();
            history.clear();
            armedCommand = "";
            note = "";
            nextGet = 0;
            host.later(host::rebuild);
            return;
        }
        if (!armedCommand.isEmpty() && now > armedUntil) {
            armedCommand = "";
            if (sendButton != null) sendButton.set("Send");
        }
        keys();
        if (capableBot() != null && !locked && !polling && now >= nextGet) poll(now);
        if (now < nextCheck) return;
        nextCheck = now + CHECK_MS;
        if (stateLabel != null) stateLabel.set(stateText());
        displayChanged();
        // A rebuild would take the cursor out of the command box: it waits (the signature stays different until it ran)
        if (!builtSignature.equals(signature()) && !(inputBox != null && inputBox.isFocused())) host.later(host::rebuild);
    }

    private String stateText() {
        BotList.Bot b = host.bots().selectedBot();
        if (b == null) return "";
        if (!b.online() || !log.online()) return "The bot is offline. Commands can not be sent until it is back.";
        return "";
    }

    private void poll(long now) {
        String id = bot;
        if (id == null) return;
        polling = true;
        nextGet = now + SLOW_MS;
        int t = token;
        Map<String, String> q = new LinkedHashMap<>();
        q.put("after", Long.toString(log.rev()));
        // client().get, not host.read: a poll must never open the password prompt by itself
        host.client().get("/api/v1/bots/" + ServerUrl.seg(id) + "/console", q, r -> {
            if (t != token) return;
            polling = false;
            if (host.closed()) return;
            if (r.ok()) {
                if (log.apply(r.body())) fillLog(false);
                nextGet = System.currentTimeMillis() + (log.pending() ? FAST_MS : SLOW_MS);
            } else if (r.kind() == ApiError.Kind.CONFIRM) {
                locked = true;
                log.clear();
                host.later(host::rebuild);
            } else if (r.kind() == ApiError.Kind.NETWORK || r.kind() == ApiError.Kind.SERVER || r.kind() == ApiError.Kind.RATE_LIMIT
                || r.kind() == ApiError.Kind.NOT_FOUND) {
                setNote(host.describe(r, "Could not load the console."));
            } else {
                host.failed(r);
            }
        });
    }

    private void fillLog(boolean toEnd) {
        if (logBox == null) return;
        boolean hide = host.policy().streamer();
        List<WTextLog.Line> lines = new ArrayList<>();
        for (ConsoleLog.Line l : log.lines(hide, ZoneId.systemDefault())) {
            lines.add(new WTextLog.Line(l.text(), new Color((l.rgb() >> 16) & 0xFF, (l.rgb() >> 8) & 0xFF, l.rgb() & 0xFF)));
        }
        if (lines.isEmpty()) lines.add(new WTextLog.Line("Nothing sent yet. Commands you send show up here with the answer of the bot.", DIM));
        logBox.setLines(lines);
        if (toEnd) logBox.scrollToEnd();
        shownLog = Boolean.toString(hide);
    }

    private void setNote(String text) {
        note = text;
        if (noteLabel != null) noteLabel.set(text);
    }

    // ---------- keys ----------

    private static boolean down(long win, int key) {
        return GLFW.glfwGetKey(win, key) == GLFW.GLFW_PRESS;
    }

    /** Enter sends, Up and Down walk the history: read each frame from the keyboard state, only while the box has (or just had) the cursor. */
    private void keys() {
        if (inputBox == null) {
            prevEnter = prevUp = prevDown = wasFocused = false;
            return;
        }
        long win = GLFW.glfwGetCurrentContext();
        if (win == 0L) return;
        boolean enter = down(win, GLFW.GLFW_KEY_ENTER) || down(win, GLFW.GLFW_KEY_KP_ENTER);
        boolean up = down(win, GLFW.GLFW_KEY_UP);
        boolean dn = down(win, GLFW.GLFW_KEY_DOWN);
        boolean focused = inputBox.isFocused();
        // the box lets go of the cursor on Enter before this runs
        if (enter && !prevEnter && (focused || wasFocused)) {
            send();
            inputBox.setFocused(true);
            focused = true;
        }
        if (focused && up && !prevUp) recall(history.up());
        if (focused && dn && !prevDown) recall(history.down());
        prevEnter = enter;
        prevUp = up;
        prevDown = dn;
        wasFocused = focused;
    }

    private void recall(String text) {
        if (text == null || inputBox == null) return;
        input = text;
        inputBox.set(text);
    }

    // ---------- sending ----------

    private void send() {
        if (sending || bot == null || capableBot() == null || locked) return;
        String cmd = input.trim();
        String bad = ConsoleLog.check(cmd);
        if (!bad.isEmpty()) {
            setNote(bad);
            return;
        }
        long now = System.currentTimeMillis();
        if (ConsoleLog.needsConfirm(cmd) && !(cmd.equals(armedCommand) && now <= armedUntil)) {
            armedCommand = cmd;
            armedUntil = now + ARM_MS;
            if (sendButton != null) sendButton.set("Run it?");
            setNote(ConsoleLog.confirmText(cmd, host.bots().list().name(host.bots().selectedBot(), host.policy().hideNames())));
            return;
        }
        armedCommand = "";
        if (sendButton != null) sendButton.set("Send");
        sending = true;
        setNote("");
        int t = token;
        JsonObject body = new JsonObject();
        body.addProperty("command", cmd);
        host.write("/api/v1/bots/" + ServerUrl.seg(bot) + "/console", body, r -> {
            if (t != token) return;
            sending = false;
            if (host.closed()) return;
            if (r.ok()) {
                history.push(cmd);
                input = "";
                if (inputBox != null) inputBox.set("");
                nextGet = 0;
                setNote("");
                if (logBox != null) logBox.scrollToEnd();
            } else if (r.kind() == ApiError.Kind.CANCELLED) {
                setNote("Not sent: the password was not confirmed.");
            } else if (r.kind() == ApiError.Kind.CONFLICT) {
                setNote("The bot is offline.");
            } else if (r.kind() == ApiError.Kind.LOGIN || r.kind() == ApiError.Kind.MUST_CHANGE) {
                host.failed(r);
            } else {
                setNote("Not sent: " + host.describe(r, "HTTP " + r.status()));
            }
        });
    }

    // ---------- widgets ----------

    public void build(WContainer parent, GuiTheme theme) {
        this.theme = theme;
        logBox = null;
        inputBox = null;
        sendButton = null;
        noteLabel = null;
        stateLabel = null;
        if (bot == null) bot = host.bots().selected();
        builtSignature = signature();
        if (!allowed()) {
            parent.add(theme.label("Your account can not use the console; ask the owner.", theme.scale(520))).expandX().widget().color(WARN);
            return;
        }
        if (bot == null) {
            parent.add(theme.label(host.bots().loaded() ? "No dashboard bot yet." : "Loading the bots...", theme.scale(520))).expandX().widget().color(DIM);
            return;
        }
        if (capableBot() == null) {
            parent.add(theme.label("This bot does not offer a console (update the Qwerte Stash plugin).", theme.scale(520))).expandX().widget().color(WARN);
            return;
        }
        if (locked) {
            parent.add(theme.label("The console is only shown after you confirm your password.", theme.scale(520))).expandX();
            WButton unlock = parent.add(theme.button("Confirm password")).widget();
            unlock.action = () -> host.later(() -> host.confirm("The console needs your password again. It is not asked for again for 15 minutes.", () -> {
                locked = false;
                log.clear();
                nextGet = 0;
                host.rebuild();
            }));
            return;
        }
        stateLabel = parent.add(theme.label(stateText(), theme.scale(520))).expandX().widget();
        stateLabel.color(WARN);
        logBox = parent.add(new WTextLog(520)).expandX().widget();
        fillLog(true);
        WHorizontalList row = parent.add(theme.horizontalList()).expandX().widget();
        WTextBox box = row.add(theme.textBox(input, "Command, for example: help")).minWidth(theme.scale(300)).expandX().widget();
        inputBox = box;
        box.action = () -> {
            String clean = ConsoleLog.clean(box.get());
            if (!clean.equals(box.get())) box.set(clean);
            input = clean;
        };
        sendButton = row.add(theme.button(armedCommand.isEmpty() ? "Send" : "Run it?")).widget();
        sendButton.tooltip = "Send the command (Enter). Up and Down bring back earlier commands.";
        sendButton.action = this::send;
        noteLabel = parent.add(theme.label(note, theme.scale(520))).expandX().widget();
        noteLabel.color(BAD);
        parent.add(theme.label("Commands that can disconnect the bot or restart the proxy ask for a second click.", theme.scale(520))).expandX().widget().color(DIM);
    }

    /** Called by the window when the streamer display flips, so the log is drawn again. */
    public void displayChanged() {
        if (logBox != null && !shownLog.equals(Boolean.toString(host.policy().streamer()))) fillLog(false);
    }
}

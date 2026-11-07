package com.porter.qwerte.manager.gui.tabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.porter.qwerte.manager.gui.panels.OwnPasswordPanel;
import com.porter.qwerte.manager.model.AuthState;
import com.porter.qwerte.manager.model.BotList;
import com.porter.qwerte.manager.model.Json;
import com.porter.qwerte.manager.model.SettingsDraft;
import com.porter.qwerte.manager.model.SettingsSchema;
import com.porter.qwerte.manager.model.SettingsSchema.Leaf;
import com.porter.qwerte.manager.model.SettingsSchema.Node;
import com.porter.qwerte.manager.model.SettingsSchema.Parsed;
import com.porter.qwerte.manager.model.Text;
import com.porter.qwerte.manager.model.UsersModel;
import com.porter.qwerte.manager.net.ApiError;
import com.porter.qwerte.manager.net.ApiResult;
import com.porter.qwerte.manager.net.ServerUrl;
import com.porter.qwerte.manager.net.SmClient;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Settings tab. Two pages:
 * <ul>
 *   <li><b>Bot settings</b>: the settings form of a dashboard bot, as the website's Settings page (scope {@code plugin}) and
 *   Proxy page (scopes {@code proxy} and {@code plugin:<id>}) show it: sections, search, an editor per kind of setting, Save
 *   and Revert, then the bot's own answer for each saved setting. The server checks every change; this form only saves a
 *   round trip.</li>
 *   <li><b>Account</b>: change your password, lock admin, log out, log out everywhere.</li>
 * </ul>
 * State lives in this object; the widgets are rebuilt from it. Nothing here can show a bot's settings to an account forced
 * into streamer mode (the server refuses them too).
 *
 * <p>In <b>proxy mode</b> ({@link #SettingsTab(ServerTabHost, boolean)}) the same form serves the window's Proxy page: only the
 * {@code proxy} and {@code plugin:<id>} scopes, no Account page and no stash-plugin ({@code plugin}) scope, starting on the proxy.
 */
public final class SettingsTab {
    private enum Page { BOT, ACCOUNT }

    private record Job(String path, JsonElement sent, long since) {
    }

    private record Note(String kind, String text) {
    }

    private static final Color WARN = new Color(255, 190, 80);
    private static final Color BAD = new Color(255, 110, 110);
    private static final Color GOOD = new Color(120, 220, 140);
    private static final Color DIM = new Color(170, 170, 180);
    private static final long POLL_MS = 3_000;
    private static final long LOADING_POLL_MS = 1_000;
    private static final long JOB_POLL_MS = 700;
    private static final long JOB_GIVE_UP_MS = 70_000;
    private static final long ARM_MS = 4_000;
    private static final int PAGE_LIMIT = 120;
    private static final int LIST_SHOWN = 40;
    private static final Pattern JOB_ID = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    // Settings whose value is an address, port or URL: hidden in streamer display, like the website
    private static final Pattern SENSITIVE_SECTION = Pattern.compile("(^|\\.)(server\\.bind|client\\.server|connectionProxy)(\\.|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_WORD = Pattern.compile("^(address|addresses|host|hostname|ip|ips|port|url|urls|uri|domain|endpoint)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_SUFFIX = Pattern.compile("[a-z0-9](Address|Addresses|Host|Hostname|Ip|IP|Ips|IPs|Port|Url|URL|Urls|Uri|URI|Domain|Endpoint)$");

    private final ServerTabHost host;
    /** The Proxy page: only the proxy and plugin:* scopes, no Account page. */
    private final boolean proxyMode;
    private GuiTheme theme;
    private Page page = Page.BOT;

    // which form is loaded
    private String formBot;
    private String formScope;
    private int requestToken;
    private boolean getBusy;
    private long nextGet;
    private String etag;
    private String message = "";
    private boolean locked;
    private JsonObject values = new JsonObject();
    private boolean haveState;
    private boolean online = true;
    private long lastSeen;
    private String stateSignature = "";
    private List<Leaf> leaves = List.of();
    private final Map<String, Leaf> byPath = new HashMap<>();
    private List<SettingsSchema.Section> sections = List.of();
    private String section;
    private String query = "";
    private final SettingsDraft draft = new SettingsDraft();
    private final Map<String, Note> notes = new HashMap<>();
    private final Set<String> busy = new HashSet<>();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private boolean jobBusy;
    private long nextJobs;
    private boolean saving;
    private String barStatus = "";
    private boolean pendingRebuild;
    private String botSignature = "";

    // widgets of the bot page
    private WVerticalList formBox;
    private WButton saveButton, revertButton;
    private WLabel barLabel;
    private final Map<String, WLabel> noteLabels = new HashMap<>();
    private final List<WTextBox> boxes = new ArrayList<>();

    // account page
    private OwnPasswordPanel passwordPanel;
    private String passwordMessage = "";
    private boolean passwordBusy;
    private WButton lockButton, logoutAllButton;
    private long logoutAllArmedUntil;

    public SettingsTab(ServerTabHost host) {
        this(host, false);
    }

    /** @param proxyMode true for the Proxy page: the proxy and plugin:* scopes only, no Account page */
    public SettingsTab(ServerTabHost host, boolean proxyMode) {
        this.host = host;
        this.proxyMode = proxyMode;
        this.formScope = proxyMode ? "proxy" : "plugin";
        this.loadedScope = formScope;
    }

    /** The window closed or the session ended: forget the loaded settings. */
    public void reset() {
        requestToken++;
        getBusy = false;
        jobBusy = false;
        saving = false;
        formBot = null;
        clearForm();
        formScope = proxyMode ? "proxy" : "plugin";
        loadedScope = formScope;
        page = Page.BOT;
        passwordBusy = false;
        passwordMessage = "";
    }

    private void clearForm() {
        etag = null;
        message = "";
        locked = false;
        values = new JsonObject();
        haveState = false;
        stateSignature = "";
        leaves = List.of();
        byPath.clear();
        sections = List.of();
        section = null;
        query = "";
        draft.clear();
        notes.clear();
        busy.clear();
        jobs.clear();
        barStatus = "";
        nextGet = 0;
        pendingRebuild = false;
    }

    // ---------- what is allowed ----------

    private AuthState auth() {
        return host.auth();
    }

    /** "" when the bot settings can be shown, else why not. */
    private String denied() {
        if (auth().forcedStreamer()) return "Bot settings are not available in streamer mode: the owner enforces it for your account (they can hold coordinates).";
        if (!auth().can("activity")) return "Your account can't see the bot's settings; ask the owner.";
        if (proxyMode && auth().permMode("proxy").equals("none")) return "Your account can't see the proxy settings; ask the owner.";
        return "";
    }

    private List<String> scopes() {
        List<String> out = new ArrayList<>();
        if (!proxyMode) out.add("plugin");
        BotList.Bot bot = host.bots().selectedBot();
        // the Settings page is the plugin's alone; the proxy and the other plugins have the Proxy page (as on the website)
        if (proxyMode && bot != null && !auth().permMode("proxy").equals("none")) {
            List<String> extra = new ArrayList<>();
            for (String s : bot.scopes()) if (s.equals("proxy") || s.startsWith("plugin:")) extra.add(s);
            extra.sort((a, b) -> a.equals("proxy") ? -1 : b.equals("proxy") ? 1 : a.compareTo(b));
            out.addAll(extra);
        }
        return out;
    }

    private static String scopeLabel(String scope) {
        return scope.equals("plugin") ? "Stash plugin" : scope.equals("proxy") ? "AquariusProxy" : "Plugin: " + scope.substring(7);
    }

    private boolean readOnly() {
        return formScope.equals("plugin") ? !auth().can("settings") : !auth().permMode("proxy").equals("change");
    }

    private boolean sensitive(String path) {
        String last = path.substring(path.lastIndexOf('.') + 1);
        return SENSITIVE_SECTION.matcher(path).find() || SENSITIVE_WORD.matcher(last).matches() || SENSITIVE_SUFFIX.matcher(last).find();
    }

    private JsonElement serverValue(String path) {
        JsonElement v = values.get(path);
        return v == null ? JsonNull.INSTANCE : v;
    }

    // ---------- per frame ----------

    public void tick(long now) {
        if (host.closed()) return;
        if (logoutAllArmedUntil != 0 && now > logoutAllArmedUntil) {
            logoutAllArmedUntil = 0;
            if (logoutAllButton != null) logoutAllButton.set("Log out everywhere");
        }
        if (page != Page.BOT || !denied().isEmpty()) return;
        host.bots().tick(now, true);
        if (host.bots().takeChanged() && !botSignature().equals(botSignature)) host.later(host::rebuild);
        syncForm();
        if (!locked && now >= nextGet && !getBusy && host.bots().selected() != null && !scopes().isEmpty()) fetch(now);
        if (!jobs.isEmpty() && now >= nextJobs && !jobBusy) pollJobs(now);
        tickSearch(now);
        if (pendingRebuild && formBox != null && !anyFocused()) {
            pendingRebuild = false;
            renderForm();
        }
    }

    /** A box has the cursor, or a key was pressed a moment ago (a fallback): the form is not rebuilt under the cursor. */
    private boolean anyFocused() {
        for (WTextBox b : boxes) if (b.isFocused()) return true;
        return System.currentTimeMillis() - lastTyped < 1_500;
    }

    private long lastTyped;

    private String botSignature() {
        StringBuilder sb = new StringBuilder();
        for (BotList.Bot b : host.bots().list().bots()) sb.append(b.botId()).append(b.online()).append(b.scopes()).append(';');
        return sb.toString();
    }

    /** The bot or scope changed: start over with the new one. */
    private void syncForm() {
        String bot = host.bots().selected();
        List<String> scopes = scopes();
        if (!scopes.contains(formScope)) formScope = scopes.isEmpty() ? formScope : proxyMode ? scopes.get(0) : "plugin";
        if (!java.util.Objects.equals(bot, formBot) || !formScope.equals(loadedScope)) {
            requestToken++;
            getBusy = false;
            jobBusy = false;
            clearForm();
            formBot = bot;
            loadedScope = formScope;
            if (formBox != null) renderForm();
        }
    }

    private String loadedScope = "plugin";

    private void fetch(long now) {
        String bot = formBot;
        String scope = formScope;
        if (bot == null) return;
        getBusy = true;
        nextGet = now + (haveState ? POLL_MS : LOADING_POLL_MS);
        int token = requestToken;
        Map<String, String> q = new LinkedHashMap<>();
        q.put("scope", scope);
        host.client().get("/api/v1/bots/" + ServerUrl.seg(bot) + "/settings", q, etag, false, SmClient.DEFAULT_TIMEOUT, null, (r, ignored) -> {
            if (token != requestToken) return;
            getBusy = false;
            if (host.closed()) return;
            switch (r.kind()) {
                case OK -> {
                    etag = r.etag();
                    apply(r.obj());
                }
                case NOT_MODIFIED -> {
                    // unchanged
                }
                case NOT_FOUND -> {
                    JsonObject o = r.obj();
                    haveState = false;
                    values = new JsonObject();
                    leaves = List.of();
                    String m = Json.bool(o, "loading") ? "Asking the bot for these settings..."
                        : o.has("online") && !Json.bool(o, "online") ? "The bot is offline; its settings show here once it is back."
                        : "This bot hasn't sent these settings yet.";
                    setMessage(m);
                }
                case CONFIRM -> {
                    locked = true;
                    haveState = false;
                    setMessage("These settings are only shown after you confirm your password.");
                }
                case NO_PERMISSION -> {
                    nextGet = System.currentTimeMillis() + 10_000;
                    setMessage(Text.sentence(Text.sanitize(r.error(), 160)).isEmpty() ? "Your account can't see these settings." : Text.sentence(Text.sanitize(r.error(), 160)));
                    host.failed(r);
                }
                default -> {
                    nextGet = System.currentTimeMillis() + 5_000;
                    if (r.kind() == ApiError.Kind.NETWORK || r.kind() == ApiError.Kind.SERVER || r.kind() == ApiError.Kind.RATE_LIMIT) {
                        setMessage(host.describe(r, "Could not load the settings."));
                    } else {
                        host.failed(r);
                    }
                }
            }
        });
    }

    private void setMessage(String text) {
        if (text.equals(message)) return;
        message = text;
        if (formBox != null) pendingRebuild = true;
    }

    private void apply(JsonObject o) {
        JsonObject v = Json.obj(o.get("values"));
        values = v == null ? new JsonObject() : v;
        online = !o.has("online") || Json.bool(o, "online");
        lastSeen = Json.lng(o, "lastSeen", 0);
        String schemaHash = Json.str(o, "schemaHash");
        String sig = schemaHash + "|" + Json.str(o, "valuesHash") + "|" + online;
        boolean schemaChanged = !haveState || !schemaHash.equals(stateSchema);
        if (schemaChanged) {
            stateSchema = schemaHash;
            leaves = SettingsSchema.leaves(o.get("schema"));
            byPath.clear();
            for (Leaf l : leaves) byPath.put(l.node().path(), l);
            sections = SettingsSchema.sections(leaves);
            draft.retain(p -> {
                Leaf l = byPath.get(p);
                return l != null && !l.node().locked();
            });
            boolean has = false;
            for (SettingsSchema.Section s : sections) if (s.name().equals(section)) has = true;
            if (!has) section = sections.isEmpty() ? null : sections.get(0).name();
        }
        haveState = true;
        message = "";
        if (schemaChanged || !sig.equals(stateSignature)) {
            stateSignature = sig;
            if (formBox != null) pendingRebuild = true;
        }
    }

    private String stateSchema = "";

    // ---------- saving ----------

    private void save() {
        if (saving || !jobs.isEmpty() || draft.size() == 0 || !online || readOnly()) return;
        if (draft.hasInvalid()) {
            barStatus = "Fix the marked values first.";
            updateBar();
            return;
        }
        for (String p : draft.paths()) {
            Leaf l = byPath.get(p);
            String err = l == null ? "no longer exists" : SettingsSchema.validate(l.node(), draft.get(p));
            if (!err.isEmpty()) {
                note(p, "error", err);
                barStatus = "Not saved: fix the marked value.";
                updateBar();
                return;
            }
        }
        String bot = formBot;
        int token = requestToken;
        JsonObject body = new JsonObject();
        body.addProperty("scope", formScope);
        body.add("changes", draft.changes(this::serverValue));
        Map<String, JsonElement> sent = new HashMap<>();
        for (String p : draft.paths()) sent.put(p, draft.get(p));
        saving = true;
        barStatus = "Saving...";
        updateBar();
        host.write("/api/v1/bots/" + ServerUrl.seg(bot) + "/settings", body, r -> {
            saving = false;
            if (token != requestToken || host.closed()) return;
            if (r.kind() == ApiError.Kind.CANCELLED) {
                barStatus = "Not saved: the password was not confirmed.";
                updateBar();
                return;
            }
            if (r.ok()) {
                JsonArray js = r.obj().has("jobs") && r.obj().get("jobs").isJsonArray() ? r.obj().getAsJsonArray("jobs") : new JsonArray();
                long now = System.currentTimeMillis();
                for (JsonElement e : js) {
                    JsonObject j = Json.obj(e);
                    if (j == null) continue;
                    String id = Json.str(j, "id");
                    String path = Json.str(j, "path");
                    if (!JOB_ID.matcher(id).matches() || path.isEmpty()) continue;
                    jobs.put(id, new Job(path, sent.get(path), now));
                    busy.add(path);
                    notes.put(path, new Note("busy", "applying"));
                }
                nextJobs = now + JOB_POLL_MS;
                barStatus = "Applying...";
                pendingRebuild = true;
                return;
            }
            JsonObject o = r.obj();
            String error = Text.sanitize(r.error(), 160);
            if (r.kind() == ApiError.Kind.CONFLICT) {
                barStatus = error.equals("bot offline") ? "The bot is offline: nothing was changed." : error.isEmpty() ? "Refused." : Text.sentence(error);
                etag = null;
                nextGet = 0;
            } else if (r.kind() == ApiError.Kind.BAD_REQUEST && o.has("path") && o.get("path").isJsonPrimitive()) {
                String path = Text.sanitize(o.get("path").getAsString(), 200);
                note(path, "error", error.isEmpty() ? "refused" : error);
                Leaf l = byPath.get(path);
                if (l != null && !l.node().section().equals("") && !l.section().equals(section)) {
                    section = l.section();
                    query = "";
                    pendingRebuild = true;
                }
                barStatus = "Not saved: fix the marked value.";
            } else if (r.kind() == ApiError.Kind.NO_PERMISSION || r.kind() == ApiError.Kind.LOGIN || r.kind() == ApiError.Kind.MUST_CHANGE) {
                host.failed(r);
                barStatus = "Not saved: your account can't do that.";
            } else {
                barStatus = "Not saved: " + (error.isEmpty() ? host.describe(r, "HTTP " + r.status()) : error);
            }
            updateBar();
        });
    }

    private void revert() {
        if (saving || !jobs.isEmpty()) return;
        for (String p : new ArrayList<>(draft.paths())) notes.remove(p);
        draft.clear();
        barStatus = "";
        pendingRebuild = true;
    }

    private void pollJobs(long now) {
        String bot = formBot;
        if (bot == null || jobs.isEmpty()) return;
        jobBusy = true;
        int token = requestToken;
        Map<String, String> q = new LinkedHashMap<>();
        q.put("ids", String.join(",", jobs.keySet()));
        host.client().get("/api/v1/bots/" + ServerUrl.seg(bot) + "/jobs", q, r -> {
            if (token != requestToken) return;
            jobBusy = false;
            if (host.closed()) return;
            long t = System.currentTimeMillis();
            if (!r.ok()) {
                nextJobs = t + 2_000;
                if (r.kind() != ApiError.Kind.NETWORK && r.kind() != ApiError.Kind.SERVER && r.kind() != ApiError.Kind.RATE_LIMIT) host.failed(r);
                giveUpOldJobs(t);
                return;
            }
            boolean conflict = false;
            JsonArray list = r.body() != null && r.body().isJsonArray() ? r.body().getAsJsonArray() : new JsonArray();
            for (JsonElement e : list) {
                JsonObject j = Json.obj(e);
                if (j == null) continue;
                Job job = jobs.get(Json.str(j, "id"));
                String status = Json.str(j, "status");
                if (job == null || status.equals("queued") || status.equals("sent")) continue;
                jobs.remove(Json.str(j, "id"));
                busy.remove(job.path());
                JsonElement value = j.has("value") ? j.get("value") : JsonNull.INSTANCE;
                String error = Text.sanitize(Json.str(j, "error"), 120);
                if (status.equals("done") || (status.equals("error") && error.equals("conflict"))) values.add(job.path(), value);
                if (status.equals("done")) {
                    if (draft.get(job.path()) != null && SettingsDraft.same(draft.get(job.path()), job.sent())) draft.remove(job.path());
                    notes.put(job.path(), new Note("ok", "applied: " + SettingsSchema.show(value)));
                } else if (status.equals("error") && error.equals("conflict")) {
                    conflict = true;
                    draft.remove(job.path());
                    notes.put(job.path(), new Note("error", "changed elsewhere meanwhile; reloaded (now " + SettingsSchema.show(value) + ")"));
                } else if (status.equals("error")) {
                    notes.put(job.path(), new Note("error", error.isEmpty() ? "failed" : error));
                } else {
                    notes.put(job.path(), new Note("error", status.equals("expired") ? "the bot didn't pick it up" : "no answer from the bot"));
                }
            }
            giveUpOldJobs(t);
            if (conflict) {
                etag = null;
                nextGet = 0;
            }
            if (jobs.isEmpty()) barStatus = "";
            else nextJobs = t + JOB_POLL_MS;
            pendingRebuild = true;
        });
    }

    private void giveUpOldJobs(long now) {
        jobs.entrySet().removeIf(e -> {
            if (now - e.getValue().since() < JOB_GIVE_UP_MS) return false;
            busy.remove(e.getValue().path());
            notes.put(e.getValue().path(), new Note("error", "no answer from the bot"));
            pendingRebuild = true;
            return true;
        });
    }

    private void note(String path, String kind, String text) {
        if (text == null || text.isEmpty()) notes.remove(path);
        else notes.put(path, new Note(kind, text));
        WLabel l = noteLabels.get(path);
        if (l != null) showNote(l, notes.get(path));
    }

    private static void showNote(WLabel l, Note n) {
        l.set(n == null ? "" : n.text());
        l.color(n == null ? DIM : n.kind().equals("ok") ? GOOD : n.kind().equals("error") ? BAD : WARN);
    }

    // ---------- widgets ----------

    public void build(WContainer parent, GuiTheme theme) {
        this.theme = theme;
        formBox = null;
        saveButton = revertButton = null;
        barLabel = null;
        noteLabels.clear();
        boxes.clear();
        passwordPanel = null;
        lockButton = logoutAllButton = null;
        botSignature = botSignature();

        if (proxyMode) page = Page.BOT;
        WHorizontalList pages = parent.add(theme.horizontalList()).widget();
        for (Page p : proxyMode ? new Page[0] : Page.values()) {
            String title = p == Page.BOT ? "Bot settings" : "Account";
            WButton b = pages.add(theme.button(p == page ? "[ " + title + " ]" : title)).widget();
            b.action = () -> {
                if (page != p) {
                    page = p;
                    host.later(host::rebuild);
                }
            };
        }
        if (page == Page.ACCOUNT) {
            buildAccount(parent);
            return;
        }
        String why = denied();
        if (!why.isEmpty()) {
            parent.add(theme.label(why, theme.scale(520))).expandX();
            return;
        }
        buildBotPage(parent);
    }

    private void buildBotPage(WContainer parent) {
        boolean hide = host.policy().hideNames();
        List<BotList.Bot> bots = host.bots().list().bots();
        if (bots.isEmpty()) {
            parent.add(theme.label(host.bots().loaded() ? "No dashboard bots yet. Turn the Qwerte Stash plugin's dashboard on in the proxy." : "Loading the bots...", theme.scale(520))).expandX();
            return;
        }
        WHorizontalList top = parent.add(theme.horizontalList()).expandX().widget();
        String[] labels = new String[bots.size()];
        String current = null;
        String selected = host.bots().selected();
        for (int i = 0; i < labels.length; i++) {
            BotList.Bot b = bots.get(i);
            labels[i] = (b.online() ? "[on] " : "[off] ") + host.bots().list().name(b, hide);
            if (b.botId().equals(selected)) current = labels[i];
        }
        WDropdown<String> pick = top.add(theme.dropdown(labels, current == null ? labels[0] : current)).widget();
        pick.action = () -> {
            String picked = pick.get();
            for (int i = 0; i < labels.length; i++) {
                if (labels[i].equals(picked)) {
                    String id = bots.get(i).botId();
                    host.later(() -> {
                        host.bots().select(id);
                        host.rebuild();
                    });
                }
            }
        };
        List<String> scopes = scopes();
        if (scopes.isEmpty()) {
            // Proxy mode: this bot has no proxy settings (not reported yet, or the proxy has none)
            parent.add(theme.label(online(bots, selected) ? "This bot has not sent any proxy settings yet." : "The bot is offline; its proxy settings show here once it is back.",
                theme.scale(520))).expandX().widget().color(WARN);
            return;
        }
        if (scopes.size() > 1) {
            String[] sl = new String[scopes.size()];
            for (int i = 0; i < sl.length; i++) sl[i] = scopeLabel(scopes.get(i));
            WDropdown<String> sp = top.add(theme.dropdown(sl, scopeLabel(formScope))).widget();
            sp.action = () -> {
                String picked = sp.get();
                for (int i = 0; i < sl.length; i++) {
                    if (sl[i].equals(picked) && !scopes.get(i).equals(formScope)) {
                        String scope = scopes.get(i);
                        host.later(() -> {
                            formScope = scope;
                            host.rebuild();
                        });
                    }
                }
            };
        }
        barLabel = top.add(theme.label("")).expandX().widget();
        barLabel.color(WARN);
        if (!readOnly()) {
            saveButton = top.add(theme.button("Save")).widget();
            saveButton.action = this::save;
            revertButton = top.add(theme.button("Revert")).widget();
            revertButton.action = () -> host.later(this::revert);
        }
        formBox = parent.add(theme.verticalList()).expandX().widget();
        renderForm();
    }

    private static boolean online(List<BotList.Bot> bots, String id) {
        for (BotList.Bot b : bots) if (b.botId().equals(id)) return b.online();
        return true;
    }

    private void updateBar() {
        int n = draft.size();
        if (saveButton != null) saveButton.set(n > 0 ? "Save (" + n + ")" : "Save");
        if (barLabel != null) {
            String t = barStatus;
            if (t.isEmpty()) t = !jobs.isEmpty() ? "Applying..." : draft.hasInvalid() ? "Fix the marked values first." : n > 0 ? n + " unsaved change" + (n == 1 ? "" : "s") : "";
            barLabel.set(t);
        }
    }

    /** Rebuilds the fields from the state. */
    private void renderForm() {
        if (formBox == null) return;
        boolean hide = host.policy().hideNames();
        formBox.clear();
        noteLabels.clear();
        boxes.clear();
        updateBar();
        if (!message.isEmpty()) {
            formBox.add(theme.label(message, theme.scale(520))).expandX().widget().color(WARN);
            if (locked) {
                WButton unlock = formBox.add(theme.button("Confirm password")).widget();
                unlock.action = () -> host.later(() -> host.confirm("These settings are only shown after you confirm your password.", () -> {
                    locked = false;
                    nextGet = 0;
                    message = "";
                    host.rebuild();
                }));
            }
            return;
        }
        if (!haveState) {
            formBox.add(theme.label("Loading...")).expandX();
            return;
        }
        BotList.Bot bot = host.bots().selectedBot();
        if (!online) {
            String name = bot == null ? "The bot" : host.bots().list().name(bot, hide);
            formBox.add(theme.label(name + " is offline" + (lastSeen > 0 ? " (last seen " + Text.ago(System.currentTimeMillis() - lastSeen) + ")" : "")
                + ". Settings are read-only until it is back.", theme.scale(520))).expandX().widget().color(WARN);
        }
        if (readOnly()) formBox.add(theme.label("Read only: your account can't change these settings.", theme.scale(520))).expandX().widget().color(DIM);

        WHorizontalList bar = formBox.add(theme.horizontalList()).expandX().widget();
        WTextBox search = bar.add(theme.textBox(query, "Search all settings")).minWidth(theme.scale(200)).expandX().widget();
        boxes.add(search);
        searchBox = search;
        search.action = () -> {
            String q = search.get();
            if (q.equals(query)) return;
            query = q;
            lastTyped = System.currentTimeMillis();
            searchDirtyAt = System.currentTimeMillis();
            searchPending = true;
        };
        if (sections.size() > 1) {
            String[] names = new String[sections.size()];
            String cur = null;
            for (int i = 0; i < names.length; i++) {
                SettingsSchema.Section s = sections.get(i);
                names[i] = s.name() + " (" + s.count() + ")";
                if (s.name().equals(section)) cur = names[i];
            }
            WDropdown<String> dd = bar.add(theme.dropdown(names, cur == null ? names[0] : cur)).widget();
            dd.action = () -> {
                String picked = dd.get();
                for (int i = 0; i < names.length; i++) {
                    if (names[i].equals(picked) && !sections.get(i).name().equals(section)) {
                        String s = sections.get(i).name();
                        host.later(() -> {
                            section = s;
                            query = "";
                            renderForm();
                        });
                    }
                }
            };
        }

        listBox = formBox.add(theme.verticalList()).expandX().widget();
        fillList(hide);
    }

    private WVerticalList listBox;

    /** The fields only (the search box stays, so typing is not interrupted). */
    private void fillList(boolean hide) {
        listBox.clear();
        noteLabels.clear();
        boxes.removeIf(b -> b != searchBox);
        WVerticalList formBox = listBox;
        List<Leaf> shown = query.trim().isEmpty() ? (section == null ? List.of() : SettingsSchema.inSection(leaves, section)) : SettingsSchema.search(leaves, query, 300);
        if (shown.isEmpty()) {
            formBox.add(theme.label(query.trim().isEmpty() ? "No settings." : "Nothing matches.")).expandX();
            return;
        }
        var view = formBox.add(theme.view()).expandX().widget();
        view.maxHeight = Math.max(theme.scale(200), Utils.getWindowHeight() * 0.5);
        String lastGroup = null;
        int count = 0;
        for (Leaf l : shown) {
            if (count++ >= PAGE_LIMIT) {
                view.add(theme.label("Showing the first " + PAGE_LIMIT + " of " + shown.size() + ". Use the search or another section for the rest.")).expandX().widget().color(DIM);
                break;
            }
            String group = (!query.trim().isEmpty() ? l.section() + (l.trail().isEmpty() ? "" : " > ") : "") + String.join(" > ", l.trail());
            if (!group.equals(lastGroup)) {
                lastGroup = group;
                if (!group.isEmpty()) view.add(theme.label(Text.sanitize(group, 120), true)).expandX();
            }
            buildField(view, l.node(), hide);
        }
    }

    private WTextBox searchBox;
    private long searchDirtyAt;
    private boolean searchPending;

    /** Applies a typed search a moment after the last key (called from the tab's tick). */
    public void tickSearch(long now) {
        if (searchPending && now - searchDirtyAt > 250 && listBox != null) {
            searchPending = false;
            fillList(host.policy().hideNames());
        }
    }

    private void buildField(WContainer parent, Node n, boolean hide) {
        WHorizontalList row = parent.add(theme.horizontalList()).expandX().widget();
        WLabel title = row.add(theme.label(n.title())).minWidth(theme.scale(190)).widget();
        title.tooltip = n.help().isEmpty() ? n.path() : n.path() + ": " + n.help();
        String path = n.path();
        JsonElement shownValue = draft.get(path) != null ? draft.get(path) : serverValue(path);
        boolean hidden = n.secret() || (hide && sensitive(path));
        boolean editable = !hidden && !readOnly() && online && !n.locked() && !busy.contains(path) && jobs.isEmpty();
        if (n.secret()) {
            row.add(theme.label(n.set() ? "secret, set" : "secret, not set")).widget().color(DIM);
        } else if (hidden) {
            row.add(theme.label("(hidden in streamer display)")).widget().color(DIM);
        } else if (!editable) {
            row.add(theme.label(SettingsSchema.show(shownValue))).widget();
            if (n.locked() && !n.reason().isEmpty()) row.add(theme.label(n.reason())).widget().color(DIM);
        } else {
            try {
                control(row, n, shownValue);
            } catch (RuntimeException e) {
                // A value of an unexpected shape must not break drawing: show it read-only
                row.add(theme.label(SettingsSchema.show(shownValue)));
            }
        }
        WLabel noteLabel = row.add(theme.label("")).expandX().widget();
        noteLabels.put(path, noteLabel);
        showNote(noteLabel, notes.get(path));
        if (draft.isInvalid(path) && notes.get(path) == null) showNote(noteLabel, new Note("error", "invalid value"));
    }

    private void edited(Node n, Parsed p) {
        lastTyped = System.currentTimeMillis();
        String path = n.path();
        if (!p.ok()) {
            draft.markInvalid(path);
            note(path, "error", p.error());
        } else {
            draft.set(path, p.value(), serverValue(path));
            note(path, "", "");
        }
        barStatus = "";
        updateBar();
    }

    private void editedValue(Node n, JsonElement value) {
        edited(n, new Parsed(true, value, ""));
    }

    private void control(WHorizontalList row, Node n, JsonElement value) {
        switch (n.type()) {
            case "bool" -> {
                JsonElement[] cur = {value};
                WButton t = row.add(theme.button(boolText(cur[0]))).widget();
                t.action = () -> {
                    boolean on = cur[0].isJsonPrimitive() && cur[0].getAsJsonPrimitive().isBoolean() && cur[0].getAsBoolean();
                    cur[0] = new JsonPrimitive(cur[0].isJsonNull() || !on);
                    t.set(boolText(cur[0]));
                    editedValue(n, cur[0]);
                };
            }
            case "enum" -> {
                List<String> opts = new ArrayList<>();
                if (n.nullable()) opts.add("(none)");
                opts.addAll(n.options());
                if (opts.isEmpty()) {
                    row.add(theme.label(SettingsSchema.show(value)));
                    return;
                }
                String cur = value.isJsonPrimitive() ? value.getAsString() : (n.nullable() ? "(none)" : opts.get(0));
                if (!opts.contains(cur)) opts.add(cur);
                WDropdown<String> dd = row.add(theme.dropdown(opts.toArray(new String[0]), cur)).widget();
                dd.action = () -> editedValue(n, dd.get().equals("(none)") && n.nullable() ? JsonNull.INSTANCE : new JsonPrimitive(dd.get()));
            }
            case "stringList" -> listControl(row, n, value);
            default -> {
                String text = value.isJsonNull() ? "" : value.isJsonPrimitive() ? value.getAsString() : "";
                WTextBox box = row.add(theme.textBox(text)).minWidth(theme.scale(n.numeric() ? 120 : 220)).widget();
                boxes.add(box);
                WCheckbox[] unset = {null};
                if (n.nullable()) {
                    unset[0] = row.add(theme.checkbox(value.isJsonNull())).widget();
                    row.add(theme.label("unset"));
                }
                Runnable changed = () -> edited(n, SettingsSchema.parse(n, box.get(), unset[0] != null && unset[0].checked));
                box.action = changed;
                if (unset[0] != null) unset[0].action = changed;
                if (n.numeric()) {
                    String range = (Double.isNaN(n.min()) ? "" : "min " + n.min()) + (Double.isNaN(n.max()) ? "" : (Double.isNaN(n.min()) ? "" : ", ") + "max " + n.max());
                    if (!range.isEmpty()) box.tooltip = range;
                }
            }
        }
    }

    private static String boolText(JsonElement v) {
        if (v.isJsonNull()) return "(none)";
        return v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() ? (v.getAsBoolean() ? "ON" : "OFF") : "?";
    }

    /** A list of text: the entries with a remove button each, and a box to add one. */
    private void listControl(WHorizontalList row, Node n, JsonElement value) {
        List<String> items = new ArrayList<>();
        if (value.isJsonArray()) for (JsonElement e : value.getAsJsonArray()) if (e.isJsonPrimitive()) items.add(e.getAsString());
        WVerticalList col = row.add(theme.verticalList()).widget();
        for (int i = 0; i < items.size() && i < LIST_SHOWN; i++) {
            int index = i;
            WHorizontalList line = col.add(theme.horizontalList()).widget();
            line.add(theme.label(Text.sanitize(items.get(i), 120)));
            WButton x = line.add(theme.button("x")).widget();
            x.tooltip = "Remove";
            x.action = () -> {
                items.remove(index);
                editedValue(n, toArray(items));
                host.later(this::renderForm);
            };
        }
        if (items.size() > LIST_SHOWN) col.add(theme.label("and " + (items.size() - LIST_SHOWN) + " more (kept as they are)")).widget().color(DIM);
        WHorizontalList add = col.add(theme.horizontalList()).widget();
        WTextBox box = add.add(theme.textBox("", "add...")).minWidth(theme.scale(160)).widget();
        boxes.add(box);
        WButton plus = add.add(theme.button("Add")).widget();
        plus.action = () -> {
            String t = box.get().trim();
            if (t.isEmpty() || items.size() >= 500) return;
            items.add(t);
            editedValue(n, toArray(items));
            host.later(this::renderForm);
        };
    }

    private static JsonArray toArray(List<String> items) {
        JsonArray a = new JsonArray();
        for (String s : items) a.add(s);
        return a;
    }

    // ---------- account ----------

    private void buildAccount(WContainer parent) {
        AuthState a = auth();
        boolean hide = host.policy().hideNames();
        String who = a.userName().isBlank() ? "owner" : Text.sanitize(a.userName(), 40);
        parent.add(theme.label("Account", true));
        parent.add(theme.label(hide ? "Logged in" : "Logged in as " + who + (a.owner() ? " (owner)" : ""))).expandX();
        if (a.owner()) {
            parent.add(theme.label("The owner has every permission.", theme.scale(520))).expandX().widget().color(DIM);
        } else {
            List<String> can = new ArrayList<>();
            for (UsersModel.Switch s : UsersModel.SWITCHES) if (a.can(s.key()) && !s.key().equals("streamer")) can.add(s.label());
            if (!a.permMode("proxy").equals("none")) can.add("Proxy settings: " + a.permMode("proxy"));
            if (!a.permMode("console").equals("none")) can.add("Console: " + a.permMode("console"));
            parent.add(theme.label("Your account can: " + (can.isEmpty() ? "nothing yet" : String.join(", ", can)) + ".", theme.scale(520))).expandX().widget().color(DIM);
            if (a.forcedStreamer()) parent.add(theme.label("Streamer mode is enforced for your account by the owner.", theme.scale(520))).expandX().widget().color(WARN);
        }
        passwordPanel = new OwnPasswordPanel();
        passwordPanel.build(parent, theme, a.minLength(), passwordMessage, this::changePassword);

        parent.add(theme.label("Sessions", true));
        WHorizontalList row = parent.add(theme.horizontalList()).widget();
        lockButton = row.add(theme.button("Lock admin now")).widget();
        lockButton.tooltip = "Ends admin mode: proxy settings, the console and user management ask for your password again.";
        lockButton.action = () -> host.write("/api/v1/auth/lock", new JsonObject(), r -> {
            if (host.closed()) return;
            host.notice(r.ok() ? "Admin locked." : host.describe(r, "Could not lock admin."));
            if (!r.ok()) host.failed(r);
            else host.refreshAuth();
        });
        WButton logout = row.add(theme.button("Log out")).widget();
        logout.action = () -> host.later(host::logout);
        logoutAllButton = row.add(theme.button("Log out everywhere")).widget();
        logoutAllButton.tooltip = a.owner() ? "Ends every session of every account, including this one. Click twice."
            : "Ends every session of your account, including this one. Click twice.";
        logoutAllButton.action = () -> {
            long now = System.currentTimeMillis();
            if (now > logoutAllArmedUntil) {
                logoutAllArmedUntil = now + ARM_MS;
                logoutAllButton.set("Confirm log out everywhere?");
                return;
            }
            logoutAllArmedUntil = 0;
            host.later(host::logoutEverywhere);
        };
    }

    private void changePassword(String current, String next) {
        if (passwordBusy) return;
        passwordBusy = true;
        passwordMessage = "";
        if (passwordPanel != null) passwordPanel.setNotice("Saving...");
        JsonObject b = new JsonObject();
        b.addProperty("current", current);
        b.addProperty("next", next);
        host.client().post("/api/v1/auth/password", b, r -> {
            passwordBusy = false;
            if (host.closed()) return;
            String text;
            if (r.ok()) text = "Password changed. Your other sessions were logged out.";
            else if (r.kind() == ApiError.Kind.WRONG_PASSWORD) text = "The current password is wrong.";
            else text = host.describe(r, "Could not change the password (HTTP " + r.status() + ").");
            passwordMessage = text;
            if (passwordPanel != null) passwordPanel.setNotice(text);
            if (!r.ok() && r.kind() != ApiError.Kind.WRONG_PASSWORD && r.kind() != ApiError.Kind.RATE_LIMIT && r.kind() != ApiError.Kind.NETWORK) host.failed(r);
        });
    }

    /** The window is closing: empty the password boxes. */
    public void clearSecrets() {
        if (passwordPanel != null) passwordPanel.clearSecrets();
    }
}

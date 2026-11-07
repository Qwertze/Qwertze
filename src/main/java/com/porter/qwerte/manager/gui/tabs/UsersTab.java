package com.porter.qwerte.manager.gui.tabs;

import com.google.gson.JsonObject;
import com.porter.qwerte.manager.gui.widgets.MaskedRenderer;
import com.porter.qwerte.manager.model.BotList;
import com.porter.qwerte.manager.model.StashRef;
import com.porter.qwerte.manager.model.Text;
import com.porter.qwerte.manager.model.UsersModel;
import com.porter.qwerte.manager.model.UsersModel.Data;
import com.porter.qwerte.manager.model.UsersModel.Perms;
import com.porter.qwerte.manager.model.UsersModel.StashScope;
import com.porter.qwerte.manager.model.UsersModel.User;
import com.porter.qwerte.manager.net.ApiError;
import com.porter.qwerte.manager.net.ApiResult;
import com.porter.qwerte.manager.net.ServerUrl;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Users tab (owner only): sub-users as the website's Settings page manages them. List, add, edit permissions (all of
 * them, including the live viewer and forced streamer mode), disable or enable, reset the password, log out, delete, rename
 * the owner, and read the audit log. Every write goes through the window's confirm-password retry. The server checks
 * everything; the forms only save a round trip. Names and audit text come from other people and are drawn as plain text.
 */
public final class UsersTab {
    private enum Kind { NONE, ADD, EDIT, RESET, OWNER }

    private static final Color WARN = new Color(255, 190, 80);
    private static final Color BAD = new Color(255, 110, 110);
    private static final Color DIM = new Color(170, 170, 180);
    private static final long ARM_MS = 4_000;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ServerTabHost host;
    private GuiTheme theme;
    private WVerticalList box;

    private Data data;
    private boolean loading;
    private boolean locked;
    private String error = "";
    private int token;

    private Kind kind = Kind.NONE;
    private String formId = "";
    private String formName = "";
    private String formPassword = "";
    private String formAgain = "";
    private boolean formMust = true;
    private String formPreset = "custom";
    private String formAllow = "";
    private Perms formPerms = new Perms();
    private String formMessage = "";
    private boolean formBusy;
    private final List<WTextBox> secrets = new ArrayList<>();

    private String armedKey = "";
    private long armedUntil;

    private boolean showAudit;
    private List<UsersModel.AuditEntry> audit = List.of();
    private String auditStatus = "";

    public UsersTab(ServerTabHost host) {
        this.host = host;
    }

    public void reset() {
        token++;
        data = null;
        loading = false;
        locked = false;
        error = "";
        audit = List.of();
        showAudit = false;
        closeForm();
    }

    private void closeForm() {
        kind = Kind.NONE;
        formId = "";
        formPassword = "";
        formAgain = "";
        formMessage = "";
        formBusy = false;
    }

    public void clearSecrets() {
        formPassword = "";
        formAgain = "";
        for (WTextBox b : secrets) b.set("");
    }

    // ---------- loading ----------

    public void tick(long now) {
        if (host.closed() || !host.auth().owner()) return;
        host.bots().tick(now, true);
        if (data == null && !loading && !locked && error.isEmpty()) load();
        if (!armedKey.isEmpty() && now > armedUntil) {
            armedKey = "";
            if (box != null) renderBody();
        }
    }

    private void load() {
        loading = true;
        int t = token;
        host.client().get("/api/v1/users", null, r -> {
            if (t != token) return;
            loading = false;
            if (host.closed()) return;
            if (r.ok()) {
                data = UsersModel.parse(r.body());
                error = "";
                locked = false;
            } else if (r.kind() == ApiError.Kind.CONFIRM) {
                locked = true;
                error = "";
            } else {
                error = host.describe(r, "Could not load the users (HTTP " + r.status() + ").");
                host.failed(r);
                if (r.kind() == ApiError.Kind.NETWORK) data = null;
            }
            if (box != null) renderBody();
        });
    }

    private void reload() {
        data = null;
        loading = false;
        token++;
        load();
    }

    // ---------- widgets ----------

    public void build(WContainer parent, GuiTheme theme) {
        this.theme = theme;
        secrets.clear();
        if (!host.auth().owner()) {
            parent.add(theme.label("Only the owner can manage users.")).expandX();
            return;
        }
        box = parent.add(theme.verticalList()).expandX().widget();
        renderBody();
    }

    private void later() {
        host.later(this::renderBody);
    }

    private void renderBody() {
        if (box == null) return;
        box.clear();
        secrets.clear();
        boolean hide = host.policy().hideNames();
        if (locked) {
            box.add(theme.label("The users are only shown after you confirm your password.", theme.scale(520))).expandX();
            WButton unlock = box.add(theme.button("Confirm password")).widget();
            unlock.action = () -> host.later(() -> host.confirm("Managing users needs your password again. It is not asked for again for 15 minutes.", () -> {
                locked = false;
                data = null;
                host.rebuild();
            }));
            return;
        }
        if (data == null) {
            box.add(theme.label(error.isEmpty() ? "Loading..." : error, theme.scale(520))).expandX().widget().color(error.isEmpty() ? DIM : BAD);
            if (!error.isEmpty()) {
                WButton retry = box.add(theme.button("Try again")).widget();
                retry.action = () -> host.later(() -> {
                    error = "";
                    renderBody();
                });
            }
            return;
        }
        // The owner
        WHorizontalList owner = box.add(theme.horizontalList()).expandX().widget();
        owner.add(theme.label(hide ? "Owner" : data.ownerName() + " (owner)")).expandX();
        owner.add(theme.label("Every permission, always.")).widget().color(DIM);
        WButton rename = owner.add(theme.button("Rename")).widget();
        rename.action = () -> {
            toggle(Kind.OWNER, "owner");
            formName = data.ownerName();
        };
        if (kind == Kind.OWNER) renameForm();

        WHorizontalList bar = box.add(theme.horizontalList()).expandX().widget();
        bar.add(theme.label(data.users().size() + " of " + data.maxUsers() + " sub-users")).expandX();
        WButton add = bar.add(theme.button("Add user")).widget();
        add.action = () -> {
            toggle(Kind.ADD, "");
            formName = "";
            formMust = true;
            formPreset = data.presets().isEmpty() ? "custom" : data.presets().keySet().iterator().next();
            formPerms = formPreset.equals("custom") ? defaultPerms() : data.presets().get(formPreset).copy();
            formAllow = UsersModel.joinAllow(formPerms.consoleAllow);
        };
        if (data.users().size() >= data.maxUsers()) add.tooltip = "At most " + data.maxUsers() + " sub-users";
        WButton auditButton = bar.add(theme.button(showAudit ? "Hide audit log" : "Audit log")).widget();
        auditButton.action = () -> {
            showAudit = !showAudit;
            if (showAudit) loadAudit();
            later();
        };
        if (kind == Kind.ADD) addForm();

        if (data.users().isEmpty()) box.add(theme.label("No sub-users yet. Add one to let someone else in with fewer permissions.", theme.scale(520))).expandX().widget().color(DIM);
        for (User u : data.users()) userRow(u, hide);
        box.add(theme.label(UsersModel.IMPLIED_HINT, theme.scale(560))).expandX().widget().color(DIM);
        if (showAudit) auditPanel(hide);
    }

    private static Perms defaultPerms() {
        Perms p = UsersModel.norm(null);
        p.flag("view", true);
        return p;
    }

    private void toggle(Kind k, String id) {
        if (kind == k && formId.equals(id)) {
            closeForm();
        } else {
            closeForm();
            kind = k;
            formId = id;
        }
        later();
    }

    private void userRow(User u, boolean hide) {
        WVerticalList card = box.add(theme.verticalList()).expandX().widget();
        WHorizontalList head = card.add(theme.horizontalList()).expandX().widget();
        head.add(theme.label(hide ? "User" : u.name(), true));
        head.add(theme.label("[" + UsersModel.presetLabel(u.perms(), data) + "]")).widget().color(DIM);
        if (u.disabled()) head.add(theme.label("Disabled")).widget().color(BAD);
        if (u.mustChange()) head.add(theme.label("Must change password")).widget().color(WARN);
        List<String> meta = new ArrayList<>();
        meta.add("Last login " + (u.lastLoginAt() < 0 ? "never" : Text.ago(System.currentTimeMillis() - u.lastLoginAt())));
        meta.add(u.sessions() + (u.sessions() == 1 ? " session" : " sessions"));
        String scope = UsersModel.scopeText(u.perms());
        if (!scope.isEmpty()) meta.add(scope);
        card.add(theme.label(String.join(" - ", meta))).expandX().widget().color(DIM);

        WHorizontalList actions = card.add(theme.horizontalList()).widget();
        String name = hide ? "this user" : u.name();
        WButton edit = actions.add(theme.button("Edit")).widget();
        edit.action = () -> {
            toggle(Kind.EDIT, u.id());
            formName = u.name();
            formPerms = u.perms().copy();
            formAllow = UsersModel.joinAllow(formPerms.consoleAllow);
        };
        String disableKey = "disable:" + u.id();
        WButton disable = actions.add(theme.button(u.disabled() ? "Enable" : armedKey.equals(disableKey) ? "Confirm disable?" : "Disable")).widget();
        disable.tooltip = u.disabled() ? "Lets " + name + " log in again." : "Ends every session of " + name + " now and blocks logging in. Click twice.";
        disable.action = () -> {
            JsonObject b = new JsonObject();
            b.addProperty("disabled", !u.disabled());
            if (u.disabled() || armed(disableKey)) post("/api/v1/users/" + ServerUrl.seg(u.id()), b, u.disabled() ? "Enabled." : "Disabled.");
        };
        WButton reset = actions.add(theme.button("Reset password")).widget();
        reset.action = () -> {
            toggle(Kind.RESET, u.id());
            formPassword = "";
            formAgain = "";
            formMust = true;
        };
        String logoutKey = "logout:" + u.id();
        WButton logout = actions.add(theme.button(armedKey.equals(logoutKey) ? "Confirm log out?" : "Log out everywhere")).widget();
        logout.tooltip = "Ends every session of " + name + ". Click twice.";
        logout.action = () -> {
            if (armed(logoutKey)) post("/api/v1/users/" + ServerUrl.seg(u.id()) + "/logout", new JsonObject(), "Sessions ended.");
        };
        String deleteKey = "delete:" + u.id();
        WButton delete = actions.add(theme.button(armedKey.equals(deleteKey) ? "Confirm delete?" : "Delete")).widget();
        delete.tooltip = "Deletes " + name + ". Their sessions end now; their orders stay. This can't be undone. Click twice.";
        delete.action = () -> {
            if (armed(deleteKey)) post("/api/v1/users/" + ServerUrl.seg(u.id()) + "/delete", new JsonObject(), "Deleted.");
        };
        if (kind == Kind.EDIT && formId.equals(u.id())) editForm(u);
        if (kind == Kind.RESET && formId.equals(u.id())) resetForm(u);
    }

    /** First click arms, second (within 4 s) confirms. Returns true when confirmed. */
    private boolean armed(String key) {
        long now = System.currentTimeMillis();
        if (armedKey.equals(key) && now <= armedUntil) {
            armedKey = "";
            return true;
        }
        armedKey = key;
        armedUntil = now + ARM_MS;
        later();
        return false;
    }

    private void post(String path, JsonObject body, String ok) {
        int t = token;
        host.write(path, body, r -> {
            if (host.closed()) return;
            if (r.ok()) {
                host.notice(ok);
                closeForm();
                reload();
                renderBody();
                return;
            }
            formBusy = false;
            String text = r.kind() == ApiError.Kind.CANCELLED ? "Cancelled: the password was not confirmed." : serverText(r, "Could not do that (HTTP " + r.status() + ").");
            formMessage = text;
            host.notice(text);
            if (r.kind() == ApiError.Kind.LOGIN || r.kind() == ApiError.Kind.MUST_CHANGE) host.failed(r);
            if (t == token) renderBody();
        });
    }

    private String serverText(ApiResult r, String fallback) {
        String e = Text.sentence(Text.sanitize(r.error(), 160));
        return e.isEmpty() ? host.describe(r, fallback) : e;
    }

    // ---------- forms ----------

    private WTextBox field(WContainer parent, String label, String text, java.util.function.Consumer<String> set) {
        parent.add(theme.label(label));
        WTextBox b = parent.add(theme.textBox(text)).minWidth(theme.scale(300)).expandX().widget();
        b.action = () -> set.accept(b.get());
        return b;
    }

    private WTextBox secret(WContainer parent, String label, String text, java.util.function.Consumer<String> set) {
        parent.add(theme.label(label));
        WTextBox b = parent.add(theme.textBox(text, null, MaskedRenderer.ANY_CHAR, MaskedRenderer.class)).minWidth(theme.scale(300)).expandX().widget();
        b.action = () -> set.accept(b.get());
        secrets.add(b);
        return b;
    }

    private WLabel message(WContainer parent) {
        WLabel l = parent.add(theme.label(formMessage, theme.scale(520))).expandX().widget();
        l.color(BAD);
        return l;
    }

    private WHorizontalList buttons(WContainer parent, String save, Runnable onSave) {
        WHorizontalList row = parent.add(theme.horizontalList()).widget();
        WButton s = row.add(theme.button(save)).widget();
        s.action = () -> {
            if (!formBusy) onSave.run();
        };
        WButton c = row.add(theme.button("Cancel")).widget();
        c.action = () -> {
            closeForm();
            later();
        };
        return row;
    }

    private WVerticalList formBox() {
        WVerticalList f = box.add(theme.verticalList()).expandX().widget();
        return f;
    }

    private void fail(String text) {
        formMessage = text;
        later();
    }

    private void renameForm() {
        WVerticalList f = formBox();
        f.add(theme.label("Rename the owner", true));
        field(f, "Owner name (1-32 letters, digits, _ . -)", formName, v -> formName = v);
        f.add(theme.label("The owner can still log in with a blank username.")).widget().color(DIM);
        buttons(f, "Rename", () -> {
            String n = formName.trim();
            if (n.equals(data.ownerName())) {
                closeForm();
                later();
                return;
            }
            String bad = UsersModel.checkName(n, "owner", data);
            if (!bad.isEmpty()) {
                fail(bad);
                return;
            }
            JsonObject b = new JsonObject();
            b.addProperty("name", n);
            formBusy = true;
            post("/api/v1/users/owner", b, "The owner is now " + n + ".");
            host.refreshAuth();
        });
        message(f);
    }

    private void addForm() {
        WVerticalList f = formBox();
        f.add(theme.label("Add user", true));
        field(f, "Username (1-32 letters, digits, _ . -)", formName, v -> formName = v);
        secret(f, "Temporary password (at least " + data.minLength() + " characters)", formPassword, v -> formPassword = v);
        secret(f, "Temporary password again", formAgain, v -> formAgain = v);
        List<String> ids = new ArrayList<>(data.presets().keySet());
        List<String> labels = new ArrayList<>();
        for (String id : ids) labels.add(UsersModel.presetName(id));
        labels.add("Custom");
        ids.add("custom");
        f.add(theme.label("Permissions"));
        WDropdown<String> dd = f.add(theme.dropdown(labels.toArray(new String[0]), labels.get(ids.indexOf(formPreset)))).widget();
        dd.action = () -> {
            int i = labels.indexOf(dd.get());
            if (i < 0 || ids.get(i).equals(formPreset)) return;
            Perms from = formPreset.equals("custom") ? formPerms : data.presets().get(formPreset);
            formPreset = ids.get(i);
            // Custom starts from the preset picked before
            formPerms = formPreset.equals("custom") ? (from == null ? defaultPerms() : from.copy()) : data.presets().get(formPreset).copy();
            formAllow = UsersModel.joinAllow(formPerms.consoleAllow);
            later();
        };
        if (formPreset.equals("custom")) {
            permsEditor(f);
        } else {
            f.add(theme.label(UsersModel.describe(data.presets().get(formPreset)), theme.scale(520))).expandX().widget().color(DIM);
        }
        WHorizontalList must = f.add(theme.horizontalList()).widget();
        WCheckbox mc = must.add(theme.checkbox(formMust)).widget();
        mc.action = () -> formMust = mc.checked;
        must.add(theme.label("Must change the password at first login"));
        buttons(f, "Add user", () -> {
            String n = formName.trim();
            String bad = UsersModel.checkName(n, null, data);
            if (bad.isEmpty()) bad = UsersModel.checkPassword(formPassword, formAgain, data.minLength());
            if (!bad.isEmpty()) {
                fail(bad);
                return;
            }
            JsonObject b = new JsonObject();
            b.addProperty("name", n);
            b.addProperty("password", formPassword);
            b.addProperty("mustChange", formMust);
            if (formPreset.equals("custom")) b.add("perms", UsersModel.toJson(finishPerms()));
            else b.addProperty("preset", formPreset);
            formBusy = true;
            post("/api/v1/users", b, "Added " + n + ".");
            clearSecrets();
        });
        message(f);
    }

    private void editForm(User u) {
        WVerticalList f = formBox();
        f.add(theme.label("Edit " + u.name(), true));
        field(f, "Username", formName, v -> formName = v);
        permsEditor(f);
        buttons(f, "Save", () -> {
            String n = formName.trim();
            JsonObject b = new JsonObject();
            if (!n.equals(u.name())) {
                String bad = UsersModel.checkName(n, u.id(), data);
                if (!bad.isEmpty()) {
                    fail(bad);
                    return;
                }
                b.addProperty("name", n);
            }
            b.add("perms", UsersModel.toJson(finishPerms()));
            formBusy = true;
            post("/api/v1/users/" + ServerUrl.seg(u.id()), b, "Saved.");
        });
        message(f);
    }

    private void resetForm(User u) {
        WVerticalList f = formBox();
        f.add(theme.label("Reset the password of " + u.name(), true));
        secret(f, "New temporary password (at least " + data.minLength() + " characters)", formPassword, v -> formPassword = v);
        secret(f, "New temporary password again", formAgain, v -> formAgain = v);
        WHorizontalList must = f.add(theme.horizontalList()).widget();
        WCheckbox mc = must.add(theme.checkbox(formMust)).widget();
        mc.action = () -> formMust = mc.checked;
        must.add(theme.label("Must change the password at next login"));
        f.add(theme.label("Every session of this user ends now.")).widget().color(DIM);
        buttons(f, "Reset password", () -> {
            String bad = UsersModel.checkPassword(formPassword, formAgain, data.minLength());
            if (!bad.isEmpty()) {
                fail(bad);
                return;
            }
            JsonObject b = new JsonObject();
            b.addProperty("password", formPassword);
            b.addProperty("mustChange", formMust);
            formBusy = true;
            post("/api/v1/users/" + ServerUrl.seg(u.id()) + "/password", b, "Password reset.");
            clearSecrets();
        });
        message(f);
    }

    /** The edited permissions with the console list read from the text box and the implied ones switched on. */
    private Perms finishPerms() {
        Perms p = formPerms.copy();
        p.consoleAllow = UsersModel.parseAllow(formAllow);
        return UsersModel.withImplied(p);
    }

    private void permsEditor(WContainer parent) {
        WVerticalList e = parent.add(theme.verticalList()).expandX().widget();
        Perms p = formPerms;
        Set<String> implied = UsersModel.implied(p);
        for (UsersModel.Switch s : UsersModel.SWITCHES) {
            boolean forced = implied.contains(s.key());
            WHorizontalList row = e.add(theme.horizontalList()).widget();
            WCheckbox c = row.add(theme.checkbox(forced || p.flag(s.key()))).widget();
            WLabel l = row.add(theme.label(s.label() + (forced ? " (on, implied)" : ""))).widget();
            l.tooltip = s.help();
            if (s.key().equals("streamer")) l.color(WARN);
            c.action = () -> {
                if (forced) {
                    c.checked = true;
                    return;
                }
                p.flag(s.key(), c.checked);
                later();
            };
        }
        e.add(theme.label("Proxy settings"));
        WDropdown<String> proxy = e.add(theme.dropdown(UsersModel.PROXY_MODES, p.proxy)).widget();
        proxy.action = () -> {
            p.proxy = proxy.get();
            later();
        };
        e.add(theme.label("Console"));
        WDropdown<String> console = e.add(theme.dropdown(UsersModel.CONSOLE_MODES, p.console)).widget();
        console.action = () -> {
            p.console = console.get();
            later();
        };
        if (p.console.equals("list")) {
            field(e, "Allowed commands, separated by ; (a command is allowed when it starts with one of them)", formAllow, v -> formAllow = v);
        }
        scope(e, "Stashes", p.stashes != null, on -> {
            p.stashes = on ? new ArrayList<>() : null;
            later();
        });
        if (p.stashes != null) {
            Map<String, StashScope> known = new LinkedHashMap<>();
            for (StashRef s : host.stashes()) known.put(new StashScope(s.server(), s.stash()).key(), new StashScope(s.server(), s.stash()));
            for (StashScope s : p.stashes) known.putIfAbsent(s.key(), s);
            if (known.isEmpty()) e.add(theme.label("None known yet.")).widget().color(DIM);
            boolean hide = host.policy().hideNames();
            for (StashScope s : known.values()) {
                WHorizontalList row = e.add(theme.horizontalList()).widget();
                WCheckbox c = row.add(theme.checkbox(p.stashes.contains(s))).widget();
                row.add(theme.label(Text.sanitize(s.stash(), 60) + (hide ? "" : " @ " + Text.sanitize(s.server(), 60))));
                c.action = () -> {
                    if (c.checked) {
                        if (!p.stashes.contains(s)) p.stashes.add(s);
                    } else {
                        p.stashes.remove(s);
                    }
                };
            }
        }
        scope(e, "Bots", p.bots != null, on -> {
            p.bots = on ? new ArrayList<>() : null;
            later();
        });
        if (p.bots != null) {
            BotList list = host.bots().list();
            List<String> ids = new ArrayList<>();
            for (BotList.Bot b : list.bots()) ids.add(b.botId());
            for (String id : p.bots) if (!ids.contains(id)) ids.add(id);
            if (ids.isEmpty()) e.add(theme.label("None known yet.")).widget().color(DIM);
            boolean hide = host.policy().hideNames();
            for (String id : ids) {
                BotList.Bot b = list.find(id);
                WHorizontalList row = e.add(theme.horizontalList()).widget();
                WCheckbox c = row.add(theme.checkbox(p.bots.contains(id))).widget();
                row.add(theme.label(b == null ? Text.sanitize(id, 40) + " (not seen now)" : list.name(b, hide) + (hide ? "" : " (" + Text.sanitize(id, 40) + ")")));
                c.action = () -> {
                    if (c.checked) {
                        if (!p.bots.contains(id)) p.bots.add(id);
                    } else {
                        p.bots.remove(id);
                    }
                };
            }
        }
    }

    private void scope(WContainer parent, String what, boolean limited, java.util.function.Consumer<Boolean> set) {
        WHorizontalList row = parent.add(theme.horizontalList()).widget();
        row.add(theme.label(what + ":"));
        WCheckbox only = row.add(theme.checkbox(limited)).widget();
        row.add(theme.label("only these"));
        only.action = () -> set.accept(only.checked);
    }

    // ---------- audit ----------

    private void loadAudit() {
        auditStatus = "Loading...";
        int t = token;
        Map<String, String> q = new LinkedHashMap<>();
        q.put("limit", "200");
        host.read("/api/v1/audit", q, r -> {
            if (t != token || host.closed()) return;
            if (r.ok()) {
                audit = UsersModel.parseAudit(r.body());
                auditStatus = audit.isEmpty() ? "" : audit.size() + " entries, newest first";
            } else {
                auditStatus = serverText(r, "Could not load the audit log.");
            }
            if (box != null) renderBody();
        });
    }

    private void auditPanel(boolean hide) {
        WHorizontalList head = box.add(theme.horizontalList()).expandX().widget();
        head.add(theme.label("Audit log", true));
        head.add(theme.label(auditStatus)).expandX().widget().color(DIM);
        WButton refresh = head.add(theme.button("Refresh")).widget();
        refresh.action = () -> {
            loadAudit();
            later();
        };
        if (audit.isEmpty()) {
            if (auditStatus.isEmpty()) box.add(theme.label("Nothing logged yet.")).widget().color(DIM);
            return;
        }
        var view = box.add(theme.view()).expandX().widget();
        view.maxHeight = Math.max(theme.scale(160), Utils.getWindowHeight() * 0.35);
        int shown = 0;
        for (UsersModel.AuditEntry a : audit) {
            if (shown++ >= 200) break;
            String when = a.at() > 0 ? Instant.ofEpochMilli(a.at()).atZone(ZoneId.systemDefault()).format(WHEN) : "";
            String text = when + "  " + (hide ? "-" : a.user().isEmpty() ? "-" : a.user()) + "  " + a.action()
                + (a.detail().isEmpty() ? "" : "  " + a.detail()) + (a.address().isEmpty() || hide ? "" : "  (" + a.address() + ")");
            view.add(theme.label(text, theme.scale(620))).expandX().widget().color(a.failed() ? BAD : new Color(225, 225, 230));
        }
    }
}

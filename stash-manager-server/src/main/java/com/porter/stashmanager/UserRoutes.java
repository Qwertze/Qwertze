package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Website accounts (PROTOCOL.md, "Users"): the owner lists, adds, changes, disables and deletes sub-users, and reads
 * the audit log. Owner only; every change also needs Admin unlocked (the owner's password in the last 15 minutes).
 */
final class UserRoutes {
    private static final Pattern USER = Pattern.compile("^/api/v1/users/(u-[0-9a-f]{12})$");
    private static final Pattern ACTION = Pattern.compile("^/api/v1/users/(u-[0-9a-f]{12})/(password|logout|delete)$");

    /** Room for a user with 200 scoped stashes. */
    static final int MAX_BODY = 64 * 1024;

    private final Users users;
    private final SessionStore sessions;
    private final AuditLog audit;
    private final PlanStore plans;

    UserRoutes(Users users, SessionStore sessions, AuditLog audit, PlanStore plans) {
        this.users = users;
        this.sessions = sessions;
        this.audit = audit;
        this.plans = plans;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/users", Router.Guard.SESSION, Router.Perm.OWNER, this::list)
            .exact(Router.POST, "/api/v1/users", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::create)
            .exact(Router.POST, "/api/v1/users/owner", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::renameOwner)
            .pattern(Router.POST, USER, Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::update)
            .pattern(Router.POST, ACTION, Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::action)
            .exact(Router.GET, "/api/v1/audit", Router.Guard.SESSION, Router.Perm.OWNER, this::audit);
    }

    private void list(HttpExchange ex) throws IOException {
        Map<String, Integer> counts = sessions.countByUser();
        JsonObject out = new JsonObject();
        JsonObject owner = new JsonObject();
        owner.addProperty("name", users.ownerName());
        owner.addProperty("sessions", counts.getOrDefault(Users.OWNER_ID, 0));
        out.add("owner", owner);
        JsonArray list = new JsonArray();
        for (Users.User u : users.list()) list.add(u.toJson(counts.getOrDefault(u.id(), 0)));
        out.add("users", list);
        JsonObject presets = new JsonObject();
        Users.PRESETS.forEach((name, p) -> presets.add(name, p.toJson()));
        out.add("presets", presets);
        out.addProperty("maxUsers", Users.MAX_USERS);
        out.addProperty("minLength", Auth.MIN_LENGTH);
        Http.json(ex, 200, out);
    }

    /** False after answering 403 "confirm password". */
    private boolean unlocked(HttpExchange ex) throws IOException {
        if (sessions.elevated((String) Http.attr(ex, Router.SESSION_TOKEN))) return true;
        Http.error(ex, 403, "confirm password");
        return false;
    }

    /**
     * perms (the whole object) or preset; null when the body has neither. Throws IllegalArgumentException (answered
     * with 400) for a bad perms object, a preset that isn't text or isn't known, or both at once.
     */
    static Users.Perms perms(JsonObject o) {
        JsonElement pe = o.get("perms");
        JsonElement pr = o.get("preset");
        boolean hasPerms = pe != null && !pe.isJsonNull();
        boolean hasPreset = pr != null && !pr.isJsonNull();
        if (hasPerms && hasPreset) throw new IllegalArgumentException("give perms or preset, not both");
        if (hasPerms) return Users.Perms.parse(pe);
        if (!hasPreset) return null;
        Users.Perms p = pr instanceof JsonPrimitive prim && prim.isString() ? Users.PRESETS.get(prim.getAsString()) : null;
        if (p == null) throw new IllegalArgumentException("preset must be one of " + String.join(", ", Users.PRESETS.keySet()));
        return p;
    }

    /** Whether the body's perms object names "streamer" (true or false) itself. */
    private static boolean explicitStreamer(JsonObject o) {
        JsonElement pe = o.get("perms");
        return pe != null && pe.isJsonObject() && pe.getAsJsonObject().has("streamer") && !pe.getAsJsonObject().get("streamer").isJsonNull();
    }

    private static Boolean optBool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        if (e instanceof JsonPrimitive p && p.isBoolean()) return p.getAsBoolean();
        throw new IllegalArgumentException(key + " must be true or false");
    }

    private void create(HttpExchange ex) throws IOException {
        if (!unlocked(ex)) return;
        JsonObject o = AuthRoutes.body(ex, MAX_BODY);
        if (o == null) return;
        String name = AddonRoutes.string(o, "name");
        String password = AddonRoutes.string(o, "password");
        String problem = Auth.checkNew(password);
        if (problem != null) {
            Http.error(ex, 400, problem);
            return;
        }
        Users.Perms perms;
        Boolean mustChange;
        try {
            perms = perms(o);
            if (perms == null) perms = Users.PRESETS.get("viewer");
            mustChange = optBool(o, "mustChange");
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        Users.User u;
        try {
            u = users.create(name, Auth.hash(password), perms, mustChange == null || mustChange);
        } catch (Users.UserException e) {
            Http.error(ex, e.status, e.getMessage());
            return;
        } catch (IOException e) {
            saveFailed(ex, e);
            return;
        }
        audit.add(Router.user(ex), "user.create", u.name() + " (" + describe(u.perms()) + ")", AuthRoutes.address(ex));
        JsonObject out = Http.ok();
        out.add("user", u.toJson(0));
        Http.json(ex, 200, out);
    }

    private void update(HttpExchange ex) throws IOException {
        Matcher m = USER.matcher(ex.getRequestURI().getRawPath());
        if (!m.matches()) {
            Http.error(ex, 404, "not found");
            return;
        }
        if (!unlocked(ex)) return;
        JsonObject o = AuthRoutes.body(ex, MAX_BODY);
        if (o == null) return;
        String name = null;
        if (o.has("name") && !o.get("name").isJsonNull()) {
            name = AddonRoutes.string(o, "name");
            if (name == null) {
                Http.error(ex, 400, "name must be text");
                return;
            }
        }
        Users.Perms perms;
        Boolean disabled;
        try {
            perms = perms(o);
            disabled = optBool(o, "disabled");
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        if (name == null && perms == null && disabled == null) {
            Http.error(ex, 400, "nothing to change (name, perms, preset or disabled)");
            return;
        }
        Users.User before = users.byId(m.group(1));
        // Force streamer mode is only changed by an explicit "streamer" key: a preset, or perms from an older page
        // without the key, keep the stored flag (nobody gets un-forced by accident).
        if (perms != null && before != null && !explicitStreamer(o)) perms = perms.withStreamer(before.perms().streamer());
        Users.User u;
        try {
            u = users.update(m.group(1), name, perms, disabled);
        } catch (Users.UserException e) {
            Http.error(ex, e.status, e.getMessage());
            return;
        } catch (IOException e) {
            saveFailed(ex, e);
            return;
        }
        int ended = 0;
        if (u.disabled()) {
            ended = sessions.removeUser(u.id(), null);
            plans.dropUser(u.id());
        }
        StringBuilder what = new StringBuilder(u.name());
        if (before != null && !before.name().equals(u.name())) what.append(", renamed from ").append(before.name());
        if (before != null && before.disabled() != u.disabled()) what.append(u.disabled() ? ", disabled" : ", enabled");
        if (before != null && before.perms().streamer() != u.perms().streamer()) {
            what.append(u.perms().streamer() ? ", forced streamer mode ON" : ", forced streamer mode OFF");
        }
        if (before != null && !before.perms().equals(u.perms())) what.append(", permissions: ").append(describe(u.perms()));
        audit.add(Router.user(ex), "user.update", what.toString(), AuthRoutes.address(ex));
        JsonObject out = Http.ok();
        out.add("user", u.toJson(sessions.countByUser().getOrDefault(u.id(), 0)));
        out.addProperty("ended", ended);
        Http.json(ex, 200, out);
    }

    private void action(HttpExchange ex) throws IOException {
        Matcher m = ACTION.matcher(ex.getRequestURI().getRawPath());
        if (!m.matches()) {
            Http.error(ex, 404, "not found");
            return;
        }
        if (!unlocked(ex)) return;
        String id = m.group(1);
        Users.User u = users.byId(id);
        if (u == null) {
            Http.error(ex, 404, "no such user");
            return;
        }
        String address = AuthRoutes.address(ex);
        JsonObject out = Http.ok();
        try {
            switch (m.group(2)) {
                case "password" -> {
                    JsonObject o = AuthRoutes.body(ex, MAX_BODY);
                    if (o == null) return;
                    String password = AddonRoutes.string(o, "password");
                    String problem = Auth.checkNew(password);
                    if (problem != null) {
                        Http.error(ex, 400, problem);
                        return;
                    }
                    Boolean mustChange;
                    try {
                        mustChange = optBool(o, "mustChange");
                    } catch (IllegalArgumentException e) {
                        Http.error(ex, 400, e.getMessage());
                        return;
                    }
                    users.setPassword(id, Auth.hash(password), mustChange == null || mustChange);
                    out.addProperty("ended", sessions.removeUser(id, null));
                    plans.dropUser(id);
                    audit.add(Router.user(ex), "user.password", u.name(), address);
                }
                case "logout" -> {
                    out.addProperty("ended", sessions.removeUser(id, null));
                    plans.dropUser(id);
                    audit.add(Router.user(ex), "user.logout", u.name(), address);
                }
                default -> {
                    users.delete(id);
                    out.addProperty("ended", sessions.removeUser(id, null));
                    plans.dropUser(id);
                    audit.add(Router.user(ex), "user.delete", u.name(), address);
                }
            }
        } catch (Users.UserException e) {
            Http.error(ex, e.status, e.getMessage());
            return;
        } catch (IOException e) {
            saveFailed(ex, e);
            return;
        }
        Http.json(ex, 200, out);
    }

    private void renameOwner(HttpExchange ex) throws IOException {
        if (!unlocked(ex)) return;
        JsonObject o = AuthRoutes.body(ex, MAX_BODY);
        if (o == null) return;
        String before = users.ownerName();
        try {
            users.renameOwner(AddonRoutes.string(o, "name"));
        } catch (Users.UserException e) {
            Http.error(ex, e.status, e.getMessage());
            return;
        } catch (IOException e) {
            saveFailed(ex, e);
            return;
        }
        audit.add(Users.OWNER_ID, users.ownerName(), "owner.rename", before + " to " + users.ownerName(), AuthRoutes.address(ex));
        Http.json(ex, 200, Http.ok());
    }

    private void audit(HttpExchange ex) throws IOException {
        int limit = 200;
        try {
            limit = Integer.parseInt(Http.query(ex).getOrDefault("limit", "200"));
        } catch (NumberFormatException ignored) {
        }
        limit = Math.max(1, Math.min(AuditLog.MAX, limit));
        JsonObject out = new JsonObject();
        out.add("entries", audit.newest(limit));
        Http.json(ex, 200, out);
    }

    private static void saveFailed(HttpExchange ex, IOException e) throws IOException {
        System.err.println("Could not save users.json: " + e.getMessage());
        Http.error(ex, 500, "could not save");
    }

    /** A short summary of permissions for the audit log, e.g. "view, order, console list, 2 stashes". */
    static String describe(Users.Perms p) {
        StringBuilder sb = new StringBuilder();
        java.util.function.Consumer<String> add = s -> sb.append(sb.isEmpty() ? "" : ", ").append(s);
        if (p.view()) add.accept("view");
        if (p.order()) add.accept("order");
        if (p.ordersAll()) add.accept("all orders");
        if (p.activity()) add.accept("bots");
        if (p.settings()) add.accept("settings");
        if (p.proxyRead()) add.accept("proxy " + p.proxy());
        if (p.consoleRead()) add.accept("console " + p.console() + ("list".equals(p.console()) ? " (" + p.consoleAllow().size() + ")" : ""));
        if (p.rescan()) add.accept("rescan");
        if (p.link()) add.accept("link");
        if (p.planner()) add.accept("planner");
        if (p.plannerOrder()) add.accept("planner order");
        if (p.viewer()) add.accept("live viewer");
        if (p.streamer()) add.accept("forced streamer mode");
        if (p.stashes() != null) add.accept(p.stashes().size() + " stash(es)");
        if (p.bots() != null) add.accept(p.bots().size() + " bot(s)");
        return sb.isEmpty() ? "nothing" : sb.toString();
    }
}

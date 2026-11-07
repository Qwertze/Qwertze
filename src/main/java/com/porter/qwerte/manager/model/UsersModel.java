package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sub-user management, as the owner's Settings page of the website does it: the list from {@code GET /api/v1/users}, the
 * permission object and the rules that are applied to it when it is saved, and the checks the forms run before they send.
 * The server is the authority; this only saves a round trip and keeps the labels honest. Pure.
 */
public final class UsersModel {
    private UsersModel() {
    }

    public static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,32}$");
    public static final String[] PROXY_MODES = {"none", "read", "change"};
    public static final String[] CONSOLE_MODES = {"none", "list", "full"};

    /** A yes/no permission with its label and help (the website's PERM_SWITCHES). */
    public record Switch(String key, String label, String help) {
    }

    public static final List<Switch> SWITCHES = List.of(
        new Switch("view", "View stashes", "Stash list, stash index, item icons and the order list."),
        new Switch("order", "Place orders", "Place orders; cancel, retry or force-cancel their own orders."),
        new Switch("ordersAll", "Manage all orders", "Cancel, retry or force-cancel anyone's orders; Clear history."),
        new Switch("activity", "Bots and activity", "Bots tab, Activity, job status and the plugin settings (read only)."),
        new Switch("settings", "Change plugin settings", "Save the Qwerte Stash plugin settings."),
        new Switch("rescan", "Rescan stashes", "Ask the bot to re-read every container of a stash."),
        new Switch("link", "Link addon", "The Link addon panel (linked bots in scope) and releasing a stash. The API key itself stays owner-only."),
        new Switch("planner", "Build planner", "Upload a schematic and see its material list against a stash."),
        new Switch("plannerOrder", "Order from the build planner", "Order a build's materials from the planner, and cancel or retry their own orders. Doesn't need Place orders."),
        new Switch("viewer", "Live viewer", "Watch a bot live: the map around it, its position, nearby players, mobs and items, its HUD and its chat. Also turns on Bots and activity."),
        new Switch("streamer", "Force streamer mode", "A restriction: the server never sends this user coordinates, player names, server addresses, chat or the bots' free text. "
            + "Settings, proxy settings and the console are refused. The user can't turn it off."));

    public static final String IMPLIED_HINT = "Placing or managing orders, or the build planner, also turns on View stashes; ordering from the build planner also turns on Build planner. "
        + "Changing plugin settings, the live viewer, or any proxy or console access, also turns on Bots and activity. "
        + "Proxy changes and console commands still need Admin unlocked (the user's own password).";

    /** A stash a user is limited to. */
    public record StashScope(String server, String stash) {
        public String key() {
            return server + "\u0000" + stash;
        }
    }

    /**
     * One user's permissions. {@code stashes} and {@code bots} are null for "all". Mutable so the editor can work on a copy.
     */
    public static final class Perms {
        public final Map<String, Boolean> flags = new LinkedHashMap<>();
        public String proxy = "none";
        public String console = "none";
        public List<String> consoleAllow = new ArrayList<>();
        public List<StashScope> stashes;
        public List<String> bots;

        public boolean flag(String key) {
            return Boolean.TRUE.equals(flags.get(key));
        }

        public void flag(String key, boolean on) {
            flags.put(key, on);
        }

        public Perms copy() {
            Perms p = new Perms();
            p.flags.putAll(flags);
            p.proxy = proxy;
            p.console = console;
            p.consoleAllow = new ArrayList<>(consoleAllow);
            p.stashes = stashes == null ? null : new ArrayList<>(stashes);
            p.bots = bots == null ? null : new ArrayList<>(bots);
            return p;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Perms p)) return false;
            return flags.equals(p.flags) && proxy.equals(p.proxy) && console.equals(p.console) && consoleAllow.equals(p.consoleAllow)
                && java.util.Objects.equals(stashes, p.stashes) && java.util.Objects.equals(bots, p.bots);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(flags, proxy, console, consoleAllow, stashes, bots);
        }
    }

    /** The permission object in a fixed shape: every switch present, unknown values off, lists cleaned. */
    public static Perms norm(JsonElement e) {
        JsonObject o = Json.obj(e);
        Perms p = new Perms();
        for (Switch s : SWITCHES) p.flags.put(s.key(), o != null && Json.bool(o, s.key()));
        if (o == null) return p;
        String proxy = Json.str(o, "proxy");
        p.proxy = proxy.equals("read") || proxy.equals("change") ? proxy : "none";
        String console = Json.str(o, "console");
        p.console = console.equals("list") || console.equals("full") ? console : "none";
        JsonArray allow = Json.arr(o.get("consoleAllow"));
        if (allow != null) {
            for (JsonElement a : allow) {
                if (a.isJsonPrimitive() && a.getAsJsonPrimitive().isString() && p.consoleAllow.size() < 100) p.consoleAllow.add(Text.sanitize(a.getAsString(), 100));
            }
        }
        JsonArray stashes = Json.arr(o.get("stashes"));
        if (stashes != null) {
            p.stashes = new ArrayList<>();
            for (JsonElement s : stashes) {
                JsonObject so = Json.obj(s);
                if (so != null && p.stashes.size() < 500) p.stashes.add(new StashScope(Json.str(so, "server"), Json.str(so, "stash")));
            }
        }
        JsonArray bots = Json.arr(o.get("bots"));
        if (bots != null) {
            p.bots = new ArrayList<>();
            for (JsonElement b : bots) {
                if (b.isJsonPrimitive() && b.getAsJsonPrimitive().isString() && p.bots.size() < 500) p.bots.add(b.getAsString());
            }
        }
        return p;
    }

    /** The server's implied-permission rule (PROTOCOL.md "Additions: website users"), applied the same way. Returns {@code p}. */
    public static Perms withImplied(Perms p) {
        if (p.flag("plannerOrder")) p.flag("planner", true);
        if (p.flag("order") || p.flag("ordersAll") || p.flag("planner")) p.flag("view", true);
        if (p.flag("settings") || p.flag("viewer") || !p.proxy.equals("none") || !p.console.equals("none")) p.flag("activity", true);
        return p;
    }

    /** Which switches the rule forces on for this selection (their toggles are shown "on (implied)" and locked). */
    public static java.util.Set<String> implied(Perms p) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (p.flag("plannerOrder")) out.add("planner");
        if (p.flag("order") || p.flag("ordersAll") || p.flag("planner") || p.flag("plannerOrder")) out.add("view");
        if (p.flag("settings") || p.flag("viewer") || !p.proxy.equals("none") || !p.console.equals("none")) out.add("activity");
        return out;
    }

    /**
     * The JSON the server takes: every switch written out (so {@code streamer} is always explicit and nobody is un-forced by
     * accident), the proxy and console modes, the allowed commands, and the scopes (null = all).
     */
    public static JsonObject toJson(Perms p) {
        JsonObject o = new JsonObject();
        for (Switch s : SWITCHES) o.addProperty(s.key(), p.flag(s.key()));
        o.addProperty("proxy", p.proxy);
        o.addProperty("console", p.console);
        JsonArray allow = new JsonArray();
        for (String a : p.consoleAllow) allow.add(a);
        o.add("consoleAllow", allow);
        if (p.stashes == null) {
            o.add("stashes", com.google.gson.JsonNull.INSTANCE);
        } else {
            JsonArray a = new JsonArray();
            for (StashScope s : p.stashes) {
                JsonObject so = new JsonObject();
                so.addProperty("server", s.server());
                so.addProperty("stash", s.stash());
                a.add(so);
            }
            o.add("stashes", a);
        }
        if (p.bots == null) {
            o.add("bots", com.google.gson.JsonNull.INSTANCE);
        } else {
            JsonArray a = new JsonArray();
            for (String b : p.bots) a.add(b);
            o.add("bots", a);
        }
        return o;
    }

    /** One allowed-command prefix per entry from text typed as {@code a; b; c}: trimmed, spaces collapsed, no duplicates (ignoring case). */
    public static List<String> parseAllow(String text) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (text == null) return out;
        for (String part : text.split("[;\\n]")) {
            String t = part.trim().replaceAll("\\s+", " ");
            if (t.isEmpty() || !seen.add(t.toLowerCase(Locale.ROOT))) continue;
            out.add(t);
            if (out.size() >= 100) break;
        }
        return out;
    }

    public static String joinAllow(List<String> allow) {
        return String.join("; ", allow);
    }

    // ---------- the list ----------

    public record User(String id, String name, boolean disabled, boolean mustChange, Perms perms, long createdAt, long lastLoginAt, int sessions) {
    }

    /** {@code presets} are the server's, by id, in its order. {@code lastLoginAt} is -1 for never. */
    public record Data(String ownerName, List<User> users, Map<String, Perms> presets, int maxUsers, int minLength) {
        public static final Data EMPTY = new Data("owner", List.of(), Map.of(), 50, 12);
    }

    /** Never null. */
    public static Data parse(JsonElement body) {
        JsonObject o = Json.obj(body);
        if (o == null) return Data.EMPTY;
        JsonObject owner = Json.obj(o.get("owner"));
        List<User> users = new ArrayList<>();
        JsonArray ua = Json.arr(o.get("users"));
        if (ua != null) {
            for (JsonElement e : ua) {
                JsonObject u = Json.obj(e);
                if (u == null || !Ids.valid(Json.str(u, "id"))) continue;
                users.add(new User(Json.str(u, "id"), Text.sanitize(Json.str(u, "name"), 40), Json.bool(u, "disabled"), Json.bool(u, "mustChange"),
                    norm(u.get("perms")), Json.lng(u, "createdAt", 0), u.has("lastLoginAt") && u.get("lastLoginAt").isJsonPrimitive() ? Json.lng(u, "lastLoginAt", -1) : -1,
                    (int) Json.num(u, "sessions", 0)));
                if (users.size() >= 100) break;
            }
        }
        Map<String, Perms> presets = new LinkedHashMap<>();
        JsonObject po = Json.obj(o.get("presets"));
        if (po != null) {
            for (String id : List.of("viewer", "orderer", "operator")) if (po.has(id)) presets.put(id, norm(po.get(id)));
            for (Map.Entry<String, JsonElement> e : po.entrySet()) {
                if (!presets.containsKey(e.getKey()) && presets.size() < 20 && e.getValue().isJsonObject()) presets.put(Text.sanitize(e.getKey(), 24), norm(e.getValue()));
            }
        }
        int min = (int) Json.num(o, "minLength", 12);
        return new Data(owner == null ? "owner" : Text.sanitize(Json.str(owner, "name"), 40), users, presets, (int) Json.num(o, "maxUsers", 50), min < 1 ? 12 : min);
    }

    /** "Viewer", "Orderer", "Operator" or "Custom": the preset whose switches and modes this matches (scopes and the command list don't count). */
    public static String presetLabel(Perms p, Data data) {
        Perms shape = shape(p);
        for (Map.Entry<String, Perms> e : data.presets().entrySet()) {
            if (shape(e.getValue()).equals(shape)) return presetName(e.getKey()) + (p.flag("streamer") ? " + forced streamer" : "");
        }
        return "Custom";
    }

    public static String presetName(String id) {
        return switch (id) {
            case "viewer" -> "Viewer";
            case "orderer" -> "Orderer";
            case "operator" -> "Operator";
            default -> id;
        };
    }

    private static Perms shape(Perms p) {
        Perms n = withImplied(p.copy());
        n.stashes = null;
        n.bots = null;
        n.consoleAllow = new ArrayList<>();
        // A preset never touches streamer mode (the server keeps the stored value), so it is not compared; the label adds it
        n.flag("streamer", false);
        return n;
    }

    /** "only 2 stashes, 1 bot" or "". */
    public static String scopeText(Perms p) {
        List<String> parts = new ArrayList<>();
        if (p.stashes != null) parts.add(p.stashes.size() + (p.stashes.size() == 1 ? " stash" : " stashes"));
        if (p.bots != null) parts.add(p.bots.size() + (p.bots.size() == 1 ? " bot" : " bots"));
        return parts.isEmpty() ? "" : "only " + String.join(", ", parts);
    }

    /** "View stashes, Place orders, Proxy settings: read." */
    public static String describe(Perms p) {
        Perms n = withImplied(p.copy());
        List<String> on = new ArrayList<>();
        for (Switch s : SWITCHES) if (n.flag(s.key())) on.add(s.label());
        if (!n.proxy.equals("none")) on.add("Proxy settings: " + n.proxy);
        if (!n.console.equals("none")) on.add("Console: " + n.console);
        return on.isEmpty() ? "Nothing." : String.join(", ", on) + ".";
    }

    // ---------- form checks ----------

    /** "" when {@code name} may be used for the user {@code selfId} (null when adding; "owner" when renaming the owner). */
    public static String checkName(String name, String selfId, Data data) {
        if (!NAME.matcher(name).matches()) return "A username is 1 to 32 letters, digits, _ . or -.";
        String lower = name.toLowerCase(Locale.ROOT);
        if (!"owner".equals(selfId) && data.ownerName().toLowerCase(Locale.ROOT).equals(lower)) return "That is the owner's name.";
        for (User u : data.users()) {
            if (!u.id().equals(selfId) && u.name().toLowerCase(Locale.ROOT).equals(lower)) return "Another user already has that name.";
        }
        return "";
    }

    public static String checkPassword(String password, String again, int minLength) {
        if (password.length() < minLength) return "The password needs at least " + minLength + " characters.";
        if (!password.equals(again)) return "The two passwords are different.";
        return "";
    }

    // ---------- the audit log ----------

    public record AuditEntry(long at, String user, String action, String detail, String address) {
        public boolean failed() {
            return action.endsWith("failed");
        }
    }

    /** {@code GET /api/v1/audit}: newest first. */
    public static List<AuditEntry> parseAudit(JsonElement body) {
        JsonObject o = Json.obj(body);
        JsonArray a = o == null ? null : Json.arr(o.get("entries"));
        List<AuditEntry> out = new ArrayList<>();
        if (a == null) return out;
        for (JsonElement e : a) {
            JsonObject x = Json.obj(e);
            if (x == null) continue;
            out.add(new AuditEntry(Json.lng(x, "at", 0), Text.sanitize(Json.str(x, "user"), 40), Text.sanitize(Json.str(x, "action"), 40),
                Text.sanitize(Json.str(x, "detail"), 300), Text.sanitize(Json.str(x, "address"), 60)));
            if (out.size() >= 2000) break;
        }
        return out;
    }
}

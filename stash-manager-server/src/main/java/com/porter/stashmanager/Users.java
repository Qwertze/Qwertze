package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Website accounts. The owner is the existing dashboard password (config.properties dashboardPassword=) and can do
 * everything; its name lives here (default "owner"). Sub-users, each with a password hash (see Auth) and permissions,
 * are kept in users.json (owner-only file permissions). Users answer the questions the routes ask: who is this session,
 * and may they do this.
 */
final class Users {
    static final String OWNER_ID = "owner";
    static final String DEFAULT_OWNER_NAME = "owner";
    static final int MAX_USERS = 50;
    static final int MAX_NAME = 32;
    static final int MAX_ALLOW = 50;
    static final int MAX_ALLOW_LENGTH = 100;
    static final int MAX_SCOPE = 200;
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1," + MAX_NAME + "}");
    static final Pattern ID = Pattern.compile("u-[0-9a-f]{12}");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Characters a console might read as chaining or substitution; refused in "list" mode. */
    static final Pattern CHAIN = Pattern.compile("[;&|`$\\n\\r]");
    /** In "list" mode a whole command may only use these (plain ASCII words, names, numbers, coordinates). */
    static final Pattern LIST_SAFE = Pattern.compile("[A-Za-z0-9 _:.,@/=+#~-]{1,500}");

    /** A stash a scoped user may see and use. */
    record StashRef(String server, String stash) {
    }

    /**
     * What a sub-user may do. The owner's are {@link #ALL}. stashes and bots null mean every stash or bot. Built only
     * through {@link #of} or {@link #parse}, which apply the implied permissions. planner: upload schematics and see
     * their material list; plannerOrder: order those materials (it does not need order). viewer: the live viewer (the
     * bot's map, position, nearby entities, HUD and chat); it implies activity. streamer: "force streamer mode", a
     * restriction: the server never sends this user positions, player names, server addresses or bot free text (see
     * Redact); the owner is never forced.
     */
    record Perms(boolean view, boolean order, boolean ordersAll, boolean activity, boolean settings, String proxy,
                 String console, List<String> consoleAllow, boolean rescan, boolean link, boolean planner,
                 boolean plannerOrder, boolean viewer, boolean streamer, List<StashRef> stashes, List<String> bots) {
        static final Perms NONE = of(false, false, false, false, false, "none", "none", List.of(), false, false, false, false, false, null, null);
        /** The owner's: everything, and never forced into streamer mode. */
        static final Perms ALL = of(true, true, true, true, true, "change", "full", List.of(), true, true, true, true, true, null, null);

        /** The same without the live viewer. */
        static Perms of(boolean view, boolean order, boolean ordersAll, boolean activity, boolean settings, String proxy,
                        String console, List<String> consoleAllow, boolean rescan, boolean link, boolean planner,
                        boolean plannerOrder, List<StashRef> stashes, List<String> bots) {
            return of(view, order, ordersAll, activity, settings, proxy, console, consoleAllow, rescan, link, planner,
                plannerOrder, false, stashes, bots);
        }

        /**
         * With the implied permissions turned on: ordering and the build planner need view, ordering from the planner
         * needs the planner, bot permissions (the live viewer included) need the Bots view.
         */
        static Perms of(boolean view, boolean order, boolean ordersAll, boolean activity, boolean settings, String proxy,
                        String console, List<String> consoleAllow, boolean rescan, boolean link, boolean planner,
                        boolean plannerOrder, boolean viewer, List<StashRef> stashes, List<String> bots) {
            return of(view, order, ordersAll, activity, settings, proxy, console, consoleAllow, rescan, link, planner,
                plannerOrder, viewer, false, stashes, bots);
        }

        /** The same, with force streamer mode. */
        static Perms of(boolean view, boolean order, boolean ordersAll, boolean activity, boolean settings, String proxy,
                        String console, List<String> consoleAllow, boolean rescan, boolean link, boolean planner,
                        boolean plannerOrder, boolean viewer, boolean streamer, List<StashRef> stashes, List<String> bots) {
            boolean pl = planner || plannerOrder;
            boolean v = view || order || ordersAll || pl;
            boolean a = activity || settings || viewer || !"none".equals(proxy) || !"none".equals(console);
            return new Perms(v, order, ordersAll, a, settings, proxy, console, List.copyOf(consoleAllow), rescan, link,
                pl, plannerOrder, viewer, streamer, stashes == null ? null : List.copyOf(stashes), bots == null ? null : List.copyOf(bots));
        }

        /** The same permissions with force streamer mode set as given. */
        Perms withStreamer(boolean on) {
            return new Perms(view, order, ordersAll, activity, settings, proxy, console, consoleAllow, rescan, link, planner,
                plannerOrder, viewer, on, stashes, bots);
        }

        boolean proxyRead() {
            return !"none".equals(proxy);
        }

        boolean proxyChange() {
            return "change".equals(proxy);
        }

        boolean consoleRead() {
            return !"none".equals(console);
        }

        boolean seesStash(String server, String stash) {
            if (stashes == null) return true;
            for (StashRef r : stashes) if (r.server.equals(server) && r.stash.equals(stash)) return true;
            return false;
        }

        boolean seesBot(String botId) {
            return bots == null || bots.contains(botId);
        }

        /** Whether this console command may be sent: "full", or "list" with a matching allowed prefix. */
        boolean consoleAllows(String command) {
            if ("full".equals(console)) return true;
            if (!"list".equals(console) || command == null) return false;
            if (CHAIN.matcher(command).find() || !LIST_SAFE.matcher(command.trim()).matches()) return false;
            String c = normalizeCommand(command);
            for (String p : consoleAllow) {
                String n = normalizeCommand(p);
                if (!n.isEmpty() && (c.equals(n) || c.startsWith(n + " "))) return true;
            }
            return false;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("view", view);
            o.addProperty("order", order);
            o.addProperty("ordersAll", ordersAll);
            o.addProperty("activity", activity);
            o.addProperty("settings", settings);
            o.addProperty("proxy", proxy);
            o.addProperty("console", console);
            JsonArray allow = new JsonArray();
            consoleAllow.forEach(allow::add);
            o.add("consoleAllow", allow);
            o.addProperty("rescan", rescan);
            o.addProperty("link", link);
            o.addProperty("planner", planner);
            o.addProperty("plannerOrder", plannerOrder);
            o.addProperty("viewer", viewer);
            o.addProperty("streamer", streamer);
            if (stashes == null) {
                o.add("stashes", JsonNull.INSTANCE);
            } else {
                JsonArray s = new JsonArray();
                for (StashRef r : stashes) {
                    JsonObject ro = new JsonObject();
                    ro.addProperty("server", r.server);
                    ro.addProperty("stash", r.stash);
                    s.add(ro);
                }
                o.add("stashes", s);
            }
            if (bots == null) {
                o.add("bots", JsonNull.INSTANCE);
            } else {
                JsonArray b = new JsonArray();
                bots.forEach(b::add);
                o.add("bots", b);
            }
            return o;
        }

        /** Perms from JSON (the whole object: a missing key means off / "none" / every stash or bot). */
        static Perms parse(JsonElement e) {
            if (e == null || !e.isJsonObject()) throw new IllegalArgumentException("perms must be an object");
            JsonObject o = e.getAsJsonObject();
            String proxy = choice(o, "proxy", "none", "read", "change");
            String console = choice(o, "console", "none", "list", "full");
            List<String> allow = new ArrayList<>();
            JsonElement ae = o.get("consoleAllow");
            if (ae != null && !ae.isJsonNull()) {
                if (!ae.isJsonArray()) throw new IllegalArgumentException("consoleAllow must be a list of text");
                for (JsonElement item : ae.getAsJsonArray()) {
                    if (!(item instanceof JsonPrimitive p) || !p.isString()) throw new IllegalArgumentException("consoleAllow must be a list of text");
                    String s = normalizeCommand(p.getAsString());
                    if (s.isEmpty()) continue;
                    if (s.length() > MAX_ALLOW_LENGTH) throw new IllegalArgumentException("console commands can have at most " + MAX_ALLOW_LENGTH + " characters");
                    if (s.chars().anyMatch(Character::isISOControl) || CHAIN.matcher(s).find()) {
                        throw new IllegalArgumentException("console commands can't have control characters or ; & | `");
                    }
                    if (!allow.contains(s)) allow.add(s);
                }
                if (allow.size() > MAX_ALLOW) throw new IllegalArgumentException("at most " + MAX_ALLOW + " console commands");
            }
            List<StashRef> stashes = null;
            JsonElement se = o.get("stashes");
            if (se != null && !se.isJsonNull()) {
                if (!se.isJsonArray()) throw new IllegalArgumentException("stashes must be null or a list of {server, stash}");
                stashes = new ArrayList<>();
                for (JsonElement item : se.getAsJsonArray()) {
                    if (!item.isJsonObject()) throw new IllegalArgumentException("stashes must be null or a list of {server, stash}");
                    String server = AddonRoutes.string(item.getAsJsonObject(), "server");
                    String stash = AddonRoutes.string(item.getAsJsonObject(), "stash");
                    if (server == null || stash == null || server.isEmpty() || stash.isEmpty() || server.length() > 256 || stash.length() > 256) {
                        throw new IllegalArgumentException("every stash needs a server and a stash");
                    }
                    StashRef r = new StashRef(server, stash);
                    if (!stashes.contains(r)) stashes.add(r);
                }
                if (stashes.size() > MAX_SCOPE) throw new IllegalArgumentException("at most " + MAX_SCOPE + " stashes");
            }
            List<String> bots = null;
            JsonElement be = o.get("bots");
            if (be != null && !be.isJsonNull()) {
                if (!be.isJsonArray()) throw new IllegalArgumentException("bots must be null or a list of bot ids");
                bots = new ArrayList<>();
                for (JsonElement item : be.getAsJsonArray()) {
                    if (!(item instanceof JsonPrimitive p) || !p.isString() || !p.getAsString().matches("[A-Za-z0-9_-]{1,64}")) {
                        throw new IllegalArgumentException("bots must be null or a list of bot ids");
                    }
                    if (!bots.contains(p.getAsString())) bots.add(p.getAsString());
                }
                if (bots.size() > MAX_SCOPE) throw new IllegalArgumentException("at most " + MAX_SCOPE + " bots");
            }
            return of(bool(o, "view"), bool(o, "order"), bool(o, "ordersAll"), bool(o, "activity"), bool(o, "settings"),
                proxy, console, allow, bool(o, "rescan"), bool(o, "link"), bool(o, "planner"),
                bool(o, "plannerOrder"), bool(o, "viewer"), bool(o, "streamer"), stashes, bots);
        }

        private static boolean bool(JsonObject o, String key) {
            JsonElement e = o.get(key);
            if (e == null || e.isJsonNull()) return false;
            if (!(e instanceof JsonPrimitive p) || !p.isBoolean()) throw new IllegalArgumentException(key + " must be true or false");
            return p.getAsBoolean();
        }

        private static String choice(JsonObject o, String key, String... options) {
            JsonElement e = o.get(key);
            if (e == null || e.isJsonNull()) return options[0];
            if (e instanceof JsonPrimitive p && p.isString()) {
                for (String opt : options) if (opt.equals(p.getAsString())) return opt;
            }
            throw new IllegalArgumentException(key + " must be one of " + String.join(", ", options));
        }
    }

    /** Lowercase, trimmed, inner whitespace collapsed. */
    static String normalizeCommand(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static final Map<String, Perms> PRESETS;

    static {
        Map<String, Perms> p = new LinkedHashMap<>();
        p.put("viewer", Perms.of(true, false, false, false, false, "none", "none", List.of(), false, false, false, false, null, null));
        p.put("orderer", Perms.of(true, true, false, false, false, "none", "none", List.of(), false, false, true, true, null, null));
        p.put("operator", Perms.of(true, true, true, true, true, "read", "none", List.of(), true, false, true, true, true, null, null));
        PRESETS = java.util.Collections.unmodifiableMap(p);
    }

    /** A sub-user as stored. Immutable: changes replace it. */
    record User(String id, String name, String hash, Perms perms, boolean disabled, boolean mustChange, long createdAt,
                long lastLoginAt, long passwordChangedAt) {
        /** Never the hash. */
        @Override
        public String toString() {
            return "User[" + id + " " + name + (disabled ? " disabled" : "") + "]";
        }

        JsonObject toJson(int sessions) {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("name", name);
            o.addProperty("disabled", disabled);
            o.addProperty("mustChange", mustChange);
            o.add("perms", perms.toJson());
            o.addProperty("createdAt", createdAt);
            o.addProperty("lastLoginAt", lastLoginAt > 0 ? lastLoginAt : null);
            o.addProperty("sessions", sessions);
            return o;
        }
    }

    /** Who a request is from: the owner or a sub-user, with what it may do. */
    record Principal(String id, String name, boolean owner, Perms perms, boolean mustChange) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("name", name);
            o.addProperty("owner", owner);
            o.addProperty("mustChange", mustChange);
            o.add("perms", perms.toJson());
            return o;
        }
    }

    /** A refused change, with the HTTP status to answer. */
    static final class UserException extends Exception {
        final int status;

        UserException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final Path file;
    private final LongSupplier clock;
    private final Map<String, User> users = new LinkedHashMap<>(); // id -> user, in creation order
    private String ownerName = DEFAULT_OWNER_NAME;

    Users(Path dataDir) {
        this(dataDir, System::currentTimeMillis);
    }

    Users(Path dataDir, LongSupplier clock) {
        this.file = dataDir == null ? null : dataDir.resolve("users.json");
        this.clock = clock;
    }

    /** Reads users.json. A broken file is an error: starting without the sub-users would silently lock them out. */
    synchronized void load() throws IOException {
        if (file == null || !Files.exists(file)) return;
        AtomicFiles.ownerOnly(file);
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException(file.getFileName() + " is not valid JSON (" + e.getMessage() + "); fix or delete it", e);
        }
        try {
            String on = root.has("ownerName") ? root.get("ownerName").getAsString() : DEFAULT_OWNER_NAME;
            ownerName = NAME.matcher(on).matches() ? on : DEFAULT_OWNER_NAME;
            JsonArray list = root.has("users") ? root.getAsJsonArray("users") : new JsonArray();
            for (JsonElement e : list) {
                JsonObject o = e.getAsJsonObject();
                String id = o.get("id").getAsString();
                String name = o.get("name").getAsString();
                if (!ID.matcher(id).matches() || users.containsKey(id) || !NAME.matcher(name).matches() || byNameLocked(name) != null
                    || sameName(name, ownerName)) {
                    System.err.println(file.getFileName() + ": skipping a user with a bad or duplicate id or name");
                    continue;
                }
                String hash = o.get("hash").getAsString();
                if (!Auth.wellFormed(hash)) System.err.println(file.getFileName() + ": " + name + " has a broken password hash and can't log in");
                Perms perms;
                try {
                    perms = Perms.parse(o.get("perms"));
                } catch (IllegalArgumentException ex) {
                    System.err.println(file.getFileName() + ": " + name + " has broken permissions (" + ex.getMessage() + "); they are all off");
                    perms = Perms.NONE;
                }
                users.put(id, new User(id, name, hash, perms, o.has("disabled") && o.get("disabled").getAsBoolean(),
                    o.has("mustChange") && o.get("mustChange").getAsBoolean(), longOf(o, "createdAt"), longOf(o, "lastLoginAt"),
                    longOf(o, "passwordChangedAt")));
            }
        } catch (RuntimeException e) {
            throw new IOException(file.getFileName() + " has an unexpected layout (" + e.getMessage() + "); fix or delete it", e);
        }
    }

    private static long longOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    private void save() throws IOException {
        if (file == null) return;
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("ownerName", ownerName);
        JsonArray list = new JsonArray();
        for (User u : users.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", u.id);
            o.addProperty("name", u.name);
            o.addProperty("hash", u.hash);
            o.add("perms", u.perms.toJson());
            o.addProperty("disabled", u.disabled);
            o.addProperty("mustChange", u.mustChange);
            o.addProperty("createdAt", u.createdAt);
            o.addProperty("lastLoginAt", u.lastLoginAt);
            o.addProperty("passwordChangedAt", u.passwordChangedAt);
            list.add(o);
        }
        root.add("users", list);
        AtomicFiles.write(file, Json.GSON.toJson(root).getBytes(StandardCharsets.UTF_8), true);
    }

    synchronized String ownerName() {
        return ownerName;
    }

    synchronized List<User> list() {
        return List.copyOf(users.values());
    }

    synchronized User byId(String id) {
        return id == null ? null : users.get(id);
    }

    synchronized User byName(String name) {
        return byNameLocked(name);
    }

    /** Names compare ignoring ASCII case only (no Unicode folding: the Kelvin sign is not a k). */
    static boolean sameName(String a, String b) {
        return a != null && b != null && NAME.matcher(a).matches() && NAME.matcher(b).matches()
            && a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }

    private User byNameLocked(String name) {
        if (name == null) return null;
        for (User u : users.values()) if (sameName(u.name, name)) return u;
        return null;
    }

    /** True when a login name means the owner: blank, or the owner's name. */
    synchronized boolean isOwnerName(String name) {
        return name == null || name.isBlank() || sameName(name.trim(), ownerName);
    }

    /** The credential tag a session of this user must carry (see Auth.tag), or null for an unknown user. */
    synchronized String credTag(String userId, String ownerHash) {
        if (OWNER_ID.equals(userId)) return Auth.tag(ownerHash);
        User u = users.get(userId);
        return u == null ? null : Auth.tag(u.hash);
    }

    /**
     * Who a session's user id is now, or null when that user is gone or disabled (the session must end). Read on every
     * request, so permission changes apply at once.
     */
    synchronized Principal principal(String userId) {
        if (OWNER_ID.equals(userId)) return new Principal(OWNER_ID, ownerName, true, Perms.ALL, false);
        User u = byId(userId);
        if (u == null || u.disabled) return null;
        return new Principal(u.id, u.name, false, u.perms, u.mustChange);
    }

    private String checkName(String name, String exceptId) throws UserException {
        if (name == null) throw new UserException(400, "name must be text");
        String n = name.trim();
        if (!NAME.matcher(n).matches()) {
            throw new UserException(400, "names are 1-" + MAX_NAME + " characters: letters, digits, _ . -");
        }
        User other = byNameLocked(n);
        if ((other != null && !other.id.equals(exceptId)) || (!OWNER_ID.equals(exceptId) && sameName(n, ownerName))) {
            throw new UserException(409, "that name is taken");
        }
        return n;
    }

    synchronized User create(String name, String hash, Perms perms, boolean mustChange) throws UserException, IOException {
        if (users.size() >= MAX_USERS) throw new UserException(400, "at most " + MAX_USERS + " users");
        String n = checkName(name, null);
        String id;
        do {
            byte[] b = new byte[6];
            RANDOM.nextBytes(b);
            id = "u-" + HexFormat.of().formatHex(b);
        } while (users.containsKey(id));
        long now = clock.getAsLong();
        User u = new User(id, n, hash, perms, false, mustChange, now, 0, now);
        users.put(id, u);
        saveOrUndo(() -> users.remove(u.id));
        return u;
    }

    /** Changes name, permissions and/or disabled (each null = unchanged). */
    synchronized User update(String id, String name, Perms perms, Boolean disabled) throws UserException, IOException {
        User u = need(id);
        String n = name == null ? u.name : checkName(name, id);
        User next = new User(u.id, n, u.hash, perms == null ? u.perms : perms, disabled == null ? u.disabled : disabled,
            u.mustChange, u.createdAt, u.lastLoginAt, u.passwordChangedAt);
        users.put(id, next);
        saveOrUndo(() -> users.put(id, u));
        return next;
    }

    synchronized void setPassword(String id, String hash, boolean mustChange) throws UserException, IOException {
        User u = need(id);
        users.put(id, new User(u.id, u.name, hash, u.perms, u.disabled, mustChange, u.createdAt, u.lastLoginAt, clock.getAsLong()));
        saveOrUndo(() -> users.put(id, u));
    }

    synchronized void delete(String id) throws UserException, IOException {
        need(id);
        Map<String, User> before = new LinkedHashMap<>(users);
        users.remove(id);
        saveOrUndo(() -> {
            users.clear();
            users.putAll(before);
        });
    }

    synchronized void renameOwner(String name) throws UserException, IOException {
        String n = checkName(name, OWNER_ID);
        String before = ownerName;
        ownerName = n;
        try {
            save();
        } catch (IOException e) {
            ownerName = before;
            throw e;
        }
    }

    /** Notes a successful login (not saved on failure: it's only shown). */
    synchronized void noteLogin(String id) {
        User u = users.get(id);
        if (u == null) return;
        users.put(id, new User(u.id, u.name, u.hash, u.perms, u.disabled, u.mustChange, u.createdAt, clock.getAsLong(), u.passwordChangedAt));
        try {
            save();
        } catch (IOException e) {
            System.err.println("Could not save users.json: " + e.getMessage());
        }
    }

    private User need(String id) throws UserException {
        User u = id == null ? null : users.get(id);
        if (u == null) throw new UserException(404, "no such user");
        return u;
    }

    private void saveOrUndo(Runnable undo) throws IOException {
        try {
            save();
        } catch (IOException e) {
            undo.run();
            throw e;
        }
    }
}

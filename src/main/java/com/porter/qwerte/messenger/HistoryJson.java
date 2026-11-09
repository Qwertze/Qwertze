package com.porter.qwerte.messenger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The saved history: one JSON file with the conversations of every server, written next to itself and moved over the
 * old one so a crash never leaves half a file, size-capped, validated on load. A broken file is moved aside and ignored.
 * Never read or written anywhere but the path it is given, never sent anywhere. No Minecraft classes.
 *
 * <pre>{"version":2,"servers":[{"server":"2b2t.org","conversations":[{"name":"Steve","messages":[{"d":"in","t":"hi","at":1700000000000}]}]}]}</pre>
 *
 * <p>A server may also have an optional {@code "global"} array of {@code {"d":"in","t":...,"at":...}}: its public chat
 * lines. Files without it load as before, and older readers ignore it.
 *
 * <p>Version 1 (no servers, one list of conversations) is still read: its conversations go to the server the player is
 * on when the file is loaded, and the next save writes version 2.
 */
public final class HistoryJson {
    public static final int VERSION = 2;
    /** Neither written nor read above this size. */
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    /** At most this many messages are written in all; the oldest go first. */
    public static final int MAX_MESSAGES = 20_000;

    public enum Status {
        OK, MISSING, CORRUPT, TOO_BIG, UNREADABLE
    }

    public record Loaded(Status status, int conversations) {
    }

    private HistoryJson() {
    }

    // ---------- JSON text ----------

    private static final class Entry {
        final Direction dir;
        final String text;
        final long at;

        Entry(Direction dir, String text, long at) {
            this.dir = dir;
            this.text = text;
            this.at = at;
        }
    }

    private record Conv(String name, List<Entry> entries) {
    }

    private record Server(String key, List<Conv> convs, List<Entry> global) {
    }

    /** A copy of what would be saved, taken on the client thread; it can be turned into JSON and written on any thread. */
    public static final class Snapshot {
        private final List<Server> model;

        private Snapshot(List<Server> model) {
            this.model = model;
        }

        /** The JSON (notes and unsent messages left out), at most {@code maxBytes} long. Does not change the snapshot's source. */
        public String toJson(int maxBytes) {
            // Work on a copy: the trimming below removes entries
            List<Server> copy = new ArrayList<>(model.size());
            for (Server s : model) {
                List<Conv> convs = new ArrayList<>(s.convs().size());
                for (Conv c : s.convs()) convs.add(new Conv(c.name(), new ArrayList<>(c.entries())));
                copy.add(new Server(s.key(), convs, new ArrayList<>(s.global())));
            }
            return HistoryJson.render(copy, maxBytes);
        }
    }

    /** The JSON of every server's store (notes and unsent messages left out), at most {@code maxBytes} long. */
    public static String toJson(ServerStores stores, int maxBytes) {
        return snapshot(stores).toJson(maxBytes);
    }

    /** Copies what would be saved. Call on the thread that owns the stores. */
    public static Snapshot snapshot(ServerStores stores) {
        List<Server> model = new ArrayList<>();
        for (String key : stores.keys()) {
            List<Conv> convs = new ArrayList<>();
            for (Conversation c : stores.store(key).all()) {
                List<Entry> list = new ArrayList<>(c.messages().size());
                for (Message m : c.messages()) {
                    if (m.direction() == Direction.NOTE || m.state() != Message.State.SENT) continue;
                    list.add(new Entry(m.direction(), m.text(), m.time()));
                }
                if (!list.isEmpty()) convs.add(new Conv(c.name(), list));
            }
            // The public chat: only lines that arrived (not the Messenger's own notes)
            List<Entry> pub = new ArrayList<>();
            for (Message m : stores.store(key).global()) {
                if (m.direction() == Direction.IN && m.state() == Message.State.SENT) pub.add(new Entry(Direction.IN, m.text(), m.time()));
            }
            if (!convs.isEmpty() || !pub.isEmpty()) model.add(new Server(key, convs, pub));
        }
        return new Snapshot(model);
    }

    private static String render(List<Server> model, int maxBytes) {
        // By count first, then by size
        int total = count(model);
        if (total > MAX_MESSAGES) dropOldest(model, total - MAX_MESSAGES);
        String json = build(model);
        while (json.getBytes(StandardCharsets.UTF_8).length > maxBytes && count(model) > 0) {
            dropOldest(model, Math.max(1, count(model) / 4));
            json = build(model);
        }
        return json;
    }

    private static int count(List<Server> model) {
        int n = 0;
        for (Server s : model) {
            n += s.global().size();
            for (Conv c : s.convs()) n += c.entries().size();
        }
        return n;
    }

    private static int countGlobal(List<Server> model) {
        int n = 0;
        for (Server s : model) n += s.global().size();
        return n;
    }

    /**
     * Removes the {@code n} oldest messages (and a few more with the same time), and what became empty. The public chat
     * goes first, so a flood of chat lines never pushes the whispers out of the file.
     */
    private static void dropOldest(List<Server> model, int n) {
        int fromGlobal = Math.min(n, countGlobal(model));
        if (fromGlobal > 0) dropOldest(model, fromGlobal, true);
        if (n - fromGlobal > 0 && count(model) > 0) dropOldest(model, n - fromGlobal, false);
    }

    private static void dropOldest(List<Server> model, int n, boolean global) {
        int total = global ? countGlobal(model) : count(model) - countGlobal(model);
        if (total <= 0) return;
        long[] times = new long[total];
        int i = 0;
        for (Server s : model) {
            if (global) {
                for (Entry e : s.global()) times[i++] = e.at;
            } else {
                for (Conv c : s.convs()) {
                    for (Entry e : c.entries()) times[i++] = e.at;
                }
            }
        }
        Arrays.sort(times);
        long cutoff = times[Math.min(times.length, n) - 1];
        for (Server s : model) {
            if (global) {
                s.global().removeIf(e -> e.at <= cutoff);
            } else {
                for (Conv c : s.convs()) c.entries().removeIf(e -> e.at <= cutoff);
                s.convs().removeIf(c -> c.entries().isEmpty());
            }
        }
        model.removeIf(s -> s.convs().isEmpty() && s.global().isEmpty());
    }

    private static String build(List<Server> model) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray servers = new JsonArray();
        for (Server s : model) {
            JsonArray convs = new JsonArray();
            for (Conv c : s.convs()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", c.name());
                o.add("messages", entries(c.entries()));
                convs.add(o);
            }
            JsonObject so = new JsonObject();
            so.addProperty("server", s.key());
            so.add("conversations", convs);
            if (!s.global().isEmpty()) so.add("global", entries(s.global()));
            servers.add(so);
        }
        root.add("servers", servers);
        return root.toString();
    }

    private static JsonArray entries(List<Entry> list) {
        JsonArray msgs = new JsonArray();
        for (Entry e : list) {
            JsonObject m = new JsonObject();
            m.addProperty("d", e.dir == Direction.IN ? "in" : "out");
            m.addProperty("t", e.text);
            m.addProperty("at", e.at);
            msgs.add(m);
        }
        return msgs;
    }

    /**
     * Reads JSON text into the stores (which should be empty). Anything malformed is skipped: bad names, bad directions,
     * bad times, wrong types, bad server names. A version 1 file goes to {@code legacyServer}. Returns the number of
     * conversations loaded, or -1 if the text is not a history at all.
     */
    public static int fromJson(String json, ServerStores into, String legacyServer) {
        JsonObject root;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) return -1;
            root = e.getAsJsonObject();
        } catch (RuntimeException | StackOverflowError e) {
            return -1;
        }
        Long version = number(root.get("version"));
        if (version == null) return -1;
        int loaded = 0;
        if (version == 1) {
            JsonElement convs = root.get("conversations");
            if (convs == null || !convs.isJsonArray()) return -1;
            loaded = loadConversations(convs.getAsJsonArray(), into.store(ServerStores.normalize(legacyServer)));
        } else if (version == VERSION) {
            JsonElement servers = root.get("servers");
            if (servers == null || !servers.isJsonArray()) return -1;
            int seen = 0;
            for (JsonElement se : servers.getAsJsonArray()) {
                if (seen++ >= ServerStores.MAX_SERVERS) break;
                if (!se.isJsonObject()) continue;
                JsonObject so = se.getAsJsonObject();
                String key = string(so.get("server"));
                JsonElement convs = so.get("conversations");
                if (!ServerStores.validKey(key) || convs == null || !convs.isJsonArray()) continue;
                ConversationStore store = into.store(key);
                loaded += loadConversations(convs.getAsJsonArray(), store);
                loadGlobal(so.get("global"), store);
            }
        } else {
            return -1;
        }
        into.clean();
        return loaded;
    }

    /** The optional public chat of a server: only lines that arrived ("in"), cleaned, in time order; the store cuts it to its cap. */
    private static void loadGlobal(JsonElement element, ConversationStore into) {
        if (element == null || !element.isJsonArray()) return;
        List<Entry> list = new ArrayList<>();
        for (JsonElement me : element.getAsJsonArray()) {
            if (!me.isJsonObject()) continue;
            JsonObject mo = me.getAsJsonObject();
            Long at = number(mo.get("at"));
            String text = string(mo.get("t"));
            if (!"in".equals(string(mo.get("d"))) || text == null || at == null || at < 0) continue;
            String clean = ChatText.message(text);
            if (!clean.isEmpty()) list.add(new Entry(Direction.IN, clean, at));
        }
        list.sort(Comparator.comparingLong(e -> e.at));
        for (Entry en : list) into.addGlobalLoaded(en.text, en.at);
    }

    private static int loadConversations(JsonArray convs, ConversationStore into) {
        int loaded = 0;
        for (JsonElement ce : convs) {
            if (loaded >= ConversationStore.MAX_CONVERSATIONS) break;
            if (!ce.isJsonObject()) continue;
            JsonObject co = ce.getAsJsonObject();
            String name = string(co.get("name"));
            if (!ChatText.isValidName(name)) continue;
            JsonElement msgs = co.get("messages");
            if (msgs == null || !msgs.isJsonArray()) continue;
            List<Entry> list = new ArrayList<>();
            for (JsonElement me : msgs.getAsJsonArray()) {
                if (!me.isJsonObject()) continue;
                JsonObject mo = me.getAsJsonObject();
                String d = string(mo.get("d"));
                Direction dir = "in".equals(d) ? Direction.IN : "out".equals(d) ? Direction.OUT : null;
                String text = string(mo.get("t"));
                Long at = number(mo.get("at"));
                if (dir == null || text == null || at == null || at < 0) continue;
                String clean = ChatText.message(text);
                if (clean.isEmpty()) continue;
                list.add(new Entry(dir, clean, at));
            }
            if (list.isEmpty()) continue;
            list.sort(Comparator.comparingLong(e -> e.at));
            for (Entry en : list) into.addLoaded(name, en.dir, en.text, en.at);
            loaded++;
        }
        return loaded;
    }

    private static String string(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        return p.isString() ? p.getAsString() : null;
    }

    private static Long number(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) return null;
        double d = p.getAsDouble();
        if (d != Math.rint(d) || Math.abs(d) > 1e15) return null;
        return p.getAsLong();
    }

    // ---------- the file ----------

    /**
     * Loads the file and merges it into the stores. A corrupt file is moved aside to {@code <name>.corrupt} and ignored.
     * TOO_BIG and UNREADABLE leave the file alone; the caller should then not write it (it would be lost).
     */
    public static Loaded load(Path file, ServerStores into, String legacyServer) {
        try {
            if (!Files.isRegularFile(file)) return new Loaded(Status.MISSING, 0);
            if (Files.size(file) > MAX_BYTES) return new Loaded(Status.TOO_BIG, 0);
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            ServerStores scratch = new ServerStores(into.cap());
            scratch.globalCap(into.globalCap());
            int n = fromJson(json, scratch, legacyServer);
            if (n < 0) {
                moveAside(file);
                return new Loaded(Status.CORRUPT, 0);
            }
            boolean wasDirty = into.anyDirty();
            for (String key : scratch.keys()) {
                ConversationStore s = scratch.store(key);
                if (!s.isEmpty() || !s.global().isEmpty()) into.store(key).mergeFrom(s);
            }
            if (!wasDirty) into.clean();
            return new Loaded(Status.OK, n);
        } catch (IOException | RuntimeException e) {
            return new Loaded(Status.UNREADABLE, 0);
        }
    }

    /** Writes the stores atomically. Returns false if it could not be written. */
    public static boolean save(Path file, ServerStores stores) {
        return write(file, snapshot(stores));
    }

    /**
     * Writes a snapshot atomically (any thread). The temporary file is made new in the same folder under a random name,
     * which never follows a link, and is readable by its owner only where the file system has such permissions; it is
     * moved over the old file, so a link in the old file's place is replaced, not written through.
     */
    public static boolean write(Path file, Snapshot snapshot) {
        Path tmp = null;
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            byte[] bytes = snapshot.toJson(MAX_BYTES).getBytes(StandardCharsets.UTF_8);
            tmp = Files.createTempFile(dir, file.getFileName() + ".", ".tmp"); // owner-only (rw-------) on POSIX
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp); // only there if the move failed
                } catch (IOException ignored) {
                    // nothing more to do
                }
            }
        }
    }

    /** Deletes the file (and a leftover temporary one). */
    public static boolean delete(Path file) {
        try {
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
            Files.deleteIfExists(file);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void moveAside(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // left where it is; the next save replaces it
        }
    }
}

package com.porter.qwerte.messenger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The conversations of one server, in memory: at most {@code cap} messages per player and {@link #MAX_CONVERSATIONS}
 * players. Single-threaded (the client thread). Pure: no Minecraft classes.
 */
public final class ConversationStore {
    public static final int MAX_CONVERSATIONS = 100;
    public static final int MIN_CAP = 10;
    public static final int MAX_CAP = 500;
    public static final int MIN_GLOBAL_CAP = 50;
    public static final int MAX_GLOBAL_CAP = 2000;
    public static final int DEFAULT_GLOBAL_CAP = 500;

    private final Map<String, Conversation> byKey = new HashMap<>();
    /** The public chat of this server, oldest first: lines from the chat feed (IN) and notes from the Messenger. */
    private final List<Message> global = new ArrayList<>();
    /** The Console of this server (with separate-console on): console lines that arrived, and notes. Oldest first. */
    private final List<Message> console = new ArrayList<>();
    private int consoleUnread;
    /** The Console is on (separate-console). Off: it has no unread count and adds nothing to the total. */
    private boolean consoleVisible = true;
    private int globalCap = DEFAULT_GLOBAL_CAP;
    private int cap;
    private boolean dirty;
    private boolean globalDirty;
    private int version;

    public ConversationStore(int cap) {
        this.cap = clampCap(cap);
    }

    public static int clampCap(int cap) {
        return Math.max(MIN_CAP, Math.min(MAX_CAP, cap));
    }

    public int cap() {
        return cap;
    }

    public static int clampGlobalCap(int cap) {
        return Math.max(MIN_GLOBAL_CAP, Math.min(MAX_GLOBAL_CAP, cap));
    }

    public int globalCap() {
        return globalCap;
    }

    /** Changes how many public chat lines are kept; a longer log is cut at once (the oldest go). */
    public void globalCap(int cap) {
        this.globalCap = clampGlobalCap(cap);
        trimGlobal();
    }

    /** The public chat log, oldest first. Do not change from outside; use the store. */
    public List<Message> global() {
        return global;
    }

    /** A line that arrived in the chat feed (kept as plain text). */
    public Message addGlobal(String text, long now) {
        Message m = new Message(Direction.IN, text, now, Message.State.SENT);
        global.add(m);
        trimGlobal();
        globalDirty = true;
        return m;
    }

    /** The Console log, oldest first. Do not change from outside; use the store. */
    public List<Message> console() {
        return console;
    }

    /** A console line that arrived. {@code reading}: the Console is open in front of the user, so it counts as read. */
    public Message addConsole(String text, long now, boolean reading) {
        Message m = new Message(Direction.IN, text, now, Message.State.SENT);
        m.console(true);
        console.add(m);
        trimConsole();
        globalDirty = true;
        if (!reading && consoleVisible) consoleUnread++;
        return m;
    }

    /** A line from the Messenger itself in the Console (not saved, not unread). */
    public Message addConsoleNote(String text, long now) {
        Message m = new Message(Direction.NOTE, text, now, Message.State.SENT);
        console.add(m);
        trimConsole();
        return m;
    }

    /** Loading from a file: adds a console line without marking anything to save. */
    Message addConsoleLoaded(String text, long time) {
        Message m = new Message(Direction.IN, text, time, Message.State.SENT);
        m.console(true);
        console.add(m);
        trimConsole();
        return m;
    }

    public int consoleUnread() {
        return consoleVisible ? consoleUnread : 0;
    }

    /** Whether the Console is shown. Turning it off forgets its unread count, so no stale "(5)" is left in the title. */
    public void consoleVisible(boolean visible) {
        consoleVisible = visible;
        if (!visible) consoleUnread = 0;
    }

    public void markConsoleRead() {
        consoleUnread = 0;
    }

    /** Forgets the Console log. */
    public void clearConsole() {
        if (console.stream().anyMatch(m -> m.direction() != Direction.NOTE)) globalDirty = true;
        console.clear();
        consoleUnread = 0;
    }

    private void trimConsole() {
        int over = console.size() - globalCap;
        if (over > 0) console.subList(0, over).clear();
    }

    /** A line from the Messenger itself in the public log (not saved). */
    public Message addGlobalNote(String text, long now) {
        Message m = new Message(Direction.NOTE, text, now, Message.State.SENT);
        global.add(m);
        trimGlobal();
        return m;
    }

    /** Loading from a file: adds a line without marking anything to save. */
    Message addGlobalLoaded(String text, long time) {
        Message m = new Message(Direction.IN, text, time, Message.State.SENT);
        global.add(m);
        trimGlobal();
        return m;
    }

    /** Forgets the public chat log. */
    public void clearGlobal() {
        if (global.stream().anyMatch(m -> m.direction() != Direction.NOTE)) globalDirty = true;
        global.clear();
    }

    private void trimGlobal() {
        int over = global.size() - globalCap;
        if (over > 0) global.subList(0, over).clear();
        trimConsole();
    }

    /** Changes the per-player cap; longer histories are cut at once. */
    public void cap(int cap) {
        this.cap = clampCap(cap);
        for (Conversation c : byKey.values()) trim(c);
        version++;
    }

    /** Counts changes to the set of conversations or their order, so a sorted list can be kept until it changes. */
    public int version() {
        return version;
    }

    /** Whispers (or a message state) changed and should be saved soon. The public chat alone does not set this. */
    public boolean dirty() {
        return dirty;
    }

    /** The public chat changed; it is saved on a long interval, or when leaving. */
    public boolean globalDirty() {
        return globalDirty;
    }

    public void clean() {
        dirty = false;
        globalDirty = false;
    }

    /** Something about an existing message changed (its state). */
    public void touch() {
        dirty = true;
    }

    public Conversation get(String name) {
        return name == null ? null : byKey.get(Conversation.key(name));
    }

    /** The conversation with that player, made if there is none. Names must be valid ({@link ChatText#isValidName}). */
    public Conversation open(String name, long now) {
        if (!ChatText.isValidName(name)) throw new IllegalArgumentException("not a player name");
        Conversation c = byKey.get(Conversation.key(name));
        if (c == null) {
            if (byKey.size() >= MAX_CONVERSATIONS) evictOldest();
            c = new Conversation(name);
            c.lastActivity(now);
            byKey.put(c.key(), c);
            version++;
        }
        return c;
    }

    /**
     * Adds a received message. {@code reading}: the conversation is open in front of the user, so it counts as read.
     */
    public Message addIncoming(String name, String text, long now, boolean reading) {
        Conversation c = open(name, now);
        c.rename(name); // the capitalisation the server used last
        Message m = new Message(Direction.IN, text, now, Message.State.SENT);
        add(c, m, now);
        if (!reading) c.unread(c.unread() + 1);
        return m;
    }

    public Message addOutgoing(String name, String text, long now, Message.State state) {
        Conversation c = open(name, now);
        Message m = new Message(Direction.OUT, text, now, state);
        add(c, m, now);
        return m;
    }

    /** A line from the Messenger itself (not saved). */
    public Message addNote(String name, String text, long now) {
        Conversation c = open(name, now);
        Message m = new Message(Direction.NOTE, text, now, Message.State.SENT);
        c.messages().add(m);
        trim(c);
        return m;
    }

    /** Loading from a file: adds a message in order without touching unread counts. */
    Message addLoaded(String name, Direction dir, String text, long time) {
        Conversation c = open(name, time);
        Message m = new Message(dir, text, time, Message.State.SENT);
        c.messages().add(m);
        c.lastActivity(Math.max(c.lastActivity(), time));
        trim(c);
        version++;
        return m;
    }

    /** Adds the other store's messages that are not here yet (same direction, time and text), keeping each list in time order. */
    public void mergeFrom(ConversationStore other) {
        for (Conversation oc : other.all()) {
            Conversation c = open(oc.name(), oc.lastActivity());
            Set<String> seen = new HashSet<>();
            for (Message m : c.messages()) seen.add(signature(m));
            boolean added = false;
            for (Message m : oc.messages()) {
                if (!seen.add(signature(m))) continue;
                c.messages().add(new Message(m.direction(), m.text(), m.time(), Message.State.SENT));
                added = true;
            }
            c.lastActivity(Math.max(c.lastActivity(), oc.lastActivity()));
            if (added) {
                c.messages().sort(Comparator.comparingLong(Message::time));
                trim(c);
            }
        }
        Set<String> seenGlobal = new HashSet<>();
        for (Message m : global) seenGlobal.add(signature(m));
        boolean addedGlobal = false;
        for (Message m : other.global) {
            if (m.direction() != Direction.IN || !seenGlobal.add(signature(m))) continue;
            Message copy = new Message(Direction.IN, m.text(), m.time(), Message.State.SENT);
            copy.console(m.console());
            global.add(copy);
            addedGlobal = true;
        }
        if (addedGlobal) {
            global.sort(Comparator.comparingLong(Message::time));
            trimGlobal();
        }
        Set<String> seenConsole = new HashSet<>();
        for (Message m : console) seenConsole.add(signature(m));
        boolean addedConsole = false;
        for (Message m : other.console) {
            if (m.direction() != Direction.IN || !seenConsole.add(signature(m))) continue;
            Message copy = new Message(Direction.IN, m.text(), m.time(), Message.State.SENT);
            copy.console(true);
            console.add(copy);
            addedConsole = true;
        }
        if (addedConsole) {
            console.sort(Comparator.comparingLong(Message::time));
            trimConsole();
        }
        version++;
    }

    private static String signature(Message m) {
        return m.direction() + "|" + m.time() + "|" + m.text();
    }

    private void add(Conversation c, Message m, long now) {
        c.messages().add(m);
        c.lastActivity(now);
        trim(c);
        dirty = true;
        version++;
    }

    private void trim(Conversation c) {
        List<Message> list = c.messages();
        int over = list.size() - cap;
        if (over > 0) list.subList(0, over).clear();
    }

    public void markRead(String name) {
        Conversation c = get(name);
        if (c != null) c.unread(0);
    }

    public int totalUnread() {
        int n = 0;
        for (Conversation c : byKey.values()) n += c.unread();
        return n + (consoleVisible ? consoleUnread : 0);
    }

    /**
     * The unread total the title shows: only what the badge settings leave on. Global has no unread count, so its flag
     * changes nothing today. The counts themselves are not touched, so a badge turned back on shows the right number.
     */
    public int visibleUnread(boolean global, boolean console, boolean dms) {
        int n = 0;
        if (dms) {
            for (Conversation c : byKey.values()) n += c.unread();
        }
        if (console && consoleVisible) n += consoleUnread;
        return n;
    }

    /**
     * The player whose conversation has the unread message that arrived most recently, or null when nothing is unread.
     * Ties go to the name that sorts first.
     */
    public String newestUnread() {
        Conversation best = null;
        long bestTime = Long.MIN_VALUE;
        for (Conversation c : byKey.values()) {
            if (c.unread() <= 0) continue;
            long t = c.lastActivity();
            for (int i = c.messages().size() - 1; i >= 0; i--) {
                Message m = c.messages().get(i);
                if (m.direction() == Direction.IN) {
                    t = m.time();
                    break;
                }
            }
            if (best == null || t > bestTime || (t == bestTime && c.key().compareTo(best.key()) < 0)) {
                best = c;
                bestTime = t;
            }
        }
        return best == null ? null : best.name();
    }

    /** Forgets one conversation. */
    public void remove(String name) {
        if (name != null && byKey.remove(Conversation.key(name)) != null) {
            dirty = true;
            version++;
        }
    }

    public void clear() {
        if (!byKey.isEmpty() || !global.isEmpty() || !console.isEmpty()) { dirty = true; globalDirty = true; }
        byKey.clear();
        global.clear();
        console.clear();
        consoleUnread = 0;
        version++;
    }

    public boolean isEmpty() {
        return byKey.isEmpty();
    }

    public int size() {
        return byKey.size();
    }

    /** Friends first, then the others; each group by most recent activity, then by name. */
    public List<Conversation> ordered(Predicate<String> isFriend) {
        List<Conversation> list = new ArrayList<>(byKey.values());
        Map<String, Boolean> friend = new HashMap<>();
        for (Conversation c : list) friend.put(c.key(), isFriend.test(c.name()));
        list.sort(Comparator
            .comparing((Conversation c) -> !friend.get(c.key()))
            .thenComparing((Conversation c) -> -c.lastActivity())
            .thenComparing(Conversation::key));
        return list;
    }

    /** All conversations, unordered. */
    public List<Conversation> all() {
        return new ArrayList<>(byKey.values());
    }

    /** Drops the conversation that was active longest ago. */
    private void evictOldest() {
        Conversation oldest = null;
        for (Conversation c : byKey.values()) {
            if (oldest == null || c.lastActivity() < oldest.lastActivity()) oldest = c;
        }
        if (oldest != null) byKey.remove(oldest.key());
    }
}

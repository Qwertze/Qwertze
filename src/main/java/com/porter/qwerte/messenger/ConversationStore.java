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

    private final Map<String, Conversation> byKey = new HashMap<>();
    private int cap;
    private boolean dirty;
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

    /** True once something that should be saved changed; {@link #clean()} resets it. */
    public boolean dirty() {
        return dirty;
    }

    public void clean() {
        dirty = false;
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
        return n;
    }

    /** Forgets one conversation. */
    public void remove(String name) {
        if (name != null && byKey.remove(Conversation.key(name)) != null) {
            dirty = true;
            version++;
        }
    }

    public void clear() {
        if (!byKey.isEmpty()) dirty = true;
        byKey.clear();
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

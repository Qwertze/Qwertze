package com.porter.qwerte.messenger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The messages with one player. Names are compared without case. */
public final class Conversation {
    private String name;
    private final String key;
    private final List<Message> messages = new ArrayList<>();
    private int unread;
    private long lastActivity;

    Conversation(String name) {
        this.name = name;
        this.key = key(name);
    }

    /** The map key of a player name. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public String name() {
        return name;
    }

    void rename(String name) {
        this.name = name;
    }

    public String key() {
        return key;
    }

    /** Oldest first. Do not change from outside; use the store. */
    public List<Message> messages() {
        return messages;
    }

    public int unread() {
        return unread;
    }

    void unread(int unread) {
        this.unread = Math.max(0, unread);
    }

    public long lastActivity() {
        return lastActivity;
    }

    void lastActivity(long t) {
        this.lastActivity = t;
    }
}

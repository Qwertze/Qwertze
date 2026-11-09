package com.porter.qwerte.messenger;

import java.util.List;
import java.util.function.Predicate;

/**
 * The logic of the Messenger without the game: what happens to a parsed whisper, and how a typed message is checked,
 * queued, rate limited and recorded. The module feeds it lines and ticks. Single-threaded (the client thread).
 * Conversations are kept per server ({@link ServerStores}); only the current server's are in play.
 * No Minecraft classes.
 */
public final class MessengerCore {
    /** Echo copies are looked for this long after a message was sent. */
    static final long ECHO_WINDOW_MS = 30_000;

    public static final String STRIPPED_NOTE = "This server seems to remove emoji from messages: the other player may not see them.";

    private record Pending(String command, Message message) {
    }

    /** What a received line did: the conversation, the new message (null for a copy of one we sent), whether it is new from them. */
    public record Received(Conversation conversation, Message message, boolean fromOther) {
    }

    private final ServerStores stores;
    private final Outbox<Pending> outbox = new Outbox<>();
    private String template = SendText.DEFAULT_TEMPLATE;
    private int intervalMs = 1500;
    private boolean strippedNoteShown;

    public MessengerCore(ServerStores stores) {
        this.stores = stores;
    }

    /** Conversations of the server the player is on. */
    public ConversationStore store() {
        return stores.current();
    }

    public ServerStores stores() {
        return stores;
    }

    public void template(String template) {
        this.template = template;
    }

    public void intervalMs(int ms) {
        this.intervalMs = Outbox.clampInterval(ms);
    }

    public int queued() {
        return outbox.size();
    }

    /** The player joined a server: its conversations are shown from now on. */
    public void switchServer(String address) {
        cancelQueued();
        stores.switchTo(address);
    }

    /** The player left the server. */
    public void leave() {
        cancelQueued();
        strippedNoteShown = false;
    }

    /**
     * A whisper found in chat.
     *
     * @param self    the player's own name; whispers to or from oneself are ignored
     * @param reading the conversation is open in front of the user (counts as read)
     */
    public Received receive(DmParser.Parsed p, String self, long now, boolean reading) {
        if (p == null || self != null && p.name().equalsIgnoreCase(self)) return null;
        ConversationStore store = store();
        if (p.direction() == Direction.IN) {
            Message m = store.addIncoming(p.name(), p.text(), now, reading);
            return new Received(store.get(p.name()), m, true);
        }
        // Our own message coming back: the copy of one sent from the window, or one typed in chat
        Conversation c = store.get(p.name());
        if (c != null) {
            Message hit = findCopy(c, p.text(), now);
            if (hit != null) {
                hit.echoed(true);
                if (EmojiEcho.stripped(hit.text(), p.text()) && !strippedNoteShown) {
                    strippedNoteShown = true;
                    store.addNote(c.name(), STRIPPED_NOTE, now);
                }
                return new Received(c, null, false);
            }
        }
        Message m = store.addOutgoing(p.name(), p.text(), now, Message.State.SENT);
        m.echoed(true);
        return new Received(store.get(p.name()), m, false);
    }

    /** The sent message this line is the server's copy of, or null. */
    private static Message findCopy(Conversation c, String echoed, long now) {
        // The newest message with the same text first
        for (int i = c.messages().size() - 1; i >= 0; i--) {
            Message m = c.messages().get(i);
            if (!candidate(m, now)) continue;
            if (EmojiEcho.matches(m.text(), echoed)) return m;
        }
        // A server may have turned a message made of symbols into something else: take the oldest waiting one of those
        for (Message m : c.messages()) {
            if (candidate(m, now) && EmojiEcho.symbolsOnly(m.text())) return m;
        }
        return null;
    }

    private static boolean candidate(Message m, long now) {
        return m.direction() == Direction.OUT && !m.echoed() && m.state() == Message.State.SENT
            && m.sentAt() >= 0 && now - m.sentAt() <= ECHO_WINDOW_MS && now >= m.sentAt();
    }

    /**
     * Checks and queues what the user typed. Nothing is sent here; {@link #tick} sends, one message per interval.
     *
     * @return null when queued, else why not (shown to the user)
     */
    public String send(String name, String raw, long now) {
        SendText.Result r = SendText.prepare(template, name, raw);
        if (!r.ok()) return r.error();
        if (outbox.size() >= Outbox.MAX_QUEUE) return "Too many messages waiting, give it a moment.";
        Message m = store().addOutgoing(name, r.text(), now, Message.State.SENDING);
        outbox.offer(new Pending(r.command(), m));
        return null;
    }

    /**
     * Sends the next waiting message if the interval has passed. {@code sender} gets the command (no slash) and returns
     * false when it could not send (not connected).
     */
    public void tick(long now, Predicate<String> sender) {
        Pending p = outbox.next(now, intervalMs);
        if (p == null) return;
        boolean ok = sender.test(p.command());
        p.message().state(ok ? Message.State.SENT : Message.State.FAILED);
        if (ok) p.message().sentAt(now);
        stores.touch();
    }

    /** Drops what is waiting (leaving the server, module off); those messages show as not sent. */
    public void cancelQueued() {
        List<Pending> dropped = outbox.clear();
        for (Pending p : dropped) p.message().state(Message.State.FAILED);
        if (!dropped.isEmpty()) stores.touch();
    }
}

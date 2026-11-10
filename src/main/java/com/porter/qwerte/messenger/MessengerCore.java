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

    /** How a waiting item is sent: a whisper command, a public chat message, or a command typed in Global. */
    enum Kind {
        WHISPER, CHAT, COMMAND
    }

    /**
     * @param payload what is handed to the game (a command without its slash, or the chat text)
     * @param message the message whose state follows the send (for Global it is not in any list)
     * @param label   what the public log may say about it if it was not sent
     */
    private record Pending(Kind kind, String payload, Message message, String label, boolean meteor) {
    }

    /** The game side of sending. Each returns false when it could not send (not connected). */
    public interface Sender {
        /** Sends a command; {@code command} has no leading slash. */
        boolean command(String command);

        /** Sends a public chat message. */
        boolean chat(String text);
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
        outbox.offer(new Pending(Kind.WHISPER, r.command(), m, null, false));
        return null;
    }

    /** A line that arrived in the chat feed, for the Global log. Lines that are empty after cleaning are ignored. */
    public void receiveGlobal(String line, long now) {
        receiveGlobal(line, now, null);
    }

    /**
     * @param console whether the line is a console line (see {@link LineKind}), or null if unknown
     * @return true if the line was added; false if it was empty or a repeat of the line just before
     */
    public boolean receiveGlobal(String line, long now, Boolean console) {
        String text = ChatText.message(line);
        if (text.isEmpty()) return false;
        // The same line delivered twice at once (a mod that re-adds a line it changed): show it once. The comparison
        // ignores a timestamp or icon in front, so the two copies need not be identical.
        String key = ChatText.stripClientPrefix(text).strip();
        key = WHITESPACE.matcher(key).replaceAll(" ");
        if (key.equals(lastGlobalKey) && now - lastGlobalAt >= 0 && now - lastGlobalAt <= DUPLICATE_WINDOW_MS) return false;
        lastGlobalKey = key;
        lastGlobalAt = now;
        store().addGlobal(text, now).console(console);
        return true;
    }

    /** Two public lines this close with the same text are one line delivered twice. */
    static final long DUPLICATE_WINDOW_MS = 300;
    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");
    private String lastGlobalKey;
    private long lastGlobalAt;

    /**
     * Checks and queues what the user typed in Global. Nothing is sent here; {@link #tick} sends it, in the same queue
     * and at the same rate as whispers. The text is handed to the game's normal chat send, as vanilla chat does (see
     * {@link GlobalSend}); Meteor's own send hook then applies to chat text.
     *
     * @return null when queued, else why not (shown to the user)
     */
    public String sendGlobal(String raw, long now) {
        return sendGlobal(raw, now, null);
    }

    /**
     * @param meteorPrefix Meteor's command prefix ("." by default), or null/empty: a chat text starting with it is run
     *                     by Meteor as a command and never reaches the server, so the log says "Ran Meteor command"
     */
    public String sendGlobal(String raw, long now, String meteorPrefix) {
        GlobalSend.Result r = GlobalSend.prepare(raw);
        if (!r.ok()) return r.error();
        if (outbox.size() >= Outbox.MAX_QUEUE) return "Too many messages waiting, give it a moment.";
        boolean command = r.kind() == GlobalSend.Kind.COMMAND;
        boolean meteor = !command && meteorPrefix != null && !meteorPrefix.isEmpty() && r.payload().startsWith(meteorPrefix);
        String label = meteor ? "Meteor command" : r.label(); // its arguments are never shown
        // Not in any list: the server's own copy of a chat message is what shows in the log
        Message m = new Message(Direction.OUT, label, now, Message.State.SENDING);
        outbox.offer(new Pending(command ? Kind.COMMAND : Kind.CHAT, r.payload(), m, label, meteor));
        return null;
    }

    /** Whether something typed in Global is waiting to be sent. */
    public boolean globalQueued() {
        return outbox.anyMatch(p -> p.kind() != Kind.WHISPER);
    }

    /**
     * Sends the next waiting message if the interval has passed. {@code sender} gets the command (no slash) and returns
     * false when it could not send (not connected). Only whispers are sent through this form.
     */
    public void tick(long now, Predicate<String> sender) {
        tick(now, new Sender() {
            @Override
            public boolean command(String command) {
                return sender.test(command);
            }

            @Override
            public boolean chat(String text) {
                return false;
            }
        });
    }

    /** Sends the next waiting message if the interval has passed: at most one per interval, whatever it is. */
    public void tick(long now, Sender sender) {
        Pending p = outbox.next(now, intervalMs);
        if (p == null) return;
        boolean ok = p.kind() == Kind.CHAT ? sender.chat(p.payload()) : sender.command(p.payload());
        p.message().state(ok ? Message.State.SENT : Message.State.FAILED);
        if (ok) p.message().sentAt(now);
        if (p.meteor()) store().addGlobalNote(ok ? "Ran Meteor command" : "Not sent: " + p.label(), now);
        else if (p.kind() == Kind.COMMAND) store().addGlobalNote(ok ? "Sent " + p.label() : "Not sent: " + p.label(), now);
        else if (p.kind() == Kind.CHAT && !ok) store().addGlobalNote("Not sent: " + p.label(), now);
        if (p.kind() == Kind.WHISPER) stores.touch(); // only whispers are worth a save
    }

    /** Drops what is waiting (leaving the server, module off); those messages show as not sent. */
    public void cancelQueued() {
        List<Pending> dropped = outbox.clear();
        long now = System.currentTimeMillis();
        for (Pending p : dropped) {
            p.message().state(Message.State.FAILED);
            if (p.kind() != Kind.WHISPER) store().addGlobalNote("Not sent: " + p.label(), now);
        }
        if (!dropped.isEmpty()) stores.touch();
    }
}

package com.porter.qwerte.messenger;

/** One line of a conversation. Only {@link #state} and {@link #echoed} change after it is made. */
public final class Message {
    /** SENDING: queued, not sent yet. SENT: handed to the server. FAILED: not sent. Received lines and notes are SENT. */
    public enum State {
        SENDING, SENT, FAILED
    }

    private final Direction direction;
    private final String text;
    private final long time;
    private State state;
    private long sentAt;
    /** An outgoing message whose copy from the server (the "to X: ..." line) was seen. Not saved. */
    private boolean echoed;
    /** Public chat only: true for a console line (system, death, join, notice), false for player chat, null when not known (loaded from a file). Not saved. */
    private Boolean console;

    public Message(Direction direction, String text, long time, State state) {
        this.direction = direction;
        this.text = text;
        this.time = time;
        this.state = state;
        this.sentAt = state == State.SENT ? time : -1;
    }

    /** When the message was handed to the server, or -1 if it was not. */
    public long sentAt() {
        return sentAt;
    }

    public void sentAt(long t) {
        this.sentAt = t;
    }

    public Direction direction() {
        return direction;
    }

    public String text() {
        return text;
    }

    public long time() {
        return time;
    }

    public State state() {
        return state;
    }

    public void state(State state) {
        this.state = state;
    }

    public boolean echoed() {
        return echoed;
    }

    public void echoed(boolean echoed) {
        this.echoed = echoed;
    }

    /** Whether this is a console line, or null when unknown. */
    public Boolean console() {
        return console;
    }

    public void console(Boolean console) {
        this.console = console;
    }
}

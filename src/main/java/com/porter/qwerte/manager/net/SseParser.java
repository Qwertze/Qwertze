package com.porter.qwerte.manager.net;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a Server-Sent Events stream (the HTML living standard's "event stream interpretation") one line or one chunk of
 * text at a time. Pure: no threads, no network. A line ends with LF, CR or CRLF, also when the CR and LF come in
 * different chunks. Comments (lines starting with a colon) are ignored; {@code retry} and {@code id} are remembered.
 * A line or an event larger than {@link #MAX} marks the parser {@link #overflowed()}: the caller should drop the
 * connection.
 */
public final class SseParser {
    /** One dispatched event. {@code name} is "message" when the stream gave none. */
    public record Event(String name, String data, String id) {
    }

    /** The most characters kept for one line or one event's data (a full snapshot of 1,024 tiles is about 1.1 MB). */
    public static final int MAX = 5 * 1024 * 1024;

    private final StringBuilder line = new StringBuilder();
    private final StringBuilder data = new StringBuilder();
    private boolean skipLf;
    private boolean started;
    private boolean overflow;
    private String name = "";
    private String lastId = "";
    private long retryMs = -1;

    /** Text as it arrived (any size, anywhere in a line). Returns the events it completed. */
    public List<Event> feedChunk(CharSequence chunk) {
        List<Event> out = new ArrayList<>(2);
        if (overflow) return out; // refused for good: the caller drops the connection
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (c == '\n') {
                if (skipLf) {
                    skipLf = false;
                    continue;
                }
                endLine(out);
            } else if (c == '\r') {
                skipLf = true;
                endLine(out);
            } else {
                skipLf = false;
                if (line.length() >= MAX) {
                    overflow = true;
                    line.setLength(0);
                }
                line.append(c);
            }
        }
        return out;
    }

    /** One complete line without its terminator. */
    public List<Event> feedLine(String text) {
        List<Event> out = new ArrayList<>(1);
        if (overflow) return out;
        handle(text, out);
        return out;
    }

    private void endLine(List<Event> out) {
        String text = line.toString();
        line.setLength(0);
        handle(text, out);
    }

    private void handle(String text, List<Event> out) {
        if (!started) {
            started = true;
            if (text.startsWith("﻿")) text = text.substring(1);
        }
        if (text.isEmpty()) {
            dispatch(out);
            return;
        }
        if (text.charAt(0) == ':') return;
        String field;
        String value;
        int colon = text.indexOf(':');
        if (colon < 0) {
            field = text;
            value = "";
        } else {
            field = text.substring(0, colon);
            value = text.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
        }
        switch (field) {
            case "event" -> name = value;
            case "data" -> {
                if (data.length() + value.length() + 1 > MAX) {
                    overflow = true;
                    data.setLength(0);
                    return;
                }
                data.append(value).append('\n');
            }
            case "id" -> {
                if (value.indexOf('\0') < 0) lastId = value;
            }
            case "retry" -> {
                if (!value.isEmpty() && value.length() <= 9 && value.chars().allMatch(ch -> ch >= '0' && ch <= '9')) retryMs = Long.parseLong(value);
            }
            default -> {
                // unknown fields are ignored
            }
        }
    }

    private void dispatch(List<Event> out) {
        if (data.length() == 0) {
            name = "";
            return;
        }
        data.setLength(data.length() - 1);
        out.add(new Event(name.isEmpty() ? "message" : name, data.toString(), lastId));
        data.setLength(0);
        name = "";
    }

    /** The last {@code id:} seen (kept across events), or "". */
    public String lastEventId() {
        return lastId;
    }

    /** The last {@code retry:} in milliseconds, or -1. */
    public long retryMillis() {
        return retryMs;
    }

    public boolean overflowed() {
        return overflow;
    }
}

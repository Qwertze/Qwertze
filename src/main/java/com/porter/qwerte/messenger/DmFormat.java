package com.porter.qwerte.messenger;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The built-in whisper formats. Every pattern matches a whole chat line (colour codes already removed) and has the named
 * groups {@code name} (the other player) and {@code msg}. They are anchored and have no nested quantifiers, so they run in
 * linear time.
 */
public enum DmFormat {
    /** Vanilla: "X whispers to you: msg" and "You whisper to X: msg". */
    VANILLA("vanilla", "Vanilla (X whispers to you: ...)",
        in("(?<name>[A-Za-z0-9_]{3,16}) whispers to you: (?<msg>.*)"),
        out("You whisper to (?<name>[A-Za-z0-9_]{3,16}): (?<msg>.*)")),
    /** 2b2t: "X whispers: msg" and "to X: msg" (public knowledge of the server's chat format). */
    TWO_B2T("2b2t", "2b2t (X whispers: ... / to X: ...)",
        in("(?<name>[A-Za-z0-9_]{3,16}) whispers: (?<msg>.*)"),
        out("(?i:to) (?<name>[A-Za-z0-9_]{3,16}): (?<msg>.*)")),
    /** EssentialsX: "[X -> me] msg" and "[me -> X] msg". */
    ESSENTIALS("essentials", "Essentials ([X -> me] ...)",
        in("\\[(?<name>[A-Za-z0-9_]{3,16}) -> me\\] (?<msg>.*)"),
        out("\\[me -> (?<name>[A-Za-z0-9_]{3,16})\\] (?<msg>.*)")),
    /** "From X: msg" and "To X: msg". */
    FROM_TO("from-to", "From X: ... / To X: ...",
        in("(?i:from) (?<name>[A-Za-z0-9_]{3,16}): (?<msg>.*)"),
        out("(?i:to) (?<name>[A-Za-z0-9_]{3,16}): (?<msg>.*)"));

    private final String id;
    private final String label;
    private final DmPattern incoming;
    private final DmPattern outgoing;

    DmFormat(String id, String label, DmPattern incoming, DmPattern outgoing) {
        this.id = id;
        this.label = label;
        this.incoming = incoming;
        this.outgoing = outgoing;
    }

    private static DmPattern in(String regex) {
        return new DmPattern(Direction.IN, Pattern.compile(regex));
    }

    private static DmPattern out(String regex) {
        return new DmPattern(Direction.OUT, Pattern.compile(regex));
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public List<DmPattern> patterns() {
        return List.of(incoming, outgoing);
    }
}

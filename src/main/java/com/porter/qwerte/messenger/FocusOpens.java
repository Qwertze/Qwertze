package com.porter.qwerte.messenger;

/**
 * The focus-opens setting: what the window shows when the focus key opens it (not the chat key, which always opens
 * Global). Pure: no Minecraft classes.
 */
public enum FocusOpens {
    LAST_OPEN("Last open"), GLOBAL("Global"), CONSOLE("Console"), NEWEST_UNREAD("Newest unread"), SPECIFIC_DM("Specific DM");

    private final String label;

    FocusOpens(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }

    /** What to show. {@code create}: for a DM, make the conversation if there is none (only a Specific DM does). */
    public record Target(Kind kind, String name, boolean create) {
        public enum Kind {
            /** Leave the window as it is (nothing was remembered). */
            KEEP, GLOBAL, CONSOLE, DM
        }

        static final Target KEEP = new Target(Kind.KEEP, null, false);
        static final Target GLOBAL = new Target(Kind.GLOBAL, null, false);
        static final Target CONSOLE = new Target(Kind.CONSOLE, null, false);
    }

    /**
     * @param lastOpen        what was open last on this server ({@link LastOpen#GLOBAL}, {@link LastOpen#CONSOLE} or a name), or null
     * @param separateConsole the Console entry exists
     * @param newestUnread    the name of the conversation whose newest unread message is the most recent, or null for none
     * @param specific        the focus-dm name (may be null or blank)
     */
    public Target resolve(String lastOpen, boolean separateConsole, String newestUnread, String specific) {
        switch (this) {
            case GLOBAL:
                return Target.GLOBAL;
            case CONSOLE:
                return separateConsole ? Target.CONSOLE : Target.GLOBAL;
            case NEWEST_UNREAD:
                if (newestUnread != null && ChatText.isValidName(newestUnread)) return new Target(Target.Kind.DM, newestUnread, false);
                return last(lastOpen, separateConsole);
            case SPECIFIC_DM: {
                String name = specific == null ? "" : specific.strip();
                if (!name.isEmpty() && ChatText.isValidName(name)) return new Target(Target.Kind.DM, name, true);
                return last(lastOpen, separateConsole);
            }
            default:
                return last(lastOpen, separateConsole);
        }
    }

    private static Target last(String lastOpen, boolean separateConsole) {
        if (lastOpen == null) return Target.KEEP;
        if (LastOpen.GLOBAL.equals(lastOpen)) return Target.GLOBAL;
        if (LastOpen.CONSOLE.equals(lastOpen)) return separateConsole ? Target.CONSOLE : Target.GLOBAL;
        if (!ChatText.isValidName(lastOpen)) return Target.KEEP;
        return new Target(Target.Kind.DM, lastOpen, false);
    }
}

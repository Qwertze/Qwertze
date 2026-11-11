package com.porter.qwerte.messenger;

/**
 * Where a public chat line goes: the Global view, or (with separate-console on) the Console entry. Pure.
 *
 * <p>Whispers never come here (they go to their own conversation). Player chat is always Global. With the setting off
 * everything stays in Global, as before. With it on, every console line type goes to the Console except the ones the
 * user asked to keep in Global.
 */
public final class ConsoleRoute {
    public enum Target {
        GLOBAL, CONSOLE
    }

    /** Line types kept in Global even when the console is separate. */
    public record Keep(boolean joinsLeaves, boolean deaths) {
        public static final Keep NONE = new Keep(false, false);
    }

    private ConsoleRoute() {
    }

    public static Target route(boolean separate, LineKind.Type type, Keep keep) {
        if (!separate) return Target.GLOBAL;
        return switch (type) {
            case PLAYER -> Target.GLOBAL;
            case JOIN_LEAVE -> keep.joinsLeaves() ? Target.GLOBAL : Target.CONSOLE;
            case DEATH -> keep.deaths() ? Target.GLOBAL : Target.CONSOLE;
            case NOTICE, ADVANCEMENT, SERVER -> Target.CONSOLE;
        };
    }

    /** The Messenger's own notes ("Ran Meteor command", "Not sent: ..."): in the Console when it is separate, else in Global. */
    public static Target routeNote(boolean separate) {
        return separate ? Target.CONSOLE : Target.GLOBAL;
    }
}

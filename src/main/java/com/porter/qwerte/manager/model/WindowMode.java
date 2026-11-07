package com.porter.qwerte.manager.model;

/**
 * How the Stash Window gets its data. {@link #LOCAL} (the default) uses only what this game has seen and never touches
 * the network; {@link #SERVER} is the optional connection to a Stash Manager server. Pure.
 */
public enum WindowMode {
    LOCAL, SERVER;

    /** The mode for the module's connect-to-server setting: off means local, whatever address or session is saved. */
    public static WindowMode of(boolean connectToServer) {
        return connectToServer ? SERVER : LOCAL;
    }

    /** Whether anything may be sent over the network in this mode. */
    public boolean network() {
        return this == SERVER;
    }
}

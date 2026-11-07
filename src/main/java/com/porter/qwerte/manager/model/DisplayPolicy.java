package com.porter.qwerte.manager.model;

/**
 * What the window may show. When the owner forces streamer mode for the user ({@code forced}) the display is locked
 * on. {@code serverMode} says the data comes from the Stash Manager server: then nothing local (the player's position,
 * the server address of the current game, the local index) may be mixed in.
 */
public record DisplayPolicy(boolean forced, boolean localToggle, boolean serverMode) {
    public boolean streamer() {
        return forced || localToggle;
    }

    public boolean hideCoords() {
        return streamer();
    }

    public boolean hideNames() {
        return streamer();
    }

    public boolean hideChat() {
        return streamer();
    }

    public boolean hideServer() {
        return streamer();
    }

    public boolean allowLocalSources() {
        return !serverMode;
    }
}

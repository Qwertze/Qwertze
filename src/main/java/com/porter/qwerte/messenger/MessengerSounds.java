package com.porter.qwerte.messenger;

import java.util.concurrent.Executor;

/**
 * The notification sounds: the built-in blip, nothing, or a file from the sounds folder. A file is loaded and played
 * on a worker thread; if it can not be used the built-in blip plays instead and one warning names the file, which is
 * not repeated until the setting or the file changes. No Minecraft classes: the game side is {@link Out}.
 */
public final class MessengerSounds {
    public static final String BUILTIN = "Built-in";
    public static final String NONE = "None";

    /** The game side. {@link #builtin} and {@link #warn} are called from the worker thread: they must hand over to the client thread themselves. */
    public interface Out {
        /** The built-in blip at this volume, 0 to 1. */
        void builtin(float volume);

        /** One line for the user. */
        void warn(String message);

        /** Plays decoded sound at this gain (0 to 1). Throws if there is no audio output. */
        void play(Pcm pcm, float gain) throws Exception;
    }

    private final SoundLibrary library;
    private final Out out;
    private final Executor worker;
    /** What was told already (a file's identity and the reason), so two settings with two bad files do not tell each other again. */
    private final java.util.Set<String> warned = new java.util.HashSet<>();

    public MessengerSounds(SoundLibrary library, Out out, Executor worker) {
        this.library = library;
        this.out = out;
        this.worker = worker;
    }

    /** The setting changed: the next failure is told again, even for the same file. */
    public synchronized void resetWarning() {
        warned.clear();
    }

    /**
     * Plays what the setting says.
     *
     * @param choice    {@link #BUILTIN}, {@link #NONE} or a file name; null or blank is the built-in sound
     * @param volumePct sound-volume, 0 to 100
     * @param master    the game's master volume, 0 to 1 (applies to a file; the built-in sound goes through the game's own mixer)
     */
    public void play(String choice, int volumePct, float master) {
        if (volumePct <= 0 || NONE.equals(choice)) return;
        if (choice == null || choice.isBlank() || BUILTIN.equals(choice)) {
            out.builtin(volumePct / 100f);
            return;
        }
        worker.execute(() -> playFile(choice, volumePct, master));
    }

    private void playFile(String name, int volumePct, float master) {
        SoundLibrary.Loaded l = library.load(name);
        if (l.pcm() == null) {
            fail(name, l.identity(), l.error(), volumePct);
            return;
        }
        float gain = Math.max(0f, Math.min(1f, volumePct / 100f * master));
        if (gain <= 0f) return;
        try {
            out.play(l.pcm(), gain);
        } catch (Throwable t) {
            fail(name, l.identity() + "|audio", "the audio output is not available", volumePct);
        }
    }

    private void fail(String name, String key, String why, int volumePct) {
        boolean tell;
        synchronized (this) {
            if (warned.size() > 64) warned.clear();
            tell = warned.add(key);
        }
        if (tell) out.warn(SoundWarning.message(name, why));
        out.builtin(volumePct / 100f);
    }
}

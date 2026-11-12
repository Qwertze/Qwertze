package com.porter.qwerte.messenger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays decoded sound through Java's own audio output (javax.sound.sampled, part of the Java runtime), apart from the
 * game's sound engine: the game has no way to play a file from outside its resources. The gain is applied to the
 * samples, so it does not depend on the sound card having a volume control. At most {@link #MAX_AT_ONCE} sounds play
 * at once; more are dropped. Call from a worker thread (opening a line can take a moment).
 */
public final class ClipPlayer {
    static final int MAX_AT_ONCE = 4;
    /** A clip that is still open after this long is closed (a device that never reports the end must not use up the slots). */
    private static final long STUCK_MS = 30_000;

    private final List<Clip> open = new ArrayList<>();
    private final List<Long> started = new ArrayList<>();

    public void play(Pcm pcm, float gain) throws Exception {
        byte[] bytes = new byte[pcm.samples().length * 2];
        for (int i = 0; i < pcm.samples().length; i++) {
            int v = Math.round(pcm.samples()[i] * gain);
            v = Math.max(-32768, Math.min(32767, v));
            bytes[i * 2] = (byte) v;
            bytes[i * 2 + 1] = (byte) (v >> 8);
        }
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, pcm.rate(), 16, pcm.channels(), pcm.channels() * 2, pcm.rate(), false);
        synchronized (this) {
            long now = System.currentTimeMillis();
            for (int i = open.size() - 1; i >= 0; i--) {
                Clip c = open.get(i);
                if (!c.isOpen()) {
                    open.remove(i);
                    started.remove(i);
                } else if (now - started.get(i) > STUCK_MS) {
                    c.close();
                    open.remove(i);
                    started.remove(i);
                }
            }
            if (open.size() >= MAX_AT_ONCE) return;
        }
        Clip clip = AudioSystem.getClip();
        try {
            clip.open(format, bytes, 0, bytes.length);
        } catch (Exception e) {
            clip.close();
            throw e;
        }
        clip.addLineListener(ev -> {
            if (ev.getType() == LineEvent.Type.STOP) clip.close();
        });
        synchronized (this) {
            open.add(clip);
            started.add(System.currentTimeMillis());
        }
        clip.start();
    }
}

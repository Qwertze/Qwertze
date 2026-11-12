package com.porter.qwerte.messenger;

/**
 * Decoded sound: 16-bit samples, interleaved by channel (1 or 2). Not copied: do not change the array.
 * Pure: no Minecraft classes.
 */
public record Pcm(short[] samples, int channels, int rate) {
    public double seconds() {
        return rate <= 0 || channels <= 0 ? 0 : samples.length / (double) channels / rate;
    }
}

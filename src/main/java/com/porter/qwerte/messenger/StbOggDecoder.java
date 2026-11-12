package com.porter.qwerte.messenger;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Decodes Ogg Vorbis with LWJGL's STB Vorbis, which the game ships (the same version, 3.3.3, in 1.21.4 and 1.21.11).
 * The length is read first, so a file over the time limit is refused before any sample is decoded.
 */
public final class StbOggDecoder implements SoundLibrary.OggDecoder {
    @Override
    public Pcm decode(byte[] data, double maxSeconds) throws SoundException {
        // Native memory that stays until the decoder is closed (a garbage collected direct buffer could be freed mid-decode)
        ByteBuffer buf = MemoryUtil.memAlloc(data.length);
        long handle = 0L;
        try {
            buf.put(data).flip();
            int[] error = new int[1];
            handle = STBVorbis.stb_vorbis_open_memory(buf, error, null);
            if (handle == 0L) throw new SoundException("not a valid Ogg Vorbis file");
            return decode(handle, maxSeconds);
        } finally {
            if (handle != 0L) STBVorbis.stb_vorbis_close(handle);
            MemoryUtil.memFree(buf); // after the decoder is closed
        }
    }

    private static Pcm decode(long handle, double maxSeconds) throws SoundException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            STBVorbis.stb_vorbis_get_info(handle, info);
            int fileChannels = info.channels();
            int rate = info.sample_rate();
            if (fileChannels < 1) throw new SoundException("not a valid Ogg Vorbis file");
            if (rate < 4000 || rate > 192_000) throw new SoundException("odd sample rate " + rate);
            int channels = Math.min(2, fileChannels); // more than two channels are mixed down
            int frames = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
            if (frames <= 0) throw new SoundException("no sound in it");
            if (frames / (double) rate > maxSeconds) throw new SoundException("longer than " + (int) maxSeconds + " seconds");
            short[] out = new short[frames * channels];
            short[] chunk = new short[4096 * channels];
            int filled = 0;
            while (filled < out.length) {
                int n = STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, chunk);
                if (n <= 0) break;
                int count = Math.min(n * channels, out.length - filled);
                System.arraycopy(chunk, 0, out, filled, count);
                filled += count;
            }
            if (filled == 0) throw new SoundException("no sound in it");
            if (filled < out.length) out = java.util.Arrays.copyOf(out, filled);
            return new Pcm(out, channels, rate);
        }
    }
}

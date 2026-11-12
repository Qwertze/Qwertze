package com.porter.qwerte.messenger;

/**
 * Reads a RIFF/WAVE file: PCM, 8 or 16 bits, mono or stereo. Everything else (float, compressed, 24 bits, more
 * channels) is refused with a short reason. The whole file is checked before any sample array is made, so a file that
 * claims a huge size can not use memory. Pure: no Minecraft classes, no javax.sound.
 */
public final class WavDecoder {
    private WavDecoder() {
    }

    public static Pcm decode(byte[] d, double maxSeconds) throws SoundException {
        if (d.length < 12 || !tag(d, 0, "RIFF") || !tag(d, 8, "WAVE")) throw new SoundException("not a WAV file");
        int format = -1;
        int channels = 0;
        int rate = 0;
        int bits = 0;
        int dataAt = -1;
        int dataLen = 0;
        int pos = 12;
        while (pos + 8 <= d.length) {
            long size = u32(d, pos + 4);
            int body = pos + 8;
            if (tag(d, pos, "fmt ")) {
                if (size < 16 || body + 16 > d.length) throw new SoundException("not a valid WAV file");
                format = u16(d, body);
                channels = u16(d, body + 2);
                rate = (int) Math.min(Integer.MAX_VALUE, u32(d, body + 4));
                bits = u16(d, body + 14);
                if (format == 0xFFFE) { // extensible: the real format is the first two bytes of the sub-format
                    if (size < 26 || body + 26 > d.length) throw new SoundException("not a valid WAV file");
                    format = u16(d, body + 24);
                }
            } else if (tag(d, pos, "data")) {
                dataAt = body;
                dataLen = (int) Math.min(size, d.length - body); // a stream's size field may be wrong or 0xFFFFFFFF: take what is there
                break;
            }
            long next = body + size + (size & 1); // chunks are padded to even sizes
            if (next > d.length) break;
            pos = (int) next;
        }
        if (format < 0) throw new SoundException("not a valid WAV file (no format)");
        if (dataAt < 0) throw new SoundException("not a valid WAV file (no sound data)");
        if (format != 1) throw new SoundException("not PCM (only uncompressed 8 or 16 bit WAV works)");
        if (bits != 8 && bits != 16) throw new SoundException(bits + " bit WAV (only 8 or 16 bit works)");
        if (channels < 1 || channels > 2) throw new SoundException(channels + " channels (only mono or stereo works)");
        if (rate < 4000 || rate > 192_000) throw new SoundException("odd sample rate " + rate);
        int frameBytes = channels * bits / 8;
        int frames = dataLen / frameBytes;
        if (frames <= 0) throw new SoundException("no sound in it");
        if (frames / (double) rate > maxSeconds) throw new SoundException("longer than " + (int) maxSeconds + " seconds");
        short[] out = new short[frames * channels];
        if (bits == 16) {
            for (int i = 0; i < out.length; i++) {
                int p = dataAt + i * 2;
                out[i] = (short) ((d[p] & 0xFF) | (d[p + 1] << 8));
            }
        } else {
            for (int i = 0; i < out.length; i++) out[i] = (short) (((d[dataAt + i] & 0xFF) - 128) << 8); // 8 bit WAV is unsigned
        }
        return new Pcm(out, channels, rate);
    }

    private static boolean tag(byte[] d, int at, String t) {
        if (at + 4 > d.length) return false;
        for (int i = 0; i < 4; i++) {
            if (d[at + i] != (byte) t.charAt(i)) return false;
        }
        return true;
    }

    private static int u16(byte[] d, int at) {
        return (d[at] & 0xFF) | ((d[at + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] d, int at) {
        return (d[at] & 0xFFL) | ((d[at + 1] & 0xFFL) << 8) | ((d[at + 2] & 0xFFL) << 16) | ((d[at + 3] & 0xFFL) << 24);
    }
}

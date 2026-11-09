package com.porter.qwerte.messenger;

import com.mojang.blaze3d.platform.NativeImage;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads an emoji picture from the jar and shrinks it to {@link #SIZE} pixels (the files are 72x72; the window draws them
 * about 9 GUI pixels wide). The jar is read through the class loader because Fabric API's resource packs may not be there.
 */
final class EmojiPixels {
    static final int SIZE = 36;

    private EmojiPixels() {
    }

    /** The picture as a new image the caller owns and must close, or null if it is missing or unreadable. */
    static NativeImage load(String file) {
        if (!file.matches("[0-9a-f\\-]{2,80}")) return null;
        try (InputStream in = EmojiPixels.class.getResourceAsStream(EmojiSet.TEXTURE_DIR + file + ".png")) {
            if (in == null) return null;
            try (NativeImage src = NativeImage.read(in)) {
                int w = src.getWidth();
                int h = src.getHeight();
                if (w <= 0 || h <= 0 || w > 512 || h > 512) return null;
                int[] argb = new int[w * h];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) argb[y * w + x] = src.getPixel(x, y);
                }
                int[] small = ImageScale.box(argb, w, h, SIZE, SIZE);
                NativeImage out = new NativeImage(SIZE, SIZE, false);
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) out.setPixel(x, y, small[y * SIZE + x]);
                }
                return out;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}

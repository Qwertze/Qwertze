package com.porter.qwerte.messenger;

/**
 * Shrinks a picture by an even box filter, so emoji drawn small are smooth instead of picking single pixels. Colours are
 * averaged weighted by alpha so transparent edges stay clean. No Minecraft classes.
 */
public final class ImageScale {
    private ImageScale() {
    }

    /**
     * @param argb   {@code srcW * srcH} pixels as 0xAARRGGBB, row 0 first
     * @return {@code dstW * dstH} pixels in the same format
     */
    public static int[] box(int[] argb, int srcW, int srcH, int dstW, int dstH) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0 || argb.length < srcW * srcH) throw new IllegalArgumentException("bad size");
        int[] out = new int[dstW * dstH];
        for (int y = 0; y < dstH; y++) {
            int y0 = (int) ((long) y * srcH / dstH);
            int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * srcH / dstH));
            for (int x = 0; x < dstW; x++) {
                int x0 = (int) ((long) x * srcW / dstW);
                int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * srcW / dstW));
                long a = 0, r = 0, g = 0, b = 0;
                int count = 0;
                for (int sy = y0; sy < y1 && sy < srcH; sy++) {
                    for (int sx = x0; sx < x1 && sx < srcW; sx++) {
                        int p = argb[sy * srcW + sx];
                        int pa = p >>> 24;
                        a += pa;
                        r += (long) ((p >> 16) & 0xFF) * pa;
                        g += (long) ((p >> 8) & 0xFF) * pa;
                        b += (long) (p & 0xFF) * pa;
                        count++;
                    }
                }
                if (count == 0 || a == 0) {
                    out[y * dstW + x] = 0;
                    continue;
                }
                int oa = (int) (a / count);
                int or = (int) (r / a);
                int og = (int) (g / a);
                int ob = (int) (b / a);
                out[y * dstW + x] = (oa << 24) | (or << 16) | (og << 8) | ob;
            }
        }
        return out;
    }
}

package com.ice.scan.img;

/**
 * 整幅缩放（区域平均）。纯算术，不碰 Android，直接可测。
 *
 * <p>用在两处：自动找边前把图压到几百像素（算法对分辨率不敏感，快得多），
 * 以及效果预览。用区域平均而不是最近邻，缩下来的纸边才不会全是锯齿。
 */
public final class Resize {

    private Resize() {
    }

    /** 按长边限制算出目标尺寸；不缩放时原样返回。 */
    public static int[] fit(int w, int h, int maxSide) {
        int longest = Math.max(w, h);
        if (longest <= maxSide || longest <= 0) return new int[]{w, h};
        double s = (double) maxSide / longest;
        return new int[]{
                Math.max(1, (int) Math.round(w * s)),
                Math.max(1, (int) Math.round(h * s))};
    }

    /**
     * 缩放到指定尺寸。
     *
     * @param src  ARGB 像素
     * @param outW 目标宽（应小于等于原图宽）
     * @param outH 目标高
     * @return 新数组；尺寸没变时返回原数组本身（调用方只读即可）
     */
    public static int[] downscale(int[] src, int w, int h, int outW, int outH) {
        if (outW == w && outH == h) return src;
        int[] out = new int[outW * outH];
        double xr = (double) w / outW;
        double yr = (double) h / outH;

        for (int y = 0; y < outH; y++) {
            int y0 = (int) (y * yr);
            int y1 = Math.min(h, Math.max(y0 + 1, (int) ((y + 1) * yr)));
            for (int x = 0; x < outW; x++) {
                int x0 = (int) (x * xr);
                int x1 = Math.min(w, Math.max(x0 + 1, (int) ((x + 1) * xr)));

                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int sy = y0; sy < y1; sy++) {
                    int row = sy * w;
                    for (int sx = x0; sx < x1; sx++) {
                        int p = src[row + sx];
                        a += p >>> 24;
                        r += p >> 16 & 0xff;
                        g += p >> 8 & 0xff;
                        b += p & 0xff;
                        n++;
                    }
                }
                if (n == 0) n = 1;
                out[y * outW + x] = ((int) (a / n) << 24) | ((int) (r / n) << 16)
                        | ((int) (g / n) << 8) | (int) (b / n);
            }
        }
        return out;
    }
}
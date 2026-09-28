package com.ice.scan.img;

/**
 * 把拍歪的四边形拉成矩形（扫描件最关键的一步）。
 *
 * <p>做法是反向映射：对输出图上的每个像素，用单应矩阵反查它来自原图的哪个位置，
 * 再双线性插值取色。这样输出图不会出现空洞（正向映射会有）。
 */
public final class Perspective {

    private Perspective() {
    }

    /**
     * 按四边形裁剪并拉直。
     *
     * @param src 原图 ARGB 像素
     * @param quad 4 个角点（TL, TR, BR, BL），坐标是原图像素
     * @param outW 输出宽
     * @param outH 输出高
     */
    public static int[] warp(int[] src, int w, int h, float[] quad, int outW, int outH) {
        int[] out = new int[outW * outH];
        double[] dst = {0, 0, outW - 1, 0, outW - 1, outH - 1, 0, outH - 1};
        double[] s = new double[8];
        for (int i = 0; i < 8; i++) s[i] = quad[i];

        // 要的是「输出 → 原图」，所以拿 dst 去找 src
        float[] hm = Homography.quadToQuad(dst, s);
        if (hm == null) {
            // 四边形退化了（比如用户把 4 个角拖成了一条线）：退化成整图缩放，别崩
            for (int y = 0; y < outH; y++) {
                int sy = (int) ((long) y * h / Math.max(1, outH));
                for (int x = 0; x < outW; x++) {
                    int sx = (int) ((long) x * w / Math.max(1, outW));
                    out[y * outW + x] = src[clamp(sy, h - 1) * w + clamp(sx, w - 1)];
                }
            }
            return out;
        }

        double[] p = new double[2];
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                Homography.map(hm, x, y, p);
                out[y * outW + x] = sampleBilinear(src, w, h, p[0], p[1]);
            }
        }
        return out;
    }

    /** 双线性采样，超出边界就夹住（避免黑边）。 */
    static int sampleBilinear(int[] src, int w, int h, double x, double y) {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x > w - 1) x = w - 1;
        if (y > h - 1) y = h - 1;

        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(x0 + 1, w - 1);
        int y1 = Math.min(y0 + 1, h - 1);
        double fx = x - x0;
        double fy = y - y0;

        int p00 = src[y0 * w + x0];
        int p10 = src[y0 * w + x1];
        int p01 = src[y1 * w + x0];
        int p11 = src[y1 * w + x1];

        int r = (int) mix(mix(p00 >> 16 & 0xff, p10 >> 16 & 0xff, fx), mix(p01 >> 16 & 0xff, p11 >> 16 & 0xff, fx), fy);
        int g = (int) mix(mix(p00 >> 8 & 0xff, p10 >> 8 & 0xff, fx), mix(p01 >> 8 & 0xff, p11 >> 8 & 0xff, fx), fy);
        int b = (int) mix(mix(p00 & 0xff, p10 & 0xff, fx), mix(p01 & 0xff, p11 & 0xff, fx), fy);
        return 0xff000000 | r << 16 | g << 8 | b;
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int clamp(int v, int max) {
        if (v < 0) return 0;
        return Math.min(v, max);
    }

    /**
     * 由四边形推出合适的输出尺寸：取两组对边的平均长度，并限制长边上限。
     * 直接用最长边会让斜拍的照片输出过大、白白吃内存。
     */
    public static int[] outputSize(float[] quad, int maxSide) {
        double wTop = dist(quad[0], quad[1], quad[2], quad[3]);
        double wBottom = dist(quad[6], quad[7], quad[4], quad[5]);
        double hLeft = dist(quad[0], quad[1], quad[6], quad[7]);
        double hRight = dist(quad[2], quad[3], quad[4], quad[5]);

        double ow = (wTop + wBottom) / 2;
        double oh = (hLeft + hRight) / 2;
        double scale = 1;
        double longest = Math.max(ow, oh);
        if (longest > maxSide) scale = maxSide / longest;

        return new int[]{
                Math.max(1, (int) Math.round(ow * scale)),
                Math.max(1, (int) Math.round(oh * scale))};
    }

    private static double dist(float x1, float y1, float x2, float y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
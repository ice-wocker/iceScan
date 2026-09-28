package com.ice.scan.img;

/**
 * 扫描件增强：去阴影、灰度、黑白。
 *
 * <p>核心一招是「按背景做除法」：先大范围模糊得到背景亮度，再用
 * {@code 像素 × 全局均值 ÷ 局部背景} 归一化。同一张纸上一半被灯照、一半在阴影里，
 * 归一化后亮度就一致了——这是去掉阴影和光照不均最便宜有效的办法。
 *
 * <p>黑白模式用 Bradley 自适应阈值（局部均值 × 0.88），靠积分图做到 O(像素数)，
 * 不会像全局阈值那样把阴影区整片染黑。
 */
public final class Enhance {

    /** 原图：只裁剪拉直，不做任何增强。 */
    public static final int ORIGINAL = 0;
    /** 彩色增强：去阴影、保留颜色。 */
    public static final int COLOR = 1;
    /** 灰度：去阴影 + 自动对比度。 */
    public static final int GRAY = 2;
    /** 黑白：最适合文字页，文件也最小。 */
    public static final int BW = 3;

    private Enhance() {
    }

    public static int[] enhance(int[] px, int w, int h, int mode) {
        if (px == null || mode == ORIGINAL) return px;

        int n = px.length;
        int[] gray = new int[n];
        for (int i = 0; i < n; i++) gray[i] = luma(px[i]);

        // 估光照的窗口必须明显大于笔画宽度，否则「背景」里混进文字，除法就把文字也一起抬亮了
        int illumR = Math.max(8, Math.min(w, h) / 8);
        int[] bg = boxBlur(gray, w, h, illumR);
        int mean = mean(gray);

        switch (mode) {
            case COLOR:
                return colorNormalize(px, bg, mean);
            case GRAY:
                return toGray(stretch(normalize(gray, bg, mean)));
            case BW:
                // 自适应阈值的窗口同样要够大（Bradley 取 s = w/8 就是这个道理）
                int thrR = Math.max(8, Math.min(w, h) / 12);
                return binarize(stretch(normalize(gray, bg, mean)), w, h, thrR);
            default:
                return px;
        }
    }

    static int luma(int p) {
        return ((p >> 16 & 0xff) * 77 + (p >> 8 & 0xff) * 151 + (p & 0xff) * 28) >> 8;
    }

    static int mean(int[] a) {
        long sum = 0;
        for (int v : a) sum += v;
        return (int) (sum / Math.max(1, a.length));
    }

    /** 积分图（前缀和，多一行一列方便算窗口）。 */
    static long[] integral(int[] a, int w, int h) {
        int stride = w + 1;
        long[] s = new long[stride * (h + 1)];
        for (int y = 0; y < h; y++) {
            long rowSum = 0;
            for (int x = 0; x < w; x++) {
                rowSum += a[y * w + x];
                s[(y + 1) * stride + (x + 1)] = s[y * stride + (x + 1)] + rowSum;
            }
        }
        return s;
    }

    /** 均值模糊，用积分图做，和半径无关，都是 O(像素数)。 */
    static int[] boxBlur(int[] a, int w, int h, int r) {
        long[] s = integral(a, w, h);
        int stride = w + 1;
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r);
            int y1 = Math.min(h - 1, y + r);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r);
                int x1 = Math.min(w - 1, x + r);
                long sum = s[(y1 + 1) * stride + (x1 + 1)]
                        - s[y0 * stride + (x1 + 1)]
                        - s[(y1 + 1) * stride + x0]
                        + s[y0 * stride + x0];
                int count = (x1 - x0 + 1) * (y1 - y0 + 1);
                out[y * w + x] = (int) (sum / count);
            }
        }
        return out;
    }

    /** Otsu：让类间方差最大的那个阈值。 */
    static int otsu(int[] gray) {
        int[] hist = new int[256];
        long total = 0;
        long sum = 0;
        for (int v : gray) {
            int c = v < 0 ? 0 : (v > 255 ? 255 : v);
            hist[c]++;
            total++;
            sum += c;
        }
        long wB = 0;
        long sumB = 0;
        double best = -1;
        int thr = 127;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            long wF = total - wB;
            if (wF == 0) break;
            sumB += (long) t * hist[t];
            double mB = (double) sumB / wB;
            double mF = (double) (sum - sumB) / wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > best) {
                best = between;
                thr = t;
            }
        }
        return thr;
    }

    /** 光照归一化：像素 × 全局均值 ÷ 局部背景。 */
    static int[] normalize(int[] gray, int[] bg, int mean) {
        int[] out = new int[gray.length];
        for (int i = 0; i < gray.length; i++) {
            double v = (double) gray[i] * mean / Math.max(1, bg[i]);
            out[i] = clamp(v);
        }
        return out;
    }

    static int[] colorNormalize(int[] px, int[] bg, int mean) {
        int[] out = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            double f = (double) mean / Math.max(1, bg[i]);
            int p = px[i];
            int r = clamp((p >> 16 & 0xff) * f);
            int g = clamp((p >> 8 & 0xff) * f);
            int b = clamp((p & 0xff) * f);
            out[i] = 0xff000000 | r << 16 | g << 8 | b;
        }
        return out;
    }

    /** 两端各切掉 1% 再拉满，比直接取 min/max 抗离群点。 */
    static int[] stretch(int[] a) {
        int[] hist = new int[256];
        for (int v : a) {
            int c = v < 0 ? 0 : (v > 255 ? 255 : v);
            hist[c]++;
        }
        int n = a.length;
        int cut = Math.max(1, n / 100);
        int lo = 0;
        int hi = 255;
        int acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= cut) {
                lo = i;
                break;
            }
        }
        acc = 0;
        for (int i = 255; i >= 0; i--) {
            acc += hist[i];
            if (acc >= cut) {
                hi = i;
                break;
            }
        }
        if (hi <= lo) return a;

        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = clamp((a[i] - lo) * 255.0 / (hi - lo));
        }
        return out;
    }

    /** Bradley 自适应阈值：比局部均值暗一截就算字。 */
    static int[] binarize(int[] gray, int w, int h, int radius) {
        int[] local = boxBlur(gray, w, h, Math.max(4, radius / 2));
        int[] out = new int[gray.length];
        for (int i = 0; i < gray.length; i++) {
            out[i] = gray[i] > local[i] * 0.88 ? 0xffffffff : 0xff000000;
        }
        return out;
    }

    static int[] toGray(int[] v) {
        int[] out = new int[v.length];
        for (int i = 0; i < v.length; i++) {
            int g = clamp(v[i]);
            out[i] = 0xff000000 | g << 16 | g << 8 | g;
        }
        return out;
    }

    private static int clamp(double v) {
        if (v <= 0) return 0;
        if (v >= 255) return 255;
        return (int) v;
    }
}
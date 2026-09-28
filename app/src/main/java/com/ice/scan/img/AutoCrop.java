package com.ice.scan.img;

/**
 * 自动找纸张的 4 个角：给编辑页一个「初猜」，用户再微调。
 *
 * <p>思路很朴素——纸在照片里通常是**最亮的那一大块**：
 * <ol>
 *   <li>转灰度、模糊掉噪点</li>
 *   <li>Otsu 自动阈值切出亮区</li>
 *   <li>取最大的连通块（顺手把零星亮点剔掉）</li>
 *   <li>用「极值点」定 4 个角：x+y 最小的是左上、最大的是右下，x−y 最大的是右上、最小的是左下</li>
 * </ol>
 *
 * <p>极值法便宜、对凸四边形足够准，但碰到旋转 45° 或纸比背景暗就会错——
 * 所以它只负责给初值，四角可拖是必要的兜底，这一条在 README 里也写明了。
 */
public final class AutoCrop {

    private AutoCrop() {
    }

    /**
     * @param argb 已缩小到检测分辨率的像素（建议长边 320 左右，再小会丢精度）
     * @return 4 个角点（TL, TR, BR, BL，坐标同输入图）；找不到可信的纸张时返回 null
     */
    public static float[] detect(int[] argb, int w, int h) {
        if (argb == null || w < 8 || h < 8) return null;
        int n = w * h;

        int[] gray = new int[n];
        for (int i = 0; i < n; i++) {
            int p = argb[i];
            gray[i] = ((p >> 16 & 0xff) * 77 + (p >> 8 & 0xff) * 151 + (p & 0xff) * 28) >> 8;
        }
        gray = Enhance.boxBlur(gray, w, h, Math.max(1, Math.min(w, h) / 100));

        int thr = Enhance.otsu(gray);
        boolean[] mask = new boolean[n];
        int bright = 0;
        for (int i = 0; i < n; i++) {
            if (gray[i] > thr) {
                mask[i] = true;
                bright++;
            }
        }

        double ratio = (double) bright / n;
        // 整张都亮：说明照片里全是纸（或背景本来就白），直接用整幅
        if (ratio > 0.90) return full(w, h);
        // 亮区太少：多半是暗色桌面上的深色物体，硬猜不如让用户自己拖
        if (ratio < 0.08) return null;

        mask = largestComponent(mask, w, h);
        float[] quad = extremes(mask, w, h);
        if (quad == null) return null;

        // 往外扩一点：纸的边缘常被阴影压暗，贴着掩膜裁会切掉内容
        return expand(quad, 0.02f);
    }

    /** 整幅画面。 */
    public static float[] full(int w, int h) {
        return new float[]{0, 0, w - 1, 0, w - 1, h - 1, 0, h - 1};
    }

    /**
     * 方便的入口：喂进全分辨率的像素，内部自己缩到检测尺寸再找边，
     * 返回**归一化**角点（0–1）。找不到纸时返回整幅画面，调用方不用处理 null。
     *
     * @param detectSide 检测分辨率的长边，320–480 比较合适
     */
    public static float[] detectNormalized(int[] argb, int w, int h, int detectSide) {
        int[] size = Resize.fit(w, h, detectSide);
        int[] small = Resize.downscale(argb, w, h, size[0], size[1]);
        float[] q = detect(small, size[0], size[1]);
        return q == null ? Quad.full() : Quad.normalize(q, size[0], size[1]);
    }

    /** 只保留最大的连通块（4 邻域），其余置 false。 */
    static boolean[] largestComponent(boolean[] mask, int w, int h) {
        int n = w * h;
        int[] label = new int[n];
        int[] stack = new int[n];
        boolean[] keep = new boolean[n];
        int best = 0;
        int bestSize = 0;
        int cur = 0;

        for (int start = 0; start < n; start++) {
            if (!mask[start] || label[start] != 0) continue;
            cur++;
            int top = 0;
            stack[top++] = start;
            label[start] = cur;
            int size = 0;
            while (top > 0) {
                int p = stack[--top];
                size++;
                int x = p % w;
                int y = p / w;
                if (x > 0) top = push(mask, label, stack, top, p - 1, cur);
                if (x < w - 1) top = push(mask, label, stack, top, p + 1, cur);
                if (y > 0) top = push(mask, label, stack, top, p - w, cur);
                if (y < h - 1) top = push(mask, label, stack, top, p + w, cur);
            }
            if (size > bestSize) {
                bestSize = size;
                best = cur;
            }
        }
        if (best == 0) return keep;
        for (int i = 0; i < n; i++) keep[i] = label[i] == best;
        return keep;
    }

    private static int push(boolean[] mask, int[] label, int[] stack, int top, int p, int cur) {
        if (mask[p] && label[p] == 0) {
            label[p] = cur;
            stack[top++] = p;
        }
        return top;
    }

    /** 用四个极值点定角，并整理成 TL, TR, BR, BL。 */
    static float[] extremes(boolean[] mask, int w, int h) {
        int tl = -1, tr = -1, br = -1, bl = -1;
        int minSum = Integer.MAX_VALUE, maxSum = Integer.MIN_VALUE;
        int maxDiff = Integer.MIN_VALUE, minDiff = Integer.MAX_VALUE;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (!mask[i]) continue;
                int sum = x + y;
                int diff = x - y;
                if (sum < minSum) {
                    minSum = sum;
                    tl = i;
                }
                if (sum > maxSum) {
                    maxSum = sum;
                    br = i;
                }
                if (diff > maxDiff) {
                    maxDiff = diff;
                    tr = i;
                }
                if (diff < minDiff) {
                    minDiff = diff;
                    bl = i;
                }
            }
        }
        if (tl < 0 || tr < 0 || br < 0 || bl < 0) return null;

        float[] q = {
                tl % w, tl / w,
                tr % w, tr / w,
                br % w, br / w,
                bl % w, bl / w};
        if (!convexEnough(q)) return null;
        return q;
    }

    /**
     * 极值点构成的四边形要足够大，否则就是噪声。
     * 顺便挡掉「四个点退化成一条线」的情况——那样后面的单应矩阵会解不出来。
     */
    private static boolean convexEnough(float[] q) {
        double area = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            area += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1];
        }
        return Math.abs(area) / 2 > 16;
    }

    /** 以质心为中心把 4 个角往外推。 */
    static float[] expand(float[] q, float ratio) {
        float cx = 0, cy = 0;
        for (int i = 0; i < 4; i++) {
            cx += q[i * 2];
            cy += q[i * 2 + 1];
        }
        cx /= 4;
        cy /= 4;
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            out[i * 2] = cx + (q[i * 2] - cx) * (1 + ratio);
            out[i * 2 + 1] = cy + (q[i * 2 + 1] - cy) * (1 + ratio);
        }
        return out;
    }
}
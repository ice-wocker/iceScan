package com.ice.scan.img;

/**
 * 四角坐标的两种表示之间的换算。
 *
 * <p>统一用「归一化坐标」（0–1）来保存角点，好处是同一份数据可以套到任意分辨率上：
 * 编辑时贴的是预览图，导出时用的是全分辨率原图，坐标不用重新标定。这一层换算单独
 * 拎出来，是为了能脱离 Android 直接测。
 */
public final class Quad {

    private Quad() {
    }

    /** 整幅图像。 */
    public static float[] full() {
        return new float[]{0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f};
    }

    /** 归一化 → 像素坐标。 */
    public static float[] toPixels(float[] norm, int w, int h) {
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            out[i * 2] = norm[i * 2] * w;
            out[i * 2 + 1] = norm[i * 2 + 1] * h;
        }
        return out;
    }

    /** 像素坐标 → 归一化。 */
    public static float[] normalize(float[] px, int w, int h) {
        float[] out = new float[8];
        if (w <= 0 || h <= 0) return full();
        for (int i = 0; i < 4; i++) {
            out[i * 2] = px[i * 2] / w;
            out[i * 2 + 1] = px[i * 2 + 1] / h;
        }
        return out;
    }

    /** 把每个角夹在图像范围内，保证不会算出越界的单应矩阵。 */
    public static float[] clamp(float[] norm) {
        float[] out = new float[8];
        for (int i = 0; i < 8; i++) {
            float v = norm[i];
            out[i] = v < 0 ? 0 : (v > 1 ? 1 : v);
        }
        return out;
    }

    public static float[] copy(float[] q) {
        return new float[]{q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7]};
    }
}
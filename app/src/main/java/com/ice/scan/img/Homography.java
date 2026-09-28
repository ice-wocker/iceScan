package com.ice.scan.img;

/**
 * 透视变换的数学部分：由 4 组对应点解出 3×3 单应矩阵。
 *
 * <p>纯算术，不碰任何 Android API，所以能直接用 JUnit 跑。
 */
public final class Homography {

    private Homography() {
    }

    /**
     * 求把 {@code src} 的 4 个点映到 {@code dst} 的 4 个点的单应矩阵。
     *
     * @param src 4 个点，顺序为 x0,y0,x1,y1,x2,y2,x3,y3
     * @param dst 同样格式的 4 个点
     * @return 行主序 3×3（h[8] 固定为 1）；四边形退化成一条线时返回 null
     */
    public static float[] quadToQuad(double[] src, double[] dst) {
        double[][] a = new double[8][8];
        double[] b = new double[8];
        for (int i = 0; i < 4; i++) {
            double x = src[i * 2];
            double y = src[i * 2 + 1];
            double u = dst[i * 2];
            double v = dst[i * 2 + 1];
            a[i * 2] = new double[]{x, y, 1, 0, 0, 0, -u * x, -u * y};
            b[i * 2] = u;
            a[i * 2 + 1] = new double[]{0, 0, 0, x, y, 1, -v * x, -v * y};
            b[i * 2 + 1] = v;
        }
        double[] h = solve(a, b);
        if (h == null) return null;
        return new float[]{
                (float) h[0], (float) h[1], (float) h[2],
                (float) h[3], (float) h[4], (float) h[5],
                (float) h[6], (float) h[7], 1f};
    }

    /** 用单应矩阵把一个点映过去。 */
    public static void map(float[] h, double x, double y, double[] out) {
        double den = h[6] * x + h[7] * y + h[8];
        if (Math.abs(den) < 1e-12) den = 1e-12;
        out[0] = (h[0] * x + h[1] * y + h[2]) / den;
        out[1] = (h[3] * x + h[4] * y + h[5]) / den;
    }

    /** 高斯消元 + 部分主元；奇异时返回 null。 */
    static double[] solve(double[][] m, double[] rhs) {
        int n = rhs.length;
        double[][] a = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(m[i], 0, a[i], 0, n);
            a[i][n] = rhs[i];
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) piv = r;
            }
            if (Math.abs(a[piv][col]) < 1e-9) return null;
            double[] t = a[col];
            a[col] = a[piv];
            a[piv] = t;

            double d = a[col][col];
            for (int c = col; c <= n; c++) a[col][c] /= d;

            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = a[r][col];
                if (f == 0) continue;
                for (int c = col; c <= n; c++) a[r][c] -= f * a[col][c];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = a[i][n];
        return x;
    }
}
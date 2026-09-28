package com.ice.scan.img;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** 几何与缩放：归一化坐标换算、输出尺寸、单应矩阵、区域平均降采样。 */
public class GeometryTest {

    @Test
    public void quadNormalizeRoundTrip() {
        float[] norm = {0.1f, 0.2f, 0.9f, 0.15f, 0.85f, 0.8f, 0.12f, 0.9f};
        float[] px = Quad.toPixels(norm, 1000, 500);
        assertEquals(100f, px[0], 1e-3);
        assertEquals(100f, px[1], 1e-3);
        assertEquals(900f, px[2], 1e-3);

        float[] back = Quad.normalize(px, 1000, 500);
        for (int i = 0; i < 8; i++) {
            assertEquals(norm[i], back[i], 1e-4);
        }
    }

    @Test
    public void quadClampPullsCornersInside() {
        float[] q = Quad.clamp(new float[]{-1f, 2f, 3f, -4f, 0.5f, 0.5f, 1f, 1f});
        assertEquals(0f, q[0], 0);
        assertEquals(1f, q[1], 0);
        assertEquals(1f, q[2], 0);
        assertEquals(0f, q[3], 0);
    }

    @Test
    public void outputSizeShrinksWhenTooLarge() {
        int[] s = Perspective.outputSize(new float[]{0, 0, 4000, 0, 4000, 3000, 0, 3000}, 2000);
        assertEquals(2000, s[0]);
        assertEquals(1500, s[1]);
    }

    @Test
    public void outputSizeKeepsSmallQuad() {
        int[] s = Perspective.outputSize(new float[]{0, 0, 100, 0, 100, 50, 0, 50}, 2000);
        assertEquals(100, s[0]);
        assertEquals(50, s[1]);
    }

    @Test
    public void homographyMapsCornersExactly() {
        double[] src = {0, 0, 10, 0, 10, 10, 0, 10};
        double[] dst = {0, 0, 100, 0, 100, 100, 0, 100};
        float[] h = Homography.quadToQuad(src, dst);
        assertNotNull(h);

        double[] p = new double[2];
        Homography.map(h, 0, 0, p);
        assertEquals(0, p[0], 1e-4);
        assertEquals(0, p[1], 1e-4);
        Homography.map(h, 5, 5, p);
        assertEquals(50, p[0], 1e-3);
        assertEquals(50, p[1], 1e-3);
        Homography.map(h, 10, 10, p);
        assertEquals(100, p[0], 1e-3);
        assertEquals(100, p[1], 1e-3);
    }

    @Test
    public void singularSystemReturnsNull() {
        assertNull(Homography.solve(new double[2][2], new double[]{1, 2}));
    }

    @Test
    public void warpKeepsUniformColor() {
        int w = 20;
        int h = 10;
        int[] src = new int[w * h];
        Arrays.fill(src, 0xFF336699);

        int[] out = Perspective.warp(src, w, h,
                new float[]{0, 0, w - 1, 0, w - 1, h - 1, 0, h - 1}, 40, 20);
        assertEquals(800, out.length);
        for (int p : out) {
            assertEquals(0xFF336699, p);
        }
    }

    @Test
    public void warpSurvivesDegenerateQuad() {
        int[] src = new int[100];
        Arrays.fill(src, 0xFF112233);
        // 四个角退化成一条线：允许走「整图缩放」的兜底分支，但不能崩
        int[] out = Perspective.warp(src, 10, 10, new float[]{0, 0, 5, 5, 10, 10, 15, 15}, 8, 8);
        assertEquals(64, out.length);
    }

    @Test
    public void resizeFitClampsLongSide() {
        assertArrayEquals(new int[]{50, 25}, Resize.fit(100, 50, 50));
        assertArrayEquals(new int[]{100, 50}, Resize.fit(100, 50, 200));
    }

    @Test
    public void downscaleAveragesArea() {
        int[] src = {0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF};
        int[] small = Resize.downscale(src, 2, 2, 1, 1);
        int gray = small[0] & 0xff;
        assertTrue("四个像素平均应为灰色，实际 " + gray, gray > 120 && gray < 136);
    }
}
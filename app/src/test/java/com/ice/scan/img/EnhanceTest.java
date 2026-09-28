package com.ice.scan.img;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** 增强算法：亮度权重、Otsu、积分图模糊、去阴影、黑白二值化。 */
public class EnhanceTest {

    @Test
    public void lumaWeightsChannels() {
        assertEquals(255, Enhance.luma(0xFFFFFFFF));
        assertEquals(0, Enhance.luma(0xFF000000));
        // 绿色权重最高，同样幅度下绿色应当最亮
        assertTrue(Enhance.luma(0xFF00FF00) > Enhance.luma(0xFFFF0000));
        assertTrue(Enhance.luma(0xFFFF0000) > Enhance.luma(0xFF0000FF));
    }

    @Test
    public void otsuFindsGapBetweenTwoPeaks() {
        int[] gray = new int[200];
        for (int i = 0; i < 100; i++) gray[i] = 30;
        for (int i = 100; i < 200; i++) gray[i] = 220;
        int t = Enhance.otsu(gray);
        // 干净的直方图上，Otsu 的最优阈值正好落在较低的峰上，能把两簇分开就行
        assertTrue("阈值应落在两峰之间，实际 " + t, t >= 30 && t < 220);
    }

    @Test
    public void boxBlurOfUniformImageIsUnchanged() {
        int[] a = new int[100];
        Arrays.fill(a, 200);
        int[] b = Enhance.boxBlur(a, 10, 10, 3);
        for (int v : b) {
            assertEquals(200, v);
        }
    }

    @Test
    public void originalModeIsNoOp() {
        int[] px = {1, 2, 3};
        assertSame(px, Enhance.enhance(px, 3, 1, Enhance.ORIGINAL));
    }

    @Test
    public void bwModeOutputsOnlyBlackOrWhite() {
        int w = 64;
        int h = 64;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                px[y * w + x] = ((x / 8 + y / 8) % 2 == 0) ? 0xFFFFFFFF : 0xFF202020;
            }
        }
        int[] out = Enhance.enhance(px, w, h, Enhance.BW);
        for (int p : out) {
            assertTrue("黑白模式只应产出纯黑或纯白", p == 0xFFFFFFFF || p == 0xFF000000);
        }
    }

    @Test
    public void colorNormalizeLiftsShadowSide() {
        // 同一张灰纸：左半被灯照亮、右半在阴影里。归一化后两半亮度要接近
        int w = 64;
        int h = 64;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                px[y * w + x] = 0xFF000000 | (x < w / 2 ? 0xC8C8C8 : 0x606060);
            }
        }
        int[] out = Enhance.enhance(px, w, h, Enhance.COLOR);
        int left = out[32 * w + 4] & 0xff;
        int right = out[32 * w + (w - 5)] & 0xff;
        assertTrue("暗部应被抬亮，左=" + left + " 右=" + right, Math.abs(left - right) < 30);
    }
}
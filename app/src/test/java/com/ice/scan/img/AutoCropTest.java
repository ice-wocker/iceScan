package com.ice.scan.img;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** 自动找边：亮纸应被框住，整幅全亮退化成整图，太小的输入直接放弃。 */
public class AutoCropTest {

    @Test
    public void findsBrightPaperOnDarkDesk() {
        int w = 200;
        int h = 160;
        int[] px = new int[w * h];
        Arrays.fill(px, 0xFF303030);
        for (int y = 30; y < 130; y++) {
            for (int x = 40; x < 160; x++) {
                px[y * w + x] = 0xFFF2F2F2;
            }
        }

        float[] q = AutoCrop.detect(px, w, h);
        assertNotNull("应当找到纸张", q);

        float[] n = Quad.normalize(q, w, h);
        // 左上角横坐标应接近纸的左缘（40/200 = 0.2）
        assertTrue("左上 x 偏差过大：" + n[0], n[0] > 0.08f && n[0] < 0.35f);
        // 右下角纵坐标应接近纸的下缘（130/160 ≈ 0.81）
        assertTrue("右下 y 偏差过大：" + n[5], n[5] > 0.6f);
    }

    @Test
    public void allBrightImageFallsBackToFullFrame() {
        int w = 100;
        int h = 100;
        int[] px = new int[w * h];
        Arrays.fill(px, 0xFFF0F0F0);

        float[] q = AutoCrop.detect(px, w, h);
        assertNotNull(q);
        assertEquals(0f, q[0], 0.001f);
        assertEquals(w - 1, q[2], 0.001f);
    }

    @Test
    public void tinyImageIsRejected() {
        assertNull(AutoCrop.detect(new int[4], 2, 2));
    }

    @Test
    public void detectNormalizedNeverReturnsNull() {
        int w = 64;
        int h = 64;
        int[] px = new int[w * h];
        Arrays.fill(px, 0xFF101010);   // 一片暗，找不到纸
        float[] n = AutoCrop.detectNormalized(px, w, h, 64);
        assertNotNull(n);
        assertEquals(8, n.length);
        // 找不到就整幅，四个角贴边
        assertEquals(0f, n[0], 1e-4f);
        assertEquals(1f, n[2], 1e-4f);
    }
}
package com.ice.scan.img;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** PDF 生成器：能正确从 JPEG 里读尺寸、拼出结构完整的文件。 */
public class PdfWriterTest {

    /**
     * 造一段「够真」的最小 JPEG：SOI + SOF0（含宽高）。
     * 解析器只需要扫到 SOF，后面的压缩数据用不着。
     */
    static byte[] fakeJpeg(int w, int h) {
        return new byte[]{
                (byte) 0xFF, (byte) 0xD8,                       // SOI
                (byte) 0xFF, (byte) 0xC0, 0x00, 0x11, 0x08,     // SOF0，长度 17，精度 8
                (byte) (h >> 8), (byte) h,
                (byte) (w >> 8), (byte) w,
                0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00,
                (byte) 0xFF, (byte) 0xD9                        // EOI
        };
    }

    @Test
    public void readsJpegSize() {
        int[] s = PdfWriter.jpegSize(fakeJpeg(1600, 1200));
        assertNotNull(s);
        assertEquals(1600, s[0]);
        assertEquals(1200, s[1]);
    }

    @Test
    public void rejectsNonJpeg() {
        assertNull(PdfWriter.jpegSize(new byte[]{1, 2, 3, 4}));
        assertNull(PdfWriter.jpegSize(null));
    }

    @Test
    public void buildsMultiPagePdf() throws IOException {
        PdfWriter w = new PdfWriter();
        w.addJpeg(fakeJpeg(800, 1000));
        w.addJpeg(fakeJpeg(1000, 800));
        assertEquals(2, w.pageCount());

        byte[] pdf = w.finish();
        assertEquals("%PDF-1.4", new String(pdf, 0, 8, StandardCharsets.US_ASCII));
        assertEquals("%%EOF\n", new String(pdf, pdf.length - 6, 6, StandardCharsets.US_ASCII));

        String all = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(all.contains("/Count 2"));
        assertTrue(all.contains("/Subtype /Image"));
        assertTrue(all.contains("/Filter /DCTDecode"));
        assertTrue(all.contains("startxref"));
        // 两页图片的尺寸都要如实写进去
        assertTrue(all.contains("/Width 800 /Height 1000"));
        assertTrue(all.contains("/Width 1000 /Height 800"));
    }

    @Test
    public void emptyPdfThrows() {
        try {
            new PdfWriter().finish();
            fail("没有页面时应该抛异常");
        } catch (IOException expected) {
            // 预期
        }
    }

    @Test
    public void badJpegIsRejected() {
        try {
            new PdfWriter().addJpeg(new byte[]{0, 0, 0, 0});
            fail("非法数据应该被拒绝");
        } catch (IOException expected) {
            // 预期
        }
    }
}
package com.ice.scan.img;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一个只干一件事的 PDF 生成器：把若干张 JPEG 拼成多页 PDF。
 *
 * <p>不引第三方库的原因很实在——PDF 里放 JPEG 根本不需要重编码：PDF 的
 * {@code /DCTDecode} 过滤器就是 JPEG，直接把字节贴进流里即可。所以这里
 * 只做「拼容器 + 写交叉引用表」，整个类不到 200 行，纯算术，能直接在 JVM 上跑测试。
 *
 * <p>页面统一按 A4 排版：图片等比缩放后居中，长边贴住 A4 的长边。
 */
public final class PdfWriter {

    /** A4，单位是 PDF 的「点」（1 点 = 1/72 英寸）。 */
    private static final double A4_W = 595.28;
    private static final double A4_H = 841.89;

    private final List<byte[]> pages = new ArrayList<>();
    private final List<int[]> sizes = new ArrayList<>();

    /**
     * 追加一页。
     *
     * @param jpeg 完整的 JPEG 字节（含 SOI/EOI）
     * @throws IOException 数据不是合法 JPEG 时抛出
     */
    public void addJpeg(byte[] jpeg) throws IOException {
        int[] size = jpegSize(jpeg);
        if (size == null) throw new IOException("不是有效的 JPEG 数据");
        pages.add(jpeg);
        sizes.add(size);
    }

    public int pageCount() {
        return pages.size();
    }

    /** 生成完整 PDF 字节。 */
    public byte[] finish() throws IOException {
        int n = pages.size();
        if (n == 0) throw new IOException("没有可导出的页面");

        // 对象编号：1 = 目录，2 = 页树，之后每页占 3 个（页 / 内容流 / 图像）
        int lastObj = 3 * n + 2;
        int[] offset = new int[lastObj + 1];

        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);

        // 规范要求文件头必须是第一行；不少阅读器靠它判断「这是不是 PDF」
        ascii(out, "%PDF-1.4\n");

        offset[1] = out.size();
        ascii(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(3 + 3 * i).append(" 0 R ");
        offset[2] = out.size();
        ascii(out, "2 0 obj\n<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + n + " >>\nendobj\n");

        for (int i = 0; i < n; i++) {
            int pageObj = 3 + 3 * i;
            int contentObj = pageObj + 1;
            int imageObj = pageObj + 2;
            int[] size = sizes.get(i);

            double scale = Math.min(A4_W / size[0], A4_H / size[1]);
            double drawW = size[0] * scale;
            double drawH = size[1] * scale;
            double dx = (A4_W - drawW) / 2;
            double dy = (A4_H - drawH) / 2;

            offset[pageObj] = out.size();
            ascii(out, pageObj + " 0 obj\n"
                    + "<< /Type /Page /Parent 2 0 R "
                    + "/MediaBox [0 0 " + fmt(A4_W) + " " + fmt(A4_H) + "] "
                    + "/Resources << /XObject << /Im0 " + imageObj + " 0 R >> >> "
                    + "/Contents " + contentObj + " 0 R >>\nendobj\n");

            // 图片的坐标原点是左上、单位正方形；这条 cm 把它铺到「宽 drawW、高 drawH、左下在 (dx,dy)」
            String content = "q " + fmt(drawW) + " 0 0 " + fmt(drawH) + " "
                    + fmt(dx) + " " + fmt(dy) + " cm /Im0 Do Q\n";
            byte[] cb = content.getBytes(StandardCharsets.US_ASCII);
            offset[contentObj] = out.size();
            ascii(out, contentObj + " 0 obj\n<< /Length " + cb.length + " >>\nstream\n");
            out.write(cb, 0, cb.length);
            ascii(out, "endstream\nendobj\n");

            byte[] jpeg = pages.get(i);
            offset[imageObj] = out.size();
            ascii(out, imageObj + " 0 obj\n"
                    + "<< /Type /XObject /Subtype /Image "
                    + "/Width " + size[0] + " /Height " + size[1] + " "
                    + "/ColorSpace /DeviceRGB /BitsPerComponent 8 "
                    + "/Filter /DCTDecode /Length " + jpeg.length + " >>\nstream\n");
            out.write(jpeg, 0, jpeg.length);
            ascii(out, "\nendstream\nendobj\n");
        }

        int xrefAt = out.size();
        int count = lastObj + 1;
        ascii(out, "xref\n0 " + count + "\n");
        ascii(out, "0000000000 65535 f \n");
        for (int i = 1; i <= lastObj; i++) {
            ascii(out, String.format(Locale.US, "%010d 00000 n \n", offset[i]));
        }
        ascii(out, "trailer\n<< /Size " + count + " /Root 1 0 R >>\nstartxref\n" + xrefAt + "\n%%EOF\n");

        return out.toByteArray();
    }

    /**
     * 从 JPEG 字节里读出宽高：扫到 SOF 段（0xC0–0xCF，排除 DHT/JPG/DAC）即可。
     * 自己解析而不是信任外部传入的尺寸，是为了让「元数据」和「真实像素」永远一致。
     */
    public static int[] jpegSize(byte[] b) {
        if (b == null || b.length < 4) return null;
        if ((b[0] & 0xff) != 0xFF || (b[1] & 0xff) != 0xD8) return null;

        int i = 2;
        while (i + 3 < b.length) {
            if ((b[i] & 0xff) != 0xFF) {
                i++;
                continue;
            }
            int marker = b[i + 1] & 0xff;
            if (marker == 0xFF) {          // 填充字节
                i++;
                continue;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD9)) {  // 无长度字段
                i += 2;
                continue;
            }
            int len = ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
            if (len < 2) return null;
            boolean sof = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                if (i + 8 >= b.length) return null;
                int h = ((b[i + 5] & 0xff) << 8) | (b[i + 6] & 0xff);
                int w = ((b[i + 7] & 0xff) << 8) | (b[i + 8] & 0xff);
                if (w <= 0 || h <= 0) return null;
                return new int[]{w, h};
            }
            if (marker == 0xDA) break;     // 已经进入压缩数据
            i += 2 + len;
        }
        return null;
    }

    private static String fmt(double v) {
        String s = String.format(Locale.US, "%.2f", v);
        // 去掉多余的 ".00"，PDF 看起来干净些
        if (s.endsWith(".00")) s = s.substring(0, s.length() - 3);
        else if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static void ascii(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }
}
package com.ice.scan.scan;

import com.ice.scan.data.Doc;
import com.ice.scan.data.DocStore;
import com.ice.scan.data.Page;
import com.ice.scan.img.AutoCrop;
import com.ice.scan.img.Enhance;
import com.ice.scan.img.Perspective;
import com.ice.scan.img.Quad;
import com.ice.scan.util.Bitmaps;

import android.graphics.Bitmap;

import java.io.File;
import java.io.IOException;

/**
 * 扫描流水线：**自动找边 → 透视拉直 → 增强 → 写 JPEG → 登记到文档**。
 *
 * <p>拍照页和批量导入页走的是同一条流水线，区别只是角点从哪来：
 * 拍照那条是用户在 {@code QuadView} 里拖出来的，导入那条是 {@link AutoCrop} 猜的。
 */
public final class Pipeline {

    /** JPEG 质量。85 是「文字边缘不糊」和「文件别太大」的平衡点。 */
    public static final int JPEG_QUALITY = 85;
    /** 自动找边吃进去的分辨率长边。再小会丢纸边细节，再大只是白费时间。 */
    public static final int DETECT_SIDE = 420;
    /** 效果预览小图的长边。 */
    public static final int PREVIEW_SIDE = 240;

    private Pipeline() {
    }

    /**
     * 按给定角点处理一页并落盘。
     *
     * @param normQuad 归一化角点
     * @param maxSide  输出长边上限
     */
    public static Page render(DocStore store, Doc doc, int[] srcPx, int w, int h,
                              float[] normQuad, int mode, int maxSide) throws IOException {
        float[] q = Quad.toPixels(normQuad, w, h);
        int[] size = Perspective.outputSize(q, maxSide);
        int[] warped = Perspective.warp(srcPx, w, h, q, size[0], size[1]);
        int[] done = Enhance.enhance(warped, size[0], size[1], mode);

        Bitmap out = Bitmaps.fromArgb(done, size[0], size[1]);
        try {
            File f = store.newPageFile(doc);
            if (!Bitmaps.writeJpeg(out, f, JPEG_QUALITY)) throw new IOException("写入图片失败");
            Page page = new Page(f.getName(), mode, out.getWidth(), out.getHeight());
            store.appendPage(doc, page.file, page.mode, page.width, page.height);
            return page;
        } finally {
            out.recycle();
        }
    }

    /** 全自动：自己找边，然后交给 {@link #render}。批量导入走这条。 */
    public static Page autoAdd(DocStore store, Doc doc, Bitmap src, int mode) throws IOException {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = Bitmaps.pixels(src);
        float[] norm = AutoCrop.detectNormalized(px, w, h, DETECT_SIDE);
        return render(store, doc, px, w, h, norm, mode, Bitmaps.processingSide());
    }

    /**
     * 只出效果预览图，不落盘。用几百像素的小图跑，拖角时能实时刷新。
     * 返回的 Bitmap 由调用方负责回收。
     */
    public static Bitmap preview(int[] smallPx, int w, int h, float[] normQuad, int mode, int maxSide) {
        float[] q = Quad.toPixels(normQuad, w, h);
        int[] size = Perspective.outputSize(q, maxSide);
        int[] warped = Perspective.warp(smallPx, w, h, q, size[0], size[1]);
        int[] done = Enhance.enhance(warped, size[0], size[1], mode);
        return Bitmaps.fromArgb(done, size[0], size[1]);
    }
}
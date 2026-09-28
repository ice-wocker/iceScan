package com.ice.scan.util;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 位图解码/编码。
 *
 * <p>所有解码都走「按长边限制」这一条路：手机随手一拍就是 4000×3000，直接整张读进
 * 内存是 48MB，中端机当场 OOM。这里统一在解码阶段就按 2 的幂次降采样，
 * 让最大那张图也只在十几 MB 量级，而且全程只分配一次（不做二次缩放）。
 */
public final class Bitmaps {

    /** 自动找边、效果预览用的长边。算法对分辨率不敏感，小图快得多。 */
    public static final int SMALL_SIDE = 480;
    /** 列表缩略图。 */
    public static final int THUMB_SIDE = 240;

    private Bitmaps() {
    }

    /**
     * 处理/导出分辨率的长边，**按堆上限自适应**。
     *
     * <p>扫描流水线里同时活着好几个「每像素 4 字节」的整型数组（灰度图、背景、积分图…），
     * 峰值内存大致是 {@code (长边 × 短边 × 4) × 十来份}。所以分辨率不能写死：
     * 旗舰机上直接吃 2200，A4 合 168 DPI；小内存机器退到 1400，
     * 宁可锐度低一点，也不能识别到一半 OOM 崩掉。
     */
    public static int processingSide() {
        long heap = Runtime.getRuntime().maxMemory();
        if (heap >= (384L << 20)) return 2200;
        if (heap >= (192L << 20)) return 1800;
        return 1400;
    }

    public static Bitmap decodeFile(File f, int maxSide) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) throw new IOException("不是有效的图片");

        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), opts(w, h, maxSide));
        if (b == null) throw new IOException("解码失败");
        return applyExif(readExif(f), b);
    }

    public static Bitmap decodeUri(ContentResolver cr, Uri uri, int maxSide) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("无法打开图片");
        try {
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            close(in);
        }
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) throw new IOException("不是有效的图片");

        // 有些 provider 的流不能重复读，所以每次都重新开一次
        Bitmap b;
        InputStream in2 = cr.openInputStream(uri);
        if (in2 == null) throw new IOException("无法打开图片");
        try {
            b = BitmapFactory.decodeStream(in2, null, opts(w, h, maxSide));
        } finally {
            close(in2);
        }
        if (b == null) throw new IOException("解码失败");

        int rotation = 0;
        InputStream in3 = cr.openInputStream(uri);
        if (in3 != null) {
            try {
                rotation = exifRotation(new ExifInterface(in3));
            } catch (IOException ignored) {
                // 没有 EXIF 很正常，当作不用旋转
            } finally {
                close(in3);
            }
        }
        return rotate(b, rotation);
    }

    /** 把内容 URI 的内容原样拷到文件。 */
    public static void copyToFile(ContentResolver cr, Uri uri, File dst) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("无法打开图片");
        try (InputStream src = in; FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = src.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
    }

    public static int[] pixels(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        return px;
    }

    public static Bitmap fromArgb(int[] px, int w, int h) {
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** 写出 JPEG。返回是否成功。 */
    public static boolean writeJpeg(Bitmap b, File dst, int quality) {
        File dir = dst.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
        try (FileOutputStream out = new FileOutputStream(dst)) {
            if (!b.compress(Bitmap.CompressFormat.JPEG, quality, out)) return false;
            out.flush();
            return true;
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            return false;
        }
    }

    public static byte[] toJpeg(Bitmap b, int quality) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(1 << 16);
        b.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        return bos.toByteArray();
    }

    /** 按长边上限等比缩放。已经够小就原样返回。 */
    public static Bitmap scaleTo(Bitmap src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxSide) return src;
        float s = (float) maxSide / longest;
        Bitmap out = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
        if (out != src) src.recycle();
        return out;
    }

    public static Bitmap rotate(Bitmap b, int degrees) {
        if (degrees == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap out = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (out != b) b.recycle();
        return out;
    }

    /** 2 的幂次降采样：保证解码后的长边落在 (maxSide/2, maxSide] 区间，只分配一次。 */
    static int sampleSize(int w, int h, int maxSide) {
        int longest = Math.max(w, h);
        int s = 1;
        while (longest / s > maxSide && s < 64) s *= 2;
        return s;
    }

    private static BitmapFactory.Options opts(int w, int h, int maxSide) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleSize(w, h, maxSide);
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return o;
    }

    private static ExifInterface readExif(File f) {
        try {
            return new ExifInterface(f.getAbsolutePath());
        } catch (IOException e) {
            return null;
        }
    }

    private static int exifRotation(ExifInterface exif) {
        if (exif == null) return 0;
        int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        switch (o) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return 270;
            default:
                return 0;
        }
    }

    private static Bitmap applyExif(ExifInterface exif, Bitmap b) {
        return rotate(b, exifRotation(exif));
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 关不掉没有挽救余地
        }
    }
}
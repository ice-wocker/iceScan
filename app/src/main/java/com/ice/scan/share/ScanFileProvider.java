package com.ice.scan.share;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * 一个极简的「文件出口」——手写版 FileProvider，作用只有两个：
 *
 * <ol>
 *   <li>把拍好的照片给系统相机 App 写进来（{@code capture/}，缓存目录）</li>
 *   <li>把导出的 PDF 交给别的 App 打开/分享（{@code share/}，私有目录）</li>
 * </ol>
 *
 * <p>两条路都不需要任何权限：前者是「你授权了这一个 URI」，后者是「我们主动把文件递出去」。
 * 之所以不直接用 {@code androidx.core.content.FileProvider}，是因为整个 App 不引 AndroidX，
 * 而这件事本身只有几十行。
 *
 * <p>安全上有两道锁：文件名只允许 {@code [A-Za-z0-9._-]}（挡住 {@code ../} 穿越），
 * 以及 provider 声明为 {@code exported="false"}（没被授权的 App 连不上来）。
 */
public class ScanFileProvider extends ContentProvider {

    /** 相机写入照片的目录（在 cache 下，随用随清）。 */
    public static final String KIND_CAPTURE = "capture";
    /** 对外分享文件的目录（在 files 下）。 */
    public static final String KIND_SHARE = "share";

    private static final String AUTHORITY_SUFFIX = ".files";

    /**
     * 拼出这个 provider 下的一个 content URI。
     *
     * <p>authority 用包名拼：本项目没有 applicationIdSuffix / flavor，包名就是 applicationId，
     * 跟清单里的 {@code ${applicationId}.files} 一定一致。
     */
    public static Uri uriFor(Context ctx, String kind, String name) {
        return new Uri.Builder()
                .scheme("content")
                .authority(ctx.getPackageName() + AUTHORITY_SUFFIX)
                .appendPath(kind)
                .appendPath(name)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        boolean write = mode != null && mode.contains("w");
        int flags = write
                ? ParcelFileDescriptor.MODE_CREATE
                | ParcelFileDescriptor.MODE_TRUNCATE
                | ParcelFileDescriptor.MODE_READ_WRITE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        try {
            return ParcelFileDescriptor.open(f, flags);
        } catch (IOException e) {
            throw new FileNotFoundException("打不开 " + uri + ": " + e.getMessage());
        }
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return "application/octet-stream";
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    /** 只提供「按路径取文件」，不支持查询。 */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() != 2) {
            throw new FileNotFoundException("路径不合法: " + uri);
        }
        String kind = segments.get(0);
        String name = segments.get(1);
        if (!safeName(name)) {
            throw new FileNotFoundException("文件名不合法: " + name);
        }

        Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("provider 还没初始化");

        File dir;
        if (KIND_CAPTURE.equals(kind)) {
            dir = new File(ctx.getCacheDir(), KIND_CAPTURE);
        } else if (KIND_SHARE.equals(kind)) {
            dir = new File(ctx.getFilesDir(), KIND_SHARE);
        } else {
            throw new FileNotFoundException("未知的目录类型: " + kind);
        }
        if (!dir.exists() && !dir.mkdirs()) throw new FileNotFoundException("无法创建目录: " + dir);
        return new File(dir, name);
    }

    /**
     * 只禁掉「路径分隔符」和「控制字符」，其余（中文、空格、括号）都放行。
     * 因为 {@link File#File(File, String)} 只会在有分隔符时才能跳出目录，
     * 把 '/' 和 '\' 挡掉之后，{@code ../} 这类穿越就不可能成立了。
     */
    private static boolean safeName(String name) {
        if (name == null || name.isEmpty() || name.length() > 120) return false;
        if (".".equals(name) || "..".equals(name)) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c < 0x20) return false;
        }
        return true;
    }
}
package com.ice.scan.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 文件读写的小工具。纯 java.io，没有 Android 依赖，方便直接测。 */
public final class Storage {

    private Storage() {
    }

    /** 递归删除目录/文件；不存在也算成功。 */
    public static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursively(k);
            }
        }
        // 删不掉就算了（比如被占用），不让它把整个流程打断
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 原子写文本：先写 .tmp 再改名。
     * 手机随时可能被杀进程，直接盖写有概率留下半截文件，改名这一步是原子的就能兜住。
     */
    public static void writeText(File f, String text) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录: " + dir);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            // 少数文件系统不支持覆盖式改名，退一步：直接写目标
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** 把字节写成文件。 */
    public static void writeBytes(File f, byte[] data) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录: " + dir);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
            out.flush();
        }
    }

    public static byte[] readBytes(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, (int) f.length()));
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024);
        return String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }
}
package com.ice.scan.data;

import android.content.Context;

import com.ice.scan.util.Storage;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 文档仓库：每份扫描件是 {@code docs/<id>/} 下的一堆 JPEG，外加一个 {@code doc.txt}。
 *
 * <p>除了 {@link #open} 之外全都不碰 Android API（只吃一个 {@code File} 作为根目录），
 * 所以单元测试可以直接指向临时目录跑，不用起模拟器。
 */
public final class DocStore {

    private final File root;

    public DocStore(File root) {
        this.root = root;
    }

    /** 正式入口：文档放进应用私有目录，别的 App 看不到，卸载即清空。 */
    public static DocStore open(Context ctx) {
        return new DocStore(new File(ctx.getFilesDir(), "docs"));
    }

    public File root() {
        return root;
    }

    public File dirOf(String id) {
        return new File(root, id);
    }

    public File pageFile(Doc doc, Page page) {
        return new File(dirOf(doc.id), page.file);
    }

    public File metaFile(String id) {
        return new File(dirOf(id), Doc.META);
    }

    /** 全部文档，按时间倒序（最近扫的在最上面）。 */
    public List<Doc> list() {
        List<Doc> out = new ArrayList<>();
        File[] dirs = root.listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            Doc doc = load(d.getName());
            if (doc != null) out.add(doc);
        }
        Collections.sort(out, new Comparator<Doc>() {
            @Override
            public int compare(Doc a, Doc b) {
                return Long.compare(b.created, a.created);
            }
        });
        return out;
    }

    /** 读一份文档；目录或元数据坏了返回 null。 */
    public Doc load(String id) {
        File f = metaFile(id);
        if (!f.isFile()) return null;
        try {
            return Doc.fromText(id, Storage.readText(f));
        } catch (IOException e) {
            return null;
        }
    }

    /** 新建文档并立刻落盘，这样中途被杀进程也不会留下半成品。 */
    public Doc create(String baseName) {
        String name = uniqueName(baseName == null || baseName.trim().isEmpty() ? "扫描件" : baseName.trim());
        long now = System.currentTimeMillis();
        String id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(now));
        File dir = new File(root, id);
        int n = 1;
        while (dir.exists()) {
            id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(now)) + "-" + (n++);
            dir = new File(root, id);
        }
        Doc doc = new Doc(id, name, now);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try {
            save(doc);
        } catch (IOException ignored) {
            // 落盘失败也把内存对象给出去，后面 addPage 还会再写一次
        }
        return doc;
    }

    public void save(Doc doc) throws IOException {
        Storage.writeText(metaFile(doc.id), doc.toText());
    }

    public void delete(String id) {
        Storage.deleteRecursively(dirOf(id));
    }

    /** 给新页面找一个不重名的文件名。 */
    public File newPageFile(Doc doc) {
        File dir = dirOf(doc.id);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        int i = doc.pages.size();
        File f;
        do {
            f = new File(dir, "p" + (i++) + ".jpg");
        } while (f.exists());
        return f;
    }

    /** 把已经写好的图片登记进元数据。 */
    public void appendPage(Doc doc, String fileName, int mode, int width, int height) throws IOException {
        doc.pages.add(new Page(fileName, mode, width, height));
        save(doc);
    }

    /** 删掉第 index 页：先改元数据再删文件，避免元数据指向一个已经不在的文件。 */
    public void deletePage(Doc doc, int index) throws IOException {
        if (index < 0 || index >= doc.pages.size()) return;
        Page p = doc.pages.remove(index);
        save(doc);
        //noinspection ResultOfMethodCallIgnored
        pageFile(doc, p).delete();
    }

    /** 重命名。 */
    public void rename(Doc doc, String newName) throws IOException {
        String n = Doc.sanitize(newName);
        doc.name = n.isEmpty() ? doc.name : n;
        save(doc);
    }

    /** 已有的名字后面加序号，避免列表里出现两个「扫描件」。 */
    private String uniqueName(String base) {
        List<Doc> all = list();
        boolean used = false;
        for (Doc d : all) {
            if (d.name.equals(base)) {
                used = true;
                break;
            }
        }
        if (!used) return base;

        for (int i = 2; i < 1000; i++) {
            String candidate = base + " " + i;
            boolean taken = false;
            for (Doc d : all) {
                if (d.name.equals(candidate)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) return candidate;
        }
        return base + " " + System.currentTimeMillis();
    }
}
package com.ice.scan.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一份扫描件（可以有多页）。
 *
 * <p>元数据用一行一条的纯文本存，而不是 JSON：不依赖 {@code org.json}，
 * 于是解析/序列化能在普通 JVM 单元测试里跑，也省得为一个文件引库。
 * 格式：
 * <pre>
 * iceScan/1
 * name=房租合同
 * created=1730000000000
 * p=p0.jpg|1|1600|1200
 * </pre>
 */
public final class Doc {

    /** 元数据文件名，放在文档自己的目录下。 */
    public static final String META = "doc.txt";
    private static final String HEADER = "iceScan/1";

    public final String id;
    public String name;
    public long created;
    public final List<Page> pages = new ArrayList<>();

    public Doc(String id, String name, long created) {
        this.id = id;
        this.name = name;
        this.created = created;
    }

    /** 目录里第一页的文件名；没有页面时返回 null。 */
    public String coverFile() {
        return pages.isEmpty() ? null : pages.get(0).file;
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        sb.append("name=").append(sanitize(name)).append('\n');
        sb.append("created=").append(created).append('\n');
        for (Page p : pages) {
            sb.append("p=").append(p.file).append('|').append(p.mode)
                    .append('|').append(p.width).append('|').append(p.height).append('\n');
        }
        return sb.toString();
    }

    /** 解析失败（文件被截断、手工改坏了）时返回 null，让上层跳过这条而不是崩掉。 */
    public static Doc fromText(String id, String text) {
        if (text == null) return null;
        String[] lines = text.split("\n");
        if (lines.length == 0 || !HEADER.equals(lines[0].trim())) return null;

        String name = null;
        long created = 0;
        List<Page> pages = new ArrayList<>();

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);

            if ("name".equals(key)) {
                name = value;
            } else if ("created".equals(key)) {
                try {
                    created = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                    created = 0;
                }
            } else if ("p".equals(key)) {
                Page p = parsePage(value);
                if (p != null) pages.add(p);
            }
        }
        if (name == null) name = "";
        Doc doc = new Doc(id, name, created);
        doc.pages.addAll(pages);
        return doc;
    }

    private static Page parsePage(String value) {
        String[] f = value.split("\\|");
        if (f.length < 4) return null;
        try {
            return new Page(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 名字里不能带换行，否则元数据文件就散架了。 */
    static String sanitize(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
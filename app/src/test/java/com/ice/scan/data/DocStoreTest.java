package com.ice.scan.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.ice.scan.util.Storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 文档仓库：元数据的读写、页面增删、重名处理、坏数据容错。
 * 全部指向临时目录，不碰 Android。
 */
public class DocStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private DocStore store() throws IOException {
        return new DocStore(tmp.newFolder("docs"));
    }

    @Test
    public void createThenReload() throws IOException {
        DocStore s = store();
        Doc d = s.create("房租合同");
        assertEquals("房租合同", d.name);
        assertTrue(s.dirOf(d.id).isDirectory());

        Doc again = s.load(d.id);
        assertNotNull(again);
        assertEquals(d.id, again.id);
        assertEquals("房租合同", again.name);
    }

    @Test
    public void appendKeepsPageOrder() throws IOException {
        DocStore s = store();
        Doc d = s.create("扫描件");

        File f1 = s.newPageFile(d);
        Storage.writeBytes(f1, new byte[]{1});
        s.appendPage(d, f1.getName(), 1, 100, 200);

        File f2 = s.newPageFile(d);
        Storage.writeBytes(f2, new byte[]{2});
        s.appendPage(d, f2.getName(), 2, 200, 100);

        Doc loaded = s.load(d.id);
        assertEquals(2, loaded.pages.size());
        assertEquals("p0.jpg", loaded.pages.get(0).file);
        assertEquals("p1.jpg", loaded.pages.get(1).file);
        assertEquals(1, loaded.pages.get(0).mode);
        assertEquals(2, loaded.pages.get(1).mode);
    }

    @Test
    public void deletePageRemovesFileAndMeta() throws IOException {
        DocStore s = store();
        Doc d = s.create("x");
        File f = s.newPageFile(d);
        Storage.writeBytes(f, new byte[]{1});
        s.appendPage(d, f.getName(), 0, 10, 10);
        assertTrue(f.exists());

        s.deletePage(d, 0);
        assertFalse(f.exists());
        assertTrue(s.load(d.id).pages.isEmpty());
    }

    @Test
    public void duplicateNamesGetSuffix() throws IOException {
        DocStore s = store();
        s.create("扫描件");
        Doc second = s.create("扫描件");
        assertEquals("扫描件 2", second.name);
    }

    @Test
    public void listIsNewestFirst() throws IOException {
        DocStore s = store();
        Doc a = s.create("a");
        a.created = 1000L;
        s.save(a);
        Doc b = s.create("b");
        b.created = 2000L;
        s.save(b);

        List<Doc> all = s.list();
        assertEquals(2, all.size());
        assertEquals("b", all.get(0).name);
        assertEquals("a", all.get(1).name);
    }

    @Test
    public void renameThenDelete() throws IOException {
        DocStore s = store();
        Doc d = s.create("旧名");
        s.rename(d, "新名");
        assertEquals("新名", s.load(d.id).name);

        s.delete(d.id);
        assertNull(s.load(d.id));
    }

    @Test
    public void corruptedMetaIsSkipped() throws IOException {
        DocStore s = store();
        Doc d = s.create("ok");
        Storage.writeText(s.metaFile(d.id), "这不是元数据");
        assertNull(s.load(d.id));
        assertTrue(s.list().isEmpty());
    }

    @Test
    public void docTextRoundTrip() {
        Doc d = new Doc("20240101-120000", "合同", 1700000000000L);
        d.pages.add(new Page("p0.jpg", 1, 1600, 1200));
        d.pages.add(new Page("p1.jpg", 3, 800, 600));

        Doc parsed = Doc.fromText(d.id, d.toText());
        assertNotNull(parsed);
        assertEquals(d.name, parsed.name);
        assertEquals(d.created, parsed.created);
        assertEquals(2, parsed.pages.size());
        assertEquals("p1.jpg", parsed.pages.get(1).file);
        assertEquals(3, parsed.pages.get(1).mode);
    }

    @Test
    public void docNameNewlinesAreStripped() {
        Doc d = new Doc("id", "第一行\n第二行", 0L);
        String text = d.toText();
        // 名字里的换行必须被清掉，否则元数据文件会散架
        assertEquals(3, text.split("\n").length);
        Doc parsed = Doc.fromText("id", text);
        assertNotNull(parsed);
        assertEquals("第一行 第二行", parsed.name);
    }
}
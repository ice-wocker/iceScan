package com.ice.scan.ui;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ice.scan.R;
import com.ice.scan.data.Doc;
import com.ice.scan.data.DocStore;
import com.ice.scan.data.Page;
import com.ice.scan.img.PdfWriter;
import com.ice.scan.share.ScanFileProvider;
import com.ice.scan.util.Bitmaps;
import com.ice.scan.util.Storage;

import java.io.File;
import java.io.IOException;

/**
 * 文档页：纵向翻看每一页，加页、删页、重命名，最后合成一个 PDF 分享出去。
 *
 * <p>PDF 是「导出」而不是「保存」：每次点导出都重新拼一遍，页面删了、加了立刻就能反映到
 * 文件里，不用去维护「PDF 和页面是否同步」这件事。
 */
public class ViewerActivity extends BaseActivity {

    /** 要查看的文档 id。 */
    public static final String EXTRA_DOC_ID = "doc_id";

    private static final String TAG = "ViewerActivity";

    /** 页面大图的长边。再大只是浪费内存，屏幕上也看不出差别。 */
    private static final int PAGE_SIDE = 1000;

    private DocStore store;
    private ThumbLoader thumbs;

    private String docId;
    private Doc doc;

    private TextView tvName;
    private TextView tvCount;
    private LinearLayout pages;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        bindMask();

        store = DocStore.open(this);
        // 大图缓存最多吃 1/8 堆；其余留给合成 PDF 时的字节数组
        int cacheBytes = (int) Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);
        thumbs = new ThumbLoader(cacheBytes);

        docId = getIntent().getStringExtra(EXTRA_DOC_ID);
        if (docId == null) {
            finish();
            return;
        }

        tvName = findViewById(R.id.tv_name);
        tvCount = findViewById(R.id.tv_count);
        pages = findViewById(R.id.pages);
        empty = findViewById(R.id.empty);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_add).setOnClickListener(v -> addPage());
        findViewById(R.id.btn_pdf).setOnClickListener(v -> exportPdf());
        findViewById(R.id.btn_more).setOnClickListener(v -> showMore());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「加一页」回来后页面数会变，统一在这里重读
        reload();
    }

    @Override
    protected void onDestroy() {
        thumbs.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 渲染

    private void reload() {
        io.execute(() -> {
            final Doc fresh = docId == null ? null : store.load(docId);
            post(() -> {
                if (fresh == null) {
                    finish();
                    return;
                }
                doc = fresh;
                render();
            });
        });
    }

    private void render() {
        if (doc == null) return;
        tvName.setText(doc.name);
        tvCount.setText(getString(R.string.viewer_pages, doc.pages.size()));

        pages.removeAllViews();
        boolean blank = doc.pages.isEmpty();
        empty.setVisibility(blank ? View.VISIBLE : View.GONE);
        if (blank) return;

        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < doc.pages.size(); i++) {
            final Page p = doc.pages.get(i);
            View item = inflater.inflate(R.layout.item_page, pages, false);

            ((TextView) item.findViewById(R.id.tv_label))
                    .setText(getString(R.string.viewer_page_label, i + 1));

            ImageView img = item.findViewById(R.id.img_page);
            thumbs.load(img, store.pageFile(doc, p), PAGE_SIDE);

            final int shown = i + 1;
            item.findViewById(R.id.btn_del).setOnClickListener(v -> confirmDeletePage(p, shown));
            pages.addView(item);
        }
    }

    // ---------------------------------------------------------------- 加页 / 删页

    private void addPage() {
        if (doc == null) return;
        Intent i = new Intent(this, CaptureActivity.class);
        i.putExtra(CaptureActivity.EXTRA_DOC_ID, doc.id);
        startActivity(i);
    }

    private void confirmDeletePage(final Page page, int shown) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.viewer_delete_page_title, shown))
                .setMessage(R.string.viewer_delete_page_msg)
                .setPositiveButton(R.string.delete, (dialog, which) -> io.execute(() -> {
                    int index = indexOfPage(page.file);
                    if (index >= 0) {
                        try {
                            store.deletePage(doc, index);
                        } catch (IOException e) {
                            Log.w(TAG, "删除页面失败", e);
                        }
                    }
                    post(this::reload);
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 用文件名找页码，而不是信任点击那一刻的索引——期间页面顺序可能已经变了。 */
    private int indexOfPage(String file) {
        for (int i = 0; i < doc.pages.size(); i++) {
            if (doc.pages.get(i).file.equals(file)) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- 更多

    private void showMore() {
        if (doc == null) return;
        String[] actions = {
                getString(R.string.viewer_share),
                getString(R.string.viewer_rename),
                getString(R.string.viewer_delete_doc)};
        new AlertDialog.Builder(this)
                .setTitle(doc.name)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) exportPdf();
                    else if (which == 1) promptRename();
                    else confirmDeleteDoc();
                })
                .show();
    }

    private void promptRename() {
        View content = getLayoutInflater().inflate(R.layout.dialog_rename, null);
        final EditText edit = content.findViewById(R.id.edit_name);
        edit.setText(doc.name);
        edit.setSelection(edit.getText().length());

        new AlertDialog.Builder(this)
                .setView(content)
                .setPositiveButton(R.string.rename_save, (dialog, which) -> {
                    final String name = edit.getText().toString().trim();
                    if (name.isEmpty()) return;
                    io.execute(() -> {
                        try {
                            store.rename(doc, name);
                        } catch (IOException e) {
                            Log.w(TAG, "重命名失败", e);
                        }
                        post(this::reload);
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDeleteDoc() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.viewer_delete_doc_title)
                .setMessage(getString(R.string.viewer_delete_doc_msg, doc.name))
                .setPositiveButton(R.string.delete, (dialog, which) -> io.execute(() -> {
                    store.delete(doc.id);
                    post(this::finish);
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- 导出 PDF

    private void exportPdf() {
        if (doc == null) return;
        if (doc.pages.isEmpty()) {
            toast(R.string.viewer_empty);
            return;
        }
        final Doc target = doc;
        showMask(R.string.export_running);
        io.execute(() -> {
            final File pdf;
            try {
                PdfWriter writer = new PdfWriter();
                for (Page p : target.pages) {
                    writer.addJpeg(Storage.readBytes(store.pageFile(target, p)));
                }
                File dir = new File(getFilesDir(), ScanFileProvider.KIND_SHARE);
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建导出目录");
                pdf = uniquePdf(dir, target.name);
                Storage.writeBytes(pdf, writer.finish());
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "导出 PDF 失败", e);
                post(() -> {
                    hideMask();
                    toast(getString(R.string.export_failed, e.getMessage()));
                });
                return;
            }
            post(() -> {
                hideMask();
                toast(R.string.export_done);
                share(pdf, target.name);
            });
        });
    }

    private void share(File pdf, String title) {
        Uri uri = ScanFileProvider.uriFor(this, ScanFileProvider.KIND_SHARE, pdf.getName());
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/pdf");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, title);
        // 只把「读这个 URI」的权利借给接收方，而不是开放整个目录
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.export_share_title)));
        } catch (ActivityNotFoundException e) {
            toast(R.string.export_no_app);
        }
    }

    /** 文件名里只留合法字符（provider 那一侧会再挡一次路径分隔符）。 */
    private static String pdfFileName(String raw) {
        StringBuilder sb = new StringBuilder();
        if (raw != null) {
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '/' || c == '\\' || c < 0x20) continue;
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) s = "扫描件";
        return s + ".pdf";
    }

    /** 同名文件加序号，避免覆盖上一次导出的结果。 */
    private static File uniquePdf(File dir, String raw) {
        String base = pdfFileName(raw);
        File f = new File(dir, base);
        if (!f.exists()) return f;
        String stem = base.substring(0, base.length() - ".pdf".length());
        for (int i = 2; i < 1000; i++) {
            File next = new File(dir, stem + " (" + i + ").pdf");
            if (!next.exists()) return next;
        }
        return new File(dir, stem + " " + System.currentTimeMillis() + ".pdf");
    }
}
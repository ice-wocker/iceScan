package com.ice.scan.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.ice.scan.R;
import com.ice.scan.data.Doc;
import com.ice.scan.data.DocStore;
import com.ice.scan.img.Enhance;
import com.ice.scan.scan.Pipeline;
import com.ice.scan.util.Bitmaps;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 首页：文档列表 + 两个入口（扫描 / 导入）。
 */
public class MainActivity extends BaseActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_IMPORT = 101;

    private DocStore store;
    private ThumbLoader thumbs;
    private ListView list;
    private View empty;
    private DocAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindMask();

        store = DocStore.open(this);
        // 缩略图缓存最多吃 1/8 堆内存，剩下的留给真正解码大图的那一步
        int cacheBytes = (int) Math.min(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);
        thumbs = new ThumbLoader(cacheBytes);

        list = findViewById(R.id.list_docs);
        empty = findViewById(R.id.empty);
        adapter = new DocAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> openViewer(adapter.getItem(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showDocMenu(adapter.getItem(position));
            return true;
        });

        findViewById(R.id.btn_scan).setOnClickListener(v ->
                startActivity(new Intent(this, CaptureActivity.class)));
        findViewById(R.id.btn_import).setOnClickListener(v -> pickImages());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        thumbs.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 列表

    private void refresh() {
        io.execute(() -> {
            final List<Doc> docs = store.list();
            post(() -> {
                adapter.setItems(docs);
                empty.setVisibility(docs.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private class DocAdapter extends BaseAdapter {
        private final List<Doc> items = new ArrayList<>();

        void setItems(List<Doc> docs) {
            items.clear();
            items.addAll(docs);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Doc getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_doc, parent, false);

            Doc doc = items.get(position);
            ((TextView) v.findViewById(R.id.name)).setText(doc.name);

            String pages = doc.pages.isEmpty()
                    ? getString(R.string.doc_empty)
                    : getString(R.string.doc_pages, doc.pages.size());
            ((TextView) v.findViewById(R.id.meta)).setText(pages + " · " + timeAgo(doc.created));

            ImageView thumb = v.findViewById(R.id.thumb);
            if (doc.pages.isEmpty()) {
                thumb.setTag(null);
                thumb.setImageDrawable(null);
            } else {
                thumbs.load(thumb, store.pageFile(doc, doc.pages.get(0)), Bitmaps.THUMB_SIDE);
            }
            return v;
        }
    }

    // ---------------------------------------------------------------- 交互

    private void openViewer(Doc doc) {
        Intent i = new Intent(this, ViewerActivity.class);
        i.putExtra(ViewerActivity.EXTRA_DOC_ID, doc.id);
        startActivity(i);
    }

    private void showDocMenu(final Doc doc) {
        String[] actions = {getString(R.string.viewer_rename), getString(R.string.viewer_delete_doc)};
        new AlertDialog.Builder(this)
                .setTitle(doc.name)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) promptRename(doc);
                    else confirmDelete(doc);
                })
                .show();
    }

    private void promptRename(final Doc doc) {
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
                        post(MainActivity.this::refresh);
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDelete(final Doc doc) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.viewer_delete_doc_title)
                .setMessage(getString(R.string.viewer_delete_doc_msg, doc.name))
                .setPositiveButton(R.string.delete, (dialog, which) -> io.execute(() -> {
                    store.delete(doc.id);
                    post(MainActivity.this::refresh);
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return;

        final List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (!uris.isEmpty()) importImages(uris);
    }

    /**
     * 批量导入：每张图自己找边、拉直、去阴影，然后登记成一页。
     * 多选时不逐张让用户拖角（十几张能拖到崩溃），找边不准的再进文档删掉重拍即可。
     */
    private void importImages(final List<Uri> uris) {
        showMask(getString(R.string.import_running));
        io.execute(() -> {
            final Doc doc = store.create(getString(R.string.default_doc_name));
            int ok = 0;
            for (int i = 0; i < uris.size(); i++) {
                final int index = i + 1;
                final int total = uris.size();
                post(() -> setMaskText(getString(R.string.import_progress, index, total)));
                Bitmap src = null;
                try {
                    src = Bitmaps.decodeUri(getContentResolver(), uris.get(i), Bitmaps.processingSide());
                    Pipeline.autoAdd(store, doc, src, Enhance.COLOR);
                    ok++;
                } catch (Exception e) {
                    Log.w(TAG, "导入第 " + index + " 张失败", e);
                } finally {
                    if (src != null) src.recycle();
                }
            }

            final int imported = ok;
            post(() -> {
                hideMask();
                if (imported == 0) {
                    store.delete(doc.id);
                    toast(R.string.import_none);
                } else {
                    toast(getString(R.string.import_done, imported));
                    openViewer(doc);
                }
                refresh();
            });
        });
    }
}
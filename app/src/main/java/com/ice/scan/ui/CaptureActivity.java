package com.ice.scan.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ice.scan.R;
import com.ice.scan.data.Doc;
import com.ice.scan.data.DocStore;
import com.ice.scan.data.Page;
import com.ice.scan.share.ScanFileProvider;
import com.ice.scan.util.Bitmaps;

import java.io.File;
import java.io.IOException;

/**
 * 扫描页：拍一张（或从相册选一张）→ 进裁剪页调边 → 回来继续加页。
 *
 * <p>拍照走的是系统相机（{@code ACTION_IMAGE_CAPTURE}），照片通过自己的
 * {@link ScanFileProvider} 写回缓存目录。这样做换来两件好事：**不需要相机权限**，
 * 也不用把预览、对焦、旋转这些相机坑全踩一遍。
 */
public class CaptureActivity extends BaseActivity {

    /** 从文档页进来时带上，表示往已有文档里加页；不传就新建一个。 */
    public static final String EXTRA_DOC_ID = "doc_id";

    private static final String TAG = "CaptureActivity";
    private static final int REQ_CAPTURE = 201;
    private static final int REQ_ALBUM = 202;
    private static final int REQ_CROP = 203;

    private DocStore store;
    private ThumbLoader thumbs;
    private Doc doc;
    private boolean createdHere;
    private File pendingCapture;

    private LinearLayout strip;
    private View stripWrap;
    private TextView tvCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture);

        store = DocStore.open(this);
        thumbs = new ThumbLoader(8 * 1024 * 1024);

        strip = findViewById(R.id.strip);
        stripWrap = findViewById(R.id.strip_wrap);
        tvCount = findViewById(R.id.tv_count);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_done).setOnClickListener(v -> finish());
        findViewById(R.id.btn_shoot).setOnClickListener(v -> shoot());
        findViewById(R.id.btn_album).setOnClickListener(v -> pickFromAlbum());

        openDocument();
    }

    /** 拿已有文档，或新建一个（新建要扫一遍目录，放后台）。 */
    private void openDocument() {
        String id = getIntent().getStringExtra(EXTRA_DOC_ID);
        if (id != null) {
            doc = store.load(id);
            if (doc == null) {
                finish();
                return;
            }
            refreshStrip();
            return;
        }
        io.execute(() -> {
            final Doc created = store.create(getString(R.string.default_doc_name));
            post(() -> {
                doc = created;
                createdHere = true;
                refreshStrip();
            });
        });
    }

    @Override
    protected void onDestroy() {
        // 从首页点进来、什么都没拍就退出：别在列表里留一个空文档
        if (createdHere && doc != null) {
            Doc onDisk = store.load(doc.id);
            if (onDisk == null || onDisk.pages.isEmpty()) store.delete(doc.id);
        }
        thumbs.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 拍照

    private void shoot() {
        if (doc == null) {
            toast(R.string.capture_shooting);
            return;
        }
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) == null) {
            toast(R.string.home_no_camera);
            return;
        }

        File file = newCaptureFile("shot");
        if (file == null) return;
        pendingCapture = file;

        Uri target = ScanFileProvider.uriFor(this, ScanFileProvider.KIND_CAPTURE, file.getName());
        intent.putExtra(MediaStore.EXTRA_OUTPUT, target);
        // 把「写这个 URI」的权利临时借给相机 App，其他地方不开放
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQ_CAPTURE);
    }

    private void pickFromAlbum() {
        if (doc == null) {
            toast(R.string.capture_shooting);
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_ALBUM);
    }

    private File newCaptureFile(String prefix) {
        File dir = new File(getCacheDir(), ScanFileProvider.KIND_CAPTURE);
        if (!dir.exists() && !dir.mkdirs()) {
            toast(getString(R.string.import_failed, "无法创建缓存目录"));
            return null;
        }
        File f = new File(dir, prefix + "-" + System.currentTimeMillis() + ".jpg");
        try {
            if (!f.createNewFile()) throw new IOException("无法创建文件");
        } catch (IOException e) {
            toast(getString(R.string.import_failed, e.getMessage()));
            return null;
        }
        return f;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQ_CAPTURE: {
                File shot = pendingCapture;
                pendingCapture = null;
                if (resultCode == RESULT_OK && shot != null && shot.length() > 0) {
                    launchCrop(shot);
                } else {
                    if (shot != null) {
                        //noinspection ResultOfMethodCallIgnored
                        shot.delete();
                    }
                    if (resultCode != RESULT_CANCELED) toast(R.string.capture_failed);
                }
                break;
            }
            case REQ_ALBUM: {
                if (resultCode != RESULT_OK || data == null || data.getData() == null) break;
                final Uri source = data.getData();
                final File dst = newCaptureFile("pick");
                if (dst == null) break;
                io.execute(() -> {
                    try {
                        Bitmaps.copyToFile(getContentResolver(), source, dst);
                    } catch (IOException e) {
                        Log.w(TAG, "复制相册图片失败", e);
                        post(() -> {
                            toast(getString(R.string.import_failed, e.getMessage()));
                            //noinspection ResultOfMethodCallIgnored
                            dst.delete();
                        });
                        return;
                    }
                    post(() -> launchCrop(dst));
                });
                break;
            }
            case REQ_CROP: {
                if (resultCode == RESULT_OK) reloadDoc();
                break;
            }
            default:
                break;
        }
    }

    private void launchCrop(File source) {
        Intent i = new Intent(this, CropActivity.class);
        i.putExtra(CropActivity.EXTRA_SOURCE, source.getAbsolutePath());
        i.putExtra(CropActivity.EXTRA_DOC_ID, doc.id);
        startActivityForResult(i, REQ_CROP);
    }

    // ---------------------------------------------------------------- 缩略图条

    private void reloadDoc() {
        io.execute(() -> {
            final Doc fresh = store.load(doc.id);
            post(() -> {
                if (fresh == null) return;
                doc = fresh;
                refreshStrip();
            });
        });
    }

    private void refreshStrip() {
        if (doc == null) return;
        tvCount.setText(getString(R.string.capture_sub, doc.pages.size()));

        strip.removeAllViews();
        if (doc.pages.isEmpty()) {
            stripWrap.setVisibility(View.GONE);
            return;
        }
        stripWrap.setVisibility(View.VISIBLE);

        LayoutInflater inflater = getLayoutInflater();
        for (Page p : doc.pages) {
            ImageView iv = (ImageView) inflater.inflate(R.layout.item_strip, strip, false);
            strip.addView(iv);
            thumbs.load(iv, store.pageFile(doc, p), Bitmaps.THUMB_SIDE);
        }
    }
}
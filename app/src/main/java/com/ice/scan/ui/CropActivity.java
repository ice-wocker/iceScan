package com.ice.scan.ui;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;

import com.ice.scan.R;
import com.ice.scan.data.Doc;
import com.ice.scan.data.DocStore;
import com.ice.scan.img.AutoCrop;
import com.ice.scan.img.Enhance;
import com.ice.scan.img.Quad;
import com.ice.scan.img.Resize;
import com.ice.scan.scan.Pipeline;
import com.ice.scan.util.Bitmaps;

import java.io.File;
import java.io.IOException;

/**
 * 裁剪页：自动找边 → 拖四角微调 → 选效果 → 落盘。
 *
 * <p>显示、编辑、预览、导出用的是**四份不同分辨率**的同一张图，这是这个页面不 OOM 的关键：
 * <ul>
 *   <li>{@code display}：铺在 {@link QuadView} 上看的那张，长边 1600，保证手感清晰；</li>
 *   <li>{@code px}：真正参与透视变换的全分辨率（处理分辨率）像素，只有落盘那一刻才动它；</li>
 *   <li>{@code smallPx}：效果预览用的长边 640 小图，拖角时反复重算也不卡；</li>
 *   <li>找边用的一次性缩略图，算完即弃。</li>
 * </ul>
 *
 * <p>角点以归一化坐标在 {@link QuadView} 里流转，所以四份图之间不需要重新标定。
 */
public class CropActivity extends BaseActivity {

    /** 待处理的源图绝对路径（相机 / 相册临时文件，进本页即接管，离开时删掉）。 */
    public static final String EXTRA_SOURCE = "source";
    /** 目标文档 id，处理完的页会追加进去。 */
    public static final String EXTRA_DOC_ID = "doc_id";

    private static final String TAG = "CropActivity";

    /** 编辑用底图的长边。够清晰，又不至于为了看一张图吃掉几十 MB。 */
    private static final int DISPLAY_SIDE = 1600;
    /** 效果预览的工作分辨率。 */
    private static final int PREVIEW_WORK = 640;
    /** 效果预览小图的长边。 */
    private static final int PREVIEW_OUT = 360;
    /** 拖角时预览的节流间隔，人眼感知不到延迟，但省掉大量重复计算。 */
    private static final long PREVIEW_DELAY_MS = 90L;

    private DocStore store;
    private Doc doc;
    private File source;

    private QuadView quad;
    private ImageView preview;
    private TextView hint;
    private TextView[] chips;

    private final int[] chipModes = {
            Enhance.ORIGINAL, Enhance.COLOR, Enhance.GRAY, Enhance.BW};

    private int mode = Enhance.COLOR;

    /** 全分辨率像素，落盘用。 */
    private int[] px;
    private int w;
    private int h;

    /** 预览用的小图，拖角时反复重算。 */
    private int[] smallPx;
    private int sw;
    private int sh;

    private Bitmap display;
    private Bitmap previewBitmap;
    private volatile int previewSeq;
    private volatile boolean ready;

    private final Runnable previewTask = this::renderPreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);
        bindMask();

        store = DocStore.open(this);

        String path = getIntent().getStringExtra(EXTRA_SOURCE);
        String docId = getIntent().getStringExtra(EXTRA_DOC_ID);
        source = path == null ? null : new File(path);
        doc = docId == null ? null : store.load(docId);
        if (source == null || !source.isFile() || doc == null) {
            finish();
            return;
        }

        quad = findViewById(R.id.quad);
        quad.setOnQuadChanged(q -> schedulePreview());
        preview = findViewById(R.id.img_preview);
        hint = findViewById(R.id.tv_hint);

        chips = new TextView[]{
                findViewById(R.id.mode_original),
                findViewById(R.id.mode_color),
                findViewById(R.id.mode_gray),
                findViewById(R.id.mode_bw)};
        for (int i = 0; i < chips.length; i++) {
            final int m = chipModes[i];
            chips[i].setOnClickListener(v -> setMode(m));
        }
        applyChipStyles();

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_retake).setOnClickListener(v -> finish());
        findViewById(R.id.btn_auto).setOnClickListener(v -> autoDetect());
        findViewById(R.id.btn_done).setOnClickListener(v -> savePage());

        load();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(previewTask);
        // 源图是相机/相册的临时文件，无论成功失败都不该留在缓存里
        if (source != null) {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        }
        if (display != null) display.recycle();
        if (previewBitmap != null) previewBitmap.recycle();
        if (quad != null) quad.setImage(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 解码

    private void load() {
        showMask(R.string.crop_saving);
        io.execute(() -> {
            int[] full;
            int fw;
            int fh;
            int[] displayPx;
            int dw;
            int dh;
            int[] workPx;
            int ww;
            int wh;
            boolean found;
            float[] norm;
            try {
                Bitmap src = Bitmaps.decodeFile(source, Bitmaps.processingSide());
                fw = src.getWidth();
                fh = src.getHeight();
                full = Bitmaps.pixels(src);
                src.recycle();

                int[] dsz = Resize.fit(fw, fh, DISPLAY_SIDE);
                dw = dsz[0];
                dh = dsz[1];
                displayPx = Resize.downscale(full, fw, fh, dw, dh);

                int[] wsz = Resize.fit(fw, fh, PREVIEW_WORK);
                ww = wsz[0];
                wh = wsz[1];
                workPx = Resize.downscale(full, fw, fh, ww, wh);

                int[] tsz = Resize.fit(fw, fh, Pipeline.DETECT_SIDE);
                int[] tiny = Resize.downscale(full, fw, fh, tsz[0], tsz[1]);
                float[] q = AutoCrop.detect(tiny, tsz[0], tsz[1]);
                found = q != null;
                norm = found ? Quad.normalize(q, tsz[0], tsz[1]) : Quad.full();
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "打开图片失败", e);
                post(() -> {
                    hideMask();
                    toast(getString(R.string.import_failed, e.getMessage()));
                    finish();
                });
                return;
            }

            final Bitmap bmp = Bitmaps.fromArgb(displayPx, dw, dh);
            final boolean detected = found;
            final float[] quadNorm = norm;
            post(() -> {
                px = full;
                w = fw;
                h = fh;
                smallPx = workPx;
                sw = ww;
                sh = wh;
                display = bmp;
                ready = true;

                quad.setImage(bmp);
                quad.setQuad(quadNorm);
                hint.setText(detected ? R.string.crop_hint_auto : R.string.crop_hint_manual);
                hideMask();
                schedulePreview();
            });
        });
    }

    // ---------------------------------------------------------------- 交互

    private void setMode(int m) {
        if (mode == m) return;
        mode = m;
        applyChipStyles();
        schedulePreview();
    }

    private void applyChipStyles() {
        if (chips == null) return;
        for (int i = 0; i < chips.length; i++) {
            boolean on = chipModes[i] == mode;
            chips[i].setBackgroundResource(on ? R.drawable.bg_pill_on : R.drawable.bg_pill);
            chips[i].setTextColor(getResources().getColor(on ? R.color.bg : R.color.text_dim, null));
            chips[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    /** 重新自动找边。找不到明显的纸时退成整幅，让用户自己拖。 */
    private void autoDetect() {
        if (!ready) return;
        showMask(R.string.crop_saving);
        io.execute(() -> {
            int[] tsz = Resize.fit(w, h, Pipeline.DETECT_SIDE);
            int[] tiny = Resize.downscale(px, w, h, tsz[0], tsz[1]);
            float[] q = AutoCrop.detect(tiny, tsz[0], tsz[1]);
            final boolean found = q != null;
            final float[] norm = found ? Quad.normalize(q, tsz[0], tsz[1]) : Quad.full();
            post(() -> {
                quad.setQuad(norm);
                hint.setText(found ? R.string.crop_hint_auto : R.string.crop_hint_manual);
                hideMask();
                schedulePreview();
            });
        });
    }

    private void schedulePreview() {
        ui.removeCallbacks(previewTask);
        ui.postDelayed(previewTask, PREVIEW_DELAY_MS);
    }

    /**
     * 用几百像素的小图重算透视+增强，只为了给 64dp 的预览框看。
     * 每次带一个序号，回来时序号对不上就丢掉——拖得快的时候，旧结果不能盖住新结果。
     */
    private void renderPreview() {
        if (!ready || smallPx == null || quad == null) return;
        final float[] q = quad.getQuad();
        final int m = mode;
        final int seq = ++previewSeq;
        io.execute(() -> {
            Bitmap b;
            try {
                b = Pipeline.preview(smallPx, sw, sh, q, m, PREVIEW_OUT);
            } catch (RuntimeException e) {
                return;
            }
            post(() -> {
                if (seq != previewSeq) {
                    b.recycle();
                    return;
                }
                if (previewBitmap != null) previewBitmap.recycle();
                previewBitmap = b;
                preview.setImageBitmap(b);
            });
        });
    }

    private void savePage() {
        if (!ready || px == null) return;
        final float[] q = quad.getQuad();
        final int m = mode;
        showMask(R.string.crop_saving);
        io.execute(() -> {
            try {
                Pipeline.render(store, doc, px, w, h, q, m, Bitmaps.processingSide());
                post(() -> {
                    hideMask();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "落盘失败", e);
                post(() -> {
                    hideMask();
                    toast(getString(R.string.import_failed, e.getMessage()));
                });
            }
        });
    }
}
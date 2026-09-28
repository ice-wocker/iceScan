package com.ice.scan.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.ice.scan.R;
import com.ice.scan.img.Quad;

/**
 * 四角可拖的裁剪框。
 *
 * <p>照片铺满画面、外面压一层半透明遮罩，只有选中的那块亮着——一眼就能看出「会留下哪儿」。
 * 拖角的时候会在同侧弹出放大镜，手指再粗也能对到纸的边缘上。
 *
 * <p>角点以**归一化坐标**保存（0–1），所以这个 View 只负责显示和交互，
 * 真正做透视变换时把同一份数据套到全分辨率原图上即可，精度不受预览尺寸影响。
 */
public class QuadView extends View {

    /** 拖动结束/过程中通知外部（已做节流，不会每一帧都回调）。 */
    public interface OnQuadChanged {
        void onQuadChanged(float[] normalized);
    }

    private static final float HANDLE_DP = 11f;
    private static final float TOUCH_DP = 36f;
    private static final float MAG_DP = 48f;
    private static final float MAG_ZOOM = 2.6f;
    private static final float MARGIN_DP = 10f;

    private final float[] quad = Quad.full();

    private Bitmap image;
    private OnQuadChanged listener;
    private boolean interactive = true;
    private int active = -1;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint magRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path quadPath = new Path();
    private final Path scrimPath = new Path();
    private final Path magClip = new Path();
    private final RectF imgRect = new RectF();
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    private float handleRadius;
    private float touchRadius;
    private float magRadius;
    private float margin;

    public QuadView(Context context) {
        super(context);
        init(context);
    }

    public QuadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public QuadView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context ctx) {
        float d = getResources().getDisplayMetrics().density;
        handleRadius = HANDLE_DP * d;
        touchRadius = TOUCH_DP * d;
        magRadius = MAG_DP * d;
        margin = MARGIN_DP * d;

        scrimPaint.setColor(getResources().getColor(R.color.scrim, null));
        scrimPaint.setStyle(Paint.Style.FILL);

        edgePaint.setColor(getResources().getColor(R.color.accent, null));
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(2f * d);

        gridPaint.setColor(0x55FFFFFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f * d);

        handleFill.setColor(getResources().getColor(R.color.white, null));
        handleFill.setStyle(Paint.Style.FILL);

        handleRing.setColor(getResources().getColor(R.color.accent, null));
        handleRing.setStyle(Paint.Style.STROKE);
        handleRing.setStrokeWidth(2f * d);

        magRing.setColor(getResources().getColor(R.color.white, null));
        magRing.setStyle(Paint.Style.STROKE);
        magRing.setStrokeWidth(2f * d);

        crossPaint.setColor(getResources().getColor(R.color.accent, null));
        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStrokeWidth(1f * d);

        setFocusable(true);
    }

    public void setImage(Bitmap b) {
        this.image = b;
        imgRect.setEmpty();
        invalidate();
    }

    public void setOnQuadChanged(OnQuadChanged l) {
        this.listener = l;
    }

    /** 设置角点（归一化坐标）。 */
    public void setQuad(float[] normalized) {
        if (normalized == null || normalized.length < 8) return;
        System.arraycopy(Quad.clamp(normalized), 0, quad, 0, 8);
        if (!validQuad()) System.arraycopy(Quad.full(), 0, quad, 0, 8);
        invalidate();
    }

    public float[] getQuad() {
        return Quad.copy(quad);
    }

    /** 回到整幅画面。 */
    public void resetQuad() {
        setQuad(Quad.full());
    }

    public void setInteractive(boolean on) {
        this.interactive = on;
        if (!on) {
            active = -1;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (image == null || image.isRecycled()) return;
        layoutImage();

        canvas.drawBitmap(image, null, imgRect, bitmapPaint);

        // 四边形路径
        quadPath.reset();
        quadPath.moveTo(viewX(0), viewY(0));
        for (int i = 1; i < 4; i++) quadPath.lineTo(viewX(i), viewY(i));
        quadPath.close();

        // 遮罩：整屏挖掉四边形
        scrimPath.reset();
        scrimPath.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        scrimPath.op(quadPath, Path.Op.DIFFERENCE);
        canvas.drawPath(scrimPath, scrimPaint);

        // 井字格：对四边形做双线性插值，比画在包围盒上更贴合斜拍的纸
        canvas.save();
        canvas.clipPath(quadPath);
        for (int k = 1; k <= 2; k++) {
            float t = k / 3f;
            float[] top = lerp(0, 1, t);
            float[] bottom = lerp(3, 2, t);
            canvas.drawLine(top[0], top[1], bottom[0], bottom[1], gridPaint);
            float[] left = lerp(0, 3, t);
            float[] right = lerp(1, 2, t);
            canvas.drawLine(left[0], left[1], right[0], right[1], gridPaint);
        }
        canvas.restore();

        canvas.drawPath(quadPath, edgePaint);

        for (int i = 0; i < 4; i++) {
            float x = viewX(i);
            float y = viewY(i);
            canvas.drawCircle(x, y, handleRadius, handleFill);
            canvas.drawCircle(x, y, handleRadius, handleRing);
        }

        if (active >= 0) drawMagnifier(canvas, active);
    }

    /** 把位图等比放进 View，四周留出手柄和放大镜的余地。 */
    private void layoutImage() {
        float pad = handleRadius + margin;
        float availW = getWidth() - pad * 2;
        float availH = getHeight() - pad * 2;
        if (availW <= 0 || availH <= 0) {
            imgRect.setEmpty();
            return;
        }
        float scale = Math.min(availW / image.getWidth(), availH / image.getHeight());
        float w = image.getWidth() * scale;
        float h = image.getHeight() * scale;
        float left = pad + (availW - w) / 2f;
        float top = pad + (availH - h) / 2f;
        imgRect.set(left, top, left + w, top + h);
    }

    private float viewX(int i) {
        return imgRect.left + quad[i * 2] * imgRect.width();
    }

    private float viewY(int i) {
        return imgRect.top + quad[i * 2 + 1] * imgRect.height();
    }

    /** 第 a 角到第 b 角之间比例 t 处的点（View 坐标）。 */
    private float[] lerp(int a, int b, float t) {
        float x = viewX(a) + (viewX(b) - viewX(a)) * t;
        float y = viewY(a) + (viewY(b) - viewY(a)) * t;
        return new float[]{x, y};
    }

    private void drawMagnifier(Canvas canvas, int idx) {
        float cx = viewX(idx);
        float cy = viewY(idx);

        // 放大镜放在离手指最远的那半边，不挡视线
        float my = cy < getHeight() * 0.42f ? getHeight() - magRadius - margin : magRadius + margin;
        float mx = clamp(cx, magRadius + margin, getWidth() - magRadius - margin);

        float pxPerView = image.getWidth() / Math.max(1f, imgRect.width());
        float bx = (cx - imgRect.left) * pxPerView;
        float by = (cy - imgRect.top) * pxPerView;
        float half = (magRadius / MAG_ZOOM) * pxPerView;

        srcRect.set((int) (bx - half), (int) (by - half), (int) (bx + half), (int) (by + half));
        dstRect.set(mx - magRadius, my - magRadius, mx + magRadius, my + magRadius);

        canvas.save();
        magClip.reset();
        magClip.addCircle(mx, my, magRadius, Path.Direction.CW);
        canvas.clipPath(magClip);
        canvas.drawColor(getResources().getColor(R.color.bg, null));
        canvas.drawBitmap(image, srcRect, dstRect, bitmapPaint);
        // 十字线正中就是那个角，方便精确对边
        canvas.drawLine(mx, my - magRadius, mx, my + magRadius, crossPaint);
        canvas.drawLine(mx - magRadius, my, mx + magRadius, my, crossPaint);
        float s = 3f * getResources().getDisplayMetrics().density;
        canvas.drawRect(mx - s, my - s, mx + s, my + s, handleFill);
        canvas.restore();
        canvas.drawCircle(mx, my, magRadius, magRing);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!interactive || image == null || imgRect.isEmpty()) return false;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                active = nearest(e.getX(), e.getY());
                if (active >= 0) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (active < 0) return false;
                moveCorner(active, e.getX(), e.getY());
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (active < 0) return false;
                active = -1;
                invalidate();
                notifyChanged();
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    private int nearest(float x, float y) {
        int best = -1;
        float bestDist = touchRadius * touchRadius;
        for (int i = 0; i < 4; i++) {
            float dx = viewX(i) - x;
            float dy = viewY(i) - y;
            float d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private void moveCorner(int i, float x, float y) {
        float u = clamp((x - imgRect.left) / Math.max(1f, imgRect.width()), 0f, 1f);
        float v = clamp((y - imgRect.top) / Math.max(1f, imgRect.height()), 0f, 1f);

        float oldU = quad[i * 2];
        float oldV = quad[i * 2 + 1];
        quad[i * 2] = u;
        quad[i * 2 + 1] = v;

        // 拖过头会把四边形拧成自交或压成一条线，那样解不出单应矩阵。
        // 直接拒绝这一步移动，手感上就是「顶住了」，比事后纠正直观。
        if (!validQuad()) {
            quad[i * 2] = oldU;
            quad[i * 2 + 1] = oldV;
            return;
        }
        invalidate();
        notifyChanged();
    }

    /** 必须是凸的、且有像样的面积（在归一化单位下）。 */
    private boolean validQuad() {
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            int k = (i + 2) % 4;
            double ax = quad[j * 2] - quad[i * 2];
            double ay = quad[j * 2 + 1] - quad[i * 2 + 1];
            double bx = quad[k * 2] - quad[j * 2];
            double by = quad[k * 2 + 1] - quad[j * 2 + 1];
            double cross = ax * by - ay * bx;
            if (Math.abs(cross) < 1e-7) return false;
            int s = cross > 0 ? 1 : -1;
            if (sign == 0) sign = s;
            else if (sign != s) return false;
        }
        double area = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            area += quad[i * 2] * quad[j * 2 + 1] - quad[j * 2] * quad[i * 2 + 1];
        }
        return Math.abs(area) / 2 > 0.004;
    }

    private void notifyChanged() {
        if (listener != null) listener.onQuadChanged(Quad.copy(quad));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
package com.ice.scan.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.ice.scan.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 四个页面共用的底座：
 *
 * <ul>
 *   <li>一条后台线程（解码、找边、透视变换、合成 PDF 都在上面跑，绝不卡主线程）</li>
 *   <li>一个主线程 Handler，并且页面销毁后自动拒绝投递（避免回调打到已经没了的界面）</li>
 *   <li>「处理中」遮罩的显隐</li>
 *   <li>「3 分钟前」这类相对时间的文案</li>
 * </ul>
 */
public abstract class BaseActivity extends Activity {

    protected final ExecutorService io = Executors.newSingleThreadExecutor();
    protected final Handler ui = new Handler(Looper.getMainLooper());

    private volatile boolean destroyed;
    private View mask;
    private TextView maskText;

    /** 在 {@code setContentView} 之后调用，绑定布局里的遮罩。布局没有就当作没有。 */
    protected void bindMask() {
        mask = findViewById(R.id.mask);
        maskText = findViewById(R.id.mask_text);
    }

    protected void showMask(String text) {
        if (mask == null) return;
        if (maskText != null) maskText.setText(text);
        mask.setVisibility(View.VISIBLE);
    }

    protected void showMask(int textRes) {
        showMask(getString(textRes));
    }

    protected void setMaskText(String text) {
        if (maskText != null) maskText.setText(text);
    }

    protected void hideMask() {
        if (mask != null) mask.setVisibility(View.GONE);
    }

    /** 页面销毁后不再投递，省得回调去碰已经不在的 View。 */
    protected void post(Runnable r) {
        if (!destroyed) ui.post(r);
    }

    protected void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    protected void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    protected String timeAgo(long time) {
        long diff = System.currentTimeMillis() - time;
        if (diff < 60_000L) return getString(R.string.time_just_now);
        if (diff < 3_600_000L) return getString(R.string.time_minutes, (int) (diff / 60_000L));
        if (diff < 86_400_000L) return getString(R.string.time_hours, (int) (diff / 3_600_000L));
        if (diff < 7 * 86_400_000L) return getString(R.string.time_days, (int) (diff / 86_400_000L));
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(time));
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }
}
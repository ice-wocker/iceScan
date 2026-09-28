package com.ice.scan.ui;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.ice.scan.util.Bitmaps;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 列表缩略图加载器。
 *
 * <p>三条规矩，缺一条列表就会「串图」：
 * <ol>
 *   <li>解码只在后台线程做（一页 240px 也就几毫秒，但几百页加起来就不能在主线程了）</li>
 *   <li>结果丢进 LRU 缓存，滚动回来不用再解码</li>
 *   <li>回填前先核对 ImageView 上的 tag —— 列表复用 View 时，旧任务可能比新任务后回来</li>
 * </ol>
 */
public final class ThumbLoader {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;

    public ThumbLoader(int cacheBytes) {
        cache = new LruCache<String, Bitmap>(Math.max(4 * 1024 * 1024, cacheBytes)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    public void load(ImageView view, File file, int maxSide) {
        final String key = file.getAbsolutePath() + "@" + maxSide;
        view.setTag(key);

        Bitmap hit = cache.get(key);
        if (hit != null && !hit.isRecycled()) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);

        io.execute(() -> {
            final Bitmap b;
            try {
                b = Bitmaps.decodeFile(file, maxSide);
            } catch (IOException e) {
                return;
            }
            cache.put(key, b);
            ui.post(() -> {
                if (key.equals(view.getTag()) && !b.isRecycled()) view.setImageBitmap(b);
            });
        });
    }

    public void shutdown() {
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }
}
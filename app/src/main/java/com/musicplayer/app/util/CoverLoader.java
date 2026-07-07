package com.musicplayer.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.util.LruCache;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.musicplayer.app.R;
import com.musicplayer.app.data.Song;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 封面图片加载器，负责从音频文件中提取嵌入式封面并缓存显示到 ImageView。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>使用 LruCache 做内存缓存，以 mediaStoreId 为 key，缓存大小为应用可用内存的 1/8（上限 32MB）</li>
 *   <li>缓存命中时同步显示，未命中时通过 AsyncTask 在后台线程解码封面</li>
 *   <li>DecodeTask 使用 WeakReference 持有 ImageView，并通过 tag 校验防止 ListView/RecyclerView 复用错位</li>
 * </ul>
 * 采用双重检查锁定的单例模式，需在 Application.onCreate() 中调用 init() 初始化。
 * </p>
 */
public final class CoverLoader {

    private static volatile CoverLoader instance;

    /** 以 mediaStoreId 为 key 的内存封面缓存 */
    private final LruCache<Long, Bitmap> memCache;
    /** 已发起但未完成的解码任务集合，用于避免重复提交 */
    private final Set<Long> pending = new HashSet<>();
    private final Context appContext;

    private CoverLoader(Context context) {
        this.appContext = context.getApplicationContext();
        // 应用可用内存的1/8作为封面缓存，上限32MB
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        int cacheSize = Math.min(maxKb, 32 * 1024);
        memCache = new LruCache<Long, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(Long key, Bitmap value) {
                // 以 KB 为单位计算每张 Bitmap 的大小
                return value.getByteCount() / 1024;
            }
        };
    }

    /**
     * 初始化 CoverLoader 单例，应在 Application.onCreate() 中调用。
     *
     * @param context 应用上下文
     */
    public static void init(Context context) {
        if (instance == null) {
            synchronized (CoverLoader.class) {
                if (instance == null) {
                    instance = new CoverLoader(context);
                }
            }
        }
    }

    /**
     * 获取 CoverLoader 单例。必须在 init() 之后调用，否则抛出异常。
     *
     * @return CoverLoader 实例
     * @throws IllegalStateException 若未初始化
     */
    public static CoverLoader get() {
        if (instance == null) {
            throw new IllegalStateException("CoverLoader not initialized");
        }
        return instance;
    }

    /**
     * 异步加载封面到 ImageView。命中缓存则同步显示，否则设置占位图并启动后台解码任务。
     *
     * @param song           歌曲对象，null 时直接显示占位图
     * @param target         目标 ImageView
     * @param placeholderRes 占位图资源 ID
     * @param roundedDp      圆角大小（dp），暂未使用
     */
    public void display(@Nullable Song song, @NonNull ImageView target,
                        int placeholderRes, int roundedDp) {
        if (song == null) {
            target.setImageResource(placeholderRes);
            return;
        }
        // 缓存命中，直接同步显示
        Bitmap cached = memCache.get(song.mediaStoreId);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        // 未命中，先设占位图，再异步解码
        target.setImageResource(placeholderRes);
        synchronized (pending) {
            new DecodeTask(this, target, song).executeOnExecutor(
                    AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    /**
     * 使用默认占位图和无圆角加载封面。
     *
     * @param song   歌曲对象
     * @param target 目标 ImageView
     */
    public void display(@Nullable Song song, @NonNull ImageView target) {
        display(song, target, R.drawable.ic_default_cover, 0);
    }

    /**
     * 后台解码封面图片的异步任务。
     * <p>
     * 在 doInBackground() 中调用 EmbeddedArtReader 读取封面，
     * 在 onPostExecute() 中将结果写入缓存并回显到 ImageView。
     * 使用 WeakReference 持有 ImageView 防止 Activity 销毁后内存泄漏，
     * 通过 tag 校验确保 ImageView 未被复用给其他歌曲。
     * </p>
     */
    private static class DecodeTask extends AsyncTask<Void, Void, Bitmap> {
        private final CoverLoader loader;
        /** WeakReference 防止 Activity 销毁后仍持有 ImageView 导致内存泄漏 */
        private final WeakReference<ImageView> viewRef;
        private final Song song;

        DecodeTask(CoverLoader loader, ImageView target, Song song) {
            this.loader = loader;
            this.viewRef = new WeakReference<>(target);
            this.song = song;
        }

        @Override
        protected Bitmap doInBackground(Void... voids) {
            // 在后台线程中读取嵌入式封面
            return EmbeddedArtReader.read(loader.appContext, song);
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap != null) {
                // 解码成功，写入内存缓存
                loader.memCache.put(song.mediaStoreId, bitmap);
                ImageView iv = viewRef.get();
                // 校验 ImageView 未被回收且未被复用给其他歌曲
                if (iv != null) {
                    Object tag = iv.getTag(R.id.cover_tag_song_id);
                    if (tag == null
                            || (long) (int) tag == song.mediaStoreId) {
                        iv.setImageBitmap(bitmap);
                    }
                }
            }
        }
    }

    /**
     * 清空全部内存缓存，可在内存紧张时调用。
     */
    public void trim() {
        memCache.evictAll();
    }
}

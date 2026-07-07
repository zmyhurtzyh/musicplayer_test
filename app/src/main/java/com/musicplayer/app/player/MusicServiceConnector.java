package com.musicplayer.app.player;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;

import com.musicplayer.app.data.Song;

import java.util.List;

/**
 * Activity/Fragment 与 MusicService 之间的绑定桥接器，封装了服务的绑定/解绑生命周期管理
 * 以及常用播放控制的便捷方法。
 * <p>
 * 设计要点：实现 ServiceConnection 接口监听连接状态变化；
 * 连接成功后自动将服务提升为前台（promoteToForeground）以保证后台播放稳定；
 * 所有便捷方法内部对 service 做空判断，避免解绑后调用导致 NPE。
 * </p>
 */
public class MusicServiceConnector implements ServiceConnection {

    /** 绑定后获取的 MusicService 实例，可能为 null（未连接时） */
    private MusicService service;
    /** 是否已绑定到服务 */
    private boolean bound = false;
    /** 连接成功后的回调，用于通知 UI 层可以开始与 Service 交互 */
    private Runnable onConnected;
    /** 应用级 Context，避免持有 Activity Context 导致内存泄漏 */
    private Context context;

    /**
     * 启动并绑定 MusicService。先通过 startForegroundService 确保服务启动，
     * 再通过 bindService 获取服务实例。
     *
     * @param hostContext 宿主 Context（通常为 Activity）
     * @param onConnected 连接成功后的回调
     */
    public void bind(@NonNull Context hostContext, Runnable onConnected) {
        this.context = hostContext.getApplicationContext();
        this.onConnected = onConnected;
        Intent intent = new Intent(context, MusicService.class);
        // 先启动前台服务，再绑定，保证服务在绑定前已成为前台服务
        ContextCompat.startForegroundService(context, intent);
        context.bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    /**
     * 解绑 MusicService。解绑后服务仍作为前台服务运行，除非主动停止。
     *
     * @param context 宿主 Context
     */
    public void unbind(@NonNull Context context) {
        if (bound && this.context != null) {
            try {
                this.context.unbindService(this);
            } catch (Exception ignore) {
                // 忽略解绑异常（如服务已停止）
            }
            bound = false;
        }
    }

    /**
     * 服务绑定成功回调。获取 MusicService 实例、提升为前台服务，并执行连接成功回调。
     *
     * @param name   绑定的组件名
     * @param binder 服务返回的 Binder 对象
     */
    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        MusicService.MusicBinder mb = (MusicService.MusicBinder) binder;
        service = mb.getService();
        bound = true;
        // 连接后立即提升为前台服务，确保后台播放不被系统回收
        service.promoteToForeground();
        if (onConnected != null) {
            onConnected.run();
        }
    }

    /**
     * 服务意外断开连接回调（如服务崩溃），将状态重置为未绑定。
     *
     * @param name 断开连接的组件名
     */
    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
        bound = false;
    }

    /**
     * 绑定死亡回调（Android 8.0+），与 onServiceDisconnected 类似但表示绑定本身已失效。
     *
     * @param name 死亡的组件名
     */
    @Override
    public void onBindingDied(ComponentName name) {
        service = null;
        bound = false;
    }

    /**
     * 判断是否已成功连接到 MusicService。
     *
     * @return 已连接返回 true
     */
    public boolean isConnected() {
        return bound && service != null;
    }

    /**
     * 获取 MusicService 实例。调用前应先通过 {@link #isConnected()} 确认连接状态。
     *
     * @return MusicService 实例，未连接时为 null
     */
    public MusicService getService() {
        return service;
    }

    // ======================== 便捷播放控制方法 ========================

    /**
     * 播放指定歌单中的目标歌曲。若目标歌曲不在列表中，由 MusicService 负责追加。
     *
     * @param songs  歌曲列表
     * @param target 目标歌曲
     */
    public void playSongs(List<Song> songs, Song target) {
        MusicService s = service;
        if (s != null && target != null) {
            s.playSong(songs, target);
        }
    }

    /**
     * 切换播放/暂停状态。
     */
    public void toggle() {
        MusicService s = service;
        if (s != null) s.togglePlay();
    }

    /**
     * 切换到下一首歌曲。
     */
    public void next() {
        MusicService s = service;
        if (s != null) s.next();
    }

    /**
     * 切换到上一首歌曲。
     */
    public void prev() {
        MusicService s = service;
        if (s != null) s.prev();
    }

    /**
     * 跳转到指定播放进度。
     *
     * @param msec 目标位置，单位毫秒
     */
    public void seekTo(int msec) {
        MusicService s = service;
        if (s != null) s.seekTo(msec);
    }

    /**
     * 判断当前是否正在播放。未连接到服务时返回 false。
     *
     * @return 正在播放返回 true
     */
    public boolean isPlaying() {
        MusicService s = service;
        return s != null && s.isPlaying();
    }

    /**
     * 暴露播放状态的 LiveData，供 UI 层订阅以响应播放/暂停变化。
     *
     * @return 播放状态 LiveData
     */
    public LiveData<Boolean> playingState() {
        return PlaybackState.get().isPlaying;
    }
}

package com.musicplayer.app.player;

import androidx.lifecycle.MutableLiveData;

/**
 * 全局播放状态单例，通过 LiveData 将播放状态变化从 MusicService 推送至 UI 层。
 * <p>
 * 设计要点：采用双重检查锁定（DCL）实现线程安全的懒加载单例；
 * 所有字段均为 MutableLiveData，UI 通过 observe 自动响应变化，
 * 而 MusicService 作为唯一写入方通过 postValue 更新状态，实现服务与界面的解耦。
 * </p>
 */
public class PlaybackState {

    /** 是否正在播放 */
    public final MutableLiveData<Boolean> isPlaying = new MutableLiveData<>(false);
    /** 当前播放歌曲在队列中的索引，-1 表示未播放 */
    public final MutableLiveData<Integer> currentIndex = new MutableLiveData<>(-1);
    /** 当前歌曲的 mediaStoreId，用于列表高亮当前播放项 */
    public final MutableLiveData<Long> currentSongId = new MutableLiveData<>(-1L);
    /** 当前播放进度位置，单位毫秒 */
    public final MutableLiveData<Integer> position = new MutableLiveData<>(0);
    /** 当前歌曲总时长，单位毫秒 */
    public final MutableLiveData<Integer> duration = new MutableLiveData<>(0);
    /** 当前播放模式，默认为列表循环 */
    public final MutableLiveData<PlayMode> playMode = new MutableLiveData<>(PlayMode.LIST);
    /** 队列变更信号，值本身无意义，仅用于通知 UI 队列已更新 */
    public final MutableLiveData<Boolean> queueChanged = new MutableLiveData<>(false);

    /** 单例实例，使用 volatile 保证多线程可见性 */
    private static volatile PlaybackState instance;

    /** 私有构造，禁止外部实例化 */
    private PlaybackState() {
    }

    /**
     * 获取 PlaybackState 单例。采用双重检查锁定（DCL）保证线程安全。
     *
     * @return PlaybackState 单例实例
     */
    public static PlaybackState get() {
        if (instance == null) {
            synchronized (PlaybackState.class) {
                if (instance == null) {
                    instance = new PlaybackState();
                }
            }
        }
        return instance;
    }
}

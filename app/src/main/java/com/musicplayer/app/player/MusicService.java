package com.musicplayer.app.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;

import com.musicplayer.app.R;
import com.musicplayer.app.data.Playlist;
import com.musicplayer.app.data.PlaylistDao;
import com.musicplayer.app.data.Song;
import com.musicplayer.app.ui.MainActivity;
import com.musicplayer.app.util.UserManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 音乐播放核心前台服务，承担音频播放、播放队列管理、通知栏控制和 MediaSession 集成等全部职责。
 * <p>
 * 设计要点：继承 MediaBrowserServiceCompat 以支持 Android Auto / 蓝牙等外部媒体控制；
 * 通过 PlaybackState 单例中的 LiveData 将播放状态变化推送至 UI 层，实现服务与界面的解耦；
 * 使用前台通知保证后台播放时不被系统回收。
 * </p>
 */
public class MusicService extends androidx.media.MediaBrowserServiceCompat {

    private static final String TAG = "MusicService";
    /** 前台通知 ID */
    private static final int NOTIFICATION_ID = 0x1771;
    /** 通知渠道 ID */
    private static final String CHANNEL_ID = "netmusic_playback";

    // ======================== 通知栏动作常量 ========================

    /** 通知栏动作：播放 */
    public static final String ACTION_PLAY = "com.musicplayer.app.PLAY";
    /** 通知栏动作：暂停 */
    public static final String ACTION_PAUSE = "com.musicplayer.app.PAUSE";
    /** 通知栏动作：下一首 */
    public static final String ACTION_NEXT = "com.musicplayer.app.NEXT";
    /** 通知栏动作：上一首 */
    public static final String ACTION_PREV = "com.musicplayer.app.PREV";
    /** 通知栏动作：停止 */
    public static final String ACTION_STOP = "com.musicplayer.app.STOP";

    /** 本地 Binder 实例，供客户端通过 bindService 获取服务引用 */
    private final IBinder binder = new MusicBinder();

    /** 底层 MediaPlayer 实例，负责实际的音频解码与输出 */
    private MediaPlayer player;
    /** MediaSession 实例，用于与系统媒体控制框架（通知栏、蓝牙、Android Auto 等）交互 */
    private MediaSessionCompat mediaSession;
    /** 主线程 Handler，用于定时刷新播放进度等需要在主线程执行的任务 */
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 当前播放队列（歌曲列表） */
    private final List<Song> queue = new ArrayList<>();
    /** 当前播放歌曲在队列中的索引，-1 表示未播放 */
    private int currentIndex = -1;

    /** 标记 MediaPlayer 是否已准备就绪（prepareAsync 完成后置 true） */
    private boolean isPrepared = false;
    /** 播放列表数据访问对象，用于写入播放历史和累加播放次数 */
    private PlaylistDao playlistDao;

    /**
     * 进度刷新任务：每 500ms 读取一次 MediaPlayer 当前位置并通过 LiveData 推送给 UI。
     * 仅在播放状态下持续调度自身；暂停或停止时由调用方移除回调。
     */
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (player != null && isPrepared && player.isPlaying()) {
                PlaybackState.get().position.postValue(player.getCurrentPosition());
            }
            // 无论是否正在播放都继续调度，保证恢复播放时能立即刷新
            main.postDelayed(this, 500);
        }
    };

    /**
     * 处理绑定请求。若为 MediaBrowser 媒体浏览请求则交由父类处理，
     * 否则返回本地 Binder 供客户端直接调用服务方法。
     *
     * @param intent 绑定意图
     * @return 对应的 IBinder 对象
     */
    @Override
    public IBinder onBind(Intent intent) {
        if (intent != null && intent.getAction() != null
                && "android.media.browse.MediaBrowserService".equals(intent.getAction())) {
            return super.onBind(intent);
        }
        return binder;
    }

    /**
     * 服务创建时初始化 MediaPlayer、MediaSession、通知渠道等核心组件。
     */
    @Override
    public void onCreate() {
        super.onCreate();
        player = new MediaPlayer();
        // 歌曲播放完毕后的回调
        player.setOnCompletionListener(mp -> onSongComplete());
        // MediaPlayer 发生错误时跳到下一首，避免播放卡住
        player.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "MediaPlayer error: what=" + what + " extra=" + extra);
            next();
            return true;
        });
        // 异步准备完成后开始播放，并更新状态
        player.setOnPreparedListener(mp -> {
            isPrepared = true;
            player.start();
            PlaybackState.get().duration.postValue(mp.getDuration());
            PlaybackState.get().isPlaying.postValue(true);
            // 启动进度刷新定时任务
            main.post(progressTick);
            updateNotification();
            updateMediaSessionPlaybackState();
        });

        playlistDao = new PlaylistDao(this);
        setupMediaSession();
        createNotificationChannel();
    }

    /**
     * 处理通过 startService 发来的控制意图（如通知栏按钮点击）。
     * 返回 START_STICKY 保证服务被系统杀后自动重启。
     *
     * @param intent  启动意图，携带播放控制动作
     * @param flags   启动标志
     * @param startId 启动 ID
     * @return 启动模式
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            handleAction(intent.getAction());
        }
        return START_STICKY;
    }

    /**
     * 根据动作字符串分派到对应的播放控制方法。
     *
     * @param action 播放控制动作常量
     */
    private void handleAction(String action) {
        switch (action) {
            case ACTION_PLAY:
                play();
                break;
            case ACTION_PAUSE:
                pause();
                break;
            case ACTION_NEXT:
                next();
                break;
            case ACTION_PREV:
                prev();
                break;
            case ACTION_STOP:
                stop();
                break;
        }
    }

    // ======================== 队列管理 ========================

    /**
     * 设置播放队列并指定起始索引，可选是否立即播放。
     *
     * @param songs   新的播放队列
     * @param index   起始播放索引
     * @param playNow 是否立即开始播放
     */
    public void setQueue(List<Song> songs, int index, boolean playNow) {
        queue.clear();
        if (songs != null) queue.addAll(songs);
        currentIndex = index;
        PlaybackState.get().queueChanged.postValue(true);
        if (playNow && index >= 0 && index < queue.size()) {
            prepareAndPlay(queue.get(index));
        }
    }

    /**
     * 播放指定歌曲。若目标歌曲不在列表中则追加到队列末尾再播放。
     *
     * @param songs  包含目标歌曲的列表
     * @param target 要播放的目标歌曲
     */
    public void playSong(List<Song> songs, Song target) {
        queue.clear();
        queue.addAll(songs);
        int index = queue.indexOf(target);
        // 目标歌曲不在列表中，追加到末尾
        if (index < 0) {
            queue.add(target);
            index = queue.size() - 1;
        }
        currentIndex = index;
        PlaybackState.get().queueChanged.postValue(true);
        prepareAndPlay(target);
    }

    /**
     * 获取当前播放队列的只读视图，防止外部修改内部队列。
     *
     * @return 不可修改的歌曲列表
     */
    public List<Song> getQueue() {
        return Collections.unmodifiableList(queue);
    }

    /**
     * 获取当前播放歌曲在队列中的索引。
     *
     * @return 当前索引，-1 表示未播放
     */
    public int getCurrentIndex() {
        return currentIndex;
    }

    /**
     * 从队列中移除指定位置的歌曲。若移除的是当前正在播放的歌曲，
     * 则自动切换到下一首；若队列清空则停止播放。
     *
     * @param index 要移除的歌曲在队列中的索引
     */
    public void removeQueueItem(int index) {
        if (queue.isEmpty() || index < 0 || index >= queue.size()) return;
        if (index == currentIndex) {
            queue.remove(index);
            if (queue.isEmpty()) {
                // 队列清空，重置状态并停止播放
                currentIndex = -1;
                stop();
                PlaybackState.get().currentIndex.postValue(-1);
                PlaybackState.get().currentSongId.postValue(-1L);
            } else {
                // 若索引越界则回到队首
                if (currentIndex >= queue.size()) currentIndex = 0;
                prepareAndPlay(queue.get(currentIndex));
            }
        } else {
            queue.remove(index);
            // 移除位置在当前播放项之前，需调整当前索引
            if (index < currentIndex) currentIndex--;
            PlaybackState.get().queueChanged.postValue(true);
        }
    }

    /**
     * 跳转到队列中指定位置并开始播放。
     *
     * @param index 目标位置索引
     */
    public void playQueueItem(int index) {
        if (index < 0 || index >= queue.size()) return;
        currentIndex = index;
        prepareAndPlay(queue.get(currentIndex));
    }

    /**
     * 获取当前正在播放的歌曲对象。
     *
     * @return 当前歌曲，未播放时返回 null
     */
    public Song getCurrentSong() {
        if (currentIndex >= 0 && currentIndex < queue.size()) {
            return queue.get(currentIndex);
        }
        return null;
    }

    // ======================== 播放控制 ========================

    /**
     * 重置 MediaPlayer 并异步准备播放指定歌曲。
     * 准备完成后由 onPrepared 回调自动开始播放。
     * 同时更新 PlaybackState 中的当前索引/歌曲 ID/进度，
     * 并将歌曲写入最近播放历史、累加播放次数。
     *
     * @param song 要播放的歌曲
     */
    private void prepareAndPlay(Song song) {
        if (song == null) return;
        isPrepared = false;
        try {
            player.reset();
            player.setDataSource(this, song.getContentUri());
            player.setLooping(false);
            // 异步准备，避免阻塞主线程
            player.prepareAsync();
        } catch (IOException e) {
            Log.e(TAG, "setDataSource failed: " + e.getMessage(), e);
            // 数据源设置失败时跳到下一首，避免播放卡住
            next();
            return;
        }

        // 立即更新 UI 状态，不等待 prepareAsync 完成
        PlaybackState.get().currentIndex.postValue(currentIndex);
        PlaybackState.get().currentSongId.postValue(song.mediaStoreId);
        PlaybackState.get().position.postValue(0);
        updateMediaSessionMetadata(song);
        // 写入最近播放历史 + 累加播放次数
        try {
            PlaylistDao dao = playlistDao;
            long userId = UserManager.get(getApplicationContext()).getUserId();
            Playlist history = dao.getByType(userId, Playlist.TYPE_HISTORY);
            if (history != null) {
                // 先读取旧的播放次数，移除后重新添加到顶部时保留
                long oldCount = dao.getPlayCount(userId, song.mediaStoreId);
                dao.removeSong(history.id, song.mediaStoreId);
                dao.addSong(history.id, song, oldCount);
            }
            dao.incrementPlayCount(userId, song.mediaStoreId);
        } catch (Exception ignore) {
        }
    }

    /**
     * 继续播放当前歌曲（从暂停处恢复）。
     */
    public void play() {
        if (isPrepared && !player.isPlaying()) {
            player.start();
            PlaybackState.get().isPlaying.postValue(true);
            // 恢复进度刷新定时任务
            main.post(progressTick);
            updateNotification();
            updateMediaSessionPlaybackState();
        }
    }

    /**
     * 暂停当前播放，保留播放位置以便恢复。
     */
    public void pause() {
        if (player != null && player.isPlaying()) {
            player.pause();
            PlaybackState.get().isPlaying.postValue(false);
            // 暂停时停止进度刷新以节省资源
            main.removeCallbacks(progressTick);
            // 立即刷新一次当前位置，确保 UI 显示准确的暂停位置
            if (isPrepared) {
                PlaybackState.get().position.postValue(player.getCurrentPosition());
            }
            updateNotification();
            updateMediaSessionPlaybackState();
        }
    }

    /**
     * 切换播放/暂停状态。正在播放则暂停，暂停中则恢复播放。
     */
    public void togglePlay() {
        if (isPrepared && player.isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    /**
     * 切换到下一首歌曲。根据当前播放模式决定下一曲：
     * <ul>
     *   <li>单曲循环 (REPEAT_ONE)：重新播放当前歌曲</li>
     *   <li>随机播放 (SHUFFLE)：随机选择一首不同的歌曲</li>
     *   <li>列表循环 (LIST)：播放队列中下一首，到末尾则回到第一首</li>
     * </ul>
     */
    public void next() {
        if (queue.isEmpty()) return;
        PlayMode mode = PlaybackState.get().playMode.getValue();
        if (mode == PlayMode.REPEAT_ONE) {
            // 单曲循环：重新准备并播放当前歌曲
            prepareAndPlay(queue.get(currentIndex));
        } else if (mode == PlayMode.SHUFFLE && queue.size() > 1) {
            // 随机播放：随机选取一个不同于当前索引的位置
            int next;
            do {
                next = (int) (Math.random() * queue.size());
            } while (next == currentIndex);
            currentIndex = next;
            prepareAndPlay(queue.get(currentIndex));
        } else {
            // 列表循环：索引 +1，到末尾回到 0
            currentIndex = (currentIndex + 1) % queue.size();
            prepareAndPlay(queue.get(currentIndex));
        }
    }

    /**
     * 切换到上一首歌曲。若当前歌曲已播放超过 3 秒则回到开头，
     * 否则根据播放模式切到上一曲：
     * <ul>
     *   <li>单曲循环 (REPEAT_ONE)：重新播放当前歌曲</li>
     *   <li>随机播放 (SHUFFLE)：随机选择一首不同的歌曲</li>
     *   <li>列表循环 (LIST)：播放队列中上一首，到开头则跳到末尾</li>
     * </ul>
     */
    public void prev() {
        if (queue.isEmpty()) return;
        // 播放超过 3 秒则回到歌曲开头，不切换歌曲
        if (isPrepared && player.getCurrentPosition() > 3000) {
            seekTo(0);
            return;
        }
        PlayMode mode = PlaybackState.get().playMode.getValue();
        if (mode == PlayMode.REPEAT_ONE) {
            // 单曲循环：重新准备并播放当前歌曲
            prepareAndPlay(queue.get(currentIndex));
        } else if (mode == PlayMode.SHUFFLE && queue.size() > 1) {
            // 随机播放：随机选取一个不同于当前索引的位置
            int prev;
            do {
                prev = (int) (Math.random() * queue.size());
            } while (prev == currentIndex);
            currentIndex = prev;
            prepareAndPlay(queue.get(currentIndex));
        } else {
            // 列表循环：索引 -1，到 -1 则跳到队列末尾
            currentIndex = currentIndex - 1;
            if (currentIndex < 0) currentIndex = queue.size() - 1;
            prepareAndPlay(queue.get(currentIndex));
        }
    }

    /**
     * 跳转到指定播放进度位置。
     *
     * @param msec 目标位置，单位毫秒
     */
    public void seekTo(int msec) {
        if (isPrepared) {
            player.seekTo(msec);
            PlaybackState.get().position.postValue(msec);
        }
    }

    /**
     * 切换播放模式，按循环顺序：列表循环 → 单曲循环 → 随机播放 → 列表循环。
     *
     * @return 切换后的新播放模式
     */
    public PlayMode togglePlayMode() {
        PlayMode current = PlaybackState.get().playMode.getValue();
        PlayMode next = current.next();
        PlaybackState.get().playMode.postValue(next);
        return next;
    }

    /**
     * 停止播放并清除播放状态，同时移除前台通知。
     */
    public void stop() {
        if (player != null) {
            player.pause();
            player.seekTo(0);
        }
        isPrepared = false;
        // 停止进度刷新
        main.removeCallbacks(progressTick);
        PlaybackState.get().isPlaying.postValue(false);
        stopForeground(true);
    }

    /**
     * 判断当前是否正在播放。
     *
     * @return 正在播放返回 true
     */
    public boolean isPlaying() {
        return player != null && isPrepared && player.isPlaying();
    }

    /**
     * 获取当前播放位置。
     *
     * @return 当前位置（毫秒），未准备好时返回 0
     */
    public int getPosition() {
        return isPrepared ? player.getCurrentPosition() : 0;
    }

    /**
     * 获取当前歌曲总时长。
     *
     * @return 总时长（毫秒），未准备好时返回 0
     */
    public int getDuration() {
        return isPrepared ? player.getDuration() : 0;
    }

    /**
     * 歌曲自然播放完毕后的回调。根据播放模式决定下一步操作：
     * 单曲循环则重新播放当前歌曲，否则切换到下一首。
     */
    private void onSongComplete() {
        PlayMode mode = PlaybackState.get().playMode.getValue();
        if (mode == PlayMode.REPEAT_ONE) {
            // 单曲循环模式：直接重新开始播放，无需重新 prepare
            player.start();
            return;
        }
        next();
    }

    // ======================== 通知与 MediaSession ========================

    /**
     * 初始化 MediaSession，设置支持的传输控制动作并注册回调，
     * 使系统媒体控制框架（通知栏、蓝牙、Android Auto 等）能控制播放。
     */
    private void setupMediaSession() {
        android.content.ComponentName component =
                new android.content.ComponentName(this, MusicService.class);
        mediaSession = new MediaSessionCompat(this, "MusicPlayer", component,
                MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY));
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                prev();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) (pos & 0x7FFFFFFFL));
            }
        });
        // 将 session token 注册到 Service，使外部 MediaBrowser 能连接
        setSessionToken(mediaSession.getSessionToken());
    }

    /**
     * 更新 MediaSession 中的媒体元数据（歌曲标题、歌手、时长），
     * 用于通知栏和锁屏界面展示歌曲信息。
     *
     * @param song 当前播放的歌曲
     */
    private void updateMediaSessionMetadata(Song song) {
        MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,
                        song.title == null ? "未知歌曲" : song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,
                        song.artist == null ? "未知歌手" : song.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration);
        mediaSession.setMetadata(b.build());
    }

    /**
     * 更新 MediaSession 的播放状态（播放/暂停）及支持的传输控制动作，
     * 使系统媒体控制框架能正确显示当前状态并响应操作。
     */
    private void updateMediaSessionPlaybackState() {
        int state = isPlaying() ? PlaybackStateCompat.STATE_PLAYING
                : PlaybackStateCompat.STATE_PAUSED;
        long pos = isPrepared ? player.getCurrentPosition() : 0;
        PlaybackStateCompat.Builder b = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_SEEK_TO);
        // 播放速度：播放中为 1.0 倍速，暂停时为 0
        b.setState(state, pos, isPlaying() ? 1f : 0f);
        mediaSession.setPlaybackState(b.build());
    }

    /**
     * 创建通知渠道（Android 8.0+ 必需），设置为低优先级避免弹出提示。
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "播放控制", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示当前播放歌曲与控制按钮");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    /**
     * 构建前台播放通知，显示当前歌曲信息和播放控制按钮（上一首、播放/暂停、下一首）。
     * 使用 MediaStyle 使通知支持媒体控制。
     *
     * @return 构建好的 Notification 对象
     */
    public Notification buildNotification() {
        Song song = getCurrentSong();
        String title = song == null ? "云音乐" : song.title;
        String artist = song == null ? "" : (song.artist == null ? "" : song.artist);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(title)
                .setContentText(artist)
                .setOngoing(isPlaying())
                .setShowWhen(false)
                .setColor(ContextCompat.getColor(this, R.color.netease_red))
                .setContentIntent(openAppIntent());

        // 通知栏控制按钮：上一首、播放/暂停、下一首
        builder.addAction(R.drawable.ic_notif_prev, "上一首",
                buildAction(ACTION_PREV));
        if (isPlaying()) {
            builder.addAction(R.drawable.ic_notif_pause, "暂停",
                    buildAction(ACTION_PAUSE));
        } else {
            builder.addAction(R.drawable.ic_notif_play, "播放",
                    buildAction(ACTION_PLAY));
        }
        builder.addAction(R.drawable.ic_notif_next, "下一首",
                buildAction(ACTION_NEXT));

        // MediaStyle 关联 MediaSession，在紧凑视图中显示 3 个按钮
        builder.setStyle(new MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2)
                .setCancelButtonIntent(buildAction(ACTION_STOP)));

        return builder.build();
    }

    /**
     * 更新已显示的前台通知内容（如切换歌曲或播放状态变化时调用）。
     */
    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    /**
     * 构建点击通知内容区域时打开主界面的 PendingIntent。
     * 使用 FLAG_ACTIVITY_SINGLE_TOP 避免重复创建 Activity 实例。
     *
     * @return PendingIntent 对象
     */
    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 构建通知栏动作按钮对应的 PendingIntent，点击后发送对应 Action 的 Intent 给 Service。
     *
     * @param action 动作常量（如 ACTION_PLAY）
     * @return PendingIntent 对象
     */
    private PendingIntent buildAction(String action) {
        Intent i = new Intent(this, MusicService.class).setAction(action);
        return PendingIntent.getService(this, action.hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 将服务提升为前台服务，显示常驻通知以保证后台播放不被系统回收。
     * Android 14 (UPSIDE_DOWN_CAKE) 及以上需指定前台服务类型为 MEDIA_PLAYBACK。
     */
    public void promoteToForeground() {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed: " + e.getMessage());
        }
    }

    /**
     * 用户从最近任务列表划掉应用时的回调，停止播放并清理资源。
     *
     * @param rootIntent 最近任务的根 Intent
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stop();
        super.onTaskRemoved(rootIntent);
    }

    /**
     * 服务销毁时释放 MediaPlayer、MediaSession 等资源，并停止进度刷新。
     */
    @Override
    public void onDestroy() {
        main.removeCallbacks(progressTick);
        if (player != null) {
            player.release();
            player = null;
        }
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    /**
     * MediaBrowser 连接时返回根节点。本应用不提供媒体浏览能力，
     * 但必须实现此方法以满足 MediaBrowserServiceCompat 接口要求。
     *
     * @param clientPackageName 客户端包名
     * @param clientUid         客户端 UID
     * @param rootHints         客户端提示信息
     * @return 空的 BrowserRoot
     */
    @Nullable
    @Override
    public BrowserRoot onGetRoot(
            String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot("", null);
    }

    /**
     * 加载指定父节点下的子媒体项。本应用不提供媒体浏览，直接返回空列表。
     *
     * @param parentId 父节点 ID
     * @param result   结果回调
     */
    @Override
    public void onLoadChildren(@NonNull String parentId,
            @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.sendResult(Collections.emptyList());
    }

    /**
     * 本地 Binder 类，供客户端通过 bindService 获取 MusicService 实例。
     */
    public class MusicBinder extends Binder {
        /**
         * 获取绑定的 MusicService 实例。
         *
         * @return MusicService 实例
         */
        public MusicService getService() {
            return MusicService.this;
        }
    }
}

package com.musicplayer.app.ui.mini;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LifecycleOwner;

import com.musicplayer.app.R;
import com.musicplayer.app.data.Song;
import com.musicplayer.app.player.MusicService;
import com.musicplayer.app.player.MusicServiceConnector;
import com.musicplayer.app.player.PlaybackState;
import com.musicplayer.app.ui.playing.PlayQueueDialogFragment;
import com.musicplayer.app.util.CoverLoader;
import com.musicplayer.app.util.FormatUtil;

/**
 * 底部迷你播放栏控件，以 ConstraintLayout 为基类实现自定义 View。
 * 显示当前歌曲的封面缩略图、标题、歌手、播放/暂停按钮和播放列表按钮，
 * 顶部有一条进度条实时反映播放进度。点击整个区域跳转全屏播放页，
 * 点击播放列表按钮弹出播放队列面板。
 * 通过订阅 PlaybackState 的 LiveData 实时同步播放状态和进度。
 */
public class MiniPlayerView extends ConstraintLayout {

    /** 封面缩略图 */
    private ImageView cover;
    /** 歌曲标题 */
    private TextView title;
    /** 歌手名称 */
    private TextView artist;
    /** 播放/暂停按钮 */
    private ImageView playBtn;
    /** 播放列表按钮 */
    private ImageView queueBtn;
    /** 顶部进度条填充视图 */
    private View progressFill;

    /** 音乐服务连接器 */
    private MusicServiceConnector connector;

    public MiniPlayerView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public MiniPlayerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public MiniPlayerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    /**
     * 初始化视图：加载布局、绑定控件、设置默认隐藏。
     * @param context 上下文
     */
    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_mini_player, this, true);
        cover = findViewById(R.id.mini_cover);
        title = findViewById(R.id.mini_title);
        artist = findViewById(R.id.mini_artist);
        playBtn = findViewById(R.id.mini_play);
        queueBtn = findViewById(R.id.mini_play_list);
        progressFill = findViewById(R.id.mini_progress_fill);
        // 默认隐藏，有播放队列时由 MainActivity 控制显示
        setVisibility(GONE);

        View divider = findViewById(R.id.mini_progress);
        if (divider != null) {
            divider.setBackgroundColor(
                    context.getResources().getColor(R.color.divider));
        }
    }

    /**
     * 绑定 MusicServiceConnector 并订阅播放状态，设置按钮点击事件。
     * @param connector 已绑定的音乐服务连接器
     */
    public void bind(@NonNull MusicServiceConnector connector) {
        this.connector = connector;

        playBtn.setOnClickListener(v -> {
            if (connector.isConnected()) {
                connector.toggle();
            }
        });
        queueBtn.setOnClickListener(v -> showQueueDialog());

        observeState();
    }

    /**
     * 手动刷新一次显示，从 PlaybackState 读取当前状态更新 UI。
     * 通常在服务绑定成功后调用。
     */
    public void refresh() {
        PlaybackState ps = PlaybackState.get();
        updateFromState(ps);
    }

    /**
     * 弹出播放队列面板，需要宿主 Context 为 FragmentActivity。
     */
    private void showQueueDialog() {
        Context c = getContext();
        if (!(c instanceof FragmentActivity)) return;
        if (!connector.isConnected()) return;
        PlayQueueDialogFragment dialog = new PlayQueueDialogFragment();
        dialog.setConnector(connector);
        dialog.show(((FragmentActivity) c).getSupportFragmentManager(), "play_queue");
    }

    /**
     * 订阅 PlaybackState 的 LiveData，实时更新封面信息、播放按钮图标和进度条。
     * 宿主 Context 必须实现 LifecycleOwner 接口（Activity 即可），
     * LiveData 会自动在生命周期内订阅和解绑。
     */
    private void observeState() {
        if (!(getContext() instanceof LifecycleOwner)) return;
        LifecycleOwner owner = (LifecycleOwner) getContext();

        // 当前歌曲变化 → 更新封面和信息，有歌曲时显示，无歌曲时隐藏
        PlaybackState.get().currentIndex.observe(owner, idx -> {
            if (idx != null && idx >= 0 && connector != null && connector.isConnected()) {
                Song song = connector.getService().getCurrentSong();
                if (song != null) {
                    cover.setTag(R.id.cover_tag_song_id, (int) song.mediaStoreId);
                    CoverLoader.get().display(song, cover);
                    title.setText(song.title);
                    artist.setText(song.artist);
                    setVisibility(VISIBLE);
                }
            } else if (idx == null || idx < 0) {
                setVisibility(GONE);
            }
        });

        // 播放状态变化 → 切换播放/暂停按钮图标
        PlaybackState.get().isPlaying.observe(owner, playing -> {
            if (playing != null) {
                playBtn.setImageResource(playing
                        ? R.drawable.ic_mini_pause
                        : R.drawable.ic_mini_play);
            }
        });

        // 播放位置变化 → 更新顶部进度条的宽度
        // 进度条宽度按当前播放位置与总时长的比例计算
        PlaybackState.get().position.observe(owner, pos -> {
            if (pos == null) return;
            Integer dur = PlaybackState.get().duration.getValue();
            if (dur != null && dur > 0) {
                int w = getWidth();
                if (w > 0) {
                    // 按比例计算进度条填充宽度
                    int fill = (int) ((float) pos / dur * w);
                    progressFill.getLayoutParams().width = fill;
                    progressFill.requestLayout();
                }
            }
        });
    }

    /**
     * 根据 PlaybackState 手动更新 UI（非 LiveData 触发），
     * 用于服务绑定成功后的首次刷新。
     * @param ps PlaybackState 实例
     */
    private void updateFromState(PlaybackState ps) {
        Integer idx = ps.currentIndex.getValue();
        if (idx != null && idx >= 0 && connector != null && connector.isConnected()) {
            Song song = connector.getService().getCurrentSong();
            if (song != null) {
                cover.setTag(R.id.cover_tag_song_id, (int) song.mediaStoreId);
                CoverLoader.get().display(song, cover);
                title.setText(song.title);
                artist.setText(song.artist);
                setVisibility(VISIBLE);

                Boolean playing = ps.isPlaying.getValue();
                if (playing != null) {
                    playBtn.setImageResource(playing
                            ? R.drawable.ic_mini_pause
                            : R.drawable.ic_mini_play);
                }
            }
        }
    }
}

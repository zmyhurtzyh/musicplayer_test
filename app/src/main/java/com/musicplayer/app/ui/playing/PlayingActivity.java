package com.musicplayer.app.ui.playing;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.musicplayer.app.R;
import com.musicplayer.app.data.Lyric;
import com.musicplayer.app.data.LyricParser;
import com.musicplayer.app.data.Playlist;
import com.musicplayer.app.data.PlaylistDao;
import com.musicplayer.app.data.Song;
import com.musicplayer.app.databinding.ActivityPlayingBinding;
import com.musicplayer.app.player.MusicServiceConnector;
import com.musicplayer.app.player.PlayMode;
import com.musicplayer.app.player.PlaybackState;
import com.musicplayer.app.util.CoverLoader;
import com.musicplayer.app.util.FormatUtil;
import com.musicplayer.app.util.UserManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 全屏播放页面，是应用的核心交互界面之一。
 * 上半部分通过 ViewPager2 承载"黑胶唱片封面页"和"歌词页"两个 Fragment，
 * 支持左右滑动切换并用圆点指示器标识当前页面；
 * 下半部分包含进度条、播放控制按钮（上一首/播放暂停/下一首）、
 * 播放模式切换、收藏、加入歌单、打开播放队列等功能。
 * 设计上采用 ViewPager2 + FragmentStateAdapter 的架构，
 * 通过 AtomicReference 持有子 Fragment 引用以实现跨 Fragment 通信。
 */
public class PlayingActivity extends AppCompatActivity {

    private ActivityPlayingBinding binding;

    /** 音乐服务连接器，用于与 MusicService 通信 */
    private final MusicServiceConnector connector = new MusicServiceConnector();
    /** 主线程 Handler，用于从子线程切换回主线程更新 UI */
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 当前歌曲的歌词列表 */
    private final List<Lyric> lyrics = new ArrayList<>(LyricParser.EMPTY);
    /** 歌词页 Fragment 的引用，使用 AtomicReference 保证线程安全 */
    private final AtomicReference<LyricPageFragment> lyricFragmentRef = new AtomicReference<>();
    /** 封面页 Fragment 的引用，使用 AtomicReference 保证线程安全 */
    private final AtomicReference<DiscPageFragment> discFragmentRef = new AtomicReference<>();

    /** 歌单数据访问对象，用于收藏和加入歌单操作 */
    private PlaylistDao playlistDao;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 沉浸式状态栏：透明背景 + 白色图标（适配深色背景）
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        // 深色背景需要白色状态栏图标，因此移除浅色状态栏标志
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        binding = ActivityPlayingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        playlistDao = new PlaylistDao(this);

        setupPager();
        setupSeekBar();
        setupControls();
        observeState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 绑定音乐服务，绑定成功后刷新全部 UI
        connector.bind(this, () -> refreshAll());
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 解绑音乐服务，防止内存泄漏
        connector.unbind(this);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    /** 启动唱片旋转动画，委托给 DiscPageFragment 执行 */
    private void startDiscRotate() {
        DiscPageFragment f = discFragmentRef.get();
        if (f != null) f.startRotate();
    }

    /** 停止唱片旋转动画，委托给 DiscPageFragment 执行 */
    private void stopDiscRotate() {
        DiscPageFragment f = discFragmentRef.get();
        if (f != null) f.stopRotate();
    }

    /**
     * 初始化 ViewPager2，设置封面页和歌词页的适配器及页面切换监听。
     * 采用 ViewPager2 + FragmentStateAdapter 架构，
     * 将上半区域拆分为两个独立 Fragment，各自管理视图和动画逻辑，
     * 页面切换时更新底部圆点指示器的选中状态。
     */
    private void setupPager() {
        binding.playingPager.setAdapter(new PlayingPagerAdapter(this));
        // 页面切换时更新指示器圆点的选中/未选中状态
        binding.playingPager.registerOnPageChangeCallback(
                new androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                    @Override
                    public void onPageSelected(int position) {
                        binding.indicator0.setImageResource(
                                position == 0 ? R.drawable.dot_indicator_selected
                                              : R.drawable.dot_indicator_unselected);
                        binding.indicator1.setImageResource(
                                position == 1 ? R.drawable.dot_indicator_selected
                                              : R.drawable.dot_indicator_unselected);
                    }
                });
    }

    /**
     * ViewPager2 的 FragmentStateAdapter，承载封面页和歌词页两个 Fragment。
     * 通过 FragmentStateAdapter 实现懒加载和生命周期管理，
     * 创建 Fragment 时将引用存入 AtomicReference，供 Activity 跨 Fragment 通信。
     */
    private class PlayingPagerAdapter extends FragmentStateAdapter {

        /** 封面页（黑胶唱片）的页面索引 */
        private static final int PAGE_DISC = 0;
        /** 歌词页的页面索引 */
        private static final int PAGE_LYRIC = 1;

        PlayingPagerAdapter(@NonNull FragmentActivity fa) {
            super(fa);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            if (position == PAGE_LYRIC) {
                LyricPageFragment f = new LyricPageFragment();
                lyricFragmentRef.set(f);
                return f;
            }
            DiscPageFragment f = new DiscPageFragment();
            discFragmentRef.set(f);
            return f;
        }

        @Override
        public int getItemCount() {
            return 2;
        }
    }

    /**
     * 黑胶唱片封面页 Fragment，展示旋转唱片、封面图片、唱针动画和歌曲信息。
     * 核心设计要点：
     * - 唱片旋转采用"接力式"动画：每次旋转 360°，动画结束时若仍在播放则启动下一轮，
     *   并从当前角度继续旋转，从而实现不跳帧的无限旋转效果。
     * - 唱针动画以右上角为支点，播放时落下（旋转到 0°），暂停时抬起（旋转到 -20°），
     *   分别使用 OvershootInterpolator 和 AccelerateDecelerateInterpolator 增强真实感。
     */
    public static class DiscPageFragment extends Fragment {

        /** 唱片容器视图，承载旋转动画 */
        private View discContainer;
        /** 封面图片 */
        private ImageView cover;
        /** 唱针图片，以右上角为支点做旋转动画 */
        private ImageView tonearm;
        /** 歌曲名称文本 */
        private TextView songName;
        /** 歌手名称文本 */
        private TextView artist;

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.page_disc, container, false);
            discContainer = v.findViewById(R.id.disc_container);
            cover = v.findViewById(R.id.disc_cover);
            tonearm = v.findViewById(R.id.tonearm);
            songName = v.findViewById(R.id.disc_song_name);
            artist = v.findViewById(R.id.disc_artist);
            // 设置唱针支点在右上角（基于 180×180 的设计尺寸按比例换算）
            // 支点位置决定了唱针旋转的物理效果，偏离支点会导致动画不自然
            if (tonearm != null) {
                tonearm.post(() -> {
                    if (tonearm.getWidth() > 0) {
                        tonearm.setPivotX(tonearm.getWidth() * 155f / 180f);
                        tonearm.setPivotY(tonearm.getHeight() * 25f / 180f);
                        // 根据当前播放状态设置初始唱针位置，
                        // 避免 Fragment 重建时唱针位置与播放状态不一致
                        Boolean playing = PlaybackState.get().isPlaying.getValue();
                        tonearm.setRotation(playing != null && playing ? 0f : -20f);
                    }
                });
            }
            return v;
        }

        @Override
        public void onResume() {
            super.onResume();
            PlayingActivity act = (PlayingActivity) requireActivity();
            Boolean playing = PlaybackState.get().isPlaying.getValue();
            if (playing != null && playing) {
                startRotate();
            }
            refreshDisc();
        }

        /**
         * 启动唱片无限旋转动画。
         * 核心机制：每次动画将唱片从当前角度旋转 360°，动画时长 20 秒。
         * 动画结束时检查播放状态，若仍在播放则递归调用自身开启下一轮旋转。
         * 由于每次都从 discContainer.getRotation() 获取当前角度，
         * 不会出现角度跳变，实现了无缝衔接的不跳帧旋转效果。
         */
        void startRotate() {
            if (discContainer == null) return;
            // 从当前角度继续旋转，避免跳帧：获取实时旋转角度作为起始值
            float currentRotation = discContainer.getRotation();
            discContainer.animate()
                    .rotation(currentRotation + 360f)
                    .setDuration(20000)
                    .setInterpolator(new LinearInterpolator())
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            // 动画结束后若仍在播放，递归启动下一轮旋转
                            if (PlaybackState.get().isPlaying.getValue() == Boolean.TRUE) {
                                startRotate();
                            }
                        }
                    })
                    .start();
        }

        /**
         * 暂停旋转动画，保持唱片当前角度不变。
         * 取消动画后 View 会停留在取消时的旋转角度，
         * 下次 startRotate 时会从该角度继续，实现暂停/恢复的无缝衔接。
         */
        void stopRotate() {
            if (discContainer != null) {
                discContainer.animate().cancel();
            }
        }

        /**
         * 唱针落下动画（播放时调用）。
         * 唱针以右上角为支点旋转到 0°，同时透明度恢复为 1.0，
         * 使用 OvershootInterpolator 模拟唱针落下后的轻微回弹效果，
         * 增强物理真实感。
         */
        void lowerTonearm() {
            if (tonearm == null || tonearm.getWidth() == 0) return;
            tonearm.setPivotX(tonearm.getWidth() * 155f / 180f);
            tonearm.setPivotY(tonearm.getHeight() * 25f / 180f);
            tonearm.animate()
                    .rotation(0f)
                    .alpha(1.0f)
                    .setDuration(500)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(0.5f))
                    .start();
        }

        /**
         * 唱针抬起动画（暂停时调用）。
         * 唱针以右上角为支点旋转到 -20°，同时透明度降为 0.8，
         * 使用 AccelerateDecelerateInterpolator 模拟唱针抬起时的先加速后减速效果。
         */
        void raiseTonearm() {
            if (tonearm == null || tonearm.getWidth() == 0) return;
            tonearm.setPivotX(tonearm.getWidth() * 155f / 180f);
            tonearm.setPivotY(tonearm.getHeight() * 25f / 180f);
            tonearm.animate()
                    .rotation(-20f)
                    .alpha(0.8f)
                    .setDuration(500)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .start();
        }

        /**
         * 刷新封面图片和歌曲文字信息。
         * @param song 当前播放的歌曲，用于加载封面和显示标题/歌手
         */
        void refresh(Song song) {
            if (song == null || cover == null) return;
            // 设置 cover 的 tag 为歌曲 ID，供 CoverLoader 防止 ImageView 复用导致的图片错乱
            cover.setTag(R.id.cover_tag_song_id, (int) song.mediaStoreId);
            CoverLoader.get().display(song, cover);
            if (songName != null) songName.setText(song.title);
            if (artist != null) artist.setText(song.artist);
        }

        /** 从 MusicService 获取当前歌曲并刷新封面页 */
        private void refreshDisc() {
            PlayingActivity act = (PlayingActivity) requireActivity();
            if (act.connector.isConnected()) {
                refresh(act.connector.getService().getCurrentSong());
            }
        }
    }

    /**
     * 歌词页 Fragment，展示可同步滚动高亮的歌词列表。
     * 歌词数据由 PlayingActivity 通过 loadLyric() 加载后传入，
     * 本 Fragment 负责歌词列表的渲染和高亮同步滚动。
     * 点击歌词行可跳转到对应播放时间点，实现"点击歌词定位播放"的交互。
     */
    public static class LyricPageFragment extends Fragment {

        /** 歌词列表视图 */
        private ListView listView;
        /** 无歌词时的空状态提示 */
        private TextView emptyView;
        /** 歌词列表适配器 */
        private LyricAdapter adapter;

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.page_lyric, container, false);
            listView = v.findViewById(R.id.lyric_list);
            emptyView = v.findViewById(R.id.lyric_empty);
            adapter = new LyricAdapter(requireContext());
            listView.setAdapter(adapter);
            // 点击歌词行跳转到对应时间：将歌词的时间戳传给播放服务 seekTo
            listView.setOnItemClickListener((parent, view, position, id) -> {
                Lyric l = adapter.getItem(position);
                PlayingActivity act = (PlayingActivity) requireActivity();
                if (l != null && act.connector.isConnected()) {
                    act.connector.seekTo((int) l.time);
                }
            });
            // Fragment 创建时用 Activity 已有的歌词数据刷新列表
            PlayingActivity act = (PlayingActivity) requireActivity();
            updateLyrics(act.lyrics);
            return v;
        }

        /**
         * 更新歌词数据并刷新列表显示。
         * @param newLyrics 新的歌词列表，为空或 null 时显示空状态提示
         */
        void updateLyrics(List<Lyric> newLyrics) {
            if (adapter != null) {
                adapter.setLyrics(newLyrics);
            }
            if (listView != null && emptyView != null) {
                if (newLyrics == null || newLyrics.isEmpty()) {
                    emptyView.setVisibility(View.VISIBLE);
                    listView.setVisibility(View.GONE);
                } else {
                    emptyView.setVisibility(View.GONE);
                    listView.setVisibility(View.VISIBLE);
                }
            }
        }

        /**
         * 高亮指定歌词行并自动滚动到该行。
         * @param line 需要高亮的歌词行索引
         */
        void highlightLine(int line) {
            highlightLine(line, true);
        }

        /**
         * 高亮指定歌词行，可控制是否滚动。
         * @param line  需要高亮的歌词行索引
         * @param scroll true 则自动滚动到该行，false 仅更新高亮不滚动（如拖动进度条时）
         */
        void highlightLine(int line, boolean scroll) {
            if (adapter != null) {
                adapter.setHighlight(line);
            }
            if (scroll && listView != null && line >= 0 && line < listView.getAdapter().getCount()) {
                listView.setSelection(line);
            }
        }

        /**
         * 歌词列表适配器，负责歌词行的渲染和高亮效果。
         * 高亮行以红色加粗大号字体显示并带有轻微放大和上浮动画，
         * 非高亮行以灰色小号字体半透明显示，形成视觉层次。
         * 通过 lastLyricLine 记录上一次高亮行号，仅在行号变化时才刷新列表，避免高频刷新导致卡顿。
         */
        static class LyricAdapter extends BaseAdapter {
            /** 高亮行文字颜色（网易红） */
            private final int highlightColor;
            /** 普通行文字颜色（白色次要） */
            private final int normalColor;
            /** 高亮行文字大小（sp 转 px） */
            private final int highlightSize;
            /** 普通行文字大小（sp 转 px） */
            private final int normalSize;
            /** 歌词数据列表 */
            private List<Lyric> data = new ArrayList<>();
            /** 上一次高亮的行号，用于去重刷新 */
            private int lastLyricLine = -1;

            LyricAdapter(Context ctx) {
                highlightColor = ctx.getResources().getColor(R.color.netease_red);
                normalColor = ctx.getResources().getColor(R.color.text_white_secondary);
                highlightSize = (int) (19 * ctx.getResources().getDisplayMetrics().scaledDensity);
                normalSize = (int) (13 * ctx.getResources().getDisplayMetrics().scaledDensity);
            }

            /** 设置歌词数据并刷新列表 */
            void setLyrics(List<Lyric> l) {
                data = l == null ? new ArrayList<>() : l;
                lastLyricLine = -1;
                notifyDataSetChanged();
            }

            /**
             * 设置高亮行号，仅在行号变化时才刷新，避免高频刷新卡顿。
             * @param line 需要高亮的行索引
             */
            void setHighlight(int line) {
                if (lastLyricLine != line) {
                    lastLyricLine = line;
                    notifyDataSetChanged();
                }
            }

            @Override
            public int getCount() {
                return data.size();
            }

            @Override
            public Lyric getItem(int position) {
                return data.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv;
                if (convertView == null) {
                    tv = new TextView(parent.getContext());
                    tv.setGravity(android.view.Gravity.CENTER);
                    int pad = (int) (14 * parent.getResources().getDisplayMetrics().density);
                    tv.setPadding(pad, pad, pad, pad);
                    tv.setMaxLines(2);
                    tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                } else {
                    tv = (TextView) convertView;
                }
                Lyric l = data.get(position);
                tv.setText(l.text);
                boolean highlight = (position == lastLyricLine);
                // 高亮行：红色加粗大号字，完全透明；普通行：灰色常规小号字，半透明
                tv.setTextColor(highlight ? highlightColor : normalColor);
                tv.setTextSize(highlight ? highlightSize : normalSize);
                tv.setTypeface(null, highlight ? Typeface.BOLD : Typeface.NORMAL);
                tv.setAlpha(highlight ? 1.0f : 0.45f);
                // 高亮行轻微放大上浮，增强视觉层次感
                tv.setScaleX(highlight ? 1.08f : 1.0f);
                tv.setScaleY(highlight ? 1.08f : 1.0f);
                tv.setTranslationY(highlight ? -2f : 0f);
                // 属性动画平滑过渡缩放和位移效果
                tv.animate().setDuration(300)
                        .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                        .start();
                return tv;
            }
        }
    }

    /**
     * 初始化进度条（SeekBar）的监听逻辑。
     * 拖动进度条时实时更新时间显示和歌词高亮（但不滚动歌词），
     * 松手时执行 seekTo 跳转并滚动到目标歌词行。
     */
    private void setupSeekBar() {
        binding.playingProgressSeek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress,
                                                  boolean fromUser) {
                        if (fromUser) {
                            Integer dur = PlaybackState.get().duration.getValue();
                            if (dur != null && dur > 0) {
                                // 将 SeekBar 进度（0-1000）换算为毫秒位置
                                int pos = (int) ((float) progress / 1000 * dur);
                                binding.playingCurrent.setText(
                                        FormatUtil.formatDuration(pos));
                                // 拖动时只更新高亮不滚动，避免列表频繁跳动
                                updateLyricHighlight(pos, false);
                            }
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        int progress = seekBar.getProgress();
                        Integer dur = PlaybackState.get().duration.getValue();
                        if (dur != null && dur > 0 && connector.isConnected()) {
                            int pos = (int) ((float) progress / 1000 * dur);
                            connector.seekTo(pos);
                            // 松手后滚动到目标歌词行
                            updateLyricHighlight(pos, true);
                        }
                    }
                });
    }

    /**
     * 初始化所有控制按钮的点击事件。
     * 包括：返回、播放/暂停、上一首/下一首、播放队列、播放模式、收藏、加入歌单。
     */
    private void setupControls() {
        binding.playingBack.setOnClickListener(v -> onBackPressed());
        binding.playingToggle.setOnClickListener(v -> connector.toggle());
        binding.playingPrev.setOnClickListener(v -> connector.prev());
        binding.playingNext.setOnClickListener(v -> connector.next());

        // 点击分享按钮弹出播放队列面板
        binding.playingShare.setOnClickListener(v -> {
            if (!connector.isConnected()) return;
            PlayQueueDialogFragment dialog = new PlayQueueDialogFragment();
            dialog.setConnector(connector);
            dialog.show(getSupportFragmentManager(), "play_queue");
        });

        // 切换播放模式：列表循环 → 单曲循环 → 随机播放
        binding.playingMode.setOnClickListener(v -> {
            if (connector.isConnected()) {
                PlayMode mode = connector.getService().togglePlayMode();
                updateModeIcon(mode);
            }
        });

        // 收藏/取消收藏：查询收藏歌单中是否包含当前歌曲，切换收藏状态
        binding.playingFavorite.setOnClickListener(v -> {
            if (!connector.isConnected()) return;
            Song song = connector.getService().getCurrentSong();
            if (song == null) return;
            Playlist fav = playlistDao.getByType(UserManager.get(this).getUserId(), Playlist.TYPE_FAVORITE);
            if (fav == null) return;
            if (playlistDao.contains(fav.id, song.mediaStoreId)) {
                playlistDao.removeSong(fav.id, song.mediaStoreId);
                binding.playingFavorite.setSelected(false);
            } else {
                playlistDao.addSong(fav.id, song);
                binding.playingFavorite.setSelected(true);
            }
        });

        // 加入歌单：弹出歌单选择对话框
        binding.playingAddToPlaylist.setOnClickListener(v -> {
            if (!connector.isConnected()) return;
            Song song = connector.getService().getCurrentSong();
            if (song == null) return;
            showAddToPlaylistDialog(song);
        });
    }

    /**
     * 弹出"加入歌单"选择对话框，列出所有歌单供用户选择。
     * @param song 要加入歌单的歌曲
     */
    private void showAddToPlaylistDialog(Song song) {
        List<Playlist> playlists = playlistDao.getAllPlaylists(UserManager.get(this).getUserId());
        if (playlists.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setMessage("还没有歌单，请先在\"我的\"页面创建")
                    .setPositiveButton(R.string.confirm, null)
                    .show();
            return;
        }
        String[] names = new String[playlists.size()];
        for (int i = 0; i < playlists.size(); i++) {
            names[i] = playlists.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("加入歌单")
                .setItems(names, (d, which) -> {
                    Playlist p = playlists.get(which);
                    if (!playlistDao.contains(p.id, song.mediaStoreId)) {
                        playlistDao.addSong(p.id, song);
                        Toast.makeText(this, "已加入「" + p.name + "」",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "歌曲已在「" + p.name + "」中",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 根据当前播放模式更新模式按钮图标。
     * @param mode 当前播放模式（LIST/REPEAT_ONE/SHUFFLE）
     */
    private void updateModeIcon(PlayMode mode) {
        switch (mode) {
            case REPEAT_ONE:
                binding.playingMode.setImageResource(R.drawable.ic_mode_repeat_one);
                break;
            case SHUFFLE:
                binding.playingMode.setImageResource(R.drawable.ic_mode_shuffle);
                break;
            case LIST:
            default:
                binding.playingMode.setImageResource(R.drawable.ic_mode_list);
                break;
        }
    }

    /**
     * 观察 PlaybackState 中的 LiveData，实时同步 UI。
     * 监听的项目包括：当前歌曲索引（切歌时刷新全部 UI）、
     * 播放状态（更新按钮图标和唱片旋转/唱针动画）、
     * 播放位置（更新进度条和歌词高亮）、播放模式（更新模式图标）。
     */
    private void observeState() {
        PlaybackState ps = PlaybackState.get();

        // 当前歌曲索引变化 → 刷新全部 UI（切歌场景）
        ps.currentIndex.observe(this, idx -> {
            if (idx != null && idx >= 0 && connector.isConnected()) {
                refreshAll();
            }
        });

        // 播放状态变化 → 更新播放/暂停按钮图标，控制唱片旋转和唱针动画
        ps.isPlaying.observe(this, playing -> {
            if (playing == null) return;
            binding.playingToggle.setImageResource(playing
                    ? R.drawable.ic_toggle_pause
                    : R.drawable.ic_toggle_play);
            if (playing) {
                startDiscRotate();
                DiscPageFragment df = discFragmentRef.get();
                if (df != null) df.lowerTonearm();
            } else {
                stopDiscRotate();
                DiscPageFragment df = discFragmentRef.get();
                if (df != null) df.raiseTonearm();
            }
        });

        // 播放位置变化 → 更新进度条、时间显示和歌词高亮
        ps.position.observe(this, pos -> {
            if (pos == null) return;
            Integer dur = ps.duration.getValue();
            if (dur != null && dur > 0) {
                // 将毫秒位置换算为 0-1000 的进度值
                int progress = (int) ((float) pos / dur * 1000);
                binding.playingProgressSeek.setProgress(progress);
                binding.playingCurrent.setText(FormatUtil.formatDuration(pos));
                binding.playingTotal.setText(FormatUtil.formatDuration(dur));
            }
            updateLyricHighlight(pos);
        });

        // 播放模式变化 → 更新模式按钮图标
        ps.playMode.observe(this, mode -> {
            if (mode != null) updateModeIcon(mode);
        });
    }

    /**
     * 刷新全部 UI 元素：歌曲标题、歌手、封面、歌词、收藏状态。
     * 在切歌或服务连接成功时调用。
     */
    private void refreshAll() {
        if (!connector.isConnected()) return;
        Song song = connector.getService().getCurrentSong();
        if (song == null) return;

        binding.playingTitleTop.setText(song.title);
        binding.playingSongName.setText(song.title);
        binding.playingArtistName.setText(song.artist);

        DiscPageFragment df = discFragmentRef.get();
        if (df != null) df.refresh(song);

        // 重新加载歌词（三级回退加载机制）
        loadLyric(song);

        // 查询当前歌曲的收藏状态并更新按钮
        Playlist fav = playlistDao.getByType(UserManager.get(this).getUserId(), Playlist.TYPE_FAVORITE);
        boolean isFav = fav != null && playlistDao.contains(fav.id, song.mediaStoreId);
        binding.playingFavorite.setSelected(isFav);
    }

    /**
     * 加载歌词，采用三级回退策略，在子线程中依次尝试：
     * 1. 歌曲同目录的 .lrc 文件（优先匹配小写 .lrc，再匹配大写 .LRC）
     * 2. assets/lyrics/ 目录下的歌词文件（按歌曲标题匹配）
     * 3. 音频文件内嵌歌词（通过 MediaMetadataRetriever 提取，metadata key 100/101）
     *    若内嵌歌词无法解析时间标签，则作为纯文本显示。
     * 加载完成后在主线程更新歌词列表。
     * @param song 当前播放的歌曲
     */
    private void loadLyric(Song song) {
        new Thread(() -> {
            List<Lyric> parsed = LyricParser.EMPTY;
            String source = "none";
            // 第一级回退：歌曲同目录的 .lrc 文件
            // 将音频文件扩展名替换为 .lrc 或 .LRC 进行匹配
            if (song.data != null) {
                int dot = song.data.lastIndexOf('.');
                if (dot > 0) {
                    String base = song.data.substring(0, dot + 1);
                    parsed = LyricParser.parseFile(base + "lrc");
                    if (!parsed.isEmpty()) {
                        source = "lrc_file";
                    } else {
                        // 尝试大写扩展名
                        parsed = LyricParser.parseFile(base + "LRC");
                        if (!parsed.isEmpty()) source = "lrc_file";
                    }
                }
            }
            // 第二级回退：assets/lyrics/ 目录
            // 按歌曲标题在 assets 的 lyrics/ 目录下查找同名 .lrc 文件
            if (parsed.isEmpty()) {
                parsed = loadLyricFromAssets(song);
                if (!parsed.isEmpty()) {
                    source = "assets";
                }
            }
            // 第三级回退：内嵌歌词
            // 通过 MediaMetadataRetriever 提取音频文件中嵌入的歌词数据
            if (parsed.isEmpty()) {
                String embeddedLyric = readEmbeddedLyric(song);
                if (embeddedLyric != null && !embeddedLyric.isEmpty()) {
                    Log.d("PlayingActivity", "内嵌歌词长度=" + embeddedLyric.length());
                    parsed = LyricParser.parse(embeddedLyric);
                    if (!parsed.isEmpty()) {
                        source = "embedded";
                    } else {
                        // 无法解析时间标签时显示纯文本：将整段歌词作为单行显示
                        source = "embedded_plain";
                        List<Lyric> plain = new ArrayList<>();
                        plain.add(new Lyric(0, embeddedLyric));
                        parsed = plain;
                    }
                }
            }
            Log.d("PlayingActivity", "歌词来源=" + source + " 行数=" + parsed.size()
                    + " song=" + song.title);
            final List<Lyric> result = parsed;
            // 切回主线程更新歌词 UI
            main.post(() -> {
                lyrics.clear();
                lyrics.addAll(result);
                LyricPageFragment lf = lyricFragmentRef.get();
                if (lf != null) lf.updateLyrics(result);
            });
        }).start();
    }

    /**
     * 从 assets 目录读取歌词文件，按歌曲标题匹配。
     * 尝试多种路径格式：lyrics/标题.lrc、lyrics/标题.LRC、标题.lrc、标题.LRC。
     * @param song 歌曲对象，使用其 title 字段匹配歌词文件名
     * @return 解析后的歌词列表，未找到则返回空列表
     */
    private List<Lyric> loadLyricFromAssets(Song song) {
        if (song.title == null || song.title.isEmpty()) {
            return LyricParser.EMPTY;
        }
        AssetManager am = getAssets();
        // 尝试多种路径组合，兼容不同的 assets 组织方式
        String[] candidates = {
                "lyrics/" + song.title + ".lrc",
                "lyrics/" + song.title + ".LRC",
                song.title + ".lrc",
                song.title + ".LRC"
        };
        for (String path : candidates) {
            try (java.io.InputStream is = am.open(path)) {
                List<Lyric> result = LyricParser.parse(is);
                if (!result.isEmpty()) {
                    Log.d("PlayingActivity", "assets 歌词命中: " + path);
                    return result;
                }
            } catch (Exception ignore) {
            }
        }
        return LyricParser.EMPTY;
    }

    /**
     * 从音频文件中提取内嵌歌词，使用 MediaMetadataRetriever。
     * metadata key 100 对应同步歌词（LRC 格式），key 101 对应非同步歌词。
     * @param song 歌曲对象，通过其 contentUri 设置数据源
     * @return 内嵌歌词文本，提取失败返回 null
     */
    private String readEmbeddedLyric(Song song) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, song.getContentUri());
            // key 100 = 同步歌词（带时间标签），key 101 = 非同步歌词（纯文本）
            String lyric = mmr.extractMetadata(100);
            if (lyric == null || lyric.isEmpty()) {
                lyric = mmr.extractMetadata(101);
            }
            return lyric;
        } catch (Exception ignore) {
            return null;
        } finally {
            try {
                mmr.release();
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * 更新歌词高亮行（默认滚动到高亮行）。
     * @param positionMs 当前播放位置（毫秒）
     */
    private void updateLyricHighlight(long positionMs) {
        updateLyricHighlight(positionMs, true);
    }

    /**
     * 更新歌词高亮行，可控制是否自动滚动。
     * 通过 LyricParser.findCurrentLine 根据时间戳查找当前应高亮的歌词行。
     * @param positionMs 当前播放位置（毫秒）
     * @param scroll     true 则自动滚动到高亮行，false 仅更新高亮不滚动
     */
    private void updateLyricHighlight(long positionMs, boolean scroll) {
        if (lyrics.isEmpty()) return;
        int line = LyricParser.findCurrentLine(lyrics, positionMs);
        if (line < 0) return;
        LyricPageFragment lf = lyricFragmentRef.get();
        if (lf != null) lf.highlightLine(line, scroll);
    }
}

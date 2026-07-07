package com.musicplayer.app.ui.library;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.musicplayer.app.R;
import com.musicplayer.app.data.Playlist;
import com.musicplayer.app.data.PlaylistDao;
import com.musicplayer.app.data.Song;
import com.musicplayer.app.player.PlaybackState;
import com.musicplayer.app.ui.MainActivity;
import com.musicplayer.app.ui.adapter.SongAdapter;
import com.musicplayer.app.ui.song.SongMoreDialogFragment;
import com.musicplayer.app.util.UserManager;
import com.musicplayer.app.util.CoverLoader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 歌单详情底部弹窗，展示歌单中的歌曲列表，支持播放、收藏和更多操作。
 * 支持两种数据来源：数据库歌单（通过 Playlist 对象加载）和临时列表（如本地音乐，直接传入歌曲列表）。
 * 面板默认展开到屏幕 75% 高度，内含"全部播放"按钮和歌曲列表。
 * 歌曲列表支持点击播放、收藏切换、更多操作（收藏/加入歌单/查看信息），
 * 当前播放歌曲以红色高亮标识。
 */
public class PlaylistDetailDialogFragment extends BottomSheetDialogFragment {

    /** 参数键：数据库歌单对象 */
    private static final String ARG_PLAYLIST = "playlist";
    /** 参数键：临时列表标题 */
    private static final String ARG_TITLE = "title";
    /** 参数键：临时列表歌曲 */
    private static final String ARG_SONGS = "songs";

    /** 歌曲列表适配器 */
    private SongAdapter adapter;
    /** 歌单数据访问对象 */
    private PlaylistDao dao;
    /** 歌曲列表数据 */
    private List<Song> songs = new ArrayList<>();

    /**
     * 创建数据库歌单的详情实例。
     * @param playlist 歌单对象，歌曲从数据库中加载
     * @return 新的 PlaylistDetailDialogFragment 实例
     */
    public static PlaylistDetailDialogFragment newInstance(Playlist playlist) {
        PlaylistDetailDialogFragment f = new PlaylistDetailDialogFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_PLAYLIST, playlist);
        f.setArguments(args);
        return f;
    }

    /**
     * 创建临时列表的详情实例（如本地音乐），歌曲直接通过参数传入。
     * @param title 列表标题
     * @param songs 歌曲列表
     * @return 新的 PlaylistDetailDialogFragment 实例
     */
    public static PlaylistDetailDialogFragment newInstance(String title, List<Song> songs) {
        PlaylistDetailDialogFragment f = new PlaylistDetailDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        args.putParcelableArrayList(ARG_SONGS, new ArrayList<>(songs));
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.ThemeOverlay_MusicPlayer_BottomSheetDialog);
        dao = new PlaylistDao(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_playlist_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ImageView cover = view.findViewById(R.id.detail_cover);
        TextView name = view.findViewById(R.id.detail_name);
        TextView count = view.findViewById(R.id.detail_count);
        View close = view.findViewById(R.id.detail_close);
        View playAll = view.findViewById(R.id.detail_play_all);
        RecyclerView recycler = view.findViewById(R.id.detail_recycler);

        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        // 配置歌曲适配器：显示序号和收藏按钮
        adapter = new SongAdapter(requireContext()).showIndex(true).showFavorite(true);
        adapter.setOnSongClickListener((songs, pos) -> {
            playFromList(songs, pos);
            dismiss();
        });
        adapter.setOnMoreClickListener(this::showSongMore);
        // 收藏按钮切换回调：更新收藏歌单中的歌曲
        adapter.setOnFavoriteToggleListener((song, nowFav) -> {
            Playlist fav = dao.getByType(UserManager.get(requireContext()).getUserId(), Playlist.TYPE_FAVORITE);
            if (fav == null) return;
            if (nowFav) dao.addSong(fav.id, song);
            else dao.removeSong(fav.id, song.mediaStoreId);
        });
        recycler.setAdapter(adapter);

        close.setOnClickListener(v -> dismiss());
        // 全部播放：从第一首开始播放
        playAll.setOnClickListener(v -> {
            if (!songs.isEmpty()) {
                playFromList(songs, 0);
                dismiss();
            }
        });

        // 高亮当前播放歌曲：监听 PlaybackState 的 currentSongId
        PlaybackState.get().currentSongId.observe(getViewLifecycleOwner(),
                id -> adapter.highlight(id == null ? -1L : id));

        // 根据参数类型加载数据：Playlist 对象从数据库读取，临时列表直接使用传入的歌曲
        Bundle args = getArguments();
        if (args != null) {
            Playlist playlist = args.getParcelable(ARG_PLAYLIST);
            if (playlist != null) {
                // 数据库歌单模式
                name.setText(playlist.name);
                applyPlaylistCover(playlist, cover);
                loadPlaylistSongs(playlist, count);
            } else {
                // 临时列表模式（如本地音乐）
                String title = args.getString(ARG_TITLE, "");
                List<Song> initial = args.getParcelableArrayList(ARG_SONGS);
                name.setText(title);
                cover.setImageResource(R.drawable.ic_local_music);
                songs = initial == null ? new ArrayList<>() : initial;
                count.setText(getString(R.string.library_count, songs.size()));
                adapter.submit(songs);
                loadFavoriteSet();
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // BottomSheet 默认展开到屏幕 75% 高度，提供更大的歌曲列表可视区域
        View bottomSheet = getDialog() != null
                ? getDialog().findViewById(com.google.android.material.R.id.design_bottom_sheet)
                : null;
        if (bottomSheet != null) {
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            behavior.setPeekHeight((int) (screenHeight * 0.75));
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    /**
     * 异步从数据库加载歌单中的歌曲，同时加载收藏状态集合。
     * @param playlist   歌单对象
     * @param countView  歌曲数量文本视图
     */
    private void loadPlaylistSongs(Playlist playlist, TextView countView) {
        new Thread(() -> {
            List<Song> loaded = dao.getSongs(playlist.id, 0);
            Set<Long> favIds = loadFavoriteIds();
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> {
                    songs = loaded;
                    countView.setText(getString(R.string.library_count, songs.size()));
                    adapter.submit(songs);
                    adapter.setFavoriteSet(favIds);
                });
            }
        }).start();
    }

    /**
     * 异步加载收藏歌曲 ID 集合，供适配器显示收藏状态。
     */
    private void loadFavoriteSet() {
        new Thread(() -> {
            Set<Long> favIds = loadFavoriteIds();
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> adapter.setFavoriteSet(favIds));
            }
        }).start();
    }

    /**
     * 查询收藏歌单中所有歌曲的 mediaStoreId 集合。
     * @return 收藏歌曲 ID 集合
     */
    private Set<Long> loadFavoriteIds() {
        Set<Long> favIds = new HashSet<>();
        Playlist fav = dao.getByType(UserManager.get(requireContext()).getUserId(), Playlist.TYPE_FAVORITE);
        if (fav != null) {
            for (Song s : dao.getSongs(fav.id, 0)) {
                favIds.add(s.mediaStoreId);
            }
        }
        return favIds;
    }

    /**
     * 设置歌单封面图片：内置歌单使用专用图标，用户歌单异步加载第一首歌的封面。
     * @param playlist 歌单对象
     * @param cover    封面 ImageView
     */
    private void applyPlaylistCover(Playlist playlist, ImageView cover) {
        if (playlist.type == Playlist.TYPE_FAVORITE) {
            cover.setImageResource(R.drawable.ic_heart_red);
        } else if (playlist.type == Playlist.TYPE_HISTORY) {
            cover.setImageResource(R.drawable.ic_history);
        } else {
            // 用户歌单：异步加载第一首歌的封面作为歌单封面
            cover.setImageResource(R.drawable.ic_playlist);
            new Thread(() -> {
                List<Song> loaded = dao.getSongs(playlist.id, 1);
                if (!loaded.isEmpty() && isAdded()) {
                    Song first = loaded.get(0);
                    requireActivity().runOnUiThread(() -> {
                        cover.setTag(R.id.cover_tag_song_id, (int) first.mediaStoreId);
                        CoverLoader.get().display(first, cover);
                    });
                }
            }).start();
        }
    }

    /**
     * 从歌曲列表发起播放，通过 MainActivity 的 playFromHome 方法交给播放服务。
     * @param songs 歌曲列表
     * @param pos   要播放的歌曲位置
     */
    private void playFromList(List<Song> songs, int pos) {
        FragmentActivity activity = getActivity();
        if (activity instanceof MainActivity && pos >= 0 && pos < songs.size()) {
            ((MainActivity) activity).playFromHome(songs, songs.get(pos));
        }
    }

    /**
     * 弹出歌曲更多操作面板（SongMoreDialogFragment），
     * 并监听收藏状态变化以同步更新当前列表中的收藏按钮状态。
     * @param song     歌曲对象
     * @param position 歌曲在列表中的位置
     */
    private void showSongMore(Song song, int position) {
        SongMoreDialogFragment dialog = SongMoreDialogFragment.newInstance(song);
        dialog.setOnFavoriteChangedListener((mediaStoreId, isFavorite) -> {
            // 收藏状态变化后更新适配器中的收藏集合
            Set<Long> current = adapter.getFavoriteIds();
            if (isFavorite) current.add(mediaStoreId);
            else current.remove(mediaStoreId);
            adapter.setFavoriteSet(current);
        });
        dialog.show(getParentFragmentManager(), "song_more");
    }
}

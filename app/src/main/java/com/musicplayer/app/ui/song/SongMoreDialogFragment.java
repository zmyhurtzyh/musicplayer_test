package com.musicplayer.app.ui.song;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.musicplayer.app.R;
import com.musicplayer.app.data.Playlist;
import com.musicplayer.app.data.PlaylistDao;
import com.musicplayer.app.data.Song;
import com.musicplayer.app.util.FormatUtil;
import com.musicplayer.app.util.UserManager;

import java.util.List;

/**
 * 歌曲更多操作底部弹窗，提供收藏/取消收藏、添加到歌单、查看歌曲信息三个操作项。
 * 操作项通过 addAction 方法动态添加到 LinearLayout 中，便于扩展新的操作。
 * 收藏状态变化后通过 OnFavoriteChangedListener 回调通知调用方同步更新 UI。
 */
public class SongMoreDialogFragment extends BottomSheetDialogFragment {

    /** 参数键：歌曲对象 */
    private static final String ARG_SONG = "song";

    /** 歌曲对象 */
    private Song song;
    /** 歌单数据访问对象 */
    private PlaylistDao dao;
    /** 收藏状态变化回调监听器 */
    private OnFavoriteChangedListener favoriteListener;

    /**
     * 收藏状态变化回调接口，通知调用方歌曲的收藏状态已改变。
     */
    public interface OnFavoriteChangedListener {
        /**
         * 收藏状态变化时回调。
         * @param mediaStoreId 歌曲的 mediaStoreId
         * @param isFavorite   变化后的收藏状态
         */
        void onFavoriteChanged(long mediaStoreId, boolean isFavorite);
    }

    /**
     * 创建歌曲更多操作面板实例。
     * @param song 歌曲对象
     * @return 新的 SongMoreDialogFragment 实例
     */
    public static SongMoreDialogFragment newInstance(Song song) {
        SongMoreDialogFragment f = new SongMoreDialogFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_SONG, song);
        f.setArguments(args);
        return f;
    }

    /**
     * 设置收藏状态变化监听器。
     * @param l 监听器实现
     */
    public void setOnFavoriteChangedListener(OnFavoriteChangedListener l) {
        this.favoriteListener = l;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.ThemeOverlay_MusicPlayer_BottomSheetDialog);
        Bundle args = getArguments();
        if (args != null) {
            song = args.getParcelable(ARG_SONG);
        }
        dao = new PlaylistDao(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_song_more, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (song == null) {
            dismiss();
            return;
        }
        TextView title = view.findViewById(R.id.more_song_title);
        TextView subtitle = view.findViewById(R.id.more_song_subtitle);
        title.setText(song.title);
        subtitle.setText(song.getSubtitle());

        LinearLayout actions = view.findViewById(R.id.more_actions);

        // 收藏/取消收藏：根据当前收藏状态切换图标和文字
        final boolean[] isFav = {checkFavorite()};
        addAction(actions, isFav[0] ? R.drawable.ic_heart_red : R.drawable.ic_heart_gray,
                getString(isFav[0] ? R.string.action_unfavorite : R.string.action_favorite),
                v -> {
                    Playlist fav = dao.getByType(UserManager.get(requireContext()).getUserId(), Playlist.TYPE_FAVORITE);
                    if (fav == null) return;
                    if (isFav[0]) {
                        dao.removeSong(fav.id, song.mediaStoreId);
                    } else {
                        dao.addSong(fav.id, song);
                    }
                    isFav[0] = !isFav[0];
                    // 通知调用方收藏状态已变化，以便同步更新列表中的收藏按钮
                    if (favoriteListener != null) {
                        favoriteListener.onFavoriteChanged(song.mediaStoreId, isFav[0]);
                    }
                    dismiss();
                });

        // 添加到歌单：弹出歌单选择对话框
        addAction(actions, R.drawable.ic_playlist,
                getString(R.string.action_add_to_playlist),
                v -> showPlaylistChooser());

        // 查看歌曲信息：弹出详细信息对话框
        addAction(actions, R.drawable.ic_info,
                getString(R.string.action_song_info),
                v -> {
                    showSongInfo();
                });
    }

    /**
     * 检查当前歌曲是否已在收藏歌单中。
     * @return true 已收藏，false 未收藏
     */
    private boolean checkFavorite() {
        Playlist fav = dao.getByType(UserManager.get(requireContext()).getUserId(), Playlist.TYPE_FAVORITE);
        return fav != null && dao.contains(fav.id, song.mediaStoreId);
    }

    /**
     * 动态添加操作项到容器中，每项包含图标和文字。
     * @param container 操作项容器（LinearLayout）
     * @param iconRes   图标资源 ID
     * @param text      操作项文字
     * @param listener  点击监听器
     */
    private void addAction(LinearLayout container, int iconRes, String text,
                           View.OnClickListener listener) {
        View item = LayoutInflater.from(container.getContext())
                .inflate(R.layout.item_menu_action, container, false);
        ImageView icon = item.findViewById(R.id.menu_icon);
        TextView text1 = item.findViewById(R.id.menu_text);
        icon.setImageResource(iconRes);
        text1.setText(text);
        item.setOnClickListener(listener);
        container.addView(item);
    }

    /**
     * 弹出歌单选择对话框，列出所有歌单供用户选择将歌曲加入。
     * @see PlaylistDao#getAllPlaylists(long)
     */
    private void showPlaylistChooser() {
        List<Playlist> playlists = dao.getAllPlaylists(UserManager.get(requireContext()).getUserId());
        if (playlists.isEmpty()) {
            new AlertDialog.Builder(requireContext())
                    .setMessage(R.string.tip_no_playlist)
                    .setPositiveButton(R.string.confirm, null)
                    .show();
            return;
        }
        String[] names = new String[playlists.size()];
        for (int i = 0; i < playlists.size(); i++) {
            names[i] = playlists.get(i).name;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.action_add_to_playlist)
                .setItems(names, (d, which) -> {
                    Playlist target = playlists.get(which);
                    dao.addSong(target.id, song);
                    dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 显示歌曲详细信息对话框，包含标题、歌手、专辑、时长和文件大小。
     */
    private void showSongInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.info_title)).append(song.title).append('\n');
        sb.append(getString(R.string.info_artist)).append(song.artist).append('\n');
        sb.append(getString(R.string.info_album)).append(song.album == null
                || song.album.isEmpty() ? getString(R.string.info_unknown) : song.album).append('\n');
        sb.append(getString(R.string.info_duration))
                .append(FormatUtil.formatDuration(song.duration)).append('\n');
        sb.append(getString(R.string.info_size)).append(FormatUtil.formatSize(song.size));
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.action_song_info)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.confirm, null)
                .show();
    }
}

package com.musicplayer.app.ui.search;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.musicplayer.app.R;
import com.musicplayer.app.data.MusicRepository;
import com.musicplayer.app.data.Playlist;
import com.musicplayer.app.data.PlaylistDao;
import com.musicplayer.app.data.Song;
import com.musicplayer.app.databinding.FragmentSearchBinding;
import com.musicplayer.app.player.PlaybackState;
import com.musicplayer.app.ui.MainActivity;
import com.musicplayer.app.ui.adapter.SongAdapter;
import com.musicplayer.app.ui.song.SongMoreDialogFragment;
import com.musicplayer.app.util.UserManager;
import com.musicplayer.app.util.PermissionHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 搜索页 Fragment，提供本地歌曲的实时搜索功能。
 * 用户输入关键字后实时过滤本地歌曲列表（标题、歌手、专辑），
 * 搜索结果使用 SongAdapter 展示，支持点击播放、收藏和更多操作。
 * 本地歌曲数据采用懒加载策略，首次进入或恢复时从 MediaStore 扫描并缓存到内存，
 * 后续搜索直接在内存中过滤，响应速度更快。
 */
public class SearchFragment extends Fragment {

    private FragmentSearchBinding binding;
    /** 歌曲列表适配器 */
    private SongAdapter adapter;
    /** 所有本地歌曲缓存，搜索时从中过滤 */
    private final List<Song> all = new ArrayList<>();
    /** 歌单数据访问对象，用于收藏操作 */
    private PlaylistDao dao;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSearchBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        dao = new PlaylistDao(requireContext());
        // 配置歌曲适配器：不显示序号，显示收藏按钮
        adapter = new SongAdapter(requireContext())
                .showIndex(false)
                .showFavorite(true);
        adapter.setOnSongClickListener((songs, pos) -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).playFromHome(songs, songs.get(pos));
            }
        });
        adapter.setOnMoreClickListener(this::showSongMore);
        // 收藏按钮切换回调
        adapter.setOnFavoriteToggleListener((song, nowFav) -> {
            Playlist fav = dao.getByType(UserManager.get(requireContext()).getUserId(), Playlist.TYPE_FAVORITE);
            if (fav == null) return;
            if (nowFav) dao.addSong(fav.id, song);
            else dao.removeSong(fav.id, song.mediaStoreId);
        });
        binding.searchRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.searchRecycler.setAdapter(adapter);

        // 输入文字实时过滤：每次文字变化后立即触发搜索
        binding.searchEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter(s.toString().trim());
            }
        });
        // 清空搜索框
        binding.searchClear.setOnClickListener(v -> binding.searchEdit.setText(""));

        // 高亮当前播放歌曲
        PlaybackState.get().currentSongId.observe(getViewLifecycleOwner(),
                id -> adapter.highlight(id == null ? -1L : id));

        ensureLoaded();
    }

    /**
     * 懒加载本地歌曲到内存，仅在首次加载或数据为空时执行。
     * 同时异步加载收藏歌曲 ID 集合，供适配器显示收藏状态。
     * 加载完成后若搜索框已有内容，触发一次过滤。
     */
    private void ensureLoaded() {
        if (!all.isEmpty()) return;
        if (!PermissionHelper.hasAudioPermission(requireContext())) return;
        new Thread(() -> {
            List<Song> songs = new MusicRepository(requireContext()).loadAllSongs();
            all.clear();
            all.addAll(songs);
            // 加载收藏集合
            PlaylistDao d = new PlaylistDao(requireContext());
            Playlist fav = d.getByType(UserManager.get(requireContext()).getUserId(), Playlist.TYPE_FAVORITE);
            Set<Long> favIds = new HashSet<>();
            if (fav != null) {
                for (Song s : d.getSongs(fav.id, 0)) {
                    favIds.add(s.mediaStoreId);
                }
            }
            if (isAdded() && binding != null) {
                requireActivity().runOnUiThread(() -> {
                    if (binding == null) return;
                    adapter.setFavoriteSet(favIds);
                    // 若搜索框已有内容，触发一次过滤
                    Editable e = binding.searchEdit.getText();
                    if (e != null && e.length() > 0) {
                        filter(e.toString().trim());
                    }
                });
            }
        }).start();
    }

    /**
     * 弹出歌曲更多操作面板，并监听收藏状态变化以同步更新列表中的收藏按钮。
     * @param song     歌曲对象
     * @param position 歌曲在列表中的位置
     */
    private void showSongMore(Song song, int position) {
        SongMoreDialogFragment dialog = SongMoreDialogFragment.newInstance(song);
        dialog.setOnFavoriteChangedListener((mediaStoreId, isFavorite) -> {
            Set<Long> current = new HashSet<>(adapter.getFavoriteIds());
            if (isFavorite) current.add(mediaStoreId);
            else current.remove(mediaStoreId);
            adapter.setFavoriteSet(current);
        });
        dialog.show(getParentFragmentManager(), "song_more");
    }

    /**
     * 根据关键字过滤本地歌曲列表并更新适配器，同时更新搜索提示文本。
     * 空关键字时显示默认提示，无搜索结果时显示"无结果"提示。
     * @param kw 搜索关键字
     */
    private void filter(String kw) {
        // 有关键字时显示清空按钮
        binding.searchClear.setVisibility(kw.isEmpty() ? View.GONE : View.VISIBLE);

        List<Song> result = MusicRepository.filter(all, kw);
        adapter.submit(result);

        // 更新搜索提示文本
        if (kw.isEmpty()) {
            binding.searchHintText.setVisibility(View.VISIBLE);
            binding.searchHintText.setText(R.string.search_empty);
        } else if (result.isEmpty()) {
            binding.searchHintText.setVisibility(View.VISIBLE);
            binding.searchHintText.setText(R.string.search_no_result);
        } else {
            binding.searchHintText.setVisibility(View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 恢复时确保数据已加载（可能首次进入时权限未授予）
        ensureLoaded();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

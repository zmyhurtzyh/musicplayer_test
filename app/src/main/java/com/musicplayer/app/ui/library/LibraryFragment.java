package com.musicplayer.app.ui.library;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.musicplayer.app.R;
import com.musicplayer.app.data.MusicRepository;
import com.musicplayer.app.data.Playlist;
import com.musicplayer.app.data.PlaylistDao;
import com.musicplayer.app.data.Song;
import com.musicplayer.app.databinding.FragmentLibraryBinding;
import com.musicplayer.app.ui.MainActivity;
import com.musicplayer.app.ui.adapter.PlaylistAdapter;
import com.musicplayer.app.util.CoverLoader;
import com.musicplayer.app.util.PermissionHelper;
import com.musicplayer.app.util.UserManager;
import com.musicplayer.app.ui.login.LoginActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * "我的音乐"页面 Fragment，展示本地音乐入口、内置歌单（收藏/最近播放）和用户自建歌单。
 * 页面顶部为"本地音乐"入口项，下方为歌单列表，支持新建歌单、删除用户歌单、
 * 点击进入歌单详情等操作。数据通过 PlaylistDao 从本地数据库读取，
 * 本地歌曲通过 MusicRepository 扫描 MediaStore 获取。
 * 实现了 PermissionAware 接口，在音频权限授予后自动重新加载数据。
 */
public class LibraryFragment extends Fragment implements MainActivity.PermissionAware {

    private FragmentLibraryBinding binding;
    /** 歌单列表适配器 */
    private PlaylistAdapter adapter;
    /** 歌单数据访问对象 */
    private PlaylistDao dao;
    /** 本地歌曲列表缓存 */
    private List<Song> localSongs = new ArrayList<>();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentLibraryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        dao = new PlaylistDao(requireContext());
        adapter = new PlaylistAdapter(LayoutInflater.from(requireContext()));
        adapter.setOnItemClickListener(this::onItemClick);
        adapter.setOnMoreClickListener(this::onMore);
        adapter.setOnLoadCoverListener(this::loadPlaylistCover);
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        // 禁用嵌套滚动，使列表在 ScrollView 中正确滚动
        binding.recycler.setNestedScrollingEnabled(false);
        binding.recycler.setAdapter(adapter);
        // 显示当前用户昵称
        binding.tvUserNickname.setText(UserManager.get(requireContext()).getNickname());
        // 新建歌单按钮
        binding.btnCreatePlaylist.setOnClickListener(v -> showCreatePlaylistDialog());
        // 刷新按钮：旋转动画 + 重新加载数据 + 通知首页刷新
        binding.btnRefresh.setOnClickListener(v -> {
            binding.btnRefresh.animate().rotationBy(360f).setDuration(500).start();
            loadData();
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).refreshHome();
            }
        });
        // 退出登录按钮：清除登录状态并跳转到登录页
        binding.btnLogout.setOnClickListener(v -> {
            UserManager.get(requireContext()).logout();
            startActivity(new Intent(requireContext(), LoginActivity.class));
            requireActivity().finish();
        });
        loadData();
    }

    /**
     * 异步加载本地歌曲和歌单列表，构建适配器数据并更新 UI。
     * 首先扫描本地歌曲，然后读取所有歌单，将本地音乐入口和歌单组合为列表项提交给适配器。
     */
    private void loadData() {
        new Thread(() -> {
            // 扫描本地歌曲（需要音频权限）
            if (PermissionHelper.hasAudioPermission(requireContext())) {
                localSongs = new MusicRepository(requireContext()).loadAllSongs();
            }
            // 构建列表项：本地音乐入口 + 所有歌单
            List<PlaylistAdapter.Item> items = new ArrayList<>();
            items.add(PlaylistAdapter.Item.local(
                    getString(R.string.library_local), R.drawable.ic_local_music));

            for (Playlist p : dao.getAllPlaylists(UserManager.get(requireContext()).getUserId())) {
                items.add(PlaylistAdapter.Item.of(p));
            }
            requireActivity().runOnUiThread(() -> {
                adapter.submit(items);
                // 仅有一项（本地音乐入口）时显示空状态提示
                binding.emptyHint.setVisibility(
                        items.size() <= 1 ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    /**
     * 列表项点击处理：本地音乐入口弹出自定义列表详情，歌单弹出数据库歌单详情。
     * @param item     点击的列表项数据
     * @param position 点击项的位置
     */
    private void onItemClick(PlaylistAdapter.Item item, int position) {
        if (item.type == PlaylistAdapter.TYPE_LOCAL) {
            showDetail(getString(R.string.library_local), localSongs);
            return;
        }
        showDetail(item.playlist);
    }

    /**
     * 弹出数据库歌单的详情面板（BottomSheet）。
     * @param playlist 歌单对象
     */
    private void showDetail(Playlist playlist) {
        PlaylistDetailDialogFragment.newInstance(playlist)
                .show(getParentFragmentManager(), "playlist_detail");
    }

    /**
     * 弹出临时列表的详情面板（如本地音乐），歌曲列表直接传入而非从数据库读取。
     * @param title 面板标题
     * @param songs 歌曲列表
     */
    private void showDetail(String title, List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setMessage(getString(R.string.empty_local))
                    .setPositiveButton(R.string.confirm, null)
                    .show();
            return;
        }
        PlaylistDetailDialogFragment.newInstance(title, songs)
                .show(getParentFragmentManager(), "playlist_detail");
    }

    /**
     * 用户歌单删除操作，弹出确认对话框后删除歌单并刷新列表。
     * @param playlist 要删除的歌单
     * @param position 歌单在列表中的位置
     */
    private void onMore(Playlist playlist, int position) {
        new AlertDialog.Builder(requireContext())
                .setMessage("删除歌单「" + playlist.name + "」？")
                .setPositiveButton(R.string.delete, (d, w) -> {
                    dao.deletePlaylist(playlist.id);
                    loadData();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 异步加载歌单封面：取歌单中第一首歌的封面作为歌单封面。
     * @param playlist 歌单对象
     * @param target   封面 ImageView 目标视图
     */
    private void loadPlaylistCover(Playlist playlist, ImageView target) {
        target.setImageResource(R.drawable.ic_playlist);
        target.setTag(R.id.cover_tag_song_id, null);
        new Thread(() -> {
            // 仅查询第一首歌用于封面
            List<Song> songs = dao.getSongs(playlist.id, 1);
            if (songs.isEmpty()) return;
            Song first = songs.get(0);
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> {
                    target.setTag(R.id.cover_tag_song_id, (int) first.mediaStoreId);
                    CoverLoader.get().display(first, target);
                });
            }
        }).start();
    }

    /**
     * 弹出新建歌单对话框，包含输入歌单名称的 EditText。
     * 确认后在数据库中创建新歌单并刷新列表。
     */
    public void showCreatePlaylistDialog() {
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.tip_playlist_name);
        LinearLayout container = new LinearLayout(requireContext());
        container.setPadding(48, 24, 48, 0);
        container.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.library_create_playlist)
                .setView(container)
                .setPositiveButton(R.string.confirm, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        dao.createPlaylist(UserManager.get(requireContext()).getUserId(), name);
                        loadData();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 权限授予后重新加载数据 */
    @Override
    public void onPermissionGranted() {
        loadData();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次恢复时重新加载，确保歌单数据最新（可能从其他页面修改了歌单）
        loadData();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

package com.musicplayer.app.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.musicplayer.app.R;
import com.musicplayer.app.data.MusicRepository;
import com.musicplayer.app.data.Playlist;
import com.musicplayer.app.data.PlaylistDao;
import com.musicplayer.app.data.Song;
import com.musicplayer.app.databinding.FragmentHomeBinding;
import com.musicplayer.app.ui.MainActivity;
import com.musicplayer.app.ui.adapter.HomeSectionAdapter;
import com.musicplayer.app.ui.adapter.SongAdapter;
import com.musicplayer.app.util.PermissionHelper;
import com.musicplayer.app.util.UserManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 首页 Fragment，展示"最近播放"、"每日推荐"和"排行榜"三个分区。
 * 数据来源为本地 MediaStore 扫描的歌曲和历史歌单记录：
 * - 最近播放：取历史歌单中最近播放的前 10 首；
 * - 每日推荐：优先推荐未播放过的歌曲，不足时从全部歌曲随机补充；
 * - 排行榜：按播放次数倒序排列，不足 6 首时按时长倒序补足。
 * 点击卡片可播放对应分区的歌曲列表。实现了 PermissionAware 接口，
 * 在音频权限授予后自动重新加载数据。
 */
public class HomeFragment extends Fragment implements MainActivity.PermissionAware {

    private FragmentHomeBinding binding;
    /** 首页分区列表适配器 */
    private HomeSectionAdapter adapter;
    /** 所有本地歌曲缓存 */
    private List<Song> allSongs = new ArrayList<>();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adapter = new HomeSectionAdapter(requireContext());
        adapter.setListener(this::onCardClick);
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        // 禁用嵌套滚动，使列表在 ScrollView 中正确滚动
        binding.recycler.setNestedScrollingEnabled(false);
        binding.recycler.setAdapter(adapter);
        loadData();
    }

    /**
     * 异步加载本地歌曲数据并构建首页三个分区。
     * 分区构建逻辑：
     * 1. 最近播放：从历史歌单取前 10 首；
     * 2. 每日推荐：从未播放过的歌曲中随机取 10 首，不足时从全部歌曲随机补充；
     * 3. 排行榜：按播放次数倒序取前 10 首，不足 6 首时按时长倒序补足。
     */
    private void loadData() {
        if (!PermissionHelper.hasAudioPermission(requireContext())) {
            return;
        }
        new Thread(() -> {
            MusicRepository repo = new MusicRepository(requireContext());
            allSongs = repo.loadAllSongs();

            PlaylistDao dao = new PlaylistDao(requireContext());

            // 最近播放：历史歌单前 10 首
            Playlist history = dao.getByType(UserManager.get(requireContext()).getUserId(), Playlist.TYPE_HISTORY);
            List<Song> historySongs = history == null
                    ? new ArrayList<>() : dao.getSongs(history.id, 10);

            // 每日推荐：优先未播放过的，不够则从全部歌曲随机补
            // 通过历史记录排除已听过的歌曲，实现"发现新歌"的推荐效果
            Set<Long> historyIds = new HashSet<>();
            for (Song s : historySongs) {
                historyIds.add(s.mediaStoreId);
            }
            List<Song> recommendPool = new ArrayList<>();
            for (Song s : allSongs) {
                if (!historyIds.contains(s.mediaStoreId)) {
                    recommendPool.add(s);
                }
            }
            // 未播放歌曲不足 10 首时，从全部歌曲中随机补充
            if (recommendPool.size() < 10 && allSongs.size() > recommendPool.size()) {
                Collections.shuffle(allSongs);
                for (Song s : allSongs) {
                    if (!recommendPool.contains(s)) {
                        recommendPool.add(s);
                        if (recommendPool.size() >= 10) break;
                    }
                }
            }
            Collections.shuffle(recommendPool);
            List<Song> recommend = recommendPool.subList(0,
                    Math.min(10, recommendPool.size()));

            // 排行榜：按播放次数倒序，不足 6 首时按时长倒序补足
            // 播放次数高的歌曲排在前面，若播放记录不足则用时长最长的歌曲填充
            List<Song> rank = dao.getTopPlayedSongs(UserManager.get(requireContext()).getUserId(), 10);
            if (rank.size() < 6 && allSongs.size() > rank.size()) {
                Set<Long> rankIds = new HashSet<>();
                for (Song s : rank) {
                    rankIds.add(s.mediaStoreId);
                }
                // 从全部歌曲中排除已在排行榜中的，按时长倒序排列作为补充
                List<Song> fallback = new ArrayList<>();
                for (Song s : allSongs) {
                    if (!rankIds.contains(s.mediaStoreId)) {
                        fallback.add(s);
                    }
                }
                fallback.sort((a, b) -> Long.compare(b.duration, a.duration));
                int need = 10 - rank.size();
                rank.addAll(fallback.subList(0, Math.min(need, fallback.size())));
            }

            // 构建三个分区的数据
            List<HomeSectionAdapter.Section> sections = new ArrayList<>();
            sections.add(buildSection(getString(R.string.home_recent),
                    historySongs, true));
            sections.add(buildSection(getString(R.string.home_recommend),
                    recommend, false));
            sections.add(buildSection(getString(R.string.home_rank),
                    rank, true));

            if (isAdded()) {
                requireActivity().runOnUiThread(() -> adapter.submit(sections));
            }
        }).start();
    }

    /**
     * 将歌曲列表转换为首页分区数据对象。
     * @param title         分区标题
     * @param songs         歌曲列表
     * @param usePlayCount  是否显示播放次数（最近播放和排行榜显示，推荐不显示）
     * @return 构建好的 Section 对象
     */
    private HomeSectionAdapter.Section buildSection(String title, List<Song> songs,
                                                    boolean usePlayCount) {
        List<HomeSectionAdapter.Card> cards = new ArrayList<>();
        if (songs != null) {
            for (Song s : songs) {
                long count = usePlayCount ? s.playCount : 0;
                cards.add(new HomeSectionAdapter.Card(s.mediaStoreId, s.title, count, s));
            }
        }
        return new HomeSectionAdapter.Section(title, cards);
    }

    /**
     * 卡片点击回调：从对应分区取出歌曲列表，通过 MainActivity 发起播放。
     * @param sectionIndex 分区索引
     * @param cardIndex    卡片索引（即歌曲在分区中的位置）
     */
    private void onCardClick(int sectionIndex, int cardIndex) {
        List<HomeSectionAdapter.Section> sections = adapter.getSections();
        if (sectionIndex < 0 || sectionIndex >= sections.size()) return;
        HomeSectionAdapter.Section section = sections.get(sectionIndex);

        // 从卡片列表取出歌曲
        List<Song> source = new ArrayList<>();
        for (HomeSectionAdapter.Card card : section.cards) {
            if (card.song != null) {
                source.add(card.song);
            }
        }
        if (source.isEmpty()) return;

        // 越界保护：点击位置超出范围时默认播放第一首
        if (cardIndex < 0 || cardIndex >= source.size()) {
            cardIndex = 0;
        }
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).playFromHome(source, source.get(cardIndex));
        }
    }

    /** 权限授予后重新加载数据 */
    @Override
    public void onPermissionGranted() {
        loadData();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次恢复时重新加载，确保推荐和排行数据最新
        loadData();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

package com.musicplayer.app.ui.playing;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.musicplayer.app.R;
import com.musicplayer.app.data.Song;
import com.musicplayer.app.player.MusicService;
import com.musicplayer.app.player.MusicServiceConnector;
import com.musicplayer.app.player.PlaybackState;
import com.musicplayer.app.util.CoverLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放队列底部弹窗，展示当前播放队列中的所有歌曲。
 * 用户可点击歌曲项切换播放，也可通过右侧删除按钮从队列中移除歌曲。
 * 当前正在播放的歌曲以红色高亮显示并带有播放图标，便于快速定位。
 * 数据通过监听 PlaybackState 的 currentIndex 和 queueChanged 实时同步。
 */
public class PlayQueueDialogFragment extends BottomSheetDialogFragment {

    /** 音乐服务连接器，用于与播放服务交互 */
    private MusicServiceConnector connector;
    /** 队列列表适配器 */
    private QueueAdapter adapter;
    /** 标题视图，显示"播放队列（N）" */
    private TextView titleView;

    /**
     * 设置已绑定的服务连接器，由调用方在 show() 前调用。
     * @param connector 已绑定到 MusicService 的连接器
     */
    public void setConnector(MusicServiceConnector connector) {
        this.connector = connector;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.ThemeOverlay_MusicPlayer_BottomSheetDialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_play_queue, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        titleView = view.findViewById(R.id.queue_title);
        RecyclerView recycler = view.findViewById(R.id.queue_recycler);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new QueueAdapter(requireContext());
        // 设置列表项点击和删除的回调
        adapter.setListener(new QueueAdapter.Listener() {
            @Override
            public void onItemClick(int position) {
                if (connector.isConnected()) {
                    connector.getService().playQueueItem(position);
                }
            }

            @Override
            public void onDelete(int position) {
                if (connector.isConnected()) {
                    connector.getService().removeQueueItem(position);
                }
            }
        });
        recycler.setAdapter(adapter);

        view.findViewById(R.id.queue_close).setOnClickListener(v -> dismiss());

        // 监听队列与当前歌曲变化，实时刷新列表
        PlaybackState ps = PlaybackState.get();
        ps.currentIndex.observe(getViewLifecycleOwner(), idx -> refresh());
        ps.queueChanged.observe(getViewLifecycleOwner(), changed -> refresh());
    }

    @Override
    public void onStart() {
        super.onStart();
        refresh();
    }

    /**
     * 刷新队列列表数据，从 MusicService 获取当前队列和播放索引，
     * 更新适配器数据并自动滚动到当前播放项。
     */
    private void refresh() {
        if (!connector.isConnected()) return;
        MusicService service = connector.getService();
        List<Song> queue = new ArrayList<>(service.getQueue());
        int current = service.getCurrentIndex();
        adapter.submit(queue, current);
        titleView.setText(getString(R.string.playing_queue) + "（" + queue.size() + "）");
        // 滚动到当前播放项，方便用户快速定位
        if (current >= 0 && current < queue.size()) {
            RecyclerView recycler = getView() != null
                    ? getView().findViewById(R.id.queue_recycler) : null;
            if (recycler != null) {
                recycler.smoothScrollToPosition(current);
            }
        }
    }

    /**
     * 播放队列列表适配器，负责渲染队列中的歌曲项。
     * 当前播放的歌曲以红色高亮并显示播放图标（替代序号），
     * 非当前歌曲显示序号。每项右侧有删除按钮可从队列中移除。
     */
    static class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.VH> {
        /** 队列歌曲数据 */
        private final List<Song> data = new ArrayList<>();
        private final Context context;
        /** 当前播放歌曲在队列中的索引 */
        private int currentIndex = -1;
        /** 列表项点击和删除的回调接口 */
        private Listener listener;

        /** 列表项交互回调接口 */
        interface Listener {
            /** 点击歌曲项，切换到该位置播放 */
            void onItemClick(int position);
            /** 点击删除按钮，从队列中移除该位置的歌曲 */
            void onDelete(int position);
        }

        QueueAdapter(Context context) {
            this.context = context;
        }

        /** 设置交互回调监听器 */
        void setListener(Listener l) {
            this.listener = l;
        }

        /**
         * 提交新的队列数据并刷新列表。
         * @param songs       当前队列歌曲列表
         * @param currentIndex 当前播放歌曲的索引
         */
        void submit(List<Song> songs, int currentIndex) {
            data.clear();
            data.addAll(songs);
            this.currentIndex = currentIndex;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_queue, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Song song = data.get(position);
            boolean isCurrent = position == currentIndex;
            h.bind(song, position, isCurrent);
            h.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(h.getBindingAdapterPosition());
            });
            h.delete.setOnClickListener(v -> {
                if (listener != null) listener.onDelete(h.getBindingAdapterPosition());
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        /** 队列列表项 ViewHolder，持有序号、播放图标、封面、标题、副标题和删除按钮 */
        static class VH extends RecyclerView.ViewHolder {
            final TextView index;
            final ImageView playingIcon;
            final ImageView cover;
            final TextView title;
            final TextView subtitle;
            final ImageView delete;

            VH(@NonNull View v) {
                super(v);
                index = v.findViewById(R.id.queue_index);
                playingIcon = v.findViewById(R.id.queue_playing_icon);
                cover = v.findViewById(R.id.queue_cover);
                title = v.findViewById(R.id.queue_title);
                subtitle = v.findViewById(R.id.queue_subtitle);
                delete = v.findViewById(R.id.queue_delete);
            }

            /**
             * 绑定歌曲数据到视图，根据是否为当前播放项切换显示样式。
             * @param song      歌曲数据
             * @param position  在队列中的位置（用于显示序号）
             * @param isCurrent 是否为当前播放的歌曲
             */
            void bind(Song song, int position, boolean isCurrent) {
                cover.setTag(R.id.cover_tag_song_id, (int) song.mediaStoreId);
                CoverLoader.get().display(song, cover);
                title.setText(song.title);
                subtitle.setText(song.getSubtitle());
                if (isCurrent) {
                    // 当前播放项：隐藏序号，显示播放图标，红色高亮文字
                    index.setVisibility(View.GONE);
                    playingIcon.setVisibility(View.VISIBLE);
                    title.setTextColor(itemView.getContext()
                            .getResources().getColor(R.color.netease_red));
                    subtitle.setTextColor(itemView.getContext()
                            .getResources().getColor(R.color.netease_red_light));
                } else {
                    // 非当前项：显示序号，隐藏播放图标，普通文字颜色
                    index.setVisibility(View.VISIBLE);
                    playingIcon.setVisibility(View.GONE);
                    index.setText(String.valueOf(position + 1));
                    title.setTextColor(itemView.getContext()
                            .getResources().getColor(R.color.text_primary));
                    subtitle.setTextColor(itemView.getContext()
                            .getResources().getColor(R.color.text_secondary));
                }
            }
        }
    }
}

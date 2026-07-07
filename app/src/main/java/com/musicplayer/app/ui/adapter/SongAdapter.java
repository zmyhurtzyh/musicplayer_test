package com.musicplayer.app.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.musicplayer.app.R;
import com.musicplayer.app.data.Song;
import com.musicplayer.app.util.CoverLoader;
import com.musicplayer.app.util.FormatUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 通用歌曲列表适配器，被多个页面复用（歌单详情、搜索结果、播放队列等）。
 * 支持灵活配置：是否显示序号、是否显示收藏按钮、是否显示更多按钮。
 * 使用 DiffUtil 进行局部刷新，避免全量 notifyDataSetChanged 导致的性能问题。
 * 当前播放歌曲以红色高亮标识，收藏按钮支持即时切换并通知外部监听器。
 */
public class SongAdapter extends RecyclerView.Adapter<SongAdapter.VH> {

    /** 歌曲数据列表 */
    private final List<Song> data = new ArrayList<>();
    private final Context context;
    private final LayoutInflater inflater;
    /** 歌曲点击监听器 */
    private OnSongClickListener clickListener;
    /** 收藏按钮切换监听器 */
    private OnFavoriteToggleListener favoriteListener;
    /** 更多按钮点击监听器 */
    private OnMoreClickListener moreListener;

    /** 是否显示序号列 */
    private boolean showIndex;
    /** 是否显示收藏按钮 */
    private boolean showFavorite = true;
    /** 是否显示更多操作按钮 */
    private boolean showMore = true;
    /** 当前高亮歌曲的 mediaStoreId，-1 表示无高亮 */
    private long highlightId = -1;
    /** 已收藏歌曲的 ID 集合，用于显示收藏按钮状态 */
    private Set<Long> favoriteIds = new HashSet<>();

    /**
     * 歌曲点击监听接口，传递完整歌曲列表和点击位置以便发起播放。
     */
    public interface OnSongClickListener {
        /**
         * 歌曲被点击时回调。
         * @param songs    当前适配器中的完整歌曲列表
         * @param position 点击的歌曲位置
         */
        void onSongClick(List<Song> songs, int position);
    }

    /**
     * 收藏按钮切换监听接口。
     */
    public interface OnFavoriteToggleListener {
        /**
         * 收藏状态切换时回调。
         * @param song        歌曲对象
         * @param nowFavorite 切换后的收藏状态
         */
        void onFavoriteToggle(Song song, boolean nowFavorite);
    }

    /**
     * 更多按钮点击监听接口。
     */
    public interface OnMoreClickListener {
        /**
         * 更多按钮被点击时回调。
         * @param song     歌曲对象
         * @param position 歌曲在列表中的位置
         */
        void onMore(Song song, int position);
    }

    public SongAdapter(@NonNull Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    /** 设置歌曲点击监听器 */
    public void setOnSongClickListener(OnSongClickListener l) {
        this.clickListener = l;
    }

    /** 设置收藏按钮切换监听器 */
    public void setOnFavoriteToggleListener(OnFavoriteToggleListener l) {
        this.favoriteListener = l;
    }

    /** 设置更多按钮点击监听器 */
    public void setOnMoreClickListener(OnMoreClickListener l) {
        this.moreListener = l;
    }

    /**
     * 配置是否显示序号列，支持链式调用。
     * @param show true 显示序号，false 隐藏
     * @return 当前适配器实例
     */
    public SongAdapter showIndex(boolean show) {
        this.showIndex = show;
        return this;
    }

    /**
     * 配置是否显示收藏按钮，支持链式调用。
     * @param show true 显示收藏按钮，false 隐藏
     * @return 当前适配器实例
     */
    public SongAdapter showFavorite(boolean show) {
        this.showFavorite = show;
        return this;
    }

    /**
     * 配置是否显示更多操作按钮，支持链式调用。
     * @param show true 显示更多按钮，false 隐藏
     * @return 当前适配器实例
     */
    public SongAdapter showMore(boolean show) {
        this.showMore = show;
        return this;
    }

    /**
     * 高亮指定歌曲（当前播放歌曲），以红色文字标识。
     * @param mediaStoreId 要高亮的歌曲 mediaStoreId，-1 表示无高亮
     */
    public void highlight(long mediaStoreId) {
        this.highlightId = mediaStoreId;
        notifyDataSetChanged();
    }

    /**
     * 设置已收藏歌曲的 ID 集合，用于更新收藏按钮的选中状态。
     * @param ids 收藏歌曲 ID 集合，null 时视为空集合
     */
    public void setFavoriteSet(@Nullable Set<Long> ids) {
        this.favoriteIds = ids == null ? new HashSet<>() : ids;
        notifyDataSetChanged();
    }

    /**
     * 获取当前收藏集合的副本，防止外部修改影响内部状态。
     * @return 收藏歌曲 ID 集合的新副本
     */
    @NonNull
    public Set<Long> getFavoriteIds() {
        return new HashSet<>(favoriteIds);
    }

    /**
     * 使用 DiffUtil 局部刷新替换歌曲数据，避免全量刷新导致的闪烁和性能问题。
     * DiffUtil 通过比较新旧列表的 mediaStoreId 判断项是否相同，仅更新变化的部分。
     * @param songs 新的歌曲列表
     */
    public void submit(@NonNull List<Song> songs) {
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return data.size();
            }

            @Override
            public int getNewListSize() {
                return songs.size();
            }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                // 以 mediaStoreId 作为项唯一标识
                return data.get(o).mediaStoreId == songs.get(n).mediaStoreId;
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                return areItemsTheSame(o, n);
            }
        });
        data.clear();
        data.addAll(songs);
        diff.dispatchUpdatesTo(this);
    }

    /** 获取当前歌曲数据列表 */
    public List<Song> getItems() {
        return data;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.item_song, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Song song = data.get(position);
        h.bind(song, position);
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    /**
     * 歌曲列表项 ViewHolder，持有序号、封面、标题、副标题、收藏按钮和更多按钮。
     * 绑定数据时根据配置决定各视图的显隐和交互行为。
     */
    class VH extends RecyclerView.ViewHolder {
        /** 序号文本 */
        final TextView index;
        /** 封面图片 */
        final ImageView cover;
        /** 歌曲标题 */
        final TextView title;
        /** 歌曲副标题（歌手·时长） */
        final TextView subtitle;
        /** 收藏按钮（心形图标） */
        final ImageView favorite;
        /** 更多操作按钮 */
        final ImageView more;

        VH(@NonNull View v) {
            super(v);
            index = v.findViewById(R.id.song_index);
            cover = v.findViewById(R.id.song_cover);
            title = v.findViewById(R.id.song_title);
            subtitle = v.findViewById(R.id.song_subtitle);
            favorite = v.findViewById(R.id.song_favorite);
            more = v.findViewById(R.id.song_more);
        }

        /**
         * 绑定歌曲数据到视图，根据配置渲染各区域。
         * @param song     歌曲数据
         * @param position 在列表中的位置
         */
        void bind(Song song, int position) {
            // 标记当前绑定歌曲的 mediaStoreId，供 CoverLoader 防止 ImageView 复用导致图片错乱
            cover.setTag(R.id.cover_tag_song_id, (int) song.mediaStoreId);

            // 序号列：按配置决定显隐
            if (showIndex) {
                index.setVisibility(View.VISIBLE);
                index.setText(String.valueOf(position + 1));
            } else {
                index.setVisibility(View.GONE);
            }
            CoverLoader.get().display(song, cover);

            title.setText(song.title);
            // 副标题格式：歌手 · 时长
            subtitle.setText(song.getSubtitle() + "  ·  " + FormatUtil.formatDuration(song.duration));

            // 当前播放歌曲红色高亮
            boolean playing = song.mediaStoreId == highlightId;
            title.setTextColor(playing
                    ? context.getResources().getColor(R.color.netease_red)
                    : context.getResources().getColor(R.color.text_primary));

            // 收藏按钮：根据收藏集合显示选中状态，点击即时切换
            if (showFavorite) {
                favorite.setVisibility(View.VISIBLE);
                boolean fav = favoriteIds.contains(song.mediaStoreId);
                favorite.setSelected(fav);
                favorite.setOnClickListener(v -> {
                    boolean now = !favorite.isSelected();
                    favorite.setSelected(now);
                    if (favoriteListener != null) {
                        favoriteListener.onFavoriteToggle(song, now);
                        // 同步更新本地收藏集合，避免后续 bind 时状态不一致
                        if (now) favoriteIds.add(song.mediaStoreId);
                        else favoriteIds.remove(song.mediaStoreId);
                    }
                });
            } else {
                favorite.setVisibility(View.GONE);
            }

            // 更多按钮：点击弹出歌曲更多操作面板
            if (showMore) {
                more.setVisibility(View.VISIBLE);
                more.setOnClickListener(v -> {
                    if (moreListener != null) {
                        moreListener.onMore(song, getBindingAdapterPosition());
                    }
                });
            } else {
                more.setVisibility(View.GONE);
            }

            // 整行点击播放歌曲
            itemView.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onSongClick(data, getBindingAdapterPosition());
                }
            });
        }
    }
}

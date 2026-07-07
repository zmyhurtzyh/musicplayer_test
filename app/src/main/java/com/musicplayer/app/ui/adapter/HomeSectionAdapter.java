package com.musicplayer.app.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.musicplayer.app.R;
import com.musicplayer.app.data.Song;
import com.musicplayer.app.util.CoverLoader;
import com.musicplayer.app.util.FormatUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页分区列表适配器，采用嵌套 RecyclerView 的双层列表结构。
 * 外层 RecyclerView 以垂直方向展示各分区（如最近播放、推荐、排行榜），
 * 每个分区内部包含标题栏和一个水平滚动的卡片列表（由内部 CardAdapter 管理）。
 * 这种嵌套 RecyclerView 的设计既保证了分区的独立滚动，又实现了横向卡片列表的流畅滚动。
 */
public class HomeSectionAdapter extends RecyclerView.Adapter<HomeSectionAdapter.SectionVH> {

    /**
     * 分区数据模型，包含分区标题和卡片列表。
     * 每个分区对应首页的一个横向滚动区域。
     */
    public static class Section {
        /** 分区标题（如"最近播放"、"每日推荐"） */
        public final String title;
        /** 分区内的卡片列表 */
        public final List<Card> cards;

        public Section(String title, List<Card> cards) {
            this.title = title;
            this.cards = cards == null ? new ArrayList<>() : cards;
        }
    }

    /**
     * 卡片数据模型，代表分区中的一张歌曲卡片。
     * 包含歌曲封面 ID、标题、播放次数和关联的 Song 对象。
     */
    public static class Card {
        /** 歌曲 MediaStore ID，用于封面加载防复用 */
        public final long mediaStoreId;
        /** 卡片标题（歌曲名称） */
        public final String title;
        /** 播放次数，大于 0 时在卡片上显示 */
        public final long playCount;
        /** 关联的 Song 对象，用于播放和封面加载 */
        public final Song song;

        public Card(long mediaStoreId, String title, long playCount, Song song) {
            this.mediaStoreId = mediaStoreId;
            this.title = title;
            this.playCount = playCount;
            this.song = song;
        }
    }

    /** 分区数据列表 */
    private final List<Section> sections = new ArrayList<>();
    private final LayoutInflater inflater;
    private final Context context;
    /** 卡片点击监听器 */
    private OnSectionCardClickListener listener;

    /**
     * 卡片点击监听接口，传递分区索引和卡片索引供调用方定位具体歌曲。
     */
    public interface OnSectionCardClickListener {
        /**
         * 卡片被点击时回调。
         * @param sectionIndex 分区在外层列表中的索引
         * @param cardIndex    卡片在内层列表中的索引
         */
        void onCardClick(int sectionIndex, int cardIndex);
    }

    public HomeSectionAdapter(@NonNull Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    /** 设置卡片点击监听器 */
    public void setListener(OnSectionCardClickListener l) {
        this.listener = l;
    }

    /**
     * 提交新的分区数据列表并刷新。
     * @param list 新的分区列表
     */
    public void submit(@NonNull List<Section> list) {
        sections.clear();
        sections.addAll(list);
        notifyDataSetChanged();
    }

    /** 获取当前分区数据列表，供外部读取 */
    public List<Section> getSections() {
        return sections;
    }

    @NonNull
    @Override
    public SectionVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.item_home_section, parent, false);
        return new SectionVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull SectionVH h, int position) {
        Section section = sections.get(position);
        h.title.setText(section.title);
        // 为每个分区创建独立的 CardAdapter，传入分区索引用于点击回调
        h.inner.setAdapter(new CardAdapter(section, h.getBindingAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return sections.size();
    }

    /**
     * 分区 ViewHolder，持有一个水平方向的内部 RecyclerView 用于展示卡片列表。
     * 内部 RecyclerView 禁用嵌套滚动，确保与外部垂直列表的滚动不冲突。
     */
    class SectionVH extends RecyclerView.ViewHolder {
        /** 分区标题文本 */
        final TextView title;
        /** "更多"按钮文本 */
        final TextView more;
        /** 内部横向滚动的卡片列表 RecyclerView */
        final RecyclerView inner;

        SectionVH(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.section_title);
            more = v.findViewById(R.id.section_more);
            inner = v.findViewById(R.id.section_recycler);
            // 水平方向布局，禁用嵌套滚动避免与外层列表滚动冲突
            inner.setLayoutManager(new LinearLayoutManager(v.getContext(),
                    RecyclerView.HORIZONTAL, false));
            inner.setNestedScrollingEnabled(false);
        }
    }

    /**
     * 分区内部卡片列表适配器，以水平方向展示歌曲卡片。
     * 每张卡片包含封面、标题和播放次数，点击时通过外部监听器回调。
     */
    class CardAdapter extends RecyclerView.Adapter<CardVH> {
        /** 所属分区的数据 */
        final Section section;
        /** 所属分区在外层列表中的索引 */
        final int sectionIndex;

        CardAdapter(Section section, int sectionIndex) {
            this.section = section;
            this.sectionIndex = sectionIndex;
        }

        @NonNull
        @Override
        public CardVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_home_card, parent, false);
            return new CardVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull CardVH h, int position) {
            Card card = section.cards.get(position);
            h.title.setText(card.title);
            // 设置封面 tag 用于 CoverLoader 防复用
            h.cover.setTag(R.id.cover_tag_song_id, (int) card.mediaStoreId);
            CoverLoader.get().display(card.song, h.cover);
            // 播放次数大于 0 时显示播放数标签
            if (card.playCount > 0) {
                h.countContainer.setVisibility(View.VISIBLE);
                h.count.setText(FormatUtil.formatCount(card.playCount));
            } else {
                h.countContainer.setVisibility(View.GONE);
            }
            h.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onCardClick(sectionIndex, h.getBindingAdapterPosition());
                }
            });
        }

        @Override
        public int getItemCount() {
            return section.cards.size();
        }
    }

    /**
     * 卡片 ViewHolder，持有封面图片、标题和播放次数视图。
     */
    static class CardVH extends RecyclerView.ViewHolder {
        /** 封面图片 */
        final ImageView cover;
        /** 歌曲标题 */
        final TextView title;
        /** 播放次数文本 */
        final TextView count;
        /** 播放次数容器，控制整体显隐 */
        final View countContainer;

        CardVH(@NonNull View v) {
            super(v);
            cover = v.findViewById(R.id.card_cover);
            title = v.findViewById(R.id.card_title);
            count = v.findViewById(R.id.card_play_count);
            countContainer = v.findViewById(R.id.card_count_container);
        }
    }
}

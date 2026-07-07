package com.musicplayer.app.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.musicplayer.app.R;
import com.musicplayer.app.data.Playlist;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌单列表适配器，支持"本地音乐入口"和"普通歌单"两种类型的列表项。
 * 本地音乐入口显示固定图标和标题，无更多操作按钮；
 * 普通歌单显示封面（内置歌单用专用图标，用户歌单异步加载第一首歌封面）、
 * 歌单名称、歌曲数量，用户歌单额外显示更多操作按钮（删除）。
 * 使用 DiffUtil 进行局部刷新，通过歌单 ID 和名称判断项是否相同。
 */
public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.VH> {

    /** 列表项类型：本地音乐入口 */
    public static final int TYPE_LOCAL = 0;
    /** 列表项类型：普通歌单 */
    public static final int TYPE_PLAYLIST = 1;

    /** 列表项数据 */
    private final List<Item> items = new ArrayList<>();
    private final LayoutInflater inflater;
    /** 列表项点击监听器 */
    private OnItemClickListener clickListener;
    /** 更多按钮点击监听器 */
    private OnMoreClickListener moreListener;
    /** 歌单封面异步加载回调 */
    private OnLoadCoverListener coverListener;

    /**
     * 列表项数据模型，支持两种类型：本地音乐入口和普通歌单。
     * 本地音乐入口仅包含标题和图标资源；普通歌单包含 Playlist 对象。
     */
    public static class Item {
        /** 项类型（TYPE_LOCAL 或 TYPE_PLAYLIST） */
        public final int type;
        /** 歌单对象（仅 TYPE_PLAYLIST 类型有效） */
        public final Playlist playlist;
        /** 入口标题（仅 TYPE_LOCAL 类型有效） */
        public final String title;
        /** 入口图标资源 ID（仅 TYPE_LOCAL 类型有效） */
        public final int iconRes;

        private Item(int type, Playlist playlist, String title, int iconRes) {
            this.type = type;
            this.playlist = playlist;
            this.title = title;
            this.iconRes = iconRes;
        }

        /**
         * 创建本地音乐入口项。
         * @param title   入口标题
         * @param iconRes 图标资源 ID
         * @return 本地音乐入口 Item
         */
        public static Item local(String title, int iconRes) {
            return new Item(TYPE_LOCAL, null, title, iconRes);
        }

        /**
         * 创建普通歌单项。
         * @param p 歌单对象
         * @return 歌单 Item
         */
        public static Item of(Playlist p) {
            return new Item(TYPE_PLAYLIST, p, null, 0);
        }
    }

    /** 列表项点击监听接口 */
    public interface OnItemClickListener {
        /**
         * 列表项被点击时回调。
         * @param item     点击的列表项数据
         * @param position 点击项的位置
         */
        void onClick(Item item, int position);
    }

    /** 更多按钮点击监听接口（用于删除歌单） */
    public interface OnMoreClickListener {
        /**
         * 更多按钮被点击时回调。
         * @param playlist 歌单对象
         * @param position 歌单在列表中的位置
         */
        void onMore(Playlist playlist, int position);
    }

    /**
     * 歌单封面异步加载回调接口。
     * 由于歌单封面需要从数据库查询第一首歌后异步加载，交由外部处理。
     */
    public interface OnLoadCoverListener {
        /**
         * 请求加载歌单封面时回调。
         * @param playlist 歌单对象
         * @param target   封面 ImageView 目标视图
         */
        void onLoadCover(Playlist playlist, ImageView target);
    }

    public PlaylistAdapter(@NonNull LayoutInflater inflater) {
        this.inflater = inflater;
    }

    /** 设置列表项点击监听器 */
    public void setOnItemClickListener(OnItemClickListener l) {
        this.clickListener = l;
    }

    /** 设置更多按钮点击监听器 */
    public void setOnMoreClickListener(OnMoreClickListener l) {
        this.moreListener = l;
    }

    /** 设置歌单封面异步加载回调 */
    public void setOnLoadCoverListener(OnLoadCoverListener l) {
        this.coverListener = l;
    }

    /**
     * 使用 DiffUtil 局部刷新替换列表数据。
     * 本地音乐入口以标题判断项是否相同，普通歌单以 ID 判断。
     * 内容比较额外考虑歌单名称和歌曲数量，确保数据变化时能正确刷新。
     * @param list 新的列表项数据
     */
    public void submit(@NonNull List<Item> list) {
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return items.size();
            }

            @Override
            public int getNewListSize() {
                return list.size();
            }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                Item a = items.get(o), b = list.get(n);
                if (a.type != b.type) return false;
                if (a.type == TYPE_PLAYLIST) {
                    return a.playlist.id == b.playlist.id;
                }
                return a.title != null && a.title.equals(b.title);
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                Item a = items.get(o), b = list.get(n);
                if (a.type != b.type) return false;
                if (a.type == TYPE_PLAYLIST) {
                    return a.playlist.id == b.playlist.id
                            && a.playlist.songCount == b.playlist.songCount
                            && (a.playlist.name == null ? b.playlist.name == null
                                    : a.playlist.name.equals(b.playlist.name));
                }
                return areItemsTheSame(o, n);
            }
        });
        items.clear();
        items.addAll(list);
        diff.dispatchUpdatesTo(this);
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.item_playlist, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Item item = items.get(position);
        if (item.type == TYPE_LOCAL) {
            // 本地音乐入口：显示图标和标题，隐藏数量和更多按钮
            h.cover.setImageResource(item.iconRes);
            h.name.setText(item.title);
            h.count.setVisibility(View.GONE);
            h.more.setVisibility(View.GONE);
        } else {
            Playlist p = item.playlist;
            // 内置歌单用专门图标，用户歌单异步加载封面
            if (p.type == Playlist.TYPE_FAVORITE) {
                h.cover.setImageResource(R.drawable.ic_heart_red);
            } else if (p.type == Playlist.TYPE_HISTORY) {
                h.cover.setImageResource(R.drawable.ic_history);
            } else if (coverListener != null) {
                // 用户歌单封面需要异步加载，委托给外部回调
                coverListener.onLoadCover(p, h.cover);
            } else {
                h.cover.setImageResource(R.drawable.ic_playlist);
            }
            h.name.setText(p.name);
            h.count.setVisibility(View.VISIBLE);
            h.count.setText(h.itemView.getContext().getString(
                    R.string.library_count, p.songCount));
            // 内置歌单（收藏/历史）不显示更多按钮，用户歌单显示删除按钮
            h.more.setVisibility(p.isBuiltin() ? View.GONE : View.VISIBLE);
            h.more.setOnClickListener(v -> {
                if (moreListener != null) moreListener.onMore(p, h.getBindingAdapterPosition());
            });
        }
        h.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onClick(item, h.getBindingAdapterPosition());
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /**
     * 歌单列表项 ViewHolder，持有封面、名称、歌曲数量和更多按钮。
     */
    static class VH extends RecyclerView.ViewHolder {
        /** 歌单封面图片 */
        final ImageView cover;
        /** 歌单名称 */
        final TextView name;
        /** 歌曲数量文本 */
        final TextView count;
        /** 更多操作按钮 */
        final ImageView more;

        VH(@NonNull View v) {
            super(v);
            cover = v.findViewById(R.id.playlist_cover);
            name = v.findViewById(R.id.playlist_name);
            count = v.findViewById(R.id.playlist_count);
            more = v.findViewById(R.id.playlist_more);
        }
    }
}

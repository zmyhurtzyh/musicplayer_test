package com.musicplayer.app.data;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌单数据模型，表示一个播放列表及其基本信息。
 * <p>
 * 歌单分为两类：内置歌单（"我喜欢的音乐"、"最近播放"）和用户自建歌单。
 * 内置歌单不可删除，用户歌单可以增删。实现 Parcelable 以支持跨组件传递。
 * songs 列表为 transient，不参与序列化，由 DAO 加载歌单详情时按需填充。
 * </p>
 */
public class Playlist implements Parcelable {

    /** 歌单类型：我喜欢的音乐（内置，不可删除） */
    public static final int TYPE_FAVORITE = 1;
    /** 歌单类型：最近播放（内置，不可删除） */
    public static final int TYPE_HISTORY = 2;
    /** 歌单类型：用户自建歌单（可删除） */
    public static final int TYPE_USER = 0;

    /** 数据库主键，自增 ID */
    public long id;
    /** 歌单名称 */
    public String name;
    /** 歌单类型：TYPE_USER / TYPE_FAVORITE / TYPE_HISTORY */
    public int type;
    /** 创建时间，毫秒时间戳 */
    public long createdAt;
    /** 歌单所属用户 ID */
    public long userId;
    /** 歌曲数量，由 PlaylistDao 查询时填充，非数据库字段 */
    public int songCount = 0;

    public Playlist() {
    }

    /**
     * 全参构造方法。
     *
     * @param id        数据库主键
     * @param name      歌单名称
     * @param type      歌单类型
     * @param createdAt 创建时间
     * @param userId    所属用户 ID
     */
    public Playlist(long id, String name, int type, long createdAt, long userId) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.createdAt = createdAt;
        this.userId = userId;
    }

    /**
     * 创建一个不持久化的临时歌单，用于本地音乐列表、搜索结果等无需保存的场景。
     *
     * @param name  歌单名称
     * @param songs 歌曲列表（仅用于计算 songCount，不会持有引用）
     * @return 临时歌单对象
     */
    public static Playlist transientList(String name, List<Song> songs) {
        Playlist p = new Playlist(0, name, TYPE_USER, System.currentTimeMillis(), 0);
        p.songCount = songs == null ? 0 : songs.size();
        return p;
    }

    /**
     * 判断是否为内置歌单（不可删除）。
     *
     * @return true 为内置歌单
     */
    public boolean isBuiltin() {
        return type == TYPE_FAVORITE || type == TYPE_HISTORY;
    }

    /**
     * 内置歌单的内存歌曲列表，由 DAO 加载歌单详情时填充。
     * transient 修饰，不参与 Parcelable 序列化。
     */
    public transient List<Song> songs = new ArrayList<>();

    /**
     * 从 Parcel 反序列化歌单对象。
     *
     * @param in Parcel 输入流
     */
    protected Playlist(Parcel in) {
        id = in.readLong();
        name = in.readString();
        type = in.readInt();
        createdAt = in.readLong();
        userId = in.readLong();
        songCount = in.readInt();
    }

    /** Parcelable 创建器 */
    public static final Creator<Playlist> CREATOR = new Creator<Playlist>() {
        @Override
        public Playlist createFromParcel(Parcel in) {
            return new Playlist(in);
        }

        @Override
        public Playlist[] newArray(int size) {
            return new Playlist[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(name);
        dest.writeInt(type);
        dest.writeLong(createdAt);
        dest.writeLong(userId);
        dest.writeInt(songCount);
    }
}

package com.musicplayer.app.data;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * 歌曲数据模型，表示一首本地音乐的所有元信息。
 * <p>
 * 同时支持两种来源：由 MediaStore 扫描得到的本地歌曲和用户自定义歌单关联的歌曲。
 * 实现 Parcelable 以支持在 Activity/Fragment 之间通过 Intent 或 Bundle 传递。
 * 以 mediaStoreId 作为歌曲唯一标识（equals/hashCode 均基于此字段）。
 * </p>
 */
public class Song implements Parcelable {

    /** 本地数据库 songs 表的主键，仅歌单关联场景使用 */
    public long id;
    /** MediaStore 中的 _ID，用于构造 content Uri 和唯一标识一首歌 */
    public long mediaStoreId;
    /** 歌曲标题 */
    public String title;
    /** 歌手名称 */
    public String artist;
    /** 专辑名称 */
    public String album;
    /** MediaStore 专辑 ID，用于获取专辑封面图 */
    public long albumId;
    /** 歌曲时长，单位毫秒 */
    public long duration;
    /** 播放次数，由 PlaylistDao 按用户维度维护（存储在 playlist_song.play_count） */
    public long playCount;
    /** 文件大小，单位字节 */
    public long size;
    /** 文件在磁盘上的绝对路径 */
    public String data;
    /** 加入 MediaStore 的时间，单位毫秒（由 MediaStore 的 DATE_ADDED 秒数转换而来） */
    public long dateAdded;

    public Song() {
    }

    /**
     * 从 Parcel 中反序列化歌曲对象。
     *
     * @param in Parcel 输入流
     */
    protected Song(Parcel in) {
        id = in.readLong();
        mediaStoreId = in.readLong();
        title = in.readString();
        artist = in.readString();
        album = in.readString();
        albumId = in.readLong();
        duration = in.readLong();
        size = in.readLong();
        data = in.readString();
        dateAdded = in.readLong();
    }

    /**
     * 构造 MediaPlayer 可播放的 content Uri。
     * 格式为 content://media/external/audio/media/{mediaStoreId}。
     *
     * @return 可供 MediaPlayer 使用的 Uri
     */
    public Uri getContentUri() {
        return Uri.withAppendedPath(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                String.valueOf(mediaStoreId));
    }

    /**
     * 获取用于界面显示的副标题，格式为"歌手 - 专辑"。
     * 歌手缺失时显示"未知歌手"，专辑缺失时仅显示歌手。
     *
     * @return 副标题字符串
     */
    public String getSubtitle() {
        String a = artist == null || artist.isEmpty() ? "未知歌手" : artist;
        if (album != null && !album.isEmpty()) {
            return a + " - " + album;
        }
        return a;
    }

    /** Parcelable 创建器 */
    public static final Creator<Song> CREATOR = new Creator<Song>() {
        @Override
        public Song createFromParcel(Parcel in) {
            return new Song(in);
        }

        @Override
        public Song[] newArray(int size) {
            return new Song[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeLong(mediaStoreId);
        dest.writeString(title);
        dest.writeString(artist);
        dest.writeString(album);
        dest.writeLong(albumId);
        dest.writeLong(duration);
        dest.writeLong(size);
        dest.writeString(data);
        dest.writeLong(dateAdded);
    }

    /**
     * 以 mediaStoreId 判断两首歌曲是否相同。
     * 同一首本地歌曲的 mediaStoreId 是唯一的。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Song)) return false;
        Song song = (Song) o;
        return mediaStoreId == song.mediaStoreId;
    }

    /**
     * 以 mediaStoreId 计算 hashCode，与 equals 保持一致。
     */
    @Override
    public int hashCode() {
        return (int) (mediaStoreId ^ (mediaStoreId >>> 32));
    }
}

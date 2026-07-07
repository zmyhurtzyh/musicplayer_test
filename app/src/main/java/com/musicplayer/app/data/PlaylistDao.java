package com.musicplayer.app.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 歌单数据访问对象（DAO），封装歌单与歌曲关联表的全部增删查改操作。
 * <p>
 * 核心职责包括：歌单的创建/删除、歌曲的添加/移除、播放次数统计与排行榜查询。
 * 设计要点：
 * <ul>
 *   <li>播放次数存储在 playlist_song.play_count 中，按用户+歌单维度隔离，不同用户互不干扰</li>
 *   <li>查询歌单内歌曲时会通过 MediaStore 校验文件是否仍存在，自动清理失效记录</li>
 * </ul>
 * </p>
 */
public class PlaylistDao {

    private final DatabaseHelper helper;
    private final Context appContext;

    /**
     * 构造方法，获取 DatabaseHelper 单例和应用级 Context。
     *
     * @param context 上下文，内部会转为 ApplicationContext 避免内存泄漏
     */
    public PlaylistDao(Context context) {
        this.helper = DatabaseHelper.get(context);
        this.appContext = context.getApplicationContext();
    }

    /**
     * 查询指定用户的全部歌单。内置歌单排在前面（type DESC），同类型按创建时间倒序。
     * 每个歌单会额外填充 songCount 字段。
     *
     * @param userId 用户 ID
     * @return 歌单列表，不会为 null
     */
    public List<Playlist> getAllPlaylists(long userId) {
        List<Playlist> list = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        // 排序策略：type DESC 保证内置歌单（type=1/2）排在用户歌单（type=0）前面
        try (Cursor c = db.query(DatabaseHelper.T_PLAYLISTS, null,
                DatabaseHelper.C_PL_USER_ID + " = ?",
                new String[]{String.valueOf(userId)},
                null, null,
                DatabaseHelper.C_PL_TYPE + " DESC, " + DatabaseHelper.C_PL_CREATED_AT + " DESC")) {
            while (c.moveToNext()) {
                list.add(cursorToPlaylist(c));
            }
        }
        // 遍历填充每个歌单的歌曲数量
        for (Playlist p : list) {
            p.songCount = countSongs(db, p.id);
        }
        return list;
    }

    /**
     * 根据歌单类型查询指定用户的歌单，通常用于查找"我喜欢的音乐"或"最近播放"等内置歌单。
     *
     * @param userId 用户 ID
     * @param type   歌单类型，参见 Playlist.TYPE_FAVORITE / TYPE_HISTORY
     * @return 匹配的歌单，不存在则返回 null
     */
    public Playlist getByType(long userId, int type) {
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.query(DatabaseHelper.T_PLAYLISTS, null,
                DatabaseHelper.C_PL_USER_ID + " = ? AND " + DatabaseHelper.C_PL_TYPE + " = ?",
                new String[]{String.valueOf(userId), String.valueOf(type)}, null, null, null, "1")) {
            if (c.moveToNext()) {
                Playlist p = cursorToPlaylist(c);
                p.songCount = countSongs(db, p.id);
                return p;
            }
        }
        return null;
    }

    /**
     * 新建用户自建歌单。
     *
     * @param userId 用户 ID
     * @param name   歌单名称
     * @return 新歌单的行 ID，插入失败返回 -1
     */
    public long createPlaylist(long userId, String name) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(DatabaseHelper.C_PL_NAME, name);
        cv.put(DatabaseHelper.C_PL_TYPE, Playlist.TYPE_USER);
        cv.put(DatabaseHelper.C_PL_USER_ID, userId);
        cv.put(DatabaseHelper.C_PL_CREATED_AT, System.currentTimeMillis());
        return db.insert(DatabaseHelper.T_PLAYLISTS, null, cv);
    }

    /**
     * 删除歌单。内置歌单（收藏/最近播放）不允许删除，方法会返回 false。
     * 删除时在事务中同时清除歌单-歌曲关联记录。
     *
     * @param playlistId 歌单 ID
     * @return true 删除成功，false 歌单为内置类型或不存在
     */
    public boolean deletePlaylist(long playlistId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        // 先检查歌单类型，内置歌单禁止删除
        try (Cursor c = db.query(DatabaseHelper.T_PLAYLISTS,
                new String[]{DatabaseHelper.C_PL_TYPE},
                DatabaseHelper.C_PL_ID + " = ?",
                new String[]{String.valueOf(playlistId)}, null, null, null)) {
            if (c.moveToNext() && c.getInt(0) != Playlist.TYPE_USER) {
                return false;
            }
        }
        // 事务：先删关联表，再删歌单表，保证数据一致性
        db.beginTransaction();
        try {
            db.delete(DatabaseHelper.T_PLAYLIST_SONG,
                    DatabaseHelper.C_PS_PLAYLIST_ID + " = ?",
                    new String[]{String.valueOf(playlistId)});
            db.delete(DatabaseHelper.T_PLAYLISTS,
                    DatabaseHelper.C_PL_ID + " = ?",
                    new String[]{String.valueOf(playlistId)});
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 添加歌曲到歌单。如果歌曲已存在于歌单中则忽略（CONFLICT_IGNORE）。
     * 内部会先确保 songs 表中有该歌曲的元数据记录。
     *
     * @param playlistId 歌单 ID
     * @param song       要添加的歌曲
     */
    public void addSong(long playlistId, Song song) {
        addSong(playlistId, song, 0);
    }

    /**
     * 添加歌曲到歌单并设置初始播放次数。如果歌曲已存在则忽略。
     *
     * @param playlistId 歌单 ID
     * @param song       要添加的歌曲
     * @param playCount  初始播放次数（用于重新添加时保留旧计数）
     */
    public void addSong(long playlistId, Song song, long playCount) {
        SQLiteDatabase db = helper.getWritableDatabase();
        long songRowId = ensureSong(db, song);
        ContentValues cv = new ContentValues();
        cv.put(DatabaseHelper.C_PS_PLAYLIST_ID, playlistId);
        cv.put(DatabaseHelper.C_PS_SONG_ID, songRowId);
        cv.put(DatabaseHelper.C_PS_ADDED_AT, System.currentTimeMillis());
        cv.put(DatabaseHelper.C_PS_PLAY_COUNT, playCount);
        db.insertWithOnConflict(DatabaseHelper.T_PLAYLIST_SONG, null, cv,
                SQLiteDatabase.CONFLICT_IGNORE);
    }

    /**
     * 从歌单中移除歌曲。仅删除 playlist_song 关联，不会删除 songs 表中的歌曲元数据。
     *
     * @param playlistId   歌单 ID
     * @param mediaStoreId 歌曲在 MediaStore 中的 ID
     */
    public void removeSong(long playlistId, long mediaStoreId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        // 通过 mediaStoreId 查找 songs 表主键
        Long songRowId = findSongRowId(db, mediaStoreId);
        if (songRowId == null) return;
        db.delete(DatabaseHelper.T_PLAYLIST_SONG,
                DatabaseHelper.C_PS_PLAYLIST_ID + " = ? AND "
                        + DatabaseHelper.C_PS_SONG_ID + " = ?",
                new String[]{String.valueOf(playlistId), String.valueOf(songRowId)});
    }

    /**
     * 判断指定歌曲是否存在于歌单中。
     *
     * @param playlistId   歌单 ID
     * @param mediaStoreId 歌曲 MediaStore ID
     * @return true 存在，false 不存在
     */
    public boolean contains(long playlistId, long mediaStoreId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Long songRowId = findSongRowId(db, mediaStoreId);
        if (songRowId == null) return false;
        try (Cursor c = db.query(DatabaseHelper.T_PLAYLIST_SONG, null,
                DatabaseHelper.C_PS_PLAYLIST_ID + " = ? AND "
                        + DatabaseHelper.C_PS_SONG_ID + " = ?",
                new String[]{String.valueOf(playlistId), String.valueOf(songRowId)},
                null, null, null)) {
            return c.getCount() > 0;
        }
    }

    /**
     * 查询歌单内的歌曲列表，按添加时间倒序排列。
     * 会自动通过 MediaStore 校验文件是否仍存在，移除已删除文件对应的记录。
     * 歌曲的 playCount 从 playlist_song.play_count 读取，为按用户隔离的播放次数。
     *
     * @param playlistId 歌单 ID
     * @param limit      最大返回数量，0 或负数表示不限制
     * @return 歌曲列表，不会为 null
     */
    public List<Song> getSongs(long playlistId, int limit) {
        List<Song> songs = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        // JOIN 查询：通过 playlist_song 关联表获取歌单中的歌曲详情，同时带出按用户隔离的 play_count
        String sql = "SELECT s.*, ps." + DatabaseHelper.C_PS_PLAY_COUNT + " AS user_play_count"
                + " FROM " + DatabaseHelper.T_SONGS + " s "
                + "INNER JOIN " + DatabaseHelper.T_PLAYLIST_SONG + " ps "
                + "ON s.id = ps." + DatabaseHelper.C_PS_SONG_ID + " "
                + "WHERE ps." + DatabaseHelper.C_PS_PLAYLIST_ID + " = ? "
                + "ORDER BY ps." + DatabaseHelper.C_PS_ADDED_AT + " DESC";
        if (limit > 0) {
            sql += " LIMIT " + limit;
        }
        List<Song> all = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(playlistId)})) {
            while (c.moveToNext()) {
                Song song = cursorToSong(c);
                // 优先使用 playlist_song 中按用户隔离的 play_count，覆盖旧的全局值
                int upcIdx = c.getColumnIndex("user_play_count");
                if (upcIdx >= 0) {
                    song.playCount = c.getLong(upcIdx);
                }
                all.add(song);
            }
        }
        // 通过MediaStore校验歌曲文件是否仍存在，分离有效歌曲和失效记录
        Set<Long> validIds = queryValidMediaStoreIds(all);
        List<Long> staleIds = new ArrayList<>();
        for (Song song : all) {
            if (validIds.contains(song.mediaStoreId)) {
                songs.add(song);
            } else {
                // 文件已被删除，记录为失效条目
                staleIds.add(song.id);
            }
        }
        // 清理失效的歌曲记录及其关联
        if (!staleIds.isEmpty()) {
            cleanStaleSongs(db, staleIds);
        }
        return songs;
    }

    /**
     * 清理文件已不存在的歌曲记录及其在歌单中的关联。
     * 在事务中依次删除 playlist_song 和 songs 表中的对应行。
     *
     * @param db         可写数据库实例
     * @param songRowIds 需要清理的 songs 表主键列表
     */
    private void cleanStaleSongs(SQLiteDatabase db, List<Long> songRowIds) {
        if (songRowIds == null || songRowIds.isEmpty()) return;
        db.beginTransaction();
        try {
            for (Long id : songRowIds) {
                // 先删关联，再删歌曲元数据
                db.delete(DatabaseHelper.T_PLAYLIST_SONG,
                        DatabaseHelper.C_PS_SONG_ID + " = ?",
                        new String[]{String.valueOf(id)});
                db.delete(DatabaseHelper.T_SONGS,
                        "id = ?",
                        new String[]{String.valueOf(id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 查询 MediaStore 中仍存在的 mediaStoreId 集合，用于过滤已删除的音频文件。
     * 使用 IN 子句批量查询，比逐条查询效率更高。
     *
     * @param songs 待校验的歌曲列表
     * @return 仍存在于 MediaStore 中的 mediaStoreId 集合
     */
    private Set<Long> queryValidMediaStoreIds(List<Song> songs) {
        Set<Long> valid = new HashSet<>();
        if (songs == null || songs.isEmpty()) return valid;
        // 构造 IN (?, ?, ...) 形式的查询条件
        StringBuilder selection = new StringBuilder(
                MediaStore.Audio.Media._ID + " IN (");
        String[] args = new String[songs.size()];
        for (int i = 0; i < songs.size(); i++) {
            if (i > 0) selection.append(",");
            selection.append("?");
            args[i] = String.valueOf(songs.get(i).mediaStoreId);
        }
        selection.append(")");
        ContentResolver cr = appContext.getContentResolver();
        try (Cursor c = cr.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Audio.Media._ID},
                selection.toString(), args, null)) {
            while (c != null && c.moveToNext()) {
                valid.add(c.getLong(0));
            }
        } catch (Exception e) {
            // 查询失败时假定全部有效，避免误删数据
            for (Song s : songs) valid.add(s.mediaStoreId);
        }
        return valid;
    }

    /**
     * 统计指定歌单中的歌曲数量。
     *
     * @param db         数据库实例
     * @param playlistId 歌单 ID
     * @return 歌曲数量
     */
    private int countSongs(SQLiteDatabase db, long playlistId) {
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + DatabaseHelper.T_PLAYLIST_SONG
                        + " WHERE " + DatabaseHelper.C_PS_PLAYLIST_ID + " = ?",
                new String[]{String.valueOf(playlistId)})) {
            return c.moveToNext() ? c.getInt(0) : 0;
        }
    }

    /**
     * 确保歌曲元数据存在于 songs 表中。若已存在则返回其行 ID，否则插入新记录。
     * 以 mediaStoreId 为唯一键判断是否已存在。
     *
     * @param db   可写数据库实例
     * @param song 歌曲对象
     * @return songs 表中的行 ID
     */
    private long ensureSong(SQLiteDatabase db, Song song) {
        Long existing = findSongRowId(db, song.mediaStoreId);
        if (existing != null) return existing;
        ContentValues cv = new ContentValues();
        cv.put(DatabaseHelper.C_SONG_MEDIA_STORE_ID, song.mediaStoreId);
        cv.put(DatabaseHelper.C_SONG_TITLE, song.title);
        cv.put(DatabaseHelper.C_SONG_ARTIST, song.artist);
        cv.put(DatabaseHelper.C_SONG_ALBUM, song.album);
        cv.put(DatabaseHelper.C_SONG_DURATION, song.duration);
        return db.insert(DatabaseHelper.T_SONGS, null, cv);
    }

    /**
     * 将指定歌曲在当前用户的最近播放歌单中的播放次数加 1。
     * 播放次数存储在 playlist_song 关联表中，按用户隔离。
     *
     * @param userId       用户 ID
     * @param mediaStoreId 歌曲 MediaStore ID
     */
    public void incrementPlayCount(long userId, long mediaStoreId) {
        Playlist history = getByType(userId, Playlist.TYPE_HISTORY);
        if (history == null) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        Long songRowId = findSongRowId(db, mediaStoreId);
        if (songRowId == null) return;
        // 在 playlist_song 关联记录上原子自增 play_count
        db.execSQL("UPDATE " + DatabaseHelper.T_PLAYLIST_SONG
                + " SET " + DatabaseHelper.C_PS_PLAY_COUNT
                + " = " + DatabaseHelper.C_PS_PLAY_COUNT + " + 1"
                + " WHERE " + DatabaseHelper.C_PS_PLAYLIST_ID + " = ?"
                + " AND " + DatabaseHelper.C_PS_SONG_ID + " = ?",
                new Object[]{history.id, songRowId});
    }

    /**
     * 获取指定用户播放次数最高的歌曲排行榜。自动过滤已从设备删除的文件。
     * 播放次数从 playlist_song 关联表读取，按用户隔离。
     *
     * @param userId 用户 ID
     * @param limit  返回数量上限
     * @return 按播放次数降序排列的歌曲列表
     */
    public List<Song> getTopPlayedSongs(long userId, int limit) {
        List<Song> all = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        // JOIN 查询：通过用户最近播放歌单的 playlist_song 关联获取按用户隔离的播放次数
        Playlist history = getByType(userId, Playlist.TYPE_HISTORY);
        if (history == null) return all;
        String sql = "SELECT s.*, ps." + DatabaseHelper.C_PS_PLAY_COUNT + " AS user_play_count"
                + " FROM " + DatabaseHelper.T_SONGS + " s"
                + " INNER JOIN " + DatabaseHelper.T_PLAYLIST_SONG + " ps"
                + " ON s.id = ps." + DatabaseHelper.C_PS_SONG_ID
                + " WHERE ps." + DatabaseHelper.C_PS_PLAYLIST_ID + " = ?"
                + " AND ps." + DatabaseHelper.C_PS_PLAY_COUNT + " > 0"
                + " ORDER BY ps." + DatabaseHelper.C_PS_PLAY_COUNT + " DESC"
                + " LIMIT " + limit;
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(history.id)})) {
            while (c.moveToNext()) {
                Song song = cursorToSong(c);
                // 读取按用户隔离的播放次数
                int pcIdx = c.getColumnIndex("user_play_count");
                if (pcIdx >= 0) {
                    song.playCount = c.getLong(pcIdx);
                }
                all.add(song);
            }
        }
        // 与 getSongs 相同的校验逻辑：过滤已删除文件
        Set<Long> validIds = queryValidMediaStoreIds(all);
        List<Song> songs = new ArrayList<>();
        List<Long> staleIds = new ArrayList<>();
        for (Song song : all) {
            if (validIds.contains(song.mediaStoreId)) {
                songs.add(song);
            } else {
                staleIds.add(song.id);
            }
        }
        if (!staleIds.isEmpty()) {
            cleanStaleSongs(db, staleIds);
        }
        return songs;
    }

    /**
     * 获取指定歌曲在当前用户最近播放歌单中的播放次数。
     *
     * @param userId       用户 ID
     * @param mediaStoreId 歌曲 MediaStore ID
     * @return 播放次数，未找到返回 0
     */
    public long getPlayCount(long userId, long mediaStoreId) {
        Playlist history = getByType(userId, Playlist.TYPE_HISTORY);
        if (history == null) return 0;
        SQLiteDatabase db = helper.getReadableDatabase();
        Long songRowId = findSongRowId(db, mediaStoreId);
        if (songRowId == null) return 0;
        try (Cursor c = db.query(DatabaseHelper.T_PLAYLIST_SONG,
                new String[]{DatabaseHelper.C_PS_PLAY_COUNT},
                DatabaseHelper.C_PS_PLAYLIST_ID + " = ? AND "
                        + DatabaseHelper.C_PS_SONG_ID + " = ?",
                new String[]{String.valueOf(history.id), String.valueOf(songRowId)},
                null, null, null, "1")) {
            if (c.moveToNext()) {
                return c.getLong(0);
            }
        }
        return 0;
    }

    /**
     * 通过 mediaStoreId 查找歌曲在 songs 表中的行 ID。
     *
     * @param db           数据库实例
     * @param mediaStoreId MediaStore 中的音频 ID
     * @return 行 ID，不存在返回 null
     */
    private Long findSongRowId(SQLiteDatabase db, long mediaStoreId) {
        try (Cursor c = db.query(DatabaseHelper.T_SONGS,
                new String[]{"id"},
                DatabaseHelper.C_SONG_MEDIA_STORE_ID + " = ?",
                new String[]{String.valueOf(mediaStoreId)},
                null, null, null, "1")) {
            if (c.moveToNext()) {
                return c.getLong(0);
            }
        }
        return null;
    }

    /**
     * 将数据库游标转换为 Playlist 对象。
     *
     * @param c 游标，当前行指向一条歌单记录
     * @return 填充好的 Playlist 对象
     */
    private Playlist cursorToPlaylist(Cursor c) {
        Playlist p = new Playlist();
        p.id = c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.C_PL_ID));
        p.name = c.getString(c.getColumnIndexOrThrow(DatabaseHelper.C_PL_NAME));
        p.type = c.getInt(c.getColumnIndexOrThrow(DatabaseHelper.C_PL_TYPE));
        p.createdAt = c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.C_PL_CREATED_AT));
        p.userId = c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.C_PL_USER_ID));
        return p;
    }

    /**
     * 将数据库游标转换为 Song 对象。
     * playCount 列为 songs 表的全局播放次数（旧版兼容），实际的按用户播放次数
     * 由 getTopPlayedSongs 中通过 playlist_song.play_count 单独填充。
     *
     * @param c 游标，当前行指向一条歌曲记录
     * @return 填充好的 Song 对象
     */
    private Song cursorToSong(Cursor c) {
        Song s = new Song();
        s.id = c.getLong(c.getColumnIndexOrThrow("id"));
        s.mediaStoreId = c.getLong(c.getColumnIndexOrThrow(
                DatabaseHelper.C_SONG_MEDIA_STORE_ID));
        s.title = c.getString(c.getColumnIndexOrThrow(DatabaseHelper.C_SONG_TITLE));
        s.artist = c.getString(c.getColumnIndexOrThrow(DatabaseHelper.C_SONG_ARTIST));
        s.album = c.getString(c.getColumnIndexOrThrow(DatabaseHelper.C_SONG_ALBUM));
        s.duration = c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.C_SONG_DURATION));
        // songs.play_count 为旧版全局播放次数，新逻辑中由调用方按需覆盖
        int pcIdx = c.getColumnIndex(DatabaseHelper.C_SONG_PLAY_COUNT);
        if (pcIdx >= 0) {
            s.playCount = c.getLong(pcIdx);
        }
        return s;
    }

    /**
     * 确保指定用户的内置歌单（"我喜欢的音乐"和"最近播放"）存在，不存在则创建。
     *
     * @param userId 用户 ID
     */
    public void ensureBuiltinPlaylists(long userId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        helper.ensureBuiltinPlaylists(db, userId);
    }
}

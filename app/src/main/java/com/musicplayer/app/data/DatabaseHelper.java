package com.musicplayer.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;

/**
 * SQLite 数据库助手类，管理应用本地数据库的创建与版本升级。
 * <p>
 * 数据库包含四张表：
 * <ul>
 *   <li>songs — 歌曲元数据（mediaStoreId、标题、歌手、专辑、时长），全局共享</li>
 *   <li>playlists — 歌单（名称、类型、创建时间、所属用户），内置歌单不可删除</li>
 *   <li>playlist_song — 歌单与歌曲的多对多关联，含按用户隔离的 play_count</li>
 *   <li>users — 用户信息（用户名、密码、昵称），支持多用户注册登录</li>
 * </ul>
 * 播放次数自 v5 起从 songs.play_count 迁移到 playlist_song.play_count，实现按用户隔离。
 * 采用单例模式，全局只维护一个 DatabaseHelper 实例。
 * </p>
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "netmusic.db";
    private static final int DB_VERSION = 6;

    // ---- 表名常量 ----
    /** 歌曲元数据表 */
    public static final String T_SONGS = "songs";
    /** 歌单表 */
    public static final String T_PLAYLISTS = "playlists";
    /** 歌单-歌曲关联表（多对多） */
    public static final String T_PLAYLIST_SONG = "playlist_song";
    /** 用户表 */
    public static final String T_USERS = "users";

    // ---- songs 表列名 ----
    /** MediaStore 中的音频 _ID，作为 songs 表的唯一业务键 */
    public static final String C_SONG_MEDIA_STORE_ID = "media_store_id";
    /** 歌曲标题 */
    public static final String C_SONG_TITLE = "title";
    /** 歌手名称 */
    public static final String C_SONG_ARTIST = "artist";
    /** 专辑名称 */
    public static final String C_SONG_ALBUM = "album";
    /** 歌曲时长（毫秒） */
    public static final String C_SONG_DURATION = "duration";
    /** 歌曲全局播放次数（v2 新增，v5 后已迁移到 playlist_song.play_count，此列仅做数据迁移兼容） */
    public static final String C_SONG_PLAY_COUNT = "play_count";

    // ---- playlists 表列名 ----
    /** 歌单主键，自增 ID */
    public static final String C_PL_ID = "id";
    /** 歌单名称 */
    public static final String C_PL_NAME = "name";
    /** 歌单类型：0=用户自建，1=我喜欢的音乐，2=最近播放 */
    public static final String C_PL_TYPE = "type";
    /** 创建时间（毫秒时间戳） */
    public static final String C_PL_CREATED_AT = "created_at";
    /** 歌单所属用户 ID（v4 新增） */
    public static final String C_PL_USER_ID = "user_id";

    // ---- playlist_song 表列名 ----
    /** 关联的歌单 ID */
    public static final String C_PS_PLAYLIST_ID = "playlist_id";
    /** 关联的 songs 表主键 */
    public static final String C_PS_SONG_ID = "song_id";
    /** 添加到歌单的时间（毫秒时间戳） */
    public static final String C_PS_ADDED_AT = "added_at";
    /** 歌曲在该歌单中的播放次数（v5 新增，实现按用户隔离的播放统计） */
    public static final String C_PS_PLAY_COUNT = "play_count";

    // ---- users 表列名 ----
    /** 用户主键，自增 ID */
    public static final String C_USER_ID = "id";
    /** 用户名，唯一 */
    public static final String C_USER_USERNAME = "username";
    /** 密码（明文存储，本地应用简化处理） */
    public static final String C_USER_PASSWORD = "password";
    /** 昵称 */
    public static final String C_USER_NICKNAME = "nickname";
    /** 注册时间（毫秒时间戳） */
    public static final String C_USER_CREATED_AT = "created_at";

    private static volatile DatabaseHelper instance;

    /**
     * 获取 DatabaseHelper 单例。使用 ApplicationContext 避免内存泄漏。
     *
     * @param context 任意上下文
     * @return DatabaseHelper 实例
     */
    public static synchronized DatabaseHelper get(@NonNull Context context) {
        if (instance == null) {
            instance = new DatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    /**
     * 数据库首次创建时调用。建表并插入内置歌单。
     * <p>
     * 表结构说明：
     * <ul>
     *   <li>songs 表：media_store_id 设为 UNIQUE，保证同一首歌曲不重复存储</li>
     *   <li>playlists 表：type 字段区分内置歌单与用户歌单</li>
     *   <li>playlist_song 表：联合主键 (playlist_id, song_id) 防止重复关联，
     *       外键引用 playlists(id) ON DELETE CASCADE，删除歌单时自动清理关联</li>
     * </ul>
     * </p>
     */
    @Override
    public void onCreate(SQLiteDatabase db) {
        // songs 表：存储歌曲元数据，media_store_id 为唯一键
        db.execSQL("CREATE TABLE " + T_SONGS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + C_SONG_MEDIA_STORE_ID + " INTEGER UNIQUE NOT NULL, "
                + C_SONG_TITLE + " TEXT, "
                + C_SONG_ARTIST + " TEXT, "
                + C_SONG_ALBUM + " TEXT, "
                + C_SONG_DURATION + " INTEGER, "
                + C_SONG_PLAY_COUNT + " INTEGER NOT NULL DEFAULT 0)");

        // playlists 表：type=0 用户自建，type=1 收藏，type=2 最近播放
        db.execSQL("CREATE TABLE " + T_PLAYLISTS + " ("
                + C_PL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + C_PL_NAME + " TEXT NOT NULL, "
                + C_PL_TYPE + " INTEGER NOT NULL DEFAULT 0, "
                + C_PL_CREATED_AT + " INTEGER NOT NULL, "
                + C_PL_USER_ID + " INTEGER NOT NULL DEFAULT 1)");

        // playlist_song 关联表：联合主键防止重复，外键级联删除，play_count 按用户歌单维度统计
        db.execSQL("CREATE TABLE " + T_PLAYLIST_SONG + " ("
                + C_PS_PLAYLIST_ID + " INTEGER NOT NULL, "
                + C_PS_SONG_ID + " INTEGER NOT NULL, "
                + C_PS_ADDED_AT + " INTEGER NOT NULL, "
                + C_PS_PLAY_COUNT + " INTEGER NOT NULL DEFAULT 0, "
                + "PRIMARY KEY(" + C_PS_PLAYLIST_ID + ", " + C_PS_SONG_ID + "), "
                + "FOREIGN KEY(" + C_PS_PLAYLIST_ID + ") REFERENCES "
                + T_PLAYLISTS + "(" + C_PL_ID + ") ON DELETE CASCADE)");

        // 为 song_id 建索引，加速按歌曲查歌单的反向查询
        db.execSQL("CREATE INDEX idx_ps_song ON " + T_PLAYLIST_SONG
                + "(" + C_PS_SONG_ID + ")");

        // users 表：用户名唯一，支持多用户注册和登录
        db.execSQL("CREATE TABLE " + T_USERS + " ("
                + C_USER_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + C_USER_USERNAME + " TEXT UNIQUE NOT NULL, "
                + C_USER_PASSWORD + " TEXT NOT NULL, "
                + C_USER_NICKNAME + " TEXT, "
                + C_USER_CREATED_AT + " INTEGER NOT NULL)");

        // 初始化内置歌单（默认用户 ID 为 1）
        ensureBuiltinPlaylists(db, 1);
    }

    /**
     * 数据库版本升级回调，按版本号递增执行迁移。
     * <ul>
     *   <li>v2: songs 表新增 play_count 列</li>
     *   <li>v3: 新增 users 表</li>
     *   <li>v4: playlists 表新增 user_id 列，歌单按用户隔离</li>
     *   <li>v5: playlist_song 表新增 play_count 列，播放次数从全局迁移到按用户隔离</li>
     *   <li>v6: 清理旧版 insertBuiltin 产生的重复内置歌单，每个用户每种类型仅保留最早一条</li>
     * </ul>
     *
     * @param db         数据库实例
     * @param oldVersion 旧版本号
     * @param newVersion 新版本号
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // v2：为 songs 表新增 play_count 字段，默认值 0
            db.execSQL("ALTER TABLE " + T_SONGS + " ADD COLUMN "
                    + C_SONG_PLAY_COUNT + " INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 3) {
            // v3：新增 users 表，支持多用户注册和登录
            db.execSQL("CREATE TABLE " + T_USERS + " ("
                    + C_USER_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + C_USER_USERNAME + " TEXT UNIQUE NOT NULL, "
                    + C_USER_PASSWORD + " TEXT NOT NULL, "
                    + C_USER_NICKNAME + " TEXT, "
                    + C_USER_CREATED_AT + " INTEGER NOT NULL)");
        }
        if (oldVersion < 4) {
            // v4：playlists 表新增 user_id 列，支持多用户歌单隔离
            db.execSQL("ALTER TABLE " + T_PLAYLISTS + " ADD COLUMN "
                    + C_PL_USER_ID + " INTEGER NOT NULL DEFAULT 1");
        }
        if (oldVersion < 5) {
            // v5：playlist_song 关联表新增 play_count 列，实现按用户隔离的播放次数统计
            db.execSQL("ALTER TABLE " + T_PLAYLIST_SONG + " ADD COLUMN "
                    + C_PS_PLAY_COUNT + " INTEGER NOT NULL DEFAULT 0");
            // 将 songs 表旧的全局 play_count 迁移到各用户的最近播放歌单关联记录中
            db.execSQL("UPDATE " + T_PLAYLIST_SONG + " SET " + C_PS_PLAY_COUNT
                    + " = (SELECT s." + C_SONG_PLAY_COUNT + " FROM " + T_SONGS + " s"
                    + " WHERE s.id = " + T_PLAYLIST_SONG + "." + C_PS_SONG_ID + ")"
                    + " WHERE " + C_PS_PLAY_COUNT + " = 0"
                    + " AND EXISTS (SELECT 1 FROM " + T_SONGS + " s"
                    + " WHERE s.id = " + T_PLAYLIST_SONG + "." + C_PS_SONG_ID
                    + " AND s." + C_SONG_PLAY_COUNT + " > 0)");
        }
        if (oldVersion < 6) {
            db.execSQL("DELETE FROM " + T_PLAYLISTS + " WHERE " + C_PL_ID + " NOT IN ("
                    + "SELECT MIN(" + C_PL_ID + ") FROM " + T_PLAYLISTS
                    + " WHERE " + C_PL_TYPE + " > 0"
                    + " GROUP BY " + C_PL_TYPE + ", " + C_PL_USER_ID + ")");
        }
    }

    /**
     * 创建内置歌单："我喜欢的音乐"（TYPE_FAVORITE）和"最近播放"（TYPE_HISTORY）。
     * 内部调用 insertBuiltin 先查询再插入，确保重复调用不会产生重复记录。
     *
     * @param db     可写数据库实例
     * @param userId 用户 ID
     */
    public void ensureBuiltinPlaylists(SQLiteDatabase db, long userId) {
        long now = System.currentTimeMillis();
        insertBuiltin(db, "我喜欢的音乐", Playlist.TYPE_FAVORITE, now, userId);
        insertBuiltin(db, "最近播放", Playlist.TYPE_HISTORY, now, userId);
    }

    /**
     * 插入一条内置歌单记录，若已存在则忽略。
     *
     * @param db        数据库实例
     * @param name      歌单名称
     * @param type      歌单类型
     * @param createdAt 创建时间
     * @param userId    用户 ID
     */
    private void insertBuiltin(SQLiteDatabase db, String name, int type, long createdAt, long userId) {
        // 先查询该用户是否已有此类型的内置歌单，避免重复插入
        try (android.database.Cursor c = db.query(T_PLAYLISTS, new String[]{C_PL_ID},
                C_PL_TYPE + "=? AND " + C_PL_USER_ID + "=?",
                new String[]{String.valueOf(type), String.valueOf(userId)},
                null, null, null)) {
            if (c.moveToFirst()) return; // 已存在则跳过
        }
        ContentValues cv = new ContentValues();
        cv.put(C_PL_NAME, name);
        cv.put(C_PL_TYPE, type);
        cv.put(C_PL_CREATED_AT, createdAt);
        cv.put(C_PL_USER_ID, userId);
        db.insert(T_PLAYLISTS, null, cv);
    }
}

package com.musicplayer.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * 用户数据访问层，封装 users 表的增删查操作。
 * <p>
 * 支持多用户注册和登录，用户名唯一约束保证不重复注册。
 * </p>
 */
public class UserDao {

    private final DatabaseHelper helper;

    public UserDao(Context context) {
        this.helper = DatabaseHelper.get(context);
    }

    /**
     * 注册新用户。
     *
     * @param username 用户名（唯一）
     * @param password 密码
     * @param nickname 昵称
     * @return 新用户的 ID，若用户名已存在返回 -1
     */
    public long register(String username, String password, String nickname) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(DatabaseHelper.C_USER_USERNAME, username);
        cv.put(DatabaseHelper.C_USER_PASSWORD, password);
        cv.put(DatabaseHelper.C_USER_NICKNAME, nickname);
        cv.put(DatabaseHelper.C_USER_CREATED_AT, System.currentTimeMillis());
        long id = db.insertWithOnConflict(DatabaseHelper.T_USERS, null, cv,
                SQLiteDatabase.CONFLICT_IGNORE);
        return id;
    }

    /**
     * 登录验证：根据用户名和密码查询用户。
     *
     * @param username 用户名
     * @param password 密码
     * @return 用户 ID，验证失败返回 -1
     */
    public long login(String username, String password) {
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.query(DatabaseHelper.T_USERS,
                new String[]{DatabaseHelper.C_USER_ID},
                DatabaseHelper.C_USER_USERNAME + "=? AND " + DatabaseHelper.C_USER_PASSWORD + "=?",
                new String[]{username, password},
                null, null, null)) {
            if (c.moveToFirst()) {
                return c.getLong(0);
            }
            return -1;
        }
    }

    /**
     * 检查用户名是否已存在。
     *
     * @param username 用户名
     * @return true 已存在
     */
    public boolean exists(String username) {
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.query(DatabaseHelper.T_USERS,
                new String[]{DatabaseHelper.C_USER_ID},
                DatabaseHelper.C_USER_USERNAME + "=?",
                new String[]{username},
                null, null, null)) {
            return c.moveToFirst();
        }
    }

    /**
     * 根据用户 ID 获取昵称。
     *
     * @param userId 用户 ID
     * @return 昵称，不存在返回 null
     */
    public String getNickname(long userId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.query(DatabaseHelper.T_USERS,
                new String[]{DatabaseHelper.C_USER_NICKNAME},
                DatabaseHelper.C_USER_ID + "=?",
                new String[]{String.valueOf(userId)},
                null, null, null)) {
            if (c.moveToFirst()) {
                return c.getString(0);
            }
            return null;
        }
    }

    /**
     * 根据用户 ID 获取用户名。
     *
     * @param userId 用户 ID
     * @return 用户名，不存在返回 null
     */
    public String getUsername(long userId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.query(DatabaseHelper.T_USERS,
                new String[]{DatabaseHelper.C_USER_USERNAME},
                DatabaseHelper.C_USER_ID + "=?",
                new String[]{String.valueOf(userId)},
                null, null, null)) {
            if (c.moveToFirst()) {
                return c.getString(0);
            }
            return null;
        }
    }
}

package com.musicplayer.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.musicplayer.app.data.PlaylistDao;
import com.musicplayer.app.data.UserDao;

/**
 * 用户登录状态管理。
 * <p>
 * 用户注册和登录验证通过 UserDao（SQLite）完成，
 * 当前登录状态通过 SharedPreferences 持久化，
 * 保证应用重启后仍能识别已登录用户。
 * </p>
 */
public class UserManager {

    private static final String PREF_NAME = "user_pref";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_LOGGED_IN = "logged_in";

    private static UserManager instance;
    private final SharedPreferences sp;
    private final UserDao userDao;
    private final PlaylistDao playlistDao;

    private UserManager(Context context) {
        Context app = context.getApplicationContext();
        sp = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        userDao = new UserDao(app);
        playlistDao = new PlaylistDao(app);
        // 清除旧版登录系统的 SharedPreferences 残留
        cleanLegacyLoginData(app);
    }

    /**
     * 清除旧版登录数据（旧版用 local_users SP 文件存储，已废弃）。
     * 仅在旧文件仍有数据时执行清理，清理后后续调用无开销。
     */
    private void cleanLegacyLoginData(Context app) {
        SharedPreferences legacy = app.getSharedPreferences("local_users", Context.MODE_PRIVATE);
        if (legacy.getAll().size() > 0) {
            legacy.edit().clear().apply();
        }
    }

    /** 获取单例 */
    public static synchronized UserManager get(Context context) {
        if (instance == null) {
            instance = new UserManager(context);
        }
        return instance;
    }

    /**
     * 用户注册。
     *
     * @param username 用户名
     * @param password 密码
     * @param nickname 昵称
     * @return 新用户 ID，用户名已存在返回 -1
     */
    public long register(String username, String password, String nickname) {
        return userDao.register(username, password, nickname);
    }

    /**
     * 用户登录验证。
     * <p>
     * 验证成功后自动将登录状态写入 SharedPreferences。
     * </p>
     *
     * @param username 用户名
     * @param password 密码
     * @return true 登录成功
     */
    public boolean login(String username, String password) {
        long userId = userDao.login(username, password);
        if (userId > 0) {
            sp.edit()
                    .putLong(KEY_USER_ID, userId)
                    .putBoolean(KEY_LOGGED_IN, true)
                    .apply();
            // 确保该用户拥有内置歌单（"我喜欢的音乐"和"最近播放"），新用户首次登录时自动创建
            playlistDao.ensureBuiltinPlaylists(userId);
            return true;
        }
        return false;
    }

    /** 当前是否已登录（校验数据库中用户是否存在，防止旧缓存导致假登录） */
    public boolean isLoggedIn() {
        if (!sp.getBoolean(KEY_LOGGED_IN, false)) return false;
        long id = sp.getLong(KEY_USER_ID, -1);
        if (id <= 0) {
            logout();
            return false;
        }
        // 确认数据库中该用户仍然存在
        if (userDao.getUsername(id) == null) {
            logout();
            return false;
        }
        return true;
    }

    /** 获取当前登录用户 ID，未登录返回 -1 */
    public long getUserId() {
        return sp.getLong(KEY_USER_ID, -1);
    }

    /** 获取当前登录用户昵称，未登录返回 null */
    public String getNickname() {
        long id = getUserId();
        if (id <= 0) return null;
        return userDao.getNickname(id);
    }

    /** 获取当前登录用户名，未登录返回 null */
    public String getUsername() {
        long id = getUserId();
        if (id <= 0) return null;
        return userDao.getUsername(id);
    }

    /** 退出登录，清除登录状态 */
    public void logout() {
        sp.edit().clear().apply();
    }

    /** 检查用户名是否已存在 */
    public boolean exists(String username) {
        return userDao.exists(username);
    }
}

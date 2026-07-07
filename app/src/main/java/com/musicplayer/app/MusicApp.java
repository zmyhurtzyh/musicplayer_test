package com.musicplayer.app;

import android.app.Application;

import com.musicplayer.app.util.CoverLoader;
import com.musicplayer.app.util.UserManager;

/**
 * 应用程序入口类，在应用启动时初始化全局单例组件。
 * <p>
 * 在 onCreate() 中依次初始化：
 * <ul>
 *   <li>CoverLoader — 封面图片加载器，初始化 LruCache 内存缓存</li>
 *   <li>UserManager — 用户状态管理器，初始化 SharedPreferences</li>
 * </ul>
 * 这两个组件采用单例模式，必须在 Application 生命周期早期完成初始化，
 * 以确保后续 Activity/Service 中可以安全获取实例。
 * </p>
 */
public class MusicApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 初始化封面加载器（构建 LruCache 内存缓存）
        CoverLoader.init(this);
        // 初始化用户状态管理器（打开 SharedPreferences + UserDao）
        UserManager.get(this);
    }
}

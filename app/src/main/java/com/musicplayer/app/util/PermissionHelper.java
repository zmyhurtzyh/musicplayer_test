package com.musicplayer.app.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限管理工具类，统一处理不同 Android 版本下的音频读取与通知权限。
 * <p>
 * 核心设计：
 * <ul>
 *   <li>Android 13（TIRAMISU）及以上：使用 READ_MEDIA_AUDIO 媒体权限</li>
 *   <li>Android 12 及以下：使用 READ_EXTERNAL_STORAGE 传统存储权限</li>
 *   <li>Android 13+ 额外请求 POST_NOTIFICATIONS 通知权限</li>
 * </ul>
 * 工具类不可实例化，所有方法均为静态方法。
 * </p>
 */
public final class PermissionHelper {

    /** 音频权限请求码，用于 onRequestPermissionsResult 回调中识别 */
    public static final int REQ_AUDIO = 0xA01;

    private PermissionHelper() {
    }

    /**
     * 获取当前设备需要的音频读取权限数组。
     * Android 13+ 返回 READ_MEDIA_AUDIO，低版本返回 READ_EXTERNAL_STORAGE。
     *
     * @return 权限字符串数组
     */
    public static String[] requiredAudioPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.READ_MEDIA_AUDIO};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    /**
     * 检查是否已获得音频读取权限。
     *
     * @param context 上下文
     * @return true 已授权，false 未授权
     */
    public static boolean hasAudioPermission(Context context) {
        for (String p : requiredAudioPermissions()) {
            if (ContextCompat.checkSelfPermission(context, p)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取通知权限数组。仅 Android 13+ 需要申请 POST_NOTIFICATIONS，
     * 低版本返回空数组（无需申请即可发送通知）。
     *
     * @return 权限字符串数组，可能为空
     */
    public static String[] requiredNotificationPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.POST_NOTIFICATIONS};
        }
        return new String[0];
    }

    /**
     * 一次性请求音频权限（Android 13+ 时同时请求通知权限）。
     *
     * @param activity 当前 Activity，用于触发系统权限对话框
     */
    public static void requestAudioPermission(Activity activity) {
        List<String> perms = new ArrayList<>();
        // 合并音频权限和通知权限
        for (String p : requiredAudioPermissions()) perms.add(p);
        for (String p : requiredNotificationPermissions()) perms.add(p);
        ActivityCompat.requestPermissions(activity,
                perms.toArray(new String[0]), REQ_AUDIO);
    }

    /**
     * 跳转到应用详情设置页面，供用户手动开启被永久拒绝的权限。
     *
     * @param context 上下文
     */
    public static void openAppSettings(Context context) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + context.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }
}

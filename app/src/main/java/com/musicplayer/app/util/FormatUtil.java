package com.musicplayer.app.util;

/**
 * 格式化工具类，提供时长、播放次数、文件大小的友好显示格式。
 * <p>
 * 三个核心方法：
 * <ul>
 *   <li>formatDuration — 将毫秒时长转为 "m:ss" 或 "h:mm:ss" 格式</li>
 *   <li>formatCount — 将播放次数转为中文友好格式（如 "1.2万"）</li>
 *   <li>formatSize — 将字节数转为 B/KB/MB 格式</li>
 * </ul>
 * 工具类不可实例化，所有方法均为静态方法。
 * </p>
 */
public final class FormatUtil {

    private FormatUtil() {
    }

    /**
     * 将毫秒时长格式化为可读的字符串。
     * 不足1小时显示 "m:ss"（如 3:05），超过1小时显示 "h:mm:ss"（如 1:02:30）。
     *
     * @param ms 时长毫秒数，负数会被视为 0
     * @return 格式化后的时长字符串
     */
    public static String formatDuration(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%d:%02d", m, s);
    }

    /**
     * 将播放次数格式化为中文友好格式。
     * 小于1万直接显示数字，1万~10亿显示"x.x万"（如 12345 → "1.2万"），
     * 超过10万显示整数万（如 150000 → "15万"）。
     *
     * @param count 播放次数
     * @return 格式化后的字符串
     */
    public static String formatCount(long count) {
        if (count < 10000) {
            return String.valueOf(count);
        }
        double wan = count / 10000.0;
        if (wan < 100000) {
            return String.format("%.1f万", wan);
        }
        return String.format("%.0f万", wan);
    }

    /**
     * 将文件大小格式化为可读的字符串。
     * 小于1KB显示 "xB"，小于1MB显示 "x.xKB"，否则显示 "x.xMB"。
     *
     * @param bytes 文件大小（字节）
     * @return 格式化后的大小字符串
     */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
    }
}

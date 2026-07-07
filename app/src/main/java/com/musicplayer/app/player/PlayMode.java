package com.musicplayer.app.player;

/**
 * 播放模式枚举，定义了三种播放模式：列表循环、单曲循环、随机播放。
 * <p>
 * 每种模式对应一个整数值，便于持久化存储（如 SharedPreferences）。
 * 通过 {@link #next()} 方法可按固定顺序循环切换模式：列表循环 → 单曲循环 → 随机播放 → 列表循环。
 * </p>
 */
public enum PlayMode {

    /** 列表循环：按顺序播放队列，到末尾后回到第一首 */
    LIST(0),
    /** 单曲循环：当前歌曲播放完毕后重新播放 */
    REPEAT_ONE(1),
    /** 随机播放：从队列中随机选取歌曲播放 */
    SHUFFLE(2);

    /** 模式对应的整数值，用于持久化存储 */
    public final int value;

    /**
     * 构造枚举项。
     *
     * @param value 模式对应的整数值
     */
    PlayMode(int value) {
        this.value = value;
    }

    /**
     * 获取下一个播放模式，按循环顺序：LIST → REPEAT_ONE → SHUFFLE → LIST。
     *
     * @return 下一个播放模式
     */
    public PlayMode next() {
        switch (this) {
            case LIST:
                return REPEAT_ONE;
            case REPEAT_ONE:
                return SHUFFLE;
            case SHUFFLE:
            default:
                return LIST;
        }
    }

    /**
     * 根据整数值获取对应的播放模式枚举。
     * 若传入的值不匹配任何模式，默认返回列表循环 (LIST)。
     *
     * @param value 播放模式的整数值
     * @return 对应的 PlayMode 枚举，未匹配时返回 LIST
     */
    public static PlayMode fromValue(int value) {
        for (PlayMode mode : values()) {
            if (mode.value == value) return mode;
        }
        return LIST;
    }
}

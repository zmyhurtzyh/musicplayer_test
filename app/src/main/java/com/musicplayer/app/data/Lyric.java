package com.musicplayer.app.data;

/**
 * 一行歌词数据，由 LyricParser 解析 LRC 文件后产生。
 * <p>
 * 包含两个不可变字段：起始时间（毫秒）和歌词文本。
 * 实现 Comparable 接口以便按时间升序排序，供二分查找定位当前歌词行。
 * </p>
 */
public class Lyric implements Comparable<Lyric> {

    /** 歌词起始时间，单位毫秒，对应 LRC 中的时间标签 */
    public final long time;
    /** 歌词文本内容，不含时间标签 */
    public final String text;

    /**
     * 构造一行歌词。
     *
     * @param time 起始时间（毫秒）
     * @param text 歌词文本，null 时转为空字符串
     */
    public Lyric(long time, String text) {
        this.time = time;
        this.text = text == null ? "" : text;
    }

    /**
     * 按时间升序比较，用于歌词列表排序。
     *
     * @param o 另一行歌词
     * @return 比较结果
     */
    @Override
    public int compareTo(Lyric o) {
        return Long.compare(this.time, o.time);
    }

    @Override
    public String toString() {
        return "Lyric{" + time + ": " + text + "}";
    }
}

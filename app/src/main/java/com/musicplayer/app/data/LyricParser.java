package com.musicplayer.app.data;

import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 歌词解析器，负责将 LRC 格式的歌词文本解析为按时间排序的 Lyric 列表。
 * <p>
 * 支持标准 LRC 时间标签格式 [mm:ss.xx] 和 [mm:ss.xxx]。
 * 同一行多个时间标签会展开为多条独立的歌词行。
 * 编码猜测策略：优先尝试 UTF-8（含 BOM 检测），失败后回退到 GBK。
 * 解析完成后自动按时间升序排列，并提供二分查找方法定位当前播放进度对应的歌词行。
 * </p>
 */
public class LyricParser {

    /**
     * 匹配 LRC 时间标签的正则表达式。
     * 捕获组：group(1)=分钟, group(2)=秒, group(3)=毫秒（可选，1-3位）
     */
    private static final Pattern TIME_TAG =
            Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    public static List<Lyric> EMPTY = Collections.emptyList();

    private LyricParser() {
    }

    /**
     * 从字符串解析歌词。输入文本需为 UTF-8 编码的内容。
     *
     * @param lrcText LRC 格式的歌词文本
     * @return 解析后的歌词列表，输入为空时返回 EMPTY
     */
    public static List<Lyric> parse(String lrcText) {
        if (TextUtils.isEmpty(lrcText)) {
            return EMPTY;
        }
        return parse(new ByteArrayInputStream(lrcText.getBytes(Charset.forName("UTF-8"))));
    }

    /**
     * 从 .lrc 文件解析歌词。自动尝试 UTF-8 与 GBK 编码。
     *
     * @param path 歌词文件的绝对路径
     * @return 解析后的歌词列表，文件不存在或读取失败时返回 EMPTY
     */
    public static List<Lyric> parseFile(String path) {
        if (TextUtils.isEmpty(path)) {
            return EMPTY;
        }
        File file = new File(path);
        if (!file.exists() || !file.canRead()) {
            return EMPTY;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            return parse(fis);
        } catch (Exception e) {
            return EMPTY;
        }
    }

    /**
     * 从输入流解析歌词，核心解析逻辑。
     * <p>
     * 解析流程：
     * 1. 读取全部字节，通过编码猜测策略解码为字符串
     * 2. 按行分割，逐行提取时间标签
     * 3. 同一行多个时间标签展开为多条歌词（如 [00:01.00][00:15.00]歌词 → 两条记录）
     * 4. 过滤无时间标签的元数据行（如 [ti:歌名]、[ar:歌手]）
     * 5. 按时间升序排序
     * </p>
     *
     * @param input 输入流
     * @return 按时间升序排列的歌词列表
     */
    public static List<Lyric> parse(InputStream input) {
        if (input == null) return EMPTY;

        byte[] bytes = readAll(input);
        String text = decode(bytes);
        if (text == null) return EMPTY;

        List<Lyric> result = new ArrayList<>();
        // 按换行符分割，兼容 \r\n 和 \n
        String[] lines = text.split("\\r?\\n");
        for (String rawLine : lines) {
            if (rawLine == null) continue;
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            // 提取行内所有时间标签
            Matcher m = TIME_TAG.matcher(line);
            List<Long> times = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                times.add(parseTime(m));
                lastEnd = m.end();
            }
            if (times.isEmpty()) {
                // 无时间标签的行为元数据行（如 [ti:]、[ar:]），跳过
                continue;
            }
            // 时间标签之后的文本即为歌词内容
            String lyricText = line.substring(lastEnd).trim();
            // 同一行多个时间标签展开：每个时间点对应同一条歌词文本
            for (long t : times) {
                result.add(new Lyric(t, lyricText));
            }
        }

        if (result.isEmpty()) return EMPTY;

        // 按时间升序排序，确保二分查找正确
        Collections.sort(result);
        return result;
    }

    /**
     * 将正则匹配的时间标签转为毫秒时间戳。
     * <p>
     * 支持两种精度：
     * - 两位小数 [.xx]：视为百分之一秒，乘以 10 转换为毫秒
     * - 三位小数 [.xxx]：直接作为毫秒值
     * </p>
     *
     * @param m 正则匹配结果
     * @return 毫秒时间戳
     */
    private static long parseTime(Matcher m) {
        try {
            int min = Integer.parseInt(m.group(1));
            int sec = Integer.parseInt(m.group(2));
            String msStr = m.group(3);
            int ms = 0;
            if (msStr != null) {
                // 兼容 .xx（两位）和 .xxx（三位）两种精度
                if (msStr.length() == 2) {
                    // 两位小数视为百分之一秒，如 .50 = 500ms
                    ms = Integer.parseInt(msStr) * 10;
                } else if (msStr.length() == 3) {
                    // 三位小数直接作为毫秒
                    ms = Integer.parseInt(msStr);
                } else {
                    ms = Integer.parseInt(msStr);
                }
            }
            return min * 60_000L + sec * 1000L + ms;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 编码猜测策略：将字节数组解码为字符串。
     * <p>
     * 策略说明：
     * 1. 检测 UTF-8 BOM（EF BB BF），若存在则跳过 BOM 并按 UTF-8 解码
     * 2. 先尝试 UTF-8 解码，如果成功（不抛异常）则使用
     * 3. UTF-8 解码失败时回退到 GBK（中文歌词常见编码）
     * </p>
     *
     * @param bytes 原始字节数组
     * @return 解码后的字符串，解码失败返回 null
     */
    private static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;

        // 检测 UTF-8 BOM 头
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }

        // 优先 UTF-8 解码
        try {
            return new String(bytes, offset, bytes.length - offset, "UTF-8");
        } catch (Exception ignore) {
            // UTF-8 解码失败，回退到 GBK
            try {
                return new String(bytes, "GBK");
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * 将输入流的全部内容读取为字节数组。
     *
     * @param input 输入流
     * @return 字节数组，读取失败返回 null
     */
    private static byte[] readAll(InputStream input) {
        try (ByteArrayOutputStream2 out = new ByteArrayOutputStream2()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = input.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 轻量 ByteArrayOutputStream 包装，无额外功能，仅为避免直接暴露内部类 */
    private static class ByteArrayOutputStream2 extends java.io.ByteArrayOutputStream {
    }

    /**
     * 二分查找当前播放进度对应的歌词行索引。
     * <p>
     * 算法说明：在按时间升序排列的歌词列表中，找到最后一个 time <= positionMs 的行。
     * 即返回当前正在显示的歌词行，而非即将显示的下一行。
     * 时间复杂度 O(log n)。
     * </p>
     *
     * @param lyrics     歌词列表（需按 time 升序排列）
     * @param positionMs 当前播放进度（毫秒）
     * @return 当前歌词行索引，无匹配时返回 -1
     */
    public static int findCurrentLine(List<Lyric> lyrics, long positionMs) {
        if (lyrics == null || lyrics.isEmpty()) return -1;
        int lo = 0, hi = lyrics.size() - 1;
        int result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lyrics.get(mid).time <= positionMs) {
                // 当前行的起始时间还未超过播放进度，记录并继续向右查找
                result = mid;
                lo = mid + 1;
            } else {
                // 已超过播放进度，向左缩小范围
                hi = mid - 1;
            }
        }
        return result;
    }
}

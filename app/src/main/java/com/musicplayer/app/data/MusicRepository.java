package com.musicplayer.app.data;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地音乐扫描仓库，通过 MediaStore API 查询设备上的音频文件。
 * <p>
 * 核心功能：扫描设备中 Music 和 Downloads 目录下的音频文件，过滤掉时长过短的铃声/提示音，
 * 按 date_added 倒序返回歌曲列表。设计上先触发 MediaScanner 同步，确保新下载的文件能被查到。
 * </p>
 */
public class MusicRepository {

    /** 最短有效时长（10秒），低于此值视为铃声或提示音予以过滤 */
    private static final long MIN_DURATION_MS = 10_000;

    private final ContentResolver resolver;
    private final Context appContext;

    /**
     * 构造方法。
     *
     * @param context 上下文，内部转为 ApplicationContext
     */
    public MusicRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.resolver = appContext.getContentResolver();
    }

    /**
     * 扫描本地全部音乐。先触发媒体扫描同步 MediaStore，再查询音频文件。
     * 过滤掉时长不足 10 秒的短铃声，按 date_added 倒序排列。
     * 对查询到的每首歌曲还会二次校验文件是否真实存在于磁盘。
     *
     * @return 歌曲列表，不会为 null
     */
    public List<Song> loadAllSongs() {
        // 先触发媒体扫描，确保新下载的音频文件被 MediaStore 索引
        scanMediaFiles();
        List<Song> songs = new ArrayList<>();
        // 查询 MediaStore 所需的列：ID、标题、歌手、专辑、专辑ID、时长、大小、文件路径、添加日期
        String[] projection = new String[]{
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_ADDED
        };

        // 过滤条件：标记为音乐 + 时长不低于阈值
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND "
                + MediaStore.Audio.Media.DURATION + " >= " + MIN_DURATION_MS;
        // 按添加时间倒序，最新添加的排最前
        String sortOrder = MediaStore.Audio.Media.DATE_ADDED + " DESC";

        Cursor cursor = null;
        try {
            cursor = resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    sortOrder);

            if (cursor != null) {
                // 预先获取各列索引，避免循环中重复查找
                int idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                int albumIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                int albumIdIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);
                int durIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int sizeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE);
                int dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                int dateIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED);

                while (cursor.moveToNext()) {
                    String data = cursor.getString(dataIdx);
                    // 二次校验文件是否真实存在于磁盘，防止 MediaStore 索引过期
                    if (data != null && !new File(data).exists()) {
                        continue;
                    }
                    Song song = new Song();
                    song.mediaStoreId = cursor.getLong(idIdx);
                    song.title = cursor.getString(titleIdx);
                    song.artist = cursor.getString(artistIdx);
                    song.album = cursor.getString(albumIdx);
                    // albumId 列可能不存在（getColumnIndex 返回 -1），需安全处理
                    song.albumId = albumIdIdx >= 0 ? cursor.getLong(albumIdIdx) : 0;
                    song.duration = cursor.getLong(durIdx);
                    song.size = cursor.getLong(sizeIdx);
                    song.data = data;
                    // MediaStore 的 DATE_ADDED 单位为秒，转为毫秒
                    song.dateAdded = cursor.getLong(dateIdx) * 1000L;
                    songs.add(song);
                }
            }
        } catch (Exception e) {
            return songs;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return songs;
    }

    /**
     * 按关键字过滤歌曲列表，匹配标题、歌手、专辑（不区分大小写）。
     *
     * @param source  原始歌曲列表
     * @param keyword 搜索关键字，null 或空字符串时返回全部
     * @return 过滤后的歌曲列表
     */
    public static List<Song> filter(List<Song> source, String keyword) {
        List<Song> result = new ArrayList<>();
        if (source == null || source.isEmpty()) return result;
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        if (kw.isEmpty()) return new ArrayList<>(source);

        for (Song s : source) {
            String title = s.title == null ? "" : s.title.toLowerCase();
            String artist = s.artist == null ? "" : s.artist.toLowerCase();
            String album = s.album == null ? "" : s.album.toLowerCase();
            // 只要标题、歌手、专辑任一匹配即保留
            if (title.contains(kw) || artist.contains(kw) || album.contains(kw)) {
                result.add(s);
            }
        }
        return result;
    }

    /**
     * 根据歌曲的 mediaStoreId 构造 Content Uri，供 MediaPlayer 直接播放。
     *
     * @param mediaStoreId MediaStore 中的音频 ID
     * @return 可供 MediaPlayer.setDataSource() 使用的 content:// Uri
     */
    public static Uri uriOf(long mediaStoreId) {
        return ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId);
    }

    /**
     * 触发媒体扫描，将 Music 和 Downloads 目录下的新音频文件同步到 MediaStore。
     * 使用 MediaScannerConnection.scanFile() 批量扫描。
     */
    private void scanMediaFiles() {
        try {
            // 扫描标准音乐目录和下载目录
            File musicDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MUSIC);
            File downloadDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            List<File> targets = new ArrayList<>();
            if (musicDir != null && musicDir.exists()) {
                collectAudioFiles(musicDir, targets);
            }
            if (downloadDir != null && downloadDir.exists()) {
                collectAudioFiles(downloadDir, targets);
            }
            if (targets.isEmpty()) return;

            String[] paths = new String[targets.size()];
            String[] mimes = new String[targets.size()];
            for (int i = 0; i < targets.size(); i++) {
                paths[i] = targets.get(i).getAbsolutePath();
                // mime 传 null 让系统自动推断
                mimes[i] = null;
            }
            MediaScannerConnection.scanFile(appContext, paths, mimes, null);
        } catch (Exception ignore) {
        }
    }

    /**
     * 递归收集指定目录下的所有音频文件。
     *
     * @param dir 起始目录
     * @param out 收集结果列表
     */
    private void collectAudioFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectAudioFiles(f, out);
            } else if (isAudioFile(f.getName())) {
                out.add(f);
            }
        }
    }

    /**
     * 根据文件扩展名判断是否为常见音频文件。
     * 支持 mp3、flac、wav、ogg、m4a、aac、flc 格式。
     *
     * @param name 文件名
     * @return true 为音频文件
     */
    private boolean isAudioFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".flac")
                || lower.endsWith(".wav") || lower.endsWith(".ogg")
                || lower.endsWith(".m4a") || lower.endsWith(".aac")
                || lower.endsWith(".flc");
    }
}

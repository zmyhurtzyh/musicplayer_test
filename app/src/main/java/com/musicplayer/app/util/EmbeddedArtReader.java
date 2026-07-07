package com.musicplayer.app.util;

import android.content.ContentUris;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;

import com.musicplayer.app.data.Song;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * 嵌入式封面读取工具，从音频文件中提取专辑封面图片。
 * <p>
 * 采用两级回退策略：优先通过 MediaMetadataRetriever 读取音频文件内嵌封面（ID3/APIC 标签），
 * 若内嵌封面不存在，则回退到通过 albumId 从 MediaStore 的专辑封面 URI 获取。
 * 注意：此类执行阻塞式 IO 操作，必须在子线程中调用。
 * </p>
 */
final class EmbeddedArtReader {

    private EmbeddedArtReader() {
    }

    /**
     * 读取歌曲封面图片。先尝试内嵌封面，再回退到 MediaStore 专辑封面。
     *
     * @param context 上下文，用于访问 ContentResolver
     * @param song    歌曲对象，需包含 mediaStoreId 和 albumId
     * @return 封面 Bitmap，无法获取时返回 null
     */
    static Bitmap read(Context context, Song song) {
        if (song == null) return null;
        Bitmap bmp = readEmbedded(context, song);
        if (bmp != null) return bmp;
        return readAlbumArt(context, song);
    }

    /**
     * 通过 MediaMetadataRetriever 读取音频文件的内嵌封面（如 MP3 的 APIC 帧）。
     * 使用 content Uri 设置数据源，兼容存储权限受限的场景。
     *
     * @param context 上下文
     * @param song    歌曲对象
     * @return 内嵌封面 Bitmap，不存在或读取失败返回 null
     */
    private static Bitmap readEmbedded(Context context, Song song) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            // 使用 content Uri 设置数据源，比文件路径更安全
            mmr.setDataSource(context, song.getContentUri());
            byte[] art = mmr.getEmbeddedPicture();
            if (art != null && art.length > 0) {
                return BitmapFactory.decodeStream(new ByteArrayInputStream(art));
            }
        } catch (Exception ignore) {
        } finally {
            try {
                mmr.release();
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    /**
     * 通过 albumId 从 MediaStore 的专辑封面 URI 获取封面。
     * URI 格式为 content://media/external/audio/albumart/{albumId}。
     *
     * @param context 上下文
     * @param song    歌曲对象，需包含有效的 albumId
     * @return 专辑封面 Bitmap，不存在或读取失败返回 null
     */
    private static Bitmap readAlbumArt(Context context, Song song) {
        if (song.albumId <= 0) return null;
        try {
            Uri albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    song.albumId);
            InputStream is = context.getContentResolver().openInputStream(albumArtUri);
            if (is != null) {
                Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();
                return bmp;
            }
        } catch (Exception ignore) {
        }
        return null;
    }
}

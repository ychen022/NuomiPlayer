package com.example.myapplication.shared;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches time-synced lyrics for a NetEase Cloud Music track from the public
 * NetEase lyrics endpoint.
 *
 * <p>Reconstructed from NuomiPlayer 2.0 (decompiled {@code 糯米播放器2.0.apk},
 * class {@code com.nuomi.shared.LyricFetcher}). The network request runs on a
 * background executor; the result is delivered back on the main thread.
 *
 * <p>Note: unlike QQ Music (which exposes {@code ucar.media.metadata.LYRICS_WHOLE}
 * directly in its {@code MediaMetadata}), NetEase lyrics are <i>not</i> present in
 * the media session metadata and must be fetched here by song id. Because this is
 * asynchronous, the fetch must be kicked off as soon as a NetEase song is known —
 * see {@code MyMusicService.RemoteCallback.onMetadataChanged} and the bind path in
 * {@code tokenRx}.
 */
public final class LyricFetcher {

    private static final String TAG = "LyricFetcher";
    private static final String API_URL = "https://music.163.com/api/song/lyric?id=%s&lv=1";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        /** @param mediaId the id the lyrics were requested for; {@code lyrics} may be null. */
        void onLyricResult(String mediaId, String lyrics);
    }

    private LyricFetcher() {
    }

    public static void fetch(final String mediaId, final Callback cb) {
        if (mediaId == null || mediaId.isEmpty()) {
            Log.w(TAG, "mediaId 为空，跳过获取");
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    cb.onLyricResult(mediaId, null);
                }
            });
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final String lyrics = fetchSync(mediaId);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onLyricResult(mediaId, lyrics);
                    }
                });
            }
        });
    }

    private static String fetchSync(String mediaId) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(String.format(API_URL, mediaId));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "NuomiPlayer/1.0");

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "HTTP " + code + " for mediaId=" + mediaId);
                return null;
            }

            StringBuilder sb = new StringBuilder();
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            if (json.has("lrc")) {
                String lyric = json.getJSONObject("lrc").optString("lyric", null);
                if (lyric != null && !lyric.isEmpty()) {
                    Log.i(TAG, "获取歌词成功 mediaId=" + mediaId + " len=" + lyric.length());
                    return lyric;
                }
            }
            Log.i(TAG, "API 返回无歌词 mediaId=" + mediaId);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "获取歌词失败 mediaId=" + mediaId, e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}

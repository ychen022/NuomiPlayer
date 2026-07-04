package com.example.myapplication.shared;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * NetEase Cloud Music lyric fetcher that returns both the original lyric and its
 * translation (when available), using NetEase's "weapi" encrypted endpoint.
 *
 * <p>Ported into this fork from upstream PR #13
 * (charlottejas/NuomiPlayer#13, by 1231joe1231). The AES/RSA request-signing
 * scheme mirrors NetEase's web client (reference:
 * https://github.com/jitwxs/163MusicLyrics). Only the numeric song id is sent
 * (encrypted in {@code params}); the sole host contacted is music.163.com.
 */
public class NcmLyricsHelper {
    private static final String TAG = "NcmLyricsHelper";
    private static final String API_URL = "https://music.163.com/weapi/song/lyric";

    // NetEase weapi encryption constants.
    private static final String NONCE = "0CoJUm6Qyw8W8jud";
    private static final String PUBKEY = "010001";
    private static final String MODULUS = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";
    private static final String VI = "0102030405060708";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface LyricsCallback {
        void onSuccess(String lyrics, String translation);

        void onError(String error);
    }

    private NcmLyricsHelper() {
    }

    /**
     * Asynchronously fetch lyrics. The callback is delivered on the main thread.
     *
     * @param mediaId the NetEase Cloud Music song id
     */
    public static void fetchLyrics(final String mediaId, final LyricsCallback callback) {
        executor.execute(() -> {
            try {
                String[] result = fetchLyricsSync(mediaId);
                final String lyrics = result[0];
                final String translation = result[1];
                mainHandler.post(() -> callback.onSuccess(lyrics, translation));
            } catch (Exception e) {
                Log.e(TAG, "获取歌词失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    /**
     * Synchronous fetch (background thread only).
     *
     * @return {@code String[0]} = original lyric, {@code String[1]} = translation (may be null)
     */
    private static String[] fetchLyricsSync(String mediaId) throws Exception {
        JSONObject rawData = new JSONObject();
        rawData.put("id", mediaId);
        rawData.put("os", "pc");
        rawData.put("lv", "-1");
        rawData.put("kv", "0");
        rawData.put("tv", "-1");
        rawData.put("rv", "0");
        rawData.put("yv", "0");
        rawData.put("ytv", "0");
        rawData.put("yrv", "0");
        rawData.put("csrf_token", "");

        String secretKey = createSecretKey(16);
        String params = aesEncrypt(aesEncrypt(rawData.toString(), NONCE), secretKey);
        String encSecKey = rsaEncrypt(secretKey);

        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setRequestProperty("Referer", "https://music.163.com");

        String postData = "params=" + urlEncode(params) + "&encSecKey=" + urlEncode(encSecKey);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(postData.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP Error: " + responseCode);
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
        }
        return parseLyricsResponse(response.toString());
    }

    private static String createSecretKey(int length) {
        String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        SecureRandom random = new SecureRandom();
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append(chars.charAt(random.nextInt(chars.length())));
        }
        return result.toString();
    }

    private static String aesEncrypt(String data, String key) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
        IvParameterSpec iv = new IvParameterSpec(VI.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, iv);
        byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private static String rsaEncrypt(String text) {
        String reversedText = new StringBuilder(text).reverse().toString();
        StringBuilder hexString = new StringBuilder();
        for (byte b : reversedText.getBytes(StandardCharsets.UTF_8)) {
            hexString.append(String.format("%02x", b));
        }
        BigInteger bigIntText = new BigInteger(hexString.toString(), 16);
        BigInteger bigIntPubKey = new BigInteger(PUBKEY, 16);
        BigInteger bigIntModulus = new BigInteger(MODULUS, 16);
        BigInteger encrypted = bigIntText.modPow(bigIntPubKey, bigIntModulus);
        String result = encrypted.toString(16);
        while (result.length() < 256) {
            result = "0" + result;
        }
        return result;
    }

    private static String urlEncode(String str) {
        try {
            return java.net.URLEncoder.encode(str, "UTF-8");
        } catch (Exception e) {
            return str;
        }
    }

    /**
     * @return {@code String[0]} = original lyric, {@code String[1]} = translation (may be null)
     */
    private static String[] parseLyricsResponse(String jsonResponse) throws Exception {
        JSONObject json = new JSONObject(jsonResponse);

        if (json.optInt("code") != 200) {
            throw new Exception("API返回错误: " + json.optString("msg"));
        }

        // 获取原文歌词
        String lyrics = null;
        JSONObject lrc = json.optJSONObject("lrc");
        if (lrc != null) {
            lyrics = lrc.optString("lyric", "");
            if (lyrics.isEmpty() || lyrics.equals("null")) {
                lyrics = null;
            }
        }

        // 获取翻译歌词
        String translation = null;
        JSONObject tlyric = json.optJSONObject("tlyric");
        if (tlyric != null) {
            translation = tlyric.optString("lyric", "");
            if (translation.isEmpty() || translation.equals("null")) {
                translation = null;
            }
        }

        if (lyrics == null) {
            throw new Exception("歌词数据为空");
        }

        return new String[]{lyrics, translation};
    }
}

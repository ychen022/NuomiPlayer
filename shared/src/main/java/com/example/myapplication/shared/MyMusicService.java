package com.example.myapplication.shared;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.media.MediaBrowserServiceCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirrors the media session of a phone playback app (QQ Music / NetEase Cloud
 * Music / …) into a local {@link MediaBrowserServiceCompat} that Android Auto
 * connects to, optionally overlaying time-synced lyrics as the "now playing"
 * title/subtitle.
 *
 * <p>Reconstructed from NuomiPlayer 2.0 (decompiled {@code 糯米播放器2.0.apk},
 * class {@code com.nuomi.shared.MyMusicService}). Behaviour is preserved
 * verbatim except for the clearly-marked {@code [FIX]} blocks that resolve the
 * "lyrics don't show until I toggle lyrics off/on and skip to the next song"
 * bug on startup. The inter-process action/extra string contract
 * ({@code com.nuomi.ACTION_CONTROLLER}, {@code pkg}/{@code binder} extras, the
 * {@code session_pref}/{@code last_pkg} preference, …) is kept identical to the
 * shipped 2.0 app so the reconstructed service stays wire-compatible with the
 * companion {@code MusicSessionSniffer}.
 */
public class MyMusicService extends MediaBrowserServiceCompat {

    private static final String TAG = "Mirror";

    private static final String ACTION_CONTROLLER = "com.nuomi.ACTION_CONTROLLER";
    private static final String ACTION_TOGGLE_LYRICS_MODE = "com.nuomi.ACTION_TOGGLE_LYRICS_MODE";
    private static final String CUSTOM_ACTION_SHOW_LYRICS = "com.nuomi.SHOW_LYRICS";
    private static final String CUSTOM_ACTION_REPEAT_MODE = "com.nuomi.REPEAT_MODE";
    private static final String CHANNEL_ID = "nuomi_launch";

    private static final String PKG_QQ = "com.tencent.qqmusic";
    private static final String PKG_NCM = "com.netease.cloudmusic";

    private static final String KEY_PLAY_MODE = "ucar.media.metadata.PLAY_MODE";
    private static final String KEY_LYRICS_WHOLE = "ucar.media.metadata.LYRICS_WHOLE";

    // PLAY | PAUSE | PLAY_PAUSE | SKIP_TO_NEXT | SKIP_TO_PREVIOUS | SEEK_TO  == 822
    private static final long MIRROR_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY
                    | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackStateCompat.ACTION_SEEK_TO;

    private MediaSessionCompat mSession;

    private MediaControllerCompat remoteCtrl;
    private final MediaControllerCompat.Callback remoteCb = new RemoteCallback();

    private MediaMetadataCompat lastRemoteMeta;
    private PlaybackStateCompat lastRemoteState;

    private boolean isLyricsMode = false;
    private boolean isNcmMode;      // current chosen source is NOT QQ Music
    private boolean isNcmSource;    // current chosen source IS NetEase Cloud Music

    private int lastPlayMode = 0;

    // Lyrics state.
    private final List<Pair<Long, String>> parsedLyrics = new ArrayList<>();
    private String lastLyricsRaw;
    private String lastNcmMediaId;
    private String ncmFetchedLyrics;

    // Simultaneous translated-lyric display (ported from upstream PR #13).
    private String ncmFetchedTranslation;
    private String lastTranslationRaw;
    private final List<Pair<Long, String>> parsedTranslation = new ArrayList<>();
    private boolean hasTranslation = false;

    // Local playback clock used to advance lyrics smoothly between remote updates.
    private long basePosMs;
    private float baseSpeed;
    private int baseState;
    private long baseUpdateElapsed;
    private long durationMs;

    private boolean suppressRemoteState;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable clearSuppression = new Runnable() {
        @Override
        public void run() {
            suppressRemoteState = false;
        }
    };

    /**
     * Ticks once per second while lyrics mode is on, re-rendering the current
     * lyric line from the local playback clock.
     */
    private final Runnable lyricsUpdater = new Runnable() {
        @Override
        public void run() {
            // [FIX] Reschedule as long as lyrics mode is active, independent of
            // whether a remote controller is currently bound. The shipped 2.0
            // build only re-posted itself inside the `remoteCtrl != null` guard,
            // so when onCreate() posted this runnable before the controller had
            // bound, it ran once, saw a null controller and never rescheduled —
            // leaving the lyric line frozen until the user toggled lyrics off/on.
            if (lyricsSupported() && isLyricsMode) {
                if (remoteCtrl != null && baseState == PlaybackStateCompat.STATE_PLAYING) {
                    applyLyricsOverlay(lastRemoteMeta);
                }
                handler.postDelayed(this, 1000);
            }
        }
    };

    /** Toggles lyrics mode on when the phone UI broadcasts a request. */
    private final BroadcastReceiver autoLyricsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "received auto-lyrics request");
            if (!lyricsSupported()) {
                Log.i(TAG, "current source does not support lyrics, ignoring");
                return;
            }
            if (!isLyricsMode) {
                isLyricsMode = true;
                Log.i(TAG, "lyrics mode enabled");
                if (lastRemoteState != null) {
                    basePosMs = lastRemoteState.getPosition();
                    baseSpeed = lastRemoteState.getPlaybackSpeed();
                    baseState = lastRemoteState.getState();
                    baseUpdateElapsed = SystemClock.elapsedRealtime();
                }
                handler.post(lyricsUpdater);
                if (remoteCtrl != null) {
                    applyLyricsOverlay(lastRemoteMeta);
                    mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
                }
            }
        }
    };

    /** Receives the selected source app's session token from MusicSessionSniffer. */
    private final BroadcastReceiver tokenRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_CONTROLLER.equals(intent.getAction())) {
                return;
            }
            String pkg = intent.getStringExtra("pkg");
            if (pkg == null) {
                Log.w(TAG, "received controller broadcast without pkg");
                return;
            }

            String chosen = getSharedPreferences("session_pref", 0).getString("last_pkg", null);
            if (chosen == null || !chosen.equals(pkg)) {
                Log.i(TAG, "ignoring broadcast from=" + pkg + ", chosen=" + chosen);
                return;
            }

            boolean newNcmMode = !PKG_QQ.equals(pkg);
            boolean newNcmSource = PKG_NCM.equals(pkg);
            if (newNcmMode != isNcmMode || newNcmSource != isNcmSource) {
                isNcmMode = newNcmMode;
                isNcmSource = newNcmSource;
                ncmFetchedLyrics = null;
                lastNcmMediaId = null;
                lastLyricsRaw = null;
                parsedLyrics.clear();
                ncmFetchedTranslation = null;
                lastTranslationRaw = null;
                parsedTranslation.clear();
                hasTranslation = false;

                if (!isNcmMode || isNcmSource) {
                    Log.i(TAG, isNcmSource
                            ? "switched to NCM mode (API lyrics available)"
                            : "switched to QQ mode (metadata lyrics available)");
                } else {
                    Log.i(TAG, "switched to unsupported source=" + pkg + ", disabling lyrics");
                    if (isLyricsMode) {
                        isLyricsMode = false;
                        handler.removeCallbacks(lyricsUpdater);
                        suppressRemoteState = false;
                    }
                }
            }

            updateSessionActive("sourceChanged:" + pkg);

            MediaSessionCompat.Token token = intent.getParcelableExtra("binder");
            if (token == null) {
                Log.w(TAG, "broadcast missing binder Token");
                return;
            }
            try {
                if (remoteCtrl != null) {
                    remoteCtrl.unregisterCallback(remoteCb);
                }
            } catch (Exception e) {
                Log.e(TAG, "failed to bind controller", e);
                return;
            }
            remoteCtrl = new MediaControllerCompat(MyMusicService.this, token);
            remoteCtrl.registerCallback(remoteCb);
            Log.i(TAG, "bound remote controller pkg=" + pkg);

            // [FIX] Route the initial snapshot through the remote callback instead
            // of calling mirror(...) directly. MediaControllerCompat.registerCallback
            // does NOT replay the current metadata, and the old direct-mirror path
            // never invoked onMetadataChanged — so for NetEase it never kicked off
            // the asynchronous NetEase lyric fetch for the *currently* playing
            // song, and never populated lastRemoteMeta/lastRemoteState. As a result
            // ncmFetchedLyrics stayed null and lyrics rendered blank until the user
            // manually skipped to the next track (the first real metadata change).
            // Feeding the snapshot through the callback fixes both: it triggers the
            // fetch for the current song and records lastRemote* state.
            remoteCb.onMetadataChanged(remoteCtrl.getMetadata());
            remoteCb.onPlaybackStateChanged(remoteCtrl.getPlaybackState());
            // [FIX] Make sure the per-second lyric ticker is actually running now
            // that a controller is bound (it may have died at onCreate time).
            if (isLyricsMode) {
                handler.removeCallbacks(lyricsUpdater);
                handler.post(lyricsUpdater);
            }

            updateSessionActive("autoLyricsOnStartup");
            notifyChildrenChanged("__default__");
            notifyChildrenChanged("__active__");
        }
    };

    // =====================================================================
    // Lyrics helpers
    // =====================================================================

    private boolean lyricsSupported() {
        // NetEase is supported (fetched via API); QQ is supported (in metadata);
        // any other "ncm mode" source that is not actually NetEase is unsupported.
        return !(isNcmMode && !isNcmSource);
    }

    private long clockPosition() {
        if (baseState != PlaybackStateCompat.STATE_PLAYING) {
            return basePosMs;
        }
        long pos = basePosMs
                + (long) ((SystemClock.elapsedRealtime() - baseUpdateElapsed) * baseSpeed);
        if (durationMs > 0) {
            pos = Math.min(pos, durationMs);
        }
        return Math.max(0, pos);
    }

    private void parseLyrics(String raw) {
        parsedLyrics.clear();
        Pattern p = Pattern.compile("\\[(\\d{1,2}):(\\d{2}(?:\\.\\d{2,3})?)\\](.*)");
        for (String line : raw.split("\n")) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                long t = (long) ((Integer.parseInt(m.group(1)) * 60
                        + Float.parseFloat(m.group(2))) * 1000f);
                String text = m.group(3).trim();
                if (!text.isEmpty()) {
                    parsedLyrics.add(new Pair<>(t, text));
                }
            }
        }
        Log.i(TAG, "parsed " + parsedLyrics.size() + " lyric lines");
    }

    private void parseTranslation(String raw) {
        parsedTranslation.clear();
        Pattern p = Pattern.compile("\\[(\\d{1,2}):(\\d{2}(?:\\.\\d{2,3})?)\\](.*)");
        for (String line : raw.split("\n")) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                long t = (long) ((Integer.parseInt(m.group(1)) * 60
                        + Float.parseFloat(m.group(2))) * 1000f);
                String text = m.group(3).trim();
                if (!text.isEmpty()) {
                    parsedTranslation.add(new Pair<>(t, text));
                }
            }
        }
    }

    /** Returns the translated line active at {@code pos} (ms), or null. */
    private String getTranslationAt(long pos) {
        if (parsedTranslation.isEmpty()) {
            return null;
        }
        int lo = 0;
        int hi = parsedTranslation.size() - 1;
        int idx = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >> 1;
            if (parsedTranslation.get(mid).first <= pos) {
                idx = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return idx >= 0 ? parsedTranslation.get(idx).second : null;
    }

    /**
     * Heuristic used to decide whether to show a translation alongside a line:
     * returns true for Chinese/English lines (translation not needed), and false
     * when the line contains Japanese kana (so its translation should be shown).
     */
    private boolean isChineseOrEnglish(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        for (char c : text.toCharArray()) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA) {
                return false;
            }
        }
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                return true;
            }
        }
        int ascii = 0;
        for (char c : text.toCharArray()) {
            if (c >= 32 && c <= 126) {
                ascii++;
            }
        }
        return ascii > text.length() * 0.8;
    }

    private void addLyricsButton(PlaybackStateCompat.Builder builder) {
        int icon = isLyricsMode ? R.drawable.ic_lyrics_24dp : R.drawable.ic_lyrics_outline_24dp;
        builder.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                CUSTOM_ACTION_SHOW_LYRICS, "歌词", icon).build());
    }

    private void addRepeatButton(PlaybackStateCompat.Builder builder, MediaMetadataCompat meta) {
        if (meta != null) {
            long mode = meta.getLong(KEY_PLAY_MODE);
            if (mode != 0 || !isNcmMode) {
                lastPlayMode = (int) mode;
            }
        }
        int icon;
        if (lastPlayMode == 0) {
            icon = R.drawable.ic_shuffle_24dp;
        } else if (lastPlayMode == 1) {
            icon = R.drawable.ic_repeat_one_24dp;
        } else {
            icon = R.drawable.ic_repeat_24dp;
        }
        builder.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                CUSTOM_ACTION_REPEAT_MODE, "循环", icon).build());
    }

    private void applyLyricsOverlay(MediaMetadataCompat meta) {
        if (!lyricsSupported() || !isLyricsMode || meta == null) {
            return;
        }

        long mode = meta.getLong(KEY_PLAY_MODE);
        if (mode != 0 || !isNcmMode) {
            lastPlayMode = (int) mode;
        }
        long dur = meta.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
        if (dur > 0) {
            durationMs = dur;
        }

        String raw;
        if (isNcmMode) {
            raw = isNcmSource ? ncmFetchedLyrics : null;
        } else {
            raw = meta.getString(KEY_LYRICS_WHOLE);
        }
        if (raw != null && !raw.equals(lastLyricsRaw)) {
            lastLyricsRaw = raw;
            parseLyrics(raw);
        }

        long pos = clockPosition();
        String currentLine = "";
        String nextLine = "";
        if (!parsedLyrics.isEmpty()) {
            int lo = 0;
            int hi = parsedLyrics.size() - 1;
            int idx = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >> 1;
                if (parsedLyrics.get(mid).first > pos) {
                    hi = mid - 1;
                } else {
                    lo = mid + 1;
                    idx = mid;
                }
            }
            currentLine = idx < 0 ? "" : parsedLyrics.get(idx).second;
            if (idx >= 0) {
                String originalLine = parsedLyrics.get(idx).second;
                // Simultaneous translation: for lines that aren't Chinese/English
                // (e.g. Japanese), show the translation on top and the original
                // underneath. Otherwise fall back to current-line + next-line.
                if (hasTranslation && !isChineseOrEnglish(originalLine)) {
                    String translatedLine = getTranslationAt(pos);
                    currentLine = translatedLine != null ? translatedLine : originalLine;
                    nextLine = originalLine;
                } else {
                    currentLine = originalLine;
                    int next = idx + 1;
                    if (next < parsedLyrics.size()) {
                        nextLine = parsedLyrics.get(next).second;
                    }
                }
            }
        }

        MediaMetadataCompat.Builder mb = new MediaMetadataCompat.Builder();
        mb.putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentLine);
        mb.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, nextLine);
        Bitmap art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
        if (art == null && isNcmSource) {
            art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON);
            if (art == null) {
                art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ART);
            }
        }
        if (art != null) {
            mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
        }
        if (durationMs > 0) {
            mb.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
        }
        mSession.setMetadata(mb.build());

        int state = baseState;
        if (state == PlaybackStateCompat.STATE_NONE || state == PlaybackStateCompat.STATE_STOPPED) {
            state = PlaybackStateCompat.STATE_PAUSED;
        }
        float speed = baseSpeed == 0 ? 1.0f : baseSpeed;
        PlaybackStateCompat.Builder pb = new PlaybackStateCompat.Builder()
                .setState(state, clockPosition(), speed)
                .setActions(MIRROR_ACTIONS);
        addLyricsButton(pb);
        addRepeatButton(pb, lastRemoteMeta);
        mSession.setPlaybackState(pb.build());
    }

    // =====================================================================
    // Mirroring
    // =====================================================================

    private void mirror(MediaMetadataCompat meta, PlaybackStateCompat state) {
        if (meta != null) {
            if (!lyricsSupported() || !isLyricsMode) {
                MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder();
                b.putString(MediaMetadataCompat.METADATA_KEY_TITLE,
                        meta.getString(MediaMetadataCompat.METADATA_KEY_TITLE));
                b.putString(MediaMetadataCompat.METADATA_KEY_ARTIST,
                        meta.getString(MediaMetadataCompat.METADATA_KEY_ARTIST));
                long dur = meta.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
                long mode = meta.getLong(KEY_PLAY_MODE);
                if (mode != 0 || !isNcmMode) {
                    lastPlayMode = (int) mode;
                }
                if (dur > 0) {
                    b.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur);
                }
                Bitmap art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
                if (isNcmMode) {
                    if (art == null) {
                        art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON);
                    }
                    if (art == null) {
                        art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ART);
                    }
                }
                if (art != null) {
                    b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
                }
                mSession.setMetadata(b.build());
            } else {
                applyLyricsOverlay(meta);
            }
        }

        if (state != null) {
            if (!lyricsSupported() || !isLyricsMode) {
                int st = state.getState();
                // Ignore NONE/STOPPED to keep the UI stable.
                if (st != PlaybackStateCompat.STATE_NONE
                        && st != PlaybackStateCompat.STATE_STOPPED) {
                    PlaybackStateCompat.Builder b = new PlaybackStateCompat.Builder()
                            .setState(st, state.getPosition(), state.getPlaybackSpeed())
                            .setActions(MIRROR_ACTIONS);
                    if (lyricsSupported()) {
                        addLyricsButton(b);
                        addRepeatButton(b, meta);
                    }
                    mSession.setPlaybackState(b.build());
                }
            } else {
                if (!suppressRemoteState) {
                    basePosMs = state.getPosition();
                    baseSpeed = state.getPlaybackSpeed();
                    baseState = state.getState();
                    baseUpdateElapsed = SystemClock.elapsedRealtime();
                }
                int st = state.getState();
                if (st == PlaybackStateCompat.STATE_NONE
                        || st == PlaybackStateCompat.STATE_STOPPED) {
                    st = PlaybackStateCompat.STATE_PAUSED;
                }
                float speed = baseSpeed == 0 ? 1.0f : baseSpeed;
                PlaybackStateCompat.Builder b = new PlaybackStateCompat.Builder()
                        .setState(st, clockPosition(), speed)
                        .setActions(MIRROR_ACTIONS);
                addLyricsButton(b);
                addRepeatButton(b, lastRemoteMeta);
                mSession.setPlaybackState(b.build());
            }
        }
    }

    private void updateSessionActive(String reason) {
        boolean want = lyricsSupported() && isLyricsMode;
        if (mSession.isActive() != want) {
            mSession.setActive(want);
            Log.i(TAG, "setActive=" + want + " reason=" + reason);
        }
    }

    private PlaybackStateCompat buildMinimalState(int state, long pos, float speed) {
        return new PlaybackStateCompat.Builder()
                .setState(state, pos, speed, SystemClock.elapsedRealtime())
                .setActions(MIRROR_ACTIONS)
                .build();
    }

    // =====================================================================
    // Remote (source app) callbacks
    // =====================================================================

    private class RemoteCallback extends MediaControllerCompat.Callback {
        @Override
        public void onMetadataChanged(MediaMetadataCompat meta) {
            lastRemoteMeta = meta;

            if (isNcmSource && meta != null) {
                String mediaId = meta.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
                if (mediaId != null && !mediaId.equals(lastNcmMediaId)) {
                    lastNcmMediaId = mediaId;
                    ncmFetchedLyrics = null;
                    lastLyricsRaw = null;
                    parsedLyrics.clear();
                    ncmFetchedTranslation = null;
                    lastTranslationRaw = null;
                    parsedTranslation.clear();
                    hasTranslation = false;
                    Log.i(TAG, "NCM song changed, fetching lyrics for mediaId=" + mediaId);
                    NcmLyricsHelper.fetchLyrics(mediaId, new NcmLyricsHelper.LyricsCallback() {
                        @Override
                        public void onSuccess(String lyrics, String translation) {
                            // Ignore stale results if the song changed again mid-fetch.
                            if (!mediaId.equals(lastNcmMediaId)) {
                                return;
                            }
                            ncmFetchedLyrics = lyrics;
                            ncmFetchedTranslation = translation;
                            // Parse the translation eagerly so applyLyricsOverlay can
                            // pair each original line with its translation.
                            parsedTranslation.clear();
                            hasTranslation = false;
                            if (translation != null && !translation.isEmpty()) {
                                lastTranslationRaw = translation;
                                parseTranslation(translation);
                                hasTranslation = !parsedTranslation.isEmpty();
                            }
                            Log.i(TAG, "NCM lyrics fetched: "
                                    + (lyrics == null ? "null" : "len=" + lyrics.length())
                                    + (hasTranslation
                                            ? " (+translation " + parsedTranslation.size() + ")"
                                            : ""));
                            if (isLyricsMode && lyrics != null) {
                                applyLyricsOverlay(lastRemoteMeta);
                            }
                        }

                        @Override
                        public void onError(String error) {
                            Log.w(TAG, "NCM lyric fetch failed: " + error);
                        }
                    });
                }
            }

            mirror(meta, null);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            lastRemoteState = state;
            mirror(null, state);
        }
    }

    // =====================================================================
    // Local (Android Auto) session callbacks
    // =====================================================================

    private class SessionCallback extends MediaSessionCompat.Callback {
        @Override
        public void onPlay() {
            if (remoteCtrl != null) {
                remoteCtrl.getTransportControls().play();
            }
        }

        @Override
        public void onPause() {
            if (remoteCtrl != null) {
                remoteCtrl.getTransportControls().pause();
            }
        }

        @Override
        public void onSkipToNext() {
            if (remoteCtrl != null) {
                remoteCtrl.getTransportControls().skipToNext();
            }
        }

        @Override
        public void onSkipToPrevious() {
            if (remoteCtrl != null) {
                remoteCtrl.getTransportControls().skipToPrevious();
            }
        }

        @Override
        public void onSeekTo(long pos) {
            if (remoteCtrl != null) {
                remoteCtrl.getTransportControls().seekTo(pos);
            }
            if (!lyricsSupported() || !isLyricsMode) {
                PlaybackStateCompat st = remoteCtrl == null ? null : remoteCtrl.getPlaybackState();
                if (st != null) {
                    mSession.setPlaybackState(st);
                }
                updateSessionActive("seekTo");
                return;
            }
            // In lyrics mode, immediately re-anchor the local clock to the sought
            // position and briefly suppress remote state so the seek doesn't jump.
            suppressRemoteState = true;
            handler.removeCallbacks(clearSuppression);
            handler.postDelayed(clearSuppression, 1200);

            long now = SystemClock.elapsedRealtime();
            PlaybackStateCompat remote = lastRemoteState;
            baseState = remote == null ? PlaybackStateCompat.STATE_PLAYING : remote.getState();
            baseSpeed = remote == null ? 1.0f : remote.getPlaybackSpeed();
            basePosMs = pos;
            baseUpdateElapsed = now;
            applyLyricsOverlay(lastRemoteMeta);
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            if (mediaId == null) {
                return;
            }
            Log.i(TAG, "onPlayFromMediaId: " + mediaId);
            String label;
            try {
                label = getPackageManager()
                        .getApplicationLabel(getPackageManager().getApplicationInfo(mediaId, 0))
                        .toString();
            } catch (Exception e) {
                label = mediaId;
            }
            getSharedPreferences("session_pref", 0).edit()
                    .putString("last_pkg", mediaId)
                    .putString("last_label", label)
                    .apply();

            LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(MyMusicService.this);
            lbm.sendBroadcast(new Intent("com.nuomi.ACTION_SELECTION_CHANGED")
                    .putExtra("pkg", mediaId).putExtra("label", label));
            lbm.sendBroadcast(new Intent("com.nuomi.REQUEST_TOKEN"));

            // If the chosen app isn't already playing, try to launch it; the
            // token broadcast that follows will bind us to its session.
            boolean active = false;
            try {
                for (SessionInfo info : SessionRepo.loadActiveSessions(MyMusicService.this)) {
                    if (mediaId.equals(info.packageName)) {
                        active = true;
                        break;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load active sessions", e);
            }
            if (!active) {
                launchOrNotify(mediaId, label);
            }

            // Show a "selected" placeholder until the real session pushes metadata.
            mSession.setMetadata(new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "已选择: " + label)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "请在手机上播放音乐")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 0)
                    .build());
            mSession.setPlaybackState(buildMinimalState(PlaybackStateCompat.STATE_PAUSED, 0, 0));
            updateSessionActive("playFromMediaId");
            notifyChildrenChanged("root");
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            if (!lyricsSupported()) {
                return;
            }
            if (CUSTOM_ACTION_SHOW_LYRICS.equals(action)) {
                isLyricsMode = !isLyricsMode;
                if (!isLyricsMode) {
                    handler.removeCallbacks(lyricsUpdater);
                    suppressRemoteState = false;
                } else {
                    if (lastRemoteState != null) {
                        basePosMs = lastRemoteState.getPosition();
                        baseSpeed = lastRemoteState.getPlaybackSpeed();
                        baseState = lastRemoteState.getState();
                        baseUpdateElapsed = SystemClock.elapsedRealtime();
                    }
                    handler.post(lyricsUpdater);
                    applyLyricsOverlay(lastRemoteMeta);
                }
                if (remoteCtrl != null) {
                    MediaMetadataCompat meta = remoteCtrl.getMetadata();
                    if (meta == null) {
                        meta = lastRemoteMeta;
                    }
                    mirror(meta, remoteCtrl.getPlaybackState());
                }
                updateSessionActive("toggleLyrics=" + isLyricsMode);
            } else if (CUSTOM_ACTION_REPEAT_MODE.equals(action)) {
                if (isNcmMode) {
                    if (isNcmSource && remoteCtrl != null) {
                        remoteCtrl.getTransportControls()
                                .sendCustomAction("ucar.media.action.PLAY_MODE", null);
                    }
                } else {
                    Intent i = new Intent(
                            "com.tencent.qqmusic.ACTION_SERVICE_PLAY_MODE_WIDGET.QQMusicPhone");
                    i.setPackage(PKG_QQ);
                    sendBroadcast(i);
                }
                // Re-mirror shortly after so the (changed) play mode icon refreshes.
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (remoteCtrl != null) {
                            mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
                        }
                    }
                }, 500);
            }
        }
    }

    // =====================================================================
    // Source app launching
    // =====================================================================

    private void launchOrNotify(String pkg, String label) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Log.w(TAG, "No launch intent for " + pkg);
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(launch);
            Log.i(TAG, "Launched " + pkg + " directly");
        } catch (Exception e) {
            Log.i(TAG, "Direct launch blocked, sending notification for " + pkg);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "启动播放器", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("点击打开音乐 App");
            nm.createNotificationChannel(channel);
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("点击打开 " + label)
                    .setContentText("在手机上播放音乐后自动连接")
                    .setContentIntent(PendingIntent.getActivity(this, pkg.hashCode(), launch,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build();
            nm.notify(1001, n);
        }
    }

    private Bitmap getAppIcon(String pkg) {
        try {
            return drawableToBitmap(getPackageManager().getApplicationIcon(pkg));
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Override
    public void onCreate() {
        super.onCreate();

        mSession = new MediaSessionCompat(this, "MirrorSession");
        mSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS);
        setSessionToken(mSession.getSessionToken());
        mSession.setPlaybackState(buildMinimalState(PlaybackStateCompat.STATE_NONE, 0, 0));
        updateSessionActive("onCreate");
        mSession.setCallback(new SessionCallback());

        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(tokenRx, new IntentFilter(ACTION_CONTROLLER));
        lbm.registerReceiver(autoLyricsReceiver, new IntentFilter(ACTION_TOGGLE_LYRICS_MODE));

        boolean autoLyrics = getSharedPreferences("settings", 0).getBoolean("autoLyrics", false);
        Log.i(TAG, "autoLyrics=" + autoLyrics);
        if (autoLyrics && lyricsSupported() && !isLyricsMode) {
            isLyricsMode = true;
            if (lastRemoteState != null) {
                basePosMs = lastRemoteState.getPosition();
                baseSpeed = lastRemoteState.getPlaybackSpeed();
                baseState = lastRemoteState.getState();
                baseUpdateElapsed = SystemClock.elapsedRealtime();
            }
            handler.post(lyricsUpdater);
            if (remoteCtrl != null) {
                applyLyricsOverlay(lastRemoteMeta);
                mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
            }
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(lyricsUpdater);
        if (remoteCtrl != null) {
            remoteCtrl.unregisterCallback(remoteCb);
        }
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.unregisterReceiver(tokenRx);
        lbm.unregisterReceiver(autoLyricsReceiver);
        mSession.release();
        super.onDestroy();
    }

    // =====================================================================
    // MediaBrowser tree (Android Auto)
    // =====================================================================

    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot("root", null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId,
                               @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();

        if ("root".equals(parentId)) {
            items.add(new MediaBrowserCompat.MediaItem(
                    new MediaDescriptionCompat.Builder()
                            .setMediaId("__default__").setTitle("我的偏好").build(),
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            items.add(new MediaBrowserCompat.MediaItem(
                    new MediaDescriptionCompat.Builder()
                            .setMediaId("__active__").setTitle("更多").build(),
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
        } else if ("__default__".equals(parentId)) {
            Set<String> favorites = getSharedPreferences("favorites", 0)
                    .getStringSet("favorite_apps", new HashSet<String>());

            Map<String, String> nowPlaying = new HashMap<>();
            try {
                for (SessionInfo info : SessionRepo.loadActiveSessions(this)) {
                    if (info.nowPlaying != null) {
                        nowPlaying.put(info.packageName, info.nowPlaying);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load active sessions", e);
            }

            if (favorites.isEmpty()) {
                favorites = new HashSet<>();
                favorites.add("com.tencent.qqmusic|QQ音乐");
                favorites.add("com.netease.cloudmusic|网易云音乐");
            }

            for (String entry : favorites) {
                String[] parts = entry.split("\\|", 2);
                if (parts.length < 2) {
                    continue;
                }
                String pkg = parts[0];
                String label = parts[1];
                String subtitle = nowPlaying.containsKey(pkg)
                        ? "正在播放: " + nowPlaying.get(pkg)
                        : "点击选择";
                MediaDescriptionCompat.Builder desc = new MediaDescriptionCompat.Builder()
                        .setMediaId(pkg).setTitle(label).setSubtitle(subtitle);
                Bitmap icon = getAppIcon(pkg);
                if (icon != null) {
                    desc.setIconBitmap(icon);
                }
                items.add(new MediaBrowserCompat.MediaItem(
                        desc.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
            }
        } else if ("__active__".equals(parentId)) {
            try {
                for (SessionInfo info : SessionRepo.loadActiveSessions(this)) {
                    String subtitle = info.nowPlaying == null
                            ? info.packageName
                            : "正在播放: " + info.nowPlaying;
                    MediaDescriptionCompat.Builder desc = new MediaDescriptionCompat.Builder()
                            .setMediaId(info.packageName)
                            .setTitle(info.appLabel)
                            .setSubtitle(subtitle);
                    Bitmap icon = getAppIcon(info.packageName);
                    if (icon != null) {
                        desc.setIconBitmap(icon);
                    }
                    items.add(new MediaBrowserCompat.MediaItem(
                            desc.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load active sessions", e);
            }
        }

        result.sendResult(items);
    }
}

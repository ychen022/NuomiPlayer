package com.example.myapplication.shared;

import android.graphics.drawable.Drawable;

/**
 * Lightweight description of an active media session discovered on the device.
 *
 * <p>Reconstructed from NuomiPlayer 2.0 (decompiled {@code 糯米播放器2.0.apk},
 * class {@code com.nuomi.shared.SessionInfo}). Behaviour is preserved verbatim.
 */
public class SessionInfo {
    public final String packageName;
    public final String appLabel;
    public final Drawable appIcon;
    public final String nowPlaying;

    public SessionInfo(String packageName, String appLabel, Drawable appIcon, String nowPlaying) {
        this.packageName = packageName;
        this.appLabel = appLabel;
        this.appIcon = appIcon;
        this.nowPlaying = nowPlaying;
    }
}

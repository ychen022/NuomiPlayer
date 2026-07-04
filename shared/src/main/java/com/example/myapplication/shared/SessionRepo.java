package com.example.myapplication.shared;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enumerates the media sessions currently active on the device (excluding our
 * own mirror session) so they can be surfaced as pickable sources in the
 * Android Auto browse tree.
 *
 * <p>Reconstructed from NuomiPlayer 2.0 (decompiled {@code 糯米播放器2.0.apk},
 * class {@code com.nuomi.shared.SessionRepo}). Requires our
 * {@code MusicSessionSniffer} NotificationListenerService to be enabled, since
 * {@link MediaSessionManager#getActiveSessions(ComponentName)} is gated behind
 * notification-listener access.
 */
public class SessionRepo {

    public static List<SessionInfo> loadActiveSessions(Context ctx) {
        MediaSessionManager msm =
                (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) {
            return new ArrayList<>();
        }

        List<MediaController> controllers;
        try {
            ComponentName listener = new ComponentName(
                    ctx.getPackageName(), ctx.getPackageName() + ".MusicSessionSniffer");
            controllers = msm.getActiveSessions(listener);
        } catch (SecurityException e) {
            return new ArrayList<>();
        }
        if (controllers == null) {
            return new ArrayList<>();
        }

        String myPkg = ctx.getPackageName();

        // De-duplicate by package, keeping the first controller seen for each app.
        Map<String, MediaController> byPackage = new LinkedHashMap<>();
        for (MediaController c : controllers) {
            String pkg = c.getPackageName();
            if (pkg != null && !pkg.equals(myPkg) && !byPackage.containsKey(pkg)) {
                byPackage.put(pkg, c);
            }
        }

        List<SessionInfo> out = new ArrayList<>();
        for (MediaController c : byPackage.values()) {
            String pkg = c.getPackageName();

            String label;
            try {
                label = ctx.getPackageManager()
                        .getApplicationLabel(ctx.getPackageManager().getApplicationInfo(pkg, 0))
                        .toString();
            } catch (Exception e) {
                label = pkg;
            }

            Drawable icon = null;
            try {
                icon = ctx.getPackageManager().getApplicationIcon(pkg);
            } catch (Exception ignored) {
            }

            String nowPlaying = null;
            MediaMetadata mm = c.getMetadata();
            if (mm != null) {
                CharSequence title = mm.getText(MediaMetadata.METADATA_KEY_TITLE);
                if (!TextUtils.isEmpty(title)) {
                    nowPlaying = title.toString();
                }
            }

            out.add(new SessionInfo(pkg, label, icon, nowPlaying));
        }
        return out;
    }
}

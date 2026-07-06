<p align="right">
  <a href="./README.zh-CN.md">中文</a> | <b>English</b>
</p>

# NuomiPlayer - Android Auto Music Companion

🚗🎶 NuomiPlayer is an Android Auto companion app that extends in-car playback experience by **mirroring media metadata and controls from your phone**, providing a cleaner UI, richer info, and **time-synced lyrics**.

I built this after realizing that **QQ Music does not support Android Auto**, while many Android Auto–ready apps don’t include the songs I listen to most. Existing workarounds were either outdated or unstable (e.g., frequent crashes), so I decided to build a lightweight and reliable solution for my own daily driving.

> Note: I had little prior Android app experience. To accelerate development, part of the **phone playback UI** was adapted from the open-source project **Booming Music** (see Credits). If any usage violates the original license, please contact me and I will promptly fix or remove the relevant code.

Download latest prebuilt APKs:
- 📦 NuomiPlayer 2.0: https://github.com/charlottejas/NuomiPlayer/raw/main/糯米播放器2.0.apk

> ### 🍴 This fork — NuomiPlayer 2.0a
> This is a community fork of the upstream 2.0 release with two changes on top:
> - **Fixed** a startup bug where NetEase Cloud Music lyrics on Android Auto would not
>   display until you toggled lyrics mode off/on **and** skipped to the next song. Lyrics
>   now appear on the first song automatically.
> - **Added** simultaneous translated-lyric display (from upstream
>   [PR #13](https://github.com/charlottejas/NuomiPlayer/pull/13)): for songs with a
>   translation whose current line is Japanese, the translation is shown on top and the
>   original line underneath. Chinese/English lyrics keep the current-line + next-line view.
>
> 📦 Download 2.0a: https://github.com/ychen022/NuomiPlayer/raw/main/糯米播放器2.0-fixed.apk
>
> ⚠️ 2.0a is signed with a different key than the official releases, so **uninstall any
> existing NuomiPlayer before installing it**. See [Fork details](#fork-details-20a) below.

## Disclaimer

This project is for **personal learning and research** only.  
- It does **not** include any music resources.
- It does **not** provide any music streaming API.
- Media data is obtained from system signals (e.g., **MediaSession / notifications / broadcast**), and is intended to avoid copyright infringement.

If any third-party code usage raises licensing concerns, please reach out and I will respond quickly.

## Features

- 🚘 Android Auto playback screen support
- 🎵 Reads media metadata from most playback apps (based on system MediaSession / notifications)
- ⏯️ Play / Pause / Previous / Next controls
- ⏩ Seek bar with drag-to-seek support
- 🖼️ Title / Artist / Album art display
- 📝 Time-synced lyrics view (Android Auto)

## Screenshots

### 🚘 Android Auto Playback
![Android Auto Playback](screenshot/auto.jpg)

### 📝 Android Auto Lyrics
![Android Auto Lyrics](screenshot/lyrics.jpg)

### 📱 Phone UI
<div style="display:flex; gap:10px;">
  <img src="screenshot/mobile.jpg" width="360"/>
  <img src="screenshot/mobile_1.jpg" width="360"/>
</div>

## Changelog

### 2.0a (this fork)
- Fixed NetEase Cloud Music lyrics not displaying on Android Auto at startup until the
  lyrics mode was toggled off/on and the song was skipped — lyrics now show on the first
  song automatically
- Added simultaneous translated-lyric display for Japanese songs (translation on top,
  original underneath), ported from upstream PR #13

### 1.4.1
- Removed some permission requirements

### 1.4.0
- Upgraded from a platform-specific adaptation to a **general solution**, now compatible with **most playback apps** (music / podcast / video) via system MediaSession/notifications
- Fixed multiple edge cases and reduced crash probability

### 1.3.1
- Fixed inability to open NetEase Cloud Music

### 1.3.0
- Added NetEase Cloud Music mirroring support

### 1.2.0
- Added playback mode switching (in order / single loop / shuffle)
- Added “enable lyrics mode by default” option

### 1.1.0
- Added real-time lyrics synced with playback progress

### 1.0.0
- First stable release
- QQ Music mirroring support
- Android Auto mode
- Basic playback controls

## APK Downloads

Download prebuilt APKs:
- 📦 NuomiPlayer 2.0a (this fork): https://github.com/ychen022/NuomiPlayer/raw/main/糯米播放器2.0-fixed.apk  
  (Upstream 2.0 + the startup lyrics fix and translated-lyric display; see [Fork details](#fork-details-20a))
- 📦 NuomiPlayer 1.4.1: https://github.com/charlottejas/NuomiPlayer/raw/main/糯米播放器1.4.1.apk  
  (Fewer permissions; recommended if 1.4.0 fails to install on some OEM devices)
- 📦 NuomiPlayer 1.4.0: https://github.com/charlottejas/NuomiPlayer/raw/main/糯米播放器1.4.0.apk  
  (General solution; recommended for most users)
- 📦 NuomiPlayer 1.3.1: https://github.com/charlottejas/NuomiPlayer/raw/main/糯米播放器1.3.1.apk
- 📦 NuomiPlayer 1.2.0: https://github.com/charlottejas/NuomiPlayer/raw/main/糯米播放器1.2.0.apk
- 📦 NuomiPlayer 1.1.0: https://github.com/charlottejas/NuomiPlayer/raw/main/糯米播放器1.1.0.apk
- 📦 NuomiPlayer 1.0.0: https://github.com/charlottejas/NuomiPlayer/raw/main/糯米播放器%201.0.0.apk

> Make sure Android Auto developer settings allow installing apps from unknown sources (see Run Guide).

## Run Guide

1. Enable Android Auto **Developer Mode**  
   Official guide: https://developer.android.com/training/cars/testing

2. In Android Auto developer settings, enable **Unknown sources**

3. Start Android Auto emulator or connect your car head unit

4. Start playing any track in your phone music app (e.g., QQ Music).  
   NuomiPlayer will mirror the playback info and controls in Android Auto.

> NuomiPlayer listens to system media signals. Make sure a playback app is actively playing.

## Tech Stack

- Java & Android SDK
- Android Auto (`automotive` module)
- MediaSession & PlaybackStateCompat
- BroadcastReceiver-based media signal parsing
- Custom icons & theming

## Fork details (2.0a)

This fork (`ychen022/NuomiPlayer`) is based on the upstream **2.0** release. Because the
upstream 2.0 source was not published, the investigation was done by reverse-engineering
the shipped `糯米播放器2.0.apk`; the affected classes were then reconstructed in the
`shared` module and the shipped APK was patched at the bytecode level so a usable build
could be produced without the full project sources.

**What changed**
- **Startup lyrics fix.** On the first bind to a NetEase session the app called its mirror
  routine directly, bypassing the metadata callback, so lyrics for the currently-playing
  song were never fetched, and the per-second lyric ticker died before a controller was
  bound. Both are now triggered on bind, so lyrics render on the first song — no more
  toggling lyrics off/on and skipping to the next track.
- **Simultaneous translated lyrics.** Ported from upstream
  [PR #13](https://github.com/charlottejas/NuomiPlayer/pull/13) (by
  [@1231joe1231](https://github.com/1231joe1231)). NetEase translations (`tlyric`) are
  fetched via the NetEase web API; for lines containing Japanese kana the translation is
  shown as the title and the original as the subtitle, while Chinese/English lyrics keep
  the current-line + next-line display.

**Privacy note.** As with upstream, the only network request added is to `music.163.com`
to fetch lyrics by song id; no other data leaves the device.

**Install**
1. `糯米播放器2.0-fixed.apk` is signed with a **debug key**, different from the official
   releases. **Uninstall any existing NuomiPlayer first**, otherwise the install fails with
   a signature mismatch.
2. Grant the app **notification access** so it can read the music app's media session.
3. Enable "**lyrics mode by default**" if you want lyrics to appear automatically.

## Credits

Special thanks to **Booming Music**: https://github.com/mardous/BoomingMusic  
Parts of the phone UI were adapted from this project.



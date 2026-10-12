# Meta Clip That!

**"Meta AI, clip that!" — now it actually works.** A Medal.tv-style rolling
game-clip recorder for Meta Quest and other Android devices.

Hit one button (or a remapped controller button) and the last 30 seconds of
your gameplay are saved as an MP4 — while recording keeps rolling in the
background, so you can clip again immediately.

## Features

- **Rolling capture**: a foreground service records your screen into an
  in-memory ring buffer of encoded H.264 frames (default last 2 minutes, hard
  max 5 minutes). Anything older is discarded automatically — storage and
  memory stay bounded no matter how long you play.
- **Instant clipping**: fire `com.metaclipthat.action.CLIP_NOW` (or tap the
  giant in-app **CLIP NOW** button) to save the last X seconds (default 30,
  adjustable 1–300) as an MP4 in `Movies/MetaClipThat`. Clipping **never**
  interrupts rolling — the buffer keeps going and the clipped segment stays
  buffered, so you can clip again right away.
- **Death-proof**: if the screen-share session dies unexpectedly, the app
  first auto-saves whatever is left in the buffer (up to your clip length),
  *then* plays a loud alarm so you know to restart rolling. Your last moments
  are never lost.
- **KeyMapper trigger**: remap any button (Quest controller, volume keys…)
  to fire the clip via KeyMapper's expert-mode shell action.
- **Shizuku**: paired status in the app; used to read the real refresh rate
  on Quest (`getprop debug.oculus.refreshRate`) so the FPS setting caps
  correctly (10 fps min … your refresh rate max).
- **Audio**: optional internal/game audio capture (Android 10+) and/or
  microphone recording, muxed into the clip.
- **Playful "M Clip that!" theme**: pale yellow/cream backgrounds with dark
  charcoal text, melon green/yellow-green accents on buttons, toggles and
  highlights, and the hand-drawn **Freckle Face** font (bundled) for headings,
  buttons and display text. Medal-style clipping-first home screen;
  KeyMapper-style grouped settings.
- **Two-stage splash**: FluxTech studio logo, crossfading into the
  "M Clip that!" logo, before the main screen.

## Getting the APK

### Option A — GitHub Actions (recommended)

1. Push this repo to GitHub (or fork it).
2. The `Build debug APK` workflow runs automatically on every push and pull
   request.
3. When it finishes, open the workflow run → **Artifacts** →
   download **MetaClipThat-debug-apk** (contains the signed-with-debug-key
   APK).
4. Sideload it onto your Quest / Android device
   (e.g. `adb install app-debug.apk`, or via SideQuest / a file manager).

### Option B — build locally

You need JDK 17 and the Android SDK (API 34, build-tools):

```bash
./gradlew assembleDebug
# APK lands at: app/build/outputs/apk/debug/app-debug.apk
```

## Setup guide

### 1. Install the app and grant permissions

- Install the APK.
- Open **Meta Clip That!** and tap **START ROLLING**. You will be asked for:
  - **Screen capture** (MediaProjection) — required, every time rolling starts.
    The app cannot record in the background without it; if you dismiss the
    system screen-share dialog, rolling simply won't start.
  - **Notifications** (Android 13+) — for the persistent "rolling" notification.
  - **Microphone** — only if you enable the microphone toggle in Settings.

### 2. Shizuku pairing (for the correct FPS cap on Quest)

1. Sideload the [Shizuku](https://shizuku.rikka.app) manager app.
2. Open Shizuku → **Pairing** → follow the wireless-debugging pairing steps
   (on Quest: enable wireless debugging in developer settings; no PC needed
   after the first pair).
3. Back in **Meta Clip That!** → home card or Settings → **Shizuku**, tap
   **PRESS TO SETUP** and allow the permission.
4. The FPS slider in Settings will now cap at your headset's real refresh
   rate (e.g. 120). Without Shizuku it falls back to the display's reported
   rate.

### 3. KeyMapper trigger (clip with a controller button)

1. Install [KeyMapper](https://github.com/keymapperorg/KeyMapper/releases)
   (grab the APK from its GitHub releases — no Play Store needed) and
   complete its setup (accessibility service + write-secure-settings or
   Shizuku/ADB grant as it instructs).
2. In KeyMapper settings, enable **Expert mode**. Expert mode unlocks the
   **Run shell command** action.
3. Create a new key map → record your **trigger** (e.g. a Quest controller
   button combo).
4. Add an **action** → choose **Run shell command** → enter exactly:

   ```
   am broadcast -a com.metaclipthat.action.CLIP_NOW -p com.metaclipthat
   ```

   (The `-p com.metaclipthat` flag is required: since Android 8, an
   implicit broadcast like `-a ...` alone is never delivered to
   manifest-declared receivers. Targeting the package makes it explicit.)

5. Save and **enable** the key map.

Now pressing that button in-game saves a clip. If rolling isn't active you'll
get a toast telling you to start it first.

## How rolling / clipping / storage works

- While rolling, the screen is encoded to H.264 (8 Mbps) and each encoded
  frame is appended to an in-memory ring buffer with its presentation
  timestamp; keyframes are indexed (codec-config bytes are prepended to each
  keyframe so every snapshot is self-contained). Audio (if enabled) is
  captured, mixed to mono, encoded to AAC live, and buffered in a parallel
  ring with matching timestamps.
- The buffer keeps only the last N minutes (your setting, max 5). Old frames
  are dropped from the front automatically, and the front of the buffer is
  always a keyframe — so memory is bounded and every snapshot is muxable.
- Clipping snapshots the last X seconds (your setting, max 300 s), finds the
  newest keyframe at/before the cut point, and muxes video + in-range audio
  straight into MP4 with `MediaMuxer` — no re-encoding, no pause in rolling,
  nothing removed from the buffer.
- Clips are published via `MediaStore` to `Movies/MetaClipThat` as
  `MetaClipThat_yyyyMMdd_HHmmss[_auto].mp4` — visible in any gallery / file
  manager, no storage permission needed.

## Project layout

```
app/src/main/
  AndroidManifest.xml
  java/com/metaclipthat/
    SplashActivity.kt          # two-stage splash (FluxTech -> M Clip that!)
    MainActivity.kt            # bottom-nav host (Clip / Settings)
    HomeFragment.kt            # Medal-style home: status, CLIP NOW, start/stop
    SettingsFragment.kt        # KeyMapper-style grouped settings
    RollingCaptureService.kt   # foreground service: encoder, ring buffer, clips
    FrameRingBuffer.kt         # VideoRingBuffer + AudioRingBuffer
    AudioCapture.kt            # internal/mic capture + live AAC encode
    ClipSaver.kt               # mux snapshot -> MP4 -> MediaStore
    ClipReceiver.kt            # exported com.metaclipthat.action.CLIP_NOW receiver
    ShizukuHelper.kt           # pairing status + Quest refresh-rate via shell
    AlertSound.kt              # loud alarm when rolling dies
    Prefs.kt                   # settings persistence
  res/
    font/freckle_face.ttf      # Freckle Face display font (Google Fonts, OFL)
    drawable-nodpi/            # splash artwork (FluxTech + M Clip that! logos)
    # playful light "M Clip that!" Material3 theme (cream + charcoal + melon)
.github/workflows/android.yml  # CI: builds + uploads the debug APK
```

## Notes / limitations

- Screen capture on Android requires the system screen-share consent dialog
  each time rolling starts, and the session dies if screen sharing stops —
  that's why the auto-clip + alarm exists.
- The 5-minute in-memory cap is deliberate: at 8 Mbps, 5 minutes ≈ 300 MB.
  Lower the buffer or FPS if your device is tight on RAM.
- Debug builds only; no release signing is configured.

# Phigros Autoplay for Android (offline practice)

Android port of [Wlenk/Phigros-Autoplay-Script](https://github.com/Wlenk/Phigros-Autoplay-Script)
(itself based on [Phisap](https://github.com/kvarenzn/phisap)). MIT licensed, see `LICENSE`.

The original is a PC tool that sends touches to the phone over ADB/scrcpy. This version runs **on the phone**:

* The original Python code (chart extraction, `algo1`/`algo2` planners) runs unchanged inside the app via
  [Chaquopy](https://chaquo.com/chaquopy/).
* Taps and swipes are injected with an **Accessibility Service** (`dispatchGesture`), so no PC, ADB or root is needed.
* A small floating bar (☰ drag handle, `-10`, `GO`/`STOP`, `+10`, `✕`) controls playback on top of the game.

Intended for practising charts **offline**. Play in airplane mode or on a throwaway account so autoplayed
scores never reach your real account.

## Build the APK (no Android Studio needed)

1. Create a GitHub repository and upload this whole folder (keep the `.github` folder).
2. Open the **Actions** tab, run **Build APK** (it also runs on every push).
3. Download the `PhigrosAutoplay-debug-apk` artifact, unzip it, install `app-debug.apk`
   (allow "install unknown apps").

Or with Android Studio: *File > Open* this folder and press Run. From a shell: `./gradlew assembleDebug`
(needs JDK 17, the Android SDK and Python 3.8+ on the PATH; the Chaquopy plugin needs the latter at build time).

## Use it

1. Open the app, tap **Extract charts from installed Phigros** (or choose an `.apk`). The first run takes a while.
2. Enable **Phigros Autoplay (tap injector)** in *Settings > Accessibility*.
   Android 13+: if it is greyed out, open the app's *App info*, tap **⋮ > Allow restricted settings**, then retry.
3. Pick a song/difficulty, choose an algorithm (`algo1` conservative, `algo2` aggressive; if one errors, try the other),
   optionally *Mirror mode*, then **Prepare and show floating GO button**.
4. Switch to Phigros and start the song.
   * **Manual sync** (recommended, as in the original): press **GO** just as the first note is about to reach the line.
   * **Delay sync**: pause the game, press **GO**; the app taps the screen centre to resume, then starts after the set
     delay (default 1.95 s like the original).
5. If notes are consistently early/late, nudge with `-10` / `+10` (changes apply to upcoming notes) and retry.

## How the port works / limits

`dispatchGesture` allows only 10 strokes per gesture and a new gesture cancels the running one, so
`app/src/main/python/touch_plan.py` cuts the planner's output into gesture windows of at most 10 strokes,
placing the cuts in quiet gaps. Fingers that stay down across a cut (holds, long drags) are chained with
`continueStroke`. Expect slightly looser timing than the ADB version (gesture hand-off adds some milliseconds), and
very dense charts may drop some notes. Tap timing is not guaranteed: this was written without a device to test on.

Chart files live in the app's private storage; uninstalling the app removes them.

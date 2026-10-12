# Android

An arm64 Android app of the port, built from this repository. It is the same game, a Vulkan 1.3 renderer on SDL3, with touch
controls added. **It has not been run on a device yet when this was written** (the first build was written without an Android
toolchain at hand); what has been tried is listed under "Status" below.

## What you need

- Android 10 or newer on arm64, with a GPU and driver that offer Vulkan 1.3 and the features the renderer checks for at start
  (`vk_context.cpp`; the log names a missing one). Android 13 or newer on a recent Snapdragon, Mali or Immortalis chip is the
  realistic floor.
- Your own copy of P.T. (the US release, CUSA01127) on the device: the app has no game files in it. Either a dump folder with
  `chunk1.psarc` and `texture.qar` (the folder the desktop installer calls the dump; the files can be anywhere inside the
  folder you pick), or a fake PKG (fPKG) made from such a dump. A PlayStation Store (retail) PKG cannot be used: it is encrypted
  for the console that owns the license, and neither this app nor the desktop installer decrypts it.
- Free space for a second copy of those two files (several GB): the app copies them (or extracts them from the PKG) into its
  private storage once.

## Installing and the first start

1. Install `P.T.PC.Port-Android-arm64.apk` (allow installs from the browser or file manager you opened it with). Every build is
   signed with the same key (`android/pt-port.keystore`), so a newer APK installs over an older one and keeps the game files.
   This first install is the only one done by hand: from a build with the updater in it (see "Updates"), the app updates itself.
2. The first start shows a page that asks for the game. Choose the dump folder, or the two files themselves, or the fPKG file
   ("Or choose a fake PKG"; a folder that holds only one PKG works too). Keep the screen on until the copy is done; then the main
   menu opens. Later starts go to the menu after the update check.
3. Settings, the save and `pt.log` are in the app's private storage (`pt-runtime/data`). Uninstalling the app deletes them and the
   copied game files.

## Updates

Every time the app starts it asks GitHub (the Releases of the repository it was built in) for a newer build. It waits at most six
seconds for the answer; offline, it starts as usual.

- A banner at the top says "Starting download...", then "Downloading update: N%" with a progress bar.
- The finished file goes to Android's installer, which asks you to confirm. When the install is done the app has been replaced and
  closed; open it again. The imported game files and settings stay.
- The first time, Android asks you to allow this app to install updates ("Install unknown apps"): a Settings page opens, switch it
  on and go back. If you cancel an install, or a download fails, the banner says so and the game starts anyway; the next start asks again.
- The build number is the CI run number: it is the APK's versionCode and the release tag `android-build-N`. A release counts as newer
  when its N is above the installed versionCode. A local build has versionCode 1, so it is always offered the newest CI build.
- The downloaded file's SHA-256 is checked against the digest GitHub lists for the release asset, and Android refuses an APK that is not
  signed with the same key as the installed app.

The check reads only published releases, which is why the CI publishes a (pre)release rather than a draft; it keeps the newest three.

## The main menu

`MenuActivity` (after the launcher's update check and the crash report, when there is one): Start, New game, Load save, Settings, and small links
to the game files (choose them again), a new update check and the log. The look is code only (`AtmosphereView`, `MenuStyle`): a dark
room with a lamp that flickers and now and then goes out, dust, a vignette and film grain; no image or font is used. The pad moves
between the entries (D-pad or stick; A presses, B goes back).

- **Start** starts the game, which loads its own save. The game runs in its own process (`android:process=":game"` on `PTActivity`):
  SDL cannot run the game's main function twice in one process, so the game's process ends with the game: when `main` returns the
  native code calls `_exit` with the game's result (`PtMain` in `main.cpp`; 0 is a normal end, which the crash report does not count, any
  other number is reported), and `PTActivity.onDestroy` ends the process as a fallback. The next Start gets a new process. The way back
  to the menu: Quit in the game's own menu, or "Main menu" at the top of the settings panel (the SET button; it asks first). `MenuActivity`
  waits (4 s at most) for the old process to be gone before it starts. A game that crashed shows its report when the menu comes back.
- **New game** starts the story from the beginning: with a save, after a question, which is kept as a backup first.
- **Load save** (`SavesActivity`, `SaveManager`): the current save (floor, cleared, Game+), Continue, Back up now, Save to a file, Choose
  a save file (a 128-byte `PT_Save_Data` slot file with the right magic and version, a PC save too), New game, and the list of backups with
  Put back and Delete. The game keeps two alternating slot files, `PT_Save_Data0` and `PT_Save_Data1`, in `data/`; the one with the
  higher sequence number is read. Everything that replaces a save keeps the old one first, in `data/saves/backup-*`.
- **Settings** (`SettingsActivity`): the same panel as in the game (`SettingsPanel`). The old second icon, "P.T. tools", is gone; its
  log is `ToolsActivity`, reached from the menu's Log link.
- The app's own settings (picture size, the frame counter, the automatic size) are in `data/android-app.txt` (`AppPrefs`), a file and
  not SharedPreferences, because the menu and the game run in different processes.

The app icon is an original drawing (`res/drawable/ic_launcher_*.xml`, an adaptive icon with a themed-icon layer): a keyhole in a warm glow.

## Controls

- Walk: put a thumb anywhere in the left part of the screen below the top strip; the stick appears under it.
- Look: drag a finger anywhere else.
- Buttons: ACT (the action, cross), ZOOM (hold; R3), X (the XMark's gouge), II (the menu), SET (the PC settings page) and JACK
  (the spoken word's stand-in, for the one part of the game that listens for it: tap it when the game listens).
- The Android back button opens the menu. In menus a tap is a click.
- A Bluetooth or USB controller works as on the desktop (SDL's gamepad support); the on-screen controls hide while a controller
  is the device in use and come back at the next touch.

## What is different from the desktop version

Off on Android: the spoken-word recognizer (whisper.cpp is not built; the JACK button is the way), enhanced textures (a desktop
tool), FSR, DLSS and XeSS, the ray-traced options, VR, the update notice, LiveSplit, the mods folder (it is in private storage,
out of reach without root) and fake PKG installs (extract on a PC first).

How a platform piece differs from the Windows and Linux ones:

| Piece | Android |
|---|---|
| executable folder | `pt-runtime` in the app's private storage (`platform/android_platform.cpp`); the APK's shaders and fonts are copied there at the start when the list in `pt_assets.txt` differs from the last copy |
| game files | `pt-runtime/game/CUSA01127`, put there by `ImportActivity` (`android/app/src/main/java/com/ptport/android`); from a fake PKG by `PkgExtractor` |
| updates | `Updater.java` and the banner in `ImportActivity`: GitHub Releases API, a download streamed into a `PackageInstaller` session |
| window | SDL's `SDLActivity` loads `libSDL3.so` and `libmain.so` (the `pt` target) and calls `SDL_main` |
| Vulkan | the system's `libvulkan.so` through volk; the headers are fetched at volk's version (the NDK's are older) |
| input | touch overlay in `platform/touch_controls.cpp`; SDL's mouse made of a touch is ignored in play and is the menus' pointer |
| audio flush to zero | FPCR.FZ, as on Apple silicon |
| exposure meter | `luminance.comp` reads the lit picture's mean with bit-level checks for NaN and infinity (Adreno's compiler does not keep them out of a comparison), limits one pixel to 2.0 of it (`SceneRenderer::LuminanceCeiling`, `PT_EV_CEILING` changes it, 0 is the desktop's no limit), and counts the pixels it left out |

The exposure meter, the picture that stayed black. In the first Android logs the exposure sat at its darkest (EV -10, 0.00019) in a lit
room, the picture black around the glowing lamp, and the meter's reading did not follow the exposure: about 0.3 at 0.007 and at
0.00019 alike, over a few percent of the pixels (a hallway view should count nearly all of them). The cause is not found yet. What
the code does now: pixels with a NaN or an infinity are left out by their bits and counted; a light's or a probe's NaN is left out
of the pixel instead of turning it black (`light_main.glsl`, `probe_main.glsl`, `probe_resolve.frag`); when more lit pixels are left
out than counted, the EV stays where it is. pt.log has three `exposure ...` lines every two seconds (the reading, the exposure it was
taken at and the EV it aims for; the counts of pixels without depth, non-finite, over 60000, black and valid, with the albedo's mean
and the partials that were unusable; the depth, lit value and albedo of the centre, left and right pixels), and the log screen
shows them.

## Building

The Android NDK (r26 or newer), CMake 3.28, Ninja, Python 3, JDK 17 and Gradle 8.7 or newer:

    export ANDROID_NDK_HOME=/path/to/ndk
    tools/android/build.sh native      # libmain.so, libSDL3.so, shaders; staged into android/app
    tools/android/build.sh apk         # Gradle: dist/P.T.PC.Port-Android-arm64.apk

`.github/workflows/android.yml` does both on GitHub's Linux runner for every push to the `android` branch or a manual run
(Actions > Android > Run workflow). The APK is a workflow artifact and a published prerelease `android-build-N` (N is the run
number, the app's versionCode; the newest three are kept), which is what the installed app's updater finds. Whatever the result, the last step pushes a short log (status, the tail of each build log, the error lines) to the
`ci-logs` branch.

## Fake PKG import

`PkgExtractor.java` is the Java form of what the desktop installer's helper does with LibOrbisPkg (`installer/Extractor`,
docs/installer.md has what is accepted and refused). A fake package's PFS key is derived from its own content ID and the
standard passcode (32 zeros); the key's digest in the package says whether that is what the package was made with. If it is
not (a store package, or a fake one made with another passcode) the package is refused with the dump message. Then: the outer
PFS image (AES-XTS from its sector 16 on), its `pfs_image.dat` (PFSC, deflate blocks), the inner PFS image, and in it
`chunk1.psarc`, `texture.qar` and `pathid_list_ps4.bin`, found by content as the desktop helper does, written under those names
with a `source.txt`.

`tools/android/pkgtest/run.sh` tests it on a desktop JDK against synthetic packages built after LibOrbisPkg's builder
(`make_test_pkgs.py`, needs python3 with `cryptography`): the IEEE 1619 XTS vectors, a signed and an unsigned outer image, the
newer key derivation, a fragmented file, a file past the doubly indirect block limit, archives found by content in a subfolder,
and the refusals (other passcode, not a PKG, truncated, another game).

## Speed and picture size

The desktop settings are far too heavy for a tablet GPU at the tablet's own resolution (3000 px wide and more). So:

- `PTActivity` draws into a fixed-size surface (`SurfaceHolder.setFixedSize`, 1080 lines by default) and Android's compositor scales it
  to the screen. The surface must be laid out `MATCH_PARENT`: SDL adds it without layout parameters, and a fixed-size surface that
  wraps its content is exactly that size, in a corner of the screen. Touches are scaled to the buffer's size (`FixedSurface.onTouch`),
  since SDL divides them by the surface size.
- The first start of a build with `android-defaults-1` (a marker in `data/`) applies the Low graphics preset.
- Settings (`SettingsPanel.java`): the touch overlay's SET button opens the panel over the running game (the game sends the Android
  message `0x8001`, `PTActivity.onUnhandledMessage` shows it); the main menu's Settings has the same panel.
  It has four presets (Fastest, Fast, Balanced, Original), the picture size, a frame rate limit, v-sync and each graphics option on
  its own. A game setting goes to the game as a `name=number` line in `data/android-settings.txt` (the number is the row's value
  index on the game's own settings page); the game looks for the file twice a second and applies each line through
  `PcSettings::Set`, as a tap on that row would, so it acts at once and `pt.ini` keeps it (`PcSettings::ApplyAndroidChanges`). With
  no game running the file waits for the next start. The picture size is in `AppPrefs` and takes effect at once
  (`PTActivity.applyPictureSize` resizes the fixed surface; the game sees a window of the new size).
- The upscaler (Settings > Upscaler, Upscaler quality, Upscaler sharpness): FSR 3, FSR 4, DLSS and XeSS are Windows DLLs or need a
  vendor's GPU and MetalFX is Apple's, so the game's Upscaler list shows them greyed. The Android build has its own, **Built-in**
  (`builtin_backend.cpp`, `builtin_upscale.comp`; docs/upscaling.md, Built-in temporal upscaler): a compute shader that needs no
  library and runs on any Vulkan GPU. It draws the scene at a fraction of the picture size (Quality is 2/3 of each side, Performance
  1/2), jittered, and rebuilds the full size picture from the last frames. It is off by default, the presets do not touch it, and it
  is untested on the tablet: the first builds that have it are the first run of its shader on an Adreno. The game's own settings page
  (pause menu, PC settings) has the same Upscaler, Quality and Sharpness rows. If the picture goes wrong with it on, set Upscaler to Off.
  Settings > Upscaler > Upscaler test view (not kept in `pt.ini`; `PT_BUILTIN_VIEW` does the same at start) replaces the upscaled picture
  with one thing the shader reads: 1 Gradient (a pattern made from the pixel position alone), 2 Game picture (the render image as it
  came out of the scene passes), 3 Motion, 4 Depth, 5 History, 6 Reactive. Gradient on the whole screen means the compute pass and
  the output are fine and an input is wrong; a cropped gradient means the output or the passes after it are wrong. The dispatch's sizes,
  jitter, exposure and the inputs' image layouts are in `pt.log` (`upscale: built-in dispatch`) for the first frames of a run.
- The frame counter (Settings > Frame counter: Off, Frame rate, Detailed): once a second the game sends the Android message `0x8002`
  (frames a second x10 in 12 bits, GPU ms x4 in 9, CPU ms x4 in 9, averaged over the second) and `PTActivity` shows it in the top left
  corner, green at the frame limit, yellow below 95 % of it and red below 80 %; Detailed adds the GPU and CPU time of a frame and the
  picture size. The same message drives the automatic picture size (Settings > Automatic picture size, off by default, experimental):
  after 25 s of play, four seconds in a row below 93 % of the frame limit with the GPU as the cause lower the size one step (480, 540,
  630, 720, 810, 900, 1080, 1260, 1440), twenty seconds of ease raise it one step, never above the size chosen; a size that just failed
  is not tried again for 90 s and a change is followed by a 10 s pause. Every change rebuilds the render targets, so it pauses the picture.
- The frame rate limit is also given to Android as a hint (`Surface.setFrameRate`, Android 11 and newer) so the display runs at that
  rate or a multiple of it: 45 frames a second on a screen that stays at 144 Hz are shown for 3 and 4 refreshes by turns, which is
  choppy. The app is marked `appCategory="game"` for the system's game mode.
- The `status:` line in `pt.log` ends with the GPU time of each part of the frame (shadows, mirror, gbuffer, lighting, compose,
  post) and the CPU time of a frame, also shown at the top of the settings panel.
- The pipeline cache is written when the app goes to the background and at shutdown (an Android app is usually ended without one,
  which left every start with a cold cache), and while it grows: at a quiet moment (a menu open or the game paused, 45 s after the last
  write) or after 4 minutes of play. The write holds the pipeline creation lock for about 12 ms, which was a hitch every 30 s.
- A suboptimal swapchain (`VK_SUBOPTIMAL_KHR`) does not rebuild the swapchain on Android. If the surface reports a rotated
  transform the swapchain uses IDENTITY with the window's size and the system rotates. The swapchain's size and transforms are in
  pt.log (`vulkan: swapchain ...`).
- Menus: a finger is the pointer and a finger going down is a click (`InputDevice::TouchPointer`); the mouse SDL makes of a touch does
  the same where it works.
- The log screen (the menu's Log link) shows the device, settings, the `status:` lines (fps, GPU ms, render size, every 10 s), warnings and errors.

## When the game closes by itself

Android keeps the reason every earlier run of an app ended (`ApplicationExitInfo`, Android 11 and newer). If the last run was
a crash, a kill for memory or a hang, the launcher (or the menu, when the game's process ended while it was in use) shows `CrashReport.java`'s report: the
device, how the run ended (reason, signal, memory then), the native crash's backtrace from the tombstone, the head and tail of
`pt.log`, and the app's logcat. Copy and Share put it where it can be pasted into an issue. The report is also kept in the app's
files as `last-crash-report.txt`. Without it, `pt.log` is at `files/pt-runtime/data/pt.log` (`adb shell run-as
com.ptport.android cat files/pt-runtime/data/pt.log`; the APK is debuggable) and the log goes to logcat under the tag `PT`.

## Status

- Written without an NDK in reach; the first builds were compiled by CI only. The touch code and the Android branches were
  syntax-checked on a desktop compiler.
- The updater is written from the Android documentation and not tried on a device: the confirmation page of the installer on
  HyperOS, a silent update (Android 12 and newer allow it only for an app that installed itself), the "Install unknown apps" flow.
- The PKG import is tested on synthetic packages only, not on a real fPKG of P.T. or on a phone (speed, the storage provider's
  file descriptor).
- Not tried on a device: Vulkan start-up on a phone or tablet GPU, the importer on a real storage provider, frame rate, the touch
  layout on different screen shapes.

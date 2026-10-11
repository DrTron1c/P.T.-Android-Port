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
   ("Or choose a fake PKG"; a folder that holds only one PKG works too). Keep the screen on until the copy is done; then the game
   starts. Later starts go straight to the game.
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

## When the game closes by itself

Android keeps the reason every earlier run of an app ended (`ApplicationExitInfo`, Android 11 and newer). If the last run was
a crash, a kill for memory or a hang, the launcher shows `CrashReport.java`'s report instead of starting the game again: the
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

# Android

An arm64 Android app of the port, built from this repository. It is the same game, a Vulkan 1.3 renderer on SDL3, with touch
controls added. **It has not been run on a device yet when this was written** (the first build was written without an Android
toolchain at hand); what has been tried is listed under "Status" below.

## What you need

- Android 10 or newer on arm64, with a GPU and driver that offer Vulkan 1.3 and the features the renderer checks for at start
  (`vk_context.cpp`; the log names a missing one). Android 13 or newer on a recent Snapdragon, Mali or Immortalis chip is the
  realistic floor.
- Your own copy of P.T. (the US release, CUSA01127) as a dump folder on the device: the app has no game files in it. It needs
  `chunk1.psarc` and `texture.qar` (the folder the desktop installer calls the dump; the files can be anywhere inside the
  folder you pick).
- Free space for a second copy of those two files (several GB): the app copies them into its private storage once.

## Installing and the first start

1. Install `P.T.PC.Port-Android-arm64.apk` (allow installs from the browser or file manager you opened it with). Every build is
   signed with the same key (`android/pt-port.keystore`), so a newer APK installs over an older one and keeps the game files.
2. The first start shows a page that asks for the dump folder. Choose the folder, or choose the two files themselves. Keep the
   screen on until the copy is done; then the game starts. Later starts go straight to the game.
3. Settings, the save and `pt.log` are in the app's private storage (`pt-runtime/data`). Uninstalling the app deletes them and the
   copied game files.

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
| game files | `pt-runtime/game/CUSA01127`, put there by `ImportActivity` (`android/app/src/main/java/com/ptport/android`) |
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
(Actions > Android > Run workflow). The APK is a workflow artifact and a draft release (visible to the repository owner under
Releases). Whatever the result, the last step pushes a short log (status, the tail of each build log, the error lines) to the
`ci-logs` branch.

## Status

- Written without an NDK in reach; the first builds were compiled by CI only. The touch code and the Android branches were
  syntax-checked on a desktop compiler.
- Not tried on a device: Vulkan start-up on a phone or tablet GPU, the importer on a real storage provider, frame rate, the touch
  layout on different screen shapes.

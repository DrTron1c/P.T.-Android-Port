#pragma once

#include <filesystem>
#include <string>

#include "engine/platform/settings.h"

// Android (docs/android.md): the pieces the desktop builds get from the folder the executable lives in. An APK has no such folder,
// so the game's "executable folder" is pt-runtime in the app's private storage: PrepareRuntime copies the shaders and fonts there
// from the APK's assets, the player's game files are imported there by ImportActivity (game/CUSA01127), and the settings, save and
// log go to its data/ as on the desktop.
namespace pt::android {

// pt-runtime in the app's private storage; empty off Android or before PrepareRuntime
std::filesystem::path RuntimeDir();

// Once at the start of main(), on the thread SDL calls SDL_main on: finds the private storage, and copies the APK's assets
// (pt_assets.txt lists them with sizes and hashes, tools/android/stage.py) into RuntimeDir() when that list differs from the copy
// left by the last start or a file is missing. False when the assets cannot be copied; the log says why.
bool PrepareRuntime();

// The graphics settings for a tablet or phone GPU. The settings file's defaults are the desktop's (the original's look at 8192 px
// shadow atlas, every effect on): an Adreno tablet ran them at a few frames a second. Applied (and the file saved by the caller)
//   - once, at the first start of a build that has this (the marker android-defaults-1 in data_dir): the Low preset;
//   - whenever the tools screen asked for a preset (data_dir/android-quality.txt: low, medium or original), which is consumed.
// After that the settings are the player's own, changed on the game's PC settings page. True when settings were changed.
bool ApplyDeviceDefaults(AppSettings& settings, const std::filesystem::path& data_dir);

// A custom Vulkan driver the person installed in the main menu's Settings (GpuDriver.java) instead of the phone's own, opened through
// libadrenotools (Android only; docs/android.md). Returns the driver's vkGetInstanceProcAddr, or null (the reason in `error`, empty when
// none is installed) to use the system driver. The driver is on trial from here until EndGpuDriverTrial(): if the app dies in between,
// the next start drops the driver so it cannot trap the person in a crash loop.
void* OpenCustomGpuDriver(std::string& error);
bool CustomGpuDriverActive();
// Closes the driver (the one that would not create an instance) so the system driver can be loaded instead.
void CloseCustomGpuDriver();
void EndGpuDriverTrial();

}

#pragma once

#include <filesystem>

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

}

#include "engine/platform/android_platform.h"

#ifdef __ANDROID__

#include <SDL3/SDL.h>

#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <system_error>
#include <vector>

#include "engine/core/log.h"
#include "engine/platform/graphics_presets.h"

namespace pt::android {
namespace {

// set once by PrepareRuntime on the main thread, read by any thread after (finding the private storage needs the JNI environment
// SDL attached to that thread)
std::filesystem::path g_runtime_dir;

struct Entry {
    uint64_t size = 0;
    std::string path;
};

std::vector<Entry> ParseManifest(const std::string& text) {
    std::vector<Entry> entries;
    std::istringstream lines(text);
    std::string line;
    while (std::getline(lines, line)) {
        // "<size> <sha256> <path>"
        const size_t first = line.find(' ');
        const size_t second = first == std::string::npos ? std::string::npos : line.find(' ', first + 1);
        if (second == std::string::npos) continue;
        Entry entry;
        entry.size = std::strtoull(line.substr(0, first).c_str(), nullptr, 10);
        entry.path = line.substr(second + 1);
        while (!entry.path.empty() && (entry.path.back() == '\r' || entry.path.back() == '\n')) entry.path.pop_back();
        if (!entry.path.empty()) entries.push_back(std::move(entry));
    }
    return entries;
}

bool ReadAssetText(const char* name, std::string& out) {
    size_t size = 0;
    void* data = SDL_LoadFile(name, &size);
    if (!data) return false;
    out.assign(static_cast<const char*>(data), size);
    SDL_free(data);
    return true;
}

bool CopyAsset(const Entry& entry, const std::filesystem::path& dir) {
    size_t size = 0;
    void* data = SDL_LoadFile(entry.path.c_str(), &size);
    if (!data) {
        LogError("android: asset {} cannot be read: {}", entry.path, SDL_GetError());
        return false;
    }
    const std::filesystem::path target = dir / std::filesystem::path(entry.path);
    std::error_code ec;
    std::filesystem::create_directories(target.parent_path(), ec);
    const std::filesystem::path part = target.string() + ".part";
    bool ok = false;
    {
        std::ofstream out(part, std::ios::binary | std::ios::trunc);
        out.write(static_cast<const char*>(data), static_cast<std::streamsize>(size));
        ok = static_cast<bool>(out);
    }
    SDL_free(data);
    if (ok) {
        std::filesystem::rename(part, target, ec);
        ok = !ec;
    }
    if (!ok) {
        LogError("android: cannot write {}", target.string());
        std::filesystem::remove(part, ec);
    }
    return ok;
}

std::string ReadWord(const std::filesystem::path& path) {
    std::ifstream in(path, std::ios::binary);
    std::string word;
    in >> word;
    for (char& c : word) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return word;
}

}  // namespace

std::filesystem::path RuntimeDir() { return g_runtime_dir; }

bool ApplyDeviceDefaults(AppSettings& settings, const std::filesystem::path& data_dir) {
    std::error_code ec;
    const std::filesystem::path request = data_dir / "android-quality.txt";
    const std::filesystem::path marker = data_dir / "android-defaults-1";
    // The frame rate, once: a game held at 45 frames a second (and the screen asked to match, PTActivity) is smoother than one that
    // runs as fast as the GPU allows, which on a 144 Hz screen shows frames for 3 and 4 refreshes by turns. Only for a pt.ini that
    // has no limit; the settings panel changes it.
    const std::filesystem::path pace_marker = data_dir / "android-defaults-2";
    bool paced = false;
    if (!std::filesystem::exists(pace_marker, ec)) {
        if (settings.display.fps_limit == 0) {
            settings.display.fps_limit = 45;
            paced = true;
            LogInfo("android: frame rate limit 45 (first start of this version)");
        }
        std::ofstream(pace_marker, std::ios::binary | std::ios::trunc) << "45\n";
    }
    std::string wanted = ReadWord(request);
    const bool first = !std::filesystem::exists(marker, ec);
    if (wanted.empty() && !first) return paced;
    if (wanted.empty()) wanted = "low";
    if (wanted == "low") {
        ApplyGraphicsPreset(settings, GraphicsPreset::Low, false);
    } else if (wanted == "original") {
        ApplyGraphicsPreset(settings, GraphicsPreset::Original, false);
    } else if (wanted == "medium") {
        // the original's look with the costliest parts lighter: shadow maps a quarter of the size, none of the screen-wide effects
        // that need extra passes at full size
        ApplyGraphicsPreset(settings, GraphicsPreset::Original, false);
        settings.graphics.shadow_quality = 2;
        settings.graphics.depth_of_field = false;
        settings.graphics.motion_blur = false;
        settings.graphics.lens_distortion = false;
        settings.graphics.lens_ghosts = false;
    } else {
        LogWarn("android: unknown graphics preset '{}' in {}", wanted, request.string());
        std::filesystem::remove(request, ec);
        return paced;
    }
    std::filesystem::remove(request, ec);
    std::ofstream(marker, std::ios::binary | std::ios::trunc) << wanted << "\n";
    LogInfo("android: graphics preset {} ({})", wanted, first ? "first start of this version" : "asked for in the tools screen");
    return true;
}

bool PrepareRuntime() {
    const char* base = SDL_GetAndroidInternalStoragePath();
    if (!base) {
        LogError("android: no private storage: {}", SDL_GetError());
        return false;
    }
    const std::filesystem::path dir = std::filesystem::path(base) / "pt-runtime";
    std::error_code ec;
    std::filesystem::create_directories(dir, ec);
    g_runtime_dir = dir;

    std::string manifest;
    if (!ReadAssetText("pt_assets.txt", manifest)) {
        LogError("android: pt_assets.txt is not in the APK: {}", SDL_GetError());
        return false;
    }
    std::string last;
    {
        std::ifstream in(dir / ".pt_assets.txt", std::ios::binary);
        std::ostringstream text;
        text << in.rdbuf();
        last = text.str();
    }
    const bool changed = last != manifest;
    const std::vector<Entry> entries = ParseManifest(manifest);
    int copied = 0;
    for (const Entry& entry : entries) {
        const std::filesystem::path target = dir / std::filesystem::path(entry.path);
        std::error_code stat_error;
        const bool present = std::filesystem::is_regular_file(target, stat_error) && std::filesystem::file_size(target, stat_error) == entry.size;
        if (!changed && present) continue;
        if (!CopyAsset(entry, dir)) return false;
        ++copied;
    }
    if (changed || copied > 0) {
        std::ofstream out(dir / ".pt_assets.txt", std::ios::binary | std::ios::trunc);
        out << manifest;
        LogInfo("android: copied {} of {} assets to {}", copied, entries.size(), dir.string());
    }
    return true;
}

}

#else

namespace pt::android {

std::filesystem::path RuntimeDir() { return {}; }
bool PrepareRuntime() { return true; }
bool ApplyDeviceDefaults(AppSettings&, const std::filesystem::path&) { return false; }

}

#endif

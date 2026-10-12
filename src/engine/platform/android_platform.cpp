#include "engine/platform/android_platform.h"

#ifdef __ANDROID__

#include <SDL3/SDL.h>

#include <adrenotools/driver.h>
#include <dlfcn.h>

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

namespace {

// <private storage>/gpu_driver: the driver package's files and main.txt, the file name of its main library; the settings screen
// writes them (GpuDriver.java). Private storage is what adrenotools requires: any app could tamper with removable storage.
std::filesystem::path GpuDriverDir() {
    const char* internal = SDL_GetAndroidInternalStoragePath();
    return internal ? std::filesystem::path(internal) / "gpu_driver" : std::filesystem::path();
}

void* g_gpu_driver = nullptr;

}  // namespace

void* OpenCustomGpuDriver(std::string& error) {
    error.clear();
    const std::filesystem::path dir = GpuDriverDir();
    std::string library;
    std::error_code ec;
    if (dir.empty() || !std::filesystem::exists(dir / "main.txt", ec)) return nullptr;
    std::ifstream main(dir / "main.txt");
    std::getline(main, library);
    // only a plain file name, never a path that leads out of the folder
    const std::filesystem::path name = library;
    if (library.empty() || name.filename() != name || library == "." || library == ".." || !std::filesystem::exists(dir / name, ec)) {
        error = "the installed driver is missing or its name is invalid; install it again";
        return nullptr;
    }
    if (std::filesystem::exists(dir / ".trial", ec)) {
        LogWarn("android: the custom GPU driver {} did not get the renderer started last time; using the phone's own", library);
        std::filesystem::remove_all(dir, ec);
        return nullptr;
    }
    std::ofstream(dir / ".trial").put('\n');
    // hookLibDir is the app's native library directory (where libmain.so is), which also holds libmain_hook.so and libhook_impl.so
    Dl_info info{};
    if (!dladdr(reinterpret_cast<void*>(&OpenCustomGpuDriver), &info) || !info.dli_fname) {
        error = "cannot find the app's library folder";
        return nullptr;
    }
    const std::string hooks = std::filesystem::path(info.dli_fname).parent_path().string() + "/";
    const std::string driver_dir = dir.string() + "/";
    // Turnip's own switches (Mesa's TU_DEBUG, read when the instance is made; the phone's own driver ignores it), chosen in Settings >
    // GPU driver > Driver workarounds (GpuDriver.java writes tu_debug.txt; an empty file is the driver's own behaviour). With Turnip on
    // the Adreno 740 the picture came out in black columns over the scene and the touch buttons alike (96 pixels apart with tiled
    // drawing, 16 of every 48 without it, a whole frame now and then), so the default turns off everything that can cause such
    // stripes. kDefaultTuDebug must match GpuDriver.OPTION_FLAGS[DEFAULT_OPTION].
    static constexpr const char* kDefaultTuDebug = "sysmem,noubwc,nolrz,flushall";
    std::string tu_debug = kDefaultTuDebug;
    if (std::ifstream flags{dir / "tu_debug.txt"}) {
        tu_debug.clear();
        std::getline(flags, tu_debug);
    }
    // only Mesa's flag words (letters, digits, '_' and commas)
    std::erase_if(tu_debug, [](char c) { return !(std::isalnum(static_cast<unsigned char>(c)) || c == '_' || c == ','); });
    if (!tu_debug.empty()) {
        setenv("TU_DEBUG", tu_debug.c_str(), 1);
    } else {
        unsetenv("TU_DEBUG");
    }
    void* handle = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, hooks.c_str(),
                                              driver_dir.c_str(), library.c_str(), nullptr, nullptr);
    if (!handle) {
        error = "adrenotools could not load the driver (an old Android version, or a device it does not support)";
        return nullptr;
    }
    void* get_proc = dlsym(handle, "vkGetInstanceProcAddr");
    if (!get_proc) {
        dlclose(handle);
        error = "the custom driver has no vkGetInstanceProcAddr";
        return nullptr;
    }
    g_gpu_driver = handle;
    const char* tu_debug = std::getenv("TU_DEBUG");
    LogInfo("android: custom GPU driver {} (TU_DEBUG={})", library, tu_debug ? tu_debug : "");
    return get_proc;
}

bool CustomGpuDriverActive() { return g_gpu_driver != nullptr; }

void CloseCustomGpuDriver() {
    if (g_gpu_driver) dlclose(g_gpu_driver);
    g_gpu_driver = nullptr;
}

void EndGpuDriverTrial() {
    std::error_code ec;
    if (g_gpu_driver) std::filesystem::remove(GpuDriverDir() / ".trial", ec);
}

}

#else

namespace pt::android {

std::filesystem::path RuntimeDir() { return {}; }
bool PrepareRuntime() { return true; }
bool ApplyDeviceDefaults(AppSettings&, const std::filesystem::path&) { return false; }

}

#endif

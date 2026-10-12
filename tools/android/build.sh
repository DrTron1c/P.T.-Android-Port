#!/usr/bin/env bash
# Builds the Android app (docs/android.md):  tools/android/build.sh native | apk | all
#   native: the game and SDL for arm64 with the NDK, then stages libraries, shaders and fonts into android/app
#   apk:    Gradle packs the staged files into dist/P.T.PC.Port-Android-arm64.apk
# Needs the Android NDK (ANDROID_NDK_HOME), CMake 3.28+, Ninja, Python 3; the apk step also JDK 17 and Gradle 8.7+.
set -euo pipefail
cd "$(dirname "$0")/../.."

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_ROOT:-}}}"
BUILD=build/android

native() {
  if [ -z "$NDK" ] || [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
    echo "build.sh: set ANDROID_NDK_HOME to the Android NDK" >&2
    exit 1
  fi
  cmake -G Ninja -S . -B "$BUILD" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-29 \
    -DANDROID_STL=c++_static \
    -DPT_VERSION_OVERRIDE="$(tr -d '[:space:]' < android/version.txt)"
  # -k 0: every error of a build in one go (the CI log is read after the run, not during it)
  cmake --build "$BUILD" --target pt pt_shaders --parallel -- -k 0
  python3 tools/android/stage.py --build "$BUILD"
}

apk() {
  # PT_VERSION_CODE: the CI's run number, the APK's versionCode (unset: 1); the version people see is android/version.txt
  (cd android && gradle --no-daemon --console=plain assembleRelease -PptVersionCode="${PT_VERSION_CODE:-1}")
  mkdir -p dist
  cp android/app/build/outputs/apk/release/app-release.apk dist/P.T.PC.Port-Android-arm64.apk
  ls -l dist/P.T.PC.Port-Android-arm64.apk
}

case "${1:-all}" in
  native) native ;;
  apk) apk ;;
  all) native; apk ;;
  *) echo "usage: $0 native|apk|all" >&2; exit 2 ;;
esac

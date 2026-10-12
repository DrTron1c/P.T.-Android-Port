#!/usr/bin/env python3
"""Stage the Android app's inputs from an NDK build (docs/android.md).

Puts into android/app/src/main:
  jniLibs/<abi>/libmain.so, libSDL3.so   the game and SDL, built by CMake
  jniLibs/<abi>/libmain_hook.so, libhook_impl.so
                                         adrenotools' hook libraries, which the custom GPU driver support loads by path
  java/org/libsdl/app/*.java             SDL's activity, taken from the SDL source CMake fetched (it must match libSDL3.so)
  assets/shaders, assets/fonts, assets/touch
                                         what the game reads at run time next to its executable on the desktop (touch: the on-screen
                                         controls' picture, assets/touch/hud.png)
  assets/pt_assets.txt                   "<size> <sha256> <path>" per asset; the game copies the assets out of the APK to
                                         its private storage when this list differs from the one it copied last
"""
import argparse
import hashlib
import shutil
import sys
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build", type=Path, required=True, help="the CMake build folder (build/android)")
    parser.add_argument("--out", type=Path, default=None, help="android/app/src/main")
    parser.add_argument("--abi", default="arm64-v8a")
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[2]
    build = args.build if args.build.is_absolute() else root / args.build
    out = args.out or root / "android" / "app" / "src" / "main"

    main_lib = build / "libmain.so"
    sdl_libs = sorted(build.rglob("libSDL3.so"))
    if not main_lib.is_file():
        print(f"stage: {main_lib} is missing (build the 'pt' target first)", file=sys.stderr)
        return 1
    if not sdl_libs:
        print(f"stage: libSDL3.so not found under {build}", file=sys.stderr)
        return 1

    jni = out / "jniLibs" / args.abi
    shutil.rmtree(out / "jniLibs", ignore_errors=True)
    jni.mkdir(parents=True)
    shutil.copy2(main_lib, jni / "libmain.so")
    shutil.copy2(sdl_libs[0], jni / "libSDL3.so")
    for name in ("libmain_hook.so", "libhook_impl.so"):
        hook = next(iter(sorted(build.rglob(name))), None)
        if hook is None:
            print(f"stage: {name} not found under {build} (build the 'main_hook' and 'hook_impl' targets)", file=sys.stderr)
            return 1
        shutil.copy2(hook, jni / name)

    sdl_java = next(iter(sorted(build.rglob("android-project/app/src/main/java/org/libsdl/app"))), None)
    if sdl_java is None or not sdl_java.is_dir():
        print("stage: SDL's Java sources (android-project/app/src/main/java/org/libsdl/app) not found in the build", file=sys.stderr)
        return 1
    java_out = out / "java" / "org" / "libsdl" / "app"
    shutil.rmtree(java_out, ignore_errors=True)
    java_out.mkdir(parents=True)
    for source in sdl_java.glob("*.java"):
        shutil.copy2(source, java_out / source.name)

    assets = out / "assets"
    shutil.rmtree(assets, ignore_errors=True)
    (assets / "shaders").mkdir(parents=True)
    (assets / "fonts").mkdir(parents=True)
    (assets / "touch").mkdir(parents=True)
    shaders = sorted((build / "shaders").glob("*.spv"))
    if not shaders:
        print("stage: no compiled shaders in the build (build the 'pt_shaders' target)", file=sys.stderr)
        return 1
    for shader in shaders:
        shutil.copy2(shader, assets / "shaders" / shader.name)
    for font in sorted((root / "assets" / "fonts").iterdir()):
        if font.is_file():
            shutil.copy2(font, assets / "fonts" / font.name)

    for picture in sorted((root / "assets" / "touch").iterdir()):
        if picture.is_file():
            shutil.copy2(picture, assets / "touch" / picture.name)

    lines = []
    for path in sorted(p for p in assets.rglob("*") if p.is_file() and p.name != "pt_assets.txt"):
        relative = path.relative_to(assets).as_posix()
        lines.append(f"{path.stat().st_size} {sha256(path)} {relative}")
    (assets / "pt_assets.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"stage: {len(shaders)} shaders, {len(lines) - len(shaders)} other assets, "
          f"libmain.so {main_lib.stat().st_size / 1e6:.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())

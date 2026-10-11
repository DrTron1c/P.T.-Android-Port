#!/usr/bin/env bash
# Tests PkgExtractor.java (the fake-PKG import of the Android app, docs/android.md) on a desktop JDK:
#   tools/android/pkgtest/run.sh [work dir]
# Needs python3 with the `cryptography` package, and a JDK. The packages it builds are synthetic (random bytes), not game data.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$here/../../.."
work="${1:-$(mktemp -d)}"
mkdir -p "$work/classes"
python3 -I "$here/make_test_pkgs.py" "$work/pkgs"
javac -Xlint:all -d "$work/classes" "$repo/android/app/src/main/java/com/ptport/android/PkgExtractor.java" "$here/PkgExtractorTest.java"
java -cp "$work/classes" com.ptport.android.PkgExtractorTest "$work/pkgs"

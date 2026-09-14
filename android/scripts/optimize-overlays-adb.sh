#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:?usage: optimize-overlays-adb.sh adb-serial}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
NDK_VERSION="$(sed -n 's/^crash2\.ndkVersion=//p' "$ROOT/android/gradle.properties")"
NDK_BIN="$ANDROID_HOME/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin"
ADB="$ANDROID_HOME/platform-tools/adb"
FRAMEWORK="$ROOT/_build/tomba2-android-framework/psxrecomp-v4"
RECOMPILER_BUILD="$ROOT/_build/psxrecomp-builder-host"
WORK="$ROOT/_build/android-overlay-cache"
REMOTE="/storage/emulated/0/Android/data/io.github.crash2recomp/files"

"$ROOT/android/scripts/fetch-framework.sh"
mkdir -p "$WORK/input" "$WORK/cache"
"$ADB" -s "$SERIAL" pull "$REMOTE/overlay_captures.json" "$WORK/input/overlay_captures.json"
"$ADB" -s "$SERIAL" pull "$REMOTE/build/project/game.toml" "$WORK/input/game.toml"

cmake -S "$FRAMEWORK/recompiler" -B "$RECOMPILER_BUILD" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF
cmake --build "$RECOMPILER_BUILD" --target psxrecomp-game -j"${JOBS:-$(nproc)}"

PSX_CACHE_ARCH_ABI=linux-arm64 python3 "$FRAMEWORK/tools/compile_overlays.py" \
    --captures "$WORK/input/overlay_captures.json" \
    --game-toml "$WORK/input/game.toml" \
    --recompiler "$RECOMPILER_BUILD/psxrecomp-game" \
    --runtime-include "$FRAMEWORK/runtime/include" \
    --project-root "$FRAMEWORK" \
    --out-dir "$WORK/cache" \
    --gcc "$NDK_BIN/aarch64-linux-android28-clang" \
    --compiler gcc --cps

"$ADB" -s "$SERIAL" shell mkdir -p "$REMOTE/cache"
"$ADB" -s "$SERIAL" push "$WORK/cache/." "$REMOTE/cache/"
"$ADB" -s "$SERIAL" shell am force-stop io.github.crash2recomp
echo "Overlay cache installed. Launch Crash 2 Recompiled and test the same scene again."

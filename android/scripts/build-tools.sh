#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
NDK_VERSION="$(sed -n 's/^crash2\.ndkVersion=//p' "$ROOT/android/gradle.properties")"
NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
NDK_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
FRAMEWORK="$ROOT/_build/tomba2-android-framework/psxrecomp-v4"
RECOMP_BUILD="$ROOT/_build/psxrecomp-builder-android-tomba"
HOST_RECOMP_BUILD="$ROOT/_build/psxrecomp-builder-host"
RUNTIME_BUILD="$ROOT/_build/runtime-bundle-android"
TINYCC="$ROOT/_build/tinycc-android"
TCC_BUILD="$ROOT/_build/tinycc-build-android"
JNI="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
ASSETS="$ROOT/android/app/src/main/assets/runtime-toolchain"
STAMP="$ASSETS/built.txt"
RUNTIME_REV="$(find "$ROOT/android/native-runtime" -type f -print0 | sort -z | xargs -0 cksum | cksum | awk '{print $1}')"
PATCH_REV="$(cksum "$ROOT/tuning/patches/0200-android-builder-layout.patch" "$ROOT/tuning/patches/0201-tinycc-android-cacheflush.patch" "$ROOT/tuning/patches/0202-android-runtime-settings.patch" | cksum | awk '{print $1}')"
SCRIPT_REV="$(cksum "$0" | awk '{print $1}')"
WANT="psxrecomp-android=bc5b8561 tinycc=0fb54300 ndk=$NDK_VERSION runtime=$RUNTIME_REV patches=$PATCH_REV script=$SCRIPT_REV"

"$ROOT/android/scripts/fetch-framework.sh"
[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "error: Android NDK $NDK_VERSION is missing" >&2
    exit 1
}
if [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$WANT" ] \
        && [ -x "$JNI/libpsxrecomp.so" ] && [ -x "$JNI/libtcc-bin.so" ] \
        && [ -f "$JNI/libmain.so" ]; then
    exit 0
fi

cmake -S "$FRAMEWORK/recompiler" -B "$RECOMP_BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF
cmake --build "$RECOMP_BUILD" --target psxrecomp psxrecomp-game psxrecomp-bios \
    -j"${JOBS:-$(nproc)}"

if [ ! -f "$FRAMEWORK/generated/OpenBIOS_full.c" ]; then
    cmake -S "$FRAMEWORK/recompiler" -B "$HOST_RECOMP_BUILD" -G Ninja \
        -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF
    cmake --build "$HOST_RECOMP_BUILD" --target psxrecomp-bios -j"${JOBS:-$(nproc)}"
    mkdir -p "$FRAMEWORK/generated"
    "$HOST_RECOMP_BUILD/psxrecomp-bios" \
        --config "$FRAMEWORK/bios/OpenBIOS.toml" \
        --rom "$FRAMEWORK/bios/openbios.bin" \
        --out-dir "$FRAMEWORK/generated"
fi

cmake -S "$ROOT/android/native-runtime" -B "$RUNTIME_BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
    -DPSXRECOMP_ROOT="$FRAMEWORK" -DPSX_SDL3_FETCH=ON
cmake --build "$RUNTIME_BUILD" --target psx-runtime -j"${JOBS:-$(nproc)}"

mkdir -p "$TCC_BUILD"
if [ ! -f "$TCC_BUILD/config.mak" ]; then
    (cd "$TCC_BUILD" && "$TINYCC/configure" \
        --cc="$NDK_BIN/aarch64-linux-android28-clang" --ar="$NDK_BIN/llvm-ar" \
        --cpu=arm64 --targetos=Android --sysroot=/ --prefix=/ \
        --config-backtrace=no --config-bcheck=no --config-predefs=no \
        --extra-cflags=-fPIE --extra-ldflags=-pie)
fi
make -C "$TCC_BUILD" -j"${JOBS:-$(nproc)}" tcc libtcc.so LIBS='-lm -ldl'
make -C "$TCC_BUILD/lib" arm64-libtcc1-usegcc=yes \
    CC="$NDK_BIN/aarch64-linux-android28-clang" AR="$NDK_BIN/llvm-ar"

mkdir -p "$JNI"
cp "$RECOMP_BUILD/psxrecomp" "$JNI/libpsxrecomp.so"
cp "$RECOMP_BUILD/psxrecomp-game" "$JNI/libpsxrecomp-game.so"
cp "$RECOMP_BUILD/psxrecomp-bios" "$JNI/libpsxrecomp-bios.so"
cp "$TCC_BUILD/tcc" "$JNI/libtcc-bin.so"
cp "$TCC_BUILD/libtcc.so" "$JNI/libtcc.so"
cp "$RUNTIME_BUILD/libmain.so" "$JNI/libmain.so"
"$NDK_BIN/llvm-strip" "$JNI"/*.so

rm -rf "$ASSETS"
mkdir -p "$ASSETS/framework" "$ASSETS/tcc" "$ASSETS/sysroot"
for dir in bios cmake lib mods recompiler/lib recompiler/seeds runtime third_party; do
    destination="$ASSETS/framework/$(dirname "$dir")"
    mkdir -p "$destination"
    [ ! -e "$FRAMEWORK/$dir" ] || cp -RL "$FRAMEWORK/$dir" "$destination/"
done
# Anchor for find_project_root(): the desktop framework tree anchors at its
# root via .gitignore, but aapt strips dotfiles from APK assets, so that file
# never reaches the device. A CMakeLists.txt marker works instead: the config
# loader treats it as a project-root marker, so BIOS seed/rom paths resolve
# under psxrecomp/. Without an anchor the on-device lookup falls through to
# the generated project dir and the BIOS step fails with "cannot open seed
# file". This file is a marker only and carries no build rules.
printf '%s\n' \
    '# Marker only, not part of the build. See build-tools.sh.' \
    '# find_project_root() anchors BIOS seed/rom paths at this directory.' \
    > "$ASSETS/framework/CMakeLists.txt"
mkdir -p "$ASSETS/licenses"
cp "$FRAMEWORK/LICENSE" "$ASSETS/licenses/PSXRecomp-LICENSE.txt"
cp "$FRAMEWORK/THIRD_PARTY_ATTRIBUTION.md" "$ASSETS/licenses/PSXRecomp-THIRD-PARTY.md"
cp "$TINYCC/COPYING" "$ASSETS/licenses/TinyCC-COPYING.txt"
cp -RL "$TINYCC/include" "$ASSETS/tcc/"
cp "$TCC_BUILD/libtcc1.a" "$ASSETS/tcc/"
cp -RL "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include" "$ASSETS/sysroot/"
mkdir -p "$ASSETS/sysroot/lib/aarch64-linux-android"
cp -RL "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/28" \
    "$ASSETS/sysroot/lib/aarch64-linux-android/"
mkdir -p "$ASSETS/crash2"
cp "$ROOT/_build/Crash2Recomp/seeds/functions.txt" "$ASSETS/crash2/functions.txt"
printf '%s\n' "$WANT" > "$STAMP"

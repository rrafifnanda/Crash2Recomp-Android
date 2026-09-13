#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FRAMEWORK_SOURCE="$ROOT/_build/tomba2-android-framework"
FRAMEWORK="$FRAMEWORK_SOURCE/psxrecomp-v4"
TINYCC="$ROOT/_build/tinycc-android"
FRAMEWORK_COMMIT=bc5b8561e655bd32a6c2c3f6753cc2d47df83014
TINYCC_COMMIT=0fb54300b56512754221d80adda85ddb9815bceb

fetch_at() {
    local url="$1" path="$2" commit="$3"
    if [ ! -d "$path/.git" ]; then
        git clone "$url" "$path"
        git -C "$path" checkout --detach "$commit"
    fi
    [ "$(git -C "$path" rev-parse HEAD)" = "$commit" ] || {
        echo "error: $path is not pinned at $commit" >&2
        exit 1
    }
}

apply_once() {
    local tree="$1" patch="$2"
    if patch --reverse --dry-run -p1 -d "$tree" < "$patch" >/dev/null 2>&1; then
        return
    fi
    patch --dry-run -p1 -d "$tree" < "$patch" >/dev/null
    patch -p1 -d "$tree" < "$patch"
}

fetch_at https://github.com/igawa6/Tomba2RecompDS.git "$FRAMEWORK_SOURCE" "$FRAMEWORK_COMMIT"
fetch_at https://github.com/TinyCC/tinycc.git "$TINYCC" "$TINYCC_COMMIT"
apply_once "$FRAMEWORK" "$ROOT/tuning/patches/0200-android-builder-layout.patch"
apply_once "$FRAMEWORK" "$ROOT/tuning/patches/0202-android-runtime-settings.patch"
apply_once "$TINYCC" "$ROOT/tuning/patches/0201-tinycc-android-cacheflush.patch"

#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?set ANDROID_HOME}"
: "${JAVA_HOME:?set JAVA_HOME}"
test -x "$JAVA_HOME/bin/java"
test -d "$ANDROID_HOME/ndk/28.2.13676358"
test -d "$ANDROID_HOME/platforms/android-36"

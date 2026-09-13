#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"

"$ROOT/android/scripts/check-env.sh"
(cd "$ROOT/android" && ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleRelease)
APK="$ROOT/android/app/build/outputs/apk/release/app-release-unsigned.apk"
"$ROOT/android/scripts/audit-apk.sh" "$APK"
echo "APK: $APK"

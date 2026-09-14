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

# Local debug-sign so the APK installs on a device. The key lives outside the
# repo (the standard ~/.android debug keystore); nothing is committed and no
# release/store signing happens here.
BT="$(ls -d "$ANDROID_HOME/build-tools/"*/ | sort -V | tail -1)"
SIGNED="$ROOT/android/app/build/outputs/apk/release/app-release-signed.apk"
if [ -f "$HOME/.android/debug.keystore" ] && [ -n "$BT" ]; then
    rm -f "$SIGNED" "$SIGNED.idsig"
    "$BT/zipalign" -f 4 "$APK" "${SIGNED%.apk}-aligned.apk"
    "$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" \
        --ks-pass pass:android --out "$SIGNED" "${SIGNED%.apk}-aligned.apk"
    rm -f "${SIGNED%.apk}-aligned.apk"
    "$BT/apksigner" verify "$SIGNED"
    echo "Signed APK (local debug key): $SIGNED"
else
    echo "warning: no local debug keystore; APK is unsigned and will not install" >&2
fi

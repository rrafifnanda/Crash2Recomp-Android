#!/usr/bin/env bash
set -euo pipefail

APK="${1:?usage: audit-apk.sh path/to.apk}"
[ -f "$APK" ] || { echo "error: APK not found: $APK" >&2; exit 1; }

bad=0
while IFS= read -r entry; do
    lower="${entry,,}"
    case "$lower" in
        *openbios.bin) ;;
        *.cue|*.bin|*.iso|*.chd|*.img|*.ccd|*.sub|*.m3u|*scus_941.54*|*_full.c|*_full_*.c|*_dispatch.c|*libcrash2_game.so*|*overlay_captures*|*overlay-cache*)
            echo "error: prohibited game-derived APK entry: $entry" >&2
            bad=1
            ;;
    esac
done < <(unzip -Z1 "$APK")

[ "$bad" -eq 0 ] || exit 1
echo "APK audit passed: no disc or generated game code"

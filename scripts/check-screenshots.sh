#!/usr/bin/env bash
# Checks App Store screenshots before they are uploaded, rather than after.
#
# App Store Connect rejects a set for reasons it states only once the upload has
# failed: a size that is off by a pixel, an alpha channel a screen recorder left
# behind, a file that is a JPEG wearing a .png. Each is a minute to check here
# and a round trip to discover there.
#
# Validates only. Nothing is resized and nothing is recompressed — a screenshot
# scaled to fit is a screenshot of the wrong thing, and silently flattening one
# would hide the very problem worth seeing.
#
#   bash scripts/check-screenshots.sh screenshots/
set -euo pipefail

DIR="${1:?usage: check-screenshots.sh <directory of .png>}"

# Which slot each size belongs to, because "the right size" is only half the
# question and the other half is where it goes. App Store Connect lists several
# slots and rejects a drop into the wrong one with a message about sizes, which
# reads as the file being wrong when it is the slot.
#
# Apple derives every smaller size from 6.9", so that is the only one to fill.
declare -a SIZES=(
    "1320x2868"
    "2868x1320"
    "1290x2796"
    "2796x1290"
    "1242x2688"
    "2688x1242"
    "1284x2778"
    "2778x1284"
)
declare -a SLOTS=(
    '6.9" Display'
    '6.9" Display (landscape)'
    '6.9" Display'
    '6.9" Display (landscape)'
    '6.5" Display'
    '6.5" Display (landscape)'
    '6.5" Display'
    '6.5" Display (landscape)'
)

slot_for() {
    local i
    for i in "${!SIZES[@]}"; do
        if [ "${SIZES[$i]}" = "$1" ]; then
            printf '%s' "${SLOTS[$i]}"
            return 0
        fi
    done
    return 1
}

command -v sips >/dev/null || { echo "sips not found — run this on macOS"; exit 1; }

shopt -s nullglob
files=("$DIR"/*.png "$DIR"/*.PNG)
if [ ${#files[@]} -eq 0 ]; then
    echo "no .png files in ${DIR}"
    exit 1
fi

bad=0
for f in "${files[@]}"; do
    w=$(sips -g pixelWidth "$f" 2>/dev/null | awk '/pixelWidth/{print $2}')
    h=$(sips -g pixelHeight "$f" 2>/dev/null | awk '/pixelHeight/{print $2}')
    size="${w}x${h}"

    slot="$(slot_for "$size" || true)"

    # `file` names the real format; an extension does not.
    kind=$(file -b --mime-type "$f")
    alpha=$(sips -g hasAlpha "$f" 2>/dev/null | awk '/hasAlpha/{print $2}')

    status="ok"
    [ -n "$slot" ] || { status="SIZE"; bad=1; }
    [ "$kind" = "image/png" ] || { status="$status FORMAT($kind)"; bad=1; }
    # Apple wants screenshots flattened. A simulator capture has no alpha; a
    # screenshot that has been through an editor often does.
    [ "$alpha" = "yes" ] && { status="$status ALPHA"; bad=1; }

    printf '%-10s %-12s %-26s %s\n' \
        "$status" "$size" "${slot:-no App Store slot}" "$(basename "$f")"
done

if [ "$bad" -ne 0 ]; then
    echo
    echo 'Accepted: 1320x2868 or 1290x2796 (6.9"), 1242x2688 or 1284x2778 (6.5")'
    echo "Capture with:  xcrun simctl io booted screenshot shot.png"
    exit 1
fi

echo
echo "${#files[@]} screenshots, all uploadable."
echo
echo "Drop them in the slot named above — App Store Connect lists several, and"
echo "a drop into the wrong one is refused with a message about sizes."

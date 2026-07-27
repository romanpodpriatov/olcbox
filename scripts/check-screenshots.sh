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

# The one size Apple asks for as of 2026: the 6.9" iPhone. Anything else it
# derives itself. Both orientations, and the 6.7" pair still accepted for it.
ACCEPTED=(
    "1320x2868"  # 6.9" portrait
    "2868x1320"  # 6.9" landscape
    "1290x2796"  # 6.7" portrait, still taken for the 6.9" slot
    "2796x1290"  # 6.7" landscape
)

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

    ok=""
    for accepted in "${ACCEPTED[@]}"; do
        [ "$size" = "$accepted" ] && ok="yes"
    done

    # `file` names the real format; an extension does not.
    kind=$(file -b --mime-type "$f")
    alpha=$(sips -g hasAlpha "$f" 2>/dev/null | awk '/hasAlpha/{print $2}')

    status="ok"
    [ -n "$ok" ] || { status="SIZE"; bad=1; }
    [ "$kind" = "image/png" ] || { status="$status FORMAT($kind)"; bad=1; }
    # Apple wants screenshots flattened. A simulator capture has no alpha; a
    # screenshot that has been through an editor often does.
    [ "$alpha" = "yes" ] && { status="$status ALPHA"; bad=1; }

    printf '%-10s %-12s %s\n' "$status" "$size" "$(basename "$f")"
done

if [ "$bad" -ne 0 ]; then
    echo
    echo "Accepted sizes: ${ACCEPTED[*]}"
    echo "Capture with:  xcrun simctl io booted screenshot shot.png"
    exit 1
fi

echo
echo "${#files[@]} screenshots, all uploadable."

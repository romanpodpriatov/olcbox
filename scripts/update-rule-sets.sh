#!/usr/bin/env bash
# Refreshes the rule-sets the app bundles, from SagerNet's `rule-set` branches.
#
# Three files: v2fly's geosite:category-ru and geosite:tld-ru, and geoip:ru, all
# compiled to sing-box's binary format. Pinned to a commit of each branch so a
# re-run reproduces the same bytes; pass GEOSITE_REF / GEOIP_REF to move them.
# Writes scripts/rule-sets.lock with what it fetched. The sha256 lines there
# must then be copied into RuleSets.kt — RuleSetsTest checks the bundle against
# those constants, so a refresh that forgets the copy fails the build rather
# than shipping unreviewed lists.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$root/sharedUI/src/commonMain/composeResources/files/rules"
lock="$root/scripts/rule-sets.lock"

branch_head() {
  curl -fsSL -H 'User-Agent: olcbox' "https://api.github.com/repos/SagerNet/$1/branches/rule-set" \
    | python3 -c 'import sys, json; print(json.load(sys.stdin)["commit"]["sha"])'
}

GEOSITE_REF="${GEOSITE_REF:-$(branch_head sing-geosite)}"
GEOIP_REF="${GEOIP_REF:-$(branch_head sing-geoip)}"

fetch() { # repo ref file
  curl -fsSL "https://raw.githubusercontent.com/SagerNet/$1/$2/$3" -o "$dest/$3"
  echo "fetched $3 @ $1/$2"
}

mkdir -p "$dest"
fetch sing-geosite "$GEOSITE_REF" geosite-category-ru.srs
fetch sing-geosite "$GEOSITE_REF" geosite-tld-ru.srs
fetch sing-geoip "$GEOIP_REF" geoip-ru.srs

{
  echo "# Written by scripts/update-rule-sets.sh. Do not edit by hand."
  echo "sing-geosite $GEOSITE_REF"
  echo "sing-geoip $GEOIP_REF"
  (cd "$dest" && sha256sum geosite-category-ru.srs geosite-tld-ru.srs geoip-ru.srs)
} > "$lock"

echo
cat "$lock"
echo
echo "Copy the sha256 values into sharedUI/src/commonMain/kotlin/org/olcbox/app/net/RuleSets.kt."

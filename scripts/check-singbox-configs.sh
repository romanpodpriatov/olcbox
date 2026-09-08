#!/usr/bin/env bash
# Runs `sing-box check` on every config the dump tests wrote, with the pinned
# release. Locally: SINGBOX_BIN=/path/to/sing-box scripts/check-singbox-configs.sh
# after `./gradlew :sharedUI:jvmTest --tests "org.olcbox.app.net.*"`. CI does the
# same in pr-checks. A shape that passes the Kotlin tests and fails here is a
# schema drift — exactly the class of bug that shipped an iOS tunnel which
# connected and carried nothing.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bin="${SINGBOX_BIN:-sing-box}"
"$bin" version | head -1

shopt -s nullglob
configs=("$root"/sharedUI/build/singbox-configs/*.json)
if [ "${#configs[@]}" -eq 0 ]; then
  echo "no configs under sharedUI/build/singbox-configs — run the jvm net tests first" >&2
  exit 1
fi

failed=0
for config in "${configs[@]}"; do
  if "$bin" check -c "$config"; then
    echo "ok   $(basename "$config")"
  else
    echo "FAIL $(basename "$config")"
    failed=1
  fi
done
exit "$failed"

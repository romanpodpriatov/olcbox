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

# `check` builds the box and stops; sing-box validates some things only when
# it starts — a DNS server's detour, for one, which is how a config passed
# every check here and then refused to start on a phone. So every shape that
# needs no tun (a tun needs privileges this runner may not have) is started
# for a few seconds as well: it either comes up and is killed by the timeout,
# or it names what is wrong with it.
if command -v timeout >/dev/null 2>&1; then
  for config in "${configs[@]}"; do
    if grep -q '"type":"tun"' "$config"; then
      continue
    fi
    set +e
    output="$(timeout 4 "$bin" run -c "$config" 2>&1)"
    code=$?
    set -e
    if [ "$code" -eq 124 ] || [ "$code" -eq 0 ]; then
      echo "runs $(basename "$config")"
    else
      echo "FAIL $(basename "$config") did not start (exit $code):"
      echo "$output" | tail -5
      failed=1
    fi
  done
else
  echo "no 'timeout' here; the start-up check was skipped (it runs in CI)"
fi
exit "$failed"

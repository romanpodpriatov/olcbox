#!/usr/bin/env python3
"""Wait for Apple's notarisation verdict without holding a macOS runner.

`notarytool --wait` is convenient and expensive: it parks a 10x-billed macOS
runner in Apple's queue, which has taken anywhere from two to twenty minutes
here. The verdict itself is an ordinary REST resource, authenticated with the
same App Store Connect key, so waiting can happen on a 1x runner and the macOS
machine only comes back for the few seconds stapling needs.

Usage:
    notary-wait.py <submission-id>

Environment:
    MACOS_NOTARY_KEY_BASE64   base64 of the .p8 private key
    MACOS_NOTARY_KEY_ID       10-character key id
    MACOS_NOTARY_ISSUER_ID    issuer uuid

Exits 0 when Apple accepts, 1 otherwise — printing the rejection reasons, which
are what a bare "Invalid" never tells you.
"""
import base64
import json
import os
import sys
import time
import urllib.request

import jwt  # PyJWT

API = "https://appstoreconnect.apple.com/notary/v2"

# Apple's own guidance is that most submissions finish within 15 minutes; the
# ceiling here is generous because a 1x runner waiting is nearly free, and a
# false timeout costs a whole rebuild.
TIMEOUT_SECONDS = 45 * 60
POLL_SECONDS = 20


def _token() -> str:
    key_b64 = os.environ["MACOS_NOTARY_KEY_BASE64"]
    private_key = base64.b64decode("".join(key_b64.split())).decode()
    now = int(time.time())
    return jwt.encode(
        {
            "iss": os.environ["MACOS_NOTARY_ISSUER_ID"],
            "iat": now,
            # Short-lived on purpose: the token is regenerated per request, so a
            # long poll never carries one that has gone stale mid-wait.
            "exp": now + 15 * 60,
            "aud": "appstoreconnect-v1",
        },
        private_key,
        algorithm="ES256",
        headers={"kid": os.environ["MACOS_NOTARY_KEY_ID"], "typ": "JWT"},
    )


def _get(path: str):
    request = urllib.request.Request(
        f"{API}/{path}", headers={"Authorization": f"Bearer {_token()}"}
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def _print_reasons(submission_id: str) -> None:
    """The verdict names neither the file nor the fault; this does."""
    try:
        location = _get(f"submissions/{submission_id}/logs")
        url = location["data"]["attributes"]["developerLogUrl"]
        with urllib.request.urlopen(url, timeout=60) as response:
            log = json.load(response)
        print(json.dumps(log, indent=2)[:8000])
    except Exception as exc:  # the verdict matters more than the explanation
        print(f"could not fetch the notary log: {exc}")


def main() -> int:
    submission_id = sys.argv[1]
    deadline = time.time() + TIMEOUT_SECONDS
    status = "Unknown"

    while time.time() < deadline:
        payload = _get(f"submissions/{submission_id}")
        status = payload["data"]["attributes"]["status"]
        print(f"status: {status}", flush=True)

        if status == "Accepted":
            return 0
        if status in ("Invalid", "Rejected"):
            print("== why Apple rejected it ==")
            _print_reasons(submission_id)
            return 1

        time.sleep(POLL_SECONDS)

    print(f"timed out after {TIMEOUT_SECONDS}s, last status: {status}")
    return 1


if __name__ == "__main__":
    sys.exit(main())

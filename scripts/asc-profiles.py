#!/usr/bin/env python3
"""Make sure an App Store provisioning profile exists for each bundle id, and
install it where xcodebuild looks.

Run by release.yml before the iOS archive. Manual signing on a runner needs the
profiles on disk, and keeping them as secrets means someone regenerates them by
hand every time the certificate is renewed. Instead the profiles are made by the
App Store Connect API from the team's distribution certificates, by name:

    scripts/asc-profiles.py --key-id K --issuer-id I --key-path AuthKey.p8 \
        --profile "ProofKit App Store=org.proofkit.app" \
        --profile "ProofKit PacketTunnel App Store=org.proofkit.app.PacketTunnel"

A profile that exists and is ACTIVE is reused. One that is INVALID — the
certificate it was made with is gone or expired — is deleted and made again.
Every current Apple Distribution certificate of the team goes into a new
profile, so it matches whichever one the runner has imported.

The API key must be able to manage certificates and profiles: Admin, or App
Manager. A Developer key can read but not create, and the failure then names
the endpoint and Apple's reason rather than a missing file at archive time.

Needs PyJWT and cryptography, as scripts/notary-wait.py does.
"""

import argparse
import base64
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.request

API = "https://api.appstoreconnect.apple.com/v1"

# Xcode 16 moved the directory; older Xcodes and some tooling still read the
# old one. Writing both costs nothing and spares a guess about the runner.
PROFILE_DIRS = (
    "~/Library/Developer/Xcode/UserData/Provisioning Profiles",
    "~/Library/MobileDevice/Provisioning Profiles",
)


class Client:
    """The few calls this needs, over urllib, with a fresh short-lived token."""

    def __init__(self, key_id: str, issuer_id: str, private_key: str):
        self.key_id = key_id
        self.issuer_id = issuer_id
        self.private_key = private_key

    def _token(self) -> str:
        import jwt  # PyJWT

        now = int(time.time())
        return jwt.encode(
            {"iss": self.issuer_id, "iat": now, "exp": now + 15 * 60, "aud": "appstoreconnect-v1"},
            self.private_key,
            algorithm="ES256",
            headers={"kid": self.key_id, "typ": "JWT"},
        )

    def request(self, method: str, path: str, body=None):
        url = path if path.startswith("http") else API + path
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", f"Bearer {self._token()}")
        req.add_header("Accept", "application/json")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                raw = resp.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            raise SystemExit(f"{method} {path} -> HTTP {e.code}: {detail}") from None

    def get(self, path: str):
        return self.request("GET", path)

    def get_all(self, path: str):
        """Follow `links.next` so a team with many certificates is read whole."""
        items = []
        while path:
            page = self.request("GET", path)
            items.extend(page.get("data", []))
            path = page.get("links", {}).get("next")
        return items

    def post(self, path: str, body):
        return self.request("POST", path, body)

    def delete(self, path: str):
        return self.request("DELETE", path)


def current_distribution_certificates(client) -> list:
    """Every Apple Distribution certificate that has not expired.

    `DISTRIBUTION` is the type Xcode issues today (one certificate for iOS and
    macOS); `IOS_DISTRIBUTION` is the older kind. Both sign an App Store build.
    """
    now = dt.datetime.now(dt.timezone.utc)
    certs = []
    for kind in ("DISTRIBUTION", "IOS_DISTRIBUTION"):
        for cert in client.get_all(f"/certificates?filter[certificateType]={kind}&limit=200"):
            expires = cert["attributes"].get("expirationDate")
            if expires and dt.datetime.fromisoformat(expires.replace("Z", "+00:00")) <= now:
                continue
            certs.append(cert)
    return certs


def bundle_id_resource(client, identifier: str) -> dict:
    # The filter is a match on the identifier, not necessarily an exact one:
    # asking for org.proofkit.app can return org.proofkit.app.PacketTunnel too.
    candidates = client.get_all(f"/bundleIds?filter[identifier]={identifier}&filter[platform]=IOS&limit=200")
    for candidate in candidates:
        if candidate["attributes"].get("identifier") == identifier:
            return candidate
    raise SystemExit(
        f"no App ID {identifier} in this team; register it under Certificates, Identifiers & Profiles first"
    )


def ensure_capability(client, identifier: str, capability_type: str) -> bool:
    """Enable a capability on the App ID; True when this call enabled it.

    A profile carries the App ID's capabilities as entitlements, so an app that
    starts declaring, say, associated domains needs the App ID to have them
    before its profile is made — otherwise xcodebuild signs nothing and says
    the profile lacks the entitlement. Apple invalidates a profile when its App
    ID gains a capability; the caller remakes it either way.
    """
    bundle = bundle_id_resource(client, identifier)
    # No `limit` here: the relationship endpoint rejects it (PARAMETER_ERROR.ILLEGAL),
    # and get_all follows `links.next` regardless.
    present = client.get_all(f"/bundleIds/{bundle['id']}/bundleIdCapabilities")
    if any(c["attributes"].get("capabilityType") == capability_type for c in present):
        print(f"{identifier} already has {capability_type}")
        return False
    client.post(
        "/bundleIdCapabilities",
        {
            "data": {
                "type": "bundleIdCapabilities",
                "attributes": {"capabilityType": capability_type},
                "relationships": {"bundleId": {"data": {"type": "bundleIds", "id": bundle["id"]}}},
            }
        },
    )
    print(f"enabled {capability_type} on {identifier}")
    return True


def ensure_profile(client, name: str, identifier: str, certificates: list, force: bool = False) -> dict:
    """Return the profile resource, creating or recreating it as needed.

    `force` remakes a profile that looks fine: the App ID under it just gained
    a capability, and a profile made before that carries the old entitlements
    whatever its state says.
    """
    wanted = {c["id"] for c in certificates}
    for existing in client.get_all(f"/profiles?filter[name]={urllib.request.quote(name)}&include=certificates&limit=200"):
        if existing["attributes"].get("name") != name:
            continue
        state = existing["attributes"].get("profileState")
        have = {c["id"] for c in existing.get("relationships", {}).get("certificates", {}).get("data", [])}
        if state == "ACTIVE" and wanted and wanted <= have and not force:
            print(f"reusing {name} ({existing['attributes'].get('uuid')})")
            return existing
        why = "the App ID gained a capability" if force else f"state {state}, certificates {sorted(have)} vs {sorted(wanted)}"
        print(f"replacing {name}: {why}")
        client.delete(f"/profiles/{existing['id']}")

    if not certificates:
        raise SystemExit("no valid Apple Distribution certificate in the team; create one in Xcode first")
    bundle = bundle_id_resource(client, identifier)
    created = client.post(
        "/profiles",
        {
            "data": {
                "type": "profiles",
                "attributes": {"name": name, "profileType": "IOS_APP_STORE"},
                "relationships": {
                    "bundleId": {"data": {"type": "bundleIds", "id": bundle["id"]}},
                    "certificates": {"data": [{"type": "certificates", "id": c["id"]} for c in certificates]},
                },
            }
        },
    )["data"]
    print(f"created {name} ({created['attributes'].get('uuid')}) for {identifier}")
    return created


def install(profile: dict, dirs=PROFILE_DIRS) -> list:
    content = base64.b64decode(profile["attributes"]["profileContent"])
    uuid = profile["attributes"]["uuid"]
    written = []
    for directory in dirs:
        path = os.path.expanduser(directory)
        os.makedirs(path, exist_ok=True)
        target = os.path.join(path, f"{uuid}.mobileprovision")
        with open(target, "wb") as f:
            f.write(content)
        written.append(target)
    return written


def parse_capability_arg(value: str):
    if "=" not in value:
        raise argparse.ArgumentTypeError(f"expected BUNDLE_ID=CAPABILITY_TYPE, got {value!r}")
    identifier, capability = value.split("=", 1)
    return identifier.strip(), capability.strip().upper()


def parse_profile_arg(value: str):
    name, sep, identifier = value.partition("=")
    if not sep or not name.strip() or not identifier.strip():
        raise argparse.ArgumentTypeError(f"expected NAME=BUNDLE_ID, got {value!r}")
    return name.strip(), identifier.strip()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--key-id", required=True)
    parser.add_argument("--issuer-id", required=True)
    parser.add_argument("--key-path", required=True, help="the .p8 private key")
    parser.add_argument("--profile", action="append", required=True, type=parse_profile_arg, metavar="NAME=BUNDLE_ID")
    parser.add_argument(
        "--capability",
        action="append",
        default=[],
        type=parse_capability_arg,
        metavar="BUNDLE_ID=CAPABILITY_TYPE",
        help="enable a capability on the App ID first, e.g. org.proofkit.app=ASSOCIATED_DOMAINS",
    )
    args = parser.parse_args(argv)

    with open(args.key_path) as f:
        client = Client(args.key_id, args.issuer_id, f.read())
    changed = {identifier for identifier, capability in args.capability if ensure_capability(client, identifier, capability)}
    certificates = current_distribution_certificates(client)
    print(f"distribution certificates in the team: {len(certificates)}")
    for name, identifier in args.profile:
        profile = ensure_profile(client, name, identifier, certificates, force=identifier in changed)
        for path in install(profile):
            print(f"installed {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

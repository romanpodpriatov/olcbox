"""Offline checks for asc-profiles.py: the decisions, not the network.

    python3 scripts/test_asc_profiles.py
"""

import base64
import importlib.util
import os
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    "asc_profiles", os.path.join(os.path.dirname(__file__), "asc-profiles.py")
)
asc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(asc)


class FakeClient:
    """Answers GETs from a table and records writes."""

    def __init__(self, pages: dict):
        self.pages = pages
        self.posted = []
        self.deleted = []

    def get_all(self, path):
        for prefix, items in self.pages.items():
            if path.startswith(prefix):
                return items
        return []

    def post(self, path, body):
        self.posted.append((path, body))
        return {"data": {"id": "new", "attributes": {"uuid": "UUID-NEW", "profileContent": base64.b64encode(b"new").decode()}}}

    def delete(self, path):
        self.deleted.append(path)
        return {}


def cert(cid, expires):
    return {"id": cid, "attributes": {"expirationDate": expires}}


def profile(pid, name, state, cert_ids):
    return {
        "id": pid,
        "attributes": {"name": name, "profileState": state, "uuid": f"UUID-{pid}", "profileContent": base64.b64encode(b"old").decode()},
        "relationships": {"certificates": {"data": [{"type": "certificates", "id": c} for c in cert_ids]}},
    }


class Certificates(unittest.TestCase):
    def test_expired_certificates_are_left_out(self):
        client = FakeClient({
            "/certificates?filter[certificateType]=DISTRIBUTION": [cert("live", "2999-01-01T00:00:00.000+00:00"), cert("dead", "2001-01-01T00:00:00.000+00:00")],
            "/certificates?filter[certificateType]=IOS_DISTRIBUTION": [cert("old-kind", "2999-01-01T00:00:00.000+00:00")],
        })
        self.assertEqual(["live", "old-kind"], [c["id"] for c in asc.current_distribution_certificates(client)])


class EnsureProfile(unittest.TestCase):
    certs = [cert("c1", "2999-01-01T00:00:00.000+00:00")]

    def test_an_active_profile_with_our_certificate_is_reused(self):
        client = FakeClient({"/profiles": [profile("p1", "ProofKit App Store", "ACTIVE", ["c1", "other"])]})
        got = asc.ensure_profile(client, "ProofKit App Store", "org.proofkit.app", self.certs)
        self.assertEqual("p1", got["id"])
        self.assertEqual([], client.posted)
        self.assertEqual([], client.deleted)

    def test_an_invalid_profile_is_deleted_and_made_again(self):
        client = FakeClient({
            "/profiles": [profile("p1", "ProofKit App Store", "INVALID", ["c1"])],
            "/bundleIds": [{"id": "b-ext", "attributes": {"identifier": "org.proofkit.app.PacketTunnel"}}, {"id": "b-app", "attributes": {"identifier": "org.proofkit.app"}}],
        })
        got = asc.ensure_profile(client, "ProofKit App Store", "org.proofkit.app", self.certs)
        self.assertEqual(["/profiles/p1"], client.deleted)
        self.assertEqual("new", got["id"])
        body = client.posted[0][1]["data"]
        self.assertEqual("IOS_APP_STORE", body["attributes"]["profileType"])
        # The exact App ID, not the extension's, which the filter also returns.
        self.assertEqual("b-app", body["relationships"]["bundleId"]["data"]["id"])
        self.assertEqual(["c1"], [c["id"] for c in body["relationships"]["certificates"]["data"]])

    def test_a_profile_missing_the_current_certificate_is_remade(self):
        client = FakeClient({
            "/profiles": [profile("p1", "ProofKit App Store", "ACTIVE", ["gone"])],
            "/bundleIds": [{"id": "b-app", "attributes": {"identifier": "org.proofkit.app"}}],
        })
        asc.ensure_profile(client, "ProofKit App Store", "org.proofkit.app", self.certs)
        self.assertEqual(["/profiles/p1"], client.deleted)
        self.assertEqual(1, len(client.posted))

    def test_a_same_prefix_name_is_not_ours(self):
        client = FakeClient({
            "/profiles": [profile("p2", "ProofKit App Store Old", "ACTIVE", ["c1"])],
            "/bundleIds": [{"id": "b-app", "attributes": {"identifier": "org.proofkit.app"}}],
        })
        asc.ensure_profile(client, "ProofKit App Store", "org.proofkit.app", self.certs)
        self.assertEqual([], client.deleted)
        self.assertEqual(1, len(client.posted))

    def test_no_certificate_is_a_clear_failure(self):
        client = FakeClient({"/bundleIds": [{"id": "b-app", "attributes": {"identifier": "org.proofkit.app"}}]})
        with self.assertRaises(SystemExit):
            asc.ensure_profile(client, "ProofKit App Store", "org.proofkit.app", [])


class Install(unittest.TestCase):
    def test_writes_the_decoded_profile_under_its_uuid_in_every_directory(self):
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            paths = asc.install(profile("p1", "n", "ACTIVE", []), dirs=(a, b))
            self.assertEqual(2, len(paths))
            for path in paths:
                self.assertTrue(path.endswith("UUID-p1.mobileprovision"))
                with open(path, "rb") as f:
                    self.assertEqual(b"old", f.read())


class Arguments(unittest.TestCase):
    def test_profile_argument_shape(self):
        self.assertEqual(("A B", "org.x"), asc.parse_profile_arg("A B=org.x"))
        with self.assertRaises(Exception):
            asc.parse_profile_arg("no-equals")


if __name__ == "__main__":
    unittest.main(argv=[sys.argv[0], "-v"])

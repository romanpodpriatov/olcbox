#!/usr/bin/env python3
"""Upload an Android App Bundle to a Google Play track.

The Android half of what the iOS job does with TestFlight: one call from CI,
authenticated as a service account the Play Console was told about, and the
build is on the track a minute later. Two things the API will not do, by
Google's design, and this script therefore expects to have happened already:
registering the app (the first bundle of a new app goes through the console by
hand) and choosing the signing key (App integrity -> App signing, before that
first bundle).

Needs google-api-python-client and google-auth. The service account needs
"Release to testing tracks" on the app for `internal`, and "Release to
production" for `production`.
"""

import argparse
import json
import sys

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

SCOPE = "https://www.googleapis.com/auth/androidpublisher"
# A multiple of 256 KiB, as the resumable protocol wants; 32 MiB keeps a
# 200 MB bundle to a handful of requests.
CHUNK = 32 * 1024 * 1024


def describe(error: HttpError) -> str:
    try:
        body = json.loads(error.content.decode("utf-8"))
        return body.get("error", {}).get("message") or error.content.decode("utf-8")
    except (ValueError, AttributeError):
        return str(error)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--service-account", required=True, help="path to the service account JSON key")
    parser.add_argument("--package", required=True, help="applicationId, e.g. org.proofkit.app")
    parser.add_argument("--bundle", required=True, help="the .aab to upload")
    parser.add_argument("--track", default="internal", help="internal, alpha, beta, production, or a custom track name")
    parser.add_argument("--release-name", help="how the release is listed in the console (defaults to the version name)")
    parser.add_argument("--notes", help="What's new, en-US")
    parser.add_argument("--status", default="completed", choices=["completed", "draft", "inProgress", "halted"])
    args = parser.parse_args()

    credentials = service_account.Credentials.from_service_account_file(args.service_account, scopes=[SCOPE])
    play = build("androidpublisher", "v3", credentials=credentials, cache_discovery=False)
    edits = play.edits()

    try:
        edit_id = edits.insert(packageName=args.package, body={}).execute()["id"]

        media = MediaFileUpload(args.bundle, mimetype="application/octet-stream", resumable=True, chunksize=CHUNK)
        uploaded = (
            edits.bundles()
            .upload(packageName=args.package, editId=edit_id, media_body=media, ackBundleInstallationWarning=True)
            .execute()
        )
        version_code = uploaded["versionCode"]

        release = {"versionCodes": [str(version_code)], "status": args.status}
        if args.release_name:
            release["name"] = args.release_name
        if args.notes:
            release["releaseNotes"] = [{"language": "en-US", "text": args.notes}]
        edits.tracks().update(
            packageName=args.package,
            editId=edit_id,
            track=args.track,
            body={"track": args.track, "releases": [release]},
        ).execute()

        try:
            edits.commit(packageName=args.package, editId=edit_id).execute()
        except HttpError as error:
            # Play refuses to send an edit for review by itself when the app has
            # other changes pending in the console. The upload is still wanted;
            # the review is then started from the console, as the message says.
            if "changesNotSentForReview" not in describe(error):
                raise
            edits.commit(packageName=args.package, editId=edit_id, changesNotSentForReview=True).execute()
            print("Committed without sending for review: the console has other pending changes.")
    except HttpError as error:
        print(f"Google Play refused: {describe(error)}", file=sys.stderr)
        return 1

    print(f"Uploaded {args.bundle} as versionCode {version_code} to the {args.track} track ({args.status}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())

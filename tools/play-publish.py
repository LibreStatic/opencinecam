#!/usr/bin/env python3
"""Publish a signed AAB to a Google Play track through the Play Developer API.

Shared by the LibreStatic apps. Standard library only; the JWT is signed with the
system `openssl`. The service-account key is the organisation-wide one in
~/.android/librestatic/*.json, or the file named by LIBRESTATIC_PLAY_KEY.

  tools/play-publish.py status
  tools/play-publish.py publish --aab app.aab [--mapping mapping.txt] [--track alpha]
                                [--notes store/play/release-notes/X.txt] [--dry-run]
                                [--no-review]

`publish` uploads the bundle (and the R8 mapping, if given), puts one release on the
track with the per-locale notes and the name "<versionCode> (<versionName>)", and
commits the edit, which sends it for review. `--dry-run` validates and discards the
edit instead. `--no-review` commits with changesNotSentForReview, leaving the change
in Publishing overview for someone to send from the Console.
"""
import argparse
import base64
import glob
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def die(msg):
    sys.exit(f"play-publish: {msg}")


def key_path():
    path = os.environ.get("LIBRESTATIC_PLAY_KEY")
    if path:
        return path
    keys = sorted(glob.glob(os.path.expanduser("~/.android/librestatic/*.json")))
    if len(keys) != 1:
        die(f"expected one key in ~/.android/librestatic, found {len(keys)}; set LIBRESTATIC_PLAY_KEY")
    return keys[0]


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=")


def access_token():
    key = json.load(open(key_path()))
    now = int(time.time())
    header = b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64(json.dumps({
        "iss": key["client_email"], "scope": SCOPE, "aud": key["token_uri"],
        "iat": now, "exp": now + 3600,
    }).encode())
    signing_input = header + b"." + claims
    # Hand the private key to openssl through a pipe so it never touches the disk.
    read_fd, write_fd = os.pipe()
    proc = subprocess.Popen(
        ["openssl", "dgst", "-sha256", "-sign", f"/dev/fd/{read_fd}"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, pass_fds=(read_fd,),
    )
    os.close(read_fd)
    with os.fdopen(write_fd, "w") as w:
        w.write(key["private_key"])
    signature, _ = proc.communicate(signing_input)
    if proc.returncode:
        die("openssl could not sign the token request")
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": (signing_input + b"." + b64(signature)).decode(),
    }).encode()
    with urllib.request.urlopen(key["token_uri"], body) as r:
        return json.load(r)["access_token"]


class Play:
    def __init__(self, package):
        self.package = package
        self.token = access_token()

    def call(self, method, url, body=None, data=None, content_type=None):
        headers = {"Authorization": f"Bearer {self.token}"}
        if body is not None:
            data = json.dumps(body).encode()
            content_type = "application/json"
        if content_type:
            headers["Content-Type"] = content_type
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                raw = r.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            try:
                detail = json.loads(detail)["error"]["message"]
            except (ValueError, KeyError):
                pass
            die(f"{method} {url.split('/applications/')[-1]} -> {e.code}: {detail}")

    def edit_url(self, edit, path=""):
        return f"{API}/{self.package}/edits/{edit}{path}"

    def upload_url(self, edit, path):
        return f"{UPLOAD}/{self.package}/edits/{edit}{path}?uploadType=media"


def gradle_value(pattern):
    text = open(os.path.join(ROOT, "app/build.gradle.kts")).read()
    match = re.search(pattern, text)
    return match.group(1) if match else None


def parse_notes(path):
    text = open(path, encoding="utf-8").read()
    notes = [{"language": lang, "text": body.strip()}
             for lang, body in re.findall(r"<([\w-]+)>\n(.*?)\n</\1>", text, re.S)]
    for note in notes:
        if len(note["text"]) > 500:
            die(f"{note['language']} notes are {len(note['text'])} characters; Play allows 500")
    if not notes:
        die(f"no <locale> blocks in {path}")
    return notes


def cmd_status(play, args):
    edit = play.call("POST", f"{API}/{play.package}/edits")["id"]
    try:
        for track in play.call("GET", play.edit_url(edit, "/tracks")).get("tracks", []):
            releases = track.get("releases", [])
            summary = "; ".join(
                f"{r.get('name', '?')} {r.get('versionCodes', [])} {r.get('status')}" for r in releases
            ) or "empty"
            print(f"{track['track']}: {summary}")
    finally:
        play.call("DELETE", play.edit_url(edit))


def cmd_publish(play, args):
    version_name = args.version_name or gradle_value(r'versionName\s*=\s*"([^"]+)"')
    notes_path = args.notes or os.path.join(ROOT, f"store/play/release-notes/{version_name}.txt")
    notes = parse_notes(notes_path)
    edit = play.call("POST", f"{API}/{play.package}/edits")["id"]
    committed = False
    try:
        print(f"uploading {os.path.basename(args.aab)} ...", flush=True)
        with open(args.aab, "rb") as f:
            bundle = play.call("POST", play.upload_url(edit, "/bundles"),
                               data=f.read(), content_type="application/octet-stream")
        code = bundle["versionCode"]
        print(f"bundle versionCode {code}, sha256 {bundle.get('sha256')}")
        if args.mapping:
            with open(args.mapping, "rb") as f:
                play.call("POST", play.upload_url(edit, f"/apks/{code}/deobfuscationFiles/proguard"),
                          data=f.read(), content_type="application/octet-stream")
            print("mapping uploaded")
        release = {
            "name": f"{code} ({version_name})",
            "versionCodes": [str(code)],
            "status": "completed",
            "releaseNotes": notes,
        }
        play.call("PUT", play.edit_url(edit, f"/tracks/{args.track}"),
                  body={"track": args.track, "releases": [release]})
        print(f"track {args.track}: {release['name']}, notes for {len(notes)} languages")
        play.call("POST", play.edit_url(edit, ":validate"))
        print("edit validated")
        if args.dry_run:
            print("dry run: edit discarded")
            return
        query = "?changesNotSentForReview=true" if args.no_review else ""
        play.call("POST", play.edit_url(edit, ":commit") + query)
        committed = True
        print("committed: left in Publishing overview" if args.no_review else "committed and sent for review")
    finally:
        if not committed:
            play.call("DELETE", play.edit_url(edit))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--package", help="default: applicationId in app/build.gradle.kts")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status", help="list every track and its releases")
    pub = sub.add_parser("publish", help="upload an AAB and release it on a track")
    pub.add_argument("--aab", required=True)
    pub.add_argument("--mapping", help="R8 mapping.txt to attach to the bundle")
    pub.add_argument("--track", default="alpha", help="API track id (default: alpha, the closed track)")
    pub.add_argument("--notes", help="default: store/play/release-notes/<versionName>.txt")
    pub.add_argument("--version-name", help="default: versionName in app/build.gradle.kts")
    pub.add_argument("--dry-run", action="store_true", help="validate, then discard the edit")
    pub.add_argument("--no-review", action="store_true", help="commit without sending for review")
    args = parser.parse_args()
    package = args.package or gradle_value(r'applicationId\s*=\s*"([^"]+)"')
    if not package:
        die("could not read applicationId; pass --package")
    play = Play(package)
    {"status": cmd_status, "publish": cmd_publish}[args.cmd](play, args)


if __name__ == "__main__":
    main()

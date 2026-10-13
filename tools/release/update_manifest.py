#!/usr/bin/env python3
"""Make and sign Shardlight Client update manifests (see docs/RELEASING.md).

Needs Python 3.9+ and OpenSSL 3 on PATH (Git for Windows ships one). The private key never leaves the
file you give it; this script only passes its path to openssl.

  keygen  PRIVATE.pem                        new Ed25519 signing key; prints the public key to configure
  pubkey  PRIVATE.pem                        print the public key of an existing signing key again
  sign    --key PRIVATE.pem --version 1.2.0 --download windows-x64 msi URL FILE [...] --out stable.json
  verify  --public-key BASE64 MANIFEST.json  check a manifest's signature before you upload it

The signature covers the canonical JSON of "latest": keys sorted, no whitespace, only integers as numbers,
non-ASCII written as UTF-8. That is exactly what CanonicalJson in the launcher computes.
"""

import argparse
import base64
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

SCHEMA_VERSION = 1
SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$")
PLATFORM = re.compile(r"^[a-z0-9][a-z0-9-]{0,31}$")
CHANNEL = re.compile(r"^[a-z0-9][a-z0-9-]{0,31}$")
TYPES = ("msi", "zip")
ED25519_SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")


def fail(message):
    sys.exit("error: " + message)


def openssl(*args, data=None):
    try:
        r = subprocess.run(["openssl", *args], input=data, capture_output=True, check=False)
    except FileNotFoundError:
        fail("openssl was not found on PATH (OpenSSL 3 is needed)")
    if r.returncode != 0:
        fail("openssl " + args[0] + " failed: " + r.stderr.decode(errors="replace").strip())
    return r.stdout


def canonical(value):
    def check(v):
        if isinstance(v, float):
            fail("only whole numbers may appear in signed data")
        if isinstance(v, dict):
            for item in v.values():
                check(item)
        if isinstance(v, list):
            for item in v:
                check(item)

    check(value)
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def public_key_base64(private_pem):
    der = openssl("pkey", "-in", str(private_pem), "-pubout", "-outform", "DER")
    if len(der) != 44 or not der.startswith(ED25519_SPKI_PREFIX):
        fail(str(private_pem) + " is not an Ed25519 key")
    return base64.b64encode(der).decode("ascii")


def sign_bytes(private_pem, data):
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp, "latest.json")
        src.write_bytes(data)
        sig = openssl("pkeyutl", "-sign", "-rawin", "-inkey", str(private_pem), "-in", str(src))
    if len(sig) != 64:
        fail("openssl did not produce an Ed25519 signature")
    return base64.b64encode(sig).decode("ascii")


def verify_bytes(public_b64, data, signature_b64):
    raw = base64.b64decode(public_b64.strip())
    if len(raw) == 32:
        raw = ED25519_SPKI_PREFIX + raw
    if len(raw) != 44 or not raw.startswith(ED25519_SPKI_PREFIX):
        fail("the public key is not an Ed25519 key in base64")
    with tempfile.TemporaryDirectory() as tmp:
        pub = Path(tmp, "pub.der")
        pub.write_bytes(raw)
        src = Path(tmp, "latest.json")
        src.write_bytes(data)
        sig = Path(tmp, "sig.bin")
        sig.write_bytes(base64.b64decode(signature_b64))
        r = subprocess.run(["openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-keyform", "DER",
                            "-inkey", str(pub), "-in", str(src), "-sigfile", str(sig)],
                           capture_output=True, check=False)
    return r.returncode == 0


def cmd_keygen(args):
    out = Path(args.private_key)
    if out.exists():
        fail(str(out) + " already exists; refusing to overwrite a signing key")
    openssl("genpkey", "-algorithm", "ed25519", "-out", str(out))
    try:
        os.chmod(out, 0o600)
    except OSError:
        pass
    print("Private key written to " + str(out) + ". Keep it offline and out of every repository.")
    print("Set this as the SHARDLIGHT_UPDATE_PUBLIC_KEY Actions variable:")
    print(public_key_base64(out))


def cmd_pubkey(args):
    print(public_key_base64(Path(args.private_key)))


def parse_download(values):
    platform, kind, url, file = values
    platform, kind = platform.lower(), kind.lower()
    if not PLATFORM.match(platform):
        fail("bad platform id " + platform + " (for example windows-x64)")
    if kind not in TYPES:
        fail("type must be one of " + ", ".join(TYPES))
    if not url.startswith("https://"):
        fail("download URLs must be https: " + url)
    path = Path(file)
    if not path.is_file():
        fail("no such file " + file)
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return {"platform": platform, "type": kind, "url": url, "sha256": digest.hexdigest(), "size": path.stat().st_size}


def cmd_sign(args):
    if not SEMVER.match(args.version):
        fail("version must be semantic, like 1.2.0 or 1.3.0-beta.1")
    if not CHANNEL.match(args.channel):
        fail("bad channel " + args.channel)
    latest = {
        "version": args.version,
        "releasedAt": args.released_at or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "downloads": [parse_download(d) for d in args.download],
    }
    if args.notes_url:
        if not args.notes_url.startswith("https://"):
            fail("--notes-url must be https")
        latest["notesUrl"] = args.notes_url
    if args.changelog:
        latest["changelog"] = Path(args.changelog).read_text(encoding="utf-8")
    data = canonical(latest)
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "channel": args.channel,
        "latest": latest,
        "signature": sign_bytes(Path(args.key), data),
    }
    if not verify_bytes(public_key_base64(Path(args.key)), data, manifest["signature"]):
        fail("the new signature did not verify")
    Path(args.out).write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("Signed manifest for " + args.version + " written to " + args.out)


def cmd_verify(args):
    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8-sig"))
    if manifest.get("schemaVersion") != SCHEMA_VERSION:
        fail("schemaVersion must be " + str(SCHEMA_VERSION))
    if verify_bytes(args.public_key, canonical(manifest["latest"]), manifest["signature"]):
        print("Signature OK for " + manifest["latest"].get("version", "?"))
    else:
        fail("the signature does NOT verify with that public key")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    k = sub.add_parser("keygen", help="make a new Ed25519 signing key")
    k.add_argument("private_key")
    k.set_defaults(run=cmd_keygen)
    pk = sub.add_parser("pubkey", help="print the public key of a signing key")
    pk.add_argument("private_key")
    pk.set_defaults(run=cmd_pubkey)
    s = sub.add_parser("sign", help="write a signed manifest")
    s.add_argument("--key", required=True, help="the private signing key (PEM)")
    s.add_argument("--version", required=True)
    s.add_argument("--channel", default="stable")
    s.add_argument("--download", action="append", required=True, nargs=4,
                   metavar=("PLATFORM", "TYPE", "URL", "FILE"),
                   help="one download: platform id (windows-x64), type (msi or zip), its https URL and the local "
                        "copy to hash; repeat for each file")
    s.add_argument("--notes-url")
    s.add_argument("--changelog", help="a text file with the release notes shown in the launcher")
    s.add_argument("--released-at", help="ISO-8601 time, default now")
    s.add_argument("--out", required=True)
    s.set_defaults(run=cmd_sign)
    v = sub.add_parser("verify", help="check a manifest's signature")
    v.add_argument("--public-key", required=True, help="the base64 public key the launcher is built with")
    v.add_argument("manifest")
    v.set_defaults(run=cmd_verify)
    args = p.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()

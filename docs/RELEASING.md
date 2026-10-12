# Releasing Shardlight Client

Nothing in this repository publishes anything by itself. CI builds and tests every push; a release is a
draft that a maintainer reviews and publishes by hand.

## 1. Distributor values

A release build can carry four values. None of them is in the repository. They are read at build time
from Gradle properties or environment variables, and CI passes them from the repository's
**Settings > Secrets and variables > Actions**:

| Actions name | Kind | Gradle property | What it does without it |
|---|---|---|---|
| `SHARDLIGHT_MSA_CLIENT_ID` | variable | `shardlight.msaClientId` | Microsoft sign-in is off until a player pastes an id in Settings > Accounts ([SIGN_IN.md](SIGN_IN.md)) |
| `SHARDLIGHT_CURSEFORGE_API_KEY` | **secret** | `shardlight.curseForgeApiKey` | CurseForge is off until a player enters their own key |
| `SHARDLIGHT_UPDATE_MANIFEST_URL` | variable | `shardlight.updateManifestUrl` | update checks are off and Settings > Updates says so |
| `SHARDLIGHT_UPDATE_PUBLIC_KEY` | variable | `shardlight.updatePublicKey` | update checks are off |

The client id, manifest URL and public key are not secret (anyone can read them from the app), so they
are variables. The CurseForge key is a secret: CurseForge issues it to you under their terms, and it ends
up inside the shipped jar, so use a key meant for distribution and be ready to rotate it.

Never paste a key or token into an issue, a pull request or a chat. If that happens, revoke it and make a
new one.

For a local build: `./gradlew build -Pshardlight.updateManifestUrl=https://...` or set the environment
variable first.

## 2. Signed updates

The launcher checks for updates only against a manifest signed with your Ed25519 key, then downloads the
file and verifies its SHA-256 and size. It never installs or runs the download; the player does.

### Make the signing key (once)

You need Python 3 and OpenSSL 3 (Git for Windows includes one).

```
python3 tools/release/update_manifest.py keygen update-signing.pem
```

It prints the public key, one line of base64. Save it as the `SHARDLIGHT_UPDATE_PUBLIC_KEY` variable.
Keep `update-signing.pem` offline, backed up, and out of every repository. If it is lost, players need a
new launcher build with a new public key before they can get updates again. If it leaks, publish a build
with a new key right away.

### Choose where the manifest lives

The manifest URL must never change between releases, and must be `https`. `{channel}` in it is replaced
by `stable` or `beta`, so one setting covers both channels, for example
`https://example.org/shardlight-client/{channel}.json`. Any static host works (GitHub Pages, a
storage bucket). Release download URLs must be `https` too.

### Write and sign a manifest for each release

After the release files exist at their final URLs:

```
python3 tools/release/update_manifest.py sign --key update-signing.pem --version 0.2.0 \
  --download windows-x64 msi https://github.com/Janxirus/Shardlight_Client/releases/download/v0.2.0/ShardlightClient-0.2.0-windows-x64.msi dist/ShardlightClient-0.2.0-windows-x64.msi \
  --download windows-x64 zip https://github.com/Janxirus/Shardlight_Client/releases/download/v0.2.0/ShardlightClient-0.2.0-windows-x64.zip dist/ShardlightClient-0.2.0-windows-x64.zip \
  --notes-url https://github.com/Janxirus/Shardlight_Client/releases/tag/v0.2.0 \
  --changelog notes-0.2.0.txt --out stable.json

python3 tools/release/update_manifest.py verify --public-key "<the public key>" stable.json
```

The script hashes the local copies, signs the canonical JSON of `latest` (sorted keys, no whitespace,
whole numbers only, UTF-8), checks its own signature and writes:

```json
{
  "schemaVersion": 1,
  "channel": "stable",
  "latest": {
    "version": "0.2.0",
    "releasedAt": "2026-11-02T10:00:00Z",
    "downloads": [{"platform": "windows-x64", "type": "msi", "url": "https://...", "sha256": "…", "size": 61234567}],
    "notesUrl": "https://...",
    "changelog": "…"
  },
  "signature": "<base64 Ed25519 signature>"
}
```

Upload it to the manifest URL. A pre-release version (`0.3.0-beta.1`) belongs in `beta.json`; the stable
channel never offers pre-releases. Players can skip a version, and nothing older than what they run is
ever offered.

## 3. Cutting a release

1. Update `launcher_version` in `gradle.properties` (semantic versioning) and add a section to
   `CHANGELOG.md`. Merge that to `main` through a pull request with green CI.
2. Run the manual test pass in [TESTING.md](TESTING.md) on the build from `main`.
3. In **Actions > Release (draft) > Run workflow**, pick `main` and type the version to confirm. The
   workflow refuses other branches, a mismatched version and a tag that already exists. It builds and
   tests on Windows and Linux, then creates a **draft** release `vX.Y.Z` with the MSI, the Windows app
   folder zip, the portable zip and `SHA256SUMS.txt`.
4. Check the draft's files and notes, then press **Publish release**.
5. Sign and upload the update manifest (section 2) so existing players are told about it.

The MSI is not code-signed yet, so Windows SmartScreen warns on first run. Signing it needs a
code-signing certificate, which only the publisher can obtain; add it to the Windows job when there is
one. macOS builds would need an Apple Developer ID to be signed and notarised.

## 4. Rolling back

Players keep their data folder across versions, and every project's files carry a schema version: a
newer launcher migrates them (keeping a `.pre-migration` copy of `settings.json`), and an older launcher
opens files from a newer one read-only instead of overwriting them, so going back to an older build
loses nothing. To pull a bad release, re-upload the previous manifest so nobody else is offered it,
mark the GitHub release as a pre-release, and ship the fix as a higher version. The launcher never
offers a lower version, so players who already updated move on with that fixed release.

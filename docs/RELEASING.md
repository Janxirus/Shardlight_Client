# Releasing Shardlight Client

Nothing in this repository publishes anything by itself. CI builds and tests every push; a release is a
draft that a maintainer reviews and publishes by hand.

## 1. Distributor values

A release build can carry four values. Only the Microsoft client id has a default in the repository
(`default_msa_client_id` in `gradle.properties`, the project's own app); the others are not in it. They are read at build time
from Gradle properties or environment variables, and CI passes them from the repository's
**Settings > Secrets and variables > Actions**:

| Actions name | Kind | Gradle property | What it does without it |
|---|---|---|---|
| `SHARDLIGHT_MSA_CLIENT_ID` | variable | `shardlight.msaClientId` | the project's app id from `gradle.properties` is used ([SIGN_IN.md](SIGN_IN.md)) |
| `SHARDLIGHT_DISCORD_APP_ID` | variable | `shardlight.discordApplicationId` | the Discord status switch stays off until a player enters an application id ([DISCORD.md](DISCORD.md)) |
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
   tests on Windows and Linux, then creates a **draft** release `vX.Y.Z` with the Windows MSI and app
   folder zip, the macOS disk image (`.dmg`, Apple silicon), the Linux `.deb` and `.rpm`, the portable zip
   and `SHA256SUMS.txt`. Each package is started once with `--capture` before it is accepted.
4. Check the draft's files and notes, then press **Publish release**.
5. Sign and upload the update manifest (section 2) so existing players are told about it.

Without signing certificates the MSI and the disk image are unsigned, so Windows SmartScreen and macOS
Gatekeeper warn on first run. Section 4 below turns signing on; it needs certificates that only the
publisher can obtain. Intel Macs use the portable zip (it carries the Intel macOS JavaFX files and needs a
Java 21 runtime); the disk image is built on Apple silicon.

## 4. Code signing

Signing is automatic: CI signs when the secrets below exist and skips it, with a notice in the run summary,
when they don't. Nothing else changes, so you can add the certificates at any time. **Never paste a
certificate, password or token into an issue, a pull request or a chat**; add them in the repository's
**Settings > Secrets and variables > Actions**. The certificate files are decoded into a temporary file or
keychain on the runner and deleted at the end of the job.

### Windows (the MSI)

| Name | Kind | What it is |
|---|---|---|
| `WINDOWS_CERTIFICATE_PFX_BASE64` | secret | your code-signing certificate with its private key, as a `.pfx` file, encoded as base64 |
| `WINDOWS_CERTIFICATE_PASSWORD` | secret | the password of that `.pfx` file |
| `WINDOWS_TIMESTAMP_URL` | variable, optional | a timestamp server; the default is `http://timestamp.digicert.com` |

Getting the certificate: buy a **code-signing certificate** from a certificate authority (an organisation
validated certificate, or an EV certificate if you want SmartScreen to trust the installer at once; EV
certificates usually come on a hardware token that CI cannot use, so check with the authority that you
can export a `.pfx`). Export it with its private key as `.pfx`, then encode it, for example in
PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("certificate.pfx")) | Set-Clipboard`, and
paste the result into the secret. `tools/release/sign_windows.ps1` signs every `.msi` with SHA-256 and a
timestamp and then verifies the signature. A cloud signing service (Azure Trusted Signing, for example) is
not wired in; it would replace that one script.

### macOS (the disk image)

macOS refuses an app version that starts with 0, so while the launcher is a `0.x.y` version the disk image is packaged as `1.x.y` (the in-app version and the file name still say `0.x.y`). Once the launcher reaches 1.0.0 the two match.

| Name | Kind | What it is |
|---|---|---|
| `MACOS_CERTIFICATE_P12_BASE64` | secret | your **Developer ID Application** certificate with its private key, exported as `.p12`, encoded as base64 (`base64 -i certificate.p12 | pbcopy`) |
| `MACOS_CERTIFICATE_PASSWORD` | secret | the password you chose when exporting the `.p12` |
| `MACOS_SIGNING_IDENTITY` | variable | the certificate's full name, for example `Developer ID Application: Your Name (ABCDE12345)` |
| `APPLE_ID` | secret | the Apple ID used for notarisation |
| `APPLE_APP_SPECIFIC_PASSWORD` | secret | an app-specific password made at appleid.apple.com (not the Apple ID password) |
| `APPLE_TEAM_ID` | variable | the 10-character team id from developer.apple.com > Membership |

Getting the certificate: join the Apple Developer Program, then in Xcode (Settings > Accounts > Manage
Certificates) or at developer.apple.com create a **Developer ID Application** certificate, and export it
from Keychain Access (certificate and private key together) as `.p12`. With the first three set, the app
inside the disk image is signed with the hardened runtime and the entitlements in
`packaging/macos/entitlements.plist` (the Java runtime needs them). With the last three also set, the disk
image is sent to Apple for notarisation and the ticket is stapled to it, so Gatekeeper opens it without a
warning. Signed but not notarised still warns.

### Update manifest

Signing the installers is separate from signing the update manifest (section 2): the manifest has its own
Ed25519 key and needs no certificate.

## 5. Rolling back

Players keep their data folder across versions, and every project's files carry a schema version: a
newer launcher migrates them (keeping a `.pre-migration` copy of `settings.json`), and an older launcher
opens files from a newer one read-only instead of overwriting them, so going back to an older build
loses nothing. To pull a bad release, re-upload the previous manifest so nobody else is offered it,
mark the GitHub release as a pre-release, and ship the fix as a higher version. The launcher never
offers a lower version, so players who already updated move on with that fixed release.

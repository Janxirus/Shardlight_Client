# Support matrix

"Tested" below means covered by the automated tests or built in CI. Nothing here has been confirmed by
playing a real game yet (see [TESTING.md](TESTING.md)).

## Launcher

| System | Build | Java | Status |
|---|---|---|---|
| Windows 10 and 11, x64 | MSI installer, app folder | included | built and unit-tested on Windows in CI; installed per user, no admin rights; not code-signed yet, so SmartScreen warns |
| Windows, x64 | portable zip | Java 21+ on the computer | built in CI |
| Linux x64 and arm64 | portable zip | Java 21+ | built, tested and screenshotted on Linux in CI |
| macOS 12+, Intel and Apple silicon | portable zip | Java 21+ | built, not run on a Mac; no signed app bundle yet, so Gatekeeper warns |
| Windows on ARM | none | none | not supported: OpenJFX ships no Windows ARM build |

The launcher itself runs on Java 21 with JavaFX 21. The game gets its own Java, chosen per Minecraft
version (see Minecraft below).

### Where sign-in tokens are kept

| System | Secret store | Without it |
|---|---|---|
| Windows | Data Protection API (per user) | always available |
| macOS | Keychain, through the `security` tool | always available |
| Linux | Secret Service, through `secret-tool` (package `libsecret-tools` or `libsecret`) | tokens are kept in memory only, so you sign in again each time you start the launcher |

Tokens are never written to a plain file.

## Minecraft

| Versions | Status |
|---|---|
| Releases and snapshots from Mojang's version manifest, including 26.x | installed from Mojang's servers and verified; modern and legacy library, native and asset layouts are tested with fixtures |
| Old alpha and beta versions | listed when "Show snapshots and old versions" is on in the new project wizard; legacy natives and assets are tested with fixtures, a real launch is not |
| Versions without a client download | refused with a message |

Java for the game: Mojang's own runtime for that version is preferred (the version file names it, for
example Java 21 for 1.20.5 to 1.21.x and Java 8 for old versions), downloaded and verified. You can pick
an installed Java per project instead; one older than the version needs is refused with a message.

## Mod loaders

| Loader | How it's installed | Status |
|---|---|---|
| Vanilla | nothing extra | supported |
| Fabric | Fabric's metadata server (`meta.fabricmc.net`) | supported; install and launch command tested with fixtures |
| Quilt | Quilt's metadata server | supported; tested with fixtures; Fabric mods are offered for Quilt projects |
| Forge | Forge's official installer, run headless | experimental: CI runs the real installer for Minecraft 26.3 and checks the version file, classpath and libraries (docs/TESTING.md); nobody has launched the game with it yet |
| NeoForge | NeoForge's official installer, run headless | experimental, as Forge |

## Catalogs

| Source | Status |
|---|---|
| Modrinth (API v2) | supported without a key; tested against a local fake of the API, and against the real API by the Live tests workflow |
| CurseForge (API v1) | works only with an approved CurseForge API key from the build or Settings > Content sources; files whose authors block third-party downloads open on the website; the real-API tests run only when the key is a repository secret |
| Local files | jars, zips and `.mrpack` files from your computer |

## Shardlight modules

| Module | Mod id | Minecraft | Loader | Java | Needs |
|---|---|---|---|---|---|
| Shardlight UI | `softglass` | 26.3 | Fabric 0.19.5+ | 25 | Fabric API |
| Shardlight Map | `shardmap` | 26.3 | Fabric 0.19.5+ | 25 | Fabric API |
| Shardlight Party | `shardparty` | 26.3 | Fabric 0.19.5+ | 25 | Fabric API |
| Shardlight Companion | `shardcompanion` | 26.3 | Fabric 0.19.5+ | 25 | Fabric API |

Each module works on its own; Map, Party and Companion use the UI's theme when it is installed too.
The 15 theme packs (7 classic, 8 new) are resource packs for 26.3. On Quilt the modules are untested,
and on Forge or NeoForge they won't load.

The modules come into the launcher's library by importing their jars (or the All Packs zip) from your
computer, or by **Download** on the Shardlight page. Download tries two places in this order: the module's Modrinth
project (once the catalog in `launcher-core/src/main/resources/shardlight/catalog.json` names one in its
`modrinthSlug`; the jar is checked against Modrinth's SHA-512), then the latest GitHub release of the module's
repository (checked against GitHub's SHA-256 when it lists one). If neither has the module, for example while
the repositories are private, the launcher says it is **not published yet** and offers to import a jar from
your computer. If a place could not be reached at all (no network, a rate limit), it says so instead of claiming
the module is unpublished. Once a module is published on Modrinth, putting its slug in `catalog.json` is the only
change needed to switch that source on.

## Presets

Presets change only the documented settings of the Shardlight mods: `config/shardlight.json`,
`config/shardmap.json`, `config/shardparty.json` and `config/shardcompanion.json`, as written by
Shardlight UI 4.1.0, Map 1.5.0, Party 1.2.1 and Companion 1.2.1. A project without Shardlight mods has
nothing for a preset to change unless you also turn on "Also tune Minecraft video settings". That switch only
works for Minecraft 26.3 (and its patch releases): the 12 `options.txt` keys it writes are listed in
`launcher-core/src/main/resources/preset/vanilla-options-26.3.txt`, and CI checks every one against the real
26.3 client on each run. Other Minecraft versions keep their video settings because their key names were not
checked (26 renamed `graphicsMode` to `graphicsPreset`, for example). The value formats of the unchanged keys
(numbers, true/false, the quoted cloud setting) are the ones Minecraft 1.21 wrote; a real game run has not
confirmed them for 26.3 yet. "Adaptive quality" is a saved **preview** switch (off by default): the launcher
writes `config/shardlight/adaptive-quality.json` as described in [ADAPTIVE_QUALITY.md](ADAPTIVE_QUALITY.md), and no
mod reads it yet.

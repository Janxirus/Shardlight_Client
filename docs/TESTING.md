# Testing

This page says what is tested automatically, what has only been checked by looking at it, and what
still needs a person with a Minecraft account. A green build means the launcher's own logic works against
test fixtures. It does not mean a real game has been started.

## Running the tests

```
./gradlew build                     # every test, with -Xlint:all -Werror
./gradlew :launcher-core:test --tests 'dev.shardlight.client.preset.*'   # one package
```

Network code never talks to the real services in the normal tests (the live tests above are the exception). `testutil/TestHttpServer` serves recorded-shape
responses for Mojang, Fabric, Quilt, Forge, NeoForge, Modrinth, CurseForge, Microsoft, Xbox and GitHub,
and `Endpoints.local(...)` points every client at it. Fake Minecraft versions, libraries and assets come
from `testutil/MinecraftFixtures`, and a small stand-in "game" jar is launched as a real process.

CI runs the same build on Linux and Windows for every push and pull request, then captures a screenshot
of every page with `--capture` under a virtual display (the `screenshots-…` artifact).

## Live tests (real services)

Some things can only be checked against the real services, so they live apart from the normal build: tests
tagged `live` (and `installer`) are excluded from `./gradlew build` and only run when asked for. The
**Live tests** workflow (`.github/workflows/live-tests.yml`) runs them on pull requests that touch
`launcher-core`, every Monday on the default branch, and on demand. It is a separate check from **Build**, so a
Modrinth outage can't hold up a pull request; don't make it a required check.

| Job | Command | What it proves |
|---|---|---|
| Real APIs | `./gradlew :launcher-core:liveTest -PliveTests` | Modrinth: search, project, versions with SHA-1/SHA-512 and a CDN URL, lookup by hash, categories, Fabric API for 26.3, "not found" for an unknown project. CurseForge: search, files (skipped unless the secret `SHARDLIGHT_CURSEFORGE_API_KEY` is set). GitHub: the release answer still has the fields the module downloader reads. Minecraft 26.3: every `options.txt` key the presets write is a string literal of the real client's `Options` class. |
| Forge and NeoForge installers | `./gradlew :launcher-core:installerTest -PinstallerTests` | Installs Minecraft 26.3, then Forge and NeoForge with their real installers through `LoaderService`, completes the version as a launch would, and checks the version file, the classpath and that every library exists with the right hash. They stay labelled experimental until someone launches them. |

Both write their HTML report as a build artifact (`live-api-reports`, `loader-installer-reports`). Run them
yourself with the commands above; they need a network that can reach those services.

## First sign-in and first launch check

Needs a Microsoft account that owns Minecraft: Java Edition, and a build whose Azure app Mojang has approved
([SIGN_IN.md](SIGN_IN.md)). Until then, step 3 shows the waiting screen and nothing further can be checked.

1. Start the launcher with a fresh data folder (`--data-dir <empty folder>`, or delete the old one).
2. On the welcome screens press **Sign in** (or **Accounts > Add account** later). A code such as `K7QD-M2XP` and
   a button for `microsoft.com/link` appear.
3. Open the page, type the code and sign in. Expected result, one of:
   * the dialog shows **Signed in as <your name>** with your head, and the Home page greets you by name;
   * **"This app is still waiting for Minecraft approval"** with **Retry** and **About Minecraft approval**.
     That means Mojang has not approved the app yet. Nothing is wrong with the account. Press Retry after the
     approval arrives; it asks for a new code. Stop the check here if you see this.
   * any other message (no Xbox account, child account, region, game not owned): it says what to do. Fix that
     and press Retry.
4. Quit and start the launcher again. You should still be signed in with no code (the token comes from the system
   secret store). Settings > Accounts > Sign out removes it.
5. **New project**: keep the defaults (newest release, no loader). Note the size the review step shows. Create it.
   The Downloads page shows the game, libraries, assets and a Java runtime installing with a progress ring.
6. Press **Play**. Expected: the Play bar goes through Preparing, Starting, Running; a Minecraft window opens at the
   title screen within a minute or two. Open a world: your player name is your Minecraft name and your skin loads
   (the session is the real one; the launcher has no offline mode).
7. Open a world, then quit the game. The Play bar returns to Play, the project shows the play time, and Logs has
   `latest.log`. If the game closes by itself, the crash screen opens with the likely cause.
8. Repeat steps 5 to 7 with a **Fabric** project and Fabric API from Discover. Then add **Shardlight UI** (needs
   the jar: Shardlight page > Download, or Import) and check its Control Center opens (Right Shift in game).

If a step does not behave as written, export diagnostics (project > Logs > Export diagnostics) and attach the zip
to the report. It contains no tokens.

## Automated tests (about 590, plus the live tests above)

| Package | Tests | What they cover |
|---|---:|---|
| `account` | 34 | account list, active account, stored sessions, sign-out, lost ownership, expired refresh, corrupt `accounts.json` |
| `auth` | 112 | device-code flow (slow-down, expiry, cancel, denial), Xbox/XSTS errors with plain-language messages, entitlement and profile checks, token refresh and rotation, the three OS secret stores, client-id lookup |
| `catalog` | 17 | Modrinth and CurseForge search, versions and files, missing-key handling, the key kept out of logs, files whose authors block third-party downloads, the install planner (recursive dependencies, blocking problems, wrong versions, already-installed files) |
| `content` | 66 | mod scan and metadata (Fabric, Quilt, Forge, NeoForge), enable/disable for the next launch, install/update/remove, history and rollback, resource pack order and enable/disable in `options.txt`, shader selection, warnings from mod metadata, refused duplicate or blocked installs, failed downloads that leave nothing behind |
| `diagnostics` | 24 | crash and log analysis with fixes, privacy-checked diagnostics zip |
| `download` | 11 | verified downloads, retries, mirror fallback, resume, checksum mismatches that leave no file, the shared cache, cancellation, thousands of queued files |
| `java` | 25 | finding installed Java, reading its version and architecture, choosing the right runtime, Mojang runtime downloads |
| `launch` | 23 | the launch command (arguments, natives, classpath, memory, Quick Play), starting the stand-in game, crash detection, one game per project |
| `loader` | 21 | Fabric and Quilt metadata, Forge and NeoForge installer handling, loader version lists |
| `log` | 5 | log redaction of tokens, keys and registered secrets |
| `minecraft` | 60 | version manifest, version JSON rules, libraries and natives per OS, assets (legacy and modern), install, verify, repair, offline use, malicious metadata |
| `modpack` | 9 | `.mrpack` and CurseForge import and export, overrides, unsafe paths |
| `nbt` | 19 | NBT read/write for `level.dat` and `servers.dat` |
| `net` | 5 | plain HTTP refused, User-Agent, rate limits and `Retry-After`, status codes |
| `preset` | 23 | every preset's settings, what a change will do, Balanced → Ultra Performance → Balanced with Custom overrides kept, revert and redo, opt-in vanilla options |
| `project` | 56 | create, duplicate, rename, archive, restore, delete, import/export, corrupt and newer `project.json`, backups and restore, worlds, screenshots, the server list and ping |
| `settings` | 7 | defaults, saving, migration with a backup copy, files from a newer launcher, broken and odd files |
| `shardlight` | 20 | the module and theme catalog, importing jars and the All Packs bundle, GitHub release downloads, compatibility checks, installing and removing modules without touching the player's own files |
| `storage` | 5 | data folder per OS, overrides, JSON migration steps |
| `update` | 23 | signed manifests, tampered or missing signatures, downgrades, skipped versions, verified downloads that are never run |
| `util` | 9 | atomic writes, path safety, safe zip extraction |
| `skin` | 38 | the skin texture layout (new and old 64x32 skins, slim arms, cape), flat previews, reading and checking PNGs, the local skin library, the Minecraft skin and cape requests against a local server (upload, cape change, errors, textures only from Mojang's host) |
| `discord` | 13 | the Discord message frames, the handshake, the status text, clearing it, nothing sent when off or when Discord is missing, reconnecting |
| `i18n` | 14 | language files from a folder and from the class path, fallback to English, language names, the two test languages, right-to-left detection, the translator template |
| app: `I18nBundlesTest` | 4 | every key the source uses exists, every value is a valid pattern, apostrophes show correctly |

### The areas the specification asks for

| Area | Where |
|---|---|
| project create / clone / archive / delete | `ProjectServiceTest` |
| project isolation | `ProjectServiceTest.duplicateCopiesEverythingAndSharesNoFile`, `ShardlightServiceTest`, `PresetEngineTest` (each writes only inside its project) |
| configuration persistence and migration | `SettingsServiceTest`, `ProjectServiceTest.migratesSchemaZeroToOne`, `AccountServiceTest`, `StorageTest` |
| download retry and verification | `DownloadManagerTest`, `HttpServiceTest`, `GameInstallerTest` |
| auth session failure handling | `MicrosoftAuthServiceTest`, `AccountServiceTest` |
| dependency resolution, valid and invalid versions | `CatalogTest` (install planner), `ContentServiceTest` |
| mod enable / disable / install / remove / update | `ContentServiceTest` |
| resource pack order | `ContentServiceTest.packOrder…` |
| Java selection | `JavaRuntimeServiceTest` |
| launch command correctness | `LaunchCommandBuilderTest`, `LaunchServiceTest` |
| backup and restore | `ProjectFilesTest` |
| preset transitions | `PresetEngineTest` |
| disabled-module overhead | `ShardlightServiceTest.deselectingRemovesOnlyShardlightFiles` (a module you turn off is removed, so it can't load). In-game cost is not measured. |
| malformed configurations | `SettingsServiceTest`, `ProjectServiceTest.corrupt…`, `AccountServiceTest`, `JsonMigrator` cases in `StorageTest` |

## Checked by looking

Every page, project tab, settings section and dialog was rendered with `--capture` at 1360×860 (Home and
a project also in the light theme) and checked for clipped text, overlaps and focus rings. That shows
layout with sample data, not behaviour with real data.

## The ten end-to-end acceptance cases

None of these has been run against a real game yet, because sign-in needs an Azure app approved for
Minecraft (docs/SIGN_IN.md) and Minecraft can't run in the build environment.

| # | Case | Automated part | Still needs a person |
|---|---|---|---|
| 1 | Install and launch owned vanilla Minecraft, no Shardlight | install, verify and the launch command against fixtures; the stand-in game starts and is watched | sign in, install a real version, play |
| 2 | Fabric project with only Shardlight Map | the module check pulls in Fabric API and nothing else; apply installs only the Map jar | needs the published Map jar and a real launch |
| 3 | All four modules and a theme, configuration isolated | apply, theme packs, per-project preset files, duplicate shares no file | real launch of two projects side by side |
| 4 | Find, install with dependencies, update, disable, remove a mod | against a local Modrinth fake | the same against live Modrinth |
| 5 | Balanced → Ultra Performance in one click and back, Custom kept | `PresetEngineTest.balancedUltraPerformanceBalancedCustomRoundTripKeepsOverrides` | see the change in game |
| 6 | Resource pack choose, order, enable, disable, visible in game | `options.txt` order and enable/disable | a verified launch |
| 7 | No background work when Shardlight visuals are off | disabled modules are removed from `mods/` | in-game profiling |
| 8 | Back up and recover a project and a world | `ProjectFilesTest` | with a real world |
| 9 | Measured FPS, frame time and memory per preset | not done; the app shows no numbers until something is measured | a repeatable benchmark on real hardware |
| 10 | Clear warnings and recovery for wrong versions and failed downloads | install planner errors, checksum and retry tests, plain-language install messages | seeing them in the real app |

## Skins, Discord status and languages (owner checks)

None of these can be checked without a real account, Discord or a graphics card, so the tests use local stand-ins and
this is the by-hand list:

1. **Skins** (needs a signed-in account that owns the game). Settings > Accounts > Skins: the model shows your skin
   and turns slowly; drag to turn it, scroll to zoom. Upload a skin (classic and then slim), choose a cape, remove it,
   and check the skin in the game. Turn your skin back to the old one from the local library. With the network off the
   section says what is wrong and keeps the library usable.
2. **Discord**: follow the steps in [DISCORD.md](DISCORD.md).
3. **Languages**: Settings > Appearance > Add a language, copy `ui.properties` to `ui_xx.properties` with a few
   changed lines and `language.name`, restart and pick it; the changed lines show and the rest stay English. Try
   `--lang ar-XB` for the mirrored layout and `--lang en-XA` for long text (see [LANGUAGES.md](LANGUAGES.md)).
4. **3D on a real computer**: the screenshots here use a software renderer. On Windows check the model is smooth, not
   black, and that the player head in the top bar matches.

## Installers, signing and look (owner checks)

The packages are built and started once in CI, but nobody has looked at them on a real computer:

1. **Windows MSI**: install it, look at the welcome page and the header (they should show the Shardlight shard and
   facets, not the Java picture; if they show the default look, the picture swap in
   `tools/release/set_msi_images.ps1` did not find its rows and CI printed a warning). Check the Start menu entry,
   the icon, and that uninstalling keeps your data. Unsigned installers show a SmartScreen warning.
2. **macOS disk image** (Apple silicon): open it, the window should show the Shardlight background; drag the app to
   Applications and start it. Unsigned builds need right-click > Open the first time.
3. **Linux**: `sudo apt install ./ShardlightClient-*.deb` (or `sudo dnf install ./ShardlightClient-*.rpm`), start
   Shardlight Client from the menu, remove it again.
4. **Splash**: start the launcher; the splash should show for a moment and then the window opens.
5. **Looks**: download the `screenshots-…` artifact from a build run and look through the dark, light and
   high-contrast folders; they are made in software rendering, so also glance at the real app once in each.
6. **Keyboard**: use the launcher with the keyboard only for a few minutes (Tab, Enter, Escape, Ctrl+K).
7. **Speed and memory**: see [PERFORMANCE.md](PERFORMANCE.md) for how to measure on a real computer.

## Manual test pass before a release

1. Install the MSI on a clean Windows user account. Start it from the Start menu.
2. Sign in with a Microsoft account that owns Minecraft. Sign out and in again; restart the launcher and
   check the account is remembered.
3. Create a vanilla project for the newest release, press Play, and reach the title screen. Open a world.
4. Create a Fabric project, find Sodium on Discover, install it, launch, and check it loaded.
5. Turn Sodium off, launch, check it didn't load. Turn it on, update it if offered, remove it.
6. On the Performance tab press Ultra Performance, launch, look at the Shardlight menus, switch back to
   Balanced and check your Custom changes are still there.
7. Back up a world, break it on purpose (delete a region file), restore the backup, and open it.
8. Pull the network cable during a big download; check the message and that a retry finishes it.
9. Export the project as `.mrpack`, import it as a new project, and launch it.
10. Uninstall the launcher and check the data folder and its worlds are still there.

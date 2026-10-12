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

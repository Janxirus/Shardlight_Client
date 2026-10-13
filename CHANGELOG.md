# Changelog

All notable changes to Shardlight Client. Versions follow [semantic versioning](https://semver.org).

## Unreleased

### Added

- **Art, installers and polish** (UI overhaul, part 8):
  - Original artwork in the shard style: a Home backdrop, a default cover for each of the nine project templates,
    eleven empty-state illustrations, a splash screen, and the installer pictures (docs/CREDITS.md). Projects
    without a picture of their own now show their template's cover.
  - Installers for every system: the Windows MSI and portable zip as before, plus a macOS `.dmg` and Linux `.deb`
    and `.rpm` built in CI. Each package is started once to prove it runs. Windows and macOS files are signed, and
    the disk image notarised, when the signing secrets exist (docs/RELEASING.md); without them signing is skipped.
  - The Shardlight page shows an in-game screenshot for each theme once one is bundled (docs/THEME_PREVIEWS.md);
    until then it keeps the pack logos.
  - Keyboard and screen readers: every button is at least 32 x 32 px, the project notes box no longer traps Tab,
    and `--capture --capture-matrix` checks every view for unnamed icon buttons, small targets and mouse-only
    controls (docs/ACCESSIBILITY.md).
  - The window's smallest size grows with the interface size setting, so a larger size never squeezes a page.
  - Nothing animates while the window is minimised.
  - Measured start time and memory (docs/PERFORMANCE.md); CI prints them on every build.

- **Extras** (UI overhaul, part 7):
  - Skins: Settings > Accounts > Skins shows your player in 3D (drag to turn, scroll to zoom), uploads a skin
    (classic or slim), switches capes, and keeps a local library of the skins you used. With Minecraft's services
    unreachable it says so and everything local still works. See docs/TESTING.md for what to check by hand.
  - Discord status, off until turned on: "Playing <project> on Minecraft <version>" while the game runs, cleared
    when it closes. It talks to the Discord app on the same computer and needs a Discord application ID
    (docs/DISCORD.md); there is none built in.
  - Languages: a picker in Settings > Appearance, translation files you can add yourself (docs/LANGUAGES.md),
    right-to-left layout, and two test languages (`en-XA` long and accented, `ar-XB` mirrored). Text that clipped
    in them is fixed: the project tabs scroll instead of shortening, filter segments wrap, stat cards grow, the
    Settings menu widens and Discover categories are translated.
  - Not included: the optional performance benchmark. It needs a Minecraft mod that cannot be built or run here, so
    the Performance tab still shows only the numbers it measures itself.

- **Core features** (UI overhaul, part 5):
  - Shardlight modules download from their Modrinth project (when the catalog names one) and then from their
    GitHub release; when neither has them the launcher says "not published yet" and offers to import a jar.
  - Live tests against the real Modrinth, CurseForge, GitHub and Mojang services, and against the real Forge and
    NeoForge installers for Minecraft 26.3, in a separate **Live tests** workflow that also runs weekly.
    They are never part of `./gradlew build`.
  - `config/shardlight/adaptive-quality.json` and docs/ADAPTIVE_QUALITY.md: the settings contract for the
    Shardlight UI mod. The switch is a labelled **Preview**, off by default.
  - A step-by-step first sign-in and first launch check in docs/TESTING.md.

### Fixed

- Minecraft video settings: Minecraft 26.3 no longer has the `graphicsMode` option, so presets now write
  `graphicsPreset`. The switch only works for 26.3 (every key is checked against the real client by CI) and leaves
  other Minecraft versions alone.

### Changed

- **Design system** (UI overhaul, part 1): bundled Inter, Inter Tabular and JetBrains Mono fonts; colour
  tokens with five surface levels, three text levels, an accent ramp computed from the accent setting
  and strong and subtle status colours, checked against WCAG AA by a test; a separate light palette; one
  type, spacing, radius and shadow scale; a `Motion` helper that honours Reduce motion; a redrawn outline
  icon set with 11 new icons; restyled buttons, fields, menus, check boxes, switches, sliders, tabs,
  lists, tables, scroll bars and tooltips. See docs/DESIGN_SYSTEM.md and docs/UI_AUDIT.md.
- **First run, Home, Library and project page** (UI overhaul, part 3): a three-step welcome (sign in, starter,
  Balanced or Ultra Performance) that ends on the new project; a sign-in dialog with a large device code, a
  waiting state, the player's head and a plain next step for every error; a Home with a project hero, a
  "Jump back in" row and an alerts strip; a Library with grid and list views, sorting, filters, folders and
  drag to reorder; and a project page with eight tabs (overview, mods, worlds, screenshots, backups,
  performance, logs, settings), a screenshot viewer, level-coloured logs with "Explain this crash", and
  generated project art.

## 0.1.0 (unreleased preview)

The first version: a standalone launcher with projects, mod browsing, optional Shardlight modules and
performance presets. It has not launched a real game yet, because Microsoft sign-in needs an approved
Azure app (see docs/SIGN_IN.md).

### Added

- **Projects**: a folder per setup with its own version, loader, mods, packs, worlds, options, Java and
  memory. New project wizard with templates; duplicate, rename, change version or loader, archive to the
  trash and restore; import and export as a zip, Modrinth `.mrpack` or CurseForge modpack.
- **Game installs** from Mojang's servers with SHA-1 checks, shared between projects; verify and repair;
  Mojang Java runtimes or a Java you choose.
- **Mod loaders**: Fabric and Quilt; Forge and NeoForge through their official installers (experimental).
- **Discover**: Modrinth search for mods, modpacks, resource packs and shaders, filtered to the project;
  dependency planning with conflict and version checks; CurseForge with an approved API key.
- **Content**: turn mods on or off for the next launch, update, remove, roll back from the history;
  resource pack order; shader selection.
- **Presets**: Ultra Performance, Performance, Balanced (default), Quality, Ultra Quality and Custom,
  with a preview of every change, a one-click Ultra Performance button, and Custom overrides kept.
- **Shardlight**: choose any of the four modules and 15 themes per project; import jars or the All Packs
  zip, or download a module's latest GitHub release; clean removal.
- **Worlds and backups**: world and project backups with safe restore, worlds to the trash.
- **Playing**: launch with the right Java and arguments, live game log, play time, crash explanations
  and a privacy-checked diagnostics zip.
- **Multiplayer**: the game's server list per project with status ping.
- **Accounts**: Microsoft device-code sign-in, ownership check, tokens in the OS secret store, several
  accounts.
- **Updates**: optional Ed25519-signed update checks and verified downloads that are never run
  automatically; `tools/release/update_manifest.py` to make keys and sign manifests.
- Light and dark themes, reduced motion, UI scale, keyboard focus rings.
- Windows MSI, Windows app folder and a portable zip for Windows, Linux and macOS, built by CI.
- 578 automated tests; CI on Linux and Windows with a screenshot of every page.

### Not in this version

- Measured FPS numbers for the presets, and the in-game side of adaptive quality.
- Discord Rich Presence, skin viewer, friends or social features.
- Languages other than English (every string is ready for translation).
- Code-signed Windows and macOS builds.

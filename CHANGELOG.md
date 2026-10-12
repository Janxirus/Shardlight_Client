# Changelog

All notable changes to Shardlight Client. Versions follow [semantic versioning](https://semver.org).

## Unreleased

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

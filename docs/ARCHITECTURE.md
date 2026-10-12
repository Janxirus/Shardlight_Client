# Shardlight Client architecture

Shardlight Client is a standalone desktop launcher for Minecraft: Java Edition. It installs and launches
the game without any Shardlight mod; the four Shardlight mods, the theme packs and the visual presets are
optional add-ons a player can pick per project.

## 1. Framework choice

**Java 21 + JavaFX 21 (LTS), built with Gradle, packaged with jpackage.**

| Option | Why it was or wasn't chosen |
|---|---|
| **JavaFX (chosen)** | Same language as the four Shardlight mods and the Minecraft ecosystem, so jar/zip/JSON handling, process launching and hashing use the JDK alone. Every dependency is on Maven Central, so builds are reproducible on any machine and in CI. CSS theming suits an original visual identity. jpackage produces a Windows MSI and a portable folder with a bundled runtime; the same code runs on Linux and macOS. The UI can be tested headlessly. |
| Kotlin + Compose Desktop | Modern declarative UI, but adds a second language, Skia native binaries and Google-hosted artifacts; nothing it offers is needed for a launcher. |
| Tauri (Rust + web UI) | Smallest installer, but splits the app into Rust and JavaScript stacks and needs WebView2; launching Java processes and reading mod jars would be re-implemented outside the JVM. |

No background service, tray daemon or web server runs. The launcher is idle when its window is closed.

## 2. Modules

```
launcher-core   every service and model; no UI code; unit-tested with JUnit 5
launcher-app    the JavaFX desktop shell (pages, dialogs, theme), talks to core only through LauncherServices
schemas/        versioned JSON contracts shared with the Shardlight mods (preset bridge)
packaging/      icons, portable start scripts, installer resources
```

Packages in `launcher-core` (`dev.shardlight.client.*`):

| Package | Responsibility |
|---|---|
| `util`, `error`, `i18n`, `log`, `task`, `storage`, `settings`, `net`, `download` | foundations: JSON, atomic writes, hashing, path safety, OS info, errors, localized strings, redacting logs, cancellation/progress, app folders, settings, HTTP with retries/rate limits, download queue |
| `minecraft` | version manifest, version JSON model and rules, libraries/natives, assets, install/verify/repair |
| `java` | Java runtime detection and Mojang runtime downloads |
| `loader` | Fabric and Quilt (metadata APIs), Forge and NeoForge (official installers) |
| `launch` | launch command builder, game process, play-time tracking |
| `diagnostics` | crash and log analysis, privacy-aware diagnostics export, performance facts |
| `auth`, `account` | Microsoft device-code sign-in, Xbox/XSTS, Minecraft entitlement + profile, OS secret stores, account list |
| `project` | projects (instances), templates, clone/archive/import/export, backups, worlds, servers, screenshots |
| `nbt` | minimal NBT reader/writer (level.dat, servers.dat) |
| `content` | installed mods/packs/shaders per project, enable/disable at next launch, history/rollback, options.txt pack order, archive safety, mod metadata |
| `catalog` | Modrinth and CurseForge clients, search, versions, dependency planning |
| `modpack` | .mrpack import/export, CurseForge manifest import/export |
| `shardlight` | the optional Shardlight module and theme catalog, content library, module installer |
| `preset` | Ultra Performance ... Ultra Quality / Custom presets, what each changes, applying them to the mods' config files |
| `update` | signed launcher update manifest check |
| `services` | `LauncherServices`, the one object that wires everything for the app |

## 3. Data layout

See `storage/AppPaths`. Shared, verified, read-only files (Minecraft versions, libraries, assets, Java
runtimes, the SHA-1 download cache) live once under `game/` and `files/`. Everything mutable lives inside
one project folder: `projects/<id>/project.json` and its game directory `projects/<id>/minecraft/` with
`mods/`, `config/`, `resourcepacks/`, `shaderpacks/`, `saves/`, `screenshots/`, `logs/`, `options.txt`.
Nothing mutable is shared between projects. Uninstalling the launcher keeps this folder (worlds are never
deleted by an uninstall).

## 4. Conventions every package follows

- **Errors**: throw `LauncherException(ErrorCode, messageKey, args...)`. The key is in an i18n bundle; the
  UI shows `userMessage()`. Never show raw exception text to users.
- **Strings**: every user-visible string is a key in `launcher-core/src/main/resources/i18n/<area>.properties`.
  Areas: `common`, `install`, `auth`, `project`, `content`, `shardlight`, `ui`. Each area owns its file.
- **Files**: write with `AtomicFiles` (temp file + fsync + atomic move); read JSON with corruption recovery.
  Paths from outside (archives, manifests, catalog file names, user names) go through `PathSafety`.
- **Network**: only through `HttpService` (HTTPS only, User-Agent, retries, rate limits) and
  `DownloadManager` (verify then move into place, resume, mirrors, shared cache). Endpoints come from
  `Endpoints`, so tests can point them at `TestHttpServer`.
- **Long work**: blocking methods taking `ProgressListener` and `CancellationToken`; the UI runs them on
  background threads.
- **Logging**: `java.util.logging`; every line passes through `Redactor`. Never log tokens, API keys or
  passwords (the launcher never sees a password).
- **Tests**: JUnit 5 in `launcher-core/src/test/java`; network code is tested against `TestHttpServer`
  fixtures, never the real services.

## 5. Security and compliance

- Microsoft sign-in uses the OAuth 2.0 device-code flow against the consumer tenant. The launcher never
  asks for or stores a password. Refresh and access tokens go to the OS secret store (Windows DPAPI,
  macOS Keychain, Linux Secret Service). If none is available the session is kept in memory only.
- Game ownership is checked through Minecraft services' entitlements and profile endpoints. There is no
  offline or cracked mode. Minecraft files come only from Mojang's servers and are verified by SHA-1.
- Mod jars are third-party code. The launcher verifies hashes the catalogs publish, shows where a file
  comes from, and never calls a mod safe.
- Archives are checked for path traversal, absolute paths, reserved names, symbolic links, entry counts
  and expansion ratio before anything is extracted.
- Modrinth requests carry a descriptive User-Agent and respect its rate limit. CurseForge is used only
  with an approved API key supplied by the distributor, and files whose authors disallow third-party
  downloads are sent to the website instead.

## 6. Shardlight integration contract

The launcher never requires a Shardlight mod. When a project has Shardlight modules selected, the
launcher installs their jars (plus Fabric API, their one real dependency) into that project only. Presets
write only the documented keys of each installed mod's own config file (`config/shardlight.json`,
`config/shardmap.json`, `config/shardparty.json`, `config/shardcompanion.json`) and record what they wrote
in `config/shardlight/launcher-preset.json` (schema in `schemas/launcher-preset.schema.json`), so the
mods' normal settings screens keep working and any change can be reverted.

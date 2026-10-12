# Contributing

Shardlight Client is All Rights Reserved (see `LICENSE`). Contributions are welcome by invitation;
open an issue first so we can agree on the change.

## Build and run

You need a JDK 21 (Temurin is fine). Everything else comes through the Gradle wrapper.

```
./gradlew build                 # compile, run all tests, build the jars
./gradlew :launcher-app:run     # start the launcher from source
./gradlew :launcher-app:run --args="--data-dir build/dev-data"   # use a throwaway data folder
./gradlew :launcher-app:portableZip       # portable zip (needs Java 21 on the target PC)
./gradlew :launcher-app:jpackageImage     # app folder with its own Java runtime (run on the target OS)
./gradlew :launcher-app:jpackageInstaller # Windows MSI (needs WiX Toolset 3 on PATH)
```

`--capture <folder>` starts the app, opens every page and saves a PNG of each, which is how CI checks
that every screen renders. Use it with `--data-dir` so it doesn't touch your real projects.

## Where things go

Read `docs/ARCHITECTURE.md` first. In short:

- `launcher-core` holds every service and has no UI code. New behaviour goes here, with JUnit tests.
  Network code is tested against `testutil/TestHttpServer`, never the real services.
- `launcher-app` is the JavaFX shell. It talks to core only through `LauncherServices`.
- Every user-visible string is an i18n key (`launcher-app/src/main/resources/i18n/ui.properties` for
  the app, `launcher-core/src/main/resources/i18n/*.properties` for services). Strings are
  `MessageFormat` patterns, so write apostrophes as `''`.
- Errors are `LauncherException(ErrorCode, key, args...)`; the UI shows `userMessage()`.
- Files are written with `AtomicFiles`; paths that come from outside go through `PathSafety`.

## Rules that are not negotiable

- Never ask for, log or store a password, token or API key outside the OS secret store.
- No offline or cracked mode, and no redistributing Minecraft or Java files: they are always downloaded
  from Mojang (or the loader's own servers) and verified.
- Only official, documented APIs for Modrinth and CurseForge. Respect files whose authors disallow
  third-party downloads.
- The launcher must work with no Shardlight mod installed, and a disabled module must not load.
- Never claim a performance number that wasn't measured.

## Pull requests

Fill in the pull request template. CI must be green on Linux and Windows. Keep changes focused and add
tests for behaviour you change. The compiler runs with `-Xlint:all -Werror`, so fix warnings rather
than suppressing them.

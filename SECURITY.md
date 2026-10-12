# Security

## Reporting a problem

Please report security problems privately, not in a public issue: use GitHub's
"Report a vulnerability" button on the repository's Security tab. Include what you found, how to
reproduce it and which version you used. You will get an answer as soon as the maintainer can look at it.

## What the launcher protects, and how

| Area | What Shardlight Client does |
|---|---|
| Passwords | Never asks for or sees one. Microsoft sign-in uses the OAuth 2.0 device-code flow in your own browser. |
| Tokens | Refresh tokens and cached game sessions live only in the operating system's secret store (Windows DPAPI, macOS Keychain, Linux Secret Service). If none is available they are kept in memory for this run only. `accounts.json` holds names and ids, never tokens. |
| Logs | Every log line passes through a redactor that removes tokens, API keys and access codes. Diagnostics exports are checked again before they are written. |
| Game ownership | Checked with Minecraft services (entitlements and profile). There is no offline or "cracked" mode. |
| Downloads | HTTPS only. Every file with a published hash (Mojang SHA-1, Modrinth SHA-512/SHA-1, CurseForge SHA-1, update SHA-256) is verified before it is moved into place; partial files never appear in a project. |
| Archives | Modpacks, project imports and resource-pack bundles are checked for `../` paths, absolute paths, links, reserved names, entry counts and zip bombs before anything is extracted. |
| Mods | Mods are third-party code that runs with your permissions. The launcher verifies the files the catalogs publish and shows where each file came from, but it cannot tell you a mod is safe. |
| Updates | The launcher only trusts an update manifest signed with the distributor's Ed25519 key, verifies the download's SHA-256, and never installs anything without you. Builds without a configured key have update checks turned off. |
| API keys | A CurseForge API key you enter is stored in the OS secret store, never in `settings.json` or logs. |

## Secrets in this repository

There are none, and there must never be. Distributor values (the Azure client id, a CurseForge API key,
the update manifest URL and public key) are passed at build time from the repository's Actions
variables and secrets; see `docs/SIGN_IN.md` and `docs/RELEASING.md`. Never paste a token or key into
an issue, pull request or chat; if that happens, revoke it right away and create a new one.

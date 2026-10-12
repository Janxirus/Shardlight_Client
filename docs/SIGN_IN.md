# Microsoft sign-in

Shardlight Client signs players in with their Microsoft account, the same way the official launcher does,
and checks that the account owns Minecraft: Java Edition. The launcher **never sees or stores a password**
and has **no offline or cracked mode**.

This page is for whoever builds and distributes Shardlight Client. Sign-in needs a Microsoft
application (an Azure "client ID") that the distributor registers and that Mojang approves for
Minecraft. Builds of this repository use the project's own app, `default_msa_client_id` in
`gradle.properties`. Anyone distributing their own build registers their own app and must not reuse
another launcher's. Without an id, the Accounts page explains that sign-in isn't set up, and every
sign-in call fails with `AUTH_NOT_CONFIGURED`.

## How sign-in works

1. **Device code** (OAuth 2.0 device authorization grant, consumer tenant
   `https://login.microsoftonline.com/consumers/oauth2/v2.0/`). The launcher asks for a short code
   (scope `XboxLive.signin offline_access`) and shows it with the link `https://www.microsoft.com/link`.
   The player opens the link in any browser, types the code and signs in on Microsoft's own page,
   including two-step verification. Meanwhile the launcher polls the token endpoint at the interval
   Microsoft asks for, slowing down when told to (`slow_down`), until the player finishes, declines or
   the code expires.
2. **Xbox Live**: the Microsoft access token is exchanged for an Xbox Live user token
   (`user.auth.xboxlive.com`) and then for an XSTS token for `rp://api.minecraftservices.com/`. A second,
   optional XSTS call (`http://xboxlive.com`) reads the Xbox user id (xuid), which the game uses for
   telemetry and chat reporting. Xbox account problems are explained to the player:

   | XErr | Meaning | What the player sees |
   |---|---|---|
   | 2148916233 | no Xbox profile yet | create one by signing in once at xbox.com |
   | 2148916234 | Xbox terms not accepted | accept them at xbox.com |
   | 2148916235 | Xbox Live not available in the account's country | can't be used for Minecraft |
   | 2148916236, 2148916237 | adult verification needed (South Korea) | verify at account.xbox.com |
   | 2148916238 | child account not in a Microsoft family | an adult adds it at account.microsoft.com/family |
   | 2148916229 | family settings block online play | a parent changes them |
   | 2148916227 | account suspended by Xbox | can't sign in |

3. **Minecraft services**: `authentication/login_with_xbox` returns the Minecraft access token (valid for
   24 hours). `entitlements/mcstore` lists purchases, and `minecraft/profile` returns the player's name,
   UUID and skin.
   * A profile means the account can play, including Xbox Game Pass accounts that have no store
     entitlement.
   * No profile and no entitlement: "this Microsoft account doesn't own Minecraft: Java Edition".
   * An entitlement but no profile: the player must first choose a profile name at
     https://www.minecraft.net/msaprofile.
4. **Later launches** reuse the stored Minecraft token while it has more than 5 minutes left. After
   that, the launcher refreshes the Microsoft token with the stored refresh token (Microsoft may hand
   out a new one, which replaces the old one immediately) and repeats steps 2 and 3. If the refresh
   token was revoked or expired, the account stays in the list marked "sign in again". If the computer
   is offline, nothing is deleted.

## 1. Register the application (Microsoft Entra)

1. Sign in at the Azure portal, https://portal.azure.com, with the Microsoft account that will own the
   app, and open **App registrations** (search for it at the top). A personal account (Outlook, Gmail
   or any other email used as a Microsoft account) needs its own directory first:
   * `entra.microsoft.com` answers a personal account with "Selected user account does not exist in
     tenant 'Microsoft Services'". Use the Azure portal instead.
   * If the portal says you have no access or asks you to create a directory or subscription, sign up
     for a free Azure account at https://azure.microsoft.com/free with the same email (Microsoft asks
     for a card to verify identity), then open App registrations again. App registrations cost nothing.
2. Click **New registration**.
   * **Name**: for example `Shardlight Client (your name)`. Players see it on Microsoft's consent page.
   * **Supported account types**: **Personal Microsoft accounts only**. "Accounts in any
     organizational directory and personal Microsoft accounts" also works. The launcher signs in
     through the `consumers` tenant, so personal accounts must be included.
   * **Redirect URI**: leave it empty. The device-code flow doesn't use one.
3. Click **Register**, then copy the **Application (client) ID** (a GUID such as
   `4f2b1a9c-1234-4cde-9abc-0123456789ab`) from the Overview page.
4. Open **Authentication**. Under **Advanced settings**, set **Allow public client flows** to **Yes**,
   then **Save**. This turns on the device-code flow. Without it, Microsoft answers `invalid_client` or
   `unauthorized_client`, and the launcher reports that the app ID was rejected.
5. Don't create a client secret or certificate. A desktop app can't keep a secret, and the device-code
   flow for public clients doesn't need one. No API permissions need to be added: `XboxLive.signin`
   and `offline_access` are requested when the player signs in.

## 2. Request Minecraft API access

New applications can't call `api.minecraftservices.com` until Mojang approves them. Until then,
`login_with_xbox` answers HTTP 403 "Invalid app registration", and the launcher says the app has to be
approved first.

1. Fill in Mojang's review form at **https://aka.ms/mce-reviewappid**, giving the **Application (client)
   ID** and, if asked, the **Directory (tenant) ID** from the app's Overview page, along with a short
   description of the launcher.
2. Wait for approval. It can take a while. Your app must follow the Minecraft EULA and Usage
   Guidelines: real accounts only, ownership checked, game files only from Mojang.
3. Approval applies to that client ID. If you register a new app, request access again.

## 3. Configure the client ID

The launcher looks for the client ID in this order and uses the first one it finds:

| Where | How |
|---|---|
| **Settings > Accounts** in the app | paste the client ID; it is saved in `settings.json` as `auth.microsoftClientId` |
| `settings.json` | `"auth": { "microsoftClientId": "4f2b1a9c-..." }` in the data folder (see below) |
| Build default: system property | `-Dshardlight.msaClientId=4f2b1a9c-...`, for example through jpackage `--java-options` |
| Build default: baked into the build | `-Pshardlight.msaClientId=4f2b1a9c-...` on the Gradle command line, or the `SHARDLIGHT_MSA_CLIENT_ID` environment variable (in CI, the Actions variable of that name; see [RELEASING.md](RELEASING.md)) |
| Build default: the project's app | `default_msa_client_id` in `gradle.properties`, used when nothing above is set |

The setting is read every time sign-in starts, so a change takes effect without a restart.
`MicrosoftClientIds.looksValid` checks that a typed value has the shape of a GUID.

Refresh tokens are tied to the app that issued them. **After you change the client ID, every player has
to sign in again.**

## What is stored where

The data folder is `%APPDATA%\ShardlightClient` on Windows, `~/Library/Application Support/ShardlightClient`
on macOS and `~/.local/share/shardlight-client` on Linux, unless `--data-dir`, `shardlight.home` or
`SHARDLIGHT_HOME` overrides it.

| What | Where | Contains |
|---|---|---|
| Account list | `accounts.json` in the data folder | for each account: profile UUID, name, xuid, skin URL and model, whether it owns the game, when it was added, last used and last checked; plus the selected account. **No tokens, ever.** |
| Microsoft refresh token | OS secret store, key `msa.refresh.<uuid>` | the long-lived token that renews the sign-in |
| Minecraft session | OS secret store, key `mc.session.<uuid>` | `{"accessToken": "...", "expiresAt": "..."}` (24 h token) |
| Client ID | `settings.json` | the app ID (not a secret) |

Never stored: passwords (the launcher never sees one), Microsoft access tokens and Xbox tokens. Those
are short-lived and recreated at every refresh.

The OS secret store is picked automatically at startup (`SecretStores.forCurrentOs`):

| System | Store | Details |
|---|---|---|
| Windows | Data Protection API (DPAPI) | each value is encrypted for the current Windows user with an application entropy that includes the key name, and saved as base64 in `<data folder>\secrets\<sha256(key)>.bin`. Only the same Windows user on the same PC can decrypt it; a copied data folder doesn't carry the sign-in along. |
| macOS | login Keychain | generic passwords, service **Shardlight Client**, account = the key. Written through `/usr/bin/security -i` with the command on standard input, so the secret never appears in the process list. |
| Linux | Secret Service (GNOME Keyring, KWallet, KeePassXC) via `secret-tool` | items labelled **Shardlight Client** with attributes `service=shardlight-client` and `account=<key>`; the value is passed on standard input. Used only when `secret-tool` is installed (package `libsecret-tools` on Debian/Ubuntu, `libsecret` on Fedora/Arch) and a test store/lookup works. |
| anything else | memory only | used when none of the above works. Sign-in works but isn't remembered after the launcher closes, and the Accounts page says so. **Tokens are never written to plain files.** |

Logs, crash reports and diagnostics exports pass through the redactor. Every token is registered with it
as soon as it is received, and token-shaped text (JWTs, `Bearer ...`, `--accessToken ...`, refresh
tokens) is masked by pattern as well.

## Signing out and revoking access

* **Sign out in the launcher** (Accounts page): deletes `msa.refresh.<uuid>` and `mc.session.<uuid>`
  from the secret store and removes the account from `accounts.json`. If the store refuses the
  deletion, the launcher says so and the account is still removed.
* **Revoke the launcher's access at Microsoft**: go to https://account.live.com/consent/Manage, find
  the app name you registered, and choose **Remove these permissions**. Existing refresh tokens stop
  working; the launcher then reports "sign in again". https://account.microsoft.com/security also
  offers "Sign out everywhere".
* **Remove leftovers by hand** (for example after uninstalling):
  * Windows: delete the `secrets` folder in `%APPDATA%\ShardlightClient`.
  * macOS: in Keychain Access, search for "Shardlight Client" and delete the entries.
  * Linux: delete the "Shardlight Client" items in Seahorse (Passwords and Keys), or run
    `secret-tool clear service shardlight-client account msa.refresh.<uuid>` (and the same for
    `mc.session.<uuid>`).

## Troubleshooting

| Error code | Likely cause | Fix |
|---|---|---|
| `AUTH_NOT_CONFIGURED` "sign-in isn't set up" | no client ID configured | step 3 |
| `AUTH_NOT_CONFIGURED` "app ID rejected" | wrong ID, or public client flows disabled, or the app doesn't allow personal accounts | steps 1.2 to 1.4 |
| `AUTH_NOT_CONFIGURED` "must be approved" | Minecraft API access not granted yet | step 2 |
| `AUTH_FAILED` "code expired" | the player took longer than the code's lifetime (about 15 minutes) | start again |
| `AUTH_EXPIRED` | refresh token revoked, expired (unused for about 90 days) or issued to another client ID | sign in again |
| `AUTH_NOT_OWNED` | the account doesn't own Java Edition, or has no profile name yet | buy the game, or set the name at minecraft.net |
| `SECRET_STORE_UNAVAILABLE` | Keychain locked, Secret Service not running, DPAPI failed | unlock the keychain or log in to the desktop session; sign-in still works for the current run |
| `NETWORK` / `RATE_LIMITED` | offline, firewall, or Minecraft's login rate limit | wait a minute and retry; nothing is deleted |

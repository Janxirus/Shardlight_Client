# Adaptive quality: the settings contract

Adaptive quality means the game lowers its own effects when the frame rate drops below a target, and brings them
back when there is room again. It is a **preview**: the launcher writes the settings described here, but no
Shardlight mod reads them yet, so switching it on changes nothing in game today. This page is what the Shardlight
UI mod needs to implement its side later, and what the launcher promises not to change without bumping
`schemaVersion`.

## The switch in the launcher

*Project > Performance > Options > Adaptive quality (Preview).* Off by default for every project. Nothing is
written for a project that never turned it on.

## The file

| | |
|---|---|
| Path | `<game folder>/config/shardlight/adaptive-quality.json` (the project's own game folder, next to `launcher-preset.json`) |
| Written by | Shardlight Client, whenever the switch or the target changes. Atomic replace; only the launcher writes it. |
| Read by | the Shardlight UI mod, once when the world loads and again when the file's modified time changes |
| Encoding | UTF-8 JSON, one object |

```json
{
  "schemaVersion": 1,
  "enabled": true,
  "targetFps": 60,
  "writtenBy": "Shardlight Client 0.1.0",
  "writtenAt": "2026-10-12T22:00:00Z"
}
```

| Key | Type | Meaning |
|---|---|---|
| `schemaVersion` | integer | Format version, `1`. A reader that does not know the number must ignore the file (adaptive quality stays off). |
| `enabled` | boolean | The user's choice. The file exists once it was turned on at least once; turning it off writes `false` instead of deleting it. A missing file means off. |
| `targetFps` | integer, 30 to 240 | The frame rate to hold. The launcher refuses values outside the range, so a reader may clamp instead of failing. |
| `writtenBy` | string | Informational, for support. |
| `writtenAt` | string, ISO 8601 UTC | Informational. |

A reader must treat unknown keys as harmless, and a file it cannot parse, or any other problem, as `enabled: false`.

## What the mod is expected to do (when it is built)

These rules are the intent the launcher's switch promises the user; the mod can refine the numbers.

1. **Measure** the frame rate over a few seconds (an average, not a single frame), and only while a world is open and
   the window has focus.
2. **Step down** when the average stays below `targetFps`: lower the settings of the Shardlight UI mod, most
   expensive first, using the cost labels of the launcher's preset table (`PresetCatalog`: `HIGH`, then `MEDIUM`,
   then `LOW`; for example menu embers and theme decorations before hover animations). One step at a time, with a
   pause between steps so the effect of each can be seen.
3. **Step up** again only after the average has stayed comfortably above the target for a longer time, so the quality does
   not flicker.
4. **Never** change the essential indicators (low-health vignette, durability and armour HUD, tool and elytra
   warnings, food and air warnings), the user's own `config/*.json` files, Minecraft's `options.txt`, or other
   mods' settings. Adaptive changes live only in memory for the session and are gone when the game closes, so a
   crash cannot leave the user's files changed.
5. **Tell the user** when it is acting (a small, dismissible notice or an entry in the HUD editor), and give an
   in-game way to switch it off for the session.

## What the launcher does today

- Saves `enabled` and `targetFps` in `project.json` (`preset.adaptiveQuality`, `preset.adaptiveTargetFps`) and writes the
  file above.
- Records the same two values in `launcher-preset.json` on every preset apply, with `"implementedInGame": false`.
  That flag changes to `true` only in a launcher version that knows a installed mod acts on the file.
- Shows the "saved, not active yet" note when a preset is applied while the switch is on.

## Checks

`PresetEngineTest.adaptiveQualityIsOffByDefaultAndNotImplementedInGame` pins the file name, `schemaVersion`, `enabled`
and `targetFps`. Change that test and this page together.

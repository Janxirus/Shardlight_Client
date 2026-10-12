package dev.shardlight.client.preset;

/**
 * One setting a preset changes.
 *
 * @param def      the setting
 * @param current  the value in effect now (the file's value, or the mod's default when the key is absent)
 * @param proposed the value the preset writes; after a revert, the value in effect again (the mod's default
 *                 when the revert removed the key)
 */
public record SettingChange(SettingDef def, Object current, Object proposed) {
}

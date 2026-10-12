package dev.shardlight.client.content;

import java.util.ArrayList;
import java.util.List;

/**
 * One installed mod, resource pack or shader pack in a project, as recorded in project.json. The file on
 * disk is {@code <game dir>/<kind folder>/<fileName>}; a disabled mod is renamed to
 * {@code <fileName>.disabled} so the loader cannot load it on the next launch.
 */
public final class ContentEntry {
    /** stable id of this entry inside the project (random UUID) */
    public String id;
    public ContentKind kind = ContentKind.MOD;
    public ContentSource source = ContentSource.LOCAL;
    /** catalog project id (Modrinth project id, CurseForge mod id, Shardlight module/theme id) */
    public String projectId;
    /** catalog version/file id */
    public String versionId;
    /** human version label, for example "0.6.3+mc26.3" */
    public String versionNumber;
    public String title;
    /** for mods: the mod id from fabric.mod.json / mods.toml when readable */
    public String modId;
    public String fileName;
    public String sha1;
    public String sha512;
    public long size;
    public boolean enabled = true;
    /** pinned entries are skipped by "update all" */
    public boolean pinned;
    /** true when this entry was pulled in as a dependency of another entry */
    public boolean dependency;
    /** ids of entries that need this one */
    public List<String> requiredBy = new ArrayList<>();
    /** ISO-8601 time of installation */
    public String installedAt;
    /** where the user can read about it (project page) */
    public String pageUrl;

    @Override
    public String toString() {
        return kind + ":" + (title == null ? fileName : title) + (enabled ? "" : " (disabled)");
    }
}

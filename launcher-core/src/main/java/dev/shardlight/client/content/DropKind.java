package dev.shardlight.client.content;

import dev.shardlight.client.game.GameDirs;

/** What a file dropped on the launcher window is, and where in a project it goes. */
public enum DropKind {
    MOD(GameDirs.MODS),
    RESOURCE_PACK(GameDirs.RESOURCE_PACKS),
    SHADER_PACK(GameDirs.SHADER_PACKS),
    WORLD(GameDirs.SAVES),
    SCREENSHOT(GameDirs.SCREENSHOTS),
    /** a .mrpack or CurseForge zip: becomes a new project, not a file in one */
    MODPACK(null),
    /** a .shardlight project export: becomes a new project */
    PROJECT_EXPORT(null),
    /** recognised but not droppable here (a data pack belongs inside a world) */
    DATA_PACK(null),
    UNKNOWN(null);

    private final String folder;

    DropKind(String folder) {
        this.folder = folder;
    }

    /** the game-directory folder the file is placed in, or null when it doesn't go into a project folder */
    public String folder() {
        return folder;
    }

    /** true when the drop goes into the selected project */
    public boolean intoProject() {
        return folder != null;
    }

    /** true when the drop makes a new project */
    public boolean makesProject() {
        return this == MODPACK || this == PROJECT_EXPORT;
    }

    public boolean accepted() {
        return intoProject() || makesProject();
    }
}

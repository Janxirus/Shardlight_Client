package dev.shardlight.client.content;

/** The kinds of add-on content a project holds, with the game-directory folder for each. */
public enum ContentKind {
    MOD("mods", "mod"),
    RESOURCE_PACK("resourcepacks", "resourcepack"),
    SHADER_PACK("shaderpacks", "shader"),
    DATAPACK("datapacks", "datapack"),
    MODPACK(null, "modpack");

    private final String folder;
    private final String modrinthType;

    ContentKind(String folder, String modrinthType) {
        this.folder = folder;
        this.modrinthType = modrinthType;
    }

    /** folder inside the game directory, null for modpacks (which become whole projects) */
    public String folder() {
        return folder;
    }

    /** Modrinth's project_type for this kind */
    public String modrinthType() {
        return modrinthType;
    }
}

package dev.shardlight.client.content;

/** Where an installed file came from. */
public enum ContentSource {
    MODRINTH,
    CURSEFORGE,
    /** an official Shardlight module or theme pack from the Shardlight content library */
    SHARDLIGHT,
    /** a file the user added by hand (drag and drop, import) */
    LOCAL,
    /** part of an imported modpack */
    MODPACK
}

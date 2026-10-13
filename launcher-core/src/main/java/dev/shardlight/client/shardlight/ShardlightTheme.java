package dev.shardlight.client.shardlight;

import java.util.List;

/**
 * One of the fifteen Shardlight themes. The same id selects the Shardlight UI mod's colours
 * ({@code "theme"} in {@code config/shardlight.json}) and names the matching resource and sound packs.
 *
 * @param id            the UI mod's {@code Theme} enum constant, for example {@code EMBER} or {@code DARK_GREEN}
 * @param displayName   the theme's name in the mod, for example "Dark Green"
 * @param packTitle     the resource pack's own title, for example "Mossgrove"
 * @param classic       one of the seven original themes (as opposed to the eight 4.0 "world" themes)
 * @param accent        accent colour, {@code #RRGGBB}
 * @param panel         panel colour, {@code #RRGGBB}
 * @param edge          edge colour, {@code #RRGGBB}
 * @param description   one English line describing the theme (as in the mod's theme picker)
 * @param uiPackFile    exact file name of the texture pack
 * @param soundPackFile exact file name of the sound pack
 * @param feel          a few words about the look, for example "moss, vines &amp; fireflies"
 * @param aliases       other names the theme is known by (for example "Nature" for Dark Green)
 */
public record ShardlightTheme(String id, String displayName, String packTitle, boolean classic, String accent,
                              String panel, String edge, String description, String uiPackFile, String soundPackFile,
                              String feel, List<String> aliases) {
    public ShardlightTheme {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    /** the pack file name for the texture pack ({@code sounds == false}) or the sound pack */
    public String packFile(boolean sounds) {
        return sounds ? soundPackFile : uiPackFile;
    }
}

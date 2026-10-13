package dev.shardlight.client.discord;

import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.settings.LauncherSettings;
import dev.shardlight.client.settings.SettingsService;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Where the Discord application id comes from, first match wins: Settings > Advanced ({@code settings.json}
 * {@code privacy.discordApplicationId}), the {@code shardlight.discordApplicationId} Java system property, then the id
 * baked into the build ({@code -Pshardlight.discordApplicationId} or {@code SHARDLIGHT_DISCORD_APP_ID}). The id is
 * public (it names the app that shows as "Playing ..."), but there is deliberately none built in: the owner creates the
 * application at discord.com/developers and puts its id here (docs/DISCORD.md).
 */
public final class DiscordApplicationIds {
    public static final String PROPERTY = "shardlight.discordApplicationId";
    private static final Pattern ID = Pattern.compile("[0-9]{15,22}");

    private DiscordApplicationIds() {
    }

    public static Supplier<String> fromSettings(SettingsService settings) {
        return () -> {
            LauncherSettings s = settings.get();
            String configured = s.privacy == null ? null : s.privacy.discordApplicationId;
            return configured != null && !configured.isBlank() ? configured.trim() : buildDefault();
        };
    }

    /** the id from the system property or the build; empty when none */
    public static String buildDefault() {
        String prop = System.getProperty(PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        return BuildInfo.discordApplicationId().orElse("");
    }

    /** a Discord application id is a number of 15 to 22 digits */
    public static boolean looksValid(String id) {
        return id != null && ID.matcher(id.trim()).matches();
    }
}

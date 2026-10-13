package dev.shardlight.client.auth;

import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.settings.LauncherSettings;
import dev.shardlight.client.settings.SettingsService;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Where the Azure application (client) id for Microsoft sign-in comes from, first match wins:
 * <ol>
 *   <li>Settings &gt; Accounts in the app ({@code settings.json} {@code auth.microsoftClientId});</li>
 *   <li>the {@code shardlight.msaClientId} Java system property (for example in the packaged launcher's
 *       {@code --java-options});</li>
 *   <li>the id baked in at build time ({@code -Pshardlight.msaClientId} or {@code SHARDLIGHT_MSA_CLIENT_ID}, see
 *       {@link BuildInfo#microsoftClientId()}).</li>
 * </ol>
 * There is deliberately no built-in id: the distributor registers their own app (docs/SIGN_IN.md).
 */
public final class MicrosoftClientIds {
    /** system property and build property name */
    public static final String PROPERTY = "shardlight.msaClientId";
    private static final Pattern GUID = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private MicrosoftClientIds() {
    }

    /** the client id from settings, falling back to the build default; read on every call */
    public static Supplier<String> fromSettings(SettingsService settings) {
        return of(() -> {
            LauncherSettings s = settings.get();
            return s.auth == null ? null : s.auth.microsoftClientId;
        });
    }

    /** {@code configured} when it is non-blank, otherwise {@link #buildDefault()} */
    public static Supplier<String> of(Supplier<String> configured) {
        return () -> {
            String v = configured.get();
            return v != null && !v.isBlank() ? v.trim() : buildDefault();
        };
    }

    /** the distributor's id from the system property or the bundled build properties; empty when none */
    public static String buildDefault() {
        String prop = System.getProperty(PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        return BuildInfo.microsoftClientId().orElse("");
    }

    /** true when {@code id} looks like an Azure application id (a GUID), for validating user input */
    public static boolean looksValid(String id) {
        return id != null && GUID.matcher(id.trim()).matches();
    }
}

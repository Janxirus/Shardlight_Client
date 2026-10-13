package dev.shardlight.client.preset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which Minecraft versions the video options in {@code options.txt} are known to work for. Option names change
 * between versions, so the presets only touch {@code options.txt} for a version whose key list was checked
 * against the real client jar ({@code VanillaOptionsLiveTest}); every other version keeps its video settings.
 */
public final class VanillaOptions {
    /** the Minecraft version the key list was checked against; its patch releases (26.3.1) count too */
    public static final String VERIFIED_FOR = "26.3";
    private static final String RESOURCE = "/preset/vanilla-options-26.3.txt";
    private static final Set<String> KEYS = load();

    private VanillaOptions() {
    }

    /** the {@code options.txt} keys the presets write on {@link #VERIFIED_FOR} */
    public static Set<String> keys() {
        return KEYS;
    }

    /** true when the presets may write video options for this Minecraft version */
    public static boolean verifiedFor(String minecraftVersion) {
        if (minecraftVersion == null) {
            return false;
        }
        String v = minecraftVersion.trim();
        return v.equals(VERIFIED_FOR) || v.startsWith(VERIFIED_FOR + ".");
    }

    private static Set<String> load() {
        try (InputStream in = VanillaOptions.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            Set<String> out = new LinkedHashSet<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.strip();
                    if (!t.isEmpty() && !t.startsWith("#")) {
                        out.add(t);
                    }
                }
            }
            return Set.copyOf(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

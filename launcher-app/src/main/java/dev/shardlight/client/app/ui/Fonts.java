package dev.shardlight.client.app.ui;

import javafx.scene.text.Font;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

/**
 * The bundled fonts: Inter for the interface, Inter Tabular (Inter with tabular figures switched on) for
 * sizes, speeds and counters, and JetBrains Mono for logs and code. All three are SIL Open Font License
 * (see docs/CREDITS.md). When a font can't be loaded the matching system font is used instead.
 */
public final class Fonts {
    private static final Logger LOG = Logger.getLogger(Fonts.class.getName());
    private static final List<String> SYSTEM_UI = List.of("Segoe UI Variable Text", "Segoe UI", "SF Pro Text",
        "Helvetica Neue", "Noto Sans", "Cantarell", "Ubuntu", "DejaVu Sans");
    private static final List<String> SYSTEM_MONO = List.of("Cascadia Mono", "Consolas", "SF Mono", "Menlo",
        "JetBrains Mono", "DejaVu Sans Mono", "Liberation Mono", "Monospaced");

    private static String ui;
    private static String tabular;
    private static String mono;

    private Fonts() {
    }

    /** loads the bundled fonts once; safe to call again */
    public static synchronized void load() {
        if (ui != null) {
            return;
        }
        boolean inter = load("Inter-Regular.ttf") & load("Inter-SemiBold.ttf");
        boolean interTab = load("InterTabular-Regular.ttf") & load("InterTabular-SemiBold.ttf");
        boolean jbMono = load("JetBrainsMono-Regular.ttf") & load("JetBrainsMono-Bold.ttf");
        List<String> families = Font.getFamilies();
        ui = inter ? "Inter" : pick(families, SYSTEM_UI);
        tabular = interTab ? "Inter Tabular" : ui;
        mono = jbMono ? "JetBrains Mono" : pick(families, SYSTEM_MONO);
        LOG.info("Fonts: interface " + ui + ", numbers " + tabular + ", code " + mono);
    }

    private static boolean load(String file) {
        try (InputStream in = Fonts.class.getResourceAsStream("/app/fonts/" + file)) {
            if (in == null) {
                LOG.warning("Bundled font missing: " + file);
                return false;
            }
            if (Font.loadFont(in, 14) == null) {
                LOG.warning("Couldn't load bundled font " + file + "; using a system font");
                return false;
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warning("Couldn't load bundled font " + file + ": " + e);
            return false;
        }
    }

    private static String pick(List<String> installed, List<String> preferred) {
        for (String f : preferred) {
            if (installed.contains(f)) {
                return f;
            }
        }
        return Font.getDefault().getFamily();
    }

    public static String ui() {
        load();
        return ui;
    }

    public static String tabular() {
        load();
        return tabular;
    }

    public static String mono() {
        load();
        return mono;
    }

    /**
     * A small stylesheet (as a data URI) that points the mono and tabular classes at whichever families
     * loaded. The main stylesheet can't know that in advance.
     */
    public static String stylesheet() {
        String css = ".mono, .code-block, .log-view, .device-code, .text-area.mono, .text-field.mono { -fx-font-family: \""
            + mono() + "\"; }\n"
            + ".tabular, .stat-value, .progress-text, .table-cell.numeric { -fx-font-family: \"" + tabular() + "\"; }\n";
        return "data:text/css;base64," + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
    }
}

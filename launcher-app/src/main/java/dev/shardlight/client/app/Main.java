package dev.shardlight.client.app;

import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.log.Logs;
import dev.shardlight.client.storage.AppPaths;
import javafx.application.Application;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Starts Shardlight Client. Options:
 * <pre>
 *   --data-dir PATH      keep all launcher data in PATH (portable use)
 *   --capture DIR        render every page to DIR/*.png and exit (documentation, visual checks)
 *   --capture-size WxH   window size for --capture (default 1360x860)
 *   --lang TAG           use this language for this run, for example de or en-XA (a test language)
 *   --page ID            open this page first (home, library, discover, shardlight, multiplayer, downloads, settings)
 * </pre>
 * The main class doesn't extend Application so JavaFX can run from the class path (portable zip).
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        LaunchArgs a = LaunchArgs.parse(args);
        if (a.dataDir() != null) {
            System.setProperty("shardlight.home", Path.of(a.dataDir()).toAbsolutePath().toString());
        }
        if (a.lang() != null && !a.lang().isBlank()) {
            // this run only: the language is not saved (see docs/LANGUAGES.md)
            System.setProperty("shardlight.language", a.lang());
        }
        AppPaths paths = AppPaths.defaults();
        try {
            paths.createDirectories();
        } catch (java.io.IOException e) {
            System.err.println("Shardlight Client can't create its data folder " + paths.root() + ": " + e.getMessage());
            System.exit(2);
        }
        Logs.init(paths.logs(), true);
        Logger.getLogger(Main.class.getName()).info(BuildInfo.NAME + " " + BuildInfo.VERSION + " starting; data in " + paths.root()
            + "; Java " + System.getProperty("java.version") + " on " + System.getProperty("os.name"));
        ShardlightApp.configure(paths, a);
        Application.launch(ShardlightApp.class, args);
    }

    /** parsed command line */
    public record LaunchArgs(String dataDir, String captureDir, int captureWidth, int captureHeight, String page, String lang) {
        static LaunchArgs parse(String[] args) {
            String data = null;
            String capture = null;
            String page = null;
            String lang = null;
            int w = 1360;
            int h = 860;
            for (int i = 0; i < args.length; i++) {
                String s = args[i];
                String next = i + 1 < args.length ? args[i + 1] : null;
                switch (s) {
                    case "--data-dir" -> { data = next; i++; }
                    case "--capture" -> { capture = next; i++; }
                    case "--page" -> { page = next; i++; }
                    case "--lang" -> { lang = next; i++; }
                    case "--capture-size" -> {
                        if (next != null && next.matches("\\d+x\\d+")) {
                            w = Integer.parseInt(next.substring(0, next.indexOf('x')));
                            h = Integer.parseInt(next.substring(next.indexOf('x') + 1));
                        }
                        i++;
                    }
                    default -> { }
                }
            }
            return new LaunchArgs(data, capture, w, h, page, lang);
        }
    }
}

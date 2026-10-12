package dev.shardlight.client.app;

import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Page;
import dev.shardlight.client.app.ui.Shell;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.settings.SettingsService;
import dev.shardlight.client.storage.AppPaths;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;

/** Wires services, the shell and pages together for the running app. */
public final class AppContext {
    private final AppPaths paths;
    private final Stage stage;
    private final SettingsService settings;
    private final Shell shell;
    private Scene scene;

    private AppContext(AppPaths paths, Stage stage, SettingsService settings) {
        this.paths = paths;
        this.stage = stage;
        this.settings = settings;
        this.shell = new Shell(ThemeManager.accent(settings.get().appearance));
        nav("home", "ui.nav.home", Icons.HOME);
        nav("library", "ui.nav.library", Icons.LIBRARY);
        nav("discover", "ui.nav.discover", Icons.DISCOVER);
        nav("shardlight", "ui.nav.shardlight", Icons.SHARD);
        nav("multiplayer", "ui.nav.multiplayer", Icons.SERVERS);
        nav("downloads", "ui.nav.downloads", Icons.DOWNLOAD);
        nav("settings", "ui.nav.settings", Icons.GEAR);
    }

    private void nav(String id, String key, String icon) {
        shell.addNav(new Shell.NavItem(id, key, icon, () -> placeholder(key)));
    }

    private static Page placeholder(String key) {
        VBox v = new VBox(UiKit.emptyState(Icons.SPARKLE, I18n.tr(key), "", null));
        return new Page() {
            @Override
            public String title() {
                return I18n.tr(key);
            }

            @Override
            public Region view() {
                return v;
            }
        };
    }

    public static AppContext create(AppPaths paths, Stage stage) throws LauncherException {
        SettingsService settings = new SettingsService(paths);
        return new AppContext(paths, stage, settings);
    }

    public AppPaths paths() {
        return paths;
    }

    public Stage stage() {
        return stage;
    }

    public SettingsService settings() {
        return settings;
    }

    public Shell shell() {
        return shell;
    }

    public Scene scene() {
        return scene;
    }

    void attach(Scene s) {
        this.scene = s;
        settings.addListener(st -> dev.shardlight.client.app.ui.Fx.ui(() -> ThemeManager.apply(s, st.appearance)));
    }

    void afterShow() {
    }

    List<String> captureSteps() {
        return List.of("home", "library", "discover", "shardlight", "multiplayer", "downloads", "settings");
    }

    void showForCapture(String id) {
        shell.navigate(id);
    }

    void close() {
    }
}

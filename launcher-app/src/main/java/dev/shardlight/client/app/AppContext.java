package dev.shardlight.client.app;

import dev.shardlight.client.app.flows.PlayFlow;
import dev.shardlight.client.app.pages.DiscoverPage;
import dev.shardlight.client.app.pages.DownloadsPage;
import dev.shardlight.client.app.pages.HomePage;
import dev.shardlight.client.app.pages.LibraryPage;
import dev.shardlight.client.app.pages.MultiplayerPage;
import dev.shardlight.client.app.pages.NewProjectWizard;
import dev.shardlight.client.app.pages.ProjectPage;
import dev.shardlight.client.app.pages.SettingsPage;
import dev.shardlight.client.app.pages.ShardlightPage;
import dev.shardlight.client.app.pages.GalleryPage;
import dev.shardlight.client.app.ui.AccountChip;
import dev.shardlight.client.app.ui.CommandPalette;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.PlayBar;
import dev.shardlight.client.app.ui.Shell;
import dev.shardlight.client.app.ui.SidebarProject;
import dev.shardlight.client.app.ui.TaskProgress;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.TopBarTools;
import dev.shardlight.client.app.ui.UiSettings;
import dev.shardlight.client.app.ui.WindowsChrome;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.services.LauncherServices;
import dev.shardlight.client.settings.LauncherSettings;
import dev.shardlight.client.storage.AppPaths;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The running app: services, window, shell and the few pieces of state pages share. */
public final class AppContext {
    private static final Logger LOG = Logger.getLogger(AppContext.class.getName());
    private final LauncherServices services;
    private final Stage stage;
    private final HostServices host;
    private final Shell shell;
    private final PlayFlow play;
    private final TopBarTools tools;
    private final CommandPalette palette;
    private final List<Runnable> projectListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> accountListeners = new CopyOnWriteArrayList<>();
    private Scene scene;

    private AppContext(LauncherServices services, Stage stage, HostServices host) {
        this.services = services;
        this.stage = stage;
        this.host = host;
        LauncherSettings.Appearance a = services.settings().get().appearance;
        applyLanguage(a.language);
        UiSettings.setReducedMotion(a.reducedMotion);
        ThemeManager.setScale(a.scale);
        this.shell = new Shell(ThemeManager.accent(a));
        this.play = new PlayFlow(this);
        shell.addNav(new Shell.NavItem("home", "ui.nav.home", Icons.HOME, () -> new HomePage(this)));
        shell.addNav(new Shell.NavItem("library", "ui.nav.library", Icons.LIBRARY, () -> new LibraryPage(this)));
        shell.addNav(new Shell.NavItem("discover", "ui.nav.discover", Icons.DISCOVER, () -> new DiscoverPage(this)));
        shell.addNav(new Shell.NavItem("shardlight", "ui.nav.shardlight", Icons.SHARD, () -> new ShardlightPage(this)));
        shell.addNav(new Shell.NavItem("multiplayer", "ui.nav.multiplayer", Icons.SERVERS, () -> new MultiplayerPage(this)));
        shell.addNav(new Shell.NavItem("downloads", "ui.nav.downloads", Icons.DOWNLOAD, () -> new DownloadsPage(this)));
        shell.addFooterNav(new Shell.NavItem("settings", "ui.nav.settings", Icons.GEAR, () -> new SettingsPage(this)));
        shell.addHidden("gallery", () -> new GalleryPage(this));
        shell.setCollapsed(a.sidebarCollapsed);
        shell.onCollapsedChanged(c -> {
            try {
                services.settings().update(s -> s.appearance.sidebarCollapsed = c);
            } catch (LauncherException e) {
                LOG.log(Level.WARNING, "Couldn't save the sidebar choice", e);
            }
        });
        shell.sidebarFooter().getChildren().add(new AccountChip(this).view());
        shell.setPinned(new SidebarProject(this).view());
        this.tools = new TopBarTools(this);
        this.palette = new CommandPalette(this);
        shell.setPalette(palette::show);
        shell.setPlayBar(new PlayBar(this).view());

        services.projects().addListener(() -> Fx.ui(() -> projectListeners.forEach(Runnable::run)));
        services.accounts().addListener(() -> Fx.ui(() -> accountListeners.forEach(Runnable::run)));
        DownloadsPage.installBadge(this);
    }

    public static AppContext create(AppPaths paths, Stage stage, HostServices host) throws LauncherException {
        return new AppContext(LauncherServices.create(paths), stage, host);
    }

    public LauncherServices services() {
        return services;
    }

    public Stage stage() {
        return stage;
    }

    public Shell shell() {
        return shell;
    }

    public Overlay overlay() {
        return shell.overlay();
    }

    public Scene scene() {
        return scene;
    }

    public PlayFlow play() {
        return play;
    }

    public TopBarTools tools() {
        return tools;
    }

    public CommandPalette palette() {
        return palette;
    }

    public void onProjectsChanged(Runnable r) {
        projectListeners.add(r);
    }

    public void onAccountsChanged(Runnable r) {
        accountListeners.add(r);
    }

    /** tell pages that projects changed (for changes made outside ProjectService, like content installs) */
    public void projectsChanged() {
        Fx.ui(() -> projectListeners.forEach(Runnable::run));
    }

    public void accountsChanged() {
        Fx.ui(() -> accountListeners.forEach(Runnable::run));
    }

    /** the project the Home page plays: the one picked last, else the most recently played */
    public Optional<Project> selectedProject() {
        String id = services.settings().get().selectedProjectId;
        if (id != null) {
            Optional<Project> p = services.projects().get(id);
            if (p.isPresent()) {
                return p;
            }
        }
        List<Project> all = services.projects().list();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    public void selectProject(String id) {
        try {
            services.settings().update(s -> {
                s.selectedProjectId = id;
                List<String> recent = new ArrayList<>(s.recentProjects);
                recent.remove(id);
                recent.add(0, id);
                while (recent.size() > 8) {
                    recent.remove(recent.size() - 1);
                }
                s.recentProjects = recent;
            });
        } catch (LauncherException e) {
            LOG.log(Level.WARNING, "Couldn't save the selected project", e);
        }
        projectsChanged();
    }

    public void openProject(String projectId) {
        shell.push(new ProjectPage(this, projectId));
    }

    /** open a project on one of its tabs: overview, content, worlds, backups, screenshots, performance, settings, logs */
    public void openProjectTab(String projectId, String tab) {
        if (!(shell.current() instanceof ProjectPage pp && projectId.equals(pp.projectId()))) {
            openProject(projectId);
        }
        if (shell.current() instanceof ProjectPage page && PROJECT_TABS.contains(tab)) {
            page.select(PROJECT_TABS.indexOf(tab));
        }
    }

    /** go to Settings, opened on {@code section} */
    public void openSettings(String section) {
        shell.navigate("settings");
        if (shell.current() instanceof SettingsPage sp) {
            sp.open(section);
        }
    }

    public void newProject(String templateId) {
        NewProjectWizard.show(this, templateId);
    }

    /** open a web page in the default browser */
    public void openUrl(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return;
        }
        try {
            if (host != null) {
                host.showDocument(url);
                return;
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "HostServices couldn't open " + url, e);
        }
        desktop(d -> d.browse(java.net.URI.create(url)));
    }

    /** show a folder in Explorer / Finder / the file manager, creating it first when missing */
    public void openFolder(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            overlay().toast("danger", I18n.tr("ui.error.openFolder", dir));
            return;
        }
        if (!desktop(d -> d.open(dir.toFile()))) {
            try {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                String cmd = os.contains("win") ? "explorer" : os.contains("mac") ? "open" : "xdg-open";
                new ProcessBuilder(cmd, dir.toString()).start();
            } catch (IOException e) {
                overlay().toast("danger", I18n.tr("ui.error.openFolder", dir));
            }
        }
    }

    @FunctionalInterface
    private interface DesktopAction {
        void run(Desktop d) throws IOException;
    }

    private static boolean desktop(DesktopAction action) {
        try {
            if (Desktop.isDesktopSupported()) {
                action.run(Desktop.getDesktop());
                return true;
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Desktop action failed", e);
        }
        return false;
    }

    static void applyLanguage(String tag) {
        I18n.setLocale(tag == null || tag.isBlank() ? Locale.getDefault() : Locale.forLanguageTag(tag));
    }

    void attach(Scene s) {
        this.scene = s;
        services.settings().addListener(st -> Fx.ui(() -> {
            UiSettings.setReducedMotion(st.appearance.reducedMotion);
            ThemeManager.apply(s, st.appearance);
            shell.setCollapsed(st.appearance.sidebarCollapsed);
            applyTitleBar();
        }));
    }

    void afterShow() {
        TaskProgress.attach(stage);
        Platform.runLater(this::applyTitleBar);
        play.checkForUpdatesQuietly();
    }

    /** on Windows, colour the native title bar like the window: its background and primary text */
    void applyTitleBar() {
        if (!WindowsChrome.supported() || scene == null) {
            return;
        }
        Region root = (Region) scene.getRoot();
        root.applyCss();
        Color bg = root.getBackground() != null && !root.getBackground().getFills().isEmpty()
            && root.getBackground().getFills().get(0).getFill() instanceof Color c ? c : null;
        Node title = root.lookup(".page-title");
        Color text = title instanceof Labeled l && l.getTextFill() instanceof Color c ? c : null;
        if (bg != null && text != null) {
            WindowsChrome.titleBar(stage, bg.getBrightness() < 0.5, bg, text);
        }
    }

    /** the pages, tabs, sections and dialogs --capture saves, in order */
    List<String> captureSteps() {
        List<String> steps = new ArrayList<>(List.of("home", "library"));
        for (String tab : PROJECT_TABS) {
            steps.add("project-" + tab);
        }
        steps.addAll(List.of("wizard", "discover", "shardlight", "multiplayer", "downloads"));
        for (String section : List.of("accounts", "appearance", "java", "downloads", "content", "privacy", "updates",
            "storage", "about")) {
            steps.add("settings-" + section);
        }
        steps.addAll(List.of("signin", "crash", "home-light", "library-light", "project-light", "discover-light",
            "settings-light"));
        // Part 2: the shell and the component gallery
        steps.addAll(List.of("gallery", "gallery-full", "palette", "notifications", "collapsed"));
        for (String page : List.of("home", "gallery")) {
            for (String theme : List.of("dark", "light", "contrast")) {
                for (String scale : List.of("100", "150")) {
                    steps.add("look-" + page + "-" + theme + "-" + scale);
                }
            }
        }
        steps.addAll(List.of("gallery-full-light", "gallery-full-contrast"));
        return steps;
    }

    /** show the app in {@code theme} (dark, light or contrast) at {@code scale} for --capture, not saved */
    private void captureLook(String theme, double scale) {
        LauncherSettings.Appearance a = dev.shardlight.client.util.Json.copy(services.settings().get().appearance,
            LauncherSettings.Appearance.class);
        a.theme = theme.equals("light") ? "light" : "dark";
        a.highContrast = theme.equals("contrast");
        a.scale = scale;
        ThemeManager.apply(scene, a);
    }

    private boolean showShellCapture(String id) {
        if (!id.equals("notifications")) {
            overlay().clearToasts();
        }
        if (id.startsWith("look-")) {
            String[] parts = id.split("-");
            captureLook(parts[2], Integer.parseInt(parts[3]) / 100.0);
            shell.setCollapsed(false);
            shell.navigate(parts[1]);
            return true;
        }
        switch (id) {
            case "gallery", "gallery-full" -> {
                captureLook("dark", 1);
                shell.navigate("gallery");
            }
            case "gallery-full-light", "gallery-full-contrast" -> {
                captureLook(id.substring("gallery-full-".length()), 1);
                shell.navigate("gallery");
            }
            case "palette" -> {
                captureLook("dark", 1);
                shell.navigate("home");
                palette.show();
                Platform.runLater(() -> {
                    if (scene.getFocusOwner() instanceof javafx.scene.control.TextField tf) {
                        tf.setText("ul");
                    }
                });
            }
            case "notifications" -> {
                shell.navigate("library");
                selectedProject().ifPresent(p -> overlay().toast("success", I18n.tr("ui.play.started", p.name)));
                overlay().toast("warning", I18n.tr("ui.signin.notRemembered"));
                overlay().toast("danger", I18n.tr("ui.downloads.toastFailed", I18n.tr("ui.downloads.other")),
                    new Overlay.Action(I18n.tr("ui.state.retry"), () -> { }));
                Platform.runLater(tools::showNotifications);
            }
            case "collapsed" -> {
                overlay().closePopover();
                shell.setCollapsed(true);
                shell.navigate("home");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static final List<String> PROJECT_TABS = List.of("overview", "content", "worlds", "backups", "screenshots",
        "performance", "settings", "logs");

    void showForCapture(String id) {
        while (overlay().hasOpenDialog()) {
            overlay().closeTop();
        }
        overlay().closePopover();
        if (showShellCapture(id)) {
            return;
        }
        if (id.startsWith("project-") && PROJECT_TABS.contains(id.substring(8))) {
            showProjectTab(PROJECT_TABS.indexOf(id.substring(8)));
            return;
        }
        if (id.startsWith("settings-") && !id.endsWith("-light")) {
            shell.navigate("settings");
            if (shell.current() instanceof SettingsPage sp) {
                sp.open(id.substring(9));
            }
            return;
        }
        switch (id) {
            case "wizard" -> {
                shell.navigate("library");
                newProject("shardlight-default");
            }
            case "signin" -> {
                shell.navigate("home");
                dev.shardlight.client.app.flows.SignInDialog.show(this, a -> { });
            }
            case "crash" -> {
                shell.navigate("home");
                selectedProject().ifPresent(p -> play.showEnded(p, CaptureSeed.exampleCrash(services)));
            }
            case "home-light", "library-light", "project-light", "discover-light", "settings-light" -> {
                LauncherSettings.Appearance light = dev.shardlight.client.util.Json.copy(services.settings().get().appearance,
                    LauncherSettings.Appearance.class);
                light.theme = "light";
                ThemeManager.apply(scene, light);
                switch (id) {
                    case "project-light" -> showProjectTab(1);
                    case "settings-light" -> {
                        shell.navigate("settings");
                        if (shell.current() instanceof SettingsPage sp) {
                            sp.open("appearance");
                        }
                    }
                    default -> shell.navigate(id.substring(0, id.length() - "-light".length()));
                }
            }
            default -> shell.navigate(id);
        }
    }

    private void showProjectTab(int index) {
        Optional<Project> p = selectedProject();
        if (p.isEmpty()) {
            return;
        }
        if (!(shell.current() instanceof ProjectPage)) {
            openProject(p.get().id);
        }
        if (shell.current() instanceof ProjectPage page) {
            page.select(index);
        }
    }

    void close() {
        services.close();
    }
}

package dev.shardlight.client.services;

import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.account.AccountService;
import dev.shardlight.client.auth.MicrosoftAuthService;
import dev.shardlight.client.auth.MicrosoftClientIds;
import dev.shardlight.client.auth.SecretStore;
import dev.shardlight.client.auth.SecretStores;
import dev.shardlight.client.catalog.CatalogProvider;
import dev.shardlight.client.catalog.CatalogRegistry;
import dev.shardlight.client.catalog.CurseForgeCatalog;
import dev.shardlight.client.catalog.InstallPlanner;
import dev.shardlight.client.catalog.ModrinthCatalog;
import dev.shardlight.client.content.ContentService;
import dev.shardlight.client.diagnostics.CrashAnalyzer;
import dev.shardlight.client.diagnostics.DiagnosticsExporter;
import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.java.JavaRuntimeService;
import dev.shardlight.client.launch.LaunchService;
import dev.shardlight.client.loader.LoaderService;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.minecraft.GameInstaller;
import dev.shardlight.client.minecraft.VersionManifestService;
import dev.shardlight.client.modpack.ModpackService;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.preset.PresetEngine;
import dev.shardlight.client.project.BackupService;
import dev.shardlight.client.project.NewProjectRequest;
import dev.shardlight.client.project.ProjectService;
import dev.shardlight.client.project.ScreenshotService;
import dev.shardlight.client.project.ServerListService;
import dev.shardlight.client.project.WorldService;
import dev.shardlight.client.settings.LauncherSettings;
import dev.shardlight.client.settings.SettingsService;
import dev.shardlight.client.shardlight.ShardlightCatalog;
import dev.shardlight.client.shardlight.ShardlightContentLibrary;
import dev.shardlight.client.shardlight.ShardlightService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.update.UpdateService;
import dev.shardlight.client.util.OsInfo;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds every service once and wires them together; the desktop app talks to the core only through this
 * object. Construction does no network work.
 */
public final class LauncherServices implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(LauncherServices.class.getName());
    /** secret store key of a CurseForge API key the user entered in Settings */
    public static final String CURSEFORGE_KEY_SECRET = "curseforge.apiKey";

    private final AppPaths paths;
    private final Endpoints endpoints;
    private final SettingsService settings;
    private final HttpService http;
    private final DownloadManager downloads;
    private final SecretStore secrets;
    private final VersionManifestService versions;
    private final GameInstaller installer;
    private final JavaRuntimeService java;
    private final LoaderService loaders;
    private final LaunchService launches;
    private final MicrosoftAuthService microsoft;
    private final AccountService accounts;
    private final ProjectService projects;
    private final BackupService backups;
    private final WorldService worlds;
    private final ScreenshotService screenshots;
    private final ServerListService servers;
    private final ContentService content;
    private final ModrinthCatalog modrinth;
    private final CurseForgeCatalog curseForge;
    private final CatalogRegistry catalogs;
    private final InstallPlanner planner;
    private final ModpackService modpacks;
    private final ShardlightCatalog shardlightCatalog;
    private final ShardlightContentLibrary shardlightLibrary;
    private final ShardlightService shardlight;
    private final PresetEngine presets;
    private final UpdateService updates;
    private final CrashAnalyzer crashAnalyzer;
    private final DiagnosticsExporter diagnostics;
    private final ProjectSetup setup;

    private LauncherServices(AppPaths paths, Endpoints endpoints, HttpService http, SecretStore secrets)
        throws LauncherException {
        this.paths = paths;
        this.endpoints = endpoints;
        try {
            paths.createDirectories();
        } catch (IOException e) {
            throw LauncherException.io(e, paths.root().toString());
        }
        this.settings = new SettingsService(paths);
        this.http = http;
        LauncherSettings.Downloads d = settings.get().downloads;
        this.downloads = new DownloadManager(http, paths.files(), d.maxConcurrent, d.maxRetries);
        this.secrets = secrets;

        this.versions = new VersionManifestService(http, endpoints, paths);
        this.installer = new GameInstaller(http, endpoints, paths, downloads, versions);
        this.java = new JavaRuntimeService(http, endpoints, paths, downloads);
        this.loaders = LoaderService.createDefault(http, endpoints, paths, downloads, installer, java);

        this.microsoft = new MicrosoftAuthService(http, endpoints, this::microsoftClientId);
        this.accounts = new AccountService(paths, secrets, microsoft);

        this.projects = new ProjectService(paths);
        this.backups = new BackupService(paths, projects);
        this.worlds = new WorldService(projects);
        this.screenshots = new ScreenshotService(projects);
        this.servers = new ServerListService(projects);
        this.crashAnalyzer = new CrashAnalyzer();
        this.launches = new LaunchService(paths, projects, loaders, installer, java,
            () -> settings.get().java.preferMojangRuntime, crashAnalyzer, OsInfo.current());

        this.content = new ContentService(paths, projects, downloads);
        this.modrinth = new ModrinthCatalog(http, endpoints);
        this.curseForge = new CurseForgeCatalog(http, endpoints, this::curseForgeKey);
        this.catalogs = new CatalogRegistry(List.<CatalogProvider>of(modrinth, curseForge));
        this.planner = new InstallPlanner();
        this.modpacks = new ModpackService(paths, projects,
            (name, mc, loader) -> projects.create(new NewProjectRequest(name, null, "modpack", mc, loader,
                Set.of(), null, null, null)),
            content, downloads, catalogs);

        this.shardlightCatalog = ShardlightCatalog.load();
        this.shardlightLibrary = new ShardlightContentLibrary(paths, http, downloads, shardlightCatalog);
        this.shardlight = new ShardlightService(paths, projects, shardlightCatalog, shardlightLibrary);
        this.presets = new PresetEngine(projects);
        this.updates = UpdateService.fromBundledConfig(http, BuildInfo.VERSION, downloads);

        this.diagnostics = new DiagnosticsExporter();
        this.setup = new ProjectSetup(this);

        settings.addListener(s -> downloads.setLimits(s.downloads.maxConcurrent, s.downloads.maxRetries));
    }

    /** the real services, with the operating system's secret store */
    public static LauncherServices create(AppPaths paths) throws LauncherException {
        return new LauncherServices(paths, Endpoints.DEFAULT, HttpService.create(), SecretStores.forCurrentOs(paths));
    }

    /** for tests: every endpoint on a local fixture server and an in-memory secret store */
    public static LauncherServices create(AppPaths paths, Endpoints endpoints, HttpService http, SecretStore secrets)
        throws LauncherException {
        return new LauncherServices(paths, endpoints, http, secrets);
    }

    /** the Microsoft application id: Settings, then the system property, then this build's own; empty when none */
    public String microsoftClientId() {
        return MicrosoftClientIds.fromSettings(settings).get();
    }

    /** the CurseForge API key the user stored, else the build's own; empty when CurseForge is switched off */
    public Optional<String> curseForgeKey() {
        if (!settings.get().catalog.curseForgeEnabled) {
            return Optional.empty();
        }
        Optional<String> key;
        try {
            key = secrets.get(CURSEFORGE_KEY_SECRET).filter(k -> !k.isBlank());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Couldn't read the CurseForge key from the secret store", e);
            key = Optional.empty();
        }
        if (key.isEmpty()) {
            key = BuildInfo.curseForgeApiKey();
        }
        key.ifPresent(Redactor::register);
        return key;
    }

    /** true when a CurseForge key is stored by the user or baked into the build (whether or not it's enabled) */
    public boolean hasCurseForgeKey() {
        try {
            if (secrets.get(CURSEFORGE_KEY_SECRET).filter(k -> !k.isBlank()).isPresent()) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // fall through to the build key
        }
        return BuildInfo.curseForgeApiKey().isPresent();
    }

    /** store (or with a blank value, forget) the user's CurseForge API key in the secret store */
    public void setCurseForgeKey(String key) {
        if (key == null || key.isBlank()) {
            secrets.delete(CURSEFORGE_KEY_SECRET);
        } else {
            Redactor.register(key.trim());
            secrets.put(CURSEFORGE_KEY_SECRET, key.trim());
        }
    }

    public AppPaths paths() {
        return paths;
    }

    public Endpoints endpoints() {
        return endpoints;
    }

    public SettingsService settings() {
        return settings;
    }

    public HttpService http() {
        return http;
    }

    public DownloadManager downloads() {
        return downloads;
    }

    public SecretStore secrets() {
        return secrets;
    }

    public VersionManifestService versions() {
        return versions;
    }

    public GameInstaller installer() {
        return installer;
    }

    public JavaRuntimeService java() {
        return java;
    }

    public LoaderService loaders() {
        return loaders;
    }

    public LaunchService launches() {
        return launches;
    }

    public MicrosoftAuthService microsoft() {
        return microsoft;
    }

    public AccountService accounts() {
        return accounts;
    }

    public ProjectService projects() {
        return projects;
    }

    public BackupService backups() {
        return backups;
    }

    public WorldService worlds() {
        return worlds;
    }

    public ScreenshotService screenshots() {
        return screenshots;
    }

    public ServerListService servers() {
        return servers;
    }

    public ContentService content() {
        return content;
    }

    public ModrinthCatalog modrinth() {
        return modrinth;
    }

    public CurseForgeCatalog curseForge() {
        return curseForge;
    }

    public CatalogRegistry catalogs() {
        return catalogs;
    }

    public InstallPlanner planner() {
        return planner;
    }

    public ModpackService modpacks() {
        return modpacks;
    }

    public ShardlightCatalog shardlightCatalog() {
        return shardlightCatalog;
    }

    public ShardlightContentLibrary shardlightLibrary() {
        return shardlightLibrary;
    }

    public ShardlightService shardlight() {
        return shardlight;
    }

    public PresetEngine presets() {
        return presets;
    }

    public UpdateService updates() {
        return updates;
    }

    public CrashAnalyzer crashAnalyzer() {
        return crashAnalyzer;
    }

    public DiagnosticsExporter diagnostics() {
        return diagnostics;
    }

    /** high-level project steps that span several services (setting up Shardlight, Fabric API, presets) */
    public ProjectSetup setup() {
        return setup;
    }

    @Override
    public void close() {
        for (var p : launches.runningAll()) {
            LOG.info("Leaving the game of project " + p.projectId() + " running");
        }
        downloads.close();
    }
}

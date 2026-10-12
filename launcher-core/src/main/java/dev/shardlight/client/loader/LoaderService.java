package dev.shardlight.client.loader;

import dev.shardlight.client.download.DownloadManager;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.java.JavaRuntimeService;
import dev.shardlight.client.minecraft.GameInstaller;
import dev.shardlight.client.minecraft.model.VersionJson;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.PathSafety;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * The mod loaders the launcher can install, and the step that turns a project's Minecraft version plus
 * {@link LoaderSpec} into the version id to launch. Vanilla needs no loader; for a loader whose
 * {@code versions/<id>/<id>.json} is already there and readable, nothing is fetched (so installed projects start
 * offline). Blocking and thread-safe; installs of the same loader version run one at a time.
 */
public final class LoaderService {
    private static final Logger LOG = Logger.getLogger(LoaderService.class.getName());

    private final Map<LoaderType, LoaderProvider> providers = new EnumMap<>(LoaderType.class);
    private final GameInstaller installer;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** @param providers one per loader type (later entries replace earlier ones of the same type) */
    public LoaderService(List<LoaderProvider> providers, GameInstaller installer) {
        for (LoaderProvider p : providers) {
            this.providers.put(p.type(), p);
        }
        this.installer = installer;
    }

    /** Fabric, Quilt, Forge and NeoForge, on the given services */
    public static LoaderService createDefault(HttpService http, Endpoints endpoints, AppPaths paths, DownloadManager downloads,
                                              GameInstaller installer, JavaRuntimeService java) {
        return new LoaderService(List.of(
            new FabricProvider(http, endpoints, paths),
            new QuiltProvider(http, endpoints, paths),
            new ForgeProvider(http, endpoints, paths, downloads, installer, java),
            new NeoForgeProvider(http, endpoints, paths, downloads, installer, java)), installer);
    }

    /** the provider of {@code type}; empty for vanilla and for loaders this launcher can't install */
    public Optional<LoaderProvider> provider(LoaderType type) {
        return type == null ? Optional.empty() : Optional.ofNullable(providers.get(type));
    }

    /** the loader types that can be installed, in enum order (vanilla excluded) */
    public List<LoaderType> available() {
        return List.copyOf(providers.keySet());
    }

    /** the loader's versions for Minecraft {@code mc}, newest first; empty for vanilla or an unknown loader */
    public List<LoaderVersion> versions(LoaderType type, String mc, CancellationToken cancel) throws LauncherException {
        if (type == null || type == LoaderType.VANILLA) {
            return List.of();
        }
        Optional<LoaderProvider> p = provider(type);
        return p.isEmpty() ? List.of() : p.get().versions(mc, cancel);
    }

    /**
     * The version id to launch for Minecraft {@code mc} with {@code spec}: {@code mc} itself for vanilla, otherwise
     * the loader's version, installing it when its version file is missing or unreadable. A loader without a
     * version picks the recommended one.
     *
     * @throws LauncherException {@link ErrorCode#UNSUPPORTED_VERSION} when the loader has no version for {@code mc}
     */
    public String ensureInstalled(String mc, LoaderSpec spec, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        return ensure(mc, spec, false, progress, cancel);
    }

    /**
     * Install the loader again even when its version file exists (for example when files only its installer can
     * create are missing). Returns the version id. Vanilla returns {@code mc}.
     */
    public String reinstall(String mc, LoaderSpec spec, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        return ensure(mc, spec, true, progress, cancel);
    }

    private String ensure(String mc, LoaderSpec spec, boolean force, ProgressListener progress, CancellationToken cancel)
        throws LauncherException {
        CancellationToken c = cancel == null ? CancellationToken.NONE : cancel;
        if (mc == null || mc.isBlank() || !PathSafety.isSafeSegment(mc.trim())) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(mc));
        }
        String minecraft = mc.trim();
        if (spec == null || spec.isVanilla()) {
            return minecraft;
        }
        LoaderProvider provider = provider(spec.type).orElseThrow(() ->
            new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "loader.error.noProvider", spec.type.displayName()));
        String version = spec.version == null ? null : spec.version.trim();
        if (version == null || version.isEmpty()) {
            version = recommended(provider, minecraft, c);
        }
        String expected = provider.versionId(minecraft, version);
        if (!force && isValid(expected, minecraft)) {
            return expected;
        }
        ReentrantLock lock = locks.computeIfAbsent(spec.type + "|" + minecraft + "|" + version, k -> new ReentrantLock());
        try {
            while (!lock.tryLock(200, TimeUnit.MILLISECONDS)) {
                c.check();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LauncherException.cancelled();
        }
        try {
            if (!force && isValid(expected, minecraft)) {
                return expected; // another thread installed it meanwhile
            }
            String id = provider.install(minecraft, version, progress, c);
            LOG.info("Loader ready: " + spec.type.displayName() + " " + version + " for " + minecraft + " -> " + id);
            return id;
        } finally {
            lock.unlock();
        }
    }

    private static String recommended(LoaderProvider provider, String mc, CancellationToken c) throws LauncherException {
        List<LoaderVersion> all = provider.versions(mc, c);
        for (LoaderVersion v : all) {
            if (v.recommended()) {
                return v.version();
            }
        }
        if (all.isEmpty()) {
            throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "loader.error.noVersions",
                provider.type().displayName(), mc);
        }
        return all.get(0).version();
    }

    /** the version file exists, parses and inherits from {@code mc} */
    private boolean isValid(String id, String mc) {
        if (id == null || !PathSafety.isSafeSegment(id) || !installer.hasVersionJson(id)) {
            return false;
        }
        try {
            VersionJson v = installer.readVersionJson(id);
            return id.equals(v.id()) && mc.equals(v.inheritsFrom()) && v.mainClass() != null;
        } catch (LauncherException e) {
            return false;
        }
    }
}

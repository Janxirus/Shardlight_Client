package dev.shardlight.client.catalog;

import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.task.CancellationToken;

import java.util.List;

/**
 * A content catalog (Modrinth, CurseForge). Every method blocks on the network, so call it off the UI
 * thread; implementations are thread-safe. Failures are {@link LauncherException}s: {@code NOT_FOUND},
 * {@code NETWORK}, {@code HTTP_STATUS}, {@code RATE_LIMITED}, {@code INVALID_DATA}, and
 * {@code API_KEY_MISSING} for a provider that isn't {@link #isAvailable() available}.
 */
public interface CatalogProvider {
    /** stable id: "modrinth", "curseforge" */
    String id();

    /** name to show ("Modrinth") */
    String displayName();

    /** false when the provider can't be used (CurseForge without an approved API key) */
    boolean isAvailable();

    /** i18n key explaining why the provider is unavailable; null when available */
    String unavailableReasonKey();

    /** search projects */
    SearchPage search(SearchQuery query, CancellationToken cancel) throws LauncherException;

    /** one project by id or slug */
    CatalogProject project(String idOrSlug, CancellationToken cancel) throws LauncherException;

    /**
     * Versions of a project, newest first. {@code minecraftVersion} and {@code loader} filter the list; null
     * (or {@link LoaderType#VANILLA}) means all.
     */
    List<CatalogVersion> versions(String projectId, String minecraftVersion, LoaderType loader, CancellationToken cancel)
        throws LauncherException;

    /** one version by id */
    CatalogVersion version(String versionId, CancellationToken cancel) throws LauncherException;

    /** category names usable in {@link SearchQuery#categories()} for this kind of content */
    List<String> categories(ContentKind kind, CancellationToken cancel) throws LauncherException;
}

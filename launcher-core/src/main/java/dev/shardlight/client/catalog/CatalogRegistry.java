package dev.shardlight.client.catalog;

import dev.shardlight.client.content.ContentSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** The catalog providers the launcher knows, in display order. Thread-safe. */
public final class CatalogRegistry {
    /** provider id of Modrinth */
    public static final String MODRINTH = "modrinth";
    /** provider id of CurseForge */
    public static final String CURSEFORGE = "curseforge";

    private final List<CatalogProvider> providers = new CopyOnWriteArrayList<>();

    /** a registry holding {@code providers} (later ones with an existing id are ignored) */
    public CatalogRegistry(Collection<? extends CatalogProvider> providers) {
        for (CatalogProvider p : providers) {
            register(p);
        }
    }

    /** a registry holding {@code providers} */
    public CatalogRegistry(CatalogProvider... providers) {
        this(List.of(providers));
    }

    /** add a provider; returns false when one with the same id is already registered */
    public synchronized boolean register(CatalogProvider provider) {
        if (provider == null || get(provider.id()).isPresent()) {
            return false;
        }
        providers.add(provider);
        return true;
    }

    /** the provider with this id (case-insensitive) */
    public Optional<CatalogProvider> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (CatalogProvider p : providers) {
            if (p.id().equalsIgnoreCase(id)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /** every provider, available or not */
    public List<CatalogProvider> all() {
        return List.copyOf(providers);
    }

    /** the providers that can be used right now */
    public List<CatalogProvider> available() {
        List<CatalogProvider> out = new ArrayList<>();
        for (CatalogProvider p : providers) {
            if (p.isAvailable()) {
                out.add(p);
            }
        }
        return out;
    }

    /** the content source recorded for files from provider {@code id} (LOCAL for unknown ids) */
    public static ContentSource sourceOf(String id) {
        if (id == null) {
            return ContentSource.LOCAL;
        }
        return switch (id.toLowerCase(Locale.ROOT)) {
            case MODRINTH -> ContentSource.MODRINTH;
            case CURSEFORGE -> ContentSource.CURSEFORGE;
            default -> ContentSource.LOCAL;
        };
    }

    /** the provider id for a content source, or null when the source isn't a catalog */
    public static String providerIdOf(ContentSource source) {
        if (source == null) {
            return null;
        }
        return switch (source) {
            case MODRINTH -> MODRINTH;
            case CURSEFORGE -> CURSEFORGE;
            default -> null;
        };
    }
}

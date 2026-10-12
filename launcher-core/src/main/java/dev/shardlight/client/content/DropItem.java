package dev.shardlight.client.content;

import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;

import java.nio.file.Path;
import java.util.Set;

/**
 * One dropped file and what the launcher made of it.
 *
 * @param file    the dropped file or folder
 * @param kind    what it is
 * @param reason  for {@link DropKind#UNKNOWN} and {@link DropKind#DATA_PACK}: the i18n key that says why it was refused
 * @param loaders for mods: the loaders the jar declares (empty when it declares none that is known)
 */
public record DropItem(Path file, DropKind kind, String reason, Set<LoaderType> loaders) {
    public DropItem {
        loaders = loaders == null ? Set.of() : Set.copyOf(loaders);
    }

    public String fileName() {
        return file.getFileName() == null ? file.toString() : file.getFileName().toString();
    }

    /** true when the mod declares loaders and none of them is the project's */
    public boolean wrongLoaderFor(LoaderSpec loader) {
        if (kind != DropKind.MOD || loaders.isEmpty()) {
            return false;
        }
        if (loader == null || loader.isVanilla()) {
            return true;
        }
        return !loaders.contains(loader.type);
    }
}

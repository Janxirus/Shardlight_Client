package dev.shardlight.client.modpack;

import dev.shardlight.client.loader.LoaderSpec;

import java.util.List;

/**
 * What a modpack update would do, without having done anything.
 *
 * @param packName    the new pack's name
 * @param samePack    false when the new pack has another name than the one installed
 * @param fromVersion the installed pack version (may be null)
 * @param toVersion   the new pack version (may be null)
 * @param fromMinecraft installed Minecraft version
 * @param toMinecraft   Minecraft version of the new pack
 * @param fromLoader  installed loader
 * @param toLoader    loader of the new pack
 * @param items       every file the update touches or deliberately leaves alone, sorted by path
 */
public record UpdatePlan(String packName, boolean samePack, String fromVersion, String toVersion, String fromMinecraft,
                         String toMinecraft, LoaderSpec fromLoader, LoaderSpec toLoader, List<UpdateItem> items) {
    public UpdatePlan {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public long count(UpdateAction action) {
        return items.stream().filter(i -> i.action() == action).count();
    }

    /** true when the update changes any file */
    public boolean changesFiles() {
        return items.stream().anyMatch(i -> i.action().writes());
    }

    public boolean changesGame() {
        return !java.util.Objects.equals(fromMinecraft, toMinecraft) || !sameLoader();
    }

    private boolean sameLoader() {
        boolean av = fromLoader == null || fromLoader.isVanilla();
        boolean bv = toLoader == null || toLoader.isVanilla();
        if (av || bv) {
            return av == bv;
        }
        return fromLoader.type == toLoader.type && java.util.Objects.equals(fromLoader.version, toLoader.version);
    }
}

package dev.shardlight.client.loader;

/** A loader choice for a project: type plus loader version (null version for vanilla). */
public final class LoaderSpec {
    public LoaderType type = LoaderType.VANILLA;
    /** loader version, for example "0.19.5" for Fabric; null for vanilla */
    public String version;

    public LoaderSpec() {
    }

    public LoaderSpec(LoaderType type, String version) {
        this.type = type;
        this.version = type == LoaderType.VANILLA ? null : version;
    }

    public static LoaderSpec vanilla() {
        return new LoaderSpec(LoaderType.VANILLA, null);
    }

    public boolean isVanilla() {
        return type == null || type == LoaderType.VANILLA;
    }

    @Override
    public String toString() {
        return isVanilla() ? "Vanilla" : type.displayName() + " " + version;
    }
}

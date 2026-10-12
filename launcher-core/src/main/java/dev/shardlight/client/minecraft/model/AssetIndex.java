package dev.shardlight.client.minecraft.model;

import com.google.gson.annotations.SerializedName;

import java.util.Map;

/**
 * An asset index file ({@code assets/indexes/<id>.json}).
 *
 * @param objects        resource path to object
 * @param virtual        {@code legacy} index: objects must also be copied to {@code assets/virtual/<id>/<path>}
 * @param mapToResources {@code pre-1.6} index: objects must be copied to {@code <gameDir>/resources/<path>}
 */
public record AssetIndex(Map<String, AssetObject> objects, Boolean virtual,
                         @SerializedName("map_to_resources") Boolean mapToResources) {
    public AssetIndex {
        objects = ModelCopies.map(objects);
    }

    public boolean isVirtual() {
        return Boolean.TRUE.equals(virtual);
    }

    public boolean isMapToResources() {
        return Boolean.TRUE.equals(mapToResources);
    }
}

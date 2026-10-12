package dev.shardlight.client.minecraft;

import com.google.gson.JsonParseException;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.minecraft.model.AssetIndex;
import dev.shardlight.client.minecraft.model.AssetObject;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Json;
import dev.shardlight.client.util.PathSafety;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Asset index handling: objects live content-addressed in {@code assets/objects/<ab>/<hash>}; a
 * {@code virtual} index also needs every object under {@code assets/virtual/<id>/<path>}, and a
 * {@code map_to_resources} index needs them under {@code <gameDir>/resources/<path>} (copied at launch).
 */
final class Assets {
    private Assets() {
    }

    /** read and validate an index file */
    static AssetIndex read(Path indexFile) throws LauncherException {
        String text;
        try {
            text = Files.readString(indexFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw LauncherException.io(e, indexFile.toString());
        }
        AssetIndex index;
        try {
            index = Json.read(text, AssetIndex.class);
        } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "install.error.assetIndexInvalid", indexFile.getFileName().toString());
        }
        if (index == null) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "install.error.assetIndexInvalid", indexFile.getFileName().toString());
        }
        for (AssetObject o : index.objects().values()) {
            if (!HashAlgo.SHA1.isValidHex(o.hash())) {
                throw new LauncherException(ErrorCode.INVALID_DATA, "install.error.assetIndexInvalid", indexFile.getFileName().toString());
            }
        }
        return index;
    }

    static Path objectPath(Path assetsRoot, String hash) {
        String h = hash.toLowerCase(Locale.ROOT);
        return assetsRoot.resolve("objects").resolve(h.substring(0, 2)).resolve(h);
    }

    /** one file per distinct object, fetched from {@code resources/<ab>/<hash>} */
    static List<PlannedFile> objects(AssetIndex index, Path assetsRoot, URI resources) throws LauncherException {
        Map<String, PlannedFile> byHash = new LinkedHashMap<>();
        for (Map.Entry<String, AssetObject> e : index.objects().entrySet()) {
            String h = e.getValue().hash().toLowerCase(Locale.ROOT);
            if (byHash.containsKey(h)) {
                continue;
            }
            URI url = InstallPlanner.resolveUrl(resources, h.substring(0, 2) + "/" + h);
            byHash.put(h, new PlannedFile(objectPath(assetsRoot, h), url, HashAlgo.SHA1, h, e.getValue().sizeOrUnknown(), e.getKey()));
        }
        return new ArrayList<>(byHash.values());
    }

    /** the copies a virtual (or map_to_resources) index needs under {@code dir}, checked by the object hash */
    static List<PlannedFile> copies(AssetIndex index, Path dir) throws LauncherException {
        List<PlannedFile> out = new ArrayList<>();
        for (Map.Entry<String, AssetObject> e : index.objects().entrySet()) {
            Path target = PathSafety.resolveInside(dir, e.getKey());
            String h = e.getValue().hash().toLowerCase(Locale.ROOT);
            out.add(new PlannedFile(target, null, HashAlgo.SHA1, h, e.getValue().sizeOrUnknown(), e.getKey()));
        }
        return out;
    }

    /** copy every object to {@code dir/<path>} unless a copy with the right size is already there */
    static int copyObjects(AssetIndex index, Path assetsRoot, Path dir, CancellationToken cancel) throws LauncherException {
        int copied = 0;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw LauncherException.io(e, dir.toString());
        }
        for (PlannedFile copy : copies(index, dir)) {
            cancel.check();
            if (copy.presentWithSize()) {
                continue;
            }
            Path src = objectPath(assetsRoot, copy.hash());
            if (!Files.isRegularFile(src)) {
                throw new LauncherException(ErrorCode.NOT_FOUND, "install.error.assetMissing", copy.label());
            }
            PathSafety.requireNoLinkEscape(dir, copy.path());
            try {
                FileOps.copy(src, copy.path());
            } catch (IOException e) {
                throw LauncherException.io(e, copy.path().toString());
            }
            copied++;
        }
        return copied;
    }
}

package dev.shardlight.client.modpack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.shardlight.client.catalog.JsonFields;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.util.PathSafety;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed CurseForge {@code manifest.json}: {@code minecraft{version, modLoaders[{id: "fabric-0.19.5",
 * primary}]}}, {@code manifestType} "minecraftModpack", {@code name}, {@code version}, {@code author},
 * {@code files[{projectID, fileID, required}]} and {@code overrides} (folder name, default "overrides").
 */
record CurseManifest(String name, String version, String author, String minecraft, LoaderSpec loader, List<FileRef> files,
                     String overrides, List<String> problems) {

    /** one referenced file */
    record FileRef(long projectId, long fileId, boolean required) {
    }

    static CurseManifest parse(String json) throws LauncherException {
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                throw new JsonParseException("not an object");
            }
            o = e.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "modpack.error.badIndex", ModpackService.CF_MANIFEST);
        }
        List<String> problems = new ArrayList<>();
        if (!"minecraftModpack".equals(JsonFields.str(o, "manifestType"))) {
            problems.add(I18n.tr("modpack.problem.notCurseForge"));
        }
        JsonObject mc = JsonFields.obj(o, "minecraft");
        String mcVersion = JsonFields.str(mc, "version");
        if (mcVersion == null || mcVersion.isBlank()) {
            problems.add(I18n.tr("modpack.problem.noMinecraft"));
        }
        LoaderSpec loader = LoaderSpec.vanilla();
        List<JsonObject> loaders = JsonFields.objects(mc, "modLoaders");
        JsonObject chosen = loaders.stream().filter(l -> JsonFields.bool(l, "primary", false)).findFirst()
            .orElse(loaders.isEmpty() ? null : loaders.get(0));
        if (chosen != null) {
            LoaderSpec l = ModpackService.curseLoader(JsonFields.str(chosen, "id"));
            if (l == null) {
                problems.add(I18n.tr("modpack.problem.unknownDependency", String.valueOf(JsonFields.str(chosen, "id"))));
                loader = null;
            } else {
                loader = l;
            }
        }
        List<FileRef> files = new ArrayList<>();
        for (JsonObject f : JsonFields.objects(o, "files")) {
            long pid = JsonFields.lng(f, "projectID", -1);
            long fid = JsonFields.lng(f, "fileID", -1);
            if (pid <= 0 || fid <= 0) {
                problems.add(I18n.tr("modpack.problem.badFileRef", pid, fid));
                continue;
            }
            files.add(new FileRef(pid, fid, JsonFields.bool(f, "required", true)));
        }
        String overrides = JsonFields.str(o, "overrides");
        overrides = overrides == null || overrides.isBlank() ? "overrides" : overrides.trim();
        if (!PathSafety.isSafeSegment(overrides)) {
            problems.add(I18n.tr("modpack.problem.unsafePath", overrides));
        }
        String name = JsonFields.str(o, "name");
        return new CurseManifest(name == null || name.isBlank() ? "Modpack" : name.strip(), JsonFields.str(o, "version"),
            JsonFields.str(o, "author"), mcVersion, loader, files, overrides, problems);
    }

    /** refuse manifests this launcher can't install */
    void requireValid() throws LauncherException {
        if (minecraft == null || minecraft.isBlank()) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "modpack.problem.noMinecraft");
        }
        if (loader == null) {
            throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "modpack.problem.unknownDependency", "modLoaders");
        }
        if (!PathSafety.isSafeSegment(overrides)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", overrides);
        }
    }
}

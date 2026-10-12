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
import dev.shardlight.client.loader.LoaderType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed {@code modrinth.index.json}: {@code formatVersion} 1, {@code game} "minecraft", {@code versionId},
 * {@code name}, {@code summary}, {@code files[]} ({@code path}, {@code hashes{sha1, sha512}},
 * {@code env{client, server}}, {@code downloads[]}, {@code fileSize}) and {@code dependencies}
 * ({@code minecraft} plus one of {@code fabric-loader}, {@code quilt-loader}, {@code forge}, {@code neoforge}).
 */
record Mrpack(int formatVersion, String game, String versionId, String name, String summary, List<FileRef> files,
              Map<String, String> dependencies, List<String> problems) {

    /** one file to download */
    record FileRef(String path, String sha1, String sha512, String envClient, String envServer, List<String> downloads,
                   long fileSize) {
    }

    static Mrpack parse(String json) throws LauncherException {
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                throw new JsonParseException("not an object");
            }
            o = e.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "modpack.error.badIndex", ModpackService.MRPACK_INDEX);
        }
        List<FileRef> files = new ArrayList<>();
        for (JsonObject f : JsonFields.objects(o, "files")) {
            JsonObject hashes = JsonFields.obj(f, "hashes");
            JsonObject env = JsonFields.obj(f, "env");
            files.add(new FileRef(String.valueOf(JsonFields.str(f, "path")), lower(JsonFields.str(hashes, "sha1")),
                lower(JsonFields.str(hashes, "sha512")), env == null ? null : JsonFields.str(env, "client"),
                env == null ? null : JsonFields.str(env, "server"), JsonFields.strings(f, "downloads"),
                JsonFields.lng(f, "fileSize", -1)));
        }
        Map<String, String> deps = new LinkedHashMap<>();
        JsonObject d = JsonFields.obj(o, "dependencies");
        if (d != null) {
            for (Map.Entry<String, JsonElement> e : d.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    deps.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        int formatVersion = (int) JsonFields.lng(o, "formatVersion", 0);
        String game = JsonFields.str(o, "game");
        List<String> problems = new ArrayList<>();
        if (formatVersion != 1) {
            problems.add(I18n.tr("modpack.problem.formatVersion", formatVersion));
        }
        if (!"minecraft".equals(game)) {
            problems.add(I18n.tr("modpack.problem.game", String.valueOf(game)));
        }
        if (deps.get("minecraft") == null || deps.get("minecraft").isBlank()) {
            problems.add(I18n.tr("modpack.problem.noMinecraft"));
        }
        int loaders = 0;
        for (String k : deps.keySet()) {
            if (k.equals("minecraft")) {
                continue;
            }
            if (ModpackService.mrpackLoaders().containsKey(k)) {
                loaders++;
            } else {
                problems.add(I18n.tr("modpack.problem.unknownDependency", k));
            }
        }
        if (loaders > 1) {
            problems.add(I18n.tr("modpack.problem.severalLoaders"));
        }
        String name = JsonFields.str(o, "name");
        return new Mrpack(formatVersion, game, JsonFields.str(o, "versionId"), name == null || name.isBlank() ? "Modpack" : name.strip(),
            JsonFields.str(o, "summary"), files, deps, problems);
    }

    String minecraft() {
        return dependencies.get("minecraft");
    }

    LoaderSpec loader() {
        for (Map.Entry<String, String> e : dependencies.entrySet()) {
            LoaderType t = ModpackService.mrpackLoaders().get(e.getKey());
            if (t != null) {
                return new LoaderSpec(t, e.getValue());
            }
        }
        return LoaderSpec.vanilla();
    }

    /** refuse packs this launcher can't install */
    void requireValid() throws LauncherException {
        if (formatVersion != 1) {
            throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "modpack.problem.formatVersion", formatVersion);
        }
        if (!"minecraft".equals(game)) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "modpack.problem.game", String.valueOf(game));
        }
        if (minecraft() == null || minecraft().isBlank()) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "modpack.problem.noMinecraft");
        }
        int loaders = 0;
        for (String k : dependencies.keySet()) {
            if (k.equals("minecraft")) {
                continue;
            }
            if (!ModpackService.mrpackLoaders().containsKey(k)) {
                throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "modpack.problem.unknownDependency", k);
            }
            loaders++;
        }
        if (loaders > 1) {
            throw new LauncherException(ErrorCode.UNSUPPORTED_VERSION, "modpack.problem.severalLoaders");
        }
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(java.util.Locale.ROOT);
    }
}

package dev.shardlight.client.minecraft;

import com.google.gson.JsonParseException;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.minecraft.model.Argument;
import dev.shardlight.client.minecraft.model.AssetIndexInfo;
import dev.shardlight.client.minecraft.model.DownloadInfo;
import dev.shardlight.client.minecraft.model.JavaVersionInfo;
import dev.shardlight.client.minecraft.model.Library;
import dev.shardlight.client.minecraft.model.LoggingInfo;
import dev.shardlight.client.minecraft.model.VersionJson;
import dev.shardlight.client.util.PathSafety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@code versions/<id>/<id>.json} and merges the {@code inheritsFrom} chain the way the official
 * launcher does:
 * <ul>
 *   <li>{@code mainClass}, {@code minecraftArguments}, {@code jar}, {@code type}: the child's when set</li>
 *   <li>{@code assetIndex}, {@code assets}, {@code javaVersion}, {@code logging}: inherited when the child lacks them</li>
 *   <li>{@code downloads}: merged per entry, the child's entries win</li>
 *   <li>{@code arguments.game} and {@code arguments.jvm}: the parent's, then the child's</li>
 *   <li>{@code libraries}: the child's first, then the parent's; a parent library whose
 *       {@code group:artifact[:classifier]} the child already lists is dropped (Fabric's ASM replaces an older one)</li>
 * </ul>
 * Duplicates inside one version's own list are kept, because they are usually OS-specific alternatives
 * that the rules separate later.
 */
public final class VersionResolver {
    /** longest inheritance chain accepted */
    public static final int MAX_DEPTH = 16;

    private final Path versionsDir;

    public VersionResolver(Path versionsDir) {
        this.versionsDir = versionsDir.toAbsolutePath().normalize();
    }

    public Path versionsDir() {
        return versionsDir;
    }

    /** {@code versions/<id>/<id>.json}; throws {@link ErrorCode#UNSAFE_PATH} for ids that aren't plain names */
    public Path jsonPath(String id) throws LauncherException {
        return versionDir(id).resolve(id + ".json");
    }

    /** {@code versions/<id>/<id>.jar} */
    public Path jarPath(String id) throws LauncherException {
        return versionDir(id).resolve(id + ".jar");
    }

    /** {@code versions/<id>} */
    public Path versionDir(String id) throws LauncherException {
        if (id == null || !PathSafety.isSafeSegment(id)) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(id));
        }
        return PathSafety.resolveInside(versionsDir, id);
    }

    /** true when the version's JSON file exists */
    public boolean exists(String id) {
        try {
            return Files.isRegularFile(jsonPath(id));
        } catch (LauncherException e) {
            return false;
        }
    }

    /** read and parse one version JSON (no inheritance) */
    public VersionJson read(String id) throws LauncherException {
        Path file = jsonPath(id);
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "install.error.versionNotInstalled", id);
        } catch (IOException e) {
            if (!Files.exists(file)) {
                throw new LauncherException(ErrorCode.NOT_FOUND, "install.error.versionNotInstalled", id);
            }
            throw LauncherException.io(e, file.toString());
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        try {
            return VersionJson.parse(text);
        } catch (JsonParseException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "install.error.versionJsonInvalid", id);
        }
    }

    /** read {@code id} and its parents and merge them */
    public ResolvedVersion resolve(String id) throws LauncherException {
        List<String> ids = new ArrayList<>();
        List<VersionJson> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String current = id;
        while (current != null) {
            if (!seen.add(current)) {
                throw new LauncherException(ErrorCode.INVALID_DATA, "install.error.inheritanceLoop", id);
            }
            if (seen.size() > MAX_DEPTH) {
                throw new LauncherException(ErrorCode.INVALID_DATA, "install.error.inheritanceLoop", id);
            }
            VersionJson v;
            try {
                v = read(current);
            } catch (LauncherException e) {
                if (e.code() == ErrorCode.NOT_FOUND && !current.equals(id)) {
                    throw new LauncherException(ErrorCode.NOT_FOUND, e, "install.error.parentMissing", id, current);
                }
                throw e;
            }
            ids.add(current);
            chain.add(v);
            current = v.inheritsFrom() == null || v.inheritsFrom().isBlank() ? null : v.inheritsFrom().trim();
        }
        return merge(ids, chain);
    }

    /**
     * Merge a chain of version JSONs, the requested version first.
     *
     * @param ids   ids of the versions, matching {@code chain}
     * @param chain the requested version, then its parent, then the parent's parent...
     */
    public static ResolvedVersion merge(List<String> ids, List<VersionJson> chain) {
        if (chain.isEmpty() || ids.size() != chain.size()) {
            throw new IllegalArgumentException("chain and ids must be non-empty and the same length");
        }
        String type = null;
        String mainClass = null;
        String minecraftArguments = null;
        String jar = null;
        AssetIndexInfo assetIndex = null;
        String assets = null;
        JavaVersionInfo javaVersion = null;
        LoggingInfo logging = null;
        Integer compliance = null;
        String releaseTime = null;
        String clientOwner = null;
        for (int i = 0; i < chain.size(); i++) {
            VersionJson v = chain.get(i);
            type = type != null ? type : blankToNull(v.type());
            mainClass = mainClass != null ? mainClass : blankToNull(v.mainClass());
            minecraftArguments = minecraftArguments != null ? minecraftArguments : blankToNull(v.minecraftArguments());
            jar = jar != null ? jar : blankToNull(v.jar());
            assetIndex = assetIndex != null ? assetIndex : v.assetIndex();
            assets = assets != null ? assets : blankToNull(v.assets());
            javaVersion = javaVersion != null ? javaVersion : v.javaVersion();
            logging = logging != null ? logging : (v.logging() != null && v.logging().client() != null ? v.logging() : null);
            compliance = compliance != null ? compliance : v.complianceLevel();
            releaseTime = releaseTime != null ? releaseTime : blankToNull(v.releaseTime());
            if (clientOwner == null && v.downloads().get("client") != null) {
                clientOwner = ids.get(i);
            }
        }

        Map<String, DownloadInfo> downloads = new LinkedHashMap<>();
        List<Argument> game = new ArrayList<>();
        List<Argument> jvm = new ArrayList<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            VersionJson v = chain.get(i);
            downloads.putAll(v.downloads());
            game.addAll(v.gameArguments());
            jvm.addAll(v.jvmArguments());
        }

        List<Library> libraries = new ArrayList<>();
        Set<String> earlierKeys = new HashSet<>();
        for (VersionJson v : chain) {
            Set<String> ownKeys = new HashSet<>();
            for (Library lib : v.libraries()) {
                String key = libraryKey(lib);
                if (key != null && earlierKeys.contains(key)) {
                    continue;
                }
                libraries.add(lib);
                if (key != null) {
                    ownKeys.add(key);
                }
            }
            earlierKeys.addAll(ownKeys);
        }

        String requested = ids.get(0);
        return new ResolvedVersion(requested, ids, type == null ? "release" : type, mainClass, minecraftArguments,
            game, jvm, libraries, downloads, clientOwner, jar == null ? requested : jar, assetIndex, assets,
            javaVersion, logging, compliance == null ? 0 : compliance, releaseTime);
    }

    /** {@code group:artifact[:classifier]} of a library, or its raw name when it isn't a valid coordinate */
    static String libraryKey(Library lib) {
        if (lib.name() == null) {
            return null;
        }
        try {
            return MavenCoordinate.parse(lib.name()).key();
        } catch (LauncherException e) {
            return lib.name();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

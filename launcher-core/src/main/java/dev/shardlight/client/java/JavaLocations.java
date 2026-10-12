package dev.shardlight.client.java;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Candidate Java executables on this computer: JAVA_HOME, PATH, the usual install folders of each OS and
 * vendor, the official Minecraft launcher's runtimes and the launcher's own runtimes. Only files that
 * exist are returned; nothing is run here.
 */
public final class JavaLocations {
    /** vendor folders under Program Files */
    static final List<String> WINDOWS_VENDORS = List.of("Java", "Eclipse Adoptium", "Eclipse Foundation", "AdoptOpenJDK",
        "Microsoft", "Zulu", "BellSoft", "Amazon Corretto", "Semeru", "IBM", "RedHat", "ojdkbuild", "Oracle");
    /** the Microsoft Store edition of the Minecraft launcher keeps its runtimes in this package's LocalCache */
    static final String STORE_LAUNCHER_PACKAGE = "Microsoft.4297127D64EC6_8wekyb3d8bbwe";

    private JavaLocations() {
    }

    /** existing java executables (java.exe on Windows, used for probing), in search order, without duplicates */
    public static List<Path> candidates(JavaEnvironment env, Path launcherRuntimes) {
        Set<Path> out = new LinkedHashSet<>();
        boolean win = env.os().isWindows();
        String exe = win ? "java.exe" : "java";

        String javaHome = env.get("JAVA_HOME");
        if (javaHome != null) {
            add(out, path(stripQuotes(javaHome)), "bin", exe);
        }
        String pathVar = env.get("PATH");
        if (pathVar != null) {
            for (String dir : pathVar.split(win ? ";" : ":")) {
                if (!dir.isBlank()) {
                    add(out, path(stripQuotes(dir.trim())), exe);
                }
            }
        }
        if (launcherRuntimes != null) {
            for (Path component : children(launcherRuntimes)) {
                for (Path platform : children(component)) {
                    add(out, platform, "bin", exe);
                    add(out, platform, "jre.bundle", "Contents", "Home", "bin", "java");
                }
            }
        }
        switch (env.os().os()) {
            case WINDOWS -> windows(env, out, exe);
            case MACOS -> mac(env, out);
            default -> linux(env, out);
        }
        return new ArrayList<>(out);
    }

    private static void windows(JavaEnvironment env, Set<Path> out, String exe) {
        Set<Path> bases = new LinkedHashSet<>();
        for (String var : List.of("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)")) {
            Path p = path(env.get(var));
            if (p != null) {
                bases.add(p);
            }
        }
        for (Path base : bases) {
            for (String vendor : WINDOWS_VENDORS) {
                for (Path home : children(base.resolve(vendor))) {
                    add(out, home, "bin", exe);
                    add(out, home, "jre", "bin", exe);
                }
            }
        }
        Path appData = path(env.get("APPDATA"));
        if (appData != null) {
            mojangRuntimes(out, appData.resolve(".minecraft").resolve("runtime"), "bin", exe);
        }
        Path local = path(env.get("LOCALAPPDATA"));
        if (local != null) {
            mojangRuntimes(out, local.resolve("Packages").resolve(STORE_LAUNCHER_PACKAGE).resolve("LocalCache")
                .resolve("Local").resolve("runtime"), "bin", exe);
        }
        if (env.userHome() != null) {
            for (Path home : children(env.userHome().resolve(".jdks"))) {
                add(out, home, "bin", exe);
            }
        }
    }

    private static void mac(JavaEnvironment env, Set<Path> out) {
        List<Path> dirs = new ArrayList<>(env.systemJvmDirs());
        if (env.userHome() != null) {
            dirs.add(env.userHome().resolve("Library").resolve("Java").resolve("JavaVirtualMachines"));
        }
        for (Path dir : dirs) {
            for (Path jvm : children(dir)) {
                add(out, jvm, "Contents", "Home", "bin", "java");
                add(out, jvm, "bin", "java");
            }
        }
        if (env.userHome() != null) {
            mojangRuntimes(out, env.userHome().resolve("Library").resolve("Application Support").resolve("minecraft")
                .resolve("runtime"), "jre.bundle", "Contents", "Home", "bin", "java");
            for (Path home : children(env.userHome().resolve(".jdks"))) {
                add(out, home, "Contents", "Home", "bin", "java");
                add(out, home, "bin", "java");
            }
        }
    }

    private static void linux(JavaEnvironment env, Set<Path> out) {
        for (Path dir : env.systemJvmDirs()) {
            for (Path jvm : children(dir)) {
                add(out, jvm, "bin", "java");
                add(out, jvm, "jre", "bin", "java");
            }
        }
        if (env.userHome() != null) {
            mojangRuntimes(out, env.userHome().resolve(".minecraft").resolve("runtime"), "bin", "java");
            for (Path home : children(env.userHome().resolve(".jdks"))) {
                add(out, home, "bin", "java");
            }
            for (Path home : children(env.userHome().resolve(".sdkman").resolve("candidates").resolve("java"))) {
                add(out, home, "bin", "java");
            }
        }
    }

    /** {@code runtime/<component>/<platform>/<component>/<rest...>}, the official launcher's layout */
    private static void mojangRuntimes(Set<Path> out, Path runtimeRoot, String... rest) {
        for (Path component : children(runtimeRoot)) {
            for (Path platform : children(component)) {
                for (Path home : children(platform)) {
                    add(out, home, rest);
                }
            }
        }
    }

    private static void add(Set<Path> out, Path base, String... parts) {
        if (base == null) {
            return;
        }
        try {
            Path p = base;
            for (String s : parts) {
                p = p.resolve(s);
            }
            if (Files.isRegularFile(p)) {
                out.add(p.toAbsolutePath().normalize());
            }
        } catch (InvalidPathException ignored) {
            // a malformed PATH entry
        }
    }

    /** sub-folders, sorted by name; empty when the folder doesn't exist or can't be read */
    static List<Path> children(Path dir) {
        List<Path> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path p : ds) {
                out.add(p);
            }
        } catch (IOException | SecurityException e) {
            return out;
        }
        out.sort(null);
        return out;
    }

    private static Path path(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Path.of(s);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static String stripQuotes(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}

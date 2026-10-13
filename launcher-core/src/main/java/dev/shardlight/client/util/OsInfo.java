package dev.shardlight.client.util;

import java.io.File;
import java.util.Locale;

/** The operating system and CPU, in the names Mojang's metadata uses. */
public final class OsInfo {
    public enum Os { WINDOWS, MACOS, LINUX, OTHER }

    public enum Arch { X64, X86, ARM64, ARM32, OTHER }

    private static final OsInfo CURRENT = detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""),
        System.getProperty("os.version", ""));

    private final Os os;
    private final Arch arch;
    private final String version;

    public OsInfo(Os os, Arch arch, String version) {
        this.os = os;
        this.arch = arch;
        this.version = version == null ? "" : version;
    }

    public static OsInfo current() {
        return CURRENT;
    }

    public static OsInfo detect(String osName, String osArch, String osVersion) {
        String n = osName.toLowerCase(Locale.ROOT);
        Os os = n.contains("win") ? Os.WINDOWS : n.contains("mac") || n.contains("darwin") ? Os.MACOS
            : n.contains("linux") || n.contains("nux") ? Os.LINUX : Os.OTHER;
        String a = osArch.toLowerCase(Locale.ROOT);
        Arch arch = switch (a) {
            case "amd64", "x86_64", "x64" -> Arch.X64;
            case "x86", "i386", "i486", "i586", "i686" -> Arch.X86;
            case "aarch64", "arm64" -> Arch.ARM64;
            case "arm", "armv7", "armv7l", "aarch32" -> Arch.ARM32;
            default -> Arch.OTHER;
        };
        return new OsInfo(os, arch, osVersion);
    }

    public Os os() {
        return os;
    }

    public Arch arch() {
        return arch;
    }

    public String version() {
        return version;
    }

    public boolean isWindows() {
        return os == Os.WINDOWS;
    }

    /** the os name used in version JSON library rules: windows, osx, linux */
    public String mojangName() {
        return switch (os) {
            case WINDOWS -> "windows";
            case MACOS -> "osx";
            default -> "linux";
        };
    }

    /** the arch name used in version JSON rules ("x86" for 32-bit, otherwise the JVM's) */
    public String mojangArch() {
        return switch (arch) {
            case X64 -> "x86_64";
            case X86 -> "x86";
            case ARM64 -> "arm64";
            case ARM32 -> "arm32";
            default -> "unknown";
        };
    }

    /** the platform key of Mojang's Java runtime manifest */
    public String javaRuntimePlatform() {
        return switch (os) {
            case WINDOWS -> arch == Arch.ARM64 ? "windows-arm64" : arch == Arch.X86 ? "windows-x86" : "windows-x64";
            case MACOS -> arch == Arch.ARM64 ? "mac-os-arm64" : "mac-os";
            default -> arch == Arch.X86 ? "linux-i386" : "linux";
        };
    }

    /** "64" or "32", for legacy ${arch} placeholders in native classifiers */
    public String bitness() {
        return arch == Arch.X86 || arch == Arch.ARM32 ? "32" : "64";
    }

    public String classpathSeparator() {
        return os == Os.WINDOWS ? ";" : ":";
    }

    /** "javaw.exe" on Windows (no console window), "java" elsewhere */
    public String javaExecutableName(boolean windowless) {
        return os == Os.WINDOWS ? (windowless ? "javaw.exe" : "java.exe") : "java";
    }

    public static String pathSeparator() {
        return File.pathSeparator;
    }

    @Override
    public String toString() {
        return os + "/" + arch + " " + version;
    }
}

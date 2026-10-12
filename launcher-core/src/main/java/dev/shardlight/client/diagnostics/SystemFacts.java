package dev.shardlight.client.diagnostics;

import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.util.OsInfo;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

/**
 * Facts about this computer that help when reading a crash: only what Java can measure directly. Values that
 * can't be read are -1 (shown as "unknown"), never estimated.
 *
 * @param launcherVersion  this launcher's version
 * @param os               operating system name and version
 * @param arch             CPU architecture as Java reports it
 * @param launcherJava     the Java running the launcher (version and vendor)
 * @param cpuThreads       logical processors
 * @param totalMemoryMb    physical memory, or -1
 * @param freeMemoryMb     free physical memory right now, or -1
 * @param launcherHeapMb   the most memory the launcher itself may use
 */
public record SystemFacts(String launcherVersion, String os, String arch, String launcherJava, int cpuThreads,
                          long totalMemoryMb, long freeMemoryMb, long launcherHeapMb) {

    public static SystemFacts collect() {
        long total = -1;
        long free = -1;
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            try {
                total = sun.getTotalMemorySize() / (1024 * 1024);
                free = sun.getFreeMemorySize() / (1024 * 1024);
            } catch (RuntimeException ignored) {
                // stays unknown
            }
        }
        return new SystemFacts(BuildInfo.VERSION,
            System.getProperty("os.name", "?") + " " + System.getProperty("os.version", ""),
            System.getProperty("os.arch", "?") + " (" + OsInfo.current().arch() + ")",
            System.getProperty("java.version", "?") + " (" + System.getProperty("java.vendor", "?") + ")",
            Runtime.getRuntime().availableProcessors(), total, free, Runtime.getRuntime().maxMemory() / (1024 * 1024));
    }

    /** total physical memory in MB, or {@code fallback} when it can't be read */
    public static long totalMemoryMb(long fallback) {
        long total = collect().totalMemoryMb();
        return total > 0 ? total : fallback;
    }

    /** the facts as "name: value" lines */
    public String describe() {
        return "Launcher: " + BuildInfo.NAME + " " + launcherVersion + "\n"
            + "Operating system: " + os.trim() + "\n"
            + "Architecture: " + arch + "\n"
            + "Launcher Java: " + launcherJava + "\n"
            + "Processor threads: " + cpuThreads + "\n"
            + "Memory: " + mb(totalMemoryMb) + " total, " + mb(freeMemoryMb) + " free\n"
            + "Launcher memory limit: " + mb(launcherHeapMb) + "\n";
    }

    private static String mb(long v) {
        return v < 0 ? "unknown" : v + " MB";
    }
}

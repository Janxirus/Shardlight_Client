package dev.shardlight.client.java;

import dev.shardlight.client.util.OsInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Where to look for Java: the OS, environment variables, the user's home and the system-wide JVM folders.
 * Tests pass a fake one to simulate Windows, macOS or Linux layouts.
 *
 * @param os            the operating system
 * @param env           environment variables (JAVA_HOME, PATH, ProgramFiles, APPDATA, LOCALAPPDATA...)
 * @param userHome      the user's home folder
 * @param systemJvmDirs folders whose subfolders are Java homes ({@code /usr/lib/jvm},
 *                      {@code /Library/Java/JavaVirtualMachines}); empty on Windows, where Program Files is used
 */
public record JavaEnvironment(OsInfo os, Map<String, String> env, Path userHome, List<Path> systemJvmDirs) {
    public JavaEnvironment {
        env = Map.copyOf(env);
        systemJvmDirs = List.copyOf(systemJvmDirs);
    }

    /** the real environment of this process */
    public static JavaEnvironment current() {
        OsInfo os = OsInfo.current();
        List<Path> dirs = switch (os.os()) {
            case MACOS -> List.of(Path.of("/Library/Java/JavaVirtualMachines"));
            case WINDOWS -> List.of();
            default -> List.of(Path.of("/usr/lib/jvm"), Path.of("/usr/lib64/jvm"), Path.of("/usr/java"),
                Path.of("/opt/java"), Path.of("/opt/jdk"));
        };
        return new JavaEnvironment(os, System.getenv(), Path.of(System.getProperty("user.home", ".")), dirs);
    }

    /** an environment variable, ignoring case on Windows; null when unset or blank */
    public String get(String name) {
        String v = env.get(name);
        if (v == null && os.isWindows()) {
            for (Map.Entry<String, String> e : env.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    v = e.getValue();
                    break;
                }
            }
        }
        return v == null || v.isBlank() ? null : v.trim();
    }
}

package dev.shardlight.client.project;

/** Per-project Java runtime and memory. */
public final class JavaSettings {
    /** a specific java executable, or null to pick automatically (Mojang's runtime for the version) */
    public String javaPath;
    public int minMemoryMb = 512;
    public int maxMemoryMb = 4096;
    /** extra JVM arguments, space separated (quotes allowed) */
    public String extraJvmArgs = "";
    /** extra game arguments, space separated */
    public String extraGameArgs = "";
}

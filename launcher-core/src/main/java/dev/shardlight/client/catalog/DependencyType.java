package dev.shardlight.client.catalog;

/** How one catalog version relates to another project or version. */
public enum DependencyType {
    /** must be installed too */
    REQUIRED,
    /** works better with it; suggested, never installed automatically */
    OPTIONAL,
    /** must not be installed together */
    INCOMPATIBLE,
    /** already bundled inside the file; nothing to install */
    EMBEDDED
}

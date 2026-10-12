package dev.shardlight.client.catalog;

/** How serious a plan issue or content warning is. */
public enum Severity {
    /** worth knowing, nothing to do */
    INFO,
    /** may cause problems; installing or launching is still allowed */
    WARNING,
    /** blocks the operation (unless the issue is overridable and the user overrides it) */
    ERROR
}

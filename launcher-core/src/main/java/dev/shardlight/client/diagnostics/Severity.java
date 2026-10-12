package dev.shardlight.client.diagnostics;

/** How serious a {@link Finding} is. */
public enum Severity {
    /** worth knowing, not a cause of the problem */
    INFO,
    /** may cause problems or explains part of them */
    WARNING,
    /** very likely the reason the game failed */
    ERROR
}

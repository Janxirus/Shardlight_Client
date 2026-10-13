package dev.shardlight.client.launch;

import dev.shardlight.client.diagnostics.Finding;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * How a game session ended.
 *
 * @param exitCode      the process exit code
 * @param crashed       true when the game ended abnormally (a non-zero exit or a new crash report) and the player
 *                      didn't ask the launcher to stop it
 * @param stopRequested true when the launcher stopped the game on the player's request
 * @param crashReport   the crash report (or Java fatal error log) the game wrote this session, if any
 * @param playTime      how long the game ran
 * @param findings      what the crash analysis recognised, most serious first; empty when it didn't crash
 */
public record GameExit(int exitCode, boolean crashed, boolean stopRequested, Optional<Path> crashReport, Duration playTime,
                       List<Finding> findings) {
    public GameExit {
        crashReport = crashReport == null ? Optional.empty() : crashReport;
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}

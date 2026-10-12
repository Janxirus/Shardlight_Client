package dev.shardlight.client.launch;

import dev.shardlight.client.log.Redactor;

import java.nio.file.Path;
import java.util.List;

/**
 * A ready-to-run game command: the Java executable followed by its arguments, and the folder to run it in.
 * The command contains the player's access token; log or show only {@link #redacted()}.
 *
 * @param command          the program and its arguments, one element per argument (no shell quoting needed)
 * @param workingDirectory the game directory
 */
public record LaunchCommand(List<String> command, Path workingDirectory) {
    public LaunchCommand {
        command = List.copyOf(command);
    }

    /** the command as one line for logs and diagnostics, with the access token and other secrets masked */
    public String redacted() {
        StringBuilder b = new StringBuilder();
        boolean maskNext = false;
        for (String arg : command) {
            String shown;
            if (maskNext) {
                shown = Redactor.MASK;
            } else if (arg.startsWith("token:")) {
                shown = "token:" + Redactor.MASK;
            } else {
                shown = Redactor.redact(arg);
            }
            maskNext = arg.equals("--accessToken") || arg.equals("--session");
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(shown.isEmpty() || shown.indexOf(' ') >= 0 || shown.indexOf('\t') >= 0 ? "\"" + shown + "\"" : shown);
        }
        return b.toString();
    }

    /** never prints the token */
    @Override
    public String toString() {
        return "LaunchCommand[" + redacted() + " in " + workingDirectory + "]";
    }
}

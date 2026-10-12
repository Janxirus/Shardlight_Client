package dev.shardlight.client.launch;

import dev.shardlight.client.java.JavaInstall;
import dev.shardlight.client.minecraft.InstalledVersion;
import dev.shardlight.client.project.Project;

import java.nio.file.Path;

/**
 * A project that is installed and ready to start: what {@link LaunchService#prepare} produced and
 * {@link LaunchService#start} runs. The command holds the access token; never log it, use
 * {@link LaunchCommand#redacted()}.
 *
 * @param project   the project as it was when the launch was prepared
 * @param versionId the version that runs (the loader's version id for modded projects)
 * @param installed the installed game files
 * @param java      the Java the game runs on
 * @param gameDir   the project's game directory
 * @param command   the full command line
 */
public record LaunchPlan(Project project, String versionId, InstalledVersion installed, JavaInstall java, Path gameDir,
                         LaunchCommand command) {
    @Override
    public String toString() {
        return "LaunchPlan[" + project.id + ", " + versionId + ", " + java + "]";
    }
}

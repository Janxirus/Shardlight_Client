package dev.shardlight.client.launch;

import dev.shardlight.client.auth.MinecraftSession;
import dev.shardlight.client.java.JavaInstall;
import dev.shardlight.client.minecraft.InstalledVersion;
import dev.shardlight.client.util.OsInfo;

import java.nio.file.Path;

/**
 * Everything {@link LaunchCommandBuilder} needs to build one launch command.
 *
 * @param installed             the installed version
 * @param java                  the Java to run it with
 * @param gameDir               the project's game directory (working directory and {@code --gameDir})
 * @param nativesDir            where the native libraries were extracted
 * @param session               the signed-in player (the access token is a secret)
 * @param minMemoryMb           {@code -Xms}, 0 to leave out
 * @param maxMemoryMb           {@code -Xmx}, 0 to leave out
 * @param extraJvmArgs          extra JVM arguments, space separated, double quotes group
 * @param extraGameArgs         extra game arguments, space separated, double quotes group
 * @param width                 window width, 0 for the game's default
 * @param height                window height, 0 for the game's default
 * @param fullscreen            start in fullscreen
 * @param quickPlaySingleplayer world folder name to open directly, or null
 * @param quickPlayMultiplayer  server address ({@code host[:port]}) to join directly, or null
 * @param demo                  start in demo mode
 * @param launcherName          {@code launcher_name} (the launcher brand)
 * @param launcherVersion       {@code launcher_version}
 * @param os                    the OS the game runs on (rules, classpath separator)
 */
public record LaunchContext(InstalledVersion installed, JavaInstall java, Path gameDir, Path nativesDir,
                            MinecraftSession session, int minMemoryMb, int maxMemoryMb, String extraJvmArgs,
                            String extraGameArgs, int width, int height, boolean fullscreen,
                            String quickPlaySingleplayer, String quickPlayMultiplayer, boolean demo,
                            String launcherName, String launcherVersion, OsInfo os) {
}

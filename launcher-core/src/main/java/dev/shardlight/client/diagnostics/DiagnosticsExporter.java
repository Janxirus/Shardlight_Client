package dev.shardlight.client.diagnostics;

import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.log.Redactor;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a zip someone can attach to a bug report, built to be safe to share: every text goes through
 * {@link Redactor} (tokens, keys), the home folder becomes {@code ~}, the player name is replaced, and server
 * addresses and IP addresses are masked unless the user chose to include them. Account files, settings,
 * {@code options.txt}, {@code servers.dat}, worlds and screenshots are never included. Nothing is uploaded;
 * the user decides where the zip goes.
 *
 * <p>Contents: {@code README.txt} (what is inside and what was left out), {@code system.txt}, the newest
 * launcher logs, and for a project its settings without notes, its content list, the game's
 * {@code latest.log}, the launcher's copy of the game output and the newest crash reports.
 */
public final class DiagnosticsExporter {
    /** launcher logs and crash reports included, newest first */
    static final int NEWEST_FILES = 3;
    /** each text file is cut to its last part */
    static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;

    private static final Pattern IPV4 = Pattern.compile(
        "(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?::\\d{1,5})?(?![\\d.])");
    private static final Pattern IPV6 = Pattern.compile("(?i)\\[?(?<![\\w:])(?:[0-9a-f]{1,4}:){4,7}[0-9a-f]{1,4}(?![\\w:])\\]?");
    private static final Pattern CONNECTING = Pattern.compile("(Connecting to )([^\\r\\n,]+)(, ?\\d+)?");
    private static final Pattern SERVER_ARG = Pattern.compile("(--(?:quickPlayMultiplayer|server|port)[ =])(\\S+)");
    private static final Pattern PLAYER = Pattern.compile("(Setting user: )(\\S+)");
    private static final Pattern PLAYER_ARG = Pattern.compile("(--username[ =])(\\S+)");
    private static final Pattern UUID_ARG = Pattern.compile("(--uuid[ =])(\\S+)");
    private static final Pattern XUID_ARG = Pattern.compile("(--xuid[ =])(\\S+)");
    private static final String SERVER_MASK = "<server>";

    /**
     * Write the zip.
     *
     * @param project                the project to describe, or null for launcher-only diagnostics
     * @param gameDir                the project's game directory, or null
     * @param launcherLogsDir        the launcher's own logs folder, or null
     * @param out                    the zip to write (replaced atomically)
     * @param includeServerAddresses keep server names and IP addresses in the logs
     * @return {@code out}
     */
    public Path export(Project project, Path gameDir, Path launcherLogsDir, Path out, boolean includeServerAddresses)
        throws LauncherException {
        Path tmp = out.resolveSibling(out.getFileName() + ".part");
        List<String> included = new ArrayList<>();
        try {
            Files.createDirectories(out.toAbsolutePath().getParent());
            try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(os)) {
                text(zip, "system.txt", SystemFacts.collect().describe(), included);
                if (project != null) {
                    text(zip, "project/project.json", projectJson(project), included);
                    text(zip, "project/content.txt", contentList(project, gameDir), included);
                }
                if (launcherLogsDir != null) {
                    for (Path log : newest(launcherLogsDir, f -> f.endsWith(".log"), NEWEST_FILES)) {
                        file(zip, "launcher/" + log.getFileName(), log, includeServerAddresses, included);
                    }
                }
                if (gameDir != null) {
                    Path logs = gameDir.resolve(GameDirs.LOGS);
                    file(zip, "game/latest.log", logs.resolve("latest.log"), includeServerAddresses, included);
                    file(zip, "game/shardlight-launch.log", logs.resolve("shardlight-launch.log"), includeServerAddresses,
                        included);
                    for (Path report : newest(gameDir.resolve(GameDirs.CRASH_REPORTS), f -> f.endsWith(".txt"), NEWEST_FILES)) {
                        file(zip, "game/crash-reports/" + report.getFileName(), report, includeServerAddresses, included);
                    }
                    for (Path fatal : newest(gameDir, f -> f.startsWith("hs_err_pid") && f.endsWith(".log"), 1)) {
                        file(zip, "game/" + fatal.getFileName(), fatal, includeServerAddresses, included);
                    }
                }
                text(zip, "README.txt", readme(project, included, includeServerAddresses), new ArrayList<>());
            }
            AtomicFiles.moveReplacing(tmp, out);
            return out;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // the partial file is harmless
            }
            throw LauncherException.io(e, out.toString());
        }
    }

    /** make one text safe to share; public so the UI can use the same rules when copying a log */
    public static String sanitize(String text, boolean includeServerAddresses) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = Redactor.redact(text);
        String home = System.getProperty("user.home");
        if (home != null && home.length() > 3) {
            s = s.replace(home, "~");
            s = s.replace(home.replace('\\', '/'), "~");
        }
        String user = System.getProperty("user.name");
        if (user != null && user.length() >= 3) {
            s = Pattern.compile("(?i)([\\\\/])" + Pattern.quote(user) + "(?=[\\\\/])").matcher(s)
                .replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "<user>"));
        }
        s = replaceGroup(PLAYER, s, "<player>");
        s = replaceGroup(PLAYER_ARG, s, "<player>");
        s = replaceGroup(UUID_ARG, s, "<uuid>");
        s = replaceGroup(XUID_ARG, s, "<xuid>");
        if (!includeServerAddresses) {
            s = CONNECTING.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + SERVER_MASK));
            s = replaceGroup(SERVER_ARG, s, SERVER_MASK);
            s = IPV4.matcher(s).replaceAll(SERVER_MASK);
            s = IPV6.matcher(s).replaceAll(SERVER_MASK);
        }
        return s;
    }

    private static String replaceGroup(Pattern p, String s, String mask) {
        return p.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + mask));
    }

    /** the project's settings without its notes, with local paths shortened */
    private static String projectJson(Project project) {
        Project copy = Json.copy(project, Project.class);
        copy.notes = copy.notes == null || copy.notes.isEmpty() ? "" : "<left out>";
        copy.content = new ArrayList<>();
        return sanitize(Json.write(copy), true);
    }

    private static String contentList(Project project, Path gameDir) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("Content the launcher knows about (").append(project.content.size()).append("):\n");
        for (ContentEntry e : project.content) {
            b.append(String.format(Locale.ROOT, "- [%s] %s %s  (%s, %s%s%s)%n", e.kind, e.title == null ? e.fileName : e.title,
                e.versionNumber == null ? "" : e.versionNumber, e.fileName, e.source, e.enabled ? "" : ", disabled",
                e.missing ? ", file missing" : ""));
        }
        if (gameDir != null) {
            Path mods = gameDir.resolve(GameDirs.MODS);
            if (Files.isDirectory(mods)) {
                b.append("\nFiles in mods/:\n");
                List<Path> files = new ArrayList<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
                    ds.forEach(files::add);
                }
                files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
                for (Path f : files) {
                    if (Files.isRegularFile(f)) {
                        b.append(String.format(Locale.ROOT, "- %s (%d KB)%n", f.getFileName(), Files.size(f) / 1024));
                    } else if (Files.isDirectory(f)) {
                        b.append("- ").append(f.getFileName()).append("/ (folder)\n");
                    }
                }
            }
        }
        return sanitize(b.toString(), true);
    }

    private static String readme(Project project, List<String> included, boolean servers) {
        StringBuilder b = new StringBuilder();
        b.append("Shardlight Client diagnostics, created ").append(Instant.now()).append("\n\n");
        b.append(project == null ? "Launcher only (no project).\n" : "Project: " + project.name + "\n");
        b.append("\nIncluded:\n");
        included.forEach(f -> b.append("- ").append(f).append('\n'));
        b.append("""

            Privacy: sign-in tokens and API keys are masked, your home folder is shown as ~ and your player name as
            <player>. Never included: accounts, launcher settings, options.txt, servers.dat, worlds, screenshots and
            project notes.
            """);
        b.append(servers ? "Server addresses were kept, as you chose.\n"
            : "Server names and IP addresses are masked as " + SERVER_MASK + ".\n");
        b.append("\nNothing was uploaded. Share this file only with people you trust to help.\n");
        return b.toString();
    }

    private static void text(ZipOutputStream zip, String name, String content, List<String> included) throws IOException {
        ZipEntry e = new ZipEntry(name);
        zip.putNextEntry(e);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        included.add(name);
    }

    private static void file(ZipOutputStream zip, String name, Path file, boolean servers, List<String> included)
        throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        String content = CrashAnalyzer.readTail(file);
        if (name.contains("hs_err_pid")) {
            content = withoutEnvironment(content);
        }
        if (Files.size(file) > MAX_TEXT_BYTES) {
            content = "[only the last " + MAX_TEXT_BYTES / 1024 + " KB of this file]\n" + content;
        }
        text(zip, name, sanitize(content, servers), included);
    }

    /** Java fatal error logs list every environment variable; leave that section out */
    static String withoutEnvironment(String hsErr) {
        StringBuilder b = new StringBuilder();
        boolean skipping = false;
        for (String line : hsErr.split("\\R", -1)) {
            if (line.startsWith("Environment Variables:")) {
                skipping = true;
                b.append("Environment Variables: <left out>\n");
                continue;
            }
            if (skipping) {
                if (line.isBlank()) {
                    skipping = false;
                    b.append('\n');
                }
                continue;
            }
            b.append(line).append('\n');
        }
        return b.toString();
    }

    private static List<Path> newest(Path dir, java.util.function.Predicate<String> name, int count) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p) && name.test(p.getFileName().toString())) {
                    files.add(p);
                }
            }
        }
        files.sort(Comparator.comparingLong(DiagnosticsExporter::modified).reversed());
        return files.subList(0, Math.min(count, files.size()));
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}

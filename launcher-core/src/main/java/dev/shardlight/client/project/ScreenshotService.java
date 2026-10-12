package dev.shardlight.client.project;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.util.PathSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A project's screenshots ({@code <game dir>/screenshots/}), newest first, and a restorable per-project
 * trash ({@code projects/<id>/.trash/screenshots/}). Only files directly inside {@code screenshots/} are
 * ever moved.
 */
public final class ScreenshotService {
    private static final Logger LOG = Logger.getLogger(ScreenshotService.class.getName());
    private static final Pattern MC_NAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2})(?:_\\d+)?\\.[A-Za-z]+");
    private static final DateTimeFormatter MC_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");
    private static final Pattern TRASH_NAME = Pattern.compile("\\d{8}-\\d{6}(?:-\\d+)?_(.+)");

    private final ProjectAccess projects;
    /** moves files; replaceable in tests */
    volatile ProjectService.DirMover mover = FileTrees::move;

    public ScreenshotService(ProjectAccess projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    /** PNG and JPEG files in {@code screenshots/}, newest first */
    public List<Screenshot> list(Project p) {
        return images(screenshotsDir(p), false);
    }

    /**
     * Copy a PNG or JPEG into {@code screenshots/} under its own name, or {@code name-2}, {@code name-3}... when that
     * name is taken. The picture appears complete or not at all. Returns the new file.
     */
    public Path importFile(Project p, Path image) throws LauncherException {
        Objects.requireNonNull(image, "image");
        if (!FileTrees.isPlainFile(image)) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "project.screenshot.notFound", String.valueOf(image.getFileName()));
        }
        Path dir = screenshotsDir(p);
        String clean = PathSafety.sanitizeFileName(image.getFileName().toString());
        int dot = clean.lastIndexOf('.');
        String stem = dot > 0 ? clean.substring(0, dot) : clean;
        String ext = dot > 0 ? clean.substring(dot) : "";
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(clean);
            for (int i = 2; Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS); i++) {
                target = dir.resolve(stem + "-" + i + ext);
            }
            Path part = dir.resolve(target.getFileName() + ".part");
            Files.copy(image, part, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            mover.move(part, target);
            return target;
        } catch (IOException e) {
            throw ProjectService.fileError(e, dir);
        }
    }

    /** move a screenshot to the project's trash; the file must lie directly inside {@code screenshots/} */
    public void moveToTrash(Project p, Path file) throws LauncherException {
        Objects.requireNonNull(file, "file");
        Path dir = screenshotsDir(p).toAbsolutePath().normalize();
        Path f = file.toAbsolutePath().normalize();
        if (!dir.equals(f.getParent())) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", file.toString());
        }
        if (!FileTrees.isPlainFile(f)) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "project.screenshot.notFound", f.getFileName().toString());
        }
        Path trash = trashDir(p);
        try {
            Files.createDirectories(trash);
            String stamp = ProjectService.stamp(System.currentTimeMillis());
            String original = f.getFileName().toString();
            String name = stamp + "_" + original;
            for (int i = 2; Files.exists(trash.resolve(name), LinkOption.NOFOLLOW_LINKS); i++) {
                name = stamp + "-" + i + "_" + original;
            }
            mover.move(f, trash.resolve(name));
        } catch (IOException e) {
            throw ProjectService.fileError(e, f);
        }
    }

    /** screenshots in the project's trash, most recently trashed first */
    public List<Screenshot> trashed(Project p) {
        return images(trashDir(p), true);
    }

    /** move a trashed screenshot back into {@code screenshots/}; returns where it went */
    public Screenshot restore(Project p, Path trashedFile) throws LauncherException {
        Path f = trashedEntry(p, trashedFile);
        Matcher m = TRASH_NAME.matcher(f.getFileName().toString());
        String original = m.matches() ? m.group(1) : f.getFileName().toString();
        Path dir = screenshotsDir(p);
        try {
            Files.createDirectories(dir);
            String target = original;
            int dot = original.lastIndexOf('.');
            for (int i = 2; Files.exists(dir.resolve(target), LinkOption.NOFOLLOW_LINKS); i++) {
                target = dot > 0 ? original.substring(0, dot) + "_" + i + original.substring(dot) : original + "_" + i;
            }
            mover.move(f, dir.resolve(target));
            return describe(dir.resolve(target), false);
        } catch (IOException e) {
            throw ProjectService.fileError(e, f);
        }
    }

    /** delete a trashed screenshot for good */
    public void deleteFromTrash(Project p, Path trashedFile) throws LauncherException {
        Path f = trashedEntry(p, trashedFile);
        try {
            FileTrees.deleteOne(f);
        } catch (IOException e) {
            throw ProjectService.fileError(e, f);
        }
    }

    private Path trashedEntry(Project p, Path trashedFile) throws LauncherException {
        Objects.requireNonNull(trashedFile, "trashedFile");
        Path trash = trashDir(p).toAbsolutePath().normalize();
        Path f = trashedFile.toAbsolutePath().normalize();
        if (!trash.equals(f.getParent())) {
            throw new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", trashedFile.toString());
        }
        if (!FileTrees.isPlainFile(f)) {
            throw new LauncherException(ErrorCode.NOT_FOUND, "project.screenshot.notFound", f.getFileName().toString());
        }
        return f;
    }

    private static List<Screenshot> images(Path dir, boolean trash) {
        List<Screenshot> out = new ArrayList<>();
        for (Path f : FileTrees.children(dir)) {
            String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!(n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")) || !FileTrees.isPlainFile(f)) {
                continue;
            }
            Screenshot s = describe(f, trash);
            if (s != null) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparingLong(Screenshot::takenAt).reversed()
            .thenComparing(s -> s.file().getFileName().toString(), Comparator.reverseOrder()));
        return out;
    }

    private static Screenshot describe(Path f, boolean trash) {
        try {
            BasicFileAttributes a = FileTrees.attrs(f);
            String name = f.getFileName().toString();
            long at = -1;
            if (trash) {
                at = ProjectService.parseStampSuffix("x-" + name.split("_", 2)[0]);
            } else {
                Matcher m = MC_NAME.matcher(name);
                if (m.matches()) {
                    try {
                        at = LocalDateTime.parse(m.group(1), MC_TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    } catch (DateTimeParseException e) {
                        at = -1;
                    }
                }
            }
            if (at <= 0) {
                at = a.lastModifiedTime().toMillis();
            }
            return new Screenshot(f, at, a.size());
        } catch (IOException e) {
            LOG.fine("Skipping " + f + ": " + e.getMessage());
            return null;
        }
    }

    private Path screenshotsDir(Project p) {
        return projects.gameDir(Objects.requireNonNull(p, "project")).resolve(GameDirs.SCREENSHOTS);
    }

    private Path trashDir(Project p) {
        return projects.projectDir(p.id).resolve(ProjectService.PROJECT_TRASH).resolve("screenshots");
    }
}

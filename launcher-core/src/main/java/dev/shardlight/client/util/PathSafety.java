package dev.shardlight.client.util;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guards for every path that comes from outside the launcher: archive entries, catalog file names,
 * modpack manifests, user-typed project names. Nothing may leave its base folder, use a Windows reserved
 * name, or follow a symbolic link out of the folder.
 */
public final class PathSafety {
    private static final Set<String> RESERVED = Set.of(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
    private static final Pattern BAD_CHARS = Pattern.compile("[<>:\"|?*\\x00-\\x1F]");
    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:.*");

    private PathSafety() {
    }

    /**
     * Resolve a relative path (with {@code /} or {@code \} separators) inside {@code base}. Throws
     * {@link ErrorCode#UNSAFE_PATH} for absolute paths, drive letters, {@code ..} segments, reserved or
     * malformed names, or anything that would end up outside {@code base}.
     */
    public static Path resolveInside(Path base, String relative) throws LauncherException {
        if (relative == null || relative.isBlank()) {
            throw unsafe(relative);
        }
        String rel = relative.replace('\\', '/');
        if (rel.startsWith("/") || DRIVE.matcher(rel).matches() || rel.contains("\0")) {
            throw unsafe(relative);
        }
        Path result = base.toAbsolutePath().normalize();
        Path root = result;
        for (String part : rel.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (!isSafeSegment(part)) {
                throw unsafe(relative);
            }
            try {
                result = result.resolve(part);
            } catch (InvalidPathException e) {
                throw unsafe(relative);
            }
        }
        result = result.normalize();
        if (!result.startsWith(root) || result.equals(root)) {
            throw unsafe(relative);
        }
        return result;
    }

    /** one path segment: no separators, no {@code ..}, no reserved device names, no trailing dot/space */
    public static boolean isSafeSegment(String part) {
        if (part == null || part.isEmpty() || part.length() > 255) {
            return false;
        }
        if (part.equals("..") || part.equals(".") || part.contains("/") || part.contains("\\")) {
            return false;
        }
        if (BAD_CHARS.matcher(part).find()) {
            return false;
        }
        if (part.endsWith(".") || part.endsWith(" ")) {
            return false;
        }
        String stem = part.toLowerCase(Locale.ROOT);
        int dot = stem.indexOf('.');
        if (dot >= 0) {
            stem = stem.substring(0, dot);
        }
        return !RESERVED.contains(stem.trim());
    }

    /** true when {@code name} is safe as a single file name */
    public static boolean isSafeFileName(String name) {
        return isSafeSegment(name);
    }

    /**
     * Turn any text into a safe file or folder name (keeps letters, digits, space, dash, underscore, dot,
     * parentheses; everything else becomes {@code _}). Never returns an empty or reserved name.
     */
    public static String sanitizeFileName(String name) {
        String s = name == null ? "" : name.strip();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 120; i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '.' || c == '(' || c == ')' || c == '+') {
                b.append(c);
            } else {
                b.append('_');
            }
        }
        String out = b.toString().replaceAll("^[. ]+|[. ]+$", "");
        if (out.isEmpty()) {
            out = "unnamed";
        }
        if (!isSafeSegment(out)) {
            out = "_" + out;
        }
        return out;
    }

    /**
     * Fail when {@code path} (or one of its parents up to {@code base}) is a symbolic link pointing outside
     * {@code base}. Use before deleting or overwriting inside user folders.
     */
    public static void requireNoLinkEscape(Path base, Path path) throws LauncherException {
        try {
            Path realBase = base.toRealPath();
            Path p = path.toAbsolutePath().normalize();
            while (p != null && p.startsWith(base.toAbsolutePath().normalize())) {
                if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(p)) {
                    Path real = p.toRealPath();
                    if (!real.startsWith(realBase)) {
                        throw unsafe(path.toString());
                    }
                }
                p = p.getParent();
            }
        } catch (IOException e) {
            throw LauncherException.io(e, path.toString());
        }
    }

    private static LauncherException unsafe(String what) {
        return new LauncherException(ErrorCode.UNSAFE_PATH, "error.unsafePath", String.valueOf(what));
    }
}

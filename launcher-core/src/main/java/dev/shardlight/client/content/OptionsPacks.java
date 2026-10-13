package dev.shardlight.client.content;

import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.game.OptionsTxt;
import dev.shardlight.client.util.PathSafety;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Resource pack selection in {@code options.txt}. Minecraft's {@code resourcePacks} list is lowest priority
 * first; user packs are {@code "file/<name>"} (older files may use the bare name); built-in and mod-provided
 * packs ({@code vanilla}, {@code fabric}, {@code programmer_art}...) are kept below the user packs.
 * {@code incompatibleResourcePacks} lists selected packs the player accepted although their format doesn't
 * match; it is kept a subset of {@code resourcePacks}.
 */
final class OptionsPacks {
    static final String PACKS = "resourcePacks";
    static final String INCOMPATIBLE = "incompatibleResourcePacks";
    private static final String PREFIX = "file/";

    private final Path file;
    private final OptionsTxt options;
    private final boolean existed;
    /** true when {@code bareName} is a pack in the resourcepacks folder */
    private final Predicate<String> userPack;
    private boolean changed;

    private OptionsPacks(Path file, OptionsTxt options, boolean existed, Predicate<String> userPack) {
        this.file = file;
        this.options = options;
        this.existed = existed;
        this.userPack = userPack;
    }

    static OptionsPacks open(Path gameDir) throws LauncherException {
        Path f = gameDir.resolve(GameDirs.OPTIONS);
        Path packsDir = gameDir.resolve(GameDirs.RESOURCE_PACKS);
        boolean existed = Files.exists(f);
        return new OptionsPacks(f, OptionsTxt.read(f), existed,
            name -> !name.isBlank() && PathSafety.isSafeFileName(name) && Files.exists(packsDir.resolve(name)));
    }

    /** the user pack name of a list entry, or null for built-in/mod packs */
    private String userName(String entry) {
        if (entry.startsWith(PREFIX)) {
            return entry.substring(PREFIX.length());
        }
        return userPack.test(entry) ? entry : null;
    }

    private boolean matches(String entry, String name) {
        return entry.equals(PREFIX + name) || (entry.equals(name) && userPack.test(name));
    }

    boolean isSelected(String name) {
        return options.getList(PACKS).stream().anyMatch(e -> matches(e, name));
    }

    /** user packs, highest priority first */
    List<String> order() {
        List<String> out = new ArrayList<>();
        for (String e : options.getList(PACKS)) {
            String n = userName(e);
            if (n != null && userPack.test(n) && !out.contains(n)) {
                out.add(0, n);
            }
        }
        return out;
    }

    /** select {@code name} as the highest-priority pack (no change when already selected) */
    void select(String name) {
        List<String> packs = baseList();
        if (packs.stream().anyMatch(e -> matches(e, name))) {
            return;
        }
        packs.add(PREFIX + name);
        options.setList(PACKS, packs);
        changed = true;
    }

    /** deselect {@code name} (both lists) */
    void deselect(String name) {
        if (!existed) {
            return;
        }
        List<String> packs = options.getList(PACKS);
        List<String> incompatible = options.getList(INCOMPATIBLE);
        boolean a = packs.removeIf(e -> matches(e, name));
        boolean b = incompatible.removeIf(e -> matches(e, name));
        if (a) {
            options.setList(PACKS, packs);
            changed = true;
        }
        if (b) {
            options.setList(INCOMPATIBLE, incompatible);
            changed = true;
        }
    }

    /**
     * Swap {@code oldName} for {@code newName} at the same position (an update). When {@code oldName} isn't
     * selected and {@code select} is true, {@code newName} is added on top. The old name leaves the
     * incompatible list (the new file may well be compatible).
     */
    void replace(String oldName, String newName, boolean select) {
        List<String> packs = baseList();
        boolean found = false;
        for (int i = 0; i < packs.size(); i++) {
            if (matches(packs.get(i), oldName)) {
                if (!found) {
                    packs.set(i, PREFIX + newName);
                    found = true;
                } else {
                    packs.remove(i--);
                }
            }
        }
        if (found) {
            packs = dedupe(packs);
            options.setList(PACKS, packs);
            changed = true;
            List<String> incompatible = options.getList(INCOMPATIBLE);
            if (incompatible.removeIf(e -> matches(e, oldName))) {
                options.setList(INCOMPATIBLE, incompatible);
            }
        } else if (select) {
            select(newName);
        }
    }

    /** reorder the user packs (highest first); packs not named stay selected below them */
    void setOrder(List<String> highestFirst) {
        List<String> current = baseList();
        List<String> builtIn = new ArrayList<>();
        List<String> userLowestFirst = new ArrayList<>();
        for (String e : current) {
            String n = userName(e);
            if (n == null) {
                builtIn.add(e);
            } else if (!highestFirst.contains(n)) {
                userLowestFirst.add(PREFIX + n);
            }
        }
        for (int i = highestFirst.size() - 1; i >= 0; i--) {
            userLowestFirst.add(PREFIX + highestFirst.get(i));
        }
        List<String> packs = dedupe(new ArrayList<>(builtIn));
        for (String u : dedupe(userLowestFirst)) {
            if (!packs.contains(u)) {
                packs.add(u);
            }
        }
        options.setList(PACKS, packs);
        // incompatible entries must still be selected; rewrite bare names the same way
        List<String> incompatible = new ArrayList<>();
        for (String e : options.getList(INCOMPATIBLE)) {
            String n = userName(e);
            String norm = n == null ? e : PREFIX + n;
            if (packs.contains(norm) && !incompatible.contains(norm)) {
                incompatible.add(norm);
            }
        }
        if (options.get(INCOMPATIBLE).isPresent() || !incompatible.isEmpty()) {
            options.setList(INCOMPATIBLE, incompatible);
        }
        changed = true;
    }

    /** the current list, or the baseline {@code ["vanilla"]} when the file or key doesn't exist yet */
    private List<String> baseList() {
        if (options.get(PACKS).isEmpty()) {
            List<String> l = new ArrayList<>();
            l.add("vanilla");
            return l;
        }
        return options.getList(PACKS);
    }

    private static List<String> dedupe(List<String> l) {
        return new ArrayList<>(new LinkedHashSet<>(l));
    }

    /** write when something changed; a new options.txt contains only the resourcePacks line */
    void save() throws LauncherException {
        if (!changed) {
            return;
        }
        if (!existed) {
            OptionsTxt fresh = OptionsTxt.parse("");
            fresh.setList(PACKS, options.getList(PACKS));
            fresh.write(file);
        } else {
            options.write(file);
        }
        changed = false;
    }

    /** names selected in options.txt, for scans */
    Set<String> selectedUserPacks() {
        Set<String> out = new LinkedHashSet<>();
        for (String e : options.getList(PACKS)) {
            String n = userName(e);
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }
}

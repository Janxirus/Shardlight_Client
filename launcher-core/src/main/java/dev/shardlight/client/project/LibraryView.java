package dev.shardlight.client.project;

import dev.shardlight.client.settings.LauncherSettings.Library;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the Library page shows: the projects that pass the filters, sorted, split into the player's folders and
 * the projects that are in none. Pure logic over {@link Library} (the saved view, order and folders), so it is
 * tested without a window.
 */
public final class LibraryView {
    private LibraryView() {
    }

    /** the ways to sort; CUSTOM follows the order the player dragged the projects into */
    public enum Sort {
        RECENT, NAME, CREATED, CUSTOM;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Sort of(String id) {
            for (Sort s : values()) {
                if (s.id().equals(id)) {
                    return s;
                }
            }
            return RECENT;
        }
    }

    /**
     * What to keep. {@code loader} is "vanilla" or a loader's lower-case name; {@code version} a Minecraft version;
     * {@code shardlight} true for projects with Shardlight modules, false for those without; blank or null = any.
     */
    public record Filter(String text, String loader, String version, Boolean shardlight) {
        public static final Filter NONE = new Filter("", null, null, null);

        public boolean active() {
            return !blank(text) || !blank(loader) || !blank(version) || shardlight != null;
        }
    }

    /** a folder with its projects, or (group == null) the projects in no folder */
    public record Section(Library.Group group, List<Project> projects) {
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** "vanilla", "fabric", "forge"...: the key the loader filter compares */
    public static String loaderKey(Project p) {
        return p.loader == null || p.loader.isVanilla() ? "vanilla" : p.loader.type.name().toLowerCase(Locale.ROOT);
    }

    public static boolean matches(Project p, Filter f) {
        if (!blank(f.text())) {
            String q = f.text().trim().toLowerCase(Locale.ROOT);
            boolean hit = (p.name != null && p.name.toLowerCase(Locale.ROOT).contains(q))
                || (p.minecraftVersion != null && p.minecraftVersion.toLowerCase(Locale.ROOT).contains(q))
                || loaderKey(p).contains(q);
            if (!hit) {
                return false;
            }
        }
        if (!blank(f.loader()) && !f.loader().equals(loaderKey(p))) {
            return false;
        }
        if (!blank(f.version()) && !f.version().equals(p.minecraftVersion)) {
            return false;
        }
        return f.shardlight() == null || f.shardlight() == p.hasShardlight();
    }

    public static Comparator<Project> comparator(Sort sort, List<String> customOrder) {
        Comparator<Project> byName = Comparator.comparing((Project p) -> p.name == null ? "" : p.name.toLowerCase(Locale.ROOT));
        return switch (sort) {
            case NAME -> byName;
            case CREATED -> Comparator.comparingLong((Project p) -> p.createdAt).reversed().thenComparing(byName);
            case CUSTOM -> Comparator.comparingInt((Project p) -> {
                int i = customOrder.indexOf(p.id);
                return i < 0 ? Integer.MAX_VALUE : i;
            }).thenComparing(Comparator.comparingLong((Project p) -> p.lastPlayedAt).reversed()).thenComparing(byName);
            default -> Comparator.comparingLong((Project p) -> p.lastPlayedAt).reversed().thenComparing(byName);
        };
    }

    /**
     * The sections to show: one per folder (in the saved order), then the projects in no folder (group == null).
     * A folder with no match is left out while a filter is active, and kept (empty) otherwise so projects can be
     * dropped into it. The last section always exists, even when empty.
     */
    public static List<Section> sections(List<Project> all, Filter filter, Sort sort, Library library) {
        Comparator<Project> order = comparator(sort, library.order);
        Set<String> grouped = new HashSet<>();
        List<Section> out = new ArrayList<>();
        for (Library.Group g : library.groups) {
            List<Project> members = all.stream().filter(p -> g.projects.contains(p.id)).toList();
            grouped.addAll(members.stream().map(p -> p.id).toList());
            List<Project> shown = members.stream().filter(p -> matches(p, filter)).sorted(order).toList();
            if (!shown.isEmpty() || !filter.active()) {
                out.add(new Section(g, shown));
            }
        }
        out.add(new Section(null, all.stream().filter(p -> !grouped.contains(p.id) && matches(p, filter)).sorted(order).toList()));
        return out;
    }

    /**
     * Move a project: into {@code groupId} (null = out of every folder), placed before {@code beforeId} in the
     * custom order (null = at the end).
     */
    public static void move(Library library, Collection<String> knownIds, String projectId, String groupId, String beforeId) {
        List<String> order = new ArrayList<>(library.order);
        for (String id : knownIds) {
            if (!order.contains(id)) {
                order.add(id);
            }
        }
        order.remove(projectId);
        int at = beforeId == null ? -1 : order.indexOf(beforeId);
        order.add(at < 0 ? order.size() : at, projectId);
        library.order = order;
        for (Library.Group g : library.groups) {
            g.projects.remove(projectId);
        }
        if (groupId != null) {
            group(library, groupId).ifPresent(g -> g.projects.add(projectId));
        }
    }

    public static Optional<Library.Group> group(Library library, String id) {
        return library.groups.stream().filter(g -> g.id.equals(id)).findFirst();
    }

    public static Library.Group addGroup(Library library, String name) {
        Library.Group g = new Library.Group();
        g.id = UUID.randomUUID().toString().substring(0, 8);
        g.name = name.trim();
        library.groups.add(g);
        return g;
    }

    /** delete a folder; its projects go back to the main list */
    public static void removeGroup(Library library, String id) {
        library.groups.removeIf(g -> g.id.equals(id));
    }

    /** forget projects that no longer exist */
    public static void prune(Library library, Collection<String> existingIds) {
        library.order.retainAll(existingIds);
        for (Library.Group g : library.groups) {
            g.projects.retainAll(existingIds);
        }
    }
}

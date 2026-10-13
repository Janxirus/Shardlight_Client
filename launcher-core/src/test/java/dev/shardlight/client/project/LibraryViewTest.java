package dev.shardlight.client.project;

import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.project.LibraryView.Filter;
import dev.shardlight.client.project.LibraryView.Section;
import dev.shardlight.client.project.LibraryView.Sort;
import dev.shardlight.client.settings.LauncherSettings.Library;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryViewTest {
    private static Project project(String id, String version, LoaderSpec loader, long created, long played, String... modules) {
        Project p = new Project();
        p.id = id;
        p.name = id.toUpperCase();
        p.minecraftVersion = version;
        p.loader = loader;
        p.createdAt = created;
        p.lastPlayedAt = played;
        p.shardlightModules.addAll(List.of(modules));
        return p;
    }

    private final Project alpha = project("alpha", "26.3", new LoaderSpec(LoaderType.FABRIC, "0.19.5"), 100, 300, "ui");
    private final Project bravo = project("bravo", "26.2", LoaderSpec.vanilla(), 300, 100);
    private final Project charlie = project("charlie", "26.3", LoaderSpec.vanilla(), 200, 200);
    private final List<Project> all = List.of(alpha, bravo, charlie);

    private static List<String> ids(Section s) {
        return s.projects().stream().map(p -> p.id).toList();
    }

    @Test
    void sortsByLastPlayedNameAndCreated() {
        Library lib = new Library();
        assertEquals(List.of("alpha", "charlie", "bravo"), ids(LibraryView.sections(all, Filter.NONE, Sort.RECENT, lib).get(0)));
        assertEquals(List.of("alpha", "bravo", "charlie"), ids(LibraryView.sections(all, Filter.NONE, Sort.NAME, lib).get(0)));
        assertEquals(List.of("bravo", "charlie", "alpha"), ids(LibraryView.sections(all, Filter.NONE, Sort.CREATED, lib).get(0)));
    }

    @Test
    void filtersByLoaderVersionAndShardlight() {
        Library lib = new Library();
        assertEquals(List.of("alpha"), ids(LibraryView.sections(all, new Filter("", "fabric", null, null), Sort.NAME, lib).get(0)));
        assertEquals(List.of("bravo", "charlie"), ids(LibraryView.sections(all, new Filter("", "vanilla", null, null), Sort.NAME, lib).get(0)));
        assertEquals(List.of("bravo"), ids(LibraryView.sections(all, new Filter("", null, "26.2", null), Sort.NAME, lib).get(0)));
        assertEquals(List.of("alpha"), ids(LibraryView.sections(all, new Filter("", null, null, true), Sort.NAME, lib).get(0)));
        assertEquals(List.of("bravo", "charlie"), ids(LibraryView.sections(all, new Filter("", null, null, false), Sort.NAME, lib).get(0)));
        assertEquals(List.of("charlie"), ids(LibraryView.sections(all, new Filter("char", "vanilla", "26.3", false), Sort.NAME, lib).get(0)));
    }

    @Test
    void draggingReordersAndFoldersHoldProjects() {
        Library lib = new Library();
        LibraryView.move(lib, List.of("alpha", "bravo", "charlie"), "charlie", null, "alpha");
        assertEquals(List.of("charlie", "alpha", "bravo"), ids(LibraryView.sections(all, Filter.NONE, Sort.CUSTOM, lib).get(0)));

        Library.Group g = LibraryView.addGroup(lib, " Survival ");
        assertEquals("Survival", g.name);
        LibraryView.move(lib, List.of("alpha", "bravo", "charlie"), "alpha", g.id, null);
        List<Section> sections = LibraryView.sections(all, Filter.NONE, Sort.CUSTOM, lib);
        assertEquals(2, sections.size());
        assertEquals(List.of("alpha"), ids(sections.get(0)));
        assertEquals(List.of("charlie", "bravo"), ids(sections.get(1)));

        LibraryView.move(lib, List.of("alpha", "bravo", "charlie"), "alpha", null, null);
        assertTrue(g.projects.isEmpty(), "moving out of a folder empties it");
        LibraryView.removeGroup(lib, g.id);
        assertEquals(1, LibraryView.sections(all, Filter.NONE, Sort.CUSTOM, lib).size());
    }

    @Test
    void emptyFoldersStayUnlessAFilterIsActive() {
        Library lib = new Library();
        LibraryView.addGroup(lib, "Empty");
        assertEquals(2, LibraryView.sections(all, Filter.NONE, Sort.RECENT, lib).size());
        assertEquals(1, LibraryView.sections(all, new Filter("alpha", null, null, null), Sort.RECENT, lib).size());
    }

    @Test
    void forgetsDeletedProjects() {
        Library lib = new Library();
        Library.Group g = LibraryView.addGroup(lib, "G");
        LibraryView.move(lib, List.of("alpha", "bravo"), "alpha", g.id, null);
        LibraryView.prune(lib, List.of("bravo"));
        assertEquals(List.of("bravo"), lib.order);
        assertTrue(g.projects.isEmpty());
    }
}

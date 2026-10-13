package dev.shardlight.client.project;

import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.storage.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectTemplatesTest {
    @TempDir
    Path dir;

    @Test
    void onlyTheShardlightTemplatePreselectsModules() {
        for (ProjectTemplate t : ProjectTemplates.all()) {
            if (t.id().equals("shardlight-default")) {
                assertEquals(Set.of("ui", "map", "party", "companion"), t.preselectedModules());
                assertEquals("ember", t.shardlightTheme());
                assertEquals(LoaderType.FABRIC, t.suggestedLoader());
                assertEquals(Preset.BALANCED, t.preset());
            } else {
                assertTrue(t.preselectedModules().isEmpty(), t.id());
                assertNull(t.shardlightTheme(), t.id());
            }
        }
    }

    @Test
    void everyTemplateIsDefinedAsSpecified() {
        List<String> ids = ProjectTemplates.all().stream().map(ProjectTemplate::id).toList();
        assertEquals(List.of("vanilla", "shardlight-default", "ultra-performance", "balanced", "creative-building", "pvp",
            "exploration", "modded-survival", "custom"), ids);
        assertEquals(ids.size(), new HashSet<>(ids).size());

        ProjectTemplate vanilla = ProjectTemplates.byId("vanilla").orElseThrow();
        assertEquals(LoaderType.VANILLA, vanilla.suggestedLoader());
        assertEquals(Preset.BALANCED, vanilla.preset());
        assertTrue(vanilla.suggestions().isEmpty());

        ProjectTemplate ultra = ProjectTemplates.byId("ultra-performance").orElseThrow();
        assertEquals(LoaderType.FABRIC, ultra.suggestedLoader());
        assertEquals(Preset.ULTRA_PERFORMANCE, ultra.preset());
        assertEquals(List.of("sodium", "lithium", "ferrite-core"),
            ultra.suggestions().stream().map(SuggestedContent::projectSlug).toList());
        assertTrue(ultra.suggestions().stream().allMatch(s -> s.source().equals("modrinth") && s.kind() == ContentKind.MOD));

        assertEquals(Preset.BALANCED, ProjectTemplates.byId("balanced").orElseThrow().preset());
        assertEquals(Preset.PERFORMANCE, ProjectTemplates.byId("pvp").orElseThrow().preset());
        assertTrue(ProjectTemplates.byId("creative-building").orElseThrow().suggestions().stream()
            .anyMatch(s -> s.projectSlug().equals("worldedit")));

        ProjectTemplate exploration = ProjectTemplates.byId("exploration").orElseThrow();
        assertTrue(exploration.preselectedModules().isEmpty(), "the map is only suggested");
        assertTrue(exploration.suggestions().stream()
            .anyMatch(s -> s.source().equals("shardlight") && s.projectSlug().equals("map")));

        assertTrue(ProjectTemplates.byId("custom").orElseThrow().custom());
        assertEquals(1, ProjectTemplates.all().stream().filter(ProjectTemplate::custom).count());
        assertTrue(ProjectTemplates.byId("PvP ").isPresent());
        assertTrue(ProjectTemplates.byId("nope").isEmpty());
        assertTrue(ProjectTemplates.byId(null).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> ProjectTemplates.all().get(0).suggestions().add(null));
    }

    @Test
    void everyStringExistsInTheBundle() {
        for (ProjectTemplate t : ProjectTemplates.all()) {
            assertTrue(I18n.has(t.nameKey()), t.nameKey());
            assertTrue(I18n.has(t.descriptionKey()), t.descriptionKey());
            for (SuggestedContent s : t.suggestions()) {
                assertTrue(I18n.has(s.reasonKey()), s.reasonKey());
            }
        }
    }

    @Test
    void templatesBuildCreationRequests() throws Exception {
        ProjectService svc = new ProjectService(new AppPaths(dir));
        Project sl = svc.create(ProjectTemplates.byId("shardlight-default").orElseThrow().request("Shiny", "1.21.4", "0.16.10"));
        assertEquals(List.of("ui", "map", "party", "companion"), List.copyOf(sl.shardlightModules));
        assertEquals("ember", sl.shardlightTheme);
        assertEquals("shardlight-default", sl.template);
        assertEquals("0.16.10", sl.loader.version);
        Project plain = svc.create(ProjectTemplates.byId("ultra-performance").orElseThrow().request("Fast", "1.21.4", "0.16.10"));
        assertFalse(plain.hasShardlight(), "suggestions are never installed or selected by themselves");
        assertTrue(plain.content.isEmpty());
        assertEquals(Preset.ULTRA_PERFORMANCE, plain.preset.active);
        Project vanilla = svc.create(ProjectTemplates.byId("vanilla").orElseThrow().request("Plain", "1.21.4", "ignored"));
        assertTrue(vanilla.loader.isVanilla());
    }
}

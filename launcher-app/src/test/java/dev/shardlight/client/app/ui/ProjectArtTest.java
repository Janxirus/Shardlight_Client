package dev.shardlight.client.app.ui;

import dev.shardlight.client.project.Project;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectArtTest {
    private static Project project(String name, String template) {
        Project p = new Project();
        p.name = name;
        p.template = template;
        return p;
    }

    @Test
    void sameProjectAlwaysGetsTheSameArt() {
        assertEquals(ProjectArt.palette(project("Survival", "vanilla")), ProjectArt.palette(project("Survival", "vanilla")));
    }

    @Test
    void differentNamesLookDifferent() {
        Set<ProjectArt.Palette> seen = new HashSet<>();
        for (String n : new String[]{"Survival", "Creative", "Skyblock", "Hardcore", "Friends", "Testing", "Redstone", "Build"}) {
            seen.add(ProjectArt.palette(project(n, "vanilla")));
        }
        assertEquals(8, seen.size());
    }

    @Test
    void templateColourSetsTheBaseHue() {
        double pvp = ProjectArt.palette(project("Same", "pvp")).hue();
        double building = ProjectArt.palette(project("Same", "creative-building")).hue();
        assertTrue(Math.abs(pvp - building) > 20, pvp + " vs " + building);
    }
}

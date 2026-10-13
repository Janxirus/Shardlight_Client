package dev.shardlight.client.app.ui;

import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.project.Project;
import javafx.scene.control.ListCell;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Short, consistent descriptions of a project for chips and subtitles. */
public final class ProjectText {
    private ProjectText() {
    }

    public static String loader(Project p) {
        if (p.loader == null || p.loader.isVanilla()) {
            return I18n.tr("ui.project.vanilla");
        }
        return p.loader.type.displayName() + (p.loader.version == null ? "" : " " + p.loader.version);
    }

    /** just the loader's name ("Fabric", "Vanilla"), without its version: for badges */
    public static String loaderName(Project p) {
        return p.loader == null || p.loader.isVanilla() ? I18n.tr("ui.project.vanilla") : p.loader.type.displayName();
    }

    public static String java(Project p) {
        return p.java != null && p.java.javaPath != null && !p.java.javaPath.isBlank()
            ? I18n.tr("ui.project.javaCustom") : I18n.tr("ui.project.javaAuto");
    }

    public static String memory(Project p) {
        int mb = p.java == null ? 4096 : p.java.maxMemoryMb;
        return mb % 1024 == 0 ? I18n.tr("ui.project.memoryGb", mb / 1024) : I18n.tr("ui.project.memoryMb", mb);
    }

    public static String preset(Project p) {
        Preset pr = p.preset == null || p.preset.active == null ? Preset.DEFAULT : p.preset.active;
        return I18n.tr(pr.nameKey());
    }

    public static long count(Project p, ContentKind kind, boolean enabledOnly) {
        if (p.content == null) {
            return 0;
        }
        return p.content.stream().filter(e -> e.kind == kind && (!enabledOnly || e.enabled)).count();
    }

    /** "12 mods, 2 resource packs": what the project holds, switched on or not (the Overview counts what is on) */
    public static String contentSummary(Project p) {
        long mods = count(p, ContentKind.MOD, false);
        long packs = count(p, ContentKind.RESOURCE_PACK, false);
        long shaders = count(p, ContentKind.SHADER_PACK, false);
        return I18n.tr("ui.project.contentSummary", mods, packs, shaders);
    }

    public static String lastPlayed(Project p) {
        if (p.lastPlayedAt <= 0) {
            return I18n.tr("ui.project.neverPlayed");
        }
        return I18n.tr("ui.project.lastPlayed", ago(p.lastPlayedAt));
    }

    public static String ago(long epochMillis) {
        Duration d = Duration.between(Instant.ofEpochMilli(epochMillis), Instant.now());
        if (d.toMinutes() < 1) {
            return I18n.tr("ui.time.justNow");
        }
        if (d.toHours() < 1) {
            return I18n.tr("ui.time.minutesAgo", d.toMinutes());
        }
        if (d.toDays() < 1) {
            return I18n.tr("ui.time.hoursAgo", d.toHours());
        }
        if (d.toDays() < 60) {
            return I18n.tr("ui.time.daysAgo", d.toDays());
        }
        return I18n.tr("ui.time.date", java.util.Date.from(Instant.ofEpochMilli(epochMillis)));
    }

    public static String entryTitle(ContentEntry e) {
        return e.title != null && !e.title.isBlank() ? e.title : e.fileName;
    }

    /** module ids as display names: "UI, Map" */
    public static String modules(Project p) {
        if (!p.hasShardlight()) {
            return I18n.tr("ui.shardlight.none");
        }
        // always UI, Map, Party, Companion, whatever order the project file lists them in
        List<String> order = dev.shardlight.client.project.ProjectTemplates.MODULE_ORDER;
        List<String> ids = new java.util.ArrayList<>(p.shardlightModules);
        ids.sort(java.util.Comparator.comparingInt(m -> order.contains(m) ? order.indexOf(m) : order.size()));
        StringBuilder b = new StringBuilder();
        for (String m : ids) {
            if (!b.isEmpty()) {
                b.append(", ");
            }
            b.append(I18n.tr("ui.shardlight.module." + m));
        }
        return b.toString();
    }

    /** a list cell for project pickers: "name  ·  version loader", or {@code emptyText} when nothing is chosen */
    public static ListCell<Project> cell(String emptyText) {
        return new ListCell<>() {
            @Override
            protected void updateItem(Project p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? emptyText : p.name + "  ·  " + p.minecraftVersion + " " + loader(p));
            }
        };
    }
}

package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ToggleSwitch;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.catalog.CatalogFile;
import dev.shardlight.client.catalog.CatalogVersion;
import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.content.ContentSource;
import dev.shardlight.client.content.HistoryEvent;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.Project;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A project's mods, resource packs and shader packs: switch each on or off for the next launch, pin,
 * remove (recoverable from the history), update from Modrinth, set the resource pack order, roll back.
 */
final class ContentTab {
    private final AppContext ctx;
    private final String projectId;
    private final VBox root = new VBox(12);
    private final VBox list = new VBox(6);
    private final TextField filter = new TextField();
    private ContentKind kind = ContentKind.MOD;
    private boolean showHistory;
    private boolean ordering;
    /** entry id -> newer Modrinth version, after "Check for updates" */
    private final Map<String, CatalogVersion> updates = new HashMap<>();

    ContentTab(AppContext ctx, String projectId) {
        this.ctx = ctx;
        this.projectId = projectId;
        filter.setPromptText(I18n.tr("ui.content.filter"));
        filter.setAccessibleText(I18n.tr("ui.content.filter"));
        filter.textProperty().addListener((o, a, b) -> render());
    }

    VBox view() {
        return root;
    }

    private Optional<Project> project() {
        return ctx.services().projects().get(projectId);
    }

    void refresh() {
        Optional<Project> found = project();
        if (found.isEmpty()) {
            return;
        }
        // reconcile with the folders first, so files added by hand show up
        Tasks.quiet(ctx.overlay(), () -> ctx.services().content().scan(found.get()), entries -> render());
    }

    private void render() {
        root.getChildren().clear();
        Optional<Project> found = project();
        if (found.isEmpty()) {
            return;
        }
        Project p = found.get();
        int selected = switch (kind) {
            case RESOURCE_PACK -> 1;
            case SHADER_PACK -> 2;
            default -> 0;
        };
        HBox kinds = UiKit.segmented(List.of(
            I18n.tr("ui.content.mods", ProjectText.count(p, ContentKind.MOD, false)),
            I18n.tr("ui.content.resourcePacks", ProjectText.count(p, ContentKind.RESOURCE_PACK, false)),
            I18n.tr("ui.content.shaderPacks", ProjectText.count(p, ContentKind.SHADER_PACK, false))), showHistory ? -1 : selected, i -> {
                kind = switch (i) {
                    case 1 -> ContentKind.RESOURCE_PACK;
                    case 2 -> ContentKind.SHADER_PACK;
                    default -> ContentKind.MOD;
                };
                showHistory = false;
                ordering = false;
                render();
            });
        Button history = UiKit.button(I18n.tr("ui.content.history"), Icons.UPDATE, () -> {
            showHistory = !showHistory;
            render();
        });
        if (showHistory) {
            history.getStyleClass().add("primary");
        }
        HBox top = new HBox(10, kinds, UiKit.spacer(), history);
        top.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(top);
        if (showHistory) {
            renderHistory(p);
            return;
        }

        Button add = UiKit.primary(I18n.tr("ui.content.browse"), Icons.DISCOVER, () -> {
            ctx.selectProject(p.id);
            DiscoverPage.openFor(ctx, kind);
        });
        Button file = UiKit.button(I18n.tr("ui.content.addFile"), Icons.PLUS, () -> addFiles(p));
        Button check = UiKit.button(I18n.tr("ui.content.checkUpdates"), Icons.REFRESH, () -> checkUpdates(p));
        Button folder = UiKit.iconButton(Icons.FOLDER, I18n.tr("ui.library.openFolder"),
            () -> ctx.openFolder(ctx.services().projects().gameDir(p).resolve(kind.folder())));
        HBox actions = new HBox(8, add, file, check);
        if (kind == ContentKind.RESOURCE_PACK) {
            Button order = UiKit.button(I18n.tr(ordering ? "ui.content.orderDone" : "ui.content.order"), Icons.LIBRARY, () -> {
                ordering = !ordering;
                render();
            });
            actions.getChildren().add(order);
        }
        long updatable = updates.keySet().stream().filter(id -> p.content.stream().anyMatch(e -> e.id.equals(id) && !e.pinned)).count();
        if (updatable > 0) {
            actions.getChildren().add(UiKit.button(I18n.tr("ui.content.updateAll", updatable), Icons.UPDATE, () -> updateAll(p)));
        }
        HBox.setHgrow(filter, Priority.ALWAYS);
        filter.setMaxWidth(320);
        HBox bar = new HBox(8, actions, UiKit.spacer(), filter, folder);
        bar.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(bar);

        if (kind == ContentKind.MOD && p.loader.isVanilla()) {
            root.getChildren().add(UiKit.banner("info", I18n.tr("ui.content.vanillaNoMods")));
        }
        if (kind == ContentKind.MOD) {
            root.getChildren().add(UiKit.label(I18n.tr("ui.content.disableHint"), "faint", "small"));
        }
        if (kind == ContentKind.SHADER_PACK) {
            root.getChildren().add(UiKit.label(I18n.tr("ui.content.shaderHint"), "faint", "small"));
        }
        list.getChildren().clear();
        if (ordering && kind == ContentKind.RESOURCE_PACK) {
            renderOrder(p);
        } else {
            renderEntries(p);
        }
        root.getChildren().add(list);
    }

    private void renderEntries(Project p) {
        String q = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        List<ContentEntry> entries = p.content.stream().filter(e -> e.kind == kind)
            .filter(e -> q.isEmpty() || ProjectText.entryTitle(e).toLowerCase(Locale.ROOT).contains(q)
                || (e.modId != null && e.modId.contains(q)))
            .sorted((a, b) -> ProjectText.entryTitle(a).compareToIgnoreCase(ProjectText.entryTitle(b)))
            .toList();
        if (entries.isEmpty()) {
            list.getChildren().add(UiKit.emptyState(Icons.PACKAGE, I18n.tr("ui.content.emptyTitle"),
                I18n.tr("ui.content.emptyBody"), null));
            return;
        }
        for (ContentEntry e : entries) {
            list.getChildren().add(row(p, e));
        }
    }

    private Node row(Project p, ContentEntry e) {
        ToggleSwitch on = new ToggleSwitch(e.enabled);
        on.setAccessibleText(I18n.tr("ui.content.enabledFor", ProjectText.entryTitle(e)));
        on.selectedProperty().addListener((o, a, b) -> Tasks.quiet(ctx.overlay(), () -> {
            ctx.services().content().setEnabled(p, e.id, b);
            return b;
        }, v -> {
            ctx.overlay().toast("info", I18n.tr(v ? "ui.content.enabled" : "ui.content.disabled", ProjectText.entryTitle(e)));
            ctx.projectsChanged();
            render();
        }));
        Label title = UiKit.label(ProjectText.entryTitle(e), "project-name");
        javafx.scene.layout.FlowPane meta = new javafx.scene.layout.FlowPane(6, 4);
        if (e.versionNumber != null) {
            meta.getChildren().add(UiKit.label(e.versionNumber, "muted", "small"));
        }
        meta.getChildren().add(UiKit.chip(I18n.tr("ui.content.source." + e.source.name().toLowerCase(Locale.ROOT)),
            e.source == ContentSource.SHARDLIGHT ? "accent" : null));
        if (e.dependency) {
            meta.getChildren().add(UiKit.chip(I18n.tr("ui.content.dependency"), null));
        }
        if (isMissing(e)) {
            meta.getChildren().add(UiKit.chip(I18n.tr("ui.content.missing"), "danger"));
        }
        if (!e.enabled) {
            meta.getChildren().add(UiKit.chip(I18n.tr("ui.content.off"), "warning"));
        }
        CatalogVersion update = updates.get(e.id);
        if (update != null) {
            meta.getChildren().add(UiKit.chip(I18n.tr("ui.content.updateTo", update.versionNumber()), "success", Icons.UPDATE));
        }
        VBox text = new VBox(3, title, meta);
        if (e.fileName != null) {
            text.getChildren().add(UiKit.label(e.fileName, "faint", "tiny"));
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        Button pin = UiKit.iconButton(Icons.PIN, I18n.tr(e.pinned ? "ui.content.unpin" : "ui.content.pin"),
            () -> Tasks.quiet(ctx.overlay(), () -> {
                ctx.services().content().setPinned(p, e.id, !e.pinned);
                return true;
            }, x -> render()));
        if (e.pinned) {
            pin.getStyleClass().add("accent-icon");
        }
        HBox row = new HBox(12, on, text);
        row.setAlignment(Pos.CENTER_LEFT);
        if (update != null && !e.pinned) {
            row.getChildren().add(UiKit.button(I18n.tr("ui.content.update"), Icons.UPDATE, () -> updateOne(p, e, update)));
        }
        if (e.pageUrl != null) {
            row.getChildren().add(UiKit.iconButton(Icons.EXTERNAL, I18n.tr("ui.content.openPage"), () -> ctx.openUrl(e.pageUrl)));
        }
        Button remove = UiKit.iconButton(Icons.TRASH, I18n.tr("ui.content.removeFor", ProjectText.entryTitle(e)),
            () -> confirmRemove(p, e));
        row.getChildren().addAll(pin, remove);
        row.getStyleClass().addAll("card", "flat", "content-row");
        return row;
    }

    private boolean isMissing(ContentEntry e) {
        try {
            var f = ContentEntry.class.getField("missing");
            return f.getBoolean(e);
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    private void confirmRemove(Project p, ContentEntry e) {
        List<String> details = new ArrayList<>();
        if (e.requiredBy != null && !e.requiredBy.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String id : e.requiredBy) {
                p.content.stream().filter(x -> x.id.equals(id)).findFirst().ifPresent(x -> names.add(ProjectText.entryTitle(x)));
            }
            if (!names.isEmpty()) {
                details.add(I18n.tr("ui.content.requiredBy", String.join(", ", names)));
            }
        }
        details.add(I18n.tr("ui.content.removeUndo"));
        ctx.overlay().confirm(I18n.tr("ui.content.removeTitle", ProjectText.entryTitle(e)), I18n.tr("ui.content.removeMessage"),
            details, I18n.tr("ui.content.remove"), true, () -> Tasks.quiet(ctx.overlay(), () -> {
                ctx.services().content().remove(p, e.id);
                return true;
            }, x -> {
                ctx.overlay().toast("success", I18n.tr("ui.content.removed", ProjectText.entryTitle(e)));
                ctx.projectsChanged();
                render();
            }));
    }

    private void addFiles(Project p) {
        String[] ext = kind == ContentKind.MOD ? new String[] {"*.jar"} : new String[] {"*.zip"};
        List<Path> files = Dialogs.openFiles(ctx.stage(), I18n.tr("ui.content.addFile"),
            I18n.tr("ui.content.filter." + kind.name().toLowerCase(Locale.ROOT)), ext);
        if (files.isEmpty()) {
            return;
        }
        ContentKind k = kind;
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.content.adding"), false, (pr, c) -> {
            int i = 0;
            for (Path f : files) {
                pr.progress("ui.content.adding", f.getFileName().toString(), i++, files.size());
                ctx.services().content().installLocalFile(p, k, f, ContentSource.LOCAL, null, null);
            }
            return files.size();
        }, n -> {
            ctx.overlay().toast("success", I18n.tr("ui.content.added", n));
            ctx.projectsChanged();
            render();
        });
    }

    private void checkUpdates(Project p) {
        List<ContentEntry> modrinth = p.content.stream()
            .filter(e -> e.kind == kind && e.sha1 != null && (e.source == ContentSource.MODRINTH || e.source == ContentSource.MODPACK))
            .toList();
        if (modrinth.isEmpty()) {
            ctx.overlay().toast("info", I18n.tr("ui.content.noCatalogEntries"));
            return;
        }
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.content.checking"), true, (pr, c) -> {
            pr.stage("ui.content.checking");
            var loader = kind == ContentKind.MOD ? p.loader.type : null;
            return ctx.services().modrinth().latestVersionsByHash(modrinth.stream().map(e -> e.sha1).toList(),
                p.minecraftVersion, loader, c);
        }, map -> {
            updates.clear();
            for (ContentEntry e : modrinth) {
                CatalogVersion v = map.get(e.sha1);
                if (v != null && !v.id().equals(e.versionId)) {
                    updates.put(e.id, v);
                }
            }
            ctx.overlay().toast(updates.isEmpty() ? "success" : "info", updates.isEmpty() ? I18n.tr("ui.content.upToDate")
                : I18n.tr("ui.content.updatesFound", updates.size()));
            render();
        });
    }

    private static Optional<CatalogFile> primary(CatalogVersion v) {
        return v.files().stream().filter(CatalogFile::primary).findFirst().or(() -> v.files().stream().findFirst());
    }

    private void updateOne(Project p, ContentEntry e, CatalogVersion v) {
        Optional<CatalogFile> f = primary(v);
        if (f.isEmpty()) {
            return;
        }
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.content.updating", ProjectText.entryTitle(e)), true,
            (pr, c) -> ctx.services().content().update(p, e.id, v, f.get(), pr, c), x -> {
                updates.remove(e.id);
                ctx.overlay().toast("success", I18n.tr("ui.content.updated", ProjectText.entryTitle(e), v.versionNumber()));
                ctx.projectsChanged();
                render();
            });
    }

    private void updateAll(Project p) {
        List<ContentEntry> todo = p.content.stream().filter(e -> updates.containsKey(e.id) && !e.pinned).toList();
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.content.updatingAll", todo.size()), true, (pr, c) -> {
            int done = 0;
            for (ContentEntry e : todo) {
                CatalogVersion v = updates.get(e.id);
                Optional<CatalogFile> f = primary(v);
                if (f.isPresent()) {
                    ctx.services().content().update(p, e.id, v, f.get(), pr, c);
                    done++;
                }
            }
            return done;
        }, n -> {
            todo.forEach(e -> updates.remove(e.id));
            ctx.overlay().toast("success", I18n.tr("ui.content.updatedAll", n));
            ctx.projectsChanged();
            render();
        });
    }

    private void renderOrder(Project p) {
        List<String> order;
        try {
            order = new ArrayList<>(ctx.services().content().resourcePackOrder(p));
        } catch (Exception e) {
            list.getChildren().add(UiKit.banner("danger", Tasks.message(e)));
            return;
        }
        list.getChildren().add(UiKit.label(I18n.tr("ui.content.orderHint"), "muted", "small"));
        if (order.isEmpty()) {
            list.getChildren().add(UiKit.label(I18n.tr("ui.content.orderEmpty"), "muted"));
            return;
        }
        for (int i = 0; i < order.size(); i++) {
            int idx = i;
            String name = order.get(i);
            Button up = UiKit.iconButton(Icons.BACK, I18n.tr("ui.content.moveUp", name), () -> move(p, order, idx, -1));
            up.setRotate(90);
            up.setDisable(i == 0);
            Button down = UiKit.iconButton(Icons.BACK, I18n.tr("ui.content.moveDown", name), () -> move(p, order, idx, 1));
            down.setRotate(-90);
            down.setDisable(i == order.size() - 1);
            Label n = UiKit.label((i + 1) + ".  " + name);
            HBox.setHgrow(n, Priority.ALWAYS);
            HBox row = new HBox(8, n, up, down);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().addAll("card", "flat");
            list.getChildren().add(row);
        }
    }

    private void move(Project p, List<String> order, int idx, int delta) {
        int target = idx + delta;
        if (target < 0 || target >= order.size()) {
            return;
        }
        List<String> next = new ArrayList<>(order);
        String moved = next.remove(idx);
        next.add(target, moved);
        Tasks.quiet(ctx.overlay(), () -> {
            ctx.services().content().setResourcePackOrder(p, next);
            return true;
        }, x -> render());
    }

    private void renderHistory(Project p) {
        Tasks.quiet(ctx.overlay(), () -> ctx.services().content().history(p), events -> {
            VBox box = new VBox(6);
            if (events.isEmpty()) {
                box.getChildren().add(UiKit.label(I18n.tr("ui.content.historyEmpty"), "muted"));
            }
            for (HistoryEvent ev : events) {
                Label what = UiKit.label(I18n.tr("ui.content.action." + ev.action(), ev.title() == null ? "" : ev.title()));
                Label when = UiKit.label(ProjectText.ago(ev.time()), "faint", "small");
                VBox text = new VBox(2, what, when);
                HBox.setHgrow(text, Priority.ALWAYS);
                Button undo = UiKit.button(I18n.tr("ui.content.rollback"), Icons.REFRESH, () -> ctx.overlay().confirm(
                    I18n.tr("ui.content.rollbackTitle"), I18n.tr("ui.content.rollbackMessage", what.getText()), List.of(),
                    I18n.tr("ui.content.rollback"), false, () -> Tasks.quiet(ctx.overlay(), () -> {
                        ctx.services().content().rollback(p, ev.id());
                        return true;
                    }, x -> {
                        ctx.overlay().toast("success", I18n.tr("ui.content.rolledBack"));
                        ctx.projectsChanged();
                        render();
                    })));
                undo.setDisable("rollback".equals(ev.action()));
                HBox row = new HBox(10, text, undo);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().addAll("card", "flat");
                box.getChildren().add(row);
            }
            root.getChildren().add(box);
        });
    }
}

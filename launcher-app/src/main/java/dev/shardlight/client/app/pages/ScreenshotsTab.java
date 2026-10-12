package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.Screenshot;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;

/** A project's screenshots as thumbnails (loaded in the background), newest first. */
final class ScreenshotsTab {
    private static final int SHOWN = 60;
    private final AppContext ctx;
    private final String projectId;
    private final VBox root = new VBox(10);

    ScreenshotsTab(AppContext ctx, String projectId) {
        this.ctx = ctx;
        this.projectId = projectId;
    }

    VBox view() {
        return root;
    }

    void refresh() {
        Optional<Project> found = ctx.services().projects().get(projectId);
        if (found.isEmpty()) {
            return;
        }
        Project p = found.get();
        Tasks.quiet(ctx.overlay(), () -> ctx.services().screenshots().list(p), shots -> render(p, shots));
    }

    private void render(Project p, List<Screenshot> shots) {
        root.getChildren().clear();
        Button folder = UiKit.button(I18n.tr("ui.shots.openFolder"), Icons.FOLDER,
            () -> ctx.openFolder(ctx.services().projects().gameDir(p).resolve("screenshots")));
        HBox bar = new HBox(8, UiKit.label(I18n.tr("ui.shots.count", shots.size()), "muted"), UiKit.spacer(), folder);
        bar.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(bar);
        if (shots.isEmpty()) {
            root.getChildren().add(UiKit.emptyState(Icons.IMAGE, I18n.tr("ui.shots.emptyTitle"), I18n.tr("ui.shots.emptyBody"), null));
            return;
        }
        FlowPane grid = new FlowPane(10, 10);
        shots.stream().limit(SHOWN).forEach(s -> grid.getChildren().add(tile(p, s)));
        root.getChildren().add(grid);
        if (shots.size() > SHOWN) {
            root.getChildren().add(UiKit.label(I18n.tr("ui.shots.more", shots.size() - SHOWN), "faint", "small"));
        }
    }

    private Node tile(Project p, Screenshot s) {
        ImageView v = new ImageView(new Image(s.file().toUri().toString(), 220, 0, true, true, true));
        v.setFitWidth(220);
        v.setPreserveRatio(true);
        Button trash = UiKit.iconButton(Icons.TRASH, I18n.tr("ui.shots.trash"), () -> ctx.overlay().confirm(
            I18n.tr("ui.shots.trashTitle"), I18n.tr("ui.shots.trashMessage", s.file().getFileName()), List.of(),
            I18n.tr("ui.shots.trash"), true, () -> Tasks.quiet(ctx.overlay(), () -> {
                ctx.services().screenshots().moveToTrash(p, s.file());
                return true;
            }, x -> refresh())));
        HBox info = new HBox(6, UiKit.label(ProjectText.ago(s.takenAt()), "faint", "small"), UiKit.spacer(), trash);
        info.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(6, v, info);
        box.getStyleClass().addAll("card", "flat");
        box.setMaxWidth(244);
        box.setOnMouseClicked(e -> {
            if (e.getClickCount() >= 2) {
                ctx.openFolder(s.file().getParent());
            }
        });
        return box;
    }
}

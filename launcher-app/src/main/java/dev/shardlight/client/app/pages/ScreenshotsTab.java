package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.MasonryPane;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.Screenshot;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/** A project's screenshots as a masonry gallery (newest first); click one to view it full size. */
final class ScreenshotsTab {
    private static final int PAGE = 60;
    private final AppContext ctx;
    private final String projectId;
    private final VBox root = new VBox();
    private int shown = PAGE;

    ScreenshotsTab(AppContext ctx, String projectId) {
        this.ctx = ctx;
        this.projectId = projectId;
        root.getStyleClass().add("stack");
    }

    VBox view() {
        return root;
    }

    private record Shot(Screenshot shot, double ratio) {
    }

    /** --capture only: open the newest screenshot in the viewer */
    void previewViewer() {
        ctx.services().projects().get(projectId).ifPresent(p -> Tasks.quiet(ctx.overlay(),
            () -> ctx.services().screenshots().list(p), shots -> {
                if (!shots.isEmpty()) {
                    ScreenshotViewer.show(ctx, shots, 0, x -> { });
                }
            }));
    }

    void refresh() {
        Optional<Project> found = ctx.services().projects().get(projectId);
        if (found.isEmpty()) {
            return;
        }
        Project p = found.get();
        Tasks.quiet(ctx.overlay(), () -> ctx.services().screenshots().list(p).stream()
            .map(s -> new Shot(s, ratio(s))).toList(), shots -> render(p, shots));
    }

    /** height / width read from the file's header, so the gallery can be laid out before any picture has loaded */
    private static double ratio(Screenshot s) {
        try (ImageInputStream in = ImageIO.createImageInputStream(s.file().toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (readers.hasNext()) {
                ImageReader r = readers.next();
                try {
                    r.setInput(in);
                    return (double) r.getHeight(0) / r.getWidth(0);
                } finally {
                    r.dispose();
                }
            }
        } catch (IOException | RuntimeException e) {
            // an unreadable file still gets a tile
        }
        return 9.0 / 16;
    }

    private void render(Project p, List<Shot> shots) {
        root.getChildren().clear();
        Button folder = UiKit.button(I18n.tr("ui.shots.openFolder"), Icons.FOLDER,
            () -> ctx.openFolder(ctx.services().projects().gameDir(p).resolve("screenshots")));
        HBox bar = new HBox(UiKit.label(I18n.tr("ui.shots.count", shots.size()), "muted"), UiKit.spacer(), folder);
        bar.getStyleClass().add("tab-bar");
        root.getChildren().add(bar);
        if (shots.isEmpty()) {
            root.getChildren().add(UiKit.emptyState(Icons.IMAGE, I18n.tr("ui.shots.emptyTitle"), I18n.tr("ui.shots.emptyBody"), null));
            return;
        }
        List<Screenshot> all = shots.stream().map(Shot::shot).toList();
        MasonryPane gallery = new MasonryPane(14, 0.857);
        for (int i = 0; i < Math.min(shown, shots.size()); i++) {
            gallery.add(tile(p, all, i, shots.get(i)), shots.get(i).ratio());
        }
        root.getChildren().add(gallery);
        if (shots.size() > shown) {
            root.getChildren().add(UiKit.button(I18n.tr("ui.shots.showMore", shots.size() - shown), Icons.CHEVRON_DOWN, () -> {
                shown += PAGE;
                render(p, shots);
            }));
        }
    }

    private Node tile(Project p, List<Screenshot> all, int index, Shot shot) {
        Screenshot s = shot.shot();
        ImageView v = new ImageView(new Image(s.file().toUri().toString(), ThemeManager.em(36), 0, true, true, true));
        v.setPreserveRatio(true);
        v.setSmooth(true);
        StackPane tile = new StackPane(v);
        tile.getStyleClass().addAll("shot-tile", "interactive");
        v.fitWidthProperty().bind(tile.widthProperty());
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(tile.widthProperty());
        clip.heightProperty().bind(tile.heightProperty());
        double arc = ThemeManager.em(1.0);
        clip.setArcWidth(arc);
        clip.setArcHeight(arc);
        tile.setClip(clip);
        Label when = UiKit.label(ProjectText.ago(s.takenAt()), "small", "on-art");
        when.getStyleClass().add("shot-caption");
        StackPane.setAlignment(when, Pos.BOTTOM_LEFT);
        tile.getChildren().add(when);
        tile.setFocusTraversable(true);
        tile.setAccessibleText(I18n.tr("ui.shots.open", s.file().getFileName()));
        Runnable open = () -> ScreenshotViewer.show(ctx, all, index, x -> confirmTrash(p, x));
        tile.setOnMouseClicked(e -> open.run());
        tile.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                open.run();
                e.consume();
            }
        });
        return tile;
    }

    private void confirmTrash(Project p, Screenshot s) {
        ctx.overlay().confirm(I18n.tr("ui.shots.trashTitle"), I18n.tr("ui.shots.trashMessage", s.file().getFileName()), List.of(),
            I18n.tr("ui.shots.trash"), true, () -> Tasks.quiet(ctx.overlay(), () -> {
                ctx.services().screenshots().moveToTrash(p, s.file());
                return true;
            }, x -> refresh()));
    }
}

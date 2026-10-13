package dev.shardlight.client.app.ui;

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Project icons: a set of built-in glyph tiles (original artwork, no Minecraft textures) or a picture the
 * player chose ("file:icon.png" inside the project folder).
 */
public final class ProjectIcons {
    /** a built-in icon: id, glyph and tile colour */
    public record Builtin(String id, String glyph, String color) {
    }

    public static final List<Builtin> BUILTIN = List.of(
        new Builtin("grass", Icons.CUBE, "#3f9b5a"),
        new Builtin("stone", Icons.CUBE, "#7a808c"),
        new Builtin("shard", Icons.SHARD, "#8b7cf6"),
        new Builtin("bolt", Icons.BOLT, "#e0a23a"),
        new Builtin("world", Icons.WORLD, "#3d7fd1"),
        new Builtin("palette", Icons.PALETTE, "#d1588f"),
        new Builtin("star", Icons.STAR, "#d6b13c"),
        new Builtin("package", Icons.PACKAGE, "#c9733d"),
        new Builtin("gauge", Icons.GAUGE, "#2fa99b"),
        new Builtin("servers", Icons.SERVERS, "#5864d6"));

    private ProjectIcons() {
    }

    public static Optional<Builtin> builtin(String id) {
        return BUILTIN.stream().filter(b -> b.id().equals(id)).findFirst();
    }

    /**
     * A square tile for {@code icon} ("builtin:<id>" or "file:<name>"). {@code projectDir} resolves file
     * icons; a missing or unreadable picture falls back to the grass tile.
     */
    public static Node tile(String icon, Path projectDir, double size) {
        if (icon != null && icon.startsWith("file:") && projectDir != null) {
            Path file = projectDir.resolve(icon.substring(5)).normalize();
            if (file.startsWith(projectDir) && Files.isRegularFile(file)) {
                Image img = new Image(file.toUri().toString(), size, size, true, true, false);
                if (!img.isError()) {
                    ImageView v = new ImageView(img);
                    v.setFitWidth(size);
                    v.setFitHeight(size);
                    v.setPreserveRatio(true);
                    Rectangle clip = new Rectangle(size, size);
                    clip.setArcWidth(size * 0.3);
                    clip.setArcHeight(size * 0.3);
                    v.setClip(clip);
                    StackPane p = new StackPane(v);
                    p.setMinSize(size, size);
                    p.setPrefSize(size, size);
                    p.setMaxSize(size, size);
                    return p;
                }
            }
        }
        String id = icon != null && icon.startsWith("builtin:") ? icon.substring(8) : "grass";
        Builtin b = builtin(id).orElse(BUILTIN.get(0));
        return glyphTile(b, size);
    }

    public static Node glyphTile(Builtin b, double size) {
        Color c = Color.web(b.color());
        Node glyph = Icons.of(b.glyph(), size * 0.55);
        glyph.lookupAll(".icon").forEach(n -> n.setStyle("-sl-icon: rgba(255,255,255,0.95);"));
        StackPane p = new StackPane(glyph);
        p.setMinSize(size, size);
        p.setPrefSize(size, size);
        p.setMaxSize(size, size);
        p.setStyle("-fx-background-radius: " + (size * 0.22) + "; -fx-background-color: linear-gradient(to bottom right, "
            + web(c.brighter()) + ", " + web(c.darker()) + ");");
        return p;
    }

    private static String web(Color c) {
        return String.format("#%02x%02x%02x", (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
            (int) Math.round(c.getBlue() * 255));
    }
}

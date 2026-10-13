package dev.shardlight.client.app.ui;

import dev.shardlight.client.project.Project;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;

/**
 * Cover art for a project, used by the hero, project cards and the project page. A picture the player chose (the
 * project icon file) is used first, cropped to fill. Otherwise the project gets the cover of its template (see
 * {@link Art}); a project with a template that has no cover gets art generated from its name and the colour of
 * its template: the same project always looks the same and two projects almost never look alike.
 */
public final class ProjectArt extends Region {
    /** the look of one project's generated art */
    public record Palette(double hue, double hue2, long seed) {
    }

    /** template id -> hue (degrees); the template's own colour */
    private static final Map<String, Double> TEMPLATE_HUE = Map.of(
        "vanilla", 135.0, "shardlight-default", 255.0, "ultra-performance", 32.0, "balanced", 172.0,
        "creative-building", 205.0, "pvp", 352.0, "exploration", 218.0, "modded-survival", 95.0, "custom", 285.0);

    private final Palette palette;
    private final Canvas canvas = new Canvas();
    private final ImageView picture;
    private final Image image;
    private final double radiusEm;

    private ProjectArt(Palette palette, Image image, double radiusEm) {
        this.palette = palette;
        this.image = image;
        this.radiusEm = radiusEm;
        getStyleClass().add("project-art");
        setMinSize(0, 0);
        if (image != null) {
            picture = new ImageView(image);
            picture.setPreserveRatio(false);
            picture.setSmooth(true);
            getChildren().add(picture);
        } else {
            picture = null;
            getChildren().add(canvas);
        }
        setFocusTraversable(false);
        setMouseTransparent(true);
    }

    /** art of {@code width} x {@code height} (the preferred size; it grows to fill its parent). */
    public static ProjectArt of(Project p, Path projectDir, double width, double height, double radiusEm) {
        Image own = picture(p, projectDir);
        // a picture the player chose first, then the template's own cover; art is drawn from the name only for a template with no cover
        Image shown = own != null ? own : Art.cover(p.template, width <= 0 ? 1000 : width).orElse(null);
        ProjectArt art = new ProjectArt(palette(p), shown, radiusEm);
        art.setPrefSize(width, height);
        art.setMinHeight(height);
        art.setMaxWidth(Double.MAX_VALUE);
        return art;
    }

    /** the colours and shapes for a project: from its name and its template */
    public static Palette palette(Project p) {
        String name = p.name == null ? "" : p.name;
        long seed = name.toLowerCase(java.util.Locale.ROOT).hashCode() * 2654435761L + (p.template == null ? 0 : p.template.hashCode());
        Random r = new Random(seed);
        double base = TEMPLATE_HUE.getOrDefault(p.template == null ? "" : p.template, 200.0);
        double hue = (base + r.nextInt(41) - 20 + 360) % 360;
        double hue2 = (hue + 28 + r.nextInt(45)) % 360;
        return new Palette(hue, hue2, seed);
    }

    private static Image picture(Project p, Path projectDir) {
        if (p.icon != null && p.icon.startsWith("file:") && projectDir != null) {
            Path file = projectDir.resolve(p.icon.substring(5)).normalize();
            if (file.startsWith(projectDir) && Files.isRegularFile(file)) {
                Image img = new Image(file.toUri().toString(), 960, 0, true, true, false);
                return img.isError() ? null : img;
            }
        }
        return null;
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        Rectangle clip = new Rectangle(w, h);
        double arc = ThemeManager.em(radiusEm) * 2;
        clip.setArcWidth(arc);
        clip.setArcHeight(arc);
        setClip(clip);
        if (picture != null) {
            picture.setX(0);
            picture.setY(0);
            double iw = image.getWidth();
            double ih = image.getHeight();
            double scale = Math.max(w / iw, h / ih);
            double vw = w / scale;
            double vh = h / scale;
            picture.setViewport(new javafx.geometry.Rectangle2D((iw - vw) / 2, (ih - vh) / 2, vw, vh));
            picture.setFitWidth(w);
            picture.setFitHeight(h);
        } else {
            canvas.setWidth(w);
            canvas.setHeight(h);
            paint(canvas.getGraphicsContext2D(), w, h, palette);
        }
    }

    /** draw the generated art: a dusk gradient, a sun or moon, and three ridges of different depth */
    static void paint(GraphicsContext g, double w, double h, Palette pal) {
        Random r = new Random(pal.seed());
        g.clearRect(0, 0, w, h);
        g.setFill(new LinearGradient(0, 0, 0.25, 1, true, CycleMethod.NO_CYCLE,
            new Stop(0, Color.hsb(pal.hue2(), 0.55, 0.62)), new Stop(0.55, Color.hsb(pal.hue(), 0.6, 0.38)),
            new Stop(1, Color.hsb(pal.hue(), 0.7, 0.16))));
        g.fillRect(0, 0, w, h);
        double sunX = w * (0.2 + r.nextDouble() * 0.6);
        double sunR = Math.min(w, h) * (0.1 + r.nextDouble() * 0.08);
        g.setFill(Color.hsb(pal.hue2(), 0.25, 1.0, 0.85));
        g.fillOval(sunX - sunR, h * 0.18 - sunR * 0.4, sunR * 2, sunR * 2);
        g.setFill(Color.hsb(pal.hue2(), 0.25, 1.0, 0.18));
        g.fillOval(sunX - sunR * 1.8, h * 0.18 - sunR * 1.2, sunR * 3.6, sunR * 3.6);
        for (int layer = 0; layer < 3; layer++) {
            double top = h * (0.5 + layer * 0.16);
            double amp = h * (0.22 - layer * 0.05);
            int points = 6 + r.nextInt(4);
            double[] xs = new double[points + 2];
            double[] ys = new double[points + 2];
            for (int i = 0; i <= points; i++) {
                xs[i] = w * i / points;
                ys[i] = top - r.nextDouble() * amp;
            }
            xs[points + 1] = w;
            ys[points + 1] = h;
            double[] px = new double[points + 3];
            double[] py = new double[points + 3];
            System.arraycopy(xs, 0, px, 0, points + 2);
            System.arraycopy(ys, 0, py, 0, points + 2);
            px[points + 2] = 0;
            py[points + 2] = h;
            g.setFill(Color.hsb(pal.hue(), 0.6 + layer * 0.1, 0.30 - layer * 0.08, 0.92));
            g.fillPolygon(px, py, points + 3);
        }
        // a few stars so large areas of sky are not flat
        g.setFill(Color.hsb(pal.hue2(), 0.1, 1.0, 0.6));
        int stars = 8 + r.nextInt(8);
        for (int i = 0; i < stars; i++) {
            double s = 1 + r.nextInt(2);
            g.fillRect(r.nextDouble() * w, r.nextDouble() * h * 0.4, s, s);
        }
    }
}

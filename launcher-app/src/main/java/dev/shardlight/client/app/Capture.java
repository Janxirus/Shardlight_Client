package dev.shardlight.client.app;

import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** --capture: shows each page in turn and saves a PNG of the window. */
final class Capture {
    private static final Logger LOG = Logger.getLogger(Capture.class.getName());
    private final AppContext ctx;
    private final Scene scene;
    private final Path dir;
    /** accessibility finding -> the views it was seen in (see A11yAudit) */
    private final java.util.Map<String, List<String>> findings = new java.util.TreeMap<>();
    /** set when --capture ended with accessibility findings and shardlight.auditStrict is on; Main turns it into the exit code */
    static volatile boolean failed;

    Capture(AppContext ctx, Scene scene, Path dir) {
        this.ctx = ctx;
        this.scene = scene;
        this.dir = dir;
    }

    void run(Runnable done) {
        List<Runnable> steps = new ArrayList<>();
        for (String id : ctx.captureSteps()) {
            steps.add(() -> ctx.showForCapture(id));
            steps.add(() -> save(id));
        }
        next(steps, 0, done);
    }

    private void next(List<Runnable> steps, int i, Runnable done) {
        if (i >= steps.size()) {
            writeReport();
            done.run();
            return;
        }
        PauseTransition p = new PauseTransition(Duration.millis(i % 2 == 0 ? 50 : 700));
        p.setOnFinished(e -> {
            try {
                steps.get(i).run();
            } catch (RuntimeException ex) {
                LOG.warning("Capture step failed: " + ex);
            }
            next(steps, i + 1, done);
        });
        p.play();
    }

    /** the whole current page, not just the part on screen, on the window's background */
    private WritableImage fullPage() {
        javafx.scene.layout.Region root = (javafx.scene.layout.Region) scene.getRoot();
        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        if (root.getBackground() != null && !root.getBackground().getFills().isEmpty()) {
            params.setFill(root.getBackground().getFills().get(0).getFill());
        }
        return ctx.shell().current().view().snapshot(params, null);
    }

    private void audit(String step) {
        List<String> issues = new ArrayList<>(A11yAudit.scan(scene.getRoot()));
        issues.addAll(A11yAudit.overflow(scene));
        if (Boolean.getBoolean("shardlight.tabWalk")) {
            issues.addAll(A11yAudit.tabWalk(scene));
        }
        for (String issue : issues) {
            List<String> views = findings.computeIfAbsent(issue, k -> new ArrayList<>());
            if (views.size() < 3) {
                views.add(step);
            }
        }
    }

    private void writeReport() {
        StringBuilder sb = new StringBuilder("Accessibility findings: " + findings.size() + "\n\n");
        findings.forEach((issue, views) -> sb.append(issue).append("\n    seen in ").append(String.join(", ", views)).append('\n'));
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("accessibility-report.txt"), sb.toString());
        } catch (IOException e) {
            LOG.warning("Couldn't write the accessibility report: " + e.getMessage());
        }
        LOG.info("Accessibility findings: " + findings.size());
        if (!findings.isEmpty() && Boolean.getBoolean("shardlight.auditStrict")) {
            failed = true;
        }
    }

    private void save(String step) {
        audit(step);
        // a matrix step "m/<theme>/<scale>/<view>" is saved as <theme>-<scale>/<view>.png
        String name = step.startsWith("m/") ? step.split("/", 4)[1] + "-" + step.split("/", 4)[2] + "/" + step.split("/", 4)[3] : step;
        WritableImage img = name.startsWith("gallery-full") ? fullPage() : scene.snapshot(null);
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader r = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, r.getArgb(x, y));
            }
        }
        try {
            Path file = dir.resolve(name + ".png");
            Files.createDirectories(file.getParent());
            ImageIO.write(out, "png", file.toFile());
            LOG.info("Captured " + name);
        } catch (IOException e) {
            LOG.warning("Couldn't write capture " + name + ": " + e.getMessage());
        }
    }
}

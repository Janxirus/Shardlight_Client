package dev.shardlight.client.app;

import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.app.ui.Art;
import dev.shardlight.client.app.ui.Motion;
import dev.shardlight.client.app.ui.Shell;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.storage.AppPaths;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The JavaFX application: builds the services, the shell and the window. */
public final class ShardlightApp extends Application {
    private static final Logger LOG = Logger.getLogger(ShardlightApp.class.getName());
    private static AppPaths paths;
    private static Main.LaunchArgs launchArgs;
    private AppContext ctx;

    static void configure(AppPaths p, Main.LaunchArgs a) {
        paths = p;
        launchArgs = a;
    }

    @Override
    public void start(Stage stage) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> LOG.log(Level.SEVERE, "Uncaught error on " + t.getName(), e));
        boolean capturing = launchArgs != null && launchArgs.captureDir() != null;
        if (capturing || Boolean.getBoolean("shardlight.noSplash")) {
            open(stage);
            return;
        }
        // The splash is painted first; the window is built on the frame after, then the splash goes away.
        Stage splash = splash();
        splash.show();
        new AnimationTimer() {
            private int frames;

            @Override
            public void handle(long now) {
                if (++frames >= 2) {
                    stop();
                    try {
                        open(stage);
                    } finally {
                        splash.close();
                    }
                }
            }
        }.start();
    }

    /** a small borderless window with the splash picture; it carries no controls and takes no input */
    private static Stage splash() {
        ImageView picture = new ImageView(Art.splash());
        picture.setFitWidth(480);
        picture.setPreserveRatio(true);
        picture.setSmooth(true);
        StackPane box = new StackPane(picture);
        Stage s = new Stage(StageStyle.UNDECORATED);
        s.setScene(new Scene(box, 480, 280));
        s.setTitle(BuildInfo.NAME);
        s.centerOnScreen();
        return s;
    }

    private void open(Stage stage) {
        try {
            ctx = AppContext.create(paths == null ? AppPaths.defaults() : paths, stage, getHostServices());
            if (launchArgs != null && launchArgs.captureDir() != null) {
                CaptureSeed.seed(ctx);
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Startup failed", e);
            StartupError.show(stage, e);
            return;
        }
        Shell shell = ctx.shell();
        Scene scene = new Scene(shell.root(), 1280, 800);
        ThemeManager.install(scene);
        ThemeManager.apply(scene, ctx.services().settings().get().appearance);
        ctx.attach(scene);
        stage.setScene(scene);
        Motion.watchWindow(stage);
        stage.setTitle(BuildInfo.NAME);
        ctx.fitMinimumSize();
        for (int size : new int[] {16, 32, 48, 64, 128, 256}) {
            try (InputStream in = getClass().getResourceAsStream("/app/icons/shardlight-" + size + ".png")) {
                if (in != null) {
                    stage.getIcons().add(new Image(in));
                }
            } catch (java.io.IOException ignored) {
                // icons are cosmetic
            }
        }
        String first = launchArgs != null && launchArgs.page() != null ? launchArgs.page() : "home";
        shell.navigate(first);
        if (launchArgs != null && launchArgs.captureDir() != null) {
            stage.setWidth(launchArgs.captureWidth());
            stage.setHeight(launchArgs.captureHeight());
            stage.show();
            new Capture(ctx, scene, java.nio.file.Path.of(launchArgs.captureDir())).run(Platform::exit);
            return;
        }
        stage.show();
        ctx.afterShow();
        StartupMeasure.afterShow(stage);
    }

    @Override
    public void stop() {
        if (ctx != null) {
            ctx.close();
        }
    }
}

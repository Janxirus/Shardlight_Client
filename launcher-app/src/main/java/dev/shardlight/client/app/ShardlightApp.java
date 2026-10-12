package dev.shardlight.client.app;

import dev.shardlight.client.BuildInfo;
import dev.shardlight.client.app.ui.Shell;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.storage.AppPaths;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

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
        stage.setTitle(BuildInfo.NAME);
        stage.setMinWidth(960);
        stage.setMinHeight(640);
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
    }

    @Override
    public void stop() {
        if (ctx != null) {
            ctx.close();
        }
    }
}

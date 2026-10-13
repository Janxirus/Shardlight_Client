package dev.shardlight.client.app.ui;

import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Long work with a progress dialog, and turning failures into messages people can act on. */
public final class Tasks {
    private static final Logger LOG = Logger.getLogger(Tasks.class.getName());

    private Tasks() {
    }

    /** blocking work that reports progress and checks for cancellation */
    @FunctionalInterface
    public interface Work<T> {
        T run(ProgressListener progress, CancellationToken cancel) throws Exception;
    }

    private record Snapshot(String stageKey, String detail, long done, long total) {
    }

    /**
     * Run {@code work} in the background behind a modal progress dialog. {@code ok} runs on the FX thread
     * with the result; failures show an error dialog titled {@code title}; a cancel shows a short toast.
     */
    public static <T> void withProgress(Overlay overlay, String title, boolean cancellable, Work<T> work,
                                        Consumer<T> ok) {
        withProgress(overlay, title, cancellable, work, ok, null);
    }

    /**
     * As above; when {@code onError} is given it replaces the standard error dialog (it is not called for a
     * cancel, which always shows a short toast).
     */
    public static <T> void withProgress(Overlay overlay, String title, boolean cancellable, Work<T> work,
                                        Consumer<T> ok, Consumer<Throwable> onError) {
        CancellationToken cancel = new CancellationToken();
        String source = "task-" + System.identityHashCode(cancel);
        TaskProgress.set(source, -1);
        Label stage = UiKit.label(I18n.tr("ui.task.starting"), "strong");
        Label detail = UiKit.label("", "muted", "small");
        detail.setWrapText(false);
        detail.setMaxWidth(ThemeManager.scaled(460));
        ProgressBar bar = UiKit.progress(-1);
        Label count = UiKit.label("", "faint", "small");
        VBox body = new VBox(8, stage, bar, detail, count);
        body.setPrefWidth(ThemeManager.scaled(480));
        Button cancelButton = new Button(I18n.tr("common.cancel"));
        cancelButton.setCancelButton(true);
        cancelButton.setVisible(cancellable);
        cancelButton.setManaged(cancellable);
        Overlay.Dialog dialog = overlay.show(Overlay.modal(title, body, cancelButton), false, false);
        cancelButton.setOnAction(e -> {
            cancelButton.setDisable(true);
            cancelButton.setText(I18n.tr("ui.task.cancelling"));
            cancel.cancel();
        });

        AtomicReference<Snapshot> latest = new AtomicReference<>();
        AtomicBoolean scheduled = new AtomicBoolean();
        ProgressListener listener = (stageKey, d, done, total) -> {
            latest.set(new Snapshot(stageKey, d, done, total));
            if (scheduled.compareAndSet(false, true)) {
                Platform.runLater(() -> {
                    scheduled.set(false);
                    Snapshot s = latest.get();
                    if (s == null) {
                        return;
                    }
                    stage.setText(s.stageKey() == null ? "" : I18n.tr(s.stageKey()));
                    detail.setText(s.detail() == null ? "" : s.detail());
                    double f = ProgressListener.fraction(s.done(), s.total());
                    bar.setProgress(f);
                    TaskProgress.set(source, f);
                    count.setText(s.total() > 0 ? I18n.tr("ui.task.count", s.done(), s.total()) : "");
                });
            }
        };
        Fx.run(() -> work.run(listener, cancel), result -> {
            TaskProgress.clear(source);
            dialog.close();
            if (ok != null) {
                ok.accept(result);
            }
        }, err -> {
            TaskProgress.clear(source);
            dialog.close();
            if (isCancelled(err)) {
                overlay.toast("info", I18n.tr("ui.task.cancelled"));
            } else if (onError != null) {
                onError.accept(unwrap(err));
            } else {
                showError(overlay, title, err);
            }
        });
    }

    /** run quick background work without a dialog; errors become a toast */
    public static <T> void quiet(Overlay overlay, java.util.concurrent.Callable<T> work, Consumer<T> ok) {
        Fx.run(work, ok, err -> {
            if (!isCancelled(err)) {
                overlay.toast("danger", message(err));
            }
        });
    }

    public static boolean isCancelled(Throwable t) {
        Throwable c = unwrap(t);
        return c instanceof LauncherException le && le.isCancelled();
    }

    /** a message for people: the localized LauncherException text, or a generic one for bugs */
    public static String message(Throwable t) {
        Throwable c = unwrap(t);
        if (c instanceof LauncherException le) {
            return le.userMessage();
        }
        LOG.log(Level.WARNING, "Unexpected error shown to the user", c);
        return I18n.tr("ui.error.unexpected", c.getClass().getSimpleName());
    }

    public static void showError(Overlay overlay, String title, Throwable t) {
        overlay.message(title, message(t));
    }

    public static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while ((c instanceof java.util.concurrent.CompletionException
            || c instanceof java.util.concurrent.ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }
}

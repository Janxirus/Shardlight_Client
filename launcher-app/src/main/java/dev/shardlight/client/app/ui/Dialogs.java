package dev.shardlight.client.app.ui;

import dev.shardlight.client.i18n.I18n;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Small reusable dialogs: ask for a name, pick options, choose files. */
public final class Dialogs {
    private Dialogs() {
    }

    /**
     * Ask for one line of text. {@code validate} returns an error message for bad input, or null when it's
     * fine; {@code ok} gets the trimmed text.
     */
    public static void text(Overlay overlay, String title, String label, String initial, String okText,
                            Function<String, String> validate, Consumer<String> ok) {
        TextField field = new TextField(initial == null ? "" : initial);
        field.setAccessibleText(label);
        Label error = UiKit.label("", "danger-text", "small");
        VBox body = new VBox(8, UiKit.label(label, "small"), field, error);
        body.setPrefWidth(440);
        Button cancel = new Button(I18n.tr("common.cancel"));
        cancel.setCancelButton(true);
        Button confirm = UiKit.primary(okText, null, () -> { });
        confirm.setDefaultButton(true);
        Overlay.Dialog d = overlay.show(Overlay.modal(title, body, cancel, confirm), true, false);
        cancel.setOnAction(e -> d.close());
        confirm.setOnAction(e -> {
            String v = field.getText() == null ? "" : field.getText().trim();
            String problem = validate == null ? null : validate.apply(v);
            if (problem != null) {
                error.setText(problem);
                return;
            }
            d.close();
            ok.accept(v);
        });
        field.requestFocus();
        field.selectAll();
    }

    /** one tick box in {@link #options} */
    public record Option(String label, String description, boolean initial) {
    }

    /** a list of tick boxes; {@code ok} gets the chosen values in the same order */
    public static void options(Overlay overlay, String title, String intro, List<Option> options, String okText,
                               boolean danger, Consumer<List<Boolean>> ok) {
        VBox body = new VBox(10);
        body.setPrefWidth(480);
        if (intro != null && !intro.isBlank()) {
            body.getChildren().add(UiKit.label(intro, "muted"));
        }
        List<CheckBox> boxes = new ArrayList<>();
        for (Option o : options) {
            CheckBox c = new CheckBox(o.label());
            c.setSelected(o.initial());
            c.setWrapText(true);
            boxes.add(c);
            body.getChildren().add(c);
            if (o.description() != null && !o.description().isBlank()) {
                Label l = UiKit.label(o.description(), "faint", "small");
                l.setPadding(new javafx.geometry.Insets(-6, 0, 0, 28));
                body.getChildren().add(l);
            }
        }
        Button cancel = new Button(I18n.tr("common.cancel"));
        cancel.setCancelButton(true);
        Button confirm = new Button(okText);
        confirm.getStyleClass().add(danger ? "danger" : "primary");
        Overlay.Dialog d = overlay.show(Overlay.modal(title, body, cancel, confirm), true, false);
        cancel.setOnAction(e -> d.close());
        confirm.setOnAction(e -> {
            d.close();
            List<Boolean> values = new ArrayList<>();
            boxes.forEach(b -> values.add(b.isSelected()));
            ok.accept(values);
        });
    }

    /** an open-file dialog; {@code extensions} like "*.zip" */
    public static Path openFile(Window owner, String title, String filterName, String... extensions) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        if (extensions.length > 0) {
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(filterName, extensions));
        }
        File f = fc.showOpenDialog(owner);
        return f == null ? null : f.toPath();
    }

    public static List<Path> openFiles(Window owner, String title, String filterName, String... extensions) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        if (extensions.length > 0) {
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(filterName, extensions));
        }
        List<File> files = fc.showOpenMultipleDialog(owner);
        List<Path> out = new ArrayList<>();
        if (files != null) {
            files.forEach(f -> out.add(f.toPath()));
        }
        return out;
    }

    public static Path saveFile(Window owner, String title, String initialName, String filterName, String extension) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.setInitialFileName(initialName);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(filterName, extension));
        File f = fc.showSaveDialog(owner);
        return f == null ? null : f.toPath();
    }
}

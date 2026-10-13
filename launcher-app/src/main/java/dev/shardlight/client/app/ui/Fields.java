package dev.shardlight.client.app.ui;

import dev.shardlight.client.i18n.I18n;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.DoubleFunction;

/** Input components: search field, number field with steppers, slider with a value bubble, labelled field. */
public final class Fields {
    /**
     * A text area normally keeps Tab for itself (it types a tab character), so the keyboard can't leave it. This makes
     * Tab and Shift+Tab move to the next and previous control, like in every other field; Ctrl+Tab still works too.
     */
    public static javafx.scene.control.TextArea tabLeaves(javafx.scene.control.TextArea area) {
        area.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.TAB && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
                e.consume();
                javafx.event.Event.fireEvent(area, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.TAB, e.isShiftDown(), true, false, false));
            }
        });
        return area;
    }

    private Fields() {
    }

    /**
     * A pill search field with a magnifier and a clear button. Escape clears it; Ctrl+F focuses the search field
     * of the page on screen (the shell looks for {@code .search-box}).
     */
    public static final class SearchField extends HBox {
        private final TextField field = new TextField();

        public SearchField(String prompt) {
            getStyleClass().add("search-box");
            setAlignment(Pos.CENTER_LEFT);
            field.getStyleClass().add("search-input");
            field.setPromptText(prompt);
            field.setAccessibleText(prompt);
            HBox.setHgrow(field, Priority.ALWAYS);
            Button clear = UiKit.iconButton(Icons.CLOSE, I18n.tr("ui.search.clear"), () -> {
                field.clear();
                field.requestFocus();
            });
            clear.getStyleClass().addAll("small", "search-clear");
            clear.visibleProperty().bind(field.textProperty().isNotEmpty());
            clear.managedProperty().bind(clear.visibleProperty());
            field.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE && !field.getText().isEmpty()) {
                    field.clear();
                    e.consume();
                }
            });
            Node icon = Icons.of(Icons.SEARCH, 16);
            icon.getStyleClass().add("faint");
            getChildren().addAll(icon, field, clear);
        }

        public TextField field() {
            return field;
        }

        public StringProperty textProperty() {
            return field.textProperty();
        }

        public String getText() {
            return field.getText();
        }

        public void focusField() {
            field.requestFocus();
            field.selectAll();
        }
    }

    /** a whole-number field with - and + steppers; arrow keys step, Page Up / Page Down step ten times */
    public static final class NumberField extends HBox {
        private final TextField field = new TextField();
        private final IntegerProperty value = new SimpleIntegerProperty();
        private final ReadOnlyStringWrapper error = new ReadOnlyStringWrapper();
        private final int min;
        private final int max;
        private final int step;

        public NumberField(int min, int max, int step, int initial, String accessibleName) {
            this.min = min;
            this.max = max;
            this.step = step;
            getStyleClass().add("number-field");
            setAlignment(Pos.CENTER_LEFT);
            field.getStyleClass().addAll("number-input", "tabular");
            field.setAccessibleText(accessibleName);
            field.setPrefColumnCount(Math.max(3, String.valueOf(max).length()));
            Button down = UiKit.iconButton(Icons.MINUS, I18n.tr("ui.number.decrease", accessibleName), () -> stepBy(-1));
            Button up = UiKit.iconButton(Icons.PLUS, I18n.tr("ui.number.increase", accessibleName), () -> stepBy(1));
            down.getStyleClass().add("stepper");
            up.getStyleClass().add("stepper");
            down.setFocusTraversable(false);
            up.setFocusTraversable(false);
            getChildren().addAll(down, field, up);
            value.set(clamp(initial));
            field.setText(String.valueOf(value.get()));
            value.addListener((o, a, b) -> {
                if (!String.valueOf(b).equals(field.getText().trim())) {
                    field.setText(String.valueOf(b));
                }
            });
            field.textProperty().addListener((o, a, b) -> parse(b));
            field.setOnKeyPressed(e -> {
                int n = switch (e.getCode()) {
                    case UP -> 1;
                    case DOWN -> -1;
                    case PAGE_UP -> 10;
                    case PAGE_DOWN -> -10;
                    default -> 0;
                };
                if (n != 0) {
                    stepBy(n);
                    e.consume();
                }
            });
            field.focusedProperty().addListener((o, a, focused) -> {
                if (!focused && error.get() == null) {
                    field.setText(String.valueOf(value.get()));
                }
            });
        }

        private void stepBy(int n) {
            value.set(clamp(value.get() + n * step));
            field.setText(String.valueOf(value.get()));
        }

        private int clamp(int v) {
            return Math.max(min, Math.min(max, v));
        }

        private void parse(String text) {
            String t = text == null ? "" : text.trim();
            try {
                int v = Integer.parseInt(t);
                if (v < min || v > max) {
                    setError(I18n.tr("ui.number.range", min, max));
                } else {
                    setError(null);
                    value.set(v);
                }
            } catch (NumberFormatException e) {
                setError(I18n.tr("ui.number.notNumber"));
            }
        }

        private void setError(String e) {
            error.set(e);
            field.getStyleClass().remove("error");
            if (e != null) {
                field.getStyleClass().add("error");
            }
        }

        public IntegerProperty valueProperty() {
            return value;
        }

        /** the message to show under the field while the typed text isn't a valid number, else null */
        public ReadOnlyStringProperty errorProperty() {
            return error.getReadOnlyProperty();
        }
    }

    /** a slider that shows its value in a bubble over the thumb while it is hovered, dragged or focused */
    public static final class ValueSlider extends StackPane {
        private final Slider slider;
        private final Label bubble = new Label();

        public ValueSlider(double min, double max, double value, DoubleFunction<String> format, String accessibleName) {
            slider = new Slider(min, max, value);
            slider.setAccessibleText(accessibleName);
            getStyleClass().add("value-slider");
            bubble.getStyleClass().addAll("value-bubble", "tabular", "elevation-1");
            bubble.setManaged(false);
            bubble.setMouseTransparent(true);
            bubble.setOpacity(0);
            getChildren().addAll(slider, bubble);
            setAlignment(Pos.BOTTOM_LEFT);
            Runnable text = () -> bubble.setText(format.apply(slider.getValue()));
            slider.valueProperty().addListener((o, a, b) -> {
                text.run();
                requestLayout();
            });
            text.run();
            Runnable show = () -> {
                boolean on = slider.isHover() || slider.isPressed() || slider.isFocused();
                if (on && bubble.getOpacity() < 1) {
                    Motion.fadeIn(bubble, Motion.Speed.FAST);
                } else if (!on && bubble.getOpacity() > 0) {
                    Motion.exit(bubble, Motion.Speed.FAST, null);
                }
            };
            slider.hoverProperty().addListener((o, a, b) -> show.run());
            slider.pressedProperty().addListener((o, a, b) -> show.run());
            slider.focusedProperty().addListener((o, a, b) -> show.run());
        }

        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            Node thumb = slider.lookup(".thumb");
            bubble.autosize();
            if (thumb != null) {
                var b = thumb.getBoundsInParent();
                double x = slider.getLayoutX() + b.getMinX() + b.getWidth() / 2 - bubble.getWidth() / 2;
                double y = slider.getLayoutY() + b.getMinY() - bubble.getHeight() - ThemeManager.em(0.286);
                bubble.relocate(x, y);
            }
        }

        public Slider slider() {
            return slider;
        }
    }

    /**
     * A labelled form field: label, control, an optional help line, and an inline error that replaces the help
     * line (and marks the control) while there is one.
     */
    public static final class Field extends VBox {
        private final Node control;
        private final Label help;
        private final Label error = new Label();

        public Field(String label, Node control, String helpText) {
            this.control = control;
            getStyleClass().add("field");
            Label l = UiKit.label(label, "field-label");
            l.setLabelFor(control);
            help = UiKit.label(helpText == null ? "" : helpText, "field-help", "faint", "small");
            help.setVisible(helpText != null);
            help.setManaged(helpText != null);
            error.getStyleClass().addAll("field-error", "small");
            error.setGraphic(Icons.of(Icons.ERROR, 13));
            error.setWrapText(true);
            error.setMinHeight(Region.USE_PREF_SIZE);
            error.setVisible(false);
            error.setManaged(false);
            getChildren().addAll(l, control, help, error);
        }

        /** follow an error message (null = valid), e.g. {@link NumberField#errorProperty()} */
        public Field bindError(ObservableValue<String> source) {
            source.addListener((o, a, b) -> setError(b));
            setError(source.getValue());
            return this;
        }

        public void setError(String message) {
            boolean on = message != null && !message.isBlank();
            error.setText(on ? message : "");
            error.setVisible(on);
            error.setManaged(on);
            help.setVisible(!on && !help.getText().isEmpty());
            help.setManaged(help.isVisible());
            Node target = control instanceof NumberField nf ? nf.getChildren().get(1) : control;
            target.getStyleClass().remove("error");
            if (on) {
                target.getStyleClass().add("error");
                Motion.enter(error, Motion.Speed.FAST, -Motion.ENTER_OFFSET / 2);
            }
            if (target instanceof javafx.scene.control.Control c) {
                c.setAccessibleHelp(on ? message : null);
            }
        }
    }
}

package dev.shardlight.client.app.ui;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Lists and tables. Both are virtualised (only visible rows exist), select several rows with Shift and Ctrl,
 * highlight the row under the pointer, and show row actions only on the hovered or selected row. Tables sort
 * by clicking a column header and keep their header row in place while scrolling; lists can keep the current
 * group's header pinned to the top with {@link #stickyHeader}.
 */
public final class Lists {
    private Lists() {
    }

    /**
     * Set up {@code list} with multi-select and rows drawn by {@code render}, with {@code actions} (may be null)
     * appearing on the right of the hovered or selected row.
     */
    public static <T> ListView<T> rows(ListView<T> list, Function<T, Node> render, Function<T, List<Button>> actions) {
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.getStyleClass().add("data-list");
        list.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Node body = render.apply(item);
                HBox.setHgrow(body, Priority.ALWAYS);
                HBox row = new HBox(body);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("data-row");
                if (actions != null) {
                    HBox a = new HBox();
                    a.getStyleClass().add("row-actions");
                    a.getChildren().addAll(actions.apply(item));
                    row.getChildren().add(a);
                }
                setGraphic(row);
            }
        });
        return list;
    }

    /**
     * Wrap {@code list} so the group of the first visible row ({@code groupOf}) stays pinned at the top while the
     * list scrolls. Rows should already be sorted by group.
     */
    public static <T> StackPane stickyHeader(ListView<T> list, Function<T, String> groupOf) {
        Label header = new Label();
        header.getStyleClass().add("sticky-header");
        header.setMaxWidth(Double.MAX_VALUE);
        header.setMouseTransparent(true);
        StackPane box = new StackPane(list, header);
        StackPane.setAlignment(header, Pos.TOP_LEFT);
        box.getStyleClass().add("sticky-list");
        Runnable update = () -> {
            if (list.lookup(".virtual-flow") instanceof VirtualFlow<?> flow && flow.getFirstVisibleCell() != null) {
                int i = flow.getFirstVisibleCell().getIndex();
                if (i >= 0 && i < list.getItems().size()) {
                    header.setText(groupOf.apply(list.getItems().get(i)));
                }
            } else if (!list.getItems().isEmpty()) {
                header.setText(groupOf.apply(list.getItems().get(0)));
            }
            header.setVisible(!list.getItems().isEmpty());
        };
        list.skinProperty().addListener((o, a, b) -> {
            if (list.lookup(".virtual-flow") instanceof VirtualFlow<?> flow) {
                flow.positionProperty().addListener((x, y, z) -> update.run());
            }
            update.run();
        });
        list.getItems().addListener((javafx.collections.ListChangeListener<T>) c -> update.run());
        update.run();
        return box;
    }

    /** set up {@code table} with multi-select, sorting and a placeholder */
    public static <T> TableView<T> table(TableView<T> table, Node placeholder) {
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getStyleClass().add("data-table");
        if (placeholder != null) {
            table.setPlaceholder(placeholder);
        }
        return table;
    }

    /** a sortable text column */
    public static <T> TableColumn<T, String> column(String title, Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(value.apply(d.getValue())));
        c.setComparator(Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
        return c;
    }

    /** a sortable number column, right-aligned with tabular figures, shown through {@code format} */
    public static <T> TableColumn<T, Number> numberColumn(String title, Function<T, Number> value,
                                                          Function<Number, String> format) {
        TableColumn<T, Number> c = new TableColumn<>(title);
        c.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(value.apply(d.getValue())));
        c.setComparator(Comparator.comparingDouble(Number::doubleValue));
        c.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : format.apply(item));
            }
        });
        c.getStyleClass().add("numeric");
        return c;
    }

    /** a column of buttons that show on the hovered or selected row */
    public static <T> TableColumn<T, T> actionsColumn(Function<T, List<Button>> actions) {
        TableColumn<T, T> c = new TableColumn<>("");
        c.setSortable(false);
        c.setReorderable(false);
        c.getStyleClass().add("actions-column");
        c.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        c.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                HBox a = new HBox();
                a.getStyleClass().add("row-actions");
                a.setAlignment(Pos.CENTER_RIGHT);
                a.getChildren().addAll(actions.apply(item));
                setGraphic(a);
            }
        });
        return c;
    }
}

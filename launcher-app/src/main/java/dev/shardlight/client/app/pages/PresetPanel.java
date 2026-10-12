package dev.shardlight.client.app.pages;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.ProjectText;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ToggleSwitch;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.preset.ApplyReport;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.preset.SettingChange;
import dev.shardlight.client.preset.SettingDef;
import dev.shardlight.client.project.Project;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Performance presets for one project: the one-click Ultra Performance button, the six preset cards,
 * exactly what the chosen preset would change (current and new value of every setting), the Custom
 * editor, and the opt-ins. No FPS numbers are shown because none have been measured.
 */
public final class PresetPanel {
    private final AppContext ctx;
    private final String projectId;
    private final VBox root = new VBox(16);
    private Preset previewing;

    public PresetPanel(AppContext ctx, String projectId) {
        this.ctx = ctx;
        this.projectId = projectId;
    }

    public VBox view() {
        return root;
    }

    public void refresh() {
        root.getChildren().clear();
        var found = ctx.services().projects().get(projectId);
        if (found.isEmpty()) {
            return;
        }
        Project p = found.get();
        Preset active = p.preset == null || p.preset.active == null ? Preset.DEFAULT : p.preset.active;
        if (previewing == null) {
            previewing = active;
        }

        Button ultra = new Button(I18n.tr(active == Preset.ULTRA_PERFORMANCE ? "ui.preset.ultraActive" : "ui.preset.ultraOneClick"));
        ultra.setGraphic(Icons.of(Icons.BOLT, 18));
        ultra.getStyleClass().add("ultra-button");
        if (active == Preset.ULTRA_PERFORMANCE) {
            ultra.getStyleClass().add("active");
        }
        ultra.setAccessibleText(I18n.tr("ui.preset.ultraAccessible", p.name));
        ultra.setOnAction(e -> apply(p, Preset.ULTRA_PERFORMANCE));
        Label ultraHint = UiKit.label(I18n.tr("ui.preset.ultraHint"), "muted", "small");
        HBox.setHgrow(ultraHint, Priority.ALWAYS);
        HBox top = new HBox(14, ultra, ultraHint);
        top.setAlignment(Pos.CENTER_LEFT);

        FlowPane cards = new FlowPane(10, 10);
        for (Preset preset : Preset.values()) {
            VBox card = new VBox(4, UiKit.label(I18n.tr(preset.nameKey()), "preset-name"),
                UiKit.label(I18n.tr(preset.descriptionKey()), "muted", "small"));
            FlowPane chips = new FlowPane(4, 4);
            if (preset == Preset.DEFAULT) {
                chips.getChildren().add(UiKit.chip(I18n.tr("ui.preset.default"), null));
            }
            if (preset == active) {
                chips.getChildren().add(UiKit.chip(I18n.tr("ui.preset.active"), "accent"));
            }
            if (preset == Preset.ULTRA_QUALITY) {
                chips.getChildren().add(UiKit.chip(I18n.tr("ui.preset.heavy"), "warning"));
            }
            card.getChildren().add(chips);
            card.getStyleClass().add("preset-card");
            if (preset == previewing) {
                card.getStyleClass().add("selected");
            }
            card.setFocusTraversable(true);
            card.setAccessibleText(I18n.tr(preset.nameKey()) + ". " + I18n.tr(preset.descriptionKey()));
            card.setOnMouseClicked(e -> {
                previewing = preset;
                refresh();
            });
            card.setOnKeyPressed(e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                    previewing = preset;
                    refresh();
                }
            });
            cards.getChildren().add(card);
        }

        root.getChildren().addAll(top, cards);
        if (!p.hasShardlight() && !p.preset.applyVanillaVideoSettings) {
            root.getChildren().add(UiKit.banner("info", I18n.tr("ui.preset.nothingToTune"),
                UiKit.button(I18n.tr("ui.preset.addModules"), Icons.SHARD, () -> ctx.shell().navigate("shardlight"))));
        }
        root.getChildren().add(whatChanges(p, previewing, active));
        if (previewing == Preset.CUSTOM) {
            root.getChildren().add(customEditor(p));
        }
        root.getChildren().add(options(p));
        root.getChildren().add(measurement(p));
    }

    private Node whatChanges(Project p, Preset target, Preset active) {
        VBox box = new VBox(10);
        box.getStyleClass().add("card");
        Label title = UiKit.label(I18n.tr("ui.preset.whatChanges", I18n.tr(target.nameKey())), "h3");
        Button applyButton = UiKit.primary(I18n.tr("ui.preset.apply", I18n.tr(target.nameKey())), Icons.CHECK,
            () -> apply(p, target));
        HBox head = new HBox(10, title, UiKit.spacer(), applyButton);
        head.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(head);
        if (target == Preset.ULTRA_QUALITY) {
            box.getChildren().add(UiKit.banner("warning", I18n.tr("ui.preset.ultraQualityWarning")));
        }
        List<SettingChange> changes;
        try {
            changes = ctx.services().presets().preview(p, target);
        } catch (Exception e) {
            box.getChildren().add(UiKit.banner("danger", Tasks.message(e)));
            return box;
        }
        if (changes.isEmpty()) {
            box.getChildren().add(UiKit.label(I18n.tr(target == active ? "ui.preset.alreadyApplied" : "ui.preset.noChanges"),
                "muted"));
            return box;
        }
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(6);
        ColumnConstraints grow = new ColumnConstraints();
        grow.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(grow, new ColumnConstraints(), new ColumnConstraints(), new ColumnConstraints(),
            new ColumnConstraints());
        String[] headers = {"ui.preset.col.setting", "ui.preset.col.module", "ui.preset.col.current", "ui.preset.col.new",
            "ui.preset.col.cost"};
        for (int i = 0; i < headers.length; i++) {
            grid.add(UiKit.label(I18n.tr(headers[i]), "faint", "small"), i, 0);
        }
        int row = 1;
        for (SettingChange c : changes) {
            SettingDef d = c.def();
            Label name = UiKit.label(I18n.tr(d.labelKey()));
            UiKit.tooltip(name, I18n.tr(d.descriptionKey()));
            grid.add(name, 0, row);
            grid.add(UiKit.label(I18n.tr("ui.preset.target." + d.target().name().toLowerCase(Locale.ROOT)), "muted", "small"), 1, row);
            grid.add(UiKit.label(value(c.current()), "muted"), 2, row);
            Label proposed = UiKit.label(value(c.proposed()));
            proposed.setStyle("-fx-font-weight: bold;");
            grid.add(proposed, 3, row);
            grid.add(UiKit.chip(I18n.tr("ui.preset.cost." + d.cost().name().toLowerCase(Locale.ROOT)),
                switch (d.cost().name()) {
                    case "HIGH" -> "danger";
                    case "MEDIUM" -> "warning";
                    default -> null;
                }), 4, row);
            row++;
        }
        box.getChildren().addAll(grid, UiKit.label(I18n.tr("ui.preset.onlyTheseChange", changes.size()), "faint", "small"));
        return box;
    }

    private static String value(Object v) {
        if (v == null) {
            return I18n.tr("ui.preset.notSet");
        }
        if (v instanceof Boolean b) {
            return I18n.tr(b ? "ui.preset.on" : "ui.preset.off");
        }
        if (v instanceof Double d && d == Math.rint(d)) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(v);
    }

    private Node customEditor(Project p) {
        VBox box = new VBox(6);
        box.getStyleClass().add("card");
        box.getChildren().addAll(UiKit.label(I18n.tr("ui.preset.customTitle"), "h3"),
            UiKit.label(I18n.tr("ui.preset.customHint"), "muted", "small"));
        Map<String, Object> custom = p.preset.customOverrides;
        Map<String, Object> balanced = ctx.services().presets().values(Preset.BALANCED);
        for (SettingDef d : ctx.services().presets().settings()) {
            if (!applies(p, d)) {
                continue;
            }
            Object current = custom.containsKey(d.key()) ? custom.get(d.key()) : balanced.get(d.key());
            Node control = control(p, d, current);
            HBox row = UiKit.settingRow(I18n.tr(d.labelKey()), I18n.tr(d.descriptionKey()), control);
            if (custom.containsKey(d.key())) {
                row.getChildren().add(UiKit.iconButton(Icons.REFRESH, I18n.tr("ui.preset.resetOne"), () -> run(p,
                    () -> ctx.services().presets().clearCustomOverride(p, d.key()))));
            }
            box.getChildren().add(row);
        }
        return box;
    }

    private boolean applies(Project p, SettingDef d) {
        return switch (d.target().name()) {
            case "UI" -> p.shardlightModules.contains("ui");
            case "MAP" -> p.shardlightModules.contains("map");
            case "PARTY" -> p.shardlightModules.contains("party");
            case "COMPANION" -> p.shardlightModules.contains("companion");
            default -> p.preset.applyVanillaVideoSettings;
        };
    }

    private Node control(Project p, SettingDef d, Object current) {
        switch (d.kind().name()) {
            case "BOOLEAN" -> {
                ToggleSwitch t = new ToggleSwitch(Boolean.TRUE.equals(current));
                t.selectedProperty().addListener((o, a, b) -> set(p, d, b));
                return t;
            }
            case "INT" -> {
                int v = current instanceof Number n ? n.intValue() : 0;
                Spinner<Integer> s = new Spinner<>(Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2, v);
                s.setEditable(true);
                s.setPrefWidth(110);
                s.valueProperty().addListener((o, a, b) -> {
                    if (b != null && !Objects.equals(a, b)) {
                        set(p, d, b);
                    }
                });
                return s;
            }
            case "DOUBLE" -> {
                double v = current instanceof Number n ? n.doubleValue() : 0;
                Spinner<Double> s = new Spinner<>(-1e9, 1e9, v, 0.05);
                s.setEditable(true);
                s.setPrefWidth(110);
                s.valueProperty().addListener((o, a, b) -> {
                    if (b != null && !Objects.equals(a, b)) {
                        set(p, d, b);
                    }
                });
                return s;
            }
            default -> {
                TextField f = new TextField(current == null ? "" : String.valueOf(current));
                f.setPrefWidth(160);
                f.setOnAction(e -> set(p, d, f.getText()));
                f.focusedProperty().addListener((o, a, b) -> {
                    if (!b) {
                        set(p, d, f.getText());
                    }
                });
                return f;
            }
        }
    }

    private void set(Project p, SettingDef d, Object value) {
        run(p, () -> ctx.services().presets().setCustomOverride(p, d.key(), value));
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private void run(Project p, Step step) {
        Tasks.quiet(ctx.overlay(), () -> {
            step.run();
            return Boolean.TRUE;
        }, ok -> {
            previewing = Preset.CUSTOM;
            ctx.projectsChanged();
            refresh();
        });
    }

    private Node options(Project p) {
        VBox box = new VBox(4);
        box.getStyleClass().add("card");
        box.getChildren().add(UiKit.label(I18n.tr("ui.preset.options"), "h3"));
        ToggleSwitch vanilla = new ToggleSwitch(p.preset.applyVanillaVideoSettings);
        vanilla.selectedProperty().addListener((o, a, b) -> Tasks.quiet(ctx.overlay(),
            () -> ctx.services().projects().update(p.id, x -> x.preset.applyVanillaVideoSettings = b), x -> refresh()));
        box.getChildren().add(UiKit.settingRow(I18n.tr("ui.preset.vanillaOptIn"), I18n.tr("ui.preset.vanillaOptInHint"), vanilla));
        ToggleSwitch adaptive = new ToggleSwitch(false);
        adaptive.setDisable(true);
        box.getChildren().add(UiKit.settingRow(I18n.tr("ui.preset.adaptive"), I18n.tr("ui.preset.adaptiveHint"), adaptive));
        if (p.preset.lastApplied != null) {
            Button revert = UiKit.button(I18n.tr("ui.preset.revert"), Icons.REFRESH, () -> Tasks.quiet(ctx.overlay(),
                () -> ctx.services().presets().revertLast(ctx.services().projects().get(p.id).orElse(p)), r -> {
                    ctx.overlay().toast("success", I18n.tr("ui.preset.reverted", r.applied().size()));
                    previewing = null;
                    ctx.projectsChanged();
                    refresh();
                }));
            box.getChildren().add(UiKit.settingRow(I18n.tr("ui.preset.lastApplied", I18n.tr(p.preset.lastApplied.nameKey()),
                ProjectText.ago(p.preset.lastAppliedAt)), I18n.tr("ui.preset.revertHint"), revert));
        }
        return box;
    }

    private Node measurement(Project p) {
        VBox box = new VBox(6, UiKit.label(I18n.tr("ui.preset.measuredTitle"), "h3"),
            UiKit.label(I18n.tr("ui.preset.measuredNone"), "muted"),
            UiKit.label(I18n.tr("ui.preset.measuredHow"), "faint", "small"));
        box.getStyleClass().addAll("card", "flat");
        box.setPadding(new Insets(14));
        return box;
    }

    private void apply(Project p, Preset target) {
        Tasks.withProgress(ctx.overlay(), I18n.tr("ui.preset.applying", I18n.tr(target.nameKey())), false,
            (pr, c) -> ctx.services().setup().applyPreset(p, target), (ApplyReport r) -> {
                previewing = target;
                ctx.projectsChanged();
                refresh();
                String msg = r.applied().isEmpty() ? I18n.tr("ui.preset.appliedNothing", I18n.tr(target.nameKey()))
                    : I18n.tr("ui.preset.applied", I18n.tr(target.nameKey()), r.applied().size());
                ctx.overlay().toast("success", msg);
                for (String w : r.warnings()) {
                    ctx.overlay().toast("warning", w);
                }
            });
    }
}

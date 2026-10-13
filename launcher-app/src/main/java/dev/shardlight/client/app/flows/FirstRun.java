package dev.shardlight.client.app.flows;

import dev.shardlight.client.account.Account;
import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Cards;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Motion;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.PlayerHead;
import dev.shardlight.client.app.ui.StepProgress;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.loader.LoaderVersion;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.project.NewProjectRequest;
import dev.shardlight.client.project.ProjectTemplate;
import dev.shardlight.client.project.ProjectTemplates;
import dev.shardlight.client.task.CancellationToken;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The welcome shown the first time the launcher opens with no projects: sign in, pick a starter template, choose
 * Balanced or Ultra Performance. Every step can be skipped; the last button creates the project and opens it with
 * Play ready.
 */
public final class FirstRun {
    /** the starters offered, in order (Balanced and Ultra Performance are the last step's choice instead) */
    static final List<String> STARTERS = List.of("vanilla", "shardlight-default", "exploration", "creative-building", "pvp",
        "modded-survival");
    private static final Map<String, String> ICONS = Map.of("vanilla", Icons.CUBE, "shardlight-default", Icons.SHARD,
        "exploration", Icons.WORLD, "creative-building", Icons.PALETTE, "pvp", Icons.BOLT, "modded-survival", Icons.PACKAGE);

    private final AppContext ctx;
    private final boolean preview;
    private final VBox body = new VBox();
    private final HBox actions;
    private final VBox modal;
    private Overlay.Dialog dialog;
    private int step;
    private String template = "vanilla";
    private Preset preset = Preset.BALANCED;
    private StepProgress progress;

    private FirstRun(AppContext ctx, boolean preview, int step) {
        this.ctx = ctx;
        this.preview = preview;
        this.step = step;
        body.getStyleClass().add("firstrun-body");
        modal = Overlay.modal(I18n.tr("ui.firstRun.title"), body, new Button());
        modal.getStyleClass().add("firstrun");
        actions = (HBox) modal.getChildren().get(2);
    }

    /** show the welcome (and remember that it was shown) */
    public static void show(AppContext ctx) {
        new FirstRun(ctx, false, 0).open();
    }

    /** --capture only: the welcome on {@code step} (0 to 2), changing nothing */
    public static void preview(AppContext ctx, int step) {
        new FirstRun(ctx, true, step).open();
    }

    /** whether the welcome is due: never shown before and nothing to come back to */
    public static boolean due(AppContext ctx) {
        return !ctx.services().settings().get().firstRunDone && ctx.services().projects().list().isEmpty();
    }

    /** an existing library means the welcome is not needed; remember that */
    public static void markDone(AppContext ctx) {
        try {
            ctx.services().settings().update(s -> s.firstRunDone = true);
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(FirstRun.class.getName()).fine("Couldn't save the first-run flag: " + e);
        }
    }

    private void open() {
        progress = new StepProgress(List.of(I18n.tr("ui.firstRun.step.signIn"), I18n.tr("ui.firstRun.step.starter"),
            I18n.tr("ui.firstRun.step.performance")), step);
        modal.getChildren().add(1, progress);
        dialog = ctx.overlay().show(modal, false, true);
        dialog.onClose(() -> {
            if (!preview) {
                markDone(ctx);
            }
        });
        render();
    }

    private void render() {
        progress.setCurrent(step);
        body.getChildren().setAll(switch (step) {
            case 0 -> signInStep();
            case 1 -> starterStep();
            default -> performanceStep();
        });
        Motion.enter(body, Motion.Speed.MEDIUM, Motion.ENTER_OFFSET);
        Button skipAll = UiKit.button(I18n.tr("ui.firstRun.skipSetup"), null, dialog::close, "ghost");
        Button back = UiKit.button(I18n.tr("ui.firstRun.back"), Icons.BACK, () -> go(step - 1));
        back.setDisable(step == 0);
        Button skip = UiKit.button(I18n.tr("ui.firstRun.skipStep"), null, () -> next(), "ghost");
        Button next = step == 2 ? UiKit.primary(I18n.tr("ui.firstRun.create"), Icons.PLAY, this::finish)
            : UiKit.primary(I18n.tr("ui.firstRun.next"), Icons.CHEVRON_RIGHT, this::next);
        actions.getChildren().setAll(skipAll, UiKit.spacer(), back, skip, next);
        next.requestFocus();
    }

    private void go(int to) {
        step = Math.max(0, Math.min(2, to));
        render();
    }

    private void next() {
        if (step == 2) {
            finish();
        } else {
            go(step + 1);
        }
    }

    private Node signInStep() {
        Optional<Account> account = preview ? Optional.empty() : ctx.services().accounts().selected();
        VBox v = new VBox();
        v.getStyleClass().add("firstrun-step");
        if (account.isPresent()) {
            HBox who = new HBox(PlayerHead.of(account.get(), 56), new VBox(
                UiKit.label(I18n.tr("ui.signin.signedInAs"), "caption", "faint"), UiKit.label(account.get().username, "h2")));
            who.getStyleClass().add("signin-success");
            v.getChildren().addAll(UiKit.label(I18n.tr("ui.firstRun.signedIn"), "h2"), who);
            return v;
        }
        Button sign = UiKit.primary(I18n.tr("ui.account.signIn"), Icons.USER,
            () -> SignInDialog.show(ctx, a -> render()));
        v.getChildren().addAll(UiKit.label(I18n.tr("ui.firstRun.signInTitle"), "h2"),
            UiKit.label(I18n.tr("ui.firstRun.signInBody"), "muted"), sign,
            UiKit.label(I18n.tr("ui.firstRun.signInSkip"), "faint", "small"));
        return v;
    }

    private Node starterStep() {
        FlowPane cards = new FlowPane();
        cards.getStyleClass().add("firstrun-cards");
        for (String id : STARTERS) {
            ProjectTemplate t = ProjectTemplates.byId(id).orElseThrow();
            VBox card = Cards.preset(ICONS.get(id), I18n.tr(t.nameKey()), I18n.tr(t.descriptionKey()), id.equals(template));
            Cards.onOpen(card, () -> {
                template = id;
                render();
            });
            card.setAccessibleText(I18n.tr(t.nameKey()) + ". " + I18n.tr(t.descriptionKey()));
            cards.getChildren().add(card);
        }
        return stepBox(UiKit.label(I18n.tr("ui.firstRun.starterTitle"), "h2"),
            UiKit.label(I18n.tr("ui.firstRun.starterBody"), "muted"), cards);
    }

    private Node performanceStep() {
        FlowPane cards = new FlowPane();
        cards.getStyleClass().add("firstrun-cards");
        for (Preset p : List.of(Preset.BALANCED, Preset.ULTRA_PERFORMANCE)) {
            VBox card = Cards.preset(p == Preset.BALANCED ? Icons.GAUGE : Icons.BOLT, I18n.tr(p.nameKey()),
                I18n.tr(p.descriptionKey()), p == preset);
            if (p == Preset.ULTRA_PERFORMANCE) {
                card.getStyleClass().add("ultra");
            }
            Cards.onOpen(card, () -> {
                preset = p;
                render();
            });
            card.setAccessibleText(I18n.tr(p.nameKey()) + ". " + I18n.tr(p.descriptionKey()));
            cards.getChildren().add(card);
        }
        return stepBox(UiKit.label(I18n.tr("ui.firstRun.performanceTitle"), "h2"),
            UiKit.label(I18n.tr("ui.firstRun.performanceBody"), "muted"), cards,
            UiKit.label(I18n.tr("ui.firstRun.performanceLater"), "faint", "small"));
    }

    private static VBox stepBox(Node... children) {
        VBox v = new VBox(children);
        v.getStyleClass().add("firstrun-step");
        return v;
    }

    /** create the chosen starter with the chosen preset on the newest release, then open it */
    private void finish() {
        if (preview) {
            dialog.close();
            return;
        }
        ProjectTemplate t = ProjectTemplates.byId(template).orElseThrow();
        Preset chosen = preset;
        dialog.close();
        Fx.run(() -> request(t, chosen), req -> ProjectCreation.run(ctx, req, false, null, true, t.suggestions()),
            err -> ctx.overlay().message(I18n.tr("ui.firstRun.failedTitle"),
                I18n.tr("ui.firstRun.failedBody", Tasks.message(err))));
    }

    private NewProjectRequest request(ProjectTemplate t, Preset chosen) throws Exception {
        String mc = ctx.services().versions().latestRelease(CancellationToken.NONE);
        String loaderVersion = null;
        if (t.suggestedLoader() != null && t.suggestedLoader() != dev.shardlight.client.loader.LoaderType.VANILLA) {
            List<LoaderVersion> versions = ctx.services().loaders().versions(t.suggestedLoader(), mc, CancellationToken.NONE);
            loaderVersion = versions.stream().filter(LoaderVersion::recommended).map(LoaderVersion::version).findFirst()
                .orElse(versions.isEmpty() ? null : versions.get(0).version());
        }
        NewProjectRequest base = t.request(I18n.tr(t.nameKey()) + " " + mc, mc, loaderVersion);
        return new NewProjectRequest(base.name(), base.icon(), base.templateId(), base.minecraftVersion(), base.loader(),
            base.shardlightModules(), base.shardlightTheme(), chosen, base.java());
    }
}

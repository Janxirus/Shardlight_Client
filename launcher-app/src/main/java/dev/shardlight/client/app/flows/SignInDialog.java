package dev.shardlight.client.app.flows;

import dev.shardlight.client.account.Account;
import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Motion;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.PlayerHead;
import dev.shardlight.client.app.ui.ProgressRing;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.auth.DeviceCode;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.task.CancellationToken;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.net.URI;
import java.util.function.Consumer;

/**
 * Microsoft sign-in with the device-code flow: the launcher shows a short code, the player enters it on
 * Microsoft's own page in their browser, and the launcher never sees a password. The dialog has four states:
 * getting a code, the code with a live waiting indicator, success (the player's head and name), and an error
 * that says what to do next.
 */
public final class SignInDialog {
    private final AppContext ctx;
    private final Consumer<Account> done;
    private final CancellationToken cancel = new CancellationToken();
    private final VBox body = new VBox();
    private final HBox actions;
    private final Overlay.Dialog dialog;
    private final Button closeButton = new Button();

    private SignInDialog(AppContext ctx, Consumer<Account> done) {
        this.ctx = ctx;
        this.done = done;
        body.getStyleClass().add("signin-body");
        VBox modal = Overlay.modal(I18n.tr("ui.signin.title"), body, closeButton);
        modal.getStyleClass().add("signin");
        actions = (HBox) modal.getChildren().get(2);
        closeButton.setText(I18n.tr("common.cancel"));
        closeButton.setCancelButton(true);
        dialog = ctx.overlay().show(modal, false, false);
        closeButton.setOnAction(e -> close());
    }

    private void close() {
        cancel.cancel();
        dialog.close();
    }

    /** open the sign-in dialog; {@code done} gets the signed-in account (not called on cancel or failure) */
    public static void show(AppContext ctx, Consumer<Account> done) {
        if (!ctx.services().accounts().isSignInConfigured()) {
            showNotConfigured(ctx, done);
            return;
        }
        SignInDialog d = new SignInDialog(ctx, done);
        d.gettingCode();
        Fx.run(() -> ctx.services().accounts().signIn(code -> Fx.ui(() -> d.code(code)), d.cancel), account -> {
            d.success(account);
            ctx.accountsChanged();
            if (done != null) {
                done.accept(account);
            }
        }, err -> {
            if (Tasks.isCancelled(err)) {
                d.dialog.close();
                return;
            }
            d.error(err, () -> {
                d.dialog.close();
                show(ctx, done);
            });
        });
    }

    /** --capture only: the dialog in one state ("code", "success", or an error kind) without signing in */
    public static void preview(AppContext ctx, String state) {
        SignInDialog d = new SignInDialog(ctx, null);
        switch (state) {
            case "getting" -> d.gettingCode();
            case "code" -> d.code(new DeviceCode("K7QD-M2XP", URI.create("https://www.microsoft.com/link"), 900, 5, "-", ""));
            case "success" -> {
                Account a = new Account();
                a.username = "Janxirus";
                d.success(a);
            }
            case "approval" -> d.error(new LauncherException(ErrorCode.AUTH_NOT_CONFIGURED, "auth.error.appNotApproved"), () -> { });
            case "network" -> d.error(new LauncherException(ErrorCode.NETWORK, "auth.error.microsoft", "timeout"), () -> { });
            default -> d.error(new LauncherException(ErrorCode.AUTH_NO_XBOX_ACCOUNT, "auth.error.xbox.noAccount"), () -> { });
        }
    }

    private void setBody(Node... nodes) {
        body.getChildren().setAll(nodes);
        Motion.enter(body, Motion.Speed.MEDIUM, Motion.ENTER_OFFSET);
    }

    private void setActions(Node... nodes) {
        actions.getChildren().setAll(nodes);
    }

    private void gettingCode() {
        VBox lines = new VBox(UiKit.skeleton("line"), UiKit.skeleton("short"));
        lines.getStyleClass().add("signin-skeleton");
        Node intro = UiKit.label(I18n.tr("ui.signin.intro"), "muted");
        setBody(intro, UiKit.label(I18n.tr("ui.signin.gettingCode"), "strong"), lines);
        closeButton.setText(I18n.tr("common.cancel"));
        setActions(closeButton);
    }

    private void code(DeviceCode code) {
        Label codeLabel = UiKit.label(code.userCode(), "device-code", "mono");
        codeLabel.setWrapText(false);
        codeLabel.setAccessibleText(I18n.tr("ui.signin.codeAccessible", String.join(" ", code.userCode().split(""))));
        Button copy = UiKit.button(I18n.tr("ui.signin.copy"), Icons.COPY, () -> { });
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
            c.putString(code.userCode());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
            UiKit.copied(copy);
            ctx.overlay().toast("success", I18n.tr("ui.signin.copied"));
        });
        HBox codeRow = new HBox(codeLabel, copy);
        codeRow.getStyleClass().add("signin-code-row");
        String shown = code.verificationUri().getHost() + code.verificationUri().getPath();
        Button open = UiKit.primary(I18n.tr("ui.signin.openPage", shown.replaceFirst("^www\\.", "")), Icons.EXTERNAL,
            () -> ctx.openUrl(code.verificationUri().toString()));
        ProgressRing ring = new ProgressRing(-1);
        ring.getStyleClass().add("small");
        HBox waiting = new HBox(ring, new VBox(UiKit.label(I18n.tr("ui.signin.waiting"), "muted"),
            UiKit.label(I18n.tr("ui.signin.expires", Math.max(1, code.expiresInSeconds() / 60)), "faint", "small")));
        waiting.getStyleClass().add("signin-waiting");
        VBox steps = new VBox(UiKit.label(I18n.tr("ui.signin.step1", shown.replaceFirst("^www\\.", "")), "strong"),
            UiKit.label(I18n.tr("ui.signin.step2"), "muted"));
        steps.getStyleClass().add("signin-steps");
        VBox content = new VBox(steps, codeRow, open, waiting, UiKit.label(I18n.tr("ui.signin.privacy"), "faint", "small"));
        content.getStyleClass().add("signin-content");
        if (!ctx.services().accounts().remembersSignIn()) {
            content.getChildren().add(UiKit.banner("warning", I18n.tr("ui.signin.notRemembered")));
        }
        setBody(content);
        closeButton.setText(I18n.tr("common.cancel"));
        setActions(closeButton);
        open.requestFocus();
    }

    private void success(Account account) {
        Node head = PlayerHead.of(account, 72);
        Node check = Icons.of(Icons.CHECK, 16);
        check.getStyleClass().add("success-icon");
        Label name = UiKit.label(account.username, "h2");
        HBox status = new HBox(check, UiKit.label(I18n.tr("ui.signin.successLine"), "muted"));
        status.getStyleClass().add("signin-status");
        VBox text = new VBox(UiKit.label(I18n.tr("ui.signin.signedInAs"), "caption", "faint"), name, status);
        text.getStyleClass().add("signin-who");
        HBox who = new HBox(head, text);
        who.getStyleClass().add("signin-success");
        setBody(who);
        Button finish = UiKit.primary(I18n.tr("ui.signin.finish"), Icons.CHECK, dialog::close);
        setActions(finish);
        finish.requestFocus();
    }

    private void error(Throwable err, Runnable retry) {
        SignInErrors advice = SignInErrors.of(err);
        Node icon = Icons.of(Icons.WARNING, 24);
        icon.getStyleClass().add("warning-icon");
        VBox text = new VBox(UiKit.label(advice.title(), "h3"), UiKit.label(advice.nextStep(), "muted"));
        text.getStyleClass().add("signin-error-text");
        HBox head = new HBox(icon, text);
        head.getStyleClass().add("signin-error");
        Label details = UiKit.label(advice.details(), "faint", "small");
        VBox content = new VBox(head, details);
        content.getStyleClass().add("signin-content");
        setBody(content);
        closeButton.setText(I18n.tr("common.close"));
        HBox.setHgrow(closeButton, javafx.scene.layout.Priority.NEVER);
        java.util.ArrayList<Node> buttons = new java.util.ArrayList<>();
        buttons.add(closeButton);
        if (advice.helpUrl() != null) {
            buttons.add(UiKit.button(advice.helpLabel(), Icons.EXTERNAL, () -> ctx.openUrl(advice.helpUrl())));
        }
        if (advice.settings()) {
            buttons.add(UiKit.button(I18n.tr("ui.signin.openSettings"), Icons.GEAR, () -> {
                dialog.close();
                ctx.openSettings("accounts");
            }));
        }
        if (advice.retry()) {
            Button again = UiKit.primary(I18n.tr("common.retry"), Icons.REFRESH, retry);
            buttons.add(again);
        }
        setActions(buttons.toArray(new Node[0]));
    }

    /** sign-in needs the distributor's Microsoft application id; explain, and let an advanced user enter one */
    private static void showNotConfigured(AppContext ctx, Consumer<Account> done) {
        TextField id = new TextField();
        id.setPromptText("00000000-0000-0000-0000-000000000000");
        id.setAccessibleText(I18n.tr("ui.signin.clientIdLabel"));
        Label error = UiKit.label("", "danger-text", "small");
        VBox body = new VBox(
            UiKit.label(I18n.tr("ui.signin.notConfigured")),
            UiKit.label(I18n.tr("ui.signin.notConfiguredHow"), "muted", "small"),
            UiKit.label(I18n.tr("ui.signin.clientIdLabel"), "small"), id, error);
        body.getStyleClass().add("signin-content");
        Button cancel = new Button(I18n.tr("common.cancel"));
        cancel.setCancelButton(true);
        Button save = UiKit.primary(I18n.tr("ui.signin.saveAndContinue"), null, () -> { });
        VBox modal = Overlay.modal(I18n.tr("ui.signin.title"), body, cancel, save);
        modal.getStyleClass().add("signin");
        Overlay.Dialog d = ctx.overlay().show(modal, true, false);
        cancel.setOnAction(e -> d.close());
        save.setOnAction(e -> {
            String v = id.getText() == null ? "" : id.getText().trim();
            if (!v.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                error.setText(I18n.tr("ui.signin.clientIdInvalid"));
                return;
            }
            try {
                ctx.services().settings().update(s -> s.auth.microsoftClientId = v);
            } catch (Exception ex) {
                error.setText(Tasks.message(ex));
                return;
            }
            d.close();
            show(ctx, done);
        });
        id.requestFocus();
    }
}

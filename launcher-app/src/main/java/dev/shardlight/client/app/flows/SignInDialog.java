package dev.shardlight.client.app.flows;

import dev.shardlight.client.account.Account;
import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.Overlay;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.auth.DeviceCode;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.task.CancellationToken;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Microsoft sign-in with the device-code flow: the launcher shows a short code, the player enters it on
 * Microsoft's own page in their browser, and the launcher never sees a password.
 */
public final class SignInDialog {
    private SignInDialog() {
    }

    /** open the sign-in dialog; {@code done} gets the signed-in account (not called on cancel or failure) */
    public static void show(AppContext ctx, Consumer<Account> done) {
        if (!ctx.services().accounts().isSignInConfigured()) {
            showNotConfigured(ctx, done);
            return;
        }
        CancellationToken cancel = new CancellationToken();
        VBox body = new VBox(14);
        body.setPrefWidth(520);
        ProgressIndicator spin = new ProgressIndicator();
        spin.setPrefSize(26, 26);
        Label status = UiKit.label(I18n.tr("ui.signin.gettingCode"), "muted");
        HBox statusRow = new HBox(10, spin, status);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        body.getChildren().addAll(UiKit.label(I18n.tr("ui.signin.intro")), statusRow);
        if (!ctx.services().accounts().remembersSignIn()) {
            body.getChildren().add(UiKit.banner("warning", I18n.tr("ui.signin.notRemembered")));
        }
        Button cancelButton = new Button(I18n.tr("common.cancel"));
        cancelButton.setCancelButton(true);
        Overlay.Dialog dialog = ctx.overlay().show(Overlay.modal(I18n.tr("ui.signin.title"), body, cancelButton), false, false);
        cancelButton.setOnAction(e -> {
            cancel.cancel();
            dialog.close();
        });

        Consumer<DeviceCode> showCode = code -> Fx.ui(() -> {
            Label codeLabel = new Label(code.userCode());
            codeLabel.getStyleClass().add("device-code");
            codeLabel.setAccessibleText(I18n.tr("ui.signin.codeAccessible", String.join(" ", code.userCode().split(""))));
            Button copy = UiKit.button(I18n.tr("ui.signin.copy"), Icons.COPY, () -> {
                ClipboardContent c = new ClipboardContent();
                c.putString(code.userCode());
                Clipboard.getSystemClipboard().setContent(c);
                ctx.overlay().toast("success", I18n.tr("ui.signin.copied"));
            });
            Button open = UiKit.primary(I18n.tr("ui.signin.openPage"), Icons.EXTERNAL,
                () -> ctx.openUrl(code.verificationUri().toString()));
            HBox codeRow = new HBox(12, codeLabel, copy);
            codeRow.setAlignment(Pos.CENTER_LEFT);
            Label step1 = UiKit.label(I18n.tr("ui.signin.step1", code.verificationUri().toString()));
            Label step2 = UiKit.label(I18n.tr("ui.signin.step2"));
            status.setText(I18n.tr("ui.signin.waiting"));
            body.getChildren().setAll(step1, open, step2, codeRow, statusRow,
                UiKit.label(I18n.tr("ui.signin.privacy"), "faint", "small"));
            open.requestFocus();
        });

        Fx.run(() -> ctx.services().accounts().signIn(showCode, cancel), account -> {
            dialog.close();
            ctx.accountsChanged();
            ctx.overlay().toast("success", I18n.tr("ui.signin.done", account.username));
            if (done != null) {
                done.accept(account);
            }
        }, err -> {
            if (Tasks.isCancelled(err)) {
                dialog.close();
                return;
            }
            spin.setVisible(false);
            spin.setManaged(false);
            Button retry = UiKit.primary(I18n.tr("common.retry"), Icons.REFRESH, () -> {
                dialog.close();
                show(ctx, done);
            });
            body.getChildren().setAll(UiKit.banner("danger", Tasks.message(err)), retry);
            cancelButton.setText(I18n.tr("common.close"));
        });
    }

    /** sign-in needs the distributor's Microsoft application id; explain, and let an advanced user enter one */
    private static void showNotConfigured(AppContext ctx, Consumer<Account> done) {
        TextField id = new TextField();
        id.setPromptText("00000000-0000-0000-0000-000000000000");
        id.setAccessibleText(I18n.tr("ui.signin.clientIdLabel"));
        Label error = UiKit.label("", "danger-text", "small");
        VBox body = new VBox(10,
            UiKit.label(I18n.tr("ui.signin.notConfigured")),
            UiKit.label(I18n.tr("ui.signin.notConfiguredHow"), "muted", "small"),
            UiKit.label(I18n.tr("ui.signin.clientIdLabel"), "small"), id, error);
        body.setPrefWidth(520);
        Button cancel = new Button(I18n.tr("common.cancel"));
        cancel.setCancelButton(true);
        Button save = UiKit.primary(I18n.tr("ui.signin.saveAndContinue"), null, () -> { });
        Overlay.Dialog d = ctx.overlay().show(Overlay.modal(I18n.tr("ui.signin.title"), body, cancel, save), true, false);
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

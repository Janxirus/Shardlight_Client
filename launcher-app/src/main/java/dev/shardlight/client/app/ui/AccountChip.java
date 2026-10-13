package dev.shardlight.client.app.ui;

import dev.shardlight.client.account.Account;
import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.SignInDialog;
import dev.shardlight.client.i18n.I18n;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Optional;

/** The account area at the bottom of the sidebar: who is signed in, or a Sign in button. */
public final class AccountChip {
    private final AppContext ctx;
    private final VBox box = new VBox();
    private boolean compact;

    public AccountChip(AppContext ctx) {
        this.ctx = ctx;
        box.getStyleClass().add("account-chip");
        ctx.onAccountsChanged(this::refresh);
        ctx.shell().onCollapsedChanged(c -> {
            compact = c;
            refresh();
        });
        compact = ctx.shell().isCollapsed();
        refresh();
    }

    public VBox view() {
        return box;
    }

    private void refresh() {
        box.getChildren().clear();
        Optional<Account> acc = ctx.services().accounts().selected();
        if (acc.isPresent()) {
            Account a = acc.get();
            Label name = UiKit.label(a.username, "account-name");
            name.setWrapText(false);
            Label sub = UiKit.label(I18n.tr("ui.account.microsoft"), "faint", "tiny");
            HBox row = new HBox(UiKit.iconBox(Icons.of(Icons.USER, 18), ThemeManager.em(2.429)));
            if (!compact) {
                VBox text = new VBox(name, sub);
                text.setMinWidth(0);
                row.getChildren().add(text);
            }
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("account-row");
            row.setFocusTraversable(true);
            row.setOnMouseClicked(e -> ctx.openSettings("accounts"));
            row.setOnKeyPressed(e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                    ctx.openSettings("accounts");
                }
            });
            row.setAccessibleText(I18n.tr("ui.account.signedInAs", a.username));
            UiKit.tooltip(row, I18n.tr("ui.account.manage"));
            box.getChildren().add(row);
        } else {
            Button b = UiKit.button(compact ? "" : I18n.tr("ui.account.signIn"), Icons.USER, () -> SignInDialog.show(ctx, null));
            b.setAccessibleText(I18n.tr("ui.account.signIn"));
            UiKit.tooltip(b, I18n.tr("ui.account.signIn"));
            b.setMaxWidth(Double.MAX_VALUE);
            box.getChildren().add(b);
        }
    }
}

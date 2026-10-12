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
    private final VBox box = new VBox(6);

    public AccountChip(AppContext ctx) {
        this.ctx = ctx;
        box.getStyleClass().add("account-chip");
        ctx.onAccountsChanged(this::refresh);
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
            HBox row = new HBox(10, UiKit.iconBox(Icons.of(Icons.USER, 18), 34), new VBox(1, name, sub));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("account-row");
            row.setOnMouseClicked(e -> ctx.shell().navigate("settings"));
            row.setAccessibleText(I18n.tr("ui.account.signedInAs", a.username));
            UiKit.tooltip(row, I18n.tr("ui.account.manage"));
            box.getChildren().add(row);
        } else {
            Button b = UiKit.button(I18n.tr("ui.account.signIn"), Icons.USER, () -> SignInDialog.show(ctx, null));
            b.setMaxWidth(Double.MAX_VALUE);
            box.getChildren().add(b);
        }
    }
}

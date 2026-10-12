package dev.shardlight.client.app.ui;

import dev.shardlight.client.account.Account;
import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.SignInDialog;
import dev.shardlight.client.i18n.I18n;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Optional;

/**
 * The global tools on the right of the top bar: the search box that opens the command palette, the downloads
 * indicator, the notifications bell with this session's toasts, and the account switcher.
 */
public final class TopBarTools {
    private static final int MAX_SHOWN = 8;
    private final AppContext ctx;
    private final Button search;
    private final StackPane downloads;
    private final Button downloadsButton;
    private final ProgressRing ring = new ProgressRing(0);
    private final Button bell;
    private final Region unread = new Region();
    private final Button account = new Button();
    private int seen;

    public TopBarTools(AppContext ctx) {
        this.ctx = ctx;
        Label hint = new Label(I18n.tr("ui.palette.shortcut"));
        hint.getStyleClass().addAll("kbd", "tabular");
        search = new Button(I18n.tr("ui.palette.searchBox"));
        search.setGraphic(Icons.of(Icons.SEARCH, 16));
        search.getStyleClass().add("global-search");
        search.setMnemonicParsing(false);
        search.setAccessibleText(I18n.tr("ui.palette.title"));
        HBox searchBox = new HBox(search, hint);
        searchBox.getStyleClass().add("global-search-box");
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.setOnMouseClicked(e -> ctx.shell().openPalette());
        search.setOnAction(e -> ctx.shell().openPalette());

        downloadsButton = UiKit.iconButton(Icons.DOWNLOAD, I18n.tr("ui.nav.downloads"), () -> ctx.shell().navigate("downloads"));
        ring.getStyleClass().add("small");
        ring.setMouseTransparent(true);
        ring.setVisible(false);
        downloads = new StackPane(downloadsButton, ring);
        downloads.getStyleClass().add("downloads-indicator");
        StackPane.setAlignment(ring, Pos.BOTTOM_RIGHT);

        bell = UiKit.iconButton(Icons.BELL, I18n.tr("ui.notifications.title"), this::showNotifications);
        unread.getStyleClass().add("unread-dot");
        unread.setMouseTransparent(true);
        unread.setVisible(false);
        unread.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane bellBox = new StackPane(bell, unread);
        StackPane.setAlignment(unread, Pos.TOP_RIGHT);
        ctx.overlay().onHistoryChanged(this::updateUnread);

        account.getStyleClass().addAll("account-switcher", "ghost");
        account.setMnemonicParsing(false);
        account.setOnAction(e -> showAccounts());
        ctx.onAccountsChanged(this::refreshAccount);
        refreshAccount();

        ctx.shell().setTools(searchBox, downloads, bellBox, account);
    }

    /** {@code active} downloads, {@code fraction} done (negative when unknown) */
    public void setDownloads(int active, double fraction) {
        ring.setVisible(active > 0);
        ring.setProgress(fraction);
        String tip = active > 0
            ? (fraction >= 0 ? I18n.tr("ui.downloads.indicator", active, Math.round(fraction * 100))
                : I18n.tr("ui.downloads.indicatorUnknown", active))
            : I18n.tr("ui.downloads.indicatorIdle");
        UiKit.tooltip(downloadsButton, tip);
        downloadsButton.setAccessibleHelp(tip);
    }

    private void updateUnread() {
        int n = ctx.overlay().history().size();
        if (n < seen) {
            seen = n;
        }
        unread.setVisible(n > seen);
        bell.setAccessibleHelp(n > seen ? I18n.tr("ui.notifications.unread", n - seen) : null);
    }

    /** open the notifications panel under the bell */
    public void showNotifications() {
        seen = ctx.overlay().history().size();
        updateUnread();
        VBox panel = new VBox();
        panel.getStyleClass().add("notification-panel");
        Button clear = UiKit.button(I18n.tr("ui.notifications.clear"), null, () -> {
            ctx.overlay().clearHistory();
            ctx.overlay().closePopover();
        }, "ghost", "small");
        List<Overlay.Notice> all = ctx.overlay().history();
        clear.setDisable(all.isEmpty());
        HBox head = new HBox(UiKit.label(I18n.tr("ui.notifications.title"), "h3"), UiKit.spacer(), clear);
        head.setAlignment(Pos.CENTER_LEFT);
        panel.getChildren().add(head);
        if (all.isEmpty()) {
            panel.getChildren().add(UiKit.label(I18n.tr("ui.notifications.empty"), "muted", "small"));
        } else {
            VBox rows = new VBox();
            rows.getStyleClass().add("notification-list");
            DateTimeFormatter time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(I18n.locale())
                .withZone(ZoneId.systemDefault());
            for (int i = all.size() - 1; i >= Math.max(0, all.size() - MAX_SHOWN); i--) {
                Overlay.Notice n = all.get(i);
                Node icon = Icons.of(switch (n.kind()) {
                    case "success" -> Icons.CHECK;
                    case "warning" -> Icons.WARNING;
                    case "danger" -> Icons.ERROR;
                    default -> Icons.INFO;
                }, 16);
                Label text = UiKit.label(n.text(), "small");
                HBox.setHgrow(text, javafx.scene.layout.Priority.ALWAYS);
                text.setMaxWidth(Double.MAX_VALUE);
                Label at = UiKit.label(time.format(n.at()), "faint", "caption", "tabular");
                at.setWrapText(false);
                at.setMinWidth(Region.USE_PREF_SIZE);
                HBox row = new HBox(icon, text, at);
                row.getStyleClass().addAll("notification-row", n.kind());
                rows.getChildren().add(row);
            }
            if (all.size() > MAX_SHOWN) {
                rows.getChildren().add(UiKit.label(I18n.tr("ui.notifications.older", all.size() - MAX_SHOWN), "faint", "small"));
            }
            panel.getChildren().add(rows);
        }
        ctx.overlay().popover(bell, panel, true);
    }

    private void refreshAccount() {
        Optional<Account> acc = ctx.services().accounts().selected();
        Node avatar = UiKit.iconBox(Icons.of(Icons.USER, 16), ThemeManager.em(2));
        avatar.getStyleClass().add("avatar");
        Node chevron = Icons.of(Icons.CHEVRON_DOWN, 14);
        chevron.getStyleClass().add("faint");
        Label name = new Label(acc.map(a -> a.username).orElse(I18n.tr("ui.account.signIn")));
        name.getStyleClass().add("account-switcher-name");
        HBox content = new HBox(avatar, name, chevron);
        content.setAlignment(Pos.CENTER_LEFT);
        content.getStyleClass().add("account-switcher-content");
        account.setGraphic(content);
        account.setAccessibleText(acc.map(a -> I18n.tr("ui.account.signedInAs", a.username))
            .orElse(I18n.tr("ui.account.signIn")));
        UiKit.tooltip(account, I18n.tr("ui.account.switch"));
    }

    private void showAccounts() {
        VBox panel = new VBox();
        panel.getStyleClass().add("account-panel");
        var service = ctx.services().accounts();
        Optional<Account> selected = service.selected();
        List<Account> all = service.accounts();
        if (!all.isEmpty()) {
            panel.getChildren().add(UiKit.label(I18n.tr("ui.account.accounts"), "eyebrow"));
        }
        for (Account a : all) {
            boolean current = selected.isPresent() && selected.get().id.equals(a.id);
            Button b = UiKit.button(a.username, Icons.USER, () -> {
                ctx.overlay().closePopover();
                if (!current) {
                    Tasks.quiet(ctx.overlay(), () -> {
                        service.select(a.id);
                        return a;
                    }, x -> {
                        ctx.accountsChanged();
                        ctx.overlay().toast("success", I18n.tr("ui.account.switched", x.username));
                    });
                }
            }, "ghost", "menu-row");
            if (current) {
                Node check = Icons.of(Icons.CHECK, 16);
                check.getStyleClass().add("accent-icon");
                b.getStyleClass().add("current");
                HBox row = new HBox(b, check);
                row.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(b, javafx.scene.layout.Priority.ALWAYS);
                b.setMaxWidth(Double.MAX_VALUE);
                b.setAccessibleHelp(I18n.tr("ui.account.current"));
                panel.getChildren().add(row);
            } else {
                b.setMaxWidth(Double.MAX_VALUE);
                panel.getChildren().add(b);
            }
        }
        Button add = UiKit.button(I18n.tr(all.isEmpty() ? "ui.account.signIn" : "ui.account.add"), Icons.PLUS, () -> {
            ctx.overlay().closePopover();
            SignInDialog.show(ctx, null);
        }, all.isEmpty() ? "primary" : "ghost", "menu-row");
        add.setMaxWidth(Double.MAX_VALUE);
        Button manage = UiKit.button(I18n.tr("ui.account.manage"), Icons.GEAR, () -> {
            ctx.overlay().closePopover();
            ctx.openSettings("accounts");
        }, "ghost", "menu-row");
        manage.setMaxWidth(Double.MAX_VALUE);
        panel.getChildren().addAll(add, manage);
        ctx.overlay().popover(account, panel, true);
    }
}

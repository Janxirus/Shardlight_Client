package dev.shardlight.client.app.pages;

import dev.shardlight.client.account.Account;
import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.SignInDialog;
import dev.shardlight.client.app.ui.Dialogs;
import dev.shardlight.client.app.ui.Fx;
import dev.shardlight.client.app.ui.Icons;
import dev.shardlight.client.app.ui.SkinPixels;
import dev.shardlight.client.app.ui.SkinViewer;
import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.app.ui.ThemeManager;
import dev.shardlight.client.app.ui.UiKit;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.skin.SkinImage;
import dev.shardlight.client.skin.SkinLibrary;
import dev.shardlight.client.skin.SkinService;
import dev.shardlight.client.skin.SkinVariant;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Skins part of Settings > Accounts: the skin on a 3D model that turns with the mouse, an upload (classic or
 * slim arms), the account's capes, and the library of skins kept on this computer. Without a working sign-in (nobody
 * signed in, or Minecraft has not approved the launcher yet) everything that needs Minecraft explains why it is off,
 * and the library and the model keep working on their own.
 */
public final class SkinsSection {
    private static final Logger LOG = Logger.getLogger(SkinsSection.class.getName());

    /** the skin shown on the model: a file the player picked, a saved skin or the one the account wears */
    private record Preview(byte[] png, Image image, String name, SkinVariant variant, String libraryId, boolean worn) {
        Preview with(SkinVariant v) {
            return new Preview(png, image, name, v, libraryId, worn);
        }
    }

    private final AppContext ctx;
    private final VBox card = new VBox();
    private final SkinViewer viewer = new SkinViewer(ThemeManager.em(18), ThemeManager.em(25));
    private Account account;
    private SkinService.Wardrobe wardrobe;
    private String unavailable;
    private boolean loading;
    private boolean busy;
    private Preview preview;
    private Image capeImage;
    private List<SkinLibrary.Entry> library = List.of();
    private long generation;
    /** the screenshots hold the model at this angle; NaN lets it turn */
    private double frozenYaw = Double.NaN;
    /** the library id of the picture the account wears, once known */
    private String wornId;

    private SkinsSection(AppContext ctx) {
        this.ctx = ctx;
        card.getStyleClass().addAll("card", "stack", "skins-card");
    }

    /** the section for the selected account; loading starts in the background */
    public static Node build(AppContext ctx) {
        SkinsSection s = new SkinsSection(ctx);
        s.start();
        return s.card;
    }

    /** with everything already known (the screenshots): no network, no account needed */
    public static Node buildWith(AppContext ctx, Account account, SkinService.Wardrobe wardrobe, String unavailable,
                                 byte[] skinPng, String name, SkinVariant variant, byte[] capePng, double viewYaw) {
        SkinsSection s = new SkinsSection(ctx);
        s.frozenYaw = viewYaw;
        s.account = account;
        s.wardrobe = wardrobe;
        s.unavailable = unavailable;
        s.library = s.libraryNow();
        Image img = SkinPixels.decode(skinPng);
        if (img != null) {
            String id = Hashing.hash(skinPng, HashAlgo.SHA256);
            s.preview = new Preview(skinPng, img, name, variant, s.library.stream().anyMatch(e -> e.id.equals(id)) ? id : null,
                wardrobe != null);
            if (wardrobe != null) {
                s.wornId = id;
            }
        }
        s.capeImage = SkinPixels.decode(capePng);
        s.refresh();
        return s.card;
    }

    // ---------------------------------------------------------------- loading

    private void start() {
        library = libraryNow();
        account = ctx.services().accounts().selected().orElse(null);
        if (account == null) {
            // nothing to ask Minecraft: show the newest saved skin, if any
            preview = library.isEmpty() ? null : previewOf(library.get(0));
            refresh();
            return;
        }
        loading = true;
        refresh();
        long gen = ++generation;
        String id = account.id;
        Fx.run(() -> {
            SkinService.Wardrobe w = ctx.services().skins().wardrobe(id, CancellationToken.NONE);
            byte[] skin = null;
            byte[] cape = null;
            var active = w.activeSkin();
            if (active.isPresent()) {
                skin = ctx.services().skins().texture(active.get().url(), CancellationToken.NONE);
            }
            var activeCape = w.activeCape();
            if (activeCape.isPresent()) {
                try {
                    cape = ctx.services().skins().texture(activeCape.get().url(), CancellationToken.NONE);
                } catch (LauncherException e) {
                    LOG.log(Level.FINE, "The cape picture could not be loaded", e);
                }
            }
            return new Object[] {w, skin, cape};
        }, result -> {
            if (gen != generation) {
                return;
            }
            loading = false;
            wardrobe = (SkinService.Wardrobe) result[0];
            unavailable = null;
            byte[] skin = (byte[]) result[1];
            capeImage = SkinPixels.decode((byte[]) result[2]);
            if (skin != null) {
                var active = wardrobe.activeSkin().orElseThrow();
                preview = adopt(skin, account.username, active.variant());
            } else if (!library.isEmpty()) {
                preview = previewOf(library.get(0));
            }
            refresh();
        }, err -> {
            if (gen != generation) {
                return;
            }
            loading = false;
            unavailable = Tasks.message(err);
            if (!library.isEmpty()) {
                preview = previewOf(library.get(0));
            }
            refresh();
        });
    }

    /** the skin the account wears becomes part of the library (it has been used), and is shown as worn */
    private Preview adopt(byte[] png, String name, SkinVariant variant) {
        Image img = SkinPixels.decode(png);
        if (img == null) {
            return null;
        }
        String id = Hashing.hash(png, HashAlgo.SHA256);
        try {
            SkinLibrary lib = ctx.services().skinLibrary();
            if (lib.find(id).isEmpty()) {
                lib.add(name, variant, png);
            }
            library = lib.list();
        } catch (LauncherException e) {
            LOG.log(Level.FINE, "The worn skin could not be added to the library", e);
        }
        wornId = id;
        return new Preview(png, img, name, variant, id, true);
    }

    private List<SkinLibrary.Entry> libraryNow() {
        try {
            return ctx.services().skinLibrary().list();
        } catch (LauncherException e) {
            LOG.log(Level.WARNING, "The skin library could not be read", e);
            return List.of();
        }
    }

    private Preview previewOf(SkinLibrary.Entry e) {
        try {
            byte[] png = ctx.services().skinLibrary().bytes(e.id);
            Image img = SkinPixels.decode(png);
            if (img == null) {
                return null;
            }
            return new Preview(png, img, e.name, e.variant(), e.id, wardrobe != null && isWornId(e.id));
        } catch (LauncherException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------- drawing

    private void refresh() {
        card.getChildren().clear();
        Label title = UiKit.label(I18n.tr("ui.skins.title"), "h3");
        HBox head = new HBox(8, title, UiKit.spacer());
        head.setAlignment(Pos.CENTER_LEFT);
        if (loading) {
            head.getChildren().add(UiKit.label(I18n.tr("ui.skins.loading"), "faint", "small"));
        }
        card.getChildren().add(head);
        card.getChildren().add(UiKit.label(I18n.tr("ui.skins.about"), "muted", "small"));
        if (account == null) {
            Button signIn = UiKit.button(I18n.tr("ui.account.signIn"), Icons.USER, () -> SignInDialog.show(ctx, a -> start()));
            card.getChildren().add(UiKit.banner("info", I18n.tr("ui.skins.noAccount"), signIn));
        } else if (unavailable != null) {
            Button retry = UiKit.button(I18n.tr("ui.state.retry"), Icons.REFRESH, this::start);
            card.getChildren().add(UiKit.banner("warning", I18n.tr("ui.skins.unavailable", unavailable), retry));
        }
        if (!viewer.isThreeDimensional()) {
            card.getChildren().add(UiKit.label(I18n.tr("ui.skins.flat"), "faint", "small"));
        }
        HBox main = new HBox(24, viewerColumn(), detailsColumn());
        main.getStyleClass().add("skins-layout");
        card.getChildren().addAll(main, libraryView());
        showOnModel();
    }

    private void showOnModel() {
        if (!Double.isNaN(frozenYaw)) {
            viewer.freeze(frozenYaw, 8);
        }
        if (preview == null) {
            viewer.show(null, SkinVariant.CLASSIC, null);
        } else {
            viewer.show(preview.image(), preview.variant(), preview.worn() ? capeImage : null);
        }
    }

    private Node viewerColumn() {
        VBox box = new VBox(8);
        box.setAlignment(Pos.TOP_CENTER);
        ViewerStack stack = new ViewerStack(viewer, preview == null);
        Button reset = UiKit.iconButton(Icons.REFRESH, I18n.tr("ui.skins.reset"), viewer::resetView);
        HBox tools = new HBox(8, UiKit.label(I18n.tr("ui.skins.hint"), "faint", "small"), UiKit.spacer(), reset);
        tools.setAlignment(Pos.CENTER_LEFT);
        tools.setMaxWidth(ThemeManager.em(18));
        box.getChildren().addAll(stack, tools);
        return box;
    }

    /** the model, with a hint laid over it while there is nothing to show */
    private static final class ViewerStack extends StackPane {
        ViewerStack(SkinViewer viewer, boolean empty) {
            getChildren().add(viewer);
            if (empty) {
                VBox hint = new VBox(4, UiKit.label(I18n.tr("ui.skins.emptyTitle"), "strong"),
                    UiKit.label(I18n.tr("ui.skins.emptyBody"), "muted", "small"));
                hint.setAlignment(Pos.CENTER);
                hint.setPadding(new Insets(0, ThemeManager.em(1.5), 0, ThemeManager.em(1.5)));
                hint.setMouseTransparent(true);
                hint.getChildren().forEach(n -> {
                    if (n instanceof Label l) {
                        l.setAlignment(Pos.CENTER);
                        l.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
                    }
                });
                getChildren().add(hint);
            }
            setMaxWidth(ThemeManager.em(18));
        }
    }

    private Node detailsColumn() {
        VBox col = new VBox();
        col.getStyleClass().add("stack");
        HBox.setHgrow(col, Priority.ALWAYS);
        col.setMinWidth(0);
        if (preview != null) {
            Label name = UiKit.label(preview.name(), "h3");
            name.setMinWidth(0);
            HBox titleRow = new HBox(8, name);
            titleRow.setAlignment(Pos.CENTER_LEFT);
            if (preview.worn()) {
                titleRow.getChildren().add(UiKit.chip(I18n.tr("ui.skins.wearing"), "success", Icons.CHECK));
            } else if (preview.libraryId() == null) {
                titleRow.getChildren().add(UiKit.chip(I18n.tr("ui.skins.notSaved"), "info"));
            }
            col.getChildren().add(titleRow);
            col.getChildren().add(UiKit.settingRow(I18n.tr("ui.skins.arms"), I18n.tr("ui.skins.armsAbout"), UiKit.segmented(
                List.of(I18n.tr("ui.skins.classic"), I18n.tr("ui.skins.slim")), preview.variant() == SkinVariant.SLIM ? 1 : 0,
                i -> setVariant(i == 1 ? SkinVariant.SLIM : SkinVariant.CLASSIC))));
        }
        col.getChildren().add(actions());
        col.getChildren().add(capes());
        return col;
    }

    private Node actions() {
        Button choose = UiKit.button(I18n.tr("ui.skins.choose"), Icons.IMAGE, this::chooseFile);
        FlowPane row = new FlowPane(8, 8);
        row.getStyleClass().add("button-row");
        row.getChildren().add(choose);
        if (preview != null) {
            boolean canWear = account != null && unavailable == null && !loading;
            Button wear = UiKit.primary(I18n.tr("ui.skins.wear"), Icons.CHECK, this::wear);
            StackPane wearBox = UiKit.explained(wear);
            if (busy) {
                UiKit.loading(wear, true);
            }
            if (preview.worn()) {
                UiKit.disable(wear, I18n.tr("ui.skins.wearAlready"));
            } else if (account == null) {
                UiKit.disable(wear, I18n.tr("ui.skins.wearSignedOut"));
            } else if (!canWear) {
                UiKit.disable(wear, I18n.tr("ui.skins.wearUnavailable"));
            }
            row.getChildren().add(wearBox);
            if (preview.libraryId() == null) {
                row.getChildren().add(UiKit.button(I18n.tr("ui.skins.save"), Icons.DOWNLOAD, this::saveToLibrary));
            }
        }
        VBox box = new VBox(row);
        box.getStyleClass().add("stack-tight");
        return box;
    }

    private Node capes() {
        VBox box = new VBox();
        box.getStyleClass().add("stack-tight");
        box.getChildren().add(UiKit.label(I18n.tr("ui.skins.cape"), "strong"));
        if (wardrobe == null) {
            box.getChildren().add(UiKit.label(I18n.tr(account == null ? "ui.skins.capesSignedOut" : "ui.skins.capesUnknown"), "muted", "small"));
            return box;
        }
        if (wardrobe.capes().isEmpty()) {
            box.getChildren().add(UiKit.label(I18n.tr("ui.skins.capesNone"), "muted", "small"));
            return box;
        }
        FlowPane row = new FlowPane(8, 8);
        String active = wardrobe.activeCape().map(SkinService.Cape::id).orElse(null);
        row.getChildren().add(capeChoice(I18n.tr("ui.skins.noCape"), active == null, null));
        for (SkinService.Cape c : wardrobe.capes()) {
            String label = c.alias() == null || c.alias().isBlank() ? I18n.tr("ui.skins.unnamedCape") : c.alias();
            row.getChildren().add(capeChoice(label, c.id().equals(active), c));
        }
        box.getChildren().add(row);
        return box;
    }

    private Node capeChoice(String text, boolean selected, SkinService.Cape cape) {
        Button b = new Button(text);
        b.setMnemonicParsing(false);
        b.getStyleClass().add("cape-choice");
        if (selected) {
            b.getStyleClass().add("selected");
            b.setGraphic(Icons.of(Icons.CHECK, 14));
        }
        b.setAccessibleText(I18n.tr(selected ? "ui.skins.capeSelected" : "ui.skins.capeSelect", text));
        b.setDisable(busy);
        b.setOnAction(e -> {
            if (!selected) {
                chooseCape(cape);
            }
        });
        return b;
    }

    private Node libraryView() {
        VBox box = new VBox();
        box.getStyleClass().add("stack");
        HBox head = new HBox(8, UiKit.label(I18n.tr("ui.skins.library"), "h3"), UiKit.label(String.valueOf(library.size()), "faint", "tabular"));
        head.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(head);
        if (library.isEmpty()) {
            box.getChildren().add(UiKit.label(I18n.tr("ui.skins.libraryEmpty"), "muted", "small"));
            return box;
        }
        FlowPane tiles = new FlowPane(12, 12);
        tiles.getStyleClass().add("skin-tiles");
        for (SkinLibrary.Entry e : library) {
            tiles.getChildren().add(tile(e));
        }
        box.getChildren().add(tiles);
        return box;
    }

    private Node tile(SkinLibrary.Entry e) {
        Node picture;
        try {
            Image skin = SkinPixels.decode(ctx.services().skinLibrary().bytes(e.id));
            ImageView v = new ImageView(SkinPixels.flatPlayer(skin, e.variant(), false, 3));
            v.setSmooth(false);
            picture = v;
        } catch (LauncherException | RuntimeException ex) {
            picture = Icons.of(Icons.USER, 24);
        }
        Label name = UiKit.label(e.name, "small");
        name.setWrapText(false);
        name.setMaxWidth(ThemeManager.em(6));
        VBox content = new VBox(6, picture, name);
        content.setAlignment(Pos.CENTER);
        Button b = new Button();
        b.setGraphic(content);
        b.getStyleClass().add("skin-tile");
        b.setMnemonicParsing(false);
        boolean selected = preview != null && e.id.equals(preview.libraryId());
        if (selected) {
            b.getStyleClass().add("selected");
        }
        b.setAccessibleText(I18n.tr("ui.skins.tile", e.name, I18n.tr(e.variant() == SkinVariant.SLIM ? "ui.skins.slim" : "ui.skins.classic")));
        b.setOnAction(ev -> {
            Preview p = previewOf(e);
            if (p != null) {
                preview = p;
                refresh();
            }
        });
        ContextMenu menu = new ContextMenu();
        MenuItem rename = new MenuItem(I18n.tr("ui.skins.rename"));
        rename.setOnAction(ev -> rename(e));
        MenuItem remove = new MenuItem(I18n.tr("ui.skins.remove"));
        remove.getStyleClass().add("danger");
        remove.setOnAction(ev -> remove(e));
        menu.getItems().addAll(rename, remove);
        b.setContextMenu(menu);
        return b;
    }

    private boolean isWornId(String id) {
        return wornId != null && wornId.equals(id);
    }

    // ---------------------------------------------------------------- actions

    private void setVariant(SkinVariant v) {
        if (preview == null || preview.variant() == v) {
            return;
        }
        preview = preview.with(v);
        if (preview.libraryId() != null) {
            try {
                ctx.services().skinLibrary().add(preview.name(), v, preview.png());
                library = ctx.services().skinLibrary().list();
            } catch (LauncherException e) {
                LOG.log(Level.FINE, "The arm shape could not be saved", e);
            }
        }
        refresh();
    }

    private void chooseFile() {
        Path file = Dialogs.openFile(ctx.stage(), I18n.tr("ui.skins.chooseTitle"), I18n.tr("ui.skins.filterPng"), "*.png");
        if (file == null) {
            return;
        }
        Fx.run(() -> {
            if (Files.size(file) > SkinImage.MAX_BYTES) {
                throw new LauncherException(dev.shardlight.client.error.ErrorCode.INVALID_DATA, "skin.error.notPng");
            }
            byte[] png = Files.readAllBytes(file);
            return new Object[] {png, SkinImage.readSkin(png)};
        }, r -> {
            byte[] png = (byte[]) r[0];
            SkinImage.Info info = (SkinImage.Info) r[1];
            Image img = SkinPixels.decode(png);
            if (img == null) {
                ctx.overlay().toast("danger", I18n.tr("skin.error.notPng"));
                return;
            }
            String base = file.getFileName().toString();
            String name = base.toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? base.substring(0, base.length() - 4) : base;
            String id = Hashing.hash(png, HashAlgo.SHA256);
            boolean known = library.stream().anyMatch(e -> e.id.equals(id));
            preview = new Preview(png, img, name, info.guessedVariant(), known ? id : null, known && isWornId(id));
            refresh();
        }, err -> ctx.overlay().toast("danger", Tasks.message(err)));
    }

    private void saveToLibrary() {
        if (preview == null || preview.libraryId() != null) {
            return;
        }
        Preview p = preview;
        Fx.run(() -> ctx.services().skinLibrary().add(p.name(), p.variant(), p.png()), e -> {
            library = libraryNow();
            preview = new Preview(p.png(), p.image(), p.name(), p.variant(), e.id, p.worn());
            ctx.overlay().toast("success", I18n.tr("ui.skins.saved", p.name()));
            refresh();
        }, err -> ctx.overlay().toast("danger", Tasks.message(err)));
    }

    private void wear() {
        if (preview == null || account == null || busy) {
            return;
        }
        Preview p = preview;
        String accountId = account.id;
        busy = true;
        refresh();
        Fx.run(() -> {
            SkinService.Wardrobe w = ctx.services().skins().uploadSkin(accountId, p.png(), p.variant(), CancellationToken.NONE);
            SkinLibrary.Entry entry = ctx.services().skinLibrary().add(p.name(), p.variant(), p.png());
            return new Object[] {w, entry};
        }, r -> {
            busy = false;
            wardrobe = (SkinService.Wardrobe) r[0];
            SkinLibrary.Entry entry = (SkinLibrary.Entry) r[1];
            wornId = entry.id;
            library = libraryNow();
            preview = new Preview(p.png(), p.image(), p.name(), p.variant(), entry.id, true);
            ctx.overlay().toast("success", I18n.tr("ui.skins.worn"));
            refresh();
        }, err -> {
            busy = false;
            ctx.overlay().toast("danger", Tasks.message(err));
            refresh();
        });
    }

    private void chooseCape(SkinService.Cape cape) {
        if (account == null || busy) {
            return;
        }
        String accountId = account.id;
        busy = true;
        refresh();
        Fx.run(() -> {
            SkinService.Wardrobe w = ctx.services().skins().setCape(accountId, cape == null ? null : cape.id(), CancellationToken.NONE);
            byte[] picture = null;
            var active = w.activeCape();
            if (active.isPresent()) {
                try {
                    picture = ctx.services().skins().texture(active.get().url(), CancellationToken.NONE);
                } catch (LauncherException e) {
                    LOG.log(Level.FINE, "The cape picture could not be loaded", e);
                }
            }
            return new Object[] {w, picture};
        }, r -> {
            busy = false;
            wardrobe = (SkinService.Wardrobe) r[0];
            capeImage = SkinPixels.decode((byte[]) r[1]);
            ctx.overlay().toast("success", I18n.tr(cape == null ? "ui.skins.capeOff" : "ui.skins.capeOn"));
            refresh();
        }, err -> {
            busy = false;
            ctx.overlay().toast("danger", Tasks.message(err));
            refresh();
        });
    }

    private void rename(SkinLibrary.Entry e) {
        Dialogs.text(ctx.overlay(), I18n.tr("ui.skins.renameTitle"), I18n.tr("ui.skins.renameLabel"), e.name,
            I18n.tr("ui.skins.rename"), v -> v.isBlank() ? I18n.tr("ui.skins.renameEmpty") : null, v -> {
                try {
                    ctx.services().skinLibrary().rename(e.id, v);
                } catch (LauncherException ex) {
                    ctx.overlay().toast("danger", Tasks.message(ex));
                }
                library = libraryNow();
                if (preview != null && e.id.equals(preview.libraryId())) {
                    preview = new Preview(preview.png(), preview.image(), v, preview.variant(), preview.libraryId(), preview.worn());
                }
                refresh();
            });
    }

    private void remove(SkinLibrary.Entry e) {
        ctx.overlay().confirm(I18n.tr("ui.skins.removeTitle", e.name), I18n.tr("ui.skins.removeMessage"), new ArrayList<>(),
            I18n.tr("ui.skins.remove"), true, () -> {
                try {
                    ctx.services().skinLibrary().remove(e.id);
                } catch (LauncherException ex) {
                    ctx.overlay().toast("danger", Tasks.message(ex));
                }
                library = libraryNow();
                if (preview != null && e.id.equals(preview.libraryId())) {
                    preview = new Preview(preview.png(), preview.image(), preview.name(), preview.variant(), null, preview.worn());
                }
                refresh();
            });
    }
}

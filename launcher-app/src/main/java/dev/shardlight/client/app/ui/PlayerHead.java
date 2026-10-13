package dev.shardlight.client.app.ui;

import dev.shardlight.client.account.Account;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.net.URI;

/**
 * A player's head, cut from their own skin: the 8 x 8 face with the hat layer on top, enlarged without
 * smoothing so the pixels stay sharp. Until the skin arrives (or when the account has none) a user icon shows.
 */
public final class PlayerHead {
    /** the only host skins are loaded from */
    static final String SKIN_HOST = "textures.minecraft.net";

    private PlayerHead() {
    }

    public static Node of(Account account, double size) {
        StackPane box = UiKit.iconBox(Icons.of(Icons.USER, size * 0.5), size);
        URI uri = skinUri(account == null ? null : account.skinUrl);
        if (uri == null) {
            return box;
        }
        Image skin = new Image(uri.toString(), 64, 64, false, false, true);
        Runnable swap = () -> {
            if (skin.isError() || skin.getProgress() < 1.0 || skin.getWidth() < 64 || skin.getHeight() < 32) {
                return;
            }
            ImageView head = new ImageView(face(skin));
            head.setSmooth(false);
            head.setFitWidth(size);
            head.setFitHeight(size);
            Rectangle clip = new Rectangle(size, size);
            clip.setArcWidth(size * 0.3);
            clip.setArcHeight(size * 0.3);
            head.setClip(clip);
            box.getChildren().setAll(head);
            Motion.fadeIn(head, Motion.Speed.MEDIUM);
        };
        if (skin.getProgress() >= 1.0) {
            swap.run();
        } else {
            skin.progressProperty().addListener((o, a, b) -> swap.run());
        }
        return box;
    }

    /** the skin's address as an https URL on Mojang's texture host, or null for anything else */
    static URI skinUri(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(url.trim());
            if (u.getHost() == null || !SKIN_HOST.equalsIgnoreCase(u.getHost())) {
                return null;
            }
            return new URI("https", null, SKIN_HOST, -1, u.getPath(), null, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** the 8 x 8 face of a 64-wide skin, as ARGB pixels, with the hat layer drawn over it where it is not clear */
    static int[] faceArgb(int[] skin, int width) {
        int[] out = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int base = skin[(8 + y) * width + 8 + x] | 0xFF000000;
                int hat = skin[(8 + y) * width + 40 + x];
                out[y * 8 + x] = (hat >>> 24) > 0 ? hat | 0xFF000000 : base;
            }
        }
        return out;
    }

    private static WritableImage face(Image skin) {
        int w = (int) skin.getWidth();
        int[] px = new int[w * (int) skin.getHeight()];
        PixelReader r = skin.getPixelReader();
        for (int y = 0; y < skin.getHeight(); y++) {
            for (int x = 0; x < w; x++) {
                px[y * w + x] = r.getArgb(x, y);
            }
        }
        int[] face = faceArgb(px, w);
        WritableImage out = new WritableImage(8, 8);
        PixelWriter pw = out.getPixelWriter();
        for (int i = 0; i < 64; i++) {
            pw.setArgb(i % 8, i / 8, face[i]);
        }
        return out;
    }
}

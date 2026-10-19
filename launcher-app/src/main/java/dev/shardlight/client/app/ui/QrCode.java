package dev.shardlight.client.app.ui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import java.util.Map;
import java.util.logging.Logger;

/** A QR code drawn as dark squares on white (always, whatever the theme, so every phone camera reads it). */
public final class QrCode {
    private static final Logger LOG = Logger.getLogger(QrCode.class.getName());

    private QrCode() {
    }

    /** a {@code size}-pixel QR code of {@code text}, or null when it can't be made */
    public static Node of(String text, int size) {
        BitMatrix m = matrix(text);
        if (m == null) {
            return null;
        }
        int scale = Math.max(1, size / m.getWidth());
        WritableImage img = new WritableImage(m.getWidth() * scale, m.getHeight() * scale);
        PixelWriter w = img.getPixelWriter();
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                Color c = m.get(x, y) ? Color.BLACK : Color.WHITE;
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        w.setColor(x * scale + dx, y * scale + dy, c);
                    }
                }
            }
        }
        ImageView view = new ImageView(img);
        view.setSmooth(false);
        StackPane box = new StackPane(view);
        box.getStyleClass().add("signin-qr-code");
        box.setMaxSize(StackPane.USE_PREF_SIZE, StackPane.USE_PREF_SIZE);
        return box;
    }

    /** the QR modules of {@code text} with a one-module quiet zone, or null when encoding fails */
    static BitMatrix matrix(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 1));
        } catch (WriterException | IllegalArgumentException e) {
            LOG.fine("Couldn't make a QR code: " + e);
            return null;
        }
    }
}

package dev.shardlight.client.app;

import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.log.Logs;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** A plain window explaining why the launcher couldn't start, with the log location. */
final class StartupError {
    private StartupError() {
    }

    static void show(Stage stage, Exception e) {
        String msg = e instanceof LauncherException le ? le.userMessage() : String.valueOf(e.getMessage());
        Label title = new Label("Shardlight Client couldn't start");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Label body = new Label(msg + "\n\nLog file: " + Logs.currentLogFile());
        body.setWrapText(true);
        VBox box = new VBox(12, title, body);
        box.setPadding(new Insets(24));
        stage.setScene(new Scene(box, 560, 260));
        stage.setTitle("Shardlight Client");
        stage.show();
    }
}

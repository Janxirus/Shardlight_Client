package dev.shardlight.client.app.ui;

import dev.shardlight.client.app.AppContext;
import dev.shardlight.client.app.flows.PlayFlow;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.project.Project;
import javafx.scene.control.Button;

/** The Play button of a project: Play, Cancel while it gets ready, Stop while it runs. Click does the right thing. */
public final class PlayButton {
    private PlayButton() {
    }

    public static Button of(AppContext ctx, Project p, String... styleClasses) {
        PlayFlow.Phase phase = ctx.play().state(p.id).phase();
        boolean running = phase == PlayFlow.Phase.RUNNING;
        boolean starting = phase == PlayFlow.Phase.PREPARING || phase == PlayFlow.Phase.DOWNLOADING
            || phase == PlayFlow.Phase.LAUNCHING;
        String label = running ? I18n.tr("ui.play.stop") : starting ? I18n.tr("common.cancel") : I18n.tr("ui.play.play");
        String icon = running ? Icons.STOP : starting ? Icons.CLOSE : Icons.PLAY;
        Button b = UiKit.button(label, icon, () -> ctx.play().play(p), styleClasses);
        if (!b.getStyleClass().contains("primary")) {
            b.getStyleClass().add("primary");
        }
        b.setAccessibleText(I18n.tr(running ? "ui.play.stopAccessible" : "ui.play.playAccessible", p.name));
        return b;
    }
}

package dev.shardlight.client.minecraft.model;

import java.util.List;

/** {@code arguments} of a modern (1.13+) version: game and JVM argument lists. */
public record Arguments(List<Argument> game, List<Argument> jvm) {
    public Arguments {
        game = ModelCopies.list(game);
        jvm = ModelCopies.list(jvm);
    }
}

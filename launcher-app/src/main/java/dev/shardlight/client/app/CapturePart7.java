package dev.shardlight.client.app;

import dev.shardlight.client.account.Account;
import dev.shardlight.client.app.pages.SettingsPage;
import dev.shardlight.client.app.pages.SkinsSection;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.skin.SkinService;
import dev.shardlight.client.skin.SkinVariant;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * --capture only: the Part 7 views (skins, the Discord switch, the language picker). The account, its capes and the
 * skins are made up here; no Minecraft service is asked.
 */
final class CapturePart7 {
    private static final Logger LOG = Logger.getLogger(CapturePart7.class.getName());
    static final List<String> VIEWS = List.of("skins", "skins-back", "skins-slim", "skins-signedout", "skins-unavailable", "discord-setup", "discord", "language");
    private static final String TEX = "http://textures.minecraft.net/texture/";

    private CapturePart7() {
    }

    static boolean show(AppContext ctx, String id) {
        seedLibrary(ctx);
        ctx.shell().navigate("settings");
        if (!(ctx.shell().current() instanceof SettingsPage sp)) {
            return true;
        }
        switch (id) {
            case "skins" -> sp.previewSkins(SkinsSection.buildWith(ctx, account(), wardrobe(), null, SampleSkins.skin(SampleSkins.WANDERER,
                SkinVariant.CLASSIC), "Wanderer", SkinVariant.CLASSIC, SampleSkins.cape(), 28));
            case "skins-back" -> sp.previewSkins(SkinsSection.buildWith(ctx, account(), wardrobe(), null, SampleSkins.skin(SampleSkins.WANDERER,
                SkinVariant.CLASSIC), "Wanderer", SkinVariant.CLASSIC, SampleSkins.cape(), 200));
            case "skins-slim" -> sp.previewSkins(SkinsSection.buildWith(ctx, account(), wardrobe(), null, SampleSkins.skin(SampleSkins.EMBER,
                SkinVariant.SLIM), "Ember Knight", SkinVariant.SLIM, null, -35));
            case "skins-signedout" -> sp.open("accounts");
            case "skins-unavailable" -> sp.previewSkins(SkinsSection.buildWith(ctx, account(), null,
                "Minecraft's servers answered with an error (HTTP 503). Try again in a few minutes.", SampleSkins.skin(SampleSkins.RANGER,
                SkinVariant.CLASSIC), "Moss Ranger", SkinVariant.CLASSIC, null, 28));
            case "discord-setup" -> sp.open("discord");
            case "discord" -> {
                // an example application id and the switch on, so the enabled state and the status text show
                try {
                    ctx.services().settings().update(st -> {
                        st.privacy.discordApplicationId = "123456789012345678";
                        st.privacy.discordRichPresence = true;
                    });
                } catch (LauncherException e) {
                    LOG.log(Level.WARNING, "Couldn't set the capture Discord id", e);
                }
                sp.open("discord");
            }
            case "language" -> sp.open("appearance");
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void seedLibrary(AppContext ctx) {
        try {
            var lib = ctx.services().skinLibrary();
            if (lib.list().isEmpty()) {
                lib.add("Moss Ranger", SkinVariant.CLASSIC, SampleSkins.skin(SampleSkins.RANGER, SkinVariant.CLASSIC));
                lib.add("Ember Knight", SkinVariant.SLIM, SampleSkins.skin(SampleSkins.EMBER, SkinVariant.SLIM));
                lib.add("Wanderer", SkinVariant.CLASSIC, SampleSkins.skin(SampleSkins.WANDERER, SkinVariant.CLASSIC));
            }
        } catch (LauncherException e) {
            LOG.log(Level.WARNING, "Couldn't seed the capture skin library", e);
        }
    }

    private static Account account() {
        Account a = new Account();
        a.id = "0123456789abcdef0123456789abcdef";
        a.username = "Wanderer";
        return a;
    }

    private static SkinService.Wardrobe wardrobe() {
        return new SkinService.Wardrobe(
            List.of(new SkinService.Skin("s1", TEX + "aaa", SkinVariant.CLASSIC, true)),
            List.of(new SkinService.Cape("c1", TEX + "ccc", "Shard", true), new SkinService.Cape("c2", TEX + "ddd", "Migrator", false)));
    }
}

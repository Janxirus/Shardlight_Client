package dev.shardlight.client.app;

import dev.shardlight.client.diagnostics.Finding;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.launch.GameExit;
import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import dev.shardlight.client.nbt.NbtCompound;
import dev.shardlight.client.nbt.NbtIo;
import dev.shardlight.client.preset.Preset;
import dev.shardlight.client.project.BackupScope;
import dev.shardlight.client.project.JavaSettings;
import dev.shardlight.client.project.LibraryView;
import dev.shardlight.client.project.NewProjectRequest;
import dev.shardlight.client.project.Project;
import dev.shardlight.client.project.ServerEntry;
import dev.shardlight.client.services.LauncherServices;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * --capture only: when the (throw-away) capture data folder has no projects, create example projects with a
 * few local mods, a resource pack, worlds, screenshots, servers, a backup and a log, so the screenshots show
 * filled pages. Everything is made up locally; nothing is downloaded or installed.
 */
final class CaptureSeed {
    private static final Logger LOG = Logger.getLogger(CaptureSeed.class.getName());
    private static final long DAY = 24L * 60 * 60 * 1000;

    private CaptureSeed() {
    }

    static void seed(AppContext ctx) {
        var projects = ctx.services().projects();
        if (!projects.list().isEmpty()) {
            return;
        }
        try {
            projects.create(new NewProjectRequest("Vanilla 26.3", "builtin:grass", "vanilla", "26.3",
                LoaderSpec.vanilla(), Set.of(), null, Preset.BALANCED, new JavaSettings()));
            projects.create(new NewProjectRequest("Map only", "builtin:world", "exploration", "26.3",
                new LoaderSpec(LoaderType.FABRIC, "0.19.5"), Set.of("map"), null, Preset.ULTRA_PERFORMANCE, new JavaSettings()));
            Project p = projects.create(new NewProjectRequest("Shardlight Survival", "builtin:shard", "shardlight-default",
                "26.3", new LoaderSpec(LoaderType.FABRIC, "0.19.5"), Set.of("ui", "map", "party", "companion"),
                "EMBER", Preset.BALANCED, new JavaSettings()));
            fill(ctx, p);
            ctx.selectProject(p.id);
            List<String> ids = projects.list().stream().map(x -> x.id).toList();
            ctx.services().settings().update(st -> {
                var folder = LibraryView.addGroup(st.library, "Testing");
                projects.list().stream().filter(x -> x.name.equals("Map only")).findFirst()
                    .ifPresent(x -> LibraryView.move(st.library, ids, x.id, folder.id, null));
            });
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Couldn't create the capture example projects", e);
        }
    }

    private static void fill(AppContext ctx, Project p) throws Exception {
        var services = ctx.services();
        Path game = services.projects().gameDir(p);
        Path mods = game.resolve(GameDirs.MODS);
        Files.createDirectories(mods);
        Files.write(mods.resolve("fabric-api-0.130.0+26.3.jar"), fabricMod("fabric-api", "Fabric API", "0.130.0+26.3"));
        Files.write(mods.resolve("sodium-fabric-0.7.0+mc26.3.jar"), fabricMod("sodium", "Sodium", "0.7.0+mc26.3"));
        Files.write(mods.resolve("lithium-fabric-0.17.0+mc26.3.jar"), fabricMod("lithium", "Lithium", "0.17.0+mc26.3"));
        Files.write(mods.resolve("zoomify-2.15.jar.disabled"), fabricMod("zoomify", "Zoomify", "2.15"));
        Path packs = game.resolve(GameDirs.RESOURCE_PACKS);
        Files.createDirectories(packs);
        Files.write(packs.resolve("Faithful 32x.zip"), zip(Map.of("pack.mcmeta",
            "{\"pack\":{\"pack_format\":75,\"description\":\"Faithful 32x\"}}")));
        services.content().scan(p);

        Path saves = game.resolve(GameDirs.SAVES);
        world(saves, "New World", "Survival island", System.currentTimeMillis() - DAY);
        world(saves, "Creative build", "Creative build", System.currentTimeMillis() - 6 * DAY);

        Path shots = game.resolve(GameDirs.SCREENSHOTS);
        Files.createDirectories(shots);
        Color[][] colours = {
            {new Color(0x2B5876), new Color(0x4E4376)}, {new Color(0xF0932B), new Color(0x6AB04C)},
            {new Color(0x0F2027), new Color(0x2C5364)}, {new Color(0xEB4D4B), new Color(0x130F40)},
        };
        for (int i = 0; i < colours.length; i++) {
            Path f = shots.resolve("2026-10-0" + (i + 1) + "_20.1" + i + ".2" + i + ".png");
            Files.write(f, image(colours[i][0], colours[i][1]));
            Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - (i + 1) * DAY));
        }

        services.servers().save(p, List.of(
            new ServerEntry("Shardlight test server", "play.example.net", null, null, true),
            new ServerEntry("Friends SMP", "friends.example.org:25570", null, Boolean.TRUE, false),
            new ServerEntry("Local world", "localhost", null, null, false)));

        Path logs = game.resolve(GameDirs.LOGS);
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("latest.log"), String.join("\n",
            "[20:14:02] [main/INFO]: Loading Minecraft 26.3 with Fabric Loader 0.19.5",
            "[20:14:02] [main/INFO]: Loading 6 mods:",
            "\t- fabric-api 0.130.0+26.3",
            "\t- lithium 0.17.0+mc26.3",
            "\t- softglass 4.1.0 (Shardlight UI)",
            "\t- sodium 0.7.0+mc26.3",
            "[20:14:04] [Render thread/INFO]: Setting user: Player",
            "[20:14:06] [Render thread/INFO]: Backend library: LWJGL version 3.4.3",
            "[20:14:09] [Render thread/INFO]: Reloading ResourceManager: vanilla, fabric, file/Faithful 32x.zip",
            "[20:14:12] [Render thread/INFO]: Sound engine started",
            "[20:14:13] [Render thread/WARN]: Mod 'zoomify' is disabled and was skipped",
            "[20:21:05] [Render thread/ERROR]: Failed to load a texture for minecraft:textures/gui/sprites/hud/missing.png",
            "java.io.FileNotFoundException: minecraft:textures/gui/sprites/hud/missing.png",
            "\tat net.minecraft.client.renderer.texture.SimpleTexture.load(SimpleTexture.java:44)",
            "[20:21:06] [Render thread/DEBUG]: Using the fallback missing texture",
            "[20:31:40] [Render thread/INFO]: Stopping!", ""), StandardCharsets.UTF_8);
        Path crashes = Files.createDirectories(game.resolve("crash-reports"));
        Files.writeString(crashes.resolve("crash-2026-10-12_20.31.40-client.txt"), String.join("\n",
            "---- Minecraft Crash Report ----",
            "Description: Initializing game",
            "net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!",
            " - Mod 'Sodium Extra' (sodium-extra) 0.7.0 requires version 0.7.0 or later of mod 'Sodium' (sodium), which is missing!",
            " - Mod 'Old Zoom' (oldzoom) 1.2 requires version 1.21.4 of 'Minecraft' (minecraft), but only the wrong version is present: 26.3!",
            ""), StandardCharsets.UTF_8);

        services.backups().backupProject(p.id, "Before trying shaders", BackupScope.EVERYTHING, ProgressListener.NONE,
            CancellationToken.NONE);

        Project played = services.projects().get(p.id).orElse(p);
        played.lastPlayedAt = System.currentTimeMillis() - 3 * 60 * 60 * 1000;
        played.totalPlayMillis = 37L * 60 * 60 * 1000 + 25 * 60 * 1000;
        played.notes = "Base at 120 64 -340. Next: finish the iron farm.";
        services.projects().save(played);
    }

    /** a crash as the crash dialog shows it, for the screenshots */
    static GameExit exampleCrash(LauncherServices services) {
        List<String> output = List.of("[main/INFO]: Loading Minecraft 26.3 with Fabric Loader 0.19.5",
            "net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!",
            " - Mod 'Sodium Extra' (sodium-extra) 0.7.0 requires version 0.7.0 or later of mod 'Sodium' (sodium), which is missing!",
            " - Mod 'Old Zoom' (oldzoom) 1.2 requires version 1.21.4 of 'Minecraft' (minecraft), but only the wrong version is present: 26.3!");
        List<Finding> findings = services.crashAnalyzer().analyze(1, null, output);
        return new GameExit(1, true, false, Optional.empty(), Duration.ofSeconds(14), findings);
    }

    private static void world(Path saves, String folder, String name, long lastPlayed) throws IOException {
        Path w = Files.createDirectories(saves.resolve(folder));
        NbtCompound data = new NbtCompound();
        data.putInt("DataVersion", 4556);
        data.putString("LevelName", name);
        data.putLong("LastPlayed", lastPlayed);
        data.putInt("GameType", folder.startsWith("Creative") ? 1 : 0);
        data.putBoolean("hardcore", false);
        data.putBoolean("allowCommands", folder.startsWith("Creative"));
        NbtCompound version = new NbtCompound();
        version.putInt("Id", 4556);
        version.putString("Name", "26.3");
        version.putString("Series", "main");
        version.putBoolean("Snapshot", false);
        data.put("Version", version);
        NbtCompound root = new NbtCompound();
        root.put("Data", data);
        Files.write(w.resolve("level.dat"), NbtIo.toBytes(root, true));
        Files.createDirectories(w.resolve("region"));
        Files.write(w.resolve("region/r.0.0.mca"), new byte[64 * 1024]);
        Files.write(w.resolve("icon.png"), image(new Color(0x3C8D2F), new Color(0x7FB3D5)));
    }

    private static byte[] fabricMod(String id, String name, String version) {
        String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"name\":\"" + name
            + "\",\"description\":\"Example mod for the screenshots\",\"environment\":\"*\",\"depends\":{\"minecraft\":\"~26.3\"}}";
        return zip(Map.of("fabric.mod.json", json));
    }

    private static byte[] zip(Map<String, String> entries) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> e : new LinkedHashMap<>(entries).entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    private static byte[] image(Color from, Color to) throws IOException {
        BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, from, 320, 180, to));
        g.fillRect(0, 0, 320, 180);
        g.setColor(new Color(255, 255, 255, 60));
        for (int x = 0; x < 320; x += 40) {
            g.fillRect(x, 120 + (x % 80 == 0 ? 10 : 0), 30, 60);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}

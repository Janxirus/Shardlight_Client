package dev.shardlight.client.content;

import dev.shardlight.client.loader.LoaderSpec;
import dev.shardlight.client.loader.LoaderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How each kind of dropped file is routed, and how unknown ones are refused. */
class DropRouterTest {
    @TempDir
    Path dir;

    Path zip(String name, String... namesAndContents) throws IOException {
        Path f = dir.resolve(name);
        try (OutputStream os = Files.newOutputStream(f); ZipOutputStream zo = new ZipOutputStream(os)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zo.putNextEntry(new ZipEntry(namesAndContents[i]));
                zo.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zo.closeEntry();
            }
        }
        return f;
    }

    DropItem route(Path f) {
        return DropRouter.classify(f);
    }

    @Test
    void modJarsGoToModsWhateverTheLoader() throws Exception {
        DropItem fabric = route(zip("a.jar", "fabric.mod.json", "{}"));
        assertEquals(DropKind.MOD, fabric.kind());
        assertEquals("mods", fabric.kind().folder());
        assertEquals(Set.of(LoaderType.FABRIC), fabric.loaders());
        assertEquals(Set.of(LoaderType.QUILT), route(zip("q.jar", "quilt.mod.json", "{}")).loaders());
        assertEquals(Set.of(LoaderType.FORGE), route(zip("f.jar", "META-INF/mods.toml", "x")).loaders());
        assertEquals(Set.of(LoaderType.NEOFORGE), route(zip("n.jar", "META-INF/neoforge.mods.toml", "x")).loaders());
        assertEquals(Set.of(LoaderType.FABRIC, LoaderType.NEOFORGE),
            route(zip("both.jar", "fabric.mod.json", "{}", "META-INF/neoforge.mods.toml", "x")).loaders());
    }

    @Test
    void aJarWithoutAModDescriptorIsRefused() throws Exception {
        DropItem lib = route(zip("lib.jar", "com/example/Thing.class", "x"));
        assertEquals(DropKind.UNKNOWN, lib.kind());
        assertEquals(DropRouter.NOT_A_MOD, lib.reason());
        assertFalse(lib.kind().accepted());
        Path broken = Files.writeString(dir.resolve("broken.jar"), "not a zip at all");
        assertEquals(DropRouter.NOT_AN_ARCHIVE, route(broken).reason());
    }

    @Test
    void theLoaderWarningOnlyAppearsWhenThereIsAMismatch() throws Exception {
        DropItem fabric = route(zip("a.jar", "fabric.mod.json", "{}"));
        assertFalse(fabric.wrongLoaderFor(new LoaderSpec(LoaderType.FABRIC, "0.19.5")));
        assertTrue(fabric.wrongLoaderFor(new LoaderSpec(LoaderType.NEOFORGE, "21.0")));
        assertTrue(fabric.wrongLoaderFor(LoaderSpec.vanilla()), "a mod in a vanilla project can't load");
        DropItem both = route(zip("b.jar", "fabric.mod.json", "{}", "META-INF/neoforge.mods.toml", "x"));
        assertFalse(both.wrongLoaderFor(new LoaderSpec(LoaderType.NEOFORGE, "21.0")));
        assertFalse(route(zip("pack.zip", "pack.mcmeta", "{}")).wrongLoaderFor(LoaderSpec.vanilla()), "only mods care");
    }

    @Test
    void zipsAreToldApartByWhatTheyHold() throws Exception {
        DropItem resource = route(zip("res.zip", "pack.mcmeta", "{}", "assets/minecraft/lang/en_us.json", "{}"));
        assertEquals(DropKind.RESOURCE_PACK, resource.kind());
        assertEquals("resourcepacks", resource.kind().folder());
        assertEquals(DropKind.RESOURCE_PACK, route(zip("bare.zip", "pack.mcmeta", "{}")).kind());

        DropItem shader = route(zip("shader.zip", "shaders/composite.fsh", "x", "shaders/composite.vsh", "x"));
        assertEquals(DropKind.SHADER_PACK, shader.kind());
        assertEquals("shaderpacks", shader.kind().folder());

        DropItem world = route(zip("world.zip", "level.dat", "x", "region/r.0.0.mca", "x"));
        assertEquals(DropKind.WORLD, world.kind());
        assertEquals("saves", world.kind().folder());
        assertEquals(DropKind.WORLD, route(zip("wrapped.zip", "My World/level.dat", "x", "My World/region/r.0.0.mca", "x")).kind(),
            "a world inside one folder is still a world");
        assertEquals(DropKind.WORLD, route(zip("mac.zip", "__MACOSX/._x", "x", "My World/level.dat", "x")).kind(), "macOS junk is ignored");
    }

    @Test
    void modpacksAndProjectExportsMakeNewProjects() throws Exception {
        DropItem mr = route(zip("pack.mrpack", "modrinth.index.json", "{}"));
        assertEquals(DropKind.MODPACK, mr.kind());
        assertTrue(mr.kind().makesProject());
        assertFalse(mr.kind().intoProject());
        assertEquals(DropKind.MODPACK, route(zip("renamed.zip", "modrinth.index.json", "{}")).kind(), "the name doesn't matter");
        DropItem cf = route(zip("cf.zip", "manifest.json", "{\"manifestType\":\"minecraftModpack\"}", "overrides/config/a.toml", "x"));
        assertEquals(DropKind.MODPACK, cf.kind());
        assertEquals(DropKind.PROJECT_EXPORT, route(zip("mine.shardlight", "shardlight-project.json", "{}")).kind());
        assertEquals(DropRouter.BAD_MODPACK, route(zip("empty.mrpack", "readme.txt", "x")).reason(), "an .mrpack without an index");
    }

    @Test
    void picturesAreCheckedByTheirFirstBytes() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0};
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
        DropItem p = route(Files.write(dir.resolve("shot.png"), png));
        assertEquals(DropKind.SCREENSHOT, p.kind());
        assertEquals("screenshots", p.kind().folder());
        assertEquals(DropKind.SCREENSHOT, route(Files.write(dir.resolve("shot.JPG"), jpg)).kind());
        assertEquals(DropKind.SCREENSHOT, route(Files.write(dir.resolve("shot.jpeg"), jpg)).kind());
        assertEquals(DropRouter.BAD_IMAGE, route(Files.writeString(dir.resolve("fake.png"), "I am text")).reason());
    }

    @Test
    void unknownThingsAreRefusedWithAReason() throws Exception {
        assertEquals(DropRouter.UNKNOWN_TYPE, route(Files.writeString(dir.resolve("notes.txt"), "x")).reason());
        assertEquals(DropRouter.UNKNOWN_TYPE, route(Files.writeString(dir.resolve("setup.exe"), "x")).reason());
        assertEquals(DropRouter.UNKNOWN_ZIP, route(zip("stuff.zip", "readme.txt", "x")).reason());
        assertEquals(DropRouter.NOT_AN_ARCHIVE, route(Files.writeString(dir.resolve("bad.zip"), "x")).reason());
        assertEquals(DropRouter.NESTED, route(zip("nested.zip", "Pack/pack.mcmeta", "{}")).reason(),
            "Minecraft needs pack.mcmeta at the top of the zip");
        DropItem data = route(zip("data.zip", "pack.mcmeta", "{}", "data/minecraft/recipes/a.json", "{}"));
        assertEquals(DropKind.DATA_PACK, data.kind());
        assertEquals(DropRouter.DATA_PACK, data.reason());
        assertFalse(data.kind().accepted(), "a data pack belongs inside a world");
        assertEquals(DropRouter.MISSING, route(dir.resolve("gone.jar")).reason());
        assertEquals(DropRouter.FOLDER, route(Files.createDirectory(dir.resolve("some-folder"))).reason());
    }

    @Test
    void aWorldFolderIsAWorld() throws Exception {
        Path w = Files.createDirectories(dir.resolve("My World"));
        Files.writeString(w.resolve("level.dat"), "x");
        DropItem item = route(w);
        assertEquals(DropKind.WORLD, item.kind());
        assertEquals("My World", item.fileName());
    }

    @Test
    void severalFilesAreEachRoutedOnTheirOwn() throws Exception {
        var items = DropRouter.classify(java.util.List.of(zip("a.jar", "fabric.mod.json", "{}"), Files.writeString(dir.resolve("x.txt"), "x"),
            zip("w.zip", "level.dat", "x")));
        assertEquals(java.util.List.of(DropKind.MOD, DropKind.UNKNOWN, DropKind.WORLD), items.stream().map(DropItem::kind).toList());
    }
}

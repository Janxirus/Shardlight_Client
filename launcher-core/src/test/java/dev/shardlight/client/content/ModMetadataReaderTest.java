package dev.shardlight.client.content;

import dev.shardlight.client.loader.LoaderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModMetadataReaderTest {
    @TempDir
    Path dir;
    final ModMetadataReader reader = new ModMetadataReader();

    @Test
    void readsFabricModJson() throws Exception {
        String json = """
            {
              "schemaVersion": 1,
              "id": "sodium",
              "version": "0.6.3+mc26.3",
              "name": "Sodium",
              "description": "Fast rendering",
              "environment": "client",
              "icon": {"16": "icon16.png", "128": "assets/sodium/icon.png"},
              "depends": {"minecraft": ["~26.3", "26.4"], "fabricloader": ">=0.15.0"},
              "recommends": {"modmenu": "*"},
              "suggests": {"iris": "*"},
              "breaks": {"optifabric": "*"},
              "conflicts": {"embeddium": "*"}
            }""";
        Path jar = TestJars.zip(dir.resolve("sodium.jar"), Map.of("fabric.mod.json", json));
        ModMetadata m = reader.read(jar).orElseThrow();
        assertEquals("sodium", m.id());
        assertEquals("Sodium", m.name());
        assertEquals("0.6.3+mc26.3", m.version());
        assertEquals(Set.of(LoaderType.FABRIC), m.loaders());
        assertEquals("client", m.environment());
        assertEquals("~26.3 || 26.4", m.depends().get("minecraft"));
        assertEquals(">=0.15.0", m.depends().get("fabricloader"));
        assertEquals("*", m.breaks().get("optifabric"));
        assertEquals("*", m.recommends().get("modmenu"));
        assertEquals("*", m.suggests().get("iris"));
        assertEquals("*", m.conflicts().get("embeddium"));
        assertEquals("assets/sodium/icon.png", m.icon());
        assertEquals("Fast rendering", m.description());
        assertEquals(ContentKind.MOD, m.kind());
        assertTrue(m.isMod());
    }

    @Test
    void fabricWithoutEnvironmentMeansBoth() throws Exception {
        Path jar = TestJars.zip(dir.resolve("a.jar"), Map.of("fabric.mod.json", "{\"id\":\"a\",\"version\":\"1\"}"));
        ModMetadata m = reader.read(jar).orElseThrow();
        assertEquals("*", m.environment());
        assertEquals("a", m.name());
    }

    @Test
    void readsQuiltModJsonWithObjectAndStringDependencies() throws Exception {
        String json = """
            {
              "schema_version": 1,
              "quilt_loader": {
                "group": "org.example",
                "id": "example_mod",
                "version": "2.1.0",
                "metadata": {"name": "Example Mod", "description": "Quilt mod", "icon": "assets/example/icon.png"},
                "depends": [
                  "quilt_loader",
                  {"id": "minecraft", "versions": ">=1.21"},
                  {"id": "quilted_fabric_api", "versions": ["7.0.0", "7.1.0"]},
                  {"id": "modmenu", "versions": "*", "optional": true}
                ],
                "breaks": [{"id": "badmod", "versions": "<2"}]
              },
              "minecraft": {"environment": "dedicated_server"}
            }""";
        Path jar = TestJars.zip(dir.resolve("q.jar"), Map.of("quilt.mod.json", json));
        ModMetadata m = reader.read(jar).orElseThrow();
        assertEquals("example_mod", m.id());
        assertEquals("Example Mod", m.name());
        assertEquals("2.1.0", m.version());
        assertEquals(Set.of(LoaderType.QUILT), m.loaders());
        assertEquals("server", m.environment());
        assertEquals("*", m.depends().get("quilt_loader"));
        assertEquals(">=1.21", m.depends().get("minecraft"));
        assertEquals("7.0.0 || 7.1.0", m.depends().get("quilted_fabric_api"));
        assertEquals("*", m.recommends().get("modmenu"));
        assertTrue(!m.depends().containsKey("modmenu"));
        assertEquals("<2", m.breaks().get("badmod"));
        assertTrue(m.isServerOnly());
    }

    @Test
    void readsNeoForgeTomlWithJarVersionFromManifest() throws Exception {
        String toml = """
            modLoader = "javafml" # the loader
            loaderVersion = "[4,)"
            license = 'All rights reserved'
            [[mods]]
            modId = "examplemod"
            version = "${file.jarVersion}"
            displayName = "Example Mod"
            logoFile = "logo.png"
            description = '''
            A multi-line
            description with "quotes".
            '''
            [[dependencies.examplemod]]
                modId = "neoforge"
                type = "required"
                versionRange = "[21.1,)"
                ordering = "NONE"
                side = "BOTH"
            [[dependencies.examplemod]]
                modId = "minecraft"
                type = "required"
                versionRange = "[1.21.1,1.22)"
            [[dependencies.examplemod]]
                modId = "badmod"
                type = "incompatible"
                versionRange = "*"
            [[dependencies.examplemod]]
                modId = "jei"
                type = "optional"
            """;
        Path jar = dir.resolve("neo.jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "3.4.5");
        try (JarOutputStream jo = new JarOutputStream(Files.newOutputStream(jar), mf)) {
            jo.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            jo.write(toml.getBytes());
            jo.closeEntry();
        }
        ModMetadata m = reader.read(jar).orElseThrow();
        assertEquals("examplemod", m.id());
        assertEquals("Example Mod", m.name());
        assertEquals("3.4.5", m.version());
        assertEquals(Set.of(LoaderType.NEOFORGE), m.loaders());
        assertEquals("[1.21.1,1.22)", m.depends().get("minecraft"));
        assertEquals("[21.1,)", m.depends().get("neoforge"));
        assertEquals("*", m.breaks().get("badmod"));
        assertEquals("*", m.recommends().get("jei"));
        assertEquals("A multi-line\ndescription with \"quotes\".", m.description());
        assertEquals("logo.png", m.icon());
    }

    @Test
    void readsForgeModsToml() throws Exception {
        Path jar = TestJars.zip(dir.resolve("forge.jar"), Map.of("META-INF/mods.toml", """
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"
            [[mods]] #mandatory
            modId="jei"
            version="15.2.0.27"
            displayName="Just Enough Items"
            [[dependencies.jei]]
                modId="forge"
                mandatory=true
                versionRange="[47.1.3,)"
                ordering="NONE"
                side="BOTH"
            [[dependencies.jei]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.1,1.20.2)"
            [[dependencies.jei]]
                modId="optional_thing"
                mandatory=false
                versionRange="*"
            """));
        ModMetadata m = reader.read(jar).orElseThrow();
        assertEquals("jei", m.id());
        assertEquals("Just Enough Items", m.name());
        assertEquals(Set.of(LoaderType.FORGE), m.loaders());
        assertEquals("[1.20.1,1.20.2)", m.minecraftPredicate());
        assertEquals("*", m.recommends().get("optional_thing"));
        assertNull(m.environment());
    }

    @Test
    void jarVersionWithoutManifestIsUnknown() throws Exception {
        Path jar = TestJars.zip(dir.resolve("noversion.jar"), Map.of("META-INF/mods.toml",
            "[[mods]]\nmodId=\"x\"\nversion=\"${file.jarVersion}\"\n"));
        ModMetadata m = reader.read(jar).orElseThrow();
        assertNull(m.version());
    }

    @Test
    void multiLoaderJarReportsEveryLoader() throws Exception {
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("fabric.mod.json", "{\"id\":\"both\",\"version\":\"1.0\"}");
        entries.put("META-INF/neoforge.mods.toml", "[[mods]]\nmodId=\"both\"\nversion=\"1.0\"\n");
        Path jar = TestJars.zip(dir.resolve("both.jar"), entries);
        ModMetadata m = reader.read(jar).orElseThrow();
        assertEquals("both", m.id());
        assertEquals(Set.of(LoaderType.FABRIC, LoaderType.NEOFORGE), m.loaders());
    }

    @Test
    void readsResourcePackWithStringDescription() throws Exception {
        Path zip = TestJars.zip(dir.resolve("Faithful 32x.zip"), Map.of(
            "pack.mcmeta", "{\"pack\":{\"pack_format\":46,\"description\":\"\u00a7aGreen \u00a7rtext\",\"supported_formats\":[42,46]}}",
            "assets/minecraft/x.png", "x"));
        ModMetadata m = reader.read(zip).orElseThrow();
        assertEquals(ContentKind.RESOURCE_PACK, m.kind());
        assertEquals("Faithful 32x", m.name());
        assertEquals("Green text", m.description());
        assertEquals(46, m.packFormat());
        assertEquals(42, m.minPackFormat());
        assertEquals(46, m.maxPackFormat());
        assertNull(m.id());
    }

    @Test
    void readsTextComponentDescriptionsAndRangeObjects() throws Exception {
        Path zip = TestJars.zip(dir.resolve("comp.zip"), Map.of("pack.mcmeta", """
            {"pack": {"pack_format": 34,
              "description": [{"text": "Hello ", "color": "gold"}, {"text": "World", "extra": [{"text": "!"}]}, " end"],
              "supported_formats": {"min_inclusive": 32, "max_inclusive": 48}}}
            """));
        ModMetadata m = reader.read(zip).orElseThrow();
        assertEquals("Hello World! end", m.description());
        assertEquals(34, m.packFormat());
        assertEquals(32, m.minPackFormat());
        assertEquals(48, m.maxPackFormat());

        Path obj = TestJars.zip(dir.resolve("obj.zip"), Map.of("pack.mcmeta",
            "{\"pack\":{\"pack_format\":15,\"description\":{\"translate\":\"pack.x\",\"fallback\":\"Fallback text\"}}}"));
        assertEquals("Fallback text", reader.read(obj).orElseThrow().description());
    }

    @Test
    void readsNewMinMaxFormats() throws Exception {
        Path zip = TestJars.zip(dir.resolve("new.zip"), Map.of("pack.mcmeta",
            "{\"pack\":{\"description\":\"n\",\"min_format\":[65,0],\"max_format\":[69,1]}}", "assets/a", "a"));
        ModMetadata m = reader.read(zip).orElseThrow();
        assertEquals(69, m.packFormat());
        assertEquals(65, m.minPackFormat());
        assertEquals(69, m.maxPackFormat());
    }

    @Test
    void dataOnlyPackIsADatapack() throws Exception {
        Path zip = TestJars.zip(dir.resolve("dp.zip"), Map.of("pack.mcmeta", "{\"pack\":{\"pack_format\":48,\"description\":\"d\"}}",
            "data/example/function/a.mcfunction", "say hi"));
        assertEquals(ContentKind.DATAPACK, reader.read(zip).orElseThrow().kind());
    }

    @Test
    void readsShaderPackZipAndFolders() throws Exception {
        Path zip = dir.resolve("BSL_v8.zip");
        Files.write(zip, TestJars.shaderPack());
        ModMetadata m = reader.read(zip).orElseThrow();
        assertEquals(ContentKind.SHADER_PACK, m.kind());
        assertEquals("BSL_v8", m.name());

        Path folder = dir.resolve("MyShader");
        Files.createDirectories(folder.resolve("shaders"));
        assertEquals(ContentKind.SHADER_PACK, reader.read(folder).orElseThrow().kind());

        Path packFolder = dir.resolve("FolderPack");
        Files.createDirectories(packFolder.resolve("assets"));
        Files.writeString(packFolder.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":46,\"description\":\"folder\"}}");
        ModMetadata pm = reader.read(packFolder).orElseThrow();
        assertEquals(ContentKind.RESOURCE_PACK, pm.kind());
        assertEquals("folder", pm.description());
    }

    @Test
    void disabledJarStillReads() throws Exception {
        Path jar = dir.resolve("a.jar.disabled");
        Files.write(jar, TestJars.fabricMod("amod", "1.0"));
        assertEquals("amod", reader.read(jar).orElseThrow().id());
    }

    @Test
    void malformedFilesGiveEmpty() throws Exception {
        assertEquals(Optional.empty(), reader.read(TestJars.zip(dir.resolve("bad1.jar"), Map.of("fabric.mod.json", "{not json"))));
        assertEquals(Optional.empty(), reader.read(TestJars.zip(dir.resolve("bad2.jar"), Map.of("fabric.mod.json", "{\"version\":\"1\"}"))));
        assertEquals(Optional.empty(), reader.read(TestJars.zip(dir.resolve("bad3.jar"), Map.of("fabric.mod.json", "[1,2]"))));
        assertEquals(Optional.empty(), reader.read(TestJars.zip(dir.resolve("bad4.jar"), Map.of("META-INF/mods.toml", "[[mods]\nmodId=x"))));
        assertEquals(Optional.empty(), reader.read(TestJars.zip(dir.resolve("bad5.jar"), Map.of("quilt.mod.json", "{\"quilt_loader\":{}}"))));
        assertEquals(Optional.empty(), reader.read(TestJars.zip(dir.resolve("bad6.zip"), Map.of("pack.mcmeta", "{\"nopack\":1}"))));
        assertEquals(Optional.empty(), reader.read(TestJars.zip(dir.resolve("empty.zip"), Map.of("readme.txt", "hi"))));
        Path notZip = dir.resolve("text.jar");
        Files.writeString(notZip, "this is not a zip");
        assertEquals(Optional.empty(), reader.read(notZip));
        assertEquals(Optional.empty(), reader.read(dir.resolve("missing.jar")));
        assertEquals(Optional.empty(), reader.read(null));
    }

    @Test
    void oversizedMetadataIsRejected() throws Exception {
        String huge = "{\"id\":\"big\",\"version\":\"1\",\"description\":\"" + "x".repeat(ModMetadataReader.MAX_METADATA_BYTES) + "\"}";
        Path jar = TestJars.zip(dir.resolve("big.jar"), Map.of("fabric.mod.json", huge));
        assertEquals(Optional.empty(), reader.read(jar));
    }

    @Test
    void deeplyNestedDescriptionDoesNotOverflow() throws Exception {
        String nested = "{\"text\":\"a\",\"extra\":[".repeat(200) + "\"b\"" + "]}".repeat(200);
        Path zip = TestJars.zip(dir.resolve("deep.zip"), Map.of("pack.mcmeta", "{\"pack\":{\"pack_format\":1,\"description\":" + nested + "}}"));
        // either parsed with a capped depth or rejected; never an exception
        reader.read(zip);
    }
}

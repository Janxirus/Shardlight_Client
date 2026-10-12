package dev.shardlight.client.live;

import com.google.gson.JsonObject;
import dev.shardlight.client.net.Endpoints;
import dev.shardlight.client.preset.VanillaOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code options.txt} key the presets write exists in the real Minecraft 26.3 client. The client jar is
 * downloaded from Mojang and the string constants of its options class (the class that names
 * {@code options.txt} and {@code resourcePacks}) are compared with {@code preset/vanilla-options-26.3.txt}.
 */
@Tag("live")
class VanillaOptionsLiveTest {
    @Test
    void everyPresetKeyIsAnOptionOfTheRealClient() throws Exception {
        String clientUrl = clientUrl();

        byte[] needleA = "options.txt".getBytes(StandardCharsets.UTF_8);
        byte[] needleB = "resourcePacks".getBytes(StandardCharsets.UTF_8);
        Set<String> constants = null;
        String optionsClass = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(LiveHttp.bytes(clientUrl)))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes = zip.readAllBytes();
                if (indexOf(bytes, needleA) >= 0 && indexOf(bytes, needleB) >= 0) {
                    Set<String> c = utf8Constants(bytes);
                    if (c.contains("options.txt") && c.contains("resourcePacks")
                        && (constants == null || e.getName().endsWith("/Options.class"))) {
                        constants = c;
                        optionsClass = e.getName();
                    }
                }
            }
        }
        assertNotNull(constants, "no class in the " + VanillaOptions.VERIFIED_FOR + " client names options.txt");

        List<String> missing = new ArrayList<>();
        for (String key : VanillaOptions.keys()) {
            if (!constants.contains(key)) {
                missing.add(key);
            }
        }
        Set<String> keyLike = new TreeSet<>();
        for (String c : constants) {
            if (c.matches("[a-z][A-Za-z0-9]{1,40}") && !c.equals("true") && !c.equals("false")) {
                keyLike.add(c);
            }
        }
        System.out.println("options class: " + optionsClass);
        System.out.println("key-like string literals in it (" + keyLike.size() + "): " + keyLike);
        assertTrue(missing.isEmpty(), "not options of " + optionsClass + ": " + missing + "; key-like string literals there: " + keyLike);
        assertEquals(12, VanillaOptions.keys().size());
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * The string literals of a class file: the Utf8 constants that a {@code CONSTANT_String} entry points at. Field
     * and method names are also Utf8 constants but are not string literals, so a name like {@code renderDistance}
     * that is only a field name does not count as an option key.
     */
    static Set<String> utf8Constants(byte[] classFile) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
        in.readInt();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        List<Integer> literal = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 8 -> literal.add(in.readUnsignedShort());
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 5, 6 -> {
                    in.skipNBytes(8);
                    i++;
                }
                case 7, 16, 19, 20 -> in.skipNBytes(2);
                case 15 -> in.skipNBytes(3);
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (int index : literal) {
            if (utf8[index] != null) {
                out.add(utf8[index]);
            }
        }
        return out;
    }

    /** prints how the graphics related option values are written, for whoever maintains the preset table */
    @Test
    void printWhatTheGraphicsOptionsLookLike() throws Exception {
        String clientUrl = clientUrl();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(LiveHttp.bytes(clientUrl)))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                String n = e.getName();
                if (n.endsWith(".class") && n.matches("net/minecraft/client/(GraphicsPreset|CloudStatus|ParticleStatus|GraphicsStatus|PrioritizeChunkUpdates|TextureFiltering|AttackIndicatorStatus)[A-Za-z$]*\\.class")) {
                    System.out.println(n + " literals: " + utf8Constants(zip.readAllBytes()));
                }
            }
        }
    }

    private static String clientUrl() throws Exception {
        JsonObject manifest = LiveHttp.json(Endpoints.DEFAULT.versionManifest().toString()).getAsJsonObject();
        String versionUrl = null;
        for (var e : manifest.getAsJsonArray("versions")) {
            JsonObject v = e.getAsJsonObject();
            if (VanillaOptions.VERIFIED_FOR.equals(v.get("id").getAsString())) {
                versionUrl = v.get("url").getAsString();
            }
        }
        assertNotNull(versionUrl, "Mojang's manifest has no " + VanillaOptions.VERIFIED_FOR);
        return LiveHttp.json(versionUrl).getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString();
    }
}

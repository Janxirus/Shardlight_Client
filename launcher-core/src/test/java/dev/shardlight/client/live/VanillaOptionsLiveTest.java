package dev.shardlight.client.live;

import com.google.gson.JsonArray;
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
        JsonObject manifest = LiveHttp.json(Endpoints.DEFAULT.versionManifest().toString()).getAsJsonObject();
        String versionUrl = null;
        JsonArray versions = manifest.getAsJsonArray("versions");
        for (var e : versions) {
            JsonObject v = e.getAsJsonObject();
            if (VanillaOptions.VERIFIED_FOR.equals(v.get("id").getAsString())) {
                versionUrl = v.get("url").getAsString();
            }
        }
        assertNotNull(versionUrl, "Mojang's manifest has no " + VanillaOptions.VERIFIED_FOR);
        String clientUrl = LiveHttp.json(versionUrl).getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject("client")
            .get("url").getAsString();

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
            if (c.matches("[a-z][A-Za-z0-9]{1,40}")) {
                keyLike.add(c);
            }
        }
        System.out.println("options class: " + optionsClass);
        System.out.println("key-like constants in it (" + keyLike.size() + "): " + keyLike);
        assertTrue(missing.isEmpty(), "not options of " + optionsClass + ": " + missing + "; key-like constants there: " + keyLike);
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

    /** the Utf8 entries of a class file's constant pool */
    static Set<String> utf8Constants(byte[] classFile) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
        in.readInt();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        Set<String> out = new LinkedHashSet<>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> out.add(in.readUTF());
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 5, 6 -> {
                    in.skipNBytes(8);
                    i++;
                }
                case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                case 15 -> in.skipNBytes(3);
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        return out;
    }
}

package dev.shardlight.client.shardlight;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reading and writing the Shardlight mods' own JSON config files ({@code config/shardlight.json} and
 * friends) without disturbing them: the file is parsed into a tree, only the requested keys are changed,
 * and every other key keeps its value, order and number formatting. The mods read these files with Gson
 * into objects whose fields start at their defaults, so a file holding only some keys is valid.
 *
 * <p>Callers doing read-modify-write hold {@code AtomicFiles.lockFor(file)} around the sequence.
 */
public final class ModConfigFiles {
    /**
     * Pretty like the mods' own Gson; explicit nulls and NaN/Infinity survive a round trip unchanged instead
     * of being dropped or failing the write.
     */
    private static final Gson PRETTY = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .serializeNulls()
        .serializeSpecialFloatingPointValues()
        .create();
    private static final long MAX_BYTES = 8L << 20;

    private ModConfigFiles() {
    }

    /**
     * The file as a JSON object: an empty object when the file doesn't exist. Throws
     * {@link ErrorCode#INVALID_DATA} ({@code shardlight.config.unreadable}) when it exists but isn't a JSON
     * object, so callers can leave such a file alone rather than overwrite the player's settings.
     */
    public static JsonObject read(Path file) throws LauncherException {
        if (!Files.exists(file)) {
            return new JsonObject();
        }
        try {
            if (Files.size(file) > MAX_BYTES) {
                throw new LauncherException(ErrorCode.INVALID_DATA, "shardlight.config.unreadable", file.getFileName().toString());
            }
            // a strict decoder: a file that isn't UTF-8 is reported instead of silently mangled
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            if (text.isBlank()) {
                return new JsonObject();
            }
            JsonElement e = JsonParser.parseString(text);
            if (!e.isJsonObject()) {
                throw new LauncherException(ErrorCode.INVALID_DATA, "shardlight.config.unreadable", file.getFileName().toString());
            }
            return e.getAsJsonObject();
        } catch (CharacterCodingException | JsonParseException | IllegalStateException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "shardlight.config.unreadable", file.getFileName().toString());
        } catch (IOException e) {
            throw FileOps.wrap(e, file);
        }
    }

    /** write the object atomically, pretty-printed, retrying over short Windows locks */
    public static void write(Path file, JsonObject o) throws LauncherException {
        FileOps.write(file, (PRETTY.toJson(o) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** the JSON text {@link #write} produces (for tests and previews) */
    public static String toText(JsonObject o) {
        return PRETTY.toJson(o) + "\n";
    }
}

package dev.shardlight.client.discord;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * The wire format of Discord's local connection: every message is a 4-byte opcode and a 4-byte length (both little
 * endian) followed by that many bytes of JSON.
 */
final class DiscordFrames {
    static final int HANDSHAKE = 0;
    static final int FRAME = 1;
    static final int CLOSE = 2;
    static final int PING = 3;
    static final int PONG = 4;
    /** Discord never sends more than a few kilobytes; anything near this is a broken stream */
    static final int MAX_LENGTH = 1024 * 1024;

    private DiscordFrames() {
    }

    /** one message read from Discord */
    record Frame(int opcode, String json) {
    }

    static byte[] encode(int opcode, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(opcode).putInt(body.length).put(body);
        return b.array();
    }

    static Frame read(InputStream in) throws IOException {
        byte[] head = in.readNBytes(8);
        if (head.length < 8) {
            throw new EOFException("Discord closed the connection");
        }
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int opcode = h.getInt();
        int length = h.getInt();
        if (length < 0 || length > MAX_LENGTH) {
            throw new IOException("Discord sent a message of " + length + " bytes");
        }
        byte[] body = in.readNBytes(length);
        if (body.length < length) {
            throw new EOFException("Discord closed the connection");
        }
        return new Frame(opcode, new String(body, StandardCharsets.UTF_8));
    }
}

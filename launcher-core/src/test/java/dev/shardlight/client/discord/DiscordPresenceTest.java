package dev.shardlight.client.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class DiscordPresenceTest {
    private static final String APP = "123456789012345678";
    private static final Instant START = Instant.parse("2026-10-13T10:00:00Z");

    /** a pretend Discord: answers the handshake and every command, and remembers what it was sent */
    static final class FakeDiscord implements DiscordTransport.Connector {
        final List<JsonObject> received = new CopyOnWriteArrayList<>();
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        volatile boolean running = true;
        volatile boolean refuseHandshake;
        volatile boolean dropNextCommand;

        @Override
        public DiscordTransport connect() throws IOException {
            if (!running) {
                throw new IOException("Discord is not running");
            }
            connections.incrementAndGet();
            ByteArrayOutputStream toDiscord = new ByteArrayOutputStream();
            return new DiscordTransport() {
                private InputStream pending = new ByteArrayInputStream(new byte[0]);
                private boolean greeted;

                @Override
                public InputStream in() {
                    return new InputStream() {
                        @Override
                        public int read() throws IOException {
                            // answers are produced when the app writes, see out()
                            return pending.read();
                        }
                    };
                }

                @Override
                public OutputStream out() {
                    return new OutputStream() {
                        @Override
                        public void write(int b) {
                            toDiscord.write(b);
                        }

                        @Override
                        public void flush() throws IOException {
                            byte[] all = toDiscord.toByteArray();
                            toDiscord.reset();
                            DiscordFrames.Frame f = DiscordFrames.read(new ByteArrayInputStream(all));
                            JsonObject o = JsonParser.parseString(f.json()).getAsJsonObject();
                            received.add(o);
                            byte[] answer;
                            if (f.opcode() == DiscordFrames.HANDSHAKE) {
                                greeted = true;
                                answer = refuseHandshake ? DiscordFrames.encode(DiscordFrames.CLOSE, "{\"code\":4000}")
                                    : DiscordFrames.encode(DiscordFrames.FRAME, "{\"cmd\":\"DISPATCH\",\"evt\":\"READY\"}");
                            } else if (dropNextCommand) {
                                dropNextCommand = false;
                                answer = new byte[0];
                            } else {
                                answer = DiscordFrames.encode(DiscordFrames.FRAME, "{\"cmd\":\"SET_ACTIVITY\",\"evt\":null}");
                            }
                            pending = new ByteArrayInputStream(answer);
                        }
                    };
                }

                @Override
                public void close() {
                    closes.incrementAndGet();
                }
            };
        }

        JsonObject lastActivityCommand() {
            for (int i = received.size() - 1; i >= 0; i--) {
                if ("SET_ACTIVITY".equals(received.get(i).has("cmd") ? received.get(i).get("cmd").getAsString() : null)) {
                    return received.get(i);
                }
            }
            return null;
        }
    }

    private static DiscordPresence presence(FakeDiscord fake, boolean enabled, String id) {
        return new DiscordPresence(() -> enabled, () -> id, fake);
    }

    @Test
    void framesRoundTrip() throws Exception {
        byte[] bytes = DiscordFrames.encode(DiscordFrames.FRAME, "{\"a\":\"é\"}");
        assertEquals(1, bytes[0]);
        assertEquals(0, bytes[1]);
        DiscordFrames.Frame f = DiscordFrames.read(new ByteArrayInputStream(bytes));
        assertEquals(DiscordFrames.FRAME, f.opcode());
        assertEquals("{\"a\":\"é\"}", f.json());
        // the length is in bytes, not characters
        assertEquals(bytes.length - 8, bytes[4] & 0xFF);
    }

    @Test
    void frameReadingRefusesTruncatedAndHugeMessages() {
        byte[] ok = DiscordFrames.encode(1, "{}");
        assertThrows(EOFException.class, () -> DiscordFrames.read(new ByteArrayInputStream(ok, 0, 5)));
        assertThrows(EOFException.class, () -> DiscordFrames.read(new ByteArrayInputStream(ok, 0, 9)));
        byte[] huge = {1, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F};
        assertThrows(IOException.class, () -> DiscordFrames.read(new ByteArrayInputStream(huge)));
    }

    @Test
    void showsThePlayedProjectAndVersion() throws Exception {
        FakeDiscord fake = new FakeDiscord();
        try (DiscordPresence p = presence(fake, true, APP)) {
            p.show(DiscordPresence.activityFor("Shardlight Survival", "26.3", START));
            p.flush();
            JsonObject hello = fake.received.get(0);
            assertEquals(APP, hello.get("client_id").getAsString());
            assertEquals(1, hello.get("v").getAsInt());
            JsonObject cmd = fake.lastActivityCommand();
            JsonObject activity = cmd.getAsJsonObject("args").getAsJsonObject("activity");
            assertEquals("Playing Shardlight Survival", activity.get("details").getAsString());
            assertEquals("on Minecraft 26.3", activity.get("state").getAsString());
            assertEquals(START.getEpochSecond(), activity.getAsJsonObject("timestamps").get("start").getAsLong());
            assertEquals(ProcessHandle.current().pid(), cmd.getAsJsonObject("args").get("pid").getAsLong());
            assertFalse(cmd.get("nonce").getAsString().isBlank());
        }
    }

    @Test
    void clearsTheStatusAndLetsGoWhenTheGameCloses() throws Exception {
        FakeDiscord fake = new FakeDiscord();
        try (DiscordPresence p = presence(fake, true, APP)) {
            p.show(DiscordPresence.activityFor("A", "26.3", START));
            p.clear();
            p.flush();
            JsonObject args = fake.lastActivityCommand().getAsJsonObject("args");
            assertFalse(args.has("activity"), "no activity means cleared");
            assertEquals(1, fake.closes.get());
            // the next game connects again
            p.show(DiscordPresence.activityFor("B", "26.3", START));
            p.flush();
            assertEquals(2, fake.connections.get());
        }
    }

    @Test
    void doesNothingWhenSwitchedOffOrWithoutAnApplicationId() throws Exception {
        FakeDiscord off = new FakeDiscord();
        try (DiscordPresence p = presence(off, false, APP)) {
            assertFalse(p.isActive());
            p.show(DiscordPresence.activityFor("A", "26.3", START));
            p.flush();
            assertEquals(0, off.connections.get());
        }
        FakeDiscord none = new FakeDiscord();
        try (DiscordPresence p = presence(none, true, "")) {
            assertFalse(p.isActive());
            p.show(DiscordPresence.activityFor("A", "26.3", START));
            p.flush();
            assertEquals(0, none.connections.get());
        }
    }

    @Test
    void staysQuietWhenDiscordIsNotRunningAndTriesAgainNextTime() throws Exception {
        FakeDiscord fake = new FakeDiscord();
        fake.running = false;
        try (DiscordPresence p = presence(fake, true, APP)) {
            p.show(DiscordPresence.activityFor("A", "26.3", START));
            p.flush();
            assertEquals(0, fake.connections.get());
            fake.running = true;
            p.show(DiscordPresence.activityFor("A", "26.3", START));
            p.flush();
            assertEquals(1, fake.connections.get());
            assertTrue(fake.lastActivityCommand() != null);
        }
    }

    @Test
    void anUnacceptedApplicationIdIsNotAnError() throws Exception {
        FakeDiscord fake = new FakeDiscord();
        fake.refuseHandshake = true;
        try (DiscordPresence p = presence(fake, true, APP)) {
            p.show(DiscordPresence.activityFor("A", "26.3", START));
            p.flush();
            assertEquals(1, fake.closes.get(), "the connection is closed again");
        }
    }

    @Test
    void reconnectsOnceWhenAnOldConnectionWentStale() throws Exception {
        FakeDiscord fake = new FakeDiscord();
        try (DiscordPresence p = presence(fake, true, APP)) {
            p.show(DiscordPresence.activityFor("A", "26.3", START));
            p.flush();
            fake.dropNextCommand = true; // Discord vanished: the next command gets no answer
            p.show(DiscordPresence.activityFor("B", "26.3", START));
            p.flush();
            assertEquals(2, fake.connections.get());
            assertEquals("Playing B", fake.lastActivityCommand().getAsJsonObject("args").getAsJsonObject("activity")
                .get("details").getAsString());
        }
    }

    @Test
    void anApplicationIdChangedInSettingsUsesANewConnection() throws Exception {
        FakeDiscord fake = new FakeDiscord();
        String[] id = {APP};
        try (DiscordPresence p = new DiscordPresence(() -> true, () -> id[0], fake)) {
            p.show(DiscordPresence.activityFor("A", "26.3", START));
            p.flush();
            id[0] = "987654321098765432";
            p.show(DiscordPresence.activityFor("A", "26.3", START));
            p.flush();
            assertEquals(2, fake.connections.get());
            assertEquals("987654321098765432", fake.received.get(fake.received.size() - 2).get("client_id").getAsString());
        }
    }

    @Test
    void longLinesAreCutToWhatDiscordAccepts() throws Exception {
        FakeDiscord fake = new FakeDiscord();
        try (DiscordPresence p = presence(fake, true, APP)) {
            p.show(DiscordPresence.activityFor("x".repeat(300), "26.3", START));
            p.flush();
            String details = fake.lastActivityCommand().getAsJsonObject("args").getAsJsonObject("activity").get("details").getAsString();
            assertEquals(128, details.length());
        }
    }

    @Test
    void activityTextHandlesMissingNamesAndVersions() {
        assertEquals("Playing Minecraft", DiscordPresence.activityFor(null, "26.3", START).details());
        assertEquals("on Minecraft", DiscordPresence.activityFor("A", " ", START).state());
    }

    @Test
    void applicationIdsLookLikeNumbers() {
        assertTrue(DiscordApplicationIds.looksValid("123456789012345678"));
        assertFalse(DiscordApplicationIds.looksValid("abc"));
        assertFalse(DiscordApplicationIds.looksValid("12345"));
        assertFalse(DiscordApplicationIds.looksValid(null));
    }

    /** the same conversation over a real Unix socket: what Discord on Linux and macOS would see */
    @Test
    void talksOverARealUnixSocket(@TempDir Path dir) throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
        Path sock = dir.resolve("d");
        List<String> seen = new ArrayList<>();
        AtomicBoolean serverDone = new AtomicBoolean();
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(sock));
            Thread t = new Thread(() -> {
                try (SocketChannel c = server.accept()) {
                    InputStream in = Channels.newInputStream(c);
                    OutputStream out = Channels.newOutputStream(c);
                    seen.add(DiscordFrames.read(in).json());
                    out.write(DiscordFrames.encode(DiscordFrames.FRAME, "{\"evt\":\"READY\"}"));
                    seen.add(DiscordFrames.read(in).json());
                    out.write(DiscordFrames.encode(DiscordFrames.FRAME, "{\"evt\":null}"));
                    seen.add(DiscordFrames.read(in).json());
                    out.write(DiscordFrames.encode(DiscordFrames.FRAME, "{\"evt\":null}"));
                } catch (IOException e) {
                    seen.add("server error " + e);
                } finally {
                    serverDone.set(true);
                }
            });
            t.start();
            DiscordTransport.Connector connector = () -> DiscordTransport.socket(sock);
            try (DiscordPresence p = new DiscordPresence(() -> true, () -> APP, connector)) {
                p.show(DiscordPresence.activityFor("Real", "26.3", START));
                p.clear();
                p.flush();
            }
            t.join(5000);
        }
        assertTrue(serverDone.get());
        assertEquals(3, seen.size(), seen.toString());
        assertTrue(seen.get(0).contains(APP));
        assertTrue(seen.get(1).contains("Playing Real"));
        assertFalse(seen.get(2).contains("activity"));
    }
}

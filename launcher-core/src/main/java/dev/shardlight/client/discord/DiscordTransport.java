package dev.shardlight.client.discord;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A connection to the Discord app running on this computer. */
interface DiscordTransport extends Closeable {
    InputStream in();

    OutputStream out();

    /** how to reach Discord: tries each of its ten local addresses in turn */
    interface Connector {
        /** a connection, or an {@link IOException} when Discord is not running */
        DiscordTransport connect() throws IOException;
    }

    /** the real thing: a named pipe on Windows, a Unix socket elsewhere */
    static Connector local() {
        return () -> {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            IOException last = new IOException("Discord is not running");
            for (int i = 0; i < 10; i++) {
                try {
                    if (windows) {
                        return pipe("\\\\.\\pipe\\discord-ipc-" + i);
                    }
                    for (Path dir : socketDirectories()) {
                        Path p = dir.resolve("discord-ipc-" + i);
                        if (Files.exists(p)) {
                            return socket(p);
                        }
                    }
                } catch (IOException e) {
                    last = e;
                }
            }
            throw last;
        };
    }

    /** where Discord puts its socket: the runtime folder first, then the temp folders, then the Flatpak and Snap places */
    static List<Path> socketDirectories() {
        List<Path> dirs = new ArrayList<>();
        for (String var : new String[] {"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
            String v = System.getenv(var);
            if (v != null && !v.isBlank()) {
                dirs.add(Path.of(v));
            }
        }
        dirs.add(Path.of("/tmp"));
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        if (runtime != null && !runtime.isBlank()) {
            dirs.add(Path.of(runtime, "app", "com.discordapp.Discord"));
            dirs.add(Path.of(runtime, "snap.discord"));
        }
        return dirs;
    }

    static DiscordTransport socket(Path path) throws IOException {
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            ch.connect(UnixDomainSocketAddress.of(path));
        } catch (IOException e) {
            ch.close();
            throw e;
        }
        InputStream in = Channels.newInputStream(ch);
        OutputStream out = Channels.newOutputStream(ch);
        return new DiscordTransport() {
            @Override
            public InputStream in() {
                return in;
            }

            @Override
            public OutputStream out() {
                return out;
            }

            @Override
            public void close() throws IOException {
                ch.close();
            }
        };
    }

    /** Windows named pipes are opened like files; reads and writes must take turns, which the caller does */
    static DiscordTransport pipe(String name) throws IOException {
        RandomAccessFile f = new RandomAccessFile(name, "rw");
        InputStream in = new InputStream() {
            @Override
            public int read() throws IOException {
                return f.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return f.read(b, off, len);
            }
        };
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                f.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                f.write(b, off, len);
            }
        };
        return new DiscordTransport() {
            @Override
            public InputStream in() {
                return in;
            }

            @Override
            public OutputStream out() {
                return out;
            }

            @Override
            public void close() throws IOException {
                f.close();
            }
        };
    }
}

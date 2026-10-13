package dev.shardlight.client.discord;

import com.google.gson.JsonObject;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.util.Json;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Discord Rich Presence through the Discord app's local connection: no server and no account is involved, Discord
 * only hears from this computer. While the game runs it shows "Playing &lt;project&gt;" and "on Minecraft
 * &lt;version&gt;" with the time played; when the game closes the status is cleared and the connection ends.
 *
 * <p>Nothing happens unless the player switched it on ({@code enabled}) and an application id is set. When Discord
 * is not running, nothing happens either, and the next game start tries again. Every call returns at once; the work
 * is done on one background thread, and a failure is only logged.
 */
public final class DiscordPresence implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(DiscordPresence.class.getName());

    /** what Discord is told: the two text lines and when the game started */
    public record Activity(String details, String state, Instant startedAt) {
    }

    private final Supplier<Boolean> enabled;
    private final Supplier<String> applicationId;
    private final DiscordTransport.Connector connector;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "discord-presence");
        t.setDaemon(true);
        return t;
    });
    private DiscordTransport transport;
    private String connectedAs;

    /** the real connection to the Discord app */
    public DiscordPresence(Supplier<Boolean> enabled, Supplier<String> applicationId) {
        this(enabled, applicationId, DiscordTransport.local());
    }

    DiscordPresence(Supplier<Boolean> enabled, Supplier<String> applicationId, DiscordTransport.Connector connector) {
        this.enabled = enabled;
        this.applicationId = applicationId;
        this.connector = connector;
    }

    /** the lines for a running game: "Playing &lt;project&gt;" over "on Minecraft &lt;version&gt;" */
    public static Activity activityFor(String projectName, String minecraftVersion, Instant startedAt) {
        String project = projectName == null || projectName.isBlank() ? "Minecraft" : projectName.strip();
        String version = minecraftVersion == null || minecraftVersion.isBlank() ? "" : minecraftVersion.strip();
        return new Activity(I18n.tr("discord.details", project), version.isEmpty() ? I18n.tr("discord.stateNoVersion")
            : I18n.tr("discord.state", version), startedAt);
    }

    /** true when switched on and an application id is set: the only case where Discord is contacted */
    public boolean isActive() {
        String id = applicationId.get();
        return Boolean.TRUE.equals(enabled.get()) && id != null && !id.isBlank();
    }

    /** show {@code activity} (when active); the previous one is replaced */
    public void show(Activity activity) {
        if (!isActive()) {
            return;
        }
        submit(() -> send(activity));
    }

    /** remove the status and end the connection */
    public void clear() {
        submit(() -> {
            if (transport != null) {
                try {
                    send(null);
                } finally {
                    disconnect();
                }
            }
        });
    }

    /** wait until everything asked so far is done (tests) */
    void flush() throws InterruptedException {
        try {
            worker.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            worker.submit(() -> {
                if (transport != null) {
                    try {
                        send(null);
                    } finally {
                        disconnect();
                    }
                }
            }).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Discord status was not cleared on exit", e);
        }
        worker.shutdownNow();
    }

    // ---------------------------------------------------------------- worker thread

    private void submit(Runnable r) {
        try {
            worker.execute(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "Discord status failed", e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closing
        }
    }

    /** one attempt; when a connection that used to work has gone stale it is made again once */
    private void send(Activity activity) {
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean reused = transport != null;
            try {
                connect();
                setActivity(activity);
                return;
            } catch (IOException e) {
                LOG.log(Level.FINE, () -> "Discord is not reachable: " + e.getMessage());
                disconnect();
                if (!reused) {
                    return;
                }
            }
        }
    }

    private void connect() throws IOException {
        String id = applicationId.get();
        if (transport != null && !id.equals(connectedAs)) {
            disconnect();
        }
        if (transport != null) {
            return;
        }
        DiscordTransport t = connector.connect();
        try {
            JsonObject hello = new JsonObject();
            hello.addProperty("v", 1);
            hello.addProperty("client_id", id);
            t.out().write(DiscordFrames.encode(DiscordFrames.HANDSHAKE, Json.COMPACT.toJson(hello)));
            t.out().flush();
            DiscordFrames.Frame answer = DiscordFrames.read(t.in());
            if (answer.opcode() == DiscordFrames.CLOSE || !answer.json().contains("READY")) {
                throw new IOException("Discord did not accept the application id");
            }
        } catch (IOException | RuntimeException e) {
            try {
                t.close();
            } catch (IOException ignored) {
                // already gone
            }
            throw e instanceof IOException io ? io : new IOException(e);
        }
        transport = t;
        connectedAs = id;
    }

    private void setActivity(Activity activity) throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        if (activity != null) {
            JsonObject a = new JsonObject();
            a.addProperty("details", clip(activity.details()));
            a.addProperty("state", clip(activity.state()));
            JsonObject ts = new JsonObject();
            ts.addProperty("start", activity.startedAt().getEpochSecond());
            a.add("timestamps", ts);
            args.add("activity", a);
        }
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "SET_ACTIVITY");
        cmd.add("args", args);
        cmd.addProperty("nonce", UUID.randomUUID().toString());
        transport.out().write(DiscordFrames.encode(DiscordFrames.FRAME, Json.COMPACT.toJson(cmd)));
        transport.out().flush();
        // Discord answers every command; reading it also notices a connection that has gone away
        DiscordFrames.Frame reply = DiscordFrames.read(transport.in());
        if (reply.opcode() == DiscordFrames.CLOSE) {
            throw new IOException("Discord closed the connection");
        }
    }

    /** Discord shows at most 128 characters per line */
    private static String clip(String s) {
        String t = s == null ? "" : s.strip();
        return t.length() <= 128 ? t : t.substring(0, 127) + "…";
    }

    private void disconnect() {
        DiscordTransport t = transport;
        transport = null;
        connectedAs = null;
        if (t != null) {
            try {
                t.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
    }
}

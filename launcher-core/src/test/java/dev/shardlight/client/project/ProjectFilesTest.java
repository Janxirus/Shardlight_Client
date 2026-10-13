package dev.shardlight.client.project;

import dev.shardlight.client.content.ContentEntry;
import dev.shardlight.client.content.ContentKind;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.nbt.NbtCompound;
import dev.shardlight.client.nbt.NbtIo;
import dev.shardlight.client.nbt.NbtList;
import dev.shardlight.client.nbt.NbtTag;
import dev.shardlight.client.storage.AppPaths;
import dev.shardlight.client.task.CancellationToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backups and restores, screenshots and their trash, the server list and the Server List Ping. */
class ProjectFilesTest {
    @TempDir
    Path dir;
    ProjectService projects;
    BackupService backups;
    Project p;
    Path game;

    @BeforeEach
    void setUp() throws Exception {
        projects = new ProjectService(new AppPaths(dir));
        backups = new BackupService(new AppPaths(dir), projects);
        p = projects.create(NewProjectRequest.vanilla("Files", "1.21.4"));
        game = projects.gameDir(p);
    }

    void fillGame() throws IOException {
        TestFiles.world(game.resolve(GameDirs.SAVES), "Survival", "My Survival", 1_000L, "1.21.4");
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/sodium.json"), "{\"quality\":\"high\"}");
        Files.createDirectories(game.resolve(GameDirs.MODS));
        Files.writeString(game.resolve("mods/sodium.jar"), "sodium");
        Files.writeString(game.resolve(GameDirs.OPTIONS), "renderDistance:12\n");
    }

    // ---------------------------------------------------------------- backups

    @Test
    void aProjectBackupRestoresExactlyWhatItHeld() throws Exception {
        fillGame();
        Map<String, String> before = TestFiles.tree(game);
        BackupInfo b = backups.backupProject(p.id, "Before shaders", BackupScope.EVERYTHING, null, CancellationToken.NONE);
        assertEquals(BackupInfo.PROJECT, b.kind());
        assertEquals("Before shaders", b.label());
        assertNotNull(b.sha256());
        assertTrue(Files.isRegularFile(backups.backupFile(b)));
        assertFalse(TestFiles.anyStaging(dir), "no .part files are left behind");

        Files.writeString(game.resolve("config/sodium.json"), "{\"quality\":\"low\"}");
        Files.delete(game.resolve("mods/sodium.jar"));
        Files.writeString(game.resolve("mods/broken.jar"), "broken");
        Files.writeString(game.resolve(GameDirs.OPTIONS), "renderDistance:2\n");

        backups.restore(b, null, CancellationToken.NONE);

        Map<String, String> after = TestFiles.tree(game);
        Map<String, String> expected = new java.util.TreeMap<>(before);
        expected.remove("saves/Survival/session.lock");
        after.remove("saves/Survival/session.lock");
        assertEquals(expected, after, "the session.lock is never copied; everything else is back");
        assertEquals("renderDistance:12\n", Files.readString(game.resolve(GameDirs.OPTIONS)));

        List<BackupInfo> all = backups.list(p.id);
        assertEquals(2, all.size());
        assertTrue(all.get(0).isPreRestore(), "the state before the restore was saved first");
        assertTrue(all.stream().anyMatch(x -> x.id().equals(b.id())));
    }

    @Test
    void aSettingsOnlyBackupLeavesWorldsAlone() throws Exception {
        fillGame();
        BackupInfo b = backups.backupProject(p.id, "settings", BackupScope.CONFIG_ONLY, null, null);
        Files.writeString(game.resolve("config/sodium.json"), "changed");
        Files.writeString(game.resolve("saves/Survival/data/raids.dat"), "new raids");
        backups.restore(b, null, null);
        assertEquals("{\"quality\":\"high\"}", Files.readString(game.resolve("config/sodium.json")));
        assertEquals("new raids", Files.readString(game.resolve("saves/Survival/data/raids.dat")));
    }

    @Test
    void restoringBringsBackTheContentList() throws Exception {
        fillGame();
        ContentEntry sodium = new ContentEntry();
        sodium.id = "sodium";
        sodium.kind = ContentKind.MOD;
        sodium.fileName = "sodium.jar";
        p = projects.update(p.id, x -> x.content.add(sodium));
        BackupInfo b = backups.backupProject(p.id, null, BackupScope.EVERYTHING, null, null);
        projects.update(p.id, x -> x.content.clear());
        backups.restore(b, null, null);
        assertEquals(List.of("sodium.jar"), projects.require(p.id).content.stream().map(e -> e.fileName).toList());
    }

    @Test
    void aWorldBackupRestoresOnlyThatWorld() throws Exception {
        fillGame();
        TestFiles.world(game.resolve(GameDirs.SAVES), "Creative", "Creative", 2_000L, "1.21.4");
        BackupInfo b = backups.backupWorld(p.id, "Survival", "world", null, null);
        assertEquals("Survival", b.worldFolder());
        Files.writeString(game.resolve("saves/Survival/data/raids.dat"), "griefed");
        Files.writeString(game.resolve("saves/Creative/data/raids.dat"), "kept");
        backups.restore(b, null, null);
        assertEquals("raids of Survival", Files.readString(game.resolve("saves/Survival/data/raids.dat")));
        assertEquals("kept", Files.readString(game.resolve("saves/Creative/data/raids.dat")));
    }

    @Test
    void aWorldTheGameHasOpenIsNotTouched() throws Exception {
        fillGame();
        Path lock = game.resolve("saves/Survival/session.lock");
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE); FileLock held = ch.lock()) {
            assertTrue(held.isValid());
            LauncherException e = assertThrows(LauncherException.class, () -> backups.backupWorld(p.id, "Survival", null, null, null));
            assertEquals("project.world.inUse", e.messageKey());
            assertThrows(LauncherException.class, () -> backups.backupProject(p.id, null, BackupScope.WORLDS_ONLY, null, null));
        }
        assertNotNull(backups.backupWorld(p.id, "Survival", null, null, null), "fine once the game lets go");
    }

    @Test
    void aDamagedBackupIsRefusedAndNothingChanges() throws Exception {
        fillGame();
        BackupInfo b = backups.backupProject(p.id, null, BackupScope.EVERYTHING, null, null);
        Files.writeString(game.resolve(GameDirs.OPTIONS), "current\n");
        byte[] zip = Files.readAllBytes(backups.backupFile(b));
        zip[zip.length / 2] ^= 0x5a;
        Files.write(backups.backupFile(b), zip);
        LauncherException e = assertThrows(LauncherException.class, () -> backups.restore(b, null, null));
        assertEquals(ErrorCode.CHECKSUM_MISMATCH, e.code());
        assertEquals("current\n", Files.readString(game.resolve(GameDirs.OPTIONS)));
        assertEquals(1, backups.list(p.id).size(), "no pre-restore backup for a restore that never started");
    }

    @Test
    void cancellingABackupLeavesNoFile() throws Exception {
        fillGame();
        CancellationToken cancel = new CancellationToken();
        cancel.cancel();
        LauncherException e = assertThrows(LauncherException.class,
            () -> backups.backupProject(p.id, null, BackupScope.EVERYTHING, null, cancel));
        assertTrue(e.isCancelled());
        assertTrue(backups.list(p.id).isEmpty());
        assertFalse(TestFiles.anyStaging(dir));
    }

    @Test
    void pruningKeepsTheNewestAndRecentPreRestoreBackups() throws Exception {
        fillGame();
        long[] now = {1_000_000_000_000L};
        backups.clock = () -> now[0];
        BackupInfo first = backups.backupProject(p.id, "one", BackupScope.CONFIG_ONLY, null, null);
        now[0] += 60_000;
        backups.restore(first, null, null); // adds a pre-restore backup
        now[0] += 60_000;
        backups.backupProject(p.id, "three", BackupScope.CONFIG_ONLY, null, null);
        now[0] += 60_000;
        backups.backupProject(p.id, "four", BackupScope.CONFIG_ONLY, null, null);
        assertEquals(4, backups.list(p.id).size());

        backups.prune(p.id, 2);
        List<String> labels = backups.list(p.id).stream().map(BackupInfo::label).toList();
        assertEquals(List.of("four", "three", BackupService.PRE_RESTORE), labels, "a fresh pre-restore backup is kept");

        now[0] += BackupService.PRE_RESTORE_KEEP_MILLIS + 1;
        backups.prune(p.id, 2);
        assertEquals(List.of("four", "three"), backups.list(p.id).stream().map(BackupInfo::label).toList());
        assertThrows(IllegalArgumentException.class, () -> backups.prune(p.id, -1));
    }

    @Test
    void aLostSidecarIsRebuiltAndDeleteRemovesBoth() throws Exception {
        fillGame();
        BackupInfo b = backups.backupProject(p.id, "label", BackupScope.CONFIG_ONLY, null, null);
        Files.delete(backups.backupFile(b).resolveSibling(b.id() + ".json"));
        List<BackupInfo> list = backups.list(p.id);
        assertEquals(1, list.size());
        assertEquals(b.id(), list.get(0).id());
        backups.delete(list.get(0));
        assertTrue(backups.list(p.id).isEmpty());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(LauncherException.class, () -> backups.delete(b)).code());
    }

    // ---------------------------------------------------------------- screenshots

    @Test
    void screenshotsGoToTheTrashAndComeBack() throws Exception {
        ScreenshotService shots = new ScreenshotService(projects);
        Path dirShots = Files.createDirectories(game.resolve(GameDirs.SCREENSHOTS));
        Path older = Files.write(dirShots.resolve("2026-10-01_20.10.20.png"), TestFiles.PNG);
        Path newer = Files.write(dirShots.resolve("2026-10-02_21.11.21.png"), TestFiles.PNG);
        Files.write(dirShots.resolve("notes.txt"), new byte[] {1});
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(500L));

        List<Screenshot> list = shots.list(p);
        assertEquals(List.of(newer.getFileName(), older.getFileName()),
            list.stream().map(s -> s.file().getFileName()).toList(), "the name's time wins over the file time");

        shots.moveToTrash(p, newer);
        assertEquals(1, shots.list(p).size());
        List<Screenshot> trashed = shots.trashed(p);
        assertEquals(1, trashed.size());

        Files.write(newer, TestFiles.PNG); // a new screenshot with the same name meanwhile
        Screenshot back = shots.restore(p, trashed.get(0).file());
        assertEquals("2026-10-02_21.11.21_2.png", back.file().getFileName().toString());
        assertEquals(3, shots.list(p).size());

        shots.moveToTrash(p, older);
        shots.deleteFromTrash(p, shots.trashed(p).get(0).file());
        assertTrue(shots.trashed(p).isEmpty());
    }

    @Test
    void onlyFilesDirectlyInTheScreenshotsFolderAreMoved() throws Exception {
        ScreenshotService shots = new ScreenshotService(projects);
        Files.createDirectories(game.resolve(GameDirs.SCREENSHOTS));
        Path outside = Files.write(game.resolve(GameDirs.OPTIONS), "x".getBytes(StandardCharsets.UTF_8));
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class, () -> shots.moveToTrash(p, outside)).code());
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class,
            () -> shots.moveToTrash(p, game.resolve("screenshots/../options.txt"))).code());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(LauncherException.class,
            () -> shots.moveToTrash(p, game.resolve("screenshots/missing.png"))).code());
        assertEquals(ErrorCode.UNSAFE_PATH, assertThrows(LauncherException.class, () -> shots.deleteFromTrash(p, outside)).code());
        assertTrue(Files.exists(outside));
    }

    // ---------------------------------------------------------------- servers.dat

    static NbtCompound server(String name, String ip, boolean hidden) {
        NbtCompound c = new NbtCompound();
        c.putString("name", name);
        c.putString("ip", ip);
        c.putBoolean("hidden", hidden);
        return c;
    }

    @Test
    void theServerListRoundTripsAndKeepsWhatTheLauncherDoesNotKnow() throws Exception {
        NbtCompound modded = server("Modded", "mods.example.net", false);
        modded.putString("someModTag", "keep me");
        NbtList list = new NbtList(NbtTag.TAG_COMPOUND);
        list.add(server("Hub", "hub.example.net:25570", false));
        list.add(modded);
        list.add(server("Direct", "10.0.0.5", true));
        NbtCompound root = new NbtCompound();
        root.put("servers", list);
        Files.write(game.resolve(GameDirs.SERVERS), NbtIo.toBytes(root, false));

        ServerListService servers = new ServerListService(projects);
        List<ServerEntry> loaded = servers.load(p);
        assertEquals(List.of("Hub", "Modded"), loaded.stream().map(ServerEntry::name).toList(), "hidden entries aren't listed");

        servers.save(p, List.of(
            new ServerEntry("Modded server", "mods.example.net", null, Boolean.FALSE, true),
            new ServerEntry("  New  ", " new.example.org ", "not base64!", null, false)));

        List<ServerEntry> again = servers.load(p);
        assertEquals(List.of(new ServerEntry("Modded server", "mods.example.net", null, Boolean.FALSE, true),
            new ServerEntry("New", "new.example.org", null, null, false)), again, "names and addresses are trimmed; a bad icon is dropped");
        NbtCompound written = NbtIo.readFile(game.resolve(GameDirs.SERVERS));
        List<NbtCompound> entries = written.getList("servers", NbtTag.TAG_COMPOUND).compounds();
        assertEquals("keep me", entries.get(0).getString("someModTag"));
        assertEquals("Direct", entries.get(entries.size() - 1).getString("name"), "the direct-connect history is kept");
        assertTrue(Files.exists(game.resolve(GameDirs.SERVERS + "_old")), "the previous file is kept like the game does");

        assertEquals(ErrorCode.INVALID_DATA, assertThrows(LauncherException.class,
            () -> servers.save(p, List.of(new ServerEntry("x", "bad host name", null, null, false)))).code());
    }

    @Test
    void anUnreadableServerListIsSetAsideNotOverwritten() throws Exception {
        Files.writeString(game.resolve(GameDirs.SERVERS), "garbage");
        ServerListService servers = new ServerListService(projects);
        assertEquals(ErrorCode.INVALID_DATA, assertThrows(LauncherException.class, () -> servers.load(p)).code());
        servers.save(p, List.of(new ServerEntry("A", "a.example.net", null, null, false)));
        try (var s = Files.list(game)) {
            assertTrue(s.anyMatch(f -> f.getFileName().toString().startsWith(GameDirs.SERVERS + ".corrupt-")));
        }
        assertEquals(1, servers.load(p).size());
    }

    @Test
    void addressesParseLikeTheGame() {
        assertEquals(new ServerListService.HostPort("play.example.net", 25565, false), ServerListService.parseAddress("play.example.net"));
        assertEquals(new ServerListService.HostPort("play.example.net", 25570, true), ServerListService.parseAddress("play.example.net:25570"));
        assertEquals(new ServerListService.HostPort("::1", 25566, true), ServerListService.parseAddress("[::1]:25566"));
        assertThrows(IllegalArgumentException.class, () -> ServerListService.parseAddress("host:99999"));
        assertThrows(IllegalArgumentException.class, () -> ServerListService.parseAddress("a b"));
        assertThrows(IllegalArgumentException.class, () -> ServerListService.parseAddress(""));
        assertTrue(ServerListService.isIpLiteral("192.168.1.2"));
        assertFalse(ServerListService.isIpLiteral("example.net"));
    }

    // ---------------------------------------------------------------- Server List Ping

    /** a one-connection Minecraft server that answers a status request and a ping */
    static final class FakeServer implements AutoCloseable {
        final ServerSocket socket;
        final Thread thread;
        final AtomicReference<String> handshakeHost = new AtomicReference<>();

        FakeServer(String statusJson, boolean answerPing) throws IOException {
            socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            thread = Thread.ofVirtual().start(() -> {
                try (Socket s = socket.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    OutputStream out = s.getOutputStream();
                    byte[] handshake = packet(in);
                    DataInputStream h = new DataInputStream(new java.io.ByteArrayInputStream(handshake));
                    varInt(h); // packet id
                    varInt(h); // protocol
                    byte[] host = new byte[varInt(h)];
                    h.readFully(host);
                    handshakeHost.set(new String(host, StandardCharsets.UTF_8));
                    packet(in); // status request
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    writeVarInt(body, 0);
                    byte[] json = statusJson.getBytes(StandardCharsets.UTF_8);
                    writeVarInt(body, json.length);
                    body.write(json);
                    send(out, body.toByteArray());
                    if (answerPing) {
                        send(out, packet(in)); // pong echoes the ping
                    }
                } catch (IOException e) {
                    // the client went away
                }
            });
        }

        int port() {
            return socket.getLocalPort();
        }

        static byte[] packet(DataInputStream in) throws IOException {
            byte[] b = new byte[varInt(in)];
            in.readFully(b);
            return b;
        }

        static void send(OutputStream out, byte[] body) throws IOException {
            ByteArrayOutputStream framed = new ByteArrayOutputStream();
            writeVarInt(framed, body.length);
            framed.write(body);
            out.write(framed.toByteArray());
            out.flush();
        }

        static int varInt(InputStream in) throws IOException {
            int value = 0;
            for (int i = 0; i < 5; i++) {
                int b = in.read();
                if (b < 0) {
                    throw new java.io.EOFException();
                }
                value |= (b & 0x7f) << (7 * i);
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IOException("varint too long");
        }

        static void writeVarInt(OutputStream out, int value) throws IOException {
            int v = value;
            while ((v & ~0x7f) != 0) {
                out.write((v & 0x7f) | 0x80);
                v >>>= 7;
            }
            out.write(v);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void pingReadsTheServersStatus() throws Exception {
        String status = "{\"version\":{\"name\":\"§aPaper 1.21.4\",\"protocol\":769},\"players\":{\"max\":20,\"online\":3},"
            + "\"description\":{\"text\":\"§6Welcome\",\"extra\":[{\"text\":\" to \"},\"Shardlight\"]},"
            + "\"favicon\":\"data:image/png;base64,iVBORw0KGgo=\"}";
        try (FakeServer server = new FakeServer(status, true)) {
            ServerListService servers = new ServerListService(projects, (host, timeout) -> Optional.empty());
            ServerStatus s = servers.ping("127.0.0.1:" + server.port(), Duration.ofSeconds(5));
            assertTrue(s.online(), String.valueOf(s.error()));
            assertEquals(3, s.playersOnline());
            assertEquals(20, s.playersMax());
            assertEquals("Welcome to Shardlight", s.motd(), "formatting codes are removed");
            assertEquals("Paper 1.21.4", s.versionName());
            assertEquals(769, s.protocol());
            assertEquals("iVBORw0KGgo=", s.faviconBase64());
            assertTrue(s.latencyMs() >= 0);
            assertNull(s.error());
            assertEquals("127.0.0.1", server.handshakeHost.get());
        }
    }

    @Test
    void srvRecordsAreFollowedWhenNoPortIsGiven() throws Exception {
        try (FakeServer server = new FakeServer("{\"players\":{\"max\":1,\"online\":0},\"description\":\"hi\"}", false)) {
            ServerListService servers = new ServerListService(projects, (host, timeout) ->
                host.equals("mc.example.test") ? Optional.of(new ServerListService.HostPort("127.0.0.1", server.port(), true))
                    : Optional.empty());
            ServerStatus s = servers.ping("mc.example.test", Duration.ofSeconds(5));
            assertTrue(s.online(), String.valueOf(s.error()));
            assertEquals("hi", s.motd());
        }
    }

    @Test
    void unreachableAndBrokenServersComeBackOffline() throws Exception {
        ServerListService servers = new ServerListService(projects, (host, timeout) -> Optional.empty());
        int closedPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        ServerStatus refused = servers.ping("127.0.0.1:" + closedPort, Duration.ofSeconds(2));
        assertFalse(refused.online());
        assertNotNull(refused.error());
        assertFalse(servers.ping("not an address", Duration.ofSeconds(1)).online());
        try (FakeServer bad = new FakeServer("[1,2,3]", false)) {
            ServerStatus s = servers.ping("127.0.0.1:" + bad.port(), Duration.ofSeconds(5));
            assertFalse(s.online());
            assertNotNull(s.error());
        }
    }

    @Test
    void statusTextIsCleanedUp() throws Exception {
        ServerStatus s = ServerListService.parseStatus("{\"description\":[{\"translate\":\"multiplayer.status\"},{\"text\":\"§lBold\"}]}");
        assertEquals("multiplayer.statusBold", s.motd());
        assertEquals(-1, s.protocol());
        assertEquals("multiplayer.statusBold", MotdSpan.plain(s.motdSpans()));
        assertNull(ServerListService.cleanFavicon("data:image/png;base64"));
        assertNull(ServerListService.cleanFavicon(" "));
    }

    @Test
    void messageOfTheDayKeepsItsColoursAndStyles() throws Exception {
        ServerStatus legacy = ServerListService.parseStatus("{\"description\":\"§6Gold §lbold§r plain §cred§n line\\n§aSecond\"}");
        var spans = legacy.motdSpans();
        assertEquals("#FFAA00", spans.get(0).colour());
        assertEquals("Gold ", spans.get(0).text());
        assertTrue(spans.get(1).bold() && "#FFAA00".equals(spans.get(1).colour()));
        assertNull(spans.get(2).colour());
        assertFalse(spans.get(2).bold());
        assertTrue(spans.get(3).colour().equals("#FF5555") && !spans.get(3).bold());
        assertTrue(spans.get(4).underlined(), "a format code after a colour keeps the colour");
        assertEquals("#55FF55", spans.get(spans.size() - 1).colour());
        assertEquals("Gold bold plain red line\nSecond", MotdSpan.plain(spans));

        ServerStatus json = ServerListService.parseStatus("{\"description\":{\"text\":\"A\",\"color\":\"aqua\",\"bold\":true,"
            + "\"extra\":[{\"text\":\"B\",\"color\":\"#ff00aa\"},{\"text\":\"C\",\"bold\":false}]}}");
        var js = json.motdSpans();
        assertEquals(3, js.size());
        assertEquals("#55FFFF", js.get(0).colour());
        assertTrue(js.get(0).bold());
        assertEquals("#FF00AA", js.get(1).colour());
        assertTrue(js.get(1).bold(), "children inherit bold");
        assertFalse(js.get(2).bold());
        assertEquals("#55FFFF", js.get(2).colour(), "and the colour");
    }
}

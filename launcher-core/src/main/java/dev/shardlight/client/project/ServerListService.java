package dev.shardlight.client.project;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.game.GameDirs;
import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.nbt.NbtCompound;
import dev.shardlight.client.nbt.NbtIo;
import dev.shardlight.client.nbt.NbtList;
import dev.shardlight.client.nbt.NbtTag;
import dev.shardlight.client.util.AtomicFiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

/**
 * The multiplayer server list of a project ({@code <game dir>/servers.dat}, uncompressed NBT: a root
 * compound with a list {@code servers} of compounds {@code name}, {@code ip}, optional {@code icon},
 * {@code acceptTextures} and {@code hidden}) plus launcher favourites in
 * {@code projects/<id>/servers-meta.json}, and the Server List Ping.
 *
 * <p>Saving keeps every tag Minecraft or a mod stored that the launcher doesn't know, keeps hidden entries
 * (the game's "direct connect" history) and leaves the previous file as {@code servers.dat_old}, like the
 * game does.
 */
public final class ServerListService {
    private static final Logger LOG = Logger.getLogger(ServerListService.class.getName());
    /** Minecraft's default port */
    public static final int DEFAULT_PORT = 25565;
    /** the handshake protocol number used for a status query; -1 by convention means "just asking" */
    static final int PROTOCOL_QUERY = -1;
    private static final int MAX_PACKET = 4 * 1024 * 1024;
    private static final int MAX_ICON_CHARS = 60_000;
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern FORMATTING = Pattern.compile("(?s)§.?");

    /** a parsed server address */
    record HostPort(String host, int port, boolean explicitPort) {
    }

    /** looks up {@code _minecraft._tcp.<host>} */
    @FunctionalInterface
    interface SrvResolver {
        Optional<HostPort> resolve(String host, long timeoutMillis);
    }

    private final ProjectAccess projects;
    private final SrvResolver srv;

    public ServerListService(ProjectAccess projects) {
        this(projects, ServerListService::lookupSrv);
    }

    ServerListService(ProjectAccess projects, SrvResolver srv) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.srv = Objects.requireNonNull(srv, "srv");
    }

    // ---- servers.dat ----------------------------------------------------------------------------------

    /** the visible servers in game order; an empty list when the game has none yet */
    public List<ServerEntry> load(Project p) throws LauncherException {
        Path file = serversFile(p);
        Set<String> favourites = readFavourites(p);
        List<ServerEntry> out = new ArrayList<>();
        if (!FileTrees.isPlainFile(file)) {
            return out;
        }
        NbtCompound root;
        try {
            root = NbtIo.readFile(file);
        } catch (IOException e) {
            throw new LauncherException(ErrorCode.INVALID_DATA, e, "project.servers.unreadable", file.toString());
        }
        for (NbtCompound c : root.getList("servers", NbtTag.TAG_COMPOUND).compounds()) {
            if (c.getBoolean("hidden")) {
                continue;
            }
            String ip = c.getString("ip");
            String icon = c.contains("icon", NbtTag.TAG_STRING) && !c.getString("icon").isBlank() ? c.getString("icon") : null;
            Boolean accept = c.contains("acceptTextures", NbtCompound.ANY_NUMBER) ? c.getBoolean("acceptTextures") : null;
            out.add(new ServerEntry(c.getString("name"), ip, icon, accept, favourites.contains(addressKey(ip))));
        }
        return out;
    }

    /**
     * Write the visible list in this order. Entries are matched to the existing ones by address (and name)
     * so tags the launcher doesn't know survive; hidden entries are kept at the end. Favourites go to
     * {@code servers-meta.json}.
     */
    public void save(Project p, List<ServerEntry> servers) throws LauncherException {
        Objects.requireNonNull(servers, "servers");
        List<ServerEntry> clean = new ArrayList<>();
        for (ServerEntry e : servers) {
            clean.add(validate(e));
        }
        Path file = serversFile(p);
        ReentrantLock lock = AtomicFiles.lockFor(file);
        lock.lock();
        try {
            NbtIo.NamedTag existing = readExisting(file);
            NbtCompound root = existing == null ? new NbtCompound() : (NbtCompound) existing.tag();
            String rootName = existing == null ? "" : existing.name();
            List<NbtCompound> visible = new ArrayList<>();
            List<NbtCompound> hidden = new ArrayList<>();
            for (NbtCompound c : root.getList("servers", NbtTag.TAG_COMPOUND).compounds()) {
                (c.getBoolean("hidden") ? hidden : visible).add(c);
            }
            NbtList list = new NbtList(NbtTag.TAG_COMPOUND);
            for (ServerEntry e : clean) {
                NbtCompound c = take(visible, e);
                c.putString("name", e.name());
                c.putString("ip", e.address());
                if (e.iconBase64() != null) {
                    c.putString("icon", e.iconBase64());
                } else {
                    c.remove("icon");
                }
                if (e.acceptTextures() != null) {
                    c.putBoolean("acceptTextures", e.acceptTextures());
                } else {
                    c.remove("acceptTextures");
                }
                c.putBoolean("hidden", false);
                list.add(c);
            }
            for (NbtCompound h : hidden) {
                list.add(h);
            }
            root.put("servers", list);
            byte[] bytes;
            try {
                bytes = NbtIo.toBytes(new NbtIo.NamedTag(rootName, root), NbtIo.Compression.NONE);
            } catch (IOException e) {
                throw new LauncherException(ErrorCode.INVALID_DATA, e, "project.servers.tooLarge");
            }
            if (FileTrees.isPlainFile(file)) {
                try {
                    Files.copy(file, file.resolveSibling(GameDirs.SERVERS + "_old"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOG.fine("Couldn't keep servers.dat_old: " + e.getMessage());
                }
            }
            AtomicFiles.writeBytes(file, bytes);
        } finally {
            lock.unlock();
        }
        writeFavourites(p, clean);
    }

    private NbtIo.NamedTag readExisting(Path file) throws LauncherException {
        if (!FileTrees.isPlainFile(file)) {
            return null;
        }
        try {
            NbtIo.NamedTag t = NbtIo.readNamedFile(file);
            if (t.tag() instanceof NbtCompound) {
                return t;
            }
        } catch (IOException e) {
            LOG.warning("servers.dat was unreadable: " + e.getMessage());
        }
        // unreadable: keep it aside instead of silently overwriting it
        Path aside = file.resolveSibling(GameDirs.SERVERS + ".corrupt-" + ProjectService.stamp(System.currentTimeMillis()));
        try {
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw LauncherException.io(e, file.toString());
        }
        return null;
    }

    /** the stored compound that best matches {@code e} (same address and name, else same address), or a new one */
    private static NbtCompound take(List<NbtCompound> pool, ServerEntry e) {
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < pool.size(); i++) {
                NbtCompound c = pool.get(i);
                if (c.getString("ip").equalsIgnoreCase(e.address()) && (pass == 1 || c.getString("name").equals(e.name()))) {
                    return pool.remove(i).copy();
                }
            }
        }
        return new NbtCompound();
    }

    private static ServerEntry validate(ServerEntry e) throws LauncherException {
        Objects.requireNonNull(e, "server");
        String name = e.name() == null ? "" : e.name().strip();
        if (name.length() > 256 || Names.hasControl(name)) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "project.servers.badName", name);
        }
        String address = e.address() == null ? "" : e.address().strip();
        try {
            parseAddress(address);
        } catch (IllegalArgumentException ex) {
            throw new LauncherException(ErrorCode.INVALID_DATA, "project.servers.badAddress", address);
        }
        String icon = e.iconBase64() == null || e.iconBase64().isBlank() ? null : e.iconBase64().strip();
        if (icon != null) {
            if (icon.startsWith("data:")) {
                icon = cleanFavicon(icon);
            }
            if (icon == null || icon.length() > MAX_ICON_CHARS || !isBase64(icon)) {
                LOG.fine("Dropping an invalid server icon for " + address);
                icon = null;
            }
        }
        return new ServerEntry(name, address, icon, e.acceptTextures(), e.favourite());
    }

    private static boolean isBase64(String s) {
        try {
            Base64.getDecoder().decode(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ---- favourites -----------------------------------------------------------------------------------

    /** projects/&lt;id&gt;/servers-meta.json */
    private static final class Meta {
        int schemaVersion = 1;
        List<String> favourites = new ArrayList<>();
    }

    private Set<String> readFavourites(Project p) throws LauncherException {
        Meta m = AtomicFiles.readJson(metaFile(p), Meta.class, Meta::new).value();
        Set<String> out = new LinkedHashSet<>();
        if (m.favourites != null) {
            for (String a : m.favourites) {
                if (a != null) {
                    out.add(addressKey(a));
                }
            }
        }
        return out;
    }

    private void writeFavourites(Project p, List<ServerEntry> servers) throws LauncherException {
        Meta m = new Meta();
        Set<String> seen = new LinkedHashSet<>();
        for (ServerEntry e : servers) {
            if (e.favourite() && seen.add(addressKey(e.address()))) {
                m.favourites.add(e.address());
            }
        }
        AtomicFiles.writeJson(metaFile(p), m);
    }

    private static String addressKey(String address) {
        return address == null ? "" : address.strip().toLowerCase(Locale.ROOT);
    }

    private Path serversFile(Project p) {
        return projects.gameDir(Objects.requireNonNull(p, "project")).resolve(GameDirs.SERVERS);
    }

    private Path metaFile(Project p) {
        return projects.projectDir(p.id).resolve(ProjectService.SERVERS_META);
    }

    // ---- Server List Ping ------------------------------------------------------------------------------

    /**
     * Ask a server for its status like the multiplayer screen does: resolve {@code _minecraft._tcp} SRV
     * records when no port is given, send the handshake and status request, read the JSON status, then
     * measure latency with a ping/pong. Never throws; failures come back with {@code online == false} and a
     * localized {@link ServerStatus#error()}.
     */
    public ServerStatus ping(String address, Duration timeout) {
        long timeoutMs = timeout == null ? 5000 : Math.max(100, Math.min(60_000, timeout.toMillis()));
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        HostPort hp;
        try {
            hp = parseAddress(address);
        } catch (IllegalArgumentException e) {
            return ServerStatus.offline(I18n.tr("project.ping.badAddress", String.valueOf(address)));
        }
        HostPort target = hp;
        if (!hp.explicitPort() && !isIpLiteral(hp.host())) {
            try {
                target = srv.resolve(hp.host(), Math.max(100, timeoutMs / 2)).orElse(hp);
            } catch (RuntimeException e) {
                target = hp;
            }
        }
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(target.host(), target.port()), remaining(deadline));
            return exchange(socket, target, deadline);
        } catch (UnknownHostException e) {
            return ServerStatus.offline(I18n.tr("project.ping.unknownHost", target.host()));
        } catch (SocketTimeoutException e) {
            return ServerStatus.offline(I18n.tr("project.ping.timeout"));
        } catch (ConnectException | NoRouteToHostException e) {
            return ServerStatus.offline(I18n.tr("project.ping.refused", target.host() + ":" + target.port()));
        } catch (BadAnswer e) {
            return ServerStatus.offline(I18n.tr("project.ping.badAnswer", e.getMessage()));
        } catch (IOException | RuntimeException e) {
            LOG.fine("Ping of " + address + " failed: " + e);
            return ServerStatus.offline(I18n.tr("project.ping.failed", String.valueOf(e.getMessage())));
        }
    }

    private static ServerStatus exchange(Socket socket, HostPort target, long deadline) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = new java.io.BufferedInputStream(socket.getInputStream());
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        writeVarInt(handshake, 0x00);
        writeVarInt(handshake, PROTOCOL_QUERY);
        writeString(handshake, target.host());
        handshake.write((target.port() >>> 8) & 0xff);
        handshake.write(target.port() & 0xff);
        writeVarInt(handshake, 1);
        ByteArrayOutputStream packets = new ByteArrayOutputStream();
        writePacket(packets, handshake.toByteArray());
        writePacket(packets, new byte[] {0x00});
        long sent = System.nanoTime();
        out.write(packets.toByteArray());
        out.flush();

        socket.setSoTimeout(remaining(deadline));
        ByteArrayInputStream status = new ByteArrayInputStream(readPacket(in, socket, deadline));
        if (readVarInt(status) != 0x00) {
            throw new BadAnswer("unexpected packet");
        }
        String json = readString(status);
        long statusRtt = (System.nanoTime() - sent) / 1_000_000L;
        ServerStatus parsed = parseStatus(json);

        long latency = statusRtt;
        try {
            long payload = System.currentTimeMillis();
            ByteArrayOutputStream ping = new ByteArrayOutputStream();
            writeVarInt(ping, 0x01);
            for (int i = 7; i >= 0; i--) {
                ping.write((int) (payload >>> (8 * i)) & 0xff);
            }
            ByteArrayOutputStream framed = new ByteArrayOutputStream();
            writePacket(framed, ping.toByteArray());
            long t0 = System.nanoTime();
            out.write(framed.toByteArray());
            out.flush();
            socket.setSoTimeout(remaining(deadline));
            ByteArrayInputStream pong = new ByteArrayInputStream(readPacket(in, socket, deadline));
            if (readVarInt(pong) == 0x01) {
                long echoed = 0;
                for (int i = 0; i < 8; i++) {
                    int b = pong.read();
                    if (b < 0) {
                        throw new EOFException();
                    }
                    echoed = (echoed << 8) | b;
                }
                if (echoed == payload) {
                    latency = (System.nanoTime() - t0) / 1_000_000L;
                }
            }
        } catch (IOException e) {
            // some servers close the connection after the status; the status round trip is a fair estimate
            LOG.fine("No pong from " + target.host() + ": " + e.getMessage());
        }
        return new ServerStatus(true, parsed.playersOnline(), parsed.playersMax(), parsed.motd(), parsed.versionName(),
            parsed.protocol(), latency, parsed.faviconBase64(), null, parsed.motdSpans());
    }

    /** parse the status JSON ({@code version}, {@code players}, {@code description}, {@code favicon}) */
    static ServerStatus parseStatus(String json) throws BadAnswer {
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                throw new BadAnswer("status is not a JSON object");
            }
            o = e.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new BadAnswer("status is not valid JSON");
        }
        JsonObject version = object(o, "version");
        JsonObject players = object(o, "players");
        StringBuilder motd = new StringBuilder();
        flatten(o.get("description"), motd, 0);
        String text = FORMATTING.matcher(motd).replaceAll("");
        if (text.length() > 2048) {
            text = text.substring(0, 2048);
        }
        List<MotdSpan> spans = MotdSpan.fromComponent(o.get("description"));
        return new ServerStatus(true, integer(players, "online", 0), integer(players, "max", 0), text.strip(),
            FORMATTING.matcher(string(version, "name")).replaceAll(""), integer(version, "protocol", -1), -1,
            cleanFavicon(string(o, "favicon")), null, spans);
    }

    /** a chat component (string, object with text/translate/extra, or array) as plain text */
    private static void flatten(JsonElement e, StringBuilder sb, int depth) {
        if (e == null || e.isJsonNull() || depth > 64 || sb.length() > 8192) {
            return;
        }
        if (e.isJsonPrimitive()) {
            sb.append(e.getAsString());
            return;
        }
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                flatten(x, sb, depth + 1);
            }
            return;
        }
        JsonObject o = e.getAsJsonObject();
        if (o.has("text") && o.get("text").isJsonPrimitive()) {
            sb.append(o.get("text").getAsString());
        } else if (o.has("fallback") && o.get("fallback").isJsonPrimitive()) {
            sb.append(o.get("fallback").getAsString());
        } else if (o.has("translate") && o.get("translate").isJsonPrimitive()) {
            sb.append(o.get("translate").getAsString());
        }
        if (o.has("extra")) {
            flatten(o.get("extra"), sb, depth + 1);
        }
    }

    /** the base64 part of a "data:image/png;base64,..." favicon, or null when it isn't valid */
    static String cleanFavicon(String favicon) {
        if (favicon == null || favicon.isBlank()) {
            return null;
        }
        String s = favicon.strip();
        int comma = s.indexOf(',');
        if (s.startsWith("data:")) {
            if (comma < 0) {
                return null;
            }
            s = s.substring(comma + 1);
        }
        s = s.replaceAll("\\s+", "");
        return s.isEmpty() || !isBase64(s) ? null : s;
    }

    private static JsonObject object(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static int integer(JsonObject o, String key, int def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---- addresses and SRV ----------------------------------------------------------------------------

    /**
     * Parse {@code host}, {@code host:port}, {@code [ipv6]}, {@code [ipv6]:port} or a bare IPv6 address.
     * Throws {@link IllegalArgumentException} for anything else.
     */
    static HostPort parseAddress(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("no address");
        }
        String s = raw.strip();
        if (s.isEmpty() || s.length() > 300 || s.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("bad address");
        }
        String host;
        String port = null;
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end < 0) {
                throw new IllegalArgumentException("unclosed bracket");
            }
            host = s.substring(1, end);
            String rest = s.substring(end + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) {
                    throw new IllegalArgumentException("text after bracket");
                }
                port = rest.substring(1);
            }
            if (!host.contains(":")) {
                throw new IllegalArgumentException("not an IPv6 address");
            }
        } else {
            int first = s.indexOf(':');
            if (first < 0) {
                host = s;
            } else if (first == s.lastIndexOf(':')) {
                host = s.substring(0, first);
                port = s.substring(first + 1);
            } else {
                host = s; // a bare IPv6 address
            }
        }
        if (host.isEmpty() || host.length() > 255 || host.contains("/") || host.contains("[") || host.contains("]")) {
            throw new IllegalArgumentException("bad host");
        }
        if (port == null) {
            return new HostPort(host, DEFAULT_PORT, false);
        }
        int p;
        try {
            p = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad port");
        }
        if (p < 1 || p > 65535) {
            throw new IllegalArgumentException("port out of range");
        }
        return new HostPort(host, p, true);
    }

    static boolean isIpLiteral(String host) {
        return host.contains(":") || IPV4.matcher(host).matches();
    }

    /** the real SRV lookup through JNDI DNS (module jdk.naming.dns); empty on any failure */
    static Optional<HostPort> lookupSrv(String host, long timeoutMillis) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put(Context.PROVIDER_URL, "dns:");
        env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(Math.max(100, Math.min(timeoutMillis, 2000))));
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes("_minecraft._tcp." + host, new String[] {"SRV"});
            Attribute a = attrs == null ? null : attrs.get("SRV");
            if (a == null) {
                return Optional.empty();
            }
            List<String> records = new ArrayList<>();
            NamingEnumeration<?> all = a.getAll();
            while (all.hasMore()) {
                records.add(String.valueOf(all.next()));
            }
            return pickSrv(records);
        } catch (NamingException | RuntimeException e) {
            LOG.fine("No SRV record for " + host + ": " + e.getMessage());
            return Optional.empty();
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (NamingException ignored) {
                    // nothing to do
                }
            }
        }
    }

    /** choose from "priority weight port target." records: lowest priority, then highest weight */
    static Optional<HostPort> pickSrv(List<String> records) {
        HostPort best = null;
        int bestPriority = Integer.MAX_VALUE;
        int bestWeight = -1;
        for (String r : records) {
            String[] f = r.strip().split("\\s+");
            if (f.length != 4) {
                continue;
            }
            try {
                int priority = Integer.parseInt(f[0]);
                int weight = Integer.parseInt(f[1]);
                int port = Integer.parseInt(f[2]);
                String target = f[3].endsWith(".") ? f[3].substring(0, f[3].length() - 1) : f[3];
                if (port < 1 || port > 65535 || target.isEmpty()) {
                    continue;
                }
                if (priority < bestPriority || (priority == bestPriority && weight > bestWeight)) {
                    best = new HostPort(target, port, true);
                    bestPriority = priority;
                    bestWeight = weight;
                }
            } catch (NumberFormatException e) {
                // skip a malformed record
            }
        }
        return Optional.ofNullable(best);
    }

    // ---- wire format ----------------------------------------------------------------------------------

    /** a malformed or unexpected answer from the server */
    static final class BadAnswer extends IOException {
        private static final long serialVersionUID = 1L;

        BadAnswer(String message) {
            super(message);
        }
    }

    static void writeVarInt(OutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7f) != 0) {
            out.write((v & 0x7f) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("connection closed");
            }
            value |= (b & 0x7f) << (7 * i);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new BadAnswer("VarInt too long");
    }

    private static void writeString(OutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }

    private static String readString(InputStream in) throws IOException {
        int len = readVarInt(in);
        if (len < 0 || len > MAX_PACKET) {
            throw new BadAnswer("bad string length");
        }
        byte[] b = in.readNBytes(len);
        if (b.length != len) {
            throw new BadAnswer("truncated string");
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void writePacket(OutputStream out, byte[] body) throws IOException {
        writeVarInt(out, body.length);
        out.write(body);
    }

    private static byte[] readPacket(InputStream in, Socket socket, long deadline) throws IOException {
        int len = readVarInt(in);
        if (len <= 0 || len > MAX_PACKET) {
            throw new BadAnswer("bad packet length " + len);
        }
        byte[] data = new byte[len];
        int off = 0;
        while (off < len) {
            socket.setSoTimeout(remaining(deadline));
            int n = in.read(data, off, len - off);
            if (n < 0) {
                throw new EOFException("connection closed");
            }
            off += n;
        }
        return data;
    }

    private static int remaining(long deadline) throws SocketTimeoutException {
        long ms = (deadline - System.nanoTime()) / 1_000_000L;
        if (ms <= 0) {
            throw new SocketTimeoutException("deadline passed");
        }
        return (int) Math.min(Integer.MAX_VALUE, ms);
    }
}

package dev.shardlight.client.download;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.net.HttpService;
import dev.shardlight.client.task.CancellationToken;
import dev.shardlight.client.task.ProgressListener;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.HashAlgo;
import dev.shardlight.client.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The download queue. Every file is fetched to {@code <target>.part}, checked against its size and hash,
 * and only then moved onto its target name, so a half-finished or corrupted download never looks
 * installed. Interrupted downloads resume with an HTTP range request when the server supports it.
 * Failed attempts are retried with backoff and mirror fallback; a checksum mismatch deletes the partial
 * file before retrying.
 *
 * <p>Verified files with a SHA-1 can be shared through a content-addressed cache ({@code files/ab/abc...})
 * so the same mod in two projects is downloaded once (hard links where the file system allows).
 */
public final class DownloadManager implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(DownloadManager.class.getName());
    private static final int KEEP_FINISHED = 400;
    private static final long NOTIFY_INTERVAL_NANOS = 200_000_000L;

    private final HttpService http;
    private final Path cacheDir;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("download-", 0).factory());
    private final Object slotLock = new Object();
    private int maxConcurrent;
    private int running;
    private volatile int maxRetries;
    private volatile long backoffMillis = 1000;
    private final Map<String, DownloadItem> items = new LinkedHashMap<>();
    private final List<DownloadListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, CancellationToken> itemCancels = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    /**
     * @param cacheDir shared verified-file cache, or null for none
     */
    public DownloadManager(HttpService http, Path cacheDir, int maxConcurrent, int maxRetries) {
        this.http = http;
        this.cacheDir = cacheDir;
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.maxRetries = Math.max(0, maxRetries);
    }

    public void setLimits(int concurrent, int retries) {
        synchronized (slotLock) {
            maxConcurrent = Math.max(1, Math.min(16, concurrent));
            slotLock.notifyAll();
        }
        maxRetries = Math.max(0, Math.min(10, retries));
    }

    /** base delay between attempts (doubles each retry); tests shorten it */
    public void setBackoffMillis(long ms) {
        backoffMillis = Math.max(0, ms);
    }

    public void addListener(DownloadListener l) {
        listeners.add(l);
    }

    public void removeListener(DownloadListener l) {
        listeners.remove(l);
    }

    /** every active item plus recently finished ones, oldest first */
    public List<DownloadItem> items() {
        synchronized (items) {
            return new ArrayList<>(items.values());
        }
    }

    /** forget finished items (the Downloads page "Clear" button) */
    public void clearFinished() {
        synchronized (items) {
            items.values().removeIf(i -> i.state().isFinished());
        }
    }

    /** cancel one queued or running item */
    public void cancel(String itemId) {
        CancellationToken t = itemCancels.get(itemId);
        if (t != null) {
            t.cancel();
        }
    }

    /** download a failed or cancelled item again, as a new item */
    public DownloadJob retry(String itemId) {
        DownloadItem old;
        synchronized (items) {
            old = items.get(itemId);
        }
        if (old == null || !(old.state() == DownloadState.FAILED || old.state() == DownloadState.CANCELLED)) {
            return null;
        }
        return submit(old.group(), List.of(old.request()), new CancellationToken());
    }

    /** queue a batch; returns at once */
    public DownloadJob submit(String group, Collection<DownloadRequest> requests, CancellationToken cancel) {
        List<DownloadItem> batch = new ArrayList<>();
        Map<Path, DownloadItem> byTarget = new LinkedHashMap<>();
        for (DownloadRequest r : requests) {
            Path key = r.target().toAbsolutePath().normalize();
            if (byTarget.containsKey(key)) {
                continue; // the same file listed twice (shared libraries): fetch once
            }
            DownloadItem item = new DownloadItem("d" + ids.incrementAndGet(), group, r);
            byTarget.put(key, item);
            batch.add(item);
        }
        synchronized (items) {
            for (DownloadItem i : batch) {
                items.put(i.id(), i);
            }
            trimFinished();
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (DownloadItem item : batch) {
            CancellationToken own = new CancellationToken();
            cancel.onCancel(own::cancel);
            itemCancels.put(item.id(), own);
            notifyNow(item);
            futures.add(CompletableFuture.runAsync(() -> run(item, own), executor)
                .whenComplete((v, t) -> itemCancels.remove(item.id())));
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        return new DownloadJob(group, batch, all, cancel);
    }

    /** queue a batch and wait for it, reporting files done/total under {@code stageKey} */
    public void downloadAll(String group, Collection<DownloadRequest> requests, String stageKey, ProgressListener progress,
                            CancellationToken cancel) throws LauncherException {
        if (requests.isEmpty()) {
            return;
        }
        submit(group, requests, cancel).await(progress, stageKey);
    }

    /** true when {@code target} exists and matches the request's size and hash */
    public static boolean isValid(DownloadRequest r) {
        return isValid(r.target(), r);
    }

    private static boolean isValid(Path file, DownloadRequest r) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            if (r.size() >= 0 && Files.size(file) != r.size()) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        if (r.hasHash()) {
            return Hashing.matches(file, r.hashAlgo(), r.hash());
        }
        // nothing to check against: an existing file with the right (or unknown) size is accepted
        return true;
    }

    // ---------------------------------------------------------------- worker

    private void run(DownloadItem item, CancellationToken cancel) {
        DownloadRequest r = item.request();
        try {
            if (cancel.isCancelled()) {
                item.state(DownloadState.CANCELLED);
                return;
            }
            if (isValid(r)) {
                setExecutable(r);
                item.bytes(sizeOf(r.target()), sizeOf(r.target()));
                item.state(DownloadState.SKIPPED);
                return;
            }
            if (fromCache(r)) {
                item.bytes(sizeOf(r.target()), sizeOf(r.target()));
                item.state(DownloadState.SKIPPED);
                return;
            }
            acquireSlot(cancel);
            try {
                download(item, cancel);
            } finally {
                releaseSlot();
            }
            toCache(r);
            item.state(DownloadState.DONE);
        } catch (LauncherException e) {
            if (e.isCancelled()) {
                item.state(DownloadState.CANCELLED);
            } else {
                item.error(e);
                item.state(DownloadState.FAILED);
                LOG.log(Level.WARNING, "Download failed: " + r.label() + " from " + item.currentUrl() + ": " + e.getMessage());
            }
        } catch (RuntimeException e) {
            item.error(new LauncherException(ErrorCode.INTERNAL, e, "error.internal", String.valueOf(e)));
            item.state(DownloadState.FAILED);
            LOG.log(Level.WARNING, "Download crashed: " + r.label(), e);
        } finally {
            notifyNow(item);
        }
    }

    private void download(DownloadItem item, CancellationToken cancel) throws LauncherException {
        DownloadRequest r = item.request();
        List<URI> urls = new ArrayList<>();
        urls.add(r.url());
        urls.addAll(r.mirrors());
        int maxAttempts = maxRetries + 1;
        LauncherException last = null;
        Path part = r.target().resolveSibling(r.target().getFileName() + ".part");
        for (URI url : urls) {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                cancel.check();
                item.currentUrl(url);
                item.attempt();
                item.state(DownloadState.RUNNING);
                notifyNow(item);
                try {
                    fetch(item, url, part, cancel);
                    item.state(DownloadState.VERIFYING);
                    notifyNow(item);
                    verify(r, part);
                    Files.createDirectories(r.target().toAbsolutePath().getParent());
                    AtomicFiles.moveReplacing(part, r.target());
                    setExecutable(r);
                    return;
                } catch (IOException e) {
                    last = LauncherException.io(e, r.target().toString());
                    break; // local disk trouble: another attempt won't help
                } catch (LauncherException e) {
                    if (e.isCancelled()) {
                        throw e;
                    }
                    last = e;
                    if (e.code() == ErrorCode.CHECKSUM_MISMATCH) {
                        deleteQuietly(part);
                    }
                    if (isPermanent(e)) {
                        deleteQuietly(part);
                        break; // try the next mirror
                    }
                    if (attempt < maxAttempts) {
                        sleep(backoffMillis * (1L << Math.min(5, attempt - 1)), cancel);
                    }
                }
            }
        }
        throw last == null ? LauncherException.internal("download without attempts") : last;
    }

    /** errors that another attempt at the same URL can't fix */
    private static boolean isPermanent(LauncherException e) {
        return switch (e.code()) {
            case NOT_FOUND, UNSAFE_PATH -> true;
            case HTTP_STATUS -> e.args().length > 1 && e.args()[1] instanceof Integer s && s >= 400 && s < 500 && s != 408;
            default -> false;
        };
    }

    private void fetch(DownloadItem item, URI url, Path part, CancellationToken cancel) throws LauncherException {
        DownloadRequest r = item.request();
        try {
            Files.createDirectories(part.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw LauncherException.io(e, part.getParent().toString());
        }
        long existing = sizeOf(part);
        if (existing > 0 && r.size() > 0 && existing >= r.size()) {
            // a complete .part from an earlier run: verify it instead of fetching again
            item.bytes(existing, r.size());
            return;
        }
        Map<String, String> headers = existing > 0 ? Map.of("Range", "bytes=" + existing + "-") : Map.of();
        HttpResponse<InputStream> resp = http.openStream(url, headers, Duration.ofMinutes(5), cancel);
        int status = resp.statusCode();
        boolean append;
        if (status == 206 && existing > 0) {
            append = true;
        } else if (status == 200) {
            append = false;
            existing = 0;
        } else if (status == 416) {
            closeQuietly(resp.body());
            deleteQuietly(part);
            throw new LauncherException(ErrorCode.HTTP_STATUS, "error.http.status", url.getHost(), status);
        } else {
            closeQuietly(resp.body());
            throw statusError(status, url);
        }
        long total = r.size() > 0 ? r.size() : resp.headers().firstValueAsLong("Content-Length").orElse(-1) + (append ? existing : 0);
        item.bytes(existing, total);
        long done = existing;
        long lastNotify = System.nanoTime();
        long windowStart = lastNotify;
        long windowBytes = 0;
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                 append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
            cancel.onCancel(() -> closeQuietly(in));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                windowBytes += n;
                if (r.size() > 0 && done > r.size() + 1024) {
                    throw new LauncherException(ErrorCode.CHECKSUM_MISMATCH, "error.checksum", r.label());
                }
                long now = System.nanoTime();
                if (now - lastNotify >= NOTIFY_INTERVAL_NANOS) {
                    long dt = now - windowStart;
                    if (dt > 0) {
                        item.speed((long) (windowBytes * 1e9 / dt));
                    }
                    if (dt > 2_000_000_000L) {
                        windowStart = now;
                        windowBytes = 0;
                    }
                    item.bytes(done, total);
                    notifyNow(item);
                    lastNotify = now;
                }
                if (cancel.isCancelled()) {
                    throw LauncherException.cancelled();
                }
            }
        } catch (IOException e) {
            if (cancel.isCancelled()) {
                throw LauncherException.cancelled();
            }
            throw new LauncherException(ErrorCode.NETWORK, e, "error.network", url.getHost(), String.valueOf(e.getMessage()));
        }
        item.bytes(done, total);
    }

    private static void verify(DownloadRequest r, Path part) throws LauncherException {
        try {
            long size = Files.size(part);
            if (r.size() >= 0 && size != r.size()) {
                throw new LauncherException(ErrorCode.CHECKSUM_MISMATCH, "error.checksum", r.label());
            }
            if (r.hasHash() && !Hashing.hash(part, r.hashAlgo()).equalsIgnoreCase(r.hash().trim())) {
                throw new LauncherException(ErrorCode.CHECKSUM_MISMATCH, "error.checksum", r.label());
            }
        } catch (IOException e) {
            throw LauncherException.io(e, part.toString());
        }
    }

    // ---------------------------------------------------------------- shared cache

    private Path cachePath(DownloadRequest r) {
        if (cacheDir == null || !r.cacheable() || r.hashAlgo() != HashAlgo.SHA1 || !HashAlgo.SHA1.isValidHex(r.hash())) {
            return null;
        }
        String h = Hashing.normalize(r.hash());
        return cacheDir.resolve(h.substring(0, 2)).resolve(h);
    }

    private boolean fromCache(DownloadRequest r) {
        Path c = cachePath(r);
        if (c == null || !isValid(c, r)) {
            return false;
        }
        try {
            Files.createDirectories(r.target().toAbsolutePath().getParent());
            Path tmp = r.target().resolveSibling(r.target().getFileName() + ".part");
            Files.deleteIfExists(tmp);
            try {
                Files.createLink(tmp, c);
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(c, tmp);
            }
            AtomicFiles.moveReplacing(tmp, r.target());
            setExecutable(r);
            return true;
        } catch (IOException e) {
            LOG.fine(() -> "Cache copy failed for " + r.label() + ": " + e);
            return false;
        }
    }

    private void toCache(DownloadRequest r) {
        Path c = cachePath(r);
        if (c == null || Files.exists(c)) {
            return;
        }
        try {
            Files.createDirectories(c.getParent());
            try {
                Files.createLink(c, r.target());
            } catch (FileAlreadyExistsException e) {
                // another thread cached it first
            } catch (IOException | UnsupportedOperationException e) {
                Path tmp = c.resolveSibling(c.getFileName() + ".tmp" + Thread.currentThread().threadId());
                Files.copy(r.target(), tmp);
                AtomicFiles.moveReplacing(tmp, c);
            }
        } catch (IOException e) {
            LOG.fine(() -> "Couldn't cache " + r.label() + ": " + e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void acquireSlot(CancellationToken cancel) throws LauncherException {
        synchronized (slotLock) {
            while (running >= maxConcurrent) {
                cancel.check();
                try {
                    slotLock.wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw LauncherException.cancelled();
                }
            }
            running++;
        }
    }

    private void releaseSlot() {
        synchronized (slotLock) {
            running--;
            slotLock.notifyAll();
        }
    }

    private void trimFinished() {
        long finished = items.values().stream().filter(i -> i.state().isFinished()).count();
        Iterator<DownloadItem> it = items.values().iterator();
        while (finished > KEEP_FINISHED && it.hasNext()) {
            if (it.next().state().isFinished()) {
                it.remove();
                finished--;
            }
        }
    }

    private void notifyNow(DownloadItem item) {
        for (DownloadListener l : listeners) {
            try {
                l.changed(item);
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Download listener failed", e);
            }
        }
    }

    private static void setExecutable(DownloadRequest r) {
        if (r.executable()) {
            r.target().toFile().setExecutable(true, false);
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.size(p) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static void sleep(long ms, CancellationToken cancel) throws LauncherException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            cancel.check();
            try {
                Thread.sleep(Math.min(100, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw LauncherException.cancelled();
            }
        }
    }

    private static LauncherException statusError(int status, URI url) {
        if (status == 404 || status == 410) {
            return new LauncherException(ErrorCode.NOT_FOUND, "error.http.notFound", url.getHost(), url.getPath());
        }
        if (status == 429) {
            return new LauncherException(ErrorCode.RATE_LIMITED, "error.http.rateLimited", url.getHost());
        }
        return new LauncherException(ErrorCode.HTTP_STATUS, "error.http.status", url.getHost(), status);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}

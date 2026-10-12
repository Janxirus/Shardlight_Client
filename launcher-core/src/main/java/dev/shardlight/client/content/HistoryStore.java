package dev.shardlight.client.content;

import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.util.AtomicFiles;
import dev.shardlight.client.util.PathSafety;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Persistence of a project's content history: {@code .history/history.json} (oldest event first) plus one
 * folder per event holding the file that event took away. Callers hold the project's content lock.
 */
final class HistoryStore {
    static final String DIR = ".history";
    static final String FILE = "history.json";

    /** the stored document */
    static final class HistoryFile {
        int schemaVersion = 1;
        List<HistoryEvent> events = new ArrayList<>();

        HistoryFile() {
        }
    }

    private HistoryStore() {
    }

    static Path dir(Path projectDir) {
        return projectDir.resolve(DIR);
    }

    /** events, oldest first; a corrupt file is set aside and an empty history returned */
    static List<HistoryEvent> load(Path projectDir) throws LauncherException {
        Path f = dir(projectDir).resolve(FILE);
        AtomicFiles.ReadResult<HistoryFile> r = AtomicFiles.readJson(f, HistoryFile.class, HistoryFile::new);
        List<HistoryEvent> out = new ArrayList<>();
        if (r.value().events != null) {
            for (HistoryEvent e : r.value().events) {
                if (e != null && e.id() != null && PathSafety.isSafeSegment(e.id()) && e.action() != null) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    static void save(Path projectDir, List<HistoryEvent> events) throws LauncherException {
        HistoryFile hf = new HistoryFile();
        hf.events = new ArrayList<>(events);
        AtomicFiles.writeJson(dir(projectDir).resolve(FILE), hf);
    }

    /**
     * Drop the oldest events until at most {@code maxEvents} remain and their stored files take at most
     * {@code maxBytes} (the newest event is always kept). Returns the dropped events; {@code events} is
     * changed in place.
     */
    static List<HistoryEvent> prune(Path projectDir, List<HistoryEvent> events, int maxEvents, long maxBytes) {
        List<HistoryEvent> dropped = new ArrayList<>();
        while (events.size() > Math.max(1, maxEvents)) {
            dropped.add(events.remove(0));
        }
        long[] sizes = new long[events.size()];
        long total = 0;
        for (int i = 0; i < events.size(); i++) {
            sizes[i] = ContentFiles.sizeOf(dir(projectDir).resolve(events.get(i).id()));
            total += sizes[i];
        }
        int i = 0;
        while (total > maxBytes && events.size() > 1) {
            dropped.add(events.remove(0));
            total -= sizes[i++];
        }
        return dropped;
    }

    /**
     * Delete the folders of dropped events. Folders no event refers to are left alone: they can only come
     * from an operation that failed half-way, and may hold the user's only copy of a file.
     */
    static void deleteStored(Path projectDir, Collection<HistoryEvent> dropped) {
        Path root = dir(projectDir);
        for (HistoryEvent e : dropped) {
            ContentFiles.deleteTree(root.resolve(e.id()));
        }
    }
}

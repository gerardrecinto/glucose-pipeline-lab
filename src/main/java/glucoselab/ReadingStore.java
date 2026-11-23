package glucoselab;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only JSONL log plus an in-memory index per subject. Ingest is idempotent on (subject, ts),
 * so a client can retry a whole batch after a timeout. On start the log is replayed to rebuild the index.
 */
public final class ReadingStore {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path log;
    private final Map<String, TreeMap<Long, Reading>> bySubject = new HashMap<>();
    private final Map<String, AtomicLong> rejectedByReason = new HashMap<>();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();

    public ReadingStore(Path log) {
        this.log = log;
        if (log != null && Files.exists(log)) replay();
    }

    private void replay() {
        try (BufferedReader in = Files.newBufferedReader(log, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                Reading r = JSON.readValue(line, Reading.class);
                bySubject.computeIfAbsent(r.subjectId(), k -> new TreeMap<>()).putIfAbsent(r.ts(), r);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not replay " + log, e);
        }
    }

    public synchronized IngestResult ingest(List<Reading> rows, long nowSeconds) {
        int acc = 0;
        int dup = 0;
        List<IngestResult.Rejection> rejected = new ArrayList<>();
        List<Reading> toWrite = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Reading r = rows.get(i);
            var reason = Validator.check(r, nowSeconds);
            if (reason.isPresent()) {
                rejected.add(new IngestResult.Rejection(i, reason.get()));
                rejectedByReason.computeIfAbsent(reason.get(), k -> new AtomicLong()).incrementAndGet();
                continue;
            }
            TreeMap<Long, Reading> series = bySubject.computeIfAbsent(r.subjectId(), k -> new TreeMap<>());
            if (series.putIfAbsent(r.ts(), r) != null) {
                dup++;
            } else {
                acc++;
                toWrite.add(r);
            }
        }
        append(toWrite);
        accepted.addAndGet(acc);
        duplicates.addAndGet(dup);
        return new IngestResult(acc, dup, rejected);
    }

    private void append(List<Reading> rows) {
        if (log == null || rows.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        try {
            for (Reading r : rows) sb.append(JSON.writeValueAsString(r)).append('\n');
            Files.writeString(log, sb, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("could not append to " + log, e);
        }
    }

    public synchronized List<Reading> range(String subject, long fromTs, long toTs) {
        TreeMap<Long, Reading> s = bySubject.get(subject);
        if (s == null) return List.of();
        return new ArrayList<>(s.subMap(fromTs, true, toTs, true).values());
    }

    public synchronized Long latestTs(String subject) {
        TreeMap<Long, Reading> s = bySubject.get(subject);
        return s == null || s.isEmpty() ? null : s.lastKey();
    }

    public synchronized boolean knows(String subject) {
        return bySubject.containsKey(subject);
    }

    /** Data lifecycle: drop readings older than the cutoff from memory and rewrite the log. Returns rows removed. */
    public synchronized int purgeOlderThan(long cutoffTs) {
        int removed = 0;
        for (TreeMap<Long, Reading> s : bySubject.values()) {
            var old = s.headMap(cutoffTs, false);
            removed += old.size();
            old.clear();
        }
        if (log != null && removed > 0) {
            List<Reading> all = new ArrayList<>();
            bySubject.values().forEach(s -> all.addAll(s.values()));
            try {
                Files.deleteIfExists(log);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            append(all);
        }
        return removed;
    }

    public long acceptedTotal() { return accepted.get(); }
    public long duplicatesTotal() { return duplicates.get(); }

    public synchronized Map<String, Long> rejectedTotals() {
        Map<String, Long> out = new TreeMap<>();
        rejectedByReason.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }
}

package com.omnibank.appmaprec.internal;

import com.omnibank.appmaprec.api.Recording;
import com.omnibank.appmaprec.api.RecordingId;
import com.omnibank.appmaprec.api.RecordingService;
import com.omnibank.appmaprec.api.RecordingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-process registry of interactive AppMap recordings.
 *
 * <p>State transitions are append-only: every observable change produces a
 * new {@link Recording} snapshot stored in {@link #snapshots}. The latest
 * snapshot per id is what {@link #get(RecordingId)} returns; the audit
 * trail is kept around so future ops UI surfaces can show a state
 * timeline without needing a separate database. (We keep the trail
 * bounded — see {@link #MAX_SNAPSHOTS_PER_RECORDING}.)
 */
public class InMemoryRecordingService implements RecordingService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRecordingService.class);
    private static final int MAX_SNAPSHOTS_PER_RECORDING = 32;
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MIN_FMT = DateTimeFormatter.ofPattern("HHmm");

    private final AppMapAgentBridge agent;
    private final RecordingArchive archive;
    private final Clock clock;
    private final Map<String, List<Recording>> snapshots = new ConcurrentHashMap<>();
    private final AtomicLong syntheticEventCounter = new AtomicLong();

    public InMemoryRecordingService(AppMapAgentBridge agent,
                                    RecordingArchive archive,
                                    Clock clock) {
        this.agent = agent;
        this.archive = archive;
        this.clock = clock;
    }

    @Override
    public boolean recordingEnabled() {
        return agent.isOperative();
    }

    @Override
    public Recording start(String label, String description) {
        if (!recordingEnabled()) {
            throw new IllegalStateException(
                    "AppMap recording is not enabled in this JVM. " +
                    "Restart with the AppMap agent attached or set " +
                    "omnibank.appmap.synthetic-recording=true to drive the UI without it.");
        }
        ZonedDateTime nowZ = ZonedDateTime.ofInstant(Instant.now(clock), ZoneId.systemDefault());
        RecordingId id = RecordingId.newId(nowZ.format(DAY_FMT), nowZ.format(MIN_FMT));

        agent.start(id.value());

        Recording fresh = new Recording(
                id,
                normaliseLabel(label, id),
                description == null ? "" : description.trim(),
                RecordingStatus.RECORDING,
                Instant.now(clock),
                Optional.empty(),
                Optional.empty(),
                0,
                List.of(),
                Optional.empty()
        );
        push(fresh);
        log.info("Started AppMap recording {} (label='{}')", id, fresh.label());
        return fresh;
    }

    @Override
    public Recording recordAction(RecordingId id,
                                  String kind,
                                  String description,
                                  Optional<String> reference) {
        Recording current = requireActive(id);
        var actions = new ArrayList<>(current.actions());
        actions.add(new Recording.RecordedAction(
                Instant.now(clock),
                kind,
                description,
                reference == null ? Optional.empty() : reference
        ));
        // Synthetic-mode bookkeeping — the agent does not actually capture
        // events here, but we increment a counter so the UI surfaces a
        // non-zero "events" reading rather than always showing zero.
        long events = current.capturedEvents();
        if (!agent.usesRealAgent()) {
            events += syntheticEventCounter.incrementAndGet() % 7 + 3;
        }
        Recording next = new Recording(
                current.id(),
                current.label(),
                current.description(),
                current.status(),
                current.startedAt(),
                current.stoppedAt(),
                current.savedFile(),
                events,
                actions,
                current.failureReason()
        );
        push(next);
        return next;
    }

    @Override
    public Recording stop(RecordingId id) {
        Recording current = requireActive(id);
        long events = agent.stop(id.value());
        if (events == 0 && !agent.usesRealAgent()) {
            events = current.capturedEvents();
        }
        Recording next = new Recording(
                current.id(),
                current.label(),
                current.description(),
                RecordingStatus.STOPPED,
                current.startedAt(),
                Optional.of(Instant.now(clock)),
                current.savedFile(),
                events,
                current.actions(),
                current.failureReason()
        );
        push(next);
        log.info("Stopped AppMap recording {} after {} actions, {} events",
                id, current.actions().size(), events);
        return next;
    }

    @Override
    public Recording save(RecordingId id) {
        Recording current = get(id).orElseThrow(() -> unknown(id));
        if (current.status().isTerminal()) {
            return current;
        }
        if (current.status() == RecordingStatus.RECORDING) {
            current = stop(id);
        }
        String filename = id.safeFileName();
        java.nio.file.Path target = archive.resolveFor(filename);
        boolean saved = agent.save(id.value(), target);
        Recording next = new Recording(
                current.id(),
                current.label(),
                current.description(),
                saved ? RecordingStatus.SAVED : RecordingStatus.FAILED,
                current.startedAt(),
                current.stoppedAt(),
                saved ? Optional.of(filename) : Optional.empty(),
                current.capturedEvents(),
                current.actions(),
                saved ? Optional.empty() : Optional.of("agent did not produce a recording file")
        );
        push(next);
        if (saved) {
            log.info("Saved AppMap recording {} -> {}", id, filename);
        } else {
            log.warn("Failed to save AppMap recording {}", id);
        }
        return next;
    }

    @Override
    public Recording cancel(RecordingId id, String reason) {
        Recording current = get(id).orElseThrow(() -> unknown(id));
        if (current.status().isTerminal()) {
            return current;
        }
        try {
            agent.stop(id.value());
        } catch (RuntimeException e) {
            log.debug("agent.stop during cancel of {} ignored: {}", id, e.toString());
        }
        Recording next = new Recording(
                current.id(),
                current.label(),
                current.description(),
                RecordingStatus.CANCELLED,
                current.startedAt(),
                Optional.of(Instant.now(clock)),
                current.savedFile(),
                current.capturedEvents(),
                current.actions(),
                Optional.ofNullable(reason).filter(s -> !s.isBlank())
        );
        push(next);
        return next;
    }

    @Override
    public Optional<Recording> get(RecordingId id) {
        var trail = snapshots.get(id.value());
        if (trail == null || trail.isEmpty()) {
            return Optional.empty();
        }
        synchronized (trail) {
            return Optional.of(trail.get(trail.size() - 1));
        }
    }

    @Override
    public List<Recording> listAll() {
        var out = new ArrayList<Recording>();
        for (var trail : snapshots.values()) {
            synchronized (trail) {
                if (!trail.isEmpty()) {
                    out.add(trail.get(trail.size() - 1));
                }
            }
        }
        out.sort(Comparator.comparing(Recording::startedAt).reversed());
        return List.copyOf(out);
    }

    private Recording requireActive(RecordingId id) {
        Recording current = get(id).orElseThrow(() -> unknown(id));
        if (current.status().isTerminal()) {
            throw new IllegalStateException(
                    "Recording " + id + " is in terminal state: " + current.status());
        }
        return current;
    }

    private void push(Recording r) {
        snapshots.compute(r.id().value(), (key, list) -> {
            var working = (list == null) ? new ArrayList<Recording>() : list;
            synchronized (working) {
                working.add(r);
                while (working.size() > MAX_SNAPSHOTS_PER_RECORDING) {
                    working.remove(0);
                }
            }
            return working;
        });
    }

    private static String normaliseLabel(String label, RecordingId id) {
        if (label == null || label.isBlank()) {
            return id.value();
        }
        return label.trim().substring(0, Math.min(label.trim().length(), 80));
    }

    private static IllegalArgumentException unknown(RecordingId id) {
        return new IllegalArgumentException("Unknown recording: " + id);
    }
}

package com.omnibank.appmaprec.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Snapshot view of a single interactive AppMap recording. Carries enough
 * information to drive the recording UI without leaking persistence-layer
 * concerns (file paths, locks, controllers).
 *
 * <p>This type is immutable. Mutations go through
 * {@link com.omnibank.appmaprec.api.RecordingService} which produces a new
 * snapshot for every state transition.
 */
public record Recording(
        RecordingId id,
        String label,
        String description,
        RecordingStatus status,
        Instant startedAt,
        Optional<Instant> stoppedAt,
        Optional<String> savedFile,
        long capturedEvents,
        List<RecordedAction> actions,
        Optional<String> failureReason
) {

    public Recording {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(stoppedAt, "stoppedAt");
        Objects.requireNonNull(savedFile, "savedFile");
        Objects.requireNonNull(actions, "actions");
        Objects.requireNonNull(failureReason, "failureReason");
        if (capturedEvents < 0) {
            throw new IllegalArgumentException("capturedEvents must be >= 0: " + capturedEvents);
        }
        actions = List.copyOf(actions);
    }

    /**
     * Wall-clock duration of the recording. For active recordings the
     * caller supplies "now"; for completed recordings the stop time is
     * used regardless of {@code now}.
     */
    public Duration durationAt(Instant now) {
        Instant end = stoppedAt.orElse(now);
        return Duration.between(startedAt, end);
    }

    /**
     * Per-action narrative describing user-driven banking operations that
     * occurred during the recording. Used by the UI to present a
     * "what got captured" summary alongside raw event counts.
     */
    public record RecordedAction(
            Instant performedAt,
            String kind,
            String description,
            Optional<String> reference
    ) {

        public RecordedAction {
            Objects.requireNonNull(performedAt, "performedAt");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(reference, "reference");
            if (kind.isBlank()) {
                throw new IllegalArgumentException("RecordedAction.kind cannot be blank");
            }
        }
    }
}

package com.omnibank.appmaprec.api;

import java.util.List;
import java.util.Optional;

/**
 * Public surface used by the recording UI controllers and integration
 * tests. Implementations coordinate the in-process registry, the AppMap
 * agent, and the on-disk recording archive.
 */
public interface RecordingService {

    /**
     * Whether the AppMap agent is attached to the running JVM. The UI uses
     * this to render "recording is disabled" guidance — no recordings can
     * be started while this returns {@code false}.
     */
    boolean recordingEnabled();

    /**
     * Begin a new recording session. The label is shown in the UI list;
     * the description is free-form prose persisted alongside the saved
     * appmap JSON.
     *
     * @throws IllegalStateException if recording is not enabled in the running JVM
     */
    Recording start(String label, String description);

    /**
     * Annotate the in-progress recording with a user-driven action so the
     * resulting appmap carries a narrative beyond raw frames. No-op for
     * unknown ids.
     */
    Recording recordAction(RecordingId id,
                           String kind,
                           String description,
                           Optional<String> reference);

    /**
     * Stop capture for the given recording without persisting. Subsequent
     * {@link #save(RecordingId)} writes the JSON. Useful when the user
     * wants to inspect the captured state before committing it.
     */
    Recording stop(RecordingId id);

    /**
     * Persist the captured events to {@code tmp/appmap/}.
     */
    Recording save(RecordingId id);

    /**
     * Cancel the recording and discard captured events.
     */
    Recording cancel(RecordingId id, String reason);

    /**
     * Look up the live snapshot for a known recording.
     */
    Optional<Recording> get(RecordingId id);

    /**
     * Return all recordings tracked in this process, newest first.
     */
    List<Recording> listAll();
}

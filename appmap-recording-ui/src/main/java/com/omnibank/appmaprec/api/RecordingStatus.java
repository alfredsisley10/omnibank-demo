package com.omnibank.appmaprec.api;

/**
 * Lifecycle states of an interactive AppMap recording.
 *
 * <pre>
 *   READY ──start──▶ RECORDING ──stop──▶ STOPPED ──save──▶ SAVED
 *                        │                  │
 *                        └────cancel────────┴──▶ CANCELLED
 * </pre>
 *
 * <p>{@code READY} exists as a transient state for the brief window between
 * a recording being created in the registry and the agent reporting that
 * tracing has actually begun. Most callers will see {@code RECORDING} as
 * the first observable state.
 */
public enum RecordingStatus {
    /** Registered with the registry, agent has not confirmed start yet. */
    READY,
    /** AppMap agent is currently capturing events into this recording. */
    RECORDING,
    /** Capture has been stopped but the JSON has not yet been persisted. */
    STOPPED,
    /** Final state — JSON was written under {@code tmp/appmap/}. */
    SAVED,
    /** Final state — recording was discarded by the user before save. */
    CANCELLED,
    /** Final state — capture failed with an error. */
    FAILED;

    public boolean isTerminal() {
        return this == SAVED || this == CANCELLED || this == FAILED;
    }

    public boolean isActive() {
        return this == READY || this == RECORDING;
    }
}

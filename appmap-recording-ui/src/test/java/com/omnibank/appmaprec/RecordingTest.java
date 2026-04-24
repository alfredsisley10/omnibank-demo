package com.omnibank.appmaprec;

import com.omnibank.appmaprec.api.Recording;
import com.omnibank.appmaprec.api.RecordingId;
import com.omnibank.appmaprec.api.RecordingStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordingTest {

    @Test
    void durationAt_uses_now_for_active_recording() {
        Instant start = Instant.parse("2026-04-24T12:00:00Z");
        Recording r = baseline(start, Optional.empty());
        Duration d = r.durationAt(Instant.parse("2026-04-24T12:00:30Z"));
        assertThat(d).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void durationAt_uses_stoppedAt_for_completed_recording() {
        Instant start = Instant.parse("2026-04-24T12:00:00Z");
        Instant stop  = Instant.parse("2026-04-24T12:01:15Z");
        Recording r = baseline(start, Optional.of(stop));
        Duration d = r.durationAt(Instant.parse("2026-04-24T15:00:00Z"));
        assertThat(d).isEqualTo(Duration.ofSeconds(75));
    }

    @Test
    void rejects_negative_event_count() {
        assertThatThrownBy(() -> new Recording(
                RecordingId.of("rec-x"),
                "label",
                "",
                RecordingStatus.RECORDING,
                Instant.now(),
                Optional.empty(),
                Optional.empty(),
                -1,
                List.of(),
                Optional.empty()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actions_list_is_immutable() {
        var actions = new java.util.ArrayList<Recording.RecordedAction>();
        Recording r = new Recording(
                RecordingId.of("rec-x"),
                "lbl",
                "",
                RecordingStatus.RECORDING,
                Instant.now(),
                Optional.empty(),
                Optional.empty(),
                0,
                actions,
                Optional.empty()
        );
        actions.add(new Recording.RecordedAction(
                Instant.now(), "k", "d", Optional.empty()));
        assertThat(r.actions()).isEmpty();
    }

    @Test
    void rejects_blank_action_kind() {
        assertThatThrownBy(() -> new Recording.RecordedAction(
                Instant.now(), "  ", "d", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void status_terminal_helpers_work() {
        assertThat(RecordingStatus.SAVED.isTerminal()).isTrue();
        assertThat(RecordingStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(RecordingStatus.FAILED.isTerminal()).isTrue();
        assertThat(RecordingStatus.RECORDING.isActive()).isTrue();
        assertThat(RecordingStatus.STOPPED.isTerminal()).isFalse();
        assertThat(RecordingStatus.STOPPED.isActive()).isFalse();
    }

    private Recording baseline(Instant start, Optional<Instant> stop) {
        return new Recording(
                RecordingId.of("rec-test"),
                "lbl",
                "",
                stop.isPresent() ? RecordingStatus.STOPPED : RecordingStatus.RECORDING,
                start,
                stop,
                Optional.empty(),
                0,
                List.of(),
                Optional.empty()
        );
    }
}

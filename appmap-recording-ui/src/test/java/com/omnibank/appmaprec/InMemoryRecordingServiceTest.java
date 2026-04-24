package com.omnibank.appmaprec;

import com.omnibank.appmaprec.api.Recording;
import com.omnibank.appmaprec.api.RecordingService;
import com.omnibank.appmaprec.api.RecordingStatus;
import com.omnibank.appmaprec.internal.AppMapAgentBridge;
import com.omnibank.appmaprec.internal.InMemoryRecordingService;
import com.omnibank.appmaprec.internal.RecordingArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRecordingServiceTest {

    private Path tmpRoot;
    private RecordingService service;

    @BeforeEach
    void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("appmap-test-");
        AppMapAgentBridge bridge = new AppMapAgentBridge(true); // synthetic
        RecordingArchive archive = new RecordingArchive(tmpRoot);
        service = new InMemoryRecordingService(bridge, archive, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmpRoot != null && Files.exists(tmpRoot)) {
            try (Stream<Path> walk = Files.walk(tmpRoot)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    @Test
    void start_creates_recording_in_recording_state() {
        Recording r = service.start("scenario A", "captures the happy path");
        assertThat(r.status()).isEqualTo(RecordingStatus.RECORDING);
        assertThat(r.label()).isEqualTo("scenario A");
        assertThat(r.description()).isEqualTo("captures the happy path");
        assertThat(r.actions()).isEmpty();
        assertThat(service.listAll()).hasSize(1);
    }

    @Test
    void recordAction_appends_and_increments_synthetic_event_count() {
        Recording r = service.start("scenario", "");
        long before = r.capturedEvents();
        Recording r2 = service.recordAction(r.id(), "test.action", "did a thing", Optional.of("ref-1"));
        assertThat(r2.actions()).hasSize(1);
        assertThat(r2.capturedEvents()).isGreaterThan(before);
    }

    @Test
    void stop_then_save_writes_file_and_marks_saved() {
        Recording r = service.start("save-flow", "");
        service.recordAction(r.id(), "k", "d", Optional.empty());
        service.stop(r.id());
        Recording saved = service.save(r.id());
        assertThat(saved.status()).isEqualTo(RecordingStatus.SAVED);
        assertThat(saved.savedFile()).isPresent();
        assertThat(Files.exists(tmpRoot.resolve(saved.savedFile().get()))).isTrue();
    }

    @Test
    void save_directly_from_recording_state_stops_first() {
        Recording r = service.start("direct-save", "");
        Recording saved = service.save(r.id());
        assertThat(saved.status()).isEqualTo(RecordingStatus.SAVED);
        assertThat(saved.stoppedAt()).isPresent();
    }

    @Test
    void cancel_marks_terminal_and_blocks_further_actions() {
        Recording r = service.start("cancel-flow", "");
        Recording cancelled = service.cancel(r.id(), "user discarded");
        assertThat(cancelled.status()).isEqualTo(RecordingStatus.CANCELLED);
        assertThat(cancelled.failureReason()).contains("user discarded");
        assertThatThrownBy(() ->
                service.recordAction(r.id(), "k", "d", Optional.empty()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void save_after_cancel_is_idempotent() {
        Recording r = service.start("idempotent", "");
        service.cancel(r.id(), null);
        Recording afterSave = service.save(r.id());
        assertThat(afterSave.status()).isEqualTo(RecordingStatus.CANCELLED);
    }

    @Test
    void start_with_no_recording_capability_throws() throws IOException {
        AppMapAgentBridge offBridge = new AppMapAgentBridge(false);
        Path other = Files.createTempDirectory("appmap-off-");
        RecordingService offService = new InMemoryRecordingService(
                offBridge, new RecordingArchive(other), Clock.systemUTC());
        try {
            assertThat(offService.recordingEnabled()).isFalse();
            assertThatThrownBy(() -> offService.start("x", "y"))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            try (Stream<Path> walk = Files.walk(other)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    @Test
    void listAll_orders_newest_first() throws InterruptedException {
        Recording first = service.start("first", "");
        Thread.sleep(5);
        Recording second = service.start("second", "");
        var all = service.listAll();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).id()).isEqualTo(second.id());
        assertThat(all.get(1).id()).isEqualTo(first.id());
    }
}

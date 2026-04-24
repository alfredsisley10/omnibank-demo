package com.omnibank.appmaprec.web;

import com.omnibank.appmaprec.api.Recording;
import com.omnibank.appmaprec.api.RecordingId;
import com.omnibank.appmaprec.api.RecordingService;
import com.omnibank.appmaprec.api.RecordingStatus;
import com.omnibank.appmaprec.internal.RecordingArchive;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST surface for the interactive AppMap recording UI.
 *
 * <p>All endpoints live under {@code /api/v1/appmap/recordings/**} so the
 * existing {@code /api/**} security rules apply; the recording UI is
 * served as static HTML from {@code /appmap-ui/index.html} which is
 * publicly reachable but cannot do anything until the user authenticates
 * (the JS calls the protected REST endpoints with credentials).</p>
 *
 * <p>Failures are translated into JSON error envelopes rather than HTML
 * stack traces so the UI can render them inline.</p>
 */
@RestController
@RequestMapping("/api/v1/appmap/recordings")
public class RecordingController {

    private final RecordingService service;
    private final RecordingArchive archive;

    public RecordingController(RecordingService service, RecordingArchive archive) {
        this.service = service;
        this.archive = archive;
    }

    @GetMapping("/_status")
    public Map<String, Object> status() {
        return Map.of(
                "recordingEnabled", service.recordingEnabled(),
                "active", service.listAll().stream()
                        .filter(r -> r.status().isActive())
                        .count(),
                "total", service.listAll().size(),
                "archiveSize", archive.list().size()
        );
    }

    @GetMapping
    public List<RecordingDto> list() {
        return service.listAll().stream().map(RecordingDto::from).toList();
    }

    @PostMapping
    public ResponseEntity<RecordingDto> start(@RequestBody StartRequest req) {
        Recording r = service.start(req.label(), req.description());
        return ResponseEntity.created(java.net.URI.create(
                        "/api/v1/appmap/recordings/" + r.id().value()))
                .body(RecordingDto.from(r));
    }

    @PostMapping("/{id}/actions")
    public RecordingDto recordAction(@PathVariable String id,
                                     @RequestBody ActionRequest req) {
        Recording r = service.recordAction(
                RecordingId.of(id),
                req.kind(),
                req.description(),
                Optional.ofNullable(req.reference())
        );
        return RecordingDto.from(r);
    }

    @PostMapping("/{id}/stop")
    public RecordingDto stop(@PathVariable String id) {
        return RecordingDto.from(service.stop(RecordingId.of(id)));
    }

    @PostMapping("/{id}/save")
    public RecordingDto save(@PathVariable String id) {
        return RecordingDto.from(service.save(RecordingId.of(id)));
    }

    @PostMapping("/{id}/cancel")
    public RecordingDto cancel(@PathVariable String id, @RequestBody CancelRequest req) {
        return RecordingDto.from(service.cancel(RecordingId.of(id), req.reason()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RecordingDto> get(@PathVariable String id) {
        return service.get(RecordingId.of(id))
                .map(RecordingDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        Optional<Recording> r = service.get(RecordingId.of(id));
        if (r.isEmpty() || r.get().savedFile().isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String name = r.get().savedFile().get();
        return archive.read(name)
                .map(bytes -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + name + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/_archive/{filename:.+}")
    public ResponseEntity<Map<String, Object>> deleteArchived(@PathVariable String filename) {
        boolean removed = archive.delete(filename);
        return ResponseEntity.ok(Map.of("deleted", removed, "filename", filename));
    }

    @GetMapping("/_archive")
    public List<RecordingArchive.ArchivedFile> archive() {
        return archive.list();
    }

    public record StartRequest(String label, String description) {}

    public record ActionRequest(String kind, String description, String reference) {}

    public record CancelRequest(String reason) {}

    public record RecordingDto(
            String id,
            String label,
            String description,
            RecordingStatus status,
            Instant startedAt,
            Instant stoppedAt,
            String savedFile,
            long capturedEvents,
            long elapsedMillis,
            int actionCount,
            List<ActionDto> actions,
            String failureReason
    ) {

        public static RecordingDto from(Recording r) {
            Instant now = Instant.now();
            return new RecordingDto(
                    r.id().value(),
                    r.label(),
                    r.description(),
                    r.status(),
                    r.startedAt(),
                    r.stoppedAt().orElse(null),
                    r.savedFile().orElse(null),
                    r.capturedEvents(),
                    r.durationAt(now).toMillis(),
                    r.actions().size(),
                    r.actions().stream().map(ActionDto::from).toList(),
                    r.failureReason().orElse(null)
            );
        }
    }

    public record ActionDto(Instant performedAt, String kind, String description, String reference) {

        public static ActionDto from(Recording.RecordedAction a) {
            return new ActionDto(
                    a.performedAt(),
                    a.kind(),
                    a.description(),
                    a.reference().orElse(null)
            );
        }
    }
}

package com.omnibank.txstream.web;

import com.omnibank.appmaprec.api.RecordingId;
import com.omnibank.appmaprec.api.RecordingService;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.kafka.AppMapSpanRecorder;
import com.omnibank.txstream.api.StreamingTransaction;
import com.omnibank.txstream.api.StreamingTransactionResult;
import com.omnibank.txstream.api.StreamingTransactionService;
import com.omnibank.txstream.api.StreamingTransactionView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST surface that drives the cross-database streaming transaction
 * flow. The recording UI binds the "Trigger streaming transaction"
 * button to {@code POST /publish}; the integration tests assert against
 * the same endpoint.
 */
@RestController
@RequestMapping("/api/v1/txstream")
public class StreamingTransactionController {

    private final StreamingTransactionService service;
    private final AppMapSpanRecorder spanRecorder;
    private final RecordingService recordings;

    public StreamingTransactionController(StreamingTransactionService service,
                                          AppMapSpanRecorder spanRecorder,
                                          RecordingService recordings) {
        this.service = service;
        this.spanRecorder = spanRecorder;
        this.recordings = recordings;
    }

    @PostMapping("/publish")
    public StreamingTransactionResult publish(@RequestBody PublishRequest req) {
        StreamingTransaction tx = req.toDomain();
        StreamingTransactionResult result = service.publish(tx);
        annotate(req.recordingId(),
                "txstream.publish",
                "Published streaming tx " + tx.transactionId() + " (" + tx.type() + ")"
                        + (result.overallSuccess() ? " — ok" : " — partial: " + result.warnings()),
                Optional.of(result.traceId()));
        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<StreamingTransactionView> get(@PathVariable UUID id) {
        return service.replay(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-account/{accountNumber}")
    public List<StreamingTransactionView> byAccount(@PathVariable String accountNumber,
                                                    @RequestParam(defaultValue = "20") int limit) {
        return service.recentForAccount(accountNumber, limit);
    }

    @GetMapping("/since/{epochMillis}")
    public List<StreamingTransactionView> since(@PathVariable long epochMillis,
                                                @RequestParam(defaultValue = "20") int limit) {
        return service.seenSince(Instant.ofEpochMilli(epochMillis), limit);
    }

    @GetMapping("/spans")
    public Map<String, Object> spans(@RequestParam(defaultValue = "50") int limit) {
        return Map.of(
                "counters", spanRecorder.counters(),
                "recent", spanRecorder.recent(limit)
        );
    }

    @GetMapping("/spans/by-trace/{traceId}")
    public List<AppMapSpanRecorder.Span> spansByTrace(@PathVariable String traceId) {
        return spanRecorder.spansForTrace(traceId);
    }

    private void annotate(String recordingId, String kind, String description, Optional<String> ref) {
        if (recordingId == null || recordingId.isBlank()) return;
        try {
            recordings.recordAction(RecordingId.of(recordingId), kind, description, ref);
        } catch (RuntimeException ignore) {
            // best-effort
        }
    }

    public record PublishRequest(
            String recordingId,
            String sourceAccount,
            String destinationAccount,
            BigDecimal amount,
            CurrencyCode currency,
            StreamingTransaction.TransactionType type,
            String memo
    ) {

        public StreamingTransaction toDomain() {
            return new StreamingTransaction(
                    UUID.randomUUID(),
                    AccountNumber.of(sourceAccount),
                    AccountNumber.of(destinationAccount),
                    Money.of(amount, currency == null ? CurrencyCode.USD : currency),
                    type == null ? StreamingTransaction.TransactionType.BOOK_TRANSFER : type,
                    memo == null ? "" : memo,
                    Instant.now()
            );
        }
    }
}

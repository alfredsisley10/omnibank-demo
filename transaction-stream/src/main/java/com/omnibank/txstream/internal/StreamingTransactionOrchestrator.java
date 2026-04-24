package com.omnibank.txstream.internal;

import com.omnibank.shared.kafka.AppMapSpanRecorder;
import com.omnibank.shared.kafka.KafkaTopics;
import com.omnibank.shared.kafka.KafkaTraceContext;
import com.omnibank.shared.nosql.DocumentStore;
import com.omnibank.txstream.api.StreamingTransaction;
import com.omnibank.txstream.api.StreamingTransactionResult;
import com.omnibank.txstream.api.StreamingTransactionService;
import com.omnibank.txstream.api.StreamingTransactionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reference implementation of {@link StreamingTransactionService}.
 *
 * <p>Each {@link #publish(StreamingTransaction)} call:
 * <ol>
 *   <li>opens a SQL transaction, writes the row, commits;</li>
 *   <li>upserts the Mongo projection;</li>
 *   <li>asks the Kafka adapter to fan the event out, with a trace
 *       context that ties the call back to the AppMap recording.</li>
 * </ol>
 *
 * <p>Each leg's outcome is collected separately so callers can see
 * exactly which step failed in the result envelope. Failures in legs 2
 * and 3 do <b>not</b> revert leg 1 — that follows the existing payments
 * hub's "system of record commits first" convention.</p>
 */
@Service
public class StreamingTransactionOrchestrator implements StreamingTransactionService {

    private static final Logger log = LoggerFactory.getLogger(StreamingTransactionOrchestrator.class);
    public static final String MONGO_COLLECTION = "txstream_transactions";

    private final StreamingTransactionRepository repository;
    private final DocumentStore documents;
    private final AppMapSpanRecorder spanRecorder;
    private final KafkaPublishAdapter publisher;

    public StreamingTransactionOrchestrator(StreamingTransactionRepository repository,
                                            DocumentStore documents,
                                            AppMapSpanRecorder spanRecorder,
                                            KafkaPublishAdapter publisher) {
        this.repository = repository;
        this.documents = documents;
        this.spanRecorder = spanRecorder;
        this.publisher = publisher;
    }

    @Override
    public StreamingTransactionResult publish(StreamingTransaction tx) {
        Instant overallStart = Instant.now();
        KafkaTraceContext rootContext = KafkaTraceContext.newRoot()
                .withBaggage("tx", tx.transactionId().toString());

        StreamingTransactionResult.LegOutcome sqlLeg;
        try {
            sqlLeg = persistSqlLeg(tx, rootContext);
        } catch (RuntimeException e) {
            // SQL leg is the system of record — if it fails the entire
            // transaction is considered failed.
            return new StreamingTransactionResult(
                    tx.transactionId(),
                    false,
                    StreamingTransactionResult.LegOutcome.failure(
                            Duration.between(overallStart, Instant.now()),
                            "sql leg failed: " + e.getMessage()),
                    StreamingTransactionResult.LegOutcome.failure(Duration.ZERO, "skipped"),
                    StreamingTransactionResult.LegOutcome.failure(Duration.ZERO, "skipped"),
                    Duration.between(overallStart, Instant.now()),
                    rootContext.traceId(),
                    List.of("sql leg failed; skipping projection and Kafka emit")
            );
        }

        StreamingTransactionResult.LegOutcome mongoLeg = projectToMongo(tx, rootContext);
        StreamingTransactionResult.LegOutcome kafkaLeg = emitToKafka(tx, rootContext);

        List<String> warnings = new ArrayList<>();
        if (!mongoLeg.success()) warnings.add("mongo projection lag: " + mongoLeg.detail());
        if (!kafkaLeg.success()) warnings.add("kafka emit failure: " + kafkaLeg.detail());

        return new StreamingTransactionResult(
                tx.transactionId(),
                sqlLeg.success() && mongoLeg.success() && kafkaLeg.success(),
                sqlLeg, mongoLeg, kafkaLeg,
                Duration.between(overallStart, Instant.now()),
                rootContext.traceId(),
                warnings
        );
    }

    @Override
    public Optional<StreamingTransactionView> replay(UUID transactionId) {
        return documents.get(MONGO_COLLECTION, transactionId.toString())
                .map(StreamingTransactionOrchestrator::toView);
    }

    @Override
    public List<StreamingTransactionView> recentForAccount(String accountNumber, int limit) {
        var rows = repository.findRecentForAccount(accountNumber);
        var out = new ArrayList<StreamingTransactionView>();
        for (var row : rows) {
            documents.get(MONGO_COLLECTION, row.transactionId().toString())
                    .map(StreamingTransactionOrchestrator::toView)
                    .ifPresent(out::add);
            if (limit > 0 && out.size() >= limit) break;
        }
        return List.copyOf(out);
    }

    @Override
    public List<StreamingTransactionView> seenSince(Instant since, int limit) {
        var docs = documents.since(MONGO_COLLECTION, "initiatedAt", since, limit);
        return docs.stream().map(StreamingTransactionOrchestrator::toView).toList();
    }

    /**
     * Persist the canonical SQL row.
     *
     * <p>{@link StreamingTransactionRepository#save} already opens a
     * transaction via Spring Data JPA's {@code SimpleJpaRepository}, so
     * the leg-level method here does not need its own
     * {@code @Transactional}. We deliberately do <b>not</b> wrap the
     * whole {@link #publish} call in a transaction either — that would
     * keep the Mongo and Kafka legs inside an open JDBC connection and
     * defeat the "best-effort downstream legs" contract this class
     * advertises.</p>
     */
    protected StreamingTransactionResult.LegOutcome persistSqlLeg(StreamingTransaction tx,
                                                                  KafkaTraceContext ctx) {
        Instant start = Instant.now();
        StreamingTransactionEntity entity = new StreamingTransactionEntity(
                tx.transactionId(),
                tx.sourceAccount().raw(),
                tx.destinationAccount().raw(),
                tx.amount().amount(),
                tx.amount().currency().name(),
                tx.type(),
                tx.memo(),
                tx.initiatedAt(),
                ctx.traceId()
        );
        repository.save(entity);
        return StreamingTransactionResult.LegOutcome.success(
                Duration.between(start, Instant.now()),
                "row inserted into txstream_transactions"
        );
    }

    private StreamingTransactionResult.LegOutcome projectToMongo(StreamingTransaction tx,
                                                                 KafkaTraceContext ctx) {
        Instant start = Instant.now();
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("_id", tx.transactionId().toString());
            doc.put("transactionId", tx.transactionId().toString());
            doc.put("sourceAccount", tx.sourceAccount().raw());
            doc.put("destinationAccount", tx.destinationAccount().raw());
            doc.put("amount", tx.amount().amount());
            doc.put("currency", tx.amount().currency().name());
            doc.put("type", tx.type().name());
            doc.put("memo", tx.memo() == null ? "" : tx.memo());
            doc.put("initiatedAt", tx.initiatedAt());
            doc.put("traceId", ctx.traceId());
            doc.put("spanId", ctx.spanId());
            documents.put(MONGO_COLLECTION, tx.transactionId().toString(), doc);
            return StreamingTransactionResult.LegOutcome.success(
                    Duration.between(start, Instant.now()),
                    "projected to " + MONGO_COLLECTION
            );
        } catch (RuntimeException e) {
            log.warn("Mongo projection failed for {}: {}", tx.transactionId(), e.toString());
            return StreamingTransactionResult.LegOutcome.failure(
                    Duration.between(start, Instant.now()),
                    "mongo error: " + e.getMessage()
            );
        }
    }

    private StreamingTransactionResult.LegOutcome emitToKafka(StreamingTransaction tx,
                                                              KafkaTraceContext ctx) {
        Instant start = Instant.now();
        KafkaTraceContext childContext = ctx.childSpan();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", tx.transactionId().toString());
        payload.put("type", tx.type().name());
        payload.put("amount", tx.amount().amount());
        payload.put("currency", tx.amount().currency().name());
        payload.put("source", tx.sourceAccount().raw());
        payload.put("destination", tx.destinationAccount().raw());
        payload.put("initiatedAt", tx.initiatedAt().toString());
        payload.put("traceId", childContext.traceId());

        try {
            publisher.publish(KafkaTopics.PAYMENT_EVENTS, tx.transactionId().toString(),
                    payload, childContext);
            spanRecorder.recordProduce(KafkaTopics.PAYMENT_EVENTS,
                    tx.transactionId().toString(), childContext);
            return StreamingTransactionResult.LegOutcome.success(
                    Duration.between(start, Instant.now()),
                    "published to " + KafkaTopics.PAYMENT_EVENTS
            );
        } catch (RuntimeException e) {
            log.warn("Kafka emit failed for {}: {}", tx.transactionId(), e.toString());
            return StreamingTransactionResult.LegOutcome.failure(
                    Duration.between(start, Instant.now()),
                    "kafka error: " + e.getMessage()
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static StreamingTransactionView toView(Map<String, Object> doc) {
        UUID id = UUID.fromString((String) doc.get("transactionId"));
        Object amt = doc.get("amount");
        BigDecimal amount;
        if (amt instanceof BigDecimal bd) amount = bd;
        else if (amt instanceof Number n)  amount = BigDecimal.valueOf(n.doubleValue());
        else if (amt == null)              amount = BigDecimal.ZERO;
        else                               amount = new BigDecimal(amt.toString());
        Object initiated = doc.get("initiatedAt");
        Instant ts = (initiated instanceof Instant i) ? i :
                Instant.parse(initiated.toString());
        return new StreamingTransactionView(
                id,
                String.valueOf(doc.get("sourceAccount")),
                String.valueOf(doc.get("destinationAccount")),
                String.valueOf(doc.get("type")),
                String.valueOf(doc.get("currency")),
                amount,
                ts,
                String.valueOf(doc.get("traceId")),
                Map.copyOf(doc)
        );
    }
}

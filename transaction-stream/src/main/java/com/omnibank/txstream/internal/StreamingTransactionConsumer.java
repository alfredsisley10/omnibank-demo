package com.omnibank.txstream.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnibank.shared.kafka.AppMapSpanRecorder;
import com.omnibank.shared.kafka.KafkaTopics;
import com.omnibank.shared.kafka.KafkaTraceContext;
import com.omnibank.shared.kafka.TracedConsumerInterceptor;
import com.omnibank.shared.nosql.DocumentStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Side-projector that listens for events on the payment-events topic
 * and writes a denormalised "consumer view" into Mongo. The point is to
 * exercise an AppMap-visible producer→consumer hop; the projection
 * itself is a demo artefact, not a real read model.
 *
 * <p>This class is invoked by both the in-memory bus (unit tests) and
 * the real Kafka container factory (integration tests). The handler
 * signature ({@code ConsumerRecord<String, String>}) is the same in
 * both cases so production wiring and tests stay symmetrical.</p>
 */
public class StreamingTransactionConsumer {

    private static final Logger log = LoggerFactory.getLogger(StreamingTransactionConsumer.class);
    public static final String CONSUMER_PROJECTION_COLLECTION = "txstream_consumer_view";

    private final DocumentStore documents;
    private final AppMapSpanRecorder spanRecorder;
    private final ObjectMapper mapper;

    public StreamingTransactionConsumer(DocumentStore documents,
                                        AppMapSpanRecorder spanRecorder,
                                        ObjectMapper mapper) {
        this.documents = documents;
        this.spanRecorder = spanRecorder;
        this.mapper = mapper;
    }

    /**
     * Handle a single record. Tests call this directly; the real
     * {@code @KafkaListener} adapter calls it via the
     * Spring listener container factory.
     */
    public void onRecord(ConsumerRecord<String, String> record) {
        Instant arrived = Instant.now();
        TracedConsumerInterceptor.wrap(record, ctx -> {
            try {
                Map<String, Object> payload = mapper.readValue(record.value(),
                        new TypeReference<Map<String, Object>>() {});
                Map<String, Object> view = new LinkedHashMap<>(payload);
                view.put("_consumedAt", Instant.now().toString());
                view.put("_consumerSpan", ctx.spanId());
                String id = String.valueOf(payload.getOrDefault("transactionId",
                        record.key() == null ? record.value() : record.key()));
                documents.put(CONSUMER_PROJECTION_COLLECTION, id, view);
                Duration latency = Duration.between(arrived, Instant.now());
                spanRecorder.recordConsume(KafkaTopics.PAYMENT_EVENTS, record.key(), ctx, latency);
                log.debug("Projected payment event {} via trace {}",
                        id, ctx.traceId());
            } catch (Exception e) {
                log.warn("Failed to project record on trace {}: {}",
                        ctx.traceId(), e.toString());
            }
            return null;
        });
    }

    public DocumentStore documents() {
        return documents;
    }
}

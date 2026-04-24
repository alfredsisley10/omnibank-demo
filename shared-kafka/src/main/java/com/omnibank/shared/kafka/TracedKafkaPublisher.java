package com.omnibank.shared.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thin wrapper around {@link KafkaTemplate} that:
 *
 * <ol>
 *   <li>Serialises payloads to JSON.</li>
 *   <li>Stamps every record with a {@link KafkaTraceContext} so AppMap
 *       can reconstruct the producer→consumer span tree.</li>
 *   <li>Echoes a copy of every send to {@link KafkaTopics#APPMAP_SPANS}
 *       — that audit firehose is what the recording-ui's "Kafka spans"
 *       panel reads to surface live traffic.</li>
 * </ol>
 *
 * <p>The class is deliberately thin so unit tests can swap the
 * {@link KafkaTemplate} for an in-process stub; the only Kafka-specific
 * logic lives in {@link #applyHeaders} which is package-visible.</p>
 */
public class TracedKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(TracedKafkaPublisher.class);

    private final KafkaTemplate<String, String> template;
    private final ObjectMapper mapper;
    private final boolean auditMirror;

    public TracedKafkaPublisher(KafkaTemplate<String, String> template,
                                ObjectMapper mapper,
                                boolean auditMirror) {
        this.template = Objects.requireNonNull(template, "template");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.auditMirror = auditMirror;
    }

    public CompletableFuture<SendResult<String, String>> publish(
            String topic, String key, Object payload, KafkaTraceContext context) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(context, "context");
        String body = serialise(payload);
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topic, null, key, body, applyHeaders(new RecordHeaders(), context));
        var future = template.send(record);
        if (auditMirror && !KafkaTopics.APPMAP_SPANS.equals(topic)) {
            mirrorToAuditTopic(topic, key, body, context);
        }
        return future;
    }

    public SendResult<String, String> publishSync(
            String topic, String key, Object payload, KafkaTraceContext context,
            long timeoutMs) {
        try {
            return publish(topic, key, payload, context).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("publish interrupted", ie);
        } catch (ExecutionException ee) {
            throw new IllegalStateException("publish failed: " + ee.getCause(), ee.getCause());
        } catch (TimeoutException te) {
            throw new IllegalStateException(
                    "publish timed out after " + timeoutMs + "ms for topic " + topic, te);
        }
    }

    public boolean auditMirrorEnabled() {
        return auditMirror;
    }

    /** Visible for testing. */
    Headers applyHeaders(Headers headers, KafkaTraceContext context) {
        for (Map.Entry<String, byte[]> e : context.toHeaders().entrySet()) {
            headers.add(new RecordHeader(e.getKey(), e.getValue()));
        }
        return headers;
    }

    private String serialise(Object payload) {
        if (payload == null) return "null";
        if (payload instanceof CharSequence cs) return cs.toString();
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise Kafka payload", e);
        }
    }

    private void mirrorToAuditTopic(String topic, String key, String body, KafkaTraceContext ctx) {
        try {
            Map<String, Object> envelope = Map.of(
                    "topic", topic,
                    "key", key == null ? "" : key,
                    "payload", body,
                    "trace", Map.of(
                            "traceId", ctx.traceId(),
                            "spanId",  ctx.spanId(),
                            "parent",  ctx.parentSpanId().orElse(""),
                            "recording", ctx.recordingId().orElse("")
                    )
            );
            ProducerRecord<String, String> mirror = new ProducerRecord<>(
                    KafkaTopics.APPMAP_SPANS, null, key, mapper.writeValueAsString(envelope),
                    applyHeaders(new RecordHeaders(), ctx));
            template.send(mirror);
        } catch (RuntimeException | JsonProcessingException e) {
            log.debug("audit mirror failed for {}: {}", topic, e.toString());
        }
    }
}

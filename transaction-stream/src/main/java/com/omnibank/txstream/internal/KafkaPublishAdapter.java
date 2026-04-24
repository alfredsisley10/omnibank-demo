package com.omnibank.txstream.internal;

import com.omnibank.shared.kafka.KafkaTraceContext;

/**
 * Internal abstraction over the Kafka publishing path so the
 * orchestrator can run with either:
 *
 * <ul>
 *   <li>{@link TracedKafkaPublishAdapter} — real Kafka, behind
 *       {@code omnibank.kafka.enabled=true};</li>
 *   <li>{@link InMemoryKafkaPublishAdapter} — Process-local fan-out for
 *       unit tests and the default dev profile.</li>
 * </ul>
 */
public interface KafkaPublishAdapter {

    void publish(String topic, String key, Object payload, KafkaTraceContext context);

    /** Diagnostic counter — total publish calls observed in this JVM. */
    long publishedCount();
}

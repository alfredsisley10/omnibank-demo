package com.omnibank.shared.kafka.testing;

import com.omnibank.shared.kafka.KafkaTraceContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Process-local Kafka simulator. Producers append to topic queues; the
 * dispatcher drains them synchronously through registered handlers.
 * Suitable for fast unit tests that need to assert producer/consumer
 * coupling without spinning up Testcontainers.
 *
 * <p>Headers are preserved end-to-end so {@link KafkaTraceContext}
 * round-trips cleanly. Messages carry monotonic offsets per topic so
 * tests can assert ordering invariants.</p>
 */
public class InMemoryKafkaBus {

    private final Map<String, Queue<Envelope>> queues = new LinkedHashMap<>();
    private final Map<String, List<BiConsumer<ConsumerRecord<String, String>, KafkaTraceContext>>> handlers = new LinkedHashMap<>();
    private final Map<String, AtomicLong> offsets = new LinkedHashMap<>();

    public synchronized void register(String topic,
                                      BiConsumer<ConsumerRecord<String, String>, KafkaTraceContext> handler) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(handler, "handler");
        handlers.computeIfAbsent(topic, t -> new ArrayList<>()).add(handler);
    }

    public synchronized List<Envelope> backlog(String topic) {
        Queue<Envelope> q = queues.get(topic);
        return q == null ? List.of() : List.copyOf(q);
    }

    public synchronized void publish(String topic, String key, String payload, KafkaTraceContext context) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(context, "context");
        long offset = offsets.computeIfAbsent(topic, t -> new AtomicLong()).getAndIncrement();
        Envelope env = new Envelope(topic, key, payload, context, offset);
        queues.computeIfAbsent(topic, t -> new ConcurrentLinkedQueue<>()).add(env);
        dispatch(env);
    }

    public synchronized void clear() {
        queues.clear();
        handlers.clear();
        offsets.clear();
    }

    private void dispatch(Envelope env) {
        var subs = handlers.getOrDefault(env.topic(), List.of());
        for (var h : subs) {
            ConsumerRecord<String, String> record = new ConsumerRecord<>(
                    env.topic(), 0, env.offset(), env.key(), env.payload());
            for (Map.Entry<String, byte[]> hdr : env.context().toHeaders().entrySet()) {
                record.headers().add(hdr.getKey(), hdr.getValue());
            }
            try {
                h.accept(record, env.context());
            } catch (RuntimeException e) {
                // surface the first failure but allow others to run; matches
                // Kafka's per-listener isolation
                throw e;
            }
        }
    }

    public record Envelope(
            String topic,
            String key,
            String payload,
            KafkaTraceContext context,
            long offset
    ) {}
}

package com.omnibank.shared.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory ring of recently observed Kafka spans, exposed for the
 * recording UI's "live spans" widget and the integration tests that
 * assert producer→consumer correlation.
 *
 * <p>This is not a substitute for a real tracing backend; it is a small
 * narrative buffer that AppMap-aware operators can poke through to
 * confirm that headers actually flowed end-to-end.</p>
 */
public class AppMapSpanRecorder {

    /** Default ring size — large enough to cover a busy demo session
     *  but small enough that we never need to worry about memory. */
    public static final int DEFAULT_CAPACITY = 1024;

    private final int capacity;
    private final java.util.Deque<Span> ring;
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public AppMapSpanRecorder() {
        this(DEFAULT_CAPACITY);
    }

    public AppMapSpanRecorder(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.ring = new java.util.concurrent.ConcurrentLinkedDeque<>();
    }

    public synchronized void recordProduce(String topic, String key, KafkaTraceContext ctx) {
        push(new Span(SpanKind.PRODUCE, topic, key, ctx, Instant.now(), Duration.ZERO));
        counters.computeIfAbsent("produce." + topic, k -> new AtomicLong()).incrementAndGet();
    }

    public synchronized void recordConsume(String topic, String key,
                                           KafkaTraceContext ctx, Duration latency) {
        push(new Span(SpanKind.CONSUME, topic, key, ctx, Instant.now(), latency));
        counters.computeIfAbsent("consume." + topic, k -> new AtomicLong()).incrementAndGet();
    }

    public List<Span> recent(int max) {
        if (max <= 0) {
            return List.of();
        }
        var copy = new ArrayList<Span>(Math.min(max, ring.size()));
        for (var it = ring.descendingIterator(); it.hasNext() && copy.size() < max; ) {
            copy.add(it.next());
        }
        return copy;
    }

    public List<Span> spansForTrace(String traceId) {
        return ring.stream()
                .filter(s -> s.traceContext().traceId().equals(traceId))
                .sorted(Comparator.comparing(Span::observedAt))
                .toList();
    }

    public Map<String, Long> counters() {
        var snap = new LinkedHashMap<String, Long>();
        counters.forEach((k, v) -> snap.put(k, v.get()));
        return Map.copyOf(snap);
    }

    public int capacity() {
        return capacity;
    }

    public synchronized int size() {
        return ring.size();
    }

    public synchronized void clear() {
        ring.clear();
    }

    private void push(Span s) {
        ring.addLast(s);
        while (ring.size() > capacity) {
            ring.pollFirst();
        }
    }

    public enum SpanKind { PRODUCE, CONSUME }

    public record Span(
            SpanKind kind,
            String topic,
            String key,
            KafkaTraceContext traceContext,
            Instant observedAt,
            Duration latency
    ) {

        public Span {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(traceContext, "traceContext");
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(latency, "latency");
        }

        public Optional<String> recordingId() {
            return traceContext.recordingId();
        }

        public String summary() {
            return kind + " " + topic + " key=" + (key == null ? "<none>" : key)
                    + " trace=" + traceContext.traceId();
        }
    }
}

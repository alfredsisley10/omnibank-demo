package com.omnibank.shared.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Lightweight trace propagation header carried on every Omnibank Kafka
 * record. The {@link #traceId} is what AppMap uses to correlate the
 * producer-side span with the consumer-side span; the {@link #spanId}
 * identifies the immediate parent so multi-hop topologies (producer →
 * stream processor → consumer) reconstruct correctly.
 *
 * <p>The header set is intentionally tiny — five UTF-8 string headers —
 * so it survives round-trips through every Kafka client, MirrorMaker,
 * and connector we have ever seen.
 */
public record KafkaTraceContext(
        String traceId,
        String spanId,
        Optional<String> parentSpanId,
        Optional<String> recordingId,
        Map<String, String> baggage
) {

    public static final String HEADER_TRACE_ID    = "x-omnibank-trace-id";
    public static final String HEADER_SPAN_ID     = "x-omnibank-span-id";
    public static final String HEADER_PARENT_SPAN = "x-omnibank-parent-span";
    public static final String HEADER_RECORDING   = "x-omnibank-recording";
    public static final String HEADER_BAGGAGE     = "x-omnibank-baggage";

    public KafkaTraceContext {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(spanId, "spanId");
        Objects.requireNonNull(parentSpanId, "parentSpanId");
        Objects.requireNonNull(recordingId, "recordingId");
        Objects.requireNonNull(baggage, "baggage");
        if (traceId.isBlank()) throw new IllegalArgumentException("traceId blank");
        if (spanId.isBlank()) throw new IllegalArgumentException("spanId blank");
        baggage = Map.copyOf(baggage);
    }

    public static KafkaTraceContext newRoot() {
        return new KafkaTraceContext(
                shortUuid(), shortUuid(), Optional.empty(), Optional.empty(), Map.of()
        );
    }

    public KafkaTraceContext childSpan() {
        return new KafkaTraceContext(
                this.traceId,
                shortUuid(),
                Optional.of(this.spanId),
                this.recordingId,
                this.baggage
        );
    }

    public KafkaTraceContext withRecording(String recordingId) {
        return new KafkaTraceContext(
                this.traceId, this.spanId, this.parentSpanId,
                Optional.ofNullable(recordingId).filter(s -> !s.isBlank()),
                this.baggage
        );
    }

    public KafkaTraceContext withBaggage(String key, String value) {
        var merged = new java.util.HashMap<>(this.baggage);
        if (value == null) {
            merged.remove(key);
        } else {
            merged.put(key, value);
        }
        return new KafkaTraceContext(traceId, spanId, parentSpanId, recordingId, merged);
    }

    /**
     * Serialise this context onto a freshly-created header map suitable
     * for {@code ProducerRecord#headers()}. The values are UTF-8 byte
     * arrays so callers can pump them into {@code RecordHeaders} without
     * re-encoding.
     */
    public Map<String, byte[]> toHeaders() {
        var out = new java.util.LinkedHashMap<String, byte[]>();
        out.put(HEADER_TRACE_ID, traceId.getBytes(StandardCharsets.UTF_8));
        out.put(HEADER_SPAN_ID,  spanId.getBytes(StandardCharsets.UTF_8));
        parentSpanId.ifPresent(p ->
                out.put(HEADER_PARENT_SPAN, p.getBytes(StandardCharsets.UTF_8)));
        recordingId.ifPresent(r ->
                out.put(HEADER_RECORDING, r.getBytes(StandardCharsets.UTF_8)));
        if (!baggage.isEmpty()) {
            out.put(HEADER_BAGGAGE, encodeBaggage(baggage).getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    /**
     * Parse a context from raw header values. Missing trace/span ids
     * cause a fresh context to be minted so callers always have something
     * to attach to a span.
     */
    public static KafkaTraceContext fromHeaders(Map<String, byte[]> headers) {
        if (headers == null || headers.isEmpty()) {
            return newRoot();
        }
        Optional<String> trace = stringHeader(headers, HEADER_TRACE_ID);
        if (trace.isEmpty()) {
            return newRoot();
        }
        String span = stringHeader(headers, HEADER_SPAN_ID).orElse(shortUuid());
        Optional<String> parent = stringHeader(headers, HEADER_PARENT_SPAN);
        Optional<String> recording = stringHeader(headers, HEADER_RECORDING);
        Map<String, String> baggage = stringHeader(headers, HEADER_BAGGAGE)
                .map(KafkaTraceContext::decodeBaggage)
                .orElse(Map.of());
        return new KafkaTraceContext(trace.get(), span, parent, recording, baggage);
    }

    private static Optional<String> stringHeader(Map<String, byte[]> headers, String key) {
        // Header keys are case-sensitive in Kafka but we accept either
        // exact or lowercase; some legacy bridges downcase them.
        for (Map.Entry<String, byte[]> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key) && e.getValue() != null && e.getValue().length > 0) {
                return Optional.of(new String(e.getValue(), StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }

    private static String encodeBaggage(Map<String, String> baggage) {
        var sb = new StringBuilder();
        for (Map.Entry<String, String> e : baggage.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            String k = e.getKey().replace(',', '_').replace('=', '_');
            String v = e.getValue() == null ? "" :
                    e.getValue().replace(',', '_').replace('=', '_');
            sb.append(k).append('=').append(v);
        }
        return sb.toString();
    }

    private static Map<String, String> decodeBaggage(String raw) {
        var out = new java.util.LinkedHashMap<String, String>();
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toLowerCase(Locale.ROOT);
    }
}

package com.omnibank.shared.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Helpers consumers call to extract trace context from incoming records
 * and stitch it into MDC + AppMap-visible thread-locals before invoking
 * business logic.
 *
 * <p>The intent is to make the producer-side span and the consumer-side
 * span share a {@code traceId} so AppMap can collapse them into a
 * single visualisation. Spring Kafka ConsumerInterceptor doesn't carry
 * an obvious extension point for this, so consumers wrap their handler
 * lambda using {@link #wrap}.</p>
 */
public final class TracedConsumerInterceptor {

    public static final String MDC_TRACE_ID    = "traceId";
    public static final String MDC_SPAN_ID     = "spanId";
    public static final String MDC_PARENT_SPAN = "parentSpan";
    public static final String MDC_RECORDING   = "recording";

    private TracedConsumerInterceptor() {}

    public static <K, V> KafkaTraceContext extract(ConsumerRecord<K, V> record) {
        Map<String, byte[]> headers = collectHeaders(record);
        return KafkaTraceContext.fromHeaders(headers);
    }

    public static <K, V, R> R wrap(ConsumerRecord<K, V> record, Function<KafkaTraceContext, R> body) {
        KafkaTraceContext context = extract(record);
        // The consumer-side span is logically a child of whatever the
        // producer recorded, so we mint a fresh span id but keep the
        // trace id constant.
        KafkaTraceContext child = context.childSpan();
        return withMdc(child, () -> body.apply(child));
    }

    public static <K, V> void wrap(ConsumerRecord<K, V> record, Consumer<KafkaTraceContext> body) {
        wrap(record, ctx -> { body.accept(ctx); return null; });
    }

    public static <R> R withMdc(KafkaTraceContext context, java.util.function.Supplier<R> body) {
        String prevTrace = MDC.get(MDC_TRACE_ID);
        String prevSpan = MDC.get(MDC_SPAN_ID);
        String prevParent = MDC.get(MDC_PARENT_SPAN);
        String prevRecord = MDC.get(MDC_RECORDING);
        try {
            MDC.put(MDC_TRACE_ID, context.traceId());
            MDC.put(MDC_SPAN_ID, context.spanId());
            context.parentSpanId().ifPresent(p -> MDC.put(MDC_PARENT_SPAN, p));
            context.recordingId().ifPresent(r -> MDC.put(MDC_RECORDING, r));
            return body.get();
        } finally {
            putOrRemove(MDC_TRACE_ID, prevTrace);
            putOrRemove(MDC_SPAN_ID, prevSpan);
            putOrRemove(MDC_PARENT_SPAN, prevParent);
            putOrRemove(MDC_RECORDING, prevRecord);
        }
    }

    public static Optional<String> currentTraceId() {
        return Optional.ofNullable(MDC.get(MDC_TRACE_ID));
    }

    private static void putOrRemove(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static <K, V> Map<String, byte[]> collectHeaders(ConsumerRecord<K, V> record) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (record == null || record.headers() == null) {
            return out;
        }
        for (Header h : record.headers()) {
            out.put(h.key(), h.value());
        }
        return out;
    }
}

package com.omnibank.shared.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TracedConsumerInterceptorTest {

    @Test
    void extract_pulls_traceId_from_record_headers() {
        KafkaTraceContext root = KafkaTraceContext.newRoot();
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        for (var h : root.toHeaders().entrySet()) {
            rec.headers().add(h.getKey(), h.getValue());
        }
        KafkaTraceContext extracted = TracedConsumerInterceptor.extract(rec);
        assertThat(extracted.traceId()).isEqualTo(root.traceId());
    }

    @Test
    void wrap_invokes_handler_with_child_span_and_populates_mdc() {
        KafkaTraceContext root = KafkaTraceContext.newRoot();
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        for (var h : root.toHeaders().entrySet()) {
            rec.headers().add(h.getKey(), h.getValue());
        }
        AtomicReference<String> seenTrace = new AtomicReference<>();
        AtomicReference<String> seenSpan = new AtomicReference<>();
        TracedConsumerInterceptor.wrap(rec, ctx -> {
            seenTrace.set(MDC.get(TracedConsumerInterceptor.MDC_TRACE_ID));
            seenSpan.set(MDC.get(TracedConsumerInterceptor.MDC_SPAN_ID));
            return null;
        });
        assertThat(seenTrace.get()).isEqualTo(root.traceId());
        assertThat(seenSpan.get()).isNotEqualTo(root.spanId()); // child span
        assertThat(MDC.get(TracedConsumerInterceptor.MDC_TRACE_ID)).isNull();
    }

    @Test
    void empty_record_yields_fresh_root() {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        KafkaTraceContext extracted = TracedConsumerInterceptor.extract(rec);
        assertThat(extracted.traceId()).isNotBlank();
        assertThat(extracted.parentSpanId()).isEmpty();
    }
}
